package com.oposicontrol.dbtest.security

import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.aplicar
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.slice3.Slice3Db
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
 * RB-17 (SEC-V1-01b, parte de Control): migracion 20261023100500. En MAIN (catalogo leido 2026-10-19) `authenticated`
 * tiene INSERT/UPDATE/DELETE en opositeca_items, etiquetas_opositeca, invitaciones_referido y preguntas_parseadas_staging,
 * cada una con `*_admin_all` (FOR ALL, es_admin()): un JWT de admin reescribe Opositeca, invitaciones y sus recompensas y
 * el staging de importacion por la Data API, sin Ktor ni auditoria.
 *
 * UA admin, UB usuario (+ anon). Control NEGATIVO del estado de MAIN; despues 42501 para todo cliente, salvo la
 * invitacion PROPIA del usuario (`invitaciones_referido_insert_propias`), que se conserva; service_role (Ktor) sigue
 * escribiendo; ROLLBACK exacto; idempotencia y PREFLIGHT cerrado.
 *
 * Forma de MAIN: las cuatro tablas no estan en `main_shape_subset_20260929.sql`; se crean aqui con lo que la migracion
 * comprueba (politicas, grants y las columnas de la politica de invitaciones de 20260803102048).
 */
class AdminTablesWriteRevokeMigrationTest {
    private val migracion = "supabase/migrations/20261023100500_security_admin_tables_write_revoke.sql"
    private val rolAdmin = "d2000000-0000-0000-0000-0000000000a1"
    private val tablas = listOf("opositeca_items", "etiquetas_opositeca", "invitaciones_referido", "preguntas_parseadas_staging")
    private val completas = tablas - "invitaciones_referido"

    private val formaMain = """
        create table public.opositeca_items (id uuid primary key default gen_random_uuid(), titulo text);
        create table public.etiquetas_opositeca (id uuid primary key default gen_random_uuid(), nombre text);
        create table public.preguntas_parseadas_staging (id uuid primary key default gen_random_uuid(), titulo text);
        create table public.invitaciones_referido (id uuid primary key default gen_random_uuid(), usuario_origen_id uuid not null,
          usuario_destino_id uuid, estado text not null default 'pendiente', recompensa_entregada boolean not null default false,
          aceptado_en timestamptz);
        alter table public.opositeca_items enable row level security;
        alter table public.etiquetas_opositeca enable row level security;
        alter table public.preguntas_parseadas_staging enable row level security;
        alter table public.invitaciones_referido enable row level security;
        create policy opositeca_items_admin_all on public.opositeca_items as permissive for all to authenticated using (es_admin()) with check (es_admin());
        create policy opositeca_items_select_publico on public.opositeca_items for select to anon, authenticated using (true);
        create policy etiquetas_opositeca_admin_all on public.etiquetas_opositeca as permissive for all to authenticated using (es_admin()) with check (es_admin());
        create policy etiquetas_opositeca_select_publico on public.etiquetas_opositeca for select to anon, authenticated using (true);
        create policy preguntas_parseadas_admin_all on public.preguntas_parseadas_staging as permissive for all to authenticated using (es_admin()) with check (es_admin());
        create policy invitaciones_referido_admin_all on public.invitaciones_referido as permissive for all to authenticated using (es_admin()) with check (es_admin());
        create policy invitaciones_referido_insert_propias on public.invitaciones_referido for insert to authenticated
          with check (((usuario_origen_id = (select auth.uid())) and (usuario_destino_id is null) and (estado = 'pendiente'::text)
            and (recompensa_entregada = false) and (aceptado_en is null)));
        create policy invitaciones_referido_select_propias on public.invitaciones_referido for select to authenticated
          using (((usuario_origen_id = (select auth.uid())) or (usuario_destino_id = (select auth.uid())) or es_admin()));
        revoke all on table public.opositeca_items, public.etiquetas_opositeca, public.preguntas_parseadas_staging,
          public.invitaciones_referido from public, anon, authenticated, service_role;
        grant select on table public.opositeca_items, public.etiquetas_opositeca to anon;
        grant select, insert, update, delete on table public.opositeca_items, public.etiquetas_opositeca, public.preguntas_parseadas_staging,
          public.invitaciones_referido to authenticated, service_role;
    """.trimIndent()

    /** UA admin, UB usuario. Una fila en cada tabla; la invitacion es de UB. */
    private fun <T> escenario(conMigracion: Boolean = true, bloque: (Connection) -> T): T =
        Slice3Db.escenario(migraciones = false) { c ->
            c.ejecutar(formaMain)
            c.ejecutar(
                """
                set session_replication_role = replica;
                insert into public.roles_app (id, codigo, nombre) values ('$rolAdmin', 'admin', 'Admin');
                insert into public.roles_usuario (usuario_id, rol_id) values ('$UA', '$rolAdmin');
                insert into public.opositeca_items (titulo) values ('item');
                insert into public.etiquetas_opositeca (nombre) values ('etiqueta');
                insert into public.preguntas_parseadas_staging (titulo) values ('staging');
                insert into public.invitaciones_referido (usuario_origen_id) values ('$UB');
                set session_replication_role = origin;
                """.trimIndent(),
            )
            if (conMigracion) c.aplicar(migracion)
            bloque(c)
        }

