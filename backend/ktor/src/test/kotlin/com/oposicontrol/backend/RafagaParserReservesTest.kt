package com.oposicontrol.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** FA37: header `CODIGO_EXAMEN` and the `--- BLOQUE: PRINCIPAL|RESERVA ---` markers of an official exam file. */
class RafagaParserReservesTest {
    private fun q(n: Int, extra: String = "") = """
--- PREGUNTA ---
TITULO: Pregunta $n
A: a$n
B: b$n
C: c$n
D: d$n
RESPUESTA: A
$extra""".trimIndent()

    @Test
    fun theHeaderAndTheBlocksAreRead() {
        val text = "Examen oficial 2026\nCODIGO_EXAMEN: IMP-1\n\n" + q(1) + "\n" + q(2, "ESTADO: ANULADA\nMOTIVO_ANULACION: tribunal") +
            "\n--- BLOQUE: RESERVA ---\n" + q(3) + "\n" + q(4)
        val r = parseQuestionBlocks(text, "rafaga")
        assertEquals(listOf("IMP-1"), r.codigosExamen)
        assertEquals(listOf(BLOQUE_PRINCIPAL, BLOQUE_PRINCIPAL, BLOQUE_RESERVA, BLOQUE_RESERVA), r.preguntas.map { it.bloque })
        assertEquals(listOf(1, 2, 3, 4), r.preguntas.map { it.numero })
        assertTrue(r.preguntas[1].anulada)
        assertTrue(r.bloquesConError.isEmpty())
        assertTrue(r.preguntas.none { it.advertencia != null }, "markers and header produce no warnings")
    }

    @Test
    fun withoutMarkersEverythingIsMain() {
        val r = parseQuestionBlocks(q(1) + "\n" + q(2), "rafaga")
        assertEquals(emptyList(), r.codigosExamen)
        assertTrue(r.preguntas.all { it.bloque == BLOQUE_PRINCIPAL })
    }

    @Test
    fun theMainMarkerCanComeBackAfterTheReserves() {
        val r = parseQuestionBlocks(q(1) + "\n--- BLOQUE: RESERVA ---\n" + q(2) + "\n--- BLOQUE: PRINCIPAL ---\n" + q(3), "rafaga")
        assertEquals(listOf(BLOQUE_PRINCIPAL, BLOQUE_RESERVA, BLOQUE_PRINCIPAL), r.preguntas.map { it.bloque })
    }

    @Test
    fun aNearMissMarkerIsAnErrorBlockNeverASilentMainOrReserve() {
        for (bad in listOf("--- BLOQUE: RESERVAS ---", "-- bloque reserva --", "--- BLOQUE RESERVA ---", "--- BLOQUE: reserva ---")) {
            val r = parseQuestionBlocks(q(1) + "\n" + bad + "\n" + q(2), "rafaga")
            assertEquals(2, r.bloquesConError.size, "'$bad' opens an error block and the question after it is uncertain too")
            assertTrue(r.bloquesConError.first().detalle.contains(INVALID_BLOQUE_DETAIL), bad)
            assertEquals(1, r.preguntas.size, "only the question before the bad marker is fine: $bad")
        }
    }

    @Test
    fun twoDifferentHeadersAreBothReportedSoTheCallerCanRejectTheFile() {
        val r = parseQuestionBlocks("CODIGO_EXAMEN: A-1\nCODIGO_EXAMEN: B-2\n" + q(1), "rafaga")
        assertEquals(listOf("A-1", "B-2"), r.codigosExamen)
    }

    @Test
    fun theHeaderIsOnlyReadBeforeTheFirstQuestion() {
        val r = parseQuestionBlocks(q(1, "FUENTE: x\nCODIGO_EXAMEN: SNEAK-9"), "rafaga")
        assertEquals(emptyList(), r.codigosExamen)
    }
}
