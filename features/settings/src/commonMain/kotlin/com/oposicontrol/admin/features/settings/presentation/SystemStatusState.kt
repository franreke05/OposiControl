package com.oposicontrol.admin.features.settings.presentation

import com.oposicontrol.admin.domain.model.SystemStatusLevel

data class SystemStatusUi(
    val overallStatus: SystemStatusLevel,
    val ktorStatus: SystemStatusLevel,
    val supabaseStatus: SystemStatusLevel,
    val workerStatus: SystemStatusLevel,
    val cpuPercent: Double?,
    val ramPercent: Double?,
    val apiLatencyMs: Long?,
    val errors24h: Long?,
    val mobileCrashes24h: Int?,
    val queueDepth: Int?,
    val updatedAtLabel: String,
)

sealed interface SystemStatusUiState {
    data object Loading : SystemStatusUiState
    data object NotConnected : SystemStatusUiState
    data class Content(val value: SystemStatusUi) : SystemStatusUiState
    data class Degraded(val value: SystemStatusUi) : SystemStatusUiState
    data class PartialData(val value: SystemStatusUi) : SystemStatusUiState
    data class Error(val message: String) : SystemStatusUiState
}

data class SystemStatusState(
    val uiState: SystemStatusUiState = SystemStatusUiState.NotConnected,
)
