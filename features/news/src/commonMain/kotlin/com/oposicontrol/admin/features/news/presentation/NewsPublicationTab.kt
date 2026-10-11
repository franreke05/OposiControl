package com.oposicontrol.admin.features.news.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.oposicontrol.admin.features.news.editor.NEWS_BANNER_PHONE_HEIGHT_DP
import com.oposicontrol.admin.features.news.editor.NEWS_BANNER_PHONE_WIDTH_DP
import com.oposicontrol.admin.features.news.editor.NEWS_BANNER_TABLET_WIDTH_DP
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.DangerOutlineButton
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.LabeledField
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.components.WorkspaceEqualRow
import com.oposicontrol.admin.core.ui.components.WorkspaceMetaLine
import com.oposicontrol.admin.core.ui.components.WorkspaceNotice
import com.oposicontrol.admin.core.ui.components.WorkspaceNoticeTone
import com.oposicontrol.admin.core.ui.components.WorkspacePanel
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.GlassTokens
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing

/**
 * Pestana «Publicación»: estado real de la noticia, checklist del editor y los tres comandos del contrato
 * (publicar, programar, archivar) mas eliminar. Lo que el backend no honra se dice aqui, no se disimula.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NewsPublicationTab(state: NewsState, onIntent: (NewsIntent) -> Unit, contentWidth: Dp) {
    val tokens = GlassTheme.tokens
    val busy = state.actionState is UiAsync.Loading
    val status = state.editorStatus.lowercase()
    val checks = newsPublicationChecklist(state.validationReport)
    val problem = state.publishProblem()
    val canCommand = !busy && status in setOf("draft", "scheduled")
    val saved = !state.isNew()

    FormSection(
        title = "Estado",
        description = "Lo que el servidor sabe de esta noticia. El estado solo cambia con los botones de abajo.",
        icon = AppIcon.CLOCK,
        iconRole = SemanticRole.INFO,
    ) {
        WorkspacePanel(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                StatusChip(status = if (state.isNew()) "Sin crear" else newsStatusLabel(state.editorStatus), tone = if (state.isNew()) com.oposicontrol.admin.core.ui.components.StatusTone.ATTENTION else newsStatusTone(state.editorStatus))
                Text(
                    text = when {
                        state.isNew() -> "Todavía no existe en el servidor."
                        status == "draft" -> "Solo la ven los administradores."
                        status == "scheduled" -> "Guardada con fecha; sigue sin verse en OposiBot."
                        status == "published" -> "Publicada en el servidor. La app la enseñará cuando su banner se conecte."
                        status == "archived" -> "Retirada: ya no cuenta como publicada."
                        else -> "Estado que este panel no reconoce."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.textPrimary,
                )
            }
            WorkspaceMetaLine("Programada para", if (state.editorPublishFrom.isBlank()) "Sin fecha" else newsDateLabel(state.editorPublishFrom))
            WorkspaceMetaLine("Publicada el", if (state.editorPublishedAt.isBlank()) "Nunca" else newsDateLabel(state.editorPublishedAt))
            WorkspaceMetaLine("Última edición", if (state.editorVersion.isBlank()) "Sin guardar" else newsDateLabel(state.editorVersion))
        }
        if (status == "scheduled") {
            WorkspaceNotice(
                tone = WorkspaceNoticeTone.WARNING,
                title = "La activación automática no está definida",
                detail = "Hoy una noticia programada no pasa sola a publicada cuando llega su fecha (decisión de producto pendiente). Cuando llegue el día, pulsa «Publicar ahora».",
            )
        }
    }

    NewsAppPreviewSection(state, contentWidth)

    FormSection(
        title = "Comprobaciones",
        description = newsChecklistSummary(checks),
        icon = AppIcon.CHECK,
        iconRole = SemanticRole.INFO,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            checks.forEach { NewsCheckItem(it) }
        }
    }

    if (status in setOf("draft", "scheduled") || state.isNew()) {
        FormSection(
            title = "Publicar",
            description = "Publicar es inmediato y definitivo: una noticia publicada ya no se edita. Si hay cambios sin guardar, se guardan antes.",
            icon = AppIcon.UPLOAD,
            iconRole = SemanticRole.INFO,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (problem != null) {
                    WorkspaceNotice(tone = WorkspaceNoticeTone.WARNING, title = "Todavía no se puede publicar", detail = problem)
                }
                PrimaryButton(
                    text = "Publicar ahora",
                    onClick = { onIntent(NewsIntent.RequestConfirm(NewsIntent.Publish)) },
                    enabled = canCommand && problem == null,
                    loading = busy && state.actionKind == NewsAction.PUBLISH,
                    icon = { AppIconImage(AppIcon.CHECK, size = 14.dp) },
                )
                NewsScheduleBlock(state, enabled = canCommand && problem == null, busy = busy, onIntent = onIntent)
            }
        }
    }

    if (saved) {
        FormSection(
            title = "Zona de riesgo",
            description = "Estas acciones cambian o borran lo guardado en el servidor y piden confirmación.",
            icon = AppIcon.WARNING,
            iconRole = SemanticRole.DANGER,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    if (status != "archived") {
                        DangerOutlineButton(
                            text = "Archivar",
                            onClick = { onIntent(NewsIntent.RequestConfirm(NewsIntent.Archive)) },
                            size = ButtonSize.COMPACT,
                            enabled = !busy && !state.isDirty,
                            loading = busy && state.actionKind == NewsAction.ARCHIVE,
                        )
                    }
                    DangerOutlineButton(
                        text = "Eliminar",
                        onClick = { onIntent(NewsIntent.RequestConfirm(NewsIntent.DeleteSelected)) },
                        size = ButtonSize.COMPACT,
                        enabled = !busy,
                        loading = busy && state.actionKind == NewsAction.DELETE,
                    )
                }
                if (state.isDirty && status != "archived") {
                    Text("Guarda o descarta los cambios antes de archivar: se archiva la versión del servidor.", style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
                }
            }
        }
    } else {
        Text(
            text = "Descartar un borrador que no existe en el servidor se hace desde la barra de guardado («Descartar»).",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary,
        )
    }
}

/** Fecha y hora UTC a mano: lo que el contrato pide es un instante futuro, y el servidor es quien lo valida. */
/** Lo que la seccion y el scroll de `WorkspaceEditor` se quedan del ancho de columna, mas el panel interior. */
private const val PREVIEW_INNER_INSET = 50 + 2 * 16

