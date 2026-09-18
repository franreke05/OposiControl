package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GhostButton
import com.oposicontrol.admin.core.ui.components.GlassToggleRow
import com.oposicontrol.admin.core.ui.components.Help
import com.oposicontrol.admin.core.ui.components.LabeledField
import com.oposicontrol.admin.core.ui.components.SectionLink
import com.oposicontrol.admin.core.ui.components.SemanticIconContainer
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.Exam

/**
 * Quién puede abrir este examen, y nada más.
 *
 * Tres piezas, en el orden en que se decide y con el peso visual que le toca a
 * cada una:
 *
 *  - **Acceso al examen.** La decisión, en dos tarjetas con el mismo sistema que
 *    las de tipo en Configuración. Es lo primero y lo más grande porque es lo
 *    único que esta pestaña decide de verdad. Debajo, en letra secundaria, lo que
 *    el contrato sostiene: es un dato del examen, la consola no calcula qué
 *    usuario puede abrirlo, y sólo un Ráfaga gratuito se sirve sin sesión.
 *  - **Monetización.** El precio en OposiTickets, con la cifra formateada a la
 *    vista y el aviso de que hoy no desbloquea nada. El bloque NO desaparece al
 *    pasar a gratuito: se queda diciendo por qué no aplica.
 *  - **Visibilidad.** Una banda, no otro formulario: VISIBILIDAD ≠ ACCESO y el
 *    enlace a Publicación, que es donde se decide. Aquí no hay ningún control de
 *    visibilidad.
 *
 * **Elegir «Gratuito» ya no borra el precio.** Antes lanzaba además un
 * `UpdatePriceTickets("0")`, así que un precio guardado desaparecía sin que nadie
 * lo pidiera y sin rastro en pantalla. Ahora el cambio de acceso sólo cambia el
 * acceso; si queda un precio, el bloque de monetización lo enseña como lo que es
 * —guardado y sin efecto— y ofrece «Quitar precio» como acción explícita.
 *
 * Lo que no está: desbloqueos por plan o suscripción (`CanonicalEntitlementPolicy`
 * no la usa ningún flujo de acceso), `permite_demos` y `permite_oposimarket`
 * (capacidades de la especialidad, no del examen). Todo lo de esta pestaña viaja
 * con «Guardar cambios»: no hay ningún guardado propio.
 */
@Composable
internal fun ExamAccessSection(
    state: ExamsState,
    exam: Exam?,
    onIntent: (ExamsIntent) -> Unit,
    onOpenPublication: () -> Unit,
    contentWidth: Dp,
    modifier: Modifier = Modifier,
) {
    // Las dos decisiones caben una al lado de otra sólo cuando cada columna sigue
    // teniendo sitio para sus tarjetas; por debajo se apilan. El ancho llega del
    // único BoxWithConstraints de la pantalla: aquí no se vuelve a medir nada.
    val twoUp = contentWidth >= ExamAccessSizing.twoUpMinWidth
    val columnWidth = if (twoUp) (contentWidth - Spacing.md) / 2 else contentWidth

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (twoUp) {
            // Sin alto compartido a propósito. Igualarlos dejaba la Monetización de
            // un examen gratuito —una sola línea de contenido— dentro de una
            // tarjeta de 250dp vacía, sólo para empatar con la columna de al lado.
            // Cada bloque mide lo que tiene que decir.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                ExamAccessBlock(state, onIntent, columnWidth, Modifier.weight(1f))
                ExamMonetizationBlock(state, onIntent, Modifier.weight(1f))
            }
        } else {
            ExamAccessBlock(state, onIntent, columnWidth, Modifier.fillMaxWidth())
            ExamMonetizationBlock(state, onIntent, Modifier.fillMaxWidth())
        }

        ExamVisibilityBand(exam = exam, onOpenPublication = onOpenPublication)
    }
}

// ---------------------------------------------------------------------------
// 1 · Acceso al examen
// ---------------------------------------------------------------------------

