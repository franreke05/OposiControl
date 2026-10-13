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
    /**
     * Sin uso: no existe ningún worker. Se conserva SOLO porque los paneles ya instalados declaran el campo; con su valor
     * por defecto no viaja por el cable (el JSON del backend no codifica valores por defecto).
     */
    val workerStatus: String = SystemStatusLevelWire.UNKNOWN,
    val cpuPercent: Double? = null,
    val ramPercent: Double? = null,
    /** Ocupación del volumen de datos de Postgres en Supabase, en porcentaje. */
    val supabaseDiskPercent: Double? = null,
    val apiLatencyMs: Long? = null,
    val errors24h: Long? = null,
    /** Sin uso, igual que [workerStatus]: no hay fuente de crashes móviles ni cola de trabajos. Nunca se rellenan. */
    val mobileCrashes24h: Int? = null,
    val queueDepth: Int? = null,
    val updatedAt: String,
    /**
     * Métricas que este despliegue no ha podido leer (su clave en [SystemStatusNotInstrumented]), para que el panel
     * pueda distinguir "no hay problema" de "no tengo forma de saberlo". Solo aparece lo que DEPENDE DEL DESPLIEGUE
     * —Node Exporter, el endpoint privilegiado de Supabase, el histograma HTTP—; lo que no existe en el producto
     * (worker, crashes móviles, cola) ya no se declara aquí como una ausencia perpetua.
     */
    val notInstrumented: List<String> = emptyList(),
    /**
     * Una fila por réplica de Ktor que Prometheus raspa. `null` = no se ha podido leer (Prometheus no responde o no
     * tiene la serie); nunca una lista vacía de relleno.
     */
    val replicas: List<AdminSystemReplicaDto>? = null,
    /** Alertas disparadas ahora mismo. `null` = no se ha podido leer; lista vacía = ninguna alerta activa. */
    val alertas: List<AdminSystemAlertDto>? = null,
    /** Tráfico de salida de Supabase que pasa por el backend. `null` = no se ha podido leer ninguna ventana. */
    val cuotaSupabase: AdminSupabaseQuotaDto? = null,
    /** Versión desplegada, según lo que declara cada réplica. `null` = no se ha podido leer. */
    val version: AdminSystemVersionDto? = null,
    /** De dónde sale lo anterior. `null` solo en un backend anterior a este campo. */
    val fuente: AdminSystemSourceDto? = null,
)

/** Una réplica de Ktor, tal como la ve Prometheus. */
@Serializable
data class AdminSystemReplicaDto(
    val nombre: String,
    /** `bot` (la app) o `control` (este panel). `null` si el scrape no lo etiqueta. */
    val perfil: String? = null,
    val arriba: Boolean,
    /** Si la última sonda de ESA réplica a Supabase respondió. `null` = la réplica no publica el dato (o está caída). */
    val veSupabase: Boolean? = null,
    /** SHA de git horneado en la imagen. `null` = la réplica no lo publica (imagen anterior a `build_info`, o caída). */
    val version: String? = null,
    /** Instante ISO-8601 en que arrancó el proceso. `null` = no disponible. */
    val arrancadaEn: String? = null,
)

/** Una alerta de `ops/monitoring/prometheus/alert_rules.yml` en estado `firing`. */
@Serializable
data class AdminSystemAlertDto(
    val nombre: String,
    val severidad: String? = null,
    /** Solo si la serie lo trae como etiqueta: las anotaciones de una regla NO viajan en la serie `ALERTS`. */
    val resumen: String? = null,
    /** Réplica afectada, cuando la regla es por réplica. */
    val replica: String? = null,
)

/**
 * Tráfico de salida de Supabase, medido en el backend ([UPSTREAM_WIRE_BYTES_METRIC]).
 *
 * Es un MÍNIMO: solo cuenta lo que el backend recibe de Supabase. Lo que la app o el panel descarguen directamente de
 * Storage, y lo que Supabase cuente por su lado, no pasa por aquí. Sirve para ver venir el límite, no para cuadrar la
 * factura.
 */
@Serializable
data class AdminSupabaseQuotaDto(
    val bytes24h: Long? = null,
    val bytes7d: Long? = null,
    /** Ver [projectMonthlySupabaseEgressBytes]. `null` si no hay ninguna ventana medida. */
    val proyeccionMensualBytes: Long? = null,
    /** Ventana de la que sale la proyección: [SupabaseQuotaWire.BASE_7D] o [SupabaseQuotaWire.BASE_24H]. */
    val proyeccionBase: String? = null,
    /**
     * [SUPABASE_MONTHLY_EGRESS_LIMIT_BYTES]. Sin valor por defecto a propósito: el JSON del backend no codifica los
     * valores por defecto, y el límite tiene que viajar siempre para que el panel no lo suponga.
     */
    val limiteMensualBytes: Long,
    val porDestino: List<AdminSupabaseQuotaTargetDto> = emptyList(),
)

/** Un destino de Supabase: `rest`, `auth`, `storage` u `other` (ver `upstreamTargetOf`). */
@Serializable
data class AdminSupabaseQuotaTargetDto(
    val destino: String,
    val bytes24h: Long? = null,
    val bytes7d: Long? = null,
)

@Serializable
data class AdminSystemVersionDto(
    /** El SHA que declaran más réplicas en pie. `null` si ninguna lo publica. */
    val sha: String? = null,
    /** Las réplicas en pie no declaran todas la misma versión: un despliegue a medias. */
    val replicasDifieren: Boolean = false,
)

@Serializable
data class AdminSystemSourceDto(
    /** `false` = Prometheus no contestó a NINGUNA consulta: lo que sale de él falta por eso, no por estar a cero. */
    val prometheusResponde: Boolean,
)

