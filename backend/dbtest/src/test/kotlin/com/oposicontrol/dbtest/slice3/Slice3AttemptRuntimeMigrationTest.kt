package com.oposicontrol.dbtest.slice3

import com.oposicontrol.dbtest.PostgresHarness
import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.aplicar
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.slice0.sqlStateDe
import com.oposicontrol.dbtest.slice3.Slice3Db.EX
import com.oposicontrol.dbtest.slice3.Slice3Db.EX_DRAFT
import com.oposicontrol.dbtest.slice3.Slice3Db.EX_EMPTY
import com.oposicontrol.dbtest.slice3.Slice3Db.UA
import com.oposicontrol.dbtest.slice3.Slice3Db.UB
import com.oposicontrol.dbtest.slice3.Slice3Db.o
import com.oposicontrol.dbtest.slice3.Slice3Db.p
import com.oposicontrol.dbtest.unico
import java.sql.SQLException
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SLICE 3: migraciones 20261008100000 (esquema), 20261008100100 (RPC de comando) y 20261008100200 (endurecimiento, que
 * redefine las RPC: todo lo de aqui corre contra las RPC ENDURECIDAS) sobre la forma de MAIN. Lo propio del endurecimiento
 * (marcador, sin congelado tardio, origen, indices) esta en [Slice3AttemptHardeningMigrationTest].
 *
 * Cada garantia lleva su CONTROL NEGATIVO: el mismo escenario sin la pieza que la da tiene que fallar, para que un test
 * verde no pueda deberse a que el escenario no ejercita nada.
 */
class Slice3AttemptRuntimeMigrationTest {

    // ------------------------------------------------------------------ D2-03 start

    @Test
    fun concurrentStartsOfTheSameUserAndExamYieldExactlyOneOpenAttempt() = Slice3Db.escenario { c ->
        val hilos = 8
        val pool = Executors.newFixedThreadPool(hilos)
        val barrera = CyclicBarrier(hilos)
        try {
            val resultados = (1..hilos).map {
                pool.submit(
                    Callable {
                        PostgresHarness.conexionExtra(c).use { otra ->
                            barrera.await(20, TimeUnit.SECONDS)
                            otra.iniciar(UA, EX)
                        }
                    },
                )
            }.map { it.get(60, TimeUnit.SECONDS) }
            assertEquals(1, resultados.count { it.first == "CREATED" }, "exactamente un arranque crea el intento: $resultados")
            assertEquals(hilos - 1, resultados.count { it.first == "RESUMED" }, "el resto lo reanuda: $resultados")
            assertEquals(1, resultados.map { it.second }.toSet().size, "todos convergen en el MISMO intento")
        } finally {
            pool.shutdownNow()
        }
        assertEquals("1", c.unico("select count(*) from public.intentos_examen where usuario_id = '$UA' and examen_id = '$EX' and estado = 'en_progreso'"))
        assertEquals("4", c.unico("select count(*) from public.preguntas_intento"), "el conjunto se congela UNA vez")
    }

    /** Control negativo: sin el indice unico (la forma de MAIN) el arranque directo del Ktor antiguo SI duplica. */
    @Test
    fun withoutTheUniqueIndexTwoOpenAttemptsArePossibleAndWithItTheSecondDirectInsertIsRejected() {
        Slice3Db.escenario(migraciones = false) { c ->
            c.ejecutar("insert into public.intentos_examen (usuario_id, examen_id) values ('$UA', '$EX')")
            assertNull(c.sqlStateDe("insert into public.intentos_examen (usuario_id, examen_id) values ('$UA', '$EX')"), "MAIN permite el duplicado")
            assertEquals("2", c.unico("select count(*) from public.intentos_examen where estado = 'en_progreso'"))
        }
        Slice3Db.escenario { c ->
            c.ejecutar("insert into public.intentos_examen (usuario_id, examen_id) values ('$UA', '$EX')")
            assertEquals("23505", c.sqlStateDe("insert into public.intentos_examen (usuario_id, examen_id) values ('$UA', '$EX')"))
            assertNull(c.sqlStateDe("insert into public.intentos_examen (usuario_id, examen_id) values ('$UB', '$EX')"), "otro usuario no choca")
        }
    }

