package com.oposicontrol.admin.features.users.presentation

import com.oposicontrol.admin.domain.model.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** MASTER5-OC-001 (nombre de cuenta estable) y MASTER5-OC-006 (limites del contrato antes de escribir). */
class UserAccountNameAndSaveLimitsTest {
    private fun user(username: String?, email: String) = User(
        id = "u", fullName = "Ana", email = email, roleIds = listOf("r"),
        isRegistered = true, createdAt = null, username = username,
    )

    @Test fun usernameWinsOverEmailSoListAndDetailRowsAgree() {
        // Lista: email = username legacy. Detalle tras guardar: email = correo real de GoTrue. Misma etiqueta, mismo dato.
        assertEquals("ana.garcia", userAccountName(user("ana.garcia", "ana.garcia")))
        assertEquals("ana.garcia", userAccountName(user("ana.garcia", "ana@x.com")))
    }

    @Test fun legacyRowWithoutUsernameFallsBackToEmailField() {
        assertEquals("ana.legacy", userAccountName(user(null, "ana.legacy")))
        assertEquals("ana.legacy", userAccountName(user("  ", "ana.legacy")))
        assertEquals(USER_NO_DATA, userAccountName(user(null, "  ")))
    }

    @Test fun refreshedRowKeepsTheKnownUsername() {
        val previous = user("ana.garcia", "ana.garcia")
        assertEquals("ana.garcia", user(null, "ana@x.com").keepingKnownFrom(previous).username)
    }

    @Test fun nameOver100CharsIsBlocked() {
        assertTrue(userSaveBlockers("a".repeat(100), "Ana").isEmpty())
        assertEquals(1, userSaveBlockers("a".repeat(101), "Ana").size)
        assertTrue(userSaveBlockers("  " + "a".repeat(100) + "  ", "Ana").isEmpty(), "se mide recortado, como el servidor")
    }

    @Test fun removingEveryRoleIsBlockedButAnAlreadyRolelessUserCanRename() {
        assertEquals(1, userSaveBlockers("Ana", "Ana", emptySet(), setOf("r1")).size)
        assertTrue(userSaveBlockers("Ana Nueva", "Ana", emptySet(), emptySet()).isEmpty())
        assertTrue(userSaveBlockers("Ana", "Ana", setOf("r1"), setOf("r1", "r2")).isEmpty())
    }

    @Test fun stateExposesTheRoleBlocker() {
        val row = user("ana", "ana")
        val state = UsersState(
            users = com.oposicontrol.admin.core.common.presentation.UiAsync.Success(listOf(row)),
            selectedUserId = "u", editorName = "Ana", editorRoleIds = emptySet(),
        )
        assertTrue(state.saveBlockers.isNotEmpty())
        assertTrue(!state.canSave)
    }
}
