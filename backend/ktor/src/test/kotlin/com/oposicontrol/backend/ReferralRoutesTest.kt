package com.oposicontrol.backend

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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** D9-01 / D9-02 (Invita y gana): lo que es de Ktor. Las reglas (estados, pago, topes, privacidad) las demuestra la base (assertions.sql). */
class ReferralRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val resolver = CanonicalIdentityResolver(
        verifySubject = { if (it == TEST_TOKEN) TEST_UID else error("firma invalida") },
        directory = { id -> CanonicalUserIdentity(id, AccountStatus.fromWire("activa"), true) },
    )

    private class Upstream : EconomyUpstream {
        val rpcs = CopyOnWriteArrayList<Pair<String, String>>()
        var handler: (String, String) -> SupabaseRawResponse = { _, _ -> SupabaseRawResponse(500, "{}") }
        override fun get(path: String): String = "[]"
        override fun rpc(function: String, body: String): SupabaseRawResponse {
            rpcs += function to body
            return handler(function, body)
        }
    }

    private val upstream = Upstream()
    private val limited = CopyOnWriteArrayList<ReferralOperation>()

    private fun Application.referralModule() {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        routing { referralRoutes(ReferralRuntime(resolver, ReferralService(upstream), limit = { op, _ -> limited += op })) }
    }

    private fun rpcError(message: String) = SupabaseRawResponse(400, """{"code":"P0001","message":"$message","details":null,"hint":null}""")
    private suspend fun HttpClient.getAs(path: String, token: String? = TEST_TOKEN): HttpResponse = get(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }
    private fun obj(body: String): JsonObject = json.parseToJsonElement(body).jsonObject

    @Test
    fun mineReadsOnlyForTheJwtSubject() = testApplication {
        application { referralModule() }
        upstream.handler = { _, _ -> SupabaseRawResponse(200, """{"codigo":"ABCDEFGH","invitados":{"total":1,"premiados":0},"otGanados":0,"configuracion":{"otPorInvitacion":null}}""") }
        val client = createClient { }
        val r = client.getAs("/api/v1/app/me/invitacion")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("no-store", r.headers[HttpHeaders.CacheControl])
        val (fn, args) = upstream.rpcs.single()
        assertEquals("app_referral_get", fn)
        assertEquals(setOf("p_usuario"), obj(args).keys)
        assertEquals(TEST_UID, obj(args)["p_usuario"]!!.jsonPrimitive.content)
        assertEquals(listOf(ReferralOperation.READ), limited.toList())
        upstream.rpcs.clear()
        for (bad in listOf("usuarioId=x", "codigo=ABC")) assertEquals(HttpStatusCode.BadRequest, client.getAs("/api/v1/app/me/invitacion?$bad").status, bad)
        assertTrue(upstream.rpcs.isEmpty())
        assertEquals(HttpStatusCode.Unauthorized, client.getAs("/api/v1/app/me/invitacion", token = null).status)
    }

    @Test
    fun listIsStrictAndMapsAnInvalidCursor() = testApplication {
        application { referralModule() }
        val client = createClient { }
        upstream.handler = { _, _ -> SupabaseRawResponse(200, """{"items":[{"handle":"ana","premiadaEn":"2026-10-16T10:00:00+00:00"}],"nextCursor":null}""") }
        assertEquals(HttpStatusCode.OK, client.getAs("/api/v1/app/me/invitaciones").status)
        var args = obj(upstream.rpcs.last().second)
        assertEquals("20", args["p_limite"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, args["p_cursor"])
        assertEquals(setOf("p_usuario", "p_cursor", "p_limite"), args.keys)
        assertEquals(HttpStatusCode.OK, client.getAs("/api/v1/app/me/invitaciones?cursor=YWJj-_%3D&limit=5").status)
        args = obj(upstream.rpcs.last().second)
        assertEquals("5", args["p_limite"]!!.jsonPrimitive.content)
        client.getAs("/api/v1/app/me/invitaciones?limit=999"); client.getAs("/api/v1/app/me/invitaciones?limit=0")
        assertEquals(listOf("50", "1"), upstream.rpcs.takeLast(2).map { obj(it.second)["p_limite"]!!.jsonPrimitive.content }, "limit is clamped to 1..50")
        upstream.rpcs.clear()
        for (bad in listOf("limit=abc", "cursor=a%20b", "cursor=" + "A".repeat(201), "usuarioId=x")) {
            assertEquals(HttpStatusCode.BadRequest, client.getAs("/api/v1/app/me/invitaciones?$bad").status, bad)
        }
        assertTrue(upstream.rpcs.isEmpty(), "una peticion invalida no llega a la base")
        upstream.handler = { _, _ -> rpcError("CURSOR_INVALID") }
        assertEquals(HttpStatusCode.BadRequest, client.getAs("/api/v1/app/me/invitaciones?cursor=AAAA").status)
    }

    @Test
    fun attachIsNeutralAndNeverThrows() {
        upstream.handler = { _, _ -> SupabaseRawResponse(500, "boom") }
        val service = ReferralService(upstream)
        runBlocking {
            service.attach(TEST_UID, "ABCDEFGH")   // upstream down: swallowed
            service.attach(TEST_UID, null)
            service.attach(TEST_UID, "   ")
            service.attach(TEST_UID, "X".repeat(17))
        }
        assertEquals(1, upstream.rpcs.size, "only the one plausible code reached the database")
        assertEquals("app_referral_attach", upstream.rpcs.single().first)
        assertEquals(setOf("p_invitado", "p_codigo", "p_ip_hash"), obj(upstream.rpcs.single().second).keys)
    }
}
