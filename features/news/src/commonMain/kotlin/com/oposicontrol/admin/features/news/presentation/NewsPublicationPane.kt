package com.oposicontrol.admin.features.news.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.*
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.GlassTokens
import com.oposicontrol.admin.domain.model.NewsValidationIssue
import com.oposicontrol.admin.domain.model.NewsValidationReport
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

internal fun newsScheduleIso(date: String, time: String): String? = runCatching {
    val parts = date.split('/')
    require(parts.size == 3)
    val (day, month, year) = parts
    val clock = time.split(':')
    require(clock.size == 2)
    LocalDateTime(year.toInt(), month.toInt(), day.toInt(), clock[0].toInt(), clock[1].toInt()).toInstant(TimeZone.UTC).toString()
}.getOrNull()

/**
 * Resultado visual de una comprobación de publicación.
 *
 * [UNVERIFIED] existe porque «no lo hemos comprobado» y «está bien» son estados
 * distintos: sin informe de validación el checklist no puede pintarse en verde.
 */
internal enum class NewsCheckTone { PASSED, BLOCKING, NOTICE, UNVERIFIED }

/** Una fila del checklist: qué se comprueba, qué dice el dominio y con qué peso. */
internal data class NewsCheckRow(val title: String, val detail: String, val tone: NewsCheckTone)

/** Agrupación de códigos reales del dominio bajo un mismo título de fila. */
internal data class NewsCheckGroup(val title: String, val codes: Set<String>, val okDetail: String)

/**
 * Las cuatro familias del checklist.
 *
 * Los códigos son los que emite `ValidateNewsArticleUseCase`; no se inventa
 * ninguno. Si el dominio añade uno nuevo y nadie lo mapea aquí, cae en la fila
 * de resto que construye [newsPublicationChecklist] en vez de desaparecer.
 */
internal val newsCheckGroups: List<NewsCheckGroup> = listOf(
    NewsCheckGroup(
        title = "Contenido",
        codes = setOf("title_required", "text_empty", "title_layer_required"),
        okDetail = "Título, cuerpo y capa de título en su sitio.",
    ),
    NewsCheckGroup(
        title = "Clasificación",
        codes = setOf("specialty_required", "offer_link_required", "offer_cta_required", "schedule_date_required"),
        okDetail = "Especialidad y datos exigidos por el tipo, completos.",
    ),
    NewsCheckGroup(
        title = "Lienzo",
        codes = setOf("canvas_invalid", "layers_empty"),
        okDetail = "Medidas válidas y al menos una capa visible.",
    ),
    NewsCheckGroup(
        title = "Imagen",
        codes = setOf("background_missing", "background_not_ready", "resource_image_missing"),
        okDetail = "Fondo definido y enlazado.",
    ),
)

/** Resume una lista de incidencias sin ocultar cuántas quedan fuera. */
internal fun newsIssueSummary(issues: List<NewsValidationIssue>): String {
    val shown = issues.take(2).joinToString(" · ") { it.message }
    val rest = issues.size - 2
    return if (rest > 0) "$shown · y $rest más" else shown
}

/**
 * Convierte el informe de validación en filas de checklist.
 *
 * Sin informe todas las filas quedan en [NewsCheckTone.UNVERIFIED]: el panel no
 * puede afirmar que algo se cumple cuando nadie lo ha comprobado todavía.
 */
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

/** Línea de cabecera del checklist. Nunca dice «todo bien» si algo está sin comprobar. */
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

/** Etiqueta accesible del icono: el significado no puede viajar solo en el color. */
internal fun newsCheckLabel(tone: NewsCheckTone): String = when (tone) {
    NewsCheckTone.PASSED -> "Cumplido"
    NewsCheckTone.BLOCKING -> "Bloquea la publicación"
    NewsCheckTone.NOTICE -> "Aviso"
    NewsCheckTone.UNVERIFIED -> "Sin comprobar"
}

