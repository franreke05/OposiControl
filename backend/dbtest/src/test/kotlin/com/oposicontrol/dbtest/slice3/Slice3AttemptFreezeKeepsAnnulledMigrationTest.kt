package com.oposicontrol.dbtest.slice3

import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.aplicar
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.slice3.Slice3Db.EX
import com.oposicontrol.dbtest.slice3.Slice3Db.UA
import com.oposicontrol.dbtest.slice3.Slice3Db.o
import com.oposicontrol.dbtest.slice3.Slice3Db.p
import com.oposicontrol.dbtest.unico
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Migracion 20261010100000 (decision del propietario del 2026-10-10): el intento muestra TODAS las preguntas activas del
 * examen, tambien las anuladas, marcadas y sin puntuar. Corre sobre las RPC endurecidas de Slice 3. En el examen del
 * escenario: 1..4 evaluables, 5 archivada, 6 anulada antes de empezar.
 */
class Slice3AttemptFreezeKeepsAnnulledMigrationTest {
    private companion object {
        const val MIGRACION = "supabase/migrations/20261010100000_slice3_attempt_freeze_keeps_annulled.sql"
    }

    @Test
    fun anAttemptFreezesAnnulledQuestionsTooButNeverCountsThem() = Slice3Db.escenario { c ->
        c.aplicar(MIGRACION)
        val (outcome, intento) = c.iniciar(UA, EX)
        assertEquals("CREATED", outcome)
        assertEquals(
            listOf(listOf("1", p(1)), listOf("2", p(2)), listOf("3", p(3)), listOf("4", p(4)), listOf("5", p(6))),
            c.filas("select posicion, pregunta_id from public.preguntas_intento where intento_id = '$intento' order by posicion"),
            "la anulada (6) entra en el intento; la archivada (5) sigue fuera",
        )
        assertEquals("4", c.unico("select total_preguntas from public.intentos_examen where id = '$intento'"), "el total sigue siendo el de evaluables")

        // Responder una anulada no guarda ni puntua, y no cuenta como respondida.
        c.responder(UA, intento!!, p(1), o(1, 1), 0)
        val anulada = c.responder(UA, intento, p(6), o(6, 1), 0)
        assertEquals("OK", anulada["outcome"])
        assertEquals("ANNULLED", anulada["estado"])
        assertEquals("1", anulada["respondidas"])
        assertEquals("0", c.unico("select count(*) from public.respuestas_intento where intento_id = '$intento' and pregunta_id = '${p(6)}'"))

        c.finalizar(UA, intento)
        assertEquals(
            listOf(listOf("CORRECT"), listOf("UNANSWERED"), listOf("UNANSWERED"), listOf("UNANSWERED"), listOf("ANNULLED")),
            c.filas("select estado_final from public.preguntas_intento where intento_id = '$intento' order by posicion"),
        )
        assertEquals(
            listOf(listOf("4", "1", "1", "0", "3")),
            c.filas("select total_preguntas, total_respondidas, total_correctas, total_falladas, total_sin_responder from public.intentos_examen where id = '$intento'"),
            "la anulada no esta en ningun total",
        )
    }

    @Test
    fun verifyIsCleanAndTheRollbackRestoresThePreviousRule() = Slice3Db.escenario { c ->
        c.aplicar(MIGRACION)
        assertEquals(
            listOf("attempt_freeze_executable_by_clients" to "0", "attempt_freeze_function_missing" to "0", "attempt_freeze_still_drops_annulled" to "0"),
            c.filas(Slice0Db.verifyDe(MIGRACION)).map { it[0]!! to it[1]!! }.sortedBy { it.first },
        )
        c.aplicar(MIGRACION) // reaplicar es inocuo
        c.ejecutar(Slice0Db.rollbackDe(MIGRACION))
        // Control negativo: sin la migracion, la anulada vuelve a quedar fuera del intento.
        val intento = c.iniciar(UA, EX).second
        assertEquals("4", c.unico("select count(*) from public.preguntas_intento where intento_id = '$intento'"))
        assertEquals("1", c.filas(Slice0Db.verifyDe(MIGRACION)).single { it[0] == "attempt_freeze_still_drops_annulled" }[1])
    }
}
