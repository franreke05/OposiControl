package com.oposicontrol.admin.features.users.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La tabla «Qué permite cada rol» es fija en el cliente: estas pruebas no comprueban el servidor, comprueban que la
 * tabla no se queda coja ni se contradice con la lista de códigos que el cliente ya usa para saber quién administra.
 */
class AdminsRoleTableTest {
    @Test
    fun theTableHasTheFourRolesInOrderOfPrivilege() {
        assertEquals(listOf("superadmin", "admin", "moderador", "usuario"), PANEL_ROLE_PERMISSIONS.map { it.code })
        assertEquals(
            listOf("Superadministrador", "Administrador", "Moderador", "Usuario"),
            PANEL_ROLE_PERMISSIONS.map { it.name },
        )
    }

    @Test
    fun onlyTheRolesTheServerLetsInAreMarkedAsEnteringThePanel() {
        // Mismo conjunto que `ADMIN_ROLE_CODES` (espejo de `CanonicalAuthorization.adminRoleCodes`): si uno cambia sin el
        // otro, la tabla diría que entra alguien a quien la lista de administradores no enseña.
        assertEquals(ADMIN_ROLE_CODES, PANEL_ROLE_PERMISSIONS.filter { it.entersPanel }.map { it.code }.toSet())
        assertEquals(AdminLevel.entries.map { it.code }.toSet(), ADMIN_ROLE_CODES)
    }

    @Test
    fun aRoleThatDoesNotEnterThePanelIsNotGivenAnyPanelPermission() {
        PANEL_ROLE_PERMISSIONS.filterNot { it.entersPanel }.forEach { role ->
            assertTrue(role.can.isEmpty(), "«${role.name}» no entra en el panel: no puede listar permisos del panel.")
            assertTrue(role.cannot.isNotEmpty(), "«${role.name}» debe decir que no entra, no quedarse en blanco.")
        }
    }

    @Test
    fun everyLineIsAFullSentence() {
        PANEL_ROLE_PERMISSIONS.flatMap { it.can + it.cannot }.forEach { line ->
            assertTrue(line.isNotBlank() && line.endsWith("."), "«$line» debe ser una frase completa.")
        }
    }

    @Test
    fun theSubTabsAreThreeAndOnlyRequestsCarriesABadge() {
        val tabs = adminsSectionTabs(pendingRequests = 2)
        assertEquals(listOf("Quién puede entrar", "Solicitudes de acceso", "Qué permite cada rol"), tabs.map { it.label })
        assertEquals(listOf(null, "2", null), tabs.map { it.badge })
        assertTrue(adminsSectionTabs(pendingRequests = 0).all { it.badge == null })
    }
}
