package com.oposicontrol.dbtest.slice1

import com.oposicontrol.dbtest.PostgresHarness
import com.oposicontrol.dbtest.SupabaseFixture.ADMIN
import com.oposicontrol.dbtest.SupabaseFixture.MODERADOR
import com.oposicontrol.dbtest.SupabaseFixture.SUPERADMIN
import com.oposicontrol.dbtest.SupabaseFixture.USER_A
import com.oposicontrol.dbtest.SupabaseFixture.USER_B
import com.oposicontrol.dbtest.SupabaseFixture.USER_C
import com.oposicontrol.dbtest.SupabaseFixture.USUARIO
import com.oposicontrol.dbtest.asignar
import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.rolesDe
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.aplicar
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.superadminsActivos
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
 * RC-02 (MASTERF1-DB-001): migracion 20260930102200. En MAIN `roles_usuario_admin_all` (FOR ALL TO authenticated,
 * es_admin()) + el grant INSERT/UPDATE/DELETE a authenticated dejan que un admin NORMAL, con su JWT, se inserte
 * `superadmin` o borre el ultimo superadmin por la Data API, saltandose admin_sync_roles (SUPERADMIN_REQUIRED de
 * 101600 y la invariante del ultimo superadmin). Control NEGATIVO del estado de MAIN, despues 42501 para todo cliente,
 * lectura propia/admin intacta, las RPC de sincronizacion (101600/101850/101900, SECURITY DEFINER) siguen funcionando,
 * ROLLBACK exacto (ACL y politica) e idempotencia, y PREFLIGHT cerrado ante estados que no se podrian restaurar.
 */
class Slice1RolesUsuarioClientWriteMigrationTest {
    private val migracion = "supabase/migrations/20260930102200_slice1_roles_usuario_client_write_revoke.sql"
    private val previas = listOf(
        "supabase/migrations/20260921071916_role_sync_guard.sql",
        "supabase/migrations/20260930101600_admin_sync_roles_actor_guard.sql",
        "supabase/migrations/20260930101850_admin_sync_roles_audited.sql",
        "supabase/migrations/20260930101900_admin_sync_roles_audited_expected.sql",
    )
    private val ddlRevoke = "supabase/migrations/20260930102100_slice1_client_ddl_privileges_revoke.sql"

    /** Forma de MAIN de roles_usuario (main_shape_subset.sql :354, :398-401): politica ALL de admin y grants de cliente. */
    private val mainShape = """
        grant usage on schema auth to anon, authenticated, service_role;
        grant usage on schema public to anon, authenticated, service_role;
        create schema if not exists private;
        create policy roles_usuario_admin_all on public.roles_usuario as permissive for all to authenticated
          using (public.es_admin()) with check (public.es_admin());
        revoke all on table public.roles_usuario from public, anon, authenticated, service_role;
        grant select on table public.roles_usuario to anon;
        grant delete, insert, references, select, trigger, truncate, update on table public.roles_usuario to authenticated, service_role;
    """.trimIndent()

    private fun <T> escenario(conMigracion: Boolean = true, bloque: (Connection) -> T): T = PostgresHarness.escenario { c ->
        previas.forEach { c.aplicar(it) }
        c.ejecutar(mainShape)
        c.aplicar(ddlRevoke)
        // USER_A admin normal, USER_B el unico superadmin, USER_C usuario.
        c.asignar(USER_A, ADMIN)
        c.asignar(USER_B, SUPERADMIN)
        c.asignar(USER_C, USUARIO)
        if (conMigracion) c.aplicar(migracion)
        bloque(c)
    }

    /** Sentencia como cliente de PostgREST (`set local role` + claim sub), SIEMPRE confirmada si va bien. SQLSTATE o null. */
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

    private fun Connection.acl(): String = unico("select relacl::text from pg_class where oid = 'public.roles_usuario'::regclass")!!

