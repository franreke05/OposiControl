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
// Unless APP_ENV is local or test, only `required` starts (RB-04 / SEC-V1-04, requireDeployable).
//
// SEC-V1-08 (20261022102500_v1_auth_binding_hardening.sql): the binding is tied to ONE email request, not to the email.
//   - it is stored PENDING ([EmailCodeBinding.begin]) and becomes valid only when GoTrue accepted to send that email
//     ([EmailCodeBinding.activate]); a refused/throttled send (429, other 4xx, 5xx, transport) leaves it pending, never valid.
//   - activating it drops every older binding of that (purpose, email): only the binding of the LAST email GoTrue sent is valid,
//     and GoTrue keeps a single live token per (user, type), so the only valid code and the only valid binding belong to the
//     same request. An attacker who asks for a mail with HIS challenge (before or after the victim, sent or throttled) can no
//     longer pair it with the victim's intercepted code.
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

/** SEC-V1-11: raw OAuth nonce sent by the client (its SHA-256 hex went to Google/Apple): 32..128 unreserved characters. */
internal val OAUTH_NONCE = Regex("^[A-Za-z0-9\\-._~]{32,128}$")

/** Lowercase hex SHA-256 of the raw nonce: what the provider puts in the ID token's `nonce` claim. */
internal fun oauthNonceHash(raw: String): String =
    MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it) }

/** The `nonce` claim of an (unverified) JWT payload, or `null` when it is not a JWT or carries none. */
internal fun oauthTokenNonceClaim(idToken: String, json: Json): String? = runCatching {
    val parts = idToken.split('.')
    if (parts.size != 3) return null
    val payload = Base64.getUrlDecoder().decode(parts[1].trimEnd('='))
    val claims = json.parseToJsonElement(payload.toString(Charsets.UTF_8)) as? kotlinx.serialization.json.JsonObject ?: return null
    (claims["nonce"] as? JsonPrimitive)?.takeIf { it.isString }?.content
}.getOrNull()

internal interface EmailCodeBinding {
    /**
     * SEC-V1-08: stores [challenge] for [emailRef] as a PENDING binding (not consumable yet) and returns its id, or `null` when the
     * challenge is already bound to another email (the first registration wins; nothing is stored).
     */
    suspend fun begin(purpose: String, emailRef: String, challenge: String): Long?

    /** SEC-V1-08: GoTrue accepted to send the email of binding [id]: it becomes the ONLY valid binding of its (purpose, email). */
    suspend fun activate(id: Long)

    /**
     * Spends the live binding of [challenge] and returns the pseudonym of the email it was requested for, or `null` (none / pending /
     * superseded by a newer sent email / expired / used / other email).
     */
    suspend fun consume(purpose: String, emailRef: String?, challenge: String): String?
}

/** PostgREST RPC with the service key; any failure is a 503 (fail closed, never "no binding"). */
internal class PostgrestEmailCodeBinding(
    private val rpc: (function: String, body: String) -> String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : EmailCodeBinding {
    override suspend fun begin(purpose: String, emailRef: String, challenge: String): Long? = upstream {
        val body = rpc(
            "auth_code_binding_begin",
            buildJsonObject { put("p_purpose", purpose); put("p_email_ref", emailRef); put("p_challenge", challenge) }.toString(),
        )
        when (val answer = json.parseToJsonElement(body)) {
            is JsonNull -> null
            is JsonPrimitive -> answer.content.toLong()
            else -> error("unexpected binding id")
        }
    }

    override suspend fun activate(id: Long) = upstream {
        rpc("auth_code_binding_activate", buildJsonObject { put("p_id", id) }.toString())
        Unit
    }

    override suspend fun consume(purpose: String, emailRef: String?, challenge: String): String? = upstream {
        val body = rpc(
            "auth_code_binding_consume",
            buildJsonObject {
                put("p_purpose", purpose)
                put("p_email_ref", emailRef?.let { JsonPrimitive(it) } ?: JsonNull)
                put("p_challenge", challenge)
            }.toString(),
        )
        (json.parseToJsonElement(body) as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    private suspend fun <T> upstream(block: () -> T): T = try {
        withUpstreamIo { block() }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        throw OposiBotsAuthProblem.unavailable()
    }
}

/** Process-local binding for tests and `development` without a database. Same rules as the SQL functions. */
internal class InMemoryEmailCodeBinding(private val clock: () -> Long = System::currentTimeMillis) : EmailCodeBinding {
    private class Row(val id: Long, val purpose: String, val emailRef: String, val challenge: String, var expiresAt: Long, var activatedSeq: Long?)

    private val rows = ArrayList<Row>()
    private var nextId = 1L
    private var nextSeq = 1L

    override suspend fun begin(purpose: String, emailRef: String, challenge: String): Long? = synchronized(rows) {
        rows.removeAll { it.expiresAt < clock() }
        val existing = rows.firstOrNull { it.purpose == purpose && it.challenge == challenge }
        if (existing != null) {
            if (existing.emailRef != emailRef) return@synchronized null
            existing.expiresAt = maxOf(existing.expiresAt, clock() + TTL_MS)
            return@synchronized existing.id
        }
        val row = Row(nextId++, purpose, emailRef, challenge, clock() + TTL_MS, null)
        rows += row
        val pending = rows.filter { it.purpose == purpose && it.emailRef == emailRef && it.activatedSeq == null }.sortedByDescending { it.id }
        rows.removeAll(pending.drop(MAX_PENDING).toSet())
        row.id
    }

    override suspend fun activate(id: Long) = synchronized(rows) {
        val row = rows.firstOrNull { it.id == id && it.expiresAt > clock() } ?: return@synchronized
        row.activatedSeq = nextSeq++
        rows.removeAll { it !== row && it.purpose == row.purpose && it.emailRef == row.emailRef && it.activatedSeq != null }
        Unit
    }

    override suspend fun consume(purpose: String, emailRef: String?, challenge: String): String? = synchronized(rows) {
        val now = clock()
        val row = rows.firstOrNull {
            it.purpose == purpose && it.challenge == challenge && it.expiresAt > now && it.activatedSeq != null && (emailRef == null || it.emailRef == emailRef)
        } ?: return@synchronized null
        val seq = row.activatedSeq ?: return@synchronized null
        if (rows.any { it.purpose == row.purpose && it.emailRef == row.emailRef && it.expiresAt > now && (it.activatedSeq ?: 0) > seq }) return@synchronized null
        rows.remove(row)
        row.emailRef
    }

    fun size(): Int = synchronized(rows) { rows.size }

    /** Bindings a consume could spend right now (activated and live). */
    fun activeCount(): Int = synchronized(rows) { rows.count { it.activatedSeq != null && it.expiresAt > clock() } }

    private companion object {
        const val TTL_MS = 3_600_000L
        const val MAX_PENDING = 5
    }
}
