package com.oposicontrol.dbtest.v1

import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `20261115100000_v1_racha_calendario` (F31, FA56, D3-26): the month calendar of the "Manten tu racha" panel, read from registros_reto_diario.
 * The body is private.racha_calendario(user, month, now): the scenarios fix the clock (Madrid) so the days and the month boundary (DST) are exact;
 * public.app_racha_calendario always passes the DB clock.
 */
class V1RachaCalendarioMigrationTest {
    private val migration = "20261115100000_v1_racha_calendario"
    private val esp = "e1000000-0000-0000-0000-0000000031e1"
    private val com = "c1000000-0000-0000-0000-0000000031e1"
    private val exam = "e3100000-0000-0000-0000-000000000001"
    private val q = "f3100000-0000-0000-0000-000000000001"
    private val ok = "0c310000-0000-0000-0000-000000000001"
    private val ko = "0c310000-0000-0000-0000-000000000002"
    private val plan = "b3100000-0000-0000-0000-000000000001"
    private fun user(n: Int) = "a0000000-0000-0000-0000-0000000031" + n.toString(16).padStart(2, '0')

    /** 2026-11-20 10:00 in Madrid (CET, UTC+1). */
    private val nov20 = "timestamptz '2026-11-20 09:00:00+00'"

    private fun Connection.world(n: Int, specialty: Boolean = true) {
        val users = (1..n).map(::user)
        ejecutar(
            """
            insert into auth.users (id, email, aud, role, email_confirmed_at) values ${users.joinToString(", ") { "('$it', '$it@test.local', 'authenticated', 'authenticated', now())" }};
            insert into public.especialidades (id, codigo, nombre, slug, activa) values ('$esp', 'F31', 'F31', 'f31', true);
            insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('$com', 'F31', 'Com F31', 'com-f31');
            insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id) values
              ${users.mapIndexed { i, u -> "('$u', 'u$i', 'f31_u$i', true, 'activa', ${if (specialty) "'$esp'" else "null"})" }.joinToString(", ")}
              on conflict (usuario_id) do update set especialidad_principal_id = excluded.especialidad_principal_id;
            insert into public.transacciones_cartera (usuario_id, tipo, cantidad, descripcion, referencia_tipo, idempotency_key)
              select u, 'ajuste', 2000, 'saldo inicial', 'admin', 'f31-seed-' || u from unnest(array[${users.joinToString(", ") { "'$it'::uuid" }}]) u;
            insert into public.planes_suscripcion (id, codigo, nombre, nivel, activo, visible, posicion) values ('$plan', 'gratuito_f31', 'Gratuito', 0, true, true, 0);
            insert into public.beneficios_plan (plan_id, codigo_beneficio, valor_integer) values ('$plan', 'ot_reto_diario', 40), ('$plan', 'ot_bonus_racha', 20);
            """.trimIndent(),
        )
    }

    /** Same one-question published exam as the F28 test: the real answer function closes the reto with one answer. */
    private fun Connection.exam() = ejecutar(
        """
        begin;
        set local session_replication_role = replica;
        insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en)
          values ('$exam', 'F31-1', '$esp', '$com', 'Examen F31', 'rafaga', 'premium', true, now() - interval '1 day');
        insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa) values ('$q', 'F31-Q1', 'rafaga', 'Pregunta F31', true);
        insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion) values ('$ok', '$q', 'A', 'Si', 1), ('$ko', '$q', 'B', 'No', 2);
        insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento) values ('$q', '$ok', 'x');
        insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen) values ('$exam', '$q', 1, 1);
        commit;
        """.trimIndent(),
    )

    /** One active national reto of the specialty for every day in [desde, hasta] (a day with an active reto and no streak day breaks the streak). */
    private fun Connection.retos(desde: String, hasta: String) = ejecutar(
        "insert into public.retos_diarios (fecha, titulo, objetivo_preguntas, aciertos_necesarios, recompensa_puntos, activo, empieza_en, termina_en, especialidad_id, comunidad_id, segmento_usuario) " +
            "select d::date, 'Reto', 3, 2, 0, true, d, d + interval '1 day', '$esp', null, 'todos' from generate_series(date '$desde', date '$hasta', interval '1 day') d",
    )

