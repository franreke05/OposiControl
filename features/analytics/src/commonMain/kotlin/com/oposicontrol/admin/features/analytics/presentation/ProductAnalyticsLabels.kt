package com.oposicontrol.admin.features.analytics.presentation

import com.oposicontrol.admin.domain.model.AnalyticsMetric
import com.oposicontrol.admin.domain.model.AnalyticsMetricStatus
import com.oposicontrol.admin.domain.model.AnalyticsSection
import com.oposicontrol.admin.domain.model.format

/**
 * Textos de la analítica.
 *
 * Viven en presentación y no en el dominio porque son literalmente lo que se
 * lee en pantalla; el dominio sólo conoce la clave estable, que es lo que
 * comparte con el backend.
 */

internal fun AnalyticsSection.label(): String = when (this) {
    AnalyticsSection.PRODUCTO -> "Producto"
    AnalyticsSection.ESTUDIO -> "Estudio"
    AnalyticsSection.RETENCION -> "Retención"
    AnalyticsSection.COMUNIDAD -> "Comunidad"
    AnalyticsSection.ECONOMIA -> "Economía"
    AnalyticsSection.MARKETING -> "Marketing"
}

internal fun AnalyticsSection.description(): String = when (this) {
    AnalyticsSection.PRODUCTO -> "Altas, onboarding y sesiones de estudio."
    AnalyticsSection.ESTUDIO -> "Intentos, respuestas y preguntas más falladas."
    AnalyticsSection.RETENCION -> "Actividad reciente, rachas y ligas."
    AnalyticsSection.COMUNIDAD -> "OposiRed, reportes y feedback de preguntas."
    AnalyticsSection.ECONOMIA -> "Compras, suscripciones y cartera de OposiTickets."
    AnalyticsSection.MARKETING -> "Atribución de altas y referidos."
}

/**
 * Cómo se pinta una métrica sin valor.
 *
 * Son dos textos distintos a propósito: NO_DATA invita a reintentar, y
 * NOT_INSTRUMENTED avisa de que reintentar no va a servir de nada porque el
 * agregado todavía no existe. Un único "—" borraría esa diferencia, que es la
 * información más accionable de la pantalla.
 */
internal fun AnalyticsMetric.displayValue(): String = when (status) {
    AnalyticsMetricStatus.AVAILABLE -> value?.format() ?: NO_DATA_LABEL
    AnalyticsMetricStatus.NO_DATA -> NO_DATA_LABEL
    AnalyticsMetricStatus.NOT_INSTRUMENTED -> NOT_INSTRUMENTED_LABEL
}

internal fun AnalyticsMetric.statusLabel(): String = when (status) {
    AnalyticsMetricStatus.AVAILABLE -> "dato real"
    AnalyticsMetricStatus.NO_DATA -> "no se pudo leer"
    AnalyticsMetricStatus.NOT_INSTRUMENTED -> "sin instrumentar"
}

internal const val NO_DATA_LABEL = "No disponible"
internal const val NOT_INSTRUMENTED_LABEL = "Sin instrumentar"

/**
 * Nombre legible de cada métrica.
 *
 * El `else` devuelve la clave cruda a propósito: si el backend añade una métrica
 * y aquí no se traduce, la tarjeta aparece igualmente con su clave técnica en
 * vez de desaparecer sin que nadie se entere.
 */
