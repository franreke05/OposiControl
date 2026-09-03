package com.oposicontrol.admin.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.oposicontrol.admin.core.ui.theme.GlassTheme

/**
 * Glass-styled confirmation dialog for destructive actions.
 * ponytail: wraps ui.window.Dialog + GlassSurface so it matches the panel aesthetic
 * instead of a raw Material AlertDialog.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "Confirmar",
    dismissLabel: String = "Cancelar",
    destructive: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = GlassTheme.tokens
    // `dismissOnBackPress` cubre Esc en escritorio y el gesto atrás en móvil (§50).
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        GlassSurface(
            modifier = Modifier.widthIn(min = 280.dp, max = 420.dp),
            shape = RoundedCornerShape(24.dp),
            tint = tokens.glassFillStrong,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.textPrimary,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.textSecondary,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    // Cancelar como secundario y no como primario apagado: en un
                    // diálogo destructivo la salida segura tiene que leerse como
                    // un botón de verdad, no como el botón peligroso desactivado.
                    SecondaryButton(text = dismissLabel, onClick = onDismiss)
                    if (destructive) {
                        DangerButton(text = confirmLabel, onClick = onConfirm)
                    } else {
                        PrimaryButton(text = confirmLabel, onClick = onConfirm)
                    }
                }
            }
        }
    }
}
