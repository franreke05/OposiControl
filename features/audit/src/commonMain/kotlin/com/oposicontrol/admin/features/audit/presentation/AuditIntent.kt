package com.oposicontrol.admin.features.audit.presentation

sealed interface AuditIntent {
    /** Relee la primera página con los filtros actuales. */
    data object Refresh : AuditIntent

    /** Buscador local sobre lo ya cargado. */
    data class UpdateSearch(val value: String) : AuditIntent

    /** Filtros de servidor. `null` quita el filtro. */
    data class SelectAction(val code: String?) : AuditIntent
    data class SelectActor(val actorId: String?) : AuditIntent
    data class UpdateFromDate(val value: String) : AuditIntent
    data class UpdateUntilDate(val value: String) : AuditIntent

    /** Quita el buscador y todos los filtros. */
    data object ClearFilters : AuditIntent

    /** Pide la página siguiente y la añade a lo cargado. */
    data object LoadMore : AuditIntent

    data class Select(val id: String) : AuditIntent

    /** Pide el antes y el después del movimiento abierto. */
    data object LoadDetail : AuditIntent
}
