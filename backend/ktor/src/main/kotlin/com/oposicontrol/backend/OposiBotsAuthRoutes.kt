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
                requireSubjectLimit(runtime, request.identifier.normalizedIdentifier())
                respond(HttpStatusCode.Accepted, runtime.gateway.register(request, authContext(runtime.configuration)))
            }
        }
        post("/email/resend") {
            call.authEndpoint(runtime, OposiBotsAuthOperation.RESEND) {
                val request = receiveAuthJson<OposiBotsEmailResendRequest>(runtime.json)
                request.validate(runtime.configuration)
                requireSubjectLimit(runtime, request.email.normalizedIdentifier())
                runtime.gateway.resend(request, authContext(runtime.configuration))
                respond(HttpStatusCode.NoContent)
            }
        }
        post("/email/exchange") {
            call.authEndpoint(runtime, OposiBotsAuthOperation.EMAIL_EXCHANGE) {
                val request = receiveAuthJson<OposiBotsEmailExchangeRequest>(runtime.json)
                request.code.requireCredential()
                requireSubjectLimit(runtime, request.code)
                respond(runtime.gateway.exchangeEmail(request, authContext(runtime.configuration)))
            }
        }
        post("/login") {
            call.authEndpoint(runtime, OposiBotsAuthOperation.LOGIN) {
                val request = receiveAuthJson<OposiBotsLoginRequest>(runtime.json)
                request.validate()
                requireSubjectLimit(runtime, request.identifier.normalizedIdentifier())
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
        get("/me") {
            call.authEndpoint(runtime, OposiBotsAuthOperation.ME) {
                val token = requiredBearerToken()
                requireSubjectLimit(runtime, token)
                respond(runtime.gateway.me(token, authContext(runtime.configuration)))
            }
        }
        post("/register/profile") {
            call.authEndpoint(runtime, OposiBotsAuthOperation.PROFILE) {
                val token = requiredBearerToken()
                val request = receiveAuthJson<OposiBotsProfileRequest>(runtime.json)
                request.validate()
                requireSubjectLimit(runtime, token)
                runtime.gateway.saveProfile(token, request, authContext(runtime.configuration))
                respond(HttpStatusCode.NoContent)
            }
        }
        post("/recover") {
            call.authEndpoint(runtime, OposiBotsAuthOperation.RECOVER) {
                val request = receiveAuthJson<OposiBotsRecoverRequest>(runtime.json)
                request.validate(runtime.configuration)
                requireSubjectLimit(runtime, request.identifier.normalizedIdentifier())
                runtime.gateway.recover(request, authContext(runtime.configuration))
                respond(HttpStatusCode.NoContent)
            }
        }
        post("/reset-password") {
            call.authEndpoint(runtime, OposiBotsAuthOperation.RESET) {
                val request = receiveAuthJson<OposiBotsResetPasswordRequest>(runtime.json)
                request.validate()
                requireSubjectLimit(runtime, request.resetToken)
                runtime.gateway.reset(request, authContext(runtime.configuration))
                respond(HttpStatusCode.NoContent)
            }
        }
        post("/oauth/google") {
            call.authEndpoint(runtime, OposiBotsAuthOperation.GOOGLE) {
                val request = receiveAuthJson<OposiBotsGoogleRequest>(runtime.json)
                request.idToken.requireCredential()
                requireSubjectLimit(runtime, request.idToken)
                respond(runtime.gateway.google(request, authContext(runtime.configuration)))
            }
        }
        post("/oauth/apple") {
            call.authEndpoint(runtime, OposiBotsAuthOperation.APPLE) {
                val request = receiveAuthJson<OposiBotsAppleRequest>(runtime.json)
                request.identityToken.requireCredential()
                request.authorizationCode.requireCredential(maxLength = 4_096)
                requireSubjectLimit(runtime, request.identityToken)
                respond(runtime.gateway.apple(request, authContext(runtime.configuration)))
            }
        }
    }
}

