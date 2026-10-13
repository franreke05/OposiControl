package com.oposicontrol.admin.features.audit.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.domain.model.AuditDetail
import com.oposicontrol.admin.domain.model.AuditMovement

data class AuditState(
    /** Lo que se pinta en el catálogo: los movimientos cargados que pasan el buscador local. */
    val movements: UiAsync<List<AuditMovement>> = UiAsync.Loading,
    /** Todo lo cargado del servidor con los filtros actuales, página a página, sin el buscador local. */
    val allMovements: List<AuditMovement> = emptyList(),
    /** Cursor de la página siguiente; `null` = no hay más que cargar. */
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    /** «Cargar más» falló: lo ya cargado sigue en pantalla y el fallo se dice junto al botón. */
    val loadMoreError: AppError? = null,
    /** Buscador LOCAL: solo mira lo ya cargado, no vuelve al servidor. */
    val searchQuery: String = "",
    /** Filtros de SERVIDOR. `null` = todas las acciones / todas las personas. */
    val actionFilter: String? = null,
    val actorFilter: String? = null,
    /** Lo tecleado en los campos de fecha (`AAAA-MM-DD`), valga o no todavía. */
    val fromDate: String = "",
    val untilDate: String = "",
    /** Las personas entre las que se puede filtrar: las vistas en lo cargado durante esta sesión. */
    val actorOptions: List<AuditActorOption> = emptyList(),
    val selectedId: String? = null,
    /** El antes y el después del movimiento abierto. `Idle` = todavía no se han pedido (se cargan bajo demanda). */
    val detail: UiAsync<AuditDetail> = UiAsync.Idle,
) {
    val selected: AuditMovement? get() = allMovements.firstOrNull { it.id == selectedId }

    /** Hay algún filtro de servidor puesto (o a medio poner): «vacío» significa entonces «ninguno así». */
    val hasServerFilters: Boolean
        get() = actionFilter != null || actorFilter != null || fromDate.isNotBlank() || untilDate.isNotBlank()

    val hasFilters: Boolean get() = hasServerFilters || searchQuery.isNotBlank()
}
