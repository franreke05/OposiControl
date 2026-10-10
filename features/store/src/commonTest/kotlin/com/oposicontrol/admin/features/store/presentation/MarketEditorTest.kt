package com.oposicontrol.admin.features.store.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.MarketPopupRule
import com.oposicontrol.admin.domain.model.MarketPromotion
import com.oposicontrol.admin.domain.model.MarketPromotionUse
import com.oposicontrol.admin.domain.model.MarketService
import com.oposicontrol.admin.domain.model.MarketTicketPack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El motor de los catálogos editables de OposiMarket y las reglas de sus cuatro entidades, sin Compose ni red. El motor es
 * uno para las cuatro: probarlo con packs lo prueba para todas.
 */
class MarketEditorTest {
    private val small = MarketTicketPack("p1", "pack_500", "500 OposiTickets", ticketAmount = 500, priceCents = 499, position = 1)
    private val big = MarketTicketPack("p2", "pack_2600", "2.600 OposiTickets", "El que más compensa", 2600, 1999, position = 2, active = false)

    private fun loaded(vararg packs: MarketTicketPack) =
        MarketEditor<MarketTicketPack, PackDraft>(draft = PackDraft()).loaded(PackSpec, AppResult.Success(packs.toList()))

    // ---------- motor ----------

    @Test
    fun `the first read opens the first record, an empty one is empty and a failed one is an error`() {
        val editor = loaded(small, big)
        assertEquals("p1", editor.selectedId)
        assertEquals("4,99", editor.draft.price)
        assertFalse(editor.dirty(PackSpec))

        assertIs<UiAsync.Empty>(loaded().items)
        assertFalse(loaded().isOpen)

        val failed = MarketEditor<MarketTicketPack, PackDraft>(draft = PackDraft())
            .loaded(PackSpec, AppResult.Failure(AppError.Network("sin red")))
        assertIs<UiAsync.Error>(failed.items)
    }

    @Test
    fun `a refresh keeps the list on screen and never throws away a draft`() {
        val editing = loaded(small, big).reduce(PackSpec, EditorAction.UpdateDraft(PackSpec.toDraft(small).copy(name = "A medias")))
        val refreshing = editing.loading()
        // Lo ya leído se sigue viendo mientras se relee: la ficha no se vacía.
        assertIs<UiAsync.Success<*>>(refreshing.items)
        assertTrue(refreshing.refreshing)
        assertTrue(refreshing.dirty(PackSpec))

        val after = refreshing.loaded(PackSpec, AppResult.Success(listOf(small.copy(priceCents = 599), big)))
        assertFalse(after.refreshing)
        assertEquals("A medias", after.draft.name)
        assertEquals(599, after.selected(PackSpec)?.priceCents)
    }

    @Test
    fun `a refresh without changes rereads the open record from the server`() {
        val after = loaded(small, big).loading().loaded(PackSpec, AppResult.Success(listOf(small.copy(priceCents = 599), big)))
        assertEquals("5,99", after.draft.price)
        assertFalse(after.dirty(PackSpec))
    }

    @Test
    fun `a failed refresh keeps what was read and says so, instead of blanking the screen`() {
        val after = loaded(small, big).loading().loaded(PackSpec, AppResult.Failure(AppError.Network("sin red")))
        assertIs<UiAsync.Success<*>>(after.items)
        assertTrue(after.refreshFailed)
        assertFalse(after.refreshing)
        assertEquals("p1", after.selectedId)
    }

    @Test
    fun `when the open record disappears the first one is opened`() {
        val opened = loaded(small, big).reduce(PackSpec, EditorAction.Open("p2"))
        assertEquals("p2", opened.selectedId)
        val after = opened.loading().loaded(PackSpec, AppResult.Success(listOf(small)))
        assertEquals("p1", after.selectedId)

        val gone = opened.loading().loaded(PackSpec, AppResult.Success(emptyList()))
        assertNull(gone.selectedId)
        assertEquals(PackDraft(), gone.draft)
    }

    @Test
    fun `leaving a record with unsaved changes asks first`() {
        val editing = loaded(small, big).reduce(PackSpec, EditorAction.UpdateDraft(PackSpec.toDraft(small).copy(price = "5,49")))
        val asked = editing.reduce(PackSpec, EditorAction.Open("p2"))
        assertEquals("p1", asked.selectedId)
        assertEquals(PendingEditorOpen("p2"), asked.pendingOpen)

        val stayed = asked.reduce(PackSpec, EditorAction.DismissOpen)
        assertNull(stayed.pendingOpen)
        assertEquals("5,49", stayed.draft.price)

        val left = asked.reduce(PackSpec, EditorAction.ConfirmOpen)
        assertEquals("p2", left.selectedId)
        assertFalse(left.dirty(PackSpec))

        // «Nuevo» con cambios pendientes también pregunta.
        assertEquals(PendingEditorOpen(null), editing.reduce(PackSpec, EditorAction.Open(null)).pendingOpen)
    }

