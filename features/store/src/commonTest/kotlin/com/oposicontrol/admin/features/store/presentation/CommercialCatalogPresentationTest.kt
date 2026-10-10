package com.oposicontrol.admin.features.store.presentation

import com.oposicontrol.admin.core.ui.components.StatusTone
import com.oposicontrol.admin.core.ui.components.WorkspaceSaveTone
import com.oposicontrol.admin.domain.model.CommercialProduct
import com.oposicontrol.admin.domain.model.CommercialTariff
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Las reglas con las que el Catálogo valida y lo que dice de cada producto, sin Compose. */
class CommercialCatalogPresentationTest {
    private val validPack = ProductDraft(code = "pack_500", name = "500 OT", type = "pack_tickets", price = "4,99", ticketAmount = "500")

    @Test
    fun `a valid pack has no problem`() {
        assertNull(productProblem(validPack, isNew = true, existingCodes = emptySet()))
    }

    @Test
    fun `the code is only checked when the product is new`() {
        assertEquals("El producto necesita un código.", productProblem(validPack.copy(code = " "), isNew = true, existingCodes = emptySet()))
        assertNotNull(productProblem(validPack.copy(code = "pack 500"), isNew = true, existingCodes = emptySet()))
        assertEquals("Ya existe un producto con ese código.", productProblem(validPack, isNew = true, existingCodes = setOf("pack_500")))
        // Un producto guardado conserva su código: no se valida ni contra sí mismo.
        assertNull(productProblem(validPack.copy(code = "código raro"), isNew = false, existingCodes = setOf("pack_500")))
    }

    @Test
    fun `each product type asks for what it needs`() {
        assertEquals("Elige el tipo de producto.", productProblem(validPack.copy(type = ""), isNew = false, existingCodes = emptySet()))
        assertEquals(
            "Un pack de tickets necesita la cantidad de OposiTickets que entrega.",
            productProblem(validPack.copy(ticketAmount = ""), isNew = false, existingCodes = emptySet()),
        )
        assertEquals(
            "Un pack debe entregar al menos un OposiTicket.",
            productProblem(validPack.copy(ticketAmount = "0"), isNew = false, existingCodes = emptySet()),
        )
        val subscription = ProductDraft(code = "sub", name = "Sub", type = "suscripcion")
        assertEquals("Una suscripción necesita su plan.", productProblem(subscription, isNew = false, existingCodes = emptySet()))
        // El precio de una suscripción lo fijan las tarifas: en blanco es válido.
        assertNull(productProblem(subscription.copy(planId = "plan"), isNew = false, existingCodes = emptySet()))
    }

    @Test
    fun `a price with three decimals is a typing mistake, not something to round`() {
        assertNotNull(productProblem(validPack.copy(price = "4,999"), isNew = false, existingCodes = emptySet()))
        assertNotNull(productProblem(validPack.copy(price = "-1"), isNew = false, existingCodes = emptySet()))
    }

    @Test
    fun `highlighted requires visible, as the server does`() {
        assertEquals(
            "Un producto destacado tiene que ser visible.",
            productProblem(validPack.copy(highlighted = true, visible = false), isNew = false, existingCodes = emptySet()),
        )
    }

    @Test
    fun `the draft becomes the product that is sent, keeping what the form does not edit`() {
        val base = CommercialProduct(
            id = "id-1",
            code = "pack_500",
            productType = "pack_tickets",
            name = "500 OT",
            currency = "USD",
            tariffs = listOf(CommercialTariff("t1", "id-1", "unitario", "standard", 499)),
        )
        val product = assertNotNull(validPack.copy(code = "otro", name = "  500 OposiTickets  ", description = "  ", price = "5").toProduct(base))

        assertEquals("id-1", product.id)
        assertEquals("pack_500", product.code) // el código no se cambia
        assertEquals("500 OposiTickets", product.name)
        assertNull(product.description)
        assertEquals(500, product.priceCents)
        assertEquals("USD", product.currency)
        assertEquals(1, product.tariffs.size)
        assertEquals("", assertNotNull(validPack.toProduct(null)).id)
        assertNull(validPack.copy(position = "x").toProduct(null))
    }

    @Test
    fun `a tariff needs period, phase and price, and its natural key cannot repeat`() {
        val existing = listOf(CommercialTariff("t1", "p", "mensual", "first_year", 299))
        assertEquals("Elige el periodo de facturación.", tariffProblem(TariffDraft(), existing))
        assertEquals("Elige la fase de precio.", tariffProblem(TariffDraft(period = "mensual"), existing))
        assertNotNull(tariffProblem(TariffDraft(period = "mensual", phase = "first_year", price = "1"), existing))
        assertEquals("La tarifa necesita un precio.", tariffProblem(TariffDraft(period = "mensual", phase = "renewal"), existing))
        assertNull(tariffProblem(TariffDraft(period = "mensual", phase = "renewal", price = "4,99"), existing))
        // Al editar, la propia tarifa no cuenta como repetida.
        assertNull(tariffProblem(TariffDraft(id = "t1", period = "mensual", phase = "first_year", price = "3"), emptyList()))
    }

