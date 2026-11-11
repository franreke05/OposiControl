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
 * `20261108100300_v1_auto_demo` (F14, propietario 2026-11-08): todo examen publicado ofrece su demo a quien no tiene acceso sin que Control marque nada.
 * Conjunto efectivo = el explicito si esta completo (gana siempre); si no, los N primeros principales (no reserva) por `posicion`, no anulados y con la pregunta
 * activa; menos de N = sin demo. `demo_disponible` por defecto true (exclusion explicita). Lo demas del modo demo no cambia (no cuenta, limites, con acceso = normal).
 * Esquema V1 completo; control negativo sin la migracion.
 */
class V1AutoDemoMigrationTest {
    private val migration = "20261108100300_v1_auto_demo"

    /** F14b (20261111100000) replaces the automatic set (first N by posicion) with a random fixed one: these tests pin the F14 semantics without it. */
    private val F14B = "20261111100000_v1_demo_aleatoria"
    private val user = "a0000000-0000-0000-0000-0000000014a1"
    private val other = "a0000000-0000-0000-0000-0000000014a2"
    private val esp = "e1000000-0000-0000-0000-0000000014a1"
    private val com = "c1000000-0000-0000-0000-0000000014a1"
    private val big = "e14a0000-0000-0000-0000-000000000001"
    private val small = "e14a0000-0000-0000-0000-000000000002"

    /** Pregunta n (1..10) del examen grande; 11..14 son las del pequeno. */
    private fun q(n: Int) = "f14a0000-0000-0000-0000-0000000000" + n.toString().padStart(2, '0')
    private fun ok(n: Int) = "0c14a000-0000-0000-0000-0000000000" + n.toString().padStart(2, '0')

    /**
     * Examen grande (premium, publicado): n = posicion 1..10; 2 = anulada, 3 = pregunta inactiva, 10 = reserva (las reservas van detras); principales validas 1, 4, 5, 6, 7, 8, 9.
     * Examen pequeno: 4 principales validas + 1 reserva (menos de N = 5 principales: la reserva nunca completa la demo). Las filas entran con los disparadores apagados y SIN nombrar `demo_disponible` (vale el defecto).
     */
    private fun seed(demoColumn: String = "", demoValue: String = "") = """
        insert into auth.users (id, email, aud, role) values ('$user', 'ad-u@test.local', 'authenticated', 'authenticated'), ('$other', 'ad-o@test.local', 'authenticated', 'authenticated');
        insert into public.especialidades (id, codigo, nombre, slug, activa) values ('$esp', 'F14', 'F14', 'f14', true);
        insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('$com', 'F14', 'Com F14', 'com-f14');
        insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id)
          values ('$user', 'ad', 'ad_f14_user', true, 'activa', '$esp'), ('$other', 'ao', 'ad_f14_other', true, 'activa', '$esp')
          on conflict (usuario_id) do update set especialidad_principal_id = excluded.especialidad_principal_id;
        begin;
        set local session_replication_role = replica;
        insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en$demoColumn)
          values ('$big', 'F14-1', '$esp', '$com', 'Grande', 'rafaga', 'premium', true, now() - interval '1 day'$demoValue),
                 ('$small', 'F14-2', '$esp', '$com', 'Pequeno', 'rafaga', 'premium', true, now() - interval '1 day'$demoValue);
        insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa)
          select ('f14a0000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, 'F14-' || n, 'rafaga', 'Pregunta ' || n, n <> 3 from generate_series(1, 15) n;
        insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion)
          select ('0c14a000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, ('f14a0000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, 'A', 'Si', 1 from generate_series(1, 15) n
          union all
          select ('0c14b000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, ('f14a0000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, 'B', 'No', 2 from generate_series(1, 15) n;
        insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento)
          select ('f14a0000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, ('0c14a000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, 'x' from generate_series(1, 15) n;
        insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen, anulada, motivo_anulacion, es_reserva, orden_reserva)
          select '$big'::uuid, ('f14a0000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, n, n, n = 2, case when n = 2 then 'anulada' end, n = 10, case when n = 10 then 1 end
          from generate_series(1, 10) n
          union all
          select '$small'::uuid, ('f14a0000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, n - 10, n - 10, false, null, n = 15, case when n = 15 then 1 end from generate_series(11, 15) n;
        commit;
    """.trimIndent()

