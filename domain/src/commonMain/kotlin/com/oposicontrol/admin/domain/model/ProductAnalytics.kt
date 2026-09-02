package com.oposicontrol.admin.domain.model

/**
 * Analítica de producto: modelo de dominio.
 *
 * El panel de analítica no puede modelarse como "un montón de enteros", porque
 * la mitad de la información útil no es el número sino **por qué no hay número**.
 * De ahí que la unidad de dominio sea [AnalyticsMetric] (clave + valor + estado)
 * y no un `Int`: una tarjeta sabe pintarse a sí misma como dato, como NO_DATA o
 * como NOT_INSTRUMENTED sin que la pantalla tenga que consultar tres listas.
 */

/** Las seis secciones del panel. Una pantalla, un selector, seis vistas. */
enum class AnalyticsSection {
    PRODUCTO,
    ESTUDIO,
    RETENCION,
    COMUNIDAD,
    ECONOMIA,
    MARKETING,
}

/**
 * Estado de una métrica.
 *
 * La diferencia entre los dos estados sin dato no es cosmética: con NO_DATA
 * reintentar puede servir (falló la lectura), con NOT_INSTRUMENTED no sirve
 * nunca (el agregado no existe y hay que construirlo).
 */
enum class AnalyticsMetricStatus {
    /** Hay un número real, incluido un cero legítimo. */
    AVAILABLE,

    /** El backend no pudo leerlo. Reintentar puede arreglarlo. */
    NO_DATA,

    /** No existe agregado que lo calcule. Necesita una vista o un RPC. */
    NOT_INSTRUMENTED,
}

/**
 * Valor de una métrica, con su unidad.
 *
 * El dinero es un caso aparte y va en céntimos enteros: un `Double` de euros
 * acabaría enseñando 99,989999 € donde hay 99,99 €.
 */
sealed interface AnalyticsValue {
    data class Count(val value: Int) : AnalyticsValue
    data class Percentage(val value: Double) : AnalyticsValue
    data class MoneyCents(val value: Long) : AnalyticsValue
}

/**
 * Una métrica lista para pintar.
 *
 * [value] es `null` siempre que [status] no sea [AnalyticsMetricStatus.AVAILABLE];
 * la política que lo garantiza está en [AnalyticsMetricPolicy] y se prueba allí,
 * de modo que ninguna pantalla pueda enseñar un número que el backend declaró
 * inválido.
 */
data class AnalyticsMetric(
    val key: String,
    val value: AnalyticsValue?,
    val status: AnalyticsMetricStatus,
    /** Qué read model haría falta. Sólo para [AnalyticsMetricStatus.NOT_INSTRUMENTED]. */
    val reason: String? = null,
)

data class AnalyticsTableRow(
    val key: String,
    val cells: List<String>,
)

/**
 * Tabla auxiliar de una sección (reparto por liga, preguntas más falladas).
 *
 * Tiene estado propio porque una tabla puede faltar por las mismas dos razones
 * que un número: no se pudo leer, o el agregado no existe.
 */
data class AnalyticsTable(
    val key: String,
    val columns: List<String>,
    val rows: List<AnalyticsTableRow>,
    val status: AnalyticsMetricStatus,
    val reason: String? = null,
)

/** Lo que el panel enseña de una sección en un momento dado. */
data class AnalyticsSectionReport(
    val section: AnalyticsSection,
    val metrics: List<AnalyticsMetric>,
    val tables: List<AnalyticsTable>,
    val generatedAt: String,
) {
    fun metric(key: String): AnalyticsMetric? = metrics.firstOrNull { it.key == key }

    /** Cuántas métricas de la sección traen número. Sirve de resumen honesto. */
    val availableCount: Int get() = metrics.count { it.status == AnalyticsMetricStatus.AVAILABLE }

    /** Cuántas están pendientes de construir un agregado. */
    val notInstrumentedCount: Int get() = metrics.count { it.status == AnalyticsMetricStatus.NOT_INSTRUMENTED }
}

/**
 * Regla única que decide el estado de una métrica.
 *
 * Vive en el dominio y no en el mapeador porque es la regla que impide repetir
 * el fallo del dashboard, y una regla así no puede estar copiada en seis sitios.
 *
 * Tiene una decisión deliberadamente estricta: **si el backend nombró la clave
 * como no disponible, el valor se descarta aunque haya llegado**. Un DTO puede
 * traer a la vez un número de relleno y la clave marcada (así funciona el
 * precedente `AdminDashboardDto`, que rellena con `0`); confiar en el número
 * sería justo volver a enseñar el cero inventado. Se hace lo contrario: manda la
 * lista, no el campo.
 */
object AnalyticsMetricPolicy {

    fun statusFor(
        key: String,
        value: AnalyticsValue?,
        unavailable: Set<String>,
        notInstrumented: Set<String>,
    ): AnalyticsMetricStatus = when {
        key in notInstrumented -> AnalyticsMetricStatus.NOT_INSTRUMENTED
        key in unavailable -> AnalyticsMetricStatus.NO_DATA
        value != null -> AnalyticsMetricStatus.AVAILABLE
        // Sin valor y sin explicación: se falla cerrado. Enseñar la tarjeta vacía
        // sin decir nada es la peor de las tres opciones.
        else -> AnalyticsMetricStatus.NO_DATA
    }

    fun metric(
        key: String,
        value: AnalyticsValue?,
        unavailable: Set<String>,
        notInstrumented: Map<String, String>,
    ): AnalyticsMetric {
        val status = statusFor(key, value, unavailable, notInstrumented.keys)
        return AnalyticsMetric(
            key = key,
            value = if (status == AnalyticsMetricStatus.AVAILABLE) value else null,
            status = status,
            reason = notInstrumented[key],
        )
    }
}

/**
 * Formato de una métrica para pantalla.
 *
 * Está en el dominio junto a [AnalyticsValue] porque las unidades son parte del
 * significado del dato: un porcentaje con un decimal y el dinero en céntimos
 * enteros formateados con [formatPriceCents], nunca con aritmética de coma
 * flotante sobre euros.
 */
fun AnalyticsValue.format(currency: String = "EUR"): String = when (this) {
    is AnalyticsValue.Count -> value.toString()
    is AnalyticsValue.Percentage -> formatPercentage(value)
    is AnalyticsValue.MoneyCents -> formatMoneyCents(value, currency)
}

/** Un decimal: más precisión finge una exactitud que el dato no tiene. */
internal fun formatPercentage(value: Double): String {
    val rounded = kotlin.math.round(value * 10.0) / 10.0
    val whole = rounded.toLong()
    val decimal = kotlin.math.round((rounded - whole) * 10.0).toLong()
    val absDecimal = if (decimal < 0) -decimal else decimal
    return "$whole,$absDecimal %"
}

/**
 * Céntimos enteros a texto. Delega en [formatPriceCents] cuando el importe cabe
 * en `Int`; por encima formatea igual sin pasar nunca por `Double`.
 */
internal fun formatMoneyCents(cents: Long, currency: String): String {
    if (cents in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        return formatPriceCents(cents.toInt(), currency)
    }
    val sign = if (cents < 0) "-" else ""
    val absolute = if (cents < 0) -cents else cents
    val fraction = absolute % 100
    return "$sign${absolute / 100},${fraction.toString().padStart(2, '0')} ${currency.uppercase()}"
}
