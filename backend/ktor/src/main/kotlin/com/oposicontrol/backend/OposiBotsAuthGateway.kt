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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import org.slf4j.LoggerFactory

private val GatewayLogger = LoggerFactory.getLogger("OC-KTOR-AUTH-GATEWAY")

internal data class OposiBotsAuthContext(
    val requestId: String,
    val clientIp: String,
)

internal interface OposiBotsAuthGateway {
    suspend fun register(request: OposiBotsRegisterRequest, context: OposiBotsAuthContext): OposiBotsRegisterResponse
    suspend fun resend(request: OposiBotsEmailResendRequest, context: OposiBotsAuthContext)
    suspend fun exchangeEmail(request: OposiBotsEmailExchangeRequest, context: OposiBotsAuthContext): OposiBotsAuthSessionDto
    suspend fun login(request: OposiBotsLoginRequest, context: OposiBotsAuthContext): OposiBotsAuthSessionDto
    suspend fun refresh(request: OposiBotsRefreshRequest, context: OposiBotsAuthContext): OposiBotsAuthSessionDto
    suspend fun logout(accessToken: String, context: OposiBotsAuthContext)
    suspend fun me(accessToken: String, context: OposiBotsAuthContext): OposiBotsMeResponse
    suspend fun saveProfile(accessToken: String, request: OposiBotsProfileRequest, context: OposiBotsAuthContext)
    suspend fun recover(request: OposiBotsRecoverRequest, context: OposiBotsAuthContext)
    suspend fun reset(request: OposiBotsResetPasswordRequest, context: OposiBotsAuthContext)
    suspend fun google(request: OposiBotsGoogleRequest, context: OposiBotsAuthContext): OposiBotsOAuthResponse
    suspend fun apple(request: OposiBotsAppleRequest, context: OposiBotsAuthContext): OposiBotsOAuthResponse
}

