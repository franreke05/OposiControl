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
 * `20261114400200_v1_consumibles_efectos` (F28 step 3, FA52): the consumables bought with OT act by themselves in the daily challenge.
 * protector_racha / protector_tickets / protector_dual on a FAILED reto (only when something would be lost), potenciador on the BASE reward,
 * doble / triple oportunidad as a held wrong answer the user may answer again. One-question reto (only one published rafaga question), so one
 * answer closes it; that shape is the same with F20b (it fills to 3 only when there are 3 eligible questions).
 */
class V1ConsumiblesEfectosMigrationTest {
    private val migration = "20261114400200_v1_consumibles_efectos"
    private val esp = "e1000000-0000-0000-0000-0000000028e1"
    private val com = "c1000000-0000-0000-0000-0000000028e1"
    private val exam = "e2800000-0000-0000-0000-000000000001"
    private val q = "f2800000-0000-0000-0000-000000000001"
    private val ok = "0c280000-0000-0000-0000-000000000001"
    private val ko = "0c280000-0000-0000-0000-000000000002"
    private val ko2 = "0c280000-0000-0000-0000-000000000003"
    private val plan = "b2800000-0000-0000-0000-000000000001"
    private fun user(n: Int) = "a0000000-0000-0000-0000-0000000028" + n.toString(16).padStart(2, '0')

    /** Users 1..[n], specialty esp, 200 OT each; the Gratuito plan pays 40 base + 20 streak bonus; one published rafaga exam with ONE question (3 options). */
    private fun Connection.world(n: Int = 4) {
        val users = (1..n).map(::user)
        ejecutar(
            """
            insert into auth.users (id, email, aud, role, email_confirmed_at) values ${users.joinToString(", ") { "('$it', '$it@test.local', 'authenticated', 'authenticated', now())" }};
            insert into public.especialidades (id, codigo, nombre, slug, activa) values ('$esp', 'F28', 'F28', 'f28', true);
            insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('$com', 'F28', 'Com F28', 'com-f28');
            insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id) values
              ${users.mapIndexed { i, u -> "('$u', 'u$i', 'f28_u$i', true, 'activa', '$esp')" }.joinToString(", ")}
              on conflict (usuario_id) do update set especialidad_principal_id = excluded.especialidad_principal_id;
            insert into public.transacciones_cartera (usuario_id, tipo, cantidad, descripcion, referencia_tipo, idempotency_key)
              select u, 'ajuste', 200, 'saldo inicial', 'admin', 'f28-seed-' || u from unnest(array[${users.joinToString(", ") { "'$it'::uuid" }}]) u;
            insert into public.planes_suscripcion (id, codigo, nombre, nivel, activo, visible, posicion) values ('$plan', 'gratuito_f28', 'Gratuito', 0, true, true, 0);
            insert into public.beneficios_plan (plan_id, codigo_beneficio, valor_integer) values ('$plan', 'ot_reto_diario', 40), ('$plan', 'ot_bonus_racha', 20);
            begin;
            set local session_replication_role = replica;
            insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en)
              values ('$exam', 'F28-1', '$esp', '$com', 'Examen F28', 'rafaga', 'premium', true, now() - interval '1 day');
            insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa) values ('$q', 'F28-Q1', 'rafaga', 'Pregunta F28', true);
            insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion) values ('$ok', '$q', 'A', 'Si', 1), ('$ko', '$q', 'B', 'No', 2), ('$ko2', '$q', 'C', 'Tal vez', 3);
            insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento) values ('$q', '$ok', 'x');
            insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen) values ('$exam', '$q', 1, 1);
            commit;
            """.trimIndent(),
        )
    }

