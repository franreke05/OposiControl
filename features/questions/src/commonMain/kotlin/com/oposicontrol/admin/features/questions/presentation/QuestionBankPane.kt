package com.oposicontrol.admin.features.questions.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.DesktopIconButton
import com.oposicontrol.admin.core.ui.components.EmptyStateCard
import com.oposicontrol.admin.core.ui.components.ErrorStateCard
import com.oposicontrol.admin.core.ui.components.GhostButton
import com.oposicontrol.admin.core.ui.components.GlassSectionSurface
import com.oposicontrol.admin.core.ui.components.humanMessage
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.QuestionListItem

/**
 * El banco: buscar, acotar y elegir.
 *
 * Una sola búsqueda —la de esta columna— y una fila compacta de filtros: los que
 * de verdad filtran. «Tema» no está porque el contrato de preguntas no tiene tema
 * y su desplegable anterior no hacía nada ([QUESTION_NO_TOPIC_NOTE]).
 *
 * El error ya no se come el banco entero: la cabecera —búsqueda, filtros y
 * recuento— sigue en su sitio y el fallo baja a una tarjeta dentro de la lista,
 * así que se puede reintentar sin perder los filtros escritos.
 *
 * **Todo lo resuelve el servidor (KQ0-4).** La lista es una página, el recuento es
 * el TOTAL de la consulta y la paginación sale de ese total. Antes todo esto era
 * cliente sobre una descarga sin cota, y el recuento hablaba de lo cargado.
 */
@Composable
internal fun QuestionBankPane(
    state: QuestionsState,
    onIntent: (QuestionsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens
    val visible = state.visibleQuestions()
    val total = state.totalQuestions()
    val scopeNote = questionScopeNote(state, state.exams)

    GlassSectionSurface(
        modifier = modifier.fillMaxSize(),
        shape = Radius.xl,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = "Banco de preguntas",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = tokens.textPrimary,
        )

        QuestionSearchField(
            value = state.searchQuery,
            onValueChange = { onIntent(QuestionsIntent.Search(it)) },
        )

        // Dos filas, cuatro filtros reales —todos resueltos en el servidor—. Con la
        // etiqueta dentro del control caben sin rótulos que multipliquen la altura.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            QuestionSelect(
                selectedLabel = questionTypeFilterOptions().first { it.value == state.typeFilter }.label,
                options = questionTypeFilterOptions(),
                onSelect = { onIntent(QuestionsIntent.SetTypeFilter(it)) },
                accessibilityLabel = "Filtrar por tipo",
                modifier = Modifier.weight(1f),
            )
            QuestionSelect(
                selectedLabel = questionStatusFilterOptions().first { it.value == state.activeFilter }.label,
                options = questionStatusFilterOptions(),
                onSelect = { onIntent(QuestionsIntent.SetActiveFilter(it)) },
                accessibilityLabel = "Filtrar por estado",
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            QuestionSelect(
                selectedLabel = questionSpecialtyFilterOptions(state.specialties)
                    .firstOrNull { it.value == state.specialtyFilter }?.label
                    ?: "Todas las especialidades",
                options = questionSpecialtyFilterOptions(state.specialties),
                onSelect = { onIntent(QuestionsIntent.SetSpecialtyFilter(it)) },
                accessibilityLabel = "Filtrar por especialidad",
                modifier = Modifier.weight(1f),
            )
            QuestionSelect(
                selectedLabel = questionExamFilterOptions(state.exams)
                    .firstOrNull { it.value == state.examFilter }?.label
                    ?: "Todos los exámenes",
                options = questionExamFilterOptions(state.exams),
                onSelect = { onIntent(QuestionsIntent.SetExamFilter(it)) },
                accessibilityLabel = "Filtrar por examen",
                modifier = Modifier.weight(1f),
            )
        }

        if (scopeNote != null) {
            Text(
                text = scopeNote,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = questionCountLabel(total),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )
            // Con un examen elegido el orden es el del examen (su posición): un
            // selector de orden ahí prometería algo que no se aplica.
            if (state.examFilter == null) {
                QuestionSelect(
                    selectedLabel = questionSortLabel(state.sort),
                    options = QuestionsSort.entries.map { QuestionFilterOption(it, questionSortLabel(it)) },
                    onSelect = { onIntent(QuestionsIntent.SetSort(it)) },
                    accessibilityLabel = "Ordenar",
                    modifier = Modifier.width(150.dp),
                )
            }
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (val questions = state.questions) {
                is UiAsync.Loading, is UiAsync.Idle -> QuestionBankSkeleton()
                is UiAsync.Error -> ErrorStateCard(
                    message = questions.error.humanMessage(),
                    onRetry = { onIntent(QuestionsIntent.Refresh) },
                )
                is UiAsync.Empty -> EmptyStateCard(
                    title = QUESTION_BANK_EMPTY_TITLE,
                    message = QUESTION_BANK_EMPTY_MESSAGE,
                    icon = AppIcon.QUESTIONS,
                    action = {
                        GhostButton(
                            text = "Nueva pregunta",
                            size = ButtonSize.COMPACT,
                            onClick = { onIntent(QuestionsIntent.StartCreate) },
                        )
                    },
                )
                is UiAsync.Success -> if (visible.isEmpty()) {
                    // Vacío por filtros ≠ banco vacío.
                    Text(
                        text = QUESTION_BANK_NO_MATCHES,
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.textSecondary,
                        modifier = Modifier.padding(vertical = Spacing.lg),
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
                    ) {
                        visible.forEach { item ->
                            QuestionBankRow(
                                item = item,
                                selected = item.id == state.selectedQuestionId,
                                onClick = { onIntent(QuestionsIntent.SelectQuestion(item.id)) },
                            )
                        }
                    }
                }
            }
        }

        if (total > state.pageSize) {
            QuestionBankPagination(state, onIntent)
        }
    }
}

