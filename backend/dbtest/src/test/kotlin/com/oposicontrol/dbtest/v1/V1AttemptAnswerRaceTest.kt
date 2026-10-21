package com.oposicontrol.dbtest.v1

import com.oposicontrol.dbtest.PostgresHarness
import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * CERT-EXAMS-2026-10-21 (Answer, D2-09): carrera answer || answer sobre la funcion V1 ACTUAL `public.app_record_attempt_answer`
 * (cuerpo de 20261022101600 sobre todas las pendientes), con DOS conexiones reales. Hasta ahora esta carrera solo se ejecutaba
 * contra el cuerpo de Slice 3.
 *
 * Sin tiempos: la primera conexion responde y deja su transaccion ABIERTA; la segunda responde la MISMA pregunta del MISMO
 * intento en otro hilo, y el test espera a verla bloqueada en `pg_stat_activity` (`wait_event_type = 'Lock'`) antes de
 * confirmar la primera. Asi se prueba la serializacion (cerrojo consultivo por usuario+pregunta + `FOR UPDATE` del intento),
 * no la suerte del planificador.
 */
class V1AttemptAnswerRaceTest {

    private val user = "a0000000-0000-0000-0000-0000000005a1"
    private val esp = "e1000000-0000-0000-0000-0000000005a1"
    private val com = "c1000000-0000-0000-0000-0000000005a1"
    private val exam = "e5a00000-0000-0000-0000-000000000001"
    private fun q(n: Int) = "f5a00000-0000-0000-0000-00000000000$n"
    private fun opt(n: Int, letra: Int) = "0a5a0000-0000-0000-000$n-00000000000$letra"

    private val seed = """
        insert into auth.users (id, email, aud, role) values ('$user', 'race-v1@test.local', 'authenticated', 'authenticated');
        insert into public.especialidades (id, codigo, nombre, slug, activa) values ('$esp', 'V1R', 'V1R', 'v1r', true);
        insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('$com', 'V1R', 'Com V1R', 'com-v1r');
        insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id)
          values ('$user', 'race', 'race_v1_user', true, 'activa', '$esp')
          on conflict (usuario_id) do update set especialidad_principal_id = excluded.especialidad_principal_id;
        begin;
        set local session_replication_role = replica;
        insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en)
          values ('$exam', 'V1-RACE', '$esp', '$com', 'V1 race', 'rafaga', 'gratuito', true, now() - interval '1 day');
        insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa)
          select ('f5a00000-0000-0000-0000-00000000000' || n)::uuid, 'V1R-' || n, 'rafaga', 'Pregunta ' || n, true from generate_series(1, 3) n;
        insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion)
          select ('0a5a0000-0000-0000-000' || n || '-00000000000' || o)::uuid, ('f5a00000-0000-0000-0000-00000000000' || n)::uuid, chr(64 + o), 'Opcion', o
          from generate_series(1, 3) n, generate_series(1, 2) o;
        insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento)
          select ('f5a00000-0000-0000-0000-00000000000' || n)::uuid, ('0a5a0000-0000-0000-000' || n || '-000000000001')::uuid, 'x' from generate_series(1, 3) n;
        insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen)
          select '$exam', ('f5a00000-0000-0000-0000-00000000000' || n)::uuid, n, n from generate_series(1, 3) n;
        commit;
    """.trimIndent()

    private fun answer(c: Connection, attempt: String, question: String, option: String): String =
        c.unico("select public.app_record_attempt_answer('$user', '$attempt', '$question', '$option', 1000, 0, null) ->> 'outcome'")!!

    private fun start(c: Connection): String {
        // a fresh attempt every round: the open one (if any) is handed in through the real command first
        c.ejecutar("select public.app_finish_exam_attempt('$user', id) from public.intentos_examen where usuario_id = '$user' and estado = 'en_progreso'")
        return c.unico("select public.app_start_exam_attempt('$user', '$exam') ->> 'intento_id'")!!
    }

