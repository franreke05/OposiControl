package com.oposicontrol.admin.features.settings.data

import com.oposicontrol.admin.domain.model.SystemStatusAvailability
import com.oposicontrol.admin.domain.repository.SystemStatusRepository

/** Explicit pre-observability adapter: it cannot manufacture production metrics. */
class NotConnectedSystemStatusRepository : SystemStatusRepository {
    override suspend fun loadStatus(): SystemStatusAvailability = SystemStatusAvailability.NotConnected
}
