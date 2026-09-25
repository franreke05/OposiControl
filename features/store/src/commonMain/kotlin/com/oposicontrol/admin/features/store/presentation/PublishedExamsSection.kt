package com.oposicontrol.admin.features.store.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.DesktopVerticalScrollbar
import com.oposicontrol.admin.core.ui.components.EmptyStateCard
import com.oposicontrol.admin.core.ui.components.GlassChip
import com.oposicontrol.admin.core.ui.components.GlassSearchBar
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.components.StatusTone
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.domain.model.Exam

/** Alto exacto de la tarjeta. Fijo para que la rejilla no dependa de si un título ocupa una línea o dos. */
private val CardHeight = 292.dp
private val CardInfoPadding = 12.dp
private val CardTitleHeight = 40.dp
private val GridGap = 16.dp

/**
 * Escaparate de OposiMarket: todos los exámenes publicados con su portada.
 *
 * Es lo que el usuario final ve a la venta, así que solo enseña lo PUBLICADO
 * (ver [publishedExams]). Consulta, no edición: la portada se compone de los
 * datos del examen y estos se editan en Exámenes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PublishedExamsSection(
    state: MarketState,
    onIntent: (MarketIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    AsyncStatePane(
        state = state.exams,
        modifier = modifier,
        emptyTitle = "Sin exámenes publicados",
        emptyMessage = "Publica un examen desde Exámenes y aparecerá aquí con su portada.",
        emptyIcon = AppIcon.EXAMS,
        onRetry = { onIntent(MarketIntent.Refresh) },
    ) { published ->
        val counts = remember(published) { publishedExamCounts(published) }
        val shown = remember(published, state.examType, state.examQuery) {
            filterPublishedExams(published, state.examType, state.examQuery)
        }
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                GlassSearchBar(
                    value = state.examQuery,
                    onValueChange = { onIntent(MarketIntent.SetExamQuery(it)) },
                    placeholder = "Buscar por título, especialidad o código",
                    modifier = Modifier.width(360.dp),
                )
                ExamTypeFilter.entries.forEach { filter ->
                    GlassChip(
                        label = "${filter.label} · ${counts[filter] ?: 0}",
                        selected = state.examType == filter,
                        onClick = { onIntent(MarketIntent.SetExamTypeFilter(filter)) },
                    )
                }
            }
            if (shown.isEmpty()) {
                EmptyStateCard(
                    title = "Ningún examen coincide",
                    message = "Prueba con otra búsqueda o cambia el tipo de examen.",
                    icon = AppIcon.SEARCH,
                )
            } else {
                val scroll = rememberScrollState()
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 12.dp),
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(GridGap),
                            verticalArrangement = Arrangement.spacedBy(GridGap),
                        ) {
                            shown.forEach { exam -> PublishedExamCard(exam) }
                        }
                    }
                    DesktopVerticalScrollbar(
                        scrollState = scroll,
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun PublishedExamCard(exam: Exam) {
    val tokens = GlassTheme.tokens
    Column(
        modifier = Modifier
            .width(ExamCoverSpec.width)
            .height(CardHeight)
            .clip(Radius.card)
            .background(tokens.surfaceSolid)
            .border(1.dp, tokens.border, Radius.card),
    ) {
        ExamCoverArt(exam)
        Column(
            modifier = Modifier.padding(CardInfoPadding).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = exam.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = tokens.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.height(CardTitleHeight),
            )
            StatusChip(
                status = exam.accessLabel(),
                tone = if (exam.accessLabel() == "Gratuito") StatusTone.POSITIVE else StatusTone.NEUTRAL,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = exam.factsLine(),
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