    /** Sentencia como cliente de PostgREST (`set local role` + claim sub). Devuelve SQLSTATE, o null + filas afectadas. */
    private fun Connection.comoCliente(sub: String?, sql: String, rol: String = "authenticated"): Pair<String?, Int> {
        ejecutar("begin")
        return try {
            ejecutar("set local role $rol")
            ejecutar("select set_config('request.jwt.claim.sub', '${sub ?: ""}', true)")
            val n = createStatement().use { it.executeUpdate(sql) }
            ejecutar("commit")
            null to n
        } catch (e: SQLException) {
            runCatching { ejecutar("rollback") }
            e.sqlState to 0
        }
    }

    private fun Connection.verify(): Map<String, Long> =
        filas(Slice0Db.verifyDe(migracion)).associate { it[0]!! to it[1]!!.toLong() }

    /** Las tres escrituras de PostgREST sobre [tabla]; no dependen de datos (el privilegio se comprueba antes). */
    private fun escrituras(tabla: String): List<String> = listOf(
        "insert into public.$tabla select * from public.$tabla where false",
        "update public.$tabla set id = id where false",
        "delete from public.$tabla where false",
    )

    private fun Connection.estado(): String = unico(
        """
        select string_agg(x, E'\n' order by x) from (
          select c.relname || ':' || coalesce(c.relacl::text, '') as x from pg_class c
          where c.relnamespace = 'public'::regnamespace and c.relname = any(array[${tablas.joinToString { "'$it'" }}])
          union all
          select c.relname || '.' || p.polname || ':' || p.polcmd::text || ':' || p.polpermissive::text || ':' ||
                 coalesce(pg_get_expr(p.polqual, p.polrelid), '') || ':' || coalesce(pg_get_expr(p.polwithcheck, p.polrelid), '')
          from pg_policy p join pg_class c on c.oid = p.polrelid
          where c.relnamespace = 'public'::regnamespace and c.relname = any(array[${tablas.joinToString { "'$it'" }}])
        ) s
        """.trimIndent(),
    )!!

    private val invitacionPropia = "insert into public.invitaciones_referido (usuario_origen_id) values ('$UB')"
    private val invitacionAjena = "insert into public.invitaciones_referido (usuario_origen_id) values ('$UB')"
    private val recompensaFalsa = "update public.invitaciones_referido set recompensa_entregada = true"

    /** Control NEGATIVO: en el estado de MAIN el admin reescribe las cuatro tablas con su JWT. */
    @Test
    fun antesDeLaMigracionElAdminEscribeLasCuatroTablasPorLaDataApi(): Unit = escenario(conMigracion = false) { c ->
        assertEquals(null to 1, c.comoCliente(UA, "update public.opositeca_items set titulo = 'pwned'"), "MAIN: admin reescribe la Opositeca")
        assertEquals(null to 1, c.comoCliente(UA, "delete from public.etiquetas_opositeca"), "MAIN: admin borra etiquetas")
        assertEquals(null to 1, c.comoCliente(UA, "update public.preguntas_parseadas_staging set titulo = 'pwned'"), "MAIN: admin toca el staging")
        assertEquals(null to 1, c.comoCliente(UA, recompensaFalsa), "MAIN: admin marca una recompensa como entregada")
        assertEquals(null to 1, c.comoCliente(UA, invitacionAjena), "MAIN: admin crea una invitacion a nombre de otro")
        val v = c.verify()
        assertEquals(11L, v["admin_tables_client_write_privilege"], "VERIFY detecta MAIN (3x3 + UPDATE/DELETE de invitaciones): $v")
        assertEquals(4L, v["admin_tables_admin_policy"], v.toString())
        assertEquals(0L, v["admin_tables_unexpected_write_policy"], v.toString())
        assertEquals(0L, v["admin_tables_service_role_write_missing"], v.toString())
        assertEquals(0L, v["invitaciones_user_insert_missing"], v.toString())
    }

    @Test
    fun trasLaMigracionNingunClienteEscribeLasTablasDeAdministracion(): Unit = escenario { c ->
        for (tabla in completas) for (sql in escrituras(tabla)) {
            assertEquals("42501", c.comoCliente(UA, sql).first, "admin: $sql")
            assertEquals("42501", c.comoCliente(UB, sql).first, "usuario: $sql")
            assertEquals("42501", c.comoCliente(null, sql, rol = "anon").first, "anon: $sql")
        }
        for (sql in escrituras("invitaciones_referido").drop(1)) {
            assertEquals("42501", c.comoCliente(UA, sql).first, "admin: $sql")
            assertEquals("42501", c.comoCliente(UB, sql).first, "usuario: $sql")
        }
        assertEquals("42501", c.comoCliente(UA, recompensaFalsa).first, "el admin ya no marca recompensas")
        assertEquals("42501", c.comoCliente(UA, invitacionAjena).first, "el admin ya no crea invitaciones de otros (RLS)")
        assertEquals("42501", c.comoCliente(null, escrituras("invitaciones_referido")[0], rol = "anon").first, "anon no inserta")
        assertEquals("false", c.unico("select string_agg(recompensa_entregada::text, ',') from public.invitaciones_referido"))
        val v = c.verify()
        assertEquals(5, v.size, v.toString())
        assertTrue(v.values.all { it == 0L }, "VERIFY: $v")
    }

