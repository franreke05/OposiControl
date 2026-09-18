package com.oposicontrol.dbtest

import com.oposicontrol.dbtest.SupabaseFixture.ADMIN
import com.oposicontrol.dbtest.SupabaseFixture.MODERADOR
import com.oposicontrol.dbtest.SupabaseFixture.SUPERADMIN
import com.oposicontrol.dbtest.SupabaseFixture.USER_A
import com.oposicontrol.dbtest.SupabaseFixture.USER_B
import com.oposicontrol.dbtest.SupabaseFixture.USER_C
import com.oposicontrol.dbtest.SupabaseFixture.USUARIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Las invariantes, probadas por COMPORTAMIENTO contra PostgreSQL real.
 *
 * Ninguna asercion de este fichero mira el texto del SQL. Se aplica el candidate
 * del repositorio a una base desechable, se ejecuta la operacion y se comprueba
 * el ESTADO DE LAS TABLAS. Da igual como se sabotee la sintaxis: si deja pasar
 * al ultimo superadministrador, esto se pone rojo.
 */
class RoleSyncSemanticTest {

    @Test
    fun elCandidateDelRepositorioSeInstalaSobreElEstadoLegacy(): Unit = PostgresHarness.escenario { c ->
        assertEquals("invoker", c.modoDeSeguridad(), "El fixture debe partir de la funcion legacy.")
        c.aplicarGuard()
        assertEquals("definer", c.modoDeSeguridad())
        assertNotNull(c.unico("select to_regprocedure('public.admin_role_code_canonical(text)')::text"))
    }

    // ---- INVARIANTE B ----------------------------------------------------

    @Test
    fun degradarAlUltimoSuperadministradorAbortaYNoTocaLaBase(): Unit = PostgresHarness.escenario { c ->
        c.asignar(USER_A, SUPERADMIN)
        c.asignar(USER_B, ADMIN)
        c.aplicarGuard()

        val error = c.sincronizarRoles(USER_A, listOf(ADMIN))

        assertNotNull(error, "Degradar al unico superadministrador tenia que abortar.")
        assertTrue("ROLE_SYNC_LAST_SUPERADMIN" in error, error)
        assertEquals(listOf("superadmin"), c.rolesDe(USER_A), "El estado tenia que quedar intacto.")
        assertEquals(1, c.superadminsActivos())
    }

    @Test
    fun conOtroSuperadministradorVivoLaDegradacionEsLegitima(): Unit = PostgresHarness.escenario { c ->
        c.asignar(USER_A, SUPERADMIN)
        c.asignar(USER_B, SUPERADMIN)
        c.aplicarGuard()

        assertNull(c.sincronizarRoles(USER_A, listOf(ADMIN)))

        assertEquals(listOf("admin"), c.rolesDe(USER_A))
        assertEquals(listOf("superadmin"), c.rolesDe(USER_B))
        assertEquals(1, c.superadminsActivos())
    }

    @Test
    fun otroAdministradorTampocoPuedeRetirarElUltimoSuperadministrador(): Unit = PostgresHarness.escenario { c ->
        c.asignar(USER_A, SUPERADMIN)
        c.asignar(USER_B, ADMIN)
        c.aplicarGuard()

        val error = c.sincronizarRoles(USER_A, listOf(MODERADOR))

        assertNotNull(error)
        assertTrue("ROLE_SYNC_LAST_SUPERADMIN" in error, error)
        assertEquals(1, c.superadminsActivos())
    }

    @Test
    fun crearElPrimerSuperadministradorDeUnSistemaSinNingunoNoSeBloquea(): Unit = PostgresHarness.escenario { c ->
        c.asignar(USER_A, USUARIO)
        c.aplicarGuard()

        assertEquals(0, c.superadminsActivos())
        assertNull(c.sincronizarRoles(USER_A, listOf(SUPERADMIN)))
        assertEquals(1, c.superadminsActivos())
    }

    // ---- INVARIANTE A ----------------------------------------------------

    @Test
    fun unUsuarioAdministrativoNoPuedeQuedarseSinRoles(): Unit = PostgresHarness.escenario { c ->
        c.asignar(USER_A, SUPERADMIN)
        c.asignar(USER_B, ADMIN)
        c.aplicarGuard()

        val error = c.sincronizarRoles(USER_B, emptyList())

        assertNotNull(error, "Vaciar los roles de un administrador tenia que abortar.")
        assertTrue("ROLE_SYNC_EMPTY_ROLES" in error, error)
        assertEquals(listOf("admin"), c.rolesDe(USER_B))
    }

    @Test
    fun aUnUsuarioCorrienteSiSeLePuedenRetirarTodosLosRoles(): Unit = PostgresHarness.escenario { c ->
        c.asignar(USER_A, SUPERADMIN)
        c.asignar(USER_C, USUARIO)
        c.aplicarGuard()

        assertNull(c.sincronizarRoles(USER_C, emptyList()))
        assertEquals(emptyList(), c.rolesDe(USER_C))
    }

    private fun java.sql.Connection.modoDeSeguridad(): String =
        unico(
            "select case when prosecdef then 'definer' else 'invoker' end " +
                "from pg_proc where oid = to_regprocedure('public.admin_sync_roles(uuid,uuid[])')",
        )!!
}
