package com.oposicontrol.dbtest.slice3

import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.aplicar
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.slice0.sqlStateDe
import com.oposicontrol.dbtest.slice3.Slice3Db.EX
import com.oposicontrol.dbtest.slice3.Slice3Db.EX_EMPTY
import com.oposicontrol.dbtest.slice3.Slice3Db.UA
import com.oposicontrol.dbtest.slice3.Slice3Db.UB
import com.oposicontrol.dbtest.slice3.Slice3Db.o
import com.oposicontrol.dbtest.slice3.Slice3Db.p
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GATE SLICE 3: migracion 20261008100200 (endurecimiento) sobre la forma de MAIN + 20261008100000 + 20261008100100.
 *
 * F-01: un intento que `app_start_exam_attempt` no creo (lo abria el `iniciar` legacy, sin EntitlementPolicy) no existe
 * para ningun comando y nunca se congela; F-06: los comandos exigen `origen_intento = 'examen'`; F-05: las dos FK nuevas
 * de 20261008100000 tienen indice. Cada garantia lleva su CONTROL NEGATIVO: el mismo escenario con las RPC tal como se
 * publicaron (`endurecimiento = false`) o sin la pieza concreta hace exactamente lo que el hallazgo describe.
 */
class Slice3AttemptHardeningMigrationTest {

    private val legacy = "1e9ac100-0000-4000-8000-000000000001"

    /** Lo que hacia `POST /api/v1/app/examenes/{id}/iniciar`: la fila sola, sin politica de acceso ni conjunto congelado. */
    private fun Connection.intentoLegacy(id: String = legacy, usuario: String = UA) =
        ejecutar("insert into public.intentos_examen (id, usuario_id, examen_id, estado, total_preguntas) values ('$id', '$usuario', '$EX', 'en_progreso', 4)")

    private fun Connection.congeladas(intento: String) = unico("select count(*) from public.preguntas_intento where intento_id = '$intento'")

    // ------------------------------------------------------------------ F-01 marcador

    @Test
    fun anAttemptTheStartCommandDidNotCreateIsNotFoundForEveryCommandAndIsNeverFrozen() = Slice3Db.escenario { c ->
        c.intentoLegacy()
        assertEquals("ATTEMPT_NOT_FOUND", c.responder(UA, legacy, p(1), o(1, 1), 0)["outcome"])
        assertEquals("ATTEMPT_NOT_FOUND", c.responder(UA, legacy, p(1), null, 0)["outcome"], "tampoco un salto")
        assertEquals("ATTEMPT_NOT_FOUND", c.finalizar(UA, legacy)["outcome"])
        assertEquals("ATTEMPT_NOT_FOUND", c.abandonar(UA, legacy, 1)["outcome"])
        assertEquals("0", c.congeladas(legacy), "sin congelado tardio: el conjunto de preguntas nunca se materializa")
        assertEquals("0", c.unico("select count(*) from public.respuestas_intento"))
        assertEquals("0", c.unico("select count(*) from public.abandonos_examen"))
        assertEquals(
            listOf(listOf("en_progreso", null, null)),
            c.filas("select estado, finalizado_en::text, creado_por_comando from public.intentos_examen where id = '$legacy'"),
            "un comando rechazado no toca la fila",
        )
        // Ya cerrado por la via antigua tampoco existe (un finalizado legacy no da revision ni resultado).
        c.ejecutar("update public.intentos_examen set estado = 'finalizado', finalizado_en = now() where id = '$legacy'")
        assertEquals("ATTEMPT_NOT_FOUND", c.finalizar(UA, legacy)["outcome"], "ni siquiera el 'already' idempotente")
        assertEquals("ATTEMPT_NOT_FOUND", c.responder(UA, legacy, p(1), o(1, 1), 0)["outcome"])
        assertEquals("ATTEMPT_NOT_FOUND", c.abandonar(UA, legacy, 1)["outcome"])
    }

    /** Control negativo de F-01: con las RPC publicadas (sin 20261008100200) el MISMO intento se congela, responde y finaliza. */
    @Test
    fun withoutTheHardeningTheSameLegacyAttemptIsFrozenLateAnsweredAndFinished() = Slice3Db.escenario(endurecimiento = false) { c ->
        c.intentoLegacy()
        assertEquals("OK", c.responder(UA, legacy, p(1), o(1, 1), 0)["outcome"])
        assertEquals("4", c.congeladas(legacy), "el hueco: el primer comando congelaba el conjunto de un intento sin politica de acceso")
        assertEquals("OK", c.finalizar(UA, legacy)["outcome"])
        assertEquals("finalizado", c.unico("select estado from public.intentos_examen where id = '$legacy'"))
    }