    @Test
    fun `the row says what tells one product from another in two facts`() {
        val pack = CommercialProduct("1", "pack_500", "pack_tickets", "500 OT", priceCents = 499, ticketAmount = 500)
        assertEquals(listOf("500 OT", "4,99 EUR"), productRowSpec(pack).meta)

        val subscription = CommercialProduct("2", "sub", "suscripcion", "Sub", tariffs = listOf(CommercialTariff("t", "2", "mensual", "standard", 299)))
        assertEquals(listOf("Suscripción", "Por tarifa"), productRowSpec(subscription).meta)

        val bare = CommercialProduct("3", "srv", "servicio", "", active = false)
        assertEquals("Producto sin nombre", productRowSpec(bare).title)
        assertEquals(listOf("Servicio", "Sin precio"), productRowSpec(bare).meta)
        assertEquals("Inactivo", productRowSpec(bare).status)
    }

    @Test
    fun `status is said with a word and a tone, never with colour alone`() {
        assertEquals(CommerceStatus("Inactivo", StatusTone.NEGATIVE), commerceStatus(active = false, visible = true, highlighted = true))
        assertEquals(CommerceStatus("Oculto", StatusTone.ATTENTION), commerceStatus(active = true, visible = false))
        assertEquals(CommerceStatus("Destacado", StatusTone.POSITIVE), commerceStatus(active = true, visible = true, highlighted = true))
        assertEquals(CommerceStatus("Activo", StatusTone.POSITIVE), commerceStatus(active = true))
    }

    @Test
    fun `the catalogue window pages the downloaded list and survives a shrinking list`() {
        val all = (1..30).toList()
        val second = catalogWindow(all, pageIndex = 1, pageSize = 12)
        assertEquals((13..24).toList(), second.items)
        assertEquals(3, second.pageCount)
        assertEquals(12, second.offset)
        assertEquals(30, second.total)

        // Estaba en la página 3 y el filtro dejó cinco filas: cae en la última página válida, no en una vacía.
        val shrunk = catalogWindow((1..5).toList(), pageIndex = 2, pageSize = 12)
        assertEquals(0, shrunk.pageIndex)
        assertEquals(5, shrunk.items.size)

        val empty = catalogWindow(emptyList<Int>(), pageIndex = 4)
        assertEquals(1, empty.pageCount)
        assertTrue(empty.items.isEmpty())
    }

    @Test
    fun `the save bar only offers what can be done`() {
        val clean = editorSaveStatus(isNew = false, dirty = false, problem = null, saving = false, createLabel = "Crear")
        assertFalse(clean.canSave)
        assertFalse(clean.canDiscard)
        assertEquals("Guardar", clean.primaryLabel)

        val dirty = editorSaveStatus(isNew = false, dirty = true, problem = null, saving = false, createLabel = "Crear")
        assertTrue(dirty.canSave && dirty.canDiscard && dirty.prominent)

        val invalid = editorSaveStatus(isNew = false, dirty = true, problem = "Falta el nombre.", saving = false, createLabel = "Crear")
        assertFalse(invalid.canSave)
        assertEquals("Falta el nombre.", invalid.message)
        assertEquals(WorkspaceSaveTone.WARNING, invalid.tone)

        val creating = editorSaveStatus(isNew = true, dirty = true, problem = null, saving = false, createLabel = "Crear producto")
        assertTrue(creating.canSave)
        assertEquals("Crear producto", creating.primaryLabel)

        val saving = editorSaveStatus(isNew = false, dirty = true, problem = null, saving = true, createLabel = "Crear")
        assertFalse(saving.canSave || saving.canDiscard)
    }

    @Test
    fun `what happened on the last save is said in the bar, which never scrolls out of view`() {
        // Un fallo manda sobre «cambios sin guardar»: se ve aunque el formulario sea largo, y deja reintentar.
        val failed = editorSaveStatus(isNew = false, dirty = true, problem = null, saving = false, createLabel = "Crear", failure = "No se pudo contactar con el servidor.")
        assertEquals("No se pudo guardar.", failed.message)
        assertEquals("No se pudo contactar con el servidor.", failed.detail)
        assertEquals(WorkspaceSaveTone.DANGER, failed.tone)
        assertTrue(failed.prominent && failed.canSave && failed.canDiscard)

        val saved = editorSaveStatus(isNew = false, dirty = false, problem = null, saving = false, createLabel = "Crear", savedMessage = "Producto guardado.")
        assertEquals("Producto guardado.", saved.message)
        assertEquals(WorkspaceSaveTone.SUCCESS, saved.tone)
        assertFalse(saved.canSave)

        // Si el servidor conservó un campo, eso sustituye al «guardado»: los dos a la vez se contradicen.
        val kept = editorSaveStatus(
            isNew = false, dirty = false, problem = null, saving = false, createLabel = "Crear",
            savedMessage = "Producto guardado.", notice = "El servidor conservó el valor anterior de la descripción.",
        )
        assertEquals("Guardado con una salvedad.", kept.message)
        assertEquals(WorkspaceSaveTone.WARNING, kept.tone)
        assertTrue(kept.prominent)

        // En cuanto se vuelve a editar, lo del guardado anterior deja de decirse.
        val editing = editorSaveStatus(isNew = false, dirty = true, problem = null, saving = false, createLabel = "Crear", savedMessage = "Producto guardado.")
        assertEquals("Cambios sin guardar.", editing.message)
    }

    @Test
    fun `non negative integers are parsed strictly`() {
        assertEquals(12, parseNonNegativeInt(" 12 "))
        assertEquals(0, parseNonNegativeInt("0"))
        assertNull(parseNonNegativeInt("-1"))
        assertNull(parseNonNegativeInt("1,5"))
        assertNull(parseNonNegativeInt(""))
        assertNull(parseNonNegativeInt("12345678901"))
    }
}
