package com.oposicontrol.admin.core.ui.components


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Spacing

/**
 * Bloque de formulario con cabecera.
 *
 * `help` es opcional y es el sitio natural donde colgar la explicación de un
 * concepto: la cabecera de la sección ya nombra de qué va el bloque, así que el
 * icono `i` al lado del título cubre todos sus campos de una vez en lugar de
 * repetirse campo a campo. Ésa es la regla de §17 — la ayuda va donde aporta, no
 * junto a cada palabra.
 */
@Composable
fun FormSection(
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    help: HelpTopic? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    GlassSurface(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(if (GlassTheme.isDesktop) Spacing.md else 22.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    help?.let { InfoHelpButton(it) }
                    if (action != null) {
                        Spacer(modifier = Modifier.weight(1f))
                        action()
                    }
                }
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            content()
        }
    }
}
