package com.oposicontrol.backend

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.contentType
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import io.ktor.util.AttributeKey
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.slf4j.LoggerFactory

internal fun Route.oposiBotsAuthRoutes(runtime: OposiBotsAuthRuntime) {
    route("/v1/auth") {
        post("/register") {
            call.authEndpoint(runtime, OposiBotsAuthOperation.REGISTER) {
                val request = receiveAuthJson<OposiBotsRegisterRequest>(runtime.json)
                request.validate(runtime.configuration)
                // MASTER-SEC-003: el tope por email es SILENCIOSO (misma 202, sin llamar a GoTrue): un tercero que nombra
                // el email de la victima no puede bloquearla con 429, solo deja de disparar correos.
                // B1 (2026-10-13): y si el email tiene una solicitud de acceso de administrador no aprobada, la MISMA 202 sin
                // llamar a GoTrue (AccessRequestMailGuard.kt): esa cuenta no puede confirmarse sola por esta via.
                val email = request.resolvedIdentifier().normalizedIdentifier()
                if (
                    !withinSilentSubjectLimit(runtime, email) ||
                    runtime.accessRequestHoldsMail(OposiBotsAuthOperation.REGISTER, email)
                ) {
                    respond(HttpStatusCode.Accepted, OposiBotsRegisterResponse())
                    return@authEndpoint
                }
                respond(HttpStatusCode.Accepted, runtime.gateway.register(request, authContext(runtime.configuration)))
            }
        }
        post("/email/resend") {
            call.authEndpoint(runtime, OposiBotsAuthOperation.RESEND) {
                val request = receiveAuthJson<OposiBotsEmailResendRequest>(runtime.json)
                request.validate(runtime.configuration)
                // MASTER-SEC-003: tope por email silencioso (misma 204, sin reenviar).
                // B1 (2026-10-13): solicitud de acceso no aprobada -> misma 204, sin reenviar (AccessRequestMailGuard.kt).
                val email = request.email.normalizedIdentifier()
                if (
                    withinSilentSubjectLimit(runtime, email) &&
                    !runtime.accessRequestHoldsMail(OposiBotsAuthOperation.RESEND, email)
                ) {
                    runtime.gateway.resend(request, authContext(runtime.configuration))
                }
                respond(HttpStatusCode.NoContent)
            }
        }
        post("/email/exchange") {
            call.authEndpoint(runtime, OposiBotsAuthOperation.EMAIL_EXCHANGE) {
                val request = receiveAuthJson<OposiBotsEmailExchangeRequest>(runtime.json)
                request.code.requireCredential()
                validateCodeVerifier(request.codeVerifier, request.email, runtime.configuration)
                requireSubjectLimit(runtime, request.code)
                respond(runtime.gateway.exchangeEmail(request, authContext(runtime.configuration)))
            }
        }
        post("/login") {
            call.authEndpoint(runtime, OposiBotsAuthOperation.LOGIN) {
                val request = receiveAuthJson<OposiBotsLoginRequest>(runtime.json)
                request.validate()
                // MASTER-SEC-003: la clave por sujeto es (email, IP de cliente). Un atacante que agota el cupo de un
                // email desde SU IP no bloquea al titular desde otra IP; el cupo por IP sigue acotando el total.
                val email = request.resolvedIdentifier().normalizedIdentifier()
                requireSubjectLimit(runtime, loginSubjectKey(email, trustedClientIp(runtime.configuration)))
                // MASTER2-AUTH-002: y un tope alto POR CUENTA (solo email): rotar IPs ya no da intentos ilimitados.
                requireSubjectLimit(runtime, email, OposiBotsAuthOperation.LOGIN_ACCOUNT)
                respond(runtime.gateway.login(request, authContext(runtime.configuration)))
            }
        }
        post("/refresh") {
            call.authEndpoint(runtime, OposiBotsAuthOperation.REFRESH) {
                val request = receiveAuthJson<OposiBotsRefreshRequest>(runtime.json)
                request.refreshToken.requireCredential()
                requireSubjectLimit(runtime, request.refreshToken)
                respond(runtime.gateway.refresh(request, authContext(runtime.configuration)))
            }
        }
        post("/logout") {
            call.authEndpoint(runtime, OposiBotsAuthOperation.LOGOUT) {
                val token = requiredBearerToken()
                requireSubjectLimit(runtime, token)
                runtime.gateway.logout(token, authContext(runtime.configuration))
                respond(HttpStatusCode.NoContent)
            }
        }
        // DEPRECADA (EP-124): traduce al servicio canonico de perfil (perfiles_usuario); nunca lee public.usuario.
        get("/me") {
            call.authEndpoint(runtime, OposiBotsAuthOperation.ME) {
                val token = requiredBearerToken()
                val canonical = runtime.canonical
                requireSubjectLimit(runtime, token)
                val identity = canonical.resolver.activeIdentity(this)
                respond(canonical.legacy.me(identity, token, authContext(runtime.configuration)))
            }
        }.legacyRoute(LegacyEndpoints.EP_124)
        // DEPRECADA (EP-218): traduce a POST /api/v1/app/me/onboarding; nunca escribe public.usuario.
        post("/register/profile") {
            call.authEndpoint(runtime, OposiBotsAuthOperation.PROFILE) {
                val token = requiredBearerToken()
                val request = receiveAuthJson<OposiBotsProfileRequest>(runtime.json)
                request.validate()
                val canonical = runtime.canonical
                requireSubjectLimit(runtime, token)
                val identity = canonical.resolver.activeIdentity(this)
                canonical.legacy.saveProfile(identity, token, request, authContext(runtime.configuration))
                respond(HttpStatusCode.NoContent)
            }
        }.legacyRoute(LegacyEndpoints.EP_218)
        post("/recover") {
            call.authEndpoint(runtime, OposiBotsAuthOperation.RECOVER) {
                val request = receiveAuthJson<OposiBotsRecoverRequest>(runtime.json)
                request.validate(runtime.configuration)
                // MASTER-SEC-003: tope por email silencioso (misma 204, sin enviar otro correo de recuperacion).
                // B1 (2026-10-13): solicitud de acceso no aprobada -> misma 204, sin correo de recuperacion (el enlace de
                // recuperacion tambien confirmaria la cuenta; AccessRequestMailGuard.kt).
                val email = request.resolvedIdentifier().normalizedIdentifier()
                if (
                    withinSilentSubjectLimit(runtime, email) &&
                    !runtime.accessRequestHoldsMail(OposiBotsAuthOperation.RECOVER, email)
                ) {
                    runtime.gateway.recover(request, authContext(runtime.configuration))
                }
                respond(HttpStatusCode.NoContent)
            }
        }
        post("/reset-password") {
            call.authEndpoint(runtime, OposiBotsAuthOperation.RESET) {
                val request = receiveAuthJson<OposiBotsResetPasswordRequest>(runtime.json)
                request.validate(runtime.configuration)
                requireSubjectLimit(runtime, request.resetToken)
                runtime.gateway.reset(request, authContext(runtime.configuration))
                respond(HttpStatusCode.NoContent)
            }
        }
        post("/oauth/google") {
            call.authEndpoint(runtime, OposiBotsAuthOperation.GOOGLE) {
                val request = receiveAuthJson<OposiBotsGoogleRequest>(runtime.json)
                request.idToken.requireCredential()
                validateOAuthNonce(request.nonce, runtime.configuration)
                requireSubjectLimit(runtime, request.idToken)
                respond(runtime.gateway.google(request, authContext(runtime.configuration)))
            }
        }
        post("/oauth/apple") {
            call.authEndpoint(runtime, OposiBotsAuthOperation.APPLE) {
                val request = receiveAuthJson<OposiBotsAppleRequest>(runtime.json)
                request.identityToken.requireCredential()
                request.authorizationCode.requireCredential(maxLength = 4_096)
                validateOAuthNonce(request.nonce, runtime.configuration)
                requireSubjectLimit(runtime, request.identityToken)
                respond(runtime.gateway.apple(request, authContext(runtime.configuration)))
            }
        }
    }
}

