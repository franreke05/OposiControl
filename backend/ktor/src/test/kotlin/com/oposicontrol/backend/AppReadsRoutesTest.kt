package com.oposicontrol.backend

import com.oposicontrol.backend.openapi.OpenApiDocs
import com.oposicontrol.backend.openapi.SchemaChecker
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.net.http.HttpRequest
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FA15: D7-05 (`GET /api/v1/app/me/billing-history`), D5-01 (`GET /api/v1/app/oposired/categorias`) y D5-16
 * (`GET /api/v1/app/opositester/me`) de punta a punta (ruta -> servicio -> pasarela -> transporte real) contra un upstream de
 * pruebas que graba cada peticion. El cable se valida contra el OpenAPI con [SchemaChecker]. Solo lecturas: el doble
 * comprueba que TODA peticion saliente es un GET.
 */
class AppReadsRoutesTest {

    private val docs = OpenApiDocs.locate()
    private val checker = SchemaChecker(docs)
    private val other = "99999999-9999-4999-8999-999999999999"
    private val json = Json { ignoreUnknownKeys = true }
    private var status: String? = "activa"
    private var profileExists = true

    private val resolver = CanonicalIdentityResolver(
        verifySubject = { if (it == TEST_TOKEN) TEST_UID else error("firma invalida") },
        directory = { id -> if (profileExists) CanonicalUserIdentity(id, AccountStatus.fromWire(status), true) else null },
    )

    private fun uuid(n: Int) = "dddddddd-dddd-4ddd-8ddd-%012d".format(n)

    private data class Purchase(val id: String, val at: String, val estado: String = "pagada", val precio: Int? = 499, val tipo: String = "opositickets") {
        val json: String
            get() = """{"id":"$id","producto_codigo":"pack-$tipo","tipo_producto":"$tipo","estado":"$estado","precio_centimos":${precio ?: "null"},"moneda":"EUR","creado_en":"$at"}"""
    }

    private data class Subscription(val id: String, val at: String, val fin: String? = null, val estado: String = "activa") {
        val json: String
            get() = """{"id":"$id","producto_codigo":"premium-mensual","estado":"$estado","inicio_en":"$at","fin_en":${fin?.let { "\"$it\"" } ?: "null"}}"""
    }

    /** Upstream de pruebas con la semantica de PostgREST que usa la pasarela: keyset descendente por la columna de la tabla y limit. */
    private class Upstream {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val urls: List<String> get() = sent.map { URLDecoder.decode(it.uri().toString(), Charsets.UTF_8) }
        var purchases: List<Purchase> = emptyList()
        var subscriptions: List<Subscription> = emptyList()
        var categories = "[]"
        var opositester = "[]"
        var failOn: String? = null

        fun respond(rawUri: String): V1StubResponse {
            val uri = URLDecoder.decode(rawUri, Charsets.UTF_8)
            if (failOn != null && uri.contains(failOn!!)) return V1StubResponse(500, """{"message":"boom"}""")
            val body = when {
                uri.contains("/rest/v1/compras_usuario") -> rows(uri, "creado_en", purchases.map { Triple(it.at, it.id, it.json) })
                uri.contains("/rest/v1/suscripciones") -> rows(uri, "inicio_en", subscriptions.map { Triple(it.at, it.id, it.json) })
                uri.contains("/rest/v1/categorias_oposired") -> categories
                uri.contains("/rest/v1/estado_opositester_usuario") -> opositester
                else -> error("peticion inesperada al upstream: $uri")
            }
            return V1StubResponse(200, body)
        }

        private fun rows(uri: String, column: String, all: List<Triple<String, String, String>>): String {
            val query = uri.substringAfter('?')
            fun at(row: Triple<String, String, String>) = OffsetDateTime.parse(row.first).toInstant()
            var rows = all.asSequence()
            Regex("&or=\\($column\\.lt\\.([^,]+),and\\($column\\.eq\\.([^,]+),id\\.lt\\.([^)]+)\\)\\)").find(query)?.let { m ->
                val cut = OffsetDateTime.parse(m.groupValues[1]).toInstant()
                val id = m.groupValues[3]
                rows = rows.filter { at(it).isBefore(cut) || (at(it) == cut && it.second < id) }
            }
            val limit = Regex("&limit=(\\d+)").find(query)!!.groupValues[1].toInt()
            return "[" + rows.sortedWith(compareByDescending<Triple<String, String, String>> { at(it) }.thenByDescending { it.second })
                .take(limit).joinToString(",") { it.third } + "]"
        }
    }

