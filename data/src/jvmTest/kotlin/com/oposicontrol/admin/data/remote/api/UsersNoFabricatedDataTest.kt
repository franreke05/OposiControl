package com.oposicontrol.admin.data.remote.api

import com.oposicontrol.admin.core.network.AdminApiJson
import com.oposicontrol.admin.data.mapper.toDomain
import com.oposicontrol.admin.data.remote.dto.UserDto
import com.oposicontrol.admin.domain.model.AccountStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** MASTER-OC-002 (isActive != accountStatus) y MASTER-OC-004 (sin ids ni fechas fabricados). */
class UsersNoFabricatedDataTest {
    private val json = AdminApiJson
    private val id = "11111111-1111-4111-8111-111111111111"
    private fun list(body: String) = json.decodeFromString<AdminUsersV1Response>(body).toUserDtos()

    @Test fun `active account status does not invent isActive when legacy is missing`() {
        val u = list("""{"items":[{"id":"$id","accountStatus":"activa","createdAt":"2026-01-01T00:00:00Z"}]}""").single()
        assertNull(u.isActive)
        assertNull(u.toDomain().isRegistered)
        assertEquals(AccountStatus.ACTIVE, u.toDomain().accountStatus)
    }

    @Test fun `blocked account status does not read as guest`() {
        val u = list("""{"items":[{"id":"$id","accountStatus":"bloqueada"}]}""").single().toDomain()
        assertNull(u.isRegistered)
        assertEquals(AccountStatus.BLOCKED, u.accountStatus)
    }

    @Test fun `legacy isActive keeps its meaning`() {
        val u = list("""{"data":[{"id":"$id","isActive":false}]}""").single().toDomain()
        assertEquals(false, u.isRegistered)
    }

    @Test fun `rows without id are dropped from the list`() {
        val rows = list("""{"items":[{"id":"","accountStatus":"activa"},{"id":"$id"}]}""")
        assertEquals(listOf(id), rows.map { it.id })
        assertEquals(emptyList(), list("""{"data":[{"isActive":true}]}"""))
    }

    @Test fun `detail without id fails instead of fabricating`() {
        assertFailsWith<IllegalStateException> {
            json.decodeFromString<AdminUserV1Dto>("""{"accountStatus":"activa"}""").toUserDto()
        }
    }

    @Test fun `missing createdAt stays null`() {
        val u = list("""{"items":[{"id":"$id"},{"id":"$id","createdAt":""}]}""")
        assertNull(u[0].createdAt)
        assertNull(u[1].createdAt)
        assertNull(u[0].toDomain().createdAt)
    }

    @Test fun `unparsable createdAt is null not DISTANT_PAST`() {
        val d = UserDto(id = id, fullName = "", email = "", roleIds = emptyList(), isActive = null, createdAt = "no-fecha")
        assertNull(d.toDomain().createdAt)
    }
}
