package com.oposicontrol.dbtest.security

import com.oposicontrol.dbtest.PostgresHarness
import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.aplicar
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.postgresql.util.PSQLException

/**
 * Advisor 0011 (MAIN, 2026-10-20): migracion 20261023100400. Las dos funciones de trigger de `noticia` reciben
 * search_path = '' sin cambiar su comportamiento. Forma de MAIN: las funciones de 20260923103000 / 20260923120000 tal cual,
 * sobre una tabla `noticia` minima.
 */
class NoticiaTriggerSearchPathMigrationTest {
    private val migracion = "supabase/migrations/20261023100400_security_noticia_trigger_search_path.sql"

    private val formaMain = """
        create table public.noticia (id serial primary key, titulo text, estado text not null default 'draft',
          published_at timestamptz, actualizado_en timestamptz not null default now() - interval '1 day');
        create or replace function public.noticia_touch_actualizado_en() returns trigger language plpgsql as ${'$'}${'$'}
        begin
          new.actualizado_en := now();
          return new;
        end;
        ${'$'}${'$'};
        create trigger noticia_actualizado_en before update on public.noticia for each row execute function public.noticia_touch_actualizado_en();
        create or replace function public.noticia_sellar_published_at() returns trigger language plpgsql as ${'$'}${'$'}
        begin
          if new.estado = 'published' and new.published_at is null then
            new.published_at := now();
          end if;
          return new;
        end;
        ${'$'}${'$'};
        create trigger noticia_published_at before insert or update on public.noticia for each row execute function public.noticia_sellar_published_at();
    """.trimIndent()

    private fun <T> escenario(conMigracion: Boolean = true, bloque: (Connection) -> T): T = PostgresHarness.escenario { c ->
        c.ejecutar(formaMain)
        if (conMigracion) c.aplicar(migracion)
        bloque(c)
    }

    private fun Connection.config(): String = unico(
        "select string_agg(proname || '=' || coalesce(proconfig::text, '-'), ',' order by proname) from pg_proc " +
            "where proname in ('noticia_touch_actualizado_en', 'noticia_sellar_published_at')",
    )!!

    private fun Connection.verify(): Map<String, Long> =
        filas(Slice0Db.verifyDe(migracion)).associate { it[0]!! to it[1]!!.toLong() }

    /** Los dos triggers siguen sellando igual, tambien con un search_path hostil en la sesion. */
    private fun Connection.comportamiento() {
        ejecutar("create schema if not exists hostil; set search_path = hostil, public")
        ejecutar("insert into public.noticia (titulo, estado) values ('a', 'draft'), ('b', 'published')")
        assertEquals("a:f|b:t", unico("select string_agg(titulo || ':' || (published_at is not null)::text, '|' order by titulo) from public.noticia").toString().replace("false", "f").replace("true", "t"))
        ejecutar("update public.noticia set estado = 'published' where titulo = 'a'")
        assertEquals("t", unico("select (published_at is not null and actualizado_en > now() - interval '1 minute')::text from public.noticia where titulo = 'a'")!!.take(1))
        ejecutar("reset search_path")
    }

    @Test
    fun antesDeLaMigracionElSearchPathEsMutable(): Unit = escenario(conMigracion = false) { c ->
        assertEquals(2L, c.verify()["noticia_trigger_search_path_mutable"], "las dos funciones")
        c.comportamiento()
    }

    @Test
    fun trasLaMigracionSearchPathVacioYMismoComportamiento(): Unit = escenario { c ->
        assertEquals("noticia_sellar_published_at={\"search_path=\\\"\\\"\"},noticia_touch_actualizado_en={\"search_path=\\\"\\\"\"}", c.config())
        assertTrue(c.verify().values.all { it == 0L }, c.verify().toString())
        c.comportamiento()
    }

    @Test
    fun rollbackExactoEIdempotente(): Unit = escenario(conMigracion = false) { c ->
        val main = c.config()
        c.aplicar(migracion)
        c.aplicar(migracion)
        c.ejecutar(Slice0Db.rollbackDe(migracion))
        assertEquals(main, c.config())
        c.aplicar(migracion)
        assertTrue(c.verify().values.all { it == 0L })
    }

    @Test
    fun preflightFallaCerradoSiLaFuncionNoEsLaDeMain(): Unit = escenario(conMigracion = false) { c ->
        c.ejecutar("alter function public.noticia_touch_actualizado_en() security definer")
        val antes = c.config()
        val e = runCatching { c.ejecutar(Slice0Db.leer(migracion)) }.exceptionOrNull() as PSQLException?
        runCatching { c.ejecutar("rollback") }
        c.autoCommit = true
        assertEquals("P0001", e?.sqlState)
        assertEquals("NOTICIA_SEARCH_PATH_PREFLIGHT", e?.serverErrorMessage?.message)
        assertEquals(antes, c.config())
    }
}