    @Test
    fun `search and filter narrow the downloaded list and return to the first page`() {
        val editor = loaded(small, big).reduce(PackSpec, EditorAction.SetPage(2))
        val inactive = editor.reduce(PackSpec, EditorAction.UpdateFilter(FILTER_INACTIVE))
        assertEquals(listOf("p2"), inactive.visible(PackSpec).map { it.id })
        assertEquals(0, inactive.pageIndex)
        assertEquals(listOf("p1"), editor.reduce(PackSpec, EditorAction.UpdateSearch("PACK_500")).visible(PackSpec).map { it.id })
    }

    @Test
    fun `discard returns to what the server has`() {
        val editing = loaded(small).reduce(PackSpec, EditorAction.UpdateDraft(PackSpec.toDraft(small).copy(name = "Otro")))
        assertEquals("500 OposiTickets", editing.reduce(PackSpec, EditorAction.Discard).draft.name)

        val creating = loaded(small).reduce(PackSpec, EditorAction.Open(null)).reduce(PackSpec, EditorAction.UpdateDraft(PackDraft(name = "x")))
        assertEquals(PackDraft(), creating.reduce(PackSpec, EditorAction.Discard).draft)
    }

    @Test
    fun `a saved record replaces its row, a new one joins the list, and the record stays open`() {
        val edited = loaded(small, big).saved(PackSpec, sent = small.copy(priceCents = 599), saved = small.copy(priceCents = 599))
        assertEquals(listOf(599, 1999), edited.all.map { it.priceCents })
        assertIs<UiAsync.Success<*>>(edited.actionState)
        assertNull(edited.notice)

        val created = loaded(small).reduce(PackSpec, EditorAction.Open(null))
            .saved(PackSpec, sent = big.copy(id = ""), saved = big)
        assertEquals(listOf("p1", "p2"), created.all.map { it.id })
        assertEquals("p2", created.selectedId)
        assertFalse(created.creatingNew)

        // Guardar el primero de una lista vacía.
        val first = loaded().reduce(PackSpec, EditorAction.Open(null)).saved(PackSpec, small.copy(id = ""), small)
        assertEquals(listOf("p1"), first.all.map { it.id })
    }

    @Test
    fun `a field the server kept is reported instead of calling it saved`() {
        val cleared = big.copy(description = null)
        val editor = loaded(small, big).reduce(PackSpec, EditorAction.Open("p2")).saved(PackSpec, sent = cleared, saved = big)
        assertEquals("El servidor conservó el valor anterior de la descripción: vaciar ese campo todavía no se guarda.", editor.notice)
        // La ficha enseña lo que de verdad hay en el servidor.
        assertEquals("El que más compensa", editor.draft.description)

        assertNull(keptByServerNotice(emptyList()))
        assertTrue(keptByServerNotice(listOf("la pantalla", "el momento", "el plan mínimo"))!!.contains("la pantalla, el momento y el plan mínimo"))
    }

    // ---------- packs ----------

    @Test
    fun `a pack needs a code when new, a name, tickets and a price`() {
        val valid = PackDraft(code = "pack_900", name = "900 OT", ticketAmount = "900", price = "7,99")
        assertNull(PackSpec.problem(valid, isNew = true, others = listOf(small)))
        assertEquals("El pack necesita un código.", PackSpec.problem(valid.copy(code = ""), true, emptyList()))
        assertEquals("Ya existe un pack con ese código.", PackSpec.problem(valid.copy(code = "pack_500"), true, listOf(small)))
        assertEquals("El pack necesita un nombre.", PackSpec.problem(valid.copy(name = " "), false, emptyList()))
        assertEquals("Un pack debe entregar al menos un OposiTicket.", PackSpec.problem(valid.copy(ticketAmount = "0"), false, emptyList()))
        assertNotNull(PackSpec.problem(valid.copy(price = ""), false, emptyList()))
        assertNotNull(PackSpec.problem(valid.copy(price = "7,999"), false, emptyList()))
        // Gratis es un precio válido; negativo no.
        assertNull(PackSpec.problem(valid.copy(price = "0"), false, emptyList()))
    }

    @Test
    fun `the pack that is sent keeps its id, code and currency`() {
        val sent = assertNotNull(PackSpec.toEntity(PackSpec.toDraft(big).copy(code = "otro", name = " Grande ", description = " "), big.copy(currency = "USD")))
        assertEquals("p2", sent.id)
        assertEquals("pack_2600", sent.code)
        assertEquals("Grande", sent.name)
        assertNull(sent.description)
        assertEquals("USD", sent.currency)
        assertEquals("", assertNotNull(PackSpec.toEntity(PackDraft(code = " c ", name = "n", ticketAmount = "1", price = "1"), null)).id)
        assertEquals(listOf("500 OT", "4,99 EUR"), packRowSpec(small).meta)
    }

