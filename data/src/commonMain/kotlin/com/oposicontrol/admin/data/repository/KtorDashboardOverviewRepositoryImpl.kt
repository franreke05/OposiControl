package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.api.BackendRoutes
import com.oposicontrol.admin.data.remote.api.adminAuthHeaders
import com.oposicontrol.admin.domain.model.DashboardActivityEntry
import com.oposicontrol.admin.domain.model.DashboardBlock
import com.oposicontrol.admin.domain.model.DashboardBlockReport
import com.oposicontrol.admin.domain.model.DashboardHealth
import com.oposicontrol.admin.domain.model.DashboardMetricKeys
import com.oposicontrol.admin.domain.model.DashboardOverview
import com.oposicontrol.admin.domain.model.SystemStatusLevel
import com.oposicontrol.admin.domain.repository.DashboardOverviewRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Dashboard V2 a través del límite canónico de Ktor.
 *
 * Una sola petición por refresco. El repositorio anterior
 * ([ContentDashboardRepositoryImpl]) orquestaba cinco repositorios desde el
 * escritorio y lanzaba además **una llamada HTTP por examen** para auditar
 * recursos: un coste que crecía con el catálogo y que componía datos de negocio
 * en el cliente en vez de detrás de la frontera con `service_role`.
 *
 * Todo el mapeo pasa por [AnalyticsMetricPolicy] (vía [AnalyticsMapping], la
 * misma pieza que usa la pantalla de Analítica). Es deliberado: el backend puede
 * mandar un número y a la vez declarar la clave como no disponible, y la única
 * forma de que ese relleno no llegue a pantalla es que una sola regla decida
 * siempre, en vez de que cada franja se acuerde de comprobar la lista.
 *
 * Aquí **no** hay un solo `?: 0`. Un `null` que se convirtiese en cero en este
 * fichero reintroduciría entero el defecto que el rediseño viene a cerrar, y hay
 * una prueba de mutación que lo vigila
 * (`KtorDashboardOverviewRepositoryImplTest`).
 */
class KtorDashboardOverviewRepositoryImpl(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
    private val adapter: NetworkResultAdapter,
) : DashboardOverviewRepository {

    override suspend fun getOverview(sections: Set<DashboardBlock>): AppResult<DashboardOverview> =
        adapter.safeCall {
            // Vacío significa "todas": se omite el parámetro en vez de mandarlo
            // en blanco, que es lo que el backend documenta como carga inicial.
            val requested = sections.takeIf { it.isNotEmpty() }?.joinToString(",") { it.wire }
            get<DashboardOverviewDto>(OVERVIEW, requested).toDomain()
        }

    private suspend inline fun <reified R> get(endpoint: String, sections: String?): R {
        val response = client.get(BackendRoutes.url(appConfig.baseUrl, endpoint)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            if (sections != null) parameter("sections", sections)
            expectSuccess = false
            // PC-09 / F-04: el panel completo son 19 lecturas en Ktor, y una franja
            // lenta no puede tumbar la petición entera. Con los 6 s de antes, la
            // degradación POR FRANJA —que es todo el diseño de este contrato— no
            // llegaba a verse: el cliente se rendía antes de que Ktor respondiera.
            timeout { requestTimeoutMillis = OVERVIEW_TIMEOUT_MILLIS }
        }
        if (!response.status.isSuccess()) {
            // Un fallo de la petición entera no se degrada a un panel vacío:
            // sería una pantalla de ceros que nadie ha calculado. Sube como
            // AppResult.Failure. Que una FRANJA falle es otra cosa distinta y
            // llega dentro del cuerpo, en `failedBlocks`.
            error("backend ${response.status.value} $endpoint: ${response.bodyAsText().take(240)}")
        }
        return response.body()
    }

    companion object {
        val OVERVIEW = BackendRoutes.ADMIN_V1_DASHBOARD_OVERVIEW

        /** Presupuesto de la petición agregada (PC-09): por encima del defecto de 30 s no. */
        const val OVERVIEW_TIMEOUT_MILLIS: Long = 25_000
    }
}

// ---------------------------------------------------------------------
// DTOs de transporte
// ---------------------------------------------------------------------

/**
 * Una franja ausente y una franja fallida se distinguen: la primera llega como
 * `null` porque no se pidió, la segunda además se nombra en [failedBlocks].
 */
