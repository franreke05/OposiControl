package com.oposicontrol.admin.features.specialties.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.GlassSectionSurface
import com.oposicontrol.admin.core.ui.components.HelpTopic
import com.oposicontrol.admin.core.ui.components.InfoHelpButton
import com.oposicontrol.admin.core.ui.components.SemanticIconContainer
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing

/**
 * Las piezas de cromo que comparten las tres columnas de Especialidades.
 *
 * Están aquí y no repetidas en cada fichero por la regla que gobierna el
 * sistema: **el marco es cristal (nivel 1) y el dato es opaco (nivel 2)**. Si
 * cada columna escribiera su propio contenedor, el día que una eligiera otro
 * padding la pantalla dejaría de leerse como una sola.
 */

/**
 * Contenedor de SECCIÓN: nivel 1 de cristal.
 *
 * El mismo marco que usa el workspace de Exámenes —contenedor translúcido con
 * icono semántico, título y acción a la derecha— para que las dos pantallas se
 * lean como la misma aplicación. Lo que va DENTRO es siempre opaco.
 */
@Composable
internal fun SpecialtiesSection(
    icon: AppIcon,
    role: SemanticRole,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    help: HelpTopic? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val text = specialtyGlassText(GlassTheme.tokens)
    GlassSectionSurface(
        modifier = modifier,
        shape = Radius.xl,
        contentPadding = SpecialtiesSizing.sectionPadding,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SemanticIconContainer(
                icon = icon,
                role = role,
                size = SpecialtiesSizing.sectionIconChip,
                iconSize = Sizing.icon,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = text.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    help?.let { InfoHelpButton(it) }
                }
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = text.secondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            action?.invoke()
        }
        content()
    }
}

/**
 * Superficie OPACA: nivel 2. Todo lo que lleva un dato o un campo vive aquí.
 *
 * No se apoya en `GlassSurface` para no arrastrar su sombra ni su brillo, y
 * porque este bloque necesita poder marcar un borde de color sin repintar el
 * relleno. Es la misma pieza que `ExamSolidPanel`.
 */
@Composable
internal fun SpecialtySolidPanel(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(Spacing.md),
    borderColor: Color? = null,
    background: Color? = null,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(Spacing.sm),
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = GlassTheme.tokens
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(Radius.card)
            .background(background ?: tokens.surfaceSolid)
            .border(1.dp, borderColor ?: tokens.border, Radius.card)
            .padding(padding),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/**
 * Una fila repartida a partes iguales, SIN `weight()` horizontal.
 *
 * Los anchos salen de [specialtyRowCells], que es una función pura y por eso
 * comprobable. El alto se iguala al del elemento más alto usando su **altura
 * intrínseca** —que se puede consultar sin gastar la medición— para que una fila
 * de tarjetas no quede escalonada cuando una descripción ocupa tres líneas y
 * otra dos.
 */
@Composable
internal fun SpecialtyEqualRow(
    content: List<@Composable (Modifier) -> Unit>,
    modifier: Modifier = Modifier,
    gap: Dp = Spacing.md,
    matchHeights: Boolean = true,
) {
    val gapPx = with(LocalDensity.current) { gap.roundToPx() }
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            content.forEach { item ->
                // `propagateMinConstraints = true` NO es un detalle: sin él el Box
                // crece hasta el alto igualado y la tarjeta de dentro se queda en su
                // alto natural, pegada arriba. Medido en la captura de 1920: las
                // tres tarjetas de tipo acababan a alturas distintas (521, 521 y
                // 543) y su línea de recuento no caía a la misma altura, que es
                // justo lo que este reparto existe para arreglar.
                Box(propagateMinConstraints = true) { item(Modifier.fillMaxWidth()) }
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val cells = specialtyRowCells(width, gapPx, measurables.size)
        val target = if (matchHeights) {
            measurables.mapIndexed { index, measurable ->
                measurable.maxIntrinsicHeight(cells[index].width)
            }.maxOrNull() ?: 0
        } else {
            0
        }
        val placeables = measurables.mapIndexed { index, measurable ->
            val cellWidth = cells[index].width
            measurable.measure(
                Constraints(
                    minWidth = cellWidth,
                    maxWidth = cellWidth,
                    minHeight = target,
                    maxHeight = maxOf(constraints.maxHeight, target),
                ),
            )
        }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            placeables.forEachIndexed { index, placeable -> placeable.placeRelative(cells[index].start, 0) }
        }
    }
}

/**
 * Interruptor con título y descripción, sobre superficie OPACA.
 *
 * No reutiliza `GlassToggleRow`: aquélla es una tarjeta de cristal con sombra,
 * pensada para el camino móvil, y aquí los interruptores viven dentro del
 * cristal del editor, donde una superficie translúcida sobre otra toma un color
 * distinto en cada punto de la pantalla.
 */
