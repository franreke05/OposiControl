package com.oposicontrol.admin.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * La regla de dominio que impide repetir el fallo del dashboard: ninguna
 * pantalla puede enseñar un número que el backend declaró inválido.
 */
class ProductAnalyticsTest {

    @Test
    fun aValueThatTheBackendDidNotFlagIsAvailable() {
        val status = AnalyticsMetricPolicy.statusFor(
            key = "totalUsers",
            value = AnalyticsValue.Count(3),
            unavailable = emptySet(),
            notInstrumented = emptySet(),
        )

        assertEquals(AnalyticsMetricStatus.AVAILABLE, status)
    }

    @Test
    fun aRealZeroIsAvailableAndKeepsItsValue() {
        // Cero usuarios invitados es un dato: la tarjeta enseña "0", no NO_DATA.
        val metric = AnalyticsMetricPolicy.metric(
            key = "guestUsers",
            value = AnalyticsValue.Count(0),
            unavailable = emptySet(),
            notInstrumented = emptyMap(),
        )

        assertEquals(AnalyticsMetricStatus.AVAILABLE, metric.status)
        assertEquals(AnalyticsValue.Count(0), metric.value)
    }

    @Test
    fun aFlaggedKeyIsNoDataEvenWhenTheDtoStillCarriesAFillerNumber() {
        // El precedente `AdminDashboardDto` rellena con 0 los campos que no pudo
        // calcular y nombra la clave. Si la política se fiara del campo, la UI
        // volvería a enseñar el cero inventado. Manda la lista.
        val metric = AnalyticsMetricPolicy.metric(
            key = "totalExams",
            value = AnalyticsValue.Count(0),
            unavailable = setOf("totalExams"),
            notInstrumented = emptyMap(),
        )

        assertEquals(AnalyticsMetricStatus.NO_DATA, metric.status)
        assertNull(metric.value, "Un valor declarado inválido no puede sobrevivir hasta la pantalla.")
    }

    /**
     * PC-04: una lectura FALLIDA es NO_DATA marcada como fallida, y su número —si lo
     * hubiera— se descarta aunque el backend no la haya puesto también en la lista
     * general. Una ausencia sin fallo no lleva la marca.
     */
    @Test
    fun aFailedReadIsNoDataFlaggedAsFailedAndNeverKeepsANumber() {
        val failed = AnalyticsMetricPolicy.metric(
            key = "activeUsers30d",
            value = AnalyticsValue.Count(0),
            unavailable = emptySet(),
            notInstrumented = emptyMap(),
            failed = setOf("activeUsers30d"),
        )
        val noData = AnalyticsMetricPolicy.metric(
            key = "globalErrorRate",
            value = null,
            unavailable = setOf("globalErrorRate"),
            notInstrumented = emptyMap(),
            failed = setOf("activeUsers30d"),
        )

        assertEquals(AnalyticsMetricStatus.NO_DATA, failed.status)
        assertNull(failed.value, "El 0 de una lectura fallida no es un cero.")
        assertTrue(failed.readFailed)
        assertEquals(AnalyticsMetricStatus.NO_DATA, noData.status)
        assertEquals(false, noData.readFailed, "«No hay nada que medir» no es «no se pudo leer».")
    }

    /** Sin la lista nueva (Analítica, o un servidor antiguo) nada cambia: nadie sale marcado. */
    @Test
    fun withoutFailedKeysNothingIsFlagged() {
        val metric = AnalyticsMetricPolicy.metric(
            key = "totalExams",
            value = null,
            unavailable = setOf("totalExams"),
            notInstrumented = emptyMap(),
        )

        assertEquals(AnalyticsMetricStatus.NO_DATA, metric.status)
        assertEquals(false, metric.readFailed)
    }

