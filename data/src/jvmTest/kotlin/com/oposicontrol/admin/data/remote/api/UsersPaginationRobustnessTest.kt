package com.oposicontrol.admin.data.remote.api

import com.oposicontrol.admin.core.network.AdminApiJson
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** MASTER2-OC-002 (paginacion robusta) y MASTER2-OC-004 (sin cartera fabricada). */
class UsersPaginationRobustnessTest {
    private fun id(n: Int) = "00000000-0000-4000-8000-${n.toString().padStart(12, '0')}"
    private fun dto(n: Int, withId: Boolean = true) =
        AdminApiJson.decodeFromString<AdminUserV1Dto>("""{"id":"${if (withId) id(n) else ""}","roleIds":[],"accountStatus":"activa"}""")

    @Test fun `offset advances by raw server rows so dropped rows do not shift pages`() = runTest {
        val offsets = mutableListOf<Int>()
        val users = collectAllUsers { _, offset ->
            offsets += offset
            when (offset) {
                // 200 filas crudas, 5 sin id (se descartan al mapear): 195 mapeadas < 200 no debe cortar el bucle
                0 -> AdminUsersV1Response(items = (0 until 200).map { dto(it, withId = it >= 5) }, total = 250, limit = 200, offset = 0)
                else -> AdminUsersV1Response(items = (200 until 250).map { dto(it) }, total = 250, limit = 200, offset = 200)
            }
        }
        assertEquals(listOf(0, 200), offsets)
        assertEquals(195 + 50, users.size)
    }

    @Test fun `overlapping pages are deduplicated by id`() = runTest {
        val users = collectAllUsers { _, offset ->
            if (offset == 0) AdminUsersV1Response(items = (0 until 200).map { dto(it) }, total = null, limit = 200)
            else AdminUsersV1Response(items = (190 until 205).map { dto(it) }, total = null, limit = 200)
        }
        assertEquals(205, users.size)
        assertEquals(users.size, users.map { it.id }.toSet().size)
    }

    @Test fun `hitting the offset cap fails loudly instead of truncating silently`() = runTest {
        assertFailsWith<IllegalStateException> {
            collectAllUsers { _, offset -> AdminUsersV1Response(items = (offset until offset + 200).map { dto(it) }, total = null, limit = 200) }
        }
    }

    @Test fun `missing ticketsBalance maps to null`() {
        val u = AdminApiJson.decodeFromString<AdminUsersV1Response>("""{"items":[{"id":"${id(1)}"}]}""").toUserDtos().single()
        assertNull(u.ticketsBalance)
    }
}