internal class SupabaseOposiBotsAuthGateway(
    private val configuration: OposiBotsAuthConfiguration,
    private val json: Json,
    private val client: HttpClient = defaultAuthHttpClient(),
) : OposiBotsAuthGateway {
    override suspend fun register(
        request: OposiBotsRegisterRequest,
        context: OposiBotsAuthContext,
    ): OposiBotsRegisterResponse {
        val body = credentialBody(request.identifier, request.password, request.captchaToken)
        val response = authRequest(HttpMethod.Post, "/auth/v1/signup", body, context = context)
        if (!response.status.isSuccessStatus()) throw mapCredentialError(response, conflictAllowed = false)
        return OposiBotsRegisterResponse()
    }

    override suspend fun resend(request: OposiBotsEmailResendRequest, context: OposiBotsAuthContext) {
        val response = authRequest(
            HttpMethod.Post,
            "/auth/v1/resend",
            buildJsonObject {
                put("type", "signup")
                put("email", request.email.trim())
                request.captchaToken?.takeIf(String::isNotBlank)?.let { put("captcha_token", it) }
            },
            context = context,
        )
        if (response.status == HttpStatusCode.TooManyRequests) throw OposiBotsAuthProblem.unavailable(60)
        if (response.status.value >= 500) throw OposiBotsAuthProblem.unavailable()
        // All other outcomes are intentionally indistinguishable.
    }

    override suspend fun exchangeEmail(
        request: OposiBotsEmailExchangeRequest,
        context: OposiBotsAuthContext,
    ): OposiBotsAuthSessionDto {
        val response = authRequest(
            HttpMethod.Post,
            "/auth/v1/verify",
            buildJsonObject {
                put("token_hash", request.code)
                put("type", "signup")
            },
            context = context,
        )
        if (!response.status.isSuccessStatus()) throw mapCredentialError(response)
        return response.toSession(context)
    }

    override suspend fun login(
        request: OposiBotsLoginRequest,
        context: OposiBotsAuthContext,
    ): OposiBotsAuthSessionDto {
        val response = authRequest(
            HttpMethod.Post,
            "/auth/v1/token?grant_type=password",
            credentialBody(request.identifier, request.password, null),
            context = context,
        )
        if (!response.status.isSuccessStatus()) throw mapCredentialError(response)
        return response.toSession(context)
    }

    override suspend fun refresh(
        request: OposiBotsRefreshRequest,
        context: OposiBotsAuthContext,
    ): OposiBotsAuthSessionDto {
        val response = authRequest(
            HttpMethod.Post,
            "/auth/v1/token?grant_type=refresh_token",
            buildJsonObject { put("refresh_token", request.refreshToken) },
            context = context,
        )
        if (!response.status.isSuccessStatus()) throw mapCredentialError(response)
        return response.toSession(context)
    }

    override suspend fun logout(accessToken: String, context: OposiBotsAuthContext) {
        val response = authRequest(
            HttpMethod.Post,
            "/auth/v1/logout?scope=global",
            body = null,
            accessToken = accessToken,
            context = context,
        )
        if (!response.status.isSuccessStatus() && response.status != HttpStatusCode.NoContent) {
            throw mapCredentialError(response)
        }
    }

    override suspend fun me(accessToken: String, context: OposiBotsAuthContext): OposiBotsMeResponse {
        val user = currentUser(accessToken, context)
        val profile = profile(accessToken, user.id, context)
        if (profile?.deletedAt != null) throw OposiBotsAuthProblem.accessDenied()
        return OposiBotsMeResponse(
            OposiBotsAuthUserDto(user.id, user.email, profile?.profileComplete == true),
        )
    }

    override suspend fun saveProfile(
        accessToken: String,
        request: OposiBotsProfileRequest,
        context: OposiBotsAuthContext,
    ) {
        currentUser(accessToken, context)
        val response = dataRequest(
            method = HttpMethod.Post,
            path = "/rest/v1/rpc/oposibots_upsert_own_profile",
            body = buildJsonObject {
                put("p_first_name", request.firstName.trim())
                put("p_last_name", request.lastName.trim())
                request.username?.trim()?.takeIf(String::isNotBlank)?.let { put("p_username", it) }
                    ?: put("p_username", JsonNull)
                put("p_specialty", request.specialty.trim())
            },
            accessToken = accessToken,
            context = context,
        )
        if (!response.status.isSuccessStatus()) {
            if (response.status == HttpStatusCode.Conflict || response.body.contains("23505")) {
                throw OposiBotsAuthProblem.conflict()
            }
            throw mapCredentialError(response)
        }
    }

    override suspend fun recover(request: OposiBotsRecoverRequest, context: OposiBotsAuthContext) {
        val redirect = configuration.passwordRecoveryRedirectUrl
            ?.let { "?redirect_to=${it.urlEncode()}" }
            .orEmpty()
        val response = authRequest(
            HttpMethod.Post,
            "/auth/v1/recover$redirect",
            buildJsonObject {
                put("email", request.identifier.trim())
                request.captchaToken?.takeIf(String::isNotBlank)?.let { put("captcha_token", it) }
            },
            context = context,
        )
        if (response.status == HttpStatusCode.TooManyRequests) throw OposiBotsAuthProblem.unavailable(60)
        if (response.status.value >= 500) throw OposiBotsAuthProblem.unavailable()
    }

    override suspend fun reset(request: OposiBotsResetPasswordRequest, context: OposiBotsAuthContext) {
        val verified = authRequest(
            HttpMethod.Post,
            "/auth/v1/verify",
            buildJsonObject {
                put("token_hash", request.resetToken)
                put("type", "recovery")
            },
            context = context,
        )
        if (!verified.status.isSuccessStatus()) throw mapCredentialError(verified)
        val recovery = verified.decode<SupabaseSession>()
        val updated = authRequest(
            HttpMethod.Put,
            "/auth/v1/user",
            buildJsonObject { put("password", request.newPassword) },
            accessToken = recovery.accessToken,
            context = context,
        )
        if (!updated.status.isSuccessStatus()) throw mapCredentialError(updated)
    }

    override suspend fun google(
        request: OposiBotsGoogleRequest,
        context: OposiBotsAuthContext,
    ): OposiBotsOAuthResponse = oauth("google", request.idToken, context)

    override suspend fun apple(
        request: OposiBotsAppleRequest,
        context: OposiBotsAuthContext,
    ): OposiBotsOAuthResponse {
        verifyAppleCode(request, context)
        return oauth("apple", request.identityToken, context)
    }

    private suspend fun oauth(
        provider: String,
        idToken: String,
        context: OposiBotsAuthContext,
    ): OposiBotsOAuthResponse {
        val response = authRequest(
            HttpMethod.Post,
            "/auth/v1/token?grant_type=id_token",
            buildJsonObject {
                put("provider", provider)
                put("id_token", idToken)
            },
            context = context,
        )
        if (!response.status.isSuccessStatus()) throw mapCredentialError(response, conflictAllowed = true)
        val mintedSession = response.decode<SupabaseSession>()
        try {
            claimOAuthCredential(provider, idToken, context)
        } catch (cancelled: CancellationException) {
            revokeMintedOAuthSession(mintedSession.accessToken, context)
            throw cancelled
        } catch (problem: OposiBotsAuthProblem) {
            revokeMintedOAuthSession(mintedSession.accessToken, context)
            throw problem
        }
        val session = response.toSession(context, mintedSession)
        return OposiBotsOAuthResponse(session, isNewAccount = !session.user.profileComplete)
    }

    private suspend fun revokeMintedOAuthSession(accessToken: String, context: OposiBotsAuthContext) {
        try {
            authRequest(
                method = HttpMethod.Post,
                path = "/auth/v1/logout?scope=global",
                body = null,
                accessToken = accessToken,
                context = context,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Best effort compensation: never replace the original replay/claim failure.
        }
    }

    private suspend fun verifyAppleCode(request: OposiBotsAppleRequest, context: OposiBotsAuthContext) {
        val clientId = configuration.appleClientId ?: throw OposiBotsAuthProblem.unavailable()
        val clientSecret = configuration.appleClientSecret ?: throw OposiBotsAuthProblem.unavailable()
        val response = upstreamRequest(
            method = HttpMethod.Post,
            absoluteUrl = "https://appleid.apple.com/auth/token",
            form = FormDataContent(
                Parameters.build {
                    append("client_id", clientId)
                    append("client_secret", clientSecret)
                    append("code", request.authorizationCode)
                    append("grant_type", "authorization_code")
                },
            ),
        )
        if (!response.status.isSuccessStatus()) throw OposiBotsAuthProblem.authentication()
        val appleToken = response.decode<AppleTokenResponse>().idToken
            ?: throw OposiBotsAuthProblem.authentication()
        if (appleToken.jwtSubject() != request.identityToken.jwtSubject()) {
            throw OposiBotsAuthProblem.authentication()
        }
    }

    private suspend fun claimOAuthCredential(
        provider: String,
        idToken: String,
        context: OposiBotsAuthContext,
    ) {
        val response = supabaseRequest(
            method = HttpMethod.Post,
            path = "/rest/v1/rpc/oposibots_claim_oauth_credential",
            body = buildJsonObject {
                put("p_provider", provider)
                put("p_credential_hash", idToken.sha256())
                put("p_expires_at", idToken.jwtExpiry().toString())
            },
            accessToken = null,
            apiKey = configuration.secretKey,
            context = context,
        )
        if (!response.status.isSuccessStatus()) {
            if (response.status == HttpStatusCode.Conflict || response.body.contains("23505")) {
                throw OposiBotsAuthProblem.authentication()
            }
            throw OposiBotsAuthProblem.unavailable()
        }
    }

    private suspend fun AuthResponse.toSession(
        context: OposiBotsAuthContext,
        decodedSession: SupabaseSession? = null,
    ): OposiBotsAuthSessionDto {
        val session = decodedSession ?: decode()
        if (session.accessToken.isBlank() || session.refreshToken.isBlank()) {
            throw OposiBotsAuthProblem.unavailable()
        }
        val profile = profile(session.accessToken, session.user.id, context)
        if (profile?.deletedAt != null) throw OposiBotsAuthProblem.accessDenied()
        return OposiBotsAuthSessionDto(
            accessToken = session.accessToken,
            refreshToken = session.refreshToken,
            expiresAtEpochSeconds = session.expiresAt
                ?: (Instant.now().epochSecond + session.expiresIn.coerceAtLeast(60)),
            user = OposiBotsAuthUserDto(
                id = session.user.id,
                email = session.user.email,
                profileComplete = profile?.profileComplete == true,
            ),
        )
    }

    private suspend fun currentUser(accessToken: String, context: OposiBotsAuthContext): SupabaseUser {
        val response = authRequest(
            HttpMethod.Get,
            "/auth/v1/user",
            body = null,
            accessToken = accessToken,
            context = context,
        )
        if (!response.status.isSuccessStatus()) throw mapCredentialError(response)
        return response.decode()
    }

    private suspend fun profile(
        accessToken: String,
        userId: String,
        context: OposiBotsAuthContext,
    ): ProfileRow? {
        val response = dataRequest(
            HttpMethod.Get,
            "/rest/v1/usuario?select=perfil_completo,deleted_at&auth_user_id=eq.${userId.urlEncode()}&limit=1",
            body = null,
            accessToken = accessToken,
            context = context,
        )
        if (!response.status.isSuccessStatus()) throw mapCredentialError(response)
        return response.decode<List<ProfileRow>>().firstOrNull()
    }

    private suspend fun authRequest(
        method: HttpMethod,
        path: String,
        body: JsonObject?,
        accessToken: String? = null,
        context: OposiBotsAuthContext,
    ): AuthResponse = supabaseRequest(
        method = method,
        path = path,
        body = body,
        accessToken = accessToken,
        apiKey = configuration.secretKey,
        context = context,
    )

    private suspend fun dataRequest(
        method: HttpMethod,
        path: String,
        body: JsonObject?,
        accessToken: String,
        context: OposiBotsAuthContext,
        prefer: String? = null,
    ): AuthResponse = supabaseRequest(
        method = method,
        path = path,
        body = body,
        accessToken = accessToken,
        apiKey = configuration.publishableKey,
        context = context,
        prefer = prefer,
    )

    private suspend fun supabaseRequest(
        method: HttpMethod,
        path: String,
        body: JsonObject?,
        accessToken: String?,
        apiKey: String,
        context: OposiBotsAuthContext,
        prefer: String? = null,
    ): AuthResponse {
        return try {
            val response = client.request("${configuration.supabaseUrl}$path") {
                this.method = method
                header("apikey", apiKey)
                accessToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                if (accessToken == null && path.startsWith("/rest/") && apiKey.count { it == '.' } == 2) {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                }
                if (
                    configuration.forwardClientIpToSupabase &&
                    path.startsWith("/auth/v1/") &&
                    context.clientIp.isNotBlank()
                ) {
                    header("Sb-Forwarded-For", context.clientIp)
                }
                prefer?.let { header("Prefer", it) }
                header("X-Request-Id", context.requestId)
                header(HttpHeaders.Accept, ContentType.Application.Json)
                body?.let {
                    contentType(ContentType.Application.Json)
                    setBody(it.toString())
                }
            }
            AuthResponse(response.status, response.bodyAsText(), json)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unexpected: Exception) {
            // Logueado (S1D1/DAY_02, 2026-08-13): antes se descartaba en
            // silencio -- cualquier fallo de red/TLS/DNS hacia Supabase se
            // veia identico a un 5xx real de Supabase, sin forma de saber
            // cual de los dos pasaba.
            GatewayLogger.warn("supabaseRequest path={} failed", path, unexpected)
            throw OposiBotsAuthProblem.unavailable()
        }
    }

    private suspend fun upstreamRequest(
        method: HttpMethod,
        absoluteUrl: String,
        form: FormDataContent,
    ): AuthResponse {
        return try {
            val response = client.request(absoluteUrl) {
                this.method = method
                setBody(form)
                header(HttpHeaders.Accept, ContentType.Application.Json)
            }
            AuthResponse(response.status, response.bodyAsText(), json)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw OposiBotsAuthProblem.unavailable()
        }
    }

    private fun mapCredentialError(
        response: AuthResponse,
        conflictAllowed: Boolean = false,
    ): OposiBotsAuthProblem {
        val error = response.error()
        val fingerprint = "${error.normalizedCode} ${error.msg}".lowercase()
        // Logueado (S1D1/DAY_02, 2026-08-13): la respuesta real de Supabase
        // Auth (status + error_code/msg) antes se descartaba por completo --
        // el cliente solo veia AuthenticationRequired/TemporarilyUnavailable
        // genericos sin ninguna pista de la causa real.
        GatewayLogger.warn("mapCredentialError status={} error_code={} msg={}", response.status.value, error.error_code, error.msg)
        return when {
            response.status == HttpStatusCode.TooManyRequests -> OposiBotsAuthProblem.unavailable(60)
            response.status.value >= 500 -> OposiBotsAuthProblem.unavailable()
            conflictAllowed && ("already" in fingerprint || response.status == HttpStatusCode.Conflict) ->
                OposiBotsAuthProblem.conflict()
            else -> OposiBotsAuthProblem.authentication()
        }
    }

    private fun credentialBody(identifier: String, password: String, captchaToken: String?): JsonObject =
        buildJsonObject {
            put("email", identifier.trim().lowercase())
            put("password", password)
            captchaToken?.takeIf(String::isNotBlank)?.let { put("captcha_token", it) }
        }
}

private data class AuthResponse(
    val status: HttpStatusCode,
    val body: String,
    val json: Json,
) {
    inline fun <reified T> decode(): T = try {
        json.decodeFromString(body)
    } catch (_: Exception) {
        throw OposiBotsAuthProblem.unavailable()
    }

    fun error(): SupabaseError = runCatching { json.decodeFromString<SupabaseError>(body) }
        .getOrDefault(SupabaseError())
}

@Serializable
private data class SupabaseSession(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_at") val expiresAt: Long? = null,
    @SerialName("expires_in") val expiresIn: Long = 3_600,
    val user: SupabaseUser,
)

@Serializable
private data class SupabaseUser(
    val id: String,
    val email: String? = null,
)

@Serializable
private data class ProfileRow(
    @SerialName("perfil_completo") val profileComplete: Boolean = false,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
private data class SupabaseError(
    // Bug real corregido (S1D1/DAY_02, 2026-08-13): la respuesta de error de
    // Supabase Auth es `{"code":400,"error_code":"...","msg":"..."}` --
    // `code` es NUMERICO (duplica el status HTTP) y el mensaje viene en
    // `msg`, no en `message`. Con los tipos viejos (`code: String?`, sin
    // `msg`), kotlinx.serialization tiraba una excepcion de tipo al decodificar
    // (numero contra String), `runCatching` la atrapaba y devolvia
    // `SupabaseError()` vacio -- TODOS los errores de Supabase se perdian en
    // silencio, sin importar cual fuera. Verificado con curl directo contra
    // `/auth/v1/signup`.
    val code: Int? = null,
    val error_code: String? = null,
    val msg: String? = null,
) {
    val normalizedCode: String get() = error_code.orEmpty()
}

@Serializable
private data class AppleTokenResponse(
    @SerialName("id_token") val idToken: String? = null,
)

private fun defaultAuthHttpClient(): HttpClient = HttpClient(Java) {
    expectSuccess = false
    install(HttpTimeout) {
        connectTimeoutMillis = 5_000
        requestTimeoutMillis = 15_000
        socketTimeoutMillis = 15_000
    }
}

private fun HttpStatusCode.isSuccessStatus(): Boolean = value in 200..299

private fun String.urlEncode(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8).replace("+", "%20")

private fun String.jwtSubject(): String {
    val parts = split('.')
    if (parts.size != 3) throw OposiBotsAuthProblem.authentication()
    val payload = runCatching {
        String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
    }.getOrElse { throw OposiBotsAuthProblem.authentication() }
    return runCatching {
        Json.parseToJsonElement(payload).jsonObject["sub"]?.jsonPrimitive?.content.orEmpty()
    }.getOrDefault("").takeIf(String::isNotBlank) ?: throw OposiBotsAuthProblem.authentication()
}

private fun String.jwtExpiry(): Instant {
    val payload = jwtPayload()
    val epoch = payload["exp"]?.jsonPrimitive?.content?.toLongOrNull()
        ?: throw OposiBotsAuthProblem.authentication()
    return runCatching { Instant.ofEpochSecond(epoch) }
        .getOrElse { throw OposiBotsAuthProblem.authentication() }
        .takeIf { it.isAfter(Instant.now()) }
        ?: throw OposiBotsAuthProblem.authentication()
}

private fun String.jwtPayload(): JsonObject {
    val parts = split('.')
    if (parts.size != 3) throw OposiBotsAuthProblem.authentication()
    val payload = runCatching {
        String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
    }.getOrElse { throw OposiBotsAuthProblem.authentication() }
    return runCatching { Json.parseToJsonElement(payload).jsonObject }
        .getOrElse { throw OposiBotsAuthProblem.authentication() }
}

private fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