    /** A live streak of [days] whose last passed day was yesterday (Madrid). */
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
    private fun Connection.answer(u: String, option: String, usar: Boolean? = null): String =
        unico("select public.app_answer_daily_challenge('$u', '${reto(u)}', '$q', '$option', ${usar ?: "null"})::text")!!
    private fun Connection.claim(u: String): String = unico("select public.app_claim_daily_challenge('$u', '${reto(u)}')::text")!!
    private fun Connection.streakRow(u: String): String =
        unico("select racha_actual_dias || '|' || (ultima_actividad_fecha = private.reto_today(clock_timestamp())) from public.rachas_usuario where usuario_id = '$u'")!!
    private fun Connection.activations(u: String): String =
        filas("select s.tipo_servicio || ':' || a.usos_consumidos || '/' || a.usos_disponibles || ':' || a.estado from public.activaciones_servicio_usuario a join public.servicios s on s.id = a.servicio_id where a.usuario_id = '$u' order by s.posicion, a.activa_desde")
            .joinToString(",") { it[0]!! }
    private fun Connection.ledger(u: String): String =
        filas("select tipo || ':' || cantidad from public.transacciones_cartera where usuario_id = '$u' and tipo in ('reto_diario', 'bonus_racha', 'potenciador') order by tipo")
            .joinToString(",") { it[0]!! }

    @Test
    fun aRachaProtectorKeepsTheStreakOfAFailedRetoOnlyWhenThereIsAStreakToKeep() = V1Db.escenario { c ->
        c.world(3)
        val (saved, plain, noStreak) = listOf(user(1), user(2), user(3))
        listOf(saved, plain).forEach { c.streak(it, 5) }
        listOf(saved, noStreak).forEach { c.buy(it, "protector_racha") }
        assertEquals("1", c.j(c.today(saved), "consumibles,protectorRacha"), "the streak panel counter")

        val r = c.answer(saved, ko)
        assertEquals("FALLADO", c.j(r, "estado"), "the reto stays failed")
        assertEquals("""["protector_racha"]""", c.j(r, "consumiblesUsados"))
        assertNull(c.j(r, "recompensa"), "a racha protector pays nothing")
        assertEquals("6|true", c.streakRow(saved), "the failed day counts as a streak day")
        assertEquals("6", c.j(r, "racha,actual"))
        assertEquals("protector_racha:1/1:usada", c.activations(saved))
        assertEquals("protector_racha", c.j(c.today(saved), "protectorUsado"))
        assertEquals("0", c.j(c.today(saved), "consumibles,protectorRacha"))
        assertTrue(c.unico("select protector_usado_id is not null from public.registros_reto_diario where usuario_id = '$saved'") == "t")
        assertTrue(runCatching { c.claim(saved) }.exceptionOrNull()?.message?.contains("RETO_NOT_PASSED") == true)

        c.answer(plain, ko)
        assertEquals("5|false", c.streakRow(plain), "without a protector the streak is not advanced (it breaks tomorrow)")

        c.answer(noStreak, ko)
        assertEquals("protector_racha:0/1:activa", c.activations(noStreak), "no streak to keep: the protector is not spent")
    }

    @Test
    fun aTicketsProtectorPaysTheRewardOfAFailedRetoAndADualKeepsBoth() = V1Db.escenario { c ->
        c.world(4)
        val (tickets, dual, both, passed) = listOf(user(1), user(2), user(3), user(4))
        listOf(tickets, dual, both, passed).forEach { c.streak(it, 4) }
        c.buy(tickets, "protector_tickets")
        c.buy(dual, "protector_dual")
        c.buy(both, "protector_racha"); c.buy(both, "protector_tickets")
        c.buy(passed, "protector_dual")

        val t = c.answer(tickets, ko)
        assertEquals("FALLADO|40|20|60", listOf("estado", "recompensa,base", "recompensa,bonusRacha", "recompensa,total").joinToString("|") { c.j(t, it)!! }, t)
        assertEquals("4|false", c.streakRow(tickets), "the streak breaks")
        assertEquals("protector_tickets:0/1:activa", c.activations(tickets), "C2: the tickets protector is reserved at answer time, not spent")
        assertEquals("0", c.j(c.today(tickets), "consumibles,protectorTickets"), "reserved: no longer offered")
        assertEquals("60", c.j(c.claim(tickets), "recompensa,total"))
        assertEquals("protector_tickets:1/1:usada", c.activations(tickets), "spent by the claim")
        assertEquals("bonus_racha:20,reto_diario:40", c.ledger(tickets), "a failed reto saved by the protector is paid like a passed one")
        assertEquals("FALLADO", c.j(c.today(tickets), "estado"))

        val d = c.answer(dual, ko)
        assertEquals("""["protector_dual"]""", c.j(d, "consumiblesUsados"))
        assertEquals("60|5|true", "${c.j(d, "recompensa,total")}|${c.streakRow(dual)}")

        val b = c.answer(both, ko)
        assertEquals("""["protector_racha", "protector_tickets"]""", c.j(b, "consumiblesUsados"), "no dual: one of each")
        assertEquals("60|5|true", "${c.j(b, "recompensa,total")}|${c.streakRow(both)}")
        assertEquals("protector_racha:1/1:usada,protector_tickets:0/1:activa", c.activations(both), "the streak one acts now; the tickets one waits for the claim")
        c.claim(both)
        assertEquals("protector_racha:1/1:usada,protector_tickets:1/1:usada", c.activations(both))

        val p = c.answer(passed, ok)
        assertEquals("SUPERADO", c.j(p, "estado"))
        assertNull(c.j(p, "consumiblesUsados"))
        assertEquals("protector_dual:0/1:activa", c.activations(passed), "a passed reto never spends a protector")
    }

