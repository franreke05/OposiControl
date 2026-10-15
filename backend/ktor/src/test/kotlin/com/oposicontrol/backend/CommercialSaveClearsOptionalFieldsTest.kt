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

/**
 * DEBT-089 (productos y planes; OposiMarket ya se corrigio en AdminMarketUpsertTest): el upsert es `merge-duplicates` y
 * `sbJson` omite los nulos, asi que vaciar un opcional YA guardado respondia 200 con el valor anterior. Guardar con valor
 * y despues vaciar: el segundo guardado tiene que llevar `null` EXPLICITO en cada columna anulable.
 */
class CommercialSaveClearsOptionalFieldsTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val plan = "22222222-2222-4222-8222-222222222222"

    private fun gateway(sent: MutableList<HttpRequest>): AdminV1Gateway = AdminV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient(sent::add) { uri ->
            when {
                "/rest/v1/auditoria_log" in uri -> V1StubResponse(201, "")
                "/rest/v1/tarifas_producto_comercial" in uri -> V1StubResponse(200, "[]")
                "/rest/v1/beneficios_plan" in uri -> V1StubResponse(200, "[]")
                else -> V1StubResponse(200, """[{"id":"$id","codigo":"c","nombre":"n","tipo_producto":"pack","nivel":1}]""")
            }
        },
    )

    private fun List<HttpRequest>.writes(table: String): List<JsonObject> =
        filter { it.method() == "POST" && "/rest/v1/$table" in it.uri().toString() }
            .map { Json.parseToJsonElement(readRequestBody(it)).jsonArray.single().jsonObject }

    @Test
    fun aProductSavedWithOptionalsAndThenClearedSendsExplicitNulls() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val g = gateway(sent)
        val full = AdminProductoComercialDto(
            id = id, codigo = "pack_100", tipoProducto = "pack", nombre = "Pack 100", descripcion = "Cien tickets",
            planId = plan, precioCentimos = 199, cantidadTickets = 100, etiquetaPromocional = "Mejor valor",
        )
        g.upsertProductoComercial(full)
        g.upsertProductoComercial(full.copy(descripcion = null, planId = null, precioCentimos = null, cantidadTickets = null, etiquetaPromocional = null))

        val (set, cleared) = sent.writes("productos_comerciales")
        assertEquals("Cien tickets", set["descripcion"]!!.jsonPrimitive.content)
        assertEquals("100", set["cantidad_tickets"]!!.jsonPrimitive.content)
        listOf("descripcion", "plan_id", "precio_centimos", "cantidad_tickets", "etiqueta_promocional").forEach {
            assertEquals(JsonNull, cleared[it], "$it vaciado tiene que viajar como null")
        }
        assertEquals("pack_100", cleared["codigo"]!!.jsonPrimitive.content, "lo que lleva valor no se toca")
    }

    @Test
    fun aPlanSavedWithADescriptionAndThenClearedSendsAnExplicitNull() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val g = gateway(sent)
        val full = AdminPlanDto(id = id, codigo = "pro", nombre = "Pro", descripcion = "Todo incluido", nivel = 2)
        g.upsertPlan(full)
        g.upsertPlan(full.copy(descripcion = null))

        val (set, cleared) = sent.writes("planes_suscripcion")
        assertEquals("Todo incluido", set["descripcion"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, cleared["descripcion"])
        assertEquals("2", cleared["nivel"]!!.jsonPrimitive.content)
    }
}
