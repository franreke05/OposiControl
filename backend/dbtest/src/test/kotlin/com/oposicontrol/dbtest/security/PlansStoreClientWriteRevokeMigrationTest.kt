package com.oposicontrol.dbtest.security

import com.oposicontrol.dbtest.PostgresHarness
import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.aplicar
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.postgresql.util.PSQLException

/**
 * SEC-BOT-P3: migracion 20261023100600 (OposiBot, auditada por OposiControl). Ningun cliente (anon, authenticated) conserva
 * INSERT/UPDATE/DELETE/TRUNCATE sobre `suscripciones`, `beneficios_plan` y `capacidades_especialidad`.
 *
 * Dos formas de partida:
 *  - MAIN (captura de catalogo 2026-10-14, baseline_main.sql): los clientes solo tienen SELECT. La migracion es un NO-OP que lo
 *    fija: mismo catalogo antes y despues, VERIFY = 0 antes y despues.
 *  - DERIVA (privilegios por defecto de Supabase `GRANT ALL ON TABLES TO anon, authenticated`, lo que reciben estas tablas al
 *    recrearse con `create table` como en el bootstrap local): RLS para INSERT/UPDATE/DELETE pero NO para TRUNCATE. Control
 *    NEGATIVO: `authenticated` vacia `suscripciones`. Tras la migracion: 42501 `permission denied` para todo cliente.
 *
 * Las tablas son stubs con el ACL y las politicas VERBATIM de la captura de MAIN (columnas minimas: la migracion solo mira ACL,
 * politicas y RLS). `planes_suscripcion` es el stub que lee la politica de `beneficios_plan`.
 */
class PlansStoreClientWriteRevokeMigrationTest {
    private val migracion = "supabase/migrations/20261023100600_security_plans_store_client_write_revoke.sql"
    private val tablas = listOf("suscripciones", "beneficios_plan", "capacidades_especialidad")

    private val ua = "aaaaaaaa-0000-0000-0000-000000000001" // admin
    private val ub = "bbbbbbbb-0000-0000-0000-000000000002" // usuario
    private val uc = "cccccccc-0000-0000-0000-000000000003" // otro usuario
    private val planVisible = "b1000000-0000-0000-0000-000000000001"
    private val planOculto = "b1000000-0000-0000-0000-000000000002"
    private val especialidad = "e1000000-0000-0000-0000-000000000001"

    private val formaMain = """
        create table public.planes_suscripcion (id uuid primary key, activo boolean not null default true, visible boolean not null default true);
        grant select on table public.planes_suscripcion to anon, authenticated, service_role;
        create table public.beneficios_plan (id uuid primary key default gen_random_uuid(), plan_id uuid not null references public.planes_suscripcion(id) on delete cascade,
          codigo_beneficio text not null, valor_integer integer);
        create table public.capacidades_especialidad (especialidad_id uuid primary key, permite_rafagas boolean not null default true);
        create table public.suscripciones (id uuid primary key default gen_random_uuid(), usuario_id uuid not null references auth.users(id) on delete cascade,
          estado text not null);
        alter table public.beneficios_plan enable row level security;
        alter table public.capacidades_especialidad enable row level security;
        alter table public.suscripciones enable row level security;
        create policy canonical_v2_benefits_public_read on public.beneficios_plan as permissive for select to anon, authenticated using ((exists ( select 1
          from public.planes_suscripcion ps where ((ps.id = beneficios_plan.plan_id) and ps.activo and ps.visible))));
        create policy canonical_v2_capabilities_public_read on public.capacidades_especialidad as permissive for select to anon, authenticated using (true);
        create policy suscripciones_select_propio on public.suscripciones as permissive for select to authenticated
          using (((usuario_id = ( select auth.uid() as uid)) or public.es_admin()));
        revoke all on table public.beneficios_plan from public, anon, authenticated, service_role;
        revoke all on table public.capacidades_especialidad from public, anon, authenticated, service_role;
        revoke all on table public.suscripciones from public, anon, authenticated, service_role;
        grant delete, insert, references, select, trigger, truncate, update on table public.beneficios_plan to service_role;
        grant select on table public.beneficios_plan to anon, authenticated;
        grant delete, insert, references, select, trigger, truncate, update on table public.capacidades_especialidad to service_role;
        grant select on table public.capacidades_especialidad to anon, authenticated;
        grant delete, insert, references, select, trigger, truncate, update on table public.suscripciones to service_role;
        grant select on table public.suscripciones to authenticated;
        insert into public.roles_usuario (usuario_id, rol_id) values ('$ua', '22222222-2222-2222-2222-222222222222');
        insert into public.planes_suscripcion (id, visible) values ('$planVisible', true), ('$planOculto', false);
        insert into public.beneficios_plan (plan_id, codigo_beneficio, valor_integer) values ('$planVisible', 'inmersivos_incluidos', 2), ('$planOculto', 'inmersivos_incluidos', 9);
        insert into public.capacidades_especialidad (especialidad_id) values ('$especialidad');
        insert into public.suscripciones (usuario_id, estado) values ('$ub', 'activa'), ('$uc', 'cancelada');
    """.trimIndent()

