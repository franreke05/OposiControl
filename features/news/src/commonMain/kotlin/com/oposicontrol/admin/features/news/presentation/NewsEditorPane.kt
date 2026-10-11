package com.oposicontrol.admin.features.news.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.DesktopTab
import com.oposicontrol.admin.core.ui.components.DesktopTabs
import com.oposicontrol.admin.core.ui.components.DesktopTabsVariant
import com.oposicontrol.admin.core.ui.components.EmptyStateCard
import com.oposicontrol.admin.core.ui.components.ErrorStateCard
import com.oposicontrol.admin.core.ui.components.GhostButton
import com.oposicontrol.admin.core.ui.components.LoadingStateCard
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.StatusDotChip
import com.oposicontrol.admin.core.ui.components.WorkspaceEditor
import com.oposicontrol.admin.core.ui.components.WorkspaceEditorHeader
import com.oposicontrol.admin.core.ui.components.WorkspaceNotice
import com.oposicontrol.admin.core.ui.components.WorkspaceNoticeTone
import com.oposicontrol.admin.core.ui.components.WorkspacePanel
import com.oposicontrol.admin.core.ui.components.WorkspacePill
import com.oposicontrol.admin.core.ui.components.WorkspaceSaveBar
import com.oposicontrol.admin.core.ui.components.WorkspaceSizing
import com.oposicontrol.admin.core.ui.components.isRetryable
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Spacing

/**
 * La ficha: cabecera con estado, tres pestanas y la barra de guardado anclada al pie. El cuerpo resuelve sus
 * estados —nada abierto, leyendo, error con reintento, ficha— antes de pintar la pestana.
 */
@Composable
internal fun NewsEditorPane(
    state: NewsState,
    onIntent: (NewsIntent) -> Unit,
    contentWidth: Dp,
    fillsHeight: Boolean,
    modifier: Modifier = Modifier,
) {
    val open = state.selectedId != null
    val ready = open && state.recordState is UiAsync.Success
    WorkspaceEditor(
        modifier = modifier,
        fillsHeight = fillsHeight,
        header = { NewsEditorHeader(state) },
        tabs = if (ready) {
            {
                val compactTabs = contentWidth < WorkspaceSizing.tabIconsMinWidth
                DesktopTabs(
                    tabs = listOf(
                        DesktopTab(NewsEditorTab.CONTENT.id, NewsEditorTab.CONTENT.label, AppIcon.EDIT.takeUnless { compactTabs }),
                        DesktopTab(NewsEditorTab.DESIGN.id, NewsEditorTab.DESIGN.label, AppIcon.LAYERS.takeUnless { compactTabs }, badge = state.editorLayers.size.takeIf { it > 0 }?.toString()),
                        DesktopTab(NewsEditorTab.PUBLICATION.id, NewsEditorTab.PUBLICATION.label, AppIcon.CLOCK.takeUnless { compactTabs }),
                    ),
                    selectedId = state.editorTab.id,
                    onSelect = { onIntent(NewsIntent.SelectTab(NewsEditorTab.fromId(it))) },
                    variant = DesktopTabsVariant.WORKSPACE,
                )
            }
        } else {
            null
        },
        footer = if (ready) {
            { NewsSaveBar(state, onIntent) }
        } else {
            null
        },
    ) {
        when {
            !open && (state.libraryState is UiAsync.Loading || state.libraryState is UiAsync.Idle) -> EmptyStateCard(
                title = "Cargando…",
                message = "La ficha se abre en cuanto llegue la biblioteca.",
                icon = AppIcon.NEWS,
            )
            !open -> EmptyStateCard(
                title = "Nada abierto",
                message = "Elige una noticia de la biblioteca o crea una nueva.",
                icon = AppIcon.NEWS,
                action = { PrimaryButton(text = "Nueva noticia", onClick = { onIntent(NewsIntent.CreateNew) }) },
            )
            state.recordState is UiAsync.Loading || state.recordState is UiAsync.Idle -> LoadingStateCard(rows = 7)
            state.recordState is UiAsync.Error -> {
                val error = (state.recordState as UiAsync.Error).error
                ErrorStateCard(
                    title = "No se pudo abrir la noticia",
                    message = error.newsMessage(),
                    onRetry = if (error.isRetryable()) ({ onIntent(NewsIntent.RetryRecord) }) else null,
                )
            }
            else -> {
                if (state.isReadOnly() && state.editorTab != NewsEditorTab.PUBLICATION) {
                    WorkspaceNotice(
                        tone = WorkspaceNoticeTone.INFO,
                        title = if (state.editorStatus.equals("archived", true)) "Archivada: solo lectura" else "Publicada: solo lectura",
                        detail = "El contrato no admite cambiar el contenido de una noticia publicada o archivada. Para cambiarla, archívala y crea una nueva.",
                    )
                }
                if (state.recoveredDraft && state.isDirty) {
                    WorkspaceNotice(
                        tone = WorkspaceNoticeTone.WARNING,
                        title = "Cambios recuperados de este equipo",
                        detail = "Se cerró OposiControl con esta noticia a medias. Lo recuperado NO está en el servidor: guárdalo o descártalo.",
                    )
                }
                when (state.editorTab) {
                    NewsEditorTab.CONTENT -> NewsContentTab(state, onIntent)
                    NewsEditorTab.DESIGN -> NewsDesignTab(state, onIntent, contentWidth)
                    NewsEditorTab.PUBLICATION -> NewsPublicationTab(state, onIntent, contentWidth)
                }
            }
        }
    }
}

