package com.oposicontrol.backend

import com.oposicontrol.backend.openapi.OpenApiDocs
import com.oposicontrol.backend.openapi.SchemaChecker
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FA13 D7-22 `PUT /api/v1/admin/planes/{codigo}` y D7-19 `PUT /api/v1/admin/comercial/productos/{codigo}/tarifas/{periodo}/{fase}`
 * de punta a punta contra un PostgREST falso con estado que aplica `ON CONFLICT` como la base. El cable se valida contra
 * `openapi/commerce/commerce.yaml`. Tambien comprueba que las rutas POST que usa el cliente actual siguen dando su forma.
 */
class AdminCatalogWriteRoutesTest {

    private val docs = OpenApiDocs.locate()
    private val checker = SchemaChecker(docs)
    private val json = Json { ignoreUnknownKeys = true }

    private val adminUid = "11111111-aaaa-4aaa-8aaa-000000000001"
    private val userUid = "22222222-bbbb-4bbb-8bbb-000000000002"
    private val adminToken = "admin-token"
    private val userToken = "user-token"
    private val planBody = """{"nombre":"Premium","descripcion":"Todo incluido","nivel":2,"activo":true,"visible":true,"posicion":3}"""
    private val tariffBody = """{"precioCentimos":299,"moneda":"eur","activo":true,"visible":true}"""
    private val tariffPath = "/api/v1/admin/comercial/productos/PREMIUM_M/tarifas/mensual/first_year"

