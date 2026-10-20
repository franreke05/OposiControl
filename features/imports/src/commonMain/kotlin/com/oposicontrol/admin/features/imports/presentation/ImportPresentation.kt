package com.oposicontrol.admin.features.imports.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.StatusTone
import com.oposicontrol.admin.domain.model.ContentExamType
import com.oposicontrol.admin.domain.model.ContentImportBatch
import com.oposicontrol.admin.domain.model.ContentImportStagingItem
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.RazonadoMarkup
import com.oposicontrol.admin.domain.model.isReserve
import com.oposicontrol.admin.domain.model.stagingCompletenessProblem

/**
 * Lo que la pantalla de Importación DECIDE, sin pintar nada.
 *
 * **Lo que el contrato de importación tiene hoy** —y es bastante menos de lo que
 * una plantilla completa pediría—:
 *
 *  - Un lote (`ContentImportBatch`) con archivo, tipo, estado y recuentos.
 *  - Filas de staging (`ContentImportStagingItem`) con enunciado, cuatro opciones,
 *    respuesta correcta, razonamiento, fuente, un `tagText` de TEXTO LIBRE, su
 *    estado de parseo y, cuando falla, el mensaje del servidor.
 *  - Un examen destino **por lote**, elegido en esta pantalla.
 *
 * **Lo que NO existe, y por tanto esta pantalla no finge:**
 *
 *  - **`ID_GLOBAL` por fila.** Staging no trae código humano de pregunta; sólo
 *    aparece `questionId` —un identificador interno— cuando la fila ya se ha
 *    publicado. No se enseña.
 *  - **`ID_EXAMEN` por fila.** El examen es del lote entero, no de cada pregunta.
 *  - **`TEMA` como código canónico.** Llega como texto libre. Pintar «TEMA-001»
 *    donde el servidor manda una frase sería inventarse una taxonomía.
 *  - **`ID_AUDIO` / `ID_VIDEO` / `ID_IMAGEN`.** El staging no tiene ningún campo
 *    de recurso. Una Inmersiva importada hoy llega sin referencias de material.
 *  - **Código humano de lote** (`IMP-000042`). Sólo hay un identificador interno,
 *    que no se enseña: el lote se identifica por su archivo.
 *  - **Parseo en cliente.** El PDF lo analiza el servidor; aquí no hay parser ni
 *    etapas intermedias que mostrar.
 *  - **Recursos de la Inmersiva.** Se importa SIN recursos (ver [IMMERSIVE_RESOURCE_IMPORT]).
 *
 * La publicación SÍ es atómica: una sola llamada, todo o nada (ver [IMPORT_ATOMIC_NOTICE]).
 *
 * Todo eso está en [importCapabilities], y la UI se pinta desde ahí.
 */

// ---------------------------------------------------------------------------
// Tipos
// ---------------------------------------------------------------------------

/**
 * El nombre de PREGUNTA del tipo, no el de examen.
 *
 * `ContentExamType.label` dice «Razonado»/«Inmersivo» porque nombra el tipo del
 * examen. Lo que se importa son preguntas, y ahí el vocabulario canónico es
 * Ráfaga · Razonada · Inmersiva (`questionApiValue`).
 */
fun importTypeLabel(type: ContentExamType): String = when (type.questionApiValue) {
    "rafaga" -> "Ráfaga"
    "razonada" -> "Razonada"
    else -> "Inmersiva"
}

/** El alias legacy se LEE; nunca se ofrece ni se escribe. */
fun importTypeFromWire(value: String): ContentExamType = ContentExamType.fromApiValue(value)

/**
 * Qué admite y qué no admite cada tipo **en la importación de hoy**.
 *
 * No es la regla de producto ideal: es la que sostiene el staging real. Por eso
 * los recursos salen como «no admitidos» también en Inmersiva — el contrato de
 * importación no tiene dónde ponerlos, y decir lo contrario prometería que el PDF
 * puede referenciar un audio.
 */
data class ImportTypeRule(
    val label: String,
    val subtitle: String,
    val admits: List<String>,
    val rejects: List<String>,
)

fun importTypeRule(type: ContentExamType): ImportTypeRule = when (type) {
    ContentExamType.RAFAGA -> ImportTypeRule(
        label = "Ráfaga",
        subtitle = "Preguntas rápidas",
        admits = listOf("Enunciado", "Opciones A–D", "Respuesta correcta", "Tema"),
        rejects = listOf("Explicación", "Fuente", "Recursos"),
    )
    ContentExamType.RAZONADO -> ImportTypeRule(
        label = "Razonada",
        subtitle = "Con explicación",
        admits = listOf("Enunciado", "Opciones A–D", "Respuesta correcta", "Tema", "Explicación", "Fuente"),
        rejects = listOf("Recursos"),
    )
    ContentExamType.INMERSIVO -> ImportTypeRule(
        label = "Inmersiva",
        subtitle = "Con explicación",
        admits = listOf("Enunciado", "Opciones A–D", "Respuesta correcta", "Tema", "Explicación", "Fuente"),
        // Honestidad: una Inmersiva PUEDE tener recursos como pregunta, pero la
        // importación no los trae. Se dice aquí para que nadie prepare un PDF con
        // códigos de audio esperando que se vinculen.
        rejects = listOf("Recursos: hay que vincularlos después, desde Preguntas"),
    )
}

data class ImportTypeChoice(
    val type: ContentExamType,
    val rule: ImportTypeRule,
    val selected: Boolean,
) {
    val selectable: Boolean get() = !selected
}

