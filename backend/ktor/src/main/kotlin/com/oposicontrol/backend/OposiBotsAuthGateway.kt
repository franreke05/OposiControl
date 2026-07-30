package com.oposicontrol.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

internal data class OposiBotsAuthOperationContext(
    val requestId: String,
)

internal interface OposiBotsAuthGateway {
    suspend fun login(
        request: OposiBotsLoginRequest,
        context: OposiBotsAuthOperationContext,
    ): OposiBotsLoginResponse

    suspend fun register(
        request: OposiBotsRegisterRequest,
        context: OposiBotsAuthOperationContext,
    ): OposiBotsRegisterResponse

    suspend fun completeRegistrationProfile(
        accessToken: String,
        request: OposiBotsRegisterAboutYouRequest,
        context: OposiBotsAuthOperationContext,
    )

    suspend fun recoverAccess(
        request: OposiBotsRecoverAccessRequest,
        context: OposiBotsAuthOperationContext,
    )

    suspend fun resetPassword(
        request: OposiBotsResetPasswordRequest,
        context: OposiBotsAuthOperationContext,
    )

    suspend fun signInWithGoogle(
        request: OposiBotsGoogleSignInRequest,
        context: OposiBotsAuthOperationContext,
    ): OposiBotsOAuthSignInResponse

    suspend fun signInWithApple(
        request: OposiBotsAppleSignInRequest,
        context: OposiBotsAuthOperationContext,
    ): OposiBotsOAuthSignInResponse
}

/**
 * Server-side Supabase Auth adapter for the OposiBots consumer API.
 *
 * Commands deliberately use an HTTP client without automatic retries. A
 * registration, password reset, or OAuth exchange must never be repeated by a
 * generic transport policy.
 */
