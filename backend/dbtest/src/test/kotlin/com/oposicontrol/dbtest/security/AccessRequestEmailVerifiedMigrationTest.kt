package com.oposicontrol.dbtest.security

import com.oposicontrol.dbtest.PostgresHarness
import com.oposicontrol.dbtest.SupabaseFixture.USER_A
import com.oposicontrol.dbtest.SupabaseFixture.USER_B
import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.aplicar
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.slice0.sqlStateDe
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Migracion 20261020100000 (plan del propietario 2026-10-14 + revision de seguridad 2026-10-14):
 * `solicitudes_acceso_admin.correo_verificado_en` (nula, SIN comprobacion de orden frente a `resuelto_en`: L4, dos relojes
 * de aplicacion) e `intentos_verificacion` (entero no nulo, 0 por defecto, nunca negativo: M2). Sobre la tabla REAL de
 * 20261013100000 (el fixture trae `auth.users` y los roles `anon`/`authenticated`/`service_role` del cluster).
 *
 * Control NEGATIVO (sin la migracion las columnas no existen), filas anteriores intactas (nula / 0), el CHECK del contador,
 * verificar despues de resolver ya se admite, los clientes siguen sin poder leerlas ni escribirlas, el bloque VERIFY da 0
 * en todo y una mutacion en memoria de la migracion (sin el CHECK) lo hace saltar.
 */
class AccessRequestEmailVerifiedMigrationTest {
    private val tabla = "supabase/migrations/20261013100000_security_admin_access_requests.sql"
    private val migracion = "supabase/migrations/20261020100000_security_access_request_email_verified.sql"
    private val check = "solicitudes_acceso_admin_intentos_verificacion_chk"
    private val solicitudA = "5a5a5a5a-0000-4000-8000-0000000000a1"
    private val solicitudB = "5a5a5a5a-0000-4000-8000-0000000000b2"

    private fun <T> escenario(conMigracion: Boolean = true, antes: (Connection) -> Unit = {}, bloque: (Connection) -> T): T =
        PostgresHarness.escenario { c ->
            c.aplicar(tabla)
            antes(c)
            if (conMigracion) c.aplicar(migracion)
            bloque(c)
        }

    private fun Connection.insertarPendiente(id: String, usuario: String) = ejecutar(
        "insert into public.solicitudes_acceso_admin (id, usuario_id, nombre, email, motivo) " +
            "values ('$id', '$usuario', 'Ada', 'ada-$usuario@test.local', 'Coordino el equipo.')",
    )

    private fun Connection.verify(): Map<String, Long> =
        filas(Slice0Db.verifyDe(migracion)).associate { it[0]!! to it[1]!!.toLong() }

    private fun Connection.columnaExiste(columna: String): Boolean = unico(
        "select count(*)::text from information_schema.columns where table_schema = 'public' " +
            "and table_name = 'solicitudes_acceso_admin' and column_name = '$columna'",
    ) == "1"

    @Test
    fun antesDeLaMigracionLasColumnasNoExisten(): Unit = escenario(conMigracion = false) { c ->
        assertEquals(false, c.columnaExiste("correo_verificado_en"))
        assertEquals(false, c.columnaExiste("intentos_verificacion"))
        assertEquals("42703", c.sqlStateDe("select correo_verificado_en from public.solicitudes_acceso_admin"))
        assertEquals("42703", c.sqlStateDe("select intentos_verificacion from public.solicitudes_acceso_admin"))
    }

    @Test
    fun lasFilasAnterioresQuedanSinVerificarConCeroIntentosYElVerifyDaCero(): Unit =
        escenario(antes = { it.insertarPendiente(solicitudA, USER_A) }) { c ->
            assertTrue(c.columnaExiste("correo_verificado_en"))
            assertTrue(c.columnaExiste("intentos_verificacion"))
            assertNull(c.unico("select correo_verificado_en::text from public.solicitudes_acceso_admin where id = '$solicitudA'"))
            assertEquals("0", c.unico("select intentos_verificacion::text from public.solicitudes_acceso_admin where id = '$solicitudA'"))
            val v = c.verify()
            assertEquals(12, v.size, v.toString())
            assertTrue(v.values.all { it == 0L }, v.toString())
        }