fun importTypeChoices(selected: ContentExamType): List<ImportTypeChoice> =
    ContentExamType.entries.map { ImportTypeChoice(it, importTypeRule(it), it == selected) }

/** El estado va en texto, no sólo en color. */
fun importTypeChoiceDescription(choice: ImportTypeChoice): String =
    "${choice.rule.label}. ${choice.rule.subtitle}. ${if (choice.selected) "Elegido." else "No elegido."}"

// ---------------------------------------------------------------------------
// Etapas del flujo
// ---------------------------------------------------------------------------

enum class ImportStepState { PENDING, ACTIVE, DONE, BLOCKED }

data class ImportStageStep(
    val index: Int,
    val label: String,
    val detail: String,
    val state: ImportStepState,
)

/**
 * Dónde está el administrador.
 *
 * Cuatro etapas y no cinco: «Validación» y «Revisión» son la misma pantalla —el
 * servidor ya devuelve las filas clasificadas y lo que se hace con ellas es
 * revisarlas—. Separarlas en el indicador daría un paso que nadie puede recorrer.
 */
fun importStages(state: ImportState): List<ImportStageStep> {
    val batch = (state.activeBatch as? UiAsync.Success)?.value
    val processing = state.activeBatch is UiAsync.Loading || state.actionState is UiAsync.Loading
    val published = batch != null && importBatchIsPublished(batch.status)
    val hasFile = state.pickedPdfName.isNotBlank()

    return listOf(
        ImportStageStep(
            index = 1,
            label = "Tipo",
            detail = importTypeLabel(state.selectedType),
            state = ImportStepState.DONE,
        ),
        ImportStageStep(
            index = 2,
            label = "Archivo",
            detail = when {
                batch != null -> batch.fileName
                hasFile -> state.pickedPdfName
                else -> "Sin archivo"
            },
            state = when {
                batch != null -> ImportStepState.DONE
                hasFile -> ImportStepState.ACTIVE
                else -> ImportStepState.PENDING
            },
        ),
        ImportStageStep(
            index = 3,
            label = "Revisión",
            detail = when {
                batch != null -> "${batch.staging.size} filas"
                processing -> "Analizando…"
                else -> "Pendiente"
            },
            state = when {
                batch != null && !published -> ImportStepState.ACTIVE
                batch != null -> ImportStepState.DONE
                processing -> ImportStepState.ACTIVE
                else -> ImportStepState.PENDING
            },
        ),
        ImportStageStep(
            index = 4,
            label = "Publicación",
            detail = when {
                batch == null -> "Pendiente"
                else -> importBatchStatusLabel(batch.status)
            },
            state = when {
                batch == null -> ImportStepState.PENDING
                batch.status.equals("fallido", ignoreCase = true) -> ImportStepState.BLOCKED
                published -> ImportStepState.DONE
                else -> ImportStepState.PENDING
            },
        ),
    )
}

// ---------------------------------------------------------------------------
// El lote
// ---------------------------------------------------------------------------

/**
 * Cómo se nombra un lote.
 *
 * Por su archivo. **No existe código humano de lote** (`IMP-000042`): el modelo
 * sólo trae un identificador interno, y enseñarlo sería exactamente el UUID que
 * esta consola no pone delante de nadie.
 */
fun importBatchLabel(batch: ContentImportBatch): String =
    batch.fileName.trim().ifBlank { "Lote sin nombre de archivo" }

/**
 * El lote ya está en el examen. El vocabulario VIVO del lote es
 * `pendiente | parseado | revisado | importado | fallido`, y sólo `importado` es un
 * lote publicado: la publicación es atómica, así que no existe «publicado a medias».
 */
fun importBatchIsPublished(status: String): Boolean = status.trim().equals("importado", ignoreCase = true)

/** Sólo los estados que el backend manda de verdad. */
fun importBatchStatusLabel(status: String): String = when (status.trim().lowercase()) {
    "pendiente" -> "Pendiente"
    "parseado" -> "Pendiente de revisión"
    "revisado" -> "Revisado"
    "importado" -> "Publicado"
    "fallido" -> "Fallido"
    "" -> "Sin estado"
    else -> status
}

fun importBatchStatusTone(status: String): StatusTone = when (status.trim().lowercase()) {
    "importado" -> StatusTone.POSITIVE
    "fallido" -> StatusTone.NEGATIVE
    "parseado", "revisado" -> StatusTone.ATTENTION
    "pendiente" -> StatusTone.NEUTRAL
    else -> StatusTone.UNKNOWN
}

/** El tipo del lote, con vocabulario de pregunta. */
fun importBatchTypeLabel(batch: ContentImportBatch): String =
    importTypeLabel(importTypeFromWire(batch.examType))

// ---------------------------------------------------------------------------
// Filas
// ---------------------------------------------------------------------------

fun importRowToneLabel(tone: ReviewFilter): String = when (tone) {
    ReviewFilter.LISTA -> "Lista"
    ReviewFilter.ADVERTENCIA -> "Advertencia"
    ReviewFilter.OMITIDA -> "Omitida"
    ReviewFilter.IMPORTADA -> "Importada"
    ReviewFilter.ERROR -> "Error"
    ReviewFilter.DUPLICADA -> "Duplicada"
    ReviewFilter.ALL -> "Todas"
}