    @Test
    fun theSchemaMigrationStopsOnExistingDuplicateOpenAttemptsAndAppliesNothing() = Slice3Db.escenario(migraciones = false) { c ->
        repeat(2) { c.ejecutar("insert into public.intentos_examen (usuario_id, examen_id) values ('$UA', '$EX')") }
        val error = runCatching { c.aplicar(Slice3Db.SCHEMA) }.exceptionOrNull() as? SQLException
        assertNotNull(error, "la migracion debe detenerse")
        assertTrue(error.message.orEmpty().contains("SLICE3_OPEN_ATTEMPT_DUPLICATES_GATE"), error.message)
        c.ejecutar("rollback")
        assertEquals("0", c.unico("select count(*) from information_schema.columns where table_schema = 'public' and column_name in ('motivo_fin', 'estado_final', 'primera_opcion_id')"))
        assertEquals("2", c.unico("select count(*) from public.intentos_examen where estado = 'en_progreso'"), "ningun intento se cierra ni se borra")
        // El owner decide cual queda abierto; despues la migracion aplica.
        c.ejecutar("update public.intentos_examen set estado = 'abandonado' where id = (select id from public.intentos_examen order by creado_en, id limit 1)")
        Slice3Db.aplicarMigraciones(c)
        // 20261008100200: el intento abierto que queda lo creo el arranque antiguo (sin marcador): no se reanuda, se cierra y
        // se crea uno nuevo. Nunca hay mas de uno abierto.
        assertEquals("CREATED", c.iniciar(UA, EX).first)
        assertEquals("RESUMED", c.iniciar(UA, EX).first)
        assertEquals("1", c.unico("select count(*) from public.intentos_examen where estado = 'en_progreso'"))
        assertEquals("3", c.unico("select count(*) from public.intentos_examen"), "ningun intento se borra")
    }

    @Test
    fun startFreezesOnlyServableQuestionsAndSnapshotsTheExamSpecialty() = Slice3Db.escenario { c ->
        assertEquals("EXAM_NOT_FOUND", c.iniciar(UA, EX_DRAFT).first, "un borrador no se puede empezar")
        assertEquals("EXAM_NOT_FOUND", c.iniciar(UA, "99999999-9999-4999-8999-999999999999").first)
        assertEquals("EXAM_EMPTY", c.iniciar(UA, EX_EMPTY).first)
        assertEquals("0", c.unico("select count(*) from public.intentos_examen"), "un arranque rechazado no deja fila")

        val (outcome, intento) = c.iniciar(UA, EX)
        assertEquals("CREATED", outcome)
        assertEquals(
            listOf(listOf("1", p(1)), listOf("2", p(2)), listOf("3", p(3)), listOf("4", p(4))),
            c.filas("select posicion, pregunta_id from public.preguntas_intento where intento_id = '$intento' order by posicion"),
            "sin la archivada (5) ni la anulada (6), renumeradas 1..n",
        )
        assertEquals(
            listOf(listOf("4", Slice3Db.ESP1, "rafaga", "1", "t")),
            c.filas("select total_preguntas, especialidad_snapshot_id, tipo_examen_snapshot, intento_numero_usuario, es_primer_intento_examen from public.intentos_examen where id = '$intento'"),
        )
        // DEC-002: el examen cambia de especialidad DESPUES; el intento conserva la del arranque y no se puede reescribir.
        c.ejecutar("update public.examenes set especialidad_id = '${Slice3Db.ESP2}' where id = '$EX'")
        assertEquals(Slice3Db.ESP1, c.unico("select especialidad_snapshot_id from public.intentos_examen where id = '$intento'"))
        assertEquals("P0001", c.sqlStateDe("update public.intentos_examen set especialidad_snapshot_id = '${Slice3Db.ESP2}' where id = '$intento'"))
        // Una anulacion posterior al arranque no saca la pregunta del conjunto congelado.
        c.ejecutar("update public.preguntas_examen set anulada = true, motivo_anulacion = 'tribunal' where examen_id = '$EX' and pregunta_id = '${p(1)}'")
        assertEquals("4", c.unico("select count(*) from public.preguntas_intento where intento_id = '$intento'"))
    }

