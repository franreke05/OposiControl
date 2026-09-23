package com.oposicontrol.admin.features.opositester.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.TesterReport

/** Único filtro secundario con soporte real: tipo_feedback (feedback_pregunta
 * no tiene columna de prioridad, así que ese filtro no se ofrece). */
data class TesterTypeOption(val value: String, val label: String)

enum class TesterMainFilter { PENDING, ALL }

data class TesterListState(
    val mainFilter: TesterMainFilter = TesterMainFilter.PENDING,
    val typeFilter: TesterTypeOption? = null,
    val knownTypes: List<TesterTypeOption> = emptyList(),
    val reports: UiAsync<List<TesterReport>> = UiAsync.Loading,
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    val refreshing: Boolean = false,
)
