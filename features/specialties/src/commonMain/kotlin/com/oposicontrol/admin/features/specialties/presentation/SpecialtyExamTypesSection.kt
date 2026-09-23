package com.oposicontrol.admin.features.specialties.presentation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.EmptyStateCard
import com.oposicontrol.admin.core.ui.components.FormFeedback
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.Help
import com.oposicontrol.admin.core.ui.components.LabeledField
import com.oposicontrol.admin.core.ui.components.SemanticIconContainer
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing

/**
 * Tipos de examen: qué puede existir de verdad en esta especialidad.
 *
 * Tres tarjetas —Ráfaga, Razonado, Inmersivo— y su interruptor, más el mensaje
 * de limitación. Debajo, y SEPARADO, lo comercial, que no es un tipo de examen y
 * no se puede leer como si lo fuera.
 *
 * Dos honestidades que esta pestaña sostiene:
 *
 *  - **Hasta dónde llegan los interruptores.** Los tres tipos restringen el
 *    guardado del editor de Exámenes y nada más: el backend no valida
 *    `tipo_examen` contra las capacidades, y ninguna ruta de la app lee
 *    `capacidades_especialidad`. Apagar un tipo no retira lo publicado.
 *  - **El mensaje de limitación es obligatorio con los tres apagados.** No es
 *    una preferencia de diseño: el servidor responde 422
 *    (`SPECIALTY_NO_MODES_WITHOUT_MESSAGE`). El aviso sale ANTES de pulsar
 *    guardar, no después de comerse el error.
 */
