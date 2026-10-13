package com.oposicontrol.admin.features.store.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.domain.model.CommercialProduct
import com.oposicontrol.admin.domain.model.MarketPopupRule
import com.oposicontrol.admin.domain.model.MarketPromotion
import com.oposicontrol.admin.domain.model.MarketService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * El escaparate de OposiMarket: qué cuenta como «en vigor». Es de solo consulta, así que lo que hay que proteger es que no
 * enseñe como publicado algo que no lo está (inactivo, oculto, caducado o que aún no ha empezado).
 */
class MarketShowcaseTest {
    private val today = "2026-10-14"

    private fun promotion(id: String, active: Boolean = true, from: String? = null, until: String? = null) =
        MarketPromotion(id = id, code = id.uppercase(), name = id, discountType = "porcentaje", value = 10.0, active = active, validFrom = from, validUntil = until)

    @Test
    fun `a product is on sale only if it is active and visible`() {
        val products = listOf(
            CommercialProduct("a", "a", "pack_tickets", "A"),
            CommercialProduct("b", "b", "pack_tickets", "B", visible = false),
            CommercialProduct("c", "c", "pack_tickets", "C", active = false),
        )
        assertEquals(listOf("a"), showcaseProducts(products).map { it.id })
    }

    @Test
    fun `inactive services and popup rules are not in force`() {
        val services = listOf(
            MarketService("s1", "s1", "Uno", serviceType = "tutoria", priceTickets = 5),
            MarketService("s2", "s2", "Dos", serviceType = "tutoria", priceTickets = 5, active = false),
        )
        assertEquals(listOf("s1"), showcaseServices(services).map { it.id })

        val rules = listOf(
            MarketPopupRule(id = "r1", code = "r1", trigger = "e", title = "t", message = "m"),
            MarketPopupRule(id = "r2", code = "r2", trigger = "e", title = "t", message = "m", active = false),
        )
        assertEquals(listOf("r1"), showcasePopups(rules).map { it.id })
    }

    @Test
    fun `a promotion is in force only while active and inside its window`() {
        val promotions = listOf(
            promotion("abierta"),
            promotion("vigente", from = "2026-10-01T00:00:00+00:00", until = "2026-10-31T23:59:59+00:00"),
            // Los dos extremos de la ventana son días completos: el propio día cuenta.
            promotion("empieza_hoy", from = "2026-10-14T00:00:00+00:00"),
            promotion("acaba_hoy", until = "2026-10-14T23:59:59+00:00"),
            promotion("caducada", until = "2026-10-13T23:59:59+00:00"),
            promotion("futura", from = "2026-10-15T00:00:00+00:00"),
            promotion("apagada", active = false),
        )
        assertEquals(listOf("abierta", "vigente", "empieza_hoy", "acaba_hoy"), showcasePromotions(promotions, today).map { it.id })
    }

    @Test
    fun `a tab keeps loading, error and idle as they are and says empty when nothing is in force`() {
        val loading = showcaseOf(MarketSection.PRODUCTS, MarketState(products = UiAsync.Loading), today)
        assertEquals(UiAsync.Loading, loading.rows)

        val failed = showcaseOf(MarketSection.PRODUCTS, MarketState(products = UiAsync.Error(AppError.Network("caída"))), today)
        assertIs<UiAsync.Error>(failed.rows)

        // En reposo no es «cargando»: la pantalla lo pinta con su propio mensaje.
        assertEquals(UiAsync.Idle, showcaseOf(MarketSection.PRODUCTS, MarketState(), today).rows)

        val hidden = listOf(CommercialProduct("b", "b", "pack_tickets", "B", visible = false))
        val nothing = showcaseOf(MarketSection.PRODUCTS, MarketState(products = UiAsync.Success(hidden)), today)
        assertEquals(UiAsync.Empty, nothing.rows)
        assertEquals(1, nothing.configured, "se dice que hay uno configurado aunque no esté en vigor")
    }

    @Test
    fun `only products are marked as consumed by the app`() {
        assertTrue(showcaseOf(MarketSection.PRODUCTS, MarketState(), today).consumedByApp)
        listOf(MarketSection.SERVICES, MarketSection.PROMOTIONS, MarketSection.POPUPS).forEach {
            assertFalse(showcaseOf(it, MarketState(), today).consumedByApp, it.name)
        }
    }

    @Test
    fun `the store offers five tabs and packs is not one of them`() {
        assertEquals(
            listOf(MarketSection.EXAMS, MarketSection.PRODUCTS, MarketSection.SERVICES, MarketSection.PROMOTIONS, MarketSection.POPUPS),
            SHOWCASE_SECTIONS,
        )
        assertFalse(MarketSection.PACKS in SHOWCASE_SECTIONS)
        assertEquals("Productos y packs", MarketSection.PRODUCTS.label())
    }

    @Test
    fun `today is a plain utc day`() {
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}""").matches(todayIsoDate()))
    }
}
