package com.oposicontrol.dbtest.v1

import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `20261109100100_v1_practica` (F23, propietario 2026-11-09): "OposiQuizzes rapidos". Una practica de 5 / 10 / 20 preguntas al azar SOLO de los examenes publicados
 * de la especialidad que el usuario puede abrir (FREE / GRANTED / SUBSCRIPTION); sin acceso = nada. Es un intento de examen sin examen (es_practica, siempre es_demo):
 * se responde, se cierra y se corrige con las RPC de siempre y nunca cuenta (ranking, primera respuesta, estadisticas, Mis examenes, cupo de demos). El limitador
 * diario la cuenta salvo que todas sus preguntas vengan de examenes GRANTED. Esquema V1 completo; control negativo sin la migracion.
 */
class V1PracticaMigrationTest {
    private val migration = "20261109100100_v1_practica"
    private val user = "a0000000-0000-0000-0000-0000000023a1"
    private val nobody = "a0000000-0000-0000-0000-0000000023a2"
    private val noSpecialty = "a0000000-0000-0000-0000-0000000023a3"
    private val esp = "e1000000-0000-0000-0000-0000000023a1"
    private val esp2 = "e1000000-0000-0000-0000-0000000023a2"
    private val esp3 = "e1000000-0000-0000-0000-0000000023a3"
    private val com = "c1000000-0000-0000-0000-0000000023a1"
    private val free = "e23a0000-0000-0000-0000-000000000001"     // gratuito: q 1..3
    private val granted = "e23a0000-0000-0000-0000-000000000002"  // premium, concedido a user: q 4 (valida), 5 (anulada), 6 (reserva)
    private val locked = "e23a0000-0000-0000-0000-000000000003"   // premium sin acceso (demo): q 11..15
    private val foreign = "e23a0000-0000-0000-0000-000000000004"  // gratuito de OTRA especialidad: q 16
    private val draft = "e23a0000-0000-0000-0000-000000000005"    // gratuito sin publicar: q 17

    private fun q(n: Int) = "f23a0000-0000-0000-0000-0000000000" + n.toString().padStart(2, '0')
    private fun ok(n: Int) = "0c23a000-0000-0000-0000-0000000000" + n.toString().padStart(2, '0')
    private val allowed get() = setOf(q(1), q(2), q(3), q(4))

    private val seed = """
        insert into auth.users (id, email, aud, role) values ('$user', 'p-u@test.local', 'authenticated', 'authenticated'), ('$nobody', 'p-n@test.local', 'authenticated', 'authenticated'),
          ('$noSpecialty', 'p-s@test.local', 'authenticated', 'authenticated');
        insert into public.especialidades (id, codigo, nombre, slug, activa) values ('$esp', 'F23', 'F23', 'f23', true), ('$esp2', 'F23B', 'F23B', 'f23b', true), ('$esp3', 'F23C', 'F23C', 'f23c', true);
        insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('$com', 'F23', 'Com F23', 'com-f23');
        insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id)
          values ('$user', 'pu', 'p_f23_user', true, 'activa', '$esp'), ('$nobody', 'pn', 'p_f23_nobody', true, 'activa', '$esp3'), ('$noSpecialty', 'ps', 'p_f23_nospec', true, 'activa', null)
          on conflict (usuario_id) do update set especialidad_principal_id = excluded.especialidad_principal_id;
        begin;
        set local session_replication_role = replica;
        insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en)
          values ('$free', 'F23-1', '$esp', '$com', 'Gratis', 'rafaga', 'gratuito', true, now() - interval '1 day'),
                 ('$granted', 'F23-2', '$esp', '$com', 'Comprado', 'rafaga', 'premium', true, now() - interval '1 day'),
                 ('$locked', 'F23-3', '$esp', '$com', 'Cerrado', 'rafaga', 'premium', true, now() - interval '1 day'),
                 ('$foreign', 'F23-4', '$esp2', '$com', 'Otra', 'rafaga', 'gratuito', true, now() - interval '1 day'),
                 ('$draft', 'F23-5', '$esp', '$com', 'Borrador', 'rafaga', 'gratuito', true, null);
        insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa)
          select ('f23a0000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, 'F23-' || n, 'rafaga', 'Pregunta ' || n, true from generate_series(1, 17) n;
        insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion)
          select ('0c23a000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, ('f23a0000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, 'A', 'Si', 1 from generate_series(1, 17) n
          union all
          select ('0c23b000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, ('f23a0000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, 'B', 'No', 2 from generate_series(1, 17) n;
        insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento)
          select ('f23a0000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, ('0c23a000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, 'x' from generate_series(1, 17) n;
        insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen, anulada, motivo_anulacion, es_reserva, orden_reserva)
          select '$free'::uuid, ('f23a0000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, n, n, false, null, false, null from generate_series(1, 3) n
          union all
          select '$granted'::uuid, ('f23a0000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, n - 3, n - 3, n = 5, case when n = 5 then 'anulada' end, n = 6, case when n = 6 then 1 end from generate_series(4, 6) n
          union all
          select '$locked'::uuid, ('f23a0000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, n - 10, n - 10, false, null, false, null from generate_series(11, 15) n
          union all
          select '$foreign'::uuid, 'f23a0000-0000-0000-0000-000000000016'::uuid, 1, 1, false, null, false, null
          union all
          select '$draft'::uuid, 'f23a0000-0000-0000-0000-000000000017'::uuid, 1, 1, false, null, false, null;
        insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta) values ('$user', '$granted', 'compra', now() - interval '1 day', null);
        commit;
    """.trimIndent()