/**
 * Tráfico de salida mensual incluido en el plan gratuito de Supabase: 5 GB (decimales, igual que están escritos los
 * umbrales de `ops/monitoring/prometheus/alert_rules.yml`). Si el proyecto cambia de plan, este es el único número que
 * hay que tocar.
 */
const val SUPABASE_MONTHLY_EGRESS_LIMIT_BYTES: Long = 5_000_000_000L

object SupabaseQuotaWire {
    const val BASE_7D = "7d"
    const val BASE_24H = "24h"
}

/** Niveles del contrato de estado, compartidos con el dominio del cliente. */
object SystemStatusLevelWire {
    const val OPERATIVE = "OPERATIVE"
    const val DEGRADED = "DEGRADED"
    const val DOWN = "DOWN"
    const val UNKNOWN = "UNKNOWN"
}

/**
 * Claves de lo que no tiene fuente.
 *
 * `/estado-sistema` solo usa las que dependen del despliegue (CPU, memoria, disco, latencia y errores). [WORKER],
 * [MOBILE_CRASHES] y [QUEUE_DEPTH] se conservan porque el bloque de sistema del panel de Inicio todavía las declara.
 */
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

/**
 * Nivel de Ktor a partir de sus réplicas.
 *
 * Antes era una constante `OPERATIVE` («si esta línea se ejecuta, Ktor responde»): cierto para la réplica que atiende al
 * panel y falso para el producto, porque la app la sirven OTRAS réplicas. Con las dos de la app caídas, el panel seguía
 * en verde.
 *
 * - Sin lectura (Prometheus no responde, o no tiene la serie) es `UNKNOWN`, NUNCA verde.
 * - Todas en pie, `OPERATIVE`.
 * - Ninguna de las que sirven a la app en pie, `DOWN`. Son las de perfil [APP_REPLICA_PROFILE]; si el scrape no etiqueta
 *   ese perfil (un solo Ktor), cuentan todas.
 * - Cualquier otro caso, `DEGRADED`: hay servicio, con menos margen.
 */
internal fun ktorLevelFromReplicas(replicas: List<ReplicaReading>?): String {
    if (replicas.isNullOrEmpty()) return SystemStatusLevelWire.UNKNOWN
    if (replicas.all { it.up }) return SystemStatusLevelWire.OPERATIVE
    val appReplicas = replicas.filter { it.profile == APP_REPLICA_PROFILE }.ifEmpty { replicas }
    return if (appReplicas.none { it.up }) SystemStatusLevelWire.DOWN else SystemStatusLevelWire.DEGRADED
}

/** Perfil de las réplicas que sirven a la app (`ops/monitoring/prometheus/prometheus.production.yml`). */
internal const val APP_REPLICA_PROFILE = "bot"

/**
 * Traduce la sonda cacheada de `/health/ready` (`UP` / `DOWN` / `UNKNOWN`) a un nivel. Lo que no sea un `UP` o un `DOWN`
 * explícito es `UNKNOWN`. La sonda no mide latencia, así que aquí no hay `DEGRADED` que deducir.
 */
internal fun supabaseLevelFromReadiness(readiness: String): String = when (readiness) {
    "UP" -> SystemStatusLevelWire.OPERATIVE
    "DOWN" -> SystemStatusLevelWire.DOWN
    else -> SystemStatusLevelWire.UNKNOWN
}

/** Resultado de [projectMonthlySupabaseEgressBytes]: los bytes y la ventana de la que salen. */
internal data class SupabaseEgressProjection(val bytes: Long, val base: String)

/**
 * Proyección del tráfico de salida a 30 días.
 *
 * Con siete días de serie se usa la media diaria de la semana (`bytes7d / 7 * 30`), que absorbe un día raro. Sin ellos
 * —Prometheus recién desplegado, o una serie más joven— solo queda extrapolar las últimas 24 h (`bytes24h * 30`), que es
 * más ruidoso: por eso se devuelve también la ventana usada, para que el panel la diga.
 *
 * Es una estimación MÍNIMA (ver [AdminSupabaseQuotaDto]) y no una predicción: supone que el mes entero se parece a la
 * ventana medida. Sin ninguna ventana no hay proyección; nunca un cero.
 */
internal fun projectMonthlySupabaseEgressBytes(
    bytes24h: Long?,
    bytes7d: Long?,
    hasSevenDaysOfHistory: Boolean,
): SupabaseEgressProjection? = when {
    hasSevenDaysOfHistory && bytes7d != null ->
        SupabaseEgressProjection(Math.round(bytes7d / 7.0 * 30.0), SupabaseQuotaWire.BASE_7D)
    bytes24h != null -> SupabaseEgressProjection(bytes24h * 30, SupabaseQuotaWire.BASE_24H)
    else -> null
}

/**
 * Versión desplegada a partir de lo que declara cada réplica EN PIE (una caída no declara nada, y eso no es una versión
 * distinta). Una réplica en pie que no publica `build_info` mientras otra sí lo hace también cuenta como diferencia: es
 * lo que se ve durante un despliegue a medias desde una imagen anterior a `build_info`.
 */
internal fun deployedVersionOf(replicas: List<ReplicaReading>?): AdminSystemVersionDto? {
    if (replicas.isNullOrEmpty()) return null
    val declared = replicas.filter { it.up }.map { it.sha }
    val mostCommon = declared.filterNotNull().groupingBy { it }.eachCount().entries
        // A igualdad de réplicas, orden alfabético: que el resultado no dependa del orden en que contestó Prometheus.
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .firstOrNull()?.key
    return AdminSystemVersionDto(sha = mostCommon, replicasDifieren = declared.distinct().size > 1)
}

@Serializable
data class AdminSystemStatusResponse(val data: AdminSystemStatusDto)
