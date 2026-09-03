package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.domain.model.DashboardMetricKey
import com.oposicontrol.admin.domain.model.DashboardMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.Instant

/**
 * Honestidad de los datos de Home.
 *
 * El defecto era que cada fuente caída acababa en pantalla como un cero limpio.
 * En logcat se vio que `articulo_tienda` y `/content/noticias` fallaban de
 * verdad —`JsonConvertException` en ambos— mientras Home mostraba ceros
 * impecables para comercio y noticias. Un cero medido y un cero por avería se
 * leían exactamente igual.
 */
class DashboardHonestDataTest {

    private fun todoBien() = unavailableDashboardMetrics(
        resourceAuditFailed = false,
        commerceFailed = false,
        newsFailed = false,
        performanceMissing = false,
        forumReportsFailed = false,
    )

    @Test
    fun `si todas las fuentes responden no se marca nada`() {
        // Un cero aquí sí es un cero de verdad y debe poder mostrarse como tal.
        assertEquals(emptySet(), todoBien())
    }

    @Test
    fun `el catalogo comercial caido marca comercio y nada mas`() {
        val resultado = unavailableDashboardMetrics(
            resourceAuditFailed = false,
            commerceFailed = true,
            newsFailed = false,
            performanceMissing = false,
            forumReportsFailed = false,
        )
        assertEquals(setOf(DashboardMetricKey.COMMERCE), resultado)
    }

    @Test
    fun `las noticias caidas no arrastran al resto de Home`() {
        val resultado = unavailableDashboardMetrics(
            resourceAuditFailed = false,
            commerceFailed = false,
            newsFailed = true,
            performanceMissing = false,
            forumReportsFailed = false,
        )
        assertEquals(setOf(DashboardMetricKey.NEWS), resultado)
    }

    @Test
    fun `las cuatro metricas de rendimiento van juntas`() {
        // Las calcula el backend en una sola respuesta: o están las cuatro o no
        // está ninguna. Marcar sólo una daría una imagen falsa de cobertura.
        val resultado = unavailableDashboardMetrics(
            resourceAuditFailed = false,
            commerceFailed = false,
            newsFailed = false,
            performanceMissing = true,
            forumReportsFailed = false,
        )
        assertEquals(
            setOf(
                DashboardMetricKey.TOTAL_ATTEMPTS,
                DashboardMetricKey.ACTIVE_USERS_30D,
                DashboardMetricKey.AVG_SCORE,
                DashboardMetricKey.GLOBAL_ERROR_RATE,
            ),
            resultado,
        )
    }

    @Test
    fun `varias fuentes caidas se acumulan sin pisarse`() {
        // El caso real observado: comercio y noticias caídos a la vez.
        val resultado = unavailableDashboardMetrics(
            resourceAuditFailed = true,
            commerceFailed = true,
            newsFailed = true,
            performanceMissing = true,
            forumReportsFailed = true,
        )
        assertEquals(8, resultado.size)
        assertTrue(DashboardMetricKey.COMMERCE in resultado)
        assertTrue(DashboardMetricKey.NEWS in resultado)
        assertTrue(DashboardMetricKey.RESOURCE_AUDIT in resultado)
        assertTrue(DashboardMetricKey.FORUM_REPORTS in resultado)
    }

    @Test
    fun `el modelo distingue no disponible de cero`() {
        // La otra mitad del contrato: la UI pregunta por `isAvailable`, no por el
        // número, y el número que acompaña a una métrica no disponible es relleno
        // de serialización, no un dato.
        val metrics = DashboardMetrics(
            totalSpecialties = 3,
            totalExams = 2,
            publishedExams = 2,
            totalQuestions = 40,
            lastUpdatedAt = Instant.parse("2026-09-03T10:00:00Z"),
            publishedNews = 0,
            unavailableMetrics = setOf(DashboardMetricKey.NEWS),
        )
        assertFalse(metrics.isAvailable(DashboardMetricKey.NEWS))
        assertTrue(metrics.isAvailable(DashboardMetricKey.TOTAL_EXAMS))
    }

    @Test
    fun `las claves de fuente del cliente no chocan con las del backend`() {
        val backend = setOf(
            DashboardMetricKey.TOTAL_SPECIALTIES, DashboardMetricKey.TOTAL_EXAMS,
            DashboardMetricKey.PUBLISHED_EXAMS, DashboardMetricKey.DRAFT_EXAMS,
            DashboardMetricKey.TOTAL_QUESTIONS, DashboardMetricKey.TOTAL_ATTEMPTS,
            DashboardMetricKey.ACTIVE_USERS_30D, DashboardMetricKey.AVG_SCORE,
            DashboardMetricKey.GLOBAL_ERROR_RATE, DashboardMetricKey.ATTEMPTS_PER_DAY,
        )
        val cliente = setOf(
            DashboardMetricKey.COMMERCE, DashboardMetricKey.NEWS,
            DashboardMetricKey.FORUM_REPORTS, DashboardMetricKey.RESOURCE_AUDIT,
        )
        assertEquals(
            emptySet(),
            backend intersect cliente,
            "Una clave repetida haría que una fuente marcase por error a otra",
        )
    }
}
