package com.oposicontrol.admin.features.store.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.MarketPopupRule
import com.oposicontrol.admin.domain.model.MarketPromotion
import com.oposicontrol.admin.domain.model.MarketService
import com.oposicontrol.admin.domain.model.MarketTicketPack
import com.oposicontrol.admin.domain.repository.MarketCatalogRepository
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MarketViewModelExamsTest {

    private class NoopAnalytics : AnalyticsTracker {
        override fun trackScreen(screenName: String) = Unit
        override fun trackAction(actionName: String, metadata: Map<String, String>) = Unit
    }

    private class EmptyMarket : MarketCatalogRepository {
        override suspend fun getTicketPacks() = AppResult.Success(emptyList<MarketTicketPack>())
        override suspend fun saveTicketPack(pack: MarketTicketPack) = AppResult.Success(pack)
        override suspend fun getServices() = AppResult.Success(emptyList<MarketService>())
        override suspend fun saveService(service: MarketService) = AppResult.Success(service)
        override suspend fun getPromotions() = AppResult.Success(emptyList<MarketPromotion>())
        override suspend fun savePromotion(promotion: MarketPromotion) = AppResult.Success(promotion)
        override suspend fun getPopupRules() = AppResult.Success(emptyList<MarketPopupRule>())
        override suspend fun savePopupRule(rule: MarketPopupRule) = AppResult.Success(rule)
    }

    private fun catalog(vararg exams: Exam) =
        AppResult.Success(PublishedCatalog(exams = exams.toList(), communities = emptyList()))

    private fun TestScope.viewModel(source: PublishedExamsSource) = MarketViewModel(
        dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        repository = EmptyMarket(),
        examsSource = source,
        analyticsTracker = NoopAnalytics(),
    )

    @Test
    fun `al abrir carga los examenes y deja solo los publicados`() = runTest {
        val model = viewModel {
            catalog(exam("pub"), exam("draft", status = ExamStatus.DRAFT))
        }
        testScheduler.advanceUntilIdle()

        val state = model.state.value
        assertEquals(MarketSection.EXAMS, state.section)
        val loaded = assertIs<UiAsync.Success<List<Exam>>>(state.exams)
        assertEquals(listOf("pub"), loaded.value.map { it.id })
    }

    @Test
    fun `sin ningun publicado es Empty y no una lista vacia con exito`() = runTest {
        val model = viewModel { catalog(exam("draft", status = ExamStatus.DRAFT)) }
        testScheduler.advanceUntilIdle()

        assertEquals(UiAsync.Empty, model.state.value.exams)
    }

    @Test
    fun `un fallo es un error y NO un escaparate vacio`() = runTest {
        val model = viewModel { AppResult.Failure(AppError.Network("sin red")) }
        testScheduler.advanceUntilIdle()

        assertIs<UiAsync.Error>(model.state.value.exams)
    }

    @Test
    fun `filtro y busqueda se guardan en el estado sin volver a pedir los examenes`() = runTest {
        var calls = 0
        val model = viewModel {
            calls++
            catalog(exam("a"))
        }
        testScheduler.advanceUntilIdle()

        model.dispatch(MarketIntent.SetExamTypeFilter(ExamTypeFilter.RAFAGA))
        model.dispatch(MarketIntent.SetExamQuery("abc"))
        testScheduler.advanceUntilIdle()

        assertEquals(ExamTypeFilter.RAFAGA, model.state.value.examType)
        assertEquals("abc", model.state.value.examQuery)
        assertEquals(1, calls)
    }

    @Test
    fun `actualizar vuelve a pedir los examenes`() = runTest {
        var calls = 0
        val model = viewModel {
            calls++
            catalog(exam("a"))
        }
        testScheduler.advanceUntilIdle()

        model.dispatch(MarketIntent.Refresh)
        testScheduler.advanceUntilIdle()

        assertEquals(2, calls)
    }
}
