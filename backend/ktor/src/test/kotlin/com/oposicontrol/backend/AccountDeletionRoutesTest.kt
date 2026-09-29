package com.oposicontrol.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SLICE 1 · D1-19 `DELETE /api/v1/app/me`: solicitud persistente + estado seguro; la ejecucion fisica es DEFERRED_EXECUTION. */
class AccountDeletionRoutesTest {

    private val original = "2026-09-30T11:00:00.654321+00:00"
    private val pending = """[{"estado_cuenta":"eliminacion_pendiente","solicitada_en":"$original","ya_pendiente":false}]"""
    private val alreadyPending = """[{"estado_cuenta":"eliminacion_pendiente","solicitada_en":"$original","ya_pendiente":true}]"""

    private fun env(setup: CanonicalTestEnv.() -> Unit = {}) = CanonicalTestEnv().apply {
        upstream.onRpc = { function, _ ->
            when (function) {
                "app_solicitar_eliminacion_cuenta" -> rpcOk(pending)
                "app_registrar_revocacion_sesiones" -> rpcOk("""{"revocacionSesiones":"hecha"}""")
                else -> error("RPC inesperado: $function")
            }
        }
        setup()
    }

    private fun ApplicationTestBuilder.mount(env: CanonicalTestEnv) = application { env.mount(this) }

