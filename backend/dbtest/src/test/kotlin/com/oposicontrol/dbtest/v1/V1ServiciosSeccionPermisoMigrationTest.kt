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
 * HOTFIX HF1 `20261115000000_v1_servicios_seccion_permiso`: Control (Ktor admin gateway -> PostgREST with the service key = role `service_role`) could not
 * save a Market service: the BEFORE INSERT OR UPDATE trigger `trg_servicios_seccion` (20261114400000) ran its SECURITY INVOKER function, which calls the
 * private helper `servicio_seccion_por_tipo` that the migration had revoked from PUBLIC (service_role never had an explicit grant) -> 42501 in production.
 * The sweep found the same defect in `trg_configuracion_v1_bounds` (20261022101500) and `trg_soluciones_markup` / `trg_staging_markup` (20261022102100).
 * The writes here run AS service_role, the way PostgREST runs them; the earlier tests wrote as the superuser and never saw it.
 */
class V1ServiciosSeccionPermisoMigrationTest {
    private val migration = "20261115000000_v1_servicios_seccion_permiso"

    /** MAIN: service_role has USAGE on private (20260928100000, line 251); the V1 schema of the harness does not carry that MAIN grant. */
    private fun Connection.comoEnMain() = ejecutar("grant usage on schema private to service_role")

    private fun <T> Connection.comoServiceRole(bloque: () -> T): T {
        ejecutar("set role service_role")
        try {
            return bloque()
        } finally {
            ejecutar("reset role")
        }
    }

    private fun Connection.error(sql: String): SQLException? =
        try {
            ejecutar(sql)
            null
        } catch (e: SQLException) {
            e
        }

    private fun Connection.seccionDe(codigo: String) = unico("select seccion || '|' || visible || '|' || posicion || '|' || coalesce(precio_tickets::text, '-') from public.servicios where codigo = '$codigo'")

    private val insertViejo = "insert into public.servicios (codigo, nombre, tipo_servicio, precio_tickets) values ('hf1_old', 'Old', 'tutoria', 5)"

    /** Control > Configuracion: the PATCH of AdminControlConfig rewrites a key (same value here). */
    private val patchConfig =
        "update public.configuracion_v1 set valor_integer = valor_integer where clave = (select min(clave) from public.configuracion_v1 where clave <> 'zona_horaria')"

    /** A solution whose rich reasoning is invalid: the trigger must REACH private.markup_valid and answer MARKUP_INVALID (not 42501). */
    private fun Connection.solucion() {
        ejecutar(
            """
            insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa) values ('f9610000-0000-0000-0000-000000000001', 'HF1-1', 'razonada', 'HF1', true);
            insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion)
              select ('0a961000-0000-0000-0000-00000000000' || o)::uuid, 'f9610000-0000-0000-0000-000000000001', chr(64 + o), 'op ' || o, o from generate_series(1, 4) o;
            insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento) values ('f9610000-0000-0000-0000-000000000001', '0a961000-0000-0000-0000-000000000001', 'x');
            """.trimIndent(),
        )
    }
    private val patchRico = "update public.soluciones_pregunta set razonamiento_rico = '{\"v\":9}'::jsonb, razonamiento_markup = 'x' where pregunta_id = 'f9610000-0000-0000-0000-000000000001'"

    @Test
    fun withoutTheHotfixServiceRoleGetsTheProduction42501() = V1Db.escenario(sin = setOf(migration)) { c ->
        c.comoEnMain()
        c.solucion()
        val e = c.comoServiceRole { c.error(insertViejo) }
        assertEquals("42501", e?.sqlState, "control negativo: $e")
        assertTrue(e!!.message!!.contains("servicio_seccion_por_tipo"), "the function of the Ktor log: ${e.message}")
        // with an explicit seccion the helper is never called (that is why not every save failed)
        c.comoServiceRole { c.ejecutar("insert into public.servicios (codigo, nombre, tipo_servicio, precio_tickets, seccion) values ('hf1_exp', 'Exp', 'tutoria', 5, 'SERVICIOS')") }
        // same defect: every config write, and a rich reasoning
        val cfg = c.comoServiceRole { c.error(patchConfig) }
        assertEquals("42501", cfg?.sqlState, "config: $cfg")
        assertTrue(cfg!!.message!!.contains("config_v1_range"), "config: ${cfg.message}")
        val rico = c.comoServiceRole { c.error(patchRico) }
        assertEquals("42501", rico?.sqlState, "markup: $rico")
        assertTrue(rico!!.message!!.contains("markup_valid"), "markup: ${rico.message}")
    }