    // ------------------------------------------------------------------ D2-09 answers

    @Test
    fun anotherUsersAttemptIsIndistinguishableFromAMissingOneForEveryCommand() = Slice3Db.escenario { c ->
        val intento = c.iniciar(UA, EX).second!!
        assertEquals("ATTEMPT_NOT_FOUND", c.responder(UB, intento, p(1), o(1, 1), 0)["outcome"])
        assertEquals("ATTEMPT_NOT_FOUND", c.finalizar(UB, intento)["outcome"])
        assertEquals("ATTEMPT_NOT_FOUND", c.abandonar(UB, intento, 1)["outcome"])
        assertEquals("ATTEMPT_NOT_FOUND", c.responder(UB, "99999999-9999-4999-8999-999999999999", p(1), o(1, 1), 0)["outcome"])
        assertEquals("0", c.unico("select count(*) from public.respuestas_intento"))
        assertEquals("en_progreso", c.unico("select estado from public.intentos_examen where id = '$intento'"), "el intento ajeno queda intacto")
        // Control: el dueno si puede.
        assertEquals("OK", c.responder(UA, intento, p(1), o(1, 1), 0)["outcome"])
    }

    @Test
    fun answersAreValidatedAgainstTheFrozenSetAndFollowTheRevisionRules() = Slice3Db.escenario { c ->
        val intento = c.iniciar(UA, EX).second!!
        assertEquals("QUESTION_NOT_IN_ATTEMPT", c.responder(UA, intento, p(6), null, 0)["outcome"], "anulada antes de empezar: fuera del conjunto")
        assertEquals("QUESTION_NOT_IN_ATTEMPT", c.responder(UA, intento, p(5), o(5, 1), 0)["outcome"], "archivada: fuera del conjunto")
        assertEquals("OPTION_NOT_IN_QUESTION", c.responder(UA, intento, p(1), o(2, 1), 0)["outcome"])
        assertEquals(mapOf("outcome" to "REVISION_CONFLICT", "current_revision" to "0"), c.responder(UA, intento, p(1), o(1, 1), 7).filterValues { it != null })

        val primera = c.responder(UA, intento, p(1), o(1, 2), 0, ms = 2_000_000_000)
        assertEquals(listOf("OK", "ANSWERED", "1", "true", "1"), listOf("outcome", "estado", "revision", "es_primera_respuesta", "respondidas").map { primera[it] })
        assertEquals(
            listOf(listOf(o(1, 2), "f", o(1, 2), "f", "1", "t", "t", "1")),
            c.filas(
                "select opcion_seleccionada_id, es_correcta, primera_opcion_id, primera_es_correcta, revision, " +
                    "tiempo_respuesta_ms < 60000, (metadatos ->> 'tiempoRecortado')::boolean, posicion_pregunta " +
                    "from public.respuestas_intento where intento_id = '$intento' and pregunta_id = '${p(1)}'",
            ),
            "el tiempo del cliente se recorta al hueco del servidor",
        )
        assertEquals("true", c.responder(UA, intento, p(1), o(1, 2), 0)["noop"], "misma opcion = no-op")
        assertEquals("ANSWER_LOCKED", c.responder(UA, intento, p(1), o(1, 1), 1)["outcome"], "DEC-015 abierta: no se cambia la respuesta")
        assertEquals("ANSWER_LOCKED", c.responder(UA, intento, p(1), null, 1)["outcome"], "ni se borra")
        assertEquals(o(1, 2), c.unico("select opcion_seleccionada_id from public.respuestas_intento where pregunta_id = '${p(1)}'"))

        // Saltar no consume la primera respuesta del usuario; responder despues si, y la primera entrega (el salto) no cambia.
        val salto = c.responder(UA, intento, p(2), null, 0)
        assertEquals(listOf("SKIPPED", "1", "false", "1"), listOf("estado", "revision", "es_primera_respuesta", "respondidas").map { salto[it] })
        assertEquals("1", c.responder(UA, intento, p(2), o(2, 1), 0)["current_revision"], "responder un salto exige su revision")
        val tras = c.responder(UA, intento, p(2), o(2, 1), 1)
        assertEquals(listOf("ANSWERED", "2", "true", "2"), listOf("estado", "revision", "es_primera_respuesta", "respondidas").map { tras[it] })
        assertEquals(
            listOf(listOf(null, null, o(2, 1), "t", "t", "t")),
            c.filas("select primera_opcion_id, primera_es_correcta, opcion_seleccionada_id, es_correcta, cambio_respuesta, es_primer_intento_pregunta from public.respuestas_intento where pregunta_id = '${p(2)}'"),
        )
        assertEquals(listOf(listOf("2", "2")), c.filas("select total_respondidas, ultima_pregunta_posicion from public.intentos_examen where id = '$intento'"))
    }

