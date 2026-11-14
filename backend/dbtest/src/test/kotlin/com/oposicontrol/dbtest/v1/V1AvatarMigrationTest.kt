package com.oposicontrol.dbtest.v1

import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `20261108100000_v1_avatar` (owner 2026-11-08, FA42): app_set_avatar / app_clear_avatar and the avatar of OTHER users in the ranking top 3 and
 * the forum authors. The one rule (private.avatar_visible_path): only the server-owned object `avatars/{uid}/{sha256}.jpg` of an ACTIVE account
 * travels; an OAuth URL copied by handle_new_auth_user never does. Full V1 schema; negative control without the migration.
 */
class V1AvatarMigrationTest {
    private val migration = "20261108100000_v1_avatar"
    private fun u(n: Int) = "a0000000-0000-0000-0000-0000000a7a${"%02d".format(n)}"
    private val esp = "e1000000-0000-0000-0000-0000000a7a01"
    private val liga = "1e910000-0000-0000-0000-0000000a7a01"
    private val cat = "c0000000-0000-0000-0000-0000000a7a01"
    private fun path(n: Int, c: Char = 'a') = "avatars/${u(n)}/${c.toString().repeat(64)}.jpg"
    private val oauth = "https://lh3.googleusercontent.com/a/photo.jpg"

    private val seed = """
        insert into auth.users (id, email, aud, role) select ('a0000000-0000-0000-0000-0000000a7a' || lpad(n::text, 2, '0'))::uuid, 'av' || n || '@test.local', 'authenticated', 'authenticated' from generate_series(1, 7) n;
        insert into public.especialidades (id, codigo, nombre, slug, activa) values ('$esp', 'AV', 'AV', 'av', true);
        insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id)
          select ('a0000000-0000-0000-0000-0000000a7a' || lpad(n::text, 2, '0'))::uuid, 'av' || n, 'av_user' || n, true, 'activa', '$esp' from generate_series(1, 7) n
          on conflict (usuario_id) do update set username = excluded.username, estado_cuenta = 'activa', especialidad_principal_id = excluded.especialidad_principal_id;
    """.trimIndent()

    private fun Connection.errorOf(sql: String): String? = try {
        ejecutar(sql); null
    } catch (e: SQLException) {
        (e as? org.postgresql.util.PSQLException)?.serverErrorMessage?.message ?: e.message
    }

    private fun Connection.set(n: Int, p: String) = unico("select public.app_set_avatar('${u(n)}', '$p')::text")!!
    private fun Connection.stored(n: Int) = unico("select avatar_url from public.perfiles_usuario where usuario_id = '${u(n)}'")

    @Test
    fun setIsIdempotentReturnsOnlyAnOwnPreviousObjectAndRefusesAnyOtherPath() = V1Db.escenario { c ->
        c.ejecutar(seed)
        // an OAuth URL stored at signup is replaced, but never returned as something to delete
        c.ejecutar("update public.perfiles_usuario set avatar_url = '$oauth' where usuario_id = '${u(1)}'")
        assertEquals("""{"changed": true, "avatarPath": "${path(1)}", "previousPath": null}""", c.set(1, path(1)))
        assertEquals(path(1), c.stored(1))
        assertEquals("""{"changed": false, "avatarPath": "${path(1)}", "previousPath": null}""", c.set(1, path(1)), "same bytes = same path = no write")
        assertEquals("""{"changed": true, "avatarPath": "${path(1, 'b')}", "previousPath": "${path(1)}"}""", c.set(1, path(1, 'b')))
        // only the caller's own server-owned object is accepted
        listOf(path(2), oauth, "avatars/${u(1)}/${"a".repeat(64)}.png", "avatars/${u(1)}/../x.jpg", "avatars/${u(1)}/${"A".repeat(64)}.jpg").forEach { bad ->
            assertEquals("INVALID_ARGUMENT", c.errorOf("select public.app_set_avatar('${u(1)}', '$bad')"), bad)
        }
        assertEquals(path(1, 'b'), c.stored(1), "nothing written by a refused call")
        // account state: same errors as every profile command
        c.ejecutar("update public.perfiles_usuario set estado_cuenta = 'bloqueada' where usuario_id = '${u(2)}'")
        assertEquals("ACCOUNT_BLOCKED", c.errorOf("select public.app_set_avatar('${u(2)}', '${path(2)}')"))
        assertEquals("ACCOUNT_BLOCKED", c.errorOf("select public.app_clear_avatar('${u(2)}')"))
        assertEquals("PROFILE_MISSING", c.errorOf("select public.app_set_avatar('a0000000-0000-0000-0000-00000000ffff', '${"avatars/a0000000-0000-0000-0000-00000000ffff/" + "a".repeat(64) + ".jpg"}')"))
    }

