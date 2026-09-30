package com.oposicontrol.dbtest.slice1

import com.oposicontrol.dbtest.SupabaseFixture.USER_A
import com.oposicontrol.dbtest.SupabaseFixture.USER_B
import com.oposicontrol.dbtest.SupabaseFixture.USER_C
import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.aplicar
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.slice0.sqlStateDe
import com.oposicontrol.dbtest.slice1.Slice1Db.COM1
import com.oposicontrol.dbtest.slice1.Slice1Db.ESP1
import com.oposicontrol.dbtest.slice1.Slice1Db.ESP2
import com.oposicontrol.dbtest.slice1.Slice1Db.ESP_OFF
import com.oposicontrol.dbtest.slice1.Slice1Db.NOEXISTE
import com.oposicontrol.dbtest.slice1.Slice1Db.USER_D
import com.oposicontrol.dbtest.slice1.Slice1Db.estadoComo
import com.oposicontrol.dbtest.slice1.Slice1Db.n
import com.oposicontrol.dbtest.slice1.Slice1Db.sembrar
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Migracion 1 (guards): columnas server-owned, respaldo DEC-002 de especialidad/comunidad para cualquier
 * escritor, unicidad de username sin mayusculas (con reporte de colisiones), handle_new_auth_user con
 * cuentas no activas, correccion del backfill de onboarding (R2 RA-1-01), VERIFY, reaplicar y rollback.
 */
class Slice1SpecialtyMigrationTest {

    private fun <T> escenario(bloque: (Connection) -> T): T = Slice1Db.escenario { c ->
        c.sembrar()
        bloque(c)
    }

    private fun Connection.verifyGuards(): Map<String, Long> =
        filas(Slice0Db.verifyDe(Slice1Db.GUARDS)).associate { it[0]!! to it[1]!!.toLong() }

    private fun Connection.verifyVerde() {
        val f = verifyGuards()
        assertEquals(7, f.size, f.toString())
        assertTrue(f.values.all { it == 0L }, "VERIFY de guards no esta verde: $f")
    }

    // ---------------------------------------------------------------- columnas server-owned

    @Test
    fun clientesNoEscribenColumnasDelServidorNiPorUpdateNiPorInsert(): Unit = escenario { c ->
        val cambios = listOf(
            "onboarding_completado = true",
            "es_invitado = true",
            "especialidad_principal_id = '$ESP1'",
            "comunidad_principal_id = '$COM1'",
            "username = 'hacker'",
            "perfil_intro_visto_en = now()",
            "estado_cuenta = 'bloqueada'",
        )
        for (rol in listOf("authenticated", "anon")) {
            for (cambio in cambios) {
                val st = c.estadoComo(rol, "update public.perfiles_usuario set $cambio where usuario_id = '$USER_A'", USER_A)
                // anon no ve filas (RLS) -> 0 filas, sin error; authenticated recibe el 42501 del guard.
                if (rol == "authenticated") assertEquals("42501", st, "$rol $cambio")
            }
        }
        assertEquals(
            listOf(null, "false", "false", null, null, "activa", null),
            c.filas("select username, onboarding_completado::text, es_invitado::text, especialidad_principal_id::text, comunidad_principal_id::text, estado_cuenta, perfil_intro_visto_en::text from public.perfiles_usuario where usuario_id = '$USER_A'").single(),
        )
        val mensaje = try {
            c.ejecutar("set role authenticated; select set_config('request.jwt.claim.sub', '$USER_A', false); update public.perfiles_usuario set username = 'x1' where usuario_id = '$USER_A'")
            null
        } catch (e: java.sql.SQLException) {
            e.message
        } finally {
            c.ejecutar("reset role")
        }
        assertTrue(mensaje?.contains("PROFILE_FIELD_NOT_CLIENT_WRITABLE") == true, "mensaje: $mensaje")

        // Un cliente si puede seguir editando los campos suyos (nombre visible, nombre, apellidos, avatar).
        assertNull(c.estadoComo("authenticated", "update public.perfiles_usuario set nombre_mostrar = 'Ana', nombre = 'Ana', apellidos = 'G', avatar_url = 'x' where usuario_id = '$USER_A'", USER_A))
        // INSERT propio (politica insert_propio) con campos server-owned tambien queda cerrado.
        c.ejecutar("delete from public.perfiles_usuario where usuario_id = '$USER_D'")
        for (valor in listOf("username", "onboarding_completado", "es_invitado", "especialidad_principal_id", "comunidad_principal_id", "perfil_intro_visto_en")) {
            val v = when (valor) {
                "onboarding_completado", "es_invitado" -> "true"
                "especialidad_principal_id" -> "'$ESP1'"
                "comunidad_principal_id" -> "'$COM1'"
                "perfil_intro_visto_en" -> "now()"
                else -> "'zz_user'"
            }
            assertEquals("42501", c.estadoComo("authenticated", "insert into public.perfiles_usuario (usuario_id, $valor) values ('$USER_D', $v)", USER_D), valor)
        }
        assertNull(c.estadoComo("authenticated", "insert into public.perfiles_usuario (usuario_id, nombre_mostrar) values ('$USER_D', 'Dora')", USER_D))
        // El servidor (service_role) si escribe cualquiera de ellos.
        assertNull(c.estadoComo("service_role", "update public.perfiles_usuario set username = 'srv', onboarding_completado = true, es_invitado = true, especialidad_principal_id = '$ESP1', comunidad_principal_id = '$COM1', perfil_intro_visto_en = now() where usuario_id = '$USER_A'"))
    }

