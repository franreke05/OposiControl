package com.oposicontrol.admin.data.mapper

import com.oposicontrol.admin.data.remote.dto.AuditEventDto
import com.oposicontrol.admin.data.remote.dto.UserDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminMapperTest {
    @Test
    fun `blank user timestamp falls back without losing roles or active state`() {
        val user = UserDto(
            id = "user-1",
            fullName = "Admin",
            email = "admin@oposicontrol.local",
            roleIds = listOf("auditor", "support"),
            isActive = false,
            createdAt = "",
        ).toDomain()

        assertTrue(user.createdAt.epochSeconds > 0L)
        assertEquals(listOf("auditor", "support"), user.roleIds)
        assertFalse(user.isActive)
    }

    @Test
    fun `blank audit timestamp falls back to a valid instant`() {
        val event = AuditEventDto(
            id = "audit-1",
            actorName = "Admin",
            action = "UPDATE",
            target = "user-1",
            occurredAt = "",
        ).toDomain()

        assertTrue(event.occurredAt.epochSeconds > 0L)
    }
}
