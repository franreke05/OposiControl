package com.oposicontrol.dbtest.v1

import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `20261115100100_v1_reto_todo_o_nada` (F31b, FA57): the reto is passed only with ALL its questions correct, and a wrong answer can be held with a PAID retry
 * offer (after the doble/triple oportunidad consumables). Three published rafaga questions, so F20b fills the reto to 3.
 */
class V1RetoTodoONadaMigrationTest {
    private val migration = "20261115100100_v1_reto_todo_o_nada"
    private val esp = "e1000000-0000-0000-0000-0000000031e2"
    private val com = "c1000000-0000-0000-0000-0000000031e2"
    private val exam = "e3110000-0000-0000-0000-000000000001"
    private val plan = "b3110000-0000-0000-0000-000000000001"
    private fun q(i: Int) = "f3110000-0000-0000-0000-00000000000$i"
    private fun ok(i: Int) = "0c31100$i-0000-0000-0000-000000000001"
    private fun ko(i: Int) = "0c31100$i-0000-0000-0000-000000000002"
    private fun ko2(i: Int) = "0c31100$i-0000-0000-0000-000000000003"
    private fun user(n: Int) = "a0000000-0000-0000-0000-0000000031" + (0x80 + n).toString(16)

    /** Users 1..[n] with [saldo] OT each; Gratuito plan 40 base + 20 streak bonus; one published rafaga exam with 3 questions. */
    private fun Connection.world(n: Int, saldo: Int = 200) {
        val users = (1..n).map(::user)
        ejecutar(
            """
            insert into auth.users (id, email, aud, role, email_confirmed_at) values ${users.joinToString(", ") { "('$it', '$it@test.local', 'authenticated', 'authenticated', now())" }};
            insert into public.especialidades (id, codigo, nombre, slug, activa) values ('$esp', 'F31B', 'F31B', 'f31b', true);
            insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('$com', 'F31B', 'Com F31B', 'com-f31b');
            insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id) values
              ${users.mapIndexed { i, u -> "('$u', 'u$i', 'f31b_u$i', true, 'activa', '$esp')" }.joinToString(", ")}
              on conflict (usuario_id) do update set especialidad_principal_id = excluded.especialidad_principal_id;
            insert into public.transacciones_cartera (usuario_id, tipo, cantidad, descripcion, referencia_tipo, idempotency_key)
              select u, 'ajuste', $saldo, 'saldo inicial', 'admin', 'f31b-seed-' || u from unnest(array[${users.joinToString(", ") { "'$it'::uuid" }}]) u;
            insert into public.planes_suscripcion (id, codigo, nombre, nivel, activo, visible, posicion) values ('$plan', 'gratuito_f31b', 'Gratuito', 0, true, true, 0);
            insert into public.beneficios_plan (plan_id, codigo_beneficio, valor_integer) values ('$plan', 'ot_reto_diario', 40), ('$plan', 'ot_bonus_racha', 20);
            begin;
            set local session_replication_role = replica;
            insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en)
              values ('$exam', 'F31B-1', '$esp', '$com', 'Examen F31B', 'rafaga', 'premium', true, now() - interval '1 day');
            ${(1..3).joinToString("\n") { i ->
                "insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa) values ('${q(i)}', 'F31B-Q$i', 'rafaga', 'Pregunta $i', true);\n" +
                    "insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion) values ('${ok(i)}', '${q(i)}', 'A', 'Si', 1), ('${ko(i)}', '${q(i)}', 'B', 'No', 2), ('${ko2(i)}', '${q(i)}', 'C', 'Tal vez', 3);\n" +
                    "insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento) values ('${q(i)}', '${ok(i)}', 'x');\n" +
                    "insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen) values ('$exam', '${q(i)}', $i, $i);"
            }}
            commit;
            """.trimIndent(),
        )
    }