@Composable
private fun NewsEditorHeader(state: NewsState) {
    val tokens = GlassTheme.tokens
    val open = state.selectedId != null
    WorkspaceEditorHeader(
        icon = AppIcon.NEWS,
        role = if (state.isNew()) SemanticRole.BRAND else SemanticRole.INFO,
        title = when {
            !open -> "Noticias"
            state.isNew() -> state.editorTitle.ifBlank { "Nueva noticia" }
            state.recordState is UiAsync.Success -> state.editorTitle.ifBlank { "Sin título" }
            else -> "Noticia"
        },
        subtitle = when {
            !open -> "Nada abierto."
            state.isNew() -> "Todavía no existe en el servidor: se crea al guardar."
            state.recordState is UiAsync.Loading -> "Leyendo la ficha…"
            state.recordState is UiAsync.Error -> "No se pudo leer."
            state.editorVersion.isNotBlank() -> "Última edición ${newsDateLabel(state.editorVersion)}"
            else -> null
        },
    ) {
        if (open && state.recordState is UiAsync.Success) {
            StatusDotChip(label = if (state.isNew()) "Sin crear" else newsStatusLabel(state.editorStatus), tone = if (state.isNew()) com.oposicontrol.admin.core.ui.components.StatusTone.ATTENTION else newsStatusTone(state.editorStatus))
            if (state.isDirty) WorkspacePill(text = "Sin guardar", icon = AppIcon.EDIT)
            val uploading = state.uploads.values.count { !it.failed }
            if (uploading > 0) WorkspacePill(text = if (uploading == 1) "Subiendo imagen" else "Subiendo $uploading imágenes", icon = AppIcon.UPLOAD)
        }
        if (open && state.recordState is UiAsync.Loading) {
            Text("Leyendo…", style = MaterialTheme.typography.labelMedium, color = tokens.textSecondary)
        }
    }
}

/**
 * La barra de guardado y, cuando el servidor rechazo la escritura por version o estado, el aviso de conflicto con
 * su unica salida honesta: releer la ficha. La barra no inventa un «guardar de todas formas»: no existe en el contrato.
 */
@Composable
private fun NewsSaveBar(state: NewsState, onIntent: (NewsIntent) -> Unit) {
    val status = state.saveStatus()
    val saving = state.actionState is UiAsync.Loading
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (status.conflict) {
            WorkspacePanel(borderColor = GlassTheme.tokens.danger.copy(alpha = 0.45f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    text = status.detail.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = GlassTheme.tokens.textSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    SecondaryButton(text = "Recargar del servidor", size = ButtonSize.COMPACT, onClick = { onIntent(NewsIntent.RetryRecord) })
                    Text(
                        text = "Lo escrito sigue en pantalla hasta que recargues.",
                        style = MaterialTheme.typography.labelMedium,
                        color = GlassTheme.tokens.textSecondary,
                    )
                }
            }
        }
        if (saving) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        WorkspaceSaveBar(
            message = status.message,
            primaryLabel = status.primaryLabel,
            onSave = { onIntent(NewsIntent.SaveDraft) },
            tone = status.tone,
            detail = if (status.conflict) null else status.detail,
            prominent = status.prominent,
            canSave = status.canSave,
            saving = saving,
            onDiscard = if (status.canDiscard) ({ onIntent(NewsIntent.Discard) }) else null,
        )
    }
}

/** Progreso o fallo de una subida de imagen, con reintento y descarte. */
@Composable
internal fun NewsUploadRow(upload: NewsUploadState, onIntent: (NewsIntent) -> Unit) {
    val tokens = GlassTheme.tokens
    WorkspacePanel(
        borderColor = if (upload.failed) tokens.danger.copy(alpha = 0.45f) else null,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (upload.failed) "No se pudo subir «${upload.fileName}»" else "Subiendo «${upload.fileName}» · ${(upload.progress * 100).toInt()} %",
                style = MaterialTheme.typography.bodySmall,
                color = if (upload.failed) tokens.dangerInk else tokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (upload.failed) {
            Text(text = upload.error.orEmpty(), style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                SecondaryButton(text = "Reintentar", size = ButtonSize.COMPACT, onClick = { onIntent(NewsIntent.RetryUpload(upload.target)) })
                GhostButton(text = "Quitar", size = ButtonSize.COMPACT, onClick = { onIntent(NewsIntent.DismissUpload(upload.target)) })
            }
        } else {
            LinearProgressIndicator(progress = { upload.progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}
