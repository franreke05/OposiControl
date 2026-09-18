package com.oposicontrol.dbtest

import com.oposicontrol.dbtest.SupabaseFixture.ADMIN
import com.oposicontrol.dbtest.SupabaseFixture.SUPERADMIN
import com.oposicontrol.dbtest.SupabaseFixture.USER_A
import com.oposicontrol.dbtest.SupabaseFixture.USER_B
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MUTACIONES SEMANTICAS.
 *
 * Cada test aplica una version SABOTEADA del candidate real a una base
 * desechable y demuestra que la operacion prohibida PASA A COLAR. Eso prueba que
 * el escenario funcional correspondiente tiene poder de deteccion: si con el
 * candidate intacto el escenario esta verde, y con el candidate mutado la base
 * deja pasar al ultimo superadministrador, quien sostiene la invariante es el
 * escenario -- no una expresion regular sobre el texto.
 *
 * Nada de esto toca el fichero del repositorio: la mutacion vive en memoria.
 */
class RoleSyncMutationTest {

    // Construidos por codigo para no meter secuencias de escape en el fuente.
    private val bs = Char(92)
    private val nl = Char(10).toString()
    private val ff = String(Character.toChars(0x000C))

    private val claseCompleta = "" + bs + "u0009-" + bs + "u000D"
    private val claseReducida = "" + bs + "u0009-" + bs + "u000A"

    private val flagG = "      ''," + nl + "      'g'" + nl
    private val sinFlagG = "      ''" + nl

    private val guardaB =
        "  if v_superadmins_antes > 0 and v_superadmins_despues = 0 then" + nl +
            "    raise exception 'ROLE_SYNC_LAST_SUPERADMIN';" + nl +
            "  end if;"

    private val ROL_NO_CANONICO = "55555555-5555-5555-5555-555555555555"

    private fun inyectarAntesDelCommit(sql: String, extra: String): String {
        val i = sql.lastIndexOf("commit;")
        check(i > 0) { "El candidate deberia terminar en commit;" }
        return sql.substring(0, i) + extra + nl + nl + sql.substring(i)
    }

    /** Rol cuyo codigo solo vale 'superadmin' DESPUES de canonicalizar. */
    private fun Connection.crearRolNoCanonico(id: String, codigo: String) =
        prepareStatement("insert into public.roles_app (id, codigo, nombre) values (?::uuid, ?, 'x')").use {
            it.setString(1, id)
            it.setString(2, codigo)
            it.executeUpdate()
        }

    // ---- El control: sin el candidate, la vulnerabilidad es real ----------

    @Test
    fun sinElCandidateElUltimoSuperadministradorSePuedeRetirar(): Unit = PostgresHarness.escenario { c ->
        c.asignar(USER_A, SUPERADMIN)
        c.asignar(USER_B, ADMIN)
        // Funcion LEGACY, sin guardas: es el estado de MAIN hoy.
        assertNull(c.sincronizarRoles(USER_A, listOf(ADMIN)))
        assertEquals(0, c.superadminsActivos(), "El defecto que el candidate cierra es real.")
    }

    // ---- Helper vaciado a identidad ---------------------------------------

    @Test
    fun helperIdentidadDejaPasarAlUltimoSuperadministrador(): Unit = PostgresHarness.escenario { c ->
        c.crearRolNoCanonico(ROL_NO_CANONICO, "SuperAdmin")
        c.asignar(USER_A, ROL_NO_CANONICO)
        c.asignar(USER_B, ADMIN)

        c.aplicarGuardMutado { sql ->
            inyectarAntesDelCommit(
                sql,
                "create or replace function public.admin_role_code_canonical(p_codigo text) " +
                    "returns text language sql immutable set search_path = '' as " +
                    "${'$'}${'$'} select coalesce(p_codigo, '') ${'$'}${'$'};",
            )
        }

        assertNull(c.sincronizarRoles(USER_A, listOf(ADMIN)), "Con el helper vaciado la guarda no ve nada.")
        assertEquals(0, c.superadminsActivos(), "Invariante B muerta: quedan cero superadministradores.")
    }

