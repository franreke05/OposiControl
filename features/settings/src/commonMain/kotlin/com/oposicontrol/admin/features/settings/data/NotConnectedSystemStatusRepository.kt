package com.oposicontrol.admin.features.settings.data

import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.AdminSessionInfo
import com.oposicontrol.admin.domain.model.SystemStatusAvailability
import com.oposicontrol.admin.domain.repository.SystemStatusRepository

/** Explicit pre-observability adapter: it cannot manufacture production metrics. */
class NotConnectedSystemStatusRepository : SystemStatusRepository {
    override suspend fun loadStatus(): SystemStatusAvailability = SystemStatusAvailability.NotConnected

    /** Sin servidor no hay quién diga qué roles tiene la sesión: es un fallo declarado, no una sesión supuesta. */
    override suspend fun loadSession(): AppResult<AdminSessionInfo> =
        AppResult.Failure(AppError.Network("El panel no está conectado a un servidor."))
}
