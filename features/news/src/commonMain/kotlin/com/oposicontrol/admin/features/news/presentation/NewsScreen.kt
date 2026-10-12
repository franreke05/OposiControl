package com.oposicontrol.admin.features.news.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.oposicontrol.admin.core.ui.components.ConfirmDialog
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.WorkspaceLayout
import com.oposicontrol.admin.core.ui.components.WorkspacePanel

internal const val NEWS_CATALOG_TAG: String = "news-catalog"
internal const val NEWS_EDITOR_TAG: String = "news-editor"
internal const val NEWS_RAIL_TAG: String = "news-rail"

/** Ancho del lienzo ampliado: cabe en 1366 con margen y deja el 800 x 500 legible a escala 1. */
private val FULLSCREEN_CANVAS_WIDTH = 900.dp

@Composable
fun NewsScreen(viewModel: NewsViewModel) {
    val state by viewModel.state.collectAsState()
    NewsContent(state, viewModel::dispatch)
}

/**
 * Noticias, en escritorio: el mismo workspace de tres columnas que el resto del panel —biblioteca, ficha con
 * pestanas y barra de guardado, resumen— con el lienzo dentro de la pestana «Diseño». Por ESTADO: el banco visual
 * lo renderiza contra estados fabricados sin Ktor ni Koin.
 */
@Composable
internal fun NewsContent(state: NewsState, onIntent: (NewsIntent) -> Unit, modifier: Modifier = Modifier) {
    WorkspaceLayout(
        modifier = modifier,
        catalog = { paneModifier -> NewsCatalogPane(state, onIntent, paneModifier.testTag(NEWS_CATALOG_TAG)) },
        editor = { paneModifier, contentWidth, fillsHeight ->
            NewsEditorPane(state, onIntent, contentWidth, fillsHeight, paneModifier.testTag(NEWS_EDITOR_TAG))
        },
        rail = { paneModifier, scrolls -> NewsRail(state, onIntent, paneModifier.testTag(NEWS_RAIL_TAG), scrolls) },
    )
    NewsDialogs(state, onIntent)
}

@Composable
private fun NewsDialogs(state: NewsState, onIntent: (NewsIntent) -> Unit) {
    if (state.pendingIntent != null) {
        ConfirmDialog(
            title = "Cambios sin guardar",
            message = "Hay cambios en esta noticia que todavía no están en el servidor. Si continúas, se pierden.",
            confirmLabel = "Descartar y continuar",
            dismissLabel = "Seguir aquí",
            destructive = false,
            onConfirm = { onIntent(NewsIntent.ConfirmPending) },
            onDismiss = { onIntent(NewsIntent.DismissPending) },
        )
    }
    val confirm = state.confirmIntent
    if (confirm != null) {
        val (title, message, label, destructive) = newsConfirmation(confirm, state)
        ConfirmDialog(
            title = title,
            message = message,
            confirmLabel = label,
            destructive = destructive,
            onConfirm = { onIntent(NewsIntent.DismissConfirm); onIntent(confirm) },
            onDismiss = { onIntent(NewsIntent.DismissConfirm) },
        )
    }
    if (state.canvasFullscreen) {
        // Sin `usePlatformDefaultWidth`: el dialogo de escritorio nace con un ancho por defecto que recortaria el lienzo.
        Dialog(
            onDismissRequest = { onIntent(NewsIntent.ToggleCanvasFullscreen) },
            properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true, usePlatformDefaultWidth = false),
        ) {
            var zoom by remember { mutableStateOf(1f) }
            WorkspacePanel(modifier = Modifier.width(FULLSCREEN_CANVAS_WIDTH)) {
                Column {
                    NewsCanvasFrame(
                        state = state,
                        onIntent = onIntent,
                        availableWidth = 900 - 32 - 32,
                        zoom = zoom,
                        onZoom = { zoom = it },
                        interactive = true,
                    )
                }
                SecondaryButton(text = "Cerrar", onClick = { onIntent(NewsIntent.ToggleCanvasFullscreen) })
            }
        }
    }
}

/** Texto de cada confirmacion: que va a pasar, dicho antes del clic. */
internal data class NewsConfirmation(val title: String, val message: String, val confirmLabel: String, val destructive: Boolean)

internal fun newsConfirmation(intent: NewsIntent, state: NewsState): NewsConfirmation = when (intent) {
    NewsIntent.Publish -> NewsConfirmation(
        title = "Publicar noticia",
        message = "Se publica ahora con el reloj del servidor. OposiBot la enseñará cuando su banner de Inicio se conecte a las noticias. Una noticia publicada no se puede editar: solo archivar.",
        confirmLabel = "Publicar",
        destructive = false,
    )
    is NewsIntent.ScheduleAt -> NewsConfirmation(
        title = "Programar noticia",
        message = "Se guarda la fecha ${newsDateLabel(intent.isoUtc)} y la noticia queda programada. La activación automática en OposiBot no está definida: habrá que publicarla cuando llegue el día.",
        confirmLabel = "Programar",
        destructive = false,
    )
    NewsIntent.Archive -> NewsConfirmation(
        title = "Archivar noticia",
        message = "Deja de contar como publicada y no se podrá volver a editar ni publicar. Se conserva en la biblioteca como archivada.",
        confirmLabel = "Archivar",
        destructive = true,
    )
    NewsIntent.DeleteSelected -> NewsConfirmation(
        title = if (state.isNew()) "Descartar borrador" else "Eliminar noticia",
        message = if (state.isNew()) {
            "Se descarta lo escrito en «${state.editorTitle.ifBlank { "Nueva noticia" }}». No existe en el servidor."
        } else {
            "Se borra «${state.editorTitle.ifBlank { "Sin título" }}» del servidor, con su fondo y su imagen de tarjeta. No se puede deshacer."
        },
        confirmLabel = if (state.isNew()) "Descartar" else "Eliminar",
        destructive = true,
    )
    else -> NewsConfirmation("Confirmar", "¿Continuar con la acción?", "Continuar", false)
}
