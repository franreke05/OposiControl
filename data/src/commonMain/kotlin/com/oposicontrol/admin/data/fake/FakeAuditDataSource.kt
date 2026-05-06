package com.oposicontrol.admin.data.fake

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.data.remote.source.AuditDataSource
import com.oposicontrol.admin.domain.model.AuditEvent
import kotlinx.coroutines.flow.Flow

class FakeAuditDataSource(
    private val store: FakeAdminStore,
    private val behavior: FakeBehaviorPolicy,
) : AuditDataSource {
    override fun observeEvents(): Flow<List<AuditEvent>> = store.auditEvents

    override suspend fun appendEvent(event: AuditEvent): AppResult<Unit> {
        return behavior.execute("audit.append") {
            store.appendAuditEvent(event)
            Unit
        }
    }
}
