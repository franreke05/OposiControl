package com.oposicontrol.admin.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ContentImportModelsTest {
    @Test
    fun canonicalVocabularyMapsLegacyOnlyAtTheBoundary() {
        assertEquals("rafaga", canonicalExamType("rafaga"))
        assertEquals("razonado", canonicalExamType("guiado"))
        assertEquals("razonado", canonicalExamType("razonado"))
        assertEquals("inmersivo", canonicalExamType("inmersivo"))
        assertEquals("razonada", canonicalQuestionType("razonado"))
        assertEquals("inmersiva", canonicalQuestionType("inmersivo"))
    }
}