internal suspend fun ApplicationCall.authEndpoint(
    runtime: OposiBotsAuthRuntime,
    operation: OposiBotsAuthOperation,
    block: suspend ApplicationCall.() -> Unit,
) {
    // SLICE 0: el mismo id que Observability (misma regla de validacion), para
    // que la cabecera X-Request-Id y el `requestId` del sobre de error coincidan.
    val requestId = ensureRequestId()
    attributes.put(AuthRequestIdKey, requestId)
    // MASTER-SEC-002: sesiones/tokens y datos de usuario nunca se cachean (tambien en errores).
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    response.headers.append("Pragma", "no-cache")
    try {
        val decision = runtime.rateLimiter.checkIp(operation, trustedClientIp(runtime.configuration))
        if (!decision.allowed) throw OposiBotsAuthProblem.unavailable(decision.retryAfterSeconds)
        block()
        logAuthResult(operation, requestId, response.status()?.value ?: HttpStatusCode.OK.value)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (problem: OposiBotsAuthProblem) {
        problem.retryAfterSeconds?.let { response.headers.append(HttpHeaders.RetryAfter, it.toString()) }
        logAuthFailure(operation, requestId, problem.status, problem.canonicalCode().name, failure = null)
        respondAuthProblem(problem, requestId)
    } catch (domain: BackendDomainException) {
        // SLICE 1: errores canonicos (INVALID_CREDENTIALS, ACCOUNT_BLOCKED, USERNAME_TAKEN...) con `code` propio y el
        // `error` legacy de OB (AuthErrorWire) derivado del estado HTTP.
        domain.retryAfterSeconds?.let { response.headers.append(HttpHeaders.RetryAfter, it.toString()) }
        logAuthFailure(operation, requestId, domain.status, domain.errorCode, failure = domain)
        respondAuthDomain(domain, requestId)
    } catch (unexpected: Exception) {
        val problem = OposiBotsAuthProblem.unavailable()
        // Logueado (S1D1/DAY_02, 2026-08-13): antes esta causa se descartaba
        // en silencio (`catch (_: Exception)`) -- sin esto, cualquier fallo
        // real (Supabase, red, bug) se veia igual que un rate-limit, sin
        // forma de diagnosticarlo. No se expone al cliente, solo al log.
        AuthLogger.warn("auth operation={} requestId={} unexpected={}", operation.name, requestId, unexpected.toString().redactSensitiveText())
        // MASTER6-OB-001: el 503 mas comun en la practica tambien emite su evento estrategico (SUPABASE_REQUEST_FAILED)
        // y la linea de causa, como las ramas hermanas; `logAuthFailure` ya escribe la linea `auth operation=...`.
        logAuthFailure(operation, requestId, problem.status, problem.canonicalCode().name, failure = unexpected)
        respondAuthProblem(problem, requestId)
    }
}

private suspend fun ApplicationCall.respondAuthDomain(domain: BackendDomainException, requestId: String) {
    if (domain.status == HttpStatusCode.Unauthorized && response.headers[HttpHeaders.WWWAuthenticate] == null) {
        response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
    }
    respond(
        domain.status,
        ErrorDto(
            error = legacyAuthErrorFor(domain.status).name,
            message = domain.message,
            code = domain.errorCode,
            requestId = requestId,
            details = domain.details,
        ),
    )
}

private suspend fun ApplicationCall.respondAuthProblem(problem: OposiBotsAuthProblem, requestId: String) {
    if (problem.status == HttpStatusCode.Unauthorized && response.headers[HttpHeaders.WWWAuthenticate] == null) {
        response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
    }
    respond(problem.status, problem.response(requestId))
}

private suspend fun ApplicationCall.requireSubjectLimit(
    runtime: OposiBotsAuthRuntime,
    subject: String,
    explicitOperation: OposiBotsAuthOperation? = null,
) {
    val operation = explicitOperation ?: when (request.path().substringAfterLast('/')) {
        "register" -> OposiBotsAuthOperation.REGISTER
        "resend" -> OposiBotsAuthOperation.RESEND
        "exchange" -> OposiBotsAuthOperation.EMAIL_EXCHANGE
        "login" -> OposiBotsAuthOperation.LOGIN
        "refresh" -> OposiBotsAuthOperation.REFRESH
        "logout" -> OposiBotsAuthOperation.LOGOUT
        "me" -> OposiBotsAuthOperation.ME
        "profile" -> OposiBotsAuthOperation.PROFILE
        "recover" -> OposiBotsAuthOperation.RECOVER
        "reset-password" -> OposiBotsAuthOperation.RESET
        "google" -> OposiBotsAuthOperation.GOOGLE
        "apple" -> OposiBotsAuthOperation.APPLE
        else -> throw OposiBotsAuthProblem.unavailable()
    }
    val decision = runtime.rateLimiter.checkSubject(operation, subject)
    if (!decision.allowed) throw OposiBotsAuthProblem.unavailable(decision.retryAfterSeconds)
}

/**
 * MASTER-SEC-003: tope por email para operaciones que ENVIAN correo (register, resend, recover). Devuelve `false` si se
 * supero; la ruta contesta entonces la MISMA respuesta de exito sin llamar a GoTrue. Asi el tope sigue impidiendo el
 * bombardeo de correos, pero un tercero que conoce el email ya no puede provocar un 429 al titular (y la respuesta no
 * revela nada). El limite por IP de [authEndpoint] sigue devolviendo 429.
 */
private suspend fun ApplicationCall.withinSilentSubjectLimit(
    runtime: OposiBotsAuthRuntime,
    subject: String,
): Boolean {
    val operation = when (request.path().substringAfterLast('/')) {
        "register" -> OposiBotsAuthOperation.REGISTER
        "resend" -> OposiBotsAuthOperation.RESEND
        "recover" -> OposiBotsAuthOperation.RECOVER
        else -> throw OposiBotsAuthProblem.unavailable()
    }
    val allowed = runtime.rateLimiter.checkSubject(operation, subject).allowed
    if (!allowed) AuthLogger.info("auth operation={} subject limit reached: silent success", operation.name)
    return allowed
}

/**
 * MASTER-SEC-003: clave de sujeto del login = email normalizado + IP de cliente de confianza.
 * MASTER2-AUTH-002: la IP entra por su cubo de limite ([rateLimitClientBucket]: IPv6 por /64).
 */
internal fun loginSubjectKey(normalizedEmail: String, clientIp: String): String =
    "$normalizedEmail|${rateLimitClientBucket(clientIp)}"

private suspend inline fun <reified T : Any> ApplicationCall.receiveAuthJson(json: Json): T {
    if (!request.contentType().match(ContentType.Application.Json)) {
        throw OposiBotsAuthProblem.validation()
    }
    val declaredLength = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declaredLength != null && declaredLength > AUTH_BODY_MAX_BYTES) {
        throw OposiBotsAuthProblem.validation()
    }
    val bytes = receiveChannel().readRemaining(AUTH_BODY_MAX_BYTES + 1L).readByteArray()
    if (bytes.size > AUTH_BODY_MAX_BYTES) throw OposiBotsAuthProblem.validation()
    return try {
        json.decodeFromString(String(bytes, StandardCharsets.UTF_8))
    } catch (_: SerializationException) {
        throw OposiBotsAuthProblem.validation()
    } catch (_: IllegalArgumentException) {
        throw OposiBotsAuthProblem.validation()
    }
}

