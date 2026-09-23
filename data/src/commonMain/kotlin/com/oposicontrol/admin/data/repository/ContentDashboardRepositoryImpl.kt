package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.common.result.getOrNull
import com.oposicontrol.admin.data.remote.api.DashboardApi
import com.oposicontrol.admin.domain.model.CommerceStatus
import com.oposicontrol.admin.domain.model.DashboardActivityEvent
import com.oposicontrol.admin.domain.model.DashboardAlert
import com.oposicontrol.admin.domain.model.DashboardAlertSeverity
import com.oposicontrol.admin.domain.model.DashboardMetricKey
import com.oposicontrol.admin.domain.model.DashboardMetrics
import com.oposicontrol.admin.domain.model.ExamResourceAudit
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.repository.CommerceRepository
import com.oposicontrol.admin.domain.repository.ContentRepository
import com.oposicontrol.admin.domain.repository.DashboardRepository
import com.oposicontrol.admin.domain.repository.NewsRepository
import com.oposicontrol.admin.domain.repository.ModerationRepository
import kotlinx.coroutines.flow.first
import kotlin.time.Clock

class ContentDashboardRepositoryImpl(
    private val contentRepository: ContentRepository,
    private val commerceRepository: CommerceRepository,
    private val newsRepository: NewsRepository,
    private val dashboardApi: DashboardApi,
    private val moderationRepository: ModerationRepository,
) : DashboardRepository {
    override suspend fun getMetrics(): AppResult<DashboardMetrics> {
        return when (val specialties = contentRepository.refreshSpecialties()) {
            is AppResult.Failure -> specialties
            is AppResult.Success -> when (val exams = contentRepository.refreshExams()) {
                is AppResult.Failure -> exams
                is AppResult.Success -> {
                    // Cada fuente que falla se ANOTA en vez de convertirse en un
                    // cero. El cero seguía llegando a la pantalla como un dato
                    // más, indistinguible de un cero real: se comprobó en
                    // logcat que `articulo_tienda` y `/content/noticias` fallaban
                    // de verdad y Home mostraba ceros limpios.
                    //
                    // Se sigue devolviendo `Success`: que se caiga el catálogo
                    // comercial no puede vaciar Home entera. Lo que se pierde es
                    // ese bloque, no la pantalla.
                    val auditResults = exams.value.map { exam ->
                        contentRepository.getExamResourceAudit(exam.id)
                    }
                    val audits = auditResults.mapNotNull { it.getOrNull() }

                    // El refresco se hace UNA vez y su resultado se reutiliza:
                    // llamar a `refreshCommerce()` aquí y otra vez dentro de
                    // `loadCommerceSnapshot()` duplicaría la petición de red.
                    val commerce = commerceRepository.refreshCommerce()
                    val commerceSnapshot = loadCommerceSnapshot(commerce)

                    val newsResult = newsRepository.refreshNews()
                    val news = newsResult.getOrNull().orEmpty()

                    val performanceMetrics = runCatching { dashboardApi.getMetrics() }.getOrNull()

                    val pendingReportsResult = runCatching { moderationRepository.listPendingReports() }
                        .getOrNull()
                    val pendingForumReports = pendingReportsResult?.getOrNull()?.size ?: 0

                    val unavailable = unavailableDashboardMetrics(
                        resourceAuditFailed = auditResults.any { it is AppResult.Failure },
                        commerceFailed = commerce is AppResult.Failure,
                        newsFailed = newsResult is AppResult.Failure,
                        performanceMissing = performanceMetrics == null,
                        forumReportsFailed = pendingReportsResult == null ||
                            pendingReportsResult is AppResult.Failure,
                    )
                    val questionsWithoutResources = audits.sumOf { it.summary.questionsWithoutResources }
                    val unlinkedResources = audits.sumOf { it.summary.unlinkedResources }
                    val pendingResources = audits.sumOf { it.summary.pendingResources }
                    val activeOffers = commerceSnapshot.offers.count { it.status == CommerceStatus.ACTIVE }
                    val scheduledOffers = commerceSnapshot.offers.count { it.status == CommerceStatus.SCHEDULED }
                    val activeBursts = commerceSnapshot.bursts.count { it.status == CommerceStatus.ACTIVE }
                    val activeLimitedResources = commerceSnapshot.limitedResources.count { it.status == CommerceStatus.ACTIVE }
                    val now = Clock.System.now()

                    AppResult.Success(
                        DashboardMetrics(
                        totalSpecialties = specialties.value.size,
                        totalExams = exams.value.size,
                            publishedExams = exams.value.count { it.status == ExamStatus.PUBLISHED },
                        totalQuestions = exams.value.sumOf { it.questionCount },
                            lastUpdatedAt = now,
                            draftExams = exams.value.count { it.status == ExamStatus.DRAFT },
                            totalAttempts = performanceMetrics?.totalAttempts ?: 0,
                            activeUsers30d = performanceMetrics?.activeUsers30d ?: 0,
                            avgScore = performanceMetrics?.avgScore ?: 0.0,
                            globalErrorRate = performanceMetrics?.globalErrorRate ?: 0.0,
                            attemptsPerDay = performanceMetrics?.attemptsPerDay.orEmpty(),
                            questionsWithoutResources = questionsWithoutResources,
                            linkedResources = audits.sumOf { it.summary.linkedResources },
                            pendingResources = pendingResources,
                            unlinkedResources = unlinkedResources,
                            publishedNews = news.count { it.status.equals("published", ignoreCase = true) },
                            draftNews = news.count { it.status.equals("draft", ignoreCase = true) || it.status.equals("borrador", ignoreCase = true) },
                            activeStoreItems = commerceSnapshot.items.count { it.status == CommerceStatus.ACTIVE },
                            activeOffers = activeOffers,
                            scheduledOffers = scheduledOffers,
                            activeBurstCampaigns = activeBursts,
                            activeLimitedResources = activeLimitedResources,
                            nextCommerceExpiryLabel = buildNextCommerceExpiryLabel(commerceSnapshot),
                            latestImportLabel = "Última revisión: ${exams.value.maxByOrNull { it.updatedAt }?.updatedAt ?: now}",
                            pendingForumReports = pendingForumReports,
                            alerts = buildAlerts(
                                questionsWithoutResources = questionsWithoutResources,
                                unlinkedResources = unlinkedResources,
                                draftNews = news.count { it.status.equals("draft", ignoreCase = true) || it.status.equals("borrador", ignoreCase = true) },
                                scheduledOffers = scheduledOffers,
                                activeBursts = activeBursts,
                            ),
                            recentEvents = buildRecentEvents(
                                latestExamTitle = exams.value.maxByOrNull { it.updatedAt }?.title,
                                latestNewsTitle = news.firstOrNull()?.title,
                                activeOffers = activeOffers,
                                timestamp = now,
                            ),
                            unavailableMetrics = unavailable,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun loadCommerceSnapshot(refresh: AppResult<*>): CommerceSnapshot {
        return when (refresh) {
            is AppResult.Failure -> CommerceSnapshot()
            is AppResult.Success -> CommerceSnapshot(
                items = commerceRepository.observeStoreItems().first(),
                offers = commerceRepository.observeStoreOffers().first(),
                limitedResources = commerceRepository.observeLimitedResources().first(),
                bursts = commerceRepository.observeBurstCampaigns().first(),
            )
        }
    }

    private fun buildNextCommerceExpiryLabel(snapshot: CommerceSnapshot): String {
        val nextExpiry = (snapshot.offers.mapNotNull { it.endsAt } + snapshot.limitedResources.mapNotNull { it.endsAt } + snapshot.bursts.mapNotNull { it.endsAt })
            .minOrNull()
        return nextExpiry?.let { "Proximo vencimiento: $it" } ?: "Sin vencimientos programados"
    }

    private fun buildAlerts(
        questionsWithoutResources: Int,
        unlinkedResources: Int,
        draftNews: Int,
        scheduledOffers: Int,
        activeBursts: Int,
    ): List<DashboardAlert> {
        return listOfNotNull(
            if (questionsWithoutResources > 0) {
                DashboardAlert(
                    id = "questions_without_resources",
                    title = "$questionsWithoutResources preguntas sin recurso",
                    detail = "Completa recursos antes de publicar contenido sensible.",
                    actionLabel = "Auditar",
                    severity = DashboardAlertSeverity.WARNING,
                )
            } else {
                null
            },
            if (unlinkedResources > 0) {
                DashboardAlert(
                    id = "unlinked_resources",
                    title = "$unlinkedResources recursos sin enlazar",
                    detail = "Hay archivos o filas pendientes de asociar.",
                    actionLabel = "Enlazar",
                    severity = DashboardAlertSeverity.CRITICAL,
                )
            } else {
                null
            },
            if (draftNews > 0) {
                DashboardAlert(
                    id = "draft_news",
                    title = "$draftNews noticias en borrador",
                    detail = "Revisa piezas editoriales antes de publicar.",
                    actionLabel = "Abrir",
                    severity = DashboardAlertSeverity.INFO,
                )
            } else {
                null
            },
            if (scheduledOffers > 0) {
                DashboardAlert(
                    id = "scheduled_offers",
                    title = "$scheduledOffers ofertas programadas",
                    detail = "Comprueba fechas y disponibilidad temporal.",
                    actionLabel = "Ver tienda",
                    severity = DashboardAlertSeverity.INFO,
                )
            } else {
                null
            },
            if (activeBursts == 0) {
                DashboardAlert(
                    id = "inactive_bursts",
                    title = "Sin ráfagas activas",
                    detail = "No hay campañas de impulso funcionando ahora.",
                    actionLabel = "Programar",
                    severity = DashboardAlertSeverity.INFO,
                )
            } else {
                null
            },
        ).take(5)
    }

    private fun buildRecentEvents(
        latestExamTitle: String?,
        latestNewsTitle: String?,
        activeOffers: Int,
        timestamp: kotlin.time.Instant,
    ): List<DashboardActivityEvent> {
        return listOfNotNull(
            latestExamTitle?.let {
                DashboardActivityEvent(
                    id = "latest_exam",
                    title = "Examen actualizado",
                    detail = it,
                    occurredAt = timestamp,
                )
            },
            latestNewsTitle?.let {
                DashboardActivityEvent(
                    id = "latest_news",
                    title = "Noticia disponible",
                    detail = it,
                    occurredAt = timestamp,
                )
            },
            if (activeOffers > 0) {
                DashboardActivityEvent(
                    id = "active_offers",
                    title = "Oferta activa",
                    detail = "$activeOffers ofertas visibles en tienda",
                    occurredAt = timestamp,
                )
            } else {
                null
            },
        )
    }

    private data class CommerceSnapshot(
        val items: List<com.oposicontrol.admin.domain.model.StoreItem> = emptyList(),
        val offers: List<com.oposicontrol.admin.domain.model.StoreOffer> = emptyList(),
        val limitedResources: List<com.oposicontrol.admin.domain.model.LimitedResourceWindow> = emptyList(),
        val bursts: List<com.oposicontrol.admin.domain.model.BurstCampaign> = emptyList(),
    )
}

/**
 * Qué métricas de Home NO tienen dato, dadas las fuentes que han fallado.
 *
 * Está separada del repositorio y es pura porque es la decisión que importa y la
 * única que se puede comprobar sin levantar cinco dobles de repositorio. El
 * defecto que corrige era este: cada fuente caída se convertía en un cero limpio
 * (`getOrNull().orEmpty()`, `?: 0`, `getOrDefault(0)`) y llegaba a pantalla como
 * un dato más. Se verificó en logcat que `articulo_tienda` y `/content/noticias`
 * fallaban de verdad mientras Home mostraba ceros impecables.
 *
 * Las cuatro métricas de rendimiento van juntas a propósito: las calcula el
 * backend en una sola respuesta, así que o están las cuatro o no está ninguna.
 */
internal fun unavailableDashboardMetrics(
    resourceAuditFailed: Boolean,
    commerceFailed: Boolean,
    newsFailed: Boolean,
    performanceMissing: Boolean,
    forumReportsFailed: Boolean,
): Set<String> = buildSet {
    if (resourceAuditFailed) add(DashboardMetricKey.RESOURCE_AUDIT)
    if (commerceFailed) add(DashboardMetricKey.COMMERCE)
    if (newsFailed) add(DashboardMetricKey.NEWS)
    if (forumReportsFailed) add(DashboardMetricKey.FORUM_REPORTS)
    if (performanceMissing) {
        add(DashboardMetricKey.TOTAL_ATTEMPTS)
        add(DashboardMetricKey.ACTIVE_USERS_30D)
        add(DashboardMetricKey.AVG_SCORE)
        add(DashboardMetricKey.GLOBAL_ERROR_RATE)
    }
}