    /** Lo que dejan los privilegios por defecto de Supabase al recrear la tabla (concedido por el dueno, sin grant option). */
    private val deriva = "grant all on table public.suscripciones, public.beneficios_plan, public.capacidades_especialidad to anon, authenticated"

    /** ECO-11 (20261022102600): service_role solo REFERENCES, SELECT, TRIGGER sobre suscripciones. */
    private val eco11 = "revoke insert, update, delete, truncate on table public.suscripciones from service_role"

    private fun <T> escenario(vararg extra: String, conMigracion: Boolean = true, bloque: (Connection) -> T): T = PostgresHarness.escenario { c ->
        c.ejecutar(formaMain)
        extra.forEach { c.ejecutar(it) }
        if (conMigracion) c.aplicar(migracion)
        bloque(c)
    }

    /** Sentencia como cliente de PostgREST (`set local role` + claim sub). Devuelve (SQLSTATE, rutina del servidor: independiente del idioma) o (null, filas). */
    private fun Connection.comoCliente(sub: String?, sql: String, rol: String = "authenticated"): Pair<String?, String> {
        ejecutar("begin")
        return try {
            ejecutar("set local role $rol")
            ejecutar("select set_config('request.jwt.claim.sub', '${sub ?: ""}', true)")
            val n = createStatement().use { it.executeUpdate(sql) }
            ejecutar("commit")
            null to n.toString()
        } catch (e: SQLException) {
            runCatching { ejecutar("rollback") }
            e.sqlState to ((e as? PSQLException)?.serverErrorMessage?.routine ?: "")
        }
    }

    private fun Connection.lecturaComo(sub: String?, sql: String, rol: String = "authenticated"): String? {
        ejecutar("begin")
        return try {
            ejecutar("set local role $rol")
            ejecutar("select set_config('request.jwt.claim.sub', '${sub ?: ""}', true)")
            unico(sql)
        } finally {
            ejecutar("rollback")
        }
    }

    private fun Connection.verify(): Map<String, Long> =
        filas(Slice0Db.verifyDe(migracion)).associate { it[0]!! to it[1]!!.toLong() }

    private fun Connection.estado(): String = unico(
        """
        select string_agg(x, E'\n' order by x) from (
          select c.relname || ':' || c.relrowsecurity::text || ':' || coalesce(c.relacl::text, '') as x from pg_class c
          where c.relnamespace = 'public'::regnamespace and c.relname = any(array[${tablas.joinToString { "'$it'" }}])
          union all
          select c.relname || '.' || p.polname || ':' || p.polcmd::text || ':' || p.polroles::text || ':' ||
                 coalesce(pg_get_expr(p.polqual, p.polrelid), '') || ':' || coalesce(pg_get_expr(p.polwithcheck, p.polrelid), '')
          from pg_policy p join pg_class c on c.oid = p.polrelid
          where c.relnamespace = 'public'::regnamespace and c.relname = any(array[${tablas.joinToString { "'$it'" }}])
        ) s
        """.trimIndent(),
    )!!