fun importRowToneStatusTone(tone: ReviewFilter): StatusTone = when (tone) {
    ReviewFilter.LISTA -> StatusTone.POSITIVE
    ReviewFilter.ADVERTENCIA -> StatusTone.ATTENTION
    ReviewFilter.OMITIDA -> StatusTone.NEUTRAL
    // Tono aparte: ya está en el examen. No es un error ni una pendiente.
    ReviewFilter.IMPORTADA -> StatusTone.POSITIVE
    ReviewFilter.ERROR -> StatusTone.NEGATIVE
    ReviewFilter.DUPLICADA -> StatusTone.ATTENTION
    ReviewFilter.ALL -> StatusTone.UNKNOWN
}

/**
 * El motivo por el que una fila no está lista.
 *
 * El texto lo escribe el servidor: aquí NO se reescribe ni se traduce a una
 * frase inventada, porque una traducción libre puede decir algo distinto de lo
 * que pasó. Lo único que se añade es el caso en que el servidor marca error y no
 * explica nada — callarlo dejaría una fila roja sin motivo — y la salida que tiene
 * cada duplicada, para que no sea un callejón sin salida.
 */
fun importRowProblem(item: ContentImportStagingItem): String? {
    val tone = stagingTone(item)
    val serverMessage = item.parseError?.trim().orEmpty()
    val duplicate = item.duplicate
    return when {
        tone == ReviewFilter.IMPORTADA -> null
        serverMessage.isNotBlank() -> serverMessage
        tone == ReviewFilter.DUPLICADA && duplicate?.origin.equals("banco", ignoreCase = true) ->
            "Ya existe en el banco${duplicate?.questionCode?.takeIf { it.isNotBlank() }?.let { " como $it" }.orEmpty()}: " +
                "omítela, vincúlala a esa pregunta o edítala."
        tone == ReviewFilter.DUPLICADA && duplicate?.origin.equals("lote", ignoreCase = true) ->
            "El PDF trae esta pregunta dos veces: omite una de las dos."
        tone == ReviewFilter.DUPLICADA -> "El servidor la ha marcado como posible duplicada de una pregunta que ya existe."
        tone == ReviewFilter.ADVERTENCIA -> "Aviso del análisis: apruébala u omítela antes de publicar."
        tone == ReviewFilter.OMITIDA -> "Marcada para omitir: no se publicará."
        tone == ReviewFilter.ERROR -> "El servidor la ha rechazado sin explicar el motivo."
        else -> null
    }
}

// ---------------------------------------------------------------------------
// Anulada por el tribunal y vínculo con una pregunta existente
// ---------------------------------------------------------------------------

/**
 * La etiqueta corta de la lista de filas. Es la MISMA etiqueta («Anulada») y el MISMO tono
 * ([IMPORT_ANNULLED_TONE], atención) que la pregunta anulada de la lista del examen
 * (`EXAM_QUESTION_ANNULLED_LABEL` en features/exams): el administrador ve lo mismo en las dos pantallas.
 */
const val IMPORT_ANNULLED_CHIP: String = "Anulada"

/** Tono del chip de anulada: atención, igual que en la lista de preguntas del examen. */
val IMPORT_ANNULLED_TONE: StatusTone = StatusTone.ATTENTION

/** Lo que enseña el interruptor de la fila. */
const val IMPORT_ANNULLED_LABEL: String = "Anulada por el tribunal"

/**
 * La anulación de una fila dicha en una frase, o `null` si no va anulada. Es un dato de
 * la pregunta EN EL EXAMEN y se lee aparte del estado de parseo.
 */
fun importRowAnnulmentNote(item: ContentImportStagingItem): String? {
    if (!item.annulled) return null
    val reason = item.annulmentReason?.trim().orEmpty()
    return if (reason.isBlank()) IMPORT_ANNULLED_LABEL else "$IMPORT_ANNULLED_LABEL: $reason"
}

/** «Vinculada a PREG-0001», o `null` si la fila no va vinculada. */
fun importRowLinkLabel(item: ContentImportStagingItem): String? {
    if (!stagingIsLinked(item)) return null
    val code = item.duplicate?.takeIf { it.questionId == item.linkQuestionId }?.questionCode?.takeIf { it.isNotBlank() }
    return if (code != null) "Vinculada a $code" else "Vinculada a una pregunta existente"
}

/**
 * Qué se puede hacer con una fila. La pantalla pinta esto y no decide nada por su cuenta.
 *
 * Una duplicada NO es un callejón sin salida: se omite, se vincula a la existente (sólo
 * si el duplicado viene del banco y trae la pregunta) o se edita. Una duplicada de LOTE
 * sólo ofrece omitir: no hay pregunta existente con la que vincular.
 */
data class ImportRowActions(
    /** La fila y sus campos se pueden tocar. `false` con el lote publicado o la fila importada. */
    val editable: Boolean,
    val canSkip: Boolean,
    val canApprove: Boolean,
    /** «Vincular a existente (PREG-…)», o `null` cuando no procede. */
    val linkLabel: String?,
    /** La pregunta con la que vincularía, para el intent. */
    val linkQuestionId: String?,
    val canUnlink: Boolean,
)

fun importRowActions(item: ContentImportStagingItem, batchPublished: Boolean): ImportRowActions {
    val tone = stagingTone(item)
    val editable = !batchPublished && tone != ReviewFilter.IMPORTADA
    val duplicate = item.duplicate
    val linkTarget = duplicate?.takeIf {
        it.origin.equals("banco", ignoreCase = true) && !it.questionId.isNullOrBlank()
    }
    val canLink = editable && linkTarget != null && !stagingIsLinked(item) && tone != ReviewFilter.OMITIDA
    return ImportRowActions(
        editable = editable,
        canSkip = editable && tone != ReviewFilter.OMITIDA,
        canApprove = editable && tone != ReviewFilter.DUPLICADA,
        linkLabel = if (canLink) {
            val code = linkTarget.questionCode.takeIf { it.isNotBlank() }
            if (code != null) "Vincular a existente ($code)" else "Vincular a existente"
        } else {
            null
        },
        linkQuestionId = if (canLink) linkTarget.questionId else null,
        canUnlink = editable && stagingIsLinked(item),
    )
}

