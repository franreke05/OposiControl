package com.oposicontrol.dbtest.v1

import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `20261114100000_v1_avatar_preset` (owner 2026-11-14, FA53): perfiles_usuario.avatar_id 1..10, app_set_avatar_preset (stores the preset and clears
 * the photo, handing back only an own previous object), the photo commands clear the preset, and avatarId travels next to avatarPath in the ranking
 * top 3 and the forum authors under the same gates. Full V1 schema; VERIFY, ROLLBACK and a negative control without the migration.
 */
class V1AvatarPresetMigrationTest {
    private val migration = "20261114100000_v1_avatar_preset"
    private fun u(n: Int) = "a0000000-0000-0000-0000-0000000f29${"%02d".format(n)}"
    private val esp = "e1000000-0000-0000-0000-0000000f2901"
    private val liga = "1e910000-0000-0000-0000-0000000f2901"
    private val cat = "c0000000-0000-0000-0000-0000000f2901"
    private fun path(n: Int, c: Char = 'a') = "avatars/${u(n)}/${c.toString().repeat(64)}.jpg"
    private val oauth = "https://lh3.googleusercontent.com/a/photo.jpg"

    private val seed = """
        insert into auth.users (id, email, aud, role) select ('a0000000-0000-0000-0000-0000000f29' || lpad(n::text, 2, '0'))::uuid, 'f29-' || n || '@test.local', 'authenticated', 'authenticated' from generate_series(1, 6) n;
        insert into public.especialidades (id, codigo, nombre, slug, activa) values ('$esp', 'F29', 'F29', 'f29', true);
        insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id)
          select ('a0000000-0000-0000-0000-0000000f29' || lpad(n::text, 2, '0'))::uuid, 'f' || n, 'f29_user' || n, true, 'activa', '$esp' from generate_series(1, 6) n
          on conflict (usuario_id) do update set username = excluded.username, estado_cuenta = 'activa', especialidad_principal_id = excluded.especialidad_principal_id;
    """.trimIndent()

    private fun Connection.errorOf(sql: String): String? = try {
        ejecutar(sql); null
    } catch (e: SQLException) {
        (e as? org.postgresql.util.PSQLException)?.serverErrorMessage?.message ?: e.message
    }

    private fun Connection.preset(n: Int, id: Int) = unico("select public.app_set_avatar_preset('${u(n)}', $id::smallint)::text")!!
    private fun Connection.photo(n: Int, p: String) = unico("select public.app_set_avatar('${u(n)}', '$p')::text")!!
    private fun Connection.row(n: Int) = filas("select coalesce(avatar_id::text, '-'), coalesce(avatar_url, '-') from public.perfiles_usuario where usuario_id = '${u(n)}'").single()

    @Test
    fun aPresetIsStoredClearsThePhotoAndHandsBackOnlyAnOwnObject() = V1Db.escenario { c ->
        c.ejecutar(seed)
        c.photo(1, path(1))
        assertEquals("""{"changed": true, "avatarId": 3, "previousPath": "${path(1)}"}""", c.preset(1, 3), "the own photo is handed back to be deleted")
        assertEquals(listOf("3", "-"), c.row(1), "preset stored, photo cleared")
        assertEquals("""{"changed": false, "avatarId": 3, "previousPath": null}""", c.preset(1, 3), "same preset = no write")
        assertEquals("""{"changed": true, "avatarId": 10, "previousPath": null}""", c.preset(1, 10))
        // an OAuth URL copied at signup is cleared but never handed back
        c.ejecutar("update public.perfiles_usuario set avatar_url = '$oauth' where usuario_id = '${u(2)}'")
        assertEquals("""{"changed": true, "avatarId": 1, "previousPath": null}""", c.preset(2, 1))
        assertEquals(listOf("1", "-"), c.row(2))
        // range: the function and the CHECK
        listOf(0, 11, -1).forEach { bad -> assertEquals("INVALID_ARGUMENT", c.errorOf("select public.app_set_avatar_preset('${u(1)}', $bad::smallint)"), "$bad") }
        assertEquals("INVALID_ARGUMENT", c.errorOf("select public.app_set_avatar_preset('${u(1)}', null)"))
        assertTrue(c.errorOf("update public.perfiles_usuario set avatar_id = 11 where usuario_id = '${u(1)}'")!!.contains("perfiles_usuario_avatar_id_check"))
        assertEquals(listOf("10", "-"), c.row(1), "nothing written by a refused call")
        // account state: same errors as every profile command
        c.ejecutar("update public.perfiles_usuario set estado_cuenta = 'bloqueada' where usuario_id = '${u(3)}'")
        assertEquals("ACCOUNT_BLOCKED", c.errorOf("select public.app_set_avatar_preset('${u(3)}', 2::smallint)"))
        assertEquals("PROFILE_MISSING", c.errorOf("select public.app_set_avatar_preset('a0000000-0000-0000-0000-00000000ffff', 2::smallint)"))
    }