    private fun Connection.aclServiceRole(): String = unico(
        """
        select string_agg(c.relname || ' ' || a.privilege_type || ' ' || a.is_grantable::text, ', ' order by c.relname, a.privilege_type)
        from pg_class c cross join lateral aclexplode(c.relacl) a
        where c.relnamespace = 'public'::regnamespace and c.relname = any(array[${tablas.joinToString { "'$it'" }}])
          and a.grantee = 'service_role'::regrole
        """.trimIndent(),
    )!!

    private fun Connection.filasDe(tabla: String) = unico("select count(*)::text from public.$tabla")!!

    /** Las cuatro escrituras de cliente sobre [tabla]; ninguna depende de datos salvo TRUNCATE (el privilegio se comprueba antes). */
    private fun escrituras(tabla: String): List<String> {
        val col = mapOf("suscripciones" to "estado", "beneficios_plan" to "valor_integer", "capacidades_especialidad" to "permite_rafagas")[tabla]
        return listOf(
            "insert into public.$tabla select * from public.$tabla where false",
            "update public.$tabla set $col = $col",
            "delete from public.$tabla",
            "truncate public.$tabla cascade",
        )
    }

    private fun Connection.ningunClienteEscribe() {
        for (tabla in tablas) for (sql in escrituras(tabla)) {
            for ((sub, rol) in listOf(ua to "authenticated", ub to "authenticated", null to "anon")) {
                val (estado, msg) = comoCliente(sub, sql, rol)
                assertEquals("42501", estado, "$rol $sub: $sql")
                assertEquals("aclcheck_error", msg, "$rol $sub: $sql (privilegio, no RLS)")
            }
        }
        assertEquals("2", filasDe("suscripciones"))
        assertEquals("2", filasDe("beneficios_plan"))
        assertEquals("1", filasDe("capacidades_especialidad"))
    }

    /** MAIN capturado: ya no hay escritura de cliente; la migracion no cambia nada y lo fija. */
    @Test
    fun enLaFormaDeMainEsUnNoOpYVerifyEsCeroAntesYDespues(): Unit = escenario(conMigracion = false) { c ->
        val main = c.estado()
        val antes = c.verify()
        assertEquals(5, antes.size, antes.toString())
        assertTrue(antes.values.all { it == 0L }, "MAIN ya cumple: $antes")
        c.ningunClienteEscribe()
        c.aplicar(migracion)
        assertEquals(main, c.estado(), "ACL, RLS y politicas identicos")
        assertTrue(c.verify().values.all { it == 0L }, c.verify().toString())
        c.ningunClienteEscribe()
    }

    /** Control NEGATIVO de la deriva: RLS frena I/U/D pero TRUNCATE no pasa por RLS. */
    @Test
    fun antesDeLaMigracionUnClienteConLosPrivilegiosPorDefectoVaciaLasTablas(): Unit = escenario(deriva, conMigracion = false) { c ->
        val v = c.verify()
        assertEquals(2L * 4 * tablas.size, v["plans_store_client_write_privilege"], "VERIFY detecta la deriva: $v")
        assertEquals("0", c.comoCliente(ub, "update public.suscripciones set estado = 'activa' where usuario_id = '$uc'").second,
            "UPDATE: RLS sin politica de escritura, 0 filas")
        val ins = c.comoCliente(ub, "insert into public.capacidades_especialidad (especialidad_id) values (gen_random_uuid())")
        assertEquals("42501", ins.first)
        assertEquals("ExecWithCheckOptions", ins.second, "INSERT lo frena RLS, no el privilegio")
        assertNull(c.comoCliente(ub, "truncate public.suscripciones").first, "deriva: un usuario hace TRUNCATE")
        assertEquals("0", c.filasDe("suscripciones"), "todas las suscripciones borradas por un cliente")
        assertNull(c.comoCliente(null, "truncate public.beneficios_plan", rol = "anon").first, "deriva: anon vacia el catalogo")
        assertEquals("0", c.filasDe("beneficios_plan"))
    }

