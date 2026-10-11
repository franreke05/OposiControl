package com.oposicontrol.admin.features.news.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.WorkspaceFacts
import com.oposicontrol.admin.core.ui.components.WorkspaceInfoLines
import com.oposicontrol.admin.core.ui.components.WorkspacePanel
import com.oposicontrol.admin.core.ui.components.WorkspaceRail
import com.oposicontrol.admin.core.ui.components.WorkspaceSection
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Spacing

/** Ancho del lienzo en la vista previa del rail: 300 de columna menos 2 x 20 de seccion y 2 x 16 de panel. */
private const val RAIL_PREVIEW_WIDTH = 228

/** La columna derecha: vista rapida, vista previa fija del documento tal como se guarda, y las reglas que conviene saber. */
@Composable
internal fun NewsRail(state: NewsState, onIntent: (NewsIntent) -> Unit, modifier: Modifier = Modifier, scrolls: Boolean = true) {
    val tokens = GlassTheme.tokens
    val ready = state.selectedId != null && state.recordState is UiAsync.Success
    WorkspaceRail(modifier = modifier, scrolls = scrolls) {
        if (ready) {
            WorkspaceSection(icon = AppIcon.ANALYTICS, role = SemanticRole.INSIGHT, title = "Vista rápida") {
                WorkspaceFacts(state.quickFacts())
            }
            WorkspaceSection(icon = AppIcon.VIEW, role = SemanticRole.INFO, title = "Vista previa", subtitle = "La composición tal como se guarda.") {
                WorkspacePanel(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    NewsCanvasPreview(state, RAIL_PREVIEW_WIDTH)
                    Text(
                        text = "El banner de Inicio de OposiBot con sus esquinas y su borde. La app todavía no lo conecta: hoy enseña una imagen fija.",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary,
                    )
                }
            }
        }
        WorkspaceSection(icon = AppIcon.INFO, role = SemanticRole.INFO, title = "Información") {
            WorkspaceInfoLines(newsInformationLines)
        }
    }
}
