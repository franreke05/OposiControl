package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.api.BackendRoutes
import com.oposicontrol.admin.data.remote.api.adminAuthHeaders
import com.oposicontrol.admin.domain.model.AnalyticsMetric
import com.oposicontrol.admin.domain.model.AnalyticsMetricPolicy
import com.oposicontrol.admin.domain.model.AnalyticsMetricStatus
import com.oposicontrol.admin.domain.model.AnalyticsSection
import com.oposicontrol.admin.domain.model.AnalyticsSectionReport
import com.oposicontrol.admin.domain.model.AnalyticsTable
import com.oposicontrol.admin.domain.model.AnalyticsTableRow
import com.oposicontrol.admin.domain.model.AnalyticsValue
import com.oposicontrol.admin.domain.model.format
import com.oposicontrol.admin.domain.repository.ProductAnalyticsRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

/**
 * Analítica de producto a través del límite canónico de Ktor.
 *
 * Nada de Supabase directo: son lecturas con service role sobre tablas de
 * negocio (dinero, comunidad, actividad), y esa llave no baja al cliente.
 *
 * Todo el mapeo pasa por [AnalyticsMetricPolicy]. Es deliberado: el backend
 * puede mandar un número y a la vez declarar la clave como no disponible (así
 * funciona el precedente del dashboard, que rellena con `0`), y la única forma
 * de que ese relleno no llegue a pantalla es que una sola regla decida siempre,
 * en vez de que cada sección se acuerde de comprobar la lista.
 */
class KtorProductAnalyticsRepositoryImpl(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
    private val adapter: NetworkResultAdapter,
) : ProductAnalyticsRepository {

    override suspend fun getSection(section: AnalyticsSection): AppResult<AnalyticsSectionReport> =
        adapter.safeCall {
            when (section) {
                AnalyticsSection.PRODUCTO -> get<ProductoAnalyticsDto>(endpoint(section)).toReport()
                AnalyticsSection.ESTUDIO -> get<EstudioAnalyticsDto>(endpoint(section)).toReport()
                AnalyticsSection.RETENCION -> get<RetencionAnalyticsDto>(endpoint(section)).toReport()
                AnalyticsSection.COMUNIDAD -> get<ComunidadAnalyticsDto>(endpoint(section)).toReport()
                AnalyticsSection.ECONOMIA -> get<EconomiaAnalyticsDto>(endpoint(section)).toReport()
                AnalyticsSection.MARKETING -> get<MarketingAnalyticsDto>(endpoint(section)).toReport()
            }
        }

    private suspend inline fun <reified R> get(endpoint: String): R {
        val response = client.get(BackendRoutes.url(appConfig.baseUrl, endpoint)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            expectSuccess = false
        }
        if (!response.status.isSuccess()) {
            // Un fallo de red no se degrada a un informe vacío: sería un panel
            // lleno de ceros que nadie ha calculado. Sube como AppResult.Failure.
            error("backend ${response.status.value} $endpoint: ${response.bodyAsText().take(240)}")
        }
        return response.body()
    }

    companion object {
        const val ANALYTICS = "/api/v1/admin/analytics"

        fun endpoint(section: AnalyticsSection): String = when (section) {
            AnalyticsSection.PRODUCTO -> "$ANALYTICS/producto"
            AnalyticsSection.ESTUDIO -> "$ANALYTICS/estudio"
            AnalyticsSection.RETENCION -> "$ANALYTICS/retencion"
            AnalyticsSection.COMUNIDAD -> "$ANALYTICS/comunidad"
            AnalyticsSection.ECONOMIA -> "$ANALYTICS/economia"
            AnalyticsSection.MARKETING -> "$ANALYTICS/marketing"
        }
    }
}

// ---------------------------------------------------------------------
// DTOs de transporte
// ---------------------------------------------------------------------

/** Hueco estructural declarado por el backend, con el read model que falta. */
@Serializable
data class AnalyticsGapPayload(
    val key: String = "",
    val reason: String = "",
)

@Serializable
data class ProductoAnalyticsDto(
    val totalUsers: Int? = null,
    val guestUsers: Int? = null,
    val onboardedUsers: Int? = null,
    val onboardingCompletionRate: Double? = null,
    val totalAppEvents: Int? = null,
    val appEventsLast30d: Int? = null,
    val totalStudySessions: Int? = null,
    val studySessionsLast30d: Int? = null,
    val finishedStudySessions: Int? = null,
    val abandonedStudySessions: Int? = null,
    val studySessionCompletionRate: Double? = null,
    val generatedAt: String = "",
    val unavailableMetrics: List<String> = emptyList(),
    val notInstrumented: List<AnalyticsGapPayload> = emptyList(),
)

