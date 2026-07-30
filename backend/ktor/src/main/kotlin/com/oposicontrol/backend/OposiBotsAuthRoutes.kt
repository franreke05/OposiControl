package com.oposicontrol.backend

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory

internal fun Route.oposiBotsAuthRoutes(
    gateway: OposiBotsAuthGateway,
    rateLimiter: OposiBotsAuthRateLimiter,
) {
    route("/v1/auth") {
        post("/login") {
            call.authEndpoint("login") {
                val request = receiveAuthBody<OposiBotsLoginRequest>()
                request.validate()
                requireAuthRateLimit(
                    rateLimiter,
                    OposiBotsAuthOperation.LOGIN,
                    request.identifier.normalizedAuthIdentifier(),
                )
                respond(
                    gateway.login(
                        request,
                        OposiBotsAuthOperationContext(requestId()),
                    ),
                )
            }
        }

        post("/register") {
            call.authEndpoint("register") {
                val request = receiveAuthBody<OposiBotsRegisterRequest>()
                request.validate()
                requireAuthRateLimit(
                    rateLimiter,
                    OposiBotsAuthOperation.REGISTER,
                    request.identifier.normalizedAuthIdentifier(),
                )
                respond(
                    HttpStatusCode.Created,
                    gateway.register(
                        request,
                        OposiBotsAuthOperationContext(requestId()),
                    ),
                )
            }
        }

        post("/register/profile") {
            call.authEndpoint("register.profile") {
                val accessToken = requiredConsumerBearerToken()
                val request = receiveAuthBody<OposiBotsRegisterAboutYouRequest>()
                request.validate()
                requireAuthRateLimit(
                    rateLimiter,
                    OposiBotsAuthOperation.REGISTER_PROFILE,
                    accessToken,
                )
                gateway.completeRegistrationProfile(
                    accessToken,
                    request,
                    OposiBotsAuthOperationContext(requestId()),
                )
                respond(HttpStatusCode.NoContent)
            }
        }

        post("/recover") {
            call.authEndpoint("recover") {
                val request = receiveAuthBody<OposiBotsRecoverAccessRequest>()
                request.validate()
                requireAuthRateLimit(
                    rateLimiter,
                    OposiBotsAuthOperation.RECOVER,
                    request.identifier.normalizedAuthIdentifier(),
                )
                try {
                    gateway.recoverAccess(
                        request,
                        OposiBotsAuthOperationContext(requestId()),
                    )
                } catch (problem: OposiBotsAuthProblem) {
                    if (problem.domainError == OposiBotsDomainErrorCode.TEMPORARILY_UNAVAILABLE) {
                        throw problem
                    }
                    // WHY: account existence and blocked state are deliberately
                    // indistinguishable on the recovery endpoint.
                }
                respond(HttpStatusCode.NoContent)
            }
        }

        post("/reset-password") {
            call.authEndpoint("reset-password") {
                val request = receiveAuthBody<OposiBotsResetPasswordRequest>()
                request.validate()
                requireAuthRateLimit(
                    rateLimiter,
                    OposiBotsAuthOperation.RESET_PASSWORD,
                    request.resetToken,
                )
                gateway.resetPassword(
                    request,
                    OposiBotsAuthOperationContext(requestId()),
                )
                respond(HttpStatusCode.NoContent)
            }
        }

        post("/oauth/google") {
            call.authEndpoint("oauth.google") {
                val request = receiveAuthBody<OposiBotsGoogleSignInRequest>()
                if (request.idToken.isBlank()) {
                    throw OposiBotsAuthProblem.temporarilyUnavailable()
                }
                requireAuthRateLimit(
                    rateLimiter,
                    OposiBotsAuthOperation.OAUTH_GOOGLE,
                    request.idToken,
                )
                respond(
                    gateway.signInWithGoogle(
                        request,
                        OposiBotsAuthOperationContext(requestId()),
                    ),
                )
            }
        }

        post("/oauth/apple") {
            call.authEndpoint("oauth.apple") {
                val request = receiveAuthBody<OposiBotsAppleSignInRequest>()
                if (request.identityToken.isBlank() || request.authorizationCode.isBlank()) {
                    throw OposiBotsAuthProblem.temporarilyUnavailable()
                }
                requireAuthRateLimit(
                    rateLimiter,
                    OposiBotsAuthOperation.OAUTH_APPLE,
                    request.identityToken,
                )
                respond(
                    gateway.signInWithApple(
                        request,
                        OposiBotsAuthOperationContext(requestId()),
                    ),
                )
            }
        }
    }
}

