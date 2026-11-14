package com.oposicontrol.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guardados de OposiMarket (packs, servicios, promociones y reglas de aviso).
 *
 * Lo que se protege: el upsert es `merge-duplicates`, asi que un campo opcional que NO viaja conserva su valor anterior.
 * Vaciar la caducidad de una promocion o el precio de un servicio respondia «guardado» y no cambiaba nada. Aqui se
 * comprueba lo que sale por el cable: los opcionales vaciados viajan como `null` explicito.
 */
class AdminMarketUpsertTest {
    private val id = "11111111-1111-4111-8111-111111111111"

    private fun gateway(sent: MutableList<HttpRequest>, usos: Int = 0): AdminV1Gateway = AdminV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient(sent::add) { uri ->
            when {
                "/rest/v1/usos_promocion_usuario" in uri -> V1StubResponse(200, "[]", contentRange = if (usos == 0) "*/0" else "0-${usos - 1}/$usos")
                "/rest/v1/auditoria_log" in uri -> V1StubResponse(201, "")
                "/rest/v1/promociones" in uri -> V1StubResponse(200, """[{"id":"$id","codigo":"VERANO","nombre":"Verano","tipo_descuento":"porcentaje","valor":10}]""")
                else -> V1StubResponse(200, """[{"id":"$id","codigo":"c","nombre":"n"}]""")
            }
        },
    )

    private fun List<HttpRequest>.written(table: String): JsonObject =
        Json.parseToJsonElement(readRequestBody(single { it.method() == "POST" && "/rest/v1/$table" in it.uri().toString() })).jsonArray.single().jsonObject

    @Test
    fun clearingThePromotionWindowTravelsAsExplicitNulls() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        gateway(sent).upsertPromotion(AdminPromotionDto(id = id, codigo = "verano", nombre = "Verano", tipoDescuento = "porcentaje", valor = 10.0))

        val row = sent.written("promociones")
        assertEquals(JsonNull, row["valido_desde"], "sin esto la fecha anterior se quedaba puesta")
        assertEquals(JsonNull, row["valido_hasta"])
        assertFalse("usos" in row, "el recuento de usos es de lectura: nunca se escribe")
        assertEquals("VERANO", row["codigo"]!!.jsonPrimitive.content)
    }

    @Test
    fun aSavedPromotionComesBackWithItsUseCount() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val saved = gateway(sent, usos = 3).upsertPromotion(AdminPromotionDto(id = id, codigo = "verano", nombre = "Verano", tipoDescuento = "porcentaje", valor = 10.0))
        assertEquals(3, saved.usos, "antes volvia sin el y el panel dejaba de ensenar los usos")
    }

    @Test
    fun clearingAServicePriceOrDescriptionTravelsAsExplicitNulls() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        gateway(sent).upsertService(AdminServiceDto(id = id, codigo = "tutoria", nombre = "Tutoría", tipoServicio = "tutoria", precioTickets = 50))

        val row = sent.written("servicios")
        assertEquals(JsonNull, row["precio_centimos"])
        assertEquals(JsonNull, row["descripcion"])
        assertEquals("50", row["precio_tickets"]!!.jsonPrimitive.content, "lo que si lleva valor no se toca")
    }

    @Test
    fun theF28ShowcaseAndConsumableFieldsTravelAndClearedOnesAreExplicitNulls() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        gateway(sent).upsertService(
            AdminServiceDto(
                id = id, codigo = "potenciador_x3", nombre = "x3", tipoServicio = "potenciador", precioTickets = 60,
                visible = false, posicion = 50, seccion = " potenciadores ", usos = 1, duracionHoras = 24, multiplicador = 3.0,
            ),
        )
        val row = sent.written("servicios")
        assertEquals("false", row["visible"]!!.jsonPrimitive.content)
        assertEquals("50", row["posicion"]!!.jsonPrimitive.content)
        assertEquals("POTENCIADORES", row["seccion"]!!.jsonPrimitive.content, "normalised to the CHECK code")
        assertEquals("1|24|3.0", listOf("usos", "duracion_horas", "multiplicador").joinToString("|") { row[it]!!.jsonPrimitive.content })

        sent.clear()
        gateway(sent).upsertService(AdminServiceDto(id = id, codigo = "doble", nombre = "Doble", tipoServicio = "doble_oportunidad", precioTickets = 30))
        val cleared = sent.written("servicios")
        listOf("seccion", "usos", "duracion_horas", "multiplicador").forEach { assertEquals(JsonNull, cleared[it], it) }
        // a pre-F28 panel does not send them: they must NOT travel, so the merge upsert keeps the stored visibility and order
        assertFalse("visible" in cleared, "an absent visible would otherwise reset a hidden service to visible")
        assertFalse("posicion" in cleared, "an absent posicion would otherwise reset the order to 0")
    }

    @Test
    fun theF28LimitsAreRefusedWithAMessageBeforeAnyRequest() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val ok = AdminServiceDto(id = id, codigo = "p", nombre = "P", tipoServicio = "potenciador", precioTickets = 30, multiplicador = 2.0)
        mapOf(
            "SERVICE_SECTION_INVALID" to ok.copy(seccion = "OFERTAS"),
            "SERVICE_POSITION_NEGATIVE" to ok.copy(posicion = -1),
            "SERVICE_USES_OUT_OF_RANGE" to ok.copy(usos = 0),
            "SERVICE_DURATION_OUT_OF_RANGE" to ok.copy(duracionHoras = 9000),
            "SERVICE_MULTIPLIER_OUT_OF_RANGE" to ok.copy(multiplicador = 0.5),
        ).forEach { (code, dto) ->
            assertEquals(code, assertFailsWith<BackendDomainException> { gateway(sent).upsertService(dto) }.errorCode, code)
        }
        ok.copy(multiplicador = null).validated() // the panel asks for it; the server keeps accepting older saves (x1 in the reto)
        assertTrue(sent.isEmpty(), "nothing of this leaves the server")
    }

    @Test
    fun clearingAPackDescriptionTravelsAsAnExplicitNull() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        gateway(sent).upsertTicketPack(AdminTicketPackDto(id = id, codigo = "pack_500", nombre = "500", cantidadTickets = 500, precioCentimos = 499))
        assertEquals(JsonNull, sent.written("paquetes_opositickets")["descripcion"])
    }

    @Test
    fun aPopupRuleSentBackToAnyMomentScreenOrPlanIsWrittenThatWay() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        gateway(sent).upsertPopupRule(
            AdminPopupRuleDto(id = id, codigo = "sin_tickets", eventoDisparador = "sin_tickets", titulo = "t", mensaje = "m", activo = false),
        )

        val row = sent.written("reglas_popup_comercial")
        listOf("cta_principal", "cta_secundario", "momento", "pantalla", "requiere_plan_minimo", "tipo_examen_objetivo").forEach {
            assertEquals(JsonNull, row[it], it)
        }
        // NOT NULL en la base: vaciarla la devuelve a su valor por defecto en vez de conservar en silencio la anterior.
        assertEquals(POPUP_DEFAULT_FREQUENCY_HOURS.toString(), row["frecuencia_minima_horas"]!!.jsonPrimitive.content)
    }

    @Test
    fun aFrequencyThatWasTypedIsKeptAsTyped() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        gateway(sent).upsertPopupRule(
            AdminPopupRuleDto(id = id, codigo = "c", eventoDisparador = "e", titulo = "t", mensaje = "m", frecuenciaMinimaHoras = 72, momento = "home"),
        )
        val row = sent.written("reglas_popup_comercial")
        assertEquals("72", row["frecuencia_minima_horas"]!!.jsonPrimitive.content)
        assertEquals("home", row["momento"]!!.jsonPrimitive.content)
    }

    @Test
    fun valuesTheDatabaseWouldRejectAreRefusedWithAMessageBeforeAnyRequest() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val promo = AdminPromotionDto(id = id, codigo = "c", nombre = "n", tipoDescuento = "porcentaje", valor = 10.0)
        assertEquals("PROMO_TYPE_INVALID", assertFailsWith<BackendDomainException> { gateway(sent).upsertPromotion(promo.copy(tipoDescuento = "regalo")) }.errorCode)
        assertEquals("PROMO_PERCENT_OVER_100", assertFailsWith<BackendDomainException> { gateway(sent).upsertPromotion(promo.copy(valor = 150.0)) }.errorCode)
        // Una cantidad fija si puede pasar de 100.
        promo.copy(tipoDescuento = "cantidad", valor = 500.0).validated()

        val rule = AdminPopupRuleDto(id = id, codigo = "c", eventoDisparador = "e", titulo = "t", mensaje = "m", frecuenciaMinimaHoras = 24)
        assertEquals("POPUP_MOMENT_INVALID", assertFailsWith<BackendDomainException> { gateway(sent).upsertPopupRule(rule.copy(momento = "siempre")) }.errorCode)
        assertTrue(sent.isEmpty(), "nada de esto llega a salir")
    }
}