    @Test
    fun clearIsIdempotentAndNeverHandsOutAForeignValue() = V1Db.escenario { c ->
        c.ejecutar(seed)
        c.set(1, path(1))
        assertEquals("""{"changed": true, "previousPath": "${path(1)}"}""", c.unico("select public.app_clear_avatar('${u(1)}')::text"))
        assertNull(c.stored(1))
        assertEquals("""{"changed": false, "previousPath": null}""", c.unico("select public.app_clear_avatar('${u(1)}')::text"))
        c.ejecutar("update public.perfiles_usuario set avatar_url = '$oauth' where usuario_id = '${u(3)}'")
        assertEquals("""{"changed": true, "previousPath": null}""", c.unico("select public.app_clear_avatar('${u(3)}')::text"), "an OAuth URL is cleared, never returned")
        assertNull(c.stored(3))
    }

    @Test
    fun theForumAuthorCarriesTheAvatarOnlyForAnActiveAccountWithAServerOwnedObject() = V1Db.escenario { c ->
        c.ejecutar(seed)
        c.ejecutar(
            """
            insert into public.categorias_oposired (id, nombre, slug, especialidad_id, solo_lectura) values ('$cat', 'AV', 'av-general', null, false);
            insert into public.publicaciones_oposired (id, usuario_id, categoria_id, titulo, contenido, estado, creado_en) values
              ('0a7a0000-0000-0000-0000-000000000001', '${u(1)}', '$cat', 'T1', 'cuerpo uno', 'publicada', now() - interval '3 minutes'),
              ('0a7a0000-0000-0000-0000-000000000002', '${u(2)}', '$cat', 'T2', 'cuerpo dos', 'publicada', now() - interval '2 minutes'),
              ('0a7a0000-0000-0000-0000-000000000003', '${u(3)}', '$cat', 'T3', 'cuerpo tres', 'publicada', now() - interval '1 minute');
            insert into public.comentarios_oposired (publicacion_id, usuario_id, contenido, estado) values ('0a7a0000-0000-0000-0000-000000000001', '${u(1)}', 'comentario', 'publicado');
            """.trimIndent(),
        )
        c.set(1, path(1))
        c.set(3, path(3))
        c.ejecutar("update public.perfiles_usuario set avatar_url = '$oauth' where usuario_id = '${u(2)}'")
        c.ejecutar("update public.perfiles_usuario set estado_cuenta = 'bloqueada' where usuario_id = '${u(3)}'")   // blocked AFTER setting the avatar

        val authors = c.filas(
            "select i -> 'autor' ->> 'handle', coalesce(i -> 'autor' ->> 'avatarPath', '-'), (i -> 'autor') ? 'avatarPath' " +
                "from jsonb_array_elements(public.app_foro_list('${u(4)}', null, 'recientes', null, 10) -> 'items') i order by 1",
        )
        assertEquals(
            listOf(listOf("av_user1", path(1), "t"), listOf("av_user2", "-", "t"), listOf("av_user3", "-", "t")),
            authors,
            "own object served; OAuth URL and blocked account: null",
        )
        assertEquals(path(1), c.unico("select public.app_foro_post('${u(4)}', '0a7a0000-0000-0000-0000-000000000001') -> 'autor' ->> 'avatarPath'"))
        assertEquals(path(1), c.unico("select public.app_foro_comments('${u(4)}', '0a7a0000-0000-0000-0000-000000000001', null, 10) -> 'items' -> 0 -> 'autor' ->> 'avatarPath'"))
        assertFalse(c.unico("select public.app_foro_list('${u(4)}', null, 'recientes', null, 10)::text")!!.contains("googleusercontent"))
        // the forum gate is unchanged: a hidden post is still FORO_NOT_FOUND, with or without avatar
        c.ejecutar("update public.publicaciones_oposired set estado = 'oculta' where id = '0a7a0000-0000-0000-0000-000000000001'")
        assertEquals("FORO_NOT_FOUND", c.errorOf("select public.app_foro_post('${u(4)}', '0a7a0000-0000-0000-0000-000000000001')"))
        // tombstone: author deleted -> handle null, avatarPath null
        c.ejecutar("update public.publicaciones_oposired set usuario_id = null where id = '0a7a0000-0000-0000-0000-000000000002'")
        assertEquals("null|null", c.unico("select coalesce(p -> 'autor' ->> 'handle', 'null') || '|' || coalesce(p -> 'autor' ->> 'avatarPath', 'null') from public.app_foro_post('${u(4)}', '0a7a0000-0000-0000-0000-000000000002') p"))
    }

