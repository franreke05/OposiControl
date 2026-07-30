package com.oposicontrol.admin.features.dashboard.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.DashboardMetrics
import com.oposicontrol.admin.domain.repository.DashboardRepository
import com.oposicontrol.admin.domain.usecase.dashboard.GetDashboardMetricsUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {
    @Test
    fun `failed refresh preserves valid metrics`() = runTest {
        val failure = AppError.Network("Sin conexion")
        val repository = QueueDashboardRepository(
            AppResult.Success(metrics(totalExams = 4)),
            AppResult.Failure(failure),
        )
        val viewModel = DashboardViewModel(
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            getDashboardMetricsUseCase = GetDashboardMetricsUseCase(repository),
            analyticsTracker = NoOpAnalyticsTracker,
        )
        advanceUntilIdle()

        viewModel.dispatch(DashboardIntent.Refresh)
        advanceUntilIdle()

        val metrics = assertIs<UiAsync.Success<DashboardMetrics>>(viewModel.state.value.metrics)
        assertEquals(4, metrics.value.totalExams)
        assertEquals(failure, viewModel.state.value.refreshError)
    }

    @Test
    fun `latest refresh owns dashboard state`() = runTest {
        val delayedRefresh = CompletableDeferred<Unit>()
        val repository = DelayedDashboardRepository(delayedRefresh)
        val viewModel = DashboardViewModel(
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            getDashboardMetricsUseCase = GetDashboardMetricsUseCase(repository),
            analyticsTracker = NoOpAnalyticsTracker,
        )
        advanceUntilIdle()

        viewModel.dispatch(DashboardIntent.Refresh)
        runCurrent()
        viewModel.dispatch(DashboardIntent.Refresh)
        runCurrent()
        delayedRefresh.complete(Unit)
        advanceUntilIdle()

        val result = assertIs<UiAsync.Success<DashboardMetrics>>(viewModel.state.value.metrics)
        assertEquals(30, result.value.totalExams)
    }

    private class QueueDashboardRepository(
        vararg responses: AppResult<DashboardMetrics>,
    ) : DashboardRepository {
        private val queue = responses.toMutableList()

        override suspend fun getMetrics(): AppResult<DashboardMetrics> = queue.removeAt(0)
    }

    private class DelayedDashboardRepository(
        private val delayedRefresh: CompletableDeferred<Unit>,
    ) : DashboardRepository {
        private var calls = 0

        override suspend fun getMetrics(): AppResult<DashboardMetrics> {
            calls += 1
            return when (calls) {
                1 -> AppResult.Success(metrics(totalExams = 1))
                2 -> {
                    delayedRefresh.await()
                    AppResult.Success(metrics(totalExams = 20))
                }

                else -> AppResult.Success(metrics(totalExams = 30))
            }
        }
    }
}

private fun metrics(totalExams: Int) = DashboardMetrics(
    totalSpecialties = 2,
    totalExams = totalExams,
    publishedExams = 1,
    totalQuestions = 10,
    lastUpdatedAt = Instant.parse("2026-07-29T10:00:00Z"),
)
