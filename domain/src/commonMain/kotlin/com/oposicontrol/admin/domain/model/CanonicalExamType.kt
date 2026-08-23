package com.oposicontrol.admin.domain.model

/** Canonical product vocabulary. Legacy database aliases are accepted only at boundaries. */
enum class ExamType(val wireValue: String, private val legacyAliases: Set<String> = emptySet()) {
    RAFAGA("rafaga"),
    RAZONADO("razonado", setOf("guiado")),
    INMERSIVO("inmersivo"),
    ;

    companion object {
        fun fromExternal(value: String): ExamType? {
            val normalized = value.trim().lowercase()
            return entries.firstOrNull { it.wireValue == normalized || normalized in it.legacyAliases }
        }
    }
}

/** Canonical question vocabulary. Legacy database aliases are accepted only at boundaries. */
enum class QuestionType(val wireValue: String, private val legacyAliases: Set<String> = emptySet()) {
    RAFAGA("rafaga"),
    RAZONADA("razonada", setOf("guiada")),
    INMERSIVA("inmersiva"),
    ;

    companion object {
        fun fromExternal(value: String): QuestionType? {
            val normalized = value.trim().lowercase()
            return entries.firstOrNull { it.wireValue == normalized || normalized in it.legacyAliases }
        }
    }
}

object CanonicalExamTypeNormalizer {
    fun normalizeExam(value: String): String? = ExamType.fromExternal(value)?.wireValue

    fun normalizeQuestion(value: String): String? = QuestionType.fromExternal(value)?.wireValue
}
