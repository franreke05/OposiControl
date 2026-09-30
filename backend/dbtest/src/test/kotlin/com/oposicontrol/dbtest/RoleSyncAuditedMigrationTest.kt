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
 * Audit loop 4 (MASTER4-OBS-001): migracion 20260930101850. `admin_sync_roles_audited` devuelve los roles del usuario
 * leidos BAJO el cerrojo consultivo de la sincronizacion (antes y despues): Ktor auditaba un `antes` leido fuera del
 * cerrojo, asi que dos sincronizaciones concurrentes registraban el mismo `antes` rancio.
 * Se parte del estado de MAIN (fixture legacy + 20260921071916) + 20260930101600.
 */
class RoleSyncAuditedMigrationTest {
    private val guardMain = "supabase/migrations/20260921071916_role_sync_guard.sql"
    private val actorGuard = "supabase/migrations/20260930101600_admin_sync_roles_actor_guard.sql"
    private val audited = "supabase/migrations/20260930101850_admin_sync_roles_audited.sql"

    private fun <T> escenario(bloque: (Connection) -> T): T = PostgresHarness.escenario { c ->
        c.aplicar(guardMain)
        c.aplicar(actorGuard)
        c.aplicar(audited)
        bloque(c)
    }

    private data class Sets(val antes: List<String>, val despues: List<String>)

    /** Como service_role. Devuelve los conjuntos o el mensaje de error. */
    private fun Connection.syncAudited(target: String, roles: List<String>, actor: String?): Result<Sets> {
        val array = if (roles.isEmpty()) "array[]::uuid[]" else roles.joinToString(",", "array[", "]") { "'$it'::uuid" }
        val actorSql = actor?.let { "'$it'::uuid" } ?: "null::uuid"
        return try {
            ejecutar("set role service_role")
            // Se descompone el jsonb en SQL (la suite no tiene libreria JSON), conservando el orden devuelto.
            val raw = unico(
                """
                with r as (select public.admin_sync_roles_audited('$target'::uuid, $array, $actorSql) as j)
                select coalesce((select string_agg(e, ',' order by n) from jsonb_array_elements_text(j -> 'antes') with ordinality t(e, n)), '')
                       || '|' ||
                       coalesce((select string_agg(e, ',' order by n) from jsonb_array_elements_text(j -> 'despues') with ordinality t(e, n)), '')
                from r
                """.trimIndent(),
            )!!
            fun lista(s: String) = s.split(',').filter(String::isNotEmpty)
            Result.success(Sets(lista(raw.substringBefore('|')), lista(raw.substringAfter('|'))))
        } catch (e: SQLException) {
            Result.failure(e)
        } finally {
            runCatching { ejecutar("reset role") }
        }
    }

    @Test
    fun devuelveLosConjuntosAntesYDespuesYEscribeComoLaSobrecargaDelActor(): Unit = escenario { c ->
        c.asignar(USER_B, SUPERADMIN)
        c.asignar(USER_C, USUARIO)
        val sets = c.syncAudited(USER_C, listOf(ADMIN, MODERADOR), actor = USER_B).getOrThrow()
        assertEquals(listOf(USUARIO), sets.antes)
        assertEquals(listOf(ADMIN, MODERADOR).sorted(), sets.despues)
        assertEquals(listOf("admin", "moderador"), c.rolesDe(USER_C))
    }

    @Test
    fun losErroresDeLaGuardaSePropaganIgualYNoCambiaNada(): Unit = escenario { c ->
        c.asignar(USER_A, ADMIN)
        c.asignar(USER_B, SUPERADMIN)
        c.asignar(USER_C, USUARIO)
        val sinSuper = c.syncAudited(USER_C, listOf(SUPERADMIN), actor = USER_A).exceptionOrNull()?.message
        assertNotNull(sinSuper)
        assertTrue("SUPERADMIN_REQUIRED" in sinSuper, sinSuper)
        val ultimo = c.syncAudited(USER_B, listOf(ADMIN), actor = USER_B).exceptionOrNull()?.message
        assertNotNull(ultimo)
        assertTrue("ROLE_SYNC_LAST_SUPERADMIN" in ultimo, ultimo)
        val sinUsuario = c.syncAudited("00000000-0000-4000-8000-0000000000ff", listOf(USUARIO), actor = USER_B).exceptionOrNull()?.message
        assertNotNull(sinUsuario)
        assertTrue("ROLE_SYNC_INVALID_USER" in sinUsuario, sinUsuario)
        assertEquals(listOf("usuario"), c.rolesDe(USER_C))
        assertEquals(listOf("superadmin"), c.rolesDe(USER_B))
    }

    @Test
    fun elAntesEsElQueVioBajoElCerrojoNoElDeUnaLecturaPrevia(): Unit = escenario { c ->
        // Ktor lee [usuario] fuera del cerrojo; otra sincronizacion (que tiene el cerrojo) deja [moderador] antes.
        c.asignar(USER_B, SUPERADMIN)
        c.asignar(USER_C, USUARIO)
        val otra = PostgresHarness.conexionExtra(c)
        val pool = Executors.newSingleThreadExecutor()
        try {
            otra.autoCommit = false
            otra.ejecutar("set role service_role")
            otra.ejecutar("select public.admin_sync_roles_audited('$USER_C'::uuid, array['$MODERADOR'::uuid], '$USER_B'::uuid)")
            val segunda = pool.submit<Result<Sets>> { c.syncAudited(USER_C, listOf(USUARIO, MODERADOR), actor = USER_B) }
            Thread.sleep(300)
            assertFalse(segunda.isDone, "la segunda sincronizacion espera al cerrojo")
            otra.commit()
            val sets = segunda.get(20, TimeUnit.SECONDS).getOrThrow()
            assertEquals(listOf(MODERADOR), sets.antes, "el antes real (tras la primera), no el [usuario] rancio")
            assertEquals(listOf(USUARIO, MODERADOR).sorted(), sets.despues)
        } finally {
            pool.shutdownNow()
            otra.close()
        }
    }

    @Test
    fun soloServiceRoleEjecutaYElRollbackQuitaSoloLaFuncionNueva(): Unit = escenario { c ->
        fun verify() = c.filas(Slice0Db.verifyDe(audited)).associate { it[0]!! to it[1]!!.toLong() }
        val v = verify()
        assertEquals(4, v.size, v.toString())
        assertTrue(v.values.all { it == 0L }, "VERIFY: $v")
        val fn = "public.admin_sync_roles_audited(uuid,uuid[],uuid)"
        assertEquals("true", c.unico("select has_function_privilege('service_role', '$fn', 'execute')::text"))
        assertEquals("false", c.unico("select has_function_privilege('anon', '$fn', 'execute')::text"))
        assertEquals("false", c.unico("select has_function_privilege('authenticated', '$fn', 'execute')::text"))

        c.ejecutar(Slice0Db.rollbackDe(audited))
        assertNull(c.unico("select to_regprocedure('$fn')::text"))
        assertNotNull(c.unico("select to_regprocedure('public.admin_sync_roles(uuid,uuid[],uuid)')::text"), "la sobrecarga del actor se conserva")
        assertNotNull(c.unico("select to_regprocedure('public.admin_sync_roles(uuid,uuid[])')::text"), "la guarda de MAIN se conserva")
        assertEquals(1L, verify()["audited_function_missing"])

        c.aplicar(audited)
        c.aplicar(audited) // idempotente
        assertTrue(verify().values.all { it == 0L })
    }
}
