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
    /**
     * El recuento que calcula el SERVIDOR sobre el staging del lote. `null` cuando no
     * viene (backend antiguo): no se inventa a partir de un `staging` vacío, que es lo
     * que llega en el listado de lotes.
     */
    val summary: ContentImportSummary? = null,
)

/**
 * `resumen` del lote tal y como lo cuenta el servidor. Cada cifra puede faltar por
 * separado en una respuesta recortada, así que ninguna se rellena con un 0 falso.
 */
data class ContentImportSummary(
    val ready: Int? = null,
    val withErrors: Int? = null,
    val warnings: Int? = null,
    val skipped: Int? = null,
    val imported: Int? = null,
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
    /**
     * La pregunta va ANULADA por el tribunal en el examen destino. Es un dato de la
     * pregunta EN EL EXAMEN, no del parseo: `parseStatus = ok` + `annulled = true` es
     * un estado válido y no se mezclan.
     */
    val annulled: Boolean = false,
    /** Sólo tiene sentido con [annulled]; `null` = sin motivo (o borrar el que hubiera). */
    val annulmentReason: String? = null,
)

/** Tope del motivo de anulación que acepta el servidor (CHECK de la base). */
const val IMPORT_ANNULMENT_REASON_MAX_LENGTH: Int = 500

/**
 * Lo que falta para que una fila se pueda GUARDAR o APROBAR, o `null` si está completa.
 *
 * Es la regla de completitud del cliente (título, cuatro opciones, respuesta A–D). No
 * aplica a omitir: una fila en error tiene que poder descartarse tal cual está.
 */
fun stagingCompletenessProblem(item: ContentImportStagingItem): String? = when {
    item.title.isBlank() -> "La pregunta necesita título."
    listOf(item.optionA, item.optionB, item.optionC, item.optionD).any { it.isBlank() } ->
        "Las cuatro opciones deben tener contenido."
    item.correctAnswer.trim().uppercase() !in setOf("A", "B", "C", "D") ->
        "La respuesta correcta debe ser A, B, C o D."
    else -> null
}

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
