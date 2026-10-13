package com.oposicontrol.admin.features.settings.presentation

import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.domain.model.AdminSessionInfo
import com.oposicontrol.admin.domain.model.SupabaseProjectionBasis
import com.oposicontrol.admin.domain.model.SupabaseQuota
import com.oposicontrol.admin.domain.model.SystemAlert
import com.oposicontrol.admin.domain.model.SystemMetric
import com.oposicontrol.admin.domain.model.SystemReplica
import com.oposicontrol.admin.domain.model.SystemStatus
import com.oposicontrol.admin.domain.model.SystemStatusLevel
import kotlin.math.roundToInt
import kotlin.time.Instant

/*
 * Textos y cálculos de la pestaña Estado, como funciones puras: se prueban sin Compose.
 *
 * La regla que comparten todas: un dato que no se ha podido leer se DICE («No disponible», «Prometheus no responde»,
 * «Sin instrumentar»); nunca se sustituye por un cero ni por un «todo bien».
 */

internal const val UNAVAILABLE: String = "No disponible"
internal const val PROMETHEUS_SILENT: String = "Prometheus no responde"
internal const val NOT_INSTRUMENTED: String = "Sin instrumentar"

// ---------------------------------------------------------------------------------------------------------------------
// Estado global
// ---------------------------------------------------------------------------------------------------------------------

/** Lo que dice el bloque Resumen: una etiqueta corta, una frase y, si hace falta, el detalle que la explica. */
internal data class SystemOverview(
    val level: SystemStatusLevel,
    val label: String,
    val headline: String,
    val detail: String,
)

/**
 * El estado global en una frase.
 *
 * El NIVEL lo decide el servidor; aquí solo se explica. La única corrección es a la baja: con todo en pie pero alertas
 * disparadas, el resumen no dice «todo funciona» sin más, porque una alerta activa es precisamente el aviso de que algo
 * va a dejar de hacerlo.
 */
internal fun systemOverview(status: SystemStatus): SystemOverview {
    val replicas = status.replicas.orEmpty()
    val down = replicas.filterNot { it.up }
    val alerts = status.alerts.orEmpty()
    return when {
        status.supabaseStatus == SystemStatusLevel.DOWN -> SystemOverview(
            level = SystemStatusLevel.DOWN,
            label = "Caído",
            headline = "Supabase no responde.",
            detail = "La app y el panel no pueden leer ni guardar datos hasta que vuelva.",
        )
        status.ktorStatus == SystemStatusLevel.DOWN -> SystemOverview(
            level = SystemStatusLevel.DOWN,
            label = "Caído",
            headline = "La app se ha quedado sin servidor.",
            detail = "No hay ninguna réplica de la app en pie. " + downReplicasSentence(down),
        )
        status.prometheusResponding == false -> SystemOverview(
            level = SystemStatusLevel.UNKNOWN,
            label = "Sin datos",
            headline = "No se puede comprobar el estado del servidor.",
            detail = "Prometheus no responde, así que no hay datos de réplicas, alertas ni consumo. " +
                supabaseSentence(status.supabaseStatus),
        )
        status.overallStatus == SystemStatusLevel.DOWN -> SystemOverview(
            level = SystemStatusLevel.DOWN,
            label = "Caído",
            headline = "Hay un servicio caído.",
            detail = "Revisa las réplicas y las alertas de esta pantalla.",
        )
        status.overallStatus == SystemStatusLevel.DEGRADED -> SystemOverview(
            level = SystemStatusLevel.DEGRADED,
            label = "Degradado",
            headline = "El servicio funciona, pero con menos margen.",
            detail = if (down.isEmpty()) "Hay un servicio que responde con problemas." else downReplicasSentence(down),
        )
        status.overallStatus == SystemStatusLevel.OPERATIVE && alerts.isNotEmpty() -> SystemOverview(
            level = SystemStatusLevel.DEGRADED,
            label = "Con alertas",
            headline = if (alerts.size == 1) "Todo responde, pero hay una alerta activa." else "Todo responde, pero hay ${alerts.size} alertas activas.",
            detail = "Las réplicas están en pie y Supabase responde. Mira el bloque de alertas.",
        )
        // Las alertas no se han podido leer: «todo funciona con normalidad» sería afirmar algo que no se ha comprobado.
        status.overallStatus == SystemStatusLevel.OPERATIVE && status.alerts == null -> SystemOverview(
            level = SystemStatusLevel.UNKNOWN,
            label = "Sin alertas leídas",
            headline = "Todo responde, pero no se han podido leer las alertas.",
            detail = "Las réplicas están en pie y Supabase responde. Vuelve a actualizar para comprobar las alertas.",
        )
        status.overallStatus == SystemStatusLevel.OPERATIVE -> SystemOverview(
            level = SystemStatusLevel.OPERATIVE,
            label = "Operativo",
            headline = "Todo funciona con normalidad.",
            detail = when (replicas.size) {
                0 -> "Supabase responde."
                1 -> "La réplica está en pie y Supabase responde."
                else -> "Las ${replicas.size} réplicas están en pie y Supabase responde."
            },
        )
        else -> SystemOverview(
            level = SystemStatusLevel.UNKNOWN,
            label = "Sin datos",
            headline = "No hay datos suficientes para saber cómo está el sistema.",
            detail = supabaseSentence(status.supabaseStatus),
        )
    }
}

