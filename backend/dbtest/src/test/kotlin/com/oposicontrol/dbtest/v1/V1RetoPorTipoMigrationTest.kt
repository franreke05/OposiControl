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
 * `20261109100000_v1_reto_por_tipo` (F20, propietario 2026-11-09): the daily challenge takes ONE question per EXAM TYPE (inmersivo, razonado, rafaga)
 * from the published exams of the user's specialty (main, non-annulled, active questions; no demo flag), skips the types without questions (a reto of
 * 1..3 questions) and picks deterministically per day and segment. Pass rule ceil(2n/3). Full V1 schema; negative control without the migration
 * (= production on 2026-11-09: only rafaga exams, so no row and NO_DISPONIBLE for everyone).
 */
class V1RetoPorTipoMigrationTest {
    private val migration = "20261109100000_v1_reto_por_tipo"

    /** The F20 body is tested on its own: 20261109100200 (F20b, always 3) replaces ensure_challenge_row and has its own test (V1RetoTresMigrationTest). */
    private val later = setOf("20261109100200_v1_reto_tres")
    private val espA = "e1000000-0000-0000-0000-0000000020a1"
    private val espB = "e1000000-0000-0000-0000-0000000020b1"
    private val espC = "e1000000-0000-0000-0000-0000000020c1"
    private val com = "c1000000-0000-0000-0000-0000000020a1"
    private val ua = "a0000000-0000-0000-0000-0000000020a1"
    private val ua2 = "a0000000-0000-0000-0000-0000000020a2"
    private val ub = "a0000000-0000-0000-0000-0000000020b1"
    private val uc = "a0000000-0000-0000-0000-0000000020c1"

    /** Question / exam ids by number (two hex digits). */
    private fun q(n: Int) = "f2000000-0000-0000-0000-0000000000" + n.toString(16).padStart(2, '0')
    private fun ok(n: Int) = "0c200000-0000-0000-0000-0000000000" + n.toString(16).padStart(2, '0')
    private fun ko(n: Int) = "0c210000-0000-0000-0000-0000000000" + n.toString(16).padStart(2, '0')
    private fun ex(n: Int) = "e2000000-0000-0000-0000-0000000000" + n.toString(16).padStart(2, '0')

    private val users = """
        insert into auth.users (id, email, aud, role, email_confirmed_at) values
          ('$ua', 'f20-a@test.local', 'authenticated', 'authenticated', now()), ('$ua2', 'f20-a2@test.local', 'authenticated', 'authenticated', now()),
          ('$ub', 'f20-b@test.local', 'authenticated', 'authenticated', now()), ('$uc', 'f20-c@test.local', 'authenticated', 'authenticated', now());
        insert into public.especialidades (id, codigo, nombre, slug, activa) values ('$espA', 'F20A', 'F20A', 'f20a', true), ('$espB', 'F20B', 'F20B', 'f20b', true), ('$espC', 'F20C', 'F20C', 'f20c', true);
        insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('$com', 'F20', 'Com F20', 'com-f20');
        insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id) values
          ('$ua', 'a', 'f20_a', true, 'activa', '$espA'), ('$ua2', 'a2', 'f20_a2', true, 'activa', '$espA'), ('$ub', 'b', 'f20_b', true, 'activa', '$espB'), ('$uc', 'c', 'f20_c', true, 'activa', '$espC')
          on conflict (usuario_id) do update set especialidad_principal_id = excluded.especialidad_principal_id;
    """.trimIndent()

    private class Q(val n: Int, val anulada: Boolean = false, val reserva: Boolean = false, val activa: Boolean = true, val demo: Boolean = false)

