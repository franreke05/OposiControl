package com.oposicontrol.dbtest

import com.oposicontrol.dbtest.SupabaseFixture.ADMIN
import com.oposicontrol.dbtest.SupabaseFixture.MODERADOR
import com.oposicontrol.dbtest.SupabaseFixture.SUPERADMIN
import com.oposicontrol.dbtest.SupabaseFixture.USER_B
import com.oposicontrol.dbtest.SupabaseFixture.USER_C
import com.oposicontrol.dbtest.SupabaseFixture.USUARIO
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.aplicar
import com.oposicontrol.dbtest.slice0.filas
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MASTER6-IDEM-003: migracion 20260930101900. La sobrecarga de 4 argumentos de `admin_sync_roles_audited` compara el
 * conjunto esperado (`p_expected_role_ids`) con los roles leidos BAJO el cerrojo consultivo de la sincronizacion y
 * levanta ROLE_SYNC_VERSION_CONFLICT antes de escribir. Antes Ktor comparaba con una lectura previa al cerrojo: una
 * concesion confirmada en esa ventana se perdia con el reemplazo completo rancio.
 */
class RoleSyncAuditedExpectedMigrationTest {
    private val guardMain = "supabase/migrations/20260921071916_role_sync_guard.sql"
    private val actorGuard = "supabase/migrations/20260930101600_admin_sync_roles_actor_guard.sql"
    private val audited = "supabase/migrations/20260930101850_admin_sync_roles_audited.sql"
    private val expected = "supabase/migrations/20260930101900_admin_sync_roles_audited_expected.sql"

    private fun <T> escenario(bloque: (Connection) -> T): T = PostgresHarness.escenario { c ->
        c.aplicar(guardMain)
        c.aplicar(actorGuard)
        c.aplicar(audited)
        c.aplicar(expected)
        bloque(c)
    }

    private fun lit(ids: List<String?>?): String = when {
        ids == null -> "null::uuid[]"
        ids.isEmpty() -> "array[]::uuid[]"
        else -> ids.joinToString(",", "array[", "]::uuid[]") { it?.let { v -> "'$v'" } ?: "null" }
    }

    /** Como service_role, sobrecarga de 4 argumentos. Devuelve `antes|despues` o el error. */
    private fun Connection.sync(target: String, roles: List<String>, actor: String, esperados: List<String?>?): Result<String> = try {
        ejecutar("set role service_role")
        Result.success(
            unico(
                """
                with r as (select public.admin_sync_roles_audited('$target'::uuid, ${lit(roles)}, '$actor'::uuid, ${lit(esperados)}) as j)
                select coalesce((select string_agg(e, ',' order by n) from jsonb_array_elements_text(j -> 'antes') with ordinality t(e, n)), '')
                       || '|' ||
                       coalesce((select string_agg(e, ',' order by n) from jsonb_array_elements_text(j -> 'despues') with ordinality t(e, n)), '')
                from r
                """.trimIndent(),
            )!!,
        )
    } catch (e: SQLException) {
        Result.failure(e)
    } finally {
        runCatching { ejecutar("reset role") }
    }

    private fun Result<String>.conflicto(): Boolean =
        exceptionOrNull()?.message?.contains("ROLE_SYNC_VERSION_CONFLICT") == true

    @Test
    fun unConjuntoEsperadoQueCoincideSincronizaYDevuelveAntesYDespues(): Unit = escenario { c ->
        c.asignar(USER_B, SUPERADMIN)
        c.asignar(USER_C, USUARIO)
        c.asignar(USER_C, MODERADOR)
        // Orden y duplicados del esperado no importan: se compara el conjunto ordenado y distinto.
        val r = c.sync(USER_C, listOf(USUARIO), USER_B, esperados = listOf(USUARIO, MODERADOR, USUARIO)).getOrThrow()
        assertEquals(listOf(MODERADOR, USUARIO).sorted().joinToString(",") + "|" + USUARIO, r)
        assertEquals(listOf("usuario"), c.rolesDe(USER_C))
    }

    @Test
    fun unConjuntoEsperadoRancioOErroneoLevantaConflictoYNoEscribe(): Unit = escenario { c ->
        c.asignar(USER_B, SUPERADMIN)
        c.asignar(USER_C, USUARIO)
        c.asignar(USER_C, MODERADOR)
        assertTrue(c.sync(USER_C, listOf(USUARIO), USER_B, esperados = listOf(USUARIO)).conflicto(), "falta un rol (foto rancia)")
        assertTrue(c.sync(USER_C, listOf(USUARIO), USER_B, esperados = listOf(USUARIO, MODERADOR, ADMIN)).conflicto(), "sobra un rol")
        assertTrue(c.sync(USER_C, listOf(USUARIO), USER_B, esperados = listOf(ADMIN, SUPERADMIN)).conflicto(), "roles equivocados")
        assertTrue(c.sync(USER_C, listOf(USUARIO), USER_B, esperados = emptyList()).conflicto(), "vacio frente a roles reales")
        assertTrue(c.sync(USER_C, listOf(USUARIO), USER_B, esperados = listOf(USUARIO, MODERADOR, null)).conflicto(), "un null nunca coincide")
        assertEquals(listOf("moderador", "usuario"), c.rolesDe(USER_C), "ninguna escritura")
    }

