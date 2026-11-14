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
 * `20261114300000_v1_stats_fix` (F27, FA54, propietario 2026-11-14): a demo or a practice never burns a question; aciertos / fallos (and tags) count real exams,
 * practices and the daily challenge; Nota media, league and ranking count only attempts with cuenta_para_ranking (now really set on the first real attempt: the
 * column default used to hide it) with the FROZEN penalty, PD = A - E/(n-1), same function as the attempt grade; "sin contestar" by question with the same criterion.
 * Backfill of existing data, VERIFY and ROLLBACK. Full V1 schema; the backfill runs over data written WITHOUT the migration.
 */
class V1StatsFixMigrationTest {
    private val migration = "20261114300000_v1_stats_fix"
    private val path = "supabase/migrations/$migration.sql"
    private val u = "a0000000-0000-0000-0000-0000000027a1"
    private val u2 = "a0000000-0000-0000-0000-0000000027a2"
    private val fresh = "a0000000-0000-0000-0000-0000000027a3"
    private val esp = "e1000000-0000-0000-0000-0000000027a1"
    private val com = "c1000000-0000-0000-0000-0000000027a1"
    private val pen = "e27a0000-0000-0000-0000-000000000001"     // penalised (rational), 4 options: q 1..4
    private val off = "e27a0000-0000-0000-0000-000000000002"     // penalty off, 4 options: q 5..8
    private val locked = "e27a0000-0000-0000-0000-000000000003"  // premium without access (demo), 4 options: q 11..15

    private fun q(n: Int) = "f27a0000-0000-0000-0000-0000000000" + n.toString().padStart(2, '0')
    private fun opt(n: Int, letra: Int) = "0c27a000-0000-0000-000$letra-0000000000" + n.toString().padStart(2, '0')

    private val seed = """
        insert into auth.users (id, email, aud, role) values ('$u', 's-u@test.local', 'authenticated', 'authenticated'), ('$u2', 's-u2@test.local', 'authenticated', 'authenticated'),
          ('$fresh', 's-f@test.local', 'authenticated', 'authenticated');
        insert into public.especialidades (id, codigo, nombre, slug, activa) values ('$esp', 'F27', 'F27', 'f27', true);
        insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('$com', 'F27', 'Com F27', 'com-f27');
        insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id)
          values ('$u', 'su', 's_f27_u', true, 'activa', '$esp'), ('$u2', 'su2', 's_f27_u2', true, 'activa', '$esp'), ('$fresh', 'sf', 's_f27_f', true, 'activa', '$esp')
          on conflict (usuario_id) do update set especialidad_principal_id = excluded.especialidad_principal_id;
        begin;
        set local session_replication_role = replica;
        insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, regla_anuladas, penalizacion_racional)
          values ('$pen', 'F27-1', '$esp', '$com', 'Penalizado', 'rafaga', 'gratuito', true, now() - interval '1 day', 'NO_PUNTUA', true),
                 ('$off', 'F27-2', '$esp', '$com', 'Sin penalizacion', 'rafaga', 'gratuito', true, now() - interval '1 day', 'NO_PUNTUA', false),
                 ('$locked', 'F27-3', '$esp', '$com', 'Cerrado', 'rafaga', 'premium', true, now() - interval '1 day', 'NO_PUNTUA', false);
        insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa)
          select ('f27a0000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, 'F27-' || n, 'rafaga', 'Pregunta ' || n, true from generate_series(1, 15) n;
        insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion)
          select ('0c27a000-0000-0000-000' || o || '-0000000000' || lpad(n::text, 2, '0'))::uuid, ('f27a0000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, chr(64 + o), 'Opcion', o
            from generate_series(1, 15) n, generate_series(1, 4) o;
        insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento)
          select ('f27a0000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, ('0c27a000-0000-0000-0001-0000000000' || lpad(n::text, 2, '0'))::uuid, 'x' from generate_series(1, 15) n;
        insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen)
          select '$pen'::uuid, ('f27a0000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, n, n from generate_series(1, 4) n
          union all
          select '$off'::uuid, ('f27a0000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, n - 4, n - 4 from generate_series(5, 8) n
          union all
          select '$locked'::uuid, ('f27a0000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, n - 10, n - 10 from generate_series(11, 15) n;
        commit;
    """.trimIndent()

