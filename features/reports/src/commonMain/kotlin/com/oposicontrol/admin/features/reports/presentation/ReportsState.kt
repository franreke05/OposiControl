package com.oposicontrol.admin.features.reports.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.domain.model.ForumReport

data class ReportsState(
    val mainFilter: ReportsMainFilter = ReportsMainFilter.PENDING,
    val secondaryFilters: ReportSecondaryFilters = ReportSecondaryFilters(),
    /** Páginas ya leídas, en el orden del servidor (la cola: lo más antiguo primero). */
    val reports: UiAsync<List<ForumReport>> = UiAsync.Loading,
    val refreshing: Boolean = false,
    /** Cursor de la página siguiente que dio el servidor; null = no hay más. */
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    /** Fallo al pedir la página siguiente: lo ya leído se conserva y se ofrece reintentar. */
    val loadMoreError: AppError? = null,
    /** Total de pendientes contado por el servidor; null = no se pudo leer (nunca un 0 inventado). */
    val pendingTotal: Int? = null,
)
