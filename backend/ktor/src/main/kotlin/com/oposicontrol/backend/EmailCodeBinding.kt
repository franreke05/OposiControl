package com.oposicontrol.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// =====================================================================
// FA39 (E2-N1-01): binding of the email deep-link code (`oposibots://auth?code=...`) to the device that asked for the email.
//
// The link carries a one-time GoTrue token_hash. A custom URL scheme can be claimed by any app, so a malicious app could read the
// code and exchange it. PKCE-style fix, enforced HERE (GoTrue's own PKCE flow does not bind `verify` with a token_hash):
//   1. register / resend / recover: the client sends `codeChallenge` = base64url(SHA-256(codeVerifier)); Ktor stores
//      (purpose, keyed pseudonym of the email, challenge) with a TTL of the mail token (1 h) BEFORE GoTrue sends the email.
//   2. email/exchange and reset-password: the client sends `code` + `codeVerifier` (+ optionally `email`). Ktor hashes the verifier and
//      CONSUMES the stored binding first. No live binding -> 401 exactly like an invalid code, and GoTrue is never called, so a
//      stolen code is neither usable nor spent. A binding of another email is not spent by a wrong attempt.
//   3. after GoTrue verifies the token, the session's email must be the one the binding was requested for; otherwise the minted
//      session is revoked (local scope) and the answer is the same 401.
// Modes (AUTH_PKCE_MODE): off (legacy) | optional (default: enforced when the client sends the fields) | required (400 without them).
// =====================================================================

internal enum class PkceMode {
    OFF, OPTIONAL, REQUIRED;

    companion object {
        fun parse(value: String?): PkceMode = when (value?.trim()?.lowercase()) {
            null, "", "optional" -> OPTIONAL
            "off" -> OFF
            "required" -> REQUIRED
            else -> error("AUTH_PKCE_MODE debe ser off, optional o required.")
        }
    }
}

internal object Pkce {
    /** RFC 7636 S256 challenge: base64url (no padding) of 32 bytes = 43 characters. */
    val CHALLENGE = Regex("^[A-Za-z0-9_-]{43}$")

    /** RFC 7636 code_verifier: 43..128 unreserved characters. */
    val VERIFIER = Regex("^[A-Za-z0-9\\-._~]{43,128}$")

    fun challengeOf(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** Keyed pseudonym of an email (HMAC-SHA-256, 64 hex): the database never holds the address. Same normalisation as the rate limiter. */
    fun emailRef(secret: String, email: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(("auth-code-binding:" + email.trim().lowercase()).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

internal interface EmailCodeBinding {
    suspend fun register(purpose: String, emailRef: String, challenge: String)

    /** Spends the live binding of [challenge] and returns the pseudonym of the email it was requested for, or `null` (none / expired / used / other email). */
    suspend fun consume(purpose: String, emailRef: String?, challenge: String): String?
}

/** PostgREST RPC with the service key; any failure is a 503 (fail closed, never "no binding"). */
internal class PostgrestEmailCodeBinding(
    private val rpc: (function: String, body: String) -> String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : EmailCodeBinding {
    override suspend fun register(purpose: String, emailRef: String, challenge: String) {
        try {
            withUpstreamIo {
                rpc(
                    "auth_code_binding_register",
                    buildJsonObject { put("p_purpose", purpose); put("p_email_ref", emailRef); put("p_challenge", challenge) }.toString(),
                )
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw OposiBotsAuthProblem.unavailable()
        }
    }

    override suspend fun consume(purpose: String, emailRef: String?, challenge: String): String? = try {
        val body = withUpstreamIo {
            rpc(
                "auth_code_binding_consume",
                buildJsonObject {
                    put("p_purpose", purpose)
                    put("p_email_ref", emailRef?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("p_challenge", challenge)
                }.toString(),
            )
        }
        (json.parseToJsonElement(body) as? JsonPrimitive)?.takeIf { it.isString }?.content
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        throw OposiBotsAuthProblem.unavailable()
    }
}

/** Process-local binding for tests and `development` without a database. */
internal class InMemoryEmailCodeBinding(private val clock: () -> Long = System::currentTimeMillis) : EmailCodeBinding {
    private data class Row(val purpose: String, val emailRef: String, val challenge: String, val expiresAt: Long)

    private val rows = ArrayList<Row>()

    override suspend fun register(purpose: String, emailRef: String, challenge: String) = synchronized(rows) {
        rows.removeAll { it.expiresAt < clock() }
        if (rows.none { it.purpose == purpose && it.challenge == challenge }) rows += Row(purpose, emailRef, challenge, clock() + 3_600_000)
        val mine = rows.filter { it.purpose == purpose && it.emailRef == emailRef }
        if (mine.size > 5) rows.removeAll(mine.take(mine.size - 5).toSet())
        Unit
    }

    override suspend fun consume(purpose: String, emailRef: String?, challenge: String): String? = synchronized(rows) {
        val row = rows.firstOrNull { it.purpose == purpose && it.challenge == challenge && it.expiresAt > clock() && (emailRef == null || it.emailRef == emailRef) }
        if (row != null) rows.remove(row)
        row?.emailRef
    }

    @Synchronized
    fun size(): Int = rows.size
}