    @Test
    fun elContadorNoAdmiteNegativosNiNuloYSeIncrementa(): Unit = escenario { c ->
        c.insertarPendiente(solicitudA, USER_A)
        assertNull(
            c.sqlStateDe(
                "update public.solicitudes_acceso_admin set intentos_verificacion = intentos_verificacion + 1 " +
                    "where id = '$solicitudA' and intentos_verificacion = 0",
            ),
        )
        assertEquals("1", c.unico("select intentos_verificacion::text from public.solicitudes_acceso_admin where id = '$solicitudA'"))
        assertEquals("23514", c.sqlStateDe("update public.solicitudes_acceso_admin set intentos_verificacion = -1 where id = '$solicitudA'"))
        assertEquals("23502", c.sqlStateDe("update public.solicitudes_acceso_admin set intentos_verificacion = null where id = '$solicitudA'"))
        // El CHECK tambien aplica a service_role (el backend no pasa por RLS, pero si por el CHECK).
        c.ejecutar("set role service_role")
        try {
            assertEquals("23514", c.sqlStateDe("update public.solicitudes_acceso_admin set intentos_verificacion = -5 where id = '$solicitudA'"))
        } finally {
            c.ejecutar("reset role")
        }
        assertEquals(0L, c.verify().getValue("rows_with_negative_attempts"))
    }

    /** L4: sin comprobacion de orden entre dos relojes de aplicacion; una verificacion posterior a la resolucion ya entra. */
    @Test
    fun verificarYResolverSeAdmitenEnCualquierOrdenDeReloj(): Unit = escenario { c ->
        c.insertarPendiente(solicitudA, USER_A)
        c.insertarPendiente(solicitudB, USER_B)
        assertNull(c.sqlStateDe("update public.solicitudes_acceso_admin set correo_verificado_en = now() - interval '1 minute' where id = '$solicitudA'"))
        assertNull(
            c.sqlStateDe(
                "update public.solicitudes_acceso_admin set estado = 'aprobada', resuelto_en = now() - interval '2 minutes', " +
                    "resuelto_por = '$USER_B' where id = '$solicitudA'",
            ),
        )
        assertNull(c.sqlStateDe("update public.solicitudes_acceso_admin set estado = 'rechazada', resuelto_en = now() where id = '$solicitudB'"))
        assertNull(c.sqlStateDe("update public.solicitudes_acceso_admin set correo_verificado_en = resuelto_en + interval '1 second' where id = '$solicitudB'"))
        assertEquals(0L, c.verify().getValue("no_clock_ordering_check"))
    }

    /** L1: una aprobacion se puede revertir a `pendiente` (la tabla exige entonces la resolucion vacia). */
    @Test
    fun unaAprobacionSePuedeRevertirAPendiente(): Unit = escenario { c ->
        c.insertarPendiente(solicitudA, USER_A)
        c.ejecutar(
            "update public.solicitudes_acceso_admin set correo_verificado_en = now(), estado = 'aprobada', resuelto_en = now(), " +
                "resuelto_por = '$USER_B' where id = '$solicitudA'",
        )
        assertNull(
            c.sqlStateDe(
                "update public.solicitudes_acceso_admin set estado = 'pendiente', resuelto_en = null, resuelto_por = null, " +
                    "motivo_resolucion = null where id = '$solicitudA' and estado = 'aprobada' and resuelto_por = '$USER_B'",
            ),
        )
        assertEquals("pendiente", c.unico("select estado from public.solicitudes_acceso_admin where id = '$solicitudA'"))
    }

    @Test
    fun losClientesSiguenSinPoderLeerNiEscribirLasColumnas(): Unit = escenario { c ->
        c.insertarPendiente(solicitudA, USER_A)
        for (rol in listOf("anon", "authenticated")) {
            c.ejecutar("set role $rol")
            try {
                for (columna in listOf("correo_verificado_en", "intentos_verificacion")) {
                    assertEquals("42501", c.sqlStateDe("select $columna from public.solicitudes_acceso_admin"), "$rol $columna")
                }
                assertEquals("42501", c.sqlStateDe("update public.solicitudes_acceso_admin set correo_verificado_en = now()"), rol)
                assertEquals("42501", c.sqlStateDe("update public.solicitudes_acceso_admin set intentos_verificacion = 0"), rol)
            } finally {
                c.ejecutar("reset role")
            }
        }
        c.ejecutar("set role service_role")
        try {
            assertNull(
                c.sqlStateDe(
                    "update public.solicitudes_acceso_admin set correo_verificado_en = now(), intentos_verificacion = 2 where id = '$solicitudA'",
                ),
            )
        } finally {
            c.ejecutar("reset role")
        }
        assertEquals("1", c.unico("select count(*)::text from public.solicitudes_acceso_admin where correo_verificado_en is not null"))
    }

