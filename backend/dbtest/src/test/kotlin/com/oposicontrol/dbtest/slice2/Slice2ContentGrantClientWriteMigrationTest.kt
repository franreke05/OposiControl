package com.oposicontrol.dbtest.slice2

import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.aplicar
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.slice3.Slice3Db
import com.oposicontrol.dbtest.slice3.Slice3Db.EX
import com.oposicontrol.dbtest.slice3.Slice3Db.EX_EMPTY
import com.oposicontrol.dbtest.slice3.Slice3Db.UA
import com.oposicontrol.dbtest.slice3.Slice3Db.UB
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.postgresql.util.PSQLException

/**
 * SLICE 2: migracion 20261008100400. En MAIN `contenido_desbloqueado_admin_all` (FOR ALL TO authenticated, es_admin()) +
 * el grant INSERT/UPDATE/DELETE a authenticated dejan que un admin NORMAL, con su JWT, se conceda, amplie o borre
 * concesiones de contenido por la Data API, saltandose Ktor (EntitlementPolicy lee esta tabla como fuente de acceso).
 * Control NEGATIVO del estado de MAIN, despues 42501 para todo cliente, lectura propia/admin intacta, service_role
 * (Ktor) conserva INSERT/UPDATE/DELETE, ROLLBACK exacto (ACL y politica), idempotencia y PREFLIGHT cerrado.
 * La forma de MAIN (tabla, politicas, grants) viene de main_shape_subset.sql via Slice3Db.
 */
class Slice2ContentGrantClientWriteMigrationTest {
    private val migracion = "supabase/migrations/20261008100400_slice2_content_grant_client_write_revoke.sql"
    private val tabla = "public.contenido_desbloqueado_usuario"
    private val rolAdmin = "d2000000-0000-0000-0000-0000000000a1"
    private val ga = "9a000000-0000-0000-0000-00000000000a"
    private val gb = "9a000000-0000-0000-0000-00000000000b"
    private val gc = "9a000000-0000-0000-0000-00000000000c"

    /** UA = admin normal (es_admin() true), UB = usuario. Una concesion cada uno. */
    private fun <T> escenario(conMigracion: Boolean = true, bloque: (Connection) -> T): T =
        Slice3Db.escenario(migraciones = false) { c ->
            c.ejecutar(
                """
                set session_replication_role = replica;
                insert into public.roles_app (id, codigo, nombre) values ('$rolAdmin', 'admin', 'Admin');
                insert into public.roles_usuario (usuario_id, rol_id) values ('$UA', '$rolAdmin');
                insert into $tabla (id, usuario_id, examen_id, motivo) values
                  ('$ga', '$UA', '$EX', 'compra'), ('$gb', '$UB', '$EX', 'compra');
                set session_replication_role = origin;
                """.trimIndent(),
            )
            if (conMigracion) c.aplicar(migracion)
            bloque(c)
        }

    /** Sentencia como cliente de PostgREST (`set local role` + claim sub), confirmada si va bien. SQLSTATE o null. */
    private fun Connection.comoCliente(sub: String?, sql: String, rol: String = "authenticated"): String? {
        ejecutar("begin")
        return try {
            ejecutar("set local role $rol")
            ejecutar("select set_config('request.jwt.claim.sub', '${sub ?: ""}', true)")
            ejecutar(sql)
            ejecutar("commit")
            null
        } catch (e: SQLException) {
            runCatching { ejecutar("rollback") }
            e.sqlState
        }
    }

    private fun Connection.lecturaComo(sub: String, sql: String): String? {
        ejecutar("begin")
        return try {
            ejecutar("set local role authenticated")
            ejecutar("select set_config('request.jwt.claim.sub', '$sub', true)")
            unico(sql)
        } finally {
            ejecutar("rollback")
        }
    }

    private fun Connection.verify(): Map<String, Long> =
        filas(Slice0Db.verifyDe(migracion)).associate { it[0]!! to it[1]!!.toLong() }

    private fun Connection.acl(): String = unico("select relacl::text from pg_class where oid = '$tabla'::regclass")!!

