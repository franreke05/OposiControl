package com.oposicontrol.admin.features.analytics.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.AnalyticsMetric
import com.oposicontrol.admin.domain.model.AnalyticsMetricStatus
import com.oposicontrol.admin.domain.model.AnalyticsSection
import com.oposicontrol.admin.domain.model.AnalyticsSectionReport
import com.oposicontrol.admin.domain.model.AnalyticsValue
import com.oposicontrol.admin.domain.repository.ProductAnalyticsRepository
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun report(
    section: AnalyticsSection,
    metrics: List<AnalyticsMetric> = listOf(
        AnalyticsMetric("totalUsers", AnalyticsValue.Count(3), AnalyticsMetricStatus.AVAILABLE),
    ),
) = AnalyticsSectionReport(
    section = section,
    metrics = metrics,
    tables = emptyList(),
    generatedAt = "2026-09-02T00:00:00Z",
)

private class FakeProductAnalyticsRepository(
    private val failing: Set<AnalyticsSection> = emptySet(),
    private val responder: (AnalyticsSection) -> AnalyticsSectionReport = ::report,
) : ProductAnalyticsRepository {
    val requested = mutableListOf<AnalyticsSection>()

    override suspend fun getSection(section: AnalyticsSection): AppResult<AnalyticsSectionReport> {
        requested += section
        return if (section in failing) {
            AppResult.Failure(AppError.Network("upstream caido"))
        } else {
            AppResult.Success(responder(section))
        }
    }
}

class ProductAnalyticsViewModelTest {