    private fun Connection.startDemo(u: String = user, exam: String = big): String = unico("select public.app_start_exam_attempt('$u', '$exam', 'DEMO')::text")!!
    private fun Connection.outcome(json: String) = unico("select ('$json'::jsonb) ->> 'outcome'")
    private fun Connection.attemptOf(json: String) = unico("select ('$json'::jsonb) ->> 'intento_id'")!!
    private fun Connection.frozen(attempt: String): List<String> =
        filas("select pregunta_id::text from public.preguntas_intento where intento_id = '$attempt' order by posicion").map { it[0]!! }
    private fun Connection.demoListo(exam: String) = unico("select public.demo_listo(e)::text from public.examenes e where e.id = '$exam'")

    @Test
    fun theAutomaticSetIsTheFirstFiveServableMainQuestionsAndNothingBeyondItIsReachable() = V1Db.escenario(sin = setOf(F14B)) { c ->
        c.ejecutar(seed())
        assertEquals("true", c.unico("select demo_disponible::text from public.examenes where id = '$big'"), "new exams default ON")
        assertEquals("true", c.demoListo(big))
        val r = c.startDemo()
        assertEquals("CREATED", c.outcome(r), r)
        assertEquals("true", c.unico("select ('$r'::jsonb ->> 'es_demo')"))
        val a = c.attemptOf(r)
        assertEquals(listOf(q(1), q(4), q(5), q(6), q(7)), c.frozen(a), "annulled (2) and inactive (3) skipped; the next ones enter; the reserve (10) never")
        assertEquals(
            "true|DEMO|false|false|0|5",
            c.filas("select es_demo::text, acceso_estado, cuenta_para_ranking::text, es_primer_intento_examen::text, intento_numero_usuario::text, total_preguntas::text from public.intentos_examen where id = '$a'")
                .single().joinToString("|"),
            "a demo never counts",
        )
        assertEquals("QUESTION_NOT_IN_ATTEMPT", c.unico("select public.app_record_attempt_answer('$user', '$a', '${q(8)}', '${ok(8)}', 0, 0, null) ->> 'outcome'"), "a sixth question is unreachable")
        assertEquals("QUESTION_NOT_IN_ATTEMPT", c.unico("select public.app_record_attempt_answer('$user', '$a', '${q(10)}', '${ok(10)}', 0, 0, null) ->> 'outcome'"), "the reserve is unreachable")
        val ans = c.unico("select public.app_record_attempt_answer('$user', '$a', '${q(1)}', '${ok(1)}', 0, 0, null)::text")!!
        assertEquals("OK|false", c.unico("select ('$ans'::jsonb ->> 'outcome') || '|' || ('$ans'::jsonb ->> 'es_primera_respuesta')"), "a demo answer is never the first answer")
        assertEquals("RESUMED", c.outcome(c.startDemo()), "a second DEMO start resumes the same demo")
    }

    @Test
    fun aCompleteExplicitSetWinsAndAPartialOneFallsBackToTheAutomaticSet() = V1Db.escenario(sin = setOf(F14B)) { c ->
        c.ejecutar(seed())
        // partial explicit (3 of 5): the automatic set applies
        c.ejecutar("update public.preguntas_examen set es_demo = true where examen_id = '$big' and posicion in (7, 8, 9)")
        val partial = c.attemptOf(c.startDemo())
        assertEquals(listOf(q(1), q(4), q(5), q(6), q(7)), c.frozen(partial))
        c.ejecutar("select public.app_abandon_exam_attempt('$user', '$partial', 1, 'salida_usuario')")
        // complete explicit (5): exactly those
        c.ejecutar("update public.preguntas_examen set es_demo = true where examen_id = '$big' and posicion in (5, 6)")
        val explicit = c.attemptOf(c.startDemo())
        assertEquals(listOf(q(5), q(6), q(7), q(8), q(9)), c.frozen(explicit), "Control's set wins")
        // DEMO_SET_FULL still guards the explicit set
        val err = runCatching { c.ejecutar("update public.preguntas_examen set es_demo = true where examen_id = '$big' and posicion = 4") }.exceptionOrNull()
        assertTrue(err?.message?.contains("DEMO_SET_FULL") == true, "a sixth flag is refused: $err")
    }

