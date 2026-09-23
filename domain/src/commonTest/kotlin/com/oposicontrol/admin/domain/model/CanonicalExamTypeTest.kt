package com.oposicontrol.admin.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CanonicalExamTypeTest {
    @Test
    fun legacyExamAliasNormalizesToCanonicalValue() {
        assertEquals("razonado", CanonicalExamTypeNormalizer.normalizeExam(" guiado "))
    }

    @Test
    fun legacyQuestionAliasNormalizesToCanonicalValue() {
        assertEquals("razonada", CanonicalExamTypeNormalizer.normalizeQuestion("GUIADA"))
    }

    @Test
    fun canonicalValuesRemainStable() {
        assertEquals(ExamType.RAFAGA, ExamType.fromExternal("rafaga"))
        assertEquals(ExamType.RAZONADO, ExamType.fromExternal("razonado"))
        assertEquals(ExamType.INMERSIVO, ExamType.fromExternal("inmersivo"))
        assertEquals(QuestionType.RAZONADA, QuestionType.fromExternal("razonada"))
    }

    @Test
    fun unknownValuesAreNotPersistedAsCanonicalValues() {
        assertNull(CanonicalExamTypeNormalizer.normalizeExam("otro"))
        assertNull(CanonicalExamTypeNormalizer.normalizeQuestion("otro"))
    }
}