    private fun Connection.start(user: String, exam: String, modo: String = "NORMAL"): String =
        unico("select public.app_start_exam_attempt('$user', '$exam', '$modo') ->> 'intento_id'")!!
    private fun Connection.answer(user: String, attempt: String, n: Int, correct: Boolean): String =
        unico("select public.app_record_attempt_answer('$user', '$attempt', '${q(n)}', '${opt(n, if (correct) 1 else 2)}', 1500, 0, null)::text")!!
    private fun Connection.finish(user: String, attempt: String) = ejecutar("select public.app_finish_exam_attempt('$user', '$attempt')")

    /** aciertos|fallos|noContestadas|notaMedia.valor|preguntasValidas */
    private fun Connection.summary(user: String): String =
        filas(
            "select (s #>> '{totales,aciertos}') || '|' || (s #>> '{totales,fallos}') || '|' || (s #>> '{totales,noContestadas}') || '|' || coalesce(s #>> '{notaMedia,valor}', 'null') " +
                "|| '|' || (s ->> 'preguntasValidas') from (select public.app_stats_summary('$user') as s) x",
        ).single()[0]!!

    @Test
    fun aNewUserHasEverythingAtZero() = V1Db.escenario { c ->
        c.ejecutar(seed)
        assertEquals("0|0|0|null|0", c.summary(fresh))
    }

    @Test
    fun threeRightOneWrongInAFourOptionPenalisedExamGivesTheRationalNota() = V1Db.escenario { c ->
        c.ejecutar(seed)
        val a = c.start(u, pen)
        assertEquals("true", c.unico("select cuenta_para_ranking::text from public.intentos_examen where id = '$a'"), "the first real attempt counts for ranking")
        listOf(1 to true, 2 to true, 3 to true, 4 to false).forEach { (n, ok) -> c.answer(u, a, n, ok) }
        c.finish(u, a)
        // (3 - 1/3) / 4 x 10 = 6.666.. -> 6.67, the same number as the attempt grade
        assertEquals("3|1|0|6.67|4", c.summary(u))
        assertEquals("6.67", c.unico("select nota::text from public.intentos_examen where id = '$a'"), "Nota media == attempt grade for the same answers")
        assertEquals("9240", c.unico("select penalizacion::text from public.primeras_respuestas_ranking where usuario_id = '$u' and pregunta_id = '${q(4)}'"), "27720 / (4 - 1)")
        assertEquals("4", c.unico("select count(*)::text from public.respuestas_intento where intento_id = '$a' and cuenta_para_ranking"))
        // a second attempt of the same exam does not count for ranking, and nothing is counted twice
        val b = c.start(u, pen)
        assertEquals("false", c.unico("select cuenta_para_ranking::text from public.intentos_examen where id = '$b'"))
        c.answer(u, b, 4, true)
        assertEquals("3|1|0|6.67|4", c.summary(u))
    }

    @Test
    fun anExamWithThePenaltyOffWeighsAWrongAnswerZeroAndBlanksFollowTheSameCriterion() = V1Db.escenario { c ->
        c.ejecutar(seed)
        val a = c.start(u, off)
        c.answer(u, a, 5, true)
        c.answer(u, a, 6, false)
        c.finish(u, a)
        // 1 of 2 answered, no penalty: 5.00; q7 and q8 left blank in a finished attempt
        assertEquals("1|1|2|5.00|2", c.summary(u))
        assertEquals("0", c.unico("select penalizacion::text from public.primeras_respuestas_ranking where usuario_id = '$u' and pregunta_id = '${q(6)}'"))
        // the blanks answered later in a second (non-ranking) attempt move to aciertos / fallos; Nota media and league count do not move
        val b = c.start(u, off)
        c.answer(u, b, 7, true)
        c.answer(u, b, 8, false)
        c.finish(u, b)
        assertEquals("2|2|0|5.00|2", c.summary(u))
    }

    @Test
    fun aDemoNeverBurnsTheQuestion() = V1Db.escenario { c ->
        c.ejecutar(seed)
        val d = c.start(u, locked, "DEMO")
        assertEquals("true", c.unico("select es_demo::text from public.intentos_examen where id = '$d'"))
        c.answer(u, d, 11, true)
        assertEquals("0|0|0|null|0", c.summary(u), "a demo answer never counts")
        c.ejecutar("insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta) values ('$u', '$locked', 'admin', now() - interval '1 hour', now() + interval '5 days')")
        val a = c.start(u, locked)
        assertEquals("false|true", c.filas("select es_demo::text, cuenta_para_ranking::text from public.intentos_examen where id = '$a'").single().joinToString("|"))
        c.answer(u, a, 11, true)
        assertEquals("1|0|0|10.00|1", c.summary(u), "the real answer to the question seen in the demo counts everywhere")
    }