@Composable
internal fun SpecialtyToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    borderColor: Color? = null,
    leading: (@Composable () -> Unit)? = null,
    trailingBelow: (@Composable () -> Unit)? = null,
) {
    val tokens = GlassTheme.tokens
    SpecialtySolidPanel(
        modifier = modifier,
        padding = PaddingValues(Spacing.sm),
        borderColor = borderColor,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            leading?.invoke()
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.textPrimary,
                )
                if (!description.isNullOrBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary,
                    )
                }
            }
            Spacer(modifier = Modifier.width(Spacing.xxs))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = tokens.surfaceSolid,
                    checkedTrackColor = tokens.accent,
                    uncheckedThumbColor = tokens.surfaceSolid,
                    uncheckedTrackColor = tokens.textSecondary.copy(alpha = 0.4f),
                ),
            )
        }
        trailingBelow?.invoke()
    }
}

/**
 * Una fila de «Antes de guardar»: punto de estado, etiqueta y su porqué.
 *
 * El punto porta el estado en color y la etiqueta lo dice en palabras, así que
 * quien no distingue ámbar de rojo lee exactamente la misma información.
 */
@Composable
internal fun SpecialtyCheckRow(check: SpecialtyCheck, modifier: Modifier = Modifier) {
    val tokens = GlassTheme.tokens
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(7.dp)
                .clip(Radius.pill)
                .background(specialtyCheckDotColor(check.status, tokens)),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = check.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = tokens.textPrimary,
            )
            Text(
                text = check.detail,
                style = MaterialTheme.typography.bodySmall,
                color = specialtyCheckDetailColor(check.status, tokens),
            )
        }
    }
}

/**
 * Píldora de metadato sobre CRISTAL.
 *
 * Relleno blanco translúcido —el mismo de `StatusDotChip`— porque el color de
 * estado sumado a un fondo translúcido daría un tono distinto en cada punto de
 * la pantalla, que es justo lo que el nivel 2 existe para evitar.
 */
@Composable
internal fun SpecialtyGlassPill(text: String, modifier: Modifier = Modifier, icon: AppIcon? = null) {
    val tokens = GlassTheme.tokens
    val palette = specialtyGlassText(tokens)
    Row(
        modifier = modifier
            .clip(Radius.pill)
            .background(specialtyGlassPillFill(tokens))
            .border(1.dp, tokens.sectionGlassStroke, Radius.pill)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        icon?.let { AppIconImage(icon = it, size = 12.dp, tint = palette.secondary) }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = palette.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Píldora que DECLARA el alcance real de un ajuste: «Parcial» o «No aplicado».
 *
 * Lleva icono además de color, y la palabra dice lo mismo que el color: una
 * capacidad que se guarda pero no se aplica no se puede distinguir de una que sí
 * sólo por el tono.
 */
@Composable
internal fun SpecialtyDeclarationPill(declaration: SpecialtyDeclaration, modifier: Modifier = Modifier) {
    val colors = specialtyDeclarationPillColors(declaration, GlassTheme.tokens)
    Row(
        modifier = modifier
            .clip(Radius.pill)
            .background(colors.background)
            .border(1.dp, colors.border, Radius.pill)
            .padding(horizontal = Spacing.xs, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        AppIconImage(icon = declaration.icon, size = 12.dp, tint = colors.icon)
        Text(
            text = declaration.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.content,
            maxLines = 1,
        )
    }
}

/**
 * Píldora corta de aviso: marca ámbar y palabra.
 *
 * Distinta de [SpecialtyDeclarationPill] a propósito: aquélla declara hasta
 * dónde llega un ajuste que se guarda («Parcial», «No aplicado») y ésta marca un
 * riesgo del estado actual («Con exámenes»). Compartir píldora haría que las dos
 * cosas se leyeran como la misma.
 */
@Composable
internal fun SpecialtyWarningPill(label: String, modifier: Modifier = Modifier) {
    val colors = specialtyNoticeColors(SpecialtyNoticeTone.WARNING, GlassTheme.tokens)
    Row(
        modifier = modifier
            .clip(Radius.pill)
            .background(colors.background)
            .border(1.dp, colors.border, Radius.pill)
            .padding(horizontal = Spacing.xs, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        AppIconImage(
            icon = specialtyNoticeIcon(SpecialtyNoticeTone.WARNING),
            size = 12.dp,
            tint = colors.icon,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.content,
            maxLines = 1,
        )
    }
}

/**
 * Un aviso dentro del formulario: icono, frase y su porqué.
 *
 * Opaco y con el borde teñido, no un `Text` en rojo suelto: un aviso que no
 * tiene forma se lee como parte del texto de apoyo del campo anterior. El color
 * lo llevan el icono y el borde; la frase se lee en `textPrimary`.
 */
@Composable
internal fun SpecialtyNotice(
    tone: SpecialtyNoticeTone,
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    val tokens = GlassTheme.tokens
    val colors = specialtyNoticeColors(tone, tokens)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(Radius.card)
            .background(colors.background)
            .border(1.dp, colors.border, Radius.card)
            .padding(Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        AppIconImage(
            icon = specialtyNoticeIcon(tone),
            size = Sizing.icon,
            tint = colors.icon,
            modifier = Modifier.padding(top = 1.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.content,
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                )
            }
        }
    }
}

/** Un par etiqueta/valor de una línea, para meta que se lee y no se edita. */
@Composable
internal fun SpecialtyMetaLine(label: String, value: String, modifier: Modifier = Modifier) {
    val tokens = GlassTheme.tokens
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = tokens.textSecondary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textPrimary,
            modifier = Modifier.weight(1f),
        )
    }
}
