package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.AdminSessionInfo
import com.oposicontrol.admin.domain.model.SystemStatusAvailability

interface SystemStatusRepository {
    suspend fun loadStatus(): SystemStatusAvailability

    /**
     * La sesión tal como la ve el servidor. Vive aquí, y no en el repositorio de autenticación, porque solo la usa la
     * pestaña Estado y no cambia la sesión guardada: es una lectura, no un inicio de sesión.
     */
    suspend fun loadSession(): AppResult<AdminSessionInfo>
}