@Composable
private fun ExamAccessBlock(
    state: ExamsState,
    onIntent: (ExamsIntent) -> Unit,
    columnWidth: Dp,
    modifier: Modifier,
) {
    val choices = examAccessChoices(state)
    val unknown = examAccessKind(state.editorTypeAccess) == ExamAccessKind.UNKNOWN
    val sideBySide = columnWidth >= ExamAccessSizing.cardsSideBySideMinWidth

    FormSection(
        title = "Acceso al examen",
        description = "Define cómo podrá obtenerse acceso al examen.",
        modifier = modifier,
        help = Help.tipoAcceso,
        icon = AppIcon.ACCESS,
        iconRole = SemanticRole.INFO,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (sideBySide) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    choices.forEach { choice ->
                        ExamAccessCard(
                            choice = choice,
                            onClick = { onIntent(ExamsIntent.UpdateTypeAccess(choice.wireValue)) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    choices.forEach { choice ->
                        ExamAccessCard(
                            choice = choice,
                            onClick = { onIntent(ExamsIntent.UpdateTypeAccess(choice.wireValue)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            // Secundario a propósito: es la verdad que impide leer esto como un
            // motor de permisos, no el protagonista de la sección.
            ExamPickerNotices(
                examAccessNotes(state).map { note ->
                    ExamPickerNoticeText(
                        message = note,
                        kind = if (unknown) ExamPickerNoticeKind.PROBLEM else ExamPickerNoticeKind.INFO,
                        canRetry = false,
                    )
                },
            )
        }
    }
}

/**
 * Una tarjeta de acceso.
 *
 * Las dos se pueden elegir siempre; sólo se pulsa la que no está elegida. La
 * elegida se marca con relleno, borde coral y un check coral, y su texto sigue en
 * `textPrimary` —el coral como texto no llega a 4,5:1 en claro—. El estado va
 * además en el `contentDescription`, para que no dependa del color.
 */
@Composable
private fun ExamAccessCard(
    choice: ExamAccessChoice,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val colors = examAccessCardColors(choice.selected, hovered && choice.selectable, tokens)
    val background by animateColorAsState(targetValue = colors.background, animationSpec = tween(140))
    val borderColor by animateColorAsState(targetValue = colors.border, animationSpec = tween(140))

    Column(
        modifier = modifier
            .clip(Radius.card)
            .background(background)
            .border(1.dp, borderColor, Radius.card)
            .then(
                if (choice.selectable) {
                    Modifier
                        .hoverable(interactionSource)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .semantics {
                selected = choice.selected
                contentDescription = examAccessCardDescription(choice)
            }
            .padding(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            SemanticIconContainer(
                icon = examAccessIcon(choice.kind),
                role = examAccessRole(choice.kind),
                size = Sizing.iconButton,
                iconSize = Sizing.iconLarge,
            )
            Text(
                text = choice.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.title,
                modifier = Modifier.weight(1f),
            )
            if (choice.selected) {
                AppIconImage(AppIcon.CHECK, size = Sizing.iconLarge, tint = colors.selectedMark)
            }
        }
        Text(
            text = choice.summary,
            style = MaterialTheme.typography.bodySmall,
            color = colors.summary,
        )
    }
}

private fun examAccessIcon(kind: ExamAccessKind): AppIcon = when (kind) {
    ExamAccessKind.FREE -> AppIcon.ACCESS
    ExamAccessKind.PREMIUM -> AppIcon.TICKETS
    ExamAccessKind.UNKNOWN -> AppIcon.HELP
}

private fun examAccessRole(kind: ExamAccessKind): SemanticRole = when (kind) {
    ExamAccessKind.FREE -> SemanticRole.INFO
    ExamAccessKind.PREMIUM -> SemanticRole.COMMERCE
    ExamAccessKind.UNKNOWN -> SemanticRole.NEUTRAL
}

// ---------------------------------------------------------------------------
// 2 · Monetización
// ---------------------------------------------------------------------------

@Composable
private fun ExamMonetizationBlock(
    state: ExamsState,
    onIntent: (ExamsIntent) -> Unit,
    modifier: Modifier,
) {
    FormSection(
        title = "Monetización",
        description = "Precio de este examen comprado suelto.",
        modifier = modifier,
        help = Help.precioExamen,
        icon = AppIcon.TICKETS,
        iconRole = SemanticRole.COMMERCE,
    ) {
        when (examPriceMode(state)) {
            ExamPriceMode.PREMIUM -> ExamPremiumPrice(state, onIntent)
            ExamPriceMode.FREE_WITH_PRICE -> ExamSavedPriceWithoutEffect(state, onIntent)
            ExamPriceMode.HIDDEN -> Text(
                text = examPriceEmptyNote(state),
                style = MaterialTheme.typography.bodySmall,
                color = GlassTheme.tokens.textSecondary,
            )
        }
    }
}

/** Premium: interruptor, cantidad y su lectura, y la deuda de desbloqueo una sola vez. */
@Composable
private fun ExamPremiumPrice(state: ExamsState, onIntent: (ExamsIntent) -> Unit) {
    val priceOn = examPriceEnabled(state.editorPriceTickets)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.field)) {
        GlassToggleRow(
            title = "Poner un precio",
            description = if (priceOn) null else "Sin precio en $EXAM_TICKETS_UNIT.",
            checked = priceOn,
            // Encender deja el campo VACÍO para escribir el precio: un «1» puesto
            // por la pantalla sería un precio que nadie ha decidido.
            onCheckedChange = { on -> onIntent(ExamsIntent.UpdatePriceTickets(if (on) "" else "0")) },
        )
        if (priceOn) {
            ExamFieldRow(
                { fieldModifier ->
                    LabeledField(
                        label = "Precio en $EXAM_TICKETS_UNIT",
                        value = state.editorPriceTickets,
                        onValueChange = { value -> onIntent(ExamsIntent.UpdatePriceTickets(value)) },
                        placeholder = "Ej. 1300",
                        supportingText = examPriceSupport(state.editorPriceTickets),
                        modifier = fieldModifier,
                        maxWidth = Dp.Unspecified,
                    )
                },
                { readingModifier -> ExamPriceReadingPanel(state.editorPriceTickets, readingModifier) },
                spans = ExamAccessSizing.priceSpans,
            )
        }
        ExamPickerNotices(
            listOf(
                ExamPickerNoticeText(
                    message = EXAM_PRICE_INERT_NOTICE,
                    kind = ExamPickerNoticeKind.INFO,
                    canRetry = false,
                ),
            ),
        )
    }
}

/**
 * La cantidad tal y como se lee: «1.577 OposiTickets».
 *
 * Da al precio la jerarquía que no tenía como texto de apoyo bajo el campo, y
 * pone la unidad donde se ve. Es SÓLO presentación: lo que viaja al guardar son
 * los dígitos del campo, sin puntuar.
 */
@Composable
private fun ExamPriceReadingPanel(editorPriceTickets: String, modifier: Modifier = Modifier) {
    val tokens = GlassTheme.tokens
    val reading = examPriceReading(editorPriceTickets)
    ExamSolidPanel(
        modifier = modifier,
        padding = PaddingValues(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = reading ?: "—",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            // Sin cifra no se pinta como un dato: es una ausencia.
            color = if (reading != null) tokens.textPrimary else tokens.textSecondary,
        )
        Text(
            text = if (reading != null) "Se guarda con el examen." else "Escribe la cantidad para verla aquí.",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary,
        )
    }
}

/**
 * Un precio guardado en un examen gratuito.
 *
 * No se borra solo y no se esconde: se dice que no hace nada y se ofrece quitarlo.
 * Tono INFO y no aviso ámbar — es un dato heredado, no un error que impida editar.
 */
@Composable
private fun ExamSavedPriceWithoutEffect(state: ExamsState, onIntent: (ExamsIntent) -> Unit) {
    val tokens = GlassTheme.tokens
    ExamSolidPanel(
        padding = PaddingValues(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            SemanticIconContainer(
                icon = AppIcon.INFO,
                role = SemanticRole.INFO,
                size = Sizing.iconButtonSmall,
                iconSize = 16.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = EXAM_PRICE_WITHOUT_EFFECT_TITLE,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.textPrimary,
                )
                Text(
                    text = examPriceWithoutEffect(state.editorPriceTickets),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                )
            }
            GhostButton(
                text = "Quitar precio",
                size = ButtonSize.COMPACT,
                onClick = { onIntent(ExamsIntent.UpdatePriceTickets("0")) },
            )
        }
        Text(
            text = "Se quita al guardar los cambios.",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary,
        )
    }
}

// ---------------------------------------------------------------------------
// 3 · Visibilidad — se decide en Publicación
// ---------------------------------------------------------------------------

/**
 * Banda informativa, no una tercera configuración.
 *
 * Sin chip de estado a propósito: el rail ya lleva «Estado» en el resumen del
 * examen y repetirlo aquí no añade nada. Lo que esta banda aporta es la relación
 * —el acceso no decide la visibilidad— y el camino al sitio donde sí se decide.
 */
@Composable
private fun ExamVisibilityBand(exam: Exam?, onOpenPublication: () -> Unit) {
    val tokens = GlassTheme.tokens
    val visibility = examVisibilitySummary(exam)
    ExamSolidPanel(
        padding = PaddingValues(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            SemanticIconContainer(
                icon = AppIcon.VIEW,
                role = SemanticRole.INFO,
                size = Sizing.iconButtonSmall,
                iconSize = 16.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Visibilidad",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.textPrimary,
                )
                Text(
                    text = "$EXAM_VISIBILITY_HEADLINE ${visibility.text}",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                )
            }
            SectionLink(text = EXAM_VISIBILITY_LINK, onClick = onOpenPublication)
        }
    }
}

private object ExamAccessSizing {
    /**
     * Ancho de editor a partir del cual Acceso y Monetización van uno al lado del
     * otro.
     *
     * **880.** Con dos columnas cada una recibe `(ancho - 16) / 2`: a 1920 el
     * editor mide ~940dp y cada columna ~462, que aguanta las dos tarjetas de
     * acceso en fila y el campo de precio con su lectura al lado. Por debajo —1440
     * da ~818 y 1366 ~754— las columnas bajarían de 400dp y las tarjetas quedarían
     * más estrechas que su propia etiqueta, así que se apilan.
     */
    val twoUpMinWidth: Dp = 880.dp

    /**
     * Ancho de COLUMNA a partir del cual las dos tarjetas de acceso caben en fila.
     *
     * Con 330 cada tarjeta dispone de ~159dp, suficiente para el icono, «Gratuito»
     * o «Premium» y su frase en dos líneas. Por debajo se apilan, que en una
     * columna estrecha se lee mejor de todos modos.
     */
    val cardsSideBySideMinWidth: Dp = 330.dp

    /** Precio y su lectura a mitades de la rejilla común de seis columnas. */
    val priceSpans: List<Int> = listOf(3, 3)
}
