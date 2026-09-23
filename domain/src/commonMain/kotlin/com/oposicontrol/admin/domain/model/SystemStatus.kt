package com.oposicontrol.admin.domain.model

import com.oposicontrol.admin.core.common.result.AppError
import kotlinx.datetime.Instant

enum class SystemStatusLevel {
    OPERATIVE,
    DEGRADED,
    DOWN,
    UNKNOWN,
}

/** Stable domain contract for the future Ktor system-status endpoint. */
data class SystemStatus(
    val overallStatus: SystemStatusLevel = SystemStatusLevel.UNKNOWN,
    val ktorStatus: SystemStatusLevel = SystemStatusLevel.UNKNOWN,
    val supabaseStatus: SystemStatusLevel = SystemStatusLevel.UNKNOWN,
    val workerStatus: SystemStatusLevel = SystemStatusLevel.UNKNOWN,
    val cpuPercent: Double? = null,
    val ramPercent: Double? = null,
    /** Ocupación del disco de datos de Supabase. `null` = no disponible. */
    val supabaseDiskPercent: Double? = null,
    val apiLatencyMs: Long? = null,
    val errors24h: Long? = null,
    val mobileCrashes24h: Int? = null,
    val queueDepth: Int? = null,
    val updatedAt: Instant? = null,
)

sealed interface SystemStatusAvailability {
    data class Available(val value: SystemStatus) : SystemStatusAvailability
    data object NotConnected : SystemStatusAvailability
    data class Failure(val error: AppError) : SystemStatusAvailability
}
