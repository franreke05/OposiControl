package com.oposicontrol.backend

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MASTER-SEC-010: el limite por usuario de las rutas `/api/v1/app/me` corre con el `sub` verificado y ANTES de leer
 * `perfiles_usuario`: una cuenta denegada tambien consume cupo y un cupo agotado no paga la lectura upstream.
 * MASTER-AUTH-015: una repeticion de `DELETE /api/v1/app/me` reintenta la revocacion de sesiones si no consta `hecha`.
 */
class CanonicalProfileLimiterAndDeletionRetryTest {

    // ---------------------------------------------------------------- MASTER-SEC-010

    @Test
    fun anExhaustedLimitAnswers429WithoutReadingTheProfileEvenForABlockedAccount() = testApplication {
        val lookups = AtomicInteger()
        val resolver = CanonicalIdentityResolver(
            verifySubject = { TEST_UID },
            directory = { id -> lookups.incrementAndGet(); CanonicalUserIdentity(id, AccountStatus.BLOQUEADA, true) },
        )
        application {
            install(ContentNegotiation) { json(testJson) }
            installRequestCorrelation()
            installBackendStatusPages()
            routing {
                get("/probe") {
                    resolver.activeIdentity(call) { throw CanonicalErrorCode.RATE_LIMITED.exception(retryAfterSeconds = 42) }
                    call.respond(HttpStatusCode.OK)
                }
            }
        }
        val response = client.get("/probe") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }
        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals(0, lookups.get(), "el limite agotado no paga la lectura de perfiles_usuario")
    }

    @Test
    fun deniedAccountsConsumeThePerUserQuotaOnEveryMeRoute() = testApplication {
        val limiter = TestCountingLimiter()
        val env = CanonicalTestEnv(limiter = limiter).apply { status = "bloqueada" }
        application { env.mount(this) }

        val me = client.get("/api/v1/app/me") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }
        assertEquals(HttpStatusCode.Forbidden, me.status)
        val onboarding = client.request("/api/v1/app/me/onboarding") {
            method = HttpMethod.Post
            header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, onboarding.status)
        assertEquals(
            listOf(OposiBotsAuthOperation.ME to TEST_UID, OposiBotsAuthOperation.PROFILE_WRITE to TEST_UID),
            limiter.subjectChecks.toList(),
            "antes, una cuenta bloqueada salia con 403 sin pasar por el limitador",
        )
    }

    @Test
    fun aRejectingLimiterWinsOverTheAccountStateOnTheDeletionRoute() = testApplication {
        val env = CanonicalTestEnv(limiter = TestCountingLimiter(subjectAllowed = false)).apply { status = "bloqueada" }
        application { env.mount(this) }
        val response = deleteMe("""{"confirmation":"ELIMINAR"}""")
        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertTrue(env.upstream.rpcCalls.isEmpty())
    }

    // ---------------------------------------------------------------- MASTER-AUTH-015

    private val original = "2026-09-30T11:00:00.654321+00:00"

    private fun deletionEnv(revocationState: String?) = CanonicalTestEnv().apply {
        status = "eliminacion_pendiente"
        upstream.onRpc = { function, _ ->
            when (function) {
                "app_solicitar_eliminacion_cuenta" ->
                    rpcOk("""[{"estado_cuenta":"eliminacion_pendiente","solicitada_en":"$original","ya_pendiente":true}]""")
                "app_registrar_revocacion_sesiones" -> rpcOk("""{"revocacionSesiones":"hecha"}""")
                else -> error("RPC inesperado: $function")
            }
        }
        upstream.onGet = { path ->
            if ("solicitudes_eliminacion_cuenta" in path && revocationState != null) """[{"revocacion_sesiones":"$revocationState"}]""" else "[]"
        }
    }

    @Test
    fun aRepeatRetriesARevocationThatFailedOrWasNeverRecorded() {
        listOf("fallida", "pendiente").forEach { state ->
            testApplication {
                val env = deletionEnv(state)
                application { env.mount(this) }
                val response = deleteMe("""{"confirmation":"ELIMINAR"}""")
                assertEquals(HttpStatusCode.Accepted, response.status, state)
                assertEquals(listOf("logout"), env.auth.calls.toList(), "$state: se reintenta el logout global")
                assertEquals("hecha", env.upstream.lastArgs("app_registrar_revocacion_sesiones")["p_resultado"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test
    fun aRepeatNeverRevokesAgainWhenTheRevocationIsAlreadyDoneOrUnreadable() {
        listOf("hecha", null).forEach { state ->
            testApplication {
                val env = deletionEnv(state)
                application { env.mount(this) }
                assertEquals(HttpStatusCode.Accepted, deleteMe("""{"confirmation":"ELIMINAR"}""").status)
                assertTrue(env.auth.calls.isEmpty(), "$state: ${env.auth.calls}")
                assertEquals(listOf("app_solicitar_eliminacion_cuenta"), env.upstream.rpcNames())
            }
        }
    }

    private suspend fun ApplicationTestBuilder.deleteMe(body: String) = client.request("/api/v1/app/me") {
        method = HttpMethod.Delete
        header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN")
        contentType(ContentType.Application.Json)
        setBody(body)
    }
}
