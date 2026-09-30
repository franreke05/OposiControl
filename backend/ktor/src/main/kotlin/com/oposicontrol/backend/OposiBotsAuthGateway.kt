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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
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

/**
 * Las operaciones de `/v1/auth` contra Supabase Auth (GoTrue). Perfil (`/me`, `/register/profile`) ya NO esta
 * aqui: desde SLICE 1 lo sirve el servicio canonico ([CanonicalProfileService]) sobre `perfiles_usuario`.
 */
internal interface OposiBotsAuthGateway {
    suspend fun register(request: OposiBotsRegisterRequest, context: OposiBotsAuthContext): OposiBotsRegisterResponse
    suspend fun resend(request: OposiBotsEmailResendRequest, context: OposiBotsAuthContext)
    suspend fun exchangeEmail(request: OposiBotsEmailExchangeRequest, context: OposiBotsAuthContext): OposiBotsAuthSessionDto
    suspend fun login(request: OposiBotsLoginRequest, context: OposiBotsAuthContext): OposiBotsAuthSessionDto
    suspend fun refresh(request: OposiBotsRefreshRequest, context: OposiBotsAuthContext): OposiBotsAuthSessionDto
    suspend fun logout(accessToken: String, context: OposiBotsAuthContext)
    suspend fun recover(request: OposiBotsRecoverRequest, context: OposiBotsAuthContext)
    suspend fun reset(request: OposiBotsResetPasswordRequest, context: OposiBotsAuthContext)
    suspend fun google(request: OposiBotsGoogleRequest, context: OposiBotsAuthContext): OposiBotsOAuthResponse
    suspend fun apple(request: OposiBotsAppleRequest, context: OposiBotsAuthContext): OposiBotsOAuthResponse
}

/**
 * GoTrue + la PUERTA DE ESTADO de cuenta.
 *
 * Toda operacion que ACUÑA tokens (login, refresh, exchange, google, apple) lee despues `perfiles_usuario`
 * con la clave de servicio ([CanonicalProfileDirectory]) y aplica `requireActive()` (deny-by-default): si la
 * cuenta no es `activa` explicita, o no hay fila, se REVOCA la sesion recien acuñada y se responde 403.
 * [profileDirectory] existe para inyectarlo en pruebas; sin el se lee `perfiles_usuario` con el cliente de este gateway.
 */
