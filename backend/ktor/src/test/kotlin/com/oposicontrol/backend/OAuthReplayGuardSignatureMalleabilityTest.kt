package com.oposicontrol.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * MASTER3-AUTH-003: el replay guard OAuth hasheaba el id_token CRUDO. Una firma RS256 de 2048 bits son 342 caracteres
 * base64url; el ultimo lleva 4 bits sobrantes que los decodificadores no estrictos ignoran, asi que un token ya usado
 * con ese caracter cambiado seguia verificando en GoTrue pero daba OTRO hash y pasaba la unicidad
 * `(provider, credential_hash)`. Ahora se hashea solo la entrada firmada (`header.payload`).
 */
class OAuthReplayGuardSignatureMalleabilityTest {
    private val context = OposiBotsAuthContext("request-oauth", "127.0.0.1")

    private fun token(signature: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"RS256","kid":"k1","typ":"JWT"}""".toByteArray())
        val payload = encoder.encodeToString("""{"iss":"https://accounts.google.com","sub":"g-123","exp":4102444800}""".toByteArray())
        return "$header.$payload.$signature"
    }

    /** 256 bytes de firma -> 342 caracteres base64url sin relleno (el ultimo con 4 bits sobrantes). */
    private val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(Random(7).nextBytes(256))

    /** Mismo token, ultimo caracter de la firma cambiado por otro que decodifica a los MISMOS 256 bytes. */
    private fun malleated(): String {
        val last = signature.last()
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val index = alphabet.indexOf(last)
        val sibling = alphabet[(index and 0b110000) or ((index + 1) and 0b001111)]
        val mutated = signature.dropLast(1) + sibling
        check(mutated != signature)
        check(Base64.getUrlDecoder().decode(mutated).contentEquals(Base64.getUrlDecoder().decode(signature))) {
            "la variante debe decodificar a la misma firma (es lo que la hace aceptable para un verificador no estricto)"
        }
        return token(mutated)
    }

    @Test
    fun theHashIgnoresTheMalleableSignatureSegment() {
        val original = token(signature)
        val variant = malleated()
        assertNotEquals(original, variant)
        assertEquals(original.oauthCredentialHash(), variant.oauthCredentialHash())
        // Un cambio en la entrada firmada (payload) si da otra huella.
        assertNotEquals(original.oauthCredentialHash(), original.replaceFirst("eyJpc3Mi", "eyJpc3Qi").oauthCredentialHash())
    }

    @Test
    fun aTokenWithoutThreeNonEmptySegmentsIsRejected() {
        listOf("a.b", "a.b.c.d", "a..c", ".b.c", "a.b.").forEach {
            assertEquals(HttpStatusCode.Unauthorized, assertFailsWith<OposiBotsAuthProblem>(it) { it.oauthCredentialHash() }.status)
        }
    }

    @Test
    fun replayingAUsedIdTokenWithAMalleatedSignatureIsRejectedAndOnlyThatSessionIsRevoked() = runTest {
        val requests = CopyOnWriteArrayList<HttpRequestData>()
        // Emula la unicidad (provider, credential_hash) de oposibots_claim_oauth_credential.
        val claimed = ConcurrentHashMap.newKeySet<String>()
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val client = HttpClient(MockEngine { request ->
            requests += request
            when (request.url.encodedPath) {
                "/auth/v1/token" -> respond(ByteReadChannel(SESSION), HttpStatusCode.OK, json)
                "/auth/v1/logout" -> respond(ByteReadChannel(""), HttpStatusCode.NoContent, json)
                "/rest/v1/perfiles_usuario" -> respond(ByteReadChannel(PROFILE), HttpStatusCode.OK, json)
                "/rest/v1/rpc/oposibots_claim_oauth_credential" -> {
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    val key = body["p_provider"]!!.jsonPrimitive.content + "|" + body["p_credential_hash"]!!.jsonPrimitive.content
                    if (claimed.add(key)) {
                        respond(ByteReadChannel(""), HttpStatusCode.NoContent, json)
                    } else {
                        respond(ByteReadChannel("""{"code":"23505","message":"duplicate key"}"""), HttpStatusCode.Conflict, json)
                    }
                }
                else -> error("Unexpected request: ${request.method} ${request.url}")
            }
        })
        val gateway = SupabaseOposiBotsAuthGateway(configuration(), Json { ignoreUnknownKeys = true }, client)

        gateway.google(OposiBotsGoogleRequest(idToken = token(signature)), context)
        val replay = assertFailsWith<OposiBotsAuthProblem> { gateway.google(OposiBotsGoogleRequest(idToken = malleated()), context) }
        assertEquals(HttpStatusCode.Unauthorized, replay.status)
        assertEquals(1, claimed.size)
        // Solo la sesion acunada por el replay se revoca, y solo en local.
        assertEquals(listOf("scope=local"), requests.filter { it.url.encodedPath == "/auth/v1/logout" }.map { it.url.encodedQuery })
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

    private companion object {
        const val UID = "44444444-4444-4444-8444-444444444444"
        const val SESSION =
            """{"access_token":"minted-access","refresh_token":"minted-refresh","expires_in":3600,"user":{"id":"$UID","email":"person@example.com"}}"""
        const val PROFILE = """[{"usuario_id":"$UID","estado_cuenta":"activa","onboarding_completado":true}]"""
    }
}
