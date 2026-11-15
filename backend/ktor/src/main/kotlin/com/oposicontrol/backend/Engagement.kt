package com.oposicontrol.backend

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// V1 wave 2 (E1-V1-ENGAGEMENT-DESIGN.md v2.1, FA29): el reto diario, su reclamacion y la racha.
//
//   D3-16 GET  /api/v1/app/retos/hoy                              estado del reto de hoy
//   D3-17 PUT  /api/v1/app/retos/{retoId}/respuestas/{preguntaId} responder (definitiva)
//   D3-18 POST /api/v1/app/retos/{retoId}/reclamar                 cobrar la recompensa congelada (idempotente por usuario y dia de Madrid)
//   D3-19 GET  /api/v1/app/retos/{retoId}/preguntas/{preguntaId}/recursos/{recursoId}  URL firmada de un recurso de una pregunta del reto (la base confirma la cadena)
//   D3-04 GET  /api/v1/app/racha                                   racha actual y maxima; FA43: liga del usuario y OT gratis ganadas hoy / tope diario (barra de Home)
//   D3-26 GET  /api/v1/app/racha/calendario?mes=YYYY-MM            FA56 (F31): calendario del mes del panel "Manten tu racha" (estado de cada dia hasta hoy)
//   D2-69 GET  /api/v1/app/contenido/version                       FA58 (F31c): tokens opacos de version de examenes, servicios, noticias y paquetes (ETag / 304)
//
// Identidad = SOLO el `sub` del JWT. Reloj, segmento, correccion, recompensa, racha y tope de OT los decide la base en UNA funcion por comando; aqui solo forma,
// limite por usuario y traduccion de errores. El cuerpo de las respuestas lo construye la base con la forma del contrato y se sirve tal cual.

internal enum class ChallengeOperation(val limit: OposiBotsAuthOperation) {
    READ(OposiBotsAuthOperation.CATALOG_READ),
    WRITE(OposiBotsAuthOperation.CHALLENGE_WRITE),
}

