package com.oposicontrol.dbtest.v1

import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `20261109100200_v1_reto_tres` (F20b, propietario 2026-11-11): the daily reto ALWAYS has 3 questions when the segment has them. After the
 * per-type picks of 20261109100000 it fills from the union of the pools (no repeated question, deterministic per day with its own salt,
 * tipo_slot = the source pool type); fewer than 3 eligible questions in total = what exists. Pass rule ceil(2n/3). Full V1 schema; negative
 * control without the migration (= production after F20: only rafaga gives a 1-question reto).
 */
class V1RetoTresMigrationTest {
    private val migration = "20261109100200_v1_reto_tres"
    private val espA = "e1000000-0000-0000-0000-0000000021a1"
    private val espB = "e1000000-0000-0000-0000-0000000021b1"
    private val espC = "e1000000-0000-0000-0000-0000000021c1"
    private val com = "c1000000-0000-0000-0000-0000000021a1"
    private val ua = "a0000000-0000-0000-0000-0000000021a1"
    private val ub = "a0000000-0000-0000-0000-0000000021b1"
    private val uc = "a0000000-0000-0000-0000-0000000021c1"

    private fun q(n: Int) = "f2100000-0000-0000-0000-0000000000" + n.toString(16).padStart(2, '0')
    private fun ok(n: Int) = "0c300000-0000-0000-0000-0000000000" + n.toString(16).padStart(2, '0')
    private fun ko(n: Int) = "0c310000-0000-0000-0000-0000000000" + n.toString(16).padStart(2, '0')
    private fun ex(n: Int) = "e2100000-0000-0000-0000-0000000000" + n.toString(16).padStart(2, '0')

    private val users = """
        insert into auth.users (id, email, aud, role, email_confirmed_at) values
          ('$ua', 'f20b-a@test.local', 'authenticated', 'authenticated', now()), ('$ub', 'f20b-b@test.local', 'authenticated', 'authenticated', now()),
          ('$uc', 'f20b-c@test.local', 'authenticated', 'authenticated', now());
        insert into public.especialidades (id, codigo, nombre, slug, activa) values ('$espA', 'F21A', 'F21A', 'f21a', true), ('$espB', 'F21B', 'F21B', 'f21b', true), ('$espC', 'F21C', 'F21C', 'f21c', true);
        insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('$com', 'F21', 'Com F21', 'com-f21');
        insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id) values
          ('$ua', 'a', 'f21_a', true, 'activa', '$espA'), ('$ub', 'b', 'f21_b', true, 'activa', '$espB'), ('$uc', 'c', 'f21_c', true, 'activa', '$espC')
          on conflict (usuario_id) do update set especialidad_principal_id = excluded.especialidad_principal_id;
    """.trimIndent()

    private class Q(val n: Int, val anulada: Boolean = false, val reserva: Boolean = false, val activa: Boolean = true)

    private fun Connection.exam(n: Int, esp: String, tipo: String, questions: List<Q>, publicado: String = "now() - interval '1 day'", activo: Boolean = true) {
        val tipoPregunta = when (tipo) { "inmersivo" -> "inmersiva"; "razonado" -> "razonada"; else -> "rafaga" }
        val sql = buildString {
            appendLine("begin;")
            appendLine("set local session_replication_role = replica;")
            appendLine(
                "insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en) " +
                    "values ('${ex(n)}', 'F21-$n', '$esp', '$com', 'Examen $n', '$tipo', 'premium', $activo, $publicado);",
            )
            questions.forEachIndexed { i, x ->
                appendLine("insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa) values ('${q(x.n)}', 'F21-Q${x.n}', '$tipoPregunta', 'Pregunta ${x.n}', ${x.activa});")
                appendLine("insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion) values ('${ok(x.n)}', '${q(x.n)}', 'A', 'Si', 1), ('${ko(x.n)}', '${q(x.n)}', 'B', 'No', 2);")
                appendLine("insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento) values ('${q(x.n)}', '${ok(x.n)}', 'x');")
                appendLine(
                    "insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen, anulada, motivo_anulacion, es_reserva, orden_reserva, es_demo) values " +
                        "('${ex(n)}', '${q(x.n)}', ${i + 1}, ${i + 1}, ${x.anulada}, ${if (x.anulada) "'anulada'" else "null"}, ${x.reserva}, ${if (x.reserva) "1" else "null"}, false);",
                )
            }
            appendLine("commit;")
        }
        ejecutar(sql)
    }