@Serializable
data class DashboardOverviewDto(
    val system: DashboardSystemPayload? = null,
    val attention: DashboardAttentionPayload? = null,
    val pulse: DashboardPulsePayload? = null,
    val content: DashboardContentPayload? = null,
    val activity: DashboardActivityPayload? = null,
    val economy: DashboardEconomyPayload? = null,
    val failedBlocks: List<String> = emptyList(),
    val generatedAt: String = "",
)

@Serializable
data class DashboardSystemPayload(
    val health: DashboardHealthPayload = DashboardHealthPayload(),
    val generatedAt: String = "",
)

@Serializable
data class DashboardHealthPayload(
    val overallStatus: String = "",
    val ktorStatus: String = "",
    val supabaseStatus: String = "",
    val updatedAt: String = "",
    /** Tiempo de la sonda a Supabase, no latencia general de la API. */
    val supabaseLatencyMs: Long? = null,
    val notInstrumented: List<String> = emptyList(),
)

@Serializable
data class DashboardAttentionPayload(
    val reportsPending: Int? = null,
    val questionFeedbackPending: Int? = null,
    val draftExams: Int? = null,
    val subscriptionsExpiring7d: Int? = null,
    val generatedAt: String = "",
    val unavailableMetrics: List<String> = emptyList(),
    /** PC-04: claves cuya LECTURA falló. Un servidor antiguo no la manda: se quedan en NO_DATA. */
    val failedMetrics: List<String> = emptyList(),
    val notInstrumented: List<AnalyticsGapPayload> = emptyList(),
)

@Serializable
data class DashboardPulsePayload(
    val activeUsers30d: Int? = null,
    val attemptsLast30d: Int? = null,
    val publishedExams: Int? = null,
    val globalErrorRate: Double? = null,
    val generatedAt: String = "",
    val unavailableMetrics: List<String> = emptyList(),
    /** PC-04: claves cuya LECTURA falló. Un servidor antiguo no la manda: se quedan en NO_DATA. */
    val failedMetrics: List<String> = emptyList(),
    val notInstrumented: List<AnalyticsGapPayload> = emptyList(),
)

@Serializable
data class DashboardContentPayload(
    val totalExams: Int? = null,
    val totalQuestions: Int? = null,
    val totalResources: Int? = null,
    val importBatches: Int? = null,
    val generatedAt: String = "",
    val unavailableMetrics: List<String> = emptyList(),
    /** PC-04: claves cuya LECTURA falló. Un servidor antiguo no la manda: se quedan en NO_DATA. */
    val failedMetrics: List<String> = emptyList(),
    val notInstrumented: List<AnalyticsGapPayload> = emptyList(),
)

@Serializable
data class DashboardActivityPayload(
    val entries: List<DashboardActivityEntryPayload> = emptyList(),
    /**
     * Filas que Ktor leyó y no pudo entregar (sin fecha interpretable o sin id).
     *
     * Es lo que impide que "leí ocho filas y ninguna era legible" y "no hay nada
     * en `auditoria_log`" lleguen idénticas: las dos serían una lista vacía.
     */
    val droppedEntries: Int = 0,
    val generatedAt: String = "",
)

@Serializable
data class DashboardActivityEntryPayload(
    val id: String = "",
    val action: String = "",
    val targetTable: String? = null,
    val targetId: String? = null,
    val occurredAt: String? = null,
)

@Serializable
data class DashboardEconomyPayload(
    val subscriptionsActive: Int? = null,
    val subscriptionsExpiring30d: Int? = null,
    val commercialProductsActive: Int? = null,
    /** Céntimos enteros. Nunca Double: el dinero no admite coma flotante. */
    val grossRevenueCents: Long? = null,
    val ticketsInCirculation: Long? = null,
    val generatedAt: String = "",
    val unavailableMetrics: List<String> = emptyList(),
    /** PC-04: claves cuya LECTURA falló. Un servidor antiguo no la manda: se quedan en NO_DATA. */
    val failedMetrics: List<String> = emptyList(),
    val notInstrumented: List<AnalyticsGapPayload> = emptyList(),
)

// ---------------------------------------------------------------------
// Mapeo a dominio
// ---------------------------------------------------------------------

