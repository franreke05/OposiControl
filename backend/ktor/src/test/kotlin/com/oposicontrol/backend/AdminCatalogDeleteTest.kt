package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Borrado de productos y tarifas del catalogo comercial.
 *
 * Lo que se protege: las suscripciones, las compras y el estado premium guardan el CODIGO del producto como texto, sin
 * clave ajena. Borrar un producto que alguien tiene contratado le dejaria sin plan, asi que con una sola referencia el
 * borrado se rechaza ANTES de tocar nada.
 */
class AdminCatalogDeleteTest {
    private val productoId = "11111111-1111-4111-8111-111111111111"
    private val tarifaId = "22222222-2222-4222-8222-222222222222"
    private val producto = """[{"id":"$productoId","codigo":"pack_500","tipo_producto":"pack_tickets","nombre":"500 OposiTickets"}]"""
    private val tarifa =
        """[{"id":"$tarifaId","producto_id":"$productoId","periodo_facturacion":"mensual","fase_precio":"first_year","precio_centimos":299,"moneda":"EUR"}]"""

    private fun gateway(
        sent: MutableList<HttpRequest>,
        productos: String = producto,
        tarifas: String = tarifa,
        suscripciones: Int = 0,
        compras: Int = 0,
        premium: Int = 0,
    ): AdminV1Gateway = AdminV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient(sent::add) { uri ->
            fun count(n: Int) = V1StubResponse(200, "[]", contentRange = if (n == 0) "*/0" else "0-${n - 1}/$n")
            when {
                "/rest/v1/suscripciones" in uri -> count(suscripciones)
                "/rest/v1/compras_usuario" in uri -> count(compras)
                "/rest/v1/estado_premium_usuario" in uri -> count(premium)
                "/rest/v1/tarifas_producto_comercial" in uri -> V1StubResponse(200, tarifas)
                "/rest/v1/productos_comerciales" in uri -> V1StubResponse(200, productos)
                else -> V1StubResponse(201, "")
            }
        },
    )

    private fun List<HttpRequest>.deletes() = filter { it.method() == "DELETE" }.map { it.uri().toString().substringAfter("/rest/v1/") }

    private fun List<HttpRequest>.audits() = filter { it.method() == "POST" && "auditoria_log" in it.uri().toString() }.map(::readRequestBody)

    @Test
    fun aProductNobodyUsesIsDeletedWithItsTariffsAndAudited() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        gateway(sent).deleteProductoComercial(productoId)

        // Primero las tarifas (la clave ajena del producto es RESTRICT), despues el producto.
        assertEquals(
            listOf("tarifas_producto_comercial?producto_id=eq.$productoId", "productos_comerciales?id=eq.$productoId"),
            sent.deletes(),
        )
        val audit = sent.audits().single()
        assertTrue("producto_comercial_eliminado" in audit && "pack_500" in audit, audit)
    }

    @Test
    fun aProductWithASubscriptionAPurchaseOrAPremiumStateIsNeverDeleted() {
        listOf(Triple(1, 0, 0), Triple(0, 2, 0), Triple(0, 0, 1)).forEach { (suscripciones, compras, premium) ->
            val sent = CopyOnWriteArrayList<HttpRequest>()
            val error = assertFailsWith<BackendDomainException> {
                gateway(sent, suscripciones = suscripciones, compras = compras, premium = premium).deleteProductoComercial(productoId)
            }
            assertEquals("PRODUCT_IN_USE", error.errorCode)
            assertEquals(HttpStatusCode.Conflict, error.status)
            assertTrue(sent.deletes().isEmpty(), "en uso: no se borra ni una tarifa")
            assertTrue(sent.audits().isEmpty())
        }
    }

    @Test
    fun theUsageIsLookedUpByTheProductCode() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        gateway(sent).deleteProductoComercial(productoId)
        val counted = sent.map { it.uri().toString() }.filter { "producto_codigo=eq.pack_500" in it }
        assertEquals(3, counted.size, "suscripciones, compras y estado premium: $counted")
    }

    @Test
    fun anUnknownProductIsNotFoundAndAnInvalidIdNeverLeaves() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val missing = assertFailsWith<BackendDomainException> { gateway(sent, productos = "[]").deleteProductoComercial(productoId) }
        assertEquals("CATALOG_PRODUCT_NOT_FOUND", missing.errorCode)
        assertTrue(sent.deletes().isEmpty())

        val untouched = CopyOnWriteArrayList<HttpRequest>()
        assertFailsWith<IllegalArgumentException> { gateway(untouched).deleteProductoComercial("1 or 1=1") }
        assertTrue(untouched.isEmpty())
    }

    @Test
    fun aTariffIsDeletedOnlyWithinItsProductAndAudited() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        gateway(sent).deleteTarifa(productoId, tarifaId)

        assertEquals(listOf("tarifas_producto_comercial?id=eq.$tarifaId&producto_id=eq.$productoId"), sent.deletes())
        val audit = sent.audits().single()
        assertTrue("tarifa_producto_eliminada" in audit && "first_year" in audit, audit)
    }

    @Test
    fun aTariffThatIsNotOfThatProductIsNotFound() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val error = assertFailsWith<BackendDomainException> { gateway(sent, tarifas = "[]").deleteTarifa(productoId, tarifaId) }
        assertEquals("TARIFF_NOT_FOUND", error.errorCode)
        assertTrue(sent.deletes().isEmpty())
    }
}