    @Test
    fun fewerThanNServableQuestionsMeansNoDemoAndAnOptOutSwitchesItOff() = V1Db.escenario(sin = setOf(F14B)) { c ->
        c.ejecutar(seed())
        assertEquals("false", c.demoListo(small))
        assertEquals("EXAM_LOCKED", c.outcome(c.startDemo(exam = small)), "4 main questions + 1 reserve < 5: no demo (fail closed)")
        // a question that stops being servable (deactivated) inside the first five pulls the next one in; with no spare main question left, the demo goes away
        c.ejecutar("update public.preguntas set activa = false where id in ('${q(8)}', '${q(9)}')")
        assertEquals("true", c.demoListo(big), "1, 4, 5, 6, 7 still servable")
        c.ejecutar("update public.preguntas set activa = false where id = '${q(7)}'")
        assertEquals("false", c.demoListo(big), "only 4 servable main questions left (the reserve never fills the demo)")
        assertEquals("EXAM_LOCKED", c.outcome(c.startDemo()))
        c.ejecutar("update public.preguntas set activa = true where id in ('${q(7)}', '${q(8)}', '${q(9)}')")
        // explicit opt-out by Control
        c.ejecutar("update public.examenes set demo_disponible = false where id = '$big'")
        assertEquals("false", c.demoListo(big))
        assertEquals("EXAM_LOCKED", c.outcome(c.startDemo()), "opted out")
        // switching it on again is always allowed (no DEMO_SET_INCOMPLETE any more)
        c.ejecutar("update public.examenes set demo_disponible = true where id = '$big'")
        assertEquals("CREATED", c.outcome(c.startDemo()))
    }

    @Test
    fun aUserWithAccessGetsANormalAttemptAndTheDemoDoesNotShiftTheNumbering() = V1Db.escenario(sin = setOf(F14B)) { c ->
        c.ejecutar(seed())
        val demo = c.attemptOf(c.startDemo())
        c.ejecutar(
            "insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta) values ('$user', '$big', 'admin', now() - interval '1 hour', now() + interval '5 days')",
        )
        val r = c.startDemo()
        assertEquals("CREATED|false", c.unico("select ('$r'::jsonb ->> 'outcome') || '|' || ('$r'::jsonb ->> 'es_demo')"), r)
        val real = c.attemptOf(r)
        assertEquals("abandonado", c.unico("select estado from public.intentos_examen where id = '$demo'"), "the open demo is closed, never resumed")
        assertEquals("1|true|false|GRANTED", c.filas("select intento_numero_usuario::text, es_primer_intento_examen::text, es_demo::text, acceso_estado from public.intentos_examen where id = '$real'").single().joinToString("|"))
        assertEquals(9, c.frozen(real).size, "the real attempt holds every active question (reserve included, its role decides)")
        // the per-user demo start limit still applies
        c.ejecutar("update public.configuracion_v1 set valor_integer = 1 where clave = 'demo_inicios_usuario_dia'")
        c.attemptOf(c.startDemo(u = other))
        val o = c.unico("select id::text from public.intentos_examen where usuario_id = '$other' and es_demo")!!
        c.ejecutar("select public.app_abandon_exam_attempt('$other', '$o', 1, 'salida_usuario')")
        assertEquals("DEMO_LIMIT", c.outcome(c.startDemo(u = other)))
    }

    @Test
    fun theBackfillSwitchesExistingExamsOnAndTheVerifyAndRollbackBehave() = V1Db.escenario(sin = setOf(migration, F14B)) { c ->
        c.ejecutar(seed(", demo_disponible", ", false"))
        assertEquals("EXAM_LOCKED", c.outcome(c.startDemo()), "negative control: without the migration an unmarked exam offers no demo")
        c.ejecutar(Slice0Db.leer("supabase/migrations/$migration.sql"))
        assertEquals("true|true", c.unico("select string_agg(demo_disponible::text, '|' order by id) from public.examenes where id in ('$big', '$small')"), "existing exams switched on")
        assertEquals("CREATED", c.outcome(c.startDemo()))
        val verify = Slice0Db.verifyDe("supabase/migrations/$migration.sql")
        val rows = c.filas(verify)
        assertEquals(6, rows.size, "VERIFY rows: $rows")
        assertEquals(emptyList(), rows.filter { it[1] != "0" }, "every VERIFY counter is 0")
        c.ejecutar(Slice0Db.rollbackDe("supabase/migrations/$migration.sql"))
        assertEquals("0", c.unico("select count(*) from pg_proc where proname in ('demo_listo', 'demo_preguntas_efectivas')"))
        assertEquals("false", c.unico("select column_default from information_schema.columns where table_schema = 'public' and table_name = 'examenes' and column_name = 'demo_disponible'"))
        assertTrue(c.filas(verify).any { it[1] != "0" }, "VERIFY fails without the migration")
    }
}
