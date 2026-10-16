package com.oposicontrol.backend

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// V1 wave 5a (E1-V1-W5-REFERRAL-CONTRACT.md, FA34): Invita y gana, lado del invitador.
//
//   D9-01 GET /api/v1/app/me/invitacion       codigo propio, contadores, importe por invitacion (null = funcion apagada)
//   D9-02 GET /api/v1/app/me/invitaciones     invitaciones PREMIADAS (solo handle publico), cursor opaco
//
// El registro del invitado (campo opcional `codigoInvitacion` de register/profile) llama a [ReferralAttach]: SIEMPRE neutral, nunca falla ni bloquea el registro.
// Identidad = SOLO el `sub` del JWT. Toda la logica (estados, reglas, pago) vive en la base; el pago lo hace el trabajo periodico (`app_job_referidos`).

internal enum class ReferralOperation(val limit: OposiBotsAuthOperation) {
    READ(OposiBotsAuthOperation.CATALOG_READ),
}

internal class ReferralRuntime(
    val resolver: CanonicalIdentityResolver,
    val service: ReferralService,
    val limit: suspend (ReferralOperation, CanonicalUserId) -> Unit = { _, _ -> },
) {
    companion object {
        fun fromEnvironment(
            resolver: CanonicalIdentityResolver,
            auth: OposiBotsAuthRuntime?,
            environment: (String) -> String? = System::getenv,
        ): ReferralRuntime? {
            val upstream = TransportEconomyUpstream.fromEnvironment(environment) ?: return null
            val limiter = auth?.rateLimiter
            return ReferralRuntime(
                resolver = resolver,
                service = ReferralService(upstream),
                limit = { operation, userId ->
                    if (limiter != null) {
                        val decision = limiter.checkSubject(operation.limit, userId.value)
                        if (!decision.allowed) throw CanonicalErrorCode.RATE_LIMITED.exception(retryAfterSeconds = decision.retryAfterSeconds.coerceIn(1, 3_600).toInt())
                    }
                },
            )
        }
    }
}

internal class ReferralService(
    private val upstream: EconomyUpstream,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun mine(userId: CanonicalUserId): JsonElement = rpc("app_referral_get", buildJsonObject { put("p_usuario", userId.value) }.toString())

    suspend fun list(userId: CanonicalUserId, cursor: String?, limit: Int): JsonElement {
        if (cursor != null && (cursor.length > 200 || !cursor.matches(CURSOR_PATTERN))) throw validationError(ErrorIssue("cursor", CanonicalIssue.INVALID_FORMAT))
        return rpc(
            "app_referral_list",
            buildJsonObject {
                put("p_usuario", userId.value)
                put("p_cursor", cursor?.let { JsonPrimitive(it) } ?: JsonNull)
                put("p_limite", limit.coerceIn(1, MAX_LIMIT))   // LimitApp: values outside 1..50 are clamped
            }.toString(),
        )
    }

    /** Registro: neutral. Cualquier fallo (red, base, formato) se traga: el registro nunca depende del codigo. */
    suspend fun attach(userId: String, code: String?) {
        val trimmed = code?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.length > 16) return
        runCatching {
            canonicalUpstreamIo {
                upstream.rpc("app_referral_attach", buildJsonObject { put("p_invitado", userId); put("p_codigo", trimmed); put("p_ip_hash", JsonNull) }.toString())
            }
        }
    }

    private suspend fun rpc(function: String, body: String): JsonElement {
        val response = canonicalUpstreamIo { upstream.rpc(function, body) }
        if (!response.ok) throw EconomyRpcErrors.failure(function, response)
        return runCatching { json.parseToJsonElement(response.body) }.getOrElse { throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception(cause = it) }
    }

    companion object {
        const val MAX_LIMIT = 50
        const val DEFAULT_LIMIT = 20
        private val CURSOR_PATTERN = Regex("^[A-Za-z0-9_=-]+$")
    }
}

/** Puente hacia el registro (`LegacyProfileAdapter`): se rellena al arrancar con el mismo upstream; sin el, el codigo se ignora. */
internal fun interface ReferralAttach {
    suspend fun attach(userId: String, code: String?)
}

private val ReferralIdentityKey = AttributeKey<CanonicalUserIdentity>("oc-referral-identity")

private suspend fun ApplicationCall.referralGate(runtime: ReferralRuntime?, operation: ReferralOperation): CanonicalUserIdentity {
    attributes.getOrNull(ReferralIdentityKey)?.let { return it }
    val rt = runtime ?: throw CanonicalErrorCode.INTERNAL.exception()
    response.header(HttpHeaders.CacheControl, "no-store")
    val identity = rt.resolver.activeIdentity(this) { userId -> rt.limit(operation, userId) }
    attributes.put(ReferralIdentityKey, identity)
    return identity
}

internal fun Route.referralRoutes(runtime: ReferralRuntime?) {
    get("/api/v1/app/me/invitacion") {
        val identity = call.referralGate(runtime, ReferralOperation.READ)
        requireOnlyKnownQueryParams(call.request.queryParameters, emptySet())
        call.respond(runtime!!.service.mine(identity.userId))
    }
    get("/api/v1/app/me/invitaciones") {
        val identity = call.referralGate(runtime, ReferralOperation.READ)
        requireOnlyKnownQueryParams(call.request.queryParameters, setOf("cursor", "limit"))
        val limitRaw = call.request.queryParameters["limit"]?.trim()
        val limit = if (limitRaw == null) ReferralService.DEFAULT_LIMIT else limitRaw.toIntOrNull() ?: throw validationError(ErrorIssue("limit", CanonicalIssue.INVALID_FORMAT))
        call.respond(runtime!!.service.list(identity.userId, call.request.queryParameters["cursor"]?.trim(), limit))
    }
}
