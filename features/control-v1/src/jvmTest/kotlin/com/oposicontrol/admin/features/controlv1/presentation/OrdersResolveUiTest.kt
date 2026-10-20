package com.oposicontrol.admin.features.controlv1.presentation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.ui.theme.AdminTheme
import com.oposicontrol.admin.domain.model.OrderDecision
import com.oposicontrol.admin.domain.model.OrderItem
import com.oposicontrol.admin.domain.model.OrderPage
import com.oposicontrol.admin.domain.model.OrderResolution
import com.oposicontrol.admin.domain.repository.ControlV1Repository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pedidos con el [OrdersViewModel] REAL y la pantalla REAL ([OrdersScreen]) contra un repositorio falso: la cola «En
 * revisión», resolver con nota obligatoria, el reembolso solo cuando el servidor lo tiene encendido y el rechazo del
 * servidor a la vista sin perder el pedido.
 */
@OptIn(ExperimentalTestApi::class)
class OrdersResolveUiTest {
    private object Unconfined : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private val order = OrderItem(
        id = "o1", state = "en_revision", provider = "google_play", productCode = "pack_100", productType = "pack", tickets = 100,
        priceCents = 499, currency = "EUR", transactionId = "GPA.1", userId = "u1", userHandle = "pepe",
        createdAt = "2026-10-17T10:00:00Z", paidAt = null, expiresAt = null, refund = null,
    )

    /**
     * El doble comun ([FakeControlV1Repository]) salvo resolver: aqui el resultado lo fija cada caso, y un exito saca el
     * pedido de la cola como haria el servidor.
     */
    private class Repo(
        val base: FakeControlV1Repository,
        var resolveResult: AppResult<OrderResolution> = AppResult.Success(OrderResolution("CREDITED", "pagada", false)),
    ) : ControlV1Repository by base {
        val resolves = mutableListOf<Triple<String, OrderDecision, String>>()
        override suspend fun resolveOrder(id: String, decision: OrderDecision, note: String): AppResult<OrderResolution> {
            resolves += Triple(id, decision, note)
            val result = resolveResult
            if (result is AppResult.Success) base.orders = OrderPage(emptyList(), 0, 25, 0, base.orders.refundsEnabled)
            return result
        }
    }

    private fun repo(refundsEnabled: Boolean = false) = Repo(
        FakeControlV1Repository().apply {
            orders = OrderPage(listOf(order), 1, 25, 0, refundsEnabled = refundsEnabled)
            reviewCount = 1
        },
    )

    private fun orders(repo: Repo, block: ComposeUiTest.(OrdersViewModel) -> Unit) =
        runDesktopComposeUiTest(width = 1366, height = 1400) {
            val vm = OrdersViewModel(Unconfined, repo)
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                    AdminTheme(darkTheme = false) { OrdersScreen(vm) }
                }
            }
            waitForIdle()
            block(vm)
        }

    private fun ComposeUiTest.openOrder() {
        onNodeWithText("@pepe").performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.openDecisions() {
        onNodeWithText("Elige una decisión").performClick()
        waitForIdle()
    }

    @Test
    fun anOrderInReviewIsListed() {
        val repo = repo()
        orders(repo) {
            onNodeWithTag(ORDERS_TABLE_TAG).assertExists()
            onNodeWithText("@pepe").assertExists()
            onNodeWithText("pack_100").assertExists()
            onNodeWithText("4,99 EUR").assertExists()
            onNodeWithText("1 pedidos esperan revisión").assertExists()
        }
        assertTrue("orders:en_revision:0" in repo.base.calls, "La pantalla no abre en la cola En revisión: ${repo.base.calls}")
    }

    @Test
    fun creditingNeedsANoteThenCallsResolveAndShowsTheServerOutcome() {
        val repo = repo()
        orders(repo) {
            openOrder()
            openDecisions()
            onNodeWithText("Acreditar").performClick()
            waitForIdle()

            // Sin motivo no se abre la confirmacion ni se llama al servidor.
            onNodeWithText("Resolver pedido").performClick()
            waitForIdle()
            onNodeWithText("Indica el motivo de la decisión (mínimo 3 caracteres).").assertExists()
            onNodeWithText("Acreditar pedido").assertDoesNotExist()
            assertTrue(repo.resolves.isEmpty())

            onAllNodes(hasSetTextAction())[0].performTextInput("Pago verificado en Play Console")
            waitForIdle()
            onNodeWithText("Resolver pedido").performClick()
            waitForIdle()
            onNodeWithText("Acreditar pedido").assertExists()
            onAllNodesWithText("Acreditar").onLast().performClick()
            waitForIdle()

            onNodeWithText(OrderResolution("CREDITED", "pagada", false).message()).assertExists()
            onNodeWithText("@pepe").assertDoesNotExist()
        }
        assertEquals(listOf(Triple("o1", OrderDecision.CREDIT, "Pago verificado en Play Console")), repo.resolves)
    }

    @Test
    fun refundIsNotOfferedWhileTheServerHasItSwitchedOff() {
        orders(repo(refundsEnabled = false)) {
            openOrder()
            onNodeWithText("El reembolso está desactivado en este entorno.").assertExists()
            openDecisions()
            onNodeWithText("Acreditar").assertExists()
            onNodeWithText("Confirmar").assertExists()
            onNodeWithText("Rechazar").assertExists()
            onNodeWithText("Reembolsar").assertDoesNotExist()
        }
    }

    @Test
    fun refundIsOfferedWhenTheServerHasItSwitchedOn() {
        orders(repo(refundsEnabled = true)) { vm ->
            openOrder()
            onNodeWithText("El reembolso está desactivado en este entorno.").assertDoesNotExist()
            openDecisions()
            onNodeWithText("Reembolsar").performClick()
            waitForIdle()
            assertEquals(OrderDecision.REFUND, vm.state.value.decision)
        }
    }

    @Test
    fun aServerRejectionIsShownAndTheOrderStaysListed() {
        val rejection = "Este pedido nunca se acreditó: no se puede confirmar ni reembolsar."
        val repo = repo().apply { resolveResult = AppResult.Failure(AppError.Validation(rejection, code = "order_not_credited")) }
        orders(repo) {
            openOrder()
            openDecisions()
            onNodeWithText("Confirmar").performClick()
            waitForIdle()
            onAllNodes(hasSetTextAction())[0].performTextInput("El proveedor dice que ya estaba pagado")
            waitForIdle()
            onNodeWithText("Resolver pedido").performClick()
            waitForIdle()
            onNodeWithText("Confirmar pedido").assertExists()
            onAllNodesWithText("Confirmar").onLast().performClick()
            waitForIdle()

            onNodeWithText(rejection).assertExists()
            onNodeWithText("@pepe").assertExists()
            onNodeWithText("Resolver pedido").assertExists()
        }
        assertEquals(OrderDecision.CONFIRM, repo.resolves.single().second)
    }
}
