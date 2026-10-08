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
 * FA12 D7-27 `GET /api/v1/admin/suscripciones` de punta a punta (ruta -> [AdminV1Gateway] -> transporte real) contra un
 * PostgREST falso con estado; el cable se valida contra `openapi/commerce/commerce.yaml`.
 */
class AdminSubscriptionRoutesTest {

    private val docs = OpenApiDocs.locate()
    private val checker = SchemaChecker(docs)
    private val json = Json { ignoreUnknownKeys = true }

    private val adminUid = "11111111-aaaa-4aaa-8aaa-000000000001"
    private val userUid = "22222222-bbbb-4bbb-8bbb-000000000002"
    private val adminToken = "admin-token"
    private val userToken = "user-token"
    private val ana = "33333333-cccc-4ccc-8ccc-000000000003"
    private val sinPerfil = "33333333-cccc-4ccc-8ccc-000000000004"
    private val path = "/api/v1/admin/suscripciones"

    private inner class Upstream {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val subscriptions = mutableListOf<JsonObject>()
        var fail = false
        private var seq = 0

        fun at(minute: Int): String = "2026-10-08T10:%02d:00.123456+00:00".format(minute)

        fun seed(minute: Int, usuario: String = ana, estado: String = "activa", proveedor: String = "google_play", inicioEn: String = at(minute), finEn: String? = null): String {
            val id = "bbbbbbbb-0000-4000-8000-%012d".format(++seq)
            subscriptions += JsonObject(
                mapOf(
                    "id" to JsonPrimitive(id), "usuario_id" to JsonPrimitive(usuario), "proveedor" to JsonPrimitive(proveedor),
                    "producto_codigo" to JsonPrimitive("premium_mensual"), "estado" to JsonPrimitive(estado), "inicio_en" to JsonPrimitive(inicioEn),
                    "fin_en" to (finEn?.let { JsonPrimitive(it) } ?: JsonNull), "renovacion_en" to JsonNull,
                    // Datos del proveedor: existen en la tabla y NUNCA deben salir.
                    "identificador_externo" to JsonPrimitive("purchase-token-secret"), "payload_validacion" to JsonObject(mapOf("raw" to JsonPrimitive("secret"))),
                ),
            )
            return id
        }

        val requests: List<String> get() = sent.map { "${it.method()} ${URLDecoder.decode(it.uri().toString(), Charsets.UTF_8)}" }

        private fun text(row: JsonObject, column: String): String? = (row[column] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

        private val keyset = Regex("""^\(inicio_en\.lt\.([^,]+),and\(inicio_en\.eq\.[^,]+,id\.lt\.([0-9a-f-]+)\)\)$""")

        fun respond(rawUri: String): V1StubResponse {
            if (fail) return V1StubResponse(500, """{"message":"boom"}""")
            val uri = URLDecoder.decode(rawUri, Charsets.UTF_8)
            val table = uri.substringAfter("/rest/v1/").substringBefore('?')
            val q = uri.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate { it.substringBefore('=') to it.substringAfter('=', "") }
            if (table == "perfiles_usuario") {
                val wanted = q.getValue("usuario_id").removePrefix("in.(").removeSuffix(")").split(',')
                return V1StubResponse(200, if (ana in wanted) """[{"usuario_id":"$ana","nombre_mostrar":"Ana Suscriptora"}]""" else "[]")
            }
            check(table == "suscripciones") { "tabla inesperada: $uri" }
            val columns = q.getValue("select").split(',')
            var rows: List<JsonObject> = subscriptions.toList()
            q.forEach { (key, value) ->
                rows = when {
                    key in setOf("select", "order", "limit") -> rows
                    key == "or" -> {
                        val (ts, id) = (keyset.matchEntire(value) ?: error("keyset inesperado: $value")).destructured
                        rows.filter { r ->
                            val c = text(r, "inicio_en")!!.compareTo(ts)
                            c < 0 || (c == 0 && text(r, "id")!! < id)
                        }
                    }
                    value.startsWith("eq.") -> rows.filter { text(it, key) == value.removePrefix("eq.") }
                    else -> error("filtro inesperado: $key=$value")
                }
            }
            check(q["order"] == "inicio_en.desc,id.desc") { "orden inesperado: ${q["order"]}" }
            rows = rows.sortedWith(compareByDescending<JsonObject> { text(it, "inicio_en") }.thenByDescending { text(it, "id") }).take(q.getValue("limit").toInt())
            return V1StubResponse(200, JsonArray(rows.map { r -> JsonObject(r.filterKeys { it in columns }) }).toString())
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

    private suspend fun ApplicationTestBuilder.call(query: String = "", token: String? = adminToken): HttpResponse =
        client.get(path + query) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }

    private fun obj(body: String): JsonObject = json.parseToJsonElement(body).jsonObject

    private fun ids(body: String): List<String> = obj(body)["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    private fun next(body: String): String? = (obj(body)["nextCursor"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private val op = docs.operations().first { it.contractId == "D7-27" }

    private suspend fun ApplicationTestBuilder.ok(query: String = ""): String {
        val response = call(query)
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, "$query -> $body")
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val ok = docs.responses(op).getValue("200")
        val schema = ((ok.value as Map<*, *>)["content"] as Map<*, *>).let { (it["application/json"] as Map<*, *>)["schema"] }
        val problems = mutableListOf<String>()
        checker.validate(OpenApiDocs.Node(schema, ok.file), json.parseToJsonElement(body), "$", problems)
        assertTrue(problems.isEmpty(), "D7-27 200 does not conform to the frozen OpenAPI:\n${problems.joinToString("\n")}\n$body")
        return body
    }

    private suspend fun ApplicationTestBuilder.rejected(query: String, status: HttpStatusCode, code: String, noUpstream: Boolean = true) {
        val before = upstream.sent.size
        val response = call(query)
        val body = obj(response.bodyAsText())
        assertEquals(status, response.status, "$query -> $body")
        assertEquals(code, body["code"]!!.jsonPrimitive.content, query)
        assertTrue(body["message"]!!.jsonPrimitive.content.isNotBlank() && body["requestId"]!!.jsonPrimitive.content.isNotBlank(), body.toString())
        assertTrue(status.value.toString() in docs.responses(op), "D7-27 does not document $status")
        assertTrue(code in (op.op["x-error-codes"] as List<*>), "D7-27 does not list $code")
        if (noUpstream) assertEquals(before, upstream.sent.size, "$query reached the upstream")
    }

    @Test
    fun anUnauthenticatedCallerIs401AndANonAdminIs403WithoutTouchingTheDatabase() = testApplication {
        application { module() }
        upstream.seed(1)
        assertEquals(HttpStatusCode.Unauthorized, call(token = null).status)
        val forbidden = call(token = userToken)
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
        assertEquals("FORBIDDEN", obj(forbidden.bodyAsText())["code"]!!.jsonPrimitive.content)
        assertTrue(upstream.sent.isEmpty())
    }

    @Test
    fun theListIsKeysetPagedNewestFirstAndConformsToTheContract() = testApplication {
        application { module() }
        val created = (1..5).map { upstream.seed(it) }
        val seen = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val body = ok("?limit=2" + (cursor?.let { "&cursor=$it" } ?: ""))
            seen += ids(body)
            cursor = next(body)
            pages++
        } while (cursor != null)
        assertEquals(created.reversed(), seen)
        assertEquals(3, pages)
        assertTrue(upstream.requests.filter { "/suscripciones?" in it }.all { "limit=3" in it }, "limit + 1, never unbounded")
        assertTrue(upstream.sent.all { it.method() == "GET" }, "read-only")
    }

    @Test
    fun aRowCarriesOnlyTheContractFieldsInUtcAndNeverProviderData() = testApplication {
        application { module() }
        upstream.seed(1, finEn = "2026-11-08T12:00:00+02:00")
        upstream.seed(2, usuario = sinPerfil)
        val body = ok()
        val rows = obj(body)["items"]!!.jsonArray.map { it.jsonObject }
        val withProfile = rows.single { it["usuarioId"]!!.jsonPrimitive.content == ana }
        assertEquals("Ana Suscriptora", withProfile["usuarioNombre"]!!.jsonPrimitive.content)
        assertEquals("2026-10-08T10:01:00.123456Z", withProfile["inicioEn"]!!.jsonPrimitive.content, "RFC 3339 UTC, microseconds kept")
        assertEquals("2026-11-08T10:00:00Z", withProfile["finEn"]!!.jsonPrimitive.content, "an offset instant is normalised to UTC")
        assertEquals("google_play", withProfile["proveedor"]!!.jsonPrimitive.content)
        assertEquals("premium_mensual", withProfile["productoCodigo"]!!.jsonPrimitive.content)
        val withoutProfile = rows.single { it["usuarioId"]!!.jsonPrimitive.content == sinPerfil }
        assertFalse("usuarioNombre" in withoutProfile, "an unresolved name is null (omitted), never the user id")
        assertTrue(rows.all { "canceladaEn" !in it }, "no cancellation instant column in production")
        // Los datos del proveedor ni se piden ni salen.
        assertFalse("secret" in body || "identificador" in body.lowercase() || "payload" in body.lowercase(), body)
        assertTrue(upstream.requests.single { "/suscripciones?" in it }.contains("select=${AdminSubscriptionQueries.COLUMNS}&"))
    }

    @Test
    fun theListFiltersByEstadoProveedorAndUser() = testApplication {
        application { module() }
        val active = upstream.seed(1)
        val cancelled = upstream.seed(2, estado = "cancelada")
        val stripe = upstream.seed(3, proveedor = "stripe")
        val other = upstream.seed(4, usuario = sinPerfil)
        assertEquals(listOf(cancelled), ids(ok("?estado=cancelada")))
        assertEquals(listOf(stripe), ids(ok("?proveedor=stripe")))
        assertEquals(listOf(other), ids(ok("?usuarioId=$sinPerfil")))
        assertEquals(listOf(stripe, active), ids(ok("?estado=activa&usuarioId=${ana.uppercase()}")), "a uuid is matched case-insensitively")
    }

    @Test
    fun invalidParametersAre400BeforeAnyRead() = testApplication {
        application { module() }
        rejected("?estado=bogus", HttpStatusCode.BadRequest, "INVALID_FILTER")
        rejected("?proveedor=paypal", HttpStatusCode.BadRequest, "INVALID_FILTER")
        rejected("?usuarioId=not-a-uuid", HttpStatusCode.BadRequest, "INVALID_FILTER")
        rejected("?estado=activa%26select%3D*", HttpStatusCode.BadRequest, "INVALID_FILTER")
        rejected("?limit=abc", HttpStatusCode.BadRequest, "VALIDATION")
        rejected("?limit=0", HttpStatusCode.BadRequest, "VALIDATION")
        rejected("?cursor=garbage", HttpStatusCode.BadRequest, "INVALID_CURSOR")
    }

    @Test
    fun aCursorIsBoundToItsFiltersAndALimitAboveTheMaximumIsClamped() = testApplication {
        application { module() }
        (1..3).forEach { upstream.seed(it) }
        val cursor = next(ok("?limit=2"))!!
        rejected("?limit=2&estado=activa&cursor=$cursor", HttpStatusCode.BadRequest, "INVALID_CURSOR")
        ok("?limit=5000")
        assertTrue(upstream.requests.last { "/suscripciones?" in it }.endsWith("limit=101"))
    }

    @Test
    fun anEmptyTableIsAnEmptyPage() = testApplication {
        application { module() }
        val body = ok()
        assertTrue(ids(body).isEmpty())
        assertEquals(null, next(body))
        assertTrue(upstream.requests.none { "perfiles_usuario" in it }, "no name lookup for an empty page")
    }

    @Test
    fun anUpstreamFailureIs502AndACorruptInstantIsAnUpstreamContractError() = testApplication {
        application { module() }
        upstream.seed(1, inicioEn = "not-a-date")
        rejected("", HttpStatusCode.BadGateway, "UPSTREAM_CONTRACT_ERROR", noUpstream = false)
        upstream.fail = true
        rejected("", HttpStatusCode.BadGateway, "UPSTREAM_ERROR", noUpstream = false)
    }
}