    private fun Connection.streak(u: String, days: Int) = ejecutar(
        "insert into public.rachas_usuario (usuario_id, racha_actual_dias, mejor_racha_dias, ultima_actividad_fecha) values ('$u', $days, $days, private.reto_today(clock_timestamp()) - 1) " +
            "on conflict (usuario_id) do update set racha_actual_dias = excluded.racha_actual_dias, mejor_racha_dias = excluded.mejor_racha_dias, ultima_actividad_fecha = excluded.ultima_actividad_fecha",
    )
    private fun Connection.buy(u: String, codigo: String) {
        unico("select outcome from public.app_redeem_service('$u', '$codigo', 'buy-$u-$codigo-' || gen_random_uuid(), null)")
    }
    private fun Connection.today(u: String): String = unico("select public.app_get_daily_challenge('$u')::text")!!
    private fun Connection.j(json: String, path: String): String? = unico("select ('${json.replace("'", "''")}'::jsonb) #>> '{$path}'")
    private fun Connection.reto(u: String): String = j(today(u), "retoId")!!
    private fun b(v: Boolean?) = v?.toString() ?: "null"

    /** A client of the new image: declares both supports (unless told otherwise). */
    private fun Connection.answer(u: String, i: Int, option: String, usar: Boolean? = null, pagar: Boolean? = null, admitePago: Boolean = true): String =
        unico(
            "select public.app_answer_daily_challenge(p_usuario => '$u', p_reto => '${reto(u)}', p_pregunta => '${q(i)}', p_opcion => '$option', " +
                "p_usar_oportunidad => ${b(usar)}, p_admite_oportunidad => true, p_pagar_reintento => ${b(pagar)}, p_admite_reintento_pago => $admitePago)::text",
        )!!
    private fun Connection.saldo(u: String): Int = unico("select coalesce((select saldo_tickets from public.carteras_usuario where usuario_id = '$u'), 0)::text")!!.toInt()
    private fun Connection.gastos(u: String): String =
        filas("select cantidad || ':' || (metadatos ->> 'op') from public.transacciones_cartera where usuario_id = '$u' and tipo = 'gasto' order by creado_en").joinToString(",") { it[0]!! }
    private fun Connection.streakRow(u: String): String =
        unico("select racha_actual_dias || '|' || (ultima_actividad_fecha = private.reto_today(clock_timestamp())) from public.rachas_usuario where usuario_id = '$u'")!!
    private fun Connection.fails(code: String, block: () -> Unit) {
        val e = runCatching(block).exceptionOrNull()
        assertTrue(e?.message?.contains(code) == true, "expected $code, got $e")
    }

    @Test
    fun allCorrectPassesAndTwoOfThreeFailsAndBreaksTheStreak() = V1Db.escenario { c ->
        c.world(2)
        val (win, lose) = listOf(user(1), user(2))
        listOf(win, lose).forEach { c.streak(it, 5) }
        assertEquals("3|3", "${c.j(c.today(win), "necesarios")}|${c.unico("select jsonb_array_length(public.app_get_daily_challenge('$win') -> 'preguntas')::text")}", "3 questions, all needed")
        assertEquals("3", c.unico("select aciertos_necesarios::text from public.retos_diarios where id = '${c.reto(win)}'"), "open rows aligned")
        (1..3).forEach { c.answer(win, it, ok(it), admitePago = false) }
        assertEquals("SUPERADO|6|true", "${c.j(c.today(win), "estado")}|${c.streakRow(win)}")

        c.answer(lose, 1, ok(1), admitePago = false); c.answer(lose, 2, ok(2), admitePago = false)
        val last = c.answer(lose, 3, ko(3), admitePago = false)
        assertEquals("FALLADO|2|3", "${c.j(last, "estado")}|${c.j(last, "aciertos")}|${c.j(last, "respondidas")}", "2 of 3 is no longer enough")
        assertNull(c.j(last, "reintentoPago"), "a client without admiteReintentoPago is never held")
        assertEquals("5|false", c.streakRow(lose), "the streak is not advanced: it breaks")
    }