    @Test
    fun twoConcurrentDifferentAnswersToTheSameQuestionStoreExactlyOne() = Slice3Db.escenario { c ->
        val intento = c.iniciar(UA, EX).second!!
        val pool = Executors.newFixedThreadPool(2)
        val barrera = CyclicBarrier(2)
        try {
            val resultados = listOf(o(1, 1), o(1, 2)).map { opcion ->
                pool.submit(
                    Callable {
                        PostgresHarness.conexionExtra(c).use { otra ->
                            barrera.await(20, TimeUnit.SECONDS)
                            otra.responder(UA, intento, p(1), opcion, 0)["outcome"]
                        }
                    },
                )
            }.map { it.get(60, TimeUnit.SECONDS) }
            assertEquals(listOf("ANSWER_LOCKED", "OK"), resultados.sortedBy { it }, "una gana, la otra ve la respuesta ya fijada")
        } finally {
            pool.shutdownNow()
        }
        assertEquals("1", c.unico("select count(*) from public.respuestas_intento where intento_id = '$intento'"))
    }

    // ------------------------------------------------------------------ D2-10 finish

    @Test
    fun anAnnulledQuestionIsNotEvaluableAndFinishIsIdempotentAndFrozen() = Slice3Db.escenario { c ->
        val intento = c.iniciar(UA, EX).second!!
        c.responder(UA, intento, p(1), o(1, 1), 0) // correcta
        c.responder(UA, intento, p(2), o(2, 2), 0) // fallada
        c.responder(UA, intento, p(3), o(3, 1), 0) // correcta... y despues anulada por el tribunal
        c.ejecutar("update public.preguntas_examen set anulada = true, motivo_anulacion = 'tribunal' where examen_id = '$EX' and pregunta_id = '${p(3)}'")
        val anulada = c.responder(UA, intento, p(3), o(3, 1), 1)
        assertEquals("ANNULLED", anulada["estado"], "responder una anulada no guarda ni puntua")
        assertEquals("2", anulada["respondidas"], "y deja de contar como respondida")

        assertEquals(mapOf<String, String?>("outcome" to "OK", "already" to "false"), c.finalizar(UA, intento))
        assertEquals(
            listOf(listOf("CORRECT"), listOf("WRONG"), listOf("ANNULLED"), listOf("UNANSWERED")),
            c.filas("select estado_final from public.preguntas_intento where intento_id = '$intento' order by posicion"),
        )
        val totales = "select estado, motivo_fin, total_preguntas, total_respondidas, total_correctas, total_falladas, total_sin_responder, " +
            "porcentaje_acierto, finalizado_en, actualizado_en from public.intentos_examen where id = '$intento'"
        val cierre = c.filas(totales).single()
        // 3 evaluables (la anulada no esta en NINGUN total): 1 correcta, 1 fallada, 1 sin responder.
        assertEquals(listOf("finalizado", "USER", "4", "2", "1", "1", "1", "50.00"), cierre.take(8))
        assertEquals(listOf(listOf("3", "1", "1", "1")), c.filas("select total_preguntas, correctas, falladas, sin_responder from public.resumen_intento_etiqueta where intento_id = '$intento'"))

        // Idempotente: repetir no recalcula nada, aunque la anulacion y la solucion cambien despues.
        c.ejecutar("update public.preguntas_examen set anulada = false, motivo_anulacion = null where examen_id = '$EX' and pregunta_id = '${p(3)}'")
        assertEquals(mapOf<String, String?>("outcome" to "OK", "already" to "true"), c.finalizar(UA, intento))
        assertEquals(cierre, c.filas(totales).single(), "la segunda finalizacion no toca la fila (ni actualizado_en)")
        assertEquals("ANNULLED", c.unico("select estado_final from public.preguntas_intento where intento_id = '$intento' and posicion = 3"))

        // Estados terminales: ni respuesta ni abandono despues de finalizar.
        assertEquals(mapOf("outcome" to "ATTEMPT_NOT_IN_PROGRESS", "estado" to "finalizado"), c.responder(UA, intento, p(4), o(4, 1), 0).filterValues { it != null })
        assertEquals("0", c.unico("select count(*) from public.respuestas_intento where intento_id = '$intento' and pregunta_id = '${p(4)}'"))
        assertEquals("ATTEMPT_ALREADY_FINISHED", c.abandonar(UA, intento, 1)["outcome"])
        assertEquals("0", c.unico("select count(*) from public.abandonos_examen"))

        // Repetir el examen: intento NUEVO, y sus respuestas ya no son la primera respuesta del usuario (PDF 2.1-05).
        val (outcome, segundo) = c.iniciar(UA, EX)
        assertEquals("CREATED", outcome)
        assertNotEquals(intento, segundo)
        assertEquals(listOf(listOf("2", "f")), c.filas("select intento_numero_usuario, es_primer_intento_examen from public.intentos_examen where id = '$segundo'"))
        assertEquals("false", c.responder(UA, segundo!!, p(1), o(1, 1), 0)["es_primera_respuesta"])
    }