    @Test
    fun aPracticeAnswerCountsInAciertosButNeverInTheRanking() = V1Db.escenario { c ->
        c.ejecutar(seed)
        val p = c.unico("select public.app_start_practice_attempt('$u', 5) ->> 'intento_id'")!!
        val first = c.unico("select pregunta_id::text from public.preguntas_intento where intento_id = '$p' order by posicion limit 1")!!
        val n = first.takeLast(2).toInt()
        c.answer(u, p, n, false)
        assertEquals("0|1|0|null|0", c.summary(u), "practice: fallos yes, Nota media / league no")
        assertEquals("practica", c.unico("select origen from public.primeras_respuestas_usuario where usuario_id = '$u'"))
        // the same question later in a real ranking attempt: counted once in aciertos/fallos (the practice one), but it enters the ranking now
        val exam = if (n <= 4) pen else off
        val a = c.start(u, exam)
        c.answer(u, a, n, true)
        assertEquals("0|1|0|10.00|1", c.summary(u))
    }

    @Test
    fun aDailyChallengeAnswerCountsInAciertosButNeverInTheRanking() = V1Db.escenario { c ->
        c.ejecutar(seed)
        val t = c.unico("select public.app_get_daily_challenge('$u')::text")!!
        val reto = c.unico("select ('${t.replace("'", "''")}'::jsonb) ->> 'retoId'")!!
        val pq = c.unico("select ('${t.replace("'", "''")}'::jsonb) #>> '{preguntas,0,preguntaId}'")!!
        val n = pq.takeLast(2).toInt()
        c.ejecutar("select public.app_answer_daily_challenge('$u', '$reto', '$pq', '${opt(n, 1)}')")
        assertEquals("1|0|0|null|0", c.summary(u))
        assertEquals("reto|null", c.filas("select origen, coalesce(tiempo_ms::text, 'null') from public.primeras_respuestas_usuario where usuario_id = '$u'").single().joinToString("|"))
        assertEquals("0", c.unico("select count(*)::text from public.primeras_respuestas_ranking where usuario_id = '$u'"))
    }

