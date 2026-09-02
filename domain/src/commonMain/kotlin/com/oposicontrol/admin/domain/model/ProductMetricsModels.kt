package com.oposicontrol.admin.domain.model

import kotlinx.datetime.Instant
import kotlin.math.ceil
import kotlin.math.round

data class CompetitiveAnswer(
    val questionId: String,
    val correct: Boolean,
    val attemptEligible: Boolean = true,
    val isFirstRealResponse: Boolean = true,
)

data class FirstResponseRecord(
    val userId: String,
    val questionId: String,
    val competitiveEligible: Boolean,
    val isFirstRealResponse: Boolean,
)

object FirstResponseIntegrity {
    fun hasAtMostOneFirstResponse(records: List<FirstResponseRecord>): Boolean =
        records.filter { it.isFirstRealResponse }
            .groupingBy { it.userId to it.questionId }
            .eachCount()
            .values.all { it <= 1 }

    fun shouldMarkFirst(existing: List<FirstResponseRecord>, userId: String, questionId: String): Boolean =
        existing.none { it.userId == userId && it.questionId == questionId }
}

data class CompetitiveSnapshot(
    val leagueCode: String,
    val questionCount: Int,
    val windowSize: Int,
    val score: Double,
)

object CompetitiveMetrics {
    private val leagueRules = listOf(
        "1000" to (1001 to 100),
        "800" to (801 to 100),
        "500" to (501 to 100),
        "200" to (201 to 100),
        "50" to (50 to 50),
    )

    /** Input must be chronological; repeated answers after the first are learning history only. */
    fun firstCompetitiveAnswers(answers: List<CompetitiveAnswer>): List<CompetitiveAnswer> {
        val seenQuestions = mutableSetOf<String>()
        return answers.filter { answer ->
            answer.attemptEligible && answer.isFirstRealResponse && seenQuestions.add(answer.questionId)
        }
    }

    fun snapshot(answers: List<CompetitiveAnswer>): CompetitiveSnapshot? {
        val firstAnswers = firstCompetitiveAnswers(answers)
        val rule = leagueRules.firstOrNull { (_, thresholdAndWindow) ->
            firstAnswers.size >= thresholdAndWindow.first
        } ?: return null
        val windowSize = rule.second.second
        val window = firstAnswers.takeLast(windowSize)
        val correct = window.count { it.correct }
        return CompetitiveSnapshot(
            leagueCode = rule.first,
            questionCount = firstAnswers.size,
            windowSize = windowSize,
            score = correct.toDouble() / window.size * 10.0,
        )
    }

    fun updateEvolution(
        previous: EvolutionState,
        previousQuestionCount: Int,
        currentQuestionCount: Int,
        currentScore: Double,
    ): EvolutionState {
        val newQuestions = (currentQuestionCount - previousQuestionCount).coerceAtLeast(0)
        val pending = previous.questionsSinceReference + newQuestions
        return if (pending >= 20) {
            EvolutionState(
                referenceScore = currentScore,
                evolution = currentScore - previous.referenceScore,
                questionsSinceReference = 0,
            )
        } else {
            previous.copy(
                evolution = currentScore - previous.referenceScore,
                questionsSinceReference = pending,
            )
        }
    }
}

data class EvolutionState(
    val referenceScore: Double,
    val evolution: Double,
    val questionsSinceReference: Int,
)

enum class AnswerOutcome { CORRECT, INCORRECT, UNANSWERED }

data class TagPerformance(
    val correct: Int,
    val incorrect: Int,
    val unanswered: Int,
    val totalTimeSeconds: Int,
    val lastExposedAt: Instant?,
) {
    val total: Int get() = correct + incorrect + unanswered
    val failureRate: Double get() = if (total == 0) 0.0 else incorrect.toDouble() / total
}

object TagMetrics {
    fun aggregate(
        outcomes: List<Pair<AnswerOutcome, Int>>,
        lastExposedAt: Instant? = null,
    ): TagPerformance = TagPerformance(
        correct = outcomes.count { it.first == AnswerOutcome.CORRECT },
        incorrect = outcomes.count { it.first == AnswerOutcome.INCORRECT },
        unanswered = outcomes.count { it.first == AnswerOutcome.UNANSWERED },
        totalTimeSeconds = outcomes.sumOf { it.second.coerceAtLeast(0) },
        lastExposedAt = lastExposedAt,
    )
}

object ExamGrade {
    fun verbal(score: Double): String = when {
        score < 5.0 -> "No suficiente"
        score < 6.0 -> "Suficiente"
        score < 7.0 -> "Bien"
        score < 9.0 -> "Notable"
        else -> "Sobresaliente"
    }
}