    @Test
    fun trasLaMigracionNingunClienteEscribeLasTresTablas(): Unit = escenario(deriva) { c ->
        c.ningunClienteEscribe()
        val v = c.verify()
        assertEquals(5, v.size, v.toString())
        assertTrue(v.values.all { it == 0L }, "VERIFY: $v")
    }

    /** Las lecturas que dependen del SELECT de cliente siguen igual (la app lee con service_role; esto es lo que MAIN expone). */
    @Test
    fun lasLecturasDeClienteNoCambian() {
        for (forma in listOf(arrayOf<String>(), arrayOf(deriva))) escenario(*forma) { c ->
            assertEquals("1", c.lecturaComo(ub, "select count(*)::text from public.suscripciones"), "usuario: solo la suya")
            assertEquals("2", c.lecturaComo(ua, "select count(*)::text from public.suscripciones"), "admin: todas (politica de MAIN)")
            assertEquals("1", c.lecturaComo(null, "select count(*)::text from public.beneficios_plan", rol = "anon"), "anon: solo plan visible")
            assertEquals("1", c.lecturaComo(ub, "select count(*)::text from public.beneficios_plan"))
            assertEquals("1", c.lecturaComo(null, "select count(*)::text from public.capacidades_especialidad", rol = "anon"))
            assertEquals("1", c.lecturaComo(ub, "select count(*)::text from public.capacidades_especialidad"))
        }
        escenario { c ->
            val e = runCatching { c.lecturaComo(null, "select count(*)::text from public.suscripciones", rol = "anon") }.exceptionOrNull() as SQLException?
            assertEquals("42501", e?.sqlState, "MAIN: anon nunca tuvo SELECT sobre suscripciones (sin cambio)")
        }
    }

    /** service_role (Ktor) no se toca: ni en la forma de MAIN ni con ECO-11 ya aplicado. */
    @Test
    fun serviceRoleQuedaExactamenteIgual() {
        for (forma in listOf(arrayOf<String>(), arrayOf(deriva), arrayOf(eco11), arrayOf(deriva, eco11))) escenario(*forma, conMigracion = false) { c ->
            val antes = c.aclServiceRole()
            c.aplicar(migracion)
            assertEquals(antes, c.aclServiceRole(), "ACL de service_role (${forma.toList()})")
            assertNull(c.comoCliente(null, "update public.beneficios_plan set valor_integer = 3 where plan_id = '$planVisible'", rol = "service_role").first)
            assertNull(c.comoCliente(null, "update public.capacidades_especialidad set permite_rafagas = false", rol = "service_role").first)
            assertEquals("2", c.lecturaComo(null, "select count(*)::text from public.suscripciones", rol = "service_role"))
            val esc = c.comoCliente(null, "update public.suscripciones set estado = estado", rol = "service_role").first
            if (eco11 in forma) assertEquals("42501", esc, "ECO-11 se conserva") else assertNull(esc)
            assertTrue(c.verify().values.all { it == 0L }, c.verify().toString())
        }
    }