    private fun Connection.start(u: String = user, n: Int = 5): String = unico("select public.app_start_practice_attempt('$u', $n)::text")!!
    private fun Connection.outcome(json: String) = unico("select ('$json'::jsonb) ->> 'outcome'")
    private fun Connection.attemptOf(json: String) = unico("select ('$json'::jsonb) ->> 'intento_id'")!!
    private fun Connection.available(u: String = user) = unico("select (public.app_practice_availability('$u') ->> 'preguntas')")!!.toInt()
    private fun Connection.frozen(attempt: String): List<String> =
        filas("select pregunta_id::text from public.preguntas_intento where intento_id = '$attempt' order by posicion").map { it[0]!! }

    @Test
    fun thePoolIsOnlyTheMainServableQuestionsOfTheExamsTheUserCanOpen() = V1Db.escenario { c ->
        c.ejecutar(seed)
        assertEquals(4, c.available(), "free 1..3 + granted 4; not annulled 5, reserve 6, locked 11..15, another specialty 16 or a draft 17")
        assertEquals(0, c.available(nobody), "a specialty with no published exam")
        assertEquals(0, c.available(noSpecialty), "no specialty, no pool")
        val r = c.start(n = 10)
        assertEquals("CREATED", c.outcome(r), r)
        val a = c.attemptOf(r)
        assertEquals(allowed, c.frozen(a).toSet(), "fewer available than asked: all of them, once")
        assertEquals("4", c.unico("select total_preguntas::text from public.intentos_examen where id = '$a'"))
        assertEquals(
            listOf("t", "t", null, "examen", "app_start_exam_attempt", "f", "f", "0", "rafaga", "NO_PUNTUA", "FREE", esp),
            c.filas(
                "select es_practica, es_demo, examen_id, origen_intento, creado_por_comando, cuenta_para_ranking, es_primer_intento_examen, intento_numero_usuario::text, " +
                    "tipo_examen_snapshot, regla_anuladas, acceso_estado, especialidad_snapshot_id::text from public.intentos_examen where id = '$a'",
            ).single().map { it?.let { v -> if (v == "true") "t" else if (v == "false") "f" else v } },
            "a practice attempt never counts and is limited like a FREE attempt (not every question is GRANTED)",
        )
        assertEquals(listOf("PRINCIPAL"), c.filas("select distinct rol from public.preguntas_intento where intento_id = '$a'").map { it[0] })
        assertEquals("0", c.unico("select count(*)::text from public.preguntas_intento where intento_id = '$a' and revision_pregunta_id is null"), "bound to immutable revisions")
        assertEquals("PRACTICE_EMPTY", c.outcome(c.start(nobody)))
        assertEquals("PRACTICE_EMPTY", c.outcome(c.start(noSpecialty)))
        assertEquals("INVALID_SIZE", c.outcome(c.start(n = 7)))
    }

