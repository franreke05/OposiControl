package com.oposicontrol.admin.features.audit.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.AuditEvent

data class AuditState(
    val events: UiAsync<List<AuditEvent>> = UiAsync.Loading,
)