    /** Mutacion en memoria: la migracion sin el CHECK del contador. El VERIFY lo detecta y la fila imposible entra. */
    @Test
    fun sinElCheckElVerifyLoDetecta(): Unit = PostgresHarness.escenario { c ->
        c.aplicar(tabla)
        val original = Slice0Db.leer(migracion)
        val mutada = original.replace(
            Regex("alter table public\\.solicitudes_acceso_admin\\s+add constraint $check[\\s\\S]*?;"),
            "",
        )
        check(mutada != original) { "La mutacion no cambio nada: el test no probaria nada." }
        c.ejecutar(mutada)
        c.insertarPendiente(solicitudA, USER_A)
        assertNull(c.sqlStateDe("update public.solicitudes_acceso_admin set intentos_verificacion = -1 where id = '$solicitudA'"))
        val v = c.verify()
        assertEquals(1L, v.getValue("attempts_check_present_and_validated"), v.toString())
        assertEquals(1L, v.getValue("rows_with_negative_attempts"), v.toString())
    }

    // ------------------------------------------------------------------------------------------------ revision #3 L1

    /**
     * Las tablas de GoTrue que toca `admin_purge_user_sessions`, con la forma de GoTrue (el repositorio no trae el esquema
     * `auth`): `sessions.user_id` -> `auth.users` en cascada; `refresh_tokens.user_id` es varchar y `session_id` -> `sessions`
     * en cascada (migracion de GoTrue 20220811173540), nula en los tokens anteriores a esa columna.
     */
    private fun Connection.tablasDeSesionDeGoTrue() = ejecutar(
        """
        create table auth.sessions (
          id uuid primary key,
          user_id uuid not null references auth.users(id) on delete cascade,
          created_at timestamptz default now()
        );
        create table auth.refresh_tokens (
          id bigserial primary key,
          token varchar(255),
          user_id varchar(255),
          revoked boolean default false,
          session_id uuid references auth.sessions(id) on delete cascade
        );
        """.trimIndent(),
    )

    private fun Connection.sembrarSesiones() {
        ejecutar(
            "insert into auth.sessions (id, user_id) values " +
                "('5e5e5e5e-0000-4000-8000-0000000000a1', '$USER_A'), ('5e5e5e5e-0000-4000-8000-0000000000a2', '$USER_A'), " +
                "('5e5e5e5e-0000-4000-8000-0000000000b1', '$USER_B')",
        )
        ejecutar(
            "insert into auth.refresh_tokens (token, user_id, session_id) values " +
                "('ra1', '$USER_A', '5e5e5e5e-0000-4000-8000-0000000000a1'), ('ra2', '$USER_A', '5e5e5e5e-0000-4000-8000-0000000000a2'), " +
                // Token ANTERIOR a `session_id` (nulo): no cae en cascada, la funcion lo borra por usuario.
                "('ra-legacy', '$USER_A', null), " +
                "('rb1', '$USER_B', '5e5e5e5e-0000-4000-8000-0000000000b1')",
        )
    }

    private fun Connection.cuenta(sql: String): String? = unico("select count(*)::text from $sql")

    @Test
    fun laPurgaBorraTodasLasSesionesYRefreshTokensDeLaCuentaYSoloDeElla(): Unit =
        escenario(antes = { it.tablasDeSesionDeGoTrue() }) { c ->
            c.sembrarSesiones()
            c.ejecutar("set role service_role")
            val borradas = try {
                c.unico("select public.admin_purge_user_sessions('$USER_A')")
            } finally {
                c.ejecutar("reset role")
            }
            assertEquals("2", borradas)
            assertEquals("0", c.cuenta("auth.sessions where user_id = '$USER_A'"))
            assertEquals("0", c.cuenta("auth.refresh_tokens where user_id = '$USER_A'"), "tambien el token sin session_id")
            assertEquals("1", c.cuenta("auth.sessions where user_id = '$USER_B'"))
            assertEquals("1", c.cuenta("auth.refresh_tokens where user_id = '$USER_B'"))
            // Idempotente: sin sesiones devuelve 0.
            c.ejecutar("set role service_role")
            try {
                assertEquals("0", c.unico("select public.admin_purge_user_sessions('$USER_A')"))
                assertEquals("22004", c.sqlStateDe("select public.admin_purge_user_sessions(null)"))
            } finally {
                c.ejecutar("reset role")
            }
        }