    private val upstream = Upstream()

    private fun Application.readsModule() {
        install(ContentNegotiation) { json(testJson) }
        installRequestCorrelation()
        installBackendStatusPages()
        val transport = SupabaseRequestTransport.forTesting(
            "https://stub.supabase.test",
            "stub-key",
            V1StubHttpClient({ upstream.sent += it }, upstream::respond),
        )
        val gateway = AppReadsGateway(upstream = { path -> transport.get(path) })
        routing { appReadsRoutes(AppReadsRuntime(resolver, AppReadsService(gateway))) }
    }

    private suspend fun HttpClient.getAs(path: String, token: String? = TEST_TOKEN): HttpResponse =
        get(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }

    private fun obj(body: String): JsonObject = json.parseToJsonElement(body).jsonObject

    private fun code(body: String): String = obj(body)["code"]!!.jsonPrimitive.content

    private fun assertConforms(contractId: String, body: String) {
        val op = docs.operations().first { it.contractId == contractId }
        val response = docs.responses(op).getValue("200")
        val schema = ((response.value as Map<*, *>)["content"] as Map<*, *>).let { (it["application/json"] as Map<*, *>)["schema"] }
        val problems = mutableListOf<String>()
        checker.validate(OpenApiDocs.Node(schema, response.file), json.parseToJsonElement(body), "$", problems)
        assertTrue(problems.isEmpty(), "$contractId does not conform to the OpenAPI:\n${problems.joinToString("\n")}\n$body")
    }

    private fun assertReadOnly() {
        assertTrue(upstream.sent.isNotEmpty())
        assertTrue(upstream.sent.all { it.method() == "GET" }, "solo lectura: ${upstream.sent.map { it.method() }}")
        upstream.urls.forEach { assertFalse(it.contains(other), "el uuid ajeno nunca sale: $it") }
    }

    private fun assertOwnRowsOnly() {
        assertReadOnly()
        upstream.urls.forEach { assertTrue(it.contains("usuario_id=eq.$TEST_UID"), "toda lectura va acotada al sub del JWT: $it") }
    }

    private val billingPath = "/api/v1/app/me/billing-history"
    private val categoriesPath = "/api/v1/app/oposired/categorias"
    private val testerPath = "/api/v1/app/opositester/me"
    private val allPaths = listOf(billingPath, categoriesPath, testerPath)

    private fun seedBilling() {
        upstream.purchases = listOf(
            Purchase(uuid(1), "2026-10-05T10:00:00.123456+00:00"),
            Purchase(uuid(2), "2026-10-03T10:00:00+00:00", estado = "pendiente", precio = null, tipo = "servicio"),
            Purchase(uuid(3), "2026-09-01T10:00:00+00:00", estado = "reembolsada"),
        )
        upstream.subscriptions = listOf(
            Subscription(uuid(11), "2026-10-04T08:00:00+00:00", fin = "2026-11-04T08:00:00+00:00"),
            Subscription(uuid(12), "2026-10-03T10:00:00+00:00", estado = "cancelada"), // mismo instante que la compra 2: desempata el id
            Subscription(uuid(13), "2026-08-01T00:00:00+00:00", estado = "caducada"),
        )
    }

    // =================================================================== D7-05

