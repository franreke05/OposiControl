package com.oposicontrol.backend

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// V1 wave 4 (E1-V1-W4-CONTRACT.md, FA33): ranking del usuario actual.
//
//   D3-24 GET /api/v1/app/ranking              liga, posicion propia, top 3 de la propia cohorte, probabilidad de plaza
//   D3-25 PUT /api/v1/app/ranking/visibilidad  opt-in/opt-out del handle publico {publico}
//
// Identidad = SOLO el `sub` del JWT; ningun endpoint recibe un id de usuario. Las posiciones y la probabilidad las MATERIALIZA un job acotado y con enfriamiento (app_job_ranking);
// aqui solo se leen. La cohorte minima se aplica dentro de la funcion SQL, nunca aqui ni en el cliente.

internal enum class RankingOperation(val limit: OposiBotsAuthOperation) {
    READ(OposiBotsAuthOperation.CATALOG_READ),
    WRITE(OposiBotsAuthOperation.PLAN_WRITE),
}

internal class RankingRuntime(
    val resolver: CanonicalIdentityResolver,
    val service: RankingService,
    val limit: suspend (RankingOperation, CanonicalUserId) -> Unit = { _, _ -> },
) {
    companion object {
        fun fromEnvironment(
            resolver: CanonicalIdentityResolver,
            auth: OposiBotsAuthRuntime?,
            environment: (String) -> String? = System::getenv,
        ): RankingRuntime? {
            val upstream = TransportEconomyUpstream.fromEnvironment(environment) ?: return null
            val limiter = auth?.rateLimiter
            return RankingRuntime(
                resolver = resolver,
                service = RankingService(upstream),
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

@Serializable
@CanonicalCommand
internal data class RankingVisibilityRequest(val publico: Boolean)

internal class RankingService(
    private val upstream: EconomyUpstream,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun view(userId: CanonicalUserId): JsonElement = rpc("app_ranking_me", buildJsonObject { put("p_usuario", userId.value) }.toString())

    suspend fun setVisibility(userId: CanonicalUserId, publico: Boolean): JsonElement =
        rpc("app_set_ranking_visibility", buildJsonObject { put("p_usuario", userId.value); put("p_publico", publico) }.toString())

    private suspend fun rpc(function: String, body: String): JsonElement {
        val response = canonicalUpstreamIo { upstream.rpc(function, body) }
        if (!response.ok) throw EconomyRpcErrors.failure(function, response)
        return runCatching { json.parseToJsonElement(response.body) }.getOrElse { throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception(cause = it) }
    }
}

private val RankingIdentityKey = AttributeKey<CanonicalUserIdentity>("oc-ranking-identity")

private suspend fun ApplicationCall.rankingGate(runtime: RankingRuntime?, operation: RankingOperation): CanonicalUserIdentity {
    attributes.getOrNull(RankingIdentityKey)?.let { return it }
    val rt = runtime ?: throw CanonicalErrorCode.INTERNAL.exception()
    response.header(HttpHeaders.CacheControl, "no-store")
    val identity = rt.resolver.activeIdentity(this) { userId -> rt.limit(operation, userId) }
    attributes.put(RankingIdentityKey, identity)
    return identity
}

internal fun Route.rankingRoutes(runtime: RankingRuntime?) {
    get("/api/v1/app/ranking") {
        val identity = call.rankingGate(runtime, RankingOperation.READ)
        requireOnlyKnownQueryParams(call.request.queryParameters, emptySet())
        call.respond(runtime!!.service.view(identity.userId))
    }
    put("/api/v1/app/ranking/visibilidad") {
        val identity = call.rankingGate(runtime, RankingOperation.WRITE)
        requireOnlyKnownQueryParams(call.request.queryParameters, emptySet())
        val request = call.receiveCanonicalCommand<RankingVisibilityRequest>()
        call.respond(runtime!!.service.setVisibility(identity.userId, request.publico))
    }
}
