package com.oposicontrol.admin.domain.model

import kotlinx.datetime.Instant

data class DashboardMetrics(
    val totalSpecialties: Int,
    val totalExams: Int,
    val publishedExams: Int,
    val totalQuestions: Int,
    val lastUpdatedAt: Instant,
    val draftExams: Int = 0,
    val totalAttempts: Int = 0,
    val activeUsers30d: Int = 0,
    val avgScore: Double = 0.0,
    val globalErrorRate: Double = 0.0,
    val attemptsPerDay: List<DayCount> = emptyList(),
    val questionsWithoutResources: Int = 0,
    val linkedResources: Int = 0,
    val pendingResources: Int = 0,
    val unlinkedResources: Int = 0,
    val publishedNews: Int = 0,
    val draftNews: Int = 0,
    val activeStoreItems: Int = 0,
    val activeOffers: Int = 0,
    val scheduledOffers: Int = 0,
    val activeBurstCampaigns: Int = 0,
    val activeLimitedResources: Int = 0,
    val nextCommerceExpiryLabel: String = "Sin vencimientos programados",
    val latestImportLabel: String = "Sin registro de importaciones",
    val pendingForumReports: Int = 0,
    val alerts: List<DashboardAlert> = emptyList(),
    val recentEvents: List<DashboardActivityEvent> = emptyList(),
    /**
     * Métricas que el backend no pudo calcular, por su clave estable
     * ([DashboardMetricKey]). El valor numérico correspondiente es relleno de
     * serialización, no un dato: la UI debe pintar NO_DATA en su lugar.
     *
     * Existe porque antes un fallo de lectura llegaba a pantalla como un `0`
     * indistinguible de un cero real, y un panel de administración que enseña
     * "0 exámenes" cuando en realidad no ha podido preguntar es peor que uno que
     * admite no saberlo.
     */
    val unavailableMetrics: Set<String> = emptySet(),
) {
    /** `false` si [key] no se pudo calcular y por tanto no debe mostrarse como número. */
    fun isAvailable(key: String): Boolean = key !in unavailableMetrics
}

/**
 * Claves estables de métrica compartidas con el backend (`DashboardMetricKey`).
 * Coinciden con el nombre del campo en el DTO de transporte.
 */
object DashboardMetricKey {
    const val TOTAL_SPECIALTIES = "totalSpecialties"
    const val TOTAL_EXAMS = "totalExams"
    const val PUBLISHED_EXAMS = "publishedExams"
    const val DRAFT_EXAMS = "draftExams"
    const val TOTAL_QUESTIONS = "totalQuestions"
    const val TOTAL_ATTEMPTS = "totalAttempts"
    const val ACTIVE_USERS_30D = "activeUsers30d"
    const val AVG_SCORE = "avgScore"
    const val GLOBAL_ERROR_RATE = "globalErrorRate"
    const val ATTEMPTS_PER_DAY = "attemptsPerDay"

    // --- Fuentes que consulta el propio cliente -----------------------------
    //
    // Las de arriba las calcula el backend y llegan ya marcadas. Éstas las lee
    // el cliente por su cuenta, y hasta ahora un fallo de cualquiera de ellas se
    // convertía en un cero limpio: `getOrNull().orEmpty()`, `?: 0`,
    // `getOrDefault(0)`. Un panel que enseña "0 noticias publicadas" cuando en
    // realidad no ha podido preguntarlo es peor que uno que admite no saberlo.

    /** Catálogo comercial: productos, ofertas, ráfagas y recursos limitados. */
    const val COMMERCE = "commerce"

    /** Noticias publicadas y en borrador. */
    const val NEWS = "news"

    /** Reportes de comunidad pendientes de revisar. */
    const val FORUM_REPORTS = "forumReports"

    /** Auditoría de recursos por examen. */
    const val RESOURCE_AUDIT = "resourceAudit"
}

data class DayCount(
    val date: String,
    val count: Int,
)

data class DashboardAlert(
    val id: String,
    val title: String,
    val detail: String,
    val actionLabel: String,
    val severity: DashboardAlertSeverity,
)

enum class DashboardAlertSeverity {
    INFO,
    WARNING,
    CRITICAL,
}

data class DashboardActivityEvent(
    val id: String,
    val title: String,
    val detail: String,
    val occurredAt: Instant,
)
