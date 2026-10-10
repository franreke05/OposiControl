package com.oposicontrol.admin.features.news.presentation

import androidx.compose.ui.text.font.FontWeight
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.ui.components.StatusTone
import com.oposicontrol.admin.core.ui.components.WorkspaceFact
import com.oposicontrol.admin.core.ui.components.WorkspaceSaveTone
import com.oposicontrol.admin.core.ui.components.humanMessage
import com.oposicontrol.admin.domain.model.NewsValidationIssue
import com.oposicontrol.admin.domain.model.NewsValidationReport
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * Funciones PURAS de presentacion: etiquetas, estado de la barra de guardado, checklist de publicacion, datos de la
 * vista rapida. Todo lo que decide que palabra se pinta vive aqui y se prueba sin Compose.
 */

data class NewsCatalogOption(val id: String, val name: String, val active: Boolean = true)

// ---------------------------------------------------------------------------------------------------------------------
// Etiquetas
// ---------------------------------------------------------------------------------------------------------------------

internal fun newsStatusLabel(value: String): String = when (value.lowercase()) {
    "draft" -> "Borrador"
    "published" -> "Publicada"
    "scheduled" -> "Programada"
    "archived" -> "Archivada"
    else -> "Sin identificar"
}

internal fun newsStatusTone(status: String): StatusTone = when (status.lowercase()) {
    "published" -> StatusTone.POSITIVE
    "scheduled" -> StatusTone.NEUTRAL
    "draft" -> StatusTone.ATTENTION
    "archived" -> StatusTone.NEGATIVE
    else -> StatusTone.UNKNOWN
}

internal fun catalogName(value: String, catalog: UiAsync<List<NewsCatalogOption>>): String = when (catalog) {
    UiAsync.Loading, UiAsync.Idle -> "Cargando…"
    is UiAsync.Error -> "No disponible"
    else -> (catalog as? UiAsync.Success)?.value?.firstOrNull { it.id == value }?.name
        ?: if (value.isBlank()) "Sin seleccionar" else "Sin identificar"
}

/** Nombre de fichero de una ruta o URL, sin directorios ni identificadores opacos. */
internal fun newsAssetName(path: String): String = path.substringBefore('?').replace('\\', '/')
    .substringAfterLast('/').takeIf { it.contains('.') && !it.matches(Regex("[a-fA-F0-9-]{30,}.*")) }
    ?.take(48) ?: "Imagen seleccionada"

/** `12/09/2026 · 10:00 UTC` a partir de un instante ISO; «Sin fecha» si no lo es. */
internal fun newsDateLabel(value: String?): String {
    val match = Regex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}).*").matchEntire(value.orEmpty()) ?: return "Sin fecha"
    val (year, month, day, hour, minute) = match.destructured
    return "$day/$month/$year · $hour:$minute UTC"
}

internal fun newsScheduleIso(date: String, time: String): String? = runCatching {
    val parts = date.trim().split('/')
    require(parts.size == 3)
    val (day, month, year) = parts
    val clock = time.trim().split(':')
    require(clock.size == 2)
    LocalDateTime(year.toInt(), month.toInt(), day.toInt(), clock[0].toInt(), clock[1].toInt()).toInstant(TimeZone.UTC).toString()
}.getOrNull()

/** Fecha y hora de un instante ISO para rellenar los campos de programacion. */
internal fun newsScheduleFields(isoUtc: String): Pair<String, String> {
    val match = Regex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}).*").matchEntire(isoUtc) ?: return "" to "09:00"
    val (year, month, day, hour, minute) = match.destructured
    return "$day/$month/$year" to "$hour:$minute"
}

internal fun newsCountLabel(count: Int): String = if (count == 1) "1 noticia" else "$count noticias"

internal fun newsLayerKindLabel(kind: String): String = when (kind.trim().lowercase()) {
    "titulo", "title" -> "Título"
    "imagen", "image" -> "Imagen"
    "badge", "etiqueta" -> "Etiqueta"
    "cta", "boton", "button" -> "Botón"
    else -> "Texto"
}

internal fun NewsLayerDraft.isImageLayer(): Boolean = kind.trim().lowercase() in listOf("imagen", "image")

internal fun NewsLayerDraft.isTitleLayer(): Boolean = kind.trim().lowercase() in listOf("titulo", "title")

internal fun NewsLayerDraft.rowSummary(): String {
    val detail = when {
        content.isBlank() -> ""
        isImageLayer() -> newsAssetName(content)
        else -> content.replace('\n', ' ').trim()
    }
    val kindLabel = newsLayerKindLabel(kind)
    return if (detail.isBlank()) "$kindLabel · sin contenido" else "$kindLabel · $detail"
}