    @Test
    fun conElHelperIntactoEseMismoCasoAborta(): Unit = PostgresHarness.escenario { c ->
        c.crearRolNoCanonico(ROL_NO_CANONICO, "SuperAdmin")
        c.asignar(USER_A, ROL_NO_CANONICO)
        c.asignar(USER_B, ADMIN)
        c.aplicarGuard()

        val error = c.sincronizarRoles(USER_A, listOf(ADMIN))
        assertNotNull(error)
        assertTrue("ROLE_SYNC_LAST_SUPERADMIN" in error, error)
        assertEquals(1, c.superadminsActivos())
    }

    // ---- Flag `g`: el P0 que encontro la quinta revision -------------------

    @Test
    fun quitarLaFlagGDejaWhitespaceFinalSinRecortar(): Unit = PostgresHarness.escenario { c ->
        c.aplicarGuardMutado { it.replace(flagG, sinFlagG) }

        assertEquals(
            "superadmin" + ff,
            c.canonical(ff + "superadmin" + ff),
            "Sin la flag 'g' solo se recorta el primer extremo.",
        )
    }

    @Test
    fun quitarLaFlagGDejaPasarAlUltimoSuperadministrador(): Unit = PostgresHarness.escenario { c ->
        c.crearRolNoCanonico(ROL_NO_CANONICO, ff + "superadmin" + ff)
        c.asignar(USER_A, ROL_NO_CANONICO)
        c.asignar(USER_B, ADMIN)

        c.aplicarGuardMutado { it.replace(flagG, sinFlagG) }

        assertNull(c.sincronizarRoles(USER_A, listOf(ADMIN)))
        assertEquals(0, c.superadminsActivos(), "Invariante B muerta por un solo token.")
    }

    @Test
    fun conLaFlagGIntactaEseMismoCasoAborta(): Unit = PostgresHarness.escenario { c ->
        c.crearRolNoCanonico(ROL_NO_CANONICO, ff + "superadmin" + ff)
        c.asignar(USER_A, ROL_NO_CANONICO)
        c.asignar(USER_B, ADMIN)
        c.aplicarGuard()

        assertEquals("superadmin", c.canonical(ff + "superadmin" + ff))
        val error = c.sincronizarRoles(USER_A, listOf(ADMIN))
        assertNotNull(error)
        assertTrue("ROLE_SYNC_LAST_SUPERADMIN" in error, error)
    }

    // ---- Clase de whitespace reducida --------------------------------------

    @Test
    fun reducirLaClaseDeWhitespaceDejaDeRecortarU000C(): Unit = PostgresHarness.escenario { c ->
        c.aplicarGuardMutado { it.replace(claseCompleta, claseReducida) }

        assertEquals(
            "superadmin" + ff,
            c.canonical("superadmin" + ff),
            "Con la clase recortada U+000C sobrevive.",
        )
    }

    @Test
    fun reducirLaClaseDeWhitespaceDejaPasarAlUltimoSuperadministrador(): Unit =
        PostgresHarness.escenario { c ->
            c.crearRolNoCanonico(ROL_NO_CANONICO, "superadmin" + ff)
            c.asignar(USER_A, ROL_NO_CANONICO)
            c.asignar(USER_B, ADMIN)

            c.aplicarGuardMutado { it.replace(claseCompleta, claseReducida) }

            assertNull(c.sincronizarRoles(USER_A, listOf(ADMIN)))
            assertEquals(0, c.superadminsActivos())
        }

    // ---- raise exception -> raise notice ------------------------------------

    @Test
    fun raiseNoticeEnLugarDeExceptionDejaPasarAlUltimoSuperadministrador(): Unit =
        PostgresHarness.escenario { c ->
            c.asignar(USER_A, SUPERADMIN)
            c.asignar(USER_B, ADMIN)

            c.aplicarGuardMutado {
                it.replace(
                    "raise exception 'ROLE_SYNC_LAST_SUPERADMIN';",
                    "raise notice 'ROLE_SYNC_LAST_SUPERADMIN';",
                )
            }

            assertNull(c.sincronizarRoles(USER_A, listOf(ADMIN)), "Un notice no aborta.")
            assertEquals(0, c.superadminsActivos())
        }

    // ---- Manejador que se traga la excepcion --------------------------------