    @Test
    fun aPotenciadorMultipliesOnlyTheBaseOfThePassedRetoAndTheClaimCreditsTheExtraOnce() = V1Db.escenario { c ->
        c.world(4)
        val (pot, failed, noBonus, capped) = listOf(user(1), user(2), user(3), user(4))
        listOf(pot, failed).forEach { c.streak(it, 4) }
        c.buy(pot, "potenciador_x3"); c.buy(pot, "potenciador_x2")
        c.buy(failed, "potenciador_x3")
        c.buy(noBonus, "potenciador_x2")

        val r = c.answer(pot, ok)
        assertEquals(
            "SUPERADO|40|20|3|80|140",
            listOf("estado", "recompensa,base", "recompensa,bonusRacha", "recompensa,multiplicador", "recompensa,extraPotenciador", "recompensa,total").joinToString("|") { c.j(r, it)!! },
            r,
        )
        assertEquals("potenciador:0/1:activa,potenciador:0/1:activa", c.activations(pot), "C2: reserved at answer time, spent by the claim")
        assertEquals("1", c.j(c.today(pot), "consumibles,potenciador"), "the reserved x3 is no longer offered")
        assertEquals("3", c.unico("select multiplicador_aplicado::int from public.registros_reto_diario where usuario_id = '$pot'"))
        val cl = c.claim(pot)
        assertEquals("potenciador:0/1:activa,potenciador:1/1:usada", c.activations(pot).split(",").sorted().joinToString(","), "one per reto (the x3 first: same expiry, higher multiplier)")
        // D1 (owner 2026-11-14): the extra respects the free-OT caps: 40 + 20 + 80 > the daily 100 -> deferred like any free earn, the reto stays pending
        assertEquals("140|false|true|80", listOf("recompensa,total", "recompensa,reclamada", "recompensa,pendiente", "recompensa,extraPotenciador").joinToString("|") { c.j(cl, it)!! })
        assertEquals("bonus_racha:20,reto_diario:40", c.ledger(pot))
        assertEquals("80|pendiente", c.unico("select importe || '|' || estado from public.ot_ganancias_diferidas where usuario_id = '$pot' and clave like 'potenciador:%'"))
        c.claim(pot)
        assertEquals("bonus_racha:20,reto_diario:40", c.ledger(pot), "claiming again pays nothing more")
        assertEquals("1", c.unico("select count(*)::text from public.ot_ganancias_diferidas where usuario_id = '$pot' and clave like 'potenciador:%'"), "deferred once")
        assertEquals("potenciador:0/1:activa,potenciador:1/1:usada", c.activations(pot).split(",").sorted().joinToString(","), "claiming again spends nothing more")
        assertEquals("80", c.j(c.today(pot), "recompensa,extraPotenciador"))

        c.answer(failed, ko)
        assertEquals("potenciador:0/1:activa", c.activations(failed), "a failed reto without a tickets protector pays nothing to multiply")

        val n = c.answer(noBonus, ok)
        assertEquals("40|0|80", listOf("recompensa,base", "recompensa,bonusRacha", "recompensa,total").joinToString("|") { c.j(n, it)!! }, "x2 of 40, no streak bonus")

        // D1: an extra above a cap on its own is NOT credited; base still paid, the reported extra is what was credited (0) and the potenciador comes back
        c.ejecutar("insert into public.configuracion_v1 (clave, valor_integer) values ('tope_ot_gratis_dia', 45) on conflict (clave) do update set valor_integer = excluded.valor_integer")
        c.buy(capped, "potenciador_x3")
        assertEquals("120", c.j(c.answer(capped, ok), "recompensa,total"), "x3 of 40 at answer time")
        val capCl = c.claim(capped)
        assertEquals("40|true|0", listOf("recompensa,total", "recompensa,reclamada", "recompensa,extraPotenciador").joinToString("|") { c.j(capCl, it)!! }, capCl)
        assertEquals("reto_diario:40", c.ledger(capped))
        assertEquals("potenciador:0/1:activa", c.activations(capped), "not credited: the potenciador use is released")
        assertEquals("40", c.j(c.today(capped), "recompensa,total"), "the frozen total is what was paid")
    }

