package com.oposicontrol.backend

import com.oposicontrol.backend.openapi.OpenApiDocs
import com.oposicontrol.backend.openapi.SchemaChecker
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
 * FA13: lecturas de catalogo de administracion D7-17, D7-21, D6-18, D6-20, D6-22 y resumen del panel D3-15, de punta a punta
 * (ruta -> gateway -> transporte real) contra un PostgREST falso con estado. El cable se valida contra
 * `openapi/commerce/commerce.yaml` y `openapi/dashboard/dashboard.yaml`, y cada lista comprueba que `data` (lo que lee el
 * cliente OposiControl actual, sin parametros) sigue ahi con los mismos nombres de campo.
 */
class AdminCatalogReadRoutesTest {

    private val docs = OpenApiDocs.locate()
    private val checker = SchemaChecker(docs)
    private val json = Json { ignoreUnknownKeys = true }

    private val adminUid = "11111111-aaaa-4aaa-8aaa-000000000001"
    private val userUid = "22222222-bbbb-4bbb-8bbb-000000000002"
    private val adminToken = "admin-token"
    private val userToken = "user-token"

    private val lists = mapOf(
        "D7-17" to "/api/v1/admin/comercial/productos",
        "D7-21" to "/api/v1/admin/planes",
        "D6-18" to "/api/v1/admin/market/servicios",
        "D6-20" to "/api/v1/admin/market/promociones",
        "D6-22" to "/api/v1/admin/market/popups",
    )
    private val tableOf = mapOf(
        "D7-17" to "productos_comerciales", "D7-21" to "planes_suscripcion", "D6-18" to "servicios",
        "D6-20" to "promociones", "D6-22" to "reglas_popup_comercial",
    )

    // ------------------------------------------------------------------ PostgREST falso con estado