    private fun Connection.today(u: String): String = unico("select public.app_get_daily_challenge('$u')::text")!!
    private fun Connection.j(json: String, path: String): String? = unico("select ('${json.replace("'", "''")}'::jsonb) #>> '{$path}'")
    private fun Connection.slots(json: String): List<String> =
        filas("select (e ->> 'posicion') || ':' || (e ->> 'tipoSlot') || ':' || (e ->> 'preguntaId') from jsonb_array_elements(('${json.replace("'", "''")}'::jsonb) -> 'preguntas') e order by (e ->> 'posicion')::int")
            .map { it[0]!! }
    private fun Connection.answer(u: String, reto: String, n: Int, correct: Boolean): String =
        unico("select public.app_answer_daily_challenge('$u', '$reto', '${q(n)}', '${if (correct) ok(n) else ko(n)}')::text")!!
    private fun num(slot: String): Int = slot.substringAfterLast('-').toInt(16)
    private fun kinds(s: List<String>) = s.map { it.substringBeforeLast(':') }

    /** Production today: only rafaga. 4 eligible (1, 2, 6, 7); annulled, reserve, inactive and other unpublished / inactive exams never enter. */
    private fun Connection.onlyRafagaInA() {
        exam(1, espA, "rafaga", listOf(Q(1), Q(2), Q(3, anulada = true), Q(4, reserva = true), Q(5, activa = false), Q(6), Q(7)))
        exam(2, espA, "razonado", listOf(Q(8)), publicado = "null")
        exam(3, espA, "inmersivo", listOf(Q(9)), publicado = "now() + interval '1 day'")
        exam(4, espA, "razonado", listOf(Q(10)), activo = false)
    }

    @Test
    fun onlyRafagaGivesThreeDistinctRafagaQuestionsWonWithTwo() = V1Db.escenario { c ->
        c.ejecutar(users)
        c.onlyRafagaInA()
        val t = c.today(ua)
        val s = c.slots(t)
        assertEquals(listOf("1:RAFAGA", "2:RAFAGA", "3:RAFAGA"), kinds(s), t)
        assertEquals(3, s.map(::num).toSet().size, "no repeated question: $s")
        assertTrue(s.map(::num).all { it in setOf(1, 2, 6, 7) }, "only eligible questions: $s")
        assertEquals("2", c.j(t, "necesarios"), "2 of 3")
        assertFalse(t.contains("opcionCorrectaId") || t.contains("\"respuesta\""), "no correction before answering")
        val reto = c.j(t, "retoId")!!
        assertEquals("3|2", c.filas("select objetivo_preguntas::text, aciertos_necesarios::text from public.retos_diarios where id = '$reto'").single().joinToString("|"))
        c.answer(ua, reto, num(s[0]), correct = true)
        assertEquals("EN_CURSO", c.j(c.answer(ua, reto, num(s[1]), correct = false), "estado"))
        val last = c.answer(ua, reto, num(s[2]), correct = true)
        assertEquals("SUPERADO|2|3|1", listOf("estado", "aciertos", "respondidas", "racha,actual").joinToString("|") { c.j(last, it)!! }, last)
    }

    @Test
    fun allThreeTypesGiveOneEachAndTwoTypesAreFilledFromTheUnion() = V1Db.escenario { c ->
        c.ejecutar(users)
        c.exam(10, espA, "inmersivo", listOf(Q(0x10), Q(0x11)))
        c.exam(11, espA, "razonado", listOf(Q(0x20), Q(0x21)))
        c.exam(12, espA, "rafaga", listOf(Q(0x30), Q(0x31)))
        val s = c.slots(c.today(ua))
        assertEquals(listOf("1:INMERSIVA", "2:RAZONADA", "3:RAFAGA"), kinds(s), "three types: one each, nothing filled")
        // B: one immersive question and several rafaga: the immersive one, one rafaga by type, then one more rafaga by the fill
        c.exam(13, espB, "inmersivo", listOf(Q(0x40)))
        c.exam(14, espB, "rafaga", listOf(Q(0x50), Q(0x51), Q(0x52)))
        val sb = c.slots(c.today(ub))
        assertEquals(listOf("1:INMERSIVA", "2:RAFAGA", "3:RAFAGA"), kinds(sb), sb.toString())
        assertEquals(q(0x40), sb[0].substringAfterLast(':'))
        assertEquals(3, sb.map(::num).toSet().size, "no repeated question: $sb")
    }

    @Test
    fun aSpecialtyWithTwoEligibleQuestionsGetsTwo() = V1Db.escenario { c ->
        c.ejecutar(users)
        c.exam(20, espC, "razonado", listOf(Q(0x60), Q(0x61, anulada = true)))
        c.exam(21, espC, "rafaga", listOf(Q(0x62), Q(0x63, reserva = true)))
        val t = c.today(uc)
        assertEquals(listOf("1:RAZONADA:${q(0x60)}", "2:RAFAGA:${q(0x62)}"), c.slots(t), "what exists, no repeats")
        assertEquals("2", c.j(t, "necesarios"))
        assertEquals("NO_DISPONIBLE", c.j(c.today(ua), "estado"), "no eligible question at all: no challenge")
    }

    @Test
    fun theFillIsDeterministicPerDayNeverRepeatsAndVariesAcrossDays() = V1Db.escenario { c ->
        c.ejecutar(users)
        c.exam(30, espA, "rafaga", (0x70..0x8f).map { Q(it) })
        fun pick(day: String): List<String> {
            c.ejecutar(
                """
                delete from public.preguntas_reto_diario where reto_id in (select id from public.retos_diarios where fecha = '$day'::date);
                delete from public.retos_diarios where fecha = '$day'::date;
                """.trimIndent(),
            )
            assertEquals("true", c.unico("select private.ensure_challenge_row('$day'::date, '$espA', null)::text"))
            return c.filas(
                "select pr.posicion || ':' || pr.tipo_slot || ':' || pr.pregunta_id from public.preguntas_reto_diario pr join public.retos_diarios r on r.id = pr.reto_id " +
                    "where r.fecha = '$day'::date and r.especialidad_id = '$espA' and r.comunidad_id is null order by pr.posicion",
            ).map { it[0]!! }
        }
        val first = pick("2026-12-01")
        assertEquals(first, pick("2026-12-01"), "re-creating the same day and segment picks the same three")
        val days = (1..20).map { pick("2027-01-" + it.toString().padStart(2, '0')) }
        assertTrue(days.all { d -> d.size == 3 && d.map { it.substringAfterLast(':') }.toSet().size == 3 }, "always 3 distinct: $days")
        assertTrue(days.toSet().size >= 10, "the picks vary by day: ${days.toSet().size}")
    }

    @Test
    fun theVerifyBlockProvesTheEndStateAndTheRollbackRestoresTheF20Body() = V1Db.escenario { c ->
        c.ejecutar(users)
        val verify = Slice0Db.verifyDe("supabase/migrations/$migration.sql")
        val rows = c.filas(verify)
        assertEquals(4, rows.size, "VERIFY rows: $rows")
        assertEquals(emptyList(), rows.filter { it[1] != "0" }, "every VERIFY counter is 0")
        val acl = c.acl()
        c.ejecutar(Slice0Db.rollbackDe("supabase/migrations/$migration.sql"))
        assertEquals(acl, c.acl(), "same ACL after the rollback")
        assertEquals(listOf("challenge_row_not_filled_to_three"), c.filas(verify).filter { it[1] != "0" }.map { it[0] }, "after the rollback the VERIFY detects it")
        c.onlyRafagaInA()
        assertEquals(1, c.slots(c.today(ua)).size, "the F20 rule is back: one per type, no fill")
    }

    @Test
    fun withoutTheMigrationOnlyRafagaGivesOneQuestion() = V1Db.escenario(sin = setOf(migration)) { c ->
        c.ejecutar(users)
        c.onlyRafagaInA()
        assertEquals(1, c.slots(c.today(ua)).size)
        assertNotEquals("0", c.filas(Slice0Db.verifyDe("supabase/migrations/$migration.sql")).first { it[0] == "challenge_row_not_filled_to_three" }[1])
    }

    private fun Connection.acl(): String =
        filas(
            "select has_function_privilege('anon', 'private.ensure_challenge_row(date,uuid,uuid)', 'execute')::text || " +
                "has_function_privilege('authenticated', 'private.ensure_challenge_row(date,uuid,uuid)', 'execute')::text",
        ).single()[0]!!
}
