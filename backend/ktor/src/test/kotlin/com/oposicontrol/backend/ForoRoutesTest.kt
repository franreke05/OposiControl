package com.oposicontrol.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
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
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** D5-02..D5-14 (Foro): lo que es de Ktor. Las reglas (puerta, premoderacion, limites, duplicados, texto) las demuestra la base (assertions.sql). */
class ForoRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val id = "3f3f3f3f-3f3f-4f3f-8f3f-3f3f3f3f3f3f"
    private val cat = "4a4a4a4a-4a4a-4a4a-8a4a-4a4a4a4a4a4a"

    private val resolver = CanonicalIdentityResolver(
        verifySubject = { if (it == TEST_TOKEN) TEST_UID else error("firma invalida") },
        directory = { uid -> CanonicalUserIdentity(uid, AccountStatus.fromWire("activa"), true) },
    )

    private class Upstream : EconomyUpstream {
        val rpcs = CopyOnWriteArrayList<Pair<String, String>>()
        var handler: (String, String) -> SupabaseRawResponse = { _, _ -> SupabaseRawResponse(200, "{}") }
        override fun get(path: String): String = "[]"
        override fun rpc(function: String, body: String): SupabaseRawResponse {
            rpcs += function to body
            return handler(function, body)
        }
    }

    private val upstream = Upstream()
    private val limited = CopyOnWriteArrayList<ForoOperation>()

    private fun Application.foroModule() {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        routing { foroRoutes(ForoRuntime(resolver, ForoService(upstream), limit = { op, _ -> limited += op })) }
    }

    private fun rpcError(message: String, detail: String? = null) =
        SupabaseRawResponse(400, """{"code":"P0001","message":"$message","details":${detail?.let { "\"" + it.replace("\"", "\\\"") + "\"" } ?: "null"},"hint":null}""")
    private fun obj(body: String): JsonObject = json.parseToJsonElement(body).jsonObject
    private suspend fun HttpClient.getAs(path: String, token: String? = TEST_TOKEN): HttpResponse = get(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }
    private suspend fun HttpClient.sendJson(path: String, body: String, key: String? = null, token: String? = TEST_TOKEN): HttpResponse = post(path) {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        key?.let { header("Idempotency-Key", it) }
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun readsPassOnlyTheJwtSubjectAndStrictQueries() = testApplication {
        application { foroModule() }
        val client = createClient { }
        upstream.handler = { _, _ -> SupabaseRawResponse(200, """{"items":[],"nextCursor":null}""") }
        val r = client.getAs("/api/v1/app/oposired/publicaciones?categoriaId=$cat&orden=votadas&limit=999")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("no-store", r.headers[HttpHeaders.CacheControl])
        val (fn, args) = upstream.rpcs.single()
        assertEquals("app_foro_list", fn)
        assertEquals(setOf("p_usuario", "p_categoria", "p_orden", "p_cursor", "p_limite"), obj(args).keys)
        assertEquals(TEST_UID, obj(args)["p_usuario"]!!.jsonPrimitive.content)
        assertEquals("50", obj(args)["p_limite"]!!.jsonPrimitive.content, "limit clamped")
        assertEquals(listOf(ForoOperation.READ), limited.toList())
        upstream.rpcs.clear()
        for (bad in listOf("categoriaId=x", "orden=otro", "cursor=a%20b", "limit=abc", "usuarioId=$id", "extra=1")) {
            assertEquals(HttpStatusCode.BadRequest, client.getAs("/api/v1/app/oposired/publicaciones?$bad").status, bad)
        }
        assertEquals(HttpStatusCode.BadRequest, client.getAs("/api/v1/app/oposired/publicaciones/not-a-uuid").status)
        assertEquals(HttpStatusCode.BadRequest, client.getAs("/api/v1/app/oposired/publicaciones/$id/comentarios?x=1").status)
        assertTrue(upstream.rpcs.isEmpty(), "a bad request never reaches the database")
        assertEquals(HttpStatusCode.Unauthorized, client.getAs("/api/v1/app/oposired/canales", token = null).status)
    }

    @Test
    fun theDatabaseKeysBecomeTheCanonicalErrors() = testApplication {
        application { foroModule() }
        val client = createClient { }
        fun check(message: String, detail: String?, status: HttpStatusCode, code: String) = kotlinx.coroutines.runBlocking {
            upstream.handler = { _, _ -> rpcError(message, detail) }
            val r = client.getAs("/api/v1/app/oposired/publicaciones/$id")
            assertEquals(status, r.status, "$message ${r.bodyAsText()}")
            assertEquals(code, obj(r.bodyAsText())["code"]!!.jsonPrimitive.content, message)
            r
        }
        check("FORO_NOT_FOUND", null, HttpStatusCode.NotFound, "NOT_FOUND")
        check("POSTING_RESTRICTED", null, HttpStatusCode.Forbidden, "POSTING_RESTRICTED")
        check("DUPLICATE_POST", null, HttpStatusCode.Conflict, "DUPLICATE_POST")
        check("CONTENT_REJECTED", null, HttpStatusCode.UnprocessableEntity, "CONTENT_REJECTED")
        val limitedResponse = check("FORO_RATE_LIMITED", """{"retryAfter":42}""", HttpStatusCode.TooManyRequests, "RATE_LIMITED")
        assertEquals("42", limitedResponse.headers[HttpHeaders.RetryAfter])
    }

    @Test
    fun createPostIsStrictAndReplayIsA200() = testApplication {
        application { foroModule() }
        val client = createClient { }
        val key = UUID.randomUUID().toString()
        upstream.handler = { _, _ -> SupabaseRawResponse(200, """{"id":"$id","repetida":false}""") }
        val created = client.sendJson("/api/v1/app/oposired/publicaciones", """{"categoriaId":"$cat","titulo":"Hola","cuerpo":"Texto"}""", key)
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val args = obj(upstream.rpcs.last().second)
        assertEquals(setOf("p_usuario", "p_categoria", "p_titulo", "p_cuerpo", "p_clave", "p_imagen"), args.keys)
        assertEquals(key, args["p_clave"]!!.jsonPrimitive.content)
        val noKey = client.sendJson("/api/v1/app/oposired/publicaciones", """{"categoriaId":"$cat","titulo":"Hola","cuerpo":"Texto"}""")
        assertEquals(HttpStatusCode.Created, noKey.status)
        assertEquals(JsonNull, obj(upstream.rpcs.last().second)["p_clave"], "the key is optional")
        upstream.handler = { _, _ -> SupabaseRawResponse(200, """{"id":"$id","repetida":true}""") }
        val replay = client.sendJson("/api/v1/app/oposired/publicaciones", """{"categoriaId":"$cat","titulo":"Hola","cuerpo":"Texto"}""", key)
        assertEquals(HttpStatusCode.OK, replay.status)
        assertEquals("true", replay.headers["Idempotent-Replayed"])
        upstream.rpcs.clear()
        for (bad in listOf("""{}""", """{"categoriaId":"$cat","titulo":"a"}""", """{"categoriaId":"x","titulo":"a","cuerpo":"b"}""", """{"categoriaId":"$cat","titulo":"a","cuerpo":"b","usuarioId":"$id"}""", """{"categoriaId":"$cat","titulo":1,"cuerpo":"b"}""")) {
            assertEquals(HttpStatusCode.BadRequest, client.sendJson("/api/v1/app/oposired/publicaciones", bad, key).status, bad)
        }
        assertEquals(HttpStatusCode.BadRequest, client.sendJson("/api/v1/app/oposired/publicaciones", """{"categoriaId":"$cat","titulo":"a","cuerpo":"b"}""", "not-a-uuid").status)
        assertTrue(upstream.rpcs.isEmpty())
        assertEquals(HttpStatusCode.Unauthorized, client.sendJson("/api/v1/app/oposired/publicaciones", "{}", key, token = null).status)
    }

    @Test
    fun aNulCharacterIsAContentRejectionNeverAServerError() = testApplication {
        application { foroModule() }
        val client = createClient { }
        for (body in listOf("""{"categoriaId":"$cat","titulo":"a b","cuerpo":"texto"}""", """{"categoriaId":"$cat","titulo":"a","cuerpo":"te xto"}""")) {
            val r = client.sendJson("/api/v1/app/oposired/publicaciones", body)
            assertEquals(HttpStatusCode.UnprocessableEntity, r.status, r.bodyAsText())
            assertEquals("CONTENT_REJECTED", obj(r.bodyAsText())["code"]!!.jsonPrimitive.content)
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, client.sendJson("/api/v1/app/oposired/publicaciones/$id/comentarios", """{"cuerpo":"x y"}""").status)
        assertEquals(HttpStatusCode.UnprocessableEntity, client.sendJson("/api/v1/app/oposired/reportes", """{"entidadTipo":"publicacion","entidadId":"$id","motivo":"spam","descripcion":"a b"}""").status)
        assertEquals(HttpStatusCode.UnprocessableEntity, client.sendJson("/api/v1/app/oposired/reportes", """{"entidadTipo":"publicacion","entidadId":"$id","motivo":"sp\u0000am"}""").status)
        assertEquals(HttpStatusCode.BadRequest, client.sendJson("/api/v1/app/oposired/reportes", """{"entidadTipo":"publicacion","entidadId":"$id","motivo":"otra cosa"}""").status)
        assertTrue(upstream.rpcs.isEmpty(), "a NUL never reaches the database")
    }

    @Test
    fun votesDeletesAndReportsGoToTheRightFunctions() = testApplication {
        application { foroModule() }
        val client = createClient { }
        upstream.handler = { _, _ -> SupabaseRawResponse(200, """{"miVoto":true,"meGusta":1}""") }
        suspend fun call(block: suspend () -> HttpResponse) = block().also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        call { client.put("/api/v1/app/oposired/publicaciones/$id/voto") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") } }
        call { client.delete("/api/v1/app/oposired/comentarios/$id/voto") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") } }
        call { client.delete("/api/v1/app/oposired/publicaciones/$id") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") } }
        val calls = upstream.rpcs.map { it.first to obj(it.second) }
        assertEquals(listOf("app_foro_vote", "app_foro_vote", "app_foro_delete_own"), calls.map { it.first })
        assertEquals("publicacion", calls[0].second["p_tipo"]!!.jsonPrimitive.content)
        assertEquals("true", calls[0].second["p_on"]!!.jsonPrimitive.content)
        assertEquals("comentario", calls[1].second["p_tipo"]!!.jsonPrimitive.content)
        assertEquals("false", calls[1].second["p_on"]!!.jsonPrimitive.content)
        assertTrue(calls.all { it.second["p_usuario"]!!.jsonPrimitive.content == TEST_UID })
        upstream.rpcs.clear()
        val ok = client.sendJson("/api/v1/app/oposired/reportes", """{"entidadTipo":"publicacion","entidadId":"$id","motivo":"spam"}""")
        assertEquals(HttpStatusCode.Accepted, ok.status, ok.bodyAsText())
        assertEquals("app_foro_report", upstream.rpcs.single().first)
        upstream.rpcs.clear()
        for (bad in listOf("""{"entidadTipo":"usuario","entidadId":"$id","motivo":"spam"}""", """{"entidadTipo":"publicacion","entidadId":"x","motivo":"spam"}""", """{"entidadTipo":"publicacion","entidadId":"$id"}""",
            """{"entidadTipo":"publicacion","entidadId":"$id","motivo":"spam","reportadoPor":"$id"}""")) {
            assertEquals(HttpStatusCode.BadRequest, client.sendJson("/api/v1/app/oposired/reportes", bad).status, bad)
        }
        assertTrue(upstream.rpcs.isEmpty())
    }
}