    @Test
    fun theStartCommandMarksItsAttemptsAndOnlyThoseAreServed() = Slice3Db.escenario { c ->
        val (outcome, intento) = c.iniciar(UA, EX)
        assertEquals("CREATED", outcome)
        assertEquals(
            listOf(listOf("app_start_exam_attempt", "examen", "4")),
            c.filas("select creado_por_comando, origen_intento, (select count(*) from public.preguntas_intento where intento_id = ie.id)::text from public.intentos_examen ie where id = '$intento'"),
            "marcador + conjunto congelado en la MISMA transaccion del arranque",
        )
        assertEquals("RESUMED" to intento, c.iniciar(UA, EX))
        assertEquals("OK", c.responder(UA, intento!!, p(1), o(1, 1), 0)["outcome"])
        assertEquals("OK", c.finalizar(UA, intento)["outcome"])
        assertEquals(mapOf<String, String?>("outcome" to "OK", "already" to "true"), c.finalizar(UA, intento))
        // Otro usuario sigue sin verlo, con marcador o sin el.
        assertEquals("ATTEMPT_NOT_FOUND", c.finalizar(UB, intento)["outcome"])
    }

    @Test
    fun startNeverResumesALegacyOpenAttemptItClosesItAndCreatesAMarkedOne() = Slice3Db.escenario { c ->
        c.intentoLegacy()
        // Un examen que no se puede empezar no cierra nada (el cierre va despues de saber que el examen es empezable).
        c.ejecutar("insert into public.intentos_examen (id, usuario_id, examen_id) values ('1e9ac100-0000-4000-8000-000000000002', '$UA', '$EX_EMPTY')")
        assertEquals("EXAM_EMPTY", c.iniciar(UA, EX_EMPTY).first)
        assertEquals("en_progreso", c.unico("select estado from public.intentos_examen where id = '1e9ac100-0000-4000-8000-000000000002'"))

        val (outcome, nuevo) = c.iniciar(UA, EX)
        assertEquals("CREATED", outcome, "el intento legacy abierto NO se reanuda")
        assertNotEquals(legacy, nuevo)
        assertEquals(
            listOf(listOf("abandonado", null, "app_start_exam_attempt", "intento_sin_marcador_de_comando")),
            c.filas("select estado, creado_por_comando, metadatos ->> 'cerradoPor', metadatos ->> 'motivo' from public.intentos_examen where id = '$legacy'"),
            "se cierra (sin borrarlo) y sigue sin marcador",
        )
        assertEquals("0", c.congeladas(legacy), "el intento legacy nunca recibe un conjunto congelado")
        assertEquals("4", c.congeladas(nuevo!!))
        assertEquals("1", c.unico("select count(*) from public.intentos_examen where usuario_id = '$UA' and examen_id = '$EX' and estado = 'en_progreso'"))
        assertEquals("RESUMED" to nuevo, c.iniciar(UA, EX), "el nuevo, con marcador, si se reanuda")
        assertEquals("ATTEMPT_NOT_FOUND", c.abandonar(UA, legacy, 1)["outcome"], "cerrado sigue sin existir para los comandos")
    }

    /** Control negativo: con las RPC publicadas el arranque canonico REANUDABA el intento legacy (y el siguiente comando lo congelaba). */
    @Test
    fun withoutTheHardeningStartResumedTheLegacyOpenAttempt() = Slice3Db.escenario(endurecimiento = false) { c ->
        c.intentoLegacy()
        assertEquals("RESUMED" to legacy, c.iniciar(UA, EX))
    }