    @Test
    fun unEsperadoNuloSeComportaComoLaSobrecargaDeTresArgumentos(): Unit = escenario { c ->
        c.asignar(USER_B, SUPERADMIN)
        c.asignar(USER_C, USUARIO)
        val r = c.sync(USER_C, listOf(MODERADOR), USER_B, esperados = null).getOrThrow()
        assertEquals("$USUARIO|$MODERADOR", r)
        assertEquals(listOf("moderador"), c.rolesDe(USER_C))
        // Los errores de la guarda se propagan igual.
        val sinSuper = c.sync(USER_C, listOf(SUPERADMIN), USER_C, esperados = null).exceptionOrNull()?.message
        assertNotNull(sinSuper)
        assertTrue("SUPERADMIN_REQUIRED" in sinSuper, sinSuper)
        // La sobrecarga de 3 argumentos se sigue resolviendo sin ambiguedad (sin defaults en la de 4).
        c.ejecutar("set role service_role")
        try {
            assertNotNull(c.unico("select public.admin_sync_roles_audited('$USER_C'::uuid, array['$USUARIO'::uuid], '$USER_B'::uuid)::text"))
        } finally {
            c.ejecutar("reset role")
        }
        assertEquals(listOf("usuario"), c.rolesDe(USER_C))
    }

    /**
     * Gate PG17 (LOW): un destino inexistente es ROLE_SYNC_INVALID_USER (404) con CUALQUIER conjunto esperado, igual que
     * con NULL o [] (ruta de 3 argumentos). Antes un esperado no vacio levantaba VERSION_CONFLICT (409).
     */
    @Test
    fun unDestinoInexistenteEsInvalidUserConCualquierConjuntoEsperado(): Unit = escenario { c ->
        c.asignar(USER_B, SUPERADMIN)
        val fantasma = "f0f0f0f0-0000-4000-8000-0000000000ee"
        for (esperados in listOf(listOf(USUARIO), listOf(USUARIO, MODERADOR), listOf<String?>(null), emptyList(), null)) {
            val error = c.sync(fantasma, listOf(USUARIO), USER_B, esperados).exceptionOrNull()?.message
            assertNotNull(error, "esperados=$esperados")
            assertTrue("ROLE_SYNC_INVALID_USER" in error, "esperados=$esperados: $error")
            assertFalse("ROLE_SYNC_VERSION_CONFLICT" in error, "esperados=$esperados: $error")
        }
        assertEquals(0L, c.unico("select count(*) from public.roles_usuario where usuario_id = '$fantasma'")!!.toLong())
    }

    /**
     * Gate PG17 (LOW, round 3): la sobrecarga de 4 argumentos es SECURITY DEFINER y NO lee auth.users. La existencia del
     * destino se delega en la guarda de `admin_sync_roles(uuid, uuid[])`: mismo error, sqlstate, detail y hint que esa
     * guarda, y siempre antes de VERSION_CONFLICT. Un conflicto sobre un destino real no deja ninguna escritura (las
     * filas de roles_usuario conservan su xmin: la reescritura de la delegacion se deshace con el conflicto).
     */
    @Test
    fun laExistenciaSeDelegaEnLaGuardaSinLeerAuthUsersYUnConflictoNoEscribe(): Unit = escenario { c ->
        assertEquals(
            "false",
            c.unico(
                "select (pg_get_functiondef('public.admin_sync_roles_audited(uuid,uuid[],uuid,uuid[])'::regprocedure) ~* 'auth\\.users')::text",
            ),
        )
        c.asignar(USER_B, SUPERADMIN)
        c.asignar(USER_C, USUARIO)
        c.asignar(USER_C, MODERADOR)
        val fantasma = "f0f0f0f0-0000-4000-8000-0000000000ef"
        val guarda = runCatching { c.unico("select public.admin_sync_roles('$fantasma'::uuid, array['$USUARIO']::uuid[])::text") }
            .exceptionOrNull() as org.postgresql.util.PSQLException
        for (esperados in listOf(listOf(USUARIO), listOf<String?>(null))) {
            val e = c.sync(fantasma, listOf(USUARIO), USER_B, esperados).exceptionOrNull() as org.postgresql.util.PSQLException
            assertEquals(guarda.sqlState, e.sqlState)
            assertEquals("P0001", e.sqlState)
            assertEquals("ROLE_SYNC_INVALID_USER", e.serverErrorMessage?.message)
            assertEquals(guarda.serverErrorMessage?.message, e.serverErrorMessage?.message)
            assertEquals(guarda.serverErrorMessage?.detail, e.serverErrorMessage?.detail)
            assertEquals(guarda.serverErrorMessage?.hint, e.serverErrorMessage?.hint)
        }
        val filas = "select string_agg(rol_id::text || ':' || xmin::text, ',' order by rol_id) from public.roles_usuario where usuario_id = '$USER_C'"
        val antes = c.unico(filas)
        assertTrue(c.sync(USER_C, listOf(USUARIO), USER_B, esperados = listOf(USUARIO)).conflicto())
        assertEquals(antes, c.unico(filas), "el conflicto no deja ninguna escritura")
    }