    /** One exam with its questions (triggers off, as the other V1 tests). [publicado] is a SQL expression. */
    private fun Connection.exam(n: Int, esp: String, tipo: String, questions: List<Q>, publicado: String = "now() - interval '1 day'", activo: Boolean = true) {
        val tipoPregunta = when (tipo) { "inmersivo" -> "inmersiva"; "razonado" -> "razonada"; else -> "rafaga" }
        val sql = buildString {
            appendLine("begin;")
            appendLine("set local session_replication_role = replica;")
            appendLine(
                "insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en) " +
                    "values ('${ex(n)}', 'F20-$n', '$esp', '$com', 'Examen $n', '$tipo', 'premium', $activo, $publicado);",
            )
            questions.forEachIndexed { i, x ->
                appendLine("insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa) values ('${q(x.n)}', 'F20-Q${x.n}', '$tipoPregunta', 'Pregunta ${x.n}', ${x.activa});")
                appendLine("insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion) values ('${ok(x.n)}', '${q(x.n)}', 'A', 'Si', 1), ('${ko(x.n)}', '${q(x.n)}', 'B', 'No', 2);")
                appendLine("insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento) values ('${q(x.n)}', '${ok(x.n)}', 'x');")
                appendLine(
                    "insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen, anulada, motivo_anulacion, es_reserva, orden_reserva, es_demo) values " +
                        "('${ex(n)}', '${q(x.n)}', ${i + 1}, ${i + 1}, ${x.anulada}, ${if (x.anulada) "'anulada'" else "null"}, ${x.reserva}, ${if (x.reserva) "1" else "null"}, ${x.demo});",
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

    /** Production today: only rafaga exams. Annulled, reserve and inactive questions, and exams not published / future / inactive of other types never enter. */
    private fun Connection.onlyRafagaInA() {
        exam(1, espA, "rafaga", listOf(Q(1), Q(2), Q(3, anulada = true), Q(4, reserva = true), Q(5, activa = false)))
        exam(2, espA, "razonado", listOf(Q(6)), publicado = "null")                          // draft
        exam(3, espA, "inmersivo", listOf(Q(7)), publicado = "now() + interval '1 day'")     // scheduled
        exam(4, espA, "razonado", listOf(Q(8)), activo = false)                              // inactive
    }

    @Test
    fun onlyRafagaExamsGiveAOneQuestionChallengeThatPassesWithOneCorrect() = V1Db.escenario(sin = later) { c ->
        c.ejecutar(users)
        c.onlyRafagaInA()
        val t = c.today(ua)
        assertEquals("DISPONIBLE", c.j(t, "estado"), t)
        val s = c.slots(t)
        assertEquals(1, s.size, "one question: $s")
        assertTrue(s.single().startsWith("1:RAFAGA:"), "compact position 1, rafaga slot: $s")
        assertTrue(num(s.single()) in setOf(1, 2), "only main, non-annulled, active questions of the published rafaga exam: $s")
        assertEquals("1", c.j(t, "necesarios"), "ceil(2*1/3) = 1")
        assertFalse(t.contains("opcionCorrectaId") || t.contains("\"respuesta\""), "no correction before answering")
        val reto = c.j(t, "retoId")!!
        assertEquals("1|1", c.filas("select objetivo_preguntas::text, aciertos_necesarios::text from public.retos_diarios where id = '$reto'").single().joinToString("|"))
        assertEquals(reto, c.j(c.today(ua2), "retoId"), "one row per day and segment: the same reto for the whole specialty")
        // the single answer closes it: right = SUPERADO (streak 1, reward frozen), wrong = FALLADO
        val win = c.answer(ua, reto, num(s.single()), correct = true)
        assertEquals("SUPERADO|1|1|1", listOf("estado", "aciertos", "respondidas", "racha,actual").joinToString("|") { c.j(win, it)!! }, win)
        assertEquals("false", c.j(win, "recompensa,reclamada"))
        assertEquals(win, c.answer(ua, reto, num(s.single()), correct = true), "idempotent retry")
        val lose = c.answer(ua2, reto, num(s.single()), correct = false)
        assertEquals("FALLADO|0|1", listOf("estado", "aciertos", "respondidas").joinToString("|") { c.j(lose, it)!! }, lose)
        assertEquals("0", c.j(lose, "racha,actual"))
        val claim = c.unico("select public.app_claim_daily_challenge('$ua', '$reto')::text")!!
        assertEquals("true", c.j(claim, "recompensa,reclamada"), claim)
        assertEquals("SUPERADO", c.j(c.today(ua), "estado"))
    }

    @Test
    fun allThreeTypesGiveOneQuestionEachAndTwoTypesGiveTwo() = V1Db.escenario(sin = later) { c ->
        c.ejecutar(users)
        // A: the three types. The immersive exam has NO demo flag: the flag no longer matters.
        c.exam(10, espA, "inmersivo", listOf(Q(0x10), Q(0x11)))
        c.exam(11, espA, "razonado", listOf(Q(0x20), Q(0x21, anulada = true)))
        c.exam(12, espA, "rafaga", listOf(Q(0x30), Q(0x31)))
        // C: inmersivo + rafaga only
        c.exam(13, espC, "inmersivo", listOf(Q(0x40)))
        c.exam(14, espC, "rafaga", listOf(Q(0x50)))
        val t = c.today(ua)
        val s = c.slots(t)
        assertEquals(listOf("1:INMERSIVA", "2:RAZONADA", "3:RAFAGA"), s.map { it.substringBeforeLast(':') }, t)
        assertTrue(num(s[0]) in setOf(0x10, 0x11) && num(s[1]) == 0x20 && num(s[2]) in setOf(0x30, 0x31), "each from its own type: $s")
        assertEquals("2", c.j(t, "necesarios"), "3 questions -> 2 correct, as before")
        val reto = c.j(t, "retoId")!!
        // 2 of 3 right passes on the third answer
        c.answer(ua, reto, num(s[0]), correct = false)
        assertEquals("EN_CURSO", c.j(c.answer(ua, reto, num(s[1]), correct = true), "estado"))
        assertEquals("SUPERADO", c.j(c.answer(ua, reto, num(s[2]), correct = true), "estado"))
        val tc = c.today(uc)
        assertEquals(listOf("1:INMERSIVA:${q(0x40)}", "2:RAFAGA:${q(0x50)}"), c.slots(tc), "an empty type is skipped, positions stay compact")
        assertEquals("2", c.j(tc, "necesarios"), "ceil(2*2/3) = 2")
        val rc = c.j(tc, "retoId")!!
        assertEquals("EN_CURSO", c.j(c.answer(uc, rc, 0x40, correct = true), "estado"))
        assertEquals("FALLADO", c.j(c.answer(uc, rc, 0x50, correct = false), "estado"), "1 of 2 does not pass")
    }

    @Test
    fun theChallengeIsScopedToTheUserSpecialty() = V1Db.escenario(sin = later) { c ->
        c.ejecutar(users)
        c.onlyRafagaInA()
        c.exam(20, espB, "razonado", listOf(Q(0x60), Q(0x61)))
        val sa = c.slots(c.today(ua))
        val sb = c.slots(c.today(ub))
        assertTrue(sa.single().startsWith("1:RAFAGA:") && num(sa.single()) in setOf(1, 2), "A: its own rafaga only: $sa")
        assertTrue(sb.single().startsWith("1:RAZONADA:") && num(sb.single()) in setOf(0x60, 0x61), "B: its own razonado only, never A's rafaga: $sb")
        assertEquals("NO_DISPONIBLE", c.j(c.today(uc), "estado"), "a specialty without published exams has no challenge")
        assertEquals("0", c.unico("select count(*) from public.retos_diarios where especialidad_id = '$espC'"))
        // another user's / segment's reto is not an oracle
        val ra = c.j(c.today(ua), "retoId")!!
        val e = runCatching { c.answer(ub, ra, num(sa.single()), correct = true) }.exceptionOrNull()
        assertTrue(e?.message?.contains("NOT_FOUND") == true, "B cannot answer A's reto: $e")
    }

    @Test
    fun thePickIsDeterministicPerDayAndSegmentAndChangesAcrossDays() = V1Db.escenario(sin = later) { c ->
        c.ejecutar(users)
        c.exam(30, espA, "rafaga", (0x70..0x7f).map { Q(it) })
        c.exam(31, espA, "razonado", (0x80..0x8f).map { Q(it) })
        fun pick(day: String): String {
            c.ejecutar(
                """
                delete from public.preguntas_reto_diario where reto_id in (select id from public.retos_diarios where fecha = '$day'::date);
                delete from public.retos_diarios where fecha = '$day'::date;
                """.trimIndent(),
            )
            assertEquals("true", c.unico("select private.ensure_challenge_row('$day'::date, '$espA', null)::text"))
            return c.filas(
                "select string_agg(pr.posicion || ':' || pr.tipo_slot || ':' || pr.pregunta_id, ',' order by pr.posicion) from public.preguntas_reto_diario pr " +
                    "join public.retos_diarios r on r.id = pr.reto_id where r.fecha = '$day'::date and r.especialidad_id = '$espA' and r.comunidad_id is null",
            ).single()[0]!!
        }
        val first = pick("2026-12-01")
        assertEquals(first, pick("2026-12-01"), "re-creating the same day and segment picks the same questions")
        assertEquals(first, pick("2026-12-01"))
        assertTrue(first.startsWith("1:razonada:") && first.contains(",2:rafaga:"), first)
        val days = (1..20).map { pick("2027-01-" + it.toString().padStart(2, '0')) }.toSet()
        assertTrue(days.size >= 5, "20 days over 16 x 16 questions: the pick varies by day ($days)")
        // the DST windows are kept: the day ends at the next local midnight
        assertEquals(
            "true",
            c.unico("select (termina_en = private.reto_day_end(fecha))::text from public.retos_diarios where fecha = '2026-12-01'::date and especialidad_id = '$espA'"),
        )
    }

    @Test
    fun theVerifyBlockProvesTheEndStateAndTheRollbackRestoresTheThreeSlotRule() = V1Db.escenario(sin = later) { c ->
        c.ejecutar(users)
        val verify = Slice0Db.verifyDe("supabase/migrations/$migration.sql")
        val rows = c.filas(verify)
        assertEquals(5, rows.size, "VERIFY rows: $rows")
        assertEquals(emptyList(), rows.filter { it[1] != "0" }, "every VERIFY counter is 0")
        val acl = c.acl()
        c.ejecutar(Slice0Db.rollbackDe("supabase/migrations/$migration.sql"))
        assertEquals(acl, c.acl(), "same ACL after the rollback")
        val after = c.filas(verify).filter { it[1] != "0" }.map { it[0] }
        assertTrue("reto_pool_not_by_published_exam_type" in after && "challenge_row_requires_every_slot" in after, "after the rollback the VERIFY detects it: $after")
        c.onlyRafagaInA()
        assertEquals("NO_DISPONIBLE", c.j(c.today(ua), "estado"), "the old rule is back: a missing slot = no challenge")
    }

    @Test
    fun withoutTheMigrationOnlyRafagaMeansNoChallengeForAnyone() = V1Db.escenario(sin = later + migration) { c ->
        c.ejecutar(users)
        c.onlyRafagaInA()
        assertEquals("NO_DISPONIBLE", c.j(c.today(ua), "estado"))
        assertEquals("0", c.unico("select count(*) from public.retos_diarios"))
        assertNotEquals("0", c.filas(Slice0Db.verifyDe("supabase/migrations/$migration.sql")).first { it[0] == "challenge_row_requires_every_slot" }[1])
    }

    private fun Connection.acl(): String =
        filas(
            "select f, has_function_privilege('anon', f, 'execute')::text || has_function_privilege('authenticated', f, 'execute')::text from " +
                "(values ('private.reto_pool(uuid,uuid,text)'), ('private.ensure_challenge_row(date,uuid,uuid)')) x(f) order by f",
        ).joinToString(";") { it.joinToString("=") }
}