private fun downReplicasSentence(down: List<SystemReplica>): String = when (down.size) {
    0 -> ""
    1 -> "Réplica caída: ${down.single().name}."
    else -> "Réplicas caídas: ${down.joinToString(", ") { it.name }}."
}

private fun supabaseSentence(level: SystemStatusLevel): String = when (level) {
    SystemStatusLevel.OPERATIVE -> "Supabase sí responde."
    SystemStatusLevel.DEGRADED -> "Supabase responde con lentitud."
    SystemStatusLevel.DOWN -> "Supabase no responde."
    SystemStatusLevel.UNKNOWN -> "Tampoco se sabe si Supabase responde."
}

internal fun SystemStatusLevel.label(): String = when (this) {
    SystemStatusLevel.OPERATIVE -> "Operativo"
    SystemStatusLevel.DEGRADED -> "Degradado"
    SystemStatusLevel.DOWN -> "Caído"
    SystemStatusLevel.UNKNOWN -> "Sin datos"
}

// ---------------------------------------------------------------------------------------------------------------------
// «Hace cuánto»
// ---------------------------------------------------------------------------------------------------------------------

/**
 * «hace 20 s», «hace 5 min», «hace 3 h», «hace 2 días». `null` si no hay instante: quien llama dice «No disponible».
 * Un instante futuro (relojes desajustados) se trata como «ahora», no como un tiempo negativo.
 */
internal fun elapsedLabel(since: Instant?, now: Instant): String? {
    if (since == null) return null
    val seconds = (now - since).inWholeSeconds.coerceAtLeast(0)
    return when {
        seconds < 60 -> "hace $seconds s"
        seconds < 3_600 -> "hace ${seconds / 60} min"
        seconds < 86_400 -> "hace ${seconds / 3_600} h"
        seconds < 172_800 -> "hace 1 día"
        else -> "hace ${seconds / 86_400} días"
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Bytes y cuota
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Bytes en unidades DECIMALES (1 MB = 1 000 000 B), que es como cuenta Supabase el tráfico y como están escritos los
 * umbrales de las alertas. Coma decimal, y sin decimales cuando son cero: «0 B», «512 B», «1,5 kB», «41,2 MB», «3 GB»,
 * «4,05 GB».
 */
internal fun formatBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0)
    return when {
        value < 1_000L -> "$value B"
        value < 999_950L -> scaled(value, 1_000L, decimals = 1, unit = "kB")
        value < 999_950_000L -> scaled(value, 1_000_000L, decimals = 1, unit = "MB")
        else -> scaled(value, 1_000_000_000L, decimals = 2, unit = "GB")
    }
}

/** Redondeo con aritmética entera: no hay formato de decimales común a todas las plataformas del panel. */
private fun scaled(value: Long, divisor: Long, decimals: Int, unit: String): String {
    val factor = if (decimals == 2) 100L else 10L
    val rounded = (value * factor + divisor / 2) / divisor
    val whole = rounded / factor
    val fraction = (rounded % factor).toString().padStart(decimals, '0').trimEnd('0')
    return if (fraction.isEmpty()) "$whole $unit" else "$whole,$fraction $unit"
}

internal fun Long?.toBytesLabel(): String = this?.let(::formatBytes) ?: UNAVAILABLE

internal enum class QuotaTone { OK, WARNING, OVER }

/** A partir de este porcentaje del límite la proyección se pinta como aviso. */
internal const val QUOTA_WARNING_PERCENT: Int = 80