    @Test
    fun serviceRoleInsertsAndUpdatesServicesWithAndWithoutSeccion() = V1Db.escenario { c ->
        c.comoEnMain()
        c.comoServiceRole {
            // 1. an older Control: no seccion, no visible, no posicion
            c.ejecutar("insert into public.servicios (codigo, nombre, tipo_servicio, precio_tickets) values ('hf1_a', 'A', 'modo_papel', 5)")
            // 2. Control's upsert (Prefer: resolution=merge-duplicates): seccion as an explicit null, then again on the SAME row (ON CONFLICT DO UPDATE)
            val upsert = """
                insert into public.servicios (id, codigo, nombre, tipo_servicio, precio_tickets, seccion, usos, duracion_horas, multiplicador)
                values ('a1f00000-0000-0000-0000-0000000000b1', 'hf1_b', '%s', 'potenciador', %d, null, 1, null, 3)
                on conflict (id) do update set nombre = excluded.nombre, precio_tickets = excluded.precio_tickets, seccion = excluded.seccion,
                  usos = excluded.usos, duracion_horas = excluded.duracion_horas, multiplicador = excluded.multiplicador
            """.trimIndent()
            c.ejecutar(upsert.format("B", 10))
            c.ejecutar(upsert.format("B2", 12))
            // 3. an explicit seccion is kept
            c.ejecutar("insert into public.servicios (codigo, nombre, tipo_servicio, precio_tickets, seccion) values ('hf1_c', 'C', 'premium', 5, 'OPOSITICKETS')")
            assertEquals("OPOSITICKETS|true|0|5", c.seccionDe("hf1_c"))
            // 4. clearing it re-derives it; 5. updating another column keeps it
            c.ejecutar("update public.servicios set seccion = null where codigo = 'hf1_c'")
            c.ejecutar("update public.servicios set precio_tickets = 7 where codigo = 'hf1_a'")
            c.ejecutar("update public.servicios set seccion = 'OPOSITICKETS', visible = false, posicion = 3 where codigo = 'hf1_a'")
        }
        assertEquals("OPOSITICKETS|false|3|7", c.seccionDe("hf1_a"), "old-panel insert derived SERVICIOS, the later explicit values are kept")
        assertEquals("POTENCIADORES|true|0|12", c.seccionDe("hf1_b"), "the upsert's null seccion is derived on insert and on conflict-update")
        assertEquals("B2", c.unico("select nombre from public.servicios where codigo = 'hf1_b'"))
        assertEquals("SERVICIOS|true|0|5", c.seccionDe("hf1_c"), "clearing the section re-derives it from the type")
        // the trigger function no longer needs the writer's USAGE on private: SECURITY DEFINER runs it as its owner
        c.ejecutar("revoke usage on schema private from service_role")
        c.comoServiceRole { c.ejecutar("insert into public.servicios (codigo, nombre, tipo_servicio, precio_tickets) values ('hf1_d', 'D', 'doble_oportunidad', 5)") }
        assertEquals("POTENCIADORES|true|0|5", c.seccionDe("hf1_d"))
        // the helpers and the trigger functions stay closed to the client roles; the helper is not granted to service_role either
        assertEquals(
            "false|false|false|false|false|false|true",
            c.unico(
                "select has_function_privilege('anon', 'private.servicio_seccion_por_tipo(text)', 'execute') || '|' || " +
                    "has_function_privilege('authenticated', 'private.servicio_seccion_por_tipo(text)', 'execute') || '|' || " +
                    "has_function_privilege('service_role', 'private.servicio_seccion_por_tipo(text)', 'execute') || '|' || " +
                    "has_function_privilege('anon', 'private.tg_servicios_seccion()', 'execute') || '|' || " +
                    "has_function_privilege('authenticated', 'private.tg_servicios_seccion()', 'execute') || '|' || " +
                    "has_function_privilege('authenticated', 'private.config_v1_range(text)', 'execute') || '|' || " +
                    "(select prosecdef from pg_proc where oid = 'private.tg_servicios_seccion()'::regprocedure)",
            ),
        )
    }