    /** El flujo legitimo del usuario se conserva: crea SU invitacion pendiente, y no una ya recompensada. */
    @Test
    fun elUsuarioSigueCreandoSuPropiaInvitacion(): Unit = escenario { c ->
        assertEquals(null to 1, c.comoCliente(UB, invitacionPropia), "su propia invitacion pendiente")
        assertEquals("42501", c.comoCliente(UB,
            "insert into public.invitaciones_referido (usuario_origen_id, recompensa_entregada) values ('$UB', true)").first,
            "nunca una con la recompensa entregada")
        assertEquals("42501", c.comoCliente(UB, "insert into public.invitaciones_referido (usuario_origen_id) values ('$UA')").first,
            "nunca a nombre de otro")
    }

    /** El camino privilegiado (Ktor, service_role) conserva escritura en las cuatro tablas. */
    @Test
    fun serviceRoleSigueEscribiendo(): Unit = escenario { c ->
        for (tabla in tablas) for (sql in escrituras(tabla)) {
            assertNull(c.comoCliente(null, sql, rol = "service_role").first, "service_role: $sql")
        }
        assertEquals(null to 1, c.comoCliente(null, recompensaFalsa, rol = "service_role"))
    }

    @Test
    fun elRollbackRestauraElEstadoExactoYTodoEsIdempotente(): Unit = escenario(conMigracion = false) { c ->
        val main = c.estado()
        c.aplicar(migracion)
        val aplicada = c.estado()
        assertNotEquals(main, aplicada)
        c.aplicar(migracion) // re-ejecucion: no-op
        assertEquals(aplicada, c.estado())
        c.ejecutar(Slice0Db.rollbackDe(migracion))
        assertEquals(main, c.estado(), "ACL y politicas byte a byte")
        c.ejecutar(Slice0Db.rollbackDe(migracion)) // re-ejecutable
        assertEquals(main, c.estado())
        assertEquals(null to 1, c.comoCliente(UA, recompensaFalsa), "el ROLLBACK reabre (documentado) el camino")
        c.aplicar(migracion)
        assertEquals(aplicada, c.estado())
        assertTrue(c.verify().values.all { it == 0L }, c.verify().toString())
    }

    @Test
    fun preflightFallaCerradoAnteEstadosQueNoSePuedenRestaurarExactos(): Unit = escenario(conMigracion = false) { c ->
        fun rechaza(caso: String, sembrar: String, deshacer: String) {
            c.ejecutar(sembrar)
            val antes = c.estado()
            val e = runCatching { c.ejecutar(Slice0Db.leer(migracion)) }.exceptionOrNull() as PSQLException?
            runCatching { c.ejecutar("rollback") }
            c.autoCommit = true
            assertEquals("P0001", e?.sqlState, caso)
            assertEquals("ADMIN_TABLES_WRITE_PREFLIGHT", e?.serverErrorMessage?.message, caso)
            assertEquals(antes, c.estado(), "nada cambiado ($caso)")
            c.ejecutar(deshacer)
        }
        rechaza("estado parcial", "revoke delete on table public.opositeca_items from authenticated",
            "grant delete on table public.opositeca_items to authenticated")
        rechaza("anon con INSERT", "grant insert on table public.etiquetas_opositeca to anon",
            "revoke insert on table public.etiquetas_opositeca from anon")
        rechaza("grant option", "grant update on table public.preguntas_parseadas_staging to authenticated with grant option",
            "revoke grant option for update on table public.preguntas_parseadas_staging from authenticated")
        rechaza("politica admin_all distinta", "alter policy invitaciones_referido_admin_all on public.invitaciones_referido with check (true)",
            "alter policy invitaciones_referido_admin_all on public.invitaciones_referido with check (public.es_admin())")
        rechaza("politica de escritura inesperada",
            "create policy opositeca_items_update_x on public.opositeca_items for update to authenticated using (true)",
            "drop policy opositeca_items_update_x on public.opositeca_items")
        rechaza("tabla ausente", "alter table public.etiquetas_opositeca rename to etiquetas_opositeca_x",
            "alter table public.etiquetas_opositeca_x rename to etiquetas_opositeca")
        c.aplicar(migracion) // control: la forma de MAIN aplica
        assertTrue(c.verify().values.all { it == 0L }, c.verify().toString())
    }
}