@Serializable
data class EstudioAnalyticsDto(
    val totalAttempts: Int? = null,
    val attemptsCompleted: Int? = null,
    val attemptsInProgress: Int? = null,
    val attemptsOtherState: Int? = null,
    val attemptsLast30d: Int? = null,
    val attemptsFirstTime: Int? = null,
    val attemptsRanked: Int? = null,
    val attemptsRafaga: Int? = null,
    val attemptsRazonado: Int? = null,
    val attemptsInmersivo: Int? = null,
    val attemptsOtherMode: Int? = null,
    val totalAnswers: Int? = null,
    val correctAnswers: Int? = null,
    val wrongAnswers: Int? = null,
    val unansweredAnswers: Int? = null,
    val globalAccuracyRate: Double? = null,
    val globalErrorRate: Double? = null,
    val firstTimeAnswers: Int? = null,
    val randomAnswers: Int? = null,
    val changedAnswers: Int? = null,
    val markedForReviewAnswers: Int? = null,
    val hardestQuestions: List<HardQuestionPayload>? = null,
    val generatedAt: String = "",
    val unavailableMetrics: List<String> = emptyList(),
    val notInstrumented: List<AnalyticsGapPayload> = emptyList(),
)

@Serializable
data class HardQuestionPayload(
    val questionId: String = "",
    val code: String? = null,
    val title: String? = null,
    val questionType: String? = null,
    val totalAnswers: Int? = null,
    val totalWrong: Int? = null,
    val errorRate: Double? = null,
    val difficulty1To10: Double? = null,
    val avgAnswerSeconds: Double? = null,
)

@Serializable
data class RetencionAnalyticsDto(
    val usersWithStreak: Int? = null,
    val usersWithActiveStreak: Int? = null,
    val usersStreak7Plus: Int? = null,
    val usersStreak30Plus: Int? = null,
    val activeUsers1d: Int? = null,
    val activeUsers7d: Int? = null,
    val activeUsers30d: Int? = null,
    val usersWithCompetitiveState: Int? = null,
    val leagueDistribution: List<LeagueBucketPayload>? = null,
    val generatedAt: String = "",
    val unavailableMetrics: List<String> = emptyList(),
    val notInstrumented: List<AnalyticsGapPayload> = emptyList(),
)

@Serializable
data class LeagueBucketPayload(
    val code: String = "",
    val name: String = "",
    val minimumQuestions: Int = 0,
    val windowSize: Int = 0,
    val users: Int? = null,
)

@Serializable
data class ComunidadAnalyticsDto(
    val posts: Int? = null,
    val postsPublished: Int? = null,
    val postsPinned: Int? = null,
    val comments: Int? = null,
    val reports: Int? = null,
    val reportsPending: Int? = null,
    val reportsReviewed: Int? = null,
    val reportsRewarded: Int? = null,
    val questionFeedback: Int? = null,
    val questionFeedbackPending: Int? = null,
    val generatedAt: String = "",
    val unavailableMetrics: List<String> = emptyList(),
    val notInstrumented: List<AnalyticsGapPayload> = emptyList(),
)

@Serializable
data class EconomiaAnalyticsDto(
    val wallets: Int? = null,
    val walletTransactions: Int? = null,
    val walletCredits: Int? = null,
    val walletDebits: Int? = null,
    val walletNeutralMovements: Int? = null,
    val purchases: Int? = null,
    val subscriptions: Int? = null,
    val subscriptionsActive: Int? = null,
    val subscriptionsTrial: Int? = null,
    val subscriptionsPaused: Int? = null,
    val subscriptionsCancelled: Int? = null,
    val subscriptionsExpired: Int? = null,
    val subscriptionsOtherState: Int? = null,
    val subscriptionsExpiring30d: Int? = null,
    val commercialProducts: Int? = null,
    val commercialProductsActive: Int? = null,
    /** Céntimos enteros. Nunca Double: el dinero no admite coma flotante. */
    val grossRevenueCents: Long? = null,
    val avgOrderValueCents: Long? = null,
    val ticketsInCirculation: Long? = null,
    val generatedAt: String = "",
    val unavailableMetrics: List<String> = emptyList(),
    val notInstrumented: List<AnalyticsGapPayload> = emptyList(),
)