/**
 * El banner de Inicio de OposiBot a tamano REAL: 358 dp en el movil y 704 dp en la tableta (misma proporcion). Es la
 * unica superficie de la app donde cabe una noticia, y por eso el lienzo tiene exactamente su forma. La tableta se
 * ensena a su tamano si cabe en la columna; si no, encogida, y se dice.
 */
@Composable
private fun NewsAppPreviewSection(state: NewsState, contentWidth: Dp) {
    val tokens = GlassTheme.tokens
    val available = (contentWidth.value.toInt() - PREVIEW_INNER_INSET).coerceAtLeast(NEWS_BANNER_PHONE_WIDTH_DP)
    val tabletWidth = NEWS_BANNER_TABLET_WIDTH_DP.coerceAtMost(available)
    FormSection(
        title = "Así se verá en OposiBot",
        description = "El banner de Inicio a tamaño real. OposiBot todavía no enseña noticias: hoy su banner usa una imagen fija.",
        icon = AppIcon.VIEW,
        iconRole = SemanticRole.INFO,
    ) {
        WorkspacePanel(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text("Móvil · $NEWS_BANNER_PHONE_WIDTH_DP × $NEWS_BANNER_PHONE_HEIGHT_DP dp", style = MaterialTheme.typography.labelMedium, color = tokens.textSecondary)
            NewsCanvasPreview(state, NEWS_BANNER_PHONE_WIDTH_DP)
            Text(
                text = if (tabletWidth == NEWS_BANNER_TABLET_WIDTH_DP) {
                    "Tableta · $NEWS_BANNER_TABLET_WIDTH_DP × 291 dp"
                } else {
                    "Tableta · $NEWS_BANNER_TABLET_WIDTH_DP × 291 dp, encogida a $tabletWidth dp para caber aquí"
                },
                style = MaterialTheme.typography.labelMedium,
                color = tokens.textSecondary,
            )
            NewsCanvasPreview(state, tabletWidth)
        }
    }
}