    @Test
    fun theMarkerCanNeverBeSetChangedOrClearedAfterTheInsert() = Slice3Db.escenario { c ->
        c.intentoLegacy()
        val marcado = c.iniciar(UB, EX).second!!
        assertEquals("P0001", c.sqlStateDe("update public.intentos_examen set creado_por_comando = 'app_start_exam_attempt' where id = '$legacy'"), "no se 'promociona' un intento legacy")
        assertEquals("P0001", c.sqlStateDe("update public.intentos_examen set creado_por_comando = null where id = '$marcado'"))
        assertNull(c.sqlStateDe("update public.intentos_examen set creado_por_comando = creado_por_comando, total_respondidas = 0 where id = '$marcado'"), "un UPDATE que no lo cambia pasa")
        assertEquals("23514", c.sqlStateDe("insert into public.intentos_examen (usuario_id, examen_id, estado, creado_por_comando) values ('$UA', '$EX', 'abandonado', 'otra_cosa')"), "CHECK: un unico valor")
        assertEquals("ATTEMPT_NOT_FOUND", c.responder(UA, legacy, p(1), o(1, 1), 0)["outcome"])
        // Los clientes no escriben la tabla en absoluto.
        for (rol in listOf("anon", "authenticated")) {
            c.ejecutar("set role $rol")
            try {
                assertEquals("42501", c.sqlStateDe("update public.intentos_examen set creado_por_comando = 'app_start_exam_attempt' where id = '$legacy'"), rol)
                assertEquals("42501", c.sqlStateDe("insert into public.intentos_examen (usuario_id, examen_id, creado_por_comando) values ('$UA', '$EX', 'app_start_exam_attempt')"), rol)
                assertEquals("42501", c.sqlStateDe("select private.tg_intentos_examen_creado_por_comando_inmutable()"), rol)
            } finally {
                c.ejecutar("reset role")
            }
        }
    }

    /** Control negativo del trigger: sin el, un UPDATE directo fabrica el marcador y el intento legacy pasa a ser respondible. */
    @Test
    fun withoutTheImmutabilityTriggerADirectUpdatePromotesALegacyAttempt() = Slice3Db.escenario { c ->
        c.intentoLegacy()
        c.ejecutar("drop trigger trg_intentos_examen_creado_por_comando_inmutable on public.intentos_examen")
        assertNull(c.sqlStateDe("update public.intentos_examen set creado_por_comando = 'app_start_exam_attempt' where id = '$legacy'"))
        // Sin conjunto congelado (ya no hay congelado tardio) ni siquiera asi se puede responder nada...
        assertEquals("QUESTION_NOT_IN_ATTEMPT", c.responder(UA, legacy, p(1), o(1, 1), 0)["outcome"])
        // ...pero el intento "existe" y se puede cerrar: es justo lo que el trigger impide.
        assertEquals("OK", c.finalizar(UA, legacy)["outcome"])
    }

    // ------------------------------------------------------------------ F-06 origen

    private val repaso = "4e9a5000-0000-4000-8000-000000000001"

    @Test
    fun theCommandsOnlyServeExamOriginAttempts() = Slice3Db.escenario { c ->
        // Un intento de repaso (sin examen) aunque llevara el marcador: el predicado de origen es independiente del marcador.
        c.ejecutar("insert into public.intentos_examen (id, usuario_id, examen_id, origen_intento, creado_por_comando) values ('$repaso', '$UA', null, 'repaso_random', 'app_start_exam_attempt')")
        assertEquals("ATTEMPT_NOT_FOUND", c.responder(UA, repaso, p(1), o(1, 1), 0)["outcome"])
        assertEquals("ATTEMPT_NOT_FOUND", c.finalizar(UA, repaso)["outcome"])
        assertEquals("ATTEMPT_NOT_FOUND", c.abandonar(UA, repaso, 1)["outcome"])
        assertEquals("en_progreso", c.unico("select estado from public.intentos_examen where id = '$repaso'"))
    }

    /** Control negativo de F-06: las RPC publicadas cerraban un intento de repaso como si fuera de examen. */
    @Test
    fun withoutTheHardeningARepasoAttemptWasFinishedByTheExamCommand() = Slice3Db.escenario(endurecimiento = false) { c ->
        c.ejecutar("insert into public.intentos_examen (id, usuario_id, examen_id, origen_intento) values ('$repaso', '$UA', null, 'repaso_random')")
        assertEquals("OK", c.finalizar(UA, repaso)["outcome"])
        assertEquals(listOf(listOf("finalizado", "USER")), c.filas("select estado, motivo_fin from public.intentos_examen where id = '$repaso'"))
    }

    // ------------------------------------------------------------------ F-05 indices de FK

    private fun Connection.plan(sql: String): String {
        ejecutar("set enable_seqscan = off")
        try {
            return filas("explain $sql").joinToString("\n") { it[0].orEmpty() }
        } finally {
            ejecutar("reset enable_seqscan")
        }
    }