/**
 * La proyección mensual frente al límite del plan.
 *
 * [percent] es el porcentaje real (puede pasar de 100); [barFraction] es lo que se pinta, recortado a la barra llena.
 */
internal data class QuotaUsage(val percent: Int, val barFraction: Float, val tone: QuotaTone)

/** `null` si falta la proyección o el límite: sin los dos no hay barra que pintar, y una barra vacía mentiría. */
internal fun quotaUsage(projectedMonthlyBytes: Long?, monthlyLimitBytes: Long?): QuotaUsage? {
    if (projectedMonthlyBytes == null || monthlyLimitBytes == null || monthlyLimitBytes <= 0) return null
    val ratio = projectedMonthlyBytes.coerceAtLeast(0).toDouble() / monthlyLimitBytes.toDouble()
    val percent = (ratio * 100).roundToInt()
    return QuotaUsage(
        percent = percent,
        barFraction = ratio.coerceIn(0.0, 1.0).toFloat(),
        tone = when {
            percent >= 100 -> QuotaTone.OVER
            percent >= QUOTA_WARNING_PERCENT -> QuotaTone.WARNING
            else -> QuotaTone.OK
        },
    )
}

/** «3 GB de 5 GB (60 %)». Si falta el límite se dice solo la proyección; si falta la proyección, que no la hay. */
internal fun quotaProjectionLabel(quota: SupabaseQuota): String {
    val projected = quota.projectedMonthlyBytes ?: return "Sin proyección: aún no hay consumo medido."
    val limit = quota.monthlyLimitBytes ?: return formatBytes(projected)
    val usage = quotaUsage(projected, limit)
    return "${formatBytes(projected)} de ${formatBytes(limit)}" + (usage?.let { " (${it.percent} %)" } ?: "")
}

internal fun quotaBasisLabel(basis: SupabaseProjectionBasis?): String = when (basis) {
    SupabaseProjectionBasis.SEVEN_DAYS -> "Calculada con la media diaria de los últimos 7 días."
    SupabaseProjectionBasis.LAST_DAY -> "Calculada solo con las últimas 24 horas: todavía no hay una semana de datos."
    null -> "Sin datos suficientes para proyectar."
}

/** Lo que se avisa siempre junto a la cuota: es un suelo, no la factura. */
internal const val QUOTA_MINIMUM_NOTICE: String =
    "Solo cuenta lo que pasa por el servidor. Lo que la app descarga directamente de Supabase no está incluido, " +
        "así que el consumo real puede ser mayor."

internal fun quotaTargetLabel(target: String): String = when (target) {
    "rest" -> "Base de datos"
    "auth" -> "Inicio de sesión"
    "storage" -> "Archivos"
    "other" -> "Otros"
    else -> target
}

// ---------------------------------------------------------------------------------------------------------------------
// Rendimiento: dato real, Prometheus mudo o sin instrumentar
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Por qué falta una métrica de rendimiento.
 *
 * - Prometheus no contestó a nada: falta por eso, y es lo primero que hay que arreglar.
 * - Prometheus contesta pero no tiene la serie: este despliegue no la mide.
 * - El disco es aparte: no sale de Prometheus sino de Supabase, así que el silencio de Prometheus no lo explica.
 */
internal fun metricGapLabel(metric: SystemMetric, status: SystemStatus): String = when {
    metric == SystemMetric.SUPABASE_DISK -> UNAVAILABLE
    status.prometheusResponding == false -> PROMETHEUS_SILENT
    status.prometheusResponding == true && metric in status.notInstrumented -> NOT_INSTRUMENTED
    else -> UNAVAILABLE
}

/** Conserva `null` como ausencia y presenta un cero medido como `0 %`. Coma decimal. */
internal fun Double?.toPercentLabel(): String? = this?.let { value ->
    val tenths = (value * 10).roundToInt()
    if (tenths % 10 == 0) {
        "${tenths / 10} %"
    } else {
        "${tenths / 10},${kotlin.math.abs(tenths % 10)} %"
    }
}

/** Una fila del bloque Rendimiento: el valor, o por qué no lo hay. */
internal data class PerformanceRow(val label: String, val value: String, val detail: String? = null, val measured: Boolean)