internal class SupabaseOposiBotsAuthGateway(
    private val configuration: BackendConfiguration,
    private val json: Json,
    private val client: HttpClient = defaultOposiBotsAuthHttpClient(),
) : OposiBotsAuthGateway {
    private val logger = LoggerFactory.getLogger("OC-SUPA")

    override suspend fun login(
        request: OposiBotsLoginRequest,
        context: OposiBotsAuthOperationContext,
    ): OposiBotsLoginResponse {
        // keepSignedIn controls client persistence. The only server credential
        // accepted by protected endpoints remains the short-lived access JWT.
        val session = passwordGrant(request.identifier, request.password, context, "login")
        requireAllowedAccount(session.user.id, context)
        return OposiBotsLoginResponse(sessionToken = session.accessToken)
    }

    override suspend fun register(
        request: OposiBotsRegisterRequest,
        context: OposiBotsAuthOperationContext,
    ): OposiBotsRegisterResponse {
        val createBody = credentialBody(request.identifier).applyPassword(request.password).let { base ->
            buildJsonObject {
                base.forEach { (key, value) -> put(key, value) }
                if ("email" in base) {
                    put("email_confirm", true)
                } else {
                    put("phone_confirm", true)
                }
            }
        }
        val created = supabaseRequest(
            operation = "register.create",
            method = HttpMethod.Post,
            path = "/auth/v1/admin/users",
            body = createBody,
            privileged = true,
            context = context,
        )
        if (!created.status.isSuccess()) throw registerProblem(created)

        val session = passwordGrant(request.identifier, request.password, context, "register.session")
        return OposiBotsRegisterResponse(sessionToken = session.accessToken)
    }

    override suspend fun completeRegistrationProfile(
        accessToken: String,
        request: OposiBotsRegisterAboutYouRequest,
        context: OposiBotsAuthOperationContext,
    ) {
        val user = currentUser(accessToken, context)
        requireAllowedAccount(user.id, context)
        val body = buildJsonObject {
            put("p_auth_user_id", user.id)
            if (user.email == null) put("p_email", JsonNull) else put("p_email", user.email)
            put("p_first_name", request.firstName.trim())
            put("p_last_name", request.lastName.trim())
            val username = request.username?.trim()?.takeIf(String::isNotEmpty)
            if (username == null) put("p_username", JsonNull) else put("p_username", username)
            put("p_specialty", request.specialty.trim())
        }
        val response = supabaseRequest(
            operation = "register.profile",
            method = HttpMethod.Post,
            path = "/rest/v1/rpc/oposibots_complete_registration_profile",
            body = body,
            privileged = true,
            context = context,
        )
        if (!response.status.isSuccess()) throw profileProblem(response)
    }

    override suspend fun recoverAccess(
        request: OposiBotsRecoverAccessRequest,
        context: OposiBotsAuthOperationContext,
    ) {
        /*
         * Supabase's password-recovery contract is email-only. A non-email
         * identifier remains an indistinguishable no-op until product defines
         * the phone recovery flow; it must never reveal account existence.
         */
        if (!request.identifier.contains('@')) return
        val redirectQuery = configuration.oposibotsPasswordRecoveryRedirectUrl
            ?.let { "?redirect_to=${it.urlEncode()}" }
            .orEmpty()
        val response = supabaseRequest(
            operation = "recover",
            method = HttpMethod.Post,
            path = "/auth/v1/recover$redirectQuery",
            body = buildJsonObject { put("email", request.identifier.trim()) },
            privileged = false,
            context = context,
        )
        if (response.status == HttpStatusCode.TooManyRequests) {
            throw OposiBotsAuthProblem.temporarilyUnavailable(rateLimited = true)
        }
        if (response.status.value >= 500) {
            throw OposiBotsAuthProblem.temporarilyUnavailable()
        }
        // WHY: every other result is intentionally collapsed to success to
        // prevent account enumeration, including blocked and missing users.
    }

    override suspend fun resetPassword(
        request: OposiBotsResetPasswordRequest,
        context: OposiBotsAuthOperationContext,
    ) {
        val verified = supabaseRequest(
            operation = "reset.verify",
            method = HttpMethod.Post,
            path = "/auth/v1/verify",
            body = buildJsonObject {
                put("token_hash", request.resetToken)
                put("type", "recovery")
            },
            privileged = false,
            context = context,
        )
        if (!verified.status.isSuccess()) throw credentialProblem(verified)
        val recoverySession = verified.decodeSession()
        requireAllowedAccount(recoverySession.user.id, context)

        val updated = supabaseRequest(
            operation = "reset.update",
            method = HttpMethod.Put,
            path = "/auth/v1/user",
            body = buildJsonObject { put("password", request.newPassword) },
            accessToken = recoverySession.accessToken,
            privileged = false,
            context = context,
        )
        if (!updated.status.isSuccess()) throw credentialProblem(updated)
    }

    override suspend fun signInWithGoogle(
        request: OposiBotsGoogleSignInRequest,
        context: OposiBotsAuthOperationContext,
    ): OposiBotsOAuthSignInResponse =
        signInWithIdToken(
            provider = "google",
            idToken = request.idToken,
            context = context,
        )

    override suspend fun signInWithApple(
        request: OposiBotsAppleSignInRequest,
        context: OposiBotsAuthOperationContext,
    ): OposiBotsOAuthSignInResponse {
        verifyAppleAuthorizationCode(request, context)
        val result = signInWithIdToken(
            provider = "apple",
            idToken = request.identityToken,
            context = context,
        )
        return result
    }

    private suspend fun passwordGrant(
        identifier: String,
        password: String,
        context: OposiBotsAuthOperationContext,
        operation: String,
    ): SupabaseSessionResponse {
        val response = supabaseRequest(
            operation = operation,
            method = HttpMethod.Post,
            path = "/auth/v1/token?grant_type=password",
            body = credentialBody(identifier).applyPassword(password),
            privileged = false,
            context = context,
        )
        if (!response.status.isSuccess()) throw credentialProblem(response)
        return response.decodeSession()
    }

    private suspend fun currentUser(
        accessToken: String,
        context: OposiBotsAuthOperationContext,
    ): SupabaseAuthUser {
        val response = supabaseRequest(
            operation = "session.user",
            method = HttpMethod.Get,
            path = "/auth/v1/user",
            accessToken = accessToken,
            privileged = false,
            context = context,
        )
        if (!response.status.isSuccess()) throw credentialProblem(response)
        return response.decode()
    }

    private suspend fun signInWithIdToken(
        provider: String,
        idToken: String,
        context: OposiBotsAuthOperationContext,
    ): OposiBotsOAuthSignInResponse {
        val expiresAt = idToken.jwtExpiry()
        val response = supabaseRequest(
            operation = "oauth.$provider",
            method = HttpMethod.Post,
            path = "/auth/v1/token?grant_type=id_token",
            body = buildJsonObject {
                put("provider", provider)
                put("id_token", idToken)
            },
            privileged = false,
            context = context,
        )
        if (!response.status.isSuccess()) throw oauthProblem(response)
        val session = response.decodeSession()
        val status = accountStatus(session.user.id, context)
        if (status.blocked) throw OposiBotsAuthProblem.accessDenied()
        claimOAuthCredential(provider, idToken, expiresAt, context)
        return OposiBotsOAuthSignInResponse(
            sessionToken = session.accessToken,
            isNewAccount = !status.profileComplete,
        )
    }

    private suspend fun verifyAppleAuthorizationCode(
        request: OposiBotsAppleSignInRequest,
        context: OposiBotsAuthOperationContext,
    ) {
        val clientId = configuration.appleClientId
            ?: throw OposiBotsAuthProblem.temporarilyUnavailable()
        val clientSecret = configuration.appleClientSecret
            ?: throw OposiBotsAuthProblem.temporarilyUnavailable()
        val response = appleRequest(
            operation = "oauth.apple.code",
            body = FormDataContent(
                Parameters.build {
                    append("client_id", clientId)
                    append("client_secret", clientSecret)
                    append("code", request.authorizationCode)
                    append("grant_type", "authorization_code")
                },
            ),
            context = context,
        )
        if (!response.status.isSuccess()) throw credentialProblem(response)
        val appleToken = response.decode<AppleTokenResponse>().idToken
            ?: throw OposiBotsAuthProblem.authenticationRequired()
        if (appleToken.jwtSubject() != request.identityToken.jwtSubject()) {
            throw OposiBotsAuthProblem.authenticationRequired()
        }
    }

    private suspend fun claimOAuthCredential(
        provider: String,
        idToken: String,
        expiresAt: Instant,
        context: OposiBotsAuthOperationContext,
    ) {
        val response = supabaseRequest(
            operation = "oauth.$provider.claim",
            method = HttpMethod.Post,
            path = "/rest/v1/rpc/oposibots_claim_oauth_credential",
            body = buildJsonObject {
                put("p_provider", provider)
                put("p_credential_hash", idToken.sha256())
                put("p_expires_at", expiresAt.toString())
            },
            privileged = true,
            context = context,
        )
        if (!response.status.isSuccess()) {
            val error = response.error()
            if (response.status == HttpStatusCode.Conflict || error.normalizedCode == "23505") {
                throw OposiBotsAuthProblem.authenticationRequired()
            }
            throw temporaryOr(response)
        }
    }

    private suspend fun requireAllowedAccount(
        authUserId: String,
        context: OposiBotsAuthOperationContext,
    ): AccountStatus {
        val status = accountStatus(authUserId, context)
        if (status.blocked) throw OposiBotsAuthProblem.accessDenied()
        return status
    }

    private suspend fun accountStatus(
        authUserId: String,
        context: OposiBotsAuthOperationContext,
    ): AccountStatus {
        val response = supabaseRequest(
            operation = "account.status",
            method = HttpMethod.Get,
            path = "/rest/v1/usuario" +
                "?select=activo,deleted_at,perfil_completo" +
                "&auth_user_id=eq.${authUserId.urlEncode()}&limit=1",
            privileged = true,
            context = context,
        )
        if (!response.status.isSuccess()) throw temporaryOr(response)
        val row = response.decodeList<AccountStatusRow>().firstOrNull()
        return AccountStatus(
            blocked = row != null && (!row.active || row.deletedAt != null),
            profileComplete = row?.profileComplete == true,
        )
    }

    private suspend fun supabaseRequest(
        operation: String,
        method: HttpMethod,
        path: String,
        body: JsonObject? = null,
        accessToken: String? = null,
        privileged: Boolean,
        context: OposiBotsAuthOperationContext,
    ): AuthHttpResponse {
        require(path.startsWith('/')) { "Supabase path must be relative." }
        val response = try {
            client.request("${configuration.supabaseUrl}$path") {
                this.method = method
                val apiKey = if (privileged) {
                    configuration.supabaseSecretKey
                } else {
                    configuration.supabasePublishableKey
                }
                header("apikey", apiKey)
                when {
                    accessToken != null ->
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                    privileged && configuration.supabaseSecretKey.isLegacyJwtApiKey() ->
                        // New sb_secret keys belong only in `apikey`; legacy
                        // service_role keys are JWTs and still require Bearer.
                        header(HttpHeaders.Authorization, "Bearer ${configuration.supabaseSecretKey}")
                }
                header(HttpHeaders.Accept, ContentType.Application.Json)
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.warn(
                "operation={} requestId={} result=network_error type={}",
                operation,
                context.requestId,
                error::class.simpleName,
            )
            throw OposiBotsAuthProblem.temporarilyUnavailable(cause = error)
        }
        logger.info(
            "operation={} requestId={} upstreamStatus={}",
            operation,
            context.requestId,
            response.status.value,
        )
        return AuthHttpResponse(response.status, response.bodyAsText(), json)
    }

    private suspend fun appleRequest(
        operation: String,
        body: FormDataContent,
        context: OposiBotsAuthOperationContext,
    ): AuthHttpResponse {
        val response = try {
            client.request(APPLE_TOKEN_ENDPOINT) {
                method = HttpMethod.Post
                setBody(body)
                header(HttpHeaders.Accept, ContentType.Application.Json)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.warn(
                "operation={} requestId={} result=network_error type={}",
                operation,
                context.requestId,
                error::class.simpleName,
            )
            throw OposiBotsAuthProblem.temporarilyUnavailable(cause = error)
        }
        logger.info(
            "operation={} requestId={} upstreamStatus={}",
            operation,
            context.requestId,
            response.status.value,
        )
        return AuthHttpResponse(response.status, response.bodyAsText(), json)
    }

    private fun registerProblem(response: AuthHttpResponse): OposiBotsAuthProblem {
        val error = response.error()
        val fingerprint = "${error.normalizedCode} ${error.normalizedMessage}".lowercase()
        return when {
            response.status == HttpStatusCode.Conflict ||
                "already" in fingerprint ||
                error.normalizedCode in ACCOUNT_CONFLICT_CODES ->
                OposiBotsAuthProblem.conflict()
            response.status == HttpStatusCode.TooManyRequests ->
                OposiBotsAuthProblem.temporarilyUnavailable(rateLimited = true)
            response.status.value >= 500 ->
                OposiBotsAuthProblem.temporarilyUnavailable()
            else ->
                OposiBotsAuthProblem.validation(emptySet())
        }
    }

    private fun credentialProblem(response: AuthHttpResponse): OposiBotsAuthProblem {
        val error = response.error()
        return when {
            error.blocked() -> OposiBotsAuthProblem.accessDenied()
            response.status == HttpStatusCode.TooManyRequests ->
                OposiBotsAuthProblem.temporarilyUnavailable(rateLimited = true)
            response.status.value >= 500 ->
                OposiBotsAuthProblem.temporarilyUnavailable()
            else ->
                OposiBotsAuthProblem.authenticationRequired()
        }
    }

    private fun oauthProblem(response: AuthHttpResponse): OposiBotsAuthProblem {
        val error = response.error()
        val fingerprint = "${error.normalizedCode} ${error.normalizedMessage}".lowercase()
        return when {
            error.blocked() -> OposiBotsAuthProblem.accessDenied()
            response.status == HttpStatusCode.Conflict ||
                "already registered" in fingerprint ||
                error.normalizedCode in ACCOUNT_CONFLICT_CODES ->
                OposiBotsAuthProblem.conflict()
            response.status == HttpStatusCode.TooManyRequests ->
                OposiBotsAuthProblem.temporarilyUnavailable(rateLimited = true)
            response.status.value >= 500 ->
                OposiBotsAuthProblem.temporarilyUnavailable()
            else ->
                OposiBotsAuthProblem.authenticationRequired()
        }
    }

    private fun profileProblem(response: AuthHttpResponse): OposiBotsAuthProblem {
        val error = response.error()
        val fingerprint = "${error.normalizedCode} ${error.normalizedMessage}".uppercase()
        return when {
            "ACCOUNT_BLOCKED" in fingerprint -> OposiBotsAuthProblem.accessDenied()
            response.status == HttpStatusCode.Conflict || error.normalizedCode == "23505" ->
                OposiBotsAuthProblem.conflict()
            else -> temporaryOr(response)
        }
    }

    private fun temporaryOr(response: AuthHttpResponse): OposiBotsAuthProblem =
        OposiBotsAuthProblem.temporarilyUnavailable(
            rateLimited = response.status == HttpStatusCode.TooManyRequests,
        )

    private fun credentialBody(identifier: String): JsonObject = buildJsonObject {
        val normalized = identifier.trim()
        if (normalized.contains('@')) {
            put("email", normalized)
        } else {
            put("phone", normalized)
        }
    }

    private fun JsonObject.applyPassword(password: String): JsonObject = buildJsonObject {
        this@applyPassword.forEach { (key, value) -> put(key, value) }
        put("password", password)
    }

    companion object {
        private const val APPLE_TOKEN_ENDPOINT = "https://appleid.apple.com/auth/token"
        private val ACCOUNT_CONFLICT_CODES = setOf(
            "email_exists",
            "phone_exists",
            "user_already_exists",
            "identity_already_exists",
        )
    }
}

private data class AuthHttpResponse(
    val status: HttpStatusCode,
    val body: String,
    val json: Json,
) {
    inline fun <reified T> decode(): T {
        return try {
            json.decodeFromString(body)
        } catch (error: SerializationException) {
            throw OposiBotsAuthProblem.temporarilyUnavailable(cause = error)
        }
    }

    inline fun <reified T> decodeList(): List<T> = decode()

    fun decodeSession(): SupabaseSessionResponse {
        val session = decode<SupabaseSessionResponse>()
        if (session.accessToken.isBlank() || session.user.id.isBlank()) {
            throw OposiBotsAuthProblem.temporarilyUnavailable()
        }
        return session
    }

    fun error(): SupabaseAuthError {
        if (body.isBlank()) return SupabaseAuthError()
        return runCatching { json.decodeFromString<SupabaseAuthError>(body) }
            .getOrElse { SupabaseAuthError() }
    }
}

@Serializable
private data class SupabaseSessionResponse(
    @SerialName("access_token") val accessToken: String,
    val user: SupabaseAuthUser,
)

@Serializable
private data class SupabaseAuthUser(
    val id: String,
    val email: String? = null,
    val phone: String? = null,
)

@Serializable
private data class SupabaseAuthError(
    val code: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    val message: String? = null,
    val msg: String? = null,
) {
    val normalizedCode: String
        get() = (code ?: errorCode).orEmpty().lowercase()

    val normalizedMessage: String
        get() = (message ?: msg).orEmpty()

    fun blocked(): Boolean {
        val fingerprint = "$normalizedCode $normalizedMessage".lowercase()
        return "banned" in fingerprint || "blocked" in fingerprint || "disabled" in fingerprint
    }
}

@Serializable
private data class AccountStatusRow(
    val activo: Boolean = true,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("perfil_completo") val profileComplete: Boolean = false,
) {
    val active: Boolean
        get() = activo
}

private data class AccountStatus(
    val blocked: Boolean,
    val profileComplete: Boolean,
)

@Serializable
private data class AppleTokenResponse(
    @SerialName("id_token") val idToken: String? = null,
)

private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299

private fun String.urlEncode(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8).replace("+", "%20")

private fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

private fun String.isLegacyJwtApiKey(): Boolean = count { character -> character == '.' } == 2

private fun String.jwtExpiry(): Instant {
    val expirySeconds = jwtPayload()["exp"]?.jsonPrimitive?.longOrNull
        ?: throw OposiBotsAuthProblem.authenticationRequired()
    val expiry = runCatching { Instant.ofEpochSecond(expirySeconds) }
        .getOrElse { throw OposiBotsAuthProblem.authenticationRequired() }
    if (!expiry.isAfter(Instant.now())) throw OposiBotsAuthProblem.authenticationRequired()
    return expiry
}

private fun String.jwtSubject(): String =
    jwtPayload()["sub"]?.jsonPrimitive?.content
        ?.takeIf(String::isNotBlank)
        ?: throw OposiBotsAuthProblem.authenticationRequired()

private fun String.jwtPayload(): JsonObject {
    val segments = split('.')
    if (segments.size != 3) throw OposiBotsAuthProblem.authenticationRequired()
    return try {
        val decoded = Base64.getUrlDecoder().decode(segments[1]).toString(StandardCharsets.UTF_8)
        Json.parseToJsonElement(decoded).jsonObject
    } catch (error: Exception) {
        throw OposiBotsAuthProblem.authenticationRequired()
    }
}

private fun defaultOposiBotsAuthHttpClient(): HttpClient = HttpClient(Java) {
    expectSuccess = false
    install(HttpTimeout) {
        connectTimeoutMillis = 5_000
        requestTimeoutMillis = 15_000
        socketTimeoutMillis = 15_000
    }
}
