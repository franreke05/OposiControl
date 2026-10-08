package com.oposicontrol.backend

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey

/** Operaciones del runtime de examen con limite por usuario (las que el contrato declara con 429). */
internal enum class AttemptOperation(val limit: OposiBotsAuthOperation) {
    START(OposiBotsAuthOperation.ATTEMPT_START),
    ANSWER(OposiBotsAuthOperation.ATTEMPT_ANSWER),
    CLOSE(OposiBotsAuthOperation.ATTEMPT_CLOSE),
    REVIEW(OposiBotsAuthOperation.ATTEMPT_REVIEW),
}

/** Lo que las rutas de intento necesitan (SLICE 3). Se construye en el arranque cuando hay resolver de identidad. */
internal class AttemptRuntime(
    val resolver: CanonicalIdentityResolver,
    val attempts: AttemptService,
    val idempotency: IdempotencyStore,
    /** Limite por usuario (JWT sub ya verificado), ANTES de leer el perfil. Lanza 429 RATE_LIMITED. Por defecto no limita (pruebas). */
    val limit: suspend (AttemptOperation, CanonicalUserId) -> Unit = { _, _ -> },
) {
    companion object {
        /** Cableado de produccion: misma credencial de servicio que el catalogo y el almacen real de idempotencia. */
        fun fromEnvironment(
            resolver: CanonicalIdentityResolver,
            catalog: CatalogService,
            limiter: OposiBotsAuthRateLimiter?,
            environment: (String) -> String? = System::getenv,
        ): AttemptRuntime? {
            val upstream = TransportAttemptUpstream.fromEnvironment(environment) ?: return null
            val store = PostgrestIdempotencyStore.fromEnvironment(environment) ?: return null
            return AttemptRuntime(
                resolver = resolver,
                attempts = AttemptService(AttemptGateway(upstream), catalog),
                idempotency = store,
                limit = { operation, userId ->
                    if (limiter != null) {
                        val decision = limiter.checkSubject(operation.limit, userId.value)
                        if (!decision.allowed) {
                            throw CanonicalErrorCode.RATE_LIMITED.exception(retryAfterSeconds = decision.retryAfterSeconds.coerceIn(1, 3_600).toInt())
                        }
                    }
                },
            )
        }
    }
}

/**
 * Runtime de examen del consumidor (SLICE 3, `openapi/attempts/attempts.yaml`, CONGELADO):
 *
 *  - D2-03 `POST /api/v1/app/examenes/{examId}/intentos`                 abrir o reanudar (`Idempotency-Key` obligatoria)
 *  - D2-07 `GET  /api/v1/app/intentos/{attemptId}`                       estado + grid
 *  - D2-08 `GET  /api/v1/app/intentos/{attemptId}/preguntas`             preguntas congeladas, SIN soluciones
 *  - D2-09 `PUT  /api/v1/app/intentos/{attemptId}/respuestas/{preguntaId}` responder o saltar (`Idempotency-Key` obligatoria)
 *  - D2-10 `POST /api/v1/app/intentos/{attemptId}/finalizar`             finalizar (`Idempotency-Key` opcional; idempotente por estado)
 *  - D2-11 `POST /api/v1/app/intentos/{attemptId}/abandonar`             abandonar (idempotente por estado)
 *  - D2-12 `GET  /api/v1/app/intentos/{attemptId}/resultado`             resultado (solo finalizado)
 *  - D2-65 `GET  /api/v1/app/intentos/{attemptId}/revision`              revision (solo finalizado)
 *
 * Orden fijo en TODAS: (1) identidad = SOLO el `sub` del JWT, deny-by-default como `/api/v1/app/me*`
 * ([CanonicalIdentityResolver.activeIdentity]: 401 / 403 ACCOUNT_BLOCKED / ACCOUNT_DELETION_PENDING / PROFILE_MISSING), con el
 * limite por usuario antes de leer el perfil; (2) ids de ruta (400 VALIDATION); (3) solo entonces la `Idempotency-Key` y
 * el cuerpo. Un intento ajeno o inexistente es 404 ATTEMPT_NOT_FOUND (nunca revela que existe): el filtro por dueno va en la
 * lectura y dentro de cada RPC. Sin runtime las rutas existen pero fallan cerrado con 500.
 *
 * D2-10 sustituye al antiguo `POST /intentos/{id}/finalizar` de [appV1Routes] (misma URL): el cierre del intento es ahora
 * UNA RPC atomica y la respuesta es el `AttemptResult` congelado.
 */