    @Test
    fun aNotInstrumentedKeyBeatsNoDataAndCarriesItsReason() {
        // La clave viene en las dos listas (notInstrumented es subconjunto de
        // unavailable). La UI debe decir "hay que construirlo", no "reintenta".
        val metric = AnalyticsMetricPolicy.metric(
            key = "grossRevenueCents",
            value = null,
            unavailable = setOf("grossRevenueCents"),
            notInstrumented = mapOf("grossRevenueCents" to "Necesita SUM(precio_centimos)."),
        )

        assertEquals(AnalyticsMetricStatus.NOT_INSTRUMENTED, metric.status)
        assertEquals("Necesita SUM(precio_centimos).", metric.reason)
        assertNull(metric.value)
    }

    @Test
    fun aMissingValueWithNoExplanationFailsClosedAsNoData() {
        // Si el backend olvidara declarar la clave, la tarjeta no puede quedarse
        // en blanco sin decir nada: se marca NO_DATA.
        val status = AnalyticsMetricPolicy.statusFor(
            key = "posts",
            value = null,
            unavailable = emptySet(),
            notInstrumented = emptySet(),
        )

        assertEquals(AnalyticsMetricStatus.NO_DATA, status)
    }

    @Test
    fun aReportSummarisesHowMuchOfItIsRealData() {
        val report = AnalyticsSectionReport(
            section = AnalyticsSection.ECONOMIA,
            metrics = listOf(
                AnalyticsMetric("wallets", AnalyticsValue.Count(2), AnalyticsMetricStatus.AVAILABLE),
                AnalyticsMetric("purchases", AnalyticsValue.Count(0), AnalyticsMetricStatus.AVAILABLE),
                AnalyticsMetric("subscriptions", null, AnalyticsMetricStatus.NO_DATA),
                AnalyticsMetric("grossRevenueCents", null, AnalyticsMetricStatus.NOT_INSTRUMENTED, "Necesita SUM."),
            ),
            tables = emptyList(),
            generatedAt = "2026-09-02T00:00:00Z",
        )

        assertEquals(2, report.availableCount)
        assertEquals(1, report.notInstrumentedCount)
        assertEquals(AnalyticsMetricStatus.NO_DATA, report.metric("subscriptions")?.status)
        assertNull(report.metric("noSuchMetric"))
    }

    // -----------------------------------------------------------------
    // Formato
    // -----------------------------------------------------------------

    @Test
    fun moneyIsFormattedFromWholeCentsWithoutFloatingPointDrift() {
        // 9999 céntimos son 99,99 €. Es el caso que un Double redondea mal.
        assertEquals("99,99 EUR", AnalyticsValue.MoneyCents(9_999L).format())
        assertEquals("0,05 EUR", AnalyticsValue.MoneyCents(5L).format())
        assertEquals("-1,50 EUR", AnalyticsValue.MoneyCents(-150L).format())
    }

    @Test
    fun moneyBeyondIntRangeStillFormatsAsWholeCents() {
        // Un ingreso acumulado supera los 21 millones de euros en céntimos, que
        // ya no caben en Int; el formato no puede degradarse a coma flotante.
        assertEquals("30000000,07 EUR", AnalyticsValue.MoneyCents(3_000_000_007L).format())
    }

    @Test
    fun percentagesKeepOneDecimal() {
        assertEquals("25,0 %", AnalyticsValue.Percentage(25.0).format())
        assertEquals("33,3 %", AnalyticsValue.Percentage(33.333).format())
        assertEquals("0,0 %", AnalyticsValue.Percentage(0.0).format())
    }

    @Test
    fun countsAreRenderedAsPlainIntegers() {
        assertEquals("0", AnalyticsValue.Count(0).format())
        assertEquals("1234", AnalyticsValue.Count(1234).format())
    }

    @Test
    fun everySectionOfThePanelIsModelled() {
        // El panel promete seis secciones en una pantalla; si alguien añade una
        // séptima al backend sin modelarla aquí, la UI la perdería en silencio.
        assertEquals(6, AnalyticsSection.entries.size)
        assertTrue(AnalyticsSection.PRODUCTO in AnalyticsSection.entries)
        assertTrue(AnalyticsSection.MARKETING in AnalyticsSection.entries)
    }
}
