package com.oposicontrol.admin.domain.model

data class ContentImportBatch(
    val id: String,
    val examId: String,
    val fileName: String,
    val examType: String,
    val status: String,
    val detectedCount: Int,
    val importedCount: Int,
    val staging: List<ContentImportStagingItem> = emptyList(),
)

data class ContentImportStagingItem(
    val id: String,
    val position: Int,
    val title: String,
    val optionA: String,
    val optionB: String,
    val optionC: String,
    val optionD: String,
    val correctAnswer: String,
    val reasoning: String,
    val sourceText: String,
    val tagText: String,
    val originalBlock: String,
    val parseStatus: String,
    val parseError: String? = null,
    val questionId: String? = null,
)

enum class ContentExamType(val apiValue: String, val questionApiValue: String, val label: String) {
    RAFAGA("rafaga", "rafaga", "Ráfaga"),
    RAZONADO("razonado", "razonada", "Razonado"),
    INMERSIVO("inmersivo", "inmersiva", "Inmersivo"),
    ;

    companion object {
        fun fromApiValue(value: String): ContentExamType = when (value.lowercase()) {
            "razonado", "guiado" -> RAZONADO
            "inmersivo" -> INMERSIVO
            else -> RAFAGA
        }
    }
}

fun canonicalExamType(value: String): String = ContentExamType.fromApiValue(value).apiValue

fun canonicalQuestionType(value: String): String = when (canonicalExamType(value)) {
    "rafaga" -> "rafaga"
    "razonado" -> "razonada"
    else -> "inmersiva"
}