/** `fontSizeSp = 0` = derivado del tipo de capa: titulo 25 sp, resto 16 sp (lo que OposiBot pinta). */
internal fun NewsLayerDraft.effectiveFontSizeSp(): Int = fontSizeSp.takeIf { it > 0 } ?: if (isTitleLayer()) 25 else 16

internal fun NewsLayerDraft.effectiveFontWeight(): FontWeight = when (fontWeight) {
    "bold" -> FontWeight.Bold
    "semibold" -> FontWeight.SemiBold
    "medium" -> FontWeight.Medium
    "normal" -> FontWeight.Normal
    else -> if (isTitleLayer()) FontWeight.Bold else FontWeight.Normal
}

// ---------------------------------------------------------------------------------------------------------------------
// Barra de guardado
// ---------------------------------------------------------------------------------------------------------------------

/** Que dice la barra de guardado. [conflict] anade «Recargar»; [primaryLabel] es Crear o Guardar. */
internal data class NewsSaveStatus(
    val message: String,
    val tone: WorkspaceSaveTone,
    val detail: String?,
    val prominent: Boolean,
    val canSave: Boolean,
    val canDiscard: Boolean,
    val primaryLabel: String,
    val conflict: Boolean = false,
)

/** Primer motivo por el que el servidor rechazaria el guardado, o `null`. Espejo de D4-03/D4-04. */
internal fun NewsState.saveProblem(): String? = when {
    editorTitle.isBlank() -> "Escribe un título para poder guardar."
    editorTitle.length > NEWS_TITLE_MAX -> "El título no puede pasar de $NEWS_TITLE_MAX caracteres."
    editorSpecialty.isBlank() -> "Elige una especialidad en Contenido."
    editorType.isBlank() -> "Elige un tipo de noticia en Contenido."
    uploads.values.any { !it.failed } -> "Espera a que terminen las subidas de imagen."
    uploads.values.any { it.failed } -> "Hay una subida fallida: reintenta o quítala."
    else -> null
}

/** Primer motivo por el que no se puede publicar ni programar, o `null`. */
internal fun NewsState.publishProblem(): String? = when {
    saveProblem() != null -> saveProblem()
    specialties.let { it is UiAsync.Success && it.value.none { option -> option.id == editorSpecialty && option.active } } -> "La especialidad elegida no está activa."
    newsTypes.let { it is UiAsync.Success && it.value.none { option -> option.id == editorType && option.active } } -> "El tipo de noticia elegido no está activo."
    validationReport?.canPublish == false -> validationReport.errors.firstOrNull()?.message ?: "La composición no pasa las comprobaciones."
    else -> null
}

internal fun NewsState.isNew(): Boolean = selectedId.isLocalNewsId()

internal fun NewsState.isReadOnly(): Boolean = editorStatus.lowercase() in setOf("published", "archived") && !isNew()

internal fun newsActionVerb(action: NewsAction?): String = when (action) {
    NewsAction.PUBLISH -> "publicar"
    NewsAction.SCHEDULE -> "programar"
    NewsAction.ARCHIVE -> "archivar"
    NewsAction.DELETE -> "eliminar"
    NewsAction.SAVE, null -> "guardar"
}

internal fun newsActionProgress(action: NewsAction?): String = when (action) {
    NewsAction.PUBLISH -> "Publicando…"
    NewsAction.SCHEDULE -> "Programando…"
    NewsAction.ARCHIVE -> "Archivando…"
    NewsAction.DELETE -> "Eliminando…"
    NewsAction.SAVE, null -> "Guardando…"
}