    private inner class Upstream {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val tables = mutableMapOf<String, MutableList<MutableMap<String, JsonElement>>>()
        val audits = mutableListOf<JsonObject>()
        var failAudit = false
        var failWrites = false
        private var seq = 0

        init {
            listOf("planes_suscripcion", "beneficios_plan", "productos_comerciales", "tarifas_producto_comercial").forEach { tables[it] = mutableListOf() }
        }

        fun id(): String = "eeeeeeee-0000-4000-8000-%012d".format(++seq)

        fun product(codigo: String): String {
            val id = id()
            tables.getValue("productos_comerciales") += mutableMapOf<String, JsonElement>(
                "id" to JsonPrimitive(id), "codigo" to JsonPrimitive(codigo), "tipo_producto" to JsonPrimitive("suscripcion"), "nombre" to JsonPrimitive("Producto"),
            )
            return id
        }

        val writes: List<String> get() = sent.filter { it.method() != "GET" }.map { "${it.method()} ${URLDecoder.decode(it.uri().toString(), Charsets.UTF_8)}" }

        private fun text(row: Map<String, JsonElement>, column: String): String? = (row[column] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

        fun respond(rawUri: String): V1StubResponse {
            val request = sent.last()
            val uri = URLDecoder.decode(rawUri, Charsets.UTF_8)
            val table = uri.substringAfter("/rest/v1/").substringBefore('?')
            val q = uri.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate { it.substringBefore('=') to it.substringAfter('=', "") }
            if (table == "auditoria_log") {
                if (failAudit) return V1StubResponse(500, """{"message":"audit boom"}""")
                audits += json.parseToJsonElement(readRequestBody(request)).jsonArray.single().jsonObject
                return V1StubResponse(201, "")
            }
            val rows = tables[table] ?: error("tabla inesperada: $uri")
            if (request.method() == "GET") {
                val found = rows.filter { r -> q.all { (k, v) -> k in setOf("select", "order", "limit") || text(r, k) == v.removePrefix("eq.") } }
                return V1StubResponse(200, JsonArray(found.map { JsonObject(it) }).toString())
            }
            check(request.method() == "POST") { "metodo inesperado: ${request.method()} $uri" }
            if (failWrites) return V1StubResponse(500, """{"message":"boom"}""")
            // `INSERT .. ON CONFLICT (<on_conflict>) DO UPDATE`: una sola sentencia, como en la base.
            val keys = q.getValue("on_conflict").split(',')
            check(request.headers().firstValue("Prefer").orElse("").contains("resolution=merge-duplicates")) { "upsert sin merge-duplicates" }
            val incoming = json.parseToJsonElement(readRequestBody(request)).jsonArray.single().jsonObject
            check("id" !in incoming) { "el id no viaja: lo pone la base" }
            val existing = rows.firstOrNull { r -> keys.all { text(r, it) == text(incoming, it) } }
            val saved = existing ?: mutableMapOf<String, JsonElement>("id" to JsonPrimitive(id()), "posicion" to JsonPrimitive(1)).also { rows += it }
            saved.putAll(incoming)
            return V1StubResponse(201, JsonArray(listOf(JsonObject(saved))).toString())
        }
    }

    private val upstream = Upstream()

    private fun Application.module() {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        install(Authentication) {
            bearer("gateway-auth") {
                authenticate { credentials ->
                    when (credentials.token) {
                        adminToken -> UserIdPrincipal(adminUid)
                        userToken -> UserIdPrincipal(userUid)
                        else -> null
                    }
                }
            }
        }
        val gateway = AdminV1Gateway.forTesting(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = V1StubHttpClient({ upstream.sent += it }, upstream::respond),
        )
        routing {
            authenticate("gateway-auth") {
                install(AdminAuthorizationPlugin) {
                    this.appEnv = "production"
                    this.hasAdminRole = { it == adminUid }
                }
                installAdminAuditActor()
                adminV1Routes(gateway)
            }
        }
    }

    private suspend fun ApplicationTestBuilder.put(path: String, body: String?, token: String? = adminToken): HttpResponse =
        client.put(path) {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            body?.let {
                contentType(ContentType.Application.Json)
                setBody(it)
            }
        }

    private fun obj(body: String): JsonObject = json.parseToJsonElement(body).jsonObject

    private fun conforms(contractId: String, status: HttpStatusCode, body: String) {
        val op = docs.operations().first { it.contractId == contractId }
        val response = docs.responses(op).getValue(status.value.toString())
        val schema = ((response.value as Map<*, *>)["content"] as Map<*, *>).let { (it["application/json"] as Map<*, *>)["schema"] }
        val problems = mutableListOf<String>()
        checker.validate(OpenApiDocs.Node(schema, response.file), json.parseToJsonElement(body), "$", problems)
        assertTrue(problems.isEmpty(), "$contractId $status does not conform to the frozen OpenAPI:\n${problems.joinToString("\n")}\n$body")
    }

    private suspend fun ApplicationTestBuilder.saved(contractId: String, path: String, body: String, expected: HttpStatusCode): JsonObject {
        val response = put(path, body)
        val text = response.bodyAsText()
        assertEquals(expected, response.status, "$path -> $text")
        conforms(contractId, expected, text)
        return obj(text)["data"]!!.jsonObject
    }

    private suspend fun ApplicationTestBuilder.rejected(contractId: String, path: String, body: String?, status: HttpStatusCode, code: String) {
        val writesBefore = upstream.writes.size
        val response = put(path, body)
        val envelope = obj(response.bodyAsText())
        assertEquals(status, response.status, "$path $body -> $envelope")
        assertEquals(code, envelope["code"]!!.jsonPrimitive.content, "$path $body")
        assertTrue(envelope["requestId"]!!.jsonPrimitive.content.isNotBlank())
        val op = docs.operations().first { it.contractId == contractId }
        assertTrue(status.value.toString() in docs.responses(op), "$contractId does not document $status")
        assertTrue(code in (op.op["x-error-codes"] as List<*>), "$contractId does not list $code")
        assertEquals(writesBefore, upstream.writes.size, "a rejected command wrote: ${upstream.writes.drop(writesBefore)}")
        assertTrue(upstream.audits.isEmpty(), "a rejected command left an audit row")
    }

    // ================================================================== 401 / 403

    @Test
    fun bothCommandsRejectAnUnauthenticatedCallerAndANonAdminBeforeReadingAnything() = testApplication {
        application { module() }
        listOf("/api/v1/admin/planes/premium" to planBody, tariffPath to tariffBody).forEach { (path, body) ->
            assertEquals(HttpStatusCode.Unauthorized, put(path, body, token = null).status, path)
            val forbidden = put(path, body, token = userToken)
            assertEquals(HttpStatusCode.Forbidden, forbidden.status, path)
            assertEquals("FORBIDDEN", obj(forbidden.bodyAsText())["code"]!!.jsonPrimitive.content)
        }
        assertTrue(upstream.sent.isEmpty() && upstream.audits.isEmpty())
    }

    // ================================================================== D7-22 plan

    @Test
    fun aPlanIsCreatedThenUpdatedByCodigoWithOneStatementAndOneAuditRowEach() = testApplication {
        application { module() }
        val created = saved("D7-22", "/api/v1/admin/planes/Premium", planBody, HttpStatusCode.Created)
        assertEquals("premium", created["codigo"]!!.jsonPrimitive.content, "the codigo is stored in lower case")
        assertEquals(2, created["nivel"]!!.jsonPrimitive.int)
        assertEquals("Todo incluido", created["descripcion"]!!.jsonPrimitive.content)
        assertTrue(created["beneficios"]!!.jsonArray.isEmpty())

        // Repetir la MISMA peticion: mismo plan, mismo id, 200, ningun duplicado.
        val again = saved("D7-22", "/api/v1/admin/planes/premium", planBody, HttpStatusCode.OK)
        assertEquals(created["id"], again["id"])
        assertEquals(1, upstream.tables.getValue("planes_suscripcion").size)

        // Cambio: `descripcion` ausente la BORRA; el resto se sustituye.
        val changed = saved("D7-22", "/api/v1/admin/planes/premium", """{"nombre":"Premium Plus","nivel":3,"activo":true,"visible":false,"posicion":0}""", HttpStatusCode.OK)
        assertEquals("Premium Plus", changed["nombre"]!!.jsonPrimitive.content)
        assertFalse("descripcion" in changed, "an absent descripcion clears the stored one")
        assertEquals("false", changed["visible"]!!.jsonPrimitive.content)
        assertEquals(0, changed["posicion"]!!.jsonPrimitive.int)

        assertEquals(3, upstream.writes.count { it.startsWith("POST https://stub.supabase.test/rest/v1/planes_suscripcion?on_conflict=codigo") }, "one upsert statement per command")
        assertEquals(3, upstream.audits.size)
        upstream.audits.forEach { audit ->
            assertEquals("plan_suscripcion_guardado", audit["accion"]!!.jsonPrimitive.content)
            assertEquals(adminUid, audit["usuario_id"]!!.jsonPrimitive.content, "the actor is the JWT sub")
            assertEquals(created["id"], audit["registro_id"])
        }
    }

    @Test
    fun anInvalidPlanCommandIsRejectedBeforeAnyWrite() = testApplication {
        application { module() }
        val path = "/api/v1/admin/planes/premium"
        rejected("D7-22", "/api/v1/admin/planes/bad%20code", planBody, HttpStatusCode.BadRequest, "VALIDATION")
        rejected("D7-22", "/api/v1/admin/planes/a%26select%3D*", planBody, HttpStatusCode.BadRequest, "VALIDATION")
        rejected("D7-22", path, "{not json", HttpStatusCode.BadRequest, "MALFORMED_BODY")
        rejected("D7-22", path, """{"nombre":"P","nivel":1,"activo":true,"visible":true}""", HttpStatusCode.BadRequest, "VALIDATION")
        rejected("D7-22", path, """{"nombre":"P","nivel":1,"activo":true,"visible":true,"posicion":1,"id":"x"}""", HttpStatusCode.BadRequest, "VALIDATION")
        rejected("D7-22", path, """{"nombre":"P","nivel":"alto","activo":true,"visible":true,"posicion":1}""", HttpStatusCode.BadRequest, "VALIDATION")
        rejected("D7-22", path, """{"nombre":"  ","nivel":1,"activo":true,"visible":true,"posicion":1}""", HttpStatusCode.UnprocessableEntity, "PLAN_NAME_REQUIRED")
        rejected("D7-22", path, """{"nombre":"P","nivel":-1,"activo":true,"visible":true,"posicion":1}""", HttpStatusCode.UnprocessableEntity, "PLAN_LEVEL_NEGATIVE")
        rejected("D7-22", path, """{"nombre":"P","nivel":4,"activo":true,"visible":true,"posicion":1}""", HttpStatusCode.UnprocessableEntity, "PLAN_LEVEL_OUT_OF_RANGE")
        rejected("D7-22", path, """{"nombre":"P","nivel":1,"activo":true,"visible":true,"posicion":-1}""", HttpStatusCode.UnprocessableEntity, "PLAN_POSITION_NEGATIVE")
        rejected("D7-22", path, """{"nombre":"P","nivel":1,"activo":false,"visible":true,"posicion":1}""", HttpStatusCode.UnprocessableEntity, "PLAN_VISIBLE_BUT_INACTIVE")
        assertTrue(upstream.tables.getValue("planes_suscripcion").isEmpty())
    }

    @Test
    fun aFailedPlanWriteIs502AndLeavesNoAuditRow() = testApplication {
        application { module() }
        upstream.failWrites = true
        val response = put("/api/v1/admin/planes/premium", planBody)
        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertEquals("UPSTREAM_ERROR", obj(response.bodyAsText())["code"]!!.jsonPrimitive.content)
        assertTrue(upstream.audits.isEmpty(), "nothing was saved, so nothing is audited")
    }

    @Test
    fun aFailedAuditRowIsNeverReportedAsSuccess() = testApplication {
        application { module() }
        upstream.failAudit = true
        val response = put("/api/v1/admin/planes/premium", planBody)
        assertTrue(response.status.value >= 500, "the audit row is mandatory: ${response.status}")
    }

    // ================================================================== D7-19 tarifa

    @Test
    fun aTariffIsCreatedThenUpdatedByItsNaturalKey() = testApplication {
        application { module() }
        val product = upstream.product("PREMIUM_M")
        val created = saved("D7-19", tariffPath, tariffBody, HttpStatusCode.Created)
        assertEquals(product, created["productoId"]!!.jsonPrimitive.content)
        assertEquals("mensual", created["periodoFacturacion"]!!.jsonPrimitive.content)
        assertEquals("first_year", created["fasePrecio"]!!.jsonPrimitive.content)
        assertEquals(299, created["precioCentimos"]!!.jsonPrimitive.int)
        assertEquals("EUR", created["moneda"]!!.jsonPrimitive.content, "the currency is stored in upper case")

        val again = saved("D7-19", tariffPath, tariffBody, HttpStatusCode.OK)
        assertEquals(created["id"], again["id"], "repeating the request never creates a second tariff")

        val changed = saved("D7-19", tariffPath, """{"precioCentimos":0,"moneda":"EUR","activo":false,"visible":false,"posicion":5}""", HttpStatusCode.OK)
        assertEquals(0, changed["precioCentimos"]!!.jsonPrimitive.int, "a free tariff is a valid price")
        assertEquals(5, changed["posicion"]!!.jsonPrimitive.int)
        // `posicion` ausente no toca la guardada.
        assertEquals(5, saved("D7-19", tariffPath, tariffBody, HttpStatusCode.OK)["posicion"]!!.jsonPrimitive.int)

        // Otra fase del mismo producto es OTRA tarifa.
        val renewal = saved("D7-19", tariffPath.replace("first_year", "renewal"), """{"precioCentimos":499,"moneda":"EUR","activo":true,"visible":true}""", HttpStatusCode.Created)
        assertFalse(renewal["id"] == created["id"])
        assertEquals(2, upstream.tables.getValue("tarifas_producto_comercial").size)

        assertTrue(upstream.writes.filter { "tarifas_producto_comercial" in it }.all { "on_conflict=producto_id,periodo_facturacion,fase_precio" in it })
        assertEquals(5, upstream.audits.size)
        upstream.audits.forEach { audit ->
            assertEquals("tarifa_producto_guardada", audit["accion"]!!.jsonPrimitive.content)
            assertEquals(adminUid, audit["usuario_id"]!!.jsonPrimitive.content)
        }
        assertEquals(499, upstream.audits.last()["despues"]!!.jsonObject["precio_centimos"]!!.jsonPrimitive.int, "the audit row records the price that was set")
    }

    @Test
    fun anInvalidTariffCommandIsRejectedBeforeAnyWrite() = testApplication {
        application { module() }
        upstream.product("PREMIUM_M")
        rejected("D7-19", "/api/v1/admin/comercial/productos/NOPE/tarifas/mensual/first_year", tariffBody, HttpStatusCode.NotFound, "PRODUCT_NOT_FOUND")
        rejected("D7-19", tariffPath.replace("mensual", "semanal"), tariffBody, HttpStatusCode.BadRequest, "VALIDATION")
        rejected("D7-19", tariffPath.replace("first_year", "promo"), tariffBody, HttpStatusCode.BadRequest, "VALIDATION")
        rejected("D7-19", "/api/v1/admin/comercial/productos/a%26b/tarifas/mensual/first_year", tariffBody, HttpStatusCode.BadRequest, "VALIDATION")
        rejected("D7-19", tariffPath, """{"moneda":"EUR","activo":true,"visible":true}""", HttpStatusCode.BadRequest, "VALIDATION")
        rejected("D7-19", tariffPath, """{"precioCentimos":2.99,"moneda":"EUR","activo":true,"visible":true}""", HttpStatusCode.BadRequest, "VALIDATION")
        rejected("D7-19", tariffPath, """{"precioCentimos":299,"moneda":"EUR","activo":true,"visible":true,"productoId":"x"}""", HttpStatusCode.BadRequest, "VALIDATION")
        rejected("D7-19", tariffPath, """{"precioCentimos":-1,"moneda":"EUR","activo":true,"visible":true}""", HttpStatusCode.UnprocessableEntity, "TARIFF_PRICE_NEGATIVE")
        rejected("D7-19", tariffPath, """{"precioCentimos":1,"moneda":" ","activo":true,"visible":true}""", HttpStatusCode.UnprocessableEntity, "TARIFF_CURRENCY_REQUIRED")
        rejected("D7-19", tariffPath, """{"precioCentimos":1,"moneda":"EUR","activo":true,"visible":true,"posicion":-2}""", HttpStatusCode.UnprocessableEntity, "TARIFF_POSITION_NEGATIVE")
        assertTrue(upstream.tables.getValue("tarifas_producto_comercial").isEmpty())
    }

    // ================================================================== D7-18 producto (FA15)

    private val productPath = "/api/v1/admin/comercial/productos/PACK_100"
    private val productBody =
        """{"tipoProducto":"pack_tickets","nombre":" Pack 100 ","descripcion":"Cien OT","cantidadTickets":100,"moneda":"eur","destacado":true,"etiquetaPromocional":"Mejor valor","posicion":2,"activo":true,"visible":true}"""

    @Test
    fun theProductCommandRejectsAnUnauthenticatedCallerAndANonAdminBeforeReadingAnything() = testApplication {
        application { module() }
        assertEquals(HttpStatusCode.Unauthorized, put(productPath, productBody, token = null).status)
        val forbidden = put(productPath, productBody, token = userToken)
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
        assertEquals("FORBIDDEN", obj(forbidden.bodyAsText())["code"]!!.jsonPrimitive.content)
        assertTrue(upstream.sent.isEmpty() && upstream.audits.isEmpty())
    }

    @Test
    fun aProductIsCreatedThenUpdatedByCodigoWithTheFieldNamesOfTheProductList() = testApplication {
        application { module() }
        val created = saved("D7-18", productPath, productBody, HttpStatusCode.Created)
        assertEquals("PACK_100", created["codigo"]!!.jsonPrimitive.content)
        assertEquals("pack_tickets", created["tipoProducto"]!!.jsonPrimitive.content)
        assertEquals("Pack 100", created["nombre"]!!.jsonPrimitive.content, "the name is trimmed")
        assertEquals("EUR", created["moneda"]!!.jsonPrimitive.content, "the currency is stored in upper case")
        assertEquals(100, created["cantidadTickets"]!!.jsonPrimitive.int)
        assertEquals("Mejor valor", created["etiquetaPromocional"]!!.jsonPrimitive.content)
        assertTrue(created["tarifas"]!!.jsonArray.isEmpty())
        // Mismos nombres que emite D7-17: el elemento guardado valida contra el elemento de la lista.
        val listItem = docs.resolveRef("commerce/commerce.yaml#/components/schemas/ProductoComercial", docs.rootFile)
        val problems = mutableListOf<String>()
        checker.validate(listItem, created, "$", problems)
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))

