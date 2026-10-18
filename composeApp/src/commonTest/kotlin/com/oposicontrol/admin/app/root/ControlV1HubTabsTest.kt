package com.oposicontrol.admin.app.root

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Las pestañas de Control V1 que cuelgan de hubs que ya existían. Fijan DÓNDE se encuentra cada cosa: quien busca
 * «Invitaciones» o «Pedidos» tiene que encontrarlas sin saber en qué hub viven, y nada de esto crea una ruta nueva.
 */
class ControlV1HubTabsTest {
    @Test
    fun referralsLiveInUsersAfterTheOriginalTabs() {
        assertEquals(UsersHubTab.REFERRALS, UsersHubTab.entries.last())
        assertEquals("Invitaciones", UsersHubTab.REFERRALS.label)
        assertEquals(UsersHubTab.entries.size, UsersHubTab.entries.map { it.id }.toSet().size)
    }

    @Test
    fun forumOpsLiveInModeration() {
        assertEquals(ModerationHubTab.FORUM, ModerationHubTab.entries.last())
        assertEquals(listOf("Moderación", "Reportes", "OposiTester", "Foro"), ModerationHubTab.entries.map { it.label })
    }

    @Test
    fun economyAndOrdersLiveInTheCatalogAndNeverMountAMarketEditor() {
        assertNull(CatalogHubTab.ECONOMY.marketSection)
        assertNull(CatalogHubTab.ORDERS.marketSection)
        assertEquals(CatalogHubTab.entries.size, CatalogHubTab.entries.map { it.id }.toSet().size)
    }

    @Test
    fun alertsLiveInSystemAndTheBadgeStillBelongsToAdmins() {
        assertEquals(SystemHubTab.ALERTS, SystemHubTab.entries.last())
        val tabs = systemHubTabs(pendingAccessRequests = 2)
        assertTrue(tabs.single { it.id == SystemHubTab.ALERTS.id }.badge == null)
    }

    @Test
    fun theMarkupEditorSitsNextToTheImportAndTheImportStaysFirst() {
        assertEquals(ImportHubTab.IMPORT, ImportHubTab.entries.first())
        assertEquals(listOf("Importación", "Marcado de razonados"), ImportHubTab.entries.map { it.label })
    }
}
