package com.oposicontrol.admin.app.root

import com.oposicontrol.admin.features.users.presentation.AccessRequestsState
import com.oposicontrol.admin.features.users.presentation.AccessReviewAvailability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * La pestaña «Solicitudes de acceso» del hub de Usuarios solo existe cuando el SERVIDOR ha dicho que la sesión puede
 * revisar solicitudes. Sin respuesta, o con un 403, no se pinta; y si estaba abierta cuando se pierde el permiso, el hub
 * cae en Usuarios.
 */
class UsersHubAccessRequestsTabTest {
    private val withoutRequests = listOf(UsersHubTab.USERS, UsersHubTab.TICKETS, UsersHubTab.COMPETITION, UsersHubTab.ACCESS)

    @Test
    fun theTabIsShownOnlyWhenTheServerAllowedTheReview() {
        fun tabsFor(availability: AccessReviewAvailability) =
            visibleUsersHubTabs(AccessRequestsState(availability = availability).canReview)

        assertEquals(withoutRequests, tabsFor(AccessReviewAvailability.UNKNOWN))
        assertEquals(withoutRequests, tabsFor(AccessReviewAvailability.DENIED))
        assertEquals(withoutRequests + UsersHubTab.ACCESS_REQUESTS, tabsFor(AccessReviewAvailability.ALLOWED))
    }

    @Test
    fun theDefaultStateHidesTheTab() {
        assertFalse(AccessRequestsState().canReview)
        assertFalse(UsersHubTab.ACCESS_REQUESTS in visibleUsersHubTabs(AccessRequestsState().canReview))
    }

    @Test
    fun aTabThatIsNoLongerVisibleFallsBackToUsers() {
        val hidden = visibleUsersHubTabs(canReviewAccessRequests = false)
        val shown = visibleUsersHubTabs(canReviewAccessRequests = true)

        assertEquals(UsersHubTab.USERS.id, effectiveUsersHubTab(UsersHubTab.ACCESS_REQUESTS.id, hidden))
        assertEquals(UsersHubTab.ACCESS_REQUESTS.id, effectiveUsersHubTab(UsersHubTab.ACCESS_REQUESTS.id, shown))
        assertEquals(UsersHubTab.TICKETS.id, effectiveUsersHubTab(UsersHubTab.TICKETS.id, hidden))
        assertEquals(UsersHubTab.USERS.id, effectiveUsersHubTab("no-existe", shown))
    }

    @Test
    fun theTabSitsLastAndIsNotReachableFromAnyMenuRoute() {
        assertEquals(UsersHubTab.ACCESS_REQUESTS, UsersHubTab.entries.last())
        assertEquals("Solicitudes de acceso", UsersHubTab.ACCESS_REQUESTS.label)
        assertTrue(UsersHubTab.entries.map { it.id }.toSet().size == UsersHubTab.entries.size, "ids únicos")
    }
}