internal fun performanceRows(status: SystemStatus): List<PerformanceRow> {
    fun row(label: String, metric: SystemMetric, value: String?, gapDetail: String? = null) = PerformanceRow(
        label = label,
        value = value ?: metricGapLabel(metric, status),
        detail = gapDetail.takeIf { value == null && metricGapLabel(metric, status) != PROMETHEUS_SILENT },
        measured = value != null,
    )
    return listOf(
        row("CPU del servidor", SystemMetric.CPU, status.cpuPercent.toPercentLabel()),
        row("Memoria del servidor", SystemMetric.MEMORY, status.ramPercent.toPercentLabel()),
        row("Disco de Supabase", SystemMetric.SUPABASE_DISK, status.supabaseDiskPercent.toPercentLabel(), "Supabase no ha dado la medida."),
        row(
            "Latencia p95",
            SystemMetric.API_LATENCY,
            status.apiLatencyMs?.let { "$it ms" },
            "Puede que no haya habido peticiones en los últimos 5 minutos.",
        ),
        row("Errores 5xx en 24 h", SystemMetric.API_ERRORS, status.errors24h?.toString(), "Puede que aún no haya 24 horas de datos."),
    )
}

// ---------------------------------------------------------------------------------------------------------------------
// Réplicas, alertas, versión y sesión
// ---------------------------------------------------------------------------------------------------------------------

internal fun replicaProfileLabel(profile: String?): String? = when (profile) {
    null -> null
    "bot" -> "App"
    "control" -> "Panel"
    else -> profile
}

internal fun replicaStateLabel(replica: SystemReplica): String = if (replica.up) "En pie" else "Caída"

/** De una réplica caída no se sabe si vería Supabase: no se afirma ni una cosa ni la otra. */
internal fun replicaSupabaseLabel(replica: SystemReplica): String = when (replica.seesSupabase) {
    true -> "Ve Supabase"
    false -> "No llega a Supabase"
    null -> if (replica.up) "Sin dato de Supabase" else "Sin dato mientras esté caída"
}

internal fun replicaVersionLabel(replica: SystemReplica): String = replica.version ?: "Versión no publicada"

internal fun replicaStartedLabel(replica: SystemReplica, now: Instant): String =
    elapsedLabel(replica.startedAt, now)?.let { "Arrancada $it" } ?: "Arranque no disponible"

internal fun alertSeverityLabel(alert: SystemAlert): String = when (alert.severity?.lowercase()) {
    "critical" -> "Crítica"
    "warning" -> "Aviso"
    null -> "Sin severidad"
    else -> alert.severity.orEmpty()
}

internal fun alertIsCritical(alert: SystemAlert): Boolean = alert.severity.equals("critical", ignoreCase = true)

/** Lo que se sabe de la alerta además de su nombre: el resumen si viene, y la réplica afectada si la hay. */
internal fun alertDetail(alert: SystemAlert): String? =
    listOfNotNull(alert.summary, alert.replica?.let { "Réplica $it" }).joinToString(" · ").takeIf { it.isNotEmpty() }

internal fun sessionRolesLabel(session: AdminSessionInfo): String =
    if (session.roles.isEmpty()) "Ningún rol asignado" else session.roles.joinToString(", ") { roleLabel(it) }

private fun roleLabel(code: String): String = when (code.lowercase()) {
    "superadmin" -> "Superadministrador"
    "admin" -> "Administrador"
    "moderador" -> "Moderador"
    "usuario" -> "Usuario"
    else -> code
}

/** El entorno con su nombre, no con la constante del código. */
fun environmentDisplayName(environment: Environment): String = when (environment) {
    Environment.DEV -> "Desarrollo"
    Environment.STAGING -> "Preproducción"
    Environment.PROD -> "Producción"
}

/** Orientación en lenguaje llano. Ninguna de estas acciones se hace desde el panel: se dicen, no se ejecutan. */
internal val systemStatusGuidance: List<String> = listOf(
    "Si una réplica está caída: entra al servidor por SSH y revisa «docker ps». Si no aparece o se reinicia sola, mira sus registros con «docker logs».",
    "Si Supabase no responde: abre el panel de Supabase y comprueba si el proyecto está pausado o ha agotado su cuota. Hasta que vuelva, la app no puede cargar datos.",
    "Si la cuota pasa del 80 %: mira en el desglose qué destino consume más. Suele ser una pantalla que pide demasiado o que se refresca sola.",
    "Si las réplicas no tienen la misma versión: hay un despliegue a medias. Termínalo o vuelve a la versión anterior en todas.",
    "Si Prometheus no responde: los servicios pueden estar bien, pero esta pantalla no lo puede comprobar. Revisa su contenedor en el servidor.",
)