/**
 * Costuras para `AdminAccessRequests.kt` (solicitud publica de acceso de administrador, mismo plano `/v1/auth`): la MISMA
 * lectura acotada del cuerpo ([receiveAuthJson], 16 KB) y la MISMA validacion de email y contrasena que el registro
 * (correo canonico unico y contrasena nueva 12..72 ASCII imprimible).
 */
internal suspend fun ApplicationCall.receiveAdminAccessRequestBody(json: Json): AdminAccessRequestBody = receiveAuthJson(json)

/** La misma lectura acotada para `POST /v1/auth/admin-access-requests/verify` (`{email, code}`). */
internal suspend fun ApplicationCall.receiveAdminAccessVerifyBody(json: Json): AdminAccessVerifyBody = receiveAuthJson(json)

/** La misma lectura acotada para `POST /v1/auth/admin-access-requests/resend-code` (`{email}`). */
internal suspend fun ApplicationCall.receiveAdminAccessResendCodeBody(json: Json): AdminAccessResendCodeBody = receiveAuthJson(json)

/** El limite por sujeto NO silencioso del plano (429 + `Retry-After` al superarlo), con la operacion explicita. */
internal suspend fun ApplicationCall.requireAuthSubjectLimit(runtime: OposiBotsAuthRuntime, operation: OposiBotsAuthOperation, subject: String) =
    requireSubjectLimit(runtime, subject, operation)

