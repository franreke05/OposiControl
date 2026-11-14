package com.oposicontrol.admin.features.store.presentation

import com.oposicontrol.admin.domain.model.MarketService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** F28 (FA52): visible, posición, sección y metadatos de consumible en la ficha de un servicio, sin Compose ni red. */
class ServiceShowcaseSpecTest {
    private val stored = MarketService(
        "s1", "potenciador_x3", "Potenciador x3", serviceType = "potenciador", priceTickets = 60,
        visible = false, position = 50, section = "POTENCIADORES", uses = 1, durationHours = 24, multiplier = 2.5,
    )

    @Test
    fun `the draft round-trips every showcase field`() {
        val draft = ServiceSpec.toDraft(stored)
        assertEquals(ServiceDraft("potenciador_x3", "Potenciador x3", "", "potenciador", "60", "", true, false, "50", "POTENCIADORES", "1", "24", "2,5"), draft)
        assertEquals(stored, ServiceSpec.toEntity(draft, stored))
    }

    @Test
    fun `clearing the optional metadata sends nulls and the section falls back to the type`() {
        val sent = assertNotNull(ServiceSpec.toEntity(ServiceSpec.toDraft(stored).copy(section = "", uses = "", durationHours = "", multiplier = "", active = false), stored))
        assertNull(sent.section)
        assertNull(sent.uses)
        assertNull(sent.durationHours)
        assertNull(sent.multiplier)
        assertEquals("POTENCIADORES", serviceSectionForType(sent.serviceType))
        assertEquals("SERVICIOS", serviceSectionForType("modo_papel"))
        assertEquals(
            listOf("los usos", "la duración", "el multiplicador"),
            ServiceSpec.keptByServer(sent, stored),
            "if the server kept them the form says so instead of claiming a save",
        )
    }

    @Test
    fun `the limits match the database checks`() {
        val ok = ServiceSpec.toDraft(stored)
        assertNull(ServiceSpec.problem(ok, false, emptyList()))
        assertEquals("La posición debe ser un número entero no negativo.", ServiceSpec.problem(ok.copy(position = "-1"), false, emptyList()))
        assertEquals("La posición debe ser un número entero no negativo.", ServiceSpec.problem(ok.copy(position = ""), false, emptyList()))
        assertEquals("Elige una de las tres secciones.", ServiceSpec.problem(ok.copy(section = "OFERTAS"), false, emptyList()))
        assertEquals("Los usos por compra van de 1 a 100.", ServiceSpec.problem(ok.copy(uses = "0"), false, emptyList()))
        assertEquals("La duración va de 1 a 8760 horas.", ServiceSpec.problem(ok.copy(durationHours = "9000"), false, emptyList()))
        listOf("0,5", "11", "2,555", "x2", "1.2.3").forEach {
            assertEquals("El multiplicador va de 1 a 10, por ejemplo 2 o 2,5.", ServiceSpec.problem(ok.copy(multiplier = it), false, emptyList()), it)
        }
        assertEquals("Un potenciador activo necesita multiplicador.", ServiceSpec.problem(ok.copy(multiplier = ""), false, emptyList()))
        assertNull(ServiceSpec.problem(ok.copy(multiplier = "", active = false), false, emptyList()), "an inactive one may wait for it")
        assertEquals(4.0, parseMultiplier("4"))
        assertEquals(2.25, parseMultiplier("2,25"))
    }

    @Test
    fun `the list row and the facts say where it shows and what it multiplies`() {
        val row = serviceRowSpec(stored)
        assertEquals("Oculto", row.status, "active but hidden")
        assertEquals(listOf("Potenciador", "60 OT", "x2,5"), row.meta)
        val facts = serviceFacts(stored).associate { it.label to it.value }
        assertEquals("Potenciadores y protecciones", facts["Sección"])
        assertEquals("50", facts["Posición"])
        assertEquals("24 h", facts["Duración"])
        assertEquals("x2,5", facts["Multiplicador"])
        assertEquals("Servicios y accesos", serviceFacts(stored.copy(section = null, serviceType = "tutoria", multiplier = null)).associate { it.label to it.value }["Sección"])
        assertNotNull(serviceEffectText("protector_racha"))
        assertNull(serviceEffectText("modo_papel"), "modo papel has no effect in 1.3: the form warns")
    }
}