object DifficultyMetrics {
    fun median(values: List<Int>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]).toDouble() / 2.0
        } else {
            sorted[middle].toDouble()
        }
    }

    fun failurePoints(failures: Int, sampleSize: Int): Int {
        if (sampleSize <= 0) return 0
        val percentage = failures.toDouble() / sampleSize * 100.0
        return when {
            percentage <= 20.0 -> 1
            percentage <= 40.0 -> 2
            percentage <= 60.0 -> 3
            percentage <= 80.0 -> 4
            else -> 5
        }
    }

    /** Higher median response time receives a higher time quintile. */
    fun timePoints(medianSeconds: Double, comparableMedians: List<Double>): Int {
        if (comparableMedians.isEmpty()) return 0
        val sorted = comparableMedians.sorted()
        val rank = sorted.indexOfLast { it <= medianSeconds } + 1
        return ceil(rank.toDouble() / sorted.size * 5.0).toInt().coerceIn(1, 5)
    }

    fun finalDifficulty(failurePoints: Int, timePoints: Int): Int =
        round((failurePoints + timePoints).toDouble() / 2.0).toInt().coerceIn(1, 5)
}

data class TicketMovement(
    val id: String,
    val amount: Int,
    val balanceBefore: Int,
    val balanceAfter: Int,
    val idempotencyKey: String? = null,
)

object TicketLedger {
    fun isConsistent(movements: List<TicketMovement>, currentBalance: Int): Boolean {
        var expectedBefore: Int? = null
        val keys = mutableSetOf<String>()
        for (movement in movements) {
            if (movement.balanceBefore + movement.amount != movement.balanceAfter) return false
            if (expectedBefore != null && expectedBefore != movement.balanceBefore) return false
            if (movement.idempotencyKey != null && !keys.add(movement.idempotencyKey)) return false
            expectedBefore = movement.balanceAfter
        }
        return movements.lastOrNull()?.balanceAfter == currentBalance ||
            (movements.isEmpty() && currentBalance == 0)
    }
}

data class VersionedEvent(val eventId: String, val eventVersion: Int)

object EventIdempotency {
    fun deduplicate(events: List<VersionedEvent>): List<VersionedEvent> {
        val seen = mutableSetOf<String>()
        return events.filter { it.eventVersion > 0 && seen.add(it.eventId) }
    }
}

data class UnitContentPurchase(val examType: ExamType, val expiresAt: Instant)

object ContentAccess {
    fun canAccess(
        examType: ExamType,
        subscriptionBenefits: Set<String>,
        unitPurchase: UnitContentPurchase?,
        now: Instant,
    ): Boolean {
        val planEntitlement = CanonicalEntitlementPolicy.canAccess(
            CanonicalPlanEntitlements("runtime", subscriptionBenefits),
            examType.wireValue,
        )
        return planEntitlement || (unitPurchase?.examType == examType && unitPurchase.expiresAt > now)
    }
}

/**
 * Qué modos existen realmente para una especialidad.
 *
 * No todas las oposiciones tienen el mismo contenido: unas sólo tienen ráfagas,
 * otras ráfagas y razonados, y sólo algunas el ecosistema completo con inmersivos.
 * OposiBot debe pintar únicamente lo habilitado, así que esto es configuración de
 * producto administrable, no una constante de frontend — habilitar un modo para
 * una especialidad no puede exigir recompilar la app.
 *
 * Respaldo: `public.capacidades_especialidad`, administrable desde
 * `/api/v1/admin/especialidades/{id}/capacidades`.
 */
data class SpecialtyCapabilities(
    val permiteRafagas: Boolean,
    val permiteRazonados: Boolean,
    val permiteInmersivos: Boolean,
    val permiteDemos: Boolean,
    val permiteOposimarket: Boolean,
    /** Vacío en los usos de sólo lectura de acceso, donde la especialidad ya es contexto. */
    val specialtyId: String = "",
    /** Explicación mostrable cuando la especialidad tiene contenido limitado. */
    val limitationMessage: String? = null,
    /**
     * `false` cuando la especialidad aún no tiene fila propia y esto son los
     * valores por defecto. Distinto de "configurada con todo desactivado": el
     * panel necesita poder avisar de que nadie ha decidido esto todavía.
     */
    val configured: Boolean = true,
) {
    /** Modos de examen habilitados, en vocabulario canónico rafaga/razonado/inmersivo. */
    val enabledExamModes: List<String>
        get() = buildList {
            if (permiteRafagas) add("rafaga")
            if (permiteRazonados) add("razonado")
            if (permiteInmersivos) add("inmersivo")
        }

    /**
     * `true` cuando el usuario final no tendría ningún examen que hacer. Demos y
     * market no cuentan: habilitan otras superficies, no un tipo de examen.
     */
    val hasNoExamModes: Boolean get() = enabledExamModes.isEmpty()
}

object SpecialtyAccess {
    fun allows(capabilities: SpecialtyCapabilities, examType: ExamType): Boolean = when (examType) {
        ExamType.RAFAGA -> capabilities.permiteRafagas
        ExamType.RAZONADO -> capabilities.permiteRazonados
        ExamType.INMERSIVO -> capabilities.permiteInmersivos
    }
}
