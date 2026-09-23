package com.oposicontrol.admin.domain.model

/**
 * Ficha completa de un usuario para el panel de escritorio.
 *
 * **Cada sección es nullable a propósito.** `null` significa "esa fila no existe
 * todavía" — un usuario sin perfil ampliado, sin estado competitivo o sin cartera
 * es un caso normal, no un error. La UI debe pintar NO_DATA, nunca un cero: un
 * cero inventado en una ficha de usuario lleva a decisiones administrativas sobre
 * datos que no existen.
 *
 * [unavailableSections] es distinto: son secciones que **no se pudieron leer**.
 * Un hueco por fallo y un hueco por ausencia se muestran de forma diferente.
 */
data class UserDetail(
    val profile: UserProfile,
    val extendedProfile: UserExtendedProfile? = null,
    val performance: UserPerformance? = null,
    val competitive: UserCompetitiveState? = null,
    val streak: UserStreak? = null,
    val economy: UserEconomy? = null,
    val subscriptions: List<UserSubscription> = emptyList(),
    val testerState: UserTesterState? = null,
    val weaknesses: List<UserTagWeakness> = emptyList(),
    val unavailableSections: Set<String> = emptySet(),
) {
    fun isUnavailable(section: String): Boolean = section in unavailableSections
}

data class UserProfile(
    val userId: String,
    val displayName: String? = null,
    val username: String? = null,
    val specialtyId: String? = null,
    val communityId: String? = null,
    val isGuest: Boolean = false,
    val onboardingCompleted: Boolean = false,
    val createdAt: String? = null,
)

/** Perfil ampliado; se rellena tras el onboarding, así que suele faltar al principio. */
data class UserExtendedProfile(
    val fullName: String? = null,
    val age: Int? = null,
    val city: String? = null,
    val employmentStatus: String? = null,
    val workplace: String? = null,
    val availableStudyMinutes: Int? = null,
    val preferredStudySlot: String? = null,
    val targetExamDate: String? = null,
    val updatedAt: String? = null,
)

data class UserPerformance(
    val finishedExams: Int = 0,
    val answeredQuestions: Int = 0,
    val correctAnswers: Int = 0,
    val successRate: Double = 0.0,
    val totalPoints: Int = 0,
    val level: Int = 1,
    val updatedAt: String? = null,
) {
    /** Falladas derivadas de lo que sí se guarda; no hay columna propia. */
    val failedAnswers: Int get() = (answeredQuestions - correctAnswers).coerceAtLeast(0)
}

/**
 * Nota media, evolución, liga y ranking.
 *
 * [questionsSinceLastEvolution] importa tanto como [evolution]: la evolución sólo
 * se recalcula al completar un bloque de preguntas nuevas, así que sin ese
 * contador no se sabe si la cifra es reciente o lleva tiempo congelada.
 *
 * [globalPercentile] es un indicador **comparativo interno** de la aplicación.
 * No es una probabilidad oficial de obtener plaza y no debe etiquetarse como tal.
 */
data class UserCompetitiveState(
    val leagueCode: String? = null,
    val leagueName: String? = null,
    val calculationWindow: Int? = null,
    val rankingEligibleQuestions: Int? = null,
    val currentAverage: Double? = null,
    val evolutionReference: Double? = null,
    val evolution: Double? = null,
    val questionsSinceLastEvolution: Int? = null,
    val leagueRank: Int? = null,
    val globalRank: Int? = null,
    val globalPercentile: Double? = null,
    val updatedAt: String? = null,
)

data class UserStreak(
    val currentDays: Int = 0,
    val bestDays: Int = 0,
    val lastActivityDate: String? = null,
)

data class UserEconomy(
    val ticketBalance: Int = 0,
    val isPremium: Boolean = false,
    val subscriptionStatus: String? = null,
    val productCode: String? = null,
    val provider: String? = null,
    val renewsAt: String? = null,
    val endsAt: String? = null,
)

data class UserSubscription(
    val id: String,
    val provider: String,
    val productCode: String,
    val status: String,
    val startedAt: String? = null,
    val endsAt: String? = null,
    val renewsAt: String? = null,
)

data class UserTesterState(
    val reportsSubmitted: Int = 0,
    val reportsValidated: Int = 0,
    val ticketsEarned: Int = 0,
    val level: String? = null,
)

/** Dominio por etiqueta temática, peor primero. */
data class UserTagWeakness(
    val tagId: String,
    val tag: String,
    val answered: Int = 0,
    val correct: Int = 0,
    val failed: Int = 0,
    val successRate: Double = 0.0,
    val masteryLevel: String? = null,
    val lastPracticedAt: String? = null,
)

/** Claves de sección, compartidas con el backend. */
object UserDetailSection {
    const val EXTENDED_PROFILE = "detalle"
    const val PERFORMANCE = "rendimiento"
    const val COMPETITIVE = "competitivo"
    const val STREAK = "racha"
    const val ECONOMY = "economia"
    const val SUBSCRIPTIONS = "suscripciones"
    const val TESTER = "opositester"
    const val WEAKNESSES = "debilidades"
}