    @Test
    fun unManejadorQueSeTragaLaExcepcionDejaConfirmadaLaEscritura(): Unit =
        PostgresHarness.escenario { c ->
            c.asignar(USER_A, SUPERADMIN)
            c.asignar(USER_B, ADMIN)

            c.aplicarGuardMutado {
                it.replace(
                    guardaB,
                    "  begin" + nl + guardaB + nl + "  exception when others then null;" + nl + "  end;",
                )
            }

            assertNull(c.sincronizarRoles(USER_A, listOf(ADMIN)))
            assertEquals(0, c.superadminsActivos(), "El delete/insert se confirma pese al raise.")
        }

    // ---- Neutralizadores por condicion imposible ----------------------------

    @Test
    fun unaCondicionImposibleEnLaInvarianteADejaVaciarAUnAdministrador(): Unit =
        PostgresHarness.escenario { c ->
            c.asignar(USER_A, SUPERADMIN)
            c.asignar(USER_B, ADMIN)

            c.aplicarGuardMutado {
                it.replace(
                    "  if v_era_administrativo and cardinality(v_role_ids) = 0 then",
                    "  if v_era_administrativo and cardinality(v_role_ids) = 0 and v_desconocidos > 0 then",
                )
            }

            assertNull(c.sincronizarRoles(USER_B, emptyList()))
            assertEquals(emptyList(), c.rolesDe(USER_B), "Invariante A muerta.")
        }

    @Test
    fun unaCondicionImposibleEnLaInvarianteBDejaPasarAlUltimoSuperadministrador(): Unit =
        PostgresHarness.escenario { c ->
            c.asignar(USER_A, SUPERADMIN)
            c.asignar(USER_B, ADMIN)

            c.aplicarGuardMutado {
                it.replace(
                    "  if v_superadmins_antes > 0 and v_superadmins_despues = 0 then",
                    "  if v_superadmins_antes > 0 and v_superadmins_despues = 0 and v_desconocidos > 0 then",
                )
            }

            assertNull(c.sincronizarRoles(USER_A, listOf(ADMIN)))
            assertEquals(0, c.superadminsActivos())
        }

    // ---- Recuento posterior ensanchado --------------------------------------

    @Test
    fun ensancharElRecuentoPosteriorDejaDegradarAlUltimoSuperadministrador(): Unit =
        PostgresHarness.escenario { c ->
            c.asignar(USER_A, SUPERADMIN)
            c.asignar(USER_B, ADMIN)

            c.aplicarGuardMutado { sql ->
                val aguja = "= 'superadmin';"
                val i = sql.lastIndexOf(aguja)
                check(i > 0)
                sql.substring(0, i) + "in ('superadmin', 'admin');" + sql.substring(i + aguja.length)
            }

            assertNull(c.sincronizarRoles(USER_A, listOf(ADMIN)))
            assertEquals(0, c.superadminsActivos(), "Contar admins como superadmins mata la invariante.")
        }

    // ---- Segunda definicion que pisa a la guardada ---------------------------

    @Test
    fun unaSegundaDefinicionEnMayusculasPisaALaFuncionGuardada(): Unit =
        PostgresHarness.escenario { c ->
            c.asignar(USER_A, SUPERADMIN)
            c.asignar(USER_B, ADMIN)

            c.aplicarGuardMutado { sql ->
                inyectarAntesDelCommit(
                    sql,
                    "CREATE OR REPLACE FUNCTION public.admin_sync_roles(p_usuario_id uuid, p_role_ids uuid[]) " +
                        "RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS ${'$'}${'$'} " +
                        "BEGIN " +
                        "DELETE FROM public.roles_usuario WHERE usuario_id = p_usuario_id; " +
                        "INSERT INTO public.roles_usuario (usuario_id, rol_id) " +
                        "SELECT DISTINCT p_usuario_id, x FROM unnest(coalesce(p_role_ids, array[]::uuid[])) x; " +
                        "END; ${'$'}${'$'};",
                )
            }

            assertNull(c.sincronizarRoles(USER_A, listOf(ADMIN)))
            assertEquals(0, c.superadminsActivos(), "La instalada es la segunda, sin guardas.")
        }
}
