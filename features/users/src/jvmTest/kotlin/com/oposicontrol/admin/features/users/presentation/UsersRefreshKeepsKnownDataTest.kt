package com.oposicontrol.admin.features.users.presentation

import com.oposicontrol.admin.domain.model.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** MASTER2-OC-004: una fila refrescada sin cartera/nombre/email no pisa el dato real con un valor fabricado. */
class UsersRefreshKeepsKnownDataTest {
    private fun user(name: String, email: String, tickets: Int?) =
        User(id = "u1", fullName = name, email = email, roleIds = emptyList(), isRegistered = null, createdAt = null, ticketsBalance = tickets)

    @Test fun `fresh row without balance keeps the previous balance`() {
        val merged = user("Ana", "a@x.es", null).keepingKnownFrom(user("Ana", "a@x.es", 42))
        assertEquals(42, merged.ticketsBalance)
    }

    @Test fun `fresh row balance wins over the previous one`() {
        assertEquals(7, user("Ana", "a@x.es", 7).keepingKnownFrom(user("Ana", "a@x.es", 42)).ticketsBalance)
    }

    @Test fun `no balance anywhere stays null not zero`() {
        assertNull(user("Ana", "a@x.es", null).keepingKnownFrom(user("Ana", "a@x.es", null)).ticketsBalance)
    }

    @Test fun `blank name and email fall back to the previous row`() {
        val merged = user("", "", null).keepingKnownFrom(user("Ana", "a@x.es", 1))
        assertEquals("Ana", merged.fullName)
        assertEquals("a@x.es", merged.email)
    }
}
