package com.oposicontrol.admin.features.imports.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.StatusTone
import com.oposicontrol.admin.domain.model.ContentExamType
import com.oposicontrol.admin.domain.model.ContentImportBatch
import com.oposicontrol.admin.domain.model.ContentImportStagingItem

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
 *  - **Publicación atómica.** `publishImportBatch` puede devolver `parcial`.
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
                batch.status.equals("parcial", ignoreCase = true) -> ImportStepState.BLOCKED
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

fun importBatchIsPublished(status: String): Boolean =
    status.equals("publicado", ignoreCase = true) || status.equals("parcial", ignoreCase = true)

/** Sólo los estados que el backend manda de verdad. */
fun importBatchStatusLabel(status: String): String = when (status.trim().lowercase()) {
    "parseado" -> "Pendiente de revisión"
    "publicado" -> "Publicado"
    "parcial" -> "Publicado a medias"
    "" -> "Sin estado"
    else -> status
}

fun importBatchStatusTone(status: String): StatusTone = when (status.trim().lowercase()) {
    "publicado" -> StatusTone.POSITIVE
    "parcial" -> StatusTone.NEGATIVE
    "parseado" -> StatusTone.ATTENTION
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
    ReviewFilter.ADVERTENCIA -> "Omitida"
    ReviewFilter.ERROR -> "Error"
    ReviewFilter.DUPLICADA -> "Duplicada"
    ReviewFilter.ALL -> "Todas"
}

fun importRowToneStatusTone(tone: ReviewFilter): StatusTone = when (tone) {
    ReviewFilter.LISTA -> StatusTone.POSITIVE
    ReviewFilter.ADVERTENCIA -> StatusTone.NEUTRAL
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
 * explica nada — callarlo dejaría una fila roja sin motivo.
 */
fun importRowProblem(item: ContentImportStagingItem): String? {
    val tone = stagingTone(item)
    val serverMessage = item.parseError?.trim().orEmpty()
    return when {
        serverMessage.isNotBlank() -> serverMessage
        tone == ReviewFilter.DUPLICADA -> "El servidor la ha marcado como posible duplicada de una pregunta que ya existe."
        tone == ReviewFilter.ADVERTENCIA -> "Marcada para omitir: no se publicará."
        tone == ReviewFilter.ERROR -> "El servidor la ha rechazado sin explicar el motivo."
        else -> null
    }
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

/** Si se puede publicar, y si no, por qué. El motivo se enseña junto al botón. */
data class ImportPublishGate(val enabled: Boolean, val reason: String)

/**
 * Cuándo se deja publicar.
 *
 * Con errores, no. Con el lote vacío, tampoco. Y ya publicado, no se vuelve a
 * publicar. Los avisos (filas omitidas) **no** bloquean: omitir es una decisión
 * del administrador, no un fallo.
 */
fun importPublishGate(state: ImportState, batch: ContentImportBatch): ImportPublishGate {
    val metrics = batch.metrics()
    return when {
        state.actionState is UiAsync.Loading ->
            ImportPublishGate(false, "Publicando el lote…")
        importBatchIsPublished(batch.status) ->
            ImportPublishGate(false, "Este lote ya se ha publicado.")
        batch.staging.isEmpty() ->
            ImportPublishGate(false, "El lote no tiene ninguna fila que publicar.")
        metrics.errors == 1 ->
            ImportPublishGate(false, "Corrige el error antes de publicar.")
        metrics.errors > 1 ->
            ImportPublishGate(false, "Corrige los ${metrics.errors} errores antes de publicar.")
        metrics.ready == 0 ->
            ImportPublishGate(false, "No hay ninguna fila lista: todas están omitidas o duplicadas.")
        metrics.ready == 1 ->
            ImportPublishGate(true, "Se publicará 1 pregunta en el examen del lote.")
        else ->
            ImportPublishGate(true, "Se publicarán ${metrics.ready} preguntas en el examen del lote.")
    }
}

/**
 * La atomicidad NO está.
 *
 * `publishImportBatch` puede devolver `parcial`, y el propio ViewModel lo dice:
 * `content_import_atomicity_candidate_v1` sigue sin aplicar. Publicar puede dejar
 * parte del lote dentro. Se avisa ANTES de pulsar, no después.
 */
const val IMPORT_NOT_ATOMIC_NOTICE: String =
    "La publicación todavía no es atómica: si falla a mitad, parte de las preguntas puede quedar creada. " +
        "Comprueba el resultado del lote al terminar."

/** Lo que significa un resultado `parcial`, dicho sin disfrazarlo de éxito. */
fun importPartialResultMessage(batch: ContentImportBatch): String =
    "Publicación parcial: ${batch.importedCount} de ${batch.detectedCount} preguntas. " +
        "El resto no se ha creado; revisa los errores antes de reintentar."

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
    ImportCapability("Validar filas", ImportCapabilityLevel.PARTIAL, "La clasificación llega del servidor; el cliente no revalida."),
    ImportCapability("Resolver examen por fila", ImportCapabilityLevel.NOT_FOUND, "El examen es del lote, no de la fila."),
    ImportCapability("Resolver tema", ImportCapabilityLevel.NOT_FOUND, "Llega como texto libre; no hay catálogo."),
    ImportCapability("Resolver recursos", ImportCapabilityLevel.NOT_FOUND, "El staging no tiene campos de recurso."),
    ImportCapability("Revisar staging", ImportCapabilityLevel.REAL, "Filtros, búsqueda y detalle por fila."),
    ImportCapability("Editar staging", ImportCapabilityLevel.REAL, "Guarda en el lote; valida las cuatro opciones y A–D."),
    ImportCapability("Detectar duplicado", ImportCapabilityLevel.PARTIAL, "El servidor las marca; el cliente sólo las agrupa."),
    ImportCapability("Ver la pregunta existente", ImportCapabilityLevel.NOT_FOUND, "No llega ninguna referencia navegable."),
    ImportCapability("Omitir duplicado", ImportCapabilityLevel.REAL, "Marca la fila como omitida; no borra nada."),
    ImportCapability("Publicar lote", ImportCapabilityLevel.REAL, "Acción explícita, bloqueada mientras haya errores."),
    ImportCapability("Publicar atómicamente", ImportCapabilityLevel.BLOCKED, "Puede devolver «parcial»: no hay todo-o-nada."),
    ImportCapability("Descargar plantilla", ImportCapabilityLevel.NOT_FOUND, "No existe ninguna plantilla que descargar."),
    ImportCapability("Ver importaciones recientes", ImportCapabilityLevel.REAL, "Listado de lotes con sus recuentos."),
    ImportCapability("Descartar lote", ImportCapabilityLevel.NOT_FOUND, "No hay operación de borrado de lote."),
)

fun importCapability(name: String): ImportCapability =
    importCapabilities().firstOrNull { it.name == name }
        ?: error("Capacidad no declarada en la matriz: $name")

/** Plantilla: no hay ninguna, y el botón lo dice en vez de aparentar un enlace. */
const val IMPORT_TEMPLATE_BLOCKED: String = "Plantilla todavía no disponible"
