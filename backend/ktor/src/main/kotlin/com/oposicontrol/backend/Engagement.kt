package com.oposicontrol.backend

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// V1 wave 2 (E1-V1-ENGAGEMENT-DESIGN.md v2.1, FA29): el reto diario, su reclamacion y la racha.
//
//   D3-16 GET  /api/v1/app/retos/hoy                              estado del reto de hoy
//   D3-17 PUT  /api/v1/app/retos/{retoId}/respuestas/{preguntaId} responder (definitiva)
//   D3-18 POST /api/v1/app/retos/{retoId}/reclamar                 cobrar la recompensa congelada (idempotente por usuario y dia de Madrid)
//   D3-19 GET  /api/v1/app/retos/{retoId}/preguntas/{preguntaId}/recursos/{recursoId}  URL firmada de un recurso de una pregunta del reto (la base confirma la cadena)
//   D3-04 GET  /api/v1/app/racha                                   racha actual y maxima
//
// Identidad = SOLO el `sub` del JWT. Reloj, segmento, correccion, recompensa, racha y tope de OT los decide la base en UNA funcion por comando; aqui solo forma,
// limite por usuario y traduccion de errores. El cuerpo de las respuestas lo construye la base con la forma del contrato y se sirve tal cual.

internal enum class ChallengeOperation(val limit: OposiBotsAuthOperation) {
    READ(OposiBotsAuthOperation.CATALOG_READ),
    WRITE(OposiBotsAuthOperation.CHALLENGE_WRITE),
}

@Serializable
@CanonicalCommand
internal data class DailyChallengeAnswerRequest(val opcionSeleccionadaId: String)

