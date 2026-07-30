package com.oposicontrol.admin.features.audit.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.AuditEvent
import com.oposicontrol.admin.domain.repository.AuditRepository
import com.oposicontrol.admin.domain.usecase.audit.ObserveAuditEventsUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class AuditViewModelTest {
    @Test
    fun `refresh recollects a cold audit flow`() = runTest {
        val repository = RefreshingAuditRepository()
        val viewModel = AuditViewModel(
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            observeAuditEventsUseCase = ObserveAuditEventsUseCase(repository),
            analyticsTracker = NoOpAnalyticsTracker,
        )
        advanceUntilIdle()

        repository.currentEvents = listOf(event(id = "audit-2"))
        viewModel.dispatch(AuditIntent.Refresh)
        advanceUntilIdle()

        val events = assertIs<UiAsync.Success<List<AuditEvent>>>(viewModel.state.value.events).value
        assertEquals(2, repository.collectionCount)
        assertEquals("audit-2", events.single().id)
        viewModel.clear()
    }
}

private class RefreshingAuditRepository : AuditRepository {
    var currentEvents: List<AuditEvent> = listOf(event(id = "audit-1"))
    var collectionCount = 0
        private set

    override fun observeEvents(): Flow<List<AuditEvent>> = flow {
        collectionCount += 1
        emit(currentEvents)
    }

    override suspend fun appendEvent(event: AuditEvent): AppResult<Unit> = AppResult.Success(Unit)
}

private fun event(id: String): AuditEvent {
    return AuditEvent(
        id = id,
        actorName = "Admin",
        action = "UPDATE",
        target = "settings",
        occurredAt = Instant.parse("2026-07-29T08:00:00Z"),
        metadata = emptyMap(),
    )
}