    // ---------- servicios ----------

    @Test
    fun `an active service needs a price in tickets or in money`() {
        val draft = ServiceDraft(code = "protector", name = "Protector", type = "protector_racha")
        assertNotNull(ServiceSpec.problem(draft, isNew = true, others = emptyList()))
        assertNull(ServiceSpec.problem(draft.copy(active = false), true, emptyList()))
        assertNull(ServiceSpec.problem(draft.copy(priceTickets = "30"), true, emptyList()))
        assertNull(ServiceSpec.problem(draft.copy(price = "1,99"), true, emptyList()))
        assertEquals("Elige el tipo de servicio.", ServiceSpec.problem(draft.copy(type = "inventado", priceTickets = "1"), true, emptyList()))
        assertNotNull(ServiceSpec.problem(draft.copy(priceTickets = "1,5"), true, emptyList()))
    }

    @Test
    fun `a service says its price in one line and reports the prices the server kept`() {
        val both = MarketService("s1", "c", "Servicio", serviceType = "potenciador", priceTickets = 30, priceCents = 199)
        assertEquals("30 OT · 1,99 EUR", servicePriceLabel(both))
        assertEquals("Sin precio", servicePriceLabel(both.copy(priceTickets = null, priceCents = null)))
        assertEquals(listOf("Potenciador", "30 OT · 1,99 EUR"), serviceRowSpec(both).meta)

        val sent = assertNotNull(ServiceSpec.toEntity(ServiceSpec.toDraft(both).copy(priceTickets = ""), both))
        assertNull(sent.priceTickets)
        assertEquals(listOf("el precio en OposiTickets"), ServiceSpec.keptByServer(sent, both))
        assertTrue(ServiceSpec.keptByServer(sent, sent).isEmpty())
    }

    @Test
    fun `every service type the database admits has a label`() {
        assertEquals(12, SERVICE_TYPES.size)
        assertTrue(SERVICE_TYPES.none { serviceTypeLabel(it) == it }, "a type is shown as its raw code")
    }

    // ---------- promociones ----------

    @Test
    fun `a promotion validates its code in upper case, its value and its window`() {
        val existing = MarketPromotion("x", "OTONO25", "Otoño", "porcentaje", 15.0)
        val valid = PromotionDraft(code = "invierno", name = "Invierno", discountType = "porcentaje", value = "12,5")
        assertNull(PromotionSpec.problem(valid, isNew = true, others = listOf(existing)))
        assertEquals("Ya existe una promoción con ese código.", PromotionSpec.problem(valid.copy(code = "otono25"), true, listOf(existing)))
        assertEquals("Elige el tipo de descuento.", PromotionSpec.problem(valid.copy(discountType = ""), true, emptyList()))
        assertNotNull(PromotionSpec.problem(valid.copy(value = "x"), true, emptyList()))
        assertEquals("El descuento no puede ser negativo.", PromotionSpec.problem(valid.copy(value = "-1"), true, emptyList()))
        assertNotNull(PromotionSpec.problem(valid.copy(discountType = "tickets_extra", value = "2,5"), true, emptyList()))
        assertNotNull(PromotionSpec.problem(valid.copy(validFrom = "01/11/2026"), true, emptyList()))
        assertNotNull(PromotionSpec.problem(valid.copy(validFrom = "2026-02-30"), true, emptyList()))
        assertEquals(
            "La fecha de fin no puede ser anterior a la de inicio.",
            PromotionSpec.problem(valid.copy(validFrom = "2026-11-10", validUntil = "2026-11-01"), true, emptyList()),
        )
        assertNull(PromotionSpec.problem(valid.copy(validFrom = "2026-11-01", validUntil = "2026-11-01"), true, emptyList()))
    }

    @Test
    fun `dates become whole days in UTC and an untouched date keeps its stored instant`() {
        val sent = assertNotNull(PromotionSpec.toEntity(PromotionDraft("otono", "Otoño", "porcentaje", "15", "2026-11-01", "2026-11-30"), null))
        assertEquals("OTONO", sent.code)
        assertEquals("2026-11-01T00:00:00Z", sent.validFrom)
        assertEquals("2026-11-30T23:59:59Z", sent.validUntil)

        val stored = MarketPromotion("id", "OTONO", "Otoño", "porcentaje", 15.0, validFrom = "2026-11-01T08:30:00+00:00", validUntil = "2026-11-30T20:00:00+00:00", uses = 7)
        val draft = PromotionSpec.toDraft(stored)
        assertEquals("2026-11-01" to "2026-11-30", draft.validFrom to draft.validUntil)
        val unchanged = assertNotNull(PromotionSpec.toEntity(draft.copy(name = "Otoño 2"), stored))
        assertEquals(stored.validFrom, unchanged.validFrom)
        assertEquals(stored.validUntil, unchanged.validUntil)

        val cleared = assertNotNull(PromotionSpec.toEntity(draft.copy(validUntil = ""), stored))
        assertNull(cleared.validUntil)
        assertEquals(listOf("la fecha de fin"), PromotionSpec.keptByServer(cleared, stored))
    }

