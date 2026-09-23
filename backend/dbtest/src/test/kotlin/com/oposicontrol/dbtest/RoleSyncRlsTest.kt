package com.oposicontrol.dbtest

import com.oposicontrol.dbtest.SupabaseFixture.ADMIN
import com.oposicontrol.dbtest.SupabaseFixture.SUPERADMIN
import com.oposicontrol.dbtest.SupabaseFixture.USER_A
import com.oposicontrol.dbtest.SupabaseFixture.USER_B
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * RLS Y PROPIEDAD DE LA FUNCION.
 *
 * `admin_sync_roles` pasa a `security definer`, asi que corre como SU
 * propietario. `public.roles_usuario` tiene RLS activa. Si ese propietario no
 * puede ver todas las filas, los recuentos de la guarda leen menos de las que
 * hay -- y una invariante que cuenta mal no aborta: deja pasar.
 *
 * Esto NO se puede comprobar leyendo el fichero. Aqui se monta el escenario y se
 * mira lo que hace la base.
 */
class RoleSyncRlsTest {

    private val PROPIETARIO = "guard_owner"

    /** Un propietario realista: ni superusuario, ni BYPASSRLS, ni dueño de las tablas. */
    private fun Connection.crearPropietarioSinBypass() {
        ejecutar(
            """
            drop role if exists $PROPIETARIO;
            create role $PROPIETARIO nologin;
            grant usage on schema public, auth to $PROPIETARIO;
            grant select, insert, delete on public.roles_usuario to $PROPIETARIO;
            grant select on public.roles_app to $PROPIETARIO;
            grant select on auth.users to $PROPIETARIO;
            """.trimIndent(),
        )
    }

    private fun Connection.propietarioDeLaRpc(): String =
        unico(
            "select pg_get_userbyid(proowner) from pg_proc " +
                "where oid = to_regprocedure('public.admin_sync_roles(uuid,uuid[])')",
        )!!

    // ---- RLS-1: configuracion correcta -----------------------------------

    @Test
    fun conPropietarioQueVeTodasLasFilasLaSincronizacionValidaFunciona(): Unit =
        PostgresHarness.escenario { c ->
            c.asignar(USER_A, SUPERADMIN)
            c.asignar(USER_B, SUPERADMIN)
            c.aplicarGuard()

            assertEquals("postgres", c.propietarioDeLaRpc())
            assertEquals(null, c.sincronizarRoles(USER_A, listOf(ADMIN)))
            assertEquals(1, c.superadminsActivos())
        }

    // ---- RLS-2: el escenario peligroso ------------------------------------

    @Test
    fun unPropietarioSinBypassNoPuedeDejarLaInvarianteMUDA(): Unit = PostgresHarness.escenario { c ->
        c.asignar(USER_A, SUPERADMIN)
        c.asignar(USER_B, ADMIN)
        c.aplicarGuard()
        c.crearPropietarioSinBypass()
        c.ejecutar("alter function public.admin_sync_roles(uuid, uuid[]) owner to $PROPIETARIO")
        assertEquals(PROPIETARIO, c.propietarioDeLaRpc())

        // Con RLS aplicandose al propietario, los recuentos ven 0 filas.
        val error = c.sincronizarRoles(USER_A, listOf(ADMIN))
        println("RLS2_ERROR: $error")

        // Lo que EXIGIMOS: que falle cerrado. Nunca «cuento cero y sigo».
        assertNotNull(
            error,
            "FALLO ABIERTO: la RPC corrio como un propietario que no ve las filas y " +
                "confirmo la operacion. Superadministradores tras la llamada: " +
                c.superadminsActivos(),
        )
        assertEquals(1, c.superadminsActivos(), "No puede haberse escrito nada.")
    }

    // ---- RLS-3: FORCE ROW LEVEL SECURITY ----------------------------------

    @Test
    fun conForceRowLevelSecurityElComportamientoSigueSiendoCerrado(): Unit =
        PostgresHarness.escenario { c ->
            c.asignar(USER_A, SUPERADMIN)
            c.asignar(USER_B, ADMIN)
            c.aplicarGuard()
            c.ejecutar("alter table public.roles_usuario force row level security")

            val error = c.sincronizarRoles(USER_A, listOf(ADMIN))

            assertNotNull(
                error,
                "Con FORCE RLS ni siquiera el dueño de la tabla esta exento: " +
                    "la operacion tenia que fallar, no colar. Superadministradores: " +
                    c.superadminsActivos(),
            )
            assertEquals(1, c.superadminsActivos())
        }

    // ---- Diagnostico de la configuracion que el preflight debe exigir ------

    @Test
    fun laConfiguracionDeRlsDeLasTablasEsLaQueElCandidateAsume(): Unit =
        PostgresHarness.escenario { c ->
            assertEquals("true", c.unico("select relrowsecurity::text from pg_class where oid = 'public.roles_usuario'::regclass"))
            assertEquals("false", c.unico("select relforcerowsecurity::text from pg_class where oid = 'public.roles_usuario'::regclass"))
            assertTrue(
                c.unico("select rolbypassrls::text from pg_roles where rolname = 'service_role'") == "true",
                "En Supabase service_role lleva BYPASSRLS; el fixture debe reproducirlo.",
            )
        }
}
