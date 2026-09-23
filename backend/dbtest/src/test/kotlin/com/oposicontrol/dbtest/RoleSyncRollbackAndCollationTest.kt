package com.oposicontrol.dbtest

import com.oposicontrol.dbtest.SupabaseFixture.ADMIN
import com.oposicontrol.dbtest.SupabaseFixture.SUPERADMIN
import com.oposicontrol.dbtest.SupabaseFixture.USER_A
import com.oposicontrol.dbtest.SupabaseFixture.USER_B
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ROLLBACK Y COLACION, los dos por comportamiento.
 *
 * El rollback no se certifica comprobando que ciertas palabras aparecen en su
 * cabecera: se aplica, y se mira que deja instalado y que deja de proteger.
 */
class RoleSyncRollbackAndCollationTest {

    private fun Connection.modo(): String =
        unico("select prosecdef::text from pg_proc where oid = to_regprocedure('public.admin_sync_roles(uuid,uuid[])')")!!

    private fun Connection.config(): String =
        unico(
            "select coalesce(array_to_string(proconfig, '|'), '') from pg_proc " +
                "where oid = to_regprocedure('public.admin_sync_roles(uuid,uuid[])')",
        )!!

    private fun Connection.puedeEjecutar(rol: String): String =
        unico("select has_function_privilege('$rol', 'public.admin_sync_roles(uuid,uuid[])', 'execute')::text")!!

    // ---- Rollback ---------------------------------------------------------

    @Test
    fun elRollbackRestauraElEstadoLegacyYReabreLaVulnerabilidad(): Unit =
        PostgresHarness.escenario { c ->
            val modoLegacy = c.modo()
            val configLegacy = c.config()
            assertEquals("false", modoLegacy, "El fixture parte de security invoker.")

            c.aplicarGuard()
            assertEquals("true", c.modo())
            assertTrue("row_security=off" in c.config())

            c.aplicarRollback()

            // 1. Vuelve exactamente al modo y la configuracion de partida.
            assertEquals(modoLegacy, c.modo(), "El rollback debe devolver security invoker.")
            assertEquals(configLegacy, c.config(), "El rollback debe devolver el search_path original.")
            assertTrue("row_security" !in c.config(), "La defensa de RLS no puede sobrevivir al rollback.")

            // 2. Los grants se reemiten: solo service_role.
            assertEquals("true", c.puedeEjecutar("service_role"))
            assertEquals("false", c.puedeEjecutar("anon"))
            assertEquals("false", c.puedeEjecutar("authenticated"))

            // 3. El helper se queda vivo a proposito.
            assertNotNull(
                c.unico("select to_regprocedure('public.admin_role_code_canonical(text)')::text"),
                "El rollback deja el helper instalado: revocarlo no era el problema.",
            )

            // 4. Y lo que de verdad importa: la proteccion YA NO ESTA.
            c.asignar(USER_A, SUPERADMIN)
            c.asignar(USER_B, ADMIN)
            assertNull(
                c.sincronizarRoles(USER_A, listOf(ADMIN)),
                "Tras revertir, retirar al ultimo superadministrador vuelve a colar.",
            )
            assertEquals(
                0,
                c.superadminsActivos(),
                "Revertir REABRE la vulnerabilidad; eso es lo esperado y esta documentado.",
            )
        }

    @Test
    fun trasElRollbackLaSincronizacionNormalSigueFuncionando(): Unit =
        PostgresHarness.escenario { c ->
            c.aplicarGuard()
            c.aplicarRollback()
            c.asignar(USER_A, SUPERADMIN)

            assertNull(c.sincronizarRoles(USER_A, listOf(ADMIN, SUPERADMIN)))
            assertEquals(listOf("admin", "superadmin"), c.rolesDe(USER_A))
        }

    // ---- Colacion ---------------------------------------------------------

    /**
     * `collate "C"` protege de que `lower()` dependa de la intercalacion de la
     * base. Bajo una turca, `lower('SUPERADMIN')` da 'superadmın' con i sin
     * punto. Si esta instalacion de PostgreSQL no trae ICU, se declara
     * NOT_AVAILABLE en vez de fingir cobertura.
     */
    @Test
    fun bajoIntercalacionTurcaElCollateCEsLoQueSostieneLaComparacion() {
        val conCollate = PostgresHarness.escenarioConIcu("tr-TR") { c ->
            c.aplicarGuard()
            c.canonical("SUPERADMIN")
        }

        if (conCollate == null) {
            println("COLLATION_RUNTIME_TEST: NOT_AVAILABLE")
            return
        }

        val sinCollate = PostgresHarness.escenarioConIcu("tr-TR") { c ->
            c.aplicarGuardMutado { it.replace(") collate \"C\"", ")") }
            c.canonical("SUPERADMIN")
        }

        println("COLLATION_RUNTIME_TEST: AVAILABLE")
        println("COLLATION_CON_COLLATE: " + conCollate)
        println("COLLATION_SIN_COLLATE: " + sinCollate)

        assertEquals("superadmin", conCollate, "Con collate \"C\" el casing no depende del locale.")
        assertNotEquals(
            conCollate,
            sinCollate,
            "Quitar collate \"C\" tiene que cambiar el resultado bajo tr-TR; si no, " +
                "la proteccion es decorativa y hay que declararlo.",
        )
    }
}
