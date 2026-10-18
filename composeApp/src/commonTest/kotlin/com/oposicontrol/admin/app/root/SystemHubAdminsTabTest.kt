package com.oposicontrol.admin.app.root

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.AccessRequest
import com.oposicontrol.admin.domain.model.AccessRequestStatus
import com.oposicontrol.admin.features.users.presentation.AccessRequestsState
import com.oposicontrol.admin.features.users.presentation.AccessReviewAvailability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El hub de Sistema tiene tres pestañas —Estado, Administradores, Auditoría— y «Solicitudes de acceso» vive dentro de
 * Administradores, no en Usuarios. Sustituye a `UsersHubAccessRequestsTabTest`: la pestaña ya no aparece y desaparece
 * según la sesión (Administradores la ve cualquier administrador); lo que sigue dependiendo del SERVIDOR es si se pueden
 * revisar solicitudes, y con ello el aviso de pendientes.
 */
class SystemHubAdminsTabTest {
    private fun request(id: String, status: AccessRequestStatus) =
        AccessRequest(
            id = id,
            userId = "u-$id",
            name = id,
            email = "$id@example.com",
            reason = "",
            status = status,
            createdAt = "2026-10-13T10:00:00Z",
        )

    @Test
    fun theHubHasFourTabsWithStatusFirst() {
        assertEquals(listOf(SystemHubTab.STATUS, SystemHubTab.ADMINS, SystemHubTab.AUDIT, SystemHubTab.ALERTS), SystemHubTab.entries)
        assertEquals(listOf("Estado", "Administradores", "Auditoría", "Alertas"), systemHubTabs(pendingAccessRequests = 0).map { it.label })
        assertEquals(SystemHubTab.entries.size, SystemHubTab.entries.map { it.id }.toSet().size, "ids únicos")
    }

    @Test
    fun theUsersHubNoLongerCarriesAccessRequests() {
        assertEquals(
            listOf("Usuarios", "OposiTickets", "Competición", "Accesos", "Invitaciones"),
            UsersHubTab.entries.map { it.label },
        )
    }

    @Test
    fun onlyTheAdminsTabWarnsAboutPendingRequests() {
        val tabs = systemHubTabs(pendingAccessRequests = 3)
        assertEquals("3", tabs.single { it.id == SystemHubTab.ADMINS.id }.badge)
        assertTrue(tabs.filter { it.id != SystemHubTab.ADMINS.id }.all { it.badge == null })
        // Sin pendientes no hay aviso: un «0» en la pestaña sería ruido.
        assertTrue(systemHubTabs(pendingAccessRequests = 0).all { it.badge == null })
    }

    @Test
    fun theWarningComesOnlyFromWhatTheServerLetThisSessionRead() {
        // Sin respuesta, o con un 403, el estado no guarda ninguna solicitud: no hay recuento que enseñar.
        assertFalse(AccessRequestsState().canReview)
        assertEquals(0, AccessRequestsState().pendingCount)
        assertEquals(0, AccessRequestsState(availability = AccessReviewAvailability.DENIED).pendingCount)

        val allowed = AccessRequestsState(
            availability = AccessReviewAvailability.ALLOWED,
            allRequests = listOf(
                request("a", AccessRequestStatus.PENDING),
                request("b", AccessRequestStatus.APPROVED),
                request("c", AccessRequestStatus.PENDING),
            ),
            requests = UiAsync.Empty,
        )
        assertEquals("2", systemHubTabs(allowed.pendingCount).single { it.id == SystemHubTab.ADMINS.id }.badge)
        assertNull(systemHubTabs(AccessRequestsState().pendingCount).single { it.id == SystemHubTab.ADMINS.id }.badge)
    }
}