internal fun NewsState.saveStatus(): NewsSaveStatus {
    val label = if (isNew()) "Crear borrador" else "Guardar"
    val saving = actionState is UiAsync.Loading
    val problem = saveProblem()
    val failure = (actionState as? UiAsync.Error)?.error
    return when {
        saving -> NewsSaveStatus(newsActionProgress(actionKind), WorkspaceSaveTone.INFO, null, prominent = isDirty, canSave = false, canDiscard = false, primaryLabel = label)
        failure != null && actionConflict -> NewsSaveStatus(
            message = "La noticia cambió en el servidor.",
            tone = WorkspaceSaveTone.DANGER,
            detail = failure.humanMessage() + " Recarga la ficha para seguir; tus cambios de aquí se perderían.",
            prominent = true,
            canSave = false,
            canDiscard = false,
            primaryLabel = label,
            conflict = true,
        )
        failure != null -> NewsSaveStatus(
            message = "No se pudo ${newsActionVerb(actionKind)}.",
            tone = WorkspaceSaveTone.DANGER,
            detail = failure.humanMessage(),
            prominent = true,
            canSave = problem == null && !isReadOnly(),
            canDiscard = isDirty,
            primaryLabel = label,
        )
        isReadOnly() -> NewsSaveStatus(
            message = if (editorStatus.equals("archived", true)) "Archivada: el contenido queda fijado." else "Publicada: el contenido queda fijado.",
            tone = WorkspaceSaveTone.INFO,
            detail = "Para cambiarla, archívala y crea una nueva. El contrato no admite editar una noticia publicada.",
            prominent = false,
            canSave = false,
            canDiscard = false,
            primaryLabel = label,
        )
        recoveredDraft && isDirty -> NewsSaveStatus(
            message = "Cambios recuperados de este equipo.",
            tone = WorkspaceSaveTone.WARNING,
            detail = problem ?: "No estaban en el servidor: guárdalos o descártalos.",
            prominent = true,
            canSave = problem == null,
            canDiscard = true,
            primaryLabel = label,
        )
        !isDirty && !isNew() && actionState is UiAsync.Success && actionMessage.isNotBlank() ->
            NewsSaveStatus(actionMessage, WorkspaceSaveTone.SUCCESS, null, prominent = false, canSave = false, canDiscard = false, primaryLabel = label)
        !isDirty && !isNew() -> NewsSaveStatus("Sin cambios pendientes.", WorkspaceSaveTone.SUCCESS, null, prominent = false, canSave = false, canDiscard = false, primaryLabel = label)
        problem != null -> NewsSaveStatus(problem, WorkspaceSaveTone.WARNING, null, prominent = true, canSave = false, canDiscard = isDirty, primaryLabel = label)
        isNew() -> NewsSaveStatus("Lista para crear.", WorkspaceSaveTone.SUCCESS, "Todavía no existe en el servidor: se crea como borrador.", prominent = true, canSave = true, canDiscard = isDirty, primaryLabel = label)
        else -> NewsSaveStatus("Cambios sin guardar.", WorkspaceSaveTone.WARNING, "Lo que ves en la ficha todavía no está en el servidor.", prominent = true, canSave = true, canDiscard = true, primaryLabel = label)
    }
}

/** Etiqueta corta del estado de guardado para cabeceras (el editor movil la usaba con este nombre). */
internal fun NewsState.saveLabel(): String = saveStatus().message

// ---------------------------------------------------------------------------------------------------------------------
// Checklist de publicacion
// ---------------------------------------------------------------------------------------------------------------------

/** [UNVERIFIED] existe porque «no lo hemos comprobado» y «esta bien» son estados distintos. */
internal enum class NewsCheckTone { PASSED, BLOCKING, NOTICE, UNVERIFIED }

internal data class NewsCheckRow(val title: String, val detail: String, val tone: NewsCheckTone)

internal data class NewsCheckGroup(val title: String, val codes: Set<String>, val okDetail: String)

/** Los codigos son los que emite `ValidateNewsDraftUseCase`; un codigo nuevo sin mapear cae en «Otras comprobaciones». */
internal val newsCheckGroups: List<NewsCheckGroup> = listOf(
    NewsCheckGroup("Contenido", setOf("title_required", "text_empty", "title_layer_required"), "Título, cuerpo y capa de título en su sitio."),
    NewsCheckGroup("Clasificación", setOf("specialty_required", "offer_link_required", "offer_cta_required", "schedule_date_required"), "Especialidad y datos exigidos por el tipo, completos."),
    NewsCheckGroup("Lienzo", setOf("canvas_invalid", "layers_empty", "layer_too_small", "layer_outside_canvas", "layer_text_required", "layer_opacity_invalid"), "Medidas válidas y capas dentro del lienzo."),
    NewsCheckGroup("Imagen", setOf("background_missing", "background_not_ready", "resource_image_missing"), "Fondo definido y enlazado."),
)

internal fun newsIssueSummary(issues: List<NewsValidationIssue>): String {
    val shown = issues.take(2).joinToString(" · ") { it.message }
    val rest = issues.size - 2
    return if (rest > 0) "$shown · y $rest más" else shown
}

internal fun newsPublicationChecklist(report: NewsValidationReport?): List<NewsCheckRow> {
    if (report == null) return newsCheckGroups.map { NewsCheckRow(it.title, "Sin comprobar todavía.", NewsCheckTone.UNVERIFIED) }
    val rows = newsCheckGroups.map { group ->
        val errors = report.errors.filter { it.code in group.codes }
        val warnings = report.warnings.filter { it.code in group.codes }
        when {
            errors.isNotEmpty() -> NewsCheckRow(group.title, newsIssueSummary(errors), NewsCheckTone.BLOCKING)
            warnings.isNotEmpty() -> NewsCheckRow(group.title, newsIssueSummary(warnings), NewsCheckTone.NOTICE)
            else -> NewsCheckRow(group.title, group.okDetail, NewsCheckTone.PASSED)
        }
    }
    val mapped = newsCheckGroups.flatMap { it.codes }.toSet()
    val restErrors = report.errors.filterNot { it.code in mapped }
    val restWarnings = report.warnings.filterNot { it.code in mapped }
    return when {
        restErrors.isNotEmpty() -> rows + NewsCheckRow("Otras comprobaciones", newsIssueSummary(restErrors), NewsCheckTone.BLOCKING)
        restWarnings.isNotEmpty() -> rows + NewsCheckRow("Otras comprobaciones", newsIssueSummary(restWarnings), NewsCheckTone.NOTICE)
        else -> rows
    }
}