@Serializable
data class MarketingAnalyticsDto(
    val attributedUsers: Int? = null,
    val referredUsers: Int? = null,
    val usersWithReferralCode: Int? = null,
    val unattributedUsers: Int? = null,
    val attributionCoverageRate: Double? = null,
    val generatedAt: String = "",
    val unavailableMetrics: List<String> = emptyList(),
    val notInstrumented: List<AnalyticsGapPayload> = emptyList(),
)

// ---------------------------------------------------------------------
// Mapeo a dominio
// ---------------------------------------------------------------------

/**
 * Contexto de mapeo de una respuesta.
 *
 * Agrupa las dos listas de disponibilidad para que construir una métrica sea
 * siempre la misma llamada y nadie pueda "olvidar" mirar la lista en una de las
 * cuarenta métricas.
 *
 * Es `internal` y no privado de este fichero porque el Dashboard V2
 * ([KtorDashboardOverviewRepositoryImpl]) mapea exactamente el mismo contrato de
 * disponibilidad. Copiarlo allí sería tener dos veces la única regla que impide
 * que un número de relleno llegue a pantalla, que es justo el defecto que este
 * fichero existe para cerrar.
 */
internal class AnalyticsMapping(
    unavailable: List<String>,
    notInstrumented: List<AnalyticsGapPayload>,
    /**
     * Claves cuya LECTURA falló (`failedMetrics`, PC-04). Por defecto vacía: las
     * secciones de Analítica no la mandan todavía y siguen exactamente igual —sin
     * número, "sin dato"—; el panel de Inicio sí, y con ella distingue "no se pudo
     * leer" de "no hay datos".
     */
    failed: List<String> = emptyList(),
) {
    private val unavailableKeys = unavailable.toSet()
    private val gaps = notInstrumented.associate { it.key to it.reason }
    private val failedKeys = failed.toSet()

    fun count(key: String, value: Int?): AnalyticsMetric =
        AnalyticsMetricPolicy.metric(key, value?.let(AnalyticsValue::Count), unavailableKeys, gaps, failedKeys)

    fun percentage(key: String, value: Double?): AnalyticsMetric =
        AnalyticsMetricPolicy.metric(key, value?.let(AnalyticsValue::Percentage), unavailableKeys, gaps, failedKeys)

    fun money(key: String, value: Long?): AnalyticsMetric =
        AnalyticsMetricPolicy.metric(key, value?.let(AnalyticsValue::MoneyCents), unavailableKeys, gaps, failedKeys)

    /**
     * Métrica que el backend sólo declara como hueco (no tiene campo propio en el
     * DTO porque no hay nada que transportar).
     */
    fun declared(key: String): AnalyticsMetric =
        AnalyticsMetricPolicy.metric(key, null, unavailableKeys, gaps, failedKeys)

    /**
     * Toda clave declarada como hueco que no tenga ya su tarjeta.
     *
     * Sin esto, un agregado que el backend declara imposible (por ejemplo
     * `avgScore`) no aparecería en ninguna parte de la pantalla, y el hueco
     * pasaría de "declarado" a "invisible", que es donde mueren los pendientes.
     */
    fun remainingGaps(alreadyMapped: List<AnalyticsMetric>, extraKeys: Set<String> = emptySet()): List<AnalyticsMetric> {
        val mapped = alreadyMapped.mapTo(mutableSetOf()) { it.key } + extraKeys
        return gaps.keys.filter { it !in mapped }.map(::declared)
    }

    fun table(
        key: String,
        columns: List<String>,
        rows: List<AnalyticsTableRow>?,
    ): AnalyticsTable {
        val status = AnalyticsMetricPolicy.statusFor(
            key = key,
            value = rows?.let { AnalyticsValue.Count(it.size) },
            unavailable = unavailableKeys,
            notInstrumented = gaps.keys,
        )
        return AnalyticsTable(
            key = key,
            columns = columns,
            rows = if (status == AnalyticsMetricStatus.AVAILABLE) rows.orEmpty() else emptyList(),
            status = status,
            reason = gaps[key],
        )
    }
}