    private fun Connection.buy(u: String, codigo: String): String {
        unico("select outcome from public.app_redeem_service('$u', '$codigo', 'buy-$u-$codigo-' || gen_random_uuid(), null)")
        return unico("select a.id::text from public.activaciones_servicio_usuario a join public.servicios s on s.id = a.servicio_id where a.usuario_id = '$u' and s.tipo_servicio = '$codigo' order by a.activa_desde desc limit 1")!!
    }

    /** A row of the user for that day: [completado], [superado], [progreso], [protector] (activation id). */
    private fun Connection.dia(u: String, fecha: String, completado: Boolean, superado: Boolean = false, progreso: Int = if (completado) 3 else 0, protector: String? = null) = ejecutar(
        "insert into public.registros_reto_diario (usuario_id, reto_id, dia, progreso, completado, superado, protector_usado_id) " +
            "select '$u', d.id, date '$fecha', $progreso, $completado, $superado, ${protector?.let { "'$it'" } ?: "null"} from public.retos_diarios d where d.fecha = date '$fecha' and d.especialidad_id = '$esp'",
    )

    private fun Connection.cal(u: String, mes: String?, now: String = nov20): String =
        unico("select private.racha_calendario('$u', ${mes?.let { "date '$it-01'" } ?: "null"}, $now)::text")!!
    private fun Connection.j(json: String, path: String): String? = unico("select ('${json.replace("'", "''")}'::jsonb) #>> '{$path}'")
    private fun Connection.estados(json: String): Map<String, String> =
        filas("select e ->> 'fecha', e ->> 'estado' from jsonb_array_elements(('${json.replace("'", "''")}'::jsonb) -> 'dias') e").associate { it[0]!! to it[1]!! }
    private fun Connection.invalid(block: () -> Unit) {
        val e = runCatching(block).exceptionOrNull()
        assertTrue(e?.message?.contains("RACHA_MES_INVALID") == true, "expected RACHA_MES_INVALID, got $e")
    }

    @Test
    fun aMonthShowsPassedFailedProtectedPendingAndIdleDays() = V1Db.escenario { c ->
        c.world(2)
        val (a, b) = listOf(user(1), user(2))
        c.retos("2026-10-28", "2026-11-20")
        val dual = c.buy(a, "protector_dual")
        val racha = c.buy(a, "protector_racha")
        val tickets = c.buy(a, "protector_tickets")
        c.dia(a, "2026-10-30", true, true); c.dia(a, "2026-10-31", true, true)
        c.dia(a, "2026-11-01", true, true)
        c.dia(a, "2026-11-02", true, protector = dual)       // live streak (11-01): the dual saved it
        c.dia(a, "2026-11-03", true, true)
        c.dia(a, "2026-11-04", true)                         // failed, no protector
        c.dia(a, "2026-11-05", true, protector = dual)       // no live streak (11-04 had a reto): the dual kept only the tickets
        c.dia(a, "2026-11-06", true, protector = racha)      // a racha protector is only taken with a live streak
        c.dia(a, "2026-11-07", true, true)
        c.dia(a, "2026-11-08", true, protector = tickets)    // tickets kept, streak lost
        c.dia(a, "2026-11-10", false, progreso = 1)          // started, never finished
        c.dia(a, "2026-11-11", false)                        // opened, never answered
        listOf("12", "13", "14", "19").forEach { c.dia(a, "2026-11-$it", true, true) }
        c.dia(a, "2026-11-20", false, progreso = 1)          // today, in progress
        c.ejecutar("insert into public.rachas_usuario (usuario_id, racha_actual_dias, mejor_racha_dias, ultima_actividad_fecha) values ('$a', 1, 7, date '2026-11-19')")
        // another user's rows on the same days never leak
        c.dia(b, "2026-11-04", true, true); c.dia(b, "2026-11-09", true, true)

        val r = c.cal(a, null)
        assertEquals("2026-11|2026-11-20", "${c.j(r, "mes")}|${c.j(r, "hoy")}", "default = current Madrid month")
        val e = c.estados(r)
        assertEquals(20, e.size, "every day up to today, future days omitted: $e")
        assertEquals(
            mapOf(
                "01" to "SUPERADO", "02" to "PROTEGIDO", "03" to "SUPERADO", "04" to "FALLADO", "05" to "FALLADO", "06" to "PROTEGIDO", "07" to "SUPERADO",
                "08" to "FALLADO", "09" to "SIN_ACTIVIDAD", "10" to "FALLADO", "11" to "SIN_ACTIVIDAD", "12" to "SUPERADO", "13" to "SUPERADO", "14" to "SUPERADO",
                "15" to "SIN_ACTIVIDAD", "16" to "SIN_ACTIVIDAD", "17" to "SIN_ACTIVIDAD", "18" to "SIN_ACTIVIDAD", "19" to "SUPERADO", "20" to "PENDIENTE",
            ).mapKeys { "2026-11-${it.key}" },
            e,
        )
        assertEquals("3", c.j(r, "rachaMes"), "01-03 and 12-14 are runs of 3 inside the month (10-30/31 never count)")
        assertEquals("1|7", "${c.j(r, "rachaActual")}|${c.j(r, "rachaMaxima")}", "the D3-04 numbers (19th passed, today pending)")
        assertEquals("2026-11-01", col0(c, "select (('${r}'::jsonb) -> 'dias' -> 0) ->> 'fecha'").single(), "days in order")

        val oct = c.cal(a, "2026-10")
        val eo = c.estados(oct)
        assertEquals(31, eo.size, "a past month: every day")
        assertEquals("SIN_ACTIVIDAD|SIN_ACTIVIDAD|SUPERADO|SUPERADO", listOf("27", "28", "30", "31").joinToString("|") { eo["2026-10-$it"]!! })
        assertEquals("2", c.j(oct, "rachaMes"))

        // the other user sees only their own rows
        val eb = c.estados(c.cal(b, null))
        assertEquals("SUPERADO|SIN_ACTIVIDAD|SUPERADO", listOf("04", "05", "09").joinToString("|") { eb["2026-11-$it"]!! })
        assertEquals("PENDIENTE", eb["2026-11-20"], "today without a row: the reto is still to be played")

        // range: from the month of the first reto up to the current month
        c.invalid { c.cal(a, "2026-09") }
        c.invalid { c.cal(a, "2026-12") }
        c.invalid { c.cal(b, "2026-10") }  // b's first reto is in November
    }

    @Test
    fun theMonthBoundaryFollowsMadridAcrossDaylightSavingChanges() = V1Db.escenario { c ->
        c.world(1)
        val u = user(1)
        c.retos("2026-10-01", "2026-10-01")
        c.dia(u, "2026-10-01", true, true)
        // autumn (CET since 2026-10-25): 22:59:59 UTC is 23:59:59 on the 31st in Madrid, 23:00 UTC is already November 1st
        val lastOct = c.cal(u, null, "timestamptz '2026-10-31 22:59:59+00'")
        assertEquals("2026-10|2026-10-31|31", "${c.j(lastOct, "mes")}|${c.j(lastOct, "hoy")}|${c.estados(lastOct).size}")
        assertEquals("PENDIENTE", c.estados(lastOct)["2026-10-31"])
        val firstNov = c.cal(u, null, "timestamptz '2026-10-31 23:00:00+00'")
        assertEquals("2026-11|2026-11-01|1", "${c.j(firstNov, "mes")}|${c.j(firstNov, "hoy")}|${c.estados(firstNov).size}")
        assertEquals("31", c.estados(c.cal(u, "2026-10", "timestamptz '2026-10-31 23:00:00+00'")).size.toString(), "October has 31 days, the 25-hour day included")
        c.invalid { c.cal(u, "2026-11", "timestamptz '2026-10-31 22:59:59+00'") }
        // spring (CEST since 2027-03-28): 21:59:59 UTC is 23:59:59 on March 31st, 22:00 UTC is April 1st
        assertEquals("2027-03|31", c.cal(u, null, "timestamptz '2027-03-31 21:59:59+00'").let { "${c.j(it, "mes")}|${c.estados(it).size}" })
        assertEquals("2027-04|2027-04-01", c.cal(u, null, "timestamptz '2027-03-31 22:00:00+00'").let { "${c.j(it, "mes")}|${c.j(it, "hoy")}" })
        assertEquals("31", c.estados(c.cal(u, "2027-03", "timestamptz '2027-03-31 22:00:00+00'")).size.toString(), "March has 31 days, the 23-hour day included")
    }

    @Test
    fun thePublicFunctionUsesTheDbClockAndTheRealAnswerWritesWhatTheCalendarReads() = V1Db.escenario { c ->
        c.world(3)
        c.exam()
        val (saved, fresh, plain) = listOf(user(1), user(2), user(3))
        // a live streak whose last day was yesterday, and a racha protector: the real 6-argument answer saves the failed day
        listOf(saved, plain).forEach {
            c.ejecutar("insert into public.rachas_usuario (usuario_id, racha_actual_dias, mejor_racha_dias, ultima_actividad_fecha) values ('$it', 5, 5, private.reto_today(clock_timestamp()) - 1)")
        }
        c.buy(saved, "protector_racha")
        fun reto(u: String) = c.j(c.unico("select public.app_get_daily_challenge('$u')::text")!!, "retoId")!!
        val today = c.unico("select private.reto_today(clock_timestamp())::text")!!
        val month = today.substring(0, 7)

        val before = c.unico("select public.app_racha_calendario('$fresh')::text")!!
        assertEquals(month, c.j(before, "mes"))
        assertEquals("PENDIENTE", c.estados(before)[today], "today, nothing played yet")

        c.unico("select public.app_answer_daily_challenge('$saved', '${reto(saved)}', '$q', '$ko', null, true)::text")
        val s = c.unico("select public.app_racha_calendario('$saved', null)::text")!!
        assertEquals("PROTEGIDO|6", "${c.estados(s)[today]}|${c.j(s, "rachaActual")}")
        assertEquals("1", c.j(s, "rachaMes"))

        c.unico("select public.app_answer_daily_challenge('$plain', '${reto(plain)}', '$q', '$ko', null, true)::text")
        assertEquals("FALLADO", c.estados(c.unico("select public.app_racha_calendario('$plain')::text")!!)[today])
        c.unico("select public.app_answer_daily_challenge('$fresh', '${reto(fresh)}', '$q', '$ok', null, true)::text")
        val f = c.unico("select public.app_racha_calendario('$fresh', date '$month-15')::text")!!
        assertEquals("SUPERADO|1|1", "${c.estados(f)[today]}|${c.j(f, "rachaMes")}|${c.j(f, "rachaActual")}", "any day of the month selects it")

        val e = runCatching { c.unico("select public.app_racha_calendario('a0000000-0000-0000-0000-0000000031ff')") }.exceptionOrNull()
        assertTrue(e?.message?.contains("WALLET_INVALID_USER") == true, "unknown user: $e")
    }

    @Test
    fun withoutASpecialtyTodayIsIdle() = V1Db.escenario { c ->
        c.world(1, specialty = false)
        val r = c.cal(user(1), null)
        assertEquals("SIN_ACTIVIDAD", c.estados(r)["2026-11-20"], "no specialty = no reto to play")
        assertEquals("0|0|0", "${c.j(r, "rachaActual")}|${c.j(r, "rachaMaxima")}|${c.j(r, "rachaMes")}")
    }

    @Test
    fun clientsCannotCallItAndTheVerifyAndRollbackRoundTrip() = V1Db.escenario { c ->
        assertEquals("falsefalsetrue", c.unico(
            "select has_function_privilege('anon', 'public.app_racha_calendario(uuid,date)', 'execute')::text || " +
                "has_function_privilege('authenticated', 'public.app_racha_calendario(uuid,date)', 'execute')::text || " +
                "has_function_privilege('service_role', 'public.app_racha_calendario(uuid,date)', 'execute')::text",
        ))
        val verify = Slice0Db.verifyDe("supabase/migrations/$migration.sql")
        val rows = c.filas(verify)
        assertEquals(4, rows.size, "VERIFY rows: $rows")
        assertEquals(emptyList(), rows.filter { it[1] != "0" }, "every VERIFY counter is 0")
        c.ejecutar(Slice0Db.rollbackDe("supabase/migrations/$migration.sql"))
        assertEquals(4, c.filas(verify).count { it[1] != "0" }, "after the rollback every check fails")
        c.ejecutar(Slice0Db.leer("supabase/migrations/$migration.sql"))
        assertEquals(emptyList(), c.filas(verify).filter { it[1] != "0" }, "re-applying converges")
    }

    private fun col0(c: Connection, sql: String): List<String> = c.filas(sql).map { it[0]!! }
}