    private fun Connection.politicas(): String = unico(
        "select string_agg(polname || ':' || polcmd::text || ':' || polroles::regrole[]::text || ':' || coalesce(pg_get_expr(polqual, polrelid), '') || " +
            "':' || coalesce(pg_get_expr(polwithcheck, polrelid), ''), E'\\n' order by polname) from pg_policy where polrelid = 'public.roles_usuario'::regclass",
    )!!

    private fun Connection.fallo(sql: String): PSQLException? {
        val e = runCatching { ejecutar(sql) }.exceptionOrNull() as PSQLException?
        runCatching { ejecutar("rollback") }
        autoCommit = true
        return e
    }

    private val autoSuperadmin = "insert into public.roles_usuario (usuario_id, rol_id) values ('$USER_A', '$SUPERADMIN')"
    private val borrarUltimoSuperadmin = "delete from public.roles_usuario where usuario_id = '$USER_B'"
    private val cambiarRol = "update public.roles_usuario set rol_id = '$SUPERADMIN' where usuario_id = '$USER_A'"

    /** Control NEGATIVO: en el estado de MAIN un admin normal se da superadmin y borra el ultimo superadmin. */
    @Test
    fun antesDeLaMigracionUnAdminNormalSeAutoasignaSuperadminYBorraElUltimo(): Unit = escenario(conMigracion = false) { c ->
        assertNull(c.comoCliente(USER_A, autoSuperadmin), "MAIN: autoasignacion de superadmin por la Data API")
        assertEquals(listOf("admin", "superadmin"), c.rolesDe(USER_A))
        assertNull(c.comoCliente(USER_A, borrarUltimoSuperadmin), "MAIN: borra al superadmin USER_B")
        assertEquals(listOf<String>(), c.rolesDe(USER_B))
        val v = c.verify()
        assertEquals(3L, v["roles_usuario_client_write_privilege"], "el VERIFY detecta el estado de MAIN: $v")
        assertEquals(1L, v["roles_usuario_client_write_policy"], v.toString())
    }

    @Test
    fun trasLaMigracionNingunClienteEscribeRolesUsuario(): Unit = escenario { c ->
        assertEquals("42501", c.comoCliente(USER_A, autoSuperadmin), "admin normal: autoasignacion denegada")
        assertEquals("42501", c.comoCliente(USER_A, cambiarRol), "UPDATE denegado")
        assertEquals("42501", c.comoCliente(USER_A, borrarUltimoSuperadmin), "DELETE denegado")
        // Tambien el superadmin: toda escritura va por la RPC (Ktor, service_role).
        assertEquals("42501", c.comoCliente(USER_B, "insert into public.roles_usuario (usuario_id, rol_id) values ('$USER_C', '$ADMIN')"))
        assertEquals("42501", c.comoCliente(null, autoSuperadmin, rol = "anon"))
        assertEquals(listOf("admin"), c.rolesDe(USER_A))
        assertEquals(1, c.superadminsActivos())
        // Lectura intacta (roles_usuario_select_propio): el propio usuario ve su fila; el admin ve todas.
        assertEquals("1", c.lecturaComo(USER_C, "select count(*)::text from public.roles_usuario"))
        assertEquals("3", c.lecturaComo(USER_A, "select count(*)::text from public.roles_usuario"))
        val v = c.verify()
        assertEquals(4, v.size, v.toString())
        assertTrue(v.values.all { it == 0L }, "VERIFY: $v")
    }