    @Test
    fun theBackfillRepairsBurnedQuestionsCountsPracticeAndRetoAndRebuildsTheRanking() = V1Db.escenario(sin = setOf(migration)) { c ->
        c.ejecutar(seed)
        // OLD rules: the demo burns q11 for the real answer; the start RPC leaves cuenta_para_ranking false; nota without penalty
        val d = c.start(u, locked, "DEMO")
        c.answer(u, d, 11, true)
        c.ejecutar("insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta) values ('$u', '$locked', 'admin', now() - interval '1 hour', now() + interval '5 days')")
        val real = c.start(u, locked)
        c.answer(u, real, 11, true)
        val a = c.start(u, pen)
        listOf(1 to true, 2 to true, 3 to true, 4 to false).forEach { (n, ok) -> c.answer(u, a, n, ok) }
        c.finish(u, a)
        val p = c.unico("select public.app_start_practice_attempt('$u2', 5) ->> 'intento_id'")!!
        val pn = c.unico("select pregunta_id::text from public.preguntas_intento where intento_id = '$p' order by posicion limit 1")!!.takeLast(2).toInt()
        c.answer(u2, p, pn, true)
        val t = c.unico("select public.app_get_daily_challenge('$u2')::text")!!
        val reto = c.unico("select ('${t.replace("'", "''")}'::jsonb) ->> 'retoId'")!!
        val rq = c.unico("select ('${t.replace("'", "''")}'::jsonb) #>> '{preguntas,0,preguntaId}'")!!
        c.ejecutar("select public.app_answer_daily_challenge('$u2', '$reto', '$rq', '${opt(rq.takeLast(2).toInt(), 2)}')")
        // C1: the same burned pattern in a LEGACY attempt (no frozen rule = pre-stats era) is never added by the backfill (its answer belongs to the pre-V1 counters)
        val fd = c.start(fresh, locked, "DEMO")
        c.answer(fresh, fd, 12, true)
        c.ejecutar("insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta) values ('$fresh', '$locked', 'admin', now() - interval '1 hour', now() + interval '5 days')")
        val legacy = c.start(fresh, locked)
        c.answer(fresh, legacy, 12, true)
        c.ejecutar("begin; set local session_replication_role = replica; update public.intentos_examen set regla_anuladas = null where id = '$legacy'; commit;")
        assertEquals("false", c.unico("select bool_or(cuenta_para_ranking)::text from public.intentos_examen"), "before: no attempt ever counted for ranking")
        assertEquals("3|1|0|7.50|4", c.summary(u), "before: q11 burned, nota 3 of 4 without penalty")
        val u2Before = c.summary(u2)
        assertEquals("0|0|0|null|0", u2Before, "before: practice and reto never counted")

        c.ejecutar(Slice0Db.leer(path))
        // u: q11 counted (burned by the demo); ranking rebuilt with the frozen rational penalty over q1..q4 + q11 = (4 - 1/3) / 5 x 10 = 7.33
        assertEquals("4|1|0|7.33|5", c.summary(u))
        assertEquals("true|true|false", c.filas("select (select cuenta_para_ranking from public.intentos_examen where id = '$real')::text, (select cuenta_para_ranking from public.intentos_examen where id = '$a')::text, (select cuenta_para_ranking from public.intentos_examen where id = '$d')::text").single().joinToString("|"))
        // u2: the practice answer (correct) and the reto answer (wrong, unless it is the same question) count in aciertos / fallos, never in the ranking
        val u2After = c.summary(u2).split("|")
        assertEquals(if (rq.takeLast(2).toInt() == pn) listOf("1", "0") else listOf("1", "1"), u2After.take(2), u2After.toString())
        assertEquals(listOf("null", "0"), u2After.takeLast(2))
        assertEquals("1", c.unico("select count(*)::text from public.estadisticas_usuario where usuario_id = '$u2'"), "u2 now has a counters row")
        assertEquals("0", c.unico("select count(*)::text from public.primeras_respuestas_usuario where usuario_id = '$fresh' and pregunta_id = '${q(12)}'"), "legacy era answer: not added (C1)")
        // VERIFY = 0 on the backfilled data; applying again changes nothing (idempotent)
        assertEquals(emptyList(), c.filas(Slice0Db.verifyDe(path)).filter { it[1] != "0" })
        c.ejecutar(Slice0Db.leer(path))
        assertEquals("4|1|0|7.33|5", c.summary(u))
        assertEquals(u2After.joinToString("|"), c.summary(u2))
    }

    @Test
    fun theVerifyBlockProvesTheEndStateAndTheRollbackRestoresTheOldBodies() = V1Db.escenario { c ->
        c.ejecutar(seed)
        val verify = Slice0Db.verifyDe(path)
        val rows = c.filas(verify)
        assertTrue(rows.size >= 15, "VERIFY rows: $rows")
        assertEquals(emptyList(), rows.filter { it[1] != "0" }, "every VERIFY counter is 0")
        c.ejecutar(Slice0Db.rollbackDe(path))
        assertEquals(
            "null|null|null|true|true|0",
            c.filas(
                "select coalesce(to_regclass('public.primeras_respuestas_ranking')::text, 'null'), coalesce(to_regprocedure('private.app_count_answer(uuid,uuid,boolean,integer,uuid,text)')::text, 'null'), " +
                    "coalesce((select tgname::text from pg_trigger where tgname = 'trg_respuestas_reto_diario_counts'), 'null'), " +
                    "((select prosrc from pg_proc where oid = 'public.app_record_attempt_answer(uuid,uuid,uuid,uuid,integer,integer,integer)'::regprocedure) like '%v_visto_en_demo%')::text, " +
                    "((select prosrc from pg_proc where oid = 'public.preparar_intento_examen()'::regprocedure) like '%coalesce(new.cuenta_para_ranking%')::text, " +
                    "(select count(*) from information_schema.columns where table_schema = 'public' and table_name = 'primeras_respuestas_usuario' and column_name = 'origen')::text",
            ).single().joinToString("|"),
            "the rollback drops what F27 added and restores the 20261022101000 / 20261022101600 bodies",
        )
        // the old rule is back: the start RPC never sets the ranking flag
        val a = c.start(u, pen)
        assertEquals("false", c.unico("select cuenta_para_ranking::text from public.intentos_examen where id = '$a'"))
    }
}