/** Lo que se lee de una fila cuando no hay enunciado. */
fun importRowTitle(item: ContentImportStagingItem): String =
    item.title.trim().ifBlank { "Sin enunciado" }

/**
 * El tema, tal y como llega: TEXTO LIBRE.
 *
 * No se presenta como `TEMA-001` ni se valida contra una taxonomía, porque el
 * contrato no manda un código y no hay catálogo de temas contra el que resolver.
 */
fun importRowTopic(item: ContentImportStagingItem): String =
    item.tagText.trim().ifBlank { "Sin tema" }

fun importRowCorrectAnswer(item: ContentImportStagingItem): String =
    item.correctAnswer.trim().uppercase().ifBlank { "Sin marcar" }

/** A, B, C o D. Nada más, y sin corregir «1» por «A». */
val IMPORT_VALID_ANSWERS: Set<String> = setOf("A", "B", "C", "D")

fun importAnswerIsValid(value: String): Boolean = value.trim().uppercase() in IMPORT_VALID_ANSWERS

// ---------------------------------------------------------------------------
// Publicación
// ---------------------------------------------------------------------------

/**
 * Tope de preguntas por examen. Lo impone la base (`EXAM_QUESTION_LIMIT_REACHED`); aquí se espeja para avisar antes.
 *
 * La RPC cuenta como relación nueva TODA fila lista/aprobada sin relación previa con el examen,
 * también las vinculadas a una pregunta del banco. El cliente no sabe cuáles de esas vinculadas ya
 * están en el examen, así que el preflight cuenta TODAS las listas (conservador). El servidor decide
 * la cifra exacta.
 */
const val IMPORT_EXAM_QUESTION_LIMIT: Int = 200

/** Qué comprueba el preflight. Cada uno espeja una regla de la RPC de publicación. */
enum class ImportPreflightKind {
    PUBLISHING,
    ALREADY_PUBLISHED,
    BATCH_PENDING,
    EMPTY_BATCH,
    ROW_ERRORS,
    WARNINGS_PENDING,
    DUPLICATES_UNRESOLVED,
    ROWS_INCOMPLETE,
    REASONING_MISSING,
    TYPE_MISMATCH,
    EXAM_LIMIT,
    NOTHING_READY,
    EXAM_PUBLISHED,

    /** El mismo contenido ya se importó en este examen (huella igual): publicar daría `IMPORT_LOT_DUPLICATE`. */
    DUPLICATE_LOT,

    /** Hay filas RESERVA y el examen no es oficial (`IMPORT_RESERVE_REQUIRES_OFFICIAL_EXAM`). */
    RESERVES_NEED_OFFICIAL,

    /** Solo hay reservas y el examen aún no tiene principales: las reservas se añaden DESPUÉS de las principales. */
    RESERVES_WITHOUT_MAIN,

    /** Aviso: las reservas se numeran y se añaden tras las principales. */
    RESERVES_NOTE,

    /** Hay filas anuladas sin el motivo del tribunal (decisión 2026-10-20; el servidor responde ANNULMENT_REASON_REQUIRED). */
    ANNULMENT_REASON_MISSING,

    /** La explicación de alguna fila lleva marcado que el servidor rechazaría (gramática de razonados, FA38). */
    MARKUP_INVALID,
}

/** Un motivo concreto. [blocking] `false` = aviso: se puede publicar, pero conviene saberlo antes. */
data class ImportPreflightIssue(
    val kind: ImportPreflightKind,
    val message: String,
    val blocking: Boolean = true,
)

/**
 * Lo que la UI sabe ANTES de pulsar «Publicar», con motivos concretos.
 *
 * Espeja las reglas de la RPC de publicación —filas en error o con advertencia sin
 * resolver, duplicadas sin resolver, filas incompletas, razonamiento obligatorio en
 * Razonada/Inmersiva, tipo de lote distinto del del examen, tope de 200 preguntas—
 * pero **no copia su SQL**: es una previsión para no mandar a ciegas. La autoridad es
 * el servidor, que vuelve a comprobarlo todo dentro de la transacción; si discrepa,
 * manda su error.
 *
 * [requiresPublishedExamConfirmation]: el examen destino está publicado y hay algo que
 * publicar, así que las preguntas se verán al instante y se pide confirmación (KI3-3).
 */
data class ImportPublishPreflight(
    val issues: List<ImportPreflightIssue>,
    /** Filas que se publicarían: las listas y aprobadas, sin contar las ya importadas. */
    val toPublish: Int,
    /** De ésas, las que se vinculan a una pregunta que ya existe. */
    val linked: Int,
    /** De ésas, las que se publican como anuladas por el tribunal. */
    val annulled: Int,
    /** Preguntas que tendría el examen después de publicar, si se conoce el actual. */
    val examTotalAfter: Int?,
    val requiresPublishedExamConfirmation: Boolean,
    /** De las que se publicarían, las que van como RESERVA. */
    val reserves: Int = 0,
) {
    val blockers: List<ImportPreflightIssue> get() = issues.filter { it.blocking }
    val warnings: List<ImportPreflightIssue> get() = issues.filterNot { it.blocking }
    val canPublish: Boolean get() = blockers.isEmpty()
}

