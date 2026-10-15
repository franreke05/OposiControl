package com.oposicontrol.admin.features.reports.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.ForumReport
import com.oposicontrol.admin.domain.model.ForumReportDetail
import com.oposicontrol.admin.domain.repository.ModerationRepository
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class FakeModerationRepository(
    private var detail: AppResult<ForumReportDetail> = AppResult.Success(
        ForumReportDetail(
            report = ForumReport(
                id = "r1",
                entityType = "hilo",
                entityId = "e1",
                reason = "Spam",
                description = "",
                status = "pendiente",
                createdAt = "2026-08-22T10:00:00Z",
                reporterId = "u1",
                reporterName = "Arturo",
            ),
            contentPreview = null,
        ),
    ),
) : ModerationRepository {
    var resolveResult: AppResult<Unit> = AppResult.Success(Unit)
    val resolveActionsReceived = mutableListOf<String>()
    val notesReceived = mutableListOf<String?>()
    val rewardsReceived = mutableListOf<Int>()

    fun setDetail(result: AppResult<ForumReportDetail>) {
        detail = result
    }

    override suspend fun listPendingReports(): AppResult<List<ForumReport>> = AppResult.Success(emptyList())
    override suspend fun listReports(includeResolved: Boolean): AppResult<List<ForumReport>> = AppResult.Success(emptyList())
    override suspend fun getReport(reportId: String): AppResult<ForumReportDetail> = detail

    override suspend fun resolveReport(reportId: String, action: String, notes: String?, ticketsReporter: Int): AppResult<Unit> {
        resolveActionsReceived += action
        notesReceived += notes
        rewardsReceived += ticketsReporter
        return resolveResult
    }

    override suspend fun grantTickets(
        userId: String,
        amount: Int,
        reason: String,
        idempotencyKey: String,
    ): AppResult<Int> = AppResult.Success(0)
    override suspend fun warnUser(userId: String, message: String): AppResult<Unit> = AppResult.Success(Unit)
}

class ReportDetailViewModelTest {

    @Test
    fun load_onInit_populatesReportOnSuccess() = runTest {
        val repository = FakeModerationRepository()
        val viewModel = ReportDetailViewModel(
            reportId = "r1",
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            repository = repository,
            analyticsTracker = NoOpAnalyticsTracker,
        )
        testScheduler.advanceUntilIdle()

        val report = assertIs<UiAsync.Success<ForumReportDetail>>(viewModel.state.value.report)
        assertEquals("r1", report.value.report.id)
    }

    @Test
    fun load_onFailure_surfacesTheAppError() = runTest {
        val repository = FakeModerationRepository(detail = AppResult.Failure(AppError.NotFound("no existe")))
        val viewModel = ReportDetailViewModel(
            reportId = "missing",
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            repository = repository,
            analyticsTracker = NoOpAnalyticsTracker,
        )
        testScheduler.advanceUntilIdle()

        assertIs<UiAsync.Error>(viewModel.state.value.report)
    }