    private suspend fun HttpClient.delete(
        body: String?,
        token: String? = TEST_TOKEN,
        contentType: ContentType? = ContentType.Application.Json,
    ): HttpResponse = request("/api/v1/app/me") {
        method = HttpMethod.Delete
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        if (body != null) {
            contentType?.let { contentType(it) }
            setBody(body)
        }
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private val confirm = """{"confirmation":"ELIMINAR","currentPassword":"the-password-123"}"""

    // ------------------------------------------------------------------ autenticacion y estado

    @Test
    fun unauthenticatedIs401AndNothingHappens() = testApplication {
        val env = env()
        mount(env)
        val response = client.delete(confirm, token = null)
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("UNAUTHENTICATED", response.json().str("code"))
        assertTrue(env.auth.calls.isEmpty() && env.upstream.log.isEmpty())
    }

    @Test
    fun aBlockedAccountIs403AccountBlockedAndNothingChanges() = testApplication {
        val env = env { status = "bloqueada" }
        mount(env)
        val response = client.delete(confirm)
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("ACCOUNT_BLOCKED", response.json().str("code"))
        assertTrue(env.auth.calls.isEmpty() && env.upstream.rpcCalls.isEmpty(), "una cuenta bloqueada usa el canal de soporte: ni reauth ni RPC")
    }

    @Test
    fun anInvalidOrUnknownAccountStateIsDeniedDenyByDefaultAndAMissingProfileIsProfileMissing() = testApplication {
        val env = env()
        mount(env)
        listOf("DISABLED", "deleted", "ANONYMIZED", "", "otro", "ACTIVA").forEach { state ->
            env.status = state
            val response = client.delete(confirm)
            assertEquals(HttpStatusCode.Forbidden, response.status, state)
            assertEquals("FORBIDDEN", response.json().str("code"), state)
        }
        env.status = null
        assertEquals("FORBIDDEN", client.delete(confirm).json().str("code"))
        env.status = "activa"
        env.profileExists = false
        assertEquals("PROFILE_MISSING", client.delete(confirm).json().str("code"))
        assertTrue(env.auth.calls.isEmpty() && env.upstream.rpcCalls.isEmpty())
    }

    // ------------------------------------------------------------------ validacion

    @Test
    fun aWrongOrMissingConfirmationIsRejectedBeforeAnythingIsCalled() = testApplication {
        val env = env()
        mount(env)
        val wrong = client.delete("""{"confirmation":"eliminar","currentPassword":"x"}""")
        assertEquals(HttpStatusCode.BadRequest, wrong.status)
        val json = wrong.json()
        assertEquals("VALIDATION", json.str("code"))
        assertEquals(listOf("confirmation"), json.issueFields())
        assertEquals(listOf("BAD_CONFIRMATION"), json.issueNames())

        val missing = client.delete("""{"currentPassword":"x"}""")
        assertEquals(listOf("confirmation"), missing.json().issueFields())
        assertEquals(listOf("REQUIRED"), missing.json().issueNames())
        assertEquals(HttpStatusCode.BadRequest, client.delete(null).status, "sin cuerpo no hay confirmacion")

        val extra = client.delete("""{"confirmation":"ELIMINAR","usuarioId":"$TEST_UID"}""")
        assertEquals(listOf("usuarioId"), extra.json().issueFields(), "ningun cuerpo lleva id de usuario")
        assertEquals(HttpStatusCode.UnsupportedMediaType, client.delete(confirm, contentType = ContentType.Text.Plain).status)
        val huge = client.delete("""{"confirmation":"ELIMINAR","currentPassword":"${"x".repeat(1_100)}"}""")
        assertEquals(HttpStatusCode.BadRequest, huge.status)
        assertTrue(env.auth.calls.isEmpty() && env.upstream.rpcCalls.isEmpty())
    }

    // ------------------------------------------------------------------ reautenticacion (antes de cambiar nada)

    @Test
    fun aPasswordAccountWithoutCurrentPasswordIs400CurrentPasswordRequiredAndNothingChanges() = testApplication {
        val env = env()
        mount(env)
        val response = client.delete("""{"confirmation":"ELIMINAR"}""")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("VALIDATION", response.json().str("code"))
        assertEquals(listOf("currentPassword"), response.json().issueFields())
        assertEquals(listOf("CURRENT_PASSWORD_REQUIRED"), response.json().issueNames())
        assertEquals(HttpStatusCode.BadRequest, client.delete("""{"confirmation":"ELIMINAR","currentPassword":""}""").status)
        assertTrue(env.upstream.rpcCalls.isEmpty())
    }

    @Test
    fun aWrongPasswordIs401ReauthFailedWithNoStateChange() = testApplication {
        val env = env { auth.passwordFailure = CanonicalErrorCode.REAUTH_FAILED.exception() }
        mount(env)
        val response = client.delete(confirm)
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("REAUTH_FAILED", response.json().str("code"))
        assertEquals("Bearer", response.headers[HttpHeaders.WWWAuthenticate])
        assertTrue(env.upstream.rpcCalls.isEmpty() && env.upstream.log.isEmpty(), "el estado no cambia: ${env.upstream.log}")
        assertTrue("logout" !in env.auth.calls)
    }

    @Test
    fun whenGoTrueCannotVerifyTheReauthenticationTheAnswerIs503AndNothingChanged() = testApplication {
        val env = env { auth.passwordFailure = CanonicalErrorCode.UPSTREAM_UNAVAILABLE.exception() }
        mount(env)
        val down = client.delete(confirm)
        assertEquals(HttpStatusCode.ServiceUnavailable, down.status)
        assertEquals("UPSTREAM_UNAVAILABLE", down.json().str("code"))
        env.auth.passwordFailure = null
        env.auth.userFailure = CanonicalErrorCode.UPSTREAM_UNAVAILABLE.exception()
        assertEquals(HttpStatusCode.ServiceUnavailable, client.delete(confirm).status)
        assertTrue(env.upstream.rpcCalls.isEmpty())
    }

    @Test
    fun anOAuthAccountNeedsAnAuthenticationFromTheLastFiveMinutes() = testApplication {
        val env = env {
            auth.user = AuthProviderUser(TEST_UID, "g@example.com", listOf("google"), "2026-01-01T00:00:00Z")
        }
        mount(env)
        val now = env.now.epochSecond

        env.authTimes = listOf(now - 301)
        val stale = client.delete("""{"confirmation":"ELIMINAR"}""")
        assertEquals(HttpStatusCode.Unauthorized, stale.status)
        assertEquals("STALE_AUTH", stale.json().str("code"))
        env.authTimes = emptyList()
        assertEquals("STALE_AUTH", client.delete("""{"confirmation":"ELIMINAR"}""").json().str("code"), "sin amr conocido se falla cerrado")
        assertTrue(env.upstream.rpcCalls.isEmpty())

        env.authTimes = listOf(now - 3_600, now - 299)
        val fresh = client.delete("""{"confirmation":"ELIMINAR","currentPassword":"ignored-for-oauth"}""")
        assertEquals(HttpStatusCode.Accepted, fresh.status)
        assertTrue(env.auth.calls.none { it.startsWith("verifyPassword") }, "una cuenta OAuth no verifica contrasena")
    }

    // ------------------------------------------------------------------ 202 y forma

    @Test
    fun acceptedIs202WithTheSafeStatusAndTheCommittedRequestedAtAndNeverClaimsCompletion() = testApplication {
        val env = env()
        mount(env)
        val response = client.delete(confirm)

        assertEquals(HttpStatusCode.Accepted, response.status)
        val body = response.json()
        assertEquals(setOf("accountStatus", "requestedAt"), body.keys, "solo el estado seguro y el instante: nada que diga 'borrado'")
        assertEquals("eliminacion_pendiente", body.str("accountStatus"))
        assertEquals("2026-09-30T11:00:00.654321Z", body.str("requestedAt"))
        assertEquals("private, no-store", response.headers[HttpHeaders.CacheControl])
        assertTrue(listOf("deleted", "completed", "anonymized", "eliminada", "borrada").none { it in response.bodyAsText().lowercase() })
    }

    @Test
    fun theOrderIsReauthenticationThenOneAtomicRpcThenInlineLogoutThenTheRecordedRevocation() = testApplication {
        val env = env()
        mount(env)
        client.delete(confirm)

        assertEquals(listOf("user", "verifyPassword:person@example.com", "logout"), env.auth.calls)
        assertEquals(listOf("app_solicitar_eliminacion_cuenta", "app_registrar_revocacion_sesiones"), env.upstream.rpcNames())
        assertEquals(TEST_UID, env.upstream.lastArgs("app_solicitar_eliminacion_cuenta").str("p_usuario_id"), "identidad = sub del JWT")
        assertEquals(setOf("p_usuario_id"), env.upstream.lastArgs("app_solicitar_eliminacion_cuenta").keys)
        assertEquals("hecha", env.upstream.lastArgs("app_registrar_revocacion_sesiones").str("p_resultado"))
    }

    /** Sin cascada destructiva sincrona: solo esas dos RPC; ninguna lectura, PATCH ni DELETE de tabla, ninguna llamada admin a Auth. */
    @Test
    fun noSynchronousDestructiveCascadeOnlyTheTwoCommandRpcsAreIssued() = testApplication {
        val env = env()
        mount(env)
        client.delete(confirm)

        assertEquals(listOf("RPC app_solicitar_eliminacion_cuenta", "RPC app_registrar_revocacion_sesiones"), env.upstream.log.toList())
        assertTrue(env.auth.calls.none { it.contains("admin", ignoreCase = true) || it.contains("delete", ignoreCase = true) || it.contains("ban", ignoreCase = true) })
    }

    // ------------------------------------------------------------------ idempotencia por estado

    @Test
    fun aRepeatWhileAlreadyPendingReturnsTheOriginalRequestedAtAndDoesNotReauthenticateOrRevokeAgain() = testApplication {
        val env = env()
        mount(env)
        val first = client.delete(confirm)
        assertEquals(HttpStatusCode.Accepted, first.status)
        env.auth.calls.clear()
        env.upstream.rpcCalls.clear()

        // La cuenta ya esta `eliminacion_pendiente` (la puerta de estado la deja pasar SOLO aqui).
        env.status = "eliminacion_pendiente"
        env.now = env.now.plusSeconds(3_600)
        env.upstream.onRpc = { _, _ -> rpcOk(alreadyPending) }
        val second = client.delete("""{"confirmation":"ELIMINAR"}""")

        assertEquals(HttpStatusCode.Accepted, second.status)
        assertEquals(first.json(), second.json(), "mismo cuerpo, mismo requestedAt original")
        assertTrue(env.auth.calls.isEmpty(), "sin reautenticacion ni segundo logout: ${env.auth.calls}")
        assertEquals(listOf("app_solicitar_eliminacion_cuenta"), env.upstream.rpcNames(), "una llamada idempotente que se autorepara; sin registrar otra revocacion")
    }

    @Test
    fun aPendingAccountStillNeedsTheConfirmationLiteral() = testApplication {
        val env = env { status = "eliminacion_pendiente" }
        mount(env)
        assertEquals(HttpStatusCode.BadRequest, client.delete("""{"confirmation":"no"}""").status)
        assertTrue(env.upstream.rpcCalls.isEmpty())
    }

    // ------------------------------------------------------------------ fallos posteriores al commit y de la base

    @Test
    fun aFailedInlineLogoutNeverChangesThe202AndIsRecordedAsFailed() = testApplication {
        val env = env { auth.logoutFailure = IllegalStateException("gotrue down") }
        mount(env)
        val response = client.delete(confirm)
        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals("fallida", env.upstream.lastArgs("app_registrar_revocacion_sesiones").str("p_resultado"))
    }

    @Test
    fun aFailureRecordingTheRevocationNeverChangesThe202() = testApplication {
        val env = env()
        env.upstream.onRpc = { function, _ ->
            when (function) {
                "app_solicitar_eliminacion_cuenta" -> rpcOk(pending)
                else -> throw IllegalStateException("db down")
            }
        }
        mount(env)
        assertEquals(HttpStatusCode.Accepted, client.delete(confirm).status)
    }

    @Test
    fun ifTheDatabaseCommandFailsNothingIsClaimedAndNoLogoutHappens() = testApplication {
        val env = env()
        mount(env)
        env.upstream.onRpc = { _, _ -> rpcError(500, "XX000", "boom") }
        val failed = client.delete(confirm)
        assertTrue(failed.status.value >= 500, "un fallo de la base no es 202: ${failed.status}")
        assertTrue("logout" !in env.auth.calls, "sin commit no se revocan sesiones")

        env.upstream.onRpc = { _, _ -> rpcOk("""[{"estado_cuenta":"activa","solicitada_en":"$original","ya_pendiente":false}]""") }
        val wrongState = client.delete(confirm)
        assertEquals(HttpStatusCode.BadGateway, wrongState.status, "un estado que no es eliminacion_pendiente jamas se presenta como exito")
        assertEquals("UPSTREAM_CONTRACT_ERROR", wrongState.json().str("code"))

        env.upstream.onRpc = { _, _ -> rpcOk("[]") }
        assertEquals("UPSTREAM_CONTRACT_ERROR", client.delete(confirm).json().str("code"))
    }

    @Test
    fun aStateRaceInsideTheRpcIsMappedToItsCanonicalCode() = testApplication {
        val env = env()
        mount(env)
        env.upstream.onRpc = { _, _ -> rpcError(403, "42501", "ACCOUNT_BLOCKED") }
        val blocked = client.delete(confirm)
        assertEquals(HttpStatusCode.Forbidden, blocked.status)
        assertEquals("ACCOUNT_BLOCKED", blocked.json().str("code"))
        assertTrue("logout" !in env.auth.calls)
    }

    @Test
    fun deletionIsRateLimitedPerUserBeforeAnyStateChange() = testApplication {
        val counting = TestCountingLimiter(subjectAllowed = false)
        val env = env { limiter = counting }
        mount(env)
        val response = client.delete(confirm)
        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals("42", response.headers[HttpHeaders.RetryAfter])
        assertEquals(listOf(OposiBotsAuthOperation.ACCOUNT_DELETION to TEST_UID), counting.subjectChecks.toList())
        assertTrue(env.auth.calls.isEmpty() && env.upstream.rpcCalls.isEmpty())
    }
}
