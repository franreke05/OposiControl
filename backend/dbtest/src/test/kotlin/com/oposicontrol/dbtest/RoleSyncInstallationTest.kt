package com.oposicontrol.dbtest

import com.oposicontrol.dbtest.SupabaseFixture.ADMIN
import com.oposicontrol.dbtest.SupabaseFixture.SUPERADMIN
import com.oposicontrol.dbtest.SupabaseFixture.USER_A
import com.oposicontrol.dbtest.SupabaseFixture.USER_B
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * INSTALACION, PRIVILEGIOS Y PREFLIGHT, comprobados contra el catalogo real.
 *
 * La autoridad es `pg_proc` y el comportamiento de la base, no una expresion
 * regular sobre el fichero: una segunda definicion en MAYUSCULAS o una firma
 * distinta cambian lo que queda INSTALADO, y eso es lo que se mide aqui.
 */
class RoleSyncInstallationTest {

    private fun nombreDeRol() = "intruso_" + UUID.randomUUID().toString().take(8)

    private fun Connection.rpc(): Map<String, String?> =
        createStatement().use { st ->
            st.executeQuery(
                """
                select p.oid::regprocedure::text as firma,
                       p.prosecdef::text        as definer,
                       p.provolatile            as volatilidad,
                       pg_get_userbyid(p.proowner) as propietario,
                       coalesce(array_to_string(p.proconfig, '|'), '') as config,
                       (select count(*) from pg_proc q
                         join pg_namespace n on n.oid = q.pronamespace
                        where n.nspname = 'public' and q.proname = 'admin_sync_roles')::text as definiciones
                from pg_proc p
                where p.oid = to_regprocedure('public.admin_sync_roles(uuid,uuid[])')
                """.trimIndent(),
            ).use { rs ->
                assertTrue(rs.next(), "La RPC no quedo instalada.")
                mapOf(
                    "firma" to rs.getString("firma"),
                    "definer" to rs.getString("definer"),
                    "volatilidad" to rs.getString("volatilidad"),
                    "propietario" to rs.getString("propietario"),
                    "config" to rs.getString("config"),
                    "definiciones" to rs.getString("definiciones"),
                )
            }
        }

    // ---- La base instalada es la autoridad --------------------------------

    @Test
    fun elCatalogoConfirmaLaFuncionQueQuedaInstalada(): Unit = PostgresHarness.escenario { c ->
        c.aplicarGuard()
        val rpc = c.rpc()

        assertEquals("admin_sync_roles(uuid,uuid[])", rpc["firma"]?.removePrefix("public."))
        assertEquals("true", rpc["definer"], "Tiene que quedar security definer.")
        assertEquals("v", rpc["volatilidad"], "volatile: `stable` reinstaura el TOCTOU en silencio.")
        assertEquals("1", rpc["definiciones"], "Una segunda definicion pisaria a la guardada.")

        val config = rpc["config"].orEmpty()
        assertTrue("search_path=" in config, "Falta el search_path fijado: $config")
        assertTrue("row_security=off" in config, "Falta la defensa de fallo cerrado: $config")
    }

    @Test
    fun elCanonicalizadorQuedaInstaladoYEsInmutable(): Unit = PostgresHarness.escenario { c ->
        c.aplicarGuard()
        assertEquals(
            "i",
            c.unico(
                "select provolatile from pg_proc " +
                    "where oid = to_regprocedure('public.admin_role_code_canonical(text)')",
            ),
        )
    }

    @Test
    fun soloServiceRoleConservaExecuteSobreLaRpc(): Unit = PostgresHarness.escenario { c ->
        c.aplicarGuard()
        assertEquals("true", c.puedeEjecutar("service_role"))
        assertEquals("false", c.puedeEjecutar("anon"))
        assertEquals("false", c.puedeEjecutar("authenticated"))
    }

    // ---- ACL preflight, probado por comportamiento -------------------------

    @Test
    fun unAclLimpioDejaAplicarElCandidate(): Unit = PostgresHarness.escenario { c ->
        c.aplicarGuard()
        assertEquals("true", c.unico("select prosecdef::text from pg_proc where oid = to_regprocedure('public.admin_sync_roles(uuid,uuid[])')"))
    }

