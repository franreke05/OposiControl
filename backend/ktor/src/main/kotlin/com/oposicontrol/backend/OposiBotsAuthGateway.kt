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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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

/** MASTER6-OB-002: codigos `error` documentados del endpoint de token de Apple (RFC 6749 5.2 + `invalid_scope`). */
internal val APPLE_TOKEN_ERROR_CODES: Set<String> =
    setOf("invalid_request", "invalid_client", "invalid_grant", "unauthorized_client", "unsupported_grant_type", "invalid_scope")

/**
 * MASTER2-AUTH-003: esperas entre reintentos de la lectura de la puerta en refresh (3 intentos, ~1 s de espera total).
 * La duracion TOTAL la acotan [REFRESH_GATE_ATTEMPT_TIMEOUT_MS] y [REFRESH_GATE_DEADLINE_MS] (MASTER3-AUTH-005), por
 * debajo del timeout del cliente (15 s) y de la ventana de reutilizacion de GoTrue (10 s por defecto).
 */
internal val REFRESH_GATE_RETRY_DELAYS_MS: List<Long> = listOf(250L, 750L)

/**
 * MASTER3-AUTH-005: las esperas solas no acotaban nada: cada intento podia durar hasta el timeout del cliente HTTP
 * (15 s), ~46 s en total con un PostgREST lento, mas que el timeout del cliente movil (15 s), que reintentaba entonces
 * con un refresh token ya rotado. Ahora cada lectura tiene su propio limite y la puerta entera un plazo global; al
 * agotarse se trata como fallo transitorio (503, nunca fail-open).
 */
internal const val REFRESH_GATE_ATTEMPT_TIMEOUT_MS: Long = 1_500L
internal const val REFRESH_GATE_DEADLINE_MS: Long = 5_000L

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
 *
 * La misma puerta consulta [accessRequestSessionGate] (AccessRequestMailGuard.kt): un usuario con una solicitud de acceso
 * de administrador NO aprobada no recibe sesion (revocada en global, 403 FORBIDDEN). Es OBLIGATORIO y no tiene valor por
 * defecto: ninguna construccion puede quedarse sin puerta por omision.
 */
