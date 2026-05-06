package com.oposicontrol.admin.data.fake

import com.oposicontrol.admin.domain.model.AdminSettings
import com.oposicontrol.admin.domain.model.AuditEvent
import com.oposicontrol.admin.domain.model.Role
import com.oposicontrol.admin.domain.model.Session
import com.oposicontrol.admin.domain.model.User
import kotlinx.coroutines.flow.MutableStateFlow

class FakeAdminStore {
    val session = MutableStateFlow<Session?>(null)
    val users = MutableStateFlow(FakeSeedData.users)
    val roles = MutableStateFlow(FakeSeedData.roles)
    val settings = MutableStateFlow(FakeSeedData.settings)
    val auditEvents = MutableStateFlow(FakeSeedData.auditEvents)

    fun replaceUsers(updated: List<User>) {
        users.value = updated
    }

    fun replaceRoles(updated: List<Role>) {
        roles.value = updated
    }

    fun replaceSettings(updated: AdminSettings) {
        settings.value = updated
    }

    fun appendAuditEvent(event: AuditEvent) {
        auditEvents.value = listOf(event) + auditEvents.value
    }
}