    /** Control negativo de "la anulada no es evaluable": el MISMO intento sin la anulacion cuenta las 4 preguntas. */
    @Test
    fun withoutTheAnnulmentTheSameAnswersCountEveryQuestion() = Slice3Db.escenario { c ->
        val intento = c.iniciar(UA, EX).second!!
        c.responder(UA, intento, p(1), o(1, 1), 0)
        c.responder(UA, intento, p(2), o(2, 2), 0)
        c.responder(UA, intento, p(3), o(3, 1), 0)
        c.finalizar(UA, intento)
        assertEquals(
            listOf(listOf("3", "2", "1", "1")),
            c.filas("select total_respondidas, total_correctas, total_falladas, total_sin_responder from public.intentos_examen where id = '$intento'"),
        )
    }

    /**
     * GATE SLICE 3 (F-01): este test afirmaba lo contrario ("el intento legacy se congela tarde y se puede responder y
     * finalizar"), que ERA el hueco: el arranque antiguo no pasa por EntitlementPolicy. Con 20261008100200 ese intento no
     * existe para ningun comando y nunca se congela. El comportamiento anterior queda como control negativo en
     * [Slice3AttemptHardeningMigrationTest].
     */
    @Test
    fun anAttemptOpenedByTheLegacyStartIsNeverFrozenAnsweredOrFinished() = Slice3Db.escenario { c ->
        // El `POST /examenes/{id}/iniciar` antiguo inserta la fila sin congelar preguntas.
        c.ejecutar("insert into public.intentos_examen (id, usuario_id, examen_id, total_preguntas) values ('1e9ac100-0000-4000-8000-000000000001', '$UA', '$EX', 4)")
        val intento = "1e9ac100-0000-4000-8000-000000000001"
        assertEquals("ATTEMPT_NOT_FOUND", c.responder(UA, intento, p(1), o(1, 1), 0)["outcome"])
        assertEquals("ATTEMPT_NOT_FOUND", c.finalizar(UA, intento)["outcome"])
        assertEquals("0", c.unico("select count(*) from public.preguntas_intento where intento_id = '$intento'"), "sin congelado tardio")
        assertEquals("0", c.unico("select count(*) from public.respuestas_intento where intento_id = '$intento'"))
        assertEquals("en_progreso", c.unico("select estado from public.intentos_examen where id = '$intento'"), "un comando rechazado no lo toca")
    }

