package com.oposicontrol.backend

import io.ktor.http.Parameters
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

// =====================================================================
// Servicio del runtime de examen (SLICE 3): D2-03, D2-07, D2-08, D2-09, D2-10, D2-11, D2-12, D2-65.
// Reglas aqui; la ruta solo hace HTTP y la pasarela solo lee o llama a la RPC atomica.
//
// Cable: `openapi/attempts/attempts.yaml` (CONGELADO). Decisiones de producto ABIERTAS -> default tecnico documentado
// y campos de solo-valor a `null` (nunca una regla inventada):
//   DEC-013  nota = correctas / evaluables * 10, redondeo half-up a 2 decimales; las anuladas no son evaluables.
//   DEC-015  DECIDIDA (dueno, 2026-10-13): la correccion se revela AL RESPONDER, en los tres tipos de examen
//            ([revealsOnAnswer]). D2-09 devuelve `esCorrecta` / `opcionCorrectaId`; al releer el intento (reanudar) el grid
//            de D2-07 dice `correcta` / `incorrecta` y la `respuesta` de D2-08 lleva los dos campos. Sale SOLO de la fila
//            propia del usuario en `respuestas_intento`; `soluciones_pregunta` sigue sin leerse con el intento abierto
//            (SEC-004). Un salto no revela nada y se puede responder una vez despues; una anulada no revela nada.
//            `correctas` / `falladas` del intento siguen `null` hasta finalizar. Una respuesta ya dada NO se cambia
//            (409 ANSWER_LOCKED).
//   DEC-014  sin pausa. Sin limite de tiempo: `deadline` = null y `motivoFin` siempre USER (TIMEOUT / ATTEMPT_EXPIRED no
//            se emiten: si la duracion del examen corta el intento es una pregunta abierta del motor).
//   DEC-004/016/019  `comparativa`, `probabilidadPlaza`, `pctAciertoGlobal` = null.
//   DEC-025          D2-65 `explanation` = null; D2-12 `razonamiento` = null (no se leen de la base; politica unica sin cambios).
//   VERSIONADO P0    (dueno 2026-10-14, 20261022100000) el intento queda ligado a una revision INMUTABLE de cada pregunta:
//                    D2-08 / D2-12 / D2-65 leen la revision; la correccion de D2-09 sale de ella. Intento legacy sin
//                    revision: contenido vivo.
//   RECURSOS V1      (dueno 2026-10-14) imagen / audio (video extensible): metadatos en D2-08 `recursos` y D2-65
//                    `resources`; bytes via `GET /intentos/{id}/recursos/{recursoId}` (URL firmada corta).
//   DEC-018  `resumen.modo` = tipo canonico del examen del intento.
//   DEMO     `modo: DEMO` no abre nada que la politica no permita (semantica abierta): se ignora como modo.
// =====================================================================

// ---------- cable (peticiones) ----------

/** D2-03. El cuerpo es opcional; `modo` solo admite los dos valores del contrato. */
@Serializable
@CanonicalCommand
internal data class AttemptStartRequest(val modo: String? = null)

/** D2-09. `opcionSeleccionadaId` ausente o `null` = saltar. Nada de lo que decide el servidor viaja aqui. */
@Serializable
@CanonicalCommand
internal data class AnswerRequest(
    val opcionSeleccionadaId: String? = null,
    val tiempoRespuestaMs: Int,
    val nivelConfianza: Int? = null,
    val baseRevision: Int,
)

/** D2-10: cuerpo vacio (cualquier campo es 400 VALIDATION UNKNOWN_FIELD). */
@Serializable
@CanonicalCommand
internal class AttemptFinishRequest

/** D2-11. El tiempo transcurrido lo calcula la base; `inactividad` es un motivo solo de servidor (DEC-014). */
@Serializable
@CanonicalCommand
internal data class AbandonRequest(val posicionPregunta: Int, val motivo: String)

// ---------- cable (respuestas) ----------

@Serializable
internal data class AttemptDto(
    val id: String,
    val origen: String,
    val examenId: String? = null,
    val especialidadId: String? = null,
    val tipoExamen: String,
    val estado: String,
    val motivoFin: String? = null,
    val iniciadoEn: String,
    val deadline: String? = null,
    val serverNow: String,
    val totalPreguntas: Int,
    val respondidas: Int,
    val correctas: Int? = null,
    val falladas: Int? = null,
    val sinResponder: Int,
    val ultimaPreguntaPosicion: Int? = null,
    /** FA28: true en un intento DEMO (solo las preguntas marcadas); ausente = false. */
    val esDemo: Boolean? = null,
)

@Serializable
internal data class AttemptGridItem(
    val posicion: Int,
    val preguntaId: String,
    val revision: Int,
    val estado: String,
    /** FA31: solo `RESERVA` (ausente = PRINCIPAL). */
    val rol: String? = null,
    val sustituyeA: Int? = null,
)

@Serializable
internal data class AttemptView(val intento: AttemptDto, val grid: List<AttemptGridItem>)

@Serializable
internal data class AttemptStartResponse(val intento: AttemptDto, val reanudado: Boolean)

@Serializable
internal data class QuestionOptionDto(val id: String, val letra: String, val texto: String)

@Serializable
internal data class AttemptQuestionAnswer(
    val opcionId: String? = null,
    val revision: Int,
    val respondidaEn: String,
    /** DEC-015: solo con opcion marcada y pregunta no anulada; ausentes en un salto. */
    val esCorrecta: Boolean? = null,
    val opcionCorrectaId: String? = null,
)

@Serializable
internal data class AttemptQuestionDto(
    val posicion: Int,
    val preguntaId: String,
    val codigoPregunta: String,
    val tipoPregunta: String,
    val titulo: String,
    val anulada: Boolean,
    /** FA31: solo `RESERVA` (ausente = PRINCIPAL); `sustituyeA` = posicion de la anulada a la que sustituye. */
    val rol: String? = null,
    val sustituyeA: Int? = null,
    val opciones: List<QuestionOptionDto>,
    val respuesta: AttemptQuestionAnswer? = null,
    /** Aditivo (EX14): recursos V1 de la revision ligada al intento. Nunca la solucion ni la explicacion. */
    val recursos: List<AttemptResourceDto> = emptyList(),
)

@Serializable
internal data class AttemptQuestionPage(val items: List<AttemptQuestionDto>, val nextCursor: String? = null)