    @Test
    fun theTwoNewForeignKeysHaveAPartialIndexThatTheReferentialLookupUses() {
        // Las consultas que lanza la integridad referencial: `... where $1 = primera_opcion_id` (ON DELETE SET NULL) y
        // `select 1 ... where especialidad_snapshot_id = $1` (NO ACTION).
        val porOpcion = "select 1 from public.respuestas_intento x where x.primera_opcion_id = '${o(1, 1)}'"
        val porEspecialidad = "select 1 from public.intentos_examen x where x.especialidad_snapshot_id = '${Slice3Db.ESP1}'"
        Slice3Db.escenario { c ->
            assertEquals(
                listOf(
                    listOf("CREATE INDEX idx_intentos_examen_especialidad_snapshot_id ON public.intentos_examen USING btree (especialidad_snapshot_id) WHERE (especialidad_snapshot_id IS NOT NULL)"),
                    listOf("CREATE INDEX idx_respuestas_intento_primera_opcion_id ON public.respuestas_intento USING btree (primera_opcion_id) WHERE (primera_opcion_id IS NOT NULL)"),
                ),
                c.filas("select indexdef from pg_indexes where schemaname = 'public' and indexname in ('idx_respuestas_intento_primera_opcion_id', 'idx_intentos_examen_especialidad_snapshot_id') order by indexname"),
            )
            assertTrue("idx_respuestas_intento_primera_opcion_id" in c.plan(porOpcion), c.plan(porOpcion))
            assertTrue("idx_intentos_examen_especialidad_snapshot_id" in c.plan(porEspecialidad), c.plan(porEspecialidad))
            // El borrado real de una opcion (lo que hace la edicion de preguntas) sigue anulando la primera respuesta.
            val intento = c.iniciar(UA, EX).second!!
            c.responder(UA, intento, p(1), o(1, 2), 0)
            c.ejecutar("delete from public.opciones_pregunta where id = '${o(1, 2)}'")
            assertEquals(listOf(listOf<String?>(null, null)), c.filas("select primera_opcion_id::text, opcion_seleccionada_id::text from public.respuestas_intento where intento_id = '$intento'"))
        }
        // Control negativo: sin 20261008100200 no hay indice que usar y esas mismas consultas recorren la tabla.
        Slice3Db.escenario(endurecimiento = false) { c ->
            assertEquals("0", c.unico("select count(*) from pg_indexes where schemaname = 'public' and indexdef like '%(primera_opcion_id)%'"))
            assertEquals("0", c.unico("select count(*) from pg_indexes where schemaname = 'public' and indexdef like '%(especialidad_snapshot_id)%'"))
            assertTrue("Seq Scan on respuestas_intento" in c.plan(porOpcion), c.plan(porOpcion))
            assertTrue("Seq Scan on intentos_examen" in c.plan(porEspecialidad), c.plan(porEspecialidad))
        }
    }

    // ------------------------------------------------------------------ preflight, VERIFY, rollback

    @Test
    fun theHardeningStopsWithoutThePublishedMigrationsAndAppliesNothing() = Slice3Db.escenario(migraciones = false) { c ->
        fun falla(): SQLException {
            val error = runCatching { c.aplicar(Slice3Db.HARDENING) }.exceptionOrNull() as? SQLException
            assertNotNull(error, "la migracion debe detenerse")
            c.ejecutar("rollback")
            return error
        }
        assertTrue(falla().message.orEmpty().contains("SLICE3_HARDENING_PREFLIGHT"), "sin 20261008100000")
        c.aplicar(Slice3Db.SCHEMA)
        val sinRpcs = falla()
        assertTrue(sinRpcs.message.orEmpty().contains("SLICE3_HARDENING_PREFLIGHT"), sinRpcs.message)
        assertTrue(sinRpcs.message.orEmpty().contains("app_start_exam_attempt"), "dice que falta: ${sinRpcs.message}")
        assertEquals("0", c.unico("select count(*) from information_schema.columns where table_schema = 'public' and column_name = 'creado_por_comando'"))
        assertNull(c.unico("select to_regclass('public.idx_respuestas_intento_primera_opcion_id')::text"))
        c.aplicar(Slice3Db.RPCS)
        c.aplicar(Slice3Db.HARDENING)
        assertEquals("CREATED", c.iniciar(UA, EX).first)
    }

