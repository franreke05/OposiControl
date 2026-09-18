package com.oposicontrol.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RafagaParserTest {
    @Test
    fun digitalTextParserKeepsLongReviewableFields() {
        val source = "FUENTE: ${"x".repeat(4000)}"
        val (questions, errors) = parseQuestionBlocks(
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
            tipoPregunta = "razonada",
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

    // -----------------------------------------------------------------
    // KI0-2 · claves antiguas y reglas por tipo
    // -----------------------------------------------------------------

    /**
     * `ID_GLOBAL: 7` justo después del título se pegaba como continuación del
     * enunciado y se publicaba dentro de él. Ahora invalida el bloque y el título
     * queda limpio.
     */
    @Test
    fun aLegacyKeyLineInvalidatesTheBlockAndNeverLeaksIntoTheTitle() {
        val parsed = parseQuestionBlocks(
            """
            --- PREGUNTA ---
            TITULO: Plazo de alegaciones
            ID_GLOBAL: 7
            A: a
            B: b
            C: c
            D: d
            RESPUESTA: B
            """.trimIndent(),
            tipoPregunta = "rafaga",
        )

        assertTrue(parsed.preguntas.isEmpty(), "${parsed.preguntas}")
        val bloque = assertNotNull(parsed.bloquesConError.singleOrNull())
        assertFalse(bloque.titulo.contains("ID_GLOBAL"), bloque.titulo)
        assertTrue(bloque.detalle.contains("ID_GLOBAL: Campo no admitido en el PDF"), bloque.detalle)
    }

    /** `NOTA:` dentro de una explicación no lleva guion bajo: es texto y sigue siéndolo. */
    @Test
    fun aPlainWordWithAColonInsideAnExplanationIsStillText() {
        val parsed = parseQuestionBlocks(
            """
            --- PREGUNTA ---
            TITULO: Plazo
            A: a
            B: b
            C: c
            D: d
            RESPUESTA: B
            RAZONAMIENTO: Según el artículo 82.
            NOTA: cuenta desde la notificación.
            """.trimIndent(),
            tipoPregunta = "razonada",
        )

        val pregunta = assertNotNull(parsed.preguntas.singleOrNull(), "${parsed.bloquesConError}")
        assertTrue(pregunta.razonamiento.contains("NOTA: cuenta desde la notificación."), pregunta.razonamiento)
    }

    @Test
    fun anAnswerOutsideAToDIsAnErrorWithAnEmptyAnswer() {
        val parsed = parseQuestionBlocks(
            """
            --- PREGUNTA ---
            TITULO: Plazo
            A: a
            B: b
            C: c
            D: d
            RESPUESTA: E
            """.trimIndent(),
            tipoPregunta = "rafaga",
        )

        assertEquals("RESPUESTA", parsed.errores.single().campo)
        assertEquals("E", parsed.bloquesConError.single().respuesta, "la letra leída viaja al staging, que la sanea a NULL")
    }

    /** DEC-IMP-04: una razonada (o inmersiva) sin explicación no es importable. */
    @Test
    fun aReasonedQuestionWithoutReasoningIsAnError() {
        val parsed = parseQuestionBlocks(
            """
            --- PREGUNTA ---
            TITULO: Plazo
            A: a
            B: b
            C: c
            D: d
            RESPUESTA: B
            """.trimIndent(),
            tipoPregunta = "razonada",
        )

        assertTrue(parsed.preguntas.isEmpty())
        assertEquals("RAZONAMIENTO", parsed.errores.single().campo)
        // Control: el mismo bloque es una Ráfaga perfectamente válida.
        assertEquals(1, parseQuestionBlocks(parsed.bloquesConError.single().bloqueOriginal, "rafaga").preguntas.size)
    }

    /** Ráfaga con FUENTE o RAZONAMIENTO: válida, pero AVISADA, porque no se van a publicar. */
    @Test
    fun aRafagaWithExtrasIsValidButWarned() {
        val parsed = parseQuestionBlocks(
            """
            --- PREGUNTA ---
            TITULO: Plazo
            A: a
            B: b
            C: c
            D: d
            RESPUESTA: B
            FUENTE: Ley 39/2015
            """.trimIndent(),
            tipoPregunta = "rafaga",
        )

        val pregunta = assertNotNull(parsed.preguntas.singleOrNull(), "${parsed.errores}")
        assertEquals(RAFAGA_DISCARDS_EXTRAS_WARNING, pregunta.advertencia)
        // Y sin extras no hay aviso.
        val limpia = parseQuestionBlocks(pregunta.bloqueOriginal.lines().filterNot { it.startsWith("FUENTE") }.joinToString("\n"), "rafaga")
        assertNull(limpia.preguntas.single().advertencia)
    }
}