    @Test
    fun `a promotion is read with its unit and its window`() {
        assertEquals("15 %", promotionValueLabel("porcentaje", 15.0))
        assertEquals("12,5 %", promotionValueLabel("porcentaje", 12.5))
        assertEquals("+50 OT", promotionValueLabel("tickets_extra", 50.0))
        assertEquals("5", promotionValueLabel("cantidad", 5.0))
        val promo = MarketPromotion("id", "A", "", "porcentaje", 15.0, active = false, validUntil = "2026-11-30T23:59:59Z")
        assertEquals("Hasta 2026-11-30", promotionWindowLabel(promo))
        assertEquals("Sin fechas", promotionWindowLabel(promo.copy(validUntil = null)))
        assertEquals("Promoción sin nombre", promotionRowSpec(promo).title)
        assertEquals("Inactiva", promotionRowSpec(promo).status)
    }

    @Test
    fun `a promotion use is told in words`() {
        val use = MarketPromotionUse("u1", "c1", 250, "2026-10-09T14:32:10Z", generatedPurchase = true, renewedAfter = null)
        assertEquals("2026-10-09 14:32 UTC", promotionUseTitle(use))
        assertEquals("Generó compra · Renovación por ver", promotionUseOutcome(use))
        assertEquals("2,50 EUR", promotionUseDiscount(use))
        assertEquals("Sin compra · No renovó", promotionUseOutcome(use.copy(generatedPurchase = false, renewedAfter = false)))
        assertEquals("Sin importe", promotionUseDiscount(use.copy(discountCents = null)))
        assertEquals("raro", formatUseInstant("raro"))
        assertEquals("12.345", formatCount(12345))
    }

    // ---------- pop-ups ----------

    @Test
    fun `an active rule needs a minimum frequency`() {
        val valid = PopupDraft(code = "sin_tickets", trigger = "sin_tickets", title = "Sin tickets", message = "Compra un pack")
        assertNull(PopupSpec.problem(valid, isNew = true, others = emptyList()))
        assertNotNull(PopupSpec.problem(valid.copy(frequencyHours = ""), true, emptyList()))
        assertNull(PopupSpec.problem(valid.copy(frequencyHours = "", active = false), true, emptyList()))
        assertEquals("La regla necesita un evento disparador.", PopupSpec.problem(valid.copy(trigger = ""), true, emptyList()))
        assertEquals("La regla necesita un título.", PopupSpec.problem(valid.copy(title = ""), true, emptyList()))
        assertEquals("La regla necesita un mensaje.", PopupSpec.problem(valid.copy(message = ""), true, emptyList()))
        assertNotNull(PopupSpec.problem(valid.copy(moment = "nunca"), true, emptyList()))
        assertNotNull(PopupSpec.problem(valid.copy(targetExamType = "test"), true, emptyList()))
        assertNotNull(PopupSpec.problem(valid.copy(priority = "-1"), true, emptyList()))
    }

    @Test
    fun `a rule sends blanks as absent and reports the ones the server kept`() {
        val stored = MarketPopupRule("r1", "c", "sin_tickets", "T", "M", primaryCta = "Ver", screen = "market", moment = "bloqueo", minimumFrequencyHours = 24, priority = 50)
        val draft = PopupSpec.toDraft(stored)
        assertEquals("24" to "50", draft.frequencyHours to draft.priority)

        val sent = assertNotNull(PopupSpec.toEntity(draft.copy(primaryCta = " ", screen = "", moment = null), stored))
        assertNull(sent.primaryCta)
        assertNull(sent.screen)
        assertEquals(listOf("el botón principal", "la pantalla", "el momento"), PopupSpec.keptByServer(sent, stored))

        assertEquals(listOf("sin_tickets", "Cada 24 h"), popupRowSpec(stored).meta)
        assertEquals("Cualquier momento", popupMomentLabel(null))
        assertEquals("Sin límite", popupFrequencyLabel(null))
        // Los valores por defecto de una regla nueva son los de la tabla.
        assertEquals("24" to "50", PopupDraft().frequencyHours to PopupDraft().priority)
    }
}