    @Test
    fun elReintentoConElConjuntoActualTrasUnConflictoSincroniza(): Unit = escenario { c ->
        c.asignar(USER_B, SUPERADMIN)
        c.asignar(USER_C, USUARIO)
        c.asignar(USER_C, MODERADOR)
        assertTrue(c.sync(USER_C, listOf(USUARIO), USER_B, esperados = listOf(USUARIO)).conflicto())
        // El editor recarga (ve [usuario, moderador]) y reintenta: entra.
        val r = c.sync(USER_C, listOf(USUARIO), USER_B, esperados = listOf(MODERADOR, USUARIO)).getOrThrow()
        assertTrue(r.endsWith("|$USUARIO"), r)
        // Repetir la misma peticion con la foto ya superada vuelve a ser conflicto (no un no-op silencioso).
        assertTrue(c.sync(USER_C, listOf(USUARIO), USER_B, esperados = listOf(MODERADOR, USUARIO)).conflicto())
        assertEquals(listOf("usuario"), c.rolesDe(USER_C))
    }

    /**
     * La carrera real: la conexion 1 tiene el cerrojo y concede `moderador` (confirma despues). La conexion 2 llega con
     * la foto vieja [usuario] (la que Ktor comprobo fuera del cerrojo) y espera al cerrojo. Al entrar debe levantar
     * conflicto y NO retirar el `moderador` recien concedido.
     */
    @Test
    fun unaSincronizacionConcurrenteConLaFotoViejaNoBorraLaConcesionHecha(): Unit = escenario { c ->
        c.asignar(USER_B, SUPERADMIN)
        c.asignar(USER_C, USUARIO)
        val otra = PostgresHarness.conexionExtra(c)
        val pool = Executors.newSingleThreadExecutor()
        try {
            otra.autoCommit = false
            otra.ejecutar("set role service_role")
            otra.ejecutar(
                "select public.admin_sync_roles_audited('$USER_C'::uuid, array['$USUARIO','$MODERADOR']::uuid[], '$USER_B'::uuid, array['$USUARIO']::uuid[])",
            )
            val segunda = pool.submit<Result<String>> { c.sync(USER_C, listOf(USUARIO), USER_B, esperados = listOf(USUARIO)) }
            Thread.sleep(300)
            assertFalse(segunda.isDone, "la segunda espera al cerrojo")
            otra.commit()
            val r = segunda.get(20, TimeUnit.SECONDS)
            assertTrue(r.conflicto(), "la foto vieja choca bajo el cerrojo: ${r.exceptionOrNull()?.message ?: r.getOrNull()}")
            assertEquals(listOf("moderador", "usuario"), c.rolesDe(USER_C), "la concesion concurrente se conserva")
        } finally {
            pool.shutdownNow()
            otra.close()
        }
    }

    @Test
    fun soloServiceRoleEjecutaYElRollbackQuitaSoloLaSobrecargaNueva(): Unit = escenario { c ->
        fun verify() = c.filas(Slice0Db.verifyDe(expected)).associate { it[0]!! to it[1]!!.toLong() }
        val v = verify()
        // Round 3: + guard_execute_missing y expected_overload_reads_auth_users (la delegacion de existencia).
        assertEquals(7, v.size, v.toString())
        assertTrue(v.values.all { it == 0L }, "VERIFY: $v")
        val fn = "public.admin_sync_roles_audited(uuid,uuid[],uuid,uuid[])"
        assertEquals("true", c.unico("select has_function_privilege('service_role', '$fn', 'execute')::text"))
        assertEquals("false", c.unico("select has_function_privilege('anon', '$fn', 'execute')::text"))
        assertEquals("false", c.unico("select has_function_privilege('authenticated', '$fn', 'execute')::text"))
        assertEquals("true", c.unico("select prosecdef::text from pg_proc where oid = '$fn'::regprocedure"))
        assertEquals("search_path=\"\";row_security=off", c.unico("select array_to_string(proconfig, ';') from pg_proc where oid = '$fn'::regprocedure"))

        c.ejecutar(Slice0Db.rollbackDe(expected))
        assertNull(c.unico("select to_regprocedure('$fn')::text"))
        assertNotNull(c.unico("select to_regprocedure('public.admin_sync_roles_audited(uuid,uuid[],uuid)')::text"), "la de 3 argumentos se conserva")
        assertEquals(1L, verify()["expected_overload_missing"])

        c.aplicar(expected)
        c.aplicar(expected) // idempotente
        assertTrue(verify().values.all { it == 0L })
    }
}