private fun OposiBotsLoginRequest.validate() {
    val issues = buildSet {
        if (identifier.isBlank()) add(OposiBotsValidationIssue.BLANK_IDENTIFIER)
        if (password.isBlank()) add(OposiBotsValidationIssue.BLANK_PASSWORD)
    }
    if (issues.isNotEmpty()) throw OposiBotsAuthProblem.validation(issues)
}

private fun OposiBotsRegisterRequest.validate() {
    val issues = buildSet {
        if (identifier.isBlank()) add(OposiBotsValidationIssue.BLANK_IDENTIFIER)
        if (password.isBlank()) add(OposiBotsValidationIssue.BLANK_PASSWORD)
    }
    if (issues.isNotEmpty()) throw OposiBotsAuthProblem.validation(issues)
}

private fun OposiBotsRegisterAboutYouRequest.validate() {
    val issues = buildSet {
        if (firstName.isBlank()) add(OposiBotsValidationIssue.BLANK_FIRST_NAME)
        if (lastName.isBlank()) add(OposiBotsValidationIssue.BLANK_LAST_NAME)
        if (specialty.isBlank()) add(OposiBotsValidationIssue.SPECIALTY_NOT_SELECTED)
    }
    if (issues.isNotEmpty()) throw OposiBotsAuthProblem.validation(issues)
}

private fun OposiBotsRecoverAccessRequest.validate() {
    if (identifier.isBlank()) {
        throw OposiBotsAuthProblem.validation(
            setOf(OposiBotsValidationIssue.BLANK_IDENTIFIER),
        )
    }
}

private fun OposiBotsResetPasswordRequest.validate() {
    if (newPassword.isBlank()) {
        throw OposiBotsAuthProblem.validation(
            setOf(OposiBotsValidationIssue.BLANK_PASSWORD),
        )
    }
    if (resetToken.isBlank()) throw OposiBotsAuthProblem.authenticationRequired()
}

private fun String.normalizedAuthIdentifier(): String = trim().lowercase()

private fun ApplicationCall.requiredConsumerBearerToken(): String {
    val header = request.headers[HttpHeaders.Authorization].orEmpty()
    if (!header.startsWith("Bearer ", ignoreCase = true)) {
        throw OposiBotsAuthProblem.authenticationRequired()
    }
    return header.substringAfter(' ').trim()
        .takeIf { it.length in 40..16_384 }
        ?: throw OposiBotsAuthProblem.authenticationRequired()
}

private fun ApplicationCall.requireAuthRateLimit(
    rateLimiter: OposiBotsAuthRateLimiter,
    operation: OposiBotsAuthOperation,
    subject: String?,
) {
    if (!rateLimiter.allow(operation, request.origin.remoteHost, subject)) {
        throw OposiBotsAuthProblem.temporarilyUnavailable(rateLimited = true)
    }
}

private suspend inline fun <reified T : Any> ApplicationCall.receiveAuthBody(): T {
    return try {
        receive()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SerializationException) {
        throw OposiBotsAuthProblem.validation(emptySet())
    } catch (_: BadRequestException) {
        throw OposiBotsAuthProblem.validation(emptySet())
    }
}

private suspend fun ApplicationCall.authEndpoint(
    operation: String,
    block: suspend ApplicationCall.() -> Unit,
) {
    val logger = LoggerFactory.getLogger("OC-KTOR")
    try {
        block()
        logger.info(
            "operation={} requestId={} result=success",
            operation,
            requestId(),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (problem: OposiBotsAuthProblem) {
        logger.info(
            "operation={} requestId={} result={} status={}",
            operation,
            requestId(),
            problem.domainError.name,
            problem.status.value,
        )
        respond(problem.status, problem.response())
    } catch (error: Exception) {
        logger.error(
            "operation={} requestId={} result=unexpected_error type={}",
            operation,
            requestId(),
            error::class.simpleName,
            error,
        )
        val problem = OposiBotsAuthProblem.temporarilyUnavailable()
        respond(problem.status, problem.response())
    }
}
