package com.oposicontrol.backend

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// V1 Access v2 (E1-V1-ACCESS-V2-DESIGN.md v2.1, FA27): el cupo de inmersivos del plan.
//
//   D7-31 GET /api/v1/app/plan/cupo                 plan, cupo, elegidos y contador del limitador diario
//   D7-32 PUT /api/v1/app/plan/cupo/{examenId}      elegir un inmersivo con el cupo (idempotente por naturaleza, sin Idempotency-Key)
//   D7-33 GET /api/v1/app/plan/catalogo             planes activos y visibles con cobertura, limite diario y tarifas (solo fase standard) COMO DATOS; sin datos de usuario
//
// Identidad = SOLO el `sub` del JWT. Todo lo demas (puertas de especialidad y capacidad, cupo, suscripcion, bloqueo por usuario) lo decide la base
// en UNA funcion bajo el cerrojo del monedero; aqui solo forma, limite por usuario y traduccion de errores.

internal enum class PlanOperation(val limit: OposiBotsAuthOperation) {
    READ(OposiBotsAuthOperation.CATALOG_READ),
    WRITE(OposiBotsAuthOperation.PLAN_WRITE),
}

internal class PlanQuotaRuntime(
    val resolver: CanonicalIdentityResolver,
    val service: PlanQuotaService,
    val limit: suspend (PlanOperation, CanonicalUserId) -> Unit = { _, _ -> },
) {
    companion object {
        fun fromEnvironment(
            resolver: CanonicalIdentityResolver,
            auth: OposiBotsAuthRuntime?,
            environment: (String) -> String? = System::getenv,
        ): PlanQuotaRuntime? {
            val upstream = TransportEconomyUpstream.fromEnvironment(environment) ?: return null
            val limiter = auth?.rateLimiter
            return PlanQuotaRuntime(
                resolver = resolver,
                service = PlanQuotaService(upstream),
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
internal data class PlanQuotaChoiceDto(val examenId: String, val titulo: String? = null, val desde: String, val hasta: String)

@Serializable
internal data class PlanQuotaDto(
    val planCodigo: String? = null,
    val nivel: Int,
    val cupoTotal: Int,
    val cupoUsado: Int,
    val cupoLibre: Int,
    val periodoHasta: String? = null,
    val limitePreguntas24h: Int? = null,
    val reveladas24h: Int = 0,
    val elegidos: List<PlanQuotaChoiceDto> = emptyList(),
)

@Serializable
private data class ChoiceRow(val outcome: String, @SerialName("examen_id") val examenId: String, val titulo: String? = null, val desde: String, val hasta: String)

internal class PlanQuotaService(
    private val upstream: EconomyUpstream,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun quota(userId: CanonicalUserId): PlanQuotaDto {
        val response = canonicalUpstreamIo { upstream.rpc("app_plan_quota", buildJsonObject { put("p_usuario", userId.value) }.toString()) }
        if (!response.ok) throw EconomyRpcErrors.failure("app_plan_quota", response)
        return runCatching { json.decodeFromString(PlanQuotaDto.serializer(), response.body) }
            .getOrElse { throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception(cause = it) }
    }

    /** D7-33: el cuerpo lo construye la base (`app_plan_catalog`) con la forma del contrato y se sirve tal cual. */
    suspend fun catalog(): kotlinx.serialization.json.JsonElement {
        val response = canonicalUpstreamIo { upstream.rpc("app_plan_catalog", "{}") }
        if (!response.ok) throw EconomyRpcErrors.failure("app_plan_catalog", response)
        return runCatching { json.parseToJsonElement(response.body) }.getOrElse { throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception(cause = it) }
    }

    suspend fun choose(userId: CanonicalUserId, examId: String): PlanQuotaChoiceDto {
        if (!examId.isUuid()) throw validationError(ErrorIssue("examenId", CanonicalIssue.INVALID_FORMAT))
        val response = canonicalUpstreamIo {
            upstream.rpc("app_choose_plan_exam", buildJsonObject { put("p_usuario", userId.value); put("p_examen", examId.lowercase()) }.toString())
        }
        if (!response.ok) throw EconomyRpcErrors.failure("app_choose_plan_exam", response)
        val row = runCatching { json.decodeFromString(ListSerializer(ChoiceRow.serializer()), response.body) }
            .getOrElse { throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception(cause = it) }
            .singleOrNull() ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        return PlanQuotaChoiceDto(row.examenId, row.titulo, row.desde, row.hasta)
    }
}

private val PlanIdentityKey = AttributeKey<CanonicalUserIdentity>("oc-plan-identity")

private suspend fun ApplicationCall.planGate(runtime: PlanQuotaRuntime?, operation: PlanOperation): CanonicalUserIdentity {
    attributes.getOrNull(PlanIdentityKey)?.let { return it }
    val rt = runtime ?: throw CanonicalErrorCode.INTERNAL.exception()
    response.header(HttpHeaders.CacheControl, "no-store")
    val identity = rt.resolver.activeIdentity(this) { userId -> rt.limit(operation, userId) }
    attributes.put(PlanIdentityKey, identity)
    return identity
}

internal fun Route.planQuotaRoutes(runtime: PlanQuotaRuntime?) {
    route("/api/v1/app/plan/catalogo") {
        get {
            call.planGate(runtime, PlanOperation.READ)
            requireOnlyKnownQueryParams(call.request.queryParameters, emptySet())
            call.respond(runtime!!.service.catalog())
        }
    }
    route("/api/v1/app/plan/cupo") {
        get {
            val identity = call.planGate(runtime, PlanOperation.READ)
            requireOnlyKnownQueryParams(call.request.queryParameters, emptySet())
            call.respond(runtime!!.service.quota(identity.userId))
        }
        put("/{examenId}") {
            val identity = call.planGate(runtime, PlanOperation.WRITE)
            requireOnlyKnownQueryParams(call.request.queryParameters, emptySet())
            call.respond(HttpStatusCode.OK, runtime!!.service.choose(identity.userId, call.parameters["examenId"].orEmpty().trim()))
        }
    }
}