    /** Espera (sin dormir a ciegas) a que [pid] este bloqueado esperando un cerrojo. */
    private fun awaitBlocked(c: Connection, pid: String, pending: Future<*>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (c.unico("select count(*) from pg_stat_activity where pid = $pid and wait_event_type = 'Lock'") == "1") return
            check(!pending.isDone) { "the second answer did not wait for the first transaction" }
            Thread.onSpinWait()
        }
        error("the second connection never blocked on the first answer")
    }

    /** c1 responde [first] y queda abierta; c2 responde [second] a la vez; devuelve los dos outcomes. */
    private fun race(c: Connection, attempt: String, question: String, first: String, second: String): Pair<String, String> {
        val pool = Executors.newSingleThreadExecutor()
        PostgresHarness.conexionExtra(c).use { c1 ->
            PostgresHarness.conexionExtra(c).use { c2 ->
                try {
                    c1.autoCommit = false
                    val pid2 = c2.unico("select pg_backend_pid()")!!
                    val out1 = answer(c1, attempt, question, first)
                    val pending = pool.submit<String> { answer(c2, attempt, question, second) }
                    awaitBlocked(c, pid2, pending)
                    assertFalse(pending.isDone, "the second answer must wait for the open first one")
                    c1.commit()
                    return out1 to pending.get(10, TimeUnit.SECONDS)
                } finally {
                    pool.shutdownNow()
                }
            }
        }
    }

    /** El estado final: una sola fila de respuesta para la pregunta, con la opcion de la PRIMERA conexion, y el contador del intento. */
    private fun stored(c: Connection, attempt: String, question: String): String =
        c.filas(
            "select count(*)::text, max(opcion_seleccionada_id::text), max(revision)::text, " +
                "(select total_respondidas::text from public.intentos_examen where id = '$attempt'), " +
                "(select estado from public.intentos_examen where id = '$attempt') " +
                "from public.respuestas_intento where intento_id = '$attempt' and pregunta_id = '$question'",
        ).single().joinToString("|")

    @Test
    fun twoConnectionsAnsweringTheSameQuestionDifferentlyStoreExactlyTheFirstOne() = V1Db.escenario { c ->
        c.ejecutar(seed)
        repeat(2) { round ->
            val attempt = start(c)
            val (o1, o2) = race(c, attempt, q(1), opt(1, 1), opt(1, 2))
            assertEquals("OK", o1, "round $round")
            assertEquals("ANSWER_LOCKED", o2, "round $round: the second, different answer is refused once the first is committed")
            assertEquals("1|${opt(1, 1)}|1|1|en_progreso", stored(c, attempt, q(1)), "round $round")
            // the reverse direction: whoever commits first wins, never a merge or a second row
            val (r1, r2) = race(c, attempt, q(2), opt(2, 2), opt(2, 1))
            assertEquals("OK" to "ANSWER_LOCKED", r1 to r2, "round $round reverse")
            assertEquals("1|${opt(2, 2)}|1|2|en_progreso", stored(c, attempt, q(2)), "round $round reverse")
        }
    }

    @Test
    fun twoConnectionsSendingTheSameAnswerAtOnceStoreOneRowAndTheSecondIsANoOpReplay() = V1Db.escenario { c ->
        c.ejecutar(seed)
        repeat(2) { round ->
            val attempt = start(c)
            val (o1, o2) = race(c, attempt, q(3), opt(3, 1), opt(3, 1))
            assertEquals("OK" to "OK", o1 to o2, "round $round")
            assertEquals("1|${opt(3, 1)}|1|1|en_progreso", stored(c, attempt, q(3)), "round $round: one row, revision 1, counted once")
            assertEquals(
                "true",
                c.unico("select (public.app_record_attempt_answer('$user', '$attempt', '${q(3)}', '${opt(3, 1)}', 1000, 0, null) ->> 'noop')"),
                "a later identical retry is a no-op",
            )
            // and the attempt still finishes with exactly that answer counted
            assertEquals("false", c.unico("select public.app_finish_exam_attempt('$user', '$attempt') ->> 'already'"))
            assertEquals("finalizado|1|1", c.filas("select estado, total_respondidas::text, total_correctas::text from public.intentos_examen where id = '$attempt'").single().joinToString("|"))
        }
    }
}
