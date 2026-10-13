package com.oposicontrol.admin.features.users.presentation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.oposicontrol.admin.core.ui.theme.AdminTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Sistema > Administradores renderizada de verdad (el mismo [AdminsContent] de producción) contra estados fabricados, sin
 * Koin ni red. Comprueba lo que se ve y lo que se despacha; NO valida datos del servidor.
 */
@OptIn(ExperimentalTestApi::class)
class AdminsScreenTest {
    private val fx = AdminsFixtures

    private fun render(
        state: AdminsState,
        accessState: AccessRequestsState,
        section: AdminsSection = AdminsSection.WHO,
        onIntent: (AdminsIntent) -> Unit = {},
        onOpenUser: (String) -> Unit = {},
        onSectionChange: (AdminsSection) -> Unit = {},
        block: ComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(width = 1366, height = 768) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                AdminTheme(darkTheme = false) {
                    AdminsContent(
                        state = state,
                        accessState = accessState,
                        section = section,
                        onSectionChange = onSectionChange,
                        onIntent = onIntent,
                        onAccessIntent = {},
                        onOpenUser = onOpenUser,
                    )
                }
            }
        }
        waitForIdle()
        block()
    }

    @Test
    fun aRowOpensThatPersonInUsersAndNothingIsEditedHere() {
        val opened = mutableListOf<String>()
        render(fx.conCuentas(), fx.superadmin(), onOpenUser = { opened += it }) {
            onNodeWithText("Lucía Fernández").assertExists()
            onAllNodesWithText("Superadministrador").fetchSemanticsNodes().let { assertEquals(1, it.size) }
            onNodeWithText("Dada de baja").assertExists()
            onNodeWithText("Marta Ruiz Peña").performClick()
            waitForIdle()
        }
        assertEquals(listOf("u-2"), opened)
    }

    @Test
    fun aNetworkErrorIsNotPaintedAsNoAdmins() {
        val intents = mutableListOf<AdminsIntent>()
        render(fx.caida(), fx.sinPermiso(), onIntent = { intents += it }) {
            onAllNodesWithText("Sin administradores").fetchSemanticsNodes().let { assertEquals(0, it.size) }
            onNodeWithText("Reintentar").performClick()
            waitForIdle()
        }
        assertEquals(listOf<AdminsIntent>(AdminsIntent.Refresh), intents)
    }

    @Test
    fun withoutTheSuperadminAnswerTheRequestsBlockExplainsItselfInsteadOfStayingEmpty() {
        render(fx.conCuentas(), fx.sinPermiso(), section = AdminsSection.REQUESTS) {
            onNodeWithTag(ADMINS_REVIEW_DENIED_TAG).assertExists()
            onNodeWithText(ACCESS_REVIEW_DENIED_MESSAGE).assertExists()
            // La cola de solicitudes no se compone: no hay nada leído que enseñar.
            onAllNodesWithTag(ACCESS_REQUESTS_CATALOG_TAG).fetchSemanticsNodes().let { assertEquals(0, it.size) }
        }
    }

    @Test
    fun aFailedCheckIsNeitherAllowedNorDenied() {
        render(fx.conCuentas(), fx.sinRespuestaPorError(), section = AdminsSection.REQUESTS) {
            onAllNodesWithTag(ADMINS_REVIEW_DENIED_TAG).fetchSemanticsNodes().let { assertEquals(0, it.size) }
            onAllNodesWithTag(ACCESS_REQUESTS_CATALOG_TAG).fetchSemanticsNodes().let { assertEquals(0, it.size) }
            onNodeWithText("No se ha podido comprobar si puedes revisar solicitudes").assertExists()
        }
    }

    @Test
    fun aSuperadminGetsTheRequestsScreenAsItWas() {
        render(fx.conCuentas(), fx.superadmin(), section = AdminsSection.REQUESTS) {
            onNodeWithTag(ACCESS_REQUESTS_CATALOG_TAG).assertExists()
            onNodeWithTag(ACCESS_REQUESTS_DETAIL_TAG).assertExists()
        }
    }

    @Test
    fun theRoleTableShowsTheFourRolesAndTheSubTabsSwitch() {
        val selected = mutableListOf<AdminsSection>()
        render(fx.conCuentas(), fx.sinPermiso(), section = AdminsSection.ROLES, onSectionChange = { selected += it }) {
            onNodeWithTag(ADMINS_ROLE_TABLE_TAG).assertExists()
            PANEL_ROLE_PERMISSIONS.forEach { role -> onNodeWithText(role.name).assertExists() }
            onNodeWithText("Quién puede entrar").performClick()
            waitForIdle()
        }
        assertEquals(listOf(AdminsSection.WHO), selected)
    }
}