        // Repetir la MISMA peticion: mismo producto, mismo id, 200, ningun duplicado.
        val again = saved("D7-18", productPath, productBody, HttpStatusCode.OK)
        assertEquals(created["id"], again["id"])
        assertEquals(1, upstream.tables.getValue("productos_comerciales").size)

        // Un precio guardado por otra via no lo toca este comando; los opcionales ausentes se BORRAN.
        upstream.tables.getValue("productos_comerciales").single()["precio_centimos"] = JsonPrimitive(999)
        val plan = upstream.id()
        upstream.tables.getValue("planes_suscripcion") += mutableMapOf<String, JsonElement>("id" to JsonPrimitive(plan), "codigo" to JsonPrimitive("premium"))
        val changed = saved(
            "D7-18",
            productPath,
            """{"tipoProducto":"suscripcion","nombre":"Premium","planId":"${plan.uppercase()}","moneda":"EUR","destacado":false,"posicion":0,"activo":true,"visible":false}""",
            HttpStatusCode.OK,
        )
        assertEquals(created["id"], changed["id"])
        assertEquals("suscripcion", changed["tipoProducto"]!!.jsonPrimitive.content)
        assertEquals(plan, changed["planId"]!!.jsonPrimitive.content, "the plan id is stored in lower case")
        assertEquals(999, changed["precioCentimos"]!!.jsonPrimitive.int, "the stored price is not part of this command")
        listOf("descripcion", "cantidadTickets", "etiquetaPromocional").forEach { assertFalse(it in changed, "an absent $it clears the stored one") }