    private inner class Upstream {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val tables = mutableMapOf<String, MutableList<JsonObject>>()
        var fail = false
        private var seq = 0

        init {
            listOf(
                "productos_comerciales", "tarifas_producto_comercial", "planes_suscripcion", "beneficios_plan", "servicios",
                "promociones", "usos_promocion_usuario", "reglas_popup_comercial",
            ).forEach { tables[it] = mutableListOf() }
        }

        fun id(): String = "cccccccc-0000-4000-8000-%012d".format(++seq)

        fun row(table: String, vararg fields: Pair<String, Any?>): String {
            val id = id()
            tables.getValue(table) += JsonObject(
                (listOf("id" to id) + fields).associate { (k, v) ->
                    k to when (v) {
                        null -> JsonNull
                        is Boolean -> JsonPrimitive(v)
                        is Number -> JsonPrimitive(v)
                        else -> JsonPrimitive(v.toString())
                    }
                },
            )
            return id
        }

        fun product(codigo: String, tipo: String = "pack_tickets", activo: Boolean = true, posicion: Int = 1): String = row(
            "productos_comerciales", "codigo" to codigo, "tipo_producto" to tipo, "nombre" to "Producto $codigo", "descripcion" to null,
            "plan_id" to null, "precio_centimos" to 499, "moneda" to "EUR", "cantidad_tickets" to 50, "activo" to activo, "visible" to true,
            "destacado" to false, "etiqueta_promocional" to null, "posicion" to posicion,
        )

        fun tariff(productId: String, periodo: String = "mensual", fase: String = "standard", cents: Int = 299): String = row(
            "tarifas_producto_comercial", "producto_id" to productId, "periodo_facturacion" to periodo, "fase_precio" to fase,
            "precio_centimos" to cents, "moneda" to "EUR", "activo" to true, "visible" to true, "posicion" to 1,
        )

        fun plan(codigo: String, nivel: Int = 1, activo: Boolean = true): String = row(
            "planes_suscripcion", "codigo" to codigo, "nombre" to "Plan $codigo", "descripcion" to null, "nivel" to nivel,
            "activo" to activo, "visible" to true, "posicion" to 1,
        )

        fun benefit(planId: String, codigo: String): String =
            row("beneficios_plan", "plan_id" to planId, "codigo_beneficio" to codigo, "valor_boolean" to true, "valor_integer" to null, "valor_numeric" to null, "valor_text" to null)

        fun service(codigo: String, tipo: String = "potenciador", activo: Boolean = true): String = row(
            "servicios", "codigo" to codigo, "nombre" to "Servicio $codigo", "descripcion" to null, "tipo_servicio" to tipo,
            "precio_tickets" to 10, "precio_centimos" to null, "activo" to activo,
        )

        fun promotion(codigo: String, activo: Boolean = true): String = row(
            "promociones", "codigo" to codigo, "nombre" to "Promo $codigo", "tipo_descuento" to "porcentaje", "valor" to 20,
            "activo" to activo, "valido_desde" to null, "valido_hasta" to "2026-12-31T23:59:59+00:00",
        )

        fun use(promotionId: String, minute: Int = 0, generoCompra: Boolean = false, renovo: Boolean? = null, descuento: Int? = 100): String = row(
            "usos_promocion_usuario", "usuario_id" to userUid, "promocion_id" to promotionId, "compra_id" to null, "descuento_centimos" to descuento,
            "genero_compra" to generoCompra, "renovo_despues" to renovo, "usado_en" to "2026-10-08T10:%02d:00+00:00".format(minute),
        )

        private val keyset = Regex("""^\((\w+)\.lt\.([^,]+),and\(\w+\.eq\.[^,]+,id\.lt\.([0-9a-f-]+)\)\)$""")

        fun popup(codigo: String, pantalla: String? = "home", prioridad: Int = 50, activo: Boolean = true): String = row(
            "reglas_popup_comercial", "codigo" to codigo, "evento_disparador" to "examen_bloqueado", "titulo" to "Titulo", "mensaje" to "Mensaje",
            "cta_principal" to "Ver planes", "cta_secundario" to null, "pantalla" to pantalla, "momento" to "home", "requiere_plan_minimo" to null,
            "tipo_examen_objetivo" to null, "frecuencia_minima_horas" to 24, "prioridad" to prioridad, "activo" to activo,
        )

        val requests: List<String> get() = sent.map { "${it.method()} ${URLDecoder.decode(it.uri().toString(), Charsets.UTF_8)}" }

        private fun text(row: JsonObject, column: String): String? = (row[column] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

        fun respond(rawUri: String): V1StubResponse {
            if (fail) return V1StubResponse(500, """{"message":"boom"}""")
            val request = sent.last()
            val uri = URLDecoder.decode(rawUri, Charsets.UTF_8)
            val table = uri.substringAfter("/rest/v1/").substringBefore('?')
            val source = tables[table] ?: error("tabla inesperada: $uri")
            val q = uri.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate { it.substringBefore('=') to it.substringAfter('=', "") }
            var rows: List<JsonObject> = source.toList()
            q.forEach { (key, value) ->
                rows = when {
                    key in setOf("select", "order", "limit", "offset") -> rows
                    key == "or" -> {
                        val (column, ts, id) = (keyset.matchEntire(value) ?: error("keyset inesperado: $value")).destructured
                        rows.filter { r -> text(r, column)!!.compareTo(ts).let { c -> c < 0 || (c == 0 && text(r, "id")!! < id) } }
                    }
                    value.startsWith("eq.") -> rows.filter { text(it, key) == value.removePrefix("eq.") }
                    value.startsWith("in.(") -> value.removePrefix("in.(").removeSuffix(")").split(',').toSet().let { set -> rows.filter { text(it, key) in set } }
                    else -> error("filtro inesperado: $key=$value")
                }
            }
            q["order"]?.split(',')?.map { it.substringBefore('.') to it.endsWith(".desc") }?.let { keys ->
                rows = rows.sortedWith { a, b ->
                    keys.firstNotNullOfOrNull { (column, desc) ->
                        val x = text(a, column).orEmpty()
                        val y = text(b, column).orEmpty()
                        val c = (x.toIntOrNull()?.let { xi -> y.toIntOrNull()?.let { yi -> xi.compareTo(yi) } } ?: x.compareTo(y))
                        c.takeIf { it != 0 }?.let { if (desc) -it else it }
                    } ?: 0
                }
            }
            val total = rows.size
            val offset = q["offset"]?.toInt() ?: 0
            var page = rows.drop(offset).take(q["limit"]?.toInt() ?: Int.MAX_VALUE)
            if (q["select"].orEmpty().contains("usos_promocion_usuario(count)")) {
                page = page.map { r ->
                    val count = tables.getValue("usos_promocion_usuario").count { text(it, "promocion_id") == text(r, "id") }
                    JsonObject(r + ("usos_promocion_usuario" to JsonArray(listOf(JsonObject(mapOf("count" to JsonPrimitive(count)))))))
                }
            }
            val counted = request.headers().firstValue("Prefer").orElse("").contains("count=exact")
            val range = if (!counted) null else if (page.isEmpty()) "*/$total" else "$offset-${offset + page.size - 1}/$total"
            return V1StubResponse(if (counted) 206 else 200, JsonArray(page).toString(), contentRange = range)
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
        val client = V1StubHttpClient({ upstream.sent += it }, upstream::respond)
        val gateway = AdminV1Gateway.forTesting(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = client,
        )
        val overview = AdminDashboardOverviewGateway(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = V1StubHttpClient({ dashboardSent += it }, ::dashboardRespond),
        )
        routing {
            authenticate("gateway-auth") {
                install(AdminAuthorizationPlugin) {
                    this.appEnv = "production"
                    this.hasAdminRole = { it == adminUid }
                }
                installAdminAuditActor()
                adminV1Routes(gateway)
                adminDashboardOverviewRoutes(overview)
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun ApplicationTestBuilder.call(path: String, token: String? = adminToken): HttpResponse =
        client.get(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }

    private fun obj(body: String): JsonObject = json.parseToJsonElement(body).jsonObject

    private fun codigos(body: String, key: String = "items"): List<String> = obj(body)[key]!!.jsonArray.map { it.jsonObject["codigo"]!!.jsonPrimitive.content }

    private fun conforms(contractId: String, body: String) {
        val op = docs.operations().first { it.contractId == contractId }
        val response = docs.responses(op).getValue("200")
        val schema = ((response.value as Map<*, *>)["content"] as Map<*, *>).let { (it["application/json"] as Map<*, *>)["schema"] }
        val problems = mutableListOf<String>()
        checker.validate(OpenApiDocs.Node(schema, response.file), json.parseToJsonElement(body), "$", problems)
        assertTrue(problems.isEmpty(), "$contractId 200 does not conform to the frozen OpenAPI:\n${problems.joinToString("\n")}\n$body")
    }

    private suspend fun ApplicationTestBuilder.ok(contractId: String, query: String = ""): String {
        val path = (lists[contractId] ?: "/api/v1/admin/dashboard/overview") + query
        val response = call(path)
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, "$path -> $body")
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl], path)
        conforms(contractId, body)
        return body
    }

    private suspend fun ApplicationTestBuilder.rejected(contractId: String, query: String, status: HttpStatusCode, code: String, noUpstream: Boolean = true) {
        val path = (lists[contractId] ?: "/api/v1/admin/dashboard/overview") + query
        val before = upstream.sent.size + dashboardSent.size
        val response = call(path)
        val body = obj(response.bodyAsText())
        assertEquals(status, response.status, "$path -> $body")
        assertEquals(code, body["code"]!!.jsonPrimitive.content, path)
        assertTrue(body["message"]!!.jsonPrimitive.content.isNotBlank() && body["requestId"]!!.jsonPrimitive.content.isNotBlank(), body.toString())
        val op = docs.operations().first { it.contractId == contractId }
        assertTrue(status.value.toString() in docs.responses(op), "$contractId does not document $status")
        assertTrue(code in (op.op["x-error-codes"] as List<*>), "$contractId does not list $code")
        if (noUpstream) assertEquals(before, upstream.sent.size + dashboardSent.size, "$path reached the upstream")
    }

    private fun seedOne(contractId: String, codigo: String, activo: Boolean = true) {
        when (contractId) {
            "D7-17" -> upstream.product(codigo, activo = activo)
            "D7-21" -> upstream.plan(codigo, activo = activo)
            "D6-18" -> upstream.service(codigo, activo = activo)
            "D6-20" -> upstream.promotion(codigo, activo = activo)
            else -> upstream.popup(codigo, activo = activo)
        }
    }

    // ================================================================== 401 / 403

    @Test
    fun everyOperationRejectsAnUnauthenticatedCallerAndANonAdminWithoutTouchingTheDatabase() = testApplication {
        application { module() }
        (lists.values + "/api/v1/admin/dashboard/overview").forEach { path ->
            assertEquals(HttpStatusCode.Unauthorized, call(path, token = null).status, path)
            val forbidden = call(path, token = userToken)
            assertEquals(HttpStatusCode.Forbidden, forbidden.status, path)
            assertEquals("FORBIDDEN", obj(forbidden.bodyAsText())["code"]!!.jsonPrimitive.content, path)
        }
        assertTrue(upstream.sent.isEmpty() && dashboardSent.isEmpty())
    }

    // ================================================================== reglas comunes de las cinco listas

    @Test
    fun withoutParametersEveryListReturnsTheWholeCatalogUnderItemsAndUnderTheLegacyData() = testApplication {
        application { module() }
        lists.keys.forEach { contract ->
            seedOne(contract, "A")
            seedOne(contract, "B", activo = false)
            val body = ok(contract)
            assertEquals(listOf("A", "B"), codigos(body).sorted(), contract)
            // El cliente OC actual lee `data` y no envia parametros: misma pagina, mismos elementos.
            assertEquals(obj(body)["items"], obj(body)["data"], contract)
            assertEquals(2, obj(body)["total"]!!.jsonPrimitive.int, contract)
            assertEquals(100, obj(body)["limit"]!!.jsonPrimitive.int, contract)
            assertEquals(0, obj(body)["offset"]!!.jsonPrimitive.int, contract)
            // Los valores por defecto salen por el cable: `activo` true y false explicitos.
            assertEquals(setOf("true", "false"), obj(body)["items"]!!.jsonArray.map { it.jsonObject["activo"]!!.jsonPrimitive.content }.toSet(), contract)
        }
        assertTrue(upstream.sent.all { it.method() == "GET" }, "read-only")
    }

    @Test
    fun everyListPagesByOffsetWithTheExactTotalAndFiltersByActivo() = testApplication {
        application { module() }
        lists.keys.forEach { contract ->
            listOf("A", "B", "C").forEach { seedOne(contract, it) }
            seedOne(contract, "Z", activo = false)
            val first = ok(contract, "?limit=2&activo=true")
            val second = ok(contract, "?limit=2&offset=2&activo=true")
            assertEquals(2, codigos(first).size, contract)
            assertEquals(3, obj(first)["total"]!!.jsonPrimitive.int, "$contract: total of the FILTER, not of the page")
            assertEquals(setOf("A", "B", "C"), (codigos(first) + codigos(second)).toSet(), contract)
            assertEquals(2, obj(second)["offset"]!!.jsonPrimitive.int, contract)
            assertEquals(listOf("Z"), codigos(ok(contract, "?activo=false")), contract)
            // La pagina y su total salen de UNA peticion acotada.
            val table = tableOf.getValue(contract)
            assertTrue(upstream.requests.filter { "/rest/v1/$table?" in it }.all { "limit=" in it && "offset=" in it }, contract)
            ok(contract, "?limit=5000")
            assertTrue(upstream.requests.last { "/rest/v1/$table?" in it }.contains("limit=100&offset=0"), "$contract: limit clamped to 100")
            ok(contract, "?offset=-3")
            assertTrue(upstream.requests.last { "/rest/v1/$table?" in it }.contains("offset=0"), "$contract: a negative offset is 0")
        }
    }

    @Test
    fun invalidParametersAre400BeforeAnyRead() = testApplication {
        application { module() }
        lists.keys.forEach { contract ->
            rejected(contract, "?activo=si", HttpStatusCode.BadRequest, "INVALID_FILTER")
            rejected(contract, "?activo=true%26select%3D*", HttpStatusCode.BadRequest, "INVALID_FILTER")
            rejected(contract, "?limit=abc", HttpStatusCode.BadRequest, "VALIDATION")
            rejected(contract, "?limit=0", HttpStatusCode.BadRequest, "VALIDATION")
            rejected(contract, "?offset=x", HttpStatusCode.BadRequest, "VALIDATION")
        }
        rejected("D7-17", "?tipo=regalo", HttpStatusCode.BadRequest, "INVALID_FILTER")
        rejected("D6-22", "?pantalla=${"p".repeat(101)}", HttpStatusCode.BadRequest, "INVALID_FILTER")
    }

    @Test
    fun anUpstreamFailureIs502WithTheCanonicalEnvelope() = testApplication {
        application { module() }
        upstream.fail = true
        lists.keys.forEach { rejected(it, "", HttpStatusCode.BadGateway, "UPSTREAM_ERROR", noUpstream = false) }
    }

    // ================================================================== lo propio de cada lista

    @Test
    fun productsCarryTheirTariffsKeepTheClientFieldNamesAndFilterByTipo() = testApplication {
        application { module() }
        val pack = upstream.product("PACK_50", posicion = 2)
        val sub = upstream.product("PREMIUM", tipo = "suscripcion", posicion = 1)
        upstream.tariff(sub, "mensual", "first_year", 299)
        upstream.tariff(sub, "mensual", "renewal", 499)
        val body = ok("D7-17")
        assertEquals(listOf("PREMIUM", "PACK_50"), codigos(body), "posicion, then codigo")
        val premium = obj(body)["items"]!!.jsonArray.first().jsonObject
        assertEquals(sub, premium["id"]!!.jsonPrimitive.content)
        assertEquals("suscripcion", premium["tipoProducto"]!!.jsonPrimitive.content)
        assertEquals(listOf(299, 499), premium["tarifas"]!!.jsonArray.map { it.jsonObject["precioCentimos"]!!.jsonPrimitive.int }.sorted())
        assertFalse("tipo" in premium || "tariffs" in premium || "storeMappings" in premium, "the design names are not emitted on this path")
        val packRow = obj(body)["items"]!!.jsonArray.last().jsonObject
        assertEquals(pack, packRow["id"]!!.jsonPrimitive.content)
        assertTrue(packRow["tarifas"]!!.jsonArray.isEmpty(), "a product without tariffs has an explicit empty list")
        assertEquals("EUR", packRow["moneda"]!!.jsonPrimitive.content)
        assertEquals(listOf("PACK_50"), codigos(ok("D7-17", "?tipo=pack_tickets")))
        // Las tarifas se piden solo para los productos de la pagina.
        assertTrue(upstream.requests.filter { "tarifas_producto_comercial" in it }.all { "producto_id=in.(" in it })
    }

    @Test
    fun plansCarryTheirBenefits() = testApplication {
        application { module() }
        val premium = upstream.plan("premium", nivel = 2)
        upstream.plan("free", nivel = 0)
        upstream.benefit(premium, "sin_anuncios")
        val items = obj(ok("D7-21"))["items"]!!.jsonArray.map { it.jsonObject }.associateBy { it["codigo"]!!.jsonPrimitive.content }
        val benefits = items.getValue("premium")["beneficios"]!!.jsonArray
        assertEquals("sin_anuncios", benefits.single().jsonObject["codigo_beneficio"]!!.jsonPrimitive.content)
        assertEquals("true", benefits.single().jsonObject["valor_boolean"]!!.jsonPrimitive.content)
        assertTrue(items.getValue("free")["beneficios"]!!.jsonArray.isEmpty())
        assertEquals(0, items.getValue("free")["nivel"]!!.jsonPrimitive.int, "nivel 0 is on the wire, not omitted")
    }

    @Test
    fun servicesKeepTheSnakeCaseFieldsTheClientReads() = testApplication {
        application { module() }
        upstream.service("POT_X2")
        val row = obj(ok("D6-18"))["items"]!!.jsonArray.single().jsonObject
        assertEquals("potenciador", row["tipo_servicio"]!!.jsonPrimitive.content)
        assertEquals(10, row["precio_tickets"]!!.jsonPrimitive.int)
        assertFalse("precio_centimos" in row || "efectos" in row)
    }

    @Test
    fun promotionsCarryTheirUseCountFromTheSameQuery() = testApplication {
        application { module() }
        val used = upstream.promotion("VERANO")
        upstream.promotion("NUEVA")
        repeat(3) { upstream.use(used) }
        val items = obj(ok("D6-20"))["items"]!!.jsonArray.map { it.jsonObject }.associateBy { it["codigo"]!!.jsonPrimitive.content }
        assertEquals(3, items.getValue("VERANO")["usos"]!!.jsonPrimitive.int)
        assertEquals(0, items.getValue("NUEVA")["usos"]!!.jsonPrimitive.int, "an unused promotion says 0, not null")
        assertEquals("porcentaje", items.getValue("VERANO")["tipo_descuento"]!!.jsonPrimitive.content)
        assertFalse("usos_promocion_usuario" in items.getValue("VERANO"), "the embed never leaks")
        assertEquals(1, upstream.requests.count { "/rest/v1/promociones?" in it }, "one query, no N+1")
        assertTrue(upstream.requests.none { "/rest/v1/usos_promocion_usuario" in it })
    }

    @Test
    fun popupRulesAreOrderedByPriorityAndFilterByScreen() = testApplication {
        application { module() }
        upstream.popup("BAJA", prioridad = 10)
        upstream.popup("ALTA", prioridad = 90)
        upstream.popup("RESULTADO", pantalla = "resultado examen", prioridad = 50)
        val body = ok("D6-22")
        assertEquals(listOf("ALTA", "RESULTADO", "BAJA"), codigos(body))
        val row = obj(body)["items"]!!.jsonArray.first().jsonObject
        assertEquals("Ver planes", row["cta_principal"]!!.jsonPrimitive.content)
        assertFalse("ctas" in row || "metricas" in row)
        assertEquals(listOf("RESULTADO"), codigos(ok("D6-22", "?pantalla=resultado%20examen")))
        // Texto libre del cliente: viaja codificado, nunca crudo.
        ok("D6-22", "?pantalla=home%26activo%3Deq.false")
        val last = upstream.sent.last { "reglas_popup_comercial" in it.uri().rawPath }.uri().rawQuery
        assertTrue("pantalla=eq.home%26activo%3Deq.false" in last, last)
    }

    // ================================================================== D7-29 usos de una promocion

    private val ghost = "8f14e45f-ceea-467a-9a3e-1b2c3d4e5f60"

    private suspend fun ApplicationTestBuilder.uses(promotionId: String, query: String = ""): String {
        val response = call("/api/v1/admin/market/promociones/$promotionId/usos$query")
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        conforms("D7-29", body)
        return body
    }

    @Test
    fun promotionUsesAreKeysetPagedNewestFirstWithExactStatsForTheWholePromotion() = testApplication {
        application { module() }
        val promo = upstream.promotion("VERANO")
        val other = upstream.promotion("OTRA")
        upstream.use(promo, 1, generoCompra = true, renovo = true)
        upstream.use(promo, 2, generoCompra = true, renovo = false)
        upstream.use(promo, 3, descuento = null)
        upstream.use(other, 4, generoCompra = true)
        val seen = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val body = obj(uses(promo, "?limit=2" + (cursor?.let { "&cursor=$it" } ?: "")))
            seen += body["items"]!!.jsonArray.map { it.jsonObject["usadoEn"]!!.jsonPrimitive.content }
            // Los contadores son de la promocion entera, no de la pagina, y solo de ESTA promocion.
            val stats = body["stats"]!!.jsonObject
            assertEquals(listOf(3, 2, 1), listOf("usos", "conversiones", "renovaciones").map { stats[it]!!.jsonPrimitive.int })
            assertFalse("descuentoTotalCentimos" in stats, "no aggregate in the database: null (omitted), never a 0 or a partial sum")
            cursor = (body["nextCursor"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            pages++
        } while (cursor != null)
        assertEquals(listOf("2026-10-08T10:03:00Z", "2026-10-08T10:02:00Z", "2026-10-08T10:01:00Z"), seen, "newest first, UTC instants")
        assertEquals(2, pages)
        val newest = obj(uses(promo, "?limit=1"))["items"]!!.jsonArray.single().jsonObject
        assertFalse("descuentoCentimos" in newest || "compraId" in newest || "renovoDespues" in newest, "unknown values are null, not invented")
        assertEquals("false", newest["generoCompra"]!!.jsonPrimitive.content)
        assertTrue(upstream.requests.filter { "usos_promocion_usuario?select=id," in it }.all { "promocion_id=eq.$promo" in it && "limit=" in it })
        assertTrue(upstream.sent.all { it.method() == "GET" }, "read-only")
    }

    @Test
    fun aPromotionWithoutUsesIsAnEmptyPageAndAnUnknownPromotionIs404() = testApplication {
        application { module() }
        val promo = upstream.promotion("NUEVA")
        val body = obj(uses(promo))
        assertTrue(body["items"]!!.jsonArray.isEmpty())
        assertEquals(0, body["stats"]!!.jsonObject["usos"]!!.jsonPrimitive.int)

        suspend fun rejected(pathAndQuery: String, status: HttpStatusCode, code: String, noUpstream: Boolean) {
            val before = upstream.sent.size
            val response = call("/api/v1/admin/market/promociones/$pathAndQuery")
            val envelope = obj(response.bodyAsText())
            assertEquals(status, response.status, "$pathAndQuery -> $envelope")
            assertEquals(code, envelope["code"]!!.jsonPrimitive.content, pathAndQuery)
            assertTrue(envelope["requestId"]!!.jsonPrimitive.content.isNotBlank())
            val op = docs.operations().first { it.contractId == "D7-29" }
            assertTrue(status.value.toString() in docs.responses(op) && code in (op.op["x-error-codes"] as List<*>), "D7-29 does not declare $status $code")
            if (noUpstream) assertEquals(before, upstream.sent.size, "$pathAndQuery reached the upstream")
        }
        rejected("$ghost/usos", HttpStatusCode.NotFound, "NOT_FOUND", noUpstream = false)
        rejected("not-a-uuid/usos", HttpStatusCode.BadRequest, "VALIDATION", noUpstream = true)
        rejected("$promo/usos?limit=0", HttpStatusCode.BadRequest, "VALIDATION", noUpstream = true)
        rejected("$promo/usos?cursor=garbage", HttpStatusCode.BadRequest, "INVALID_CURSOR", noUpstream = true)
        // Un cursor de otra promocion no sirve aqui.
        val other = upstream.promotion("OTRA")
        (1..2).forEach { upstream.use(other, it) }
        val foreign = (obj(uses(other, "?limit=1"))["nextCursor"] as JsonPrimitive).content
        rejected("$promo/usos?cursor=$foreign", HttpStatusCode.BadRequest, "INVALID_CURSOR", noUpstream = true)
        assertEquals(HttpStatusCode.Unauthorized, call("/api/v1/admin/market/promociones/$promo/usos", token = null).status)
        assertEquals(HttpStatusCode.Forbidden, call("/api/v1/admin/market/promociones/$promo/usos", token = userToken).status)
    }

    // ================================================================== D3-15 resumen del panel

    private val dashboardSent = CopyOnWriteArrayList<HttpRequest>()
    private var dashboardFailOn: String? = null

    private fun dashboardRespond(uri: String): V1StubResponse {
        dashboardFailOn?.let { if (uri.contains(it)) return V1StubResponse(500, """{"message":"boom"}""") }
        return when {
            uri.contains("/rest/v1/auditoria_log") -> V1StubResponse(
                200,
                """[{"id":"dddddddd-0000-4000-8000-000000000001","usuario_id":"$adminUid","accion":"examen_publicado","tabla_afectada":"examenes","registro_id":"dddddddd-0000-4000-8000-000000000002","creado_en":"2026-10-08T10:00:00+00:00"}]""",
            )
            uri.contains("/rest/v1/rpc/admin_dashboard_active_users") -> V1StubResponse(200, "7")
            uri.contains("/rest/v1/rpc/admin_dashboard_economy_totals") -> V1StubResponse(200, """[{"gross_revenue_cents":12345,"paid_purchases":3,"tickets_in_circulation":900}]""")
            else -> V1StubResponse(206, "[]", contentRange = "*/4")
        }
    }

    @Test
    fun theOverviewReturnsTheSixBlocksAndConformsToTheContract() = testApplication {
        application { module() }
        val body = obj(ok("D3-15"))
        assertEquals(setOf("system", "attention", "pulse", "content", "activity", "economy", "failedBlocks", "generatedAt"), body.keys)
        assertTrue(body["failedBlocks"]!!.jsonArray.isEmpty())
        assertEquals(4, body["attention"]!!.jsonObject["reportsPending"]!!.jsonPrimitive.int)
        assertEquals(7, body["pulse"]!!.jsonObject["activeUsers30d"]!!.jsonPrimitive.int)
        assertEquals(12345, body["economy"]!!.jsonObject["grossRevenueCents"]!!.jsonPrimitive.int)
        assertEquals("examen_publicado", body["activity"]!!.jsonObject["entries"]!!.jsonArray.single().jsonObject["action"]!!.jsonPrimitive.content)
        assertTrue(dashboardSent.all { it.method() == "GET" || it.uri().path.contains("/rpc/") }, "reads and read-only aggregate functions only")
    }

    @Test
    fun aSectionSelectionReturnsOnlyThoseBlocksAndAnUnknownNameIs400WithoutEchoingIt() = testApplication {
        application { module() }
        val body = obj(ok("D3-15", "?sections=content,Activity"))
        assertEquals(setOf("content", "activity", "failedBlocks", "generatedAt"), body.keys)
        rejected("D3-15", "?sections=content,secreto-del-cliente", HttpStatusCode.BadRequest, "UNKNOWN_DASHBOARD_SECTION")
        assertFalse("secreto-del-cliente" in call("/api/v1/admin/dashboard/overview?sections=secreto-del-cliente").bodyAsText())
    }

    @Test
    fun aFailedBlockIsAbsentAndNamedAndTheResponseStillConforms() = testApplication {
        application { module() }
        dashboardFailOn = "/rest/v1/auditoria_log"
        val body = obj(ok("D3-15"))
        assertFalse("activity" in body, "a block that cannot be computed is absent, never an empty success")
        assertEquals(listOf("activity"), body["failedBlocks"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue("content" in body && "economy" in body, "the other blocks are still served")
    }
}
