package com.oposicontrol.backend

import kotlinx.serialization.Serializable

/**
 * Estado del sistema agregado en Ktor.
 *
 * La arquitectura acordada es logs / health / Sentry / Prometheus → agregación
 * EN Ktor → panel. El panel **nunca** lee logs ni métricas directamente: sólo
 * consume este resumen.
 *
 * Cada componente lleva su propio nivel, y lo que todavía no tiene fuente se
 * declara `UNKNOWN` en vez de `OPERATIVE`. Un panel que pinta todo en verde
 * porque no sabe nada es peor que uno que admite no saberlo: el primero se mira
 * y se cree.
 */
@Serializable
data class AdminSystemStatusDto(
    val overallStatus: String,
    val ktorStatus: String,
    val supabaseStatus: String,
    val workerStatus: String,
    val cpuPercent: Double? = null,
    val ramPercent: Double? = null,
    /** Ocupación del volumen de datos de Postgres en Supabase, en porcentaje. */
    val supabaseDiskPercent: Double? = null,
    val apiLatencyMs: Long? = null,
    val errors24h: Long? = null,
    val mobileCrashes24h: Int? = null,
    val queueDepth: Int? = null,
    val updatedAt: String,
    /**
     * Componentes y métricas sin fuente conectada todavía, para que el panel
     * pueda distinguir "no hay problema" de "no tengo forma de saberlo".
     */
    val notInstrumented: List<String> = emptyList(),
)

/** Niveles del contrato de estado, compartidos con el dominio del cliente. */
object SystemStatusLevelWire {
    const val OPERATIVE = "OPERATIVE"
    const val DEGRADED = "DEGRADED"
    const val DOWN = "DOWN"
    const val UNKNOWN = "UNKNOWN"
}

/** Claves de lo que aún no tiene fuente. */
object SystemStatusNotInstrumented {
    /** No hay worker asíncrono desplegado todavía. */
    const val WORKER = "workerStatus"

    /** CPU y memoria del host proceden de Node Exporter vía Prometheus. */
    const val CPU = "cpuPercent"
    const val MEMORY = "ramPercent"

    /**
     * El disco de datos procede del endpoint privilegiado de Supabase, no de
     * Prometheus: su ausencia es independiente de la de CPU y memoria.
     */
    const val SUPABASE_DISK = "supabaseDiskPercent"

    /** Latencia y errores proceden del histograma/contador HTTP de Ktor. */
    const val API_LATENCY = "apiLatencyMs"
    const val API_ERRORS = "errors24h"

    /** Requiere Sentry (o equivalente) recibiendo eventos de la app móvil. */
    const val MOBILE_CRASHES = "mobileCrashes24h"

    /** Requiere una cola real; hoy la importación es síncrona. */
    const val QUEUE_DEPTH = "queueDepth"
}

/**
 * Combina el estado de los componentes conocidos en un nivel global.
 *
 * Reglas, en orden:
 * - Si algún componente conocido está caído, el conjunto está caído.
 * - Si alguno está degradado, el conjunto está degradado.
 * - Si todos los conocidos están operativos, el conjunto está operativo — los
 *   desconocidos NO degradan el global, porque "no instrumentado" no es una
 *   incidencia y teñir todo de amarillo por ello haría que nadie mire el color.
 * - Si no se conoce ninguno, el conjunto es desconocido.
 */
internal fun aggregateSystemStatus(knownComponents: List<String>): String = when {
    knownComponents.isEmpty() -> SystemStatusLevelWire.UNKNOWN
    knownComponents.any { it == SystemStatusLevelWire.DOWN } -> SystemStatusLevelWire.DOWN
    knownComponents.any { it == SystemStatusLevelWire.DEGRADED } -> SystemStatusLevelWire.DEGRADED
    knownComponents.all { it == SystemStatusLevelWire.OPERATIVE } -> SystemStatusLevelWire.OPERATIVE
    else -> SystemStatusLevelWire.UNKNOWN
}

/**
 * Traduce el resultado de comprobar Supabase a un nivel.
 *
 * Una lectura lenta pero correcta es `DEGRADED`, no `DOWN`: el servicio responde
 * y la diferencia importa para quien decide si hay que actuar ahora.
 */
internal fun supabaseLevelFrom(reachable: Boolean, latencyMs: Long?, slowThresholdMs: Long): String = when {
    !reachable -> SystemStatusLevelWire.DOWN
    latencyMs != null && latencyMs > slowThresholdMs -> SystemStatusLevelWire.DEGRADED
    else -> SystemStatusLevelWire.OPERATIVE
}

@Serializable
data class AdminSystemStatusResponse(val data: AdminSystemStatusDto)