internal class ChallengeRuntime(
    val resolver: CanonicalIdentityResolver,
    val service: ChallengeService,
    val signer: ((String, String, Int) -> String)? = null,
    val limit: suspend (ChallengeOperation, CanonicalUserId) -> Unit = { _, _ -> },
) {
    companion object {
        fun fromEnvironment(
            resolver: CanonicalIdentityResolver,
            auth: OposiBotsAuthRuntime?,
            environment: (String) -> String? = System::getenv,
        ): ChallengeRuntime? {
            val upstream = TransportEconomyUpstream.fromEnvironment(environment) ?: return null
            val limiter = auth?.rateLimiter
            val gateway = TransportAttemptUpstream.fromEnvironment(environment)?.let { AttemptGateway(it) }
            return ChallengeRuntime(
                resolver = resolver,
                service = ChallengeService(upstream),
                signer = gateway?.let { g -> { bucket: String, path: String, ttl: Int -> g.signedUrl(bucket, path, ttl) } },
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

internal class ChallengeService(
    private val upstream: EconomyUpstream,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    /** La base devuelve el contenido de la revision bajo `_recursos` (con bucket y ruta); aqui se convierte a los metadatos del contrato y se quita lo interno. */
    suspend fun today(userId: CanonicalUserId): JsonElement {
        val body = rpc("app_get_daily_challenge", buildJsonObject { put("p_usuario", userId.value) }.toString())
        val root = body as? JsonObject ?: return body
        val questions = root["preguntas"] as? JsonArray ?: return body
        val mapped = questions.map { element ->
            val q = element as? JsonObject ?: return@map element
            val raw = (q["_recursos"] as? JsonArray).orEmpty()
            val dtos = raw.mapNotNull { r -> runCatching { json.decodeFromJsonElement(RevisionResource.serializer(), r) }.getOrNull()?.let(QuestionResources::dto) }
                .sortedBy { it.id }
            val kept = JsonObject(q.filterKeys { it != "_recursos" })
            if (dtos.isEmpty()) kept else JsonObject(kept + ("recursos" to json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(AttemptResourceDto.serializer()), dtos)))
        }
        return JsonObject(root + ("preguntas" to JsonArray(mapped)))
    }

    suspend fun resource(userId: CanonicalUserId, retoId: String, questionId: String, resourceId: String, now: java.time.Instant, sign: (String, String, Int) -> String): AttemptResourceAccess {
        val issues = buildList {
            if (!retoId.isUuid()) add(ErrorIssue("retoId", CanonicalIssue.INVALID_FORMAT))
            if (!questionId.isUuid()) add(ErrorIssue("preguntaId", CanonicalIssue.INVALID_FORMAT))
            if (!resourceId.isUuid()) add(ErrorIssue("recursoId", CanonicalIssue.INVALID_FORMAT))
        }
        if (issues.isNotEmpty()) throw validationError(*issues.toTypedArray())
        val response = canonicalUpstreamIo {
            upstream.rpc(
                "app_challenge_resource",
                buildJsonObject {
                    put("p_usuario", userId.value)
                    put("p_reto", retoId.lowercase())
                    put("p_pregunta", questionId.lowercase())
                    put("p_recurso", resourceId.lowercase())
                }.toString(),
            )
        }
        if (!response.ok) {
            // Cualquier cadena rota (reto ajeno, pregunta de otro reto, recurso de otra pregunta) es la MISMA respuesta: sin sondeo.
            if (response.body.contains("NOT_FOUND")) throw CanonicalErrorCode.RESOURCE_NOT_FOUND.exception()
            throw EconomyRpcErrors.failure("app_challenge_resource", response)
        }
        val resource = runCatching { json.decodeFromString(RevisionResource.serializer(), response.body) }.getOrElse { throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception(cause = it) }
        return QuestionResources.access(resource, resourceId.lowercase(), now, sign)
    }

    suspend fun streak(userId: CanonicalUserId): JsonElement = rpc("app_get_streak", buildJsonObject { put("p_usuario", userId.value) }.toString())

    suspend fun answer(userId: CanonicalUserId, retoId: String, questionId: String, request: DailyChallengeAnswerRequest): JsonElement {
        val issues = buildList {
            if (!retoId.isUuid()) add(ErrorIssue("retoId", CanonicalIssue.INVALID_FORMAT))
            if (!questionId.isUuid()) add(ErrorIssue("preguntaId", CanonicalIssue.INVALID_FORMAT))
            if (!request.opcionSeleccionadaId.isUuid()) add(ErrorIssue("opcionSeleccionadaId", CanonicalIssue.INVALID_FORMAT))
        }
        if (issues.isNotEmpty()) throw validationError(*issues.toTypedArray())
        return rpc(
            "app_answer_daily_challenge",
            buildJsonObject {
                put("p_usuario", userId.value)
                put("p_reto", retoId.lowercase())
                put("p_pregunta", questionId.lowercase())
                put("p_opcion", request.opcionSeleccionadaId.lowercase())
            }.toString(),
        )
    }

    suspend fun claim(userId: CanonicalUserId, retoId: String): JsonElement {
        if (!retoId.isUuid()) throw validationError(ErrorIssue("retoId", CanonicalIssue.INVALID_FORMAT))
        return rpc("app_claim_daily_challenge", buildJsonObject { put("p_usuario", userId.value); put("p_reto", retoId.lowercase()) }.toString())
    }

    private suspend fun rpc(function: String, body: String): JsonElement {
        val response = canonicalUpstreamIo { upstream.rpc(function, body) }
        if (!response.ok) throw EconomyRpcErrors.failure(function, response)
        return runCatching { json.parseToJsonElement(response.body) }.getOrElse { throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception(cause = it) }
    }
}

private val ChallengeIdentityKey = AttributeKey<CanonicalUserIdentity>("oc-challenge-identity")

private suspend fun ApplicationCall.challengeGate(runtime: ChallengeRuntime?, operation: ChallengeOperation): CanonicalUserIdentity {
    attributes.getOrNull(ChallengeIdentityKey)?.let { return it }
    val rt = runtime ?: throw CanonicalErrorCode.INTERNAL.exception()
    response.header(HttpHeaders.CacheControl, "no-store")
    val identity = rt.resolver.activeIdentity(this) { userId -> rt.limit(operation, userId) }
    attributes.put(ChallengeIdentityKey, identity)
    return identity
}

internal fun Route.challengeRoutes(runtime: ChallengeRuntime?) {
    route("/api/v1/app") {
        get("/retos/hoy") {
            val identity = call.challengeGate(runtime, ChallengeOperation.READ)
            requireOnlyKnownQueryParams(call.request.queryParameters, emptySet())
            call.respond(runtime!!.service.today(identity.userId))
        }
        get("/racha") {
            val identity = call.challengeGate(runtime, ChallengeOperation.READ)
            requireOnlyKnownQueryParams(call.request.queryParameters, emptySet())
            call.respond(runtime!!.service.streak(identity.userId))
        }
        get("/retos/{retoId}/preguntas/{preguntaId}/recursos/{recursoId}") {
            val identity = call.challengeGate(runtime, ChallengeOperation.READ)
            requireOnlyKnownQueryParams(call.request.queryParameters, emptySet())
            val sign = runtime!!.signer ?: throw CanonicalErrorCode.INTERNAL.exception()
            call.respond(
                runtime.service.resource(
                    identity.userId, call.parameters["retoId"].orEmpty().trim(), call.parameters["preguntaId"].orEmpty().trim(),
                    call.parameters["recursoId"].orEmpty().trim(), java.time.Instant.now(), sign,
                ),
            )
        }
        put("/retos/{retoId}/respuestas/{preguntaId}") {
            val identity = call.challengeGate(runtime, ChallengeOperation.WRITE)
            requireOnlyKnownQueryParams(call.request.queryParameters, emptySet())
            val request = call.receiveCanonicalCommand<DailyChallengeAnswerRequest>()
            call.respond(
                HttpStatusCode.OK,
                runtime!!.service.answer(identity.userId, call.parameters["retoId"].orEmpty().trim(), call.parameters["preguntaId"].orEmpty().trim(), request),
            )
        }
        post("/retos/{retoId}/reclamar") {
            val identity = call.challengeGate(runtime, ChallengeOperation.WRITE)
            requireOnlyKnownQueryParams(call.request.queryParameters, emptySet())
            call.respond(HttpStatusCode.OK, runtime!!.service.claim(identity.userId, call.parameters["retoId"].orEmpty().trim()))
        }
    }
}