internal fun newsChecklistSummary(rows: List<NewsCheckRow>): String {
    val blocking = rows.count { it.tone == NewsCheckTone.BLOCKING }
    val notices = rows.count { it.tone == NewsCheckTone.NOTICE }
    return when {
        rows.any { it.tone == NewsCheckTone.UNVERIFIED } -> "Sin validar"
        blocking > 0 && notices > 0 -> "$blocking bloquean · $notices con avisos"
        blocking == 1 -> "1 comprobación bloquea"
        blocking > 1 -> "$blocking comprobaciones bloquean"
        notices == 1 -> "1 aviso, ninguna bloquea"
        notices > 1 -> "$notices avisos, ninguna bloquea"
        else -> "Ninguna bloquea la publicación"
    }
}

internal fun newsCheckLabel(tone: NewsCheckTone): String = when (tone) {
    NewsCheckTone.PASSED -> "Cumplido"
    NewsCheckTone.BLOCKING -> "Bloquea la publicación"
    NewsCheckTone.NOTICE -> "Aviso"
    NewsCheckTone.UNVERIFIED -> "Sin comprobar"
}

// ---------------------------------------------------------------------------------------------------------------------
// Vista rapida e informacion
// ---------------------------------------------------------------------------------------------------------------------

internal fun NewsState.quickFacts(): List<WorkspaceFact> = listOf(
    WorkspaceFact("Estado", if (isNew()) "Sin crear" else newsStatusLabel(editorStatus), emphasised = true),
    WorkspaceFact("Capas", "${editorLayers.count { it.visible }} visibles de ${editorLayers.size}"),
    WorkspaceFact("Especialidad", catalogName(editorSpecialty, specialties)),
    WorkspaceFact("Tipo", catalogName(editorType, newsTypes)),
    WorkspaceFact("Publicación", if (editorPublishFrom.isBlank()) "Sin programar" else newsDateLabel(editorPublishFrom)),
    WorkspaceFact("Publicada el", if (editorPublishedAt.isBlank()) "Nunca" else newsDateLabel(editorPublishedAt)),
)

/** Reglas que conviene saber antes de guardar. Todas ciertas en el contrato de hoy. */
internal val newsInformationLines: List<String> = listOf(
    "Guardar escribe en el servidor. Un borrador solo lo ven los administradores.",
    "Publicar fija la noticia: el contrato no admite editar una publicada. Para cambiarla, archívala y crea otra.",
    "Programada guarda la fecha; la activación automática en OposiBot no está definida todavía (decisión pendiente).",
    "Imágenes: PNG, JPG, WebP o GIF de hasta 10 MB; se comprimen al subir.",
    "El lienzo mide 800 × 500, que es lo que OposiBot pinta. La tarjeta final del feed depende de la decisión D4-18.",
)

internal fun newsLibraryMeta(item: NewsItem, state: NewsState): List<String> {
    val date = when {
        !item.publishedAt.isNullOrBlank() -> "Publicada " + newsDateLabel(item.publishedAt).substringBefore(" ·")
        item.publishFrom.isNotBlank() -> "Programada " + newsDateLabel(item.publishFrom).substringBefore(" ·")
        item.updatedAt.isNotBlank() -> "Editada " + newsDateLabel(item.updatedAt).substringBefore(" ·")
        else -> "Sin fecha"
    }
    return listOf(date, catalogName(item.type, state.newsTypes))
}

internal fun NewsState.hasLibraryFilters(): Boolean =
    searchQuery.isNotBlank() || statusFilter != "all" || specialtyFilter != "all" || typeFilter != "all"

/** Mensaje humano de un error de carga: nunca el texto crudo. */
internal fun AppError.newsMessage(): String = humanMessage()

/** Ancho a partir del cual el inspector de capa cabe al lado del lienzo dentro de la ficha. */
internal const val NEWS_DESIGN_SIDE_BY_SIDE_MIN_WIDTH: Int = 880

/** Ancho fijo del inspector cuando va al lado del lienzo. */
internal const val NEWS_INSPECTOR_WIDTH: Int = 296

internal const val NEWS_TITLE_MAX: Int = 300
