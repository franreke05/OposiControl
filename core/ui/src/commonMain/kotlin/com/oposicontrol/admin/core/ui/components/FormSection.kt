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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.GlassTokens
import com.oposicontrol.admin.core.ui.theme.Sizing
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
    /**
     * Icono de la cabecera, dentro de su contenedor semántico.
     *
     * Opcional y `null` por defecto, y con `null` la cabecera se compone
     * exactamente igual que antes: los usos que ya existían —Configuración entre
     * ellos— no lo pasan y no cambian. Existe porque en un workspace de escritorio
     * cada bloque abre con el mismo contenedor de icono que el panel de Inicio y
     * que las columnas del propio workspace; sin él, el formulario era la única
     * superficie de la pantalla que no hablaba ese idioma.
     */
    icon: AppIcon? = null,
    /** Familia de color del icono. Sólo cuenta cuando hay [icon]. */
    iconRole: SemanticRole = SemanticRole.INFO,
    content: @Composable () -> Unit,
) {
    GlassSurface(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(if (GlassTheme.isDesktop) Spacing.md else 22.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (icon == null) {
                FormSectionHeader(title, description, help, action, Modifier)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    SemanticIconContainer(
                        icon = icon,
                        role = iconRole,
                        size = Sizing.iconButton,
                        iconSize = Sizing.icon,
                    )
                    FormSectionHeader(title, description, help, action, Modifier.weight(1f))
                }
            }
            content()
        }
    }
}

@Composable
private fun FormSectionHeader(
    title: String,
    description: String?,
    help: HelpTopic?,
    action: (@Composable () -> Unit)?,
    modifier: Modifier,
) {
    val tokens = GlassTheme.tokens
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = formSectionTitleColor(tokens),
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
                color = formSectionDescriptionColor(tokens),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Qué color pinta la cabecera de un bloque de formulario.
//
// Funciones y no un token suelto dentro del composable para que `ContrastTest`
// mida exactamente lo que se pinta. El título no pasaba `color` y heredaba el
// color de contenido por defecto, que era negro: `#000000` sobre la superficie
// sólida del tema oscuro, 1,72:1, en todos los `FormSection` de la aplicación.
// ---------------------------------------------------------------------------

/** El título del bloque: el dato principal de su cabecera. */
internal fun formSectionTitleColor(tokens: GlassTokens): Color = tokens.textPrimary

/**
 * La descripción bajo el título.
 *
 * Antes salía de `colorScheme.onSurfaceVariant`, que el tema alimenta con
 * `textSecondary` en los dos temas: el valor no cambia, cambia que ahora es el
 * token y no un rodeo por Material que el test no veía.
 */
internal fun formSectionDescriptionColor(tokens: GlassTokens): Color = tokens.textSecondary
