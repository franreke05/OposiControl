package com.oposicontrol.admin.features.audit.presentation

sealed interface AuditIntent {
    data object Refresh : AuditIntent
    data class UpdateSearch(val value: String) : AuditIntent
}