    @Test
    fun aFailedClaimKeepsTheReservedConsumablesAndAnUnclaimedReservationFreesItself() = V1Db.escenario { c ->
        c.world(2)
        val (unverified, idle) = listOf(user(1), user(2))
        c.streak(unverified, 4)
        c.buy(unverified, "protector_tickets"); c.buy(unverified, "potenciador_x2")
        c.buy(idle, "potenciador_x2")
        c.ejecutar("update auth.users set email_confirmed_at = null where id = '$unverified'")

        val r = c.answer(unverified, ko)
        assertEquals("FALLADO|100", "${c.j(r, "estado")}|${c.j(r, "recompensa,total")}", "tickets protected (40 + 20) and the base doubled (+40)")
        assertTrue(runCatching { c.claim(unverified) }.exceptionOrNull()?.message?.contains("EMAIL_UNVERIFIED") == true)
        assertEquals("protector_tickets:0/1:activa,potenciador:0/1:activa", c.activations(unverified), "a failed claim spends nothing")
        assertEquals("", c.ledger(unverified))
        c.ejecutar("update auth.users set email_confirmed_at = now() where id = '$unverified'")
        assertEquals("100", c.j(c.claim(unverified), "recompensa,total"))
        assertEquals("protector_tickets:1/1:usada,potenciador:1/1:usada", c.activations(unverified), "the successful claim spends both, once")
        assertEquals("bonus_racha:20,reto_diario:40,reto_diario:40", c.ledger(unverified), "40 + 20 + 40 = the daily cap 100: the extra is credited (D1, as a free earn)")
        assertEquals("0", c.unico("select count(*)::text from public.reto_consumibles_reservados where usuario_id = '$unverified'"))

        c.answer(idle, ok)
        assertEquals("0", c.j(c.today(idle), "consumibles,potenciador"), "reserved while the reto can be claimed")
        c.ejecutar("update public.reto_consumibles_reservados set reclamable_hasta = private.reto_today(clock_timestamp()) - 1 where usuario_id = '$idle'")
        assertEquals("1", c.j(c.today(idle), "consumibles,potenciador"), "never claimed: after the last claim day the use is free again")
        assertEquals("potenciador:0/1:activa", c.activations(idle))
    }