    @Test
    fun soloServiceRolePuedeEjecutarLaPurgaYElVerifyLoConfirma(): Unit =
        escenario(antes = { it.tablasDeSesionDeGoTrue() }) { c ->
            c.sembrarSesiones()
            for (rol in listOf("anon", "authenticated")) {
                c.ejecutar("set role $rol")
                try {
                    assertEquals("42501", c.sqlStateDe("select public.admin_purge_user_sessions('$USER_A')"), rol)
                } finally {
                    c.ejecutar("reset role")
                }
            }
            assertEquals("2", c.cuenta("auth.sessions where user_id = '$USER_A'"), "nadie salvo service_role purga")
            assertEquals(
                "f",
                c.unico("select has_function_privilege('public', 'public.admin_purge_user_sessions(uuid)', 'execute')"),
            )
            val v = c.verify()
            assertEquals(0L, v.getValue("purge_fn_present_security_definer_empty_search_path"), v.toString())
            assertEquals(0L, v.getValue("purge_fn_executable_by_clients"), v.toString())
            assertEquals(0L, v.getValue("purge_fn_not_executable_by_service_role"), v.toString())
        }

    @Test
    fun antesDeLaMigracionLaPurgaNoExiste(): Unit = escenario(conMigracion = false) { c ->
        assertEquals("42883", c.sqlStateDe("select public.admin_purge_user_sessions('$USER_A')"))
    }

    /** Mutacion en memoria: la migracion sin el `revoke`. Los clientes heredan EXECUTE de PUBLIC y el VERIFY lo detecta. */
    @Test
    fun sinElRevokeLosClientesPurganYElVerifyLoDetecta(): Unit = PostgresHarness.escenario { c ->
        c.aplicar(tabla)
        c.tablasDeSesionDeGoTrue()
        val original = Slice0Db.leer(migracion)
        val mutada = original.replace(
            Regex("revoke all on function public\\.admin_purge_user_sessions\\(uuid\\)[^;]*;"),
            "",
        )
        check(mutada != original) { "La mutacion no cambio nada: el test no probaria nada." }
        c.ejecutar(mutada)
        c.sembrarSesiones()
        c.ejecutar("set role anon")
        try {
            assertNull(c.sqlStateDe("select public.admin_purge_user_sessions('$USER_A')"), "sin el revoke, anon purga")
        } finally {
            c.ejecutar("reset role")
        }
        assertEquals("0", c.cuenta("auth.sessions where user_id = '$USER_A'"))
        assertEquals(1L, c.verify().getValue("purge_fn_executable_by_clients"))
    }

    /** Mutacion en memoria: la funcion sin el borrado explicito de refresh tokens. El token sin `session_id` sobrevive. */
    @Test
    fun sinElBorradoExplicitoElRefreshTokenAntiguoSobrevive(): Unit = PostgresHarness.escenario { c ->
        c.aplicar(tabla)
        c.tablasDeSesionDeGoTrue()
        val original = Slice0Db.leer(migracion)
        val mutada = original.replace(Regex("delete from auth\\.refresh_tokens [^;]*;"), "")
        check(mutada != original) { "La mutacion no cambio nada: el test no probaria nada." }
        c.ejecutar(mutada)
        c.sembrarSesiones()
        c.ejecutar("set role service_role")
        try {
            c.unico("select public.admin_purge_user_sessions('$USER_A')")
        } finally {
            c.ejecutar("reset role")
        }
        assertEquals("0", c.cuenta("auth.sessions where user_id = '$USER_A'"))
        assertEquals("1", c.cuenta("auth.refresh_tokens where user_id = '$USER_A'"), "solo cae en cascada lo que tiene session_id")
    }
}