    private fun Connection.politicas(): String = unico(
        "select string_agg(polname || ':' || polcmd::text || ':' || polroles::regrole[]::text || ':' || coalesce(pg_get_expr(polqual, polrelid), '') || " +
            "':' || coalesce(pg_get_expr(polwithcheck, polrelid), ''), E'\\n' order by polname) from pg_policy where polrelid = '$tabla'::regclass",
    )!!

    private fun Connection.fallo(sql: String): PSQLException? {
        val e = runCatching { ejecutar(sql) }.exceptionOrNull() as PSQLException?
        runCatching { ejecutar("rollback") }
        autoCommit = true
        return e
    }

    private fun Connection.total(donde: String = "true") = unico("select count(*)::text from $tabla where $donde")

    private val autoConcesion = "insert into $tabla (usuario_id, examen_id, motivo) values ('$UA', '$EX_EMPTY', 'admin')"
    private val ampliarAjena = "update $tabla set valido_hasta = now() + interval '1 year' where id = '$gb'"
    private val borrarAjena = "delete from $tabla where id = '$gb'"

    /** Control NEGATIVO: en el estado de MAIN un admin normal escribe concesiones por SQL directo con su JWT. */
    @Test
    fun antesDeLaMigracionUnAdminNormalInsertaActualizaYBorraConcesionesPorLaDataApi(): Unit = escenario(conMigracion = false) { c ->
        assertNull(c.comoCliente(UA, autoConcesion), "MAIN: el admin se concede acceso a si mismo")
        assertEquals("1", c.total("usuario_id = '$UA' and examen_id = '$EX_EMPTY'"))
        assertNull(c.comoCliente(UA, ampliarAjena), "MAIN: amplia la concesion de otro")
        assertEquals("1", c.total("id = '$gb' and valido_hasta is not null"))
        assertNull(c.comoCliente(UA, borrarAjena), "MAIN: borra la concesion de otro")
        assertEquals("0", c.total("id = '$gb'"))
        val v = c.verify()
        assertEquals(3L, v["content_grant_client_write_privilege"], "el VERIFY detecta el estado de MAIN: $v")
        assertEquals(1L, v["content_grant_client_write_policy"], v.toString())
        assertEquals(0L, v["content_grant_select_policy_missing"], v.toString())
        assertEquals(0L, v["content_grant_service_role_write_missing"], v.toString())
    }

    @Test
    fun trasLaMigracionNingunClienteEscribeConcesiones(): Unit = escenario { c ->
        val insertarUsuario = "insert into $tabla (usuario_id, examen_id, motivo) values ('$UB', '$EX_EMPTY', 'admin')"
        val actualizarPropia = "update $tabla set valido_hasta = now() + interval '1 year' where id = '$gb'"
        val borrarPropia = "delete from $tabla where id = '$gb'"
        assertEquals("42501", c.comoCliente(UB, insertarUsuario), "usuario: INSERT denegado")
        assertEquals("42501", c.comoCliente(UB, actualizarPropia), "usuario: UPDATE denegado")
        assertEquals("42501", c.comoCliente(UB, borrarPropia), "usuario: DELETE denegado")
        assertEquals("42501", c.comoCliente(UA, autoConcesion), "admin normal: INSERT denegado")
        assertEquals("42501", c.comoCliente(UA, ampliarAjena), "admin normal: UPDATE denegado")
        assertEquals("42501", c.comoCliente(UA, borrarAjena), "admin normal: DELETE denegado")
        assertEquals("42501", c.comoCliente(null, autoConcesion, rol = "anon"), "anon: INSERT denegado")
        assertEquals("42501", c.comoCliente(null, ampliarAjena, rol = "anon"), "anon: UPDATE denegado")
        assertEquals("42501", c.comoCliente(null, borrarAjena, rol = "anon"), "anon: DELETE denegado")
        assertEquals("2", c.total(), "ninguna fila cambio")
        assertEquals("0", c.total("valido_hasta is not null"))
        // Lectura intacta (contenido_desbloqueado_select_propio): el usuario ve su fila; el admin ve todas.
        assertEquals("1", c.lecturaComo(UB, "select count(*)::text from $tabla"))
        assertEquals("2", c.lecturaComo(UA, "select count(*)::text from $tabla"))
        val v = c.verify()
        assertEquals(4, v.size, v.toString())
        assertTrue(v.values.all { it == 0L }, "VERIFY: $v")
    }