internal class SupabaseOposiBotsAuthGateway(
    private val configuration: OposiBotsAuthConfiguration,
    private val json: Json,
    private val client: HttpClient = defaultAuthHttpClient(),
    private val profileDirectory: CanonicalProfileDirectory? = null,
) : OposiBotsAuthGateway, AuthProviderClient, AutoCloseable {
    /** MASTER-ARCH-008: libera el `HttpClient` (lo llama [OposiBotsAuthRuntime.close] al parar la aplicacion). */
    override fun close() {
        client.close()
    }

    override suspend fun register(
        request: OposiBotsRegisterRequest,
        context: OposiBotsAuthContext,
    ): OposiBotsRegisterResponse {
        val body = credentialBody(request.resolvedIdentifier(), request.password, request.captchaToken)
        val response = authRequest(HttpMethod.Post, "/auth/v1/signup", body, context = context)
        // Enumeration-safe (D1-01): con autoconfirm GoTrue contesta 422 user_already_exists en vez del 200 ofuscado;
        // un duplicado se contesta igual que un alta nueva (202) y nunca revela que el email existe.
        if (response.status.value == 422 && response.error().normalizedCode in DUPLICATE_SIGNUP_CODES) {
            return OposiBotsRegisterResponse()
        }
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
        // MASTER-AUTH-007: el 429 de GoTrue (60 s por usuario) solo existe para emails registrados; propagarlo seria un
        // oraculo de existencia. Se registra internamente y se contesta igual que el resto (204).
        if (response.status == HttpStatusCode.TooManyRequests) {
            GatewayLogger.warn("auth email throttled by upstream (indistinguishable 204 returned)")
        }
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
        if (!response.status.isSuccessStatus()) throw mapCredentialError(response, purpose = AuthPurpose.EXCHANGE)
        return response.toSession(context)
    }

    override suspend fun login(
        request: OposiBotsLoginRequest,
        context: OposiBotsAuthContext,
    ): OposiBotsAuthSessionDto {
        val response = authRequest(
            HttpMethod.Post,
            "/auth/v1/token?grant_type=password",
            credentialBody(request.resolvedIdentifier(), request.password, null),
            context = context,
        )
        if (!response.status.isSuccessStatus()) throw mapCredentialError(response, purpose = AuthPurpose.LOGIN)
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
        // MASTER-AUTH-010: si la puerta no puede LEER el estado (fallo transitorio), la sesion rotada no se revoca: el
        // cliente nunca recibio sus tokens y reintenta con su refresh token anterior (ventana de reutilizacion de GoTrue).
        // Revocarla (y antes, en global) convertia una caida de PostgREST en cierre de sesion en todos los dispositivos.
        return response.toSession(context, transientRevocation = null)
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

    override suspend fun recover(request: OposiBotsRecoverRequest, context: OposiBotsAuthContext) {
        val redirect = configuration.passwordRecoveryRedirectUrl
            ?.let { "?redirect_to=${it.urlEncode()}" }
            .orEmpty()
        val response = authRequest(
            HttpMethod.Post,
            "/auth/v1/recover$redirect",
            buildJsonObject {
                put("email", request.resolvedIdentifier().trim())
                request.captchaToken?.takeIf(String::isNotBlank)?.let { put("captcha_token", it) }
            },
            context = context,
        )
        // MASTER-AUTH-007: el 429 de GoTrue (60 s por usuario) solo existe para emails registrados; propagarlo seria un
        // oraculo de existencia. Se registra internamente y se contesta igual que el resto (204).
        if (response.status == HttpStatusCode.TooManyRequests) {
            GatewayLogger.warn("auth email throttled by upstream (indistinguishable 204 returned)")
        }
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
        val updated = try {
            authRequest(
                HttpMethod.Put,
                "/auth/v1/user",
                buildJsonObject { put("password", request.newPassword) },
                accessToken = recovery.accessToken,
                context = context,
            )
        } catch (failure: Exception) {
            // Incluye la cancelacion: la sesion de recuperacion acuñada por /verify no debe quedar viva.
            revokeMintedSession(recovery.accessToken, context, scope = "local")
            throw failure
        }
        if (!updated.status.isSuccessStatus()) {
            // MASTER-AUTH-006: la contraseña NO cambio -> solo muere la sesion de recuperacion, no las demas.
            revokeMintedSession(recovery.accessToken, context, scope = "local")
            throw mapCredentialError(updated)
        }
        // MASTER-AUTH-006: contraseña cambiada -> se revocan TODAS las sesiones y refresh tokens del usuario (incluida
        // la de recuperacion). Mejor esfuerzo: la contraseña ya cambio, un fallo aqui no convierte el reset en error.
        revokeMintedSession(recovery.accessToken, context, scope = "global")
    }

    override suspend fun google(
        request: OposiBotsGoogleRequest,
        context: OposiBotsAuthContext,
    ): OposiBotsOAuthResponse = oauth("google", request.idToken, request.nonce, context)

    override suspend fun apple(
        request: OposiBotsAppleRequest,
        context: OposiBotsAuthContext,
    ): OposiBotsOAuthResponse {
        // Credenciales de servidor ANTES de acuñar nada (sin ellas no hay forma de validar el authorizationCode).
        if (configuration.appleClientId == null || configuration.appleClientSecret == null) {
            throw OposiBotsAuthProblem.unavailable()
        }
        // MASTER-AUTH-011: el authorizationCode (de un solo uso) se canjea DESPUES de la puerta de estado, asi un 503
        // transitorio de la puerta no lo quema y el reintento documentado funciona.
        val response = oauth("apple", request.identityToken, request.nonce, context) {
            verifyAppleCode(request, context)
        }
        // Solo tras pasar la puerta de estado y el replay guard: los nombres de la primera autenticacion.
        persistFirstAuthNames(response.session.user.id, request.firstAuthNames(), context)
        return response
    }

    /**
     * Orden (MASTER-AUTH-005/011): acuñar -> PUERTA DE ESTADO -> [beforeClaim] -> reclamar la credencial.
     *
     * - La puerta va antes del reclamo: un 503 transitorio de la puerta deja la credencial SIN consumir y el reintento
     *   con el mismo idToken funciona. Una denegacion (bloqueada, borrado pendiente...) es terminal igualmente.
     * - Si [beforeClaim] o el reclamo fallan (replay, carrera concurrente), se revoca SOLO la sesion recien acuñada
     *   (`scope=local`): un replay de un idToken ya usado no puede cerrar las demas sesiones del usuario real.
     */
    private suspend fun oauth(
        provider: String,
        idToken: String,
        nonce: String?,
        context: OposiBotsAuthContext,
        beforeClaim: suspend () -> Unit = {},
    ): OposiBotsOAuthResponse {
        val response = authRequest(
            HttpMethod.Post,
            "/auth/v1/token?grant_type=id_token",
            buildJsonObject {
                put("provider", provider)
                put("id_token", idToken)
                nonce?.takeIf(String::isNotBlank)?.let { put("nonce", it) }
            },
            context = context,
        )
        if (!response.status.isSuccessStatus()) throw mapCredentialError(response, conflictAllowed = true)
        val mintedSession = response.decode<SupabaseSession>()
        val session = response.toSession(context, mintedSession)
        try {
            beforeClaim()
            claimOAuthCredential(provider, idToken, context)
        } catch (failure: Exception) {
            // Incluye CancellationException (se relanza tal cual) y cualquier problema del reclamo.
            revokeMintedSession(mintedSession.accessToken, context, scope = "local")
            throw failure
        }
        return OposiBotsOAuthResponse(session, isNewAccount = !session.user.onboardingCompleted)
    }

    /**
     * Revoca la sesion recien acuñada. `local` (por defecto) mata SOLO esa sesion; `global` mata todas las del usuario
     * y se reserva para decisiones definitivas (cuenta denegada por la puerta, contraseña cambiada).
     * Mejor esfuerzo: nunca sustituye al error original.
     */
    private suspend fun revokeMintedSession(accessToken: String, context: OposiBotsAuthContext, scope: String = "local") {
        withContext(NonCancellable) {
            try {
                authRequest(
                    method = HttpMethod.Post,
                    path = "/auth/v1/logout?scope=$scope",
                    body = null,
                    accessToken = accessToken,
                    context = context,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Best effort compensation: never replace the original replay/claim/gate failure.
            }
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

    /**
     * Nombre y apellidos de la primera autenticacion de Apple -> `perfiles_usuario.nombre/apellidos`, SOLO si estan
     * vacios (escritura del servidor con la clave de servicio, tras la puerta de estado). Un fallo no niega el acceso.
     *
     * EXCEPCION DOCUMENTADA al invariante "solo las RPC app_* escriben perfiles" (MASTER-ARCH-001, ver
     * ARCHITECTURE_DELTA_S1.md): un UPDATE unico condicionado a `nombre/apellidos` vacios; si compite con
     * `app_actualizar_perfil` (que tiene la fila con FOR UPDATE) Postgres espera y reevalua el WHERE, asi que nunca pisa
     * un nombre ya escrito. Solo transita vacio -> lleno, una vez, y sube `actualizado_en` (ETag).
     */
    private suspend fun persistFirstAuthNames(
        userId: String,
        names: Pair<String?, String?>,
        context: OposiBotsAuthContext,
    ) {
        val id = CanonicalUserId.fromJwtSubject(userId).value
        listOf("nombre" to names.first, "apellidos" to names.second).forEach { (column, value) ->
            val text = value?.trim()?.take(100)?.takeIf(String::isNotEmpty) ?: return@forEach
            try {
                val response = supabaseRequest(
                    method = HttpMethod.Patch,
                    path = "/rest/v1/perfiles_usuario?usuario_id=eq.$id&or=($column.is.null,$column.eq.)",
                    // MASTER-ARCH-001: el ETag de perfil es `actualizado_en`; sin subirlo, un If-Match anterior a esta
                    // escritura seguiria valiendo y un PATCH /me/profile lo pisaria (actualizacion perdida).
                    body = buildJsonObject {
                        put(column, text)
                        put("actualizado_en", Instant.now().toString())
                    },
                    accessToken = null,
                    apiKey = configuration.secretKey,
                    context = context,
                    prefer = "return=minimal",
                )
                if (!response.status.isSuccessStatus()) {
                    GatewayLogger.warn("first-auth {} not persisted status={}", column, response.status.value)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                GatewayLogger.warn("first-auth {} not persisted: {}", column, failure.toString())
            }
        }
    }

    private suspend fun AuthResponse.toSession(
        context: OposiBotsAuthContext,
        decodedSession: SupabaseSession? = null,
        transientRevocation: String? = "local",
    ): OposiBotsAuthSessionDto {
        val session = decodedSession ?: decode()
        if (session.accessToken.isBlank() || session.refreshToken.isBlank()) {
            throw OposiBotsAuthProblem.unavailable()
        }
        val identity = enforceAccountGate(session, context, transientRevocation)
        return OposiBotsAuthSessionDto(
            accessToken = session.accessToken,
            refreshToken = session.refreshToken,
            expiresAtEpochSeconds = session.expiresAt
                ?: (Instant.now().epochSecond + session.expiresIn.coerceAtLeast(60)),
            user = OposiBotsAuthUserDto(
                id = session.user.id,
                email = session.user.email,
                profileComplete = identity.onboardingCompleted,
                onboardingCompleted = identity.onboardingCompleted,
                accountStatus = AccountStatus.ACTIVA.wire,
            ),
        )
    }

    /**
     * Puerta de estado tras acuñar tokens. Solo `activa` explicita pasa (fallo CERRADO: nunca se devuelve la sesion).
     *
     * - Denegacion DEFINITIVA (403 ACCOUNT_BLOCKED / ACCOUNT_DELETION_PENDING / FORBIDDEN / PROFILE_MISSING): se
     *   revoca en `global`, la cuenta no debe conservar ninguna sesion.
     * - Fallo TRANSITORIO de lectura (503, cancelacion): MASTER-AUTH-010, se revoca solo la sesion acuñada
     *   ([transientRevocation] = `local`) o nada (`null`, refresh: el cliente reintenta con su token anterior). Una
     *   caida de PostgREST ya no cierra la sesion en todos los dispositivos.
     */
    private suspend fun enforceAccountGate(
        session: SupabaseSession,
        context: OposiBotsAuthContext,
        transientRevocation: String? = "local",
    ): CanonicalUserIdentity {
        suspend fun revokeTransient() {
            transientRevocation?.let { revokeMintedSession(session.accessToken, context, scope = it) }
        }
        try {
            val userId = CanonicalUserId.fromJwtSubject(session.user.id)
            val identity = findProfile(userId, context) ?: throw CanonicalErrorCode.PROFILE_MISSING.exception()
            return identity.requireActive()
        } catch (cancelled: CancellationException) {
            revokeTransient()
            throw cancelled
        } catch (denied: BackendDomainException) {
            revokeMintedSession(session.accessToken, context, scope = "global")
            throw denied
        } catch (problem: OposiBotsAuthProblem) {
            revokeTransient()
            throw problem
        } catch (unexpected: Exception) {
            GatewayLogger.warn("account gate could not read the profile: {}", unexpected.toString())
            revokeTransient()
            throw OposiBotsAuthProblem.unavailable()
        }
    }

    private suspend fun findProfile(userId: CanonicalUserId, context: OposiBotsAuthContext): CanonicalUserIdentity? {
        profileDirectory?.let { return it.find(userId) }
        val response = supabaseRequest(
            method = HttpMethod.Get,
            path = PostgrestCanonicalProfileDirectory.profileStatePath(userId),
            body = null,
            accessToken = null,
            apiKey = configuration.secretKey,
            context = context,
        )
        if (!response.status.isSuccessStatus()) throw OposiBotsAuthProblem.unavailable()
        val row = json.decodeFromString(ListSerializer(PostgrestCanonicalProfileDirectory.ProfileRow.serializer()), response.body)
            .firstOrNull() ?: return null
        return row.toIdentity(userId) { throw OposiBotsAuthProblem.unavailable() }
    }

    // ---------- AuthProviderClient (servicio canonico de perfil y borrado de cuenta) ----------

    override suspend fun user(accessToken: String, context: OposiBotsAuthContext): AuthProviderUser = providerCall {
        val response = authRequest(HttpMethod.Get, "/auth/v1/user", body = null, accessToken = accessToken, context = context)
        if (!response.status.isSuccessStatus()) throw providerFailure(response)
        val user = response.decode<GoTrueUser>()
        AuthProviderUser(
            id = user.id,
            email = user.email,
            providers = (user.appMetadata.providers + user.identities.mapNotNull { it.provider }).distinct(),
            createdAt = user.createdAt,
        )
    }

    override suspend fun verifyPassword(email: String, password: String, context: OposiBotsAuthContext) = providerCall {
        val response = authRequest(
            HttpMethod.Post,
            "/auth/v1/token?grant_type=password",
            credentialBody(email, password, null),
            context = context,
        )
        when {
            response.status.isSuccessStatus() -> {
                // La verificacion mintio una sesion: se revoca SOLO esa (local), no las demas del usuario.
                runCatching { response.decode<SupabaseSession>().accessToken }
                    .getOrNull()
                    ?.let { revokeMintedSession(it, context, scope = "local") }
            }
            response.status == HttpStatusCode.TooManyRequests || response.status.value >= 500 -> throw providerFailure(response)
            else -> throw CanonicalErrorCode.REAUTH_FAILED.exception()
        }
        Unit
    }

    /** Fallos de red / decodificacion del gateway -> 503 canonico (nada cambio). Los canonicos suben tal cual. */
    private suspend fun <T> providerCall(block: suspend () -> T): T = try {
        block()
    } catch (problem: OposiBotsAuthProblem) {
        throw CanonicalErrorCode.UPSTREAM_UNAVAILABLE.exception(retryAfterSeconds = problem.retryAfterSeconds?.toInt())
    }

    private fun providerFailure(response: AuthResponse): BackendDomainException = when {
        response.status == HttpStatusCode.TooManyRequests -> CanonicalErrorCode.RATE_LIMITED.exception(retryAfterSeconds = 60)
        response.status.value >= 500 -> CanonicalErrorCode.UPSTREAM_UNAVAILABLE.exception()
        else -> CanonicalErrorCode.UNAUTHENTICATED.exception()
    }

    // ---------- transporte ----------

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
            GatewayLogger.warn("supabaseRequest path={} failed", path.substringBefore('?'), unexpected)
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

    private enum class AuthPurpose { GENERIC, LOGIN, EXCHANGE }

    private fun mapCredentialError(
        response: AuthResponse,
        conflictAllowed: Boolean = false,
        purpose: AuthPurpose = AuthPurpose.GENERIC,
    ): Exception {
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
            purpose == AuthPurpose.LOGIN && error.normalizedCode == "invalid_credentials" ->
                CanonicalErrorCode.INVALID_CREDENTIALS.exception()
            purpose == AuthPurpose.LOGIN && error.normalizedCode == "email_not_confirmed" ->
                CanonicalErrorCode.EMAIL_UNVERIFIED.exception()
            purpose == AuthPurpose.EXCHANGE -> CanonicalErrorCode.AUTH_CODE_INVALID.exception()
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

/** `GET /auth/v1/user`: solo lo que el servicio de perfil necesita. */
@Serializable
private data class GoTrueUser(
    val id: String,
    val email: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("app_metadata") val appMetadata: GoTrueAppMetadata = GoTrueAppMetadata(),
    val identities: List<GoTrueIdentity> = emptyList(),
)

@Serializable
private data class GoTrueAppMetadata(val providers: List<String> = emptyList())

@Serializable
private data class GoTrueIdentity(val provider: String? = null)

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

private val DUPLICATE_SIGNUP_CODES = setOf("user_already_exists", "email_exists")