    @Test
    fun aPaidRetryChargesHalfOfTodaysRewardOnceAndAnswersAgain() = V1Db.escenario { c ->
        c.world(1)
        val u = user(1)
        c.streak(u, 4)  // today would be day 5: base 40 + bonus 20 -> price 30
        val held = c.answer(u, 1, ko(1))
        assertEquals("false|EN_CURSO|0", "${c.j(held, "esCorrecta")}|${c.j(held, "estado")}|${c.j(held, "respondidas")}")
        assertEquals("true|30|200", "${c.j(held, "reintentoPago,disponible")}|${c.j(held, "reintentoPago,precioTickets")}|${c.j(held, "reintentoPago,saldoTickets")}")
        assertNull(c.j(held, "opcionCorrectaId"), "no correction while held")
        val t = c.today(u)
        assertEquals("true", c.filas("select q ->> 'reintentoPagoPendiente' from jsonb_array_elements(('${t.replace("'", "''")}'::jsonb) -> 'preguntas') q where q ->> 'preguntaId' = '${q(1)}'").single()[0])
        assertEquals("30|200", "${c.j(t, "reintentoPago,precioTickets")}|${c.j(t, "reintentoPago,saldoTickets")}")
        assertEquals(held, c.answer(u, 1, ko(1)), "the same option without a decision repeats the offer")
        c.fails("ANSWER_LOCKED") { c.answer(u, 1, ok(1)) }       // another option needs the decision
        c.fails("ANSWER_LOCKED") { c.answer(u, 1, ko(1), pagar = true) }  // the paid retry must be another option
        val paid = c.answer(u, 1, ok(1), pagar = true)
        assertEquals("true|${ok(1)}|1", "${c.j(paid, "esCorrecta")}|${c.j(paid, "opcionCorrectaId")}|${c.j(paid, "respondidas")}")
        assertEquals("170|-30:reintento_reto", "${c.saldo(u)}|${c.gastos(u)}", "one ledger debit")
        assertEquals(paid, c.answer(u, 1, ok(1), pagar = true), "a retried pay request replays the stored answer, no second debit")
        assertEquals("170", c.saldo(u).toString())
        // another question gets its own offer; wrong again after paying = final
        assertEquals("true", c.j(c.answer(u, 2, ko(2)), "reintentoPago,disponible"))
        val again = c.answer(u, 2, ko2(2), pagar = true)
        assertEquals("false|${ok(2)}|2", "${c.j(again, "esCorrecta")}|${c.j(again, "opcionCorrectaId")}|${c.j(again, "respondidas")}", "final, with its correction")
        assertEquals("140", c.saldo(u).toString())
        c.fails("ANSWER_LOCKED") { c.answer(u, 2, ok(2), pagar = true) }
        val end = c.answer(u, 3, ok(3))
        assertEquals("FALLADO", c.j(end, "estado"), "one wrong question = failed")
        assertNull(c.j(c.today(u), "reintentoPago"), "no price once the reto is closed")
    }

    @Test
    fun notEnoughTicketsChangesNothingAndTheOfferStaysUntilDeclined() = V1Db.escenario { c ->
        c.world(1, saldo = 10)
        val u = user(1)
        assertEquals("20", c.j(c.answer(u, 1, ko(1)), "reintentoPago,precioTickets"), "no streak: (40 + 0) / 2")
        val e = runCatching { c.answer(u, 1, ok(1), pagar = true) }.exceptionOrNull()
        assertTrue(e?.message?.contains("INSUFFICIENT_BALANCE") == true && e.message!!.contains("\"price\":20") && e.message!!.contains("\"balance\":10"), "$e")
        assertEquals("10||pendiente", "${c.saldo(u)}|${c.gastos(u)}|${c.unico("select estado from public.reto_reintentos_pago where usuario_id = '$u'")}")
        assertEquals("true", c.j(c.answer(u, 1, ko(1)), "reintentoPago,disponible"), "the offer stays")
        val declined = c.answer(u, 1, ok(1), pagar = false)
        assertEquals("false|${ok(1)}|1", "${c.j(declined, "esCorrecta")}|${c.j(declined, "opcionCorrectaId")}|${c.j(declined, "respondidas")}", "declined: the held wrong answer is final")
        assertEquals("rechazado", c.unico("select estado from public.reto_reintentos_pago where usuario_id = '$u'"))
    }