    @Test
    fun elRollbackRestauraElAclDeMainYTodoEsReaplicable(): Unit = escenario(conMigracion = false) { c ->
        val main = c.estado()
        c.aplicar(migracion)
        c.aplicar(migracion) // re-ejecucion: no-op
        assertEquals(main, c.estado())
        c.ejecutar(Slice0Db.rollbackDe(migracion))
        assertEquals(main, c.estado(), "ACL, RLS y politicas de MAIN byte a byte")
        c.ejecutar(Slice0Db.rollbackDe(migracion)) // re-ejecutable
        assertEquals(main, c.estado())
        c.aplicar(migracion)
        assertEquals(main, c.estado())
        assertTrue(c.verify().values.all { it == 0L }, c.verify().toString())
    }

    /** Sobre la deriva el ROLLBACK devuelve el ACL de MAIN (solo SELECT), no reabre la escritura que MAIN nunca tuvo. */
    @Test
    fun sobreLaDerivaElRollbackNoReabreLaEscrituraYLaReaplicacionFunciona(): Unit = escenario(deriva, conMigracion = false) { c ->
        val derivada = c.estado()
        c.aplicar(migracion)
        val aplicada = c.estado()
        assertNotEquals(derivada, aplicada)
        c.ejecutar(Slice0Db.rollbackDe(migracion))
        assertEquals(aplicada, c.estado(), "los SELECT ya estaban: el ROLLBACK no cambia nada")
        c.ningunClienteEscribe()
        c.aplicar(migracion)
        assertEquals(aplicada, c.estado())
        assertTrue(c.verify().values.all { it == 0L }, c.verify().toString())
    }

    @Test
    fun preflightFallaCerradoSinCambiarNada(): Unit = escenario(deriva, conMigracion = false) { c ->
        fun rechaza(caso: String, sembrar: String, deshacer: String) {
            c.ejecutar(sembrar)
            val antes = c.estado()
            val e = runCatching { c.ejecutar(Slice0Db.leer(migracion)) }.exceptionOrNull() as PSQLException?
            runCatching { c.ejecutar("rollback") }
            c.autoCommit = true
            assertEquals("P0001", e?.sqlState, caso)
            assertEquals("PLANS_STORE_WRITE_PREFLIGHT", e?.serverErrorMessage?.message, caso)
            assertEquals(antes, c.estado(), "nada cambiado ($caso)")
            c.ejecutar(deshacer)
        }
        rechaza("PUBLIC con INSERT", "grant insert on table public.beneficios_plan to public", "revoke insert on table public.beneficios_plan from public")
        rechaza("grant option", "grant truncate on table public.suscripciones to authenticated with grant option",
            "revoke grant option for truncate on table public.suscripciones from authenticated")
        val tercero = "plans_store_tercero_" + UUID.randomUUID().toString().take(8)
        c.ejecutar("create role $tercero nologin")
        try {
            rechaza(
                "concedido por otro rol",
                "grant insert on table public.capacidades_especialidad to $tercero with grant option; " +
                    "set role $tercero; grant insert on table public.capacidades_especialidad to anon; reset role",
                "revoke insert on table public.capacidades_especialidad from $tercero cascade",
            )
        } finally {
            c.ejecutar("drop role $tercero")
        }
        rechaza("politica de escritura", "create policy x_insert on public.suscripciones for insert to authenticated with check (true)",
            "drop policy x_insert on public.suscripciones")
        rechaza("politica ALL a PUBLIC", "create policy x_all on public.beneficios_plan for all using (true)",
            "drop policy x_all on public.beneficios_plan")
        rechaza("RLS desactivada", "alter table public.capacidades_especialidad disable row level security",
            "alter table public.capacidades_especialidad enable row level security")
        rechaza("SELECT de MAIN ausente", "revoke select on table public.capacidades_especialidad from anon",
            "grant select on table public.capacidades_especialidad to anon")
        rechaza("tabla ausente", "alter table public.beneficios_plan rename to beneficios_plan_x",
            "alter table public.beneficios_plan_x rename to beneficios_plan")
        c.aplicar(migracion) // control: la forma deshecha aplica
        assertTrue(c.verify().values.all { it == 0L }, c.verify().toString())
    }
}