        val statements = upstream.sent.filter { it.method() == "POST" && "productos_comerciales" in it.uri().toString() }
        assertEquals(3, statements.size, "one upsert statement per command")
        assertTrue(statements.all { "on_conflict=codigo" in it.uri().toString() })
        statements.forEach { assertFalse("precio_centimos" in readRequestBody(it), "the price column is never written") }
        assertEquals(3, upstream.audits.size)
        upstream.audits.forEach { audit ->
            assertEquals("producto_comercial_guardado", audit["accion"]!!.jsonPrimitive.content)
            assertEquals(adminUid, audit["usuario_id"]!!.jsonPrimitive.content, "the actor is the JWT sub")
            assertEquals(created["id"], audit["registro_id"])
        }
    }

    @Test
    fun anInvalidProductCommandIsRejectedBeforeAnyWrite() = testApplication {
        application { module() }
        fun body(vararg replace: Pair<String, String>): String =
            replace.fold("""{"tipoProducto":"pack_tickets","nombre":"P","cantidadTickets":10,"moneda":"EUR","destacado":false,"posicion":1,"activo":true,"visible":true}""") { acc, (from, to) -> acc.replace(from, to) }
        rejected("D7-18", "/api/v1/admin/comercial/productos/bad%20code", productBody, HttpStatusCode.BadRequest, "VALIDATION")
        rejected("D7-18", "/api/v1/admin/comercial/productos/a%26select%3D*", productBody, HttpStatusCode.BadRequest, "VALIDATION")
        rejected("D7-18", productPath, "{not json", HttpStatusCode.BadRequest, "MALFORMED_BODY")
        rejected("D7-18", productPath, body("\"nombre\":\"P\"," to ""), HttpStatusCode.BadRequest, "VALIDATION")
        rejected("D7-18", productPath, body("pack_tickets" to "opositickets"), HttpStatusCode.BadRequest, "VALIDATION")
        // Los nombres del diseno y los campos del servidor no se aceptan: solo los de D7-17.
        rejected("D7-18", productPath, body("\"tipoProducto\"" to "\"tipo\""), HttpStatusCode.BadRequest, "VALIDATION")
        rejected("D7-18", productPath, body("\"posicion\":1" to "\"posicion\":1,\"planCodigo\":\"premium\""), HttpStatusCode.BadRequest, "VALIDATION")
        rejected("D7-18", productPath, body("\"posicion\":1" to "\"posicion\":1,\"precioCentimos\":100"), HttpStatusCode.BadRequest, "VALIDATION")
        rejected("D7-18", productPath, body("\"posicion\":1" to "\"posicion\":1,\"id\":\"x\""), HttpStatusCode.BadRequest, "VALIDATION")
        rejected("D7-18", productPath, body("\"posicion\":1" to "\"posicion\":1,\"planId\":\"no-uuid\""), HttpStatusCode.BadRequest, "VALIDATION")
        rejected("D7-18", productPath, body("\"cantidadTickets\":10" to "\"cantidadTickets\":1.5"), HttpStatusCode.BadRequest, "VALIDATION")
        rejected("D7-18", productPath, body("\"nombre\":\"P\"" to "\"nombre\":\"${"x".repeat(201)}\""), HttpStatusCode.BadRequest, "VALIDATION")
        rejected("D7-18", productPath, body("\"nombre\":\"P\"" to "\"nombre\":\"P\",\"descripcion\":\"${"x".repeat(2001)}\""), HttpStatusCode.BadRequest, "VALIDATION")
        rejected("D7-18", productPath, body("\"nombre\":\"P\"" to "\"nombre\":\"  \""), HttpStatusCode.UnprocessableEntity, "PRODUCT_NAME_REQUIRED")
        rejected("D7-18", productPath, body("\"moneda\":\"EUR\"" to "\"moneda\":\" \""), HttpStatusCode.UnprocessableEntity, "PRODUCT_CURRENCY_REQUIRED")
        rejected("D7-18", productPath, body("\"cantidadTickets\":10" to "\"cantidadTickets\":0"), HttpStatusCode.UnprocessableEntity, "PRODUCT_TICKETS_NOT_POSITIVE")
        rejected("D7-18", productPath, body("\"posicion\":1" to "\"posicion\":-1"), HttpStatusCode.UnprocessableEntity, "PRODUCT_POSITION_NEGATIVE")
        rejected("D7-18", productPath, body("\"destacado\":false" to "\"destacado\":true", "\"visible\":true" to "\"visible\":false"), HttpStatusCode.UnprocessableEntity, "PRODUCT_HIGHLIGHTED_BUT_HIDDEN")
        rejected(
            "D7-18", productPath, body("\"posicion\":1" to "\"posicion\":1,\"planId\":\"eeeeeeee-0000-4000-8000-999999999999\""),
            HttpStatusCode.UnprocessableEntity, "PRODUCT_PLAN_NOT_FOUND",
        )
        assertTrue(upstream.tables.getValue("productos_comerciales").isEmpty())
    }

    @Test
    fun aFailedProductWriteIs502AndAFailedAuditRowIsNeverReportedAsSuccess() = testApplication {
        application { module() }
        upstream.failWrites = true
        val failed = put(productPath, productBody)
        assertEquals(HttpStatusCode.BadGateway, failed.status)
        assertEquals("UPSTREAM_ERROR", obj(failed.bodyAsText())["code"]!!.jsonPrimitive.content)
        assertTrue(upstream.audits.isEmpty(), "nothing was saved, so nothing is audited")
        upstream.failWrites = false
        upstream.failAudit = true
        assertTrue(put(productPath, productBody).status.value >= 500, "the audit row is mandatory")
    }

    // ================================================================== las rutas POST del cliente actual

    @Test
    fun theLegacyPostRoutesTheCurrentClientUsesAreStillRouted() = testApplication {
        application { module() }
        // No se ejercita su escritura (no cambio): basta con que sigan montadas y protegidas igual que antes.
        listOf("/api/v1/admin/planes", "/api/v1/admin/comercial/productos").forEach { path ->
            val response = client.post(path) {
                header(HttpHeaders.Authorization, "Bearer $userToken")
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status, path)
        }
    }
}
