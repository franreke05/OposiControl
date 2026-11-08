package com.oposicontrol.dbtest.v1

import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `20261108100200_v1_onboarding_display_name` (owner 2026-11-08): completing the onboarding stores the display name (nombre_mostrar) as the
 * username, instead of the e-mail prefix the auth trigger wrote. Same command otherwise. Full V1 schema; negative control without the migration.
 */
class V1OnboardingDisplayNameMigrationTest {
    private val migration = "20261108100200_v1_onboarding_display_name"
    private val ana = "a0000000-0000-0000-0000-0000000d1501"
    private val bea = "a0000000-0000-0000-0000-0000000d1502"
    private val esp = "e1000000-0000-0000-0000-0000000d1501"

    private val seed = """
        insert into auth.users (id, email, aud, role) values ('$ana', 'ana.lopez@test.local', 'authenticated', 'authenticated'), ('$bea', 'bea@test.local', 'authenticated', 'authenticated');
        insert into public.especialidades (id, codigo, nombre, slug, activa) values ('$esp', 'DN', 'DN', 'dn', true);
        insert into public.perfiles_usuario (usuario_id, nombre_mostrar, onboarding_completado, estado_cuenta)
          values ('$ana', 'ana.lopez', false, 'activa'), ('$bea', 'bea', false, 'activa')
          on conflict (usuario_id) do update set nombre_mostrar = excluded.nombre_mostrar, onboarding_completado = false, username = null, estado_cuenta = 'activa';
    """.trimIndent()

    private fun Connection.onboard(uid: String, username: String, nombre: String = "Ana"): String =
        unico("select public.app_completar_onboarding('$uid', '$username', '$nombre', 'Lopez', '$esp', '{}'::uuid[], null, null, null) ->> 'nombreMostrar'")!!

    private fun Connection.displayName(uid: String) = unico("select nombre_mostrar from public.perfiles_usuario where usuario_id = '$uid'")

    private fun Connection.errorOf(sql: String): String? = try {
        ejecutar(sql); null
    } catch (e: SQLException) {
        (e as? org.postgresql.util.PSQLException)?.serverErrorMessage?.message ?: e.message
    }

    @Test
    fun completingTheOnboardingStoresTheUsernameAsTheDisplayName() = V1Db.escenario { c ->
        c.ejecutar(seed)
        assertEquals("ana.lopez", c.displayName(ana), "before: the e-mail prefix of the auth trigger")
        assertEquals("Ana_Opos", c.onboard(ana, "Ana_Opos"), "the command answers the new display name")
        assertEquals("Ana_Opos", c.displayName(ana))
        // an identical replay writes nothing (a display name changed meanwhile stays)
        c.ejecutar("update public.perfiles_usuario set nombre_mostrar = 'otro' where usuario_id = '$ana'")
        assertEquals("otro", c.onboard(ana, "Ana_Opos"))
        // a refused onboarding writes nothing either
        assertEquals("USERNAME_TAKEN", c.errorOf("select public.app_completar_onboarding('$bea', 'ana_opos', 'Bea', 'Lopez', '$esp', '{}'::uuid[], null, null, null)"))
        assertEquals("bea", c.displayName(bea))
        assertEquals("ALREADY_ONBOARDED_CONFLICT", c.errorOf("select public.app_completar_onboarding('$ana', 'Ana_Otra', 'Ana', 'Lopez', '$esp', '{}'::uuid[], null, null, null)"))
        assertEquals("otro", c.displayName(ana))
    }

    @Test
    fun theVerifyBlockProvesTheEndStateAndTheRollbackRestoresTheOldCommand() = V1Db.escenario { c ->
        c.ejecutar(seed)
        val verify = Slice0Db.verifyDe("supabase/migrations/$migration.sql")
        val rows = c.filas(verify)
        assertTrue(rows.size >= 3, "VERIFY rows: $rows")
        assertEquals(emptyList(), rows.filter { it[1] != "0" }, "every VERIFY counter is 0")
        c.ejecutar(Slice0Db.rollbackDe("supabase/migrations/$migration.sql"))
        assertEquals(true, c.filas(verify).any { it[1] != "0" }, "VERIFY fails without the migration")
        assertEquals("bea", c.onboard(bea, "Bea_Opos", "Bea"), "the 20260930100500 command: nombre_mostrar untouched")
        assertEquals("false|true", c.unico(
            "select has_function_privilege('authenticated', 'public.app_completar_onboarding(uuid,text,text,text,uuid,uuid[],text,jsonb,text)', 'execute') || '|' || " +
                "has_function_privilege('service_role', 'public.app_completar_onboarding(uuid,text,text,text,uuid,uuid[],text,jsonb,text)', 'execute')",
        ))
    }

    @Test
    fun negativeControlWithoutTheMigrationTheDisplayNameStaysTheEmailPrefix() = V1Db.escenario(sin = setOf(migration)) { c ->
        c.ejecutar(seed)
        assertEquals("ana.lopez", c.onboard(ana, "Ana_Opos"))
    }
}