private fun plural(count: Int, singular: String, plural: String): String =
    if (count == 1) "1 $singular" else "$count $plural"

/**
 * Decide si el lote se puede publicar y explica por qué no.
 *
 * [exam] es el examen destino cuando se conoce; sin él se omiten sólo las reglas que lo
 * necesitan (tipo, tope, examen publicado) y el resto se aplica igual.
 * [busy] = hay una operación en vuelo.
 */
fun importPublishPreflight(
    batch: ContentImportBatch,
    exam: Exam? = null,
    busy: Boolean = false,
): ImportPublishPreflight {
    val issues = mutableListOf<ImportPreflightIssue>()
    fun blocker(kind: ImportPreflightKind, message: String) {
        issues.add(ImportPreflightIssue(kind, message, blocking = true))
    }

    // Una duplicada de LOTE cuya gemela ya está omitida o importada no estorba: la RPC sólo
    // compara filas ok/aprobada entre sí. Se resuelve a su estado real, no a «Duplicada».
    val byId = batch.staging.associateBy { it.id }
    fun tone(row: ContentImportStagingItem): ReviewFilter {
        val plain = stagingTone(row)
        if (plain != ReviewFilter.DUPLICADA) return plain
        val twin = row.duplicate?.takeIf { it.origin.equals("lote", ignoreCase = true) }
            ?.stagingOriginalId?.let(byId::get)
        val twinGone = twin != null && stagingTone(twin) in setOf(ReviewFilter.OMITIDA, ReviewFilter.IMPORTADA)
        val decided = row.parseStatus.trim().lowercase() in setOf("ok", "aprobada")
        return if (twinGone && decided) ReviewFilter.LISTA else plain
    }

    // Sólo cuentan las filas que aún no están en el examen.
    val pending = batch.staging.filter { stagingTone(it) != ReviewFilter.IMPORTADA && it.questionId.isNullOrBlank() }
    val questionType = importTypeFromWire(batch.examType)
    val reasoningRequired = questionType != ContentExamType.RAFAGA

    val ready = pending.filter { tone(it) == ReviewFilter.LISTA }
    val linked = ready.count { stagingIsLinked(it) }
    // TOPE DE 200 — espeja la RPC, que cuenta como relación NUEVA toda fila ok/aprobada que aún
    // no tenga relación con el examen, INCLUIDAS las vinculadas a una pregunta del banco. El
    // cliente no sabe cuáles de esas vinculadas ya están en el examen, así que cuenta TODAS las
    // listas (conservador): a lo sumo avisa de más, nunca deja pasar lo que el servidor rechazaría.
    // El servidor decide la cifra exacta.
    val fresh = ready.size
    val annulled = ready.count { it.annulled }
    val published = importBatchIsPublished(batch.status)

    if (busy) blocker(ImportPreflightKind.PUBLISHING, "Publicando el lote…")
    if (published) {
        blocker(ImportPreflightKind.ALREADY_PUBLISHED, "Este lote ya se ha publicado.")
    } else {
        batch.duplicateOfBatchId?.let { earlier ->
            blocker(
                ImportPreflightKind.DUPLICATE_LOT,
                "Este contenido ya se importó en este examen (lote $earlier): publicarlo otra vez duplicaría las preguntas. " +
                    "Ábrelo desde el historial o sube un fichero distinto.",
            )
        }
        // La RPC sólo publica lotes `parseado`, `revisado` o `fallido` (reintento); `pendiente`
        // (aún sin parsear) lo rechaza con IMPORT_LOTE_STATE_INVALID.
        if (batch.status.trim().equals("pendiente", ignoreCase = true)) {
            blocker(
                ImportPreflightKind.BATCH_PENDING,
                "El lote sigue pendiente de procesar: no se puede publicar hasta que termine el análisis.",
            )
        }
        if (batch.staging.isEmpty()) {
            blocker(ImportPreflightKind.EMPTY_BATCH, "El lote no tiene ninguna fila que publicar.")
        }

        val errors = pending.count { tone(it) == ReviewFilter.ERROR }
        when {
            errors == 1 -> blocker(ImportPreflightKind.ROW_ERRORS, "Corrige el error antes de publicar.")
            errors > 1 -> blocker(ImportPreflightKind.ROW_ERRORS, "Corrige los $errors errores antes de publicar.")
        }

        // Decisión del propietario 2026-10-20: una pregunta no se publica anulada sin el motivo del tribunal.
        val annulledWithoutReason = pending.filter { tone(it) != ReviewFilter.OMITIDA && it.annulled && it.annulmentReason.isNullOrBlank() }
        if (annulledWithoutReason.isNotEmpty()) {
            blocker(
                ImportPreflightKind.ANNULMENT_REASON_MISSING,
                plural(annulledWithoutReason.size, "pregunta anulada no tiene", "preguntas anuladas no tienen") +
                    " el motivo del tribunal: indícalo en la revisión antes de publicar.",
            )
        }

        val warnings = pending.count { tone(it) == ReviewFilter.ADVERTENCIA }
        if (warnings > 0) {
            blocker(
                ImportPreflightKind.WARNINGS_PENDING,
                "${plural(warnings, "fila con advertencia sin resolver", "filas con advertencia sin resolver")}: " +
                    "apruébalas u omítelas.",
            )
        }

        val duplicates = pending.count { tone(it) == ReviewFilter.DUPLICADA }
        if (duplicates > 0) {
            blocker(
                ImportPreflightKind.DUPLICATES_UNRESOLVED,
                "${plural(duplicates, "duplicada sin resolver", "duplicadas sin resolver")}: " +
                    "omítelas o vincúlalas a la pregunta existente.",
            )
        }

        // Completitud: sólo las que van a crear pregunta (las vinculadas usan la existente).
        val creating = ready.filter { !stagingIsLinked(it) }
        val incomplete = creating.count { stagingCompletenessProblem(it) != null }
        if (incomplete > 0) {
            blocker(
                ImportPreflightKind.ROWS_INCOMPLETE,
                "${plural(incomplete, "fila incompleta", "filas incompletas")} (falta enunciado, opciones o respuesta): " +
                    "complétalas u omítelas.",
            )
        }
        // FA38: el servidor vuelve a analizar el marcado de cada explicación y rechaza la fila si es inválido; se avisa antes.
        val badMarkup = creating.count { it.reasoning.isNotBlank() && RazonadoMarkup.parse(it.reasoning) is RazonadoMarkup.Result.Rejected }
        if (badMarkup > 0) {
            blocker(
                ImportPreflightKind.MARKUP_INVALID,
                "${plural(badMarkup, "fila tiene", "filas tienen")} marcado de explicación no válido: corrígelo (el editor «Marcado de razonados» dice qué falla).",
            )
        }
        if (reasoningRequired) {
            val noReasoning = creating.count { stagingCompletenessProblem(it) == null && it.reasoning.isBlank() }
            if (noReasoning > 0) {
                blocker(
                    ImportPreflightKind.REASONING_MISSING,
                    "${plural(noReasoning, "fila", "filas")} sin explicación: " +
                        "${importTypeLabel(questionType)} exige razonamiento en cada pregunta.",
                )
            }
        }

        if (exam != null && importTypeFromWire(exam.typeExam) != questionType) {
            blocker(
                ImportPreflightKind.TYPE_MISMATCH,
                "El lote es de tipo ${importTypeLabel(questionType)} y el examen destino es " +
                    "${importTypeLabel(importTypeFromWire(exam.typeExam))}: no coinciden.",
            )
        }
        val reserveRows = ready.count { it.isReserve }
        if (reserveRows > 0) {
            if (exam != null && !exam.isOfficial) {
                blocker(
                    ImportPreflightKind.RESERVES_NEED_OFFICIAL,
                    "${plural(reserveRows, "fila va como reserva", "filas van como reserva")} y el examen no es oficial: " +
                        "márcalo como oficial o pasa esas filas a principales.",
                )
            }
            if (exam != null && exam.questionCount == 0 && ready.size == reserveRows) {
                blocker(
                    ImportPreflightKind.RESERVES_WITHOUT_MAIN,
                    "Las reservas se añaden después de las principales y este examen aún no tiene ninguna: importa primero las principales.",
                )
            }
            issues.add(
                ImportPreflightIssue(
                    ImportPreflightKind.RESERVES_NOTE,
                    "${plural(reserveRows, "reserva", "reservas")}: se añaden después de las principales, numeradas por el orden del fichero. " +
                        "Solo puntúan si se anula alguna principal.",
                    blocking = false,
                ),
            )
        }
        if (exam != null && fresh > 0 && exam.questionCount + fresh > IMPORT_EXAM_QUESTION_LIMIT) {
            val total = exam.questionCount + fresh
            blocker(
                ImportPreflightKind.EXAM_LIMIT,
                "El examen ya tiene ${exam.questionCount} preguntas y este lote añade $fresh: serían $total y el " +
                    "máximo es $IMPORT_EXAM_QUESTION_LIMIT. Omite al menos ${total - IMPORT_EXAM_QUESTION_LIMIT}.",
            )
        }

        if (issues.none { it.blocking } && ready.isEmpty()) {
            blocker(
                ImportPreflightKind.NOTHING_READY,
                "No hay ninguna fila lista: todas están omitidas o duplicadas.",
            )
        }
    }

    val confirmation = exam != null && exam.status == ExamStatus.PUBLISHED && ready.isNotEmpty() && !published
    if (confirmation) {
        issues.add(
            ImportPreflightIssue(
                ImportPreflightKind.EXAM_PUBLISHED,
                "El examen está publicado: las preguntas se verán al instante en la app. " +
                    "Se te pedirá confirmación antes de publicar.",
                blocking = false,
            ),
        )
    }

    return ImportPublishPreflight(
        issues = issues,
        toPublish = ready.size,
        linked = linked,
        annulled = annulled,
        examTotalAfter = exam?.let { it.questionCount + fresh },
        requiresPublishedExamConfirmation = confirmation,
        reserves = ready.count { it.isReserve },
    )
}