    @Test
    fun theConsumableComesFirstThenThePaidOfferAndDecliningAConsumableIsFinal() = V1Db.escenario { c ->
        c.world(2)
        val (both, decline) = listOf(user(1), user(2))
        listOf(both, decline).forEach { c.buy(it, "doble_oportunidad") }
        val first = c.answer(both, 1, ko(1))
        assertEquals("true", c.j(first, "oportunidad,disponible"), "the consumable first")
        assertNull(c.j(first, "reintentoPago"))
        val second = c.answer(both, 1, ko2(1), usar = true)
        assertEquals("doble_oportunidad|true", "${c.j(second, "consumiblesUsados,0")}|${c.j(second, "reintentoPago,disponible")}", "use spent, wrong again, no use left: the paid offer")
        val paid = c.answer(both, 1, ok(1), pagar = true)
        assertEquals("true", c.j(paid, "esCorrecta"))

        assertEquals("true", c.j(c.answer(decline, 1, ko(1)), "oportunidad,disponible"))
        val final = c.answer(decline, 1, ko(1), usar = false)
        assertEquals("false|1", "${c.j(final, "esCorrecta")}|${c.j(final, "respondidas")}", "declining an available consumable is final")
        assertNull(c.j(final, "reintentoPago"))
        assertEquals("0", c.unico("select count(*)::text from public.reto_reintentos_pago where usuario_id = '$decline'"))
    }

    @Test
    fun aProtectorStillSavesTheStreakAfterADeclinedRetryAndOldCallersAreNeverHeld() = V1Db.escenario { c ->
        c.world(2)
        val (saved, old) = listOf(user(1), user(2))
        c.streak(saved, 5)
        c.buy(saved, "protector_racha")
        assertEquals("true", c.j(c.answer(saved, 1, ko(1)), "reintentoPago,disponible"))
        c.answer(saved, 1, ko(1), pagar = false)
        c.answer(saved, 2, ok(2)); val end = c.answer(saved, 3, ok(3))
        assertEquals("FALLADO|protector_racha", "${c.j(end, "estado")}|${c.j(end, "consumiblesUsados,0")}")
        assertEquals("6|true", c.streakRow(saved), "the protector keeps the streak of the failed reto")

        // the running image: 6 named arguments, no p_admite_reintento_pago -> never held
        val r = c.unico("select public.app_answer_daily_challenge(p_usuario => '$old', p_reto => '${c.reto(old)}', p_pregunta => '${q(1)}', p_opcion => '${ko(1)}', p_usar_oportunidad => null, p_admite_oportunidad => true)::text")!!
        assertEquals("false|${ok(1)}|1", "${c.j(r, "esCorrecta")}|${c.j(r, "opcionCorrectaId")}|${c.j(r, "respondidas")}")
        // a held answer of a client that then stops declaring support is declined (never stuck)
        c.answer(old, 2, ko(2))
        val dropped = c.unico("select public.app_answer_daily_challenge('${old}', '${c.reto(old)}', '${q(2)}', '${ok(2)}')::text")!!
        assertEquals("false|${ok(2)}", "${c.j(dropped, "esCorrecta")}|${c.j(dropped, "opcionCorrectaId")}", "the held wrong answer becomes final")
        assertEquals("1", c.unico("select count(*)::text from pg_proc where proname = 'app_answer_daily_challenge' and pronamespace = 'public'::regnamespace"), "one overload")
    }

    /** Review Control 2026-11-15 (FA52c): the price is half of what the free-OT caps let the claim credit NOW; nothing collectible = no offer. */
    @Test
    fun thePriceFollowsTheFreeOtCapsAndNothingCollectibleMeansNoOffer() = V1Db.escenario { c ->
        c.world(2)
        val (full, partial) = listOf(user(1), user(2))
        listOf(full, partial).forEach { c.streak(it, 4) }  // today would pay 40 base + 20 bonus = 60 (price 30 without caps)
        // free OT already earned today through the one writer (daily cap 100): 100 = full, 70 = 30 left
        c.unico("select estado from public.app_credit_free_ot('$full', 'referido', 100, 'referido', null, 'f31b-cap-full', '{}'::jsonb)")
        c.unico("select estado from public.app_credit_free_ot('$partial', 'referido', 70, 'referido', null, 'f31b-cap-partial', '{}'::jsonb)")
        assertNull(c.j(c.today(full), "reintentoPago"), "cap reached: no price shown")
        val r = c.answer(full, 1, ko(1))
        assertEquals("false|${ok(1)}|1", "${c.j(r, "esCorrecta")}|${c.j(r, "opcionCorrectaId")}|${c.j(r, "respondidas")}", "nothing collectible: final at once, as for an old client")
        assertNull(c.j(r, "reintentoPago"))
        assertEquals("0", c.unico("select count(*)::text from public.reto_reintentos_pago where usuario_id = '$full'"))
        // 30 left: the base (40) would be deferred, the bonus (20) fits -> collectible 20 -> price 10
        assertEquals("10", c.j(c.today(partial), "reintentoPago,precioTickets"))
        assertEquals("10", c.j(c.answer(partial, 1, ko(1)), "reintentoPago,precioTickets"))
    }

