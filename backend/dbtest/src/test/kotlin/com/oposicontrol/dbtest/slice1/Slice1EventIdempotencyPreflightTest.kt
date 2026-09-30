package com.oposicontrol.dbtest.slice1

import com.oposicontrol.dbtest.PostgresHarness
import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.aplicar
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Audit loop 2, area DB (MASTER2-DB-001): el PREFLIGHT de 20260930100800. Ninguna migracion del repo crea
 * `eventos_app` ni `record_event` (vienen del baseline de MAIN). Si faltan, o si MAIN tiene el indice unico GLOBAL
 * de la clave con otro nombre (el `drop index if exists` seria un no-op silencioso y la clave seguiria siendo
 * global), la migracion aborta ENTERA con un mensaje claro y no deja nada aplicado. El VERIFY
 * `global_index_still_present` detecta ahora cualquier indice unico de una sola columna sobre idempotency_key.
 */
class Slice1EventIdempotencyPreflightTest {
    private val migracion = "supabase/migrations/20260930100800_slice1_event_idempotency_per_user.sql"
    private val candidate009 = "supabase/canonical/product_metrics_candidate_v1/009_record_event_idempotency_rpc.sql"

    private fun tabla(indiceGlobal: String?): String = """
        create table public.eventos_app (
          id uuid not null default gen_random_uuid() primary key,
          usuario_id uuid references auth.users(id) on delete set null,
          sesion_id uuid, nombre_evento text not null, pantalla text, entidad_tipo text, entidad_id uuid,
          plataforma text, version_app text, idioma text, duracion_ms integer,
          propiedades jsonb not null default '{}'::jsonb, creado_en timestamptz not null default now(),
          event_version integer not null default 1, occurred_en timestamptz not null default now(),
          received_en timestamptz not null default now(), especialidad_id uuid, intento_id uuid, pregunta_id uuid,
          idempotency_key text not null constraint eventos_app_idempotency_key_non_blank check (length(btrim(idempotency_key)) > 0)
        );
    """.trimIndent() + (indiceGlobal?.let { "\ncreate unique index $it on public.eventos_app using btree (idempotency_key);" } ?: "")

    /** Aplica la migracion esperando que el preflight aborte; devuelve el mensaje y deja la conexion limpia. */
    private fun Connection.fallaAlAplicar(): String = try {
        aplicar(migracion)
        fail("el preflight tenia que abortar la migracion")
    } catch (e: SQLException) {
        assertEquals("P0001", e.sqlState, e.message)
        ejecutar("rollback")
        e.message.orEmpty()
    }

    private fun Connection.indicePorUsuario(): String? =
        unico("select to_regclass('public.ux_eventos_app_idempotency_key_usuario')::text")

    @Test
    fun sinEventosAppAbortaConBaselineMissing(): Unit = PostgresHarness.escenario { c ->
        val msg = c.fallaAlAplicar()
        assertTrue(msg.contains("PREFLIGHT_BASELINE_MISSING") && msg.contains("public.eventos_app"), msg)
        assertNull(c.unico("select to_regclass('public.eventos_app')::text"))
    }

    @Test
    fun sinRecordEventAbortaSinTocarNada(): Unit = PostgresHarness.escenario { c ->
        c.ejecutar(tabla("ux_eventos_app_idempotency_key"))
        val msg = c.fallaAlAplicar()
        assertTrue(msg.contains("PREFLIGHT_BASELINE_MISSING") && msg.contains("record_event"), msg)
        assertNull(c.indicePorUsuario(), "nada aplicado: el indice por usuario no existe")
        assertEquals("ux_eventos_app_idempotency_key", c.unico("select to_regclass('public.ux_eventos_app_idempotency_key')::text"))
        assertNull(c.unico("select to_regprocedure('public.record_event(uuid, text, text, text, uuid, jsonb, integer, timestamptz, uuid, uuid, uuid, text)')::text"))
    }

    @Test
    fun indiceGlobalConOtroNombreEsDeriva(): Unit = PostgresHarness.escenario { c ->
        c.ejecutar(tabla("eventos_app_idempotency_key_key"))
        c.aplicar(candidate009)
        val msg = c.fallaAlAplicar()
        assertTrue(msg.contains("PREFLIGHT_MAIN_DRIFT") && msg.contains("eventos_app_idempotency_key_key"), msg)
        assertNull(c.indicePorUsuario(), "nada aplicado")
    }

    @Test
    fun sinNingunIndiceDeClaveEsDeriva(): Unit = PostgresHarness.escenario { c ->
        c.ejecutar(tabla(null))
        c.aplicar(candidate009)
        val msg = c.fallaAlAplicar()
        assertTrue(msg.contains("PREFLIGHT_MAIN_DRIFT") && msg.contains("neither"), msg)
        assertNull(c.indicePorUsuario())
    }

    @Test
    fun conElBaselineDeMainPasaYEsReejecutableYTrasRollback(): Unit = PostgresHarness.escenario { c ->
        c.ejecutar(tabla("ux_eventos_app_idempotency_key"))
        c.aplicar(candidate009)
        c.aplicar(migracion)
        c.aplicar(migracion) // re-ejecucion: el indice por usuario ya existe y el global ya no
        c.ejecutar(Slice0Db.rollbackDe(migracion))
        c.aplicar(migracion) // tras el rollback vuelve el indice global con el nombre del baseline
        val v = c.filas(Slice0Db.verifyDe(migracion)).associate { it[0]!! to it[1]!!.toLong() }
        assertTrue(v.values.all { it == 0L }, v.toString())
    }

    @Test
    fun verifyGlobalIndexCuentaCualquierIndiceUnicoDeLaClave(): Unit = PostgresHarness.escenario { c ->
        c.ejecutar(tabla("ux_eventos_app_idempotency_key"))
        c.aplicar(candidate009)
        c.aplicar(migracion)
        // Deriva posterior (o no detectada): un indice unico global con OTRO nombre. El VERIFY por nombre no lo veia.
        c.ejecutar("create unique index eventos_app_idempotency_key_key on public.eventos_app using btree (idempotency_key)")
        val v = c.filas(Slice0Db.verifyDe(migracion)).associate { it[0]!! to it[1]!!.toLong() }
        assertEquals(1L, v["global_index_still_present"], v.toString())
    }
}