/**
 * Una fila del banco.
 *
 * Manda el enunciado —es lo que identifica una pregunta al leerla—, y debajo su
 * código humano, el tipo y el estado. La elegida lleva relleno, una barra coral a
 * la izquierda y `selected` en semántica: no se distingue sólo por color.
 *
 * El código sale de [questionCodeLabel]: si el backend no lo mandó, se dice. La
 * fila anterior pintaba `id.take(8)` —un trozo de UUID— en su lugar.
 */
@Composable
private fun QuestionBankRow(
    item: QuestionListItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = GlassTheme.tokens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radius.md)
            .background(if (selected) tokens.surfaceSelected else tokens.surfaceSolid)
            .border(1.dp, if (selected) tokens.accent.copy(alpha = 0.55f) else tokens.border, Radius.md)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .semantics {
                this.selected = selected
                contentDescription = "${questionCodeLabel(item.code)}. ${item.statement}. " +
                    "${questionTypeLabel(item.type)}. ${questionStatusLabel(item.active)}."
            }
            .height(androidx.compose.foundation.layout.IntrinsicSize.Min),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(if (selected) tokens.accentInk else androidx.compose.ui.graphics.Color.Transparent),
        )
        Column(
            modifier = Modifier.weight(1f).padding(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            Text(
                text = item.statement,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = tokens.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    text = questionCodeLabel(item.code),
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.textSecondary,
                )
                QuestionTypePill(item.type)
                if (item.position != null) {
                    Text(
                        text = questionExamLinkPositionLabel(item.position!!),
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.textSecondary,
                    )
                }
                Box(modifier = Modifier.weight(1f))
                if (!item.active) {
                    Text(
                        text = questionStatusLabel(active = false),
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.textSecondary,
                    )
                }
            }
        }
    }
}

/** Mientras carga, filas del alto real: la lista no salta al llegar los datos. */
@Composable
private fun QuestionBankSkeleton() {
    val tokens = GlassTheme.tokens
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        repeat(6) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(66.dp)
                    .clip(Radius.md)
                    .background(tokens.surfaceMuted),
            )
        }
    }
}

@Composable
private fun QuestionBankPagination(state: QuestionsState, onIntent: (QuestionsIntent) -> Unit) {
    val tokens = GlassTheme.tokens
    val totalPages = state.totalPages()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            DesktopIconButton(
                label = "Página anterior",
                onClick = { onIntent(QuestionsIntent.SetPageIndex((state.pageIndex - 1).coerceAtLeast(0))) },
                enabled = state.pageIndex > 0,
                size = 28.dp,
            ) { AppIconImage(AppIcon.CHEVRON_LEFT, size = 12.dp) }
            Text(
                text = "${state.pageIndex + 1} / $totalPages",
                style = MaterialTheme.typography.labelMedium,
                color = tokens.textSecondary,
            )
            DesktopIconButton(
                label = "Página siguiente",
                onClick = { onIntent(QuestionsIntent.SetPageIndex((state.pageIndex + 1).coerceAtMost(totalPages - 1))) },
                enabled = state.pageIndex < totalPages - 1,
                size = 28.dp,
            ) { AppIconImage(AppIcon.CHEVRON_RIGHT, size = 12.dp) }
        }
        QuestionSelect(
            selectedLabel = "${state.pageSize} por página",
            // Ninguno supera el máximo que admite el servidor (100).
            options = QUESTION_PAGE_SIZES.map { QuestionFilterOption(it, "$it por página") },
            onSelect = { onIntent(QuestionsIntent.SetPageSize(it)) },
            accessibilityLabel = "Preguntas por página",
            modifier = Modifier.width(150.dp),
        )
    }
}