    @Test
    fun adminNoEscribeColumnasDelServidorPorPostgrest(): Unit = escenario { c ->
        // Los roles no son columnas de perfil; el cambio de roles sigue siendo solo por RLS admin. Un admin
        // autenticado tampoco puede saltarse el guard de columnas server-owned de OTRO perfil.
        c.ejecutar("insert into public.roles_usuario (usuario_id, rol_id) values ('$USER_B', '22222222-2222-2222-2222-222222222222')")
        assertEquals("42501", c.estadoComo("authenticated", "update public.perfiles_usuario set onboarding_completado = true where usuario_id = '$USER_A'", USER_B))
    }

    // ---------------------------------------------------------------- DEC-002 respaldo

    @Test
    fun especialidadYComunidadDebenExistirYEspecialidadEstarActivaParaCualquierEscritor(): Unit = escenario { c ->
        fun escribir(sql: String) = c.estadoComo("service_role", sql)
        assertNull(escribir("update public.perfiles_usuario set especialidad_principal_id = '$ESP1' where usuario_id = '$USER_A'"))
        assertNull(escribir("update public.perfiles_usuario set especialidad_principal_id = '$ESP2' where usuario_id = '$USER_A'"))
        for (malo in listOf(ESP_OFF, NOEXISTE)) {
            assertEquals("22023", escribir("update public.perfiles_usuario set especialidad_principal_id = '$malo' where usuario_id = '$USER_A'"), malo)
        }
        assertEquals("22023", escribir("update public.perfiles_usuario set comunidad_principal_id = '$NOEXISTE' where usuario_id = '$USER_A'"))
        assertEquals(ESP2, c.unico("select especialidad_principal_id::text from public.perfiles_usuario where usuario_id = '$USER_A'"))
        // INSERT: tambien validado.
        c.ejecutar("delete from public.perfiles_usuario where usuario_id = '$USER_D'")
        assertEquals("22023", escribir("insert into public.perfiles_usuario (usuario_id, especialidad_principal_id) values ('$USER_D', '$ESP_OFF')"))
        assertNull(escribir("insert into public.perfiles_usuario (usuario_id, especialidad_principal_id) values ('$USER_D', '$ESP1')"))
        // Mensaje estable.
        val msg = try {
            c.ejecutar("update public.perfiles_usuario set especialidad_principal_id = '$ESP_OFF' where usuario_id = '$USER_A'")
            null
        } catch (e: java.sql.SQLException) {
            e.message
        }
        assertTrue(msg?.contains("UNKNOWN_SPECIALTY") == true, msg)
    }

    @Test
    fun desactivarOBorrarUnaEspecialidadNoRompeAlosPerfilesYNoTocaElHistorial(): Unit = escenario { c ->
        c.ejecutar(
            """
            create table public.intentos_examen (id serial primary key, usuario_id uuid, especialidad_id uuid);
            insert into public.intentos_examen (usuario_id, especialidad_id) values ('$USER_A', '$ESP1');
            update public.perfiles_usuario set especialidad_principal_id = '$ESP1' where usuario_id in ('$USER_A', '$USER_B');
            """.trimIndent(),
        )
        // Desactivar: el perfil conserva el valor y se puede seguir editando lo demas.
        c.ejecutar("update public.especialidades set activa = false where id = '$ESP1'")
        assertNull(c.estadoComo("service_role", "update public.perfiles_usuario set nombre = 'Ana' where usuario_id = '$USER_A'"))
        assertEquals(ESP1, c.unico("select especialidad_principal_id::text from public.perfiles_usuario where usuario_id = '$USER_A'"))
        // Pero no se puede ELEGIR una inactiva (ni re-asignar la misma tras cambiar).
        assertEquals("22023", c.estadoComo("service_role", "update public.perfiles_usuario set especialidad_principal_id = '$ESP_OFF' where usuario_id = '$USER_A'"))
        // Borrar la especialidad: FK ON DELETE SET NULL (update interno con NULL) no dispara el backstop.
        c.ejecutar("delete from public.especialidades where id = '$ESP1'")
        assertNull(c.unico("select especialidad_principal_id::text from public.perfiles_usuario where usuario_id = '$USER_A'"))
        assertEquals(ESP1, c.unico("select especialidad_id::text from public.intentos_examen"), "el historial conserva la especialidad de su examen")
    }

