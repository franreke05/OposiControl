package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.domain.model.SystemStatusAvailability

interface SystemStatusRepository {
    suspend fun loadStatus(): SystemStatusAvailability
}