@Composable
internal fun SpecialtyExamTypesSection(
    state: SpecialtiesState,
    breakdown: SpecialtyExamBreakdown?,
    contentWidth: Dp,
    onIntent: (SpecialtiesIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (state.creatingNew) {
            // `PUT /especialidades/{id}/capacidades` necesita un id: no hay a
            // dónde escribir hasta que el alta devuelva uno. Decirlo es mejor que
            // enseñar tres interruptores que no se pueden guardar.
            FormSection(
                title = "Tipos de examen",
                description = "Qué tipos se pueden crear en esta especialidad.",
                help = Help.capacidadesEspecialidad,
                icon = AppIcon.LAYERS,
                iconRole = SemanticRole.INFO,
            ) {
                EmptyStateCard(
                    title = "Todavía no hay dónde guardarlos",
                    message = "Los tipos viven en su propia tabla y su endpoint necesita el identificador " +
                        "de la especialidad. Crea la especialidad y se cargan solos.",
                    icon = AppIcon.INFO,
                )
            }
            return@Column
        }

        FormSection(
            title = "Tipos de examen",
            description = "Qué tipos se pueden crear en esta especialidad. No todas tienen el mismo contenido.",
            help = Help.capacidadesEspecialidad,
            icon = AppIcon.LAYERS,
            iconRole = SemanticRole.INFO,
        ) {
            AsyncStatePane(
                state = state.capabilities,
                emptyTitle = "Sin tipos configurados",
                emptyMessage = "Esta especialidad no tiene configuración de tipos.",
                emptyIcon = AppIcon.LAYERS,
                loadingRows = 3,
                // Faltaba: un fallo de la tabla de capacidades dejaba la sección
                // muerta, sin forma de volver a intentarlo.
                onRetry = { onIntent(SpecialtiesIntent.Refresh) },
            ) { _ ->
                val draft = state.capabilitiesDraft
                if (draft == null) {
                    EmptyStateCard(
                        title = "Sin especialidad abierta",
                        message = "Abre una especialidad del catálogo para ver sus tipos.",
                        icon = AppIcon.LAYERS,
                    )
                    return@AsyncStatePane
                }

                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (!draft.configured) {
                        // Nadie ha decidido esto todavía: lo que se ve son los
                        // valores por defecto del esquema, no una elección.
                        SpecialtyNotice(
                            tone = SpecialtyNoticeTone.INFO,
                            title = "Todavía sin configurar",
                            detail = "Esta especialidad no tiene fila propia en `capacidades_especialidad`. " +
                                "Lo que se ve son los valores por defecto; guardar los fija.",
                        )
                    }

                    val cards = SpecialtyExamType.entries.map { type ->
                        val body: @Composable (Modifier) -> Unit = { cardModifier ->
                            SpecialtyTypeCard(
                                type = type,
                                enabled = type.isEnabled(draft),
                                examCount = breakdown?.countOf(type),
                                examsUnknown = state.exams is UiAsync.Error,
                                onToggle = { value ->
                                    onIntent(SpecialtiesIntent.ToggleCapability(type.toggle, value))
                                },
                                modifier = cardModifier,
                            )
                        }
                        body
                    }
                    if (contentWidth >= SpecialtiesSizing.typeCardsThreeUpMinWidth) {
                        SpecialtyEqualRow(content = cards, gap = Spacing.md)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            cards.forEach { card -> card(Modifier.fillMaxWidth()) }
                        }
                    }

                    SpecialtyMetaLine(
                        label = "Tipos abiertos",
                        value = if (draft.hasNoExamModes) {
                            "Ninguno de los tres"
                        } else {
                            SpecialtyExamType.entries
                                .filter { it.isEnabled(draft) }
                                .joinToString(" · ") { it.label }
                        },
                    )

                    if (draft.hasNoExamModes) {
                        SpecialtyNotice(
                            tone = if (draft.limitationMessage.isNullOrBlank()) {
                                SpecialtyNoticeTone.DANGER
                            } else {
                                SpecialtyNoticeTone.WARNING
                            },
                            title = if (draft.limitationMessage.isNullOrBlank()) {
                                "Sin ningún tipo abierto hace falta el mensaje de limitación"
                            } else {
                                "Sin ningún tipo abierto, el usuario sólo verá el mensaje"
                            },
                            detail = if (draft.limitationMessage.isNullOrBlank()) {
                                "El servidor rechaza el guardado con un 422 mientras el mensaje esté vacío."
                            } else {
                                "En esta especialidad no se podrá crear ningún examen nuevo desde el panel."
                            },
                        )
                    }

                    LabeledField(
                        label = "Mensaje de limitación",
                        value = draft.limitationMessage.orEmpty(),
                        onValueChange = { onIntent(SpecialtiesIntent.UpdateLimitationMessage(it)) },
                        placeholder = "Ej. Contenido inmersivo en preparación para esta especialidad",
                        singleLine = false,
                        minLines = 2,
                        required = draft.hasNoExamModes,
                        supportingText = if (draft.hasNoExamModes) {
                            "Obligatorio ahora mismo: con los tres tipos apagados el servidor no guarda sin él."
                        } else {
                            "Explicación que acompaña a una especialidad con contenido limitado."
                        },
                    )
                }
            }
        }

        // -------------------------------------------------------------------
        // Comercial
        //
        // Bloque APARTE, no una cuarta y una quinta tarjeta junto a los tipos:
        // demos y market no habilitan un tipo de examen, habilitan otras
        // superficies. Y sobre todo, ninguno de los dos hace hoy lo que su
        // nombre promete, así que van con su alcance declarado en una píldora.
        // -------------------------------------------------------------------
        if (state.capabilitiesDraft != null) {
            val draft = state.capabilitiesDraft
            FormSection(
                title = "Comercial",
                description = "Dos ajustes que se guardan y que hoy no llegan a aplicarse.",
                icon = AppIcon.MARKET,
                iconRole = SemanticRole.COMMERCE,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    SpecialtyCommercialCapability.entries.forEach { capability ->
                        val checked = when (capability) {
                            SpecialtyCommercialCapability.DEMOS -> draft.permiteDemos
                            SpecialtyCommercialCapability.OPOSIMARKET -> draft.permiteOposimarket
                        }
                        SpecialtyToggleRow(
                            title = capability.label,
                            description = capability.description,
                            checked = checked,
                            onCheckedChange = { value ->
                                onIntent(SpecialtiesIntent.ToggleCapability(capability.toggle, value))
                            },
                            leading = {
                                SemanticIconContainer(
                                    icon = capability.icon,
                                    role = SemanticRole.COMMERCE,
                                    size = Sizing.iconButton,
                                    iconSize = Sizing.icon,
                                )
                            },
                            trailingBelow = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    SpecialtyDeclarationPill(capability.declaration)
                                    Text(
                                        text = capability.declarationDetail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = GlassTheme.tokens.textSecondary,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }

        FormFeedback(
            state = state.capabilitiesActionState,
            loadingText = "Escribiendo en capacidades_especialidad…",
            successText = "Tipos de examen guardados en capacidades_especialidad.",
        )
    }
}

/**
 * Una tarjeta de tipo de examen.
 *
 * Lleva su interruptor, su descripción real y su recuento de exámenes, porque
 * los tres se leen juntos: el interruptor es la decisión, la descripción es qué
 * significa y el recuento es lo que está en juego. Apagado con exámenes detrás,
 * la tarjeta se tiñe de aviso y lo dice con palabras además de con el borde.
 */
@Composable
private fun SpecialtyTypeCard(
    type: SpecialtyExamType,
    enabled: Boolean,
    examCount: Int?,
    examsUnknown: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens
    val hasExams = (examCount ?: 0) > 0
    val colors = specialtyTypeCardColors(enabled, hasExams, tokens)
    val background by animateColorAsState(targetValue = colors.background, animationSpec = tween(140))
    val borderColor by animateColorAsState(targetValue = colors.border, animationSpec = tween(140))

    Column(
        modifier = modifier
            .clip(Radius.card)
            .background(background)
            .border(1.dp, borderColor, Radius.card)
            .padding(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            // El tinte semántico sólo con el tipo abierto: apagado a neutro es
            // parte del mensaje de «esto no existe aquí».
            SemanticIconContainer(
                icon = type.icon,
                role = if (enabled) type.role else SemanticRole.NEUTRAL,
                size = Sizing.iconButton,
                iconSize = Sizing.iconLarge,
            )
            Text(
                text = type.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.title,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = tokens.surfaceSolid,
                    checkedTrackColor = tokens.accent,
                    uncheckedThumbColor = tokens.surfaceSolid,
                    uncheckedTrackColor = tokens.textSecondary.copy(alpha = 0.4f),
                ),
            )
        }
        Text(
            text = type.description,
            style = MaterialTheme.typography.bodySmall,
            color = colors.description,
            // Tres líneas fijas y no las que pida cada texto: las descripciones
            // miden 2, 2 y 3 líneas, y con el alto libre el recuento caía a una
            // altura distinta en cada tarjeta —medido en la captura de 1920: 525,
            // 591 y 525— dentro de una fila que precisamente iguala los altos.
            minLines = 3,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                text = when {
                    examsUnknown -> "Recuento no disponible"
                    examCount == null -> "Contando…"
                    else -> specialtyExamCountLabel(examCount)
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.description,
            )
            if (!enabled && hasExams) {
                // «Con exámenes» y no la píldora de alcance («Parcial»): esa
                // declara hasta dónde llega un ajuste comercial, y reutilizarla
                // aquí diría que este tipo se aplica a medias, que no es lo que
                // pasa.
                SpecialtyWarningPill(label = "Con exámenes")
            }
        }
        if (!enabled && hasExams) {
            Text(
                text = "Apagado con exámenes: el editor de Exámenes dejará de aceptar este tipo al guardar.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.description,
            )
        }
    }
}