internal fun Route.attemptRoutes(runtime: AttemptRuntime?) {
    val store: IdempotencyStore = runtime?.idempotency ?: MisconfiguredIdempotencyStore
    route("/api/v1/app") {
        post("/examenes/{examId}/intentos") {
            val rt = runtime.orMisconfigured()
            val identity = call.gatedIdentity()
            val request = call.receiveCanonicalCommand<AttemptStartRequest>()
            val started = rt.attempts.start(identity.userId, call.pathUuid("examId"), request)
            if (started.created) {
                call.response.header(HttpHeaders.Location, "/api/v1/app/intentos/${started.response.intento.id}")
                call.respond(HttpStatusCode.Created, started.response)
            } else {
                call.respond(started.response)
            }
        }.install(CanonicalIdempotency) {
            routeTemplate = "/api/v1/app/examenes/{examId}/intentos"
            required = true
            this.store = store
            before = { call -> call.attemptGate(runtime, AttemptOperation.START, "examId") }
            actor = { call -> call.gatedIdentity().userId.value }
        }

        route("/intentos/{attemptId}") {
            get {
                val rt = runtime.orMisconfigured()
                val identity = call.attemptGate(runtime, null, "attemptId")
                call.respond(rt.attempts.get(identity.userId, call.pathUuid("attemptId")))
            }
            get("/preguntas") {
                val rt = runtime.orMisconfigured()
                val identity = call.attemptGate(runtime, null, "attemptId")
                val page = parseAttemptPage(
                    call.request.queryParameters,
                    AttemptService.QUESTIONS_DEFAULT_LIMIT,
                    AttemptService.QUESTIONS_MAX_LIMIT,
                    "preguntas",
                )
                call.respond(rt.attempts.questions(identity.userId, call.pathUuid("attemptId"), page))
            }
            put("/respuestas/{preguntaId}") {
                val rt = runtime.orMisconfigured()
                val identity = call.gatedIdentity()
                // `opcionSeleccionadaId` es nullable en el contrato: `null` explicito = ausente = saltar.
                val request = call.receiveCanonicalCommand<AnswerRequest>(nullableFields = setOf("opcionSeleccionadaId"))
                call.respond(rt.attempts.answer(identity.userId, call.pathUuid("attemptId"), call.pathUuid("preguntaId"), request))
            }.install(CanonicalIdempotency) {
                routeTemplate = "/api/v1/app/intentos/{attemptId}/respuestas/{preguntaId}"
                required = true
                this.store = store
                before = { call -> call.attemptGate(runtime, AttemptOperation.ANSWER, "attemptId", "preguntaId") }
                actor = { call -> call.gatedIdentity().userId.value }
            }
            post("/finalizar") {
                val rt = runtime.orMisconfigured()
                val identity = call.gatedIdentity()
                call.receiveCanonicalCommand<AttemptFinishRequest>()
                call.respond(rt.attempts.finish(identity.userId, call.pathUuid("attemptId")))
            }.install(CanonicalIdempotency) {
                routeTemplate = "/api/v1/app/intentos/{attemptId}/finalizar"
                required = false
                this.store = store
                before = { call -> call.attemptGate(runtime, AttemptOperation.CLOSE, "attemptId") }
                actor = { call -> call.gatedIdentity().userId.value }
            }
            post("/abandonar") {
                val rt = runtime.orMisconfigured()
                val identity = call.attemptGate(runtime, AttemptOperation.CLOSE, "attemptId")
                val request = call.receiveCanonicalCommand<AbandonRequest>()
                rt.attempts.abandon(identity.userId, call.pathUuid("attemptId"), request)
                call.respond(HttpStatusCode.NoContent)
            }
            get("/resultado") {
                val rt = runtime.orMisconfigured()
                val identity = call.attemptGate(runtime, null, "attemptId", cacheControl = RESULT_CACHE)
                call.respond(rt.attempts.result(identity.userId, call.pathUuid("attemptId")))
            }
            get("/revision") {
                val rt = runtime.orMisconfigured()
                val identity = call.attemptGate(runtime, AttemptOperation.REVIEW, "attemptId")
                val page = parseAttemptPage(
                    call.request.queryParameters,
                    CursorPageRequest.APP_DEFAULT_LIMIT,
                    CursorPageRequest.APP_MAX_LIMIT,
                    "revision",
                )
                call.respond(rt.attempts.review(identity.userId, call.pathUuid("attemptId"), page))
            }
        }
    }
}

