package com.oposicontrol.admin.features.news.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.*
import com.oposicontrol.admin.core.ui.theme.GlassTheme
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

@Composable
internal fun NewsPublicationPane(state: NewsState, onIntent: (NewsIntent) -> Unit) {
    var confirmation by remember { mutableStateOf<NewsIntent?>(null) }
    var scheduling by remember(state.selectedId) { mutableStateOf(false) }
    var date by remember(state.selectedId, state.editorPublishFrom) { mutableStateOf(if (state.editorPublishFrom.length >= 10) newsDateLabel(state.editorPublishFrom).substringBefore(" ·") else "") }
    var time by remember(state.selectedId, state.editorPublishFrom) { mutableStateOf(state.editorPublishFrom.substringAfter('T', "09:00").take(5)) }
    val busy = state.actionState is UiAsync.Loading
    val remoteReady = state.libraryState is UiAsync.Success
    val knownCatalogs = (state.specialties as? UiAsync.Success)?.value?.any { it.id == state.editorSpecialty } == true &&
        (state.newsTypes as? UiAsync.Success)?.value?.any { it.id == state.editorType } == true
    NewsPanel("Publicación") {
        StatusChip(newsStatusLabel(state.editorStatus))
        Text(state.saveLabel(), style = MaterialTheme.typography.bodySmall, color = GlassTheme.tokens.textSecondary)
        if (state.editorPublishFrom.isNotBlank()) Text(newsDateLabel(state.editorPublishFrom), style = MaterialTheme.typography.bodySmall)
        Text("El guardado local conserva el último borrador en este equipo, sin guardar su fecha programada.", style = MaterialTheme.typography.bodySmall, color = GlassTheme.tokens.textSecondary)
        if (!remoteReady) Text("Publicación no disponible hasta actualizar la biblioteca correctamente.", style = MaterialTheme.typography.bodySmall, color = GlassTheme.tokens.textSecondary)
        if (!knownCatalogs) Text("Selecciona una especialidad y un tipo disponibles en Clasificación.", style = MaterialTheme.typography.bodySmall, color = GlassTheme.tokens.textSecondary)
        GhostButton(if (scheduling) "Cerrar programación" else "Programar publicación", { scheduling = !scheduling }, enabled = !busy)
        if (scheduling) {
            NewsField("Fecha (dd/mm/aaaa)", date, { date = it })
            NewsField("Hora UTC (hh:mm)", time, { time = it })
            val iso = newsScheduleIso(date, time)
            if (date.isNotBlank() && iso == null) Text("Introduce una fecha y hora válidas.", color = GlassTheme.tokens.danger, style = MaterialTheme.typography.bodySmall)
            SecondaryButton("Guardar programación", { iso?.let { onIntent(NewsIntent.ScheduleAt(it)) } }, Modifier.fillMaxWidth(), enabled = remoteReady && iso != null && knownCatalogs && !busy)
            Text("Guarda la fecha y el estado programado. La activación automática no está verificada.", style = MaterialTheme.typography.bodySmall, color = GlassTheme.tokens.textSecondary)
        }
        if (state.actionState is UiAsync.Error) Text("No se ha completado el guardado. Revisa el contenido y reintenta.", color = GlassTheme.tokens.danger, style = MaterialTheme.typography.bodySmall)
        state.validationReport?.errors?.take(4)?.forEach { Text(it.message, style = MaterialTheme.typography.bodySmall, color = GlassTheme.tokens.danger) }
    }
    if (state.selectedId != null) NewsPanel("Zona de riesgo") {
        Text("Estas acciones afectan al contenido guardado.", style = MaterialTheme.typography.bodySmall, color = GlassTheme.tokens.textSecondary)
        if (!state.selectedId.startsWith("local-") && state.editorStatus != "archived") DangerOutlineButton("Archivar noticia", { confirmation = NewsIntent.Archive }, Modifier.fillMaxWidth(), enabled = remoteReady && !busy && !state.isDirty)
        DangerOutlineButton(if (state.selectedId.startsWith("local-")) "Eliminar borrador local" else "Eliminar noticia", { confirmation = NewsIntent.DeleteSelected }, Modifier.fillMaxWidth(), enabled = !busy && (remoteReady || state.selectedId.startsWith("local-")))
        if (state.isDirty) Text("Publica los cambios para conservarlos en el servidor antes de archivar. Guardarlos localmente no los incorpora a la versión archivada.", style = MaterialTheme.typography.bodySmall, color = GlassTheme.tokens.textSecondary)
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