    /** El camino privilegiado (Ktor, service_role) conserva INSERT/UPDATE/DELETE. */
    @Test
    fun serviceRoleSigueEscribiendoConcesiones(): Unit = escenario { c ->
        val insertar = "insert into $tabla (id, usuario_id, examen_id, motivo) values ('$gc', '$UB', '$EX_EMPTY', 'regalo')"
        assertNull(c.comoCliente(null, insertar, rol = "service_role"))
        assertEquals("1", c.total("id = '$gc'"))
        assertNull(c.comoCliente(null, ampliarAjena, rol = "service_role"))
        assertEquals("1", c.total("id = '$gb' and valido_hasta is not null"))
        assertNull(c.comoCliente(null, borrarAjena, rol = "service_role"))
        assertEquals("0", c.total("id = '$gb'"))
        assertEquals("2", c.total(), "quedan ga y gc; solo cambio lo que escribio service_role")
    }

    @Test
    fun elRollbackRestauraAclYPoliticaExactasYTodoEsIdempotente(): Unit = escenario(conMigracion = false) { c ->
        val acl = c.acl()
        val pol = c.politicas()
        c.aplicar(migracion)
        val aplicada = c.acl()
        assertNotEquals(acl, aplicada)
        assertTrue(!c.politicas().contains("contenido_desbloqueado_admin_all"))
        c.aplicar(migracion) // re-ejecucion: no-op
        assertEquals(aplicada, c.acl())
        c.ejecutar(Slice0Db.rollbackDe(migracion))
        assertEquals(acl, c.acl(), "ACL byte a byte")
        assertEquals(pol, c.politicas(), "politica de MAIN recreada igual")
        c.ejecutar(Slice0Db.rollbackDe(migracion)) // re-ejecutable
        assertEquals(acl, c.acl())
        assertEquals(pol, c.politicas())
        assertNull(c.comoCliente(UA, autoConcesion), "el ROLLBACK reabre (documentado) el camino")
        c.aplicar(migracion)
        assertEquals(aplicada, c.acl())
        assertTrue(c.verify().values.all { it == 0L }, c.verify().toString())
    }

    @Test
    fun preflightFallaCerradoAnteEstadosQueNoSePuedenRestaurarExactos(): Unit = escenario(conMigracion = false) { c ->
        fun rechaza(caso: String, sembrar: String, deshacer: String) {
            c.ejecutar(sembrar)
            val acl = c.acl()
            val pol = c.politicas()
            val e = c.fallo(Slice0Db.leer(migracion))
            assertEquals("P0001", e?.sqlState, caso)
            assertEquals("CONTENT_GRANT_CLIENT_WRITE_PREFLIGHT", e?.serverErrorMessage?.message, caso)
            assertEquals(acl, c.acl(), "nada revocado ($caso)")
            assertEquals(pol, c.politicas(), "nada borrado ($caso)")
            c.ejecutar(deshacer)
        }
        rechaza("estado parcial", "revoke delete on table $tabla from authenticated", "grant delete on table $tabla to authenticated")
        rechaza("anon con INSERT", "grant insert on table $tabla to anon", "revoke insert on table $tabla from anon")
        rechaza("PUBLIC con UPDATE", "grant update on table $tabla to public", "revoke update on table $tabla from public")
        rechaza(
            "grant option",
            "grant insert on table $tabla to authenticated with grant option",
            "revoke grant option for insert on table $tabla from authenticated",
        )
        rechaza(
            "politica admin_all distinta",
            "alter policy contenido_desbloqueado_admin_all on $tabla with check (true)",
            "alter policy contenido_desbloqueado_admin_all on $tabla with check (public.es_admin())",
        )
        rechaza(
            "sin politica de lectura",
            "alter policy contenido_desbloqueado_select_propio on $tabla rename to hx_otra",
            "alter policy hx_otra on $tabla rename to contenido_desbloqueado_select_propio",
        )
        c.aplicar(migracion) // control: la forma de MAIN aplica
        assertTrue(c.verify().values.all { it == 0L }, c.verify().toString())
    }
}
