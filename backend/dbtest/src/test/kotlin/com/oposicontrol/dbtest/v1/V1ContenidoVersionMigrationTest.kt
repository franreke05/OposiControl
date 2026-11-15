package com.oposicontrol.dbtest.v1

import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `20261115100200_v1_contenido_version` (F31c, FA58, D2-69): one opaque token per area that changes exactly when something visible to the user changes.
 * The news tables are MAIN objects (not in the gate base): the scenario creates them from their own repo migration (20260923103000).
 */
class V1ContenidoVersionMigrationTest {
    private val migration = "20261115100200_v1_contenido_version"
    private val esp = "e1000000-0000-0000-0000-0000000031e3"
    private val esp2 = "e1000000-0000-0000-0000-0000000031e4"
    private val com = "c1000000-0000-0000-0000-0000000031e3"
    private val u = "a0000000-0000-0000-0000-0000000031c1"
    private val other = "a0000000-0000-0000-0000-0000000031c2"

    private fun Connection.world() {
        ejecutar(
            """
            insert into auth.users (id, email, aud, role, email_confirmed_at) values ('$u', 'u@test.local', 'authenticated', 'authenticated', now()), ('$other', 'o@test.local', 'authenticated', 'authenticated', now());
            insert into public.especialidades (id, codigo, nombre, slug, activa) values ('$esp', 'F31C', 'F31C', 'f31c', true), ('$esp2', 'F31D', 'F31D', 'f31d', true);
            insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('$com', 'F31C', 'Com F31C', 'com-f31c');
            insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id) values
              ('$u', 'u', 'f31c_u', true, 'activa', '$esp'), ('$other', 'o', 'f31c_o', true, 'activa', '$esp2')
              on conflict (usuario_id) do update set especialidad_principal_id = excluded.especialidad_principal_id;
            """.trimIndent(),
        )
        ejecutar(Slice0Db.leer("supabase/migrations/20260923103000_news_canvas_schema.sql"))
    }

    private fun Connection.v(user: String = u): Map<String, String> {
        val j = unico("select public.app_contenido_version('$user')::text")!!
        return listOf("examenes", "servicios", "noticias", "paquetes").associateWith { k -> unico("select ('$j'::jsonb) ->> '$k'")!! }
    }
    private fun Connection.exam(id: String, especialidad: String, publicado: String) = ejecutar(
        "begin; set local session_replication_role = replica; insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en) " +
            "values ('$id', 'C-$id', '$especialidad', '$com', 'Examen', 'rafaga', 'premium', true, $publicado); commit;",
    )
    private fun changed(before: Map<String, String>, after: Map<String, String>): Set<String> = before.keys.filter { before[it] != after[it] }.toSet()