    @Test
    fun itIsAnsweredAndFinishedWithTheUsualRpcsAndNeverCounts() = V1Db.escenario { c ->
        c.ejecutar(seed)
        val a = c.attemptOf(c.start())
        val first = c.frozen(a).first()
        val n = first.takeLast(2).toInt()
        val answer = c.unico("select public.app_record_attempt_answer('$user', '$a', '$first', '${ok(n)}', 1000, 0, null)::text")!!
        assertEquals("OK", c.outcome(answer), answer)
        assertEquals("true", c.unico("select ('$answer'::jsonb ->> 'es_correcta')"))
        assertEquals("false", c.unico("select ('$answer'::jsonb ->> 'es_primera_respuesta')"), "a practice answer is never the first answer")
        assertEquals("0", c.unico("select count(*)::text from public.primeras_respuestas_usuario where usuario_id = '$user'"), "no stats")
        assertEquals("f", c.unico("select case when cuenta_para_ranking then 't' else 'f' end from public.respuestas_intento where intento_id = '$a'"))
        assertNull(c.unico("select examen_id::text from public.respuestas_intento where intento_id = '$a'"))
        assertEquals("OK", c.outcome(c.unico("select public.app_finish_exam_attempt('$user', '$a')::text")!!))
        assertEquals(
            listOf("finalizado", "1", "0", "3", "2.50"),
            c.filas("select estado, total_correctas::text, total_falladas::text, total_sin_responder::text, nota::text from public.intentos_examen where id = '$a'").single(),
            "1 of 4 right, no penalty: 2.50",
        )
        val coleccion = c.unico("select public.app_my_exams_collection('$user', null, null, null, null, null, 50, false, null)::text")!!
        assertFalse(a in coleccion, "never in Mis examenes")
        // a later REAL first answer to the question seen corrected here is stored but does not count (C-D4, same rule as the demo)
        val real = c.attemptOf(c.unico("select public.app_start_exam_attempt('$user', '$free', 'NORMAL')::text")!!)
        if (first in c.frozen(real)) {
            c.unico("select public.app_record_attempt_answer('$user', '$real', '$first', '${ok(n)}', 1000, 0, null)::text")
            assertEquals("f", c.unico("select case when cuenta_para_ranking then 't' else 'f' end from public.respuestas_intento where intento_id = '$real' and pregunta_id = '$first'"))
        }
    }

    @Test
    fun generatingAgainClosesTheOpenPracticeAndPracticeNeverUsesTheDemoQuota() = V1Db.escenario { c ->
        c.ejecutar(seed)
        val a1 = c.attemptOf(c.start())
        val a2 = c.attemptOf(c.start(n = 20))
        assertTrue(a1 != a2)
        assertEquals(listOf("abandonado", "en_progreso"), listOf(a1, a2).map { c.unico("select estado from public.intentos_examen where id = '$it'") })
        c.ejecutar("insert into public.configuracion_v1 (clave, valor_integer) values ('demo_inicios_usuario_dia', 1) on conflict (clave) do update set valor_integer = 1")
        val demo = c.unico("select public.app_start_exam_attempt('$user', '$locked', 'DEMO')::text")!!
        assertEquals("CREATED", c.outcome(demo), "two practice starts do not use the demo quota of 1: $demo")
        assertEquals("48", c.unico("select count(*)::text from generate_series(1, 48) g cross join lateral public.app_start_practice_attempt('$user', 5 + 0 * g) r where r ->> 'outcome' = 'CREATED'"))
        assertEquals("PRACTICE_LIMIT", c.outcome(c.start()), "the per-user practice cap: 50 starts in 24 h")
        assertEquals("1", c.unico("select count(*)::text from public.intentos_examen where usuario_id = '$user' and es_practica and estado = 'en_progreso'"), "only one open practice")
    }

    @Test
    fun theDailyLimiterCountsAPracticeUnlessEveryQuestionIsGranted() = V1Db.escenario { c ->
        c.ejecutar(seed)
        c.ejecutar(
            """
            insert into public.planes_suscripcion (id, codigo, nombre, nivel) values ('b1000000-0000-0000-0000-0000000023a1', 'gratuito-f23', 'Gratuito', 0);
            insert into public.beneficios_plan (plan_id, codigo_beneficio, valor_integer) values ('b1000000-0000-0000-0000-0000000023a1', 'limite_preguntas_24h', 2);
            """.trimIndent(),
        )
        val a = c.attemptOf(c.start())
        val qs = c.frozen(a)
        assertEquals("DAILY_LIMIT_REACHED", c.outcome(c.unico("select public.app_reveal_attempt_questions('$user', '$a', array['${qs.joinToString("','")}']::uuid[])::text")!!),
            "es_demo does not exempt a practice: 4 questions > limit 2")
        // only the granted exam left: every question GRANTED = exempt, like a purchased exam
        c.ejecutar("update public.preguntas_examen set anulada = true, motivo_anulacion = 'x' where examen_id = '$free'")
        val g = c.attemptOf(c.start())
        assertEquals(listOf(q(4)), c.frozen(g))
        assertEquals("GRANTED", c.unico("select acceso_estado from public.intentos_examen where id = '$g'"))
        assertEquals("OK", c.outcome(c.unico("select public.app_reveal_attempt_questions('$user', '$g', array['${q(4)}']::uuid[])::text")!!))
    }

    @Test
    fun withoutTheMigrationThereIsNoPractice() = V1Db.escenario(sin = setOf(migration)) { c ->
        c.ejecutar(seed)
        assertEquals("0", c.unico("select count(*)::text from pg_proc where proname in ('app_start_practice_attempt', 'app_practice_availability')"))
        assertEquals("0", c.unico("select count(*)::text from information_schema.columns where table_name = 'intentos_examen' and column_name = 'es_practica'"))
    }
}