    // ---------------------------------------------------------------- username

    @Test
    fun usernameEsUnicoSinDistinguirMayusculas(): Unit = escenario { c ->
        assertEquals("1", c.unico("select count(*) from pg_indexes where indexname = 'perfiles_usuario_username_lower_uq'"))
        assertNull(c.estadoComo("service_role", "update public.perfiles_usuario set username = 'Maria' where usuario_id = '$USER_A'"))
        assertEquals("23505", c.estadoComo("service_role", "update public.perfiles_usuario set username = 'maria' where usuario_id = '$USER_B'"))
        assertEquals("23505", c.estadoComo("service_role", "update public.perfiles_usuario set username = 'MARIA' where usuario_id = '$USER_B'"))
        // Varios NULL siguen permitidos (indice parcial).
        assertEquals(null, c.unico("select username from public.perfiles_usuario where usuario_id = '$USER_B'"))
        assertNull(c.estadoComo("service_role", "update public.perfiles_usuario set username = null where usuario_id = '$USER_C'"))
        assertNull(c.estadoComo("service_role", "update public.perfiles_usuario set username = 'Maria' where usuario_id = '$USER_A'"))
    }

    @Test
    fun colisionesDeUsernamePreexistentesSeReportanSinFallarLaMigracion(): Unit = Slice1Db.escenario(migraciones = false) { c ->
        c.sembrar()
        c.ejecutar("update public.perfiles_usuario set username = 'Bob' where usuario_id = '$USER_A'")
        c.ejecutar("update public.perfiles_usuario set username = 'bob' where usuario_id = '$USER_B'")
        c.aplicar(Slice1Db.GUARDS) // no falla
        assertEquals("0", c.unico("select count(*) from pg_indexes where indexname = 'perfiles_usuario_username_lower_uq'"))
        val f = c.verifyGuards()
        assertEquals(1L, f["username_lower_collisions"], f.toString())
        assertEquals(1L, f["username_lower_index_missing"], f.toString())
        // Resuelta la colision, re-aplicar crea el indice.
        c.ejecutar("update public.perfiles_usuario set username = 'bob2' where usuario_id = '$USER_B'")
        c.aplicar(Slice1Db.GUARDS)
        assertEquals("1", c.unico("select count(*) from pg_indexes where indexname = 'perfiles_usuario_username_lower_uq'"))
        c.verifyVerde()
    }

    // ---------------------------------------------------------------- handle_new_auth_user

    @Test
    fun handleNewAuthUserNoReescribeCuentasNoActivas(): Unit = escenario { c ->
        // Estado anonimizado/pendiente: nombre_mostrar, username y avatar borrados por el servidor.
        c.ejecutar("update public.perfiles_usuario set nombre_mostrar = null, username = null, avatar_url = null where usuario_id in ('$USER_B', '$USER_C', '$USER_A')")
        // Un cambio de email/metadata en auth.users (p. ej. scrub a tombstone) dispara el trigger.
        c.ejecutar("update auth.users set email = 'tombstone-' || substr(id::text, 1, 4) || '@invalid.local', raw_user_meta_data = '{\"username\":\"volvio\",\"nombre\":\"Vuelve\",\"avatar_url\":\"http://x/a.png\"}' where id in ('$USER_A', '$USER_B', '$USER_C')")
        for (uid in listOf(USER_B, USER_C)) {
            assertEquals(
                listOf(null, null, null),
                c.filas("select nombre_mostrar, username, avatar_url from public.perfiles_usuario where usuario_id = '$uid'").single(),
                "no activa no se repuebla: $uid",
            )
        }
        // Activa: coalesce sobre NULL para nombre_mostrar/avatar. CAMBIO DELIBERADO (audit MASTER-DB-001,
        // migracion 20260930100500): el username de raw_user_meta_data (controlable por el cliente via
        // GoTrue) ya NO se copia; antes este test afirmaba "volvio", que era el defecto.
        assertEquals(
            listOf("Vuelve", null, "http://x/a.png"),
            c.filas("select nombre_mostrar, username, avatar_url from public.perfiles_usuario where usuario_id = '$USER_A'").single(),
        )
        // Alta nueva: crea perfil activa normalmente.
        c.ejecutar("insert into auth.users (id, email) values ('12345678-1234-1234-1234-123456789012', 'nuevo@test.local')")
        assertEquals("activa", c.unico("select estado_cuenta from public.perfiles_usuario where usuario_id = '12345678-1234-1234-1234-123456789012'"))
    }