    @Test
    fun aDobleOportunidadHoldsTheWrongAnswerAndLetsTheUserAnswerAgain() = V1Db.escenario { c ->
        c.world(3)
        val (retry, decline, never) = listOf(user(1), user(2), user(3))
        listOf(retry, decline, never).forEach { c.buy(it, "doble_oportunidad") }

        val held = c.answer(retry, ko)
        assertEquals("false|EN_CURSO|0|true|1", listOf("esCorrecta", "estado", "respondidas", "oportunidad,disponible", "oportunidad,restantes").joinToString("|") { c.j(held, it)!! }, held)
        assertNull(c.j(held, "opcionCorrectaId"), "no correction while the answer is held")
        assertEquals("0", c.unico("select count(*) from public.respuestas_reto_diario where usuario_id = '$retry'"), "nothing stored")
        assertEquals("true", c.j(c.today(retry), "preguntas,0,oportunidadPendiente"))
        assertNull(c.j(c.today(retry), "preguntas,0,respuesta"))
        assertEquals(held, c.answer(retry, ko), "the same option without a decision repeats the offer")
        assertTrue(runCatching { c.answer(retry, ok) }.exceptionOrNull()?.message?.contains("ANSWER_LOCKED") == true, "another option needs the explicit choice")
        assertTrue(runCatching { c.answer(retry, ko, usar = true) }.exceptionOrNull()?.message?.contains("ANSWER_LOCKED") == true, "the retry must be another option")
        assertEquals("doble_oportunidad:0/1:activa", c.activations(retry), "refusals spend nothing")

        val second = c.answer(retry, ok, usar = true)
        assertEquals("true|SUPERADO|$ok", listOf("esCorrecta", "estado", "opcionCorrectaId").joinToString("|") { c.j(second, it)!! }, second)
        assertEquals("""["doble_oportunidad"]""", c.j(second, "consumiblesUsados"))
        assertEquals("doble_oportunidad:1/1:usada", c.activations(retry))
        assertEquals("$ok|t", c.filas("select opcion_id::text, es_correcta::text from public.respuestas_reto_diario where usuario_id = '$retry'").single().joinToString("|").replace("true", "t"))
        val replay = c.answer(retry, ok, usar = true)
        assertEquals("true|SUPERADO", "${c.j(replay, "esCorrecta")}|${c.j(replay, "estado")}", "idempotent once stored (the stored result; consumiblesUsados belongs to the answer that spent it)")
        assertEquals("doble_oportunidad:1/1:usada", c.activations(retry), "a replay spends nothing")

        c.answer(decline, ko)
        val declined = c.answer(decline, ko, usar = false)
        assertEquals("false|FALLADO|$ok", listOf("esCorrecta", "estado", "opcionCorrectaId").joinToString("|") { c.j(declined, it)!! })
        assertEquals("doble_oportunidad:0/1:activa", c.activations(decline), "declining keeps the use")

        val direct = c.answer(never, ko, usar = false)
        assertEquals("FALLADO", c.j(direct, "estado"), "false on the first answer = never offer")
        assertFalse(direct.contains("oportunidad"))
    }

    @Test
    fun aTripleOportunidadGivesTwoRetries() = V1Db.escenario { c ->
        c.world(1)
        val u = user(1)
        c.buy(u, "triple_oportunidad")
        assertEquals("2", c.j(c.answer(u, ko), "oportunidad,restantes"))
        val again = c.answer(u, ko2, usar = true)
        assertEquals("false|1", "${c.j(again, "esCorrecta")}|${c.j(again, "oportunidad,restantes")}", "wrong again with a use left: held again")
        assertEquals("triple_oportunidad:1/2:activa", c.activations(u))
        assertEquals("SUPERADO", c.j(c.answer(u, ok, usar = true), "estado"))
        assertEquals("triple_oportunidad:2/2:usada", c.activations(u))
    }

    @Test
    fun theVerifyBlockAndTheRollbackRestoreTheOldFunctions() = V1Db.escenario { c ->
        val verify = Slice0Db.verifyDe("supabase/migrations/$migration.sql")
        val rows = c.filas(verify)
        assertEquals(8, rows.size, "VERIFY rows: $rows")
        assertEquals(emptyList(), rows.filter { it[1] != "0" }, "every VERIFY counter is 0")
        c.ejecutar(Slice0Db.rollbackDe("supabase/migrations/$migration.sql"))
        val after = c.filas(verify).filter { it[1] != "0" }.map { it[0] }
        assertTrue("answer_without_oportunidad_argument" in after && "claim_does_not_credit_the_potenciador" in after, "after the rollback the VERIFY detects it: $after")
        assertEquals("falsefalsetrue", c.unico(
            "select has_function_privilege('anon', 'public.app_answer_daily_challenge(uuid,uuid,uuid,uuid)', 'execute')::text || " +
                "has_function_privilege('authenticated', 'public.app_answer_daily_challenge(uuid,uuid,uuid,uuid)', 'execute')::text || " +
                "has_function_privilege('service_role', 'public.app_answer_daily_challenge(uuid,uuid,uuid,uuid)', 'execute')::text",
        ), "the 4-argument function is back with its grants")
        // the old behaviour: a protector is never spent
        c.world(1)
        c.streak(user(1), 5)
        c.buy(user(1), "protector_racha")
        assertEquals("FALLADO", c.j(c.unico("select public.app_answer_daily_challenge('${user(1)}', '${c.reto(user(1))}', '$q', '$ko')::text")!!, "estado"))
        assertEquals("protector_racha:0/1:activa", c.activations(user(1)))
        c.ejecutar(Slice0Db.leer("supabase/migrations/$migration.sql"))
        assertEquals(emptyList(), c.filas(verify).filter { it[1] != "0" }, "re-applying converges")
    }
}