internal fun analyticsMetricLabel(key: String): String = when (key) {
    // Producto
    "totalUsers" -> "Usuarios registrados"
    "guestUsers" -> "Usuarios invitados"
    "onboardedUsers" -> "Onboarding completado"
    "onboardingCompletionRate" -> "% onboarding completado"
    "totalAppEvents" -> "Eventos de app"
    "appEventsLast30d" -> "Eventos (30 días)"
    "totalStudySessions" -> "Sesiones de estudio"
    "studySessionsLast30d" -> "Sesiones (30 días)"
    "finishedStudySessions" -> "Sesiones terminadas"
    "abandonedStudySessions" -> "Sesiones abandonadas"
    "studySessionCompletionRate" -> "% sesiones terminadas"
    "eventsByName" -> "Eventos por nombre"
    "sessionsByPlatform" -> "Sesiones por plataforma"
    "sessionsByAppVersion" -> "Sesiones por versión"
    "avgStudySessionSeconds" -> "Duración media de sesión"

    // Estudio
    "totalAttempts" -> "Intentos de examen"
    "attemptsCompleted" -> "Intentos completados"
    "attemptsInProgress" -> "Intentos en curso"
    "attemptsOtherState" -> "Intentos en otro estado"
    "attemptsLast30d" -> "Intentos (30 días)"
    "attemptsFirstTime" -> "Primeros intentos"
    "attemptsRanked" -> "Intentos que puntúan"
    "attemptsRafaga" -> "Intentos ráfaga"
    "attemptsRazonado" -> "Intentos razonado"
    "attemptsInmersivo" -> "Intentos inmersivo"
    "attemptsOtherMode" -> "Intentos de otro modo"
    "totalAnswers" -> "Respuestas"
    "correctAnswers" -> "Respuestas correctas"
    "wrongAnswers" -> "Respuestas falladas"
    "unansweredAnswers" -> "Sin responder"
    "globalAccuracyRate" -> "% de acierto global"
    "globalErrorRate" -> "% de fallo global"
    "firstTimeAnswers" -> "Primeras respuestas"
    "randomAnswers" -> "Respondidas al azar"
    "changedAnswers" -> "Respuestas cambiadas"
    "markedForReviewAnswers" -> "Marcadas para repasar"
    "hardestQuestions" -> "Preguntas más falladas"
    "avgScore" -> "Nota media"
    "avgAttemptDurationSeconds" -> "Duración media de intento"
    "avgAnswerTimeSeconds" -> "Tiempo medio por respuesta"
    "attemptsPerDay" -> "Intentos por día"
    "tagWeaknessRanking" -> "Debilidades por etiqueta"

    // Retención
    "activeUsers1d" -> "Activos hoy"
    "activeUsers7d" -> "Activos (7 días)"
    "activeUsers30d" -> "Activos (30 días)"
    "usersWithStreak" -> "Usuarios con racha"
    "usersWithActiveStreak" -> "Rachas vivas"
    "usersStreak7Plus" -> "Racha de 7 días o más"
    "usersStreak30Plus" -> "Racha de 30 días o más"
    "usersWithCompetitiveState" -> "Usuarios en competición"
    "leagueDistribution" -> "Reparto por liga"
    "avgCurrentStreakDays" -> "Racha media actual"
    "avgBestStreakDays" -> "Mejor racha media"
    "avgCompetitiveScore" -> "Nota media competitiva"
    "retentionCohorts" -> "Retención por cohorte"

    // Comunidad
    "posts" -> "Publicaciones"
    "postsPublished" -> "Publicaciones publicadas"
    "postsPinned" -> "Publicaciones fijadas"
    "comments" -> "Comentarios"
    "reports" -> "Reportes de contenido"
    "reportsPending" -> "Reportes pendientes"
    "reportsReviewed" -> "Reportes revisados"
    "reportsRewarded" -> "Reportes recompensados"
    "questionFeedback" -> "Feedback de preguntas"
    "questionFeedbackPending" -> "Feedback pendiente"
    "postsByCategory" -> "Publicaciones por categoría"
    "topContributors" -> "Mayores contribuidores"
    "avgCommentsPerPost" -> "Comentarios por publicación"
    "medianReportResolutionHours" -> "Mediana de resolución de reportes"

    // Economía
    "grossRevenueCents" -> "Ingreso bruto"
    "avgOrderValueCents" -> "Ticket medio"
    "purchases" -> "Compras"
    "subscriptions" -> "Suscripciones"
    "subscriptionsActive" -> "Suscripciones activas"
    "subscriptionsTrial" -> "En periodo de prueba"
    "subscriptionsPaused" -> "Suscripciones pausadas"
    "subscriptionsCancelled" -> "Suscripciones canceladas"
    "subscriptionsExpired" -> "Suscripciones caducadas"
    "subscriptionsOtherState" -> "Suscripciones en otro estado"
    "subscriptionsExpiring30d" -> "Vencen en 30 días"
    "wallets" -> "Carteras"
    "walletTransactions" -> "Movimientos de cartera"
    "walletCredits" -> "Movimientos de abono"
    "walletDebits" -> "Movimientos de cargo"
    "walletNeutralMovements" -> "Movimientos sin importe"
    "ticketsInCirculation" -> "OposiTickets en circulación"
    "commercialProducts" -> "Productos comerciales"
    "commercialProductsActive" -> "Productos activos"
    "revenueByProduct" -> "Ingreso por producto"
    "purchasesByState" -> "Compras por estado"

    // Marketing
    "attributedUsers" -> "Usuarios con atribución"
    "unattributedUsers" -> "Usuarios sin atribución"
    "attributionCoverageRate" -> "% con atribución"
    "referredUsers" -> "Usuarios referidos"
    "usersWithReferralCode" -> "Con código de referido"
    "usersBySource" -> "Usuarios por fuente"
    "usersByChannel" -> "Usuarios por canal"
    "usersByCampaign" -> "Usuarios por campaña"
    "topReferrers" -> "Mayores referidores"

    else -> key
}