    // ---------------------------------------------------------------- backfill onboarding (R2 RA-1-01)

    @Test
    fun onboardingSoloSeMarcaConUsernameNombreYApellidosDesdePerfilCompleto(): Unit = Slice1Db.escenario(migraciones = false) { c ->
        c.ejecutar(
            """
            insert into auth.users (id, email) values ('$USER_D', 'd@t.l');
            -- D: onboarding nativo previo sin fila legacy (nunca se toca).
            update public.perfiles_usuario set onboarding_completado = true, username = 'nativo', nombre = 'N', apellidos = 'M' where usuario_id = '$USER_D';
            insert into public.usuario (auth_user_id, email, nombre, apellidos, username, perfil_completo) values
              ('$USER_A', 'a@t.l', 'Ana', 'Garcia', 'ana_g', true),
              ('$USER_B', 'b@t.l', 'Bea', null, 'bea', true),
              ('$USER_C', 'c@t.l', 'Carlos', null, null, true);
            """.trimIndent(),
        )
        c.aplicar(Slice0Db.IDENTITY) // regla ANTIGUA: marca a los tres legacy
        fun onb() = c.filas("select usuario_id::text, onboarding_completado::text from public.perfiles_usuario order by usuario_id").associate { it[0]!! to it[1]!! }
        assertEquals("true", onb()[USER_B])
        assertEquals("true", onb()[USER_C])

        c.aplicar(Slice1Db.GUARDS) // delta con la regla correcta
        assertEquals(mapOf(USER_A to "true", USER_B to "false", USER_C to "false", USER_D to "true"), onb())
        // El informe deja de afirmar 'filled' para las revertidas.
        assertEquals(0L, c.n("select count(*) from private.identity_backfill_report where field = 'onboarding_completado' and usuario_id in ('$USER_B','$USER_C')"))
        c.verifyVerde()

        // Idempotente y re-ejecutable: nada cambia.
        val antes = c.filas("select * from public.perfiles_usuario order by usuario_id")
        c.aplicar(Slice1Db.GUARDS)
        assertEquals(antes, c.filas("select * from public.perfiles_usuario order by usuario_id"))

        // Cuando el perfil se completa (nombre/apellidos/username presentes), el delta lo marca.
        c.ejecutar("update public.perfiles_usuario set apellidos = 'Lopez' where usuario_id = '$USER_B'")
        c.aplicar(Slice1Db.GUARDS)
        assertEquals("true", onb()[USER_B])
        assertEquals("false", onb()[USER_C], "C sigue sin username/apellidos")
        c.verifyVerde()
    }

    // ---------------------------------------------------------------- VERIFY / reaplicar / rollback

    @Test
    fun verifyReaplicarYRollbackDeLosGuards(): Unit = escenario { c ->
        c.verifyVerde()
        c.aplicar(Slice1Db.GUARDS)
        c.verifyVerde()

        c.ejecutar(Slice0Db.rollbackDe(Slice1Db.GUARDS))
        assertEquals("0", c.unico("select count(*) from pg_trigger where tgname in ('trg_perfiles_usuario_server_owned_guard','trg_perfiles_usuario_validar_referencias')"))
        assertEquals("0", c.unico("select count(*) from pg_indexes where indexname = 'perfiles_usuario_username_lower_uq'"))
        assertEquals("0", c.unico("select count(*) from pg_proc where prosrc like '%estado_cuenta = ''activa''%' and proname = 'handle_new_auth_user'"))
        // Sin guards, un cliente vuelve a poder (estado anterior de MAIN): el rollback es real.
        assertNull(c.estadoComo("authenticated", "update public.perfiles_usuario set username = 'x9' where usuario_id = '$USER_A'", USER_A))
        // El guard de estado_cuenta de Slice 0 sigue.
        assertEquals("42501", c.estadoComo("authenticated", "update public.perfiles_usuario set estado_cuenta = 'bloqueada' where usuario_id = '$USER_A'", USER_A))
        c.ejecutar(Slice0Db.rollbackDe(Slice1Db.GUARDS))
        c.aplicar(Slice1Db.GUARDS)
        c.verifyVerde()
    }
}