    private fun newViewModel(
        scheduler: TestCoroutineScheduler,
        repository: ProductAnalyticsRepository,
    ) = ProductAnalyticsViewModel(
        dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(scheduler)),
        repository = repository,
        analyticsTracker = NoOpAnalyticsTracker,
    )

    @Test
    fun openingThePanelLoadsOnlyTheFirstSection() = runTest {
        // Cada seccion son entre diez y veinte conteos exactos en el backend:
        // cargar las seis al abrir gastaria seis rondas para enseñar una.
        val repository = FakeProductAnalyticsRepository()
        val viewModel = newViewModel(testScheduler, repository)

        testScheduler.advanceUntilIdle()

        assertEquals(listOf(AnalyticsSection.PRODUCTO), repository.requested)
        assertIs<UiAsync.Success<AnalyticsSectionReport>>(viewModel.state.value.current)
    }

    @Test
    fun changingSectionLoadsItOnceAndComingBackDoesNotRefetch() = runTest {
        val repository = FakeProductAnalyticsRepository()
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(ProductAnalyticsIntent.SelectSection(AnalyticsSection.ECONOMIA))
        testScheduler.advanceUntilIdle()
        viewModel.dispatch(ProductAnalyticsIntent.SelectSection(AnalyticsSection.PRODUCTO))
        testScheduler.advanceUntilIdle()
        viewModel.dispatch(ProductAnalyticsIntent.SelectSection(AnalyticsSection.ECONOMIA))
        testScheduler.advanceUntilIdle()

        assertEquals(
            listOf(AnalyticsSection.PRODUCTO, AnalyticsSection.ECONOMIA),
            repository.requested,
            "Volver a una pestaña ya cargada no puede costar otra ronda de conteos.",
        )
    }

    @Test
    fun refreshAlwaysRefetchesTheVisibleSection() = runTest {
        val repository = FakeProductAnalyticsRepository()
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(ProductAnalyticsIntent.Refresh)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(AnalyticsSection.PRODUCTO, AnalyticsSection.PRODUCTO), repository.requested)
    }

    @Test
    fun aFailingSectionDoesNotDiscardTheSectionsThatDidLoad() = runTest {
        // Un fallo en economia no puede dejar en blanco lo que ya se leyo de
        // producto: son informes independientes.
        val repository = FakeProductAnalyticsRepository(failing = setOf(AnalyticsSection.ECONOMIA))
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(ProductAnalyticsIntent.SelectSection(AnalyticsSection.ECONOMIA))
        testScheduler.advanceUntilIdle()

        assertIs<UiAsync.Error>(viewModel.state.value.current)
        assertIs<UiAsync.Success<AnalyticsSectionReport>>(
            viewModel.state.value.reports.getValue(AnalyticsSection.PRODUCTO),
        )
    }

    @Test
    fun aFailedSectionIsRetriedOnTheNextVisitInsteadOfStayingBroken() = runTest {
        val repository = FakeProductAnalyticsRepository(failing = setOf(AnalyticsSection.ECONOMIA))
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(ProductAnalyticsIntent.SelectSection(AnalyticsSection.ECONOMIA))
        testScheduler.advanceUntilIdle()
        viewModel.dispatch(ProductAnalyticsIntent.SelectSection(AnalyticsSection.PRODUCTO))
        testScheduler.advanceUntilIdle()
        viewModel.dispatch(ProductAnalyticsIntent.SelectSection(AnalyticsSection.ECONOMIA))
        testScheduler.advanceUntilIdle()

        assertEquals(
            2,
            repository.requested.count { it == AnalyticsSection.ECONOMIA },
            "Una seccion que fallo no queda cacheada como fallo: se reintenta al volver.",
        )
    }

    @Test
    fun aReportWithoutASingleReadableMetricIsStillShownAndNotHiddenAsEmpty() = runTest {
        // "Sin resultados" esconderia lo unico que hay que ver: que nada se pudo
        // leer y que agregados faltan por construir.
        val repository = FakeProductAnalyticsRepository(
            responder = {
                report(
                    section = it,
                    metrics = listOf(
                        AnalyticsMetric("totalUsers", null, AnalyticsMetricStatus.NO_DATA),
                        AnalyticsMetric("avgScore", null, AnalyticsMetricStatus.NOT_INSTRUMENTED, "Necesita AVG."),
                    ),
                )
            },
        )
        val viewModel = newViewModel(testScheduler, repository)

        testScheduler.advanceUntilIdle()

        val current = assertIs<UiAsync.Success<AnalyticsSectionReport>>(viewModel.state.value.current)
        assertEquals(0, current.value.availableCount)
        assertEquals(1, current.value.notInstrumentedCount)
    }

    // -----------------------------------------------------------------
    // Presentacion de una metrica
    // -----------------------------------------------------------------

    @Test
    fun aRealZeroIsRenderedAsZeroAndNotAsNoData() {
        val metric = AnalyticsMetric("guestUsers", AnalyticsValue.Count(0), AnalyticsMetricStatus.AVAILABLE)

        assertEquals("0", metric.displayValue())
        assertEquals("dato real", metric.statusLabel())
    }

    @Test
    fun theTwoKindsOfMissingMetricAreRenderedDifferently() {
        val unreadable = AnalyticsMetric("totalUsers", null, AnalyticsMetricStatus.NO_DATA)
        val missingAggregate =
            AnalyticsMetric("avgScore", null, AnalyticsMetricStatus.NOT_INSTRUMENTED, "Necesita AVG.")

        assertEquals(NO_DATA_LABEL, unreadable.displayValue())
        assertEquals(NOT_INSTRUMENTED_LABEL, missingAggregate.displayValue())
        assertTrue(
            unreadable.displayValue() != missingAggregate.displayValue(),
            "Reintentar sirve en un caso y no en el otro: la pantalla no puede igualarlos.",
        )
        assertTrue(unreadable.statusLabel() != missingAggregate.statusLabel())
    }

    @Test
    fun moneyIsRenderedFromWholeCents() {
        val metric = AnalyticsMetric(
            key = "grossRevenueCents",
            value = AnalyticsValue.MoneyCents(9_999L),
            status = AnalyticsMetricStatus.AVAILABLE,
        )

        assertEquals("99,99 EUR", metric.displayValue())
    }

    @Test
    fun anUnknownMetricKeyFallsBackToTheKeyInsteadOfDisappearing() {
        // Si el backend añade una metrica y aqui falta la traduccion, la tarjeta
        // aparece con su clave tecnica; desaparecer seria peor.
        assertEquals("unaMetricaNueva", analyticsMetricLabel("unaMetricaNueva"))
        assertEquals("Usuarios registrados", analyticsMetricLabel("totalUsers"))
    }

    @Test
    fun everySectionHasItsOwnLabelAndDescription() {
        val labels = AnalyticsSection.entries.map { it.label() }
        val descriptions = AnalyticsSection.entries.map { it.description() }

        assertEquals(6, labels.size)
        assertEquals(labels.size, labels.toSet().size, "Dos secciones con el mismo rotulo son indistinguibles.")
        assertTrue(descriptions.all { it.isNotBlank() })
    }
}
