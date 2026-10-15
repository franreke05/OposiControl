package com.oposicontrol.admin.features.forum.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.domain.model.ForumThread

data class ForumState(
    val mainFilter: ForumMainFilter = ForumMainFilter.RECENT,
    val categoryFilter: ForumCategoryOption? = null,
    val knownCategories: List<ForumCategoryOption> = emptyList(),
    val threads: UiAsync<List<ForumThread>> = UiAsync.Loading,
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    /** Fallo al pedir la página siguiente: lo leído se conserva, se dice y se ofrece reintentar. */
    val loadMoreError: AppError? = null,
    val refreshing: Boolean = false,
    /** null = todavía no se resolvió, o falló de forma no bloqueante (la banner
     * de "reportes pendientes" simplemente no se muestra en ese caso). Nunca 0
     * por defecto para no arriesgarse a mostrar "0 reportes pendientes". */
    val pendingReportsCount: Int? = null,
    /** El conteo de pendientes falló: se dice y se ofrece reintentar (antes se ocultaba en silencio). */
    val pendingReportsCountFailed: Boolean = false,
)