/** Si se puede publicar, y si no, por qué. El motivo se enseña junto al botón. */
data class ImportPublishGate(
    val enabled: Boolean,
    val reason: String,
    /** Todos los motivos concretos (bloqueos y avisos), para listarlos bajo el botón. */
    val issues: List<ImportPreflightIssue> = emptyList(),
    val requiresPublishedExamConfirmation: Boolean = false,
)

/** El examen destino del lote, si la lista de exámenes está cargada y lo contiene. */
fun ImportState.examOf(batch: ContentImportBatch): Exam? =
    (exams as? UiAsync.Success)?.value?.firstOrNull { it.id == batch.examId }

/**
 * Cuándo se deja publicar: lo que diga [importPublishPreflight].
 *
 * Con errores, advertencias o duplicadas sin resolver, no. Con el lote vacío o ya
 * publicado, tampoco. Las filas omitidas **no** bloquean: omitir es una decisión del
 * administrador, no un fallo.
 */
fun importPublishGate(state: ImportState, batch: ContentImportBatch): ImportPublishGate {
    val preflight = importPublishPreflight(
        batch = batch,
        exam = state.examOf(batch),
        busy = state.actionState is UiAsync.Loading,
    )
    val first = preflight.blockers.firstOrNull()
    if (first != null) {
        return ImportPublishGate(false, first.message, preflight.issues, preflight.requiresPublishedExamConfirmation)
    }
    val n = preflight.toPublish
    val what = if (n == 1) "Se publicará 1 pregunta" else "Se publicarán $n preguntas"
    val extras = buildList {
        if (preflight.linked > 0) add(plural(preflight.linked, "vinculada a una existente", "vinculadas a existentes"))
        if (preflight.annulled > 0) add(plural(preflight.annulled, "anulada por el tribunal", "anuladas por el tribunal"))
        if (preflight.reserves > 0) add(plural(preflight.reserves, "como reserva", "como reservas"))
    }
    val reason = "$what en el examen del lote" + if (extras.isEmpty()) "." else " (${extras.joinToString(", ")})."
    return ImportPublishGate(true, reason, preflight.issues, preflight.requiresPublishedExamConfirmation)
}

