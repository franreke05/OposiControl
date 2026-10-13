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
//   DEC-025/040      D2-65 `explanation` = null y `resources` = []; D2-12 `razonamiento` = null (no se leen de la base).
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
)

@Serializable
internal data class AttemptGridItem(val posicion: Int, val preguntaId: String, val revision: Int, val estado: String)

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
    val opciones: List<QuestionOptionDto>,
    val respuesta: AttemptQuestionAnswer? = null,
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
    val nota: Double,
    val calificacion: String,
    val duracionSegundos: Int,
    val correctas: Int,
    val falladas: Int,
    val sinResponder: Int,
)

@Serializable
internal data class ResultQuestion(
    val posicion: Int,
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

@Serializable
internal data class ReviewResource(val id: String, val tipo: String, val titulo: String)

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
    val firstAnswer: ReviewFirstAnswer? = null,
    val finalAnswer: ReviewFinalAnswer? = null,
    val cambioRespuesta: Boolean,
    val esPrimeraRespuesta: Boolean,
    val correctAnswer: ReviewCorrectAnswer? = null,
    /** DEC-025 / DEC-040 abiertas: `null`. */
    val explanation: ReviewExplanation? = null,
    /** DEC-025 / DEC-040 abiertas: vacio. */
    val resources: List<ReviewResource>,
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

// ---------- servicio ----------

internal class AttemptService(
    private val gateway: AttemptGateway,
    private val catalog: CatalogService,
    private val clock: () -> Instant = Instant::now,
) {
    // ----- D2-03 -----
    suspend fun start(userId: CanonicalUserId, examId: String, request: AttemptStartRequest): StartedAttempt = withUpstreamIo {
        if (request.modo != null && request.modo !in START_MODES) throw validationError(ErrorIssue("modo", CanonicalIssue.INVALID_FORMAT))
        val now = clock()
        val uid = userId.value
        // D7-25: la UNICA politica de acceso. Borrador, archivado e inexistente son el mismo 404.
        val access = catalog.startAccess(uid, examId, now) ?: throw CanonicalErrorCode.EXAM_NOT_FOUND.exception()
        if (!access.verdict.allowed) {
            throw when (access.verdict.denyReason) {
                AccessDenyReason.CAPABILITY_DISABLED -> CanonicalErrorCode.SPECIALTY_TYPE_NOT_ALLOWED.exception()
                else -> CanonicalErrorCode.EXAM_LOCKED.exception(details = ErrorDetails(accessState = access.verdict.state.name))
            }
        }
        val outcome = gateway.start(uid, examId)
        val created = when (outcome.outcome) {
            "CREATED" -> true
            "RESUMED" -> false
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
        val frozen = bounded(gateway.frozenSet(row.id))
        val finished = row.estado == FINISHED
        val answers = gateway.answers(row.id, graded = finished)
        val annulled = if (finished) emptySet() else row.examenId?.let(gateway::annulledNow).orEmpty()
        val reveals = !finished && revealsOnAnswer(row.kind())
        val grid = frozen.map { question ->
            val answer = answers[question.preguntaId]
            AttemptGridItem(
                posicion = question.posicion,
                preguntaId = question.preguntaId,
                revision = answer?.revision ?: 0,
                estado = if (finished) gridStateFinished(question.estadoFinal, answer) else gridStateOpen(question.preguntaId in annulled, answer, reveals),
            )
        }
        val unanswered = if (finished) null else grid.count { it.estado == GRID_UNANSWERED }
        AttemptView(intento = row.toDto(now, openUnanswered = unanswered), grid = grid)
    }

    // ----- D2-08 -----
    suspend fun questions(userId: CanonicalUserId, attemptId: String, page: CursorPageRequest): AttemptQuestionPage = withUpstreamIo {
        val row = ownedAttempt(userId, attemptId)
        val rows = gateway.questionPage(row.id, positionAfter(page.after), page.fetchLimit)
        val cut = page.page(rows) { listOf(JsonPrimitive(it.posicion)) }
        // DEC-015: la correccion de lo YA respondido, igual con el intento abierto que finalizado, y solo de la fila propia.
        // Sin soluciones, explicaciones ni recursos (eso es D2-12 / D2-65).
        val reveals = row.estado == FINISHED || revealsOnAnswer(row.kind())
        val answers = gateway.answers(row.id, graded = false, questionIds = cut.items.map { it.preguntaId })
        // Con el intento finalizado manda el estado congelado; abierto, la anulacion vigente del examen.
        val annulled = if (row.estado == FINISHED || cut.items.isEmpty()) emptySet() else row.examenId?.let(gateway::annulledNow).orEmpty()
        AttemptQuestionPage(
            items = cut.items.map { question ->
                val content = question.preguntas ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
                val answer = answers[question.preguntaId]
                val isAnnulled = question.estadoFinal == STATUS_ANNULLED || question.preguntaId in annulled
                AttemptQuestionDto(
                    posicion = question.posicion,
                    preguntaId = question.preguntaId,
                    codigoPregunta = content.codigoPregunta.orContractError(),
                    tipoPregunta = questionType(content.tipoPregunta),
                    titulo = content.titulo.orContractError(),
                    anulada = isAnnulled,
                    opciones = options(content),
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
                // un salto y una anulada, nada. Si esta lectura falla, falla la llamada: el plugin de idempotencia no guarda
                // errores, asi que el reintento con la misma clave cae en el no-op de la RPC y revela.
                val revealed = if (estado == ANSWER_ANSWERED && attemptRevealsOnAnswer(userId, attemptId)) revealedAnswer(attemptId, questionId) else null
                AnswerResult(
                    revision = revision,
                    estado = estado,
                    esCorrecta = revealed?.esCorrecta,
                    opcionCorrectaId = revealed?.opcionCorrectaId,
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
     * RPC ya valido como del usuario. Que no este, o que no lleve opcion o correccion, contradice el ANSWERED de la RPC.
     */
    private fun revealedAnswer(attemptId: String, questionId: String): AttemptAnswerRow =
        gateway.answers(attemptId, graded = false, questionIds = listOf(questionId))[questionId]
            ?.takeIf { it.opcionSeleccionadaId != null && it.esCorrecta != null }
            ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()

    private fun resultOf(row: AttemptRow): AttemptResult {
        if (row.estado != FINISHED) throw CanonicalErrorCode.ATTEMPT_NOT_FINISHED.exception()
        val frozen = bounded(gateway.resultQuestions(row.id))
        val answers = gateway.answers(row.id, graded = true)
        val tags = gateway.tagSummary(row.id)
        if (tags.size > AttemptGateway.MAX_ATTEMPT_QUESTIONS) throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        val evaluable = row.totalCorrectas + row.totalFalladas + row.totalSinResponder
        val nota = gradeOf(row.totalCorrectas, evaluable)
        return AttemptResult(
            resumen = ResultSummary(
                examenId = row.examenId,
                especialidadId = row.especialidadSnapshotId,
                modo = row.kind().wire,
                nota = nota.toDouble(),
                calificacion = bandOf(nota),
                duracionSegundos = row.durationSeconds(),
                correctas = row.totalCorrectas,
                falladas = row.totalFalladas,
                sinResponder = row.totalSinResponder,
            ),
            preguntas = frozen.map { question ->
                val answer = answers[question.preguntaId]
                val annulled = question.estadoFinal == STATUS_ANNULLED
                ResultQuestion(
                    posicion = question.posicion,
                    opcionElegida = answer?.opcionSeleccionadaId?.takeUnless { annulled },
                    opcionCorrecta = gateway.correctOptionId(question.preguntas)?.takeUnless { annulled },
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
        val content = question.preguntas ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        val status = question.estadoFinal?.takeIf { it in REVIEW_STATUSES } ?: legacyStatus(answer)
        val annulled = status == STATUS_ANNULLED
        val shown = answer?.takeUnless { annulled }
        val options = options(content)
        // Fila anterior a SLICE 3 (`actualizada_en` nulo): no hubo cambio posible, primera = final.
        val legacyRow = shown != null && shown.actualizadaEn == null
        val answeredAt = shown?.let { instant(it.respondidaEn ?: it.creadaEn) }
        return ReviewItem(
            posicion = question.posicion,
            preguntaId = question.preguntaId,
            codigoPregunta = content.codigoPregunta.orContractError(),
            tipoPregunta = questionType(content.tipoPregunta),
            enunciado = content.titulo.orContractError(),
            opciones = options,
            status = status,
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
            correctAnswer = gateway.correctOptionId(content)?.takeUnless { annulled }?.let { correctId ->
                options.firstOrNull { it.id == correctId }?.let { ReviewCorrectAnswer(opcionId = correctId, letra = it.letra) }
            },
            explanation = null,
            resources = emptyList(),
            timings = ReviewTimings(
                tiempoRespuestaMs = shown?.let { it.tiempoRespuestaMs ?: it.tiempoRespuestaSegundos?.let { s -> s.toLong() * 1_000 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() }?.coerceAtLeast(0),
                respondidaEn = answeredAt,
                actualizadaEn = shown?.actualizadaEn?.let(::instant),
            ),
            etiquetas = content.etiquetas.mapNotNull { it.etiquetas }.distinctBy { it.id }.sortedBy { it.nombre }.map { ReviewTag(it.id, it.nombre) },
        )
    }

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

    private fun gridStateFinished(status: String?, answer: AttemptAnswerRow?): String = when (status ?: legacyStatus(answer)) {
        "CORRECT" -> GRID_CORRECT
        "WRONG" -> GRID_WRONG
        STATUS_ANNULLED -> GRID_ANNULLED
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
        private const val GRID_UNANSWERED = "sin_responder"
        private const val GRID_ANSWERED = "respondida"
        private const val GRID_CORRECT = "correcta"
        private const val GRID_WRONG = "incorrecta"
        private const val GRID_ANNULLED = "anulada"
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
        private val REVIEW_STATUSES = setOf("CORRECT", "WRONG", "SKIPPED", "UNANSWERED", "ANNULLED")
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