    /** Review Control 2026-11-15 (point 5): a doble oportunidad bought WHILE a paid retry is held is not used for that question. */
    @Test
    fun aDobleOportunidadBoughtWhileAPaidRetryIsHeldWaitsForTheNextQuestion() = V1Db.escenario { c ->
        c.world(1)
        val u = user(1)
        assertEquals("true", c.j(c.answer(u, 1, ko(1)), "reintentoPago,disponible"))
        c.buy(u, "doble_oportunidad")
        c.fails("ANSWER_LOCKED") { c.answer(u, 1, ok(1), usar = true) }  // the held question only takes the paid decision
        val declined = c.answer(u, 1, ok(1), pagar = false)
        assertEquals("false|${ok(1)}", "${c.j(declined, "esCorrecta")}|${c.j(declined, "opcionCorrectaId")}", "declined = final: the new consumable is not offered")
        assertNull(c.j(declined, "oportunidad"))
        assertEquals("1", c.j(c.today(u), "consumibles,oportunidades"), "not spent")
        assertEquals("true", c.j(c.answer(u, 2, ko(2)), "oportunidad,disponible"), "the next wrong answer gets the consumable first")
    }

    @Test
    fun theVerifyBlockAndTheRollbackRestoreTheOldFunctions() = V1Db.escenario { c ->
        val verify = Slice0Db.verifyDe("supabase/migrations/$migration.sql")
        val rows = c.filas(verify)
        assertEquals(7, rows.size, "VERIFY rows: $rows")
        assertEquals(emptyList(), rows.filter { it[1] != "0" }, "every VERIFY counter is 0")
        c.ejecutar(Slice0Db.rollbackDe("supabase/migrations/$migration.sql"))
        val after = c.filas(verify).filter { it[1] != "0" }.map { it[0] }
        assertTrue("answer_without_all_correct_rule" in after && "today_without_paid_retry" in after && "new_rows_not_all_correct" in after, "after the rollback the VERIFY detects it: $after")
        assertEquals("falsefalsetrue", c.unico(
            "select has_function_privilege('anon', 'public.app_answer_daily_challenge(uuid,uuid,uuid,uuid,boolean,boolean)', 'execute')::text || " +
                "has_function_privilege('authenticated', 'public.app_answer_daily_challenge(uuid,uuid,uuid,uuid,boolean,boolean)', 'execute')::text || " +
                "has_function_privilege('service_role', 'public.app_answer_daily_challenge(uuid,uuid,uuid,uuid,boolean,boolean)', 'execute')::text",
        ), "the 6-argument function is back with its grants")
        // the old rule: 2 of 3 passes
        c.world(1)
        val u = user(1)
        (1..2).forEach { c.unico("select public.app_answer_daily_challenge('$u', '${c.reto(u)}', '${q(it)}', '${ok(it)}')") }
        assertEquals("SUPERADO", c.j(c.unico("select public.app_answer_daily_challenge('$u', '${c.reto(u)}', '${q(3)}', '${ko(3)}')::text")!!, "estado"))
        assertNull(c.unico("select to_regclass('public.reto_reintentos_pago')::text"), "the table is gone")
        c.ejecutar(Slice0Db.leer("supabase/migrations/$migration.sql"))
        assertEquals(emptyList(), c.filas(verify).filter { it[1] != "0" }, "re-applying converges")
    }
}
