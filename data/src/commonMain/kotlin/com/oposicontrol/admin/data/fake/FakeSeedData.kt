package com.oposicontrol.admin.data.fake

import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.domain.model.AdminSettings
import com.oposicontrol.admin.domain.model.AuditEvent
import com.oposicontrol.admin.domain.model.Permission
import com.oposicontrol.admin.domain.model.Role
import com.oposicontrol.admin.domain.model.User
import kotlinx.datetime.Instant

object FakeSeedData {
    val roles: List<Role> = listOf(
        Role(
            id = "super-admin",
            name = "Super Admin",
            permissions = listOf(
                Permission("users.read", "Leer usuarios"),
                Permission("users.write", "Editar usuarios"),
                Permission("roles.manage", "Gestionar roles"),
                Permission("audit.read", "Leer auditoria"),
            ),
        ),
        Role(
            id = "support",
            name = "Support",
            permissions = listOf(
                Permission("users.read", "Leer usuarios"),
                Permission("audit.read", "Leer auditoria"),
            ),
        ),
        Role(
            id = "auditor",
            name = "Auditor",
            permissions = listOf(
                Permission("audit.read", "Leer auditoria"),
            ),
        ),
    )

    val users: List<User> = listOf(
        User(
            id = "usr-001",
            fullName = "Alicia Gomez",
            email = "alicia@oposicontrol.local",
            roleIds = listOf("super-admin"),
            isActive = true,
            createdAt = Instant.parse("2026-02-28T09:00:00Z"),
        ),
        User(
            id = "usr-002",
            fullName = "Bruno Ortega",
            email = "bruno@oposicontrol.local",
            roleIds = listOf("support"),
            isActive = true,
            createdAt = Instant.parse("2026-03-27T09:00:00Z"),
        ),
        User(
            id = "usr-003",
            fullName = "Carla Paredes",
            email = "carla@oposicontrol.local",
            roleIds = listOf("auditor"),
            isActive = false,
            createdAt = Instant.parse("2026-04-08T09:00:00Z"),
        ),
    )

    val settings: AdminSettings = AdminSettings(
        displayName = "Administrador Demo",
        email = "admin@oposicontrol.local",
        receiveReleaseNotes = true,
        defaultEnvironment = Environment.DEV,
        preferFakeData = true,
        enableVerboseLogs = true,
    )

    val auditEvents: List<AuditEvent> = listOf(
        AuditEvent(
            id = "aud-001",
            actorName = "Sistema",
            action = "SEED_BOOTSTRAP",
            target = "fake-store",
            occurredAt = Instant.parse("2026-04-14T07:00:00Z"),
            metadata = mapOf("mode" to "fake"),
        ),
        AuditEvent(
            id = "aud-002",
            actorName = "Alicia Gomez",
            action = "EXAM_PUBLISH",
            target = "exam-tcae-marzo",
            occurredAt = Instant.parse("2026-04-14T08:15:00Z"),
            metadata = mapOf("status" to "published"),
        ),
        AuditEvent(
            id = "aud-003",
            actorName = "Bruno Ortega",
            action = "SUPPORT_REPLY",
            target = "conv-1",
            occurredAt = Instant.parse("2026-04-14T09:40:00Z"),
            metadata = mapOf("channel" to "support"),
        ),
    )
}