    // ------------------------------------------------------------------ D2-11 abandon

    @Test
    fun abandonWritesOneRowComputesElapsedOnTheServerAndIsIdempotent() = Slice3Db.escenario { c ->
        val intento = c.iniciar(UA, EX).second!!
        c.ejecutar("update public.intentos_examen set iniciado_en = now() - interval '90 seconds' where id = '$intento'")
        assertEquals("INVALID_POSITION", c.abandonar(UA, intento, 5)["outcome"])
        assertEquals("INVALID_POSITION", c.abandonar(UA, intento, 0)["outcome"])
        assertEquals("INVALID_REASON", c.abandonar(UA, intento, 2, "inactividad")["outcome"], "motivo reservado al servidor (DEC-014)")
        assertEquals("0", c.unico("select count(*) from public.abandonos_examen"), "un abandono rechazado no escribe nada")

        assertEquals(mapOf<String, String?>("outcome" to "OK", "already" to "false"), c.abandonar(UA, intento, 2))
        assertEquals(mapOf<String, String?>("outcome" to "OK", "already" to "true"), c.abandonar(UA, intento, 3, "otro"))
        assertEquals(
            listOf(listOf(UA, EX, p(2), "2", "t", "4", "cerrar_examen", "salida_usuario")),
            c.filas(
                "select usuario_id, examen_id, pregunta_id, posicion_pregunta, tiempo_transcurrido_segundos between 90 and 600, " +
                    "total_preguntas, motivo_detectado, metadatos ->> 'motivo' from public.abandonos_examen where intento_id = '$intento'",
            ),
            "una sola fila, la del primer abandono",
        )
        assertEquals(listOf(listOf("abandonado", "2", p(2))), c.filas("select estado, abandonado_en_posicion, abandonado_en_pregunta_id from public.intentos_examen where id = '$intento'"))
        assertEquals("ATTEMPT_ABANDONED", c.finalizar(UA, intento)["outcome"])
        assertEquals("ATTEMPT_NOT_IN_PROGRESS", c.responder(UA, intento, p(1), o(1, 1), 0)["outcome"])
        assertEquals("CREATED", c.iniciar(UA, EX).first, "un intento abandonado no bloquea uno nuevo")
    }

    // ------------------------------------------------------------------ grants, VERIFY, rollback

    @Test
    fun theCommandsAreReachableOnlyThroughServiceRole() = Slice3Db.escenario { c ->
        val llamadas = listOf(
            "select public.app_start_exam_attempt('$UA', '$EX')",
            "select public.app_record_attempt_answer('$UA', gen_random_uuid(), gen_random_uuid(), null, 0, 0, null)",
            "select public.app_finish_exam_attempt('$UA', gen_random_uuid())",
            "select public.app_abandon_exam_attempt('$UA', gen_random_uuid(), 1, 'otro')",
        )
        for (rol in listOf("anon", "authenticated")) {
            c.ejecutar("set role $rol")
            try {
                llamadas.forEach { assertEquals("42501", c.sqlStateDe(it), "$rol no puede ejecutar: $it") }
                assertEquals("42501", c.sqlStateDe("select private.app_congelar_preguntas_intento(gen_random_uuid(), gen_random_uuid())"))
                assertEquals("42501", c.sqlStateDe("insert into public.preguntas_intento (intento_id, pregunta_id, posicion) values (gen_random_uuid(), '${p(1)}', 1)"))
            } finally {
                c.ejecutar("reset role")
            }
        }
        assertEquals("0", c.unico("select count(*) from public.intentos_examen"))
        c.ejecutar("set role service_role")
        try {
            llamadas.forEach { assertNull(c.sqlStateDe(it), "service_role debe poder ejecutar: $it") }
        } finally {
            c.ejecutar("reset role")
        }
        assertEquals("1", c.unico("select count(*) from public.intentos_examen"))
        assertEquals(
            "0",
            c.unico(
                "select count(*) from pg_proc p where p.proname like 'app\\_%\\_attempt%' and p.pronamespace = 'public'::regnamespace " +
                    "and not (p.prosecdef and p.proconfig @> array['search_path=\"\"'])",
            ),
            "SECURITY DEFINER con search_path fijado",
        )
    }