    @Test
    fun aPhotoReplacesThePresetAndClearingClearsBoth() = V1Db.escenario { c ->
        c.ejecutar(seed)
        c.preset(1, 4)
        assertEquals("""{"changed": true, "avatarPath": "${path(1)}", "previousPath": null}""", c.photo(1, path(1)))
        assertEquals(listOf("-", path(1)), c.row(1), "a profile holds a preset OR a photo")
        c.preset(1, 5)
        assertEquals("""{"changed": true, "previousPath": null}""", c.unico("select public.app_clear_avatar('${u(1)}')::text"), "the photo had been cleared by the preset")
        assertEquals(listOf("-", "-"), c.row(1))
        assertEquals("""{"changed": false, "previousPath": null}""", c.unico("select public.app_clear_avatar('${u(1)}')::text"), "idempotent")
    }

    @Test
    fun theForumAuthorCarriesTheAvatarIdOnlyForAnActiveAccount() = V1Db.escenario { c ->
        c.ejecutar(seed)
        c.ejecutar(
            """
            insert into public.categorias_oposired (id, nombre, slug, especialidad_id, solo_lectura) values ('$cat', 'F29', 'f29-general', null, false);
            insert into public.publicaciones_oposired (id, usuario_id, categoria_id, titulo, contenido, estado, creado_en) values
              ('0f290000-0000-0000-0000-000000000001', '${u(1)}', '$cat', 'T1', 'cuerpo uno', 'publicada', now() - interval '3 minutes'),
              ('0f290000-0000-0000-0000-000000000002', '${u(2)}', '$cat', 'T2', 'cuerpo dos', 'publicada', now() - interval '2 minutes'),
              ('0f290000-0000-0000-0000-000000000003', '${u(3)}', '$cat', 'T3', 'cuerpo tres', 'publicada', now() - interval '1 minute');
            insert into public.comentarios_oposired (publicacion_id, usuario_id, contenido, estado) values ('0f290000-0000-0000-0000-000000000001', '${u(1)}', 'comentario', 'publicado');
            """.trimIndent(),
        )
        c.preset(1, 7)
        c.photo(2, path(2))
        c.preset(3, 9)
        c.ejecutar("update public.perfiles_usuario set estado_cuenta = 'bloqueada' where usuario_id = '${u(3)}'")   // blocked AFTER choosing
        val authors = c.filas(
            "select i -> 'autor' ->> 'handle', coalesce(i -> 'autor' ->> 'avatarId', '-'), coalesce(i -> 'autor' ->> 'avatarPath', '-'), ((i -> 'autor') ? 'avatarId')::text " +
                "from jsonb_array_elements(public.app_foro_list('${u(4)}', null, 'recientes', null, 10) -> 'items') i order by 1",
        )
        assertEquals(
            listOf(listOf("f29_user1", "7", "-", "true"), listOf("f29_user2", "-", path(2), "true"), listOf("f29_user3", "-", "-", "true")),
            authors,
            "preset, photo, blocked account: the key is always there",
        )
        assertEquals("7", c.unico("select public.app_foro_post('${u(4)}', '0f290000-0000-0000-0000-000000000001') -> 'autor' ->> 'avatarId'"))
        assertEquals("7", c.unico("select public.app_foro_comments('${u(4)}', '0f290000-0000-0000-0000-000000000001', null, 10) -> 'items' -> 0 -> 'autor' ->> 'avatarId'"))
        // tombstone: author deleted -> avatarId null
        c.ejecutar("update public.publicaciones_oposired set usuario_id = null where id = '0f290000-0000-0000-0000-000000000001'")
        assertEquals("null|null", c.unico("select coalesce(p -> 'autor' ->> 'handle', 'null') || '|' || coalesce(p -> 'autor' ->> 'avatarId', 'null') from public.app_foro_post('${u(4)}', '0f290000-0000-0000-0000-000000000001') p"))
    }