    @Test
    fun unGrantHostilHeredadoAbortaLaMigracionEnteraYNoDejaEstadoIntermedio(): Unit =
        PostgresHarness.escenario { c ->
            val intruso = nombreDeRol()
            c.ejecutar("create role $intruso nologin")
            c.ejecutar("grant execute on function public.admin_sync_roles(uuid, uuid[]) to $intruso")

            val error = try {
                c.aplicarGuard()
                null
            } catch (e: java.sql.SQLException) {
                e.message
            }
            // El script abre `begin;`: tras abortar hay que cerrar la transaccion fallida.
            c.ejecutar("rollback")

            assertNotNull(error, "Un grantee inesperado tenia que abortar el apply.")
            assertTrue("ROLE_SYNC_GUARD_UNEXPECTED_ACL" in error, error)
            assertTrue(intruso in error, "El mensaje debe nombrar al intruso: $error")

            // Sin estado intermedio: sigue la legacy y el helper no existe.
            assertEquals(
                "false",
                c.unico("select prosecdef::text from pg_proc where oid = to_regprocedure('public.admin_sync_roles(uuid,uuid[])')"),
                "La funcion tenia que quedarse como estaba.",
            )
            assertNull(
                c.unico("select to_regprocedure('public.admin_role_code_canonical(text)')::text"),
                "El helper no puede haberse creado si la migracion aborto.",
            )
        }

    // ---- Privilegios sobre auth.users --------------------------------------

    @Test
    fun sinSelectSobreAuthUsersLaRpcFallaYNoMutaNada(): Unit = PostgresHarness.escenario { c ->
        val propietario = "owner_" + UUID.randomUUID().toString().take(8)
        c.asignar(USER_A, SUPERADMIN)
        c.asignar(USER_B, ADMIN)
        c.aplicarGuard()
        c.ejecutar(
            """
            create role $propietario nologin bypassrls;
            grant usage on schema public, auth to $propietario;
            grant select, insert, delete on public.roles_usuario to $propietario;
            grant select on public.roles_app to $propietario;
            """.trimIndent(),
        )
        // Deliberadamente SIN select sobre auth.users.
        c.ejecutar("alter function public.admin_sync_roles(uuid, uuid[]) owner to $propietario")

        val error = c.sincronizarRoles(USER_B, listOf(ADMIN, SUPERADMIN))

        assertNotNull(error, "Sin permiso de lectura sobre auth.users tiene que fallar.")
        assertEquals(listOf("admin"), c.rolesDe(USER_B), "No puede haber mutacion parcial.")
        assertEquals(1, c.superadminsActivos())
    }

    // ---- Privilegios sobre el helper ---------------------------------------

    @Test
    fun sinExecuteSobreElHelperLaRpcFallaCerradaYNoMutaNada(): Unit = PostgresHarness.escenario { c ->
        val propietario = "owner_" + UUID.randomUUID().toString().take(8)
        c.asignar(USER_A, SUPERADMIN)
        c.asignar(USER_B, ADMIN)
        c.aplicarGuard()
        c.ejecutar(
            """
            create role $propietario nologin bypassrls;
            grant usage on schema public, auth to $propietario;
            grant select, insert, delete on public.roles_usuario to $propietario;
            grant select on public.roles_app to $propietario;
            grant select on auth.users to $propietario;
            """.trimIndent(),
        )
        c.ejecutar("alter function public.admin_sync_roles(uuid, uuid[]) owner to $propietario")
        c.ejecutar("revoke execute on function public.admin_role_code_canonical(text) from public")

        val error = c.sincronizarRoles(USER_A, listOf(ADMIN))

        assertNotNull(error, "Sin EXECUTE sobre el helper la RPC tiene que caer, no colar.")
        assertEquals(listOf("superadmin"), c.rolesDe(USER_A), "No puede haber mutacion parcial.")
    }

    // ---- standard_conforming_strings ----------------------------------------

    @Test
    fun elCanonicalizadorNoDependeDeStandardConformingStrings(): Unit = PostgresHarness.escenario { c ->
        c.ejecutar("set standard_conforming_strings = off")
        assertEquals(
            "off",
            c.unico("select current_setting('standard_conforming_strings')"),
            "Si el ajuste no llega a aplicarse, este test no prueba nada.",
        )
        c.aplicarGuard()
        c.ejecutar("reset standard_conforming_strings")

        val ff = String(Character.toChars(0x000C))
        assertEquals(
            "superadmin",
            c.canonical(ff + "superadmin" + ff),
            "Con standard_conforming_strings = off la clase de whitespace degenera.",
        )
    }
}