internal fun DashboardOverviewDto.toDomain(): DashboardOverview {
    val blocks = buildMap {
        system?.let { put(DashboardBlock.SYSTEM, it.toReport()) }
        attention?.let { put(DashboardBlock.ATTENTION, it.toReport()) }
        pulse?.let { put(DashboardBlock.PULSE, it.toReport()) }
        content?.let { put(DashboardBlock.CONTENT, it.toReport()) }
        activity?.let { put(DashboardBlock.ACTIVITY, it.toReport()) }
        economy?.let { put(DashboardBlock.ECONOMY, it.toReport()) }
    }
    return DashboardOverview(
        blocks = blocks,
        // Un nombre de franja que este cliente no conoce se descarta: el panel no
        // puede pintar el fallo de algo que no sabe mostrar, y quedarse con la
        // cadena cruda sólo movería el problema a la UI.
        failedBlocks = failedBlocks.mapNotNull(DashboardBlock::fromWire).toSet(),
        generatedAt = generatedAt.requiredInstant("generatedAt"),
    )
}

private fun DashboardSystemPayload.toReport() = DashboardBlockReport(
    block = DashboardBlock.SYSTEM,
    health = DashboardHealth(
        overall = health.overallStatus.toSystemStatusLevel(),
        ktor = health.ktorStatus.toSystemStatusLevel(),
        supabase = health.supabaseStatus.toSystemStatusLevel(),
        // `updatedAt` sí es opcional: es un adorno de la tarjeta, no el dato.
        updatedAt = health.updatedAt.toInstantOrNull(),
        // Ausente (sonda fallida) se conserva como ausente. Un 0 ms se leería
        // como "Supabase responde instantáneamente", que es lo contrario de lo
        // que significa: no hubo respuesta que cronometrar.
        supabaseLatencyMs = health.supabaseLatencyMs,
        notInstrumented = health.notInstrumented,
    ),
    generatedAt = generatedAt.requiredInstant("system.generatedAt"),
)

private fun DashboardAttentionPayload.toReport(): DashboardBlockReport {
    val mapping = AnalyticsMapping(unavailableMetrics, notInstrumented, failedMetrics)
    val metrics = listOf(
        mapping.count(DashboardMetricKeys.REPORTS_PENDING, reportsPending),
        mapping.count(DashboardMetricKeys.QUESTION_FEEDBACK_PENDING, questionFeedbackPending),
        mapping.count(DashboardMetricKeys.DRAFT_EXAMS, draftExams),
        mapping.count(DashboardMetricKeys.SUBSCRIPTIONS_EXPIRING_7D, subscriptionsExpiring7d),
    )
    return DashboardBlockReport(
        block = DashboardBlock.ATTENTION,
        metrics = metrics + mapping.remainingGaps(metrics),
        generatedAt = generatedAt.requiredInstant("attention.generatedAt"),
    )
}

private fun DashboardPulsePayload.toReport(): DashboardBlockReport {
    val mapping = AnalyticsMapping(unavailableMetrics, notInstrumented, failedMetrics)
    val metrics = listOf(
        mapping.count(DashboardMetricKeys.ACTIVE_USERS_30D, activeUsers30d),
        mapping.count(DashboardMetricKeys.ATTEMPTS_LAST_30D, attemptsLast30d),
        mapping.count(DashboardMetricKeys.PUBLISHED_EXAMS, publishedExams),
        mapping.percentage(DashboardMetricKeys.GLOBAL_ERROR_RATE, globalErrorRate),
    )
    return DashboardBlockReport(
        block = DashboardBlock.PULSE,
        metrics = metrics + mapping.remainingGaps(metrics),
        generatedAt = generatedAt.requiredInstant("pulse.generatedAt"),
    )
}

private fun DashboardContentPayload.toReport(): DashboardBlockReport {
    val mapping = AnalyticsMapping(unavailableMetrics, notInstrumented, failedMetrics)
    val metrics = listOf(
        mapping.count(DashboardMetricKeys.TOTAL_EXAMS, totalExams),
        mapping.count(DashboardMetricKeys.TOTAL_QUESTIONS, totalQuestions),
        mapping.count(DashboardMetricKeys.TOTAL_RESOURCES, totalResources),
        mapping.count(DashboardMetricKeys.IMPORT_BATCHES, importBatches),
    )
    return DashboardBlockReport(
        block = DashboardBlock.CONTENT,
        metrics = metrics + mapping.remainingGaps(metrics),
        generatedAt = generatedAt.requiredInstant("content.generatedAt"),
    )
}