    @Test
    fun verifyIsZeroAndTheRollbackRestoresTheMainShapeAndTheMigrationsReapply() = Slice3Db.escenario(migraciones = false) { c ->
        val forma = "select string_agg(table_name || '.' || column_name, ',' order by table_name, column_name) from information_schema.columns " +
            "where table_schema = 'public' and table_name in ('intentos_examen', 'respuestas_intento', 'preguntas_intento')"
        val antes = c.unico(forma)
        Slice3Db.aplicarMigraciones(c)
        Slice3Db.aplicarMigraciones(c) // idempotentes
        for (migracion in listOf(Slice3Db.SCHEMA, Slice3Db.RPCS, Slice3Db.HARDENING)) {
            val filas = c.filas(Slice0Db.verifyDe(migracion))
            assertTrue(filas.isNotEmpty(), "$migracion: VERIFY sin filas")
            assertEquals(emptyList(), filas.filter { it[1] != "0" }, "$migracion: VERIFY con contadores distintos de 0")
        }
        assertNotEquals(antes, c.unico(forma))
        val intento = c.iniciar(UA, EX).second!!
        c.responder(UA, intento, p(1), o(1, 1), 0)

        c.ejecutar(Slice0Db.rollbackDe(Slice3Db.HARDENING))
        c.ejecutar(Slice0Db.rollbackDe(Slice3Db.RPCS))
        c.ejecutar(Slice0Db.rollbackDe(Slice3Db.SCHEMA))
        assertEquals(antes, c.unico(forma), "el rollback deja las columnas de MAIN")
        assertEquals("0", c.unico("select count(*) from pg_proc where proname in ('app_start_exam_attempt', 'app_record_attempt_answer', 'app_finish_exam_attempt', 'app_abandon_exam_attempt', 'app_congelar_preguntas_intento', 'tg_intentos_examen_especialidad_snapshot_inmutable', 'tg_intentos_examen_creado_por_comando_inmutable')"))
        assertNull(c.unico("select to_regclass('public.ux_intentos_examen_abierto')::text"))
        assertNull(c.unico("select to_regclass('public.idx_respuestas_intento_primera_opcion_id')::text"))
        assertNull(c.unico("select to_regclass('public.idx_intentos_examen_especialidad_snapshot_id')::text"))
        assertEquals("1", c.unico("select count(*) from public.respuestas_intento where intento_id = '$intento'"), "el rollback no borra respuestas ni intentos")

        Slice3Db.aplicarMigraciones(c)
        // El rollback de 20261008100200 quita la columna del marcador (lo dice su cabecera): el intento abierto vuelve a ser
        // "uno que el comando no creo", asi que el siguiente arranque lo cierra y crea otro. Sus datos siguen ahi.
        assertEquals("CREATED", c.iniciar(UA, EX).first)
        assertEquals("abandonado", c.unico("select estado from public.intentos_examen where id = '$intento'"))
        assertEquals("1", c.unico("select count(*) from public.respuestas_intento where intento_id = '$intento'"))
    }
}
