package com.oposicontrol.admin.domain.usecase.audit

import com.oposicontrol.admin.domain.repository.AuditRepository

class ObserveAuditEventsUseCase(
    private val repository: AuditRepository,
) {
    operator fun invoke() = repository.observeEvents()
}