internal fun validateAuthEmailAndPassword(email: String, password: String) =
    validateIdentifierAndPassword(email, password, newPassword = true)

private fun ApplicationCall.requiredBearerToken(): String {
    val header = request.headers[HttpHeaders.Authorization].orEmpty()
    if (!header.startsWith("Bearer ", ignoreCase = true)) throw OposiBotsAuthProblem.authentication()
    return header.substringAfter(' ').trim()
        .takeIf { it.length in 40..AUTH_CREDENTIAL_MAX_LENGTH }
        ?: throw OposiBotsAuthProblem.authentication()
}

private fun ApplicationCall.authContext(configuration: OposiBotsAuthConfiguration) = OposiBotsAuthContext(
    requestId = attributes[AuthRequestIdKey],
    clientIp = trustedClientIp(configuration),
)

internal fun ApplicationCall.trustedClientIp(configuration: OposiBotsAuthConfiguration): String =
    resolveTrustedClientIp(
        configuration = configuration,
        // MASTER-SEC-001: remoteAddress (literal IP), nunca remoteHost (DNS inverso, controlable por el peer).
        peerHost = request.origin.remoteAddress,
        forwardedFor = request.headers["X-Forwarded-For"],
    )

internal fun resolveTrustedClientIp(
    configuration: OposiBotsAuthConfiguration,
    peerHost: String,
    forwardedFor: String?,
): String {
    val peer = normalizePeerAddress(peerHost)
    if (!configuration.trustProxyHeaders) return peer
    if (configuration.trustedProxyHosts.none { normalizePeerAddress(it) == peer }) return peer
    return forwardedFor
        ?.substringAfterLast(',')
        ?.trim()
        ?.takeIf(::isIpLiteral)
        ?: peer
}

