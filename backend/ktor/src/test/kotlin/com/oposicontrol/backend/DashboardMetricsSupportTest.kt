package com.oposicontrol.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DashboardMetricsSupportTest {

    @Test
    fun readsExactTotalFromASingleRowCountRequest() {
        // Forma real de la respuesta a `Range: 0-0` + `Prefer: count=exact`.
        assertEquals(1234, parseContentRangeTotal("0-0/1234"))
    }

    @Test
    fun readsZeroWhenTheTableIsEmpty() {
        // PostgREST usa `*` como rango cuando no hay filas, pero el total sigue
        // siendo exacto: un 0 legitimo, que no debe confundirse con "no disponible".
        assertEquals(0, parseContentRangeTotal("*/0"))
    }

    @Test
    fun readsTotalFromAFullPageRange() {
        assertEquals(500, parseContentRangeTotal("0-24/500"))
    }

    @Test
    fun returnsNullWhenTheCountWasNotRequested() {
        // Sin `Prefer: count=exact` PostgREST devuelve `*` como total. Tratar eso
        // como 0 es exactamente el bug que esta correccion elimina.
        assertNull(parseContentRangeTotal("0-24/*"))
    }

    @Test
    fun returnsNullWhenTheHeaderIsAbsentOrUnusable() {
        assertNull(parseContentRangeTotal(null))
        assertNull(parseContentRangeTotal(""))
        assertNull(parseContentRangeTotal("   "))
        assertNull(parseContentRangeTotal("no-slash-here"))
        assertNull(parseContentRangeTotal("0-0/not-a-number"))
    }

    @Test
    fun returnsNullForANegativeTotal() {
        assertNull(parseContentRangeTotal("0-0/-3"))
    }

    @Test
    fun metricKeysAreStableAndMatchTheWireFieldNames() {
        // Las claves viajan al cliente para marcar una tarjeta como NO_DATA, asi
        // que deben coincidir con el nombre del campo del DTO. Si alguien renombra
        // un campo del dashboard sin actualizar su clave, la UI marcaria la tarjeta
        // equivocada y este test lo detiene.
        assertEquals("totalSpecialties", DashboardMetricKey.TOTAL_SPECIALTIES)
        assertEquals("totalExams", DashboardMetricKey.TOTAL_EXAMS)
        assertEquals("publishedExams", DashboardMetricKey.PUBLISHED_EXAMS)
        assertEquals("draftExams", DashboardMetricKey.DRAFT_EXAMS)
        assertEquals("totalQuestions", DashboardMetricKey.TOTAL_QUESTIONS)
        assertEquals("totalAttempts", DashboardMetricKey.TOTAL_ATTEMPTS)
        assertEquals("activeUsers30d", DashboardMetricKey.ACTIVE_USERS_30D)
        assertEquals("avgScore", DashboardMetricKey.AVG_SCORE)
        assertEquals("globalErrorRate", DashboardMetricKey.GLOBAL_ERROR_RATE)
        assertEquals("attemptsPerDay", DashboardMetricKey.ATTEMPTS_PER_DAY)
    }
}
