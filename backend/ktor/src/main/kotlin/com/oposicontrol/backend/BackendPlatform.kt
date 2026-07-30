package com.oposicontrol.backend

import com.oposicontrol.admin.core.api.AdminApiHeaders
import com.oposicontrol.admin.core.api.AdminApiJson
import com.oposicontrol.admin.core.api.ApiErrorResponse
import com.oposicontrol.admin.core.api.HealthResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.origin
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import org.slf4j.event.Level
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

internal const val ADMIN_AUTHENTICATION = "supabase-admin-jwt"
private val ADMIN_RATE_LIMIT = RateLimitName("admin-protected")
private val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9._-]{8,128}")

internal data class ReadinessSnapshot(
    val ready: Boolean,
    val status: String,
)

internal fun interface ReadinessProvider {
    suspend fun snapshot(): ReadinessSnapshot
}

internal fun Application.configureBackendPlatform(
    authenticator: AdminTokenAuthenticator,
    trustProxyHeaders: Boolean = false,
) {
    if (trustProxyHeaders) {
        // Safe only when Ktor is reachable exclusively through the configured
        // reverse proxy, as required by the production deployment.
        install(XForwardedHeaders)
    }
    install(ContentNegotiation) {
        json(AdminApiJson.format)
    }
    install(CallId) {
        retrieveFromHeader(AdminApiHeaders.REQUEST_ID)
        verify { it.matches(REQUEST_ID_PATTERN) }
        generate { UUID.randomUUID().toString() }
        replyToHeader(AdminApiHeaders.REQUEST_ID)
    }
    install(CallLogging) {
        level = Level.INFO
        callIdMdc("requestId")
        filter { call -> !call.request.path().startsWith("/health/live") }
    }
    install(StatusPages) {
        exception<ApiProblem> { call, problem ->
            call.respond(problem.status, problem.toResponse(call.requestId()))
        }
        exception<SerializationException> { call, _ ->
            val problem = BadRequestProblem("El cuerpo JSON no es válido.")
            call.respond(problem.status, problem.toResponse(call.requestId()))
        }
        exception<IllegalArgumentException> { call, _ ->
            val problem = BadRequestProblem()
            call.respond(problem.status, problem.toResponse(call.requestId()))
        }
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) throw cause
            call.application.log.error(
                "Unhandled backend error requestId={} type={}",
                call.requestId(),
                cause::class.simpleName,
            )
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiErrorResponse(
                    code = "internal_error",
                    message = "Se produjo un error interno.",
                    requestId = call.requestId(),
                    retryable = false,
                ),
            )
        }
        status(HttpStatusCode.Unauthorized) { call, _ ->
            if (call.request.path().startsWith("/v1/auth/")) {
                val problem = OposiBotsAuthProblem.authenticationRequired()
                call.respond(problem.status, problem.response())
                return@status
            }
            call.respond(
                HttpStatusCode.Unauthorized,
                UnauthorizedProblem().toResponse(call.requestId()),
            )
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            if (call.request.path().startsWith("/v1/auth/")) {
                val problem = OposiBotsAuthProblem.notFound()
                call.respond(problem.status, problem.response())
                return@status
            }
            call.respond(
                HttpStatusCode.NotFound,
                NotFoundProblem().toResponse(call.requestId()),
            )
        }
        status(HttpStatusCode.TooManyRequests) { call, _ ->
            if (call.request.path().startsWith("/v1/auth/")) {
                val problem = OposiBotsAuthProblem.temporarilyUnavailable(rateLimited = true)
                call.respond(problem.status, problem.response())
                return@status
            }
            call.respond(
                HttpStatusCode.TooManyRequests,
                ApiErrorResponse(
                    code = "rate_limited",
                    message = "Demasiadas solicitudes. Inténtalo de nuevo más tarde.",
                    requestId = call.requestId(),
                    retryable = true,
                ),
            )
        }
    }
    install(Authentication) {
        bearer(ADMIN_AUTHENTICATION) {
            realm = "OposiControl Admin"
            authenticate { credentials ->
                authenticator.authenticate(credentials.token)
            }
        }
    }
    install(RateLimit) {
        register(ADMIN_RATE_LIMIT) {
            rateLimiter(limit = 180, refillPeriod = 1.minutes)
            requestKey { call ->
                call.principalOrNull()?.authUserId?.toString()
                    ?: call.request.origin.remoteHost
            }
        }
    }
}

internal fun Application.configureTestableBackend(
    authenticator: AdminTokenAuthenticator,
    gateway: CanonicalAdminGateway,
    serviceVersion: String = "test",
    readinessProvider: ReadinessProvider = ReadinessProvider {
        ReadinessSnapshot(ready = true, status = "ready")
    },
) {
    configureBackendPlatform(authenticator)
    routing {
        liveRoute(serviceVersion)
        protectedAdminRoutes {
            readinessRoute(serviceVersion, readinessProvider)
            canonicalAdminRoutes(gateway)
        }
    }
}

internal fun Routing.liveRoute(serviceVersion: String) {
    get("/health/live") {
        call.respond(
            HealthResponse(
                status = "ok",
                service = "oposicontrol-backend",
                version = serviceVersion,
                requestId = call.requestId(),
            ),
        )
    }
}

internal fun Route.protectedAdminRoutes(block: Route.() -> Unit) {
    authenticate(ADMIN_AUTHENTICATION) {
        rateLimit(ADMIN_RATE_LIMIT) {
            block()
        }
    }
}

internal fun Route.readinessRoute(
    serviceVersion: String,
    readinessProvider: ReadinessProvider,
) {
    get("/health/ready") {
        call.requirePermission("maintenance")
        val snapshot = readinessProvider.snapshot()
        call.respond(
            if (snapshot.ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            HealthResponse(
                status = snapshot.status,
                service = "oposicontrol-backend",
                version = serviceVersion,
                requestId = call.requestId(),
            ),
        )
    }
}

internal fun io.ktor.server.application.ApplicationCall.requestId(): String =
    callId ?: response.headers[AdminApiHeaders.REQUEST_ID] ?: UUID.randomUUID().toString()

private fun io.ktor.server.application.ApplicationCall.principalOrNull(): AdminPrincipal? =
    principal<AdminPrincipal>()