    @Test
    fun theRankingTop3CarriesTheAvatarIdOnlyWhileTheUserIsPublicAndActive() = V1Db.escenario { c ->
        c.ejecutar(seed)
        c.ejecutar(
            """
            insert into public.ligas_nivel (id, codigo, nombre, preguntas_minimas, ventana_calculo, posicion) values ('$liga', 'f29-1', 'Liga F29', 0, 3, 1);
            insert into public.estado_competitivo_usuario (usuario_id, liga_nivel_id, preguntas_validas_ranking, ventana_calculo, nota_media_actual)
              select ('a0000000-0000-0000-0000-0000000f29' || lpad(n::text, 2, '0'))::uuid, '$liga', 3, 3, (array[9.0, 8.0, 7.0, 6.0, 5.0, 4.0])[n] from generate_series(1, 6) n;
            update public.configuracion_v1 set valor_integer = 5 where clave = 'cohorte_minima_comparativa';
            update public.perfiles_usuario set ranking_publico = true where usuario_id in ('${u(1)}', '${u(2)}', '${u(3)}');
            """.trimIndent(),
        )
        c.preset(1, 2)
        c.photo(2, path(2))
        c.preset(5, 6)   // not public: never listed
        c.unico("select public.app_job_ranking(true)::text")
        fun top3() = c.filas(
            "select t ->> 'handle', coalesce(t ->> 'avatarId', '-'), coalesce(t ->> 'avatarPath', '-') from jsonb_array_elements(public.app_ranking_me('${u(4)}') -> 'top3') t order by (t ->> 'posicion')::int",
        )
        assertEquals(listOf(listOf("f29_user1", "2", "-"), listOf("f29_user2", "-", path(2)), listOf("f29_user3", "-", "-")), top3())
        c.ejecutar("update public.perfiles_usuario set ranking_publico = false where usuario_id = '${u(1)}'")
        assertEquals(listOf("f29_user1", "-", "-"), top3()[0], "became private: the preset stops at once")
        c.ejecutar("update public.perfiles_usuario set ranking_publico = true, estado_cuenta = 'bloqueada' where usuario_id = '${u(1)}'")
        assertEquals(listOf("f29_user1", "-", "-"), top3()[0], "blocked account: no preset")
        assertEquals("4|f29-1", c.unico("select (r ->> 'posicion') || '|' || (r -> 'liga' ->> 'codigo') from public.app_ranking_me('${u(4)}') r"), "the rest of the payload is unchanged")
    }

    @Test
    fun theVerifyProvesTheEndStateAndTheRollbackRestoresTheF3Shapes() = V1Db.escenario { c ->
        c.ejecutar(seed)
        val verify = Slice0Db.verifyDe("supabase/migrations/$migration.sql")
        val rows = c.filas(verify)
        assertEquals(17, rows.size, "VERIFY rows: $rows")
        assertEquals(emptyList(), rows.filter { it[1] != "0" }, "every VERIFY counter is 0")
        c.photo(1, path(1))
        c.ejecutar(Slice0Db.rollbackDe("supabase/migrations/$migration.sql"))
        assertEquals("0", c.unico("select count(*) from pg_proc where proname in ('app_set_avatar_preset', 'avatar_visible_id')"))
        assertEquals("0", c.unico("select count(*) from information_schema.columns where table_schema = 'public' and table_name = 'perfiles_usuario' and column_name = 'avatar_id'"))
        assertEquals("0", c.unico("select count(*) from pg_proc p where p.pronamespace = 'public'::regnamespace and p.prosrc like '%avatar_id%'"), "no function mentions the column any more")
        assertEquals(path(1), c.unico("select avatar_url from public.perfiles_usuario where usuario_id = '${u(1)}'"), "the photo stays")
        assertTrue(runCatching { c.filas(verify).any { it[1] != "0" } }.getOrDefault(true), "VERIFY fails without the migration")
        // re-apply = same end state
        c.ejecutar(Slice0Db.leer("supabase/migrations/$migration.sql"))
        assertEquals(emptyList(), c.filas(verify).filter { it[1] != "0" })
        assertNull(c.unico("select avatar_id::text from public.perfiles_usuario where usuario_id = '${u(1)}'"))
    }

    @Test
    fun negativeControlWithoutTheMigrationThereIsNoPresetAnywhere() = V1Db.escenario(sin = setOf(migration)) { c ->
        c.ejecutar(seed)
        assertEquals("0", c.unico("select count(*) from pg_proc where proname in ('app_set_avatar_preset', 'avatar_visible_id')"))
        assertEquals("0", c.unico("select count(*) from information_schema.columns where table_schema = 'public' and table_name = 'perfiles_usuario' and column_name = 'avatar_id'"))
        assertEquals("0", c.unico("select count(*) from pg_proc p where p.proname = 'app_foro_list' and p.prosrc like '%avatarId%'"))
    }
}