/**
 * La publicación ES atómica: una sola llamada en la base, todo o nada. Si algo falla,
 * el lote no cambia y no queda ninguna pregunta a medias.
 */
const val IMPORT_ATOMIC_NOTICE: String =
    "La publicación es atómica: o se publica todo el lote o no cambia nada."

// ---------------------------------------------------------------------------
// Historial
// ---------------------------------------------------------------------------

/** Las cifras por estado de un lote, cada una `null` cuando no se conoce. */
data class ImportBatchCounts(
    val ready: Int?,
    val errors: Int?,
    val warnings: Int?,
    val skipped: Int?,
    val imported: Int?,
)

/**
 * De dónde salen las cifras de un lote, por este orden:
 *
 *  1. El `resumen` del servidor, cuando viene.
 *  2. El staging cargado, si trae filas (un lote abierto).
 *  3. Nada. El listado de lotes no trae filas: contar un staging vacío daba «0 listas ·
 *     0 con error» en un lote que tenía cientos. Sin fuente no se inventa ningún número.
 */
fun importBatchCounts(batch: ContentImportBatch): ImportBatchCounts? {
    batch.summary?.let {
        return ImportBatchCounts(it.ready, it.withErrors, it.warnings, it.skipped, it.imported)
    }
    if (batch.staging.isEmpty()) return null
    val metrics = batch.metrics()
    return ImportBatchCounts(metrics.ready, metrics.errors, metrics.warnings, metrics.skipped, metrics.imported)
}

/** La línea de recuentos del historial. Sin fuente, un guion. */
fun importBatchHistoryLine(batch: ContentImportBatch): String {
    val counts = importBatchCounts(batch)
    val parts = mutableListOf(importBatchTypeLabel(batch), "${batch.detectedCount} detectadas")
    if (counts == null) {
        parts += "— listas"
        parts += "— con error"
    } else {
        counts.ready?.let { parts += "$it listas" }
        counts.errors?.let { parts += "$it con error" }
        counts.warnings?.takeIf { it > 0 }?.let { parts += "$it con advertencia" }
        counts.skipped?.takeIf { it > 0 }?.let { parts += "$it omitidas" }
        counts.imported?.takeIf { it > 0 }?.let { parts += "$it importadas" }
    }
    return parts.joinToString(" · ")
}

// ---------------------------------------------------------------------------
// Guía de códigos — lo que el PDF usa DE VERDAD
// ---------------------------------------------------------------------------

data class ImportCodeEntry(val field: String, val example: String, val note: String)

/**
 * Qué campos se esperan en el PDF.
 *
 * El panel anterior anunciaba `ID_GLOBAL`, `ID_EXAMEN`, `TEMA`, `ID_AUDIO`,
 * `ID_VIDEO` e `ID_IMAGEN` con ejemplos de código humano. **Ninguno de esos seis
 * llega al staging**: el examen se elige aquí, el tema viaja como texto libre y
 * los recursos no existen en el contrato de importación. Prometerlos hacía que se
 * prepararan PDF con columnas que el servidor ignora.
 */
fun importCodeGuide(type: ContentExamType): List<ImportCodeEntry> {
    val base = listOf(
        ImportCodeEntry("Enunciado", "¿Cuál de las siguientes…?", "El texto de la pregunta."),
        ImportCodeEntry("Opciones", "A, B, C y D", "Las cuatro deben tener contenido."),
        ImportCodeEntry("Correcta", "A", "Sólo A, B, C o D."),
        ImportCodeEntry("Tema", "Texto libre", "Llega tal cual: no se resuelve contra un catálogo."),
    )
    val reasoned = listOf(
        ImportCodeEntry("Explicación", "Porque la norma…", "La justificación que se enseña tras responder."),
        ImportCodeEntry("Fuente", "BOE núm. 122", "Referencia normativa o bibliográfica."),
    )
    return when (type) {
        ContentExamType.RAFAGA -> base
        ContentExamType.RAZONADO, ContentExamType.INMERSIVO -> base + reasoned
    }
}

