package com.oposicontrol.admin.features.specialties.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.EmptyStateCard
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.SemanticIconContainer
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.SummaryCard
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing

/**
 * Impacto: qué hay detrás de esta especialidad, desglosado por tipo.
 *
 * El desglose por tipo es el dato que hace peligroso apagar un interruptor y por
 * eso tiene pestaña propia: un total no dice nada —«4 exámenes» no advierte de
 * nada—, mientras que «Ráfaga 3 · Inmersivo 1 · Razonado 0» dice exactamente qué
 * se está tocando al cerrar un tipo.
 *
 * **De dónde sale y con qué salvedad.** No hay endpoint que lo devuelva: se
 * calcula en cliente agrupando por `especialidad_id` y `tipo_examen` la lista
 * completa de exámenes, con el tipo canonizado, así que incluye borradores y
 * archivados. Esa etiqueta va en la cifra, no en una nota al pie: un recuento sin
 * ella se lee como «exámenes que la gente puede hacer».
 *
 * Lo que NO está: número de preguntas por especialidad (no se calcula en ningún
 * nivel), comunidades y etiquetas (no hay relación con la especialidad) y
 * cualquier fecha.
 */
@Composable
internal fun SpecialtyImpactSection(
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
        FormSection(
            title = "Exámenes de esta especialidad",
            description = "Cuántos hay de cada tipo. El recuento incluye borradores y archivados.",
            icon = AppIcon.EXAMS,
            iconRole = SemanticRole.INSIGHT,
        ) {
            if (state.selectedId == null) {
                EmptyStateCard(
                    title = "Sin especialidad abierta",
                    message = "Abre una especialidad del catálogo para ver qué exámenes tiene.",
                    icon = AppIcon.EXAMS,
                )
                return@FormSection
            }
            AsyncStatePane(
                state = state.exams,
                emptyTitle = "Sin exámenes",
                emptyMessage = "Todavía no hay ningún examen creado en el sistema.",
                emptyIcon = AppIcon.EXAMS,
                loadingRows = 3,
                onRetry = { onIntent(SpecialtiesIntent.Refresh) },
            ) { _ ->
                if (breakdown == null) {
                    EmptyStateCard(
                        title = "Sin especialidad abierta",
                        message = "Abre una especialidad del catálogo para ver qué exámenes tiene.",
                        icon = AppIcon.EXAMS,
                    )
                    return@AsyncStatePane
                }
                val draft = state.capabilitiesDraft
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    val cards = buildList<@Composable (Modifier) -> Unit> {
                        add { cardModifier ->
                            SummaryCard(
                                title = "Total",
                                value = breakdown.total.toString(),
                                supporting = SPECIALTY_EXAM_COUNT_CAVEAT_SHORT,
                                modifier = cardModifier,
                            )
                        }
                        SpecialtyExamType.entries.forEach { type ->
                            add { cardModifier ->
                                SummaryCard(
                                    title = type.label,
                                    value = breakdown.countOf(type).toString(),
                                    supporting = when {
                                        draft == null -> "Tipos sin cargar"
                                        type.isEnabled(draft) -> "Tipo abierto"
                                        breakdown.countOf(type) > 0 -> "Tipo cerrado, con exámenes"
                                        else -> "Tipo cerrado"
                                    },
                                    modifier = cardModifier,
                                )
                            }
                        }
                    }
                    // Cuatro tarjetas en fila cuando el editor da de sí; si no, dos
                    // y dos. Los anchos salen del reparto de celdas, no de pesos.
                    if (contentWidth >= SpecialtiesSizing.typeCardsThreeUpMinWidth) {
                        SpecialtyEqualRow(content = cards, gap = Spacing.sm, matchHeights = true)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            cards.chunked(2).forEach { pair ->
                                SpecialtyEqualRow(content = pair, gap = Spacing.sm, matchHeights = true)
                            }
                        }
                    }

                    if (breakdown.unknown > 0) {
                        SpecialtyNotice(
                            tone = SpecialtyNoticeTone.WARNING,
                            title = "${breakdown.unknown} sin tipo reconocido",
                            detail = "Su `tipo_examen` no es ráfaga, razonado ni inmersivo (ni el alias " +
                                "legacy `guiado`), así que ninguno de los tres interruptores los cubre.",
                        )
                    }

                    val off = specialtyTypesOffWithExams(breakdown, draft)
                    if (off.isNotEmpty()) {
                        SpecialtySolidPanel(
                            borderColor = specialtyNoticeColors(SpecialtyNoticeTone.WARNING, GlassTheme.tokens).border,
                            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                        ) {
                            Text(
                                text = "Tipos cerrados que tienen exámenes",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = GlassTheme.tokens.textPrimary,
                            )
                            off.forEach { entry ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                                ) {
                                    SemanticIconContainer(
                                        icon = entry.type.icon,
                                        role = SemanticRole.WARNING,
                                        size = Sizing.iconButtonSmall,
                                        iconSize = Sizing.icon,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${entry.type.label}: ${specialtyExamCountLabel(entry.count)}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = GlassTheme.tokens.textPrimary,
                                        )
                                        Text(
                                            text = "Al guardar, el editor de Exámenes dejará de aceptar este " +
                                                "tipo en esta especialidad. Lo ya publicado se sigue sirviendo.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = GlassTheme.tokens.textSecondary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        FormSection(
            title = "Hasta dónde llega esta pantalla",
            description = "Lo que estos interruptores hacen de verdad, y lo que no.",
            icon = AppIcon.INFO,
            iconRole = SemanticRole.INFO,
        ) {
            SpecialtySolidPanel(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                specialtyInformationLines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = GlassTheme.tokens.textSecondary,
                    )
                }
                if (state.exams is UiAsync.Error) {
                    Text(
                        text = "Ahora mismo el desglose no está disponible: la lista de exámenes no se ha " +
                            "podido leer, así que los recuentos de esta pestaña no se pueden calcular.",
                        style = MaterialTheme.typography.bodySmall,
                        color = GlassTheme.tokens.danger,
                    )
                }
            }
        }
    }
}
