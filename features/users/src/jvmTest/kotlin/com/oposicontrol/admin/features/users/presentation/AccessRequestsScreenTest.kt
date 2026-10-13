package com.oposicontrol.admin.features.users.presentation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.theme.AdminTheme
import com.oposicontrol.admin.domain.model.AccessRequest
import com.oposicontrol.admin.domain.model.AccessRequestStatus
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La pestana «Solicitudes de acceso» renderizada de verdad (el mismo [AccessRequestsContent] de produccion, dentro de
 * `AdminTheme`) contra estados fabricados: sin Koin ni red. Comprueba lo que se ve y lo que se despacha, y deja una
 * captura en `build/visual-harness/` para revisarla a ojo. NO valida datos del servidor.
 */
@OptIn(ExperimentalTestApi::class)
class AccessRequestsScreenTest {
    private val pending = AccessRequest(
        id = "r-1",
        userId = "u-1",
        name = "Ada Lovelace",
        email = "ada@example.com",
        reason = "Coordino el equipo de contenido de Enfermería.",
        status = AccessRequestStatus.PENDING,
        createdAt = "2026-10-12T09:00:00Z",
    )
    private val rejected = AccessRequest(
        id = "r-2",
        userId = "u-2",
        name = "Grace Hopper",
        email = "grace@example.com",
        reason = "Quiero revisar exámenes.",
        status = AccessRequestStatus.REJECTED,
        createdAt = "2026-10-10T09:00:00Z",
        resolvedAt = "2026-10-11T09:00:00Z",
        resolutionReason = "No pertenece al equipo.",
    )

    private fun state(selected: AccessRequest = pending) = AccessRequestsState(
        availability = AccessReviewAvailability.ALLOWED,
        allRequests = listOf(pending, rejected),
        requests = UiAsync.Success(listOf(pending, rejected)),
        selectedId = selected.id,
    )

    private fun render(
        state: AccessRequestsState,
        capture: String? = null,
        onIntent: (AccessRequestsIntent) -> Unit = {},
        block: androidx.compose.ui.test.ComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(width = 1366, height = 768) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                AdminTheme(darkTheme = false) { AccessRequestsContent(state = state, onIntent = onIntent) }
            }
        }
        waitForIdle()
        block()
        if (capture != null) {
            val dir = File("build/visual-harness").also { it.mkdirs() }
            val roots = onAllNodes(isRoot())
            val count = roots.fetchSemanticsNodes().size
            val file = File(dir, capture)
            ImageIO.write(roots[count - 1].captureToImage().toAwtImage(), "png", file)
            assertTrue(file.exists() && file.length() > 0L)
        }
    }

    @Test
    fun aPendingRequestShowsWhoAsksWhyAndTheTwoDecisions() {
        val sent = mutableListOf<AccessRequestsIntent>()
        render(state(), capture = "access-requests-pending.png", onIntent = { sent += it }) {
            onNodeWithTag(ACCESS_REQUESTS_CATALOG_TAG).assertExists()
            onNodeWithTag(ACCESS_REQUESTS_DETAIL_TAG).assertExists()
            onAllNodesWithText("Ada Lovelace").onFirst().assertExists()
            onAllNodesWithText("ada@example.com").onFirst().assertExists()
            onNodeWithText("Coordino el equipo de contenido de Enfermería.").assertExists()
            onNodeWithText("1 pendiente").assertExists()
            onNodeWithText("Grace Hopper").assertExists()

            onNodeWithText("Aprobar").performClick()
            onNodeWithText("Rechazar").performClick()
            onNodeWithText("Rechazadas").performClick()
            onNodeWithText("Grace Hopper").performClick()
        }
        assertEquals(
            listOf(
                AccessRequestsIntent.AskApprove("r-1"),
                AccessRequestsIntent.AskReject("r-1"),
                AccessRequestsIntent.UpdateFilter(AccessRequestFilter.REJECTED),
                AccessRequestsIntent.Select("r-2"),
            ),
            sent,
        )
    }

    @Test
    fun aResolvedRequestOffersNoDecisionAndShowsTheReason() {
        render(state(selected = rejected), capture = "access-requests-rejected.png") {
            onNodeWithText("Solicitud rechazada").assertExists()
            onNodeWithText("Motivo: No pertenece al equipo.").assertExists()
            onNodeWithText("Aprobar").assertDoesNotExist()
            onNodeWithText("Rechazar").assertDoesNotExist()
        }
    }

    @Test
    fun bothDecisionsAskForConfirmationBeforeAnythingIsSent() {
        val sent = mutableListOf<AccessRequestsIntent>()
        val approving = state().copy(pendingDecision = PendingAccessDecision(pending, AccessDecisionKind.APPROVE))
        render(approving, capture = "access-requests-confirm-approve.png", onIntent = { sent += it }) {
            onNodeWithText("Aprobar solicitud").assertExists()
            onNodeWithText("Cancelar").performClick()
        }
        val rejecting = state().copy(pendingDecision = PendingAccessDecision(pending, AccessDecisionKind.REJECT, "No pertenece al equipo."))
        render(rejecting, onIntent = { sent += it }) {
            onNodeWithText("Rechazar solicitud").assertExists()
            onNodeWithText("Motivo: No pertenece al equipo.", substring = true).assertExists()
        }
        assertEquals(listOf<AccessRequestsIntent>(AccessRequestsIntent.DismissDecision), sent)
    }

    @Test
    fun theEmptyQueueAndTheErrorAreSaidInWords() {
        render(AccessRequestsState(availability = AccessReviewAvailability.ALLOWED, requests = UiAsync.Empty)) {
            onNodeWithText("Sin solicitudes").assertExists()
            onNodeWithText("Sin solicitud abierta").assertExists()
        }
    }
}