private fun OposiBotsRegisterRequest.validate(configuration: OposiBotsAuthConfiguration) {
    validateIdentifierAndPassword(resolvedIdentifier(), password, newPassword = true)
    validateCaptcha(captchaToken, configuration)
    validateCodeChallenge(codeChallenge, configuration)
}

private fun OposiBotsEmailResendRequest.validate(configuration: OposiBotsAuthConfiguration) {
    requireCanonicalAuthEmail(email)
    validateCaptcha(captchaToken, configuration)
    validateCodeChallenge(codeChallenge, configuration)
}

private fun OposiBotsLoginRequest.validate() {
    validateIdentifierAndPassword(resolvedIdentifier(), password, newPassword = false)
}

private fun OposiBotsRecoverRequest.validate(configuration: OposiBotsAuthConfiguration) {
    requireCanonicalAuthEmail(resolvedIdentifier())
    validateCaptcha(captchaToken, configuration)
    validateCodeChallenge(codeChallenge, configuration)
}

private fun OposiBotsResetPasswordRequest.validate(configuration: OposiBotsAuthConfiguration) {
    resetToken.requireCredential()
    validatePassword(newPassword)
    validateCodeVerifier(codeVerifier, email, configuration)
}

/** SEC-V1-11: with AUTH_OAUTH_NONCE_REQUIRED the raw OAuth nonce is mandatory (400 before any upstream call); otherwise legacy. */
private fun validateOAuthNonce(nonce: String?, configuration: OposiBotsAuthConfiguration) {
    if (!configuration.oauthNonceRequired) return
    if (nonce == null || !OAUTH_NONCE.matches(nonce)) throw OposiBotsAuthProblem.validation(OposiBotsValidationIssue.INVALID_CREDENTIAL)
}

/** FA39: shape of the S256 challenge sent with the email request; required only in `required` mode, ignored in `off`. */
private fun validateCodeChallenge(challenge: String?, configuration: OposiBotsAuthConfiguration) {
    if (configuration.pkceMode == PkceMode.OFF) return
    if (challenge == null) {
        if (configuration.pkceMode == PkceMode.REQUIRED) throw OposiBotsAuthProblem.validation(OposiBotsValidationIssue.INVALID_CODE_CHALLENGE)
        return
    }
    if (!Pkce.CHALLENGE.matches(challenge)) throw OposiBotsAuthProblem.validation(OposiBotsValidationIssue.INVALID_CODE_CHALLENGE)
}