private fun newsCheckIcon(tone: NewsCheckTone): AppIcon = when (tone) {
    NewsCheckTone.PASSED -> AppIcon.CHECK
    NewsCheckTone.BLOCKING -> AppIcon.CLOSE
    NewsCheckTone.NOTICE -> AppIcon.INFO
    NewsCheckTone.UNVERIFIED -> AppIcon.HELP
}

/** Color de familia: vale para icono, punto y tinte al 12 %. */
private fun newsCheckColor(tokens: GlassTokens, tone: NewsCheckTone): Color = when (tone) {
    NewsCheckTone.PASSED -> tokens.success
    NewsCheckTone.BLOCKING -> tokens.danger
    NewsCheckTone.NOTICE -> tokens.info
    NewsCheckTone.UNVERIFIED -> tokens.textSecondary
}

/** Tinta de la misma familia, la que sí se lee como texto. */
private fun newsCheckInk(tokens: GlassTokens, tone: NewsCheckTone): Color = when (tone) {
    NewsCheckTone.PASSED -> tokens.successInk
    NewsCheckTone.BLOCKING -> tokens.dangerInk
    NewsCheckTone.NOTICE -> tokens.infoInk
    NewsCheckTone.UNVERIFIED -> tokens.textSecondary
}

@Composable
private fun NewsCheckBadge(tone: NewsCheckTone) {
    val color = newsCheckColor(GlassTheme.tokens, tone)
    Box(
        Modifier.size(24.dp).background(color.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        AppIconImage(newsCheckIcon(tone), size = 16.dp, tint = color, contentDescription = newsCheckLabel(tone))
    }
}

@Composable
private fun NewsCheckItem(row: NewsCheckRow) {
    val tokens = GlassTheme.tokens
    Row(
        Modifier.fillMaxWidth().background(tokens.surfaceMuted, RoundedCornerShape(12.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        NewsCheckBadge(row.tone)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = tokens.textPrimary)
            Text(row.detail, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
        }
    }
}

/** Aviso de contexto: no es una comprobación del dominio, así que no lleva tarjeta. */
@Composable
private fun NewsPublicationNote(tone: NewsCheckTone, message: String) {
    val tokens = GlassTheme.tokens
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        AppIconImage(newsCheckIcon(tone), size = 16.dp, tint = newsCheckColor(tokens, tone), contentDescription = newsCheckLabel(tone))
        Text(message, style = MaterialTheme.typography.bodySmall, color = newsCheckInk(tokens, tone))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NewsPublicationPane(state: NewsState, onIntent: (NewsIntent) -> Unit) {
    var confirmation by remember { mutableStateOf<NewsIntent?>(null) }
    var scheduling by remember(state.selectedId) { mutableStateOf(false) }
    var date by remember(state.selectedId, state.editorPublishFrom) { mutableStateOf(if (state.editorPublishFrom.length >= 10) newsDateLabel(state.editorPublishFrom).substringBefore(" ·") else "") }
    var time by remember(state.selectedId, state.editorPublishFrom) { mutableStateOf(state.editorPublishFrom.substringAfter('T', "09:00").take(5)) }
    val tokens = GlassTheme.tokens
    val busy = state.actionState is UiAsync.Loading
    val remoteReady = state.libraryState is UiAsync.Success
    val knownCatalogs = (state.specialties as? UiAsync.Success)?.value?.any { it.id == state.editorSpecialty } == true &&
        (state.newsTypes as? UiAsync.Success)?.value?.any { it.id == state.editorType } == true
    val checks = newsPublicationChecklist(state.validationReport)
    val notes = buildList {
        if (!remoteReady) add(NewsCheckTone.BLOCKING to "Publicación no disponible hasta actualizar la biblioteca correctamente.")
        if (!knownCatalogs) add(NewsCheckTone.NOTICE to "Selecciona una especialidad y un tipo disponibles en Clasificación.")
        if (state.actionState is UiAsync.Error) add(NewsCheckTone.BLOCKING to "No se ha completado el guardado. Revisa el contenido y reintenta.")
    }
    NewsPanel("Publicación") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusChip(newsStatusLabel(state.editorStatus))
                Text(state.saveLabel(), style = MaterialTheme.typography.labelMedium, color = tokens.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!state.editorStatus.equals("published", ignoreCase = true)) {
                Text("Esta noticia no está publicada.", style = MaterialTheme.typography.bodyMedium, color = tokens.textPrimary)
            }
            if (state.editorPublishFrom.isNotBlank()) {
                Text("Fecha de publicación · ${newsDateLabel(state.editorPublishFrom)}", style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
            }
        }
        HorizontalDivider(color = tokens.border)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Comprobaciones", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = tokens.textPrimary)
                Text(newsChecklistSummary(checks), style = MaterialTheme.typography.labelSmall, color = tokens.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            checks.forEach { NewsCheckItem(it) }
        }
        if (notes.isNotEmpty()) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            notes.forEach { (tone, message) -> NewsPublicationNote(tone, message) }
        }
        HorizontalDivider(color = tokens.border)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GhostButton(
                if (scheduling) "Cerrar programación" else "Programar publicación",
                { scheduling = !scheduling },
                enabled = !busy,
                icon = { AppIconImage(AppIcon.CLOCK, size = 16.dp) },
            )
            if (scheduling) {
                NewsField("Fecha (dd/mm/aaaa)", date, { date = it })
                NewsField("Hora UTC (hh:mm)", time, { time = it })
                val iso = newsScheduleIso(date, time)
                if (date.isNotBlank() && iso == null) NewsPublicationNote(NewsCheckTone.BLOCKING, "Introduce una fecha y hora válidas.")
                SecondaryButton("Guardar programación", { iso?.let { onIntent(NewsIntent.ScheduleAt(it)) } }, Modifier.fillMaxWidth(), enabled = remoteReady && iso != null && knownCatalogs && !busy)
                Text("Guarda la fecha y el estado programado. La activación automática no está verificada.", style = MaterialTheme.typography.labelSmall, color = tokens.textSecondary)
            }
        }
        Text("El guardado local conserva el último borrador en este equipo, sin guardar su fecha programada.", style = MaterialTheme.typography.labelSmall, color = tokens.textSecondary)
    }
    if (state.selectedId != null) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = tokens.border)
        Text("Zona de riesgo · estas acciones afectan al contenido guardado.", style = MaterialTheme.typography.labelSmall, color = tokens.textSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!state.selectedId.startsWith("local-") && state.editorStatus != "archived") {
                DangerOutlineButton("Archivar noticia", { confirmation = NewsIntent.Archive }, size = ButtonSize.COMPACT, enabled = remoteReady && !busy && !state.isDirty)
            }
            DangerOutlineButton(
                if (state.selectedId.startsWith("local-")) "Eliminar borrador local" else "Eliminar noticia",
                { confirmation = NewsIntent.DeleteSelected },
                size = ButtonSize.COMPACT,
                enabled = !busy && (remoteReady || state.selectedId.startsWith("local-")),
            )
        }
        if (state.isDirty) Text("Publica los cambios para conservarlos en el servidor antes de archivar. Guardarlos localmente no los incorpora a la versión archivada.", style = MaterialTheme.typography.labelSmall, color = tokens.textSecondary)
    }
    confirmation?.let { intent -> ConfirmDialog(
        title = when (intent) { NewsIntent.Publish -> "Publicar noticia"; NewsIntent.Archive -> "Archivar noticia"; else -> "Eliminar noticia" },
        message = when (intent) {
            NewsIntent.Publish -> "Se enviará esta versión al servidor con estado publicado."
            NewsIntent.Archive -> "Se archivará la versión guardada en el servidor y se descartará la copia local de esta noticia. Los cambios guardados solo en este equipo se perderán."
            else -> "Confirma la acción sobre «${state.editorTitle.ifBlank { "Nueva noticia" }}»."
        },
        confirmLabel = "Confirmar", destructive = intent != NewsIntent.Publish,
        onConfirm = { confirmation = null; onIntent(intent) }, onDismiss = { confirmation = null },
    ) }
}