/** Lo que el PDF NO lleva, y conviene decir antes de preparar el archivo. */
const val IMPORT_NO_CODES_NOTICE: String =
    "El examen destino se elige aquí, no en el PDF. La importación no acepta todavía códigos de examen, " +
        "de tema ni de recursos dentro del archivo."

/** PDF estructurado, no escaneado: no hay OCR y no se finge que lo haya. */
const val IMPORT_PDF_REQUIREMENT: String =
    "El PDF debe contener texto seleccionable. Un documento escaneado no se puede leer."

// ---------------------------------------------------------------------------
// Matriz de honestidad — la UI se pinta desde aquí
// ---------------------------------------------------------------------------

enum class ImportCapabilityLevel {
    /** Existe y funciona. */
    REAL,

    /** Existe con una limitación que la UI dice. */
    PARTIAL,

    /** La operación existe en el contrato pero hoy no es segura. */
    BLOCKED,

    /** No existe en el contrato: no hay nada que llamar. */
    NOT_FOUND,
}

data class ImportCapability(
    val name: String,
    val level: ImportCapabilityLevel,
    val note: String,
)

fun importCapabilities(): List<ImportCapability> = listOf(
    ImportCapability("Seleccionar tipo", ImportCapabilityLevel.REAL, "Ráfaga, Razonada e Inmersiva."),
    ImportCapability("Seleccionar examen destino", ImportCapabilityLevel.REAL, "Uno por lote, filtrado por tipo."),
    ImportCapability("Seleccionar PDF", ImportCapabilityLevel.REAL, "Diálogo de archivo del sistema en escritorio."),
    ImportCapability("Arrastrar y soltar", ImportCapabilityLevel.NOT_FOUND, "No hay zona de arrastre implementada."),
    ImportCapability("Parsear PDF", ImportCapabilityLevel.PARTIAL, "Lo hace el servidor al subir; el cliente no ve etapas."),
    ImportCapability("Validar filas", ImportCapabilityLevel.PARTIAL, "La clasificación llega del servidor; el cliente sólo espeja sus reglas antes de publicar."),
    ImportCapability("Resolver examen por fila", ImportCapabilityLevel.NOT_FOUND, "El examen es del lote, no de la fila."),
    ImportCapability("Resolver tema", ImportCapabilityLevel.NOT_FOUND, "Llega como texto libre; no hay catálogo."),
    // IMMERSIVE_RESOURCE_IMPORT = NOT_IMPLEMENTED: la inmersiva se importa SIN recursos.
    ImportCapability("Resolver recursos", ImportCapabilityLevel.NOT_FOUND, "IMMERSIVE_RESOURCE_IMPORT = $IMMERSIVE_RESOURCE_IMPORT: el staging no tiene campos de recurso; la inmersiva se importa sin ellos."),
    ImportCapability("Revisar staging", ImportCapabilityLevel.REAL, "Filtros, búsqueda y detalle por fila."),
    ImportCapability("Editar staging", ImportCapabilityLevel.REAL, "Guarda en el lote; valida las cuatro opciones y A–D."),
    ImportCapability("Detectar duplicado", ImportCapabilityLevel.PARTIAL, "El servidor las marca; el cliente sólo las agrupa."),
    ImportCapability("Ver la pregunta existente", ImportCapabilityLevel.NOT_FOUND, "No llega ninguna referencia navegable."),
    ImportCapability("Omitir duplicado", ImportCapabilityLevel.REAL, "Marca la fila como omitida; no borra nada ni exige que esté completa."),
    ImportCapability("Vincular a existente", ImportCapabilityLevel.REAL, "Una duplicada del banco se aprueba vinculada a la pregunta existente; no crea otra."),
    ImportCapability("Marcar anulada por el tribunal", ImportCapabilityLevel.REAL, "Interruptor y motivo por fila; se publica en la relación con el examen."),
    ImportCapability("Publicar lote", ImportCapabilityLevel.REAL, "Acción explícita, bloqueada mientras el preflight encuentre motivos."),
    ImportCapability("Publicar atómicamente", ImportCapabilityLevel.REAL, "Todo o nada: una sola transacción en la base; si algo falla, el lote no cambia."),
    ImportCapability("Descargar plantilla", ImportCapabilityLevel.NOT_FOUND, "No existe ninguna plantilla que descargar."),
    ImportCapability("Ver importaciones recientes", ImportCapabilityLevel.REAL, "Listado de lotes con sus recuentos."),
    ImportCapability("Descartar lote", ImportCapabilityLevel.NOT_FOUND, "No hay operación de borrado de lote."),
)

fun importCapability(name: String): ImportCapability =
    importCapabilities().firstOrNull { it.name == name }
        ?: error("Capacidad no declarada en la matriz: $name")

/**
 * La Inmersiva se importa SIN recursos (audio, vídeo, imagen): el staging no tiene dónde
 * ponerlos y el PDF no los referencia. Se vinculan después, desde Preguntas. No está
 * implementado y esta pantalla no lo finge.
 */
const val IMMERSIVE_RESOURCE_IMPORT: String = "NOT_IMPLEMENTED"

/** Lo que la UI dice de la Inmersiva importada. */
const val IMMERSIVE_RESOURCE_IMPORT_NOTICE: String =
    "La Inmersiva se importa sin recursos: el audio, el vídeo y las imágenes se vinculan después, desde Preguntas."

/** Plantilla: no hay ninguna, y el botón lo dice en vez de aparentar un enlace. */
const val IMPORT_TEMPLATE_BLOCKED: String = "Plantilla todavía no disponible"