/** FA39: shape of the verifier (and of the optional email hint) sent with the code. Shape errors never depend on the code. */
private fun validateCodeVerifier(verifier: String?, email: String?, configuration: OposiBotsAuthConfiguration) {
    if (configuration.pkceMode == PkceMode.OFF) return
    if (verifier == null) {
        if (configuration.pkceMode == PkceMode.REQUIRED) throw OposiBotsAuthProblem.validation(OposiBotsValidationIssue.INVALID_CODE_VERIFIER)
    } else if (!Pkce.VERIFIER.matches(verifier)) {
        throw OposiBotsAuthProblem.validation(OposiBotsValidationIssue.INVALID_CODE_VERIFIER)
    }
    if (email != null && authEmailIssue(email) != null) throw OposiBotsAuthProblem.validation(OposiBotsValidationIssue.INVALID_IDENTIFIER)
}

private fun OposiBotsProfileRequest.validate() {
    val issues = buildSet {
        if (firstName.isBlank() || firstName.length > 100) add(OposiBotsValidationIssue.BLANK_FIRST_NAME)
        if (lastName.isBlank() || lastName.length > 100) add(OposiBotsValidationIssue.BLANK_LAST_NAME)
        // V1: con `especialidadId` el nombre no hace falta (y si llegan los dos, gana el id). Un id mal formado es 400.
        if (especialidadId != null) {
            if (!especialidadId.isUuid()) add(OposiBotsValidationIssue.SPECIALTY_NOT_SELECTED)
        } else if (specialty.isBlank() || specialty.length > 100) {
            add(OposiBotsValidationIssue.SPECIALTY_NOT_SELECTED)
        }
        if (username != null && (username.length !in 3..30 || !username.matches(Regex("[A-Za-z0-9._-]+")))) {
            add(OposiBotsValidationIssue.INVALID_USERNAME)
        }
    }
    if (issues.isNotEmpty()) {
        throw OposiBotsAuthProblem(HttpStatusCode.BadRequest, OposiBotsErrorCode.Validation, issues)
    }
}

/**
 * [newPassword] = la peticion FIJA una contrasena (registro, solicitud de acceso): se le aplica la regla de contrasena nueva
 * ([isAcceptableNewPassword]: 12..72 caracteres ASCII imprimibles). El login solo comprueba una que ya existe y conserva su
 * regla de siempre (12..128 caracteres). El correo, en los dos casos, con la regla canonica unica ([authEmailIssue]).
 */
private fun validateIdentifierAndPassword(identifier: String, password: String, newPassword: Boolean) {
    val issues = buildSet {
        authEmailIssue(identifier)?.let(::add)
        if (password.isBlank()) add(OposiBotsValidationIssue.BLANK_PASSWORD)
        else if (if (newPassword) !password.isAcceptableNewPassword() else password.length !in 12..128) {
            add(OposiBotsValidationIssue.INVALID_PASSWORD)
        }
    }
    if (issues.isNotEmpty()) {
        throw OposiBotsAuthProblem(HttpStatusCode.BadRequest, OposiBotsErrorCode.Validation, issues)
    }
}

private fun validatePassword(password: String) {
    if (password.isBlank()) throw OposiBotsAuthProblem.validation(OposiBotsValidationIssue.BLANK_PASSWORD)
    if (!password.isAcceptableNewPassword()) {
        throw OposiBotsAuthProblem.validation(OposiBotsValidationIssue.INVALID_PASSWORD)
    }
}

private fun validateCaptcha(token: String?, configuration: OposiBotsAuthConfiguration) {
    val valid = token != null &&
        token.length in 32..4_096 &&
        token.none(Char::isWhitespace)
    if ((configuration.captchaRequired && !valid) || (token != null && !valid)) {
        throw OposiBotsAuthProblem.validation(OposiBotsValidationIssue.INVALID_CAPTCHA)
    }
}


