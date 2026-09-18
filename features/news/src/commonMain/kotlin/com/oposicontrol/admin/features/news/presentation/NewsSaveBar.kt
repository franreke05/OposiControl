package com.oposicontrol.admin.features.news.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.*
import com.oposicontrol.admin.core.ui.theme.GlassTheme

internal fun NewsState.canSendPublication(): Boolean = libraryState is UiAsync.Success &&
    actionState !is UiAsync.Loading && editorTitle.isNotBlank() && validationReport?.canPublish != false &&
    (specialties as? UiAsync.Success)?.value?.any { it.id == editorSpecialty } == true &&
    (newsTypes as? UiAsync.Success)?.value?.any { it.id == editorType } == true

@Composable
internal fun NewsSaveBar(state: NewsState, onIntent: (NewsIntent) -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    val busy = state.actionState is UiAsync.Loading
    GlassSurface(Modifier.fillMaxWidth().height(72.dp), tint = GlassTheme.tokens.surfaceSolid) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.saveLabel(), style = MaterialTheme.typography.labelLarge)
                Text(if (state.libraryState !is UiAsync.Success) "Publicación no disponible" else "El borrador local no cambia la versión publicada.", style = MaterialTheme.typography.labelSmall, color = GlassTheme.tokens.textSecondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Guardar borrador local", { onIntent(NewsIntent.SaveDraft) }, enabled = !busy)
                PrimaryButton(if (state.editorStatus == "published") "Publicar cambios" else "Publicar ahora", { confirm = true }, enabled = state.canSendPublication(), loading = busy)
            }
        }
    }
    if (confirm) ConfirmDialog(title = "Publicar noticia", message = "Se enviará esta versión al servidor con estado publicado y sin fecha futura.", confirmLabel = "Publicar", destructive = false, onConfirm = { confirm = false; onIntent(NewsIntent.Publish) }, onDismiss = { confirm = false })
}