    @Test
    fun configAndRichReasoningWritesReachTheirValidatorsAsServiceRole() = V1Db.escenario { c ->
        c.comoEnMain()
        c.solucion()
        assertNull(c.comoServiceRole { c.error(patchConfig) }, "Control > Configuracion saves")
        val fuera = c.comoServiceRole {
            c.error("update public.configuracion_v1 set valor_integer = -999999 where clave = (select min(clave) from public.configuracion_v1 where clave <> 'zona_horaria')")
        }
        assertTrue(fuera?.message?.contains("CONFIG_VALUE_OUT_OF_BOUNDS") == true, "the bounds still apply: $fuera")
        val rico = c.comoServiceRole { c.error(patchRico) }
        assertTrue(rico?.message?.contains("MARKUP_INVALID") == true, "the markup validator runs (no 42501): $rico")
    }

    @Test
    fun theSweepQueryOfTheActaFindsNoTriggerCallingAFunctionServiceRoleCannotRun() = V1Db.escenario(sin = setOf(migration)) { c ->
        c.comoEnMain()
        val acta = Slice0Db.leer("docs/ledger/2026/11/2026-11-15-hf1-servicios-seccion.md")
        val sweep = acta.substringAfter("-- SWEEP-BEGIN\n").substringBefore("-- SWEEP-END")
        assertTrue(sweep.contains("pg_trigger"), "sweep query in the acta")
        assertEquals(
            listOf(
                "configuracion_v1|trg_configuracion_v1_bounds|private.tg_configuracion_v1_bounds()|private.config_v1_range(text)",
                "preguntas_parseadas_staging|trg_staging_markup|private.tg_staging_markup()|private.markup_plain(jsonb)",
                "preguntas_parseadas_staging|trg_staging_markup|private.tg_staging_markup()|private.markup_valid(jsonb)",
                "servicios|trg_servicios_seccion|private.tg_servicios_seccion()|private.servicio_seccion_por_tipo(text)",
                "soluciones_pregunta|trg_soluciones_markup|private.tg_soluciones_markup()|private.markup_plain(jsonb)",
                "soluciones_pregunta|trg_soluciones_markup|private.tg_soluciones_markup()|private.markup_valid(jsonb)",
            ),
            c.filas(sweep).map { it.take(4).joinToString("|").removePrefix("public.").replace("|public.", "|") },
            "before the hotfix: the production bug and the three found by the sweep",
        )
        c.ejecutar(Slice0Db.leer("supabase/migrations/$migration.sql"))
        assertEquals(emptyList(), c.filas(sweep), "after the hotfix: nothing left")
    }

    @Test
    fun theVerifyBlockProvesTheEndStateAndTheRollbackBringsTheBugBack() = V1Db.escenario { c ->
        c.comoEnMain()
        val verify = Slice0Db.verifyDe("supabase/migrations/$migration.sql")
        val rows = c.filas(verify)
        assertEquals(6, rows.size, "VERIFY rows: $rows")
        assertEquals(emptyList(), rows.filter { it[1] != "0" }, "every VERIFY counter is 0")
        c.ejecutar(Slice0Db.rollbackDe("supabase/migrations/$migration.sql"))
        assertEquals(listOf("hf1_trigger_function_not_security_definer|4"), c.filas(verify).filter { it[1] != "0" }.map { it[0] + "|" + it[1] }, "the rollback is the invoker functions")
        val e = c.comoServiceRole { c.error(insertViejo) }
        assertEquals("42501", e?.sqlState, "the rollback brings the bug back: $e")
        c.ejecutar(Slice0Db.leer("supabase/migrations/$migration.sql"))
        assertEquals(emptyList(), c.filas(verify).filter { it[1] != "0" }, "re-applying converges")
        c.comoServiceRole { c.ejecutar(insertViejo) }
        assertEquals("SERVICIOS|true|0|5", c.seccionDe("hf1_old"))
    }
}