    @Test
    fun theRankingTop3CarriesTheAvatarOnlyWhileTheUserIsPublicAndActive() = V1Db.escenario { c ->
        c.ejecutar(seed)
        c.ejecutar(
            """
            insert into public.ligas_nivel (id, codigo, nombre, preguntas_minimas, ventana_calculo, posicion) values ('$liga', 'av-1', 'Liga AV', 0, 3, 1);
            insert into public.estado_competitivo_usuario (usuario_id, liga_nivel_id, preguntas_validas_ranking, ventana_calculo, nota_media_actual)
              select ('a0000000-0000-0000-0000-0000000a7a' || lpad(n::text, 2, '0'))::uuid, '$liga', 3, 3, (array[9.0, 8.0, 7.0, 6.0, 5.0, 4.0])[n] from generate_series(1, 6) n;
            update public.configuracion_v1 set valor_integer = 5 where clave = 'cohorte_minima_comparativa';
            update public.perfiles_usuario set ranking_publico = true where usuario_id in ('${u(1)}', '${u(2)}', '${u(3)}');
            """.trimIndent(),
        )
        c.set(1, path(1))
        c.set(2, path(2))
        c.set(5, path(5))   // u5 has an avatar but is not public: never listed, never served
        c.ejecutar("update public.perfiles_usuario set avatar_url = '$oauth' where usuario_id = '${u(3)}'")
        c.unico("select public.app_job_ranking(true)::text")
        fun top3() = c.filas(
            "select t ->> 'handle', coalesce(t ->> 'avatarPath', '-') from jsonb_array_elements(public.app_ranking_me('${u(4)}') -> 'top3') t order by (t ->> 'posicion')::int",
        )
        assertEquals(listOf(listOf("av_user1", path(1)), listOf("av_user2", path(2)), listOf("av_user3", "-")), top3())
        // a listed user who became private (the job has not run yet; the row is still there): the avatar stops at once
        c.ejecutar("update public.perfiles_usuario set ranking_publico = false where usuario_id = '${u(2)}'")
        assertEquals(listOf("av_user2", "-"), top3()[1])
        // a blocked account: no avatar
        c.ejecutar("update public.perfiles_usuario set estado_cuenta = 'bloqueada' where usuario_id = '${u(1)}'")
        assertEquals(listOf("av_user1", "-"), top3()[0])
        assertFalse(c.unico("select public.app_ranking_me('${u(4)}')::text")!!.contains(path(5)), "a private user's avatar never appears")
        // the rest of the payload is the 20261022101700 shape
        assertEquals("4|av-1", c.unico("select (r ->> 'posicion') || '|' || (r -> 'liga' ->> 'codigo') from public.app_ranking_me('${u(4)}') r"))
    }

    @Test
    fun theVerifyBlockProvesTheEndStateAndTheRollbackRestoresTheOldShapes() = V1Db.escenario { c ->
        c.ejecutar(seed)
        val verify = Slice0Db.verifyDe("supabase/migrations/$migration.sql")
        val rows = c.filas(verify)
        assertTrue(rows.size >= 12, "VERIFY rows: $rows")
        assertEquals(emptyList(), rows.filter { it[1] != "0" }, "every VERIFY counter is 0")
        assertEquals("false|false|true", c.unico(
            "select has_function_privilege('anon', 'public.app_set_avatar(uuid,text)', 'execute') || '|' || has_function_privilege('authenticated', 'public.app_clear_avatar(uuid)', 'execute') || '|' || has_function_privilege('service_role', 'public.app_set_avatar(uuid,text)', 'execute')",
        ))
        c.set(1, path(1))
        c.ejecutar(Slice0Db.rollbackDe("supabase/migrations/$migration.sql"))
        assertEquals("0", c.unico("select count(*) from pg_proc where proname in ('app_set_avatar', 'app_clear_avatar', 'avatar_visible_path')"))
        assertEquals(path(1), c.stored(1), "documented: the stored value stays")
        assertEquals("0", c.unico("select count(*) from pg_proc p where p.pronamespace = 'public'::regnamespace and p.proname in ('app_ranking_me', 'app_foro_list', 'app_foro_post', 'app_foro_comments') and p.prosrc like '%avatar%'"))
        assertEquals("4", c.unico("select count(*) from pg_proc p where p.pronamespace = 'public'::regnamespace and p.proname in ('app_ranking_me', 'app_foro_list', 'app_foro_post', 'app_foro_comments')"))
        // re-apply = same end state
        c.ejecutar(Slice0Db.leer("supabase/migrations/$migration.sql"))
        assertEquals(emptyList(), c.filas(verify).filter { it[1] != "0" })
    }

    @Test
    // 20261114100000_v1_avatar_preset (FA53) needs this one (preflight): it is left out too
    fun negativeControlWithoutTheMigrationThereIsNoAvatarCommandAndNoAvatarInTheReads() = V1Db.escenario(sin = setOf(migration, "20261114100000_v1_avatar_preset")) { c ->
        c.ejecutar(seed)
        assertEquals("0", c.unico("select count(*) from pg_proc where proname in ('app_set_avatar', 'app_clear_avatar', 'avatar_visible_path')"))
        assertEquals("0", c.unico("select count(*) from pg_proc p where p.proname = 'app_foro_list' and p.prosrc like '%avatar%'"))
    }
}