internal class SupabaseOposiBotsAuthGateway(
    private val configuration: OposiBotsAuthConfiguration,
    private val json: Json,
    internal val accessRequestSessionGate: AccessRequestSessionGate,
    private val client: HttpClient = defaultAuthHttpClient(),
    private val profileDirectory: CanonicalProfileDirectory? = null,
    /** FA39: binding of the email-link code to the requesting device; `null` = disabled (tests / legacy wiring). */
    private val codeBinding: EmailCodeBinding? = null,
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
        bindChallenge("signup", request.resolvedIdentifier(), request.codeChallenge)
        val response = authRequest(HttpMethod.Post, "/auth/v1/signup${emailConfirmRedirectQuery()}", body, context = context)
        // Enumeration-safe (D1-01): con autoconfirm GoTrue contesta 422 user_already_exists en vez del 200 ofuscado;
        // un duplicado se contesta igual que un alta nueva (202) y nunca revela que el email existe.
        // MASTER4-API-003: tambien con 400 (antes solo 422): tras quitar el 401 generico, un duplicado con 400 ya no puede
        // salir como VALIDATION (seria un oraculo de existencia frente al 202 de un alta nueva).
        if ((response.status.value == 422 || response.status.value == 400) && response.error().normalizedCode in DUPLICATE_SIGNUP_CODES) {
            return OposiBotsRegisterResponse()
        }
        // REAL-AUTH GATE (E1-V1-REAL-AUTH-RESULT.md): a real GoTrue answers 429 over_email_send_rate_limit to a signup of an EXISTING unconfirmed account (60 s per user) and to anyone when the hourly mail budget is spent;
        // the first is an existence oracle (a new signup answers 202), so a 429 is logged and answered like a new signup. (The stub never throttles.)
        if (response.status == HttpStatusCode.TooManyRequests) {
            GatewayLogger.warn("auth signup throttled by upstream (indistinguishable 202 returned)")
            logStrategicEvent(StrategicEvent.AUTH_SIGNUP_THROTTLED, requestId = context.requestId, operation = "register", result = "202_without_mail")
            return OposiBotsRegisterResponse()
        }
        if (!response.status.isSuccessStatus()) throw mapCredentialError(response, conflictAllowed = false, purpose = AuthPurpose.REGISTER)
        return OposiBotsRegisterResponse()
    }

    override suspend fun resend(request: OposiBotsEmailResendRequest, context: OposiBotsAuthContext) {
        bindChallenge("signup", request.email, request.codeChallenge)
        val response = authRequest(
            HttpMethod.Post,
            "/auth/v1/resend${emailConfirmRedirectQuery()}",
            buildJsonObject {
                put("type", "signup")
                put("email", request.email.canonicalForUpstream())
                request.captchaToken?.takeIf(String::isNotBlank)?.let { put("captcha_token", it) }
            },
            context = context,
        )
        // MASTER-AUTH-007: el 429 de GoTrue (60 s por usuario) solo existe para emails registrados; propagarlo seria un
        // oraculo de existencia. Se registra internamente y se contesta igual que el resto (204).
        if (response.status == HttpStatusCode.TooManyRequests) {
            GatewayLogger.warn("auth email throttled by upstream (indistinguishable 204 returned)")
        }
        logMailRejectedByUpstream("resend", response, request.email, context)
        if (response.status.value >= 500) throw OposiBotsAuthProblem.unavailable()
        // All other outcomes are intentionally indistinguishable.
    }

    /**
     * B2 (2026-10-13): `?redirect_to=` del correo de confirmacion (signup y resend), igual que [recover] con el suyo. Sin
     * `OPOSIBOTS_EMAIL_CONFIRM_REDIRECT_URL` devuelve vacio y la peticion es identica a la de siempre.
     */
    private fun emailConfirmRedirectQuery(): String =
        configuration.emailConfirmRedirectUrl
            ?.let { "?redirect_to=${it.urlEncode()}" }
            .orEmpty()

    /**
     * B4 (2026-10-13): en resend y recover todo lo que no es 5xx sale como 204 (a proposito: no revela que correos
     * existen). Un 4xx de GoTrue distinto de 429 (captcha rechazado, `redirect_to` no permitido, correo invalido, proveedor
     * de correo mal configurado...) significaba «no ha salido ningun correo» sin dejar rastro. La respuesta al cliente NO
     * cambia; queda un WARN con el estado y el `error_code`. Del correo solo su referencia con clave ([authLogEmailRef],
     * prefijo de un HMAC-SHA-256), nunca el valor, y nunca el `msg` de GoTrue (puede reflejarlo). El `error_code` se
     * reduce a ASCII `[A-Za-z0-9_]`: lo escribe el upstream y `isLetterOrDigit` dejaba pasar cualquier letra Unicode.
     */
    private fun logMailRejectedByUpstream(operation: String, response: AuthResponse, email: String, context: OposiBotsAuthContext) {
        val status = response.status.value
        if (status !in 400..499 || response.status == HttpStatusCode.TooManyRequests) return
        GatewayLogger.warn(
            "auth email {} rejected by upstream status={} error_code={} emailRef={} requestId={} (indistinguishable 204 returned)",
            operation,
            status,
            response.error().normalizedCode
                .filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' }
                .take(64)
                .ifEmpty { "-" },
            configuration.authLogEmailRef(email),
            context.requestId,
        )
    }

    override suspend fun exchangeEmail(
        request: OposiBotsEmailExchangeRequest,
        context: OposiBotsAuthContext,
    ): OposiBotsAuthSessionDto {
        // FA39: the stored binding is spent BEFORE GoTrue spends the one-time token; no binding = the same 401 as an invalid code.
        val boundTo = consumeBinding("signup", request.email, request.codeVerifier) { CanonicalErrorCode.AUTH_CODE_INVALID.exception() }
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
        // Candidate merge: the FA39 binding check runs on the decoded session BEFORE the account gate in toSession
        // (develop H1/RB-16), so a mismatched binding never reaches the gate; then the EMAIL_LINK grant as on develop.
        val decoded = response.decode<SupabaseSession>()
        if (boundTo != null && !decoded.user.email.matchesBinding(boundTo)) {
            revokeMintedSession(decoded.accessToken, context)
            throw CanonicalErrorCode.AUTH_CODE_INVALID.exception()
        }
        return response.toSession(context, AccessRequestSessionGrant.EMAIL_LINK, decoded)
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
        return response.toSession(context, AccessRequestSessionGrant.PASSWORD)
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
        // MASTER2-AUTH-003: GoTrue YA roto el refresh token; si la lectura falla y el cliente reintenta fuera de la
        // ventana de reutilizacion, la deteccion de reuso revoca la familia. Por eso, SOLO aqui, la lectura de la
        // puerta se reintenta con un backoff corto y acotado antes de tratar el fallo como transitorio (nunca fail-open).
        return response.toSession(
            context,
            AccessRequestSessionGrant.REFRESH,
            transientRevocation = null,
            transientReadRetryDelaysMillis = REFRESH_GATE_RETRY_DELAYS_MS,
        )
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
            // MASTER3-AUTH-001: solo un 401/403 con `error_code=session_not_found` prueba que la sesion ya no existe.
            // Cualquier otro fallo (400/404/422, apikey mala, un 403 por otra causa) se mapea como antes y NO lo marca.
            if (
                (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) &&
                response.error().normalizedCode in SESSION_GONE_CODES
            ) {
                GatewayLogger.info("logout: upstream reports the session no longer exists status={}", response.status.value)
                throw OposiBotsAuthProblem.sessionGone()
            }
            throw mapCredentialError(response)
        }
    }

    override suspend fun recover(request: OposiBotsRecoverRequest, context: OposiBotsAuthContext) {
        bindChallenge("recovery", request.resolvedIdentifier(), request.codeChallenge)
        val redirect = configuration.passwordRecoveryRedirectUrl
            ?.let { "?redirect_to=${it.urlEncode()}" }
            .orEmpty()
        val response = authRequest(
            HttpMethod.Post,
            "/auth/v1/recover$redirect",
            buildJsonObject {
                put("email", request.resolvedIdentifier().canonicalForUpstream())
                request.captchaToken?.takeIf(String::isNotBlank)?.let { put("captcha_token", it) }
            },
            context = context,
        )
        // MASTER-AUTH-007: el 429 de GoTrue (60 s por usuario) solo existe para emails registrados; propagarlo seria un
        // oraculo de existencia. Se registra internamente y se contesta igual que el resto (204).
        if (response.status == HttpStatusCode.TooManyRequests) {
            GatewayLogger.warn("auth email throttled by upstream (indistinguishable 204 returned)")
        }
        logMailRejectedByUpstream("recover", response, request.resolvedIdentifier(), context)
        if (response.status.value >= 500) throw OposiBotsAuthProblem.unavailable()
    }

    override suspend fun reset(request: OposiBotsResetPasswordRequest, context: OposiBotsAuthContext) {
        val boundTo = consumeBinding("recovery", request.email, request.codeVerifier) { OposiBotsAuthProblem.authentication() }
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
        if (boundTo != null && !recovery.user.email.matchesBinding(boundTo)) {
            revokeMintedSession(recovery.accessToken, context)
            throw OposiBotsAuthProblem.authentication()
        }
        val updated = try {
            authRequest(
                HttpMethod.Put,
                "/auth/v1/user",
                buildJsonObject { put("password", request.newPassword) },
                accessToken = recovery.accessToken,
                context = context,
            )
        } catch (failure: Exception) {
            // MASTER7-AUTH-001: resultado DESCONOCIDO (transporte, timeout, cancelacion): el PUT pudo llegar a GoTrue y
            // cambiar la contraseña. Deny-by-default: se trata como si hubiera cambiado y se revocan TODAS las sesiones y
            // refresh tokens del usuario (logout de usuario `scope=global`, que tambien mata la de recuperacion). La
            // peticion SIEMPRE falla con el error original (nunca se informa exito por esta via); si el revoke falla se
            // registra en WARN dentro de [revokeMintedSession] y el error original se propaga igualmente.
            revokeMintedSession(recovery.accessToken, context, scope = "global")
            throw failure
        }
        if (!updated.status.isSuccessStatus()) {
            // MASTER-AUTH-006: un 4xx definitivo = la contraseña NO cambio -> solo muere la sesion de recuperacion.
            // RC-09: un 5xx/408 (proxy, timeout de upstream) es resultado DESCONOCIDO, igual que la excepcion de arriba:
            // la contraseña pudo cambiar -> deny-by-default, revoke `scope=global`. El error original se propaga igual.
            val outcomeUnknown = updated.status.value >= 500 || updated.status == HttpStatusCode.RequestTimeout
            revokeMintedSession(recovery.accessToken, context, scope = if (outcomeUnknown) "global" else "local")
            // MASTER3-AUTH-002: /verify ya consumio el token de recuperacion (un solo uso); un rechazo de la contraseña por
            // GoTrue (weak/same_password) es 400 VALIDATION, no 401, y el cliente debe pedir un enlace nuevo.
            throw mapCredentialError(updated, purpose = AuthPurpose.PASSWORD_RESET)
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
        val session = response.toSession(context, AccessRequestSessionGrant.OAUTH, mintedSession)
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
     *
     * MASTER5-OBS-003: un fallo del revoke ya no es silencioso. Un estado no 2xx (salvo 401/404: la sesion ya no existe,
     * que es el resultado buscado) o un fallo de transporte dejan un WARN con scope, status/clase y requestId: una
     * sesion acuñada que sigue viva tras una denegacion o un replay tiene ahora rastro. Nunca se registra el token.
     */
    private suspend fun revokeMintedSession(accessToken: String, context: OposiBotsAuthContext, scope: String = "local") {
        withContext(NonCancellable) {
            try {
                val response = authRequest(
                    method = HttpMethod.Post,
                    path = "/auth/v1/logout?scope=$scope",
                    body = null,
                    accessToken = accessToken,
                    context = context,
                )
                if (!response.status.revokeReachedItsGoal()) {
                    GatewayLogger.warn(
                        "minted-session revoke failed scope={} status={} requestId={}",
                        scope,
                        response.status.value,
                        context.requestId,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Best effort compensation: never replace the original replay/claim/gate failure.
                GatewayLogger.warn(
                    "minted-session revoke failed scope={} error={} requestId={}: {}",
                    scope,
                    failure::class.simpleName,
                    context.requestId,
                    failure.redactedMessage("revoke failed").redactSensitiveText(),
                )
            }
        }
    }

    /** 2xx = revocada; 401/404 = la sesion ya no existe (caducada o revocada antes): tambien es el resultado buscado. */
    private fun HttpStatusCode.revokeReachedItsGoal(): Boolean =
        isSuccessStatus() || this == HttpStatusCode.Unauthorized || this == HttpStatusCode.NotFound

    /**
     * MASTER6-OB-002: cada salida de error deja una linea WARN con una CATEGORIA distinta, el estado de Apple y el campo
     * `error` de Apple solo si es uno de sus codigos documentados ([APPLE_TOKEN_ERROR_CODES]); nunca el code, el
     * id_token, el client_secret ni el cuerpo. `invalid_client` (client_secret caducado o mal configurado) es un fallo de
     * configuracion del SERVIDOR: 503, no un 401 que culpa al usuario.
     */
    private suspend fun verifyAppleCode(request: OposiBotsAppleRequest, context: OposiBotsAuthContext) {
        val clientId = configuration.appleClientId ?: throw appleFailure("apple_not_configured", context, unavailable = true)
        val clientSecret = configuration.appleClientSecret ?: throw appleFailure("apple_not_configured", context, unavailable = true)
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
            category = "apple_token",
            context = context,
        )
        if (!response.status.isSuccessStatus()) {
            val appleError = response.appleErrorCode()
            throw appleFailure(
                "apple_token_rejected",
                context,
                upstreamStatus = response.status.value,
                appleError = appleError,
                unavailable = appleError == "invalid_client" || response.status.value >= 500,
            )
        }
        val decoded = try {
            response.decode<AppleTokenResponse>()
        } catch (undecodable: OposiBotsAuthProblem) {
            throw appleFailure("apple_token_undecodable", context, upstreamStatus = response.status.value, unavailable = true)
        }
        val appleToken = decoded.idToken
            ?: throw appleFailure("apple_token_missing_id_token", context, upstreamStatus = response.status.value)
        if (appleToken.jwtSubject() != request.identityToken.jwtSubject()) {
            throw appleFailure("apple_subject_mismatch", context, upstreamStatus = response.status.value)
        }
    }

    private fun appleFailure(
        category: String,
        context: OposiBotsAuthContext,
        upstreamStatus: Int? = null,
        appleError: String? = null,
        unavailable: Boolean = false,
    ): OposiBotsAuthProblem {
        GatewayLogger.warn(
            "apple verification failed category={} upstreamStatus={} appleError={} requestId={}",
            category,
            upstreamStatus ?: "-",
            appleError ?: "-",
            context.requestId,
        )
        return if (unavailable) OposiBotsAuthProblem.unavailable() else OposiBotsAuthProblem.authentication()
    }

    /** El `error` de una respuesta de error de Apple, solo si es un codigo documentado (nunca texto libre). */
    private fun AuthResponse.appleErrorCode(): String? = runCatching {
        json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.takeIf { it in APPLE_TOKEN_ERROR_CODES } ?: if (body.isBlank()) null else "other"

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
                put("p_credential_hash", idToken.oauthCredentialHash())
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
            // MASTER7-ARCH-001: un nombre de Apple con caracteres de control/formato/bidi NO se guarda (no fatal: la columna
            // queda vacia y el usuario la fija por el endpoint de perfil, que aplica la misma regla).
            val text = value?.trim()?.take(100)?.takeIf(String::isNotEmpty)?.takeIf(::isAcceptableDisplayName) ?: return@forEach
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
                GatewayLogger.warn("first-auth {} not persisted: {}", column, failure.toString().redactSensitiveText())
            }
        }
    }

    /** [grant] = como se acuño la sesion (H1): la puerta de solicitudes de acceso lo necesita para una solicitud sin verificar. */
    private suspend fun AuthResponse.toSession(
        context: OposiBotsAuthContext,
        grant: AccessRequestSessionGrant,
        decodedSession: SupabaseSession? = null,
        transientRevocation: String? = "local",
        transientReadRetryDelaysMillis: List<Long> = emptyList(),
    ): OposiBotsAuthSessionDto {
        val session = decodedSession ?: decode()
        if (session.accessToken.isBlank() || session.refreshToken.isBlank()) {
            throw OposiBotsAuthProblem.unavailable()
        }
        val identity = enforceAccountGate(session, context, grant, transientRevocation, transientReadRetryDelaysMillis)
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
     * - Solicitud de acceso de administrador NO aprobada ([AccessRequestSessionGate], por id de usuario): denegacion
     *   definitiva, 403 FORBIDDEN y revocacion `global`, igual que una cuenta bloqueada. Se decide ANTES que el estado del
     *   perfil (es la causa mas especifica y no depende de que la cuenta tenga fila de perfil). Si esa lectura falla es
     *   un fallo transitorio como el del perfil: 503, nunca se entrega la sesion. Con una solicitud SIN verificar decide
     *   [grant] (H1, AccessRequestMailGuard.kt): contrasena/refresh -> denegacion; OAuth o enlace de correo -> pasa tras
     *   neutralizar la credencial del creador y revocar sus demas sesiones (`scope=others`); si eso falla, 503 y global.
     * - M-1: la lectura (perfil + decision de la solicitud) es SIN efectos y es lo unico que se reintenta y se acota. La
     *   limpieza va despues, una vez, bajo `NonCancellable`; una denegacion revoca SIEMPRE en global.
     */
    private suspend fun enforceAccountGate(
        session: SupabaseSession,
        context: OposiBotsAuthContext,
        grant: AccessRequestSessionGrant,
        transientRevocation: String? = "local",
        transientReadRetryDelaysMillis: List<Long> = emptyList(),
    ): CanonicalUserIdentity {
        // M-1: la revocacion global de una denegacion de solicitud de acceso va dentro del bloque no cancelable de su limpieza;
        // este indicador evita repetirla (o sustituirla por una local) en los `catch`.
        var revokedGlobally = false
        suspend fun revokeTransient() {
            if (revokedGlobally) return
            transientRevocation?.let { revokeMintedSession(session.accessToken, context, scope = it) }
        }
        try {
            val userId = CanonicalUserId.fromJwtSubject(session.user.id)
            // La lectura (perfil + decision de la solicitud) es SIN efectos y es lo unico que se reintenta y se acota.
            val gate = readGateRetryingTransient(userId, context, grant, transientReadRetryDelaysMillis)
            when (val decision = gate.accessRequest) {
                AccessRequestGateDecision.Allow -> Unit
                is AccessRequestGateDecision.Deny -> {
                    // DESPUES de la lectura, una sola vez y sin poder cancelarse a medias: la limpieza (mejor esfuerzo, la
                    // denegacion no depende de ella) y SIEMPRE la revocacion global.
                    withContext(NonCancellable) {
                        runDenyCleanup(decision, grant, userId, context)
                        revokeMintedSession(session.accessToken, context, scope = "global")
                        revokedGlobally = true
                    }
                    GatewayLogger.warn(
                        "account gate denied the session: non-approved admin access request userId={} requestId={}",
                        userId.value,
                        context.requestId,
                    )
                    throw CanonicalErrorCode.FORBIDDEN.exception()
                }
                is AccessRequestGateDecision.AllowAfterCleanup -> {
                    // El estado del perfil decide antes: una cuenta que no puede entrar se deniega sin limpiar nada.
                    (gate.identity ?: throw CanonicalErrorCode.PROFILE_MISSING.exception()).requireActive()
                    val allowed = withContext(NonCancellable) {
                        try {
                            accessRequestSessionGate.cleanUp(decision, grant) { revokeOtherSessions(session.accessToken, context) }
                        } catch (failure: Exception) {
                            GatewayLogger.warn(
                                "account gate: access request cleanup failed, session denied userId={} requestId={} error={}",
                                userId.value,
                                context.requestId,
                                failure::class.simpleName,
                            )
                            null
                        }
                    }
                    when (allowed) {
                        true -> Unit
                        // La fila sigue (alguien la verifico entre medias): denegacion definitiva.
                        false -> throw CanonicalErrorCode.FORBIDDEN.exception()
                        // La limpieza fallo: nunca se entrega una sesion con la credencial del creador viva. 503 y global.
                        null -> {
                            withContext(NonCancellable) {
                                revokeMintedSession(session.accessToken, context, scope = "global")
                                revokedGlobally = true
                            }
                            throw OposiBotsAuthProblem.unavailable()
                        }
                    }
                }
            }
            val identity = gate.identity ?: throw CanonicalErrorCode.PROFILE_MISSING.exception()
            return identity.requireActive()
        } catch (cancelled: CancellationException) {
            revokeTransient()
            throw cancelled
        } catch (denied: BackendDomainException) {
            if (!revokedGlobally) revokeMintedSession(session.accessToken, context, scope = "global")
            throw denied
        } catch (problem: OposiBotsAuthProblem) {
            revokeTransient()
            throw problem
        } catch (unexpected: Exception) {
            GatewayLogger.warn("account gate could not read the profile: {}", unexpected.toString().redactSensitiveText())
            revokeTransient()
            throw OposiBotsAuthProblem.unavailable()
        }
    }

    /** La limpieza de una denegacion: mejor esfuerzo (se deniega igual), con un ERROR si falla. Nunca lanza salvo cancelacion. */
    private suspend fun runDenyCleanup(
        decision: AccessRequestGateDecision.Deny,
        grant: AccessRequestSessionGrant,
        userId: CanonicalUserId,
        context: OposiBotsAuthContext,
    ) {
        if (decision.neutralise == null) return
        try {
            accessRequestSessionGate.cleanUp(decision, grant) { error("una denegacion no revoca con scope=others") }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            GatewayLogger.error(
                "account gate: access request credential neutralisation failed on a denied session userId={} requestId={} error={}",
                userId.value,
                context.requestId,
                failure::class.simpleName,
            )
        }
    }

    /**
     * Revoca las DEMAS sesiones del usuario con la recien acuñada (`POST /auth/v1/logout?scope=others`): conserva la del
     * titular que esta entrando y mata las que el creador de la solicitud pudiera tener. A diferencia de
     * [revokeMintedSession] NO es de mejor esfuerzo: cualquier respuesta que no sea 2xx lanza (la puerta deniega).
     */
    private suspend fun revokeOtherSessions(accessToken: String, context: OposiBotsAuthContext) {
        val response = authRequest(
            method = HttpMethod.Post,
            path = "/auth/v1/logout?scope=others",
            body = null,
            accessToken = accessToken,
            context = context,
        )
        check(response.status.isSuccessStatus()) { "logout scope=others -> ${response.status.value}" }
    }

    /**
     * MASTER2-AUTH-003: reintenta SOLO fallos transitorios de lectura (503/red/decodificacion); una denegacion de
     * dominio o una cancelacion se propagan al primer intento. Agotados los reintentos, se relanza el ultimo fallo.
     */
    private suspend fun readGateRetryingTransient(
        userId: CanonicalUserId,
        context: OposiBotsAuthContext,
        grant: AccessRequestSessionGrant,
        retryDelaysMillis: List<Long>,
    ): GateRead {
        if (retryDelaysMillis.isEmpty()) return readGate(userId, context, grant)
        // MASTER3-AUTH-005: plazo global. `withTimeoutOrNull` solo absorbe SU propio timeout: una cancelacion externa
        // (cliente desconectado) se sigue propagando como cancelacion. Corre en Dispatchers.IO para que plazos y esperas
        // sean SIEMPRE de reloj real, sea cual sea el dispatcher de quien llama.
        val read = withContext(Dispatchers.IO) {
            withTimeoutOrNull(REFRESH_GATE_DEADLINE_MS) {
                retryingGateRead(userId, context, grant, retryDelaysMillis)
            }
        }
        if (read == null) {
            GatewayLogger.warn("account gate read exceeded its {} ms deadline requestId={}", REFRESH_GATE_DEADLINE_MS, context.requestId)
            throw OposiBotsAuthProblem.unavailable()
        }
        return read
    }

    /**
     * Lo que la puerta lee de una vez. Envoltorio no nulo: distingue "sin fila de perfil" (`identity == null`) de "se
     * agoto el plazo" (`withTimeoutOrNull` -> null).
     */
    private class GateRead(val identity: CanonicalUserIdentity?, val accessRequest: AccessRequestGateDecision)

    /**
     * Las DOS lecturas de la puerta (perfil y solicitud de acceso) A LA VEZ: la segunda no suma su latencia a la primera,
     * la puerta tarda lo que la mas lenta. Si una falla, la otra se cancela y sale el fallo original; los limites por
     * intento, el plazo global y los reintentos de refresh cubren a las dos porque envuelven esta funcion.
     *
     * M-1: aqui SOLO se lee ([AccessRequestSessionGate.decide] no escribe nada). Un intento cortado o repetido no puede
     * dejar una limpieza hecha que el siguiente intento lea como «ya no hay solicitud» (una denegacion convertida en entrega).
     */
    private suspend fun readGate(userId: CanonicalUserId, context: OposiBotsAuthContext, grant: AccessRequestSessionGrant): GateRead = coroutineScope {
        val accessRequest = async { accessRequestSessionGate.decide(userId.value, grant) }
        val identity = findProfile(userId, context)
        GateRead(identity, accessRequest.await())
    }

    private suspend fun retryingGateRead(
        userId: CanonicalUserId,
        context: OposiBotsAuthContext,
        grant: AccessRequestSessionGrant,
        retryDelaysMillis: List<Long>,
    ): GateRead {
        var attempt = 0
        while (true) {
            try {
                // Limite POR INTENTO: un intento lento cuenta como fallo transitorio y deja sitio al reintento.
                return withTimeoutOrNull(REFRESH_GATE_ATTEMPT_TIMEOUT_MS) { readGate(userId, context, grant) }
                    ?: throw OposiBotsAuthProblem.unavailable()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (denied: BackendDomainException) {
                throw denied
            } catch (transient: Exception) {
                val wait = retryDelaysMillis.getOrNull(attempt) ?: throw transient
                attempt++
                GatewayLogger.info("account gate read failed (attempt {}), retrying in {} ms: {}", attempt, wait, transient.toString().redactSensitiveText())
                delay(wait)
            }
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
            // MASTER2-OBS-004: sin el `Throwable` como ultimo argumento (su traza y mensaje saltaban la redaccion).
            GatewayLogger.warn("supabaseRequest path={} failed: {}", path.substringBefore('?'), unexpected.redactedMessage("upstream request failed"))
            throw OposiBotsAuthProblem.unavailable()
        }
    }

    private suspend fun upstreamRequest(
        method: HttpMethod,
        absoluteUrl: String,
        form: FormDataContent,
        category: String,
        context: OposiBotsAuthContext,
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
        } catch (transport: Exception) {
            // MASTER6-OB-002: red, TLS, DNS o timeout hacia el proveedor: categoria y clase (sin mensaje: puede llevar la URL).
            GatewayLogger.warn(
                "upstream request failed category={}_transport error={} requestId={}",
                category,
                transport::class.simpleName,
                context.requestId,
            )
            throw OposiBotsAuthProblem.unavailable()
        }
    }

    private enum class AuthPurpose { GENERIC, LOGIN, EXCHANGE, REGISTER, PASSWORD_RESET }

    // ----- FA39: binding of the email-link code (see EmailCodeBinding.kt) -----

    /** Before GoTrue sends the email: stores the client's challenge for that email. Fail closed: no binding stored, no email sent. */
    private suspend fun bindChallenge(purpose: String, email: String, challenge: String?) {
        val binding = codeBinding ?: return
        if (configuration.pkceMode == PkceMode.OFF) return
        if (challenge == null) {
            if (configuration.pkceMode == PkceMode.REQUIRED) throw OposiBotsAuthProblem.validation(OposiBotsValidationIssue.INVALID_CODE_CHALLENGE)
            return
        }
        binding.register(purpose, Pkce.emailRef(configuration.rateLimitHmacSecret, email), challenge)
    }

    /** Before GoTrue verifies the token: the pseudonym of the email the spent binding was requested for, or `null` when binding is not in force for this call. */
    private suspend fun consumeBinding(purpose: String, email: String?, verifier: String?, invalid: () -> Exception): String? {
        val binding = codeBinding ?: return null
        if (configuration.pkceMode == PkceMode.OFF) return null
        if (verifier == null) {
            if (configuration.pkceMode == PkceMode.REQUIRED) throw OposiBotsAuthProblem.validation(OposiBotsValidationIssue.INVALID_CODE_VERIFIER)
            return null
        }
        val emailRef = email?.takeIf(String::isNotBlank)?.let { Pkce.emailRef(configuration.rateLimitHmacSecret, it) }
        return binding.consume(purpose, emailRef, Pkce.challengeOf(verifier)) ?: throw invalid()
    }

    private fun String?.matchesBinding(emailRef: String): Boolean =
        this != null && java.security.MessageDigest.isEqual(Pkce.emailRef(configuration.rateLimitHmacSecret, this).toByteArray(), emailRef.toByteArray())

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
        GatewayLogger.warn("mapCredentialError status={} error_code={} msg={}", response.status.value, error.error_code, error.msg?.redactSensitiveText())
        return when {
            response.status == HttpStatusCode.TooManyRequests -> OposiBotsAuthProblem.unavailable(60)
            response.status.value >= 500 -> OposiBotsAuthProblem.unavailable()
            conflictAllowed && ("already" in fingerprint || response.status == HttpStatusCode.Conflict) ->
                OposiBotsAuthProblem.conflict()
            purpose == AuthPurpose.LOGIN && error.normalizedCode == "invalid_credentials" ->
                CanonicalErrorCode.INVALID_CREDENTIALS.exception()
            purpose == AuthPurpose.LOGIN && error.normalizedCode == "email_not_confirmed" ->
                CanonicalErrorCode.EMAIL_UNVERIFIED.exception()
            purpose == AuthPurpose.REGISTER || purpose == AuthPurpose.PASSWORD_RESET ->
                mapPasswordSetError(response.status, error, purpose)
            purpose == AuthPurpose.EXCHANGE -> CanonicalErrorCode.AUTH_CODE_INVALID.exception()
            else -> OposiBotsAuthProblem.authentication()
        }
    }

    /**
     * MASTER3-AUTH-002 / MASTER4-API-003: register (D1-01) y el `PUT /auth/v1/user` de reset (D1-10) envian una
     * contraseña nueva. Solo `weak_password`/`same_password` son rechazos DE LA CONTRASEÑA; `validation_failed` es el
     * codigo generico de GoTrue (email mal formado, contraseña > 72 bytes, campo ausente...) y se clasifica por el
     * campo que nombra su `msg`, o queda como VALIDATION sin campo: antes se etiquetaba siempre INVALID_PASSWORD.
     * Un 400/422 de configuracion del servicio (registro/proveedor deshabilitado, email no autorizado) es 503: no es culpa
     * del cliente. El resto: 400/422 -> VALIDATION generico; 401/403 -> 401 SOLO en reset (token de recuperacion
     * invalido, declarado en D1-10); cualquier otro 4xx -> 503. Register nunca devuelve 401 (D1-01 no lo declara).
     */
    private fun mapPasswordSetError(status: HttpStatusCode, error: SupabaseError, purpose: AuthPurpose): Exception {
        val clientRejected = status.value == 400 || status.value == 422
        val code = error.normalizedCode
        return when {
            clientRejected && code in REJECTED_PASSWORD_CODES ->
                OposiBotsAuthProblem.validation(OposiBotsValidationIssue.INVALID_PASSWORD)
            clientRejected && code == "email_address_invalid" ->
                OposiBotsAuthProblem.validation(OposiBotsValidationIssue.INVALID_IDENTIFIER)
            clientRejected && code == "captcha_failed" ->
                OposiBotsAuthProblem.validation(OposiBotsValidationIssue.INVALID_CAPTCHA)
            clientRejected && code in SERVICE_CONFIGURATION_CODES -> OposiBotsAuthProblem.unavailable()
            clientRejected && code == "validation_failed" -> {
                val message = error.msg.orEmpty().lowercase()
                when {
                    "password" in message -> OposiBotsAuthProblem.validation(OposiBotsValidationIssue.INVALID_PASSWORD)
                    "email" in message -> OposiBotsAuthProblem.validation(OposiBotsValidationIssue.INVALID_IDENTIFIER)
                    else -> OposiBotsAuthProblem.validation()
                }
            }
            clientRejected -> OposiBotsAuthProblem.validation()
            purpose == AuthPurpose.PASSWORD_RESET &&
                (status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden) ->
                OposiBotsAuthProblem.authentication()
            else -> OposiBotsAuthProblem.unavailable()
        }
    }

    private fun credentialBody(identifier: String, password: String, captchaToken: String?): JsonObject =
        buildJsonObject {
            put("email", identifier.canonicalForUpstream())
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

/**
 * El correo que se envia a GoTrue: SIEMPRE la forma canonica unica ([canonicalAuthEmail]), la misma que usan los limites y
 * la guarda de correo (antes resend y recover mandaban el correo solo recortado). Las rutas ya validaron la forma; si un
 * llamador directo pasa algo sin forma canonica, se envia recortado y GoTrue decide (nunca se inventa un correo).
 */
private fun String.canonicalForUpstream(): String = canonicalAuthEmail(this)?.takeIf(String::isNotEmpty) ?: trim()

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

/**
 * MASTER3-AUTH-003: huella del replay guard OAuth. Se hashea SOLO la entrada firmada (`header.payload`), nunca el token
 * crudo: el segmento de firma es maleable (bits sobrantes del ultimo caracter base64url que los decodificadores no
 * estrictos ignoran; en ECDSA ademas `(r, n-s)`), asi que el mismo id_token con otra firma "equivalente" daba otro hash
 * y pasaba la unicidad `(provider, credential_hash)`. `header.payload` es exactamente lo que la firma cubre: cualquier
 * cambio ahi invalida la firma en GoTrue. Un token sin 3 segmentos no vacios se rechaza (401).
 */
internal fun String.oauthCredentialHash(): String {
    val parts = split('.')
    if (parts.size != 3 || parts.any(String::isEmpty)) throw OposiBotsAuthProblem.authentication()
    return "${parts[0]}.${parts[1]}".sha256()
}

private fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

private val DUPLICATE_SIGNUP_CODES = setOf("user_already_exists", "email_exists")

/**
 * MASTER3-AUTH-002: codigos con los que GoTrue rechaza la contraseña nueva (register/reset). MASTER4-API-003: sin
 * `validation_failed` (generico de GoTrue: tambien lo usa para un email mal formado).
 */
private val REJECTED_PASSWORD_CODES = setOf("weak_password", "same_password")

/** MASTER4-API-003: 400/422 de GoTrue por configuracion del servicio (no del cliente): 503 en register/reset. */
private val SERVICE_CONFIGURATION_CODES = setOf(
    "signup_disabled",
    "email_provider_disabled",
    "provider_disabled",
    "email_address_not_authorized",
)

/**
 * MASTER3-AUTH-001: `error_code` con el que GoTrue dice que la sesion del JWT ya no existe (logout previo). `bad_jwt`
 * NO entra: tambien cubre firma invalida o token caducado, que no prueban nada sobre las demas sesiones.
 */
private val SESSION_GONE_CODES = setOf("session_not_found")
