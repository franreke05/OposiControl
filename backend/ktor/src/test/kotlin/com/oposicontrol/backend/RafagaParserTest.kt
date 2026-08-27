package com.oposicontrol.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RafagaParserTest {
    @Test
    fun digitalTextParserKeepsLongReviewableFields() {
        val source = "FUENTE: ${"x".repeat(4000)}"
        val (questions, errors) = parseRafagaText(
            """
            --- PREGUNTA ---
            TITULO: Pregunta larga
            A: Opción A
            B: Opción B
            C: Opción C
            D: Opción D
            RESPUESTA: C
            RAZONAMIENTO: Explicación extensa
            $source
            """.trimIndent(),
        )

        assertTrue(errors.isEmpty())
        assertEquals(1, questions.size)
        assertEquals("C", questions.single().respuesta)
        assertTrue(questions.single().fuente.length > 3000)
    }

    @Test
    fun invalidDigitalTextReturnsHumanParseErrorWithoutQuestions() {
        val (questions, errors) = parseRafagaText(
            """
            --- PREGUNTA ---
            TITULO: Sin respuesta
            A: A
            B: B
            C: C
            D: D
            """.trimIndent(),
        )

        assertTrue(questions.isEmpty())
        assertEquals("RESPUESTA", errors.single().campo)
    }
}