private fun ProductoAnalyticsDto.toReport(): AnalyticsSectionReport {
    val mapping = AnalyticsMapping(unavailableMetrics, notInstrumented)
    val metrics = listOf(
        mapping.count("totalUsers", totalUsers),
        mapping.count("guestUsers", guestUsers),
        mapping.count("onboardedUsers", onboardedUsers),
        mapping.percentage("onboardingCompletionRate", onboardingCompletionRate),
        mapping.count("totalStudySessions", totalStudySessions),
        mapping.count("studySessionsLast30d", studySessionsLast30d),
        mapping.count("finishedStudySessions", finishedStudySessions),
        mapping.count("abandonedStudySessions", abandonedStudySessions),
        mapping.percentage("studySessionCompletionRate", studySessionCompletionRate),
        mapping.count("totalAppEvents", totalAppEvents),
        mapping.count("appEventsLast30d", appEventsLast30d),
    )
    return AnalyticsSectionReport(
        section = AnalyticsSection.PRODUCTO,
        metrics = metrics + mapping.remainingGaps(metrics),
        tables = emptyList(),
        generatedAt = generatedAt,
    )
}

private fun EstudioAnalyticsDto.toReport(): AnalyticsSectionReport {
    val mapping = AnalyticsMapping(unavailableMetrics, notInstrumented)
    val metrics = listOf(
        mapping.count("totalAttempts", totalAttempts),
        mapping.count("attemptsCompleted", attemptsCompleted),
        mapping.count("attemptsInProgress", attemptsInProgress),
        mapping.count("attemptsOtherState", attemptsOtherState),
        mapping.count("attemptsLast30d", attemptsLast30d),
        mapping.count("attemptsFirstTime", attemptsFirstTime),
        mapping.count("attemptsRanked", attemptsRanked),
        mapping.count("attemptsRafaga", attemptsRafaga),
        mapping.count("attemptsRazonado", attemptsRazonado),
        mapping.count("attemptsInmersivo", attemptsInmersivo),
        mapping.count("attemptsOtherMode", attemptsOtherMode),
        mapping.count("totalAnswers", totalAnswers),
        mapping.count("correctAnswers", correctAnswers),
        mapping.count("wrongAnswers", wrongAnswers),
        mapping.count("unansweredAnswers", unansweredAnswers),
        mapping.percentage("globalAccuracyRate", globalAccuracyRate),
        mapping.percentage("globalErrorRate", globalErrorRate),
        mapping.count("firstTimeAnswers", firstTimeAnswers),
        mapping.count("randomAnswers", randomAnswers),
        mapping.count("changedAnswers", changedAnswers),
        mapping.count("markedForReviewAnswers", markedForReviewAnswers),
    )
    val hardest = mapping.table(
        key = "hardestQuestions",
        columns = listOf("Pregunta", "Tipo", "Respuestas", "Fallos", "% fallo", "Dificultad"),
        rows = hardestQuestions?.map { question ->
            AnalyticsTableRow(
                key = question.questionId,
                cells = listOf(
                    question.title ?: question.code ?: question.questionId,
                    question.questionType ?: "-",
                    question.totalAnswers?.toString() ?: "-",
                    question.totalWrong?.toString() ?: "-",
                    question.errorRate?.let { AnalyticsValue.Percentage(it).format() } ?: "-",
                    question.difficulty1To10?.let(::oneDecimal) ?: "-",
                ),
            )
        },
    )
    return AnalyticsSectionReport(
        section = AnalyticsSection.ESTUDIO,
        metrics = metrics + mapping.remainingGaps(metrics, setOf("hardestQuestions")),
        tables = listOf(hardest),
        generatedAt = generatedAt,
    )
}

/**
 * Dificultad 1-10 con un decimal. No es porcentaje ni dinero, así que no pasa
 * por [AnalyticsValue]: es una celda de tabla, no una métrica con unidad.
 */
private fun oneDecimal(value: Double): String {
    val scaled = kotlin.math.round(value * 10.0).toLong()
    val whole = scaled / 10
    val decimal = if (scaled % 10 < 0) -(scaled % 10) else scaled % 10
    return "$whole,$decimal"
}

