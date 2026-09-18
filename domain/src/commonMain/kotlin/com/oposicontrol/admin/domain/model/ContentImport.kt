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
    /**
     * `false` cuando la detección de duplicados NO se pudo ejecutar (falta IM3): la
     * pantalla lo dice tal cual. Sin este dato, un lote sin comprobar se vería igual
     * que uno comprobado y limpio, y el revisor publicaría repetidas.
     */
    val duplicatesChecked: Boolean = true,
)

/**
 * Una coincidencia detectada para una fila (KI3-2). [origin] es `banco` (ya existe
 * publicada: se puede vincular) o `lote` (el PDF la trae dos veces: hay que omitir una).
 */
data class ContentImportDuplicate(
    val origin: String,
    val questionId: String? = null,
    val questionCode: String = "",
    val stagingOriginalId: String? = null,
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
    /** La pregunta existente con la que el revisor decide vincular esta fila (IM2). `null` = crear. */
    val linkQuestionId: String? = null,
    /** Lectura: la coincidencia detectada, o `null`. */
    val duplicate: ContentImportDuplicate? = null,
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