    @Test
    fun billingHistoryMergesPurchasesAndSubscriptionsNewestFirstAndConforms() = testApplication {
        application { readsModule() }
        seedBilling()
        val response = client.getAs(billingPath)
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        assertEquals("private, no-store", response.headers[HttpHeaders.CacheControl])
        assertConforms("D7-05", body)
        val items = obj(body)["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(uuid(1), uuid(11), uuid(12), uuid(2), uuid(3), uuid(13)), items.map { it["id"]!!.jsonPrimitive.content })
        assertNull(obj(body)["nextCursor"], "ultima pagina")

        val purchase = items[0]
        assertEquals("purchase", purchase["kind"]!!.jsonPrimitive.content)
        assertEquals("opositickets", purchase["tipo"]!!.jsonPrimitive.content)
        assertEquals(499, purchase["precioCentimos"]!!.jsonPrimitive.content.toInt())
        assertEquals("EUR", purchase["moneda"]!!.jsonPrimitive.content)
        assertEquals("2026-10-05T10:00:00.123456Z", purchase["occurredAt"]!!.jsonPrimitive.content)
        assertNull(purchase["startsAt"])
        assertNull(items[3]["precioCentimos"], "una compra pendiente sin precio no inventa un 0")

        val subscription = items[1]
        assertEquals("subscription", subscription["kind"]!!.jsonPrimitive.content)
        assertEquals("suscripcion", subscription["tipo"]!!.jsonPrimitive.content)
        assertEquals("2026-10-04T08:00:00Z", subscription["startsAt"]!!.jsonPrimitive.content)
        assertEquals("2026-11-04T08:00:00Z", subscription["endsAt"]!!.jsonPrimitive.content)
        assertNull(subscription["precioCentimos"])
        assertNull(subscription["moneda"])

        upstream.urls.forEach { url ->
            listOf("payload_validacion", "identificador", "proveedor", "select=*").forEach { assertFalse(url.contains(it), "columna interna pedida: $url") }
        }
        assertOwnRowsOnly()
    }

    @Test
    fun billingHistoryCursorWalksTheMergedListWithoutSkippingOrRepeating() = testApplication {
        application { readsModule() }
        seedBilling()
        val seen = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val body = client.getAs("$billingPath?limit=2" + (cursor?.let { "&cursor=$it" } ?: "")).bodyAsText()
            assertConforms("D7-05", body)
            val page = obj(body)
            val ids = page["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
            assertTrue(ids.size <= 2)
            seen += ids
            cursor = page["nextCursor"]?.jsonPrimitive?.content
            pages += 1
        } while (cursor != null && pages < 10)
        assertEquals(listOf(uuid(1), uuid(11), uuid(12), uuid(2), uuid(3), uuid(13)), seen)
        assertEquals(3, pages)
        assertTrue(upstream.urls.all { it.contains("&limit=3") }, "cada tabla se pide con limit + 1")
        assertTrue(upstream.urls.any { it.contains("compras_usuario") && it.contains("&or=(creado_en.lt.") })
        assertTrue(upstream.urls.any { it.contains("suscripciones") && it.contains("&or=(inicio_en.lt.") })
        assertOwnRowsOnly()
    }

    @Test
    fun billingHistoryKindReadsOnlyOneTableAndACursorOfAnotherFilterIsRejected() = testApplication {
        application { readsModule() }
        seedBilling()
        val onlyPurchases = obj(client.getAs("$billingPath?kind=purchase&limit=1").bodyAsText())
        assertEquals(listOf("purchase"), onlyPurchases["items"]!!.jsonArray.map { it.jsonObject["kind"]!!.jsonPrimitive.content })
        assertTrue(upstream.urls.none { it.contains("suscripciones") }, "kind=purchase no lee suscripciones")
        val purchaseCursor = onlyPurchases["nextCursor"]!!.jsonPrimitive.content

        upstream.sent.clear()
        val onlySubs = obj(client.getAs("$billingPath?kind=subscription").bodyAsText())
        assertEquals(listOf(uuid(11), uuid(12), uuid(13)), onlySubs["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertTrue(upstream.urls.none { it.contains("compras_usuario") })

        upstream.sent.clear()
        val foreign = client.getAs("$billingPath?kind=subscription&cursor=$purchaseCursor")
        assertEquals(HttpStatusCode.BadRequest, foreign.status)
        assertEquals("INVALID_CURSOR", code(foreign.bodyAsText()))
        assertTrue(upstream.sent.isEmpty(), "un cursor de otra lista no llega a la base")
    }

    @Test
    fun billingHistoryRejectsBadInputBeforeAnyRead() = testApplication {
        application { readsModule() }
        val expected = mapOf(
            "$billingPath?kind=refund" to "INVALID_FILTER",
            "$billingPath?limit=0" to "VALIDATION",
            "$billingPath?limit=abc" to "VALIDATION",
            "$billingPath?cursor=not-a-cursor" to "INVALID_CURSOR",
        )
        expected.forEach { (path, expectedCode) ->
            val response = client.getAs(path)
            assertEquals(HttpStatusCode.BadRequest, response.status, path)
            assertEquals(expectedCode, code(response.bodyAsText()), path)
        }
        assertTrue(upstream.sent.isEmpty(), "ninguna lectura con parametros invalidos")
    }

    @Test
    fun billingHistoryClampsTheLimitAndIgnoresAnyUserParameter() = testApplication {
        application { readsModule() }
        seedBilling()
        val response = client.getAs("$billingPath?limit=500&usuarioId=$other&userId=$other")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(upstream.urls.all { it.contains("&limit=51") }, "limit recortado a 50 (+1): ${upstream.urls}")
        assertOwnRowsOnly()
    }

    @Test
    fun billingHistoryFailsClosedOnACorruptRow() = testApplication {
        application { readsModule() }
        upstream.purchases = listOf(Purchase("no-es-un-uuid", "2026-10-05T10:00:00+00:00"))
        val response = client.getAs(billingPath)
        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertEquals("UPSTREAM_CONTRACT_ERROR", code(response.bodyAsText()))
    }

    // =================================================================== D5-01

    @Test
    fun categoriesReturnTheActiveCatalogInOrderAndConform() = testApplication {
        application { readsModule() }
        upstream.categories = """[
            {"id":"${uuid(1)}","nombre":"General","slug":"general","descripcion":null,"posicion":1},
            {"id":"${uuid(2)}","nombre":"Temario","slug":"temario","descripcion":"Dudas del temario","posicion":2}
        ]"""
        val response = client.getAs(categoriesPath)
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        assertEquals("private, max-age=300", response.headers[HttpHeaders.CacheControl])
        assertConforms("D5-01", body)
        val items = obj(body)["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("general", "temario"), items.map { it["slug"]!!.jsonPrimitive.content })
        assertNull(items[0]["descripcion"])
        assertEquals("Dudas del temario", items[1]["descripcion"]!!.jsonPrimitive.content)
        val url = upstream.urls.single()
        assertTrue(url.contains("activa=eq.true"), "solo activas: $url")
        assertTrue(url.contains("order=posicion.asc,nombre.asc,id.asc") && url.contains("&limit=50"), url)
        assertFalse(url.contains("usuario_id"), "el catalogo es el mismo para todos")
        assertReadOnly()
    }

    @Test
    fun anEmptyCategoryCatalogIsAValidEmptyListAndTheCapIsFifty() = testApplication {
        application { readsModule() }
        val empty = client.getAs(categoriesPath).bodyAsText()
        assertConforms("D5-01", empty)
        assertTrue(obj(empty)["items"]!!.jsonArray.isEmpty())
        // Un upstream que ignorase el limit no puede sacar mas de 50 por el cable.
        upstream.categories = (1..60).joinToString(",", "[", "]") { """{"id":"${uuid(it)}","nombre":"C$it","slug":"c$it","descripcion":null,"posicion":$it}""" }
        val capped = client.getAs(categoriesPath).bodyAsText()
        assertConforms("D5-01", capped)
        assertEquals(50, obj(capped)["items"]!!.jsonArray.size)
    }

    // =================================================================== D5-16

    @Test
    fun opositesterReturnsOwnCountersWithNullLevelAndConforms() = testApplication {
        application { readsModule() }
        upstream.opositester = """[{"reportes_enviados":7,"reportes_validados":3,"tickets_ganados_reportes":15}]"""
        val response = client.getAs("$testerPath?usuarioId=$other")
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        assertEquals("private, no-store", response.headers[HttpHeaders.CacheControl])
        assertConforms("D5-16", body)
        val state = obj(body)
        assertEquals(7, state["reportesEnviados"]!!.jsonPrimitive.content.toInt())
        assertEquals(3, state["reportesValidados"]!!.jsonPrimitive.content.toInt())
        assertEquals(15, state["ticketsGanados"]!!.jsonPrimitive.content.toInt())
        assertNull(state["nivel"], "DEC-028: nivel null (ausente) hasta que se definan los niveles")
        assertFalse(body.contains("\"data\""), "objeto DESNUDO")
        assertFalse(upstream.urls.single().contains("nivel_opositester"), "el nivel almacenado no se pide")
        assertOwnRowsOnly()
    }

    @Test
    fun aUserWithoutOpositesterRowGetsZeros() = testApplication {
        application { readsModule() }
        val body = client.getAs(testerPath).bodyAsText()
        assertConforms("D5-16", body)
        assertEquals(setOf(0), listOf("reportesEnviados", "reportesValidados", "ticketsGanados").map { obj(body)[it]!!.jsonPrimitive.content.toInt() }.toSet())
        assertOwnRowsOnly()
    }

    // =================================================================== identidad y fallo de upstream (los tres)

    @Test
    fun everyReadRequiresAValidTokenBeforeTouchingTheDatabase() = testApplication {
        application { readsModule() }
        allPaths.forEach { path ->
            listOf(null, "otro-token").forEach { token ->
                val response = client.getAs(path, token)
                assertEquals(HttpStatusCode.Unauthorized, response.status, "$path token=$token")
                assertEquals("UNAUTHENTICATED", code(response.bodyAsText()), path)
            }
        }
        assertTrue(upstream.sent.isEmpty())
    }

    @Test
    fun aBlockedDeletingUnknownOrProfilelessAccountIsRejectedFailClosed() = testApplication {
        application { readsModule() }
        val cases = listOf("bloqueada" to "ACCOUNT_BLOCKED", "eliminacion_pendiente" to "ACCOUNT_DELETION_PENDING", "estado_nuevo" to null, null to null)
        cases.forEach { (wire, expectedCode) ->
            status = wire
            allPaths.forEach { path ->
                val response = client.getAs(path)
                assertEquals(HttpStatusCode.Forbidden, response.status, "$path estado=$wire")
                expectedCode?.let { assertEquals(it, code(response.bodyAsText()), path) }
            }
        }
        status = "activa"
        profileExists = false
        allPaths.forEach { path ->
            val response = client.getAs(path)
            assertEquals(HttpStatusCode.Forbidden, response.status, path)
            assertEquals("PROFILE_MISSING", code(response.bodyAsText()), path)
        }
        assertTrue(upstream.sent.isEmpty(), "una cuenta no activa nunca llega a la base")
    }

    @Test
    fun anUpstreamFailureIsACanonicalErrorAndNeverAnEmptySuccess() = testApplication {
        application { readsModule() }
        seedBilling()
        mapOf(
            billingPath to "suscripciones",
            categoriesPath to "categorias_oposired",
            testerPath to "estado_opositester_usuario",
        ).forEach { (path, table) ->
            upstream.failOn = table
            val response = client.getAs(path)
            val body = response.bodyAsText()
            assertTrue(response.status.value in setOf(502, 503), "$path -> ${response.status}")
            assertTrue(code(body).startsWith("UPSTREAM_"), "$path -> $body")
            assertFalse(body.contains("boom"), "el detalle del upstream no sale: $body")
        }
    }

    @Test
    fun withoutRuntimeTheRoutesFailClosed() = testApplication {
        application {
            install(ContentNegotiation) { json(testJson) }
            installRequestCorrelation()
            installBackendStatusPages()
            routing { appReadsRoutes(null) }
        }
        allPaths.forEach { assertEquals(HttpStatusCode.InternalServerError, client.getAs(it).status, it) }
    }

}