    @Test
    fun verifyIsZeroReportsUnmarkedOpenAttemptsWithoutFailingAndDetectsTheUnhardenedFunctions() = Slice3Db.escenario(endurecimiento = false) { c ->
        // Dos intentos abiertos del arranque antiguo y uno ya cerrado: la migracion NO falla por ellos ni los toca.
        c.intentoLegacy()
        c.intentoLegacy("1e9ac100-0000-4000-8000-000000000003", UB)
        c.ejecutar("insert into public.intentos_examen (usuario_id, examen_id, estado) values ('$UA', '$EX', 'finalizado')")
        c.aplicar(Slice3Db.HARDENING)
        c.aplicar(Slice3Db.HARDENING) // idempotente
        assertEquals(listOf(listOf("en_progreso", "2"), listOf("finalizado", "1")), c.filas("select estado, count(*) from public.intentos_examen group by estado order by estado"))
        val verify = c.filas(Slice0Db.verifyDe(Slice3Db.HARDENING))
        assertEquals(11, verify.size, "filas del VERIFY: $verify")
        assertEquals(emptyList(), verify.filter { it[1] != "0" }, "VERIFY con contadores distintos de 0")
        assertEquals(listOf("open_exam_attempts_without_command_marker_info", "0", "2"), verify.single { it[0] == "open_exam_attempts_without_command_marker_info" }, "informa, no falla")

        // Control negativo del VERIFY: con los cuerpos publicados (re-aplicar 20261008100100) lo detecta.
        c.aplicar(Slice3Db.RPCS)
        val sinEndurecer = c.filas(Slice0Db.verifyDe(Slice3Db.HARDENING)).filter { it[1] != "0" }.associate { it[0]!! to it[1]!! }
        assertEquals(
            mapOf(
                "hardening_command_without_marker_or_origin_guard" to "3",
                "hardening_command_with_late_freeze" to "2",
                "hardening_start_does_not_set_marker" to "1",
            ),
            sinEndurecer,
        )
        // ...y sin los objetos nuevos tambien (rollback del endurecimiento).
        c.ejecutar(Slice0Db.rollbackDe(Slice3Db.HARDENING))
        val trasRollback = c.filas(Slice0Db.verifyDe(Slice3Db.HARDENING).substringBefore("union all\nselect 'open_exam_attempts_without_command_marker_info'"))
            .filter { it[1] != "0" }.map { it[0]!! }.toSet()
        assertTrue(
            trasRollback.containsAll(
                setOf(
                    "hardening_marker_column_missing", "hardening_marker_check_missing", "hardening_marker_immutable_trigger_missing",
                    "hardening_fk_indexes_missing_or_wrong",
                ),
            ),
            trasRollback.toString(),
        )
    }

    @Test
    fun theRollbackDropsOnlyWhatTheHardeningAddedAndReapplyingThePublishedRpcsRestoresThem() = Slice3Db.escenario(endurecimiento = false) { c ->
        val forma = "select string_agg(table_name || '.' || column_name, ',' order by table_name, column_name) from information_schema.columns " +
            "where table_schema = 'public' and table_name in ('intentos_examen', 'respuestas_intento', 'preguntas_intento')"
        val indices = "select string_agg(indexname, ',' order by indexname) from pg_indexes where schemaname = 'public' and tablename in ('intentos_examen', 'respuestas_intento')"
        val antes = c.unico(forma) to c.unico(indices)
        c.aplicar(Slice3Db.HARDENING)
        val intento = c.iniciar(UA, EX).second!!
        c.responder(UA, intento, p(1), o(1, 1), 0)
        assertNotEquals(antes, c.unico(forma) to c.unico(indices))

        c.ejecutar(Slice0Db.rollbackDe(Slice3Db.HARDENING))
        assertEquals(antes, c.unico(forma) to c.unico(indices), "columnas e indices como antes del endurecimiento")
        assertEquals("0", c.unico("select count(*) from pg_proc where proname = 'tg_intentos_examen_creado_por_comando_inmutable'"))
        assertEquals("1", c.unico("select count(*) from public.respuestas_intento where intento_id = '$intento'"), "el rollback no borra intentos ni respuestas")
        // Lo dice la cabecera: `create or replace` no guarda historia. Hasta re-aplicar 20261008100100 los comandos fallan
        // (nombran la columna que ya no esta), es decir, cerrado; no sirven nada.
        assertEquals("42703", c.sqlStateDe("select public.app_finish_exam_attempt('$UA', '$intento')"))
        assertFalse(c.unico("select estado from public.intentos_examen where id = '$intento'") == "finalizado")
        c.aplicar(Slice3Db.RPCS)
        assertEquals("OK", c.finalizar(UA, intento)["outcome"], "con los cuerpos publicados de vuelta el intento se cierra")
    }
}
