package com.oposicontrol.admin.domain.model

import com.oposicontrol.admin.core.common.result.AppError
import kotlinx.datetime.Instant

enum class SystemStatusLevel {
    OPERATIVE,
    DEGRADED,
    DOWN,
    UNKNOWN,
}

/** Las métricas de rendimiento que el backend puede declarar sin lectura (`notInstrumented`). */
enum class SystemMetric {
    CPU,
    MEMORY,
    SUPABASE_DISK,
    API_LATENCY,
    API_ERRORS,
}

/**
 * Resumen del estado del sistema, tal como lo agrega el backend (`GET /api/v1/admin/estado-sistema`).
 *
 * Aquí no se calcula ningún nivel. Todo lo que puede faltar es nullable y sin relleno: `null` es «no se ha podido leer»,
 * nunca un cero. [prometheusResponding] y [notInstrumented] dicen POR QUÉ falta, para que la pantalla no llame igual a
 * «Prometheus no responde» y a «este despliegue no lo mide».
 */
data class SystemStatus(
    val overallStatus: SystemStatusLevel = SystemStatusLevel.UNKNOWN,
    val ktorStatus: SystemStatusLevel = SystemStatusLevel.UNKNOWN,
    val supabaseStatus: SystemStatusLevel = SystemStatusLevel.UNKNOWN,
    val cpuPercent: Double? = null,
    val ramPercent: Double? = null,
    /** Ocupación del disco de datos de Supabase. `null` = no disponible. */
    val supabaseDiskPercent: Double? = null,
    val apiLatencyMs: Long? = null,
    val errors24h: Long? = null,
    val updatedAt: Instant? = null,
    /** Una por réplica de Ktor. `null` = no se han podido leer (no es «cero réplicas»). */
    val replicas: List<SystemReplica>? = null,
    /** `null` = no se han podido leer; lista vacía = ninguna alerta activa. */
    val alerts: List<SystemAlert>? = null,
    val supabaseQuota: SupabaseQuota? = null,
    val deployedVersion: DeployedVersion? = null,
    /** `false` = Prometheus no contestó a nada. `null` = el backend no lo dice (versión anterior a este dato). */
    val prometheusResponding: Boolean? = null,
    val notInstrumented: Set<SystemMetric> = emptySet(),
)

data class SystemReplica(
    val name: String,
    /** `bot` (las que sirven a la app) o `control` (la del panel). */
    val profile: String? = null,
    val up: Boolean,
    /** Si esa réplica llega a Supabase. `null` = no lo publica (o está caída). */
    val seesSupabase: Boolean? = null,
    /** SHA de git que corre. `null` = no lo publica. */
    val version: String? = null,
    val startedAt: Instant? = null,
)

data class SystemAlert(
    val name: String,
    val severity: String? = null,
    val summary: String? = null,
    val replica: String? = null,
)

/** De qué ventana sale la proyección mensual: la semana entera, o solo el último día si no hay una semana de datos. */
enum class SupabaseProjectionBasis {
    SEVEN_DAYS,
    LAST_DAY,
}

/**
 * Tráfico de salida de Supabase visto desde el backend. Es un MÍNIMO: no incluye lo que la app o el panel descarguen
 * directamente de Supabase.
 */
data class SupabaseQuota(
    val bytes24h: Long? = null,
    val bytes7d: Long? = null,
    val projectedMonthlyBytes: Long? = null,
    val projectionBasis: SupabaseProjectionBasis? = null,
    /** El límite mensual del plan, tal como lo declara el backend. `null` = no lo ha dicho. */
    val monthlyLimitBytes: Long? = null,
    val byTarget: List<SupabaseQuotaTarget> = emptyList(),
)

data class SupabaseQuotaTarget(
    val target: String,
    val bytes24h: Long? = null,
    val bytes7d: Long? = null,
)

data class DeployedVersion(
    /** El SHA que corre en más réplicas. `null` = ninguna lo publica. */
    val sha: String? = null,
    /** Las réplicas en pie no corren todas lo mismo: un despliegue a medias. */
    val replicasDiffer: Boolean = false,
)

/** Quién usa el panel y con qué roles, según el SERVIDOR (`GET /api/v1/admin/sesion`). */
data class AdminSessionInfo(
    val userId: String,
    val displayName: String? = null,
    /** Códigos de rol canónicos (`superadmin`, `admin`, `moderador`…). */
    val roles: List<String> = emptyList(),
    val isSuperAdmin: Boolean = false,
    val isAdmin: Boolean = false,
)

sealed interface SystemStatusAvailability {
    data class Available(val value: SystemStatus) : SystemStatusAvailability
    data object NotConnected : SystemStatusAvailability
    data class Failure(val error: AppError) : SystemStatusAvailability
}
