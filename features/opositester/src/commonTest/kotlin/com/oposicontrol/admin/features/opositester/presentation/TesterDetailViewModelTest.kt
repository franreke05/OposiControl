package com.oposicontrol.admin.features.opositester.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.TesterReportDetail
import com.oposicontrol.admin.domain.repository.TesterRepository
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private fun detail(id: String, status: String = "pendiente") = TesterReportDetail(
    id = id,
    authorName = "Autor",
    type = "pregunta_incorrecta",
    description = "Fragmento $id",
    status = status,
    createdAt = "2026-08-23T10:00:00Z",
    question = null,
)

private class FakeTesterDetailRepository(
    private val getResponder: (id: String) -> AppResult<TesterReportDetail>,
    private val resolveResponder: (id: String, estado: String, nota: String?) -> AppResult<Unit> = { _, _, _ -> AppResult.Success(Unit) },
) : TesterRepository {
    val resolveCalls = mutableListOf<Triple<String, String, String?>>()

    override suspend fun listReports(status: String?, cursor: String?, type: String?, pageSize: Int) = error("not used")

    override suspend fun getReport(id: String): AppResult<TesterReportDetail> = getResponder(id)

    override suspend fun resolveReport(id: String, estado: String, nota: String?): AppResult<Unit> {
        resolveCalls += Triple(id, estado, nota)
        return resolveResponder(id, estado, nota)
    }
}

private fun newViewModel(scheduler: TestCoroutineScheduler, repository: TesterRepository, testerId: String = "t1") = TesterDetailViewModel(
    testerId = testerId,
    dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(scheduler)),
    testerRepository = repository,
    analyticsTracker = NoOpAnalyticsTracker,
)

class TesterDetailViewModelTest {
    @Test
    fun load_onInit_startsInLoading_thenPopulatesTheReport() = runTest {
        val repository = FakeTesterDetailRepository(getResponder = { id -> AppResult.Success(detail(id)) })
        val viewModel = newViewModel(testScheduler, repository, testerId = "t1")

        assertIs<UiAsync.Loading>(viewModel.state.value.report)
        testScheduler.advanceUntilIdle()

        val success = assertIs<UiAsync.Success<TesterReportDetail>>(viewModel.state.value.report)
        assertEquals("t1", success.value.id)
    }

    @Test
    fun load_onFailure_surfacesTheAppError() = runTest {
        val repository = FakeTesterDetailRepository(getResponder = { AppResult.Failure(AppError.Network("sin conexion")) })
        val viewModel = newViewModel(testScheduler, repository)

        testScheduler.advanceUntilIdle()

        assertIs<UiAsync.Error>(viewModel.state.value.report)
    }

    @Test
    fun load_onNotFound_surfacesNotFound_forTheNotFoundCopyInTheScreen() = runTest {
        val repository = FakeTesterDetailRepository(getResponder = { AppResult.Failure(AppError.NotFound("no existe")) })
        val viewModel = newViewModel(testScheduler, repository)

        testScheduler.advanceUntilIdle()

        val error = assertIs<UiAsync.Error>(viewModel.state.value.report)
        assertIs<AppError.NotFound>(error.error)
    }

    @Test
    fun retry_afterAFailure_canSucceed() = runTest {
        var shouldFail = true
        val repository = FakeTesterDetailRepository(getResponder = { id ->
            if (shouldFail) AppResult.Failure(AppError.Network("sin conexion")) else AppResult.Success(detail(id))
        })
        val viewModel = newViewModel(testScheduler, repository)
        testScheduler.advanceUntilIdle()
        assertIs<UiAsync.Error>(viewModel.state.value.report)

        shouldFail = false
        viewModel.dispatch(TesterDetailIntent.Load)
        testScheduler.advanceUntilIdle()

        assertIs<UiAsync.Success<TesterReportDetail>>(viewModel.state.value.report)
    }

    @Test
    fun resolve_sendsTheRealResuelto_status_andReloadsOnSuccess() = runTest {
        var current = "pendiente"
        val repository = FakeTesterDetailRepository(
            getResponder = { id -> AppResult.Success(detail(id, status = current)) },
            resolveResponder = { _, estado, _ -> current = estado; AppResult.Success(Unit) },
        )
        val viewModel = newViewModel(testScheduler, repository, testerId = "t1")
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(TesterDetailIntent.Resolve)
        testScheduler.advanceUntilIdle()

        assertEquals(Triple("t1", "resuelto", null), repository.resolveCalls.single())
        assertIs<UiAsync.Success<Unit>>(viewModel.state.value.actionState)
        val success = assertIs<UiAsync.Success<TesterReportDetail>>(viewModel.state.value.report)
        assertEquals("resuelto", success.value.status)
    }

    @Test
    fun discard_sendsTheRealDescartado_status() = runTest {
        val repository = FakeTesterDetailRepository(getResponder = { id -> AppResult.Success(detail(id)) })
        val viewModel = newViewModel(testScheduler, repository, testerId = "t1")
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(TesterDetailIntent.Discard)
        testScheduler.advanceUntilIdle()

        assertEquals(Triple("t1", "descartado", null), repository.resolveCalls.single())
    }

    @Test
    fun resolve_onFailure_surfacesTheErrorOnActionState_withoutLosingTheLoadedReport() = runTest {
        val repository = FakeTesterDetailRepository(
            getResponder = { id -> AppResult.Success(detail(id)) },
            resolveResponder = { _, _, _ -> AppResult.Failure(AppError.Authorization("sin permisos")) },
        )
        val viewModel = newViewModel(testScheduler, repository, testerId = "t1")
        testScheduler.advanceUntilIdle()

        viewModel.dispatch(TesterDetailIntent.Resolve)
        testScheduler.advanceUntilIdle()

        assertIs<UiAsync.Error>(viewModel.state.value.actionState)
        assertIs<UiAsync.Success<TesterReportDetail>>(viewModel.state.value.report)
    }
}