    /** Las RPC SECURITY DEFINER de sincronizacion (101600/101850/101900) siguen escribiendo como service_role. */
    @Test
    fun lasRpcDeSincronizacionSiguenFuncionandoComoServiceRole(): Unit = escenario { c ->
        /** Como service_role (Ktor). Mensaje de error o null; las lecturas de comprobacion van como el dueno. */
        fun comoServicio(sql: String): String? {
            c.ejecutar("set role service_role")
            return try {
                c.ejecutar(sql)
                null
            } catch (e: SQLException) {
                e.message
            } finally {
                c.ejecutar("reset role")
            }
        }
        assertNull(comoServicio("select public.admin_sync_roles('$USER_C'::uuid, array['$MODERADOR'::uuid], '$USER_B'::uuid)"))
        assertEquals(listOf("moderador"), c.rolesDe(USER_C))
        assertNull(comoServicio("select public.admin_sync_roles_audited('$USER_C'::uuid, array['$USUARIO'::uuid], '$USER_B'::uuid)"))
        assertEquals(listOf("usuario"), c.rolesDe(USER_C))
        assertNull(
            comoServicio(
                "select public.admin_sync_roles_audited('$USER_C'::uuid, array['$ADMIN'::uuid], '$USER_B'::uuid, array['$USUARIO'::uuid])",
            ),
        )
        assertEquals(listOf("admin"), c.rolesDe(USER_C))
        // D1-26 sigue en la RPC: un admin normal como actor no se concede superadmin.
        val e = comoServicio("select public.admin_sync_roles('$USER_A'::uuid, array['$ADMIN'::uuid, '$SUPERADMIN'::uuid], '$USER_A'::uuid)")
        assertTrue(e?.contains("SUPERADMIN_REQUIRED") == true, e)
        assertEquals(listOf("admin"), c.rolesDe(USER_A))
    }

    @Test
    fun elRollbackRestauraAclYPoliticaExactasYTodoEsIdempotente(): Unit = escenario(conMigracion = false) { c ->
        val acl = c.acl()
        val pol = c.politicas()
        c.aplicar(migracion)
        val aplicada = c.acl()
        assertNotEquals(acl, aplicada)
        assertTrue(!c.politicas().contains("roles_usuario_admin_all"))
        c.aplicar(migracion) // re-ejecucion: no-op
        assertEquals(aplicada, c.acl())
        c.ejecutar(Slice0Db.rollbackDe(migracion))
        assertEquals(acl, c.acl(), "ACL byte a byte")
        assertEquals(pol, c.politicas(), "politica de MAIN recreada igual")
        c.ejecutar(Slice0Db.rollbackDe(migracion)) // re-ejecutable
        assertEquals(acl, c.acl())
        assertEquals(pol, c.politicas())
        assertNull(c.comoCliente(USER_A, autoSuperadmin), "el ROLLBACK reabre (documentado) el camino")
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
            assertEquals("ROLES_USUARIO_CLIENT_WRITE_PREFLIGHT", e?.serverErrorMessage?.message, caso)
            assertEquals(acl, c.acl(), "nada revocado ($caso)")
            assertEquals(pol, c.politicas(), "nada borrado ($caso)")
            c.ejecutar(deshacer)
        }
        rechaza("estado parcial", "revoke delete on table public.roles_usuario from authenticated", "grant delete on table public.roles_usuario to authenticated")
        rechaza("anon con INSERT", "grant insert on table public.roles_usuario to anon", "revoke insert on table public.roles_usuario from anon")
        rechaza("PUBLIC con UPDATE", "grant update on table public.roles_usuario to public", "revoke update on table public.roles_usuario from public")
        rechaza(
            "grant option",
            "grant insert on table public.roles_usuario to authenticated with grant option",
            "revoke grant option for insert on table public.roles_usuario from authenticated",
        )
        rechaza(
            "politica admin_all distinta",
            "alter policy roles_usuario_admin_all on public.roles_usuario with check (true)",
            "alter policy roles_usuario_admin_all on public.roles_usuario with check (public.es_admin())",
        )
        rechaza(
            "sin politica de lectura",
            "alter policy roles_usuario_select_propio on public.roles_usuario rename to hx_otra",
            "alter policy hx_otra on public.roles_usuario rename to roles_usuario_select_propio",
        )
        c.aplicar(migracion) // control: la forma de MAIN aplica
        assertTrue(c.verify().values.all { it == 0L }, c.verify().toString())
    }
}
