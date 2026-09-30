package com.oposicontrol.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MASTER3-AUTH-001: la convergencia del reintento de borrado (MASTER2-AUTH-009) anotaba `hecha` (terminal) ante
 * CUALQUIER 401/403 del logout, y el gateway colapsaba 400/404/422/apikey mala/un 403 ajeno en 401. Ahora solo la
 * evidencia explicita de GoTrue (401/403 + `error_code=session_not_found`) marca la sesion como ya revocada; todo lo
 * demas sigue siendo `fallida`, asi que un reintento posterior vuelve a intentarlo.
 *
 * Se prueba con el gateway REAL (MockEngine con respuestas de GoTrue): su excepcion es la que recibe el servicio.
 */
class AccountDeletionSessionGoneEvidenceTest {
    private val context = OposiBotsAuthContext("request-deletion", "127.0.0.1")

    private fun gateway(status: HttpStatusCode, body: String): SupabaseOposiBotsAuthGateway {
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val client = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/auth/v1/logout" -> respond(ByteReadChannel(body), status, json)
                else -> error("Unexpected request: ${request.method} ${request.url}")
            }
        })
        return SupabaseOposiBotsAuthGateway(configuration(), Json { ignoreUnknownKeys = true }, client)
    }

    /** Respuestas reales de GoTrue para un logout que falla por motivos AJENOS a "la sesion ya no existe". */
    private val notSessionGone = listOf(
        HttpStatusCode.BadRequest to """{"code":400,"error_code":"validation_failed","msg":"bad"}""",
        HttpStatusCode.NotFound to """{"code":404,"error_code":"not_found","msg":"no"}""",
        HttpStatusCode.UnprocessableEntity to """{"code":422,"error_code":"unexpected_failure","msg":"x"}""",
        // Kong/API gateway: apikey invalida (sin error_code de GoTrue).
        HttpStatusCode.Unauthorized to """{"message":"Invalid API key"}""",
        // 403 por otra causa (firma/caducidad del JWT): no prueba nada sobre las demas sesiones.
        HttpStatusCode.Forbidden to """{"code":403,"error_code":"bad_jwt","msg":"invalid JWT"}""",
        HttpStatusCode.Forbidden to """{"code":403,"error_code":"not_admin","msg":"x"}""",
    )

    @Test
    fun theGatewayMarksSessionGoneOnlyForAnExplicitSessionNotFound() = runTest {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            val gone = assertFailsWith<OposiBotsAuthProblem> {
                gateway(status, """{"code":${status.value},"error_code":"session_not_found","msg":"Session from session_id claim in JWT does not exist"}""")
                    .logout("access", context)
            }
            assertTrue(gone.upstreamSessionGone, "$status session_not_found")
            assertEquals(HttpStatusCode.Unauthorized, gone.status, "hacia el cliente sigue siendo 401")
        }
        for ((status, body) in notSessionGone) {
            val other = assertFailsWith<OposiBotsAuthProblem> { gateway(status, body).logout("access", context) }
            assertFalse(other.upstreamSessionGone, "$status $body no prueba que la sesion este revocada")
        }
    }

    @Test
    fun aRetryWhoseLogoutFailsForAnyOtherReasonKeepsFallidaSoItIsRetriedAgain() {
        for ((status, body) in notSessionGone) {
            testApplication {
                val e = retryEnv(gateway(status, body))
                application { e.mount(this) }
                assertEquals(HttpStatusCode.Accepted, deleteMe().status)
                assertEquals("fallida", recorded(e), "$status $body")
            }
        }
    }

    @Test
    fun aRetryWhoseLogoutReportsSessionNotFoundConvergesToHecha() {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            testApplication {
                val e = retryEnv(gateway(status, """{"code":${status.value},"error_code":"session_not_found","msg":"gone"}"""))
                application { e.mount(this) }
                assertEquals(HttpStatusCode.Accepted, deleteMe().status)
                assertEquals("hecha", recorded(e), "$status session_not_found")
            }
        }
    }

    /** Solicitud YA pendiente con la revocacion anotada `fallida`: el DELETE es un reintento de la revocacion. */
    private fun retryEnv(provider: SupabaseOposiBotsAuthGateway) = CanonicalTestEnv().apply {
        status = "eliminacion_pendiente"
        // La excepcion REAL que el gateway lanza ante esa respuesta de GoTrue, entregada al servicio de borrado.
        auth.logoutFailure = runBlocking { runCatching { provider.logout("access", context) }.exceptionOrNull() as Exception }
        upstream.onRpc = { function, _ ->
            when (function) {
                "app_solicitar_eliminacion_cuenta" ->
                    rpcOk("""[{"estado_cuenta":"eliminacion_pendiente","solicitada_en":"2026-09-30T11:00:00+00:00","ya_pendiente":true}]""")
                "app_registrar_revocacion_sesiones" -> rpcOk("""{"revocacionSesiones":"x"}""")
                else -> error("RPC inesperado: $function")
            }
        }
        upstream.onGet = { path -> if ("solicitudes_eliminacion_cuenta" in path) """[{"revocacion_sesiones":"fallida"}]""" else "[]" }
    }

    private fun recorded(e: CanonicalTestEnv) =
        e.upstream.lastArgs("app_registrar_revocacion_sesiones")["p_resultado"]!!.jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.deleteMe() = client.request("/api/v1/app/me") {
        method = HttpMethod.Delete
        header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN")
        contentType(ContentType.Application.Json)
        setBody("""{"confirmation":"ELIMINAR"}""")
    }

    private fun configuration() = OposiBotsAuthConfiguration(
        supabaseUrl = "https://example.supabase.co",
        publishableKey = "client-publishable",
        secretKey = "server-secret",
        appEnvironment = "test",
        passwordRecoveryRedirectUrl = null,
        appleClientId = null,
        appleClientSecret = null,
        captchaRequired = false,
        trustProxyHeaders = false,
        trustedProxyHosts = emptySet(),
        forwardClientIpToSupabase = false,
        rateLimitMode = "memory",
        rateLimitHmacSecret = "test-only-rate-limit-secret-32chars",
        valkeyUrl = null,
    )
}
