package com.oposicontrol.backend

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.SerializationException
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Todo lo que las rutas canonicas de perfil necesitan (SLICE 1). Se construye una vez en el arranque
 * ([canonicalProfileRuntime]) y es lo que comparten las rutas canonicas y los adaptadores legacy.
 */
internal class CanonicalProfileRuntime(
    val resolver: CanonicalIdentityResolver,
    val profiles: CanonicalProfileService,
    val deletion: AccountDeletionService,
    val legacy: LegacyProfileAdapter,
)

/**
 * Rutas canonicas de perfil (SLICE 1, `openapi/profiles/profiles.yaml`).
 *
 * - D1-11 `GET /api/v1/app/me`, D1-12 `POST /me/onboarding`, D1-13 `PATCH /me/profile`,
 *   D1-17 `POST /me/legal-acceptances`: `activeIdentity` (401 / 403 / 503) y limite por usuario.
 * - D1-19 `DELETE /me`: `deletionIdentity` (activa o ya pendiente).
 * - D1-16 `GET /legal/documents`: publica, limite por IP.
 *
 * Los errores suben como [BackendDomainException] al `StatusPages` (sobre canonico). La identidad sale
 * SIEMPRE del `sub` del JWT verificado: ningun cuerpo lleva id de usuario.
 */
internal fun Route.canonicalProfileRoutes(runtime: OposiBotsAuthRuntime) {
    val canonical = requireNotNull(runtime.canonical) { "canonicalProfileRoutes necesita un CanonicalProfileRuntime" }
    route("/api/v1/app") {
        get("/legal/documents") {
            val decision = runtime.rateLimiter.checkIp(OposiBotsAuthOperation.LEGAL_LIST, call.canonicalClientIp(runtime))
            if (!decision.allowed) throw rateLimited(decision)
            val result = canonical.profiles.listLegalDocuments(call.request.queryParameters["tipo"])
            // MASTER-API-007: revalidacion frecuente; el ETag es de contenido. NO documentado aun en profiles.yaml D1-16 (contrato congelado): texto pendiente en ARCHITECTURE_DELTA_S1 KR3 API-007 / KR4 OPENAPI-002.
            call.response.header(HttpHeaders.CacheControl, "public, max-age=300, must-revalidate")
            call.response.header(HttpHeaders.ETag, result.etag)
            if (ifNoneMatchMatches(call.request.header(HttpHeaders.IfNoneMatch), result.etag)) {
                call.respond(HttpStatusCode.NotModified)
            } else {
                call.respond(LegalDocumentList(result.items))
            }
        }
        route("/me") {
            get {
                call.privateNoStore()
                val identity = canonical.resolver.activeIdentity(call) { call.limitUser(runtime, OposiBotsAuthOperation.ME, it) }
                val result = canonical.profiles.me(identity, call.bearerToken(), call.canonicalContext(runtime))
                result.etag?.let { call.response.header(HttpHeaders.ETag, it) }
                call.respond(result.me)
            }
            delete {
                call.privateNoStore()
                val subject = canonical.resolver.deletionIdentity(call) { call.limitUser(runtime, OposiBotsAuthOperation.ACCOUNT_DELETION, it) }
                val request = call.receiveCanonicalCommand<AccountDeletionRequest>()
                val accepted = canonical.deletion.request(
                    subject.identity,
                    call.bearerToken(),
                    subject.claims,
                    request,
                    call.canonicalContext(runtime),
                )
                call.respond(HttpStatusCode.Accepted, accepted)
            }
            post("/onboarding") {
                call.privateNoStore()
                val identity = canonical.resolver.activeIdentity(call) { call.limitUser(runtime, OposiBotsAuthOperation.PROFILE_WRITE, it) }
                val request = call.receiveCanonicalCommand<OnboardingRequest>()
                val result = canonical.profiles.completeOnboarding(identity, call.bearerToken(), request, call.canonicalContext(runtime))
                result.etag?.let { call.response.header(HttpHeaders.ETag, it) }
                call.respond(result.me)
            }
            patch("/profile") {
                call.privateNoStore()
                val identity = canonical.resolver.activeIdentity(call) { call.limitUser(runtime, OposiBotsAuthOperation.PROFILE_WRITE, it) }
                val request = call.receiveCanonicalCommand<ProfilePatchRequest>()
                val result = canonical.profiles.updateProfile(
                    identity,
                    call.bearerToken(),
                    request,
                    parseIfMatch(call.request.header(HttpHeaders.IfMatch)),
                    call.canonicalContext(runtime),
                )
                result.etag?.let { call.response.header(HttpHeaders.ETag, it) }
                call.respond(result.me)
            }
            post("/legal-acceptances") {
                call.privateNoStore()
                val identity = canonical.resolver.activeIdentity(call) { call.limitUser(runtime, OposiBotsAuthOperation.PROFILE_WRITE, it) }
                val request = call.receiveCanonicalCommand<LegalAcceptanceRequest>()
                call.respond(canonical.profiles.acceptLegalDocuments(identity, request))
            }
        }
    }
}

private fun ApplicationCall.privateNoStore() {
    response.header(HttpHeaders.CacheControl, "private, no-store")
}

private suspend fun ApplicationCall.limitUser(
    runtime: OposiBotsAuthRuntime,
    operation: OposiBotsAuthOperation,
    userId: CanonicalUserId,
) {
    // MASTER-SEC-010: se llama con el sub verificado ANTES de leer el perfil (resolver en dos fases).
    val decision = runtime.rateLimiter.checkSubject(operation, userId.value)
    if (!decision.allowed) throw rateLimited(decision)
}

private fun rateLimited(decision: OposiBotsRateLimitDecision): BackendDomainException =
    CanonicalErrorCode.RATE_LIMITED.exception(retryAfterSeconds = decision.retryAfterSeconds.coerceIn(1, 3_600).toInt())

private fun ApplicationCall.bearerToken(): String =
    request.headers[HttpHeaders.Authorization].orEmpty().substringAfter(' ').trim()

private fun ApplicationCall.canonicalContext(runtime: OposiBotsAuthRuntime) =
    OposiBotsAuthContext(requestId = ensureRequestId(), clientIp = canonicalClientIp(runtime))

private fun ApplicationCall.canonicalClientIp(runtime: OposiBotsAuthRuntime): String = trustedClientIp(runtime.configuration)

/** `If-Match` opcional: `*` o ausente = sin precondicion; un instante ISO (con o sin comillas / `W/`) = version. Otra cosa: 400. */
internal fun parseIfMatch(header: String?): String? {
    val value = header?.trim()?.removePrefix("W/")?.trim('"')?.trim().orEmpty()
    if (value.isEmpty() || value == "*") return null
    return try {
        OffsetDateTime.parse(value).toInstant().toString()
    } catch (_: DateTimeParseException) {
        throw validationError(ErrorIssue("If-Match", CanonicalIssue.INVALID_FORMAT))
    }
}