private suspend fun ApplicationCall.authEndpoint(
    runtime: OposiBotsAuthRuntime,
    operation: OposiBotsAuthOperation,
    block: suspend ApplicationCall.() -> Unit,
) {
    // SLICE 0: el mismo id que Observability (misma regla de validacion), para
    // que la cabecera X-Request-Id y el `requestId` del sobre de error coincidan.
    val requestId = ensureRequestId()
    attributes.put(AuthRequestIdKey, requestId)
    try {
        val decision = runtime.rateLimiter.checkIp(operation, trustedClientIp(runtime.configuration))
        if (!decision.allowed) throw OposiBotsAuthProblem.unavailable(decision.retryAfterSeconds)
        block()
        logAuthResult(operation, requestId, response.status()?.value ?: HttpStatusCode.OK.value)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (problem: OposiBotsAuthProblem) {
        problem.retryAfterSeconds?.let { response.headers.append(HttpHeaders.RetryAfter, it.toString()) }
        logAuthResult(operation, requestId, problem.status.value)
        respondAuthProblem(problem, requestId)
    } catch (unexpected: Exception) {
        val problem = OposiBotsAuthProblem.unavailable()
        // Logueado (S1D1/DAY_02, 2026-08-13): antes esta causa se descartaba
        // en silencio (`catch (_: Exception)`) -- sin esto, cualquier fallo
        // real (Supabase, red, bug) se veia igual que un rate-limit, sin
        // forma de diagnosticarlo. No se expone al cliente, solo al log.
        AuthLogger.warn("auth operation={} requestId={} unexpected={}", operation.name, requestId, unexpected.toString())
        logAuthResult(operation, requestId, problem.status.value)
        respondAuthProblem(problem, requestId)
    }
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
) {
    val operation = when (request.path().substringAfterLast('/')) {
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

private fun ApplicationCall.trustedClientIp(configuration: OposiBotsAuthConfiguration): String =
    resolveTrustedClientIp(
        configuration = configuration,
        peerHost = request.origin.remoteHost,
        forwardedFor = request.headers["X-Forwarded-For"],
    )

internal fun resolveTrustedClientIp(
    configuration: OposiBotsAuthConfiguration,
    peerHost: String,
    forwardedFor: String?,
): String {
    val peer = peerHost.take(128)
    if (!configuration.trustProxyHeaders) return peer
    if (configuration.trustedProxyHosts.none { it.equals(peer, ignoreCase = true) }) return peer
    return forwardedFor
        ?.substringAfterLast(',')
        ?.trim()
        ?.takeIf(::isIpLiteral)
        ?: peer
}

private fun OposiBotsRegisterRequest.validate(configuration: OposiBotsAuthConfiguration) {
    validateIdentifierAndPassword(identifier, password)
    validateCaptcha(captchaToken, configuration)
}

private fun OposiBotsEmailResendRequest.validate(configuration: OposiBotsAuthConfiguration) {
    if (email.isBlank()) throw OposiBotsAuthProblem.validation(OposiBotsValidationIssue.BLANK_IDENTIFIER)
    if (!email.isValidEmail()) {
        throw OposiBotsAuthProblem.validation(OposiBotsValidationIssue.INVALID_IDENTIFIER)
    }
    validateCaptcha(captchaToken, configuration)
}

private fun OposiBotsLoginRequest.validate() {
    validateIdentifierAndPassword(identifier, password)
}

private fun OposiBotsRecoverRequest.validate(configuration: OposiBotsAuthConfiguration) {
    if (identifier.isBlank()) throw OposiBotsAuthProblem.validation(OposiBotsValidationIssue.BLANK_IDENTIFIER)
    if (!identifier.isValidEmail()) {
        throw OposiBotsAuthProblem.validation(OposiBotsValidationIssue.INVALID_IDENTIFIER)
    }
    validateCaptcha(captchaToken, configuration)
}

private fun OposiBotsResetPasswordRequest.validate() {
    resetToken.requireCredential()
    validatePassword(newPassword)
}

private fun OposiBotsProfileRequest.validate() {
    val issues = buildSet {
        if (firstName.isBlank() || firstName.length > 100) add(OposiBotsValidationIssue.BLANK_FIRST_NAME)
        if (lastName.isBlank() || lastName.length > 100) add(OposiBotsValidationIssue.BLANK_LAST_NAME)
        if (specialty.isBlank() || specialty.length > 100) add(OposiBotsValidationIssue.SPECIALTY_NOT_SELECTED)
        if (username != null && (username.length !in 3..30 || !username.matches(Regex("[A-Za-z0-9._-]+")))) {
            add(OposiBotsValidationIssue.INVALID_USERNAME)
        }
    }
    if (issues.isNotEmpty()) {
        throw OposiBotsAuthProblem(HttpStatusCode.BadRequest, OposiBotsErrorCode.Validation, issues)
    }
}

private fun validateIdentifierAndPassword(identifier: String, password: String) {
    val issues = buildSet {
        if (identifier.isBlank()) add(OposiBotsValidationIssue.BLANK_IDENTIFIER)
        else if (!identifier.isValidEmail()) add(OposiBotsValidationIssue.INVALID_IDENTIFIER)
        if (password.isBlank()) add(OposiBotsValidationIssue.BLANK_PASSWORD)
        else if (password.length !in 12..128) add(OposiBotsValidationIssue.INVALID_PASSWORD)
    }
    if (issues.isNotEmpty()) {
        throw OposiBotsAuthProblem(HttpStatusCode.BadRequest, OposiBotsErrorCode.Validation, issues)
    }
}

private fun validatePassword(password: String) {
    if (password.isBlank()) throw OposiBotsAuthProblem.validation(OposiBotsValidationIssue.BLANK_PASSWORD)
    if (password.length !in 12..128) {
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

private fun String.isValidEmail(): Boolean {
    val normalized = trim()
    return normalized.length in 3..320 &&
        EMAIL_REGEX.matches(normalized)
}

private fun isIpLiteral(value: String): Boolean {
    if (value.length !in 2..45 || value.any(Char::isWhitespace)) return false
    val ipv4Parts = value.split('.')
    if (ipv4Parts.size == 4 && ipv4Parts.all { it.isNotEmpty() && it.length <= 3 && it.toIntOrNull() in 0..255 }) {
        return true
    }
    return ':' in value && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }
}

private fun logAuthResult(operation: OposiBotsAuthOperation, requestId: String, status: Int) {
    AuthLogger.info("auth operation={} requestId={} status={}", operation.name, requestId, status)
}

private fun String.requireCredential(maxLength: Int = AUTH_CREDENTIAL_MAX_LENGTH) {
    if (isBlank() || length > maxLength) {
        throw OposiBotsAuthProblem.authentication()
    }
}

private fun String.normalizedIdentifier(): String = trim().lowercase()

private const val AUTH_BODY_MAX_BYTES = 16 * 1_024
private const val AUTH_CREDENTIAL_MAX_LENGTH = 16_384
private val AuthRequestIdKey = AttributeKey<String>("oposibots-auth-request-id")
private val AuthLogger = LoggerFactory.getLogger("OC-KTOR-AUTH")
private val EMAIL_REGEX = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")