private fun DashboardActivityPayload.toReport(): DashboardBlockReport {
    val mapped = entries.mapNotNull { it.toDomainOrNull() }
    return DashboardBlockReport(
        block = DashboardBlock.ACTIVITY,
        // Dos reglas, y las dos se aplican aquí a propósito:
        //
        // 1. Una entrada sin fecha interpretable se descarta. El backend ya las
        //    descarta, y aun así no se le pone la fecha de la lectura por si
        //    acaso: fabricar el `occurredAt` es el fallo que este contrato cierra.
        // 2. El orden se impone aquí, no se hereda. El backend pide
        //    `order=creado_en.desc` y eso seguirá siendo correcto, pero quien
        //    consume esta lista hace `take(5)` para enseñar "lo más reciente": si
        //    el orden fuese sólo una promesa del servidor, tocar esa query
        //    enseñaría cinco filas cualesquiera **en silencio**, sin que nada
        //    fallara. Una garantía que la pantalla necesita se declara y se
        //    prueba donde se necesita.
        activity = mapped.sortedByDescending { it.occurredAt },
        // Lo que descartó Ktor, más lo que se caiga aquí. El backend ya filtra
        // por fecha e id, así que el segundo sumando debería ser siempre 0; se
        // suma igualmente porque el descarte de este lado existe, y contarlo a
        // medias publicaría un número que no es el que dice ser.
        droppedActivityEntries = droppedEntries + (entries.size - mapped.size),
        generatedAt = generatedAt.requiredInstant("activity.generatedAt"),
    )
}

private fun DashboardEconomyPayload.toReport(): DashboardBlockReport {
    val mapping = AnalyticsMapping(unavailableMetrics, notInstrumented, failedMetrics)
    val metrics = listOf(
        mapping.count(DashboardMetricKeys.SUBSCRIPTIONS_ACTIVE, subscriptionsActive),
        mapping.count(DashboardMetricKeys.SUBSCRIPTIONS_EXPIRING_30D, subscriptionsExpiring30d),
        mapping.count(DashboardMetricKeys.COMMERCIAL_PRODUCTS_ACTIVE, commercialProductsActive),
        mapping.money(DashboardMetricKeys.GROSS_REVENUE_CENTS, grossRevenueCents),
        // Conteo, NO dinero. Los OposiTickets son una moneda interna sin tipo de
        // cambio: pintarlos con `MoneyCents` los formatearía con `formatPriceCents`
        // y 1.500 OT saldrían como «15,00 €». Ya no es hipotético: desde PC-11 la
        // cifra llega de verdad (`admin_dashboard_economy_totals`, 21475 en MAIN).
        mapping.count(DashboardMetricKeys.TICKETS_IN_CIRCULATION, ticketsInCirculation?.toCountOrNull()),
    )
    return DashboardBlockReport(
        block = DashboardBlock.ECONOMY,
        metrics = metrics + mapping.remainingGaps(metrics),
        generatedAt = generatedAt.requiredInstant("economy.generatedAt"),
    )
}

private fun DashboardActivityEntryPayload.toDomainOrNull(): DashboardActivityEntry? {
    val occurred = occurredAt.toInstantOrNull() ?: return null
    return DashboardActivityEntry(
        id = id,
        action = action,
        targetTable = targetTable,
        targetId = targetId,
        occurredAt = occurred,
    )
}

/**
 * `Long` del cable a `Int` de [AnalyticsValue.Count], o `null` si no cabe.
 *
 * El contrato transporta los tickets como `Long` (el backend los leería de un
 * `SUM`) pero `AnalyticsValue.Count` es `Int`. Un valor fuera de rango se publica
 * como NO_DATA en vez de truncarse: «Sin dato» para tres mil millones de tickets
 * es incorrecto pero inofensivo, mientras que un `toInt()` a secas daría un
 * número truncado —posiblemente negativo— con toda la pinta de ser real. El
 * arreglo de verdad es que `AnalyticsValue` admita conteos de 64 bits; queda
 * anotado como deuda.
 */
private fun Long.toCountOrNull(): Int? =
    takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()

private fun String?.toInstantOrNull(): Instant? =
    this?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }

/**
 * Instante obligatorio del contrato.
 *
 * `generatedAt` lo escribe siempre el backend con la hora del servidor; que no
 * se pueda interpretar significa que la respuesta no cumple el contrato. Se
 * falla la llamada en vez de sustituirlo por la hora local: un panel que dice
 * "actualizado ahora mismo" cuando no sabe cuándo se generó es la versión sutil
 * del mismo engaño que los eventos fechados con `now()`.
 */
private fun String.requiredInstant(field: String): Instant =
    toInstantOrNull() ?: error("backend 502 ${BackendRoutes.ADMIN_V1_DASHBOARD_OVERVIEW}: '$field' no es un instante ('$this')")