private fun RetencionAnalyticsDto.toReport(): AnalyticsSectionReport {
    val mapping = AnalyticsMapping(unavailableMetrics, notInstrumented)
    val metrics = listOf(
        mapping.count("activeUsers1d", activeUsers1d),
        mapping.count("activeUsers7d", activeUsers7d),
        mapping.count("activeUsers30d", activeUsers30d),
        mapping.count("usersWithStreak", usersWithStreak),
        mapping.count("usersWithActiveStreak", usersWithActiveStreak),
        mapping.count("usersStreak7Plus", usersStreak7Plus),
        mapping.count("usersStreak30Plus", usersStreak30Plus),
        mapping.count("usersWithCompetitiveState", usersWithCompetitiveState),
    )
    val leagues = mapping.table(
        key = "leagueDistribution",
        columns = listOf("Liga", "Preguntas mínimas", "Ventana", "Usuarios"),
        rows = leagueDistribution?.map { league ->
            AnalyticsTableRow(
                key = league.code,
                cells = listOf(
                    league.name,
                    league.minimumQuestions.toString(),
                    league.windowSize.toString(),
                    // Una liga cuyo conteo falló no se rellena con 0.
                    league.users?.toString() ?: "sin dato",
                ),
            )
        },
    )
    return AnalyticsSectionReport(
        section = AnalyticsSection.RETENCION,
        metrics = metrics + mapping.remainingGaps(metrics, setOf("leagueDistribution")),
        tables = listOf(leagues),
        generatedAt = generatedAt,
    )
}

private fun ComunidadAnalyticsDto.toReport(): AnalyticsSectionReport {
    val mapping = AnalyticsMapping(unavailableMetrics, notInstrumented)
    val metrics = listOf(
        mapping.count("posts", posts),
        mapping.count("postsPublished", postsPublished),
        mapping.count("postsPinned", postsPinned),
        mapping.count("comments", comments),
        mapping.count("reports", reports),
        mapping.count("reportsPending", reportsPending),
        mapping.count("reportsReviewed", reportsReviewed),
        mapping.count("reportsRewarded", reportsRewarded),
        mapping.count("questionFeedback", questionFeedback),
        mapping.count("questionFeedbackPending", questionFeedbackPending),
    )
    return AnalyticsSectionReport(
        section = AnalyticsSection.COMUNIDAD,
        metrics = metrics + mapping.remainingGaps(metrics),
        tables = emptyList(),
        generatedAt = generatedAt,
    )
}

private fun EconomiaAnalyticsDto.toReport(): AnalyticsSectionReport {
    val mapping = AnalyticsMapping(unavailableMetrics, notInstrumented)
    val metrics = listOf(
        mapping.money("grossRevenueCents", grossRevenueCents),
        mapping.money("avgOrderValueCents", avgOrderValueCents),
        mapping.count("purchases", purchases),
        mapping.count("subscriptions", subscriptions),
        mapping.count("subscriptionsActive", subscriptionsActive),
        mapping.count("subscriptionsTrial", subscriptionsTrial),
        mapping.count("subscriptionsPaused", subscriptionsPaused),
        mapping.count("subscriptionsCancelled", subscriptionsCancelled),
        mapping.count("subscriptionsExpired", subscriptionsExpired),
        mapping.count("subscriptionsOtherState", subscriptionsOtherState),
        mapping.count("subscriptionsExpiring30d", subscriptionsExpiring30d),
        mapping.count("wallets", wallets),
        mapping.count("walletTransactions", walletTransactions),
        mapping.count("walletCredits", walletCredits),
        mapping.count("walletDebits", walletDebits),
        mapping.count("walletNeutralMovements", walletNeutralMovements),
        mapping.money("ticketsInCirculation", ticketsInCirculation),
        mapping.count("commercialProducts", commercialProducts),
        mapping.count("commercialProductsActive", commercialProductsActive),
    )
    return AnalyticsSectionReport(
        section = AnalyticsSection.ECONOMIA,
        metrics = metrics + mapping.remainingGaps(metrics),
        tables = emptyList(),
        generatedAt = generatedAt,
    )
}

private fun MarketingAnalyticsDto.toReport(): AnalyticsSectionReport {
    val mapping = AnalyticsMapping(unavailableMetrics, notInstrumented)
    val metrics = listOf(
        mapping.count("attributedUsers", attributedUsers),
        mapping.count("unattributedUsers", unattributedUsers),
        mapping.percentage("attributionCoverageRate", attributionCoverageRate),
        mapping.count("referredUsers", referredUsers),
        mapping.count("usersWithReferralCode", usersWithReferralCode),
    )
    return AnalyticsSectionReport(
        section = AnalyticsSection.MARKETING,
        metrics = metrics + mapping.remainingGaps(metrics),
        tables = emptyList(),
        generatedAt = generatedAt,
    )
}
