package com.oposicontrol.dbtest

import com.oposicontrol.dbtest.SupabaseFixture.ADMIN
import com.oposicontrol.dbtest.SupabaseFixture.MODERADOR
import com.oposicontrol.dbtest.SupabaseFixture.SUPERADMIN
import com.oposicontrol.dbtest.SupabaseFixture.USER_A
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
 * Audit loop 2 (MASTER2-AUTH-008): migracion 20260930101600. La regla D1-26 (conceder/retirar admin o superadmin
 * exige un actor SUPERADMIN) se comprueba DENTRO de la RPC, bajo el mismo cerrojo consultivo que serializa toda
 * sincronizacion de roles. Antes Ktor la leia aparte y la RPC no conocia al actor (TOCTOU).
 * Se parte del estado de MAIN: fixture legacy + 20260921071916_role_sync_guard.sql.
 */
class RoleSyncActorGuardMigrationTest {
    private val guardMain = "supabase/migrations/20260921071916_role_sync_guard.sql"
    private val actorGuard = "supabase/migrations/20260930101600_admin_sync_roles_actor_guard.sql"

    private fun <T> escenario(bloque: (Connection) -> T): T = PostgresHarness.escenario { c ->
        c.aplicar(guardMain)
        c.aplicar(actorGuard)
        bloque(c)
    }

    /** Llama a la sobrecarga de 3 argumentos como service_role. null = exito; si no, el mensaje de error. */
    private fun Connection.sync(target: String, roles: List<String>, actor: String?): String? {
        val array = if (roles.isEmpty()) "array[]::uuid[]" else roles.joinToString(",", "array[", "]") { "'$it'::uuid" }
        val actorSql = actor?.let { "'$it'::uuid" } ?: "null::uuid"
        return try {
            ejecutar("set role service_role")
            ejecutar("select public.admin_sync_roles('$target'::uuid, $array, $actorSql)")
            null
        } catch (e: SQLException) {
            e.message
        } finally {
            runCatching { ejecutar("reset role") }
        }
    }

    @Test
    fun unAdminNormalNoConcedeNiRetiraRolesAdministrativos(): Unit = escenario { c ->
        c.asignar(USER_A, ADMIN)
        c.asignar(USER_B, SUPERADMIN)
        c.asignar(USER_C, USUARIO)

        val conceder = c.sync(USER_C, listOf(SUPERADMIN), actor = USER_A)
        assertNotNull(conceder)
        assertTrue("SUPERADMIN_REQUIRED" in conceder, conceder)
        val retirar = c.sync(USER_B, listOf(USUARIO), actor = USER_A)
        assertNotNull(retirar)
        assertTrue("SUPERADMIN_REQUIRED" in retirar, retirar)
        assertEquals(listOf("usuario"), c.rolesDe(USER_C))
        assertEquals(listOf("superadmin"), c.rolesDe(USER_B))
    }

    @Test
    fun unSuperadminSiYLosRolesNoAdministrativosSiguenAbiertos(): Unit = escenario { c ->
        c.asignar(USER_A, ADMIN)
        c.asignar(USER_B, SUPERADMIN)
        c.asignar(USER_C, USUARIO)

        assertNull(c.sync(USER_C, listOf(ADMIN), actor = USER_B))
        assertEquals(listOf("admin"), c.rolesDe(USER_C))
        // Mantener un rol administrativo que ya tenia + cambiar solo moderador: cualquier admin.
        assertNull(c.sync(USER_C, listOf(ADMIN, MODERADOR), actor = USER_A))
        assertEquals(listOf("admin", "moderador"), c.rolesDe(USER_C))
        // Sin actor (token estatico) solo cambios no administrativos.
        assertTrue("SUPERADMIN_REQUIRED" in c.sync(USER_C, listOf(MODERADOR), actor = null)!!)
        assertEquals(listOf("admin", "moderador"), c.rolesDe(USER_C))
        // Las invariantes de la guarda de 2 argumentos siguen aplicando (ultimo superadmin).
        assertTrue("ROLE_SYNC_LAST_SUPERADMIN" in c.sync(USER_B, listOf(ADMIN), actor = USER_B)!!)
        assertEquals(listOf("superadmin"), c.rolesDe(USER_B))
    }

    @Test
    fun laComprobacionDelActorVeLaDegradacionConcurrenteQueLeGanoElCerrojo(): Unit = escenario { c ->
        // A es superadmin cuando Ktor hace su lectura previa; B (otro superadmin) lo degrada en paralelo.
        c.asignar(USER_A, SUPERADMIN)
        c.asignar(USER_B, SUPERADMIN)
        c.asignar(USER_C, USUARIO)
        val otra = PostgresHarness.conexionExtra(c)
        val pool = Executors.newSingleThreadExecutor()
        try {
            otra.autoCommit = false
            otra.ejecutar("set role service_role")
            otra.ejecutar("select public.admin_sync_roles('$USER_A'::uuid, array['$ADMIN'::uuid], '$USER_B'::uuid)")
            // La concesion de A espera al cerrojo; al entrar ya ve a A sin superadmin.
            val concesion = pool.submit<String?> { c.sync(USER_C, listOf(ADMIN), actor = USER_A) }
            Thread.sleep(300)
            assertFalse(concesion.isDone, "la segunda sincronizacion debe esperar al cerrojo")
            otra.commit()
            val error = concesion.get(20, TimeUnit.SECONDS)
            assertNotNull(error, "sin cerrojo + actor en la RPC, la concesion de un ex-superadmin pasaba")
            assertTrue("SUPERADMIN_REQUIRED" in error, error)
            assertEquals(listOf("usuario"), c.rolesDe(USER_C))
            assertEquals(listOf("admin"), c.rolesDe(USER_A))
        } finally {
            pool.shutdownNow()
            otra.close()
        }
    }

    @Test
    fun soloServiceRoleEjecutaYElRollbackQuitaSoloLaSobrecarga(): Unit = escenario { c ->
        fun verify() = c.filas(Slice0Db.verifyDe(actorGuard)).associate { it[0]!! to it[1]!!.toLong() }
        val v = verify()
        assertEquals(4, v.size, v.toString())
        assertTrue(v.values.all { it == 0L }, "VERIFY: $v")
        assertEquals("true", c.unico("select has_function_privilege('service_role', 'public.admin_sync_roles(uuid,uuid[],uuid)', 'execute')::text"))
        assertEquals("false", c.unico("select has_function_privilege('anon', 'public.admin_sync_roles(uuid,uuid[],uuid)', 'execute')::text"))
        assertEquals("false", c.unico("select has_function_privilege('authenticated', 'public.admin_sync_roles(uuid,uuid[],uuid)', 'execute')::text"))

        c.ejecutar(Slice0Db.rollbackDe(actorGuard))
        assertNull(c.unico("select to_regprocedure('public.admin_sync_roles(uuid,uuid[],uuid)')::text"))
        assertNotNull(c.unico("select to_regprocedure('public.admin_sync_roles(uuid,uuid[])')::text"), "la guarda de MAIN se conserva")
        assertEquals(1L, verify()["actor_overload_missing"])

        c.aplicar(actorGuard)
        c.aplicar(actorGuard) // idempotente
        assertTrue(verify().values.all { it == 0L })
    }
}
