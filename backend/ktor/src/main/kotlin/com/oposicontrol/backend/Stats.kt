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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// V1 wave 3 (E1-V1-W3-CONTRACT.md, FA32): estadisticas del usuario actual.
//
//   D3-20 GET /api/v1/app/estadisticas/resumen                     nota media, evolucion, racha, liga, totales
//   D3-21 GET /api/v1/app/estadisticas/debilidades?examenId&top   etiquetas debiles (agregado o de un examen)
//   D3-22 GET /api/v1/app/estadisticas/dificultad/{preguntaId}      dificultad 1-5 de una pregunta que el usuario ha respondido
//
// Identidad = SOLO el `sub` del JWT; nada de ids de usuario en la ruta. Las cifras las escribe la base en la misma transaccion que la primera respuesta (exactamente una vez) y aqui solo se leen
// (tablas de una fila o indexadas); ningun endpoint dispara un recalculo. Aqui solo forma, limite por usuario y traduccion de errores.

internal enum class StatsOperation(val limit: OposiBotsAuthOperation) {
    READ(OposiBotsAuthOperation.CATALOG_READ),
}

internal class StatsRuntime(
    val resolver: CanonicalIdentityResolver,
    val service: StatsService,
    val limit: suspend (StatsOperation, CanonicalUserId) -> Unit = { _, _ -> },
) {
    companion object {
        fun fromEnvironment(
            resolver: CanonicalIdentityResolver,
            auth: OposiBotsAuthRuntime?,
            environment: (String) -> String? = System::getenv,
        ): StatsRuntime? {
            val upstream = TransportEconomyUpstream.fromEnvironment(environment) ?: return null
            val limiter = auth?.rateLimiter
            return StatsRuntime(
                resolver = resolver,
                service = StatsService(upstream),
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

internal class StatsService(
    private val upstream: EconomyUpstream,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun summary(userId: CanonicalUserId): JsonElement = rpc("app_stats_summary", buildJsonObject { put("p_usuario", userId.value) }.toString())

    suspend fun weaknesses(userId: CanonicalUserId, examId: String?, limit: Int): JsonElement {
        if (examId != null && !examId.isUuid()) throw validationError(ErrorIssue("examenId", CanonicalIssue.INVALID_FORMAT))
        if (limit !in 1..MAX_LIMIT) throw validationError(ErrorIssue("top", CanonicalIssue.OUT_OF_RANGE))
        return rpc(
            "app_stats_weaknesses",
            buildJsonObject {
                put("p_usuario", userId.value)
                put("p_examen", examId?.lowercase()?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
                put("p_limite", limit)
            }.toString(),
        )
    }

    suspend fun difficulty(userId: CanonicalUserId, questionId: String): JsonElement {
        if (!questionId.isUuid()) throw validationError(ErrorIssue("preguntaId", CanonicalIssue.INVALID_FORMAT))
        return rpc("app_stats_difficulty", buildJsonObject { put("p_usuario", userId.value); put("p_pregunta", questionId.lowercase()) }.toString())
    }

    private suspend fun rpc(function: String, body: String): JsonElement {
        val response = canonicalUpstreamIo { upstream.rpc(function, body) }
        if (!response.ok) throw EconomyRpcErrors.failure(function, response)
        return runCatching { json.parseToJsonElement(response.body) }.getOrElse { throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception(cause = it) }
    }

    companion object {
        const val MAX_LIMIT = 50
        const val DEFAULT_LIMIT = 10
    }
}

private val StatsIdentityKey = AttributeKey<CanonicalUserIdentity>("oc-stats-identity")

private suspend fun ApplicationCall.statsGate(runtime: StatsRuntime?, operation: StatsOperation): CanonicalUserIdentity {
    attributes.getOrNull(StatsIdentityKey)?.let { return it }
    val rt = runtime ?: throw CanonicalErrorCode.INTERNAL.exception()
    response.header(HttpHeaders.CacheControl, "no-store")
    val identity = rt.resolver.activeIdentity(this) { userId -> rt.limit(operation, userId) }
    attributes.put(StatsIdentityKey, identity)
    return identity
}

internal fun Route.statsRoutes(runtime: StatsRuntime?) {
    get("/api/v1/app/estadisticas/resumen") {
        val identity = call.statsGate(runtime, StatsOperation.READ)
        requireOnlyKnownQueryParams(call.request.queryParameters, emptySet())
        call.respond(runtime!!.service.summary(identity.userId))
    }
    get("/api/v1/app/estadisticas/debilidades") {
        val identity = call.statsGate(runtime, StatsOperation.READ)
        requireOnlyKnownQueryParams(call.request.queryParameters, setOf("examenId", "top"))
        val limitRaw = call.request.queryParameters["top"]?.trim()
        val limit = if (limitRaw == null) StatsService.DEFAULT_LIMIT else limitRaw.toIntOrNull() ?: throw validationError(ErrorIssue("top", CanonicalIssue.INVALID_FORMAT))
        call.respond(runtime!!.service.weaknesses(identity.userId, call.request.queryParameters["examenId"]?.trim(), limit))
    }
    get("/api/v1/app/estadisticas/dificultad/{preguntaId}") {
        val identity = call.statsGate(runtime, StatsOperation.READ)
        requireOnlyKnownQueryParams(call.request.queryParameters, emptySet())
        call.respond(runtime!!.service.difficulty(identity.userId, call.parameters["preguntaId"].orEmpty().trim()))
    }
}
