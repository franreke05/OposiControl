package com.oposicontrol.admin.features.tickets.presentation

import com.oposicontrol.admin.core.ui.components.StatusTone
import com.oposicontrol.admin.domain.model.TicketsOverview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TicketsPresentationTest {
    @Test
    fun `every ledger type of the database check has a readable name and an unknown one is shown as it is`() {
        val known = listOf(
            "recarga", "pack_tickets", "gasto", "compra_examen", "compra_servicio", "ajuste", "devolucion", "regalo",
            "reto_diario", "bonus_racha", "referido", "reporte_validado", "potenciador", "protector",
        )
        known.forEach { type -> assertTrue(ticketMovementTypeLabel(type) != type, "$type sin nombre legible") }
        assertEquals("Ajuste manual", ticketMovementTypeLabel(" AJUSTE "))
        assertEquals("tipo_nuevo", ticketMovementTypeLabel("tipo_nuevo"))
        assertEquals("Sin tipo", ticketMovementTypeLabel(""))
    }

    @Test
    fun `amounts carry their sign in the text`() {
        assertEquals("+50 OT", formatSignedOt(50))
        assertEquals("−10 OT", formatSignedOt(-10))
        assertEquals("0 OT", formatSignedOt(0))
        assertEquals("+2.600 OT", formatSignedOt(2600))
        assertEquals("−10.000 OT", formatSignedOt(-10000))
        assertEquals("1.234.567", formatCount(1234567))
        assertEquals("999", formatCount(999))
        assertEquals(StatusTone.POSITIVE, ticketMovementTone(1))
        assertEquals(StatusTone.NEUTRAL, ticketMovementTone(-1))
    }

    @Test
    fun `the balance trail only says what the ledger knows`() {
        val base = TicketMovement("1", "ajuste", 50, "r", "2026-10-09T14:32:10Z")
        assertNull(ticketBalanceTrail(base))
        assertEquals("Saldo resultante 150 OT", ticketBalanceTrail(base.copy(balanceAfter = 150)))
        assertEquals("Saldo 100 → 150 OT", ticketBalanceTrail(base.copy(balanceBefore = 100, balanceAfter = 150)))
    }

    @Test
    fun `instants are shown as date and minute and anything else as it comes`() {
        assertEquals("2026-10-09 14:32", formatLedgerInstant("2026-10-09T14:32:10.123456+00:00"))
        assertEquals("ayer", formatLedgerInstant("ayer"))
        assertEquals("Sin fecha", formatLedgerInstant("  "))
    }

    @Test
    fun `origin absence is said as absence`() {
        assertEquals("Sin origen registrado", ticketOriginLabel(null))
        assertEquals("Administración", ticketOriginLabel("admin"))
        assertEquals("campaña", ticketOriginLabel("campaña"))
    }

    @Test
    fun `adjustment amounts are whole numbers with an optional sign`() {
        assertEquals(50, parseAdjustmentAmount("+50"))
        assertEquals(-10, parseAdjustmentAmount(" -10 "))
        assertEquals(-10, parseAdjustmentAmount("−10"))
        assertEquals(7, parseAdjustmentAmount("7"))
        listOf("", "12,5", "12.5", "1e3", "diez", "+", "-", "1234567").forEach { assertNull(parseAdjustmentAmount(it), it) }
    }

    @Test
    fun `the adjustment rules are the ones the backend enforces`() {
        assertEquals("Elige una cartera antes de ajustar el saldo.", adjustmentProblem("5", "m", hasWallet = false))
        assertEquals("Escribe la cantidad de OposiTickets.", adjustmentProblem("", "m", hasWallet = true))
        assertTrue(adjustmentProblem("1,5", "m", true)!!.contains("entero"))
        assertEquals("La cantidad no puede ser cero.", adjustmentProblem("0", "m", true))
        assertTrue(adjustmentProblem("10001", "m", true)!!.contains("10.000"))
        assertTrue(adjustmentProblem("-10001", "m", true)!!.contains("10.000"))
        assertTrue(adjustmentProblem("5", "  ", true)!!.contains("motivo"))
        assertTrue(adjustmentProblem("5", "x".repeat(1001), true)!!.contains("1.000"))
        assertNull(adjustmentProblem("10000", "x".repeat(1000), true))
        assertNull(adjustmentProblem("-10000", "motivo", true))
    }

    @Test
    fun `wallets are matched by name or specialty and by balance`() {
        val ana = WalletRow("1", "Ana García", "TCAE", 120)
        val luis = WalletRow("2", "Luis", "", 0)
        assertTrue(walletMatches(ana, "  tca ", WalletFilter.ALL))
        assertTrue(walletMatches(ana, "GARC", WalletFilter.WITH_BALANCE))
        assertTrue(!walletMatches(ana, "", WalletFilter.EMPTY))
        assertTrue(walletMatches(luis, "", WalletFilter.EMPTY))
        assertTrue(!walletMatches(luis, "tcae", WalletFilter.ALL))
        assertTrue(!walletCatalogHasFilters("  ", WalletFilter.ALL))
        assertTrue(walletCatalogHasFilters("", WalletFilter.EMPTY))
    }

    @Test
    fun `a count the backend could not compute is not available and never zero`() {
        val facts = ticketsOverviewFacts(TicketsOverview(wallets = 3, movements = null, credits = 0, debits = null))
        assertEquals(listOf("3", "No disponible", "0", "No disponible"), facts.map { it.value })
        assertEquals(listOf("Carteras", "Movimientos", "Abonos", "Cargos"), facts.map { it.label })
        assertTrue(ticketsOverviewFacts(null).all { it.value == "No disponible" })
    }
}
