package com.oposicontrol.admin.features.exams.presentation

import kotlin.test.Test
import kotlin.test.assertEquals

class QuestionImportParserTest {
    @Test
    fun `parses aiken multiple questions`() {
        val content = """
            Primera pregunta importada?
            A. Uno
            B. Dos
            C. Tres
            D. Cuatro
            ANSWER: B

            Segunda pregunta importada?
            A. Alfa
            B. Beta
            C. Gamma
            D. Delta
            ANSWER: D
        """.trimIndent()

        val result = QuestionImportParser.parse(content)

        assertEquals(2, result.size)
        assertEquals("Primera pregunta importada?", result.first().statement)
        assertEquals("B", result.first().correctLabel)
        assertEquals("Delta", result.last().options.last())
    }

    @Test
    fun `parses simple gift questions`() {
        val content = "::Nombre::Capital de Espana?{~Barcelona =Madrid ~Valencia ~Sevilla}"

        val result = QuestionImportParser.parse(content)

        assertEquals(1, result.size)
        assertEquals("Nombre", result.first().title)
        assertEquals("Capital de Espana?", result.first().statement)
        assertEquals("B", result.first().correctLabel)
    }

    @Test
    fun `limits report to fifty valid questions`() {
        val content = (1..55).joinToString("\n\n") { index ->
            """
                Pregunta $index?
                A. Uno
                B. Dos
                C. Tres
                D. Cuatro
                ANSWER: A
            """.trimIndent()
        }

        val result = QuestionImportParser.parseReport(content)

        assertEquals(50, result.validCount)
        assertEquals(5, result.skippedOverLimit)
    }

    @Test
    fun `reports aiken question with less than four options`() {
        val content = """
            Pregunta incompleta?
            A. Uno
            B. Dos
            C. Tres
            ANSWER: B
        """.trimIndent()

        val result = QuestionImportParser.parseReport(content)

        assertEquals(0, result.validCount)
        assertEquals(1, result.issues.size)
    }

    @Test
    fun `reports aiken question without correct answer`() {
        val content = """
            Pregunta sin respuesta?
            A. Uno
            B. Dos
            C. Tres
            D. Cuatro
        """.trimIndent()

        val result = QuestionImportParser.parseReport(content)

        assertEquals(0, result.validCount)
        assertEquals(1, result.issues.size)
    }

    @Test
    fun `reports mixed valid and invalid questions`() {
        val content = """
            Valida?
            A. Uno
            B. Dos
            C. Tres
            D. Cuatro
            ANSWER: D

            Invalida?
            A. Uno
            B. Dos
            C. Tres
            D. Cuatro
        """.trimIndent()

        val result = QuestionImportParser.parseReport(content)

        assertEquals(1, result.validCount)
        assertEquals(1, result.issues.size)
    }
}