/** Sin zone id (`%eth0`), sin corchetes, en minusculas y acotado: forma canonica para comparar literales IP. */
internal fun normalizePeerAddress(raw: String): String =
    raw.trim().substringBefore('%').trim('[', ']').lowercase().take(128)

internal fun isIpLiteral(value: String): Boolean {
    if (value.length !in 2..45 || value.any(Char::isWhitespace)) return false
    val ipv4Parts = value.split('.')
    if (ipv4Parts.size == 4 && ipv4Parts.all { it.isNotEmpty() && it.length <= 3 && it.toIntOrNull() in 0..255 }) {
        return true
    }
    return ':' in value && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }
}

private fun logAuthResult(operation: OposiBotsAuthOperation, requestId: String, status: Int, errorCode: String? = null) {
    AuthLogger.info("auth operation={} requestId={} status={} code={}", operation.name, requestId, status, errorCode ?: "-")
}

/**
 * MASTER5-OBS-001: el plano `/v1/auth` responde sus errores AQUI (no llegan a StatusPages), asi que aqui se registra lo
 * mismo que StatusPages registra en el resto del backend:
 * - 5xx: evento estrategico (UNCAUGHT_EXCEPTION si es INTERNAL, si no SUPABASE_REQUEST_FAILED) y un WARN con el codigo
 *   y la causa REDACTADA ([redactedMessage]); antes solo quedaba `status=503` y la causa se perdia.
 * - 401: AUTH_LOGIN_FAILED; 403: AUTHORIZATION_DENIED (MASTER5-OBS-004), con el codigo como `error_type` y el usuario
 *   verificado cuando la ruta llego a resolverlo. Nunca el mensaje ni credenciales.
 * La linea `auth operation=... status=... code=...` se sigue emitiendo siempre.
 */
private fun ApplicationCall.logAuthFailure(
    operation: OposiBotsAuthOperation,
    requestId: String,
    status: HttpStatusCode,
    errorCode: String,
    failure: Throwable?,
) {
    logAuthResult(operation, requestId, status.value, errorCode)
    val event = when {
        status.value >= 500 ->
            if (errorCode == CanonicalErrorCode.INTERNAL.name) StrategicEvent.UNCAUGHT_EXCEPTION else StrategicEvent.SUPABASE_REQUEST_FAILED
        status == HttpStatusCode.Unauthorized -> StrategicEvent.AUTH_LOGIN_FAILED
        status == HttpStatusCode.Forbidden -> StrategicEvent.AUTHORIZATION_DENIED
        else -> return
    }
    logStrategicEvent(
        event,
        requestId = requestId,
        operation = operation.name,
        result = status.value.toString(),
        durationMs = elapsedMsOrNull(),
        errorType = errorCode,
        userId = verifiedCanonicalUserIdOrNull(),
    )
    if (status.value >= 500) {
        // MASTER6-OB-002: con la clase de la causa (antes solo su mensaje, y "-" cuando no la habia: la causa de un
        // OposiBotsAuthProblem del gateway ya la registra el propio gateway con su categoria).
        val cause = failure?.let { it.cause ?: it }
        AuthLogger.warn(
            "auth failure operation={} requestId={} status={} code={} causeClass={}: {}",
            operation.name,
            requestId,
            status.value,
            errorCode,
            cause?.let { it::class.simpleName } ?: "-",
            cause?.redactedMessage("Auth failure") ?: "-",
        )
    }
}

private fun String.requireCredential(maxLength: Int = AUTH_CREDENTIAL_MAX_LENGTH) {
    if (isBlank() || length > maxLength) {
        throw OposiBotsAuthProblem.authentication()
    }
}

/**
 * El correo canonico ([canonicalAuthEmail]) de un identificador YA validado por la ruta: clave de los limites por sujeto y
 * de la guarda de correo (y lo mismo que el gateway envia a GoTrue).
 */
private fun String.normalizedIdentifier(): String = requireCanonicalAuthEmail(this)

private const val AUTH_BODY_MAX_BYTES = 16 * 1_024
private const val AUTH_CREDENTIAL_MAX_LENGTH = 16_384
private val AuthRequestIdKey = AttributeKey<String>("oposibots-auth-request-id")
private val AuthLogger = LoggerFactory.getLogger("OC-KTOR-AUTH")