@Serializable
internal data class AnswerResult(
    val revision: Int,
    val estado: String,
    /** DEC-015: la correccion de la respuesta recien dada (`estado` ANSWERED). `null` en un salto y en una anulada. */
    val esCorrecta: Boolean? = null,
    /** Puede faltar con `esCorrecta` presente (la opcion correcta copiada en la fila es `ON DELETE SET NULL`). */
    val opcionCorrectaId: String? = null,
    val esPrimeraRespuesta: Boolean,
    val respondidas: Int,
)

@Serializable
internal data class ResultSummary(
    val examenId: String? = null,
    val especialidadId: String? = null,
    val modo: String,
    /** FA28: true cuando el resultado es de un intento DEMO. */
    val esDemo: Boolean? = null,
    /** FA31: `JsonNull` explicito cuando no hubo evaluables (el contrato lo exige presente). */
    val nota: kotlinx.serialization.json.JsonElement,
    val calificacion: String,
    val duracionSegundos: Int,
    val correctas: Int,
    val falladas: Int,
    val sinResponder: Int,
    val evaluables: Int? = null,
    val anuladas: Int? = null,
    val reservasUsadas: Int? = null,
    val anuladasSinReserva: Int? = null,
    val reglaAnuladas: String? = null,
    val penalizacion: Penalizacion? = null,
)

@Serializable
internal data class Penalizacion(val numerador: Int, val denominador: Int)

@Serializable
internal data class ResultQuestion(
    val posicion: Int,
    /** FA31: solo en intentos con la regla congelada. */
    val estado: String? = null,
    val rol: String? = null,
    val sustituyeA: Int? = null,
    val opcionElegida: String? = null,
    val opcionCorrecta: String? = null,
    /** DEC-025 / DEC-040 abiertas: `null`. */
    val razonamiento: String? = null,
    val tiempoSeg: Int,
    /** DEC-019 / DEC-004 abiertas: `null`. */
    val pctAciertoGlobal: Double? = null,
)

@Serializable
internal data class ResultTag(
    val etiquetaId: String,
    val nombre: String,
    val correctas: Int,
    val falladas: Int,
    val sinResponder: Int,
    val tiempoTotalSeg: Int,
)

/** DEC-016 abierta: nunca se construye; existe para que el cable tenga su forma. */
@Serializable
internal data class PlacementProbability(val porcentaje: Double, val totalComparables: Int)

@Serializable
internal data class AttemptResult(
    val resumen: ResultSummary,
    val preguntas: List<ResultQuestion>,
    val porEtiqueta: List<ResultTag>,
    /** DEC-019 / DEC-004 abiertas: `null`. */
    val comparativa: kotlinx.serialization.json.JsonObject? = null,
    /** DEC-016 abierta: `null`. */
    val probabilidadPlaza: PlacementProbability? = null,
)

@Serializable
internal data class ReviewAttemptDto(
    val id: String,
    val examenId: String? = null,
    val especialidadId: String? = null,
    val origen: String,
    val tipoExamen: String,
    val estado: String,
    val motivoFin: String,
    val iniciadoEn: String,
    val finalizadoEn: String,
    val duracionSegundos: Int,
)

@Serializable
internal data class ReviewFirstAnswer(val opcionId: String? = null, val esCorrecta: Boolean? = null, val respondidaEn: String)

@Serializable
internal data class ReviewFinalAnswer(val opcionId: String? = null, val esCorrecta: Boolean? = null, val actualizadaEn: String, val revision: Int)

@Serializable
internal data class ReviewCorrectAnswer(val opcionId: String, val letra: String)

@Serializable
internal data class ReviewExplanation(val razonamiento: String)

/**
 * Recurso V1 (imagen, audio, video) tal como quedo en la revision del intento (EX14). Solo metadatos: los bytes los sirve
 * Storage tras pedir acceso a `GET /intentos/{id}/recursos/{recursoId}` (URL firmada, temporal, nunca persistida).
 * Contrato canonico: resourceId = [id], type = [tipo], [mimeType], version = [version], [durationMs], access = la ruta.
 */
@Serializable
internal data class AttemptResourceDto(
    val id: String,
    val tipo: String,
    val titulo: String,
    val mimeType: String? = null,
    /** Token estable del objeto (los bytes de un `recursoId` no se reescriben: la subida es idempotente por id). Clave de cache. */
    val version: String,
    val sizeBytes: Long? = null,
    /** `null` hasta que la ingesta (autoria protegida) lo calcule: PROTECTED_FILE_REQUIRED. */
    val durationMs: Long? = null,
    /** Solo audio. `null` hasta que la ingesta lo calcule una vez (EX14, [AudioEnvelope]). */
    val envelope: AudioEnvelopeDto? = null,
)

/** Envolvente de audio compacta: una muestra UInt8 (0..255, normalizada al pico del fichero) cada [stepMs] ms, en base64. */
@Serializable
internal data class AudioEnvelopeDto(val stepMs: Int, val samples: String)

/** Acceso temporal a los bytes de un recurso. `url` caduca en [expiresAt]; se pide otra al caducar. */
@Serializable
internal data class AttemptResourceAccess(val url: String, val expiresAt: String, val mimeType: String? = null, val version: String)

@Serializable
internal data class ReviewTimings(val tiempoRespuestaMs: Int? = null, val respondidaEn: String? = null, val actualizadaEn: String? = null)

@Serializable
internal data class ReviewTag(val id: String, val nombre: String)

@Serializable
internal data class ReviewItem(
    val posicion: Int,
    val preguntaId: String,
    val codigoPregunta: String,
    val tipoPregunta: String,
    val enunciado: String,
    val opciones: List<QuestionOptionDto>,
    val status: String,
    /** FA31: solo `RESERVA` (ausente = PRINCIPAL). */
    val rol: String? = null,
    val sustituyeA: Int? = null,
    val firstAnswer: ReviewFirstAnswer? = null,
    val finalAnswer: ReviewFinalAnswer? = null,
    val cambioRespuesta: Boolean,
    val esPrimeraRespuesta: Boolean,
    val correctAnswer: ReviewCorrectAnswer? = null,
    /** DEC-025 / DEC-040 abiertas: `null`. */
    val explanation: ReviewExplanation? = null,
    /** Recursos V1 de la revision del intento (decision del dueno 2026-10-14); `[]` si no tiene o el intento es legacy. */
    val resources: List<AttemptResourceDto>,
    val timings: ReviewTimings,
    val etiquetas: List<ReviewTag>,
)