    @Test
    fun eachTokenMovesOnlyWithWhatTheUserCanSee() = V1Db.escenario { c ->
        c.world()
        val t0 = c.v()
        assertTrue(t0.values.all { it.matches(Regex("^[0-9a-f]{16}$")) }, "$t0")
        assertEquals(t0, c.v(), "stable while nothing changes")

        // exams: own specialty published now = change; another specialty or a future publication = no change; its time comes = change
        c.exam("e3120000-0000-0000-0000-000000000001", esp, "now() - interval '1 minute'")
        val t1 = c.v(); assertEquals(setOf("examenes"), changed(t0, t1))
        c.exam("e3120000-0000-0000-0000-000000000002", esp2, "now() - interval '1 minute'")
        c.exam("e3120000-0000-0000-0000-000000000003", esp, "now() + interval '1 hour'")
        assertEquals(t1, c.v(), "another specialty and a scheduled exam are not visible yet")
        assertNotEquals(t1["examenes"], c.v(other)["examenes"], "the other specialty has its own token")
        c.ejecutar("update public.examenes set publicado_en = now() - interval '1 second' where id = 'e3120000-0000-0000-0000-000000000003'")
        val t2 = c.v(); assertEquals(setOf("examenes"), changed(t1, t2))
        c.ejecutar("update public.examenes set precio_tickets = 50 where id = 'e3120000-0000-0000-0000-000000000001'")
        val t3 = c.v(); assertEquals(setOf("examenes"), changed(t2, t3), "a visible field (price) changed")
        c.ejecutar("update public.examenes set activo = false where id = 'e3120000-0000-0000-0000-000000000001'")
        val t4 = c.v(); assertEquals(setOf("examenes"), changed(t3, t4), "unpublished")

        // services: visible + active only
        c.ejecutar("update public.servicios set precio_tickets = precio_tickets + 1 where codigo = 'protector_racha'")
        val t5 = c.v(); assertEquals(setOf("servicios"), changed(t4, t5))
        c.ejecutar("update public.servicios set visible = false where codigo = 'protector_racha'")
        val t6 = c.v(); assertEquals(setOf("servicios"), changed(t5, t6))
        c.ejecutar("update public.servicios set precio_tickets = precio_tickets + 1 where codigo = 'protector_racha'")
        assertEquals(t6, c.v(), "a hidden service never moves the token")

        // news: published of the specialty, scheduled once due; drafts, other specialty, future = nothing
        val tipo = c.unico("select id::text from public.tipo_noticia where codigo = 'AVISO'")
        c.ejecutar("insert into public.noticia (id, titulo, especialidad_id, tipo_noticia_id, estado) values ('d3120000-0000-0000-0000-000000000001', 'Borrador', '$esp', '$tipo', 'draft')")
        c.ejecutar("insert into public.noticia (id, titulo, especialidad_id, tipo_noticia_id, estado, published_at) values ('d3120000-0000-0000-0000-000000000002', 'Otra', '$esp2', '$tipo', 'published', now())")
        c.ejecutar("insert into public.noticia (id, titulo, especialidad_id, tipo_noticia_id, estado, publicar_desde) values ('d3120000-0000-0000-0000-000000000003', 'Luego', '$esp', '$tipo', 'scheduled', now() + interval '1 hour')")
        assertEquals(t6, c.v())
        c.ejecutar("update public.noticia set estado = 'published', published_at = now() where id = 'd3120000-0000-0000-0000-000000000001'")
        val t7 = c.v(); assertEquals(setOf("noticias"), changed(t6, t7))
        c.ejecutar("update public.noticia set publicar_desde = now() - interval '1 second' where id = 'd3120000-0000-0000-0000-000000000003'")
        val t8 = c.v(); assertEquals(setOf("noticias"), changed(t7, t8), "a scheduled news item is visible once due")
        c.ejecutar("update public.tipo_noticia set activo = false where codigo = 'AVISO'")
        val t9 = c.v(); assertEquals(setOf("noticias"), changed(t8, t9), "an inactive news type hides its items")

        // ticket packs on sale
        c.ejecutar("insert into public.productos_comerciales (codigo, tipo_producto, nombre, precio_centimos, cantidad_tickets, activo, visible) values ('pack_f31c', 'pack_tickets', 'Pack', 199, 100, true, true)")
        val t10 = c.v(); assertEquals(setOf("paquetes"), changed(t9, t10))
        c.ejecutar("update public.productos_comerciales set visible = false where codigo = 'pack_f31c'")
        assertEquals(t9["paquetes"], c.v()["paquetes"], "hidden again: back to the previous set")

        val e = runCatching { c.unico("select public.app_contenido_version('a0000000-0000-0000-0000-0000000031ff')") }.exceptionOrNull()
        assertTrue(e?.message?.contains("WALLET_INVALID_USER") == true, "$e")
    }

    @Test
    fun withoutTheNewsTablesTheNewsTokenIsTheEmptyOne() = V1Db.escenario { c ->
        c.ejecutar(
            "insert into auth.users (id, email, aud, role) values ('$u', 'u@test.local', 'authenticated', 'authenticated'); " +
                "insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta) values ('$u', 'u', 'f31c_u', true, 'activa') on conflict (usuario_id) do nothing;",
        )
        assertEquals(c.unico("select left(md5('noticias|'), 16)"), c.v()["noticias"])
    }

    @Test
    fun clientsCannotCallItAndTheVerifyAndRollbackRoundTrip() = V1Db.escenario { c ->
        val verify = Slice0Db.verifyDe("supabase/migrations/$migration.sql")
        val rows = c.filas(verify)
        assertEquals(2, rows.size, "VERIFY rows: $rows")
        assertEquals(emptyList(), rows.filter { it[1] != "0" })
        c.ejecutar(Slice0Db.rollbackDe("supabase/migrations/$migration.sql"))
        assertEquals(2, c.filas(verify).count { it[1] != "0" })
        c.ejecutar(Slice0Db.leer("supabase/migrations/$migration.sql"))
        assertEquals(emptyList(), c.filas(verify).filter { it[1] != "0" })
    }
}
