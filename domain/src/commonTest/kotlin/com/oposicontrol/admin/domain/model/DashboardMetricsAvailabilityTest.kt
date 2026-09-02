package com.oposicontrol.admin.domain.model

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * El dashboard admin transporta un número por cada métrica aunque el backend no
 * haya podido calcularla (el DTO no es nullable), así que la señal de "esto no es
 * un dato" viaja aparte, en `unavailableMetrics`. Estos tests fijan esa regla:
 * sin ella, un fallo de lectura vuelve a pintarse como un `0` creíble.
 */
class DashboardMetricsAvailabilityTest {

    private fun metrics(
        unavailable: Set<String> = emptySet(),
        avgScore: Double = 0.0,
        totalExams: Int = 0,
    ) = DashboardMetrics(
        totalSpecialties = 0,
        totalExams = totalExams,
        publishedExams = 0,
        totalQuestions = 0,
        lastUpdatedAt = Instant.fromEpochMilliseconds(0),
        avgScore = avgScore,
        unavailableMetrics = unavailable,
    )

    @Test
    fun everyMetricIsAvailableWhenTheBackendReportsNoGaps() {
        val subject = metrics()

        assertTrue(subject.isAvailable(DashboardMetricKey.AVG_SCORE))
        assertTrue(subject.isAvailable(DashboardMetricKey.TOTAL_EXAMS))
        assertTrue(subject.isAvailable(DashboardMetricKey.ACTIVE_USERS_30D))
    }

    @Test
    fun aMetricListedAsUnavailableIsNotAvailableEvenThoughItCarriesAValue() {
        // avgScore llega como 0.0 de relleno: sin la lista, seria imposible
        // distinguirlo de "la media real es 0".
        val subject = metrics(unavailable = setOf(DashboardMetricKey.AVG_SCORE), avgScore = 0.0)

        assertFalse(subject.isAvailable(DashboardMetricKey.AVG_SCORE))
    }

    @Test
    fun markingOneMetricDoesNotAffectTheOthers() {
        val subject = metrics(unavailable = setOf(DashboardMetricKey.AVG_SCORE), totalExams = 12)

        assertFalse(subject.isAvailable(DashboardMetricKey.AVG_SCORE))
        assertTrue(subject.isAvailable(DashboardMetricKey.TOTAL_EXAMS))
        assertTrue(subject.isAvailable(DashboardMetricKey.GLOBAL_ERROR_RATE))
    }

    @Test
    fun aRealZeroStaysAvailableAndIsNotConfusedWithAGap() {
        // Cero legitimo: hay cero examenes y el backend pudo comprobarlo.
        val subject = metrics(totalExams = 0)

        assertTrue(subject.isAvailable(DashboardMetricKey.TOTAL_EXAMS))
    }

    @Test
    fun defaultMetricsAssumeEverythingIsReal() {
        // Compatibilidad: un backend anterior no envia el campo, y en ese caso lo
        // recibido se sigue tratando como dato real (no como un panel en blanco).
        assertTrue(metrics().unavailableMetrics.isEmpty())
    }
}
