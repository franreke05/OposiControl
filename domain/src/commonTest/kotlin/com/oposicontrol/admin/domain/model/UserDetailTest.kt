package com.oposicontrol.admin.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * La ficha de usuario distingue tres estados por sección, y confundirlos es
 * exactamente el fallo que ya se corrigió en el dashboard:
 *
 *  - sección con datos            -> se muestran
 *  - sección `null`               -> la fila no existe todavía (NO_DATA)
 *  - sección en unavailableSections -> no se pudo leer (NO_DISPONIBLE)
 */
class UserDetailTest {

    private val profile = UserProfile(userId = "u-1", displayName = "Arturo")

    @Test
    fun aMissingSectionIsNullAndNotAnEmptyObject() {
        // Un usuario recién registrado no tiene ninguna de estas filas. Rellenarlas
        // con ceros haría creer que su racha es 0 y su nota media 0.
        val detail = UserDetail(profile = profile)

        assertNull(detail.performance)
        assertNull(detail.competitive)
        assertNull(detail.streak)
        assertNull(detail.economy)
        assertTrue(detail.weaknesses.isEmpty())
    }

    @Test
    fun anUnreadableSectionIsDistinguishableFromAMissingOne() {
        val detail = UserDetail(
            profile = profile,
            unavailableSections = setOf(UserDetailSection.COMPETITIVE),
        )

        assertTrue(detail.isUnavailable(UserDetailSection.COMPETITIVE))
        // Ausente pero legible: no está marcada, sólo es null.
        assertFalse(detail.isUnavailable(UserDetailSection.STREAK))
    }

    @Test
    fun aSectionWithRealZerosIsNotMarkedUnavailable() {
        // Un usuario que respondió 0 preguntas es un cero legítimo.
        val detail = UserDetail(
            profile = profile,
            performance = UserPerformance(answeredQuestions = 0, correctAnswers = 0),
        )

        assertFalse(detail.isUnavailable(UserDetailSection.PERFORMANCE))
        assertEquals(0, detail.performance?.answeredQuestions)
    }

    @Test
    fun failedAnswersAreDerivedBecauseThereIsNoStoredColumn() {
        val performance = UserPerformance(answeredQuestions = 120, correctAnswers = 87)

        assertEquals(33, performance.failedAnswers)
    }

    @Test
    fun derivedFailuresNeverGoNegativeOnInconsistentData() {
        // Si las estadísticas quedasen desalineadas, un número negativo de fallos
        // en pantalla sería peor que un cero.
        val performance = UserPerformance(answeredQuestions = 10, correctAnswers = 12)

        assertEquals(0, performance.failedAnswers)
    }

    @Test
    fun competitiveStateCarriesTheEvolutionFreshnessCounter() {
        // La evolución sólo se recalcula por bloques; sin este contador no se
        // puede saber si el valor mostrado es reciente.
        val competitive = UserCompetitiveState(
            currentAverage = 7.4,
            evolution = 0.8,
            questionsSinceLastEvolution = 12,
            calculationWindow = 100,
        )

        assertEquals(12, competitive.questionsSinceLastEvolution)
        assertEquals(100, competitive.calculationWindow)
    }

    @Test
    fun weaknessesKeepTheWorstFirstOrderingGivenByTheQuery() {
        // El backend ordena por porcentaje ascendente; el modelo no reordena, así
        // que el recorte de la consulta deja arriba lo accionable.
        val detail = UserDetail(
            profile = profile,
            weaknesses = listOf(
                UserTagWeakness(tagId = "t-1", tag = "Farmacología", successRate = 31.0),
                UserTagWeakness(tagId = "t-2", tag = "Legislación", successRate = 58.0),
            ),
        )

        assertEquals(listOf("Farmacología", "Legislación"), detail.weaknesses.map { it.tag })
    }
}