@Composable
private fun NewsScheduleBlock(state: NewsState, enabled: Boolean, busy: Boolean, onIntent: (NewsIntent) -> Unit) {
    val tokens = GlassTheme.tokens
    val (initialDate, initialTime) = newsScheduleFields(state.editorPublishFrom)
    var date by remember(state.selectedId, state.editorPublishFrom) { mutableStateOf(initialDate) }
    var time by remember(state.selectedId, state.editorPublishFrom) { mutableStateOf(initialTime) }
    val iso = newsScheduleIso(date, time)
    val invalid = date.isNotBlank() && iso == null
    Column(modifier = Modifier.widthIn(max = Sizing.inputDescriptionMaxWidth), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text("Programar", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = tokens.textPrimary)
        WorkspaceEqualRow(columns = 2, gap = Spacing.sm) {
            LabeledField(label = "Fecha (dd/mm/aaaa)", value = date, onValueChange = { date = it }, placeholder = "20/11/2026", readOnly = !enabled, isError = invalid)
            LabeledField(label = "Hora UTC (hh:mm)", value = time, onValueChange = { time = it }, placeholder = "09:00", readOnly = !enabled, isError = invalid)
        }
        if (invalid) Text("Introduce una fecha y una hora válidas.", style = MaterialTheme.typography.bodySmall, color = tokens.danger)
        SecondaryButton(
            text = if (state.editorStatus.equals("scheduled", true)) "Cambiar la fecha" else "Programar",
            onClick = { iso?.let { onIntent(NewsIntent.RequestConfirm(NewsIntent.ScheduleAt(it))) } },
            enabled = enabled && iso != null,
            loading = busy && state.actionKind == NewsAction.SCHEDULE,
            icon = { AppIconImage(AppIcon.CLOCK, size = 14.dp) },
        )
        Text("La fecha tiene que ser futura (reloj del servidor). Se guarda tal cual; la activación automática no está definida.", style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
    }
}

@Composable
private fun NewsCheckItem(row: NewsCheckRow) {
    val tokens = GlassTheme.tokens
    Row(
        Modifier.fillMaxWidth().background(tokens.surfaceMuted, Radius.card).padding(Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        val color = newsCheckColor(tokens, row.tone)
        Box(Modifier.size(24.dp).background(color.copy(alpha = 0.12f), Radius.sm), contentAlignment = Alignment.Center) {
            AppIconImage(newsCheckIcon(row.tone), size = 16.dp, tint = color, contentDescription = newsCheckLabel(row.tone))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = tokens.textPrimary)
            Text(row.detail, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
        }
    }
}

private fun newsCheckIcon(tone: NewsCheckTone): AppIcon = when (tone) {
    NewsCheckTone.PASSED -> AppIcon.CHECK
    NewsCheckTone.BLOCKING -> AppIcon.CLOSE
    NewsCheckTone.NOTICE -> AppIcon.INFO
    NewsCheckTone.UNVERIFIED -> AppIcon.HELP
}

private fun newsCheckColor(tokens: GlassTokens, tone: NewsCheckTone): Color = when (tone) {
    NewsCheckTone.PASSED -> tokens.success
    NewsCheckTone.BLOCKING -> tokens.danger
    NewsCheckTone.NOTICE -> tokens.info
    NewsCheckTone.UNVERIFIED -> tokens.textSecondary
}