@Serializable
internal data class AttemptReviewPage(val attempt: ReviewAttemptDto, val items: List<ReviewItem>, val nextCursor: String? = null)

// ---------- peticiones ya validadas ----------

/** `cursor` + `limit` de D2-08 (1..20, por defecto 10) o D2-65 (1..50, por defecto 20). Keyset = posicion. */
internal fun parseAttemptPage(params: Parameters, default: Int, max: Int, list: String): CursorPageRequest =
    CursorPageRequest.of(
        cursor = params["cursor"],
        limit = params["limit"],
        default = default,
        max = max,
        scope = CursorScope(sort = listOf("posicion asc"), filters = mapOf("lista" to list)),
    )

/** La clave del cursor de posicion: UN entero >= 1. Cualquier otra forma es 400 INVALID_CURSOR. */
internal fun positionAfter(keys: List<JsonElement>?): Int {
    if (keys == null) return 0
    val position = (keys.singleOrNull() as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    if (position == null || position < 1) throw CanonicalErrorCode.INVALID_CURSOR.exception()
    return position
}

internal data class StartedAttempt(val response: AttemptStartResponse, val created: Boolean)

/** Una pregunta del intento ya normalizada (revision inmutable o, legacy, contenido vivo). */
private data class QuestionView(
    val codigo: String,
    val tipo: String,
    val titulo: String,
    val opciones: List<QuestionOptionDto>,
    val recursos: List<AttemptResourceDto>,
    val etiquetas: List<ReviewTag>,
    val correctId: String?,
)

// ---------- servicio ----------

internal class AttemptService(
    private val gateway: AttemptGateway,
    private val catalog: CatalogService,
    private val clock: () -> Instant = Instant::now,
    private val metrics: io.micrometer.core.instrument.MeterRegistry = BackendPrometheusMetrics.registry,
) {
    // ----- D2-03 -----
    suspend fun start(userId: CanonicalUserId, examId: String, request: AttemptStartRequest): StartedAttempt = withUpstreamIo {
        if (request.modo != null && request.modo !in START_MODES) throw validationError(ErrorIssue("modo", CanonicalIssue.INVALID_FORMAT))
        val now = clock()
        val uid = userId.value
        // D7-25: la UNICA politica de acceso. Borrador, archivado e inexistente son el mismo 404.
        val access = catalog.startAccess(uid, examId, now) ?: throw CanonicalErrorCode.EXAM_NOT_FOUND.exception()
        val wantsDemo = request.modo == "DEMO" && access.verdict.state == AccessState.DEMO
        if (!access.verdict.allowed && !wantsDemo) {
            throw when (access.verdict.denyReason) {
                AccessDenyReason.CAPABILITY_DISABLED -> CanonicalErrorCode.SPECIALTY_TYPE_NOT_ALLOWED.exception()
                AccessDenyReason.SPECIALTY_MISMATCH -> CanonicalErrorCode.EXAM_NOT_FOUND.exception()
                else -> CanonicalErrorCode.EXAM_LOCKED.exception(details = ErrorDetails(accessState = access.verdict.state.name))
            }
        }
        val outcome = gateway.start(uid, examId, demo = wantsDemo)
        val created = when (outcome.outcome) {
            "CREATED" -> true
            "RESUMED" -> false
            "DEMO_LIMIT" -> throw CanonicalErrorCode.RATE_LIMITED.exception(retryAfterSeconds = 3_600)
            "EXAM_LOCKED" -> throw CanonicalErrorCode.EXAM_LOCKED.exception(details = ErrorDetails(accessState = outcome.state))
            "SPECIALTY_TYPE_NOT_ALLOWED" -> throw CanonicalErrorCode.SPECIALTY_TYPE_NOT_ALLOWED.exception()
            "EXAM_NOT_FOUND" -> throw CanonicalErrorCode.EXAM_NOT_FOUND.exception()
            "EXAM_EMPTY" -> throw CanonicalErrorCode.EXAM_EMPTY.exception()
            // El contrato congelado no define un codigo propio para "mas de 200 preguntas servibles" (x-error-codes de D2-03:
            // ... EXAM_NOT_FOUND, EXAM_EMPTY ...). Es el mismo caso de negocio que EXAM_EMPTY: el examen, tal como esta
            // publicado, no tiene un conjunto de preguntas que se pueda servir (409, no lo arregla un reintento), y no un
            // fallo del servicio de datos (antes caia en 502 UPSTREAM_CONTRACT_ERROR).
            "EXAM_TOO_LARGE" -> throw CanonicalErrorCode.EXAM_EMPTY.exception(EXAM_TOO_LARGE_MESSAGE)
            else -> throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        }
        val attemptId = outcome.intentoId?.takeIf { it.isUuid() } ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        val row = gateway.attempt(uid, attemptId) ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        StartedAttempt(AttemptStartResponse(intento = row.toDto(now, openUnanswered = null), reanudado = !created), created)
    }

    // ----- D2-07 -----
    suspend fun get(userId: CanonicalUserId, attemptId: String): AttemptView = withUpstreamIo {
        val now = clock()
        val row = ownedAttempt(userId, attemptId)
        val finished = row.estado == FINISHED
        val frozen = bounded(gateway.frozenSet(row.id, open = !finished))
        val answers = gateway.answers(row.id, graded = finished)
        val annulled = if (finished || frozen.none { it.rol == null }) emptySet() else row.examenId?.let(gateway::annulledNow).orEmpty()
        val reveals = !finished && revealsOnAnswer(row.kind())
        val grid = frozen.map { question ->
            val answer = answers[question.preguntaId]
            AttemptGridItem(
                posicion = question.posicion,
                preguntaId = question.preguntaId,
                revision = answer?.revision ?: 0,
                estado = if (finished) gridStateFinished(question.estadoFinal, answer) else gridStateOpen(question.isAnnulled(annulled), answer, reveals),
                rol = question.wireRole(),
                sustituyeA = question.sustituyeAPosicion,
            )
        }
        val unanswered = if (finished) null else grid.count { it.estado == GRID_UNANSWERED }
        AttemptView(intento = row.toDto(now, openUnanswered = unanswered), grid = grid)
    }

    // ----- D2-08 -----
    suspend fun questions(userId: CanonicalUserId, attemptId: String, page: CursorPageRequest): AttemptQuestionPage = withUpstreamIo {
        val row = ownedAttempt(userId, attemptId)
        val rows = gateway.questionPage(row.id, positionAfter(page.after), page.fetchLimit, open = row.estado != FINISHED)
        val cut = page.page(rows) { listOf(JsonPrimitive(it.posicion)) }
        // Limitador diario (C-L1): lo que la pagina va a REVELAR se registra antes de servirla; un intento finalizado ya se vio.
        if (row.estado != FINISHED && cut.items.isNotEmpty()) {
            val reveal = gateway.reveal(userId.value, row.id, cut.items.map { it.preguntaId })
            when (reveal.outcome) {
                "OK" -> Unit
                "DAILY_LIMIT_REACHED" -> throw CanonicalErrorCode.DAILY_LIMIT_REACHED.exception(retryAfterSeconds = (reveal.retryAfterSeconds ?: 3_600).coerceIn(1, 86_400))
                "ATTEMPT_NOT_FOUND" -> throw CanonicalErrorCode.ATTEMPT_NOT_FOUND.exception()
                else -> throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
            }
        }
        // DEC-015: la correccion de lo YA respondido, igual con el intento abierto que finalizado, y solo de la fila propia.
        // Sin soluciones, explicaciones ni recursos (eso es D2-12 / D2-65).
        val reveals = row.estado == FINISHED || revealsOnAnswer(row.kind())
        val answers = gateway.answers(row.id, graded = false, questionIds = cut.items.map { it.preguntaId })
        // Con el intento finalizado manda el estado congelado; abierto, la anulacion vigente del examen.
        val annulled = if (row.estado == FINISHED || cut.items.none { it.rol == null }) emptySet() else row.examenId?.let(gateway::annulledNow).orEmpty()
        AttemptQuestionPage(
            items = cut.items.map { question ->
                val content = question.view()
                val answer = answers[question.preguntaId]
                val isAnnulled = question.estadoFinal == STATUS_ANNULLED || question.isAnnulled(annulled)
                AttemptQuestionDto(
                    posicion = question.posicion,
                    preguntaId = question.preguntaId,
                    codigoPregunta = content.codigo,
                    tipoPregunta = content.tipo,
                    titulo = content.titulo,
                    anulada = isAnnulled,
                    rol = question.wireRole(),
                    sustituyeA = question.sustituyeAPosicion,
                    opciones = content.opciones,
                    recursos = content.recursos,
                    respuesta = answer?.let {
                        val revealed = it.takeIf { _ -> reveals && !isAnnulled && it.opcionSeleccionadaId != null }
                        AttemptQuestionAnswer(
                            opcionId = it.opcionSeleccionadaId,
                            revision = it.revision,
                            respondidaEn = instant(it.respondidaEn ?: it.creadaEn),
                            esCorrecta = revealed?.esCorrecta,
                            opcionCorrectaId = revealed?.opcionCorrectaId,
                        )
                    },
                )
            },
            nextCursor = cut.nextCursor,
        )
    }

    // ----- D2-09 -----
    suspend fun answer(userId: CanonicalUserId, attemptId: String, questionId: String, request: AnswerRequest): AnswerResult = withUpstreamIo {
        val issues = buildList {
            if (request.opcionSeleccionadaId != null && !request.opcionSeleccionadaId.isUuid()) add(ErrorIssue("opcionSeleccionadaId", CanonicalIssue.INVALID_FORMAT))
            if (request.tiempoRespuestaMs < 0) add(ErrorIssue("tiempoRespuestaMs", CanonicalIssue.OUT_OF_RANGE))
            if (request.baseRevision < 0) add(ErrorIssue("baseRevision", CanonicalIssue.OUT_OF_RANGE))
        }
        if (issues.isNotEmpty()) throw validationError(*issues.toTypedArray())
        val outcome = gateway.answer(
            userId = userId.value,
            attemptId = attemptId,
            questionId = questionId,
            optionId = request.opcionSeleccionadaId?.lowercase(),
            timeMs = request.tiempoRespuestaMs,
            baseRevision = request.baseRevision,
            confidence = request.nivelConfianza,
        )
        when (outcome.outcome) {
            "OK" -> {
                val revision = outcome.revision ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
                val estado = outcome.estado?.takeIf { it in ANSWER_STATES } ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
                val respondidas = outcome.respondidas ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
                // DEC-015: solo una respuesta (primera vez o la misma opcion reenviada, que la RPC devuelve como no-op) revela;
                // un salto y una anulada, nada. Desde 20261022100000 la RPC devuelve la correccion de la fila que acaba de
                // escribir (corregida con la REVISION del intento): una sola llamada. Una base sin esa migracion no la
                // devuelve y se lee la fila (segunda llamada); si esa lectura falla, falla la llamada (el reintento con la
                // misma clave cae en el no-op de la RPC y revela).
                val reveals = estado == ANSWER_ANSWERED && attemptRevealsOnAnswer(userId, attemptId)
                val esCorrecta: Boolean?
                val opcionCorrectaId: String?
                if (reveals && outcome.esCorrecta != null) {
                    esCorrecta = outcome.esCorrecta
                    opcionCorrectaId = outcome.opcionCorrectaId?.takeIf { it.isUuid() }
                } else if (reveals) {
                    val row = revealedAnswer(userId, attemptId, questionId)
                    esCorrecta = row.esCorrecta
                    opcionCorrectaId = row.opcionCorrectaId
                } else {
                    esCorrecta = null
                    opcionCorrectaId = null
                }
                AnswerResult(
                    revision = revision,
                    estado = estado,
                    esCorrecta = esCorrecta,
                    opcionCorrectaId = opcionCorrectaId,
                    esPrimeraRespuesta = outcome.esPrimeraRespuesta ?: false,
                    respondidas = respondidas,
                )
            }
            "ATTEMPT_NOT_FOUND" -> throw attemptNotOwned()
            "ATTEMPT_NOT_IN_PROGRESS" -> throw CanonicalErrorCode.ATTEMPT_NOT_IN_PROGRESS.exception()
            "QUESTION_NOT_IN_ATTEMPT" -> throw CanonicalErrorCode.QUESTION_NOT_IN_ATTEMPT.exception()
            "OPTION_NOT_IN_QUESTION" -> throw CanonicalErrorCode.OPTION_NOT_IN_QUESTION.exception()
            "ANSWER_LOCKED" -> throw CanonicalErrorCode.ANSWER_LOCKED.exception()
            "REVISION_CONFLICT" -> throw CanonicalErrorCode.REVISION_CONFLICT.exception(
                details = ErrorDetails(currentRevision = (outcome.currentRevision ?: 0).toLong()),
            )
            else -> throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        }
    }

    // ----- D2-10 -----
    suspend fun finish(userId: CanonicalUserId, attemptId: String): AttemptResult = withUpstreamIo {
        when (gateway.finish(userId.value, attemptId).outcome) {
            "OK" -> Unit
            "ATTEMPT_NOT_FOUND" -> throw attemptNotOwned()
            "ATTEMPT_ABANDONED" -> throw CanonicalErrorCode.ATTEMPT_ABANDONED.exception()
            else -> throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        }
        resultOf(gateway.attempt(userId.value, attemptId) ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception())
    }

    // ----- D2-11 -----
    suspend fun abandon(userId: CanonicalUserId, attemptId: String, request: AbandonRequest): Unit = withUpstreamIo {
        val issues = buildList {
            if (request.posicionPregunta < 1) add(ErrorIssue("posicionPregunta", CanonicalIssue.OUT_OF_RANGE))
            if (request.motivo !in ABANDON_REASONS) add(ErrorIssue("motivo", CanonicalIssue.INVALID_FORMAT))
        }
        if (issues.isNotEmpty()) throw validationError(*issues.toTypedArray())
        when (gateway.abandon(userId.value, attemptId, request.posicionPregunta, request.motivo).outcome) {
            "OK" -> Unit
            "ATTEMPT_NOT_FOUND" -> throw attemptNotOwned()
            "ATTEMPT_ALREADY_FINISHED" -> throw CanonicalErrorCode.ATTEMPT_ALREADY_FINISHED.exception()
            "INVALID_POSITION" -> throw validationError(ErrorIssue("posicionPregunta", CanonicalIssue.OUT_OF_RANGE))
            "INVALID_REASON" -> throw validationError(ErrorIssue("motivo", CanonicalIssue.INVALID_FORMAT))
            else -> throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        }
    }

    // ----- D2-12 -----
    suspend fun result(userId: CanonicalUserId, attemptId: String): AttemptResult = withUpstreamIo {
        resultOf(ownedAttempt(userId, attemptId))
    }

    // ----- D2-65 -----
    suspend fun review(userId: CanonicalUserId, attemptId: String, page: CursorPageRequest): AttemptReviewPage = withUpstreamIo {
        val row = ownedAttempt(userId, attemptId)
        // Solo finalizado: con el intento abierto o abandonado no se lee ni una solucion.
        if (row.estado != FINISHED) throw CanonicalErrorCode.ATTEMPT_NOT_FINISHED.exception()
        val rows = gateway.reviewPage(row.id, positionAfter(page.after), page.fetchLimit)
        val cut = page.page(rows) { listOf(JsonPrimitive(it.posicion)) }
        val answers = gateway.answers(row.id, graded = true, questionIds = cut.items.map { it.preguntaId })
        AttemptReviewPage(
            attempt = ReviewAttemptDto(
                id = row.id,
                examenId = row.examenId,
                especialidadId = row.especialidadSnapshotId,
                origen = ORIGIN_EXAM,
                tipoExamen = row.kind().wire,
                estado = FINISHED,
                motivoFin = row.endReason(),
                iniciadoEn = instant(row.iniciadoEn),
                finalizadoEn = instant(row.finalizadoEn),
                duracionSegundos = row.durationSeconds(),
            ),
            items = cut.items.map { question -> reviewItem(question, answers[question.preguntaId]) },
            nextCursor = cut.nextCursor,
        )
    }

    // ---------- piezas ----------

    /** Intento ajeno, inexistente o de otro origen: el mismo 404 (nunca revela que existe). */
    private fun ownedAttempt(userId: CanonicalUserId, attemptId: String): AttemptRow =
        gateway.attempt(userId.value, attemptId) ?: throw attemptNotOwned()

    /**
     * D2-09 no lee el intento (dueno, estado y pertenencia los valida la RPC). Mientras [revealsOnAnswer] diga lo mismo
     * para todos los tipos no hace falta su tipo (dos llamadas: RPC + la fila); el dia que un tipo deje de revelar, se lee
     * aqui el intento del usuario (una tercera).
     */
    private fun attemptRevealsOnAnswer(userId: CanonicalUserId, attemptId: String): Boolean =
        ExamKind.entries.all { revealsOnAnswer(it) } || revealsOnAnswer(ownedAttempt(userId, attemptId).kind())

    /**
     * La fila que la RPC acaba de escribir (o ya tenia, en el no-op), leida SOLO de `respuestas_intento` del intento que la
     * RPC ya valido como del usuario, y ADEMAS filtrada por `usuario_id` (defensa en profundidad: esta lectura no depende
     * solo de esa validacion). Que no este, o que no lleve opcion o correccion, contradice el ANSWERED de la RPC.
     */
    private fun revealedAnswer(userId: CanonicalUserId, attemptId: String, questionId: String): AttemptAnswerRow =
        gateway.answers(attemptId, graded = false, questionIds = listOf(questionId), ownerId = userId.value)[questionId]
            ?.takeIf { it.opcionSeleccionadaId != null && it.esCorrecta != null }
            ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()

    private fun resultOf(row: AttemptRow): AttemptResult {
        if (row.estado != FINISHED) throw CanonicalErrorCode.ATTEMPT_NOT_FINISHED.exception()
        val frozen = bounded(gateway.resultQuestions(row.id))
        val answers = gateway.answers(row.id, graded = true)
        val tags = gateway.tagSummary(row.id)
        if (tags.size > AttemptGateway.MAX_ATTEMPT_QUESTIONS) throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        val evaluable = row.totalCorrectas + row.totalFalladas + row.totalSinResponder
        // FA31: un intento con la regla congelada trae la nota calculada por la base (una sola implementacion); los anteriores, la formula DEC-013.
        val frozenRules = row.reglaAnuladas != null
        val nota: BigDecimal? = if (frozenRules) row.nota?.let { BigDecimal.valueOf(it).setScale(2, RoundingMode.HALF_UP) } else gradeOf(row.totalCorrectas, evaluable)
        return AttemptResult(
            resumen = ResultSummary(
                examenId = row.examenId,
                especialidadId = row.especialidadSnapshotId,
                modo = row.kind().wire,
                esDemo = row.esDemo.takeIf { it },
                nota = nota?.let { kotlinx.serialization.json.JsonPrimitive(it.toDouble()) } ?: kotlinx.serialization.json.JsonNull,
                calificacion = nota?.let { bandOf(it) } ?: BAND_NO_EVALUABLES,
                duracionSegundos = row.durationSeconds(),
                correctas = row.totalCorrectas,
                falladas = row.totalFalladas,
                sinResponder = row.totalSinResponder,
                evaluables = row.totalEvaluables.takeIf { frozenRules },
                anuladas = row.totalAnuladas.takeIf { frozenRules },
                reservasUsadas = row.reservasUsadas.takeIf { frozenRules },
                anuladasSinReserva = row.anuladasSinReserva.takeIf { frozenRules },
                reglaAnuladas = row.reglaAnuladas,
                penalizacion = if (frozenRules && (row.penalizacionNum ?: 0) > 0) Penalizacion(row.penalizacionNum!!, (row.penalizacionDen ?: 1).coerceAtLeast(1)) else null,
            ),
            preguntas = frozen.map { question ->
                val answer = answers[question.preguntaId]
                val annulled = question.estadoFinal == STATUS_ANNULLED
                ResultQuestion(
                    posicion = question.posicion,
                    estado = question.estadoFinal.takeIf { frozenRules },
                    rol = question.wireRole().takeIf { frozenRules },
                    sustituyeA = question.sustituyeAPosicion.takeIf { frozenRules },
                    opcionElegida = answer?.opcionSeleccionadaId?.takeUnless { annulled || question.estadoFinal == STATUS_UNUSED_RESERVE },
                    opcionCorrecta = question.correctOptionId()?.takeUnless { annulled },
                    razonamiento = null,
                    tiempoSeg = answerSeconds(answer),
                    pctAciertoGlobal = null,
                )
            },
            porEtiqueta = tags.map { tag ->
                ResultTag(
                    etiquetaId = tag.etiquetaId,
                    nombre = tag.etiquetas?.nombre.orContractError(),
                    correctas = tag.correctas,
                    falladas = tag.falladas,
                    sinResponder = tag.sinResponder,
                    tiempoTotalSeg = tag.tiempoTotalSegundos.coerceAtLeast(0),
                )
            },
            comparativa = null,
            probabilidadPlaza = null,
        )
    }

    private fun reviewItem(question: FrozenQuestionRow, answer: AttemptAnswerRow?): ReviewItem {
        val content = question.view()
        val status = question.estadoFinal?.takeIf { it in REVIEW_STATUSES } ?: legacyStatus(answer)
        val annulled = status == STATUS_ANNULLED
        val shown = answer?.takeUnless { annulled || status == STATUS_UNUSED_RESERVE }
        val options = content.opciones
        // Fila anterior a SLICE 3 (`actualizada_en` nulo): no hubo cambio posible, primera = final.
        val legacyRow = shown != null && shown.actualizadaEn == null
        val answeredAt = shown?.let { instant(it.respondidaEn ?: it.creadaEn) }
        return ReviewItem(
            posicion = question.posicion,
            preguntaId = question.preguntaId,
            codigoPregunta = content.codigo,
            tipoPregunta = content.tipo,
            enunciado = content.titulo,
            opciones = options,
            status = status,
            rol = question.wireRole(),
            sustituyeA = question.sustituyeAPosicion,
            firstAnswer = shown?.let {
                ReviewFirstAnswer(
                    opcionId = if (legacyRow) it.opcionSeleccionadaId else it.primeraOpcionId,
                    esCorrecta = if (legacyRow) it.esCorrecta else it.primeraEsCorrecta,
                    respondidaEn = answeredAt!!,
                )
            },
            finalAnswer = shown?.let {
                ReviewFinalAnswer(
                    opcionId = it.opcionSeleccionadaId,
                    esCorrecta = it.esCorrecta?.takeIf { _ -> it.opcionSeleccionadaId != null },
                    actualizadaEn = it.actualizadaEn?.let(::instant) ?: answeredAt!!,
                    revision = it.revision,
                )
            },
            cambioRespuesta = shown?.cambioRespuesta ?: false,
            esPrimeraRespuesta = shown?.esPrimerIntentoPregunta ?: false,
            correctAnswer = content.correctId?.takeUnless { annulled }?.let { correctId ->
                options.firstOrNull { it.id == correctId }?.let { ReviewCorrectAnswer(opcionId = correctId, letra = it.letra) }
            },
            explanation = null,
            resources = content.recursos,
            timings = ReviewTimings(
                tiempoRespuestaMs = shown?.let { it.tiempoRespuestaMs ?: it.tiempoRespuestaSegundos?.let { s -> s.toLong() * 1_000 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() }?.coerceAtLeast(0),
                respondidaEn = answeredAt,
                actualizadaEn = shown?.actualizadaEn?.let(::instant),
            ),
            etiquetas = content.etiquetas,
        )
    }

    /**
     * El contenido de una pregunta del intento: el de SU revision inmutable (20261022100000) o, en un intento congelado
     * antes de la migracion, el vivo. [QuestionView.correctId] solo existe si la lectura pidio la solucion (D2-12 / D2-65).
     */
    private fun FrozenQuestionRow.view(): QuestionView {
        val frozen = revision?.contenido
        if (revisionId != null && frozen != null) {
            return QuestionView(
                codigo = frozen.codigo.orContractError(),
                tipo = questionType(frozen.tipo),
                titulo = frozen.titulo.orContractError(),
                opciones = frozen.opciones.sortedBy { it.posicion }.map { option ->
                    QuestionOptionDto(
                        id = option.id,
                        letra = option.letra.takeIf { it in OPTION_LETTERS } ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception(),
                        texto = option.texto,
                    )
                },
                recursos = frozen.recursos.sortedWith(compareBy({ it.posicion }, { it.id })).mapNotNull(::resourceDto),
                etiquetas = frozen.etiquetas.distinctBy { it.id }.sortedBy { it.nombre }.map { ReviewTag(it.id, it.nombre) },
                correctId = revision.solucion?.opcionCorrectaId,
            )
        }
        val content = preguntas ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        return QuestionView(
            codigo = content.codigoPregunta.orContractError(),
            tipo = questionType(content.tipoPregunta),
            titulo = content.titulo.orContractError(),
            opciones = options(content),
            recursos = emptyList(),
            etiquetas = content.etiquetas.mapNotNull { it.etiquetas }.distinctBy { it.id }.sortedBy { it.nombre }.map { ReviewTag(it.id, it.nombre) },
            correctId = gateway.correctOptionId(content),
        )
    }

    private fun FrozenQuestionRow.correctOptionId(): String? =
        if (revisionId != null && revision != null) revision.solucion?.opcionCorrectaId else gateway.correctOptionId(preguntas)

    // ----- EX14: acceso a los bytes de un recurso -----

    /**
     * El dueno de un intento pide acceso a un recurso de la revision de ESE intento: Ktor autoriza (dueno + recurso
     * congelado en una revision ligada al intento), Storage sirve los bytes con una URL firmada de vida corta. Sin
     * intento propio no hay acceso: un `recursoId` suelto no abre nada. El intento solo existe si D2-03 paso la politica
     * de acceso (EntitlementPolicy) al empezarlo; como D2-08 / D2-65, no se re-evalua el entitlement (el conjunto congelado
     * es el alcance). Un recurso que no es de este intento es 404 RESOURCE_NOT_FOUND; uno con bucket o ruta fuera del
     * contrato Q3 tambien (falla cerrado, nunca se firma una ruta arbitraria).
     */
    suspend fun resourceAccess(userId: CanonicalUserId, attemptId: String, resourceId: String): AttemptResourceAccess = withUpstreamIo {
        val row = ownedAttempt(userId, attemptId)
        val started = System.nanoTime()
        val resource = gateway.frozenResource(row.id, resourceId, open = row.estado != FINISHED)
        metrics.timer("exam_media_metadata_seconds").record(java.time.Duration.ofNanos(System.nanoTime() - started))
        QuestionResources.access(resource, resourceId, clock()) { bucket, path, ttl -> gateway.signedUrl(bucket, path, ttl) }
    }

    private fun resourceDto(resource: RevisionResource): AttemptResourceDto? = QuestionResources.dto(resource)

    private fun AttemptRow.toDto(now: Instant, openUnanswered: Int?): AttemptDto {
        if (estado !in ATTEMPT_STATES) throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        val finished = estado == FINISHED
        return AttemptDto(
            id = id,
            origen = ORIGIN_EXAM,
            examenId = examenId,
            especialidadId = especialidadSnapshotId,
            tipoExamen = kind().wire,
            estado = estado,
            motivoFin = if (finished) endReason() else null,
            iniciadoEn = instant(iniciadoEn),
            deadline = null,
            serverNow = now.toString(),
            totalPreguntas = totalPreguntas.coerceAtLeast(0),
            respondidas = totalRespondidas.coerceAtLeast(0),
            // DEC-015: se revela pregunta a pregunta; los TOTALES de correccion siguen siendo del intento finalizado.
            correctas = totalCorrectas.takeIf { finished },
            falladas = totalFalladas.takeIf { finished },
            sinResponder = when {
                finished -> totalSinResponder
                openUnanswered != null -> openUnanswered
                else -> totalPreguntas - totalRespondidas
            }.coerceAtLeast(0),
            esDemo = esDemo.takeIf { it },
            ultimaPreguntaPosicion = ultimaPreguntaPosicion?.takeIf { it >= 1 },
        )
    }

    private fun AttemptRow.kind(): ExamKind =
        ExamKind.fromWireOrNull(tipoExamenSnapshot) ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()

    /** Un intento cerrado antes de SLICE 3 no tiene `motivo_fin`: el cierre legacy era siempre una accion del usuario. */
    private fun AttemptRow.endReason(): String = motivoFin?.takeIf { it in END_REASONS } ?: END_USER

    private fun AttemptRow.durationSeconds(): Int =
        (duracionSegundos ?: run {
            val start = parseInstant(iniciadoEn)
            val end = parseInstant(finalizadoEn)
            Duration.between(start, end).seconds.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        }).coerceAtLeast(0)

    /** DEC-015: lo respondido se ve corregido desde SU fila; `respondida` queda para un tipo que no revele o una fila sin correccion. */
    private fun gridStateOpen(annulled: Boolean, answer: AttemptAnswerRow?, reveals: Boolean): String = when {
        annulled -> GRID_ANNULLED
        answer?.opcionSeleccionadaId == null -> GRID_UNANSWERED
        !reveals || answer.esCorrecta == null -> GRID_ANSWERED
        answer.esCorrecta == true -> GRID_CORRECT
        else -> GRID_WRONG
    }

    /** FA31: la anulacion CONGELADA al empezar manda en los intentos con rol; los anteriores leen la anulacion viva (M10). */
    private fun FrozenQuestionRow.isAnnulled(live: Set<String>): Boolean = if (rol != null) anuladaAlInicio == true else preguntaId in live

    /** FA31: solo la reserva activada se rotula (`RESERVA`); lo demas es PRINCIPAL (ausente). */
    private fun FrozenQuestionRow.wireRole(): String? = if (rol == "RESERVA_ACTIVA" || rol == "RESERVA_NO_USADA") "RESERVA" else null

    private fun gridStateFinished(status: String?, answer: AttemptAnswerRow?): String = when (status ?: legacyStatus(answer)) {
        "CORRECT" -> GRID_CORRECT
        "WRONG" -> GRID_WRONG
        STATUS_ANNULLED -> GRID_ANNULLED
        STATUS_UNUSED_RESERVE -> GRID_UNUSED_RESERVE
        else -> GRID_UNANSWERED
    }

    /** Intento cerrado por el flujo legacy (sin estado congelado): se deriva de la respuesta guardada. */
    private fun legacyStatus(answer: AttemptAnswerRow?): String = when {
        answer == null -> "UNANSWERED"
        answer.opcionSeleccionadaId == null -> "SKIPPED"
        answer.esCorrecta == true -> "CORRECT"
        else -> "WRONG"
    }

    private fun options(content: QuestionRef): List<QuestionOptionDto> =
        content.opciones.sortedBy { it.posicion }.map { option ->
            QuestionOptionDto(
                id = option.id,
                letra = option.letra.takeIf { it in OPTION_LETTERS } ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception(),
                texto = option.textoOpcion,
            )
        }

    /** Canonico, o el sinonimo legacy que solo se acepta al leer (vocabulario de 10_EXAM_ENGINE_CONTRACT). */
    private fun questionType(value: String?): String = when (value) {
        "rafaga" -> "rafaga"
        "razonada", "guiada" -> "razonada"
        "inmersiva" -> "inmersiva"
        else -> throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
    }

    private fun answerSeconds(answer: AttemptAnswerRow?): Int =
        (answer?.tiempoRespuestaMs?.let { (it + 500) / 1_000 } ?: answer?.tiempoRespuestaSegundos ?: 0).coerceAtLeast(0)

    private fun <T> bounded(rows: List<T>): List<T> {
        // Mas filas que el tope del contrato no se truncan en silencio: falla cerrado.
        if (rows.size > AttemptGateway.MAX_ATTEMPT_QUESTIONS) throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        return rows
    }

    private fun String?.orContractError(): String = this ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()

    private fun parseInstant(value: String?): Instant =
        value?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() } ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()

    private fun instant(value: String?): String = parseInstant(value).toString()

    companion object {
        const val QUESTIONS_DEFAULT_LIMIT = 10
        const val QUESTIONS_MAX_LIMIT = 20

        private const val FINISHED = "finalizado"
        private const val ORIGIN_EXAM = "EXAM"
        private const val END_USER = "USER"
        private const val STATUS_ANNULLED = "ANNULLED"
        private const val STATUS_UNUSED_RESERVE = "RESERVA_NO_UTILIZADA"
        private const val BAND_NO_EVALUABLES = "sin_evaluables"
        private const val GRID_UNANSWERED = "sin_responder"
        private const val GRID_ANSWERED = "respondida"
        private const val GRID_CORRECT = "correcta"
        private const val GRID_WRONG = "incorrecta"
        private const val GRID_ANNULLED = "anulada"
        private const val GRID_UNUSED_RESERVE = "reserva_no_utilizada"
        private val ATTEMPT_STATES = setOf("en_progreso", "finalizado", "abandonado")
        private val END_REASONS = setOf("USER", "TIMEOUT")
        private val START_MODES = setOf("NORMAL", "DEMO")

        /** `EXAM_TOO_LARGE` de `app_start_exam_attempt`: sale como 409 EXAM_EMPTY (el contrato no tiene codigo propio). */
        internal const val EXAM_TOO_LARGE_MESSAGE = "El examen no se puede empezar: supera el maximo de preguntas de un intento."
        private const val ANSWER_ANSWERED = "ANSWERED"
        private val ANSWER_STATES = setOf(ANSWER_ANSWERED, "SKIPPED", "ANNULLED")

        /**
         * DEC-015 (dueno, 2026-10-13): el tipo de examen del intento revela la correccion AL RESPONDER? Hoy los tres.
         * Es el UNICO sitio que lo decide (D2-07, D2-08 y D2-09): una regla por tipo es cambiar una linea de este `when`.
         */
        internal fun revealsOnAnswer(kind: ExamKind): Boolean = when (kind) {
            ExamKind.RAFAGA -> true
            ExamKind.RAZONADO -> true
            ExamKind.INMERSIVO -> true
        }
        private val ABANDON_REASONS = setOf("salida_usuario", "otro")
        private val REVIEW_STATUSES = setOf("CORRECT", "WRONG", "SKIPPED", "UNANSWERED", "ANNULLED", "RESERVA_NO_UTILIZADA")

        /** Recursos V1 (decision del dueno 2026-10-14): imagen y audio; video extensible. PDF / texto / enlace fuera. */
        internal val RESOURCE_TYPES = setOf("imagen", "audio", "video")

        /** Bucket PRIVADO de Q3 (`recursos_pregunta_ubicacion_formato_chk`). */
        internal const val RESOURCES_BUCKET = "recursos-pregunta"

        /** Vida de la URL firmada: corta (un enlace filtrado caduca pronto); el cliente pide otra al caducar. */
        internal const val RESOURCE_URL_TTL_SECONDS = 300
        private val OPTION_LETTERS = setOf("A", "B", "C", "D")

        /** DEC-013 (default tecnico): correctas / evaluables * 10, half-up a 2 decimales. Sin evaluables, 0. */
        internal fun gradeOf(correct: Int, evaluable: Int): BigDecimal =
            if (evaluable <= 0) {
                BigDecimal.ZERO.setScale(2)
            } else {
                BigDecimal(correct.coerceIn(0, evaluable)).multiply(BigDecimal.TEN).divide(BigDecimal(evaluable), 2, RoundingMode.HALF_UP)
            }

        /** Banda de la nota ya redondeada (PDF 3.2): <5, 5-5.99, 6-6.99, 7-8.99, 9-10. */
        internal fun bandOf(grade: BigDecimal): String = when {
            grade < BigDecimal(5) -> "no_suficiente"
            grade < BigDecimal(6) -> "suficiente"
            grade < BigDecimal(7) -> "bien"
            grade < BigDecimal(9) -> "notable"
            else -> "sobresaliente"
        }
    }
}

/**
 * FA31 / C-O5: GEMELO en Kotlin de `private.official_nota` (la base es la UNICA implementacion: Ktor sirve la nota que ella guarda). Existe para que una matriz
 * de paridad (OfficialScoringParityTest, md5 fijado tambien en assertions.sql) detecte cualquier deriva de formula o de redondeo. Aritmetica entera exacta:
 * nota = max(0, c x den - num x f) x 10 / (den x e), HALF-UP a 2 decimales UNA vez; null sin evaluables.
 */
internal object OfficialScoring {
    fun nota(correctas: Int, falladas: Int, evaluables: Int, num: Int, den: Int): BigDecimal? {
        if (evaluables <= 0 || den < 1 || num < 0) return null
        val bruto = (correctas.toLong() * den - num.toLong() * falladas).coerceAtLeast(0L)
        val d = den.toLong() * evaluables
        val centesimas = (bruto * 2000L + d) / (2L * d)
        return BigDecimal.valueOf(centesimas, 2)
    }
}
