package com.oposicontrol.dbtest.security

import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.aplicar
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.slice3.Slice3Db
import com.oposicontrol.dbtest.slice3.Slice3Db.UA
import com.oposicontrol.dbtest.slice3.Slice3Db.UB
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.postgresql.util.PSQLException

/**
 * SEC-V1-24: migracion 20261023100300. En MAIN (catalogo 2026-10-20) es_moderador_o_admin() ignora `estado_cuenta` y
 * `authenticated` tiene I/U/D en las tablas de moderacion: un moderador BLOQUEADO sigue moderando por PostgREST.
 * UA moderador ACTIVO, UC moderador BLOQUEADO, UB usuario. Forma de MAIN: `main_shape_subset.sql` (via Slice3Db) +
 * `estado_cuenta` (20260928100000, fuera del subconjunto).
 */
class ModeratorActiveAccountMigrationTest {
    private val migracion = "supabase/migrations/20261023100300_security_moderator_requires_active_account.sql"
    private val uc = "a3000000-0000-0000-0000-00000000000c"
    private val rolMod = "d2000000-0000-0000-0000-0000000000b1"

    private fun <T> escenario(conMigracion: Boolean = true, bloque: (Connection) -> T): T =
        Slice3Db.escenario(migraciones = false) { c ->
            c.ejecutar(
                """
                alter table public.perfiles_usuario add column if not exists estado_cuenta text not null default 'activa';
                set session_replication_role = replica;
                insert into auth.users (id, email) values ('$uc', 'c@s3.local');
                insert into public.roles_app (id, codigo, nombre) values ('$rolMod', 'moderador', 'Moderador');
                insert into public.roles_usuario (usuario_id, rol_id) values ('$UA', '$rolMod'), ('$uc', '$rolMod');
                insert into public.perfiles_usuario (usuario_id, nombre_mostrar, estado_cuenta) values
                  ('$UA', 'mod activo', 'activa'), ('$uc', 'mod bloqueado', 'bloqueada'), ('$UB', 'usuario', 'activa');
                insert into public.reportes_contenido (reportado_por, tipo_entidad, entidad_id, motivo) values ('$UB', 'publicacion_oposired', gen_random_uuid(), 'spam');
                insert into public.acciones_moderacion (moderador_id, tipo_accion) values ('$UA', 'advertir');
                set session_replication_role = origin;
                """.trimIndent(),
            )
            if (conMigracion) c.aplicar(migracion)
            bloque(c)
        }

    private fun Connection.comoCliente(sub: String, sql: String): Pair<String?, Int> {
        ejecutar("begin")
        return try {
            ejecutar("set local role authenticated")
            ejecutar("select set_config('request.jwt.claim.sub', '$sub', true)")
            val n = createStatement().use { it.executeUpdate(sql) }
            ejecutar("commit")
            null to n
        } catch (e: SQLException) {
            runCatching { ejecutar("rollback") }
            e.sqlState to 0
        }
    }

    private fun Connection.lecturaComo(sub: String, sql: String): String? {
        ejecutar("begin")
        return try {
            ejecutar("set local role authenticated")
            ejecutar("select set_config('request.jwt.claim.sub', '$sub', true)")
            unico(sql)
        } finally {
            ejecutar("rollback")
        }
    }

    private fun Connection.verify(): Map<String, Long> =
        filas(Slice0Db.verifyDe(migracion)).associate { it[0]!! to it[1]!!.toLong() }

    private fun Connection.funcion(): String = unico(
        "select prosrc || '|' || prosecdef || '|' || proconfig::text || '|' || coalesce(proacl::text, '') from pg_proc where oid = 'public.es_moderador_o_admin()'::regprocedure",
    )!!

    private val leerReportes = "select count(*)::text from public.reportes_contenido"
    private val borrarAcciones = "delete from public.acciones_moderacion"
    private val falsificarAccion = "insert into public.acciones_moderacion (moderador_id, tipo_accion) values ('$uc', 'bloquear_usuario')"

    @Test
    fun antesDeLaMigracionUnModeradorBloqueadoSigueModerando(): Unit = escenario(conMigracion = false) { c ->
        assertEquals("1", c.lecturaComo(uc, leerReportes), "MAIN: el bloqueado lee la cola de reportes")
        assertEquals(null to 1, c.comoCliente(uc, falsificarAccion), "MAIN: el bloqueado registra acciones")
        assertEquals(null to 2, c.comoCliente(uc, borrarAcciones), "MAIN: el bloqueado borra el historial de moderacion")
        assertEquals(1L, c.verify()["moderator_ignores_account_state"])
    }

    @Test
    fun trasLaMigracionElBloqueadoEsUnUsuarioMasYElActivoSigue(): Unit = escenario { c ->
        assertEquals("1", c.lecturaComo(UA, leerReportes), "moderador activo lee reportes")
        assertEquals("0", c.lecturaComo(uc, leerReportes), "bloqueado: no ve la cola")
        assertEquals("0", c.lecturaComo(UB, leerReportes), "usuario: no ve la cola")
        assertEquals("42501", c.comoCliente(uc, falsificarAccion).first, "bloqueado: WITH CHECK rechaza")
        assertEquals(null to 0, c.comoCliente(uc, borrarAcciones), "bloqueado: no ve filas que borrar")
        assertEquals("1", c.unico("select count(*)::text from public.acciones_moderacion"), "historial intacto")
        assertEquals(null to 1, c.comoCliente(UA, falsificarAccion.replace(uc, UA)), "moderador activo sigue registrando")
        assertTrue(c.verify().values.all { it == 0L }, c.verify().toString())
    }

    @Test
    fun rollbackExactoEIdempotente(): Unit = escenario(conMigracion = false) { c ->
        val main = c.funcion()
        c.aplicar(migracion)
        val aplicada = c.funcion()
        assertNotEquals(main, aplicada)
        assertEquals(aplicada.substringAfter('|'), main.substringAfter('|'), "SECURITY DEFINER, search_path y ACL iguales")
        c.aplicar(migracion)
        assertEquals(aplicada, c.funcion(), "re-ejecucion no-op")
        c.ejecutar(Slice0Db.rollbackDe(migracion))
        assertEquals(
            main.substringBefore('|').replace(Regex("\\s+"), " ").trim(),
            c.funcion().substringBefore('|').replace(Regex("\\s+"), " ").trim(),
            "cuerpo de MAIN restaurado",
        )
        assertEquals(main.substringAfter('|'), c.funcion().substringAfter('|'))
        c.aplicar(migracion)
        assertTrue(c.verify().values.all { it == 0L })
    }

    @Test
    fun preflightFallaCerradoSiElCuerpoNoEsElDeMain(): Unit = escenario(conMigracion = false) { c ->
        c.ejecutar(
            "create or replace function public.es_moderador_o_admin() returns boolean language sql stable security definer " +
                "set search_path to '' as \$f\$ select true \$f\$",
        )
        val antes = c.funcion()
        val e = runCatching { c.ejecutar(Slice0Db.leer(migracion)) }.exceptionOrNull() as PSQLException?
        runCatching { c.ejecutar("rollback") }
        c.autoCommit = true
        assertEquals("P0001", e?.sqlState)
        assertEquals("MODERATOR_ACTIVE_PREFLIGHT", e?.serverErrorMessage?.message)
        assertEquals(antes, c.funcion(), "nada cambiado")
    }
}