    @Test
    fun resolve_sendsAReportStateActionOnly_neverAContentModerationAction() = runTest {
        val repository = FakeModerationRepository()
        val viewModel = ReportDetailViewModel(
            reportId = "r1",
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            repository = repository,
            analyticsTracker = NoOpAnalyticsTracker,
        )

        viewModel.dispatch(ReportDetailIntent.Resolve)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("resuelto_sin_accion"), repository.resolveActionsReceived)
        assertTrue(repository.resolveActionsReceived.none { it == "ocultar" })
        assertIs<UiAsync.Success<Unit>>(viewModel.state.value.actionState)
    }

    @Test
    fun discard_sendsTheDiscardAction_andReloadsTheReport() = runTest {
        val repository = FakeModerationRepository()
        val viewModel = ReportDetailViewModel(
            reportId = "r1",
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            repository = repository,
            analyticsTracker = NoOpAnalyticsTracker,
        )

        viewModel.dispatch(ReportDetailIntent.Discard)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("descartar"), repository.resolveActionsReceived)
        assertIs<UiAsync.Success<ForumReportDetail>>(viewModel.state.value.report)
    }

    @Test
    fun resolve_onFailure_keepsAnErrorStateInsteadOfSilentlySucceeding() = runTest {
        val repository = FakeModerationRepository().apply {
            resolveResult = AppResult.Failure(AppError.Authorization("sin permiso"))
        }
        val viewModel = ReportDetailViewModel(
            reportId = "r1",
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            repository = repository,
            analyticsTracker = NoOpAnalyticsTracker,
        )

        viewModel.dispatch(ReportDetailIntent.Resolve)
        testScheduler.advanceUntilIdle()

        assertIs<UiAsync.Error>(viewModel.state.value.actionState)
    }

    private fun kotlinx.coroutines.test.TestScope.vm(repository: FakeModerationRepository) = ReportDetailViewModel(
        reportId = "r1",
        dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        repository = repository,
        analyticsTracker = NoOpAnalyticsTracker,
    ).also { testScheduler.advanceUntilIdle() }

    private fun kotlinx.coroutines.test.TestScope.send(vm: ReportDetailViewModel, vararg intents: ReportDetailIntent) {
        intents.forEach { vm.dispatch(it); testScheduler.advanceUntilIdle() }
    }

    @Test
    fun resolve_sendsTheTypedNotesAndReward() = runTest {
        val repository = FakeModerationRepository()
        val viewModel = vm(repository)
        send(viewModel, ReportDetailIntent.UpdateNotes("  spam claro  "), ReportDetailIntent.UpdateReward("25"), ReportDetailIntent.Resolve)
        assertEquals(listOf<String?>("spam claro"), repository.notesReceived)
        assertEquals(listOf(25), repository.rewardsReceived)
        assertEquals("", viewModel.state.value.notes, "tras resolver el formulario se limpia")
    }

    @Test
    fun discard_neverSendsAReward() = runTest {
        val repository = FakeModerationRepository()
        val viewModel = vm(repository)
        send(viewModel, ReportDetailIntent.UpdateReward("25"), ReportDetailIntent.Discard)
        assertEquals(listOf(0), repository.rewardsReceived)
        assertEquals(listOf<String?>(null), repository.notesReceived)
    }

    @Test
    fun aRewardAboveTheServerLimit_blocksResolveWithoutCallingTheServer() = runTest {
        val repository = FakeModerationRepository()
        val viewModel = vm(repository)
        send(viewModel, ReportDetailIntent.UpdateReward("10001"), ReportDetailIntent.Resolve)
        assertTrue(repository.resolveActionsReceived.isEmpty())
        assertIs<AppError.Validation>((viewModel.state.value.actionState as UiAsync.Error).error)
    }

    @Test
    fun notesAboveTheServerLimit_blockBothActions() = runTest {
        val repository = FakeModerationRepository()
        val viewModel = vm(repository)
        send(viewModel, ReportDetailIntent.UpdateNotes("x".repeat(REPORT_NOTES_MAX + 5)), ReportDetailIntent.Discard)
        assertTrue(repository.resolveActionsReceived.isEmpty())
        assertEquals(REPORT_NOTES_MAX + 1, viewModel.state.value.notes.length, "se conserva lo justo para avisar del exceso")
    }

    @Test
    fun withoutAReporter_noRewardIsOfferedOrSent() = runTest {
        val repository = FakeModerationRepository()
        repository.setDetail(
            AppResult.Success(
                ForumReportDetail(
                    report = ForumReport("r1", "hilo", "e1", "Spam", "", "pendiente", "2026-08-22T10:00:00Z", reporterId = "", reporterName = ""),
                    contentPreview = null,
                ),
            ),
        )
        val viewModel = vm(repository)
        assertEquals(false, viewModel.state.value.rewardAvailable)
        send(viewModel, ReportDetailIntent.UpdateReward("25"), ReportDetailIntent.Resolve)
        assertEquals(listOf(0), repository.rewardsReceived)
    }
}
