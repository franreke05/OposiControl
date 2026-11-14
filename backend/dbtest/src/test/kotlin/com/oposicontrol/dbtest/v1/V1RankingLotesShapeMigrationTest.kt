package com.oposicontrol.dbtest.v1

import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `20261114500000_v1_ranking_lotes_shape` (ventana 1.3, hallazgo S3): `public.lotes_actualizacion_ranking` en la captura de MAIN (2026-10-14) tiene la forma
 * PRE-V1 (sin tipo / filas / detalle / error, estado 'pendiente' con CHECK de cuatro valores, iniciado_en sin default) y los trabajos V1 de dificultad y ranking
 * fallan en ella. La migracion la alinea con guardas; en la forma V1 (overlay del gate y de este arnes) no hace nada. Aqui la forma de MAIN se reproduce
 * recreando la tabla con el DDL literal de la captura.
 */
class V1RankingLotesShapeMigrationTest {
    private val migration = "20261114500000_v1_ranking_lotes_shape"
    private val path = "supabase/migrations/$migration.sql"

    /** DDL literal de baseline_main.sql (2026-10-14) para la tabla, sin la FK a public.ligas (no la usa ningun cuerpo V1). */
    private val mainShape = """
        drop table public.lotes_actualizacion_ranking cascade;
        create table public.lotes_actualizacion_ranking (id uuid not null default gen_random_uuid(), liga_id uuid, estado text not null default 'pendiente'::text,
          iniciado_en timestamp with time zone, finalizado_en timestamp with time zone, total_usuarios_procesados integer not null default 0, mensaje_error text,
          creado_en timestamp with time zone not null default now());
        alter table public.lotes_actualizacion_ranking add constraint lotes_actualizacion_ranking_pkey primary key (id);
        alter table public.lotes_actualizacion_ranking add constraint lotes_actualizacion_ranking_estado_check
          check ((estado = any (array['pendiente'::text, 'ejecutando'::text, 'completado'::text, 'fallido'::text])));
        alter table public.lotes_actualizacion_ranking add constraint lotes_actualizacion_ranking_total_usuarios_procesados_check check ((total_usuarios_procesados >= 0));
        alter table public.lotes_actualizacion_ranking enable row level security;
        revoke all on table public.lotes_actualizacion_ranking from public, anon, authenticated;
        insert into public.lotes_actualizacion_ranking (estado, iniciado_en, total_usuarios_procesados) values ('completado', now() - interval '30 days', 12);
    """.trimIndent()

    private fun Connection.shape(): String =
        unico(
            "select string_agg(column_name || '=' || coalesce(column_default, '-'), ',' order by column_name) || '|' || " +
                "coalesce((select pg_get_constraintdef(oid) from pg_constraint where conname = 'lotes_actualizacion_ranking_estado_check'), 'no-check') " +
                "from information_schema.columns where table_schema = 'public' and table_name = 'lotes_actualizacion_ranking'",
        )!!

    private fun Connection.job(name: String): String = unico("select public.$name(true) ->> 'estado'")!!

    private fun Connection.verifyZero() {
        val rows = filas(Slice0Db.verifyDe(path))
        assertTrue(rows.size >= 10, "VERIFY rows: $rows")
        assertEquals(emptyList(), rows.filter { it[1] != "0" }, "every VERIFY counter is 0")
    }

    @Test
    fun onTheV1ShapeItIsANoOpAndTheJobsKeepWorking() = V1Db.escenario { c ->
        val before = V1Db.escenario(sin = setOf(migration)) { it.shape() }
        assertEquals(before, c.shape(), "the V1 shape is untouched")
        c.verifyZero()
        assertEquals("OK", c.job("app_job_dificultad"))
        assertEquals("OK", c.job("app_job_ranking"))
        c.ejecutar(Slice0Db.rollbackDe(path))
        assertEquals(before, c.shape(), "the ROLLBACK is a no-op on the V1 shape")
    }

    @Test
    fun onTheMainShapeTheJobsFailWithoutItWorkWithItAndTheRollbackRestoresTheShape() = V1Db.escenario(sin = setOf(migration)) { c ->
        c.ejecutar(mainShape)
        val main = c.shape()
        // negative control: this is what MAIN does today on every tick
        try {
            c.job("app_job_ranking")
            fail("negative control: on the MAIN shape the ranking job must fail")
        } catch (e: SQLException) {
            assertTrue(e.message.orEmpty().contains("tipo"), "fails on the missing tipo column: ${e.message}")
        }
        c.ejecutar(Slice0Db.leer(path))
        c.verifyZero()
        assertEquals("OK", c.job("app_job_dificultad"))
        assertEquals("OK", c.job("app_job_ranking"))
        assertEquals("RECIENTE", c.unico("select public.app_job_ranking(false) ->> 'estado'"), "iniciado_en default: the cooldown sees the batch just written")
        assertEquals(
            "2|1|1",
            c.unico(
                "select count(*) filter (where tipo in ('dificultad', 'ranking') and estado = 'ok' and iniciado_en is not null)::text || '|' || " +
                    "count(*) filter (where tipo is null and estado = 'completado' and total_usuarios_procesados = 12)::text || '|' || " +
                    "count(*) filter (where tipo is null)::text from public.lotes_actualizacion_ranking",
            ),
            "two V1 batches written; the legacy row is untouched",
        )
        c.ejecutar(Slice0Db.rollbackDe(path))
        assertEquals(main, c.shape(), "the ROLLBACK restores the MAIN shape (columns, defaults, CHECK)")
        assertEquals("1", c.unico("select count(*)::text from public.lotes_actualizacion_ranking"), "only the legacy row stays")
        // re-apply after the rollback: same end state
        c.ejecutar(Slice0Db.leer(path))
        c.verifyZero()
        assertEquals("OK", c.job("app_job_ranking"))
    }
}