/** `Cache-Control` exacto que fija el contrato para las respuestas de intento (cabecera `NoStore`, enum `no-store`). */
private const val NO_STORE = "no-store"

/** D2-12: el resultado es inmutable y solo del dueno; cache privada corta (los bloques hoy `null` pueden rellenarse). */
private const val RESULT_CACHE = "private, max-age=60"

private val AttemptIdentityKey = AttributeKey<CanonicalUserIdentity>("oc-attempt-identity")

/**
 * La puerta comun: cabecera de cache, identidad ACTIVA (con el limite por usuario antes de leer el perfil) y forma de los
 * ids de ruta. Idempotente dentro de la llamada: el plugin de idempotencia la ejecuta antes de mirar la clave y el handler
 * reutiliza la identidad ya resuelta con [gatedIdentity].
 */
private suspend fun ApplicationCall.attemptGate(
    runtime: AttemptRuntime?,
    operation: AttemptOperation?,
    vararg pathIds: String,
    cacheControl: String = NO_STORE,
): CanonicalUserIdentity {
    attributes.getOrNull(AttemptIdentityKey)?.let { return it }
    val rt = runtime.orMisconfigured()
    response.header(HttpHeaders.CacheControl, cacheControl)
    val identity = rt.resolver.activeIdentity(this) { userId -> operation?.let { rt.limit(it, userId) } }
    pathIds.forEach { pathUuid(it) }
    attributes.put(AttemptIdentityKey, identity)
    return identity
}

private fun ApplicationCall.gatedIdentity(): CanonicalUserIdentity =
    attributes.getOrNull(AttemptIdentityKey) ?: throw CanonicalErrorCode.INTERNAL.exception()

/** Id de ruta en forma canonica (8-4-4-4-12), en minusculas. Otra cosa: 400 VALIDATION INVALID_FORMAT. */
private fun ApplicationCall.pathUuid(name: String): String {
    val value = parameters[name].orEmpty().trim()
    if (!value.isUuid()) throw validationError(ErrorIssue(name, CanonicalIssue.INVALID_FORMAT))
    return value.lowercase()
}

private fun AttemptRuntime?.orMisconfigured(): AttemptRuntime =
    this ?: throw CanonicalErrorCode.INTERNAL.exception()

/** Sin runtime no hay almacen: la puerta ya responde 500 antes; esto solo evita un `lateinit` sin inicializar. */
private object MisconfiguredIdempotencyStore : IdempotencyStore {
    override suspend fun begin(request: IdempotencyBeginRequest): IdempotencyBeginResult = throw CanonicalErrorCode.INTERNAL.exception()

    override suspend fun complete(claim: IdempotencyClaim, response: StoredIdempotentResponse) = Unit

    override suspend fun release(claim: IdempotencyClaim) = Unit
}