@Serializable
@CanonicalCommand
internal data class DailyChallengeAnswerRequest(
    val opcionSeleccionadaId: String,
    /** FA52 (F28): tras un fallo retenido por una doble/triple oportunidad, `true` = gastar un uso y responder otra opcion; `false` = rechazarla (la respuesta retenida queda definitiva; en la primera respuesta: no ofrecerla). Ausente = sin decision. */
    val usarOportunidad: Boolean? = null,
    /**
     * C3 (revision 2026-11-14): `true` = este cliente sabe tratar la respuesta RETENIDA por una doble/triple oportunidad (oferta + `usarOportunidad`).
     * Ausente / `false` / null = cliente que no la conoce: Ktor manda `p_admite_oportunidad = false` y la base trata la decision como `false` (nunca se
     * retiene; una retenida previa queda rechazada) y `usarOportunidad` se ignora, para que un cliente viejo nunca deje el reto atascado.
     */
    val admiteOportunidad: Boolean? = null,
    /** FA57 (F31b): tras un fallo RETENIDO con oferta de reintento de pago, `true` = pagar el precio y responder OTRA opcion; `false` = rechazarlo (la retenida queda definitiva; en la primera respuesta: no ofrecerlo). Ausente = sin decision. */
    val pagarReintento: Boolean? = null,
    /** FA57: `true` = este cliente sabe tratar la oferta de reintento de pago. Ausente / false / null = nunca se retiene (cliente viejo) y `pagarReintento` se ignora. */
    val admiteReintentoPago: Boolean? = null,
)

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

    /**
     * D3-26 (FA56): `mes` = YYYY-MM o ausente (mes actual de Madrid, lo decide la base). El rango (desde el mes del primer reto del usuario hasta el mes
     * actual) lo decide la base con su reloj: RACHA_MES_INVALID -> 400 VALIDATION, issue `mes` OUT_OF_RANGE.
     */
    suspend fun calendar(userId: CanonicalUserId, mes: String?): JsonElement {
        if (mes != null && !RACHA_MES.matches(mes)) throw validationError(ErrorIssue("mes", CanonicalIssue.INVALID_FORMAT))
        val body = buildJsonObject {
            put("p_usuario", userId.value)
            if (mes != null) put("p_mes", "$mes-01")
        }.toString()
        val response = canonicalUpstreamIo { upstream.rpc("app_racha_calendario", body) }
        if (!response.ok) {
            if (response.body.contains("RACHA_MES_INVALID")) throw validationError(ErrorIssue("mes", CanonicalIssue.OUT_OF_RANGE))
            throw EconomyRpcErrors.failure("app_racha_calendario", response)
        }
        return runCatching { json.parseToJsonElement(response.body) }.getOrElse { throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception(cause = it) }
    }

    /**
     * D2-69 (FA58): los cuatro tokens opacos que construye la base (`app_contenido_version`) y el ETag de contenido (sha-256 de los cuatro, entre comillas).
     * Un cuerpo sin los cuatro tokens de texto es un contrato roto de la base (502), nunca se rellena.
     */
    suspend fun contentVersion(userId: CanonicalUserId): Pair<JsonObject, String> {
        val body = rpc("app_contenido_version", buildJsonObject { put("p_usuario", userId.value) }.toString())
        val root = body as? JsonObject ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        val tokens = CONTENT_VERSION_AREAS.map { area ->
            (root[area] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content
                ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(tokens.joinToString("|").toByteArray(Charsets.UTF_8))
        val etag = "\"" + digest.joinToString("") { "%02x".format(it) }.take(32) + "\""
        return JsonObject(CONTENT_VERSION_AREAS.zip(tokens).associate { (area, token) -> area to JsonPrimitive(token) }) to etag
    }

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
                // C3: solo un cliente que declara admiteOportunidad puede recibir una respuesta retenida; sin el, la base decide false (nunca retener),
                // igual que con la llamada de 4 argumentos de la imagen anterior. (Necesita la base con 20261114400200: la imagen va DESPUES.)
                val admite = request.admiteOportunidad == true
                put("p_admite_oportunidad", admite)
                if (admite) request.usarOportunidad?.let { put("p_usar_oportunidad", it) }
                // FA57: mismo patron para el reintento de pago (necesita la base con 20261115100100: la imagen va DESPUES)
                val admitePago = request.admiteReintentoPago == true
                put("p_admite_reintento_pago", admitePago)
                if (admitePago) request.pagarReintento?.let { put("p_pagar_reintento", it) }
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

/** YYYY-MM con mes 01..12 (el rango real lo acota la base). */
private val RACHA_MES = Regex("^[0-9]{4}-(0[1-9]|1[0-2])$")

/** D2-69 (FA58): las areas con version, en el orden del contrato y del ETag. */
private val CONTENT_VERSION_AREAS = listOf("examenes", "servicios", "noticias", "paquetes")

private val ChallengeIdentityKey = AttributeKey<CanonicalUserIdentity>("oc-challenge-identity")

private suspend fun ApplicationCall.challengeGate(runtime: ChallengeRuntime?, operation: ChallengeOperation, cacheControl: String = "no-store"): CanonicalUserIdentity {
    attributes.getOrNull(ChallengeIdentityKey)?.let { return it }
    val rt = runtime ?: throw CanonicalErrorCode.INTERNAL.exception()
    response.header(HttpHeaders.CacheControl, cacheControl)
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
        get("/racha/calendario") {
            val identity = call.challengeGate(runtime, ChallengeOperation.READ, cacheControl = "private, no-store")  // datos del usuario: nunca en una cache compartida
            requireOnlyKnownQueryParams(call.request.queryParameters, setOf("mes"))
            val mes = call.request.queryParameters.getAll("mes")?.let { values ->
                if (values.size != 1) throw validationError(ErrorIssue("mes", CanonicalIssue.INVALID_FORMAT))
                values.single().trim()
            }
            val body = runtime!!.service.calendar(identity.userId, mes)
            call.respond(body)
        }
        get("/contenido/version") {
            // FA58: la app lo consulta para saber si recargar; revalidacion siempre (no-cache) y 304 mientras el ETag no cambie
            val identity = call.challengeGate(runtime, ChallengeOperation.READ, cacheControl = "private, no-cache")
            requireOnlyKnownQueryParams(call.request.queryParameters, emptySet())
            val (body, etag) = runtime!!.service.contentVersion(identity.userId)
            call.response.header(HttpHeaders.ETag, etag)
            if (ifNoneMatchMatches(call.request.header(HttpHeaders.IfNoneMatch), etag)) call.respond(HttpStatusCode.NotModified) else call.respond(body)
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
            val request = call.receiveCanonicalCommand<DailyChallengeAnswerRequest>(nullableFields = setOf("usarOportunidad", "admiteOportunidad", "pagarReintento", "admiteReintentoPago"))
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
