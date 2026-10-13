package com.oposicontrol.admin.features.settings.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.domain.model.AdminSessionInfo
import com.oposicontrol.admin.domain.model.SystemStatus
import kotlin.time.Instant

sealed interface SystemStatusUiState {
    data object Loading : SystemStatusUiState

    /** El panel no tiene servidor al que preguntar. No es un fallo: no hay nada que reintentar hasta configurarlo. */
    data object NotConnected : SystemStatusUiState

    /**
     * Una lectura del servidor y el momento en que el PANEL la recibió. «Última lectura» se cuenta desde [readAt] y no
     * desde la hora que declara el servidor, para que un reloj desajustado en cualquiera de los dos no la falsee.
     */
    data class Content(val status: SystemStatus, val readAt: Instant) : SystemStatusUiState

    data class Error(val error: AppError) : SystemStatusUiState
}

data class SystemStatusState(
    val uiState: SystemStatusUiState = SystemStatusUiState.Loading,
    /** Hay una lectura en curso. Con datos ya en pantalla se quedan a la vista mientras llega la nueva. */
    val refreshing: Boolean = false,
    /** La sesión según el SERVIDOR. Falla por separado: sin ella el resto del estado se sigue viendo. */
    val session: UiAsync<AdminSessionInfo> = UiAsync.Loading,
    val environmentLabel: String = "",
    val panelVersion: String = "",
)
