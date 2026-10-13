package com.oposicontrol.backend

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

// =====================================================================
// Pasarela del runtime de examen (SLICE 3: D2-03, D2-07..D2-12, D2-65). PostgREST con la clave de servicio.
//
// - ESCRITURAS: solo las cuatro RPC atomicas de `20261008100100_slice3_attempt_command_rpcs.sql`, endurecidas por
//   `20261008100200_slice3_attempt_hardening.sql` (SECURITY DEFINER, EXECUTE solo `service_role`). Devuelven `{outcome: ...}`; traducirlo al sobre canonico es de [AttemptService].
// - LECTURAS: SIEMPRE con `usuario_id = <sub del JWT>` en el filtro del intento (un intento ajeno no existe), y siempre
//   ACOTADAS (un intento tiene como mucho [MAX_ATTEMPT_QUESTIONS] preguntas). Ninguna regla de negocio aqui.
// - SEC-004: `soluciones_pregunta` solo se lee en [reviewPage] y [resultQuestions], que el servicio llama UNICAMENTE con
//   el intento ya `finalizado`. [questionPage] (D2-08) jamas la nombra, ni `razonamiento` se lee en ningun sitio.
// - DEC-015 (decidida 2026-10-13): la correccion que se ensena con el intento ABIERTO sale SOLO de la fila propia del
//   usuario en `respuestas_intento` (`es_correcta`, `opcion_correcta_id`, que `app_record_attempt_answer` escribe al
//   responder). Una pregunta sin tocar no tiene fila y una saltada las tiene a `null`: no hay nada que leer de mas.
// =====================================================================

/** Puerto bloqueante hacia Supabase para los intentos. */
internal interface AttemptUpstream {
    fun get(path: String): String

    fun rpc(function: String, body: String): String
}

internal class TransportAttemptUpstream(private val transport: SupabaseRequestTransport) : AttemptUpstream {
    override fun get(path: String): String = transport.get(path)

    override fun rpc(function: String, body: String): String = transport.rpc(function, body)

    companion object {
        fun fromEnvironment(environment: (String) -> String? = System::getenv): TransportAttemptUpstream? =
            SupabaseRequestTransport.fromEnvironment(environment)?.let(::TransportAttemptUpstream)
    }
}

// ---------- filas (snake_case, tal cual vive en la base) ----------

@Serializable
internal data class AttemptRow(
    val id: String,
    @SerialName("examen_id") val examenId: String? = null,
    val estado: String,
    @SerialName("motivo_fin") val motivoFin: String? = null,
    @SerialName("iniciado_en") val iniciadoEn: String,
    @SerialName("finalizado_en") val finalizadoEn: String? = null,
    @SerialName("duracion_segundos") val duracionSegundos: Int? = null,
    @SerialName("total_preguntas") val totalPreguntas: Int = 0,
    @SerialName("total_respondidas") val totalRespondidas: Int = 0,
    @SerialName("total_correctas") val totalCorrectas: Int = 0,
    @SerialName("total_falladas") val totalFalladas: Int = 0,
    @SerialName("total_sin_responder") val totalSinResponder: Int = 0,
    @SerialName("tipo_examen_snapshot") val tipoExamenSnapshot: String? = null,
    @SerialName("especialidad_snapshot_id") val especialidadSnapshotId: String? = null,
    @SerialName("ultima_pregunta_posicion") val ultimaPreguntaPosicion: Int? = null,
)

@Serializable
internal data class AttemptAnswerRow(
    @SerialName("pregunta_id") val preguntaId: String,
    @SerialName("opcion_seleccionada_id") val opcionSeleccionadaId: String? = null,
    @SerialName("es_correcta") val esCorrecta: Boolean? = null,
    /** La opcion correcta copiada en la fila al responder (FK `ON DELETE SET NULL`: puede faltar con `es_correcta` presente). */
    @SerialName("opcion_correcta_id") val opcionCorrectaId: String? = null,
    val revision: Int = 1,
    @SerialName("respondida_en") val respondidaEn: String? = null,
    @SerialName("actualizada_en") val actualizadaEn: String? = null,
    @SerialName("creada_en") val creadaEn: String? = null,
    @SerialName("tiempo_respuesta_ms") val tiempoRespuestaMs: Int? = null,
    @SerialName("tiempo_respuesta_segundos") val tiempoRespuestaSegundos: Int? = null,
    @SerialName("primera_opcion_id") val primeraOpcionId: String? = null,
    @SerialName("primera_es_correcta") val primeraEsCorrecta: Boolean? = null,
    @SerialName("cambio_respuesta") val cambioRespuesta: Boolean = false,
    @SerialName("es_primer_intento_pregunta") val esPrimerIntentoPregunta: Boolean = false,
)

@Serializable
internal data class OptionRow(val id: String, val letra: String, @SerialName("texto_opcion") val textoOpcion: String, val posicion: Int = 0)

@Serializable
internal data class TagRef(val id: String, val nombre: String)

@Serializable
internal data class QuestionTagRow(val etiquetas: TagRef? = null)

@Serializable
private data class SolutionRef(@SerialName("opcion_correcta_id") val opcionCorrectaId: String? = null)

/** El agregado de pregunta embebido. [soluciones] llega como objeto (FK unica) o lista segun PostgREST: se lee con [correctOptionId]. */
@Serializable
internal data class QuestionRef(
    @SerialName("codigo_pregunta") val codigoPregunta: String? = null,
    @SerialName("tipo_pregunta") val tipoPregunta: String? = null,
    val titulo: String? = null,
    @SerialName("opciones_pregunta") val opciones: List<OptionRow> = emptyList(),
    @SerialName("soluciones_pregunta") val soluciones: JsonElement? = null,
    @SerialName("etiquetas_pregunta") val etiquetas: List<QuestionTagRow> = emptyList(),
)

@Serializable
internal data class FrozenQuestionRow(
    val posicion: Int,
    @SerialName("pregunta_id") val preguntaId: String,
    @SerialName("estado_final") val estadoFinal: String? = null,
    val preguntas: QuestionRef? = null,
)

@Serializable
private data class AnnulledRow(@SerialName("pregunta_id") val preguntaId: String)

@Serializable
internal data class TagSummaryRow(
    @SerialName("etiqueta_id") val etiquetaId: String,
    val correctas: Int = 0,
    val falladas: Int = 0,
    @SerialName("sin_responder") val sinResponder: Int = 0,
    @SerialName("tiempo_total_segundos") val tiempoTotalSegundos: Int = 0,
    val etiquetas: TagNameRef? = null,
)

@Serializable
internal data class TagNameRef(val nombre: String? = null)

/** Resultado de una RPC de comando: `outcome` + lo que cada una anade. */
@Serializable
internal data class AttemptCommandOutcome(
    val outcome: String,
    @SerialName("intento_id") val intentoId: String? = null,
    val estado: String? = null,
    val revision: Int? = null,
    @SerialName("current_revision") val currentRevision: Int? = null,
    @SerialName("es_primera_respuesta") val esPrimeraRespuesta: Boolean? = null,
    val respondidas: Int? = null,
    val already: Boolean? = null,
)

internal class AttemptGateway(
    private val upstream: AttemptUpstream,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private inline fun <reified T> decodeList(body: String): List<T> =
        json.decodeFromString(ListSerializer(serializer<T>()), body)

    /** PostgREST devuelve el jsonb de la funcion tal cual (objeto); se admite tambien la lista de una fila. */
    private fun decodeOutcome(body: String): AttemptCommandOutcome {
        val element = json.parseToJsonElement(body)
        val row = when (element) {
            is JsonObject -> element
            is JsonArray -> element.singleOrNull() as? JsonObject
            else -> null
        } ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        return json.decodeFromJsonElement<AttemptCommandOutcome>(row)
    }

    // ---------- comandos (RPC) ----------

    fun start(userId: String, examId: String): AttemptCommandOutcome {
        require(userId.isUuid() && examId.isUuid()) { "ids" }
        return decodeOutcome(
            upstream.rpc(
                "app_start_exam_attempt",
                buildJsonObject { put("p_usuario_id", userId); put("p_examen_id", examId) }.toString(),
            ),
        )
    }

    fun answer(
        userId: String,
        attemptId: String,
        questionId: String,
        optionId: String?,
        timeMs: Int,
        baseRevision: Int,
        confidence: Int?,
    ): AttemptCommandOutcome {
        require(userId.isUuid() && attemptId.isUuid() && questionId.isUuid() && (optionId == null || optionId.isUuid())) { "ids" }
        val body = buildJsonObject {
            put("p_usuario_id", userId)
            put("p_intento_id", attemptId)
            put("p_pregunta_id", questionId)
            put("p_opcion_id", optionId?.let(::JsonPrimitive) ?: JsonNull)
            put("p_tiempo_respuesta_ms", timeMs)
            put("p_base_revision", baseRevision)
            put("p_nivel_confianza", confidence?.let(::JsonPrimitive) ?: JsonNull)
        }
        return decodeOutcome(upstream.rpc("app_record_attempt_answer", body.toString()))
    }

    fun finish(userId: String, attemptId: String): AttemptCommandOutcome {
        require(userId.isUuid() && attemptId.isUuid()) { "ids" }
        return decodeOutcome(
            upstream.rpc(
                "app_finish_exam_attempt",
                buildJsonObject { put("p_usuario_id", userId); put("p_intento_id", attemptId) }.toString(),
            ),
        )
    }

    fun abandon(userId: String, attemptId: String, position: Int, reason: String): AttemptCommandOutcome {
        require(userId.isUuid() && attemptId.isUuid()) { "ids" }
        val body = buildJsonObject {
            put("p_usuario_id", userId)
            put("p_intento_id", attemptId)
            put("p_posicion", position)
            put("p_motivo", reason)
        }
        return decodeOutcome(upstream.rpc("app_abandon_exam_attempt", body.toString()))
    }

    // ---------- lecturas ----------

    /**
     * El intento de EXAMEN de ese usuario creado por el comando canonico, o `null` (inexistente, ajeno, de otro origen o sin
     * el marcador `creado_por_comando` de `app_start_exam_attempt`: indistinguibles a proposito). Un intento que no paso por
     * D2-03 no paso por EntitlementPolicy: para D2-07 / D2-08 / D2-12 / D2-65 no existe (20261008100200, F-01).
     */
    fun attempt(userId: String, attemptId: String): AttemptRow? {
        require(userId.isUuid() && attemptId.isUuid()) { "ids" }
        val query = "/rest/v1/intentos_examen?select=$ATTEMPT_COLUMNS&id=eq.$attemptId&usuario_id=eq.$userId" +
            "&origen_intento=eq.examen&creado_por_comando=not.is.null&limit=1"
        return decodeList<AttemptRow>(upstream.get(query)).firstOrNull()
    }

    /** El conjunto congelado (posicion, pregunta, estado al finalizar), SIN contenido. Pide un maximo + 1. */
    fun frozenSet(attemptId: String): List<FrozenQuestionRow> {
        require(attemptId.isUuid()) { "attemptId" }
        return decodeList(
            upstream.get(
                "/rest/v1/preguntas_intento?select=posicion,pregunta_id,estado_final&intento_id=eq.$attemptId" +
                    "&order=posicion.asc&limit=${MAX_ATTEMPT_QUESTIONS + 1}",
            ),
        )
    }

    /** D2-08: una pagina del conjunto congelado con enunciado y opciones. NUNCA `soluciones_pregunta` (la correccion de lo ya respondido sale de [answers]). */
    fun questionPage(attemptId: String, afterPosition: Int, fetchLimit: Int): List<FrozenQuestionRow> {
        require(attemptId.isUuid()) { "attemptId" }
        return decodeList(
            upstream.get(
                "/rest/v1/preguntas_intento?select=posicion,pregunta_id,estado_final,preguntas($QUESTION_COLUMNS)&intento_id=eq.$attemptId" +
                    "&posicion=gt.$afterPosition&order=posicion.asc&limit=$fetchLimit",
            ),
        )
    }

    /** D2-65: una pagina de la revision (intento YA finalizado): contenido vivo + opcion correcta + etiquetas. */
    fun reviewPage(attemptId: String, afterPosition: Int, fetchLimit: Int): List<FrozenQuestionRow> {
        require(attemptId.isUuid()) { "attemptId" }
        return decodeList(
            upstream.get(
                "/rest/v1/preguntas_intento?select=posicion,pregunta_id,estado_final," +
                    "preguntas($QUESTION_COLUMNS,soluciones_pregunta(opcion_correcta_id),etiquetas_pregunta(etiquetas(id,nombre)))" +
                    "&intento_id=eq.$attemptId&posicion=gt.$afterPosition&order=posicion.asc&limit=$fetchLimit",
            ),
        )
    }

    /** D2-12: el conjunto congelado con la opcion correcta (intento YA finalizado). Pide un maximo + 1. */
    fun resultQuestions(attemptId: String): List<FrozenQuestionRow> {
        require(attemptId.isUuid()) { "attemptId" }
        return decodeList(
            upstream.get(
                "/rest/v1/preguntas_intento?select=posicion,pregunta_id,estado_final,preguntas(soluciones_pregunta(opcion_correcta_id))" +
                    "&intento_id=eq.$attemptId&order=posicion.asc&limit=${MAX_ATTEMPT_QUESTIONS + 1}",
            ),
        )
    }

    /**
     * Respuestas del intento; con [questionIds] solo las de esa pagina (como mucho 50 ids ya validados).
     * [graded] = false (intento ABIERTO, D2-08 o la revelacion de D2-09): la respuesta propia con SU correccion (`es_correcta`,
     * `opcion_correcta_id` de la misma fila, DEC-015), sin la primera respuesta ni los tiempos. Nunca `soluciones_pregunta` (SEC-004).
     * [ownerId] (la revelacion de D2-09, que no lee antes el intento): ademas `usuario_id=eq.`, defensa en profundidad para que
     * esa lectura no dependa solo de que la RPC haya validado al dueno del intento.
     */
    fun answers(
        attemptId: String,
        graded: Boolean,
        questionIds: Collection<String>? = null,
        ownerId: String? = null,
    ): Map<String, AttemptAnswerRow> {
        require(attemptId.isUuid()) { "attemptId" }
        require(ownerId == null || ownerId.isUuid()) { "ownerId" }
        if (questionIds != null && questionIds.isEmpty()) return emptyMap()
        val owner = ownerId?.let { "&usuario_id=eq.$it" }.orEmpty()
        val filter = questionIds?.let { ids ->
            require(ids.size <= MAX_PAGE_IDS && ids.all(String::isUuid)) { "questionIds" }
            "&pregunta_id=in.(${ids.joinToString(",")})"
        }.orEmpty()
        val columns = if (graded) GRADED_ANSWER_COLUMNS else OPEN_ANSWER_COLUMNS
        val query = "/rest/v1/respuestas_intento?select=$columns&intento_id=eq.$attemptId$owner$filter" +
            "&order=pregunta_id.asc&limit=${MAX_ATTEMPT_QUESTIONS + 1}"
        return decodeList<AttemptAnswerRow>(upstream.get(query)).associateBy { it.preguntaId }
    }

    /** Preguntas ANULADAS ahora mismo en el examen (un examen tiene como mucho 200 vinculos). Pide un maximo + 1. */
    fun annulledNow(examId: String): Set<String> {
        require(examId.isUuid()) { "examId" }
        val rows = decodeList<AnnulledRow>(
            upstream.get(
                "/rest/v1/preguntas_examen?select=pregunta_id&examen_id=eq.$examId&anulada=eq.true" +
                    "&order=pregunta_id.asc&limit=${MAX_ATTEMPT_QUESTIONS + 1}",
            ),
        )
        // Un conjunto truncado marcaria como evaluable una pregunta anulada: falla cerrado.
        if (rows.size > MAX_ATTEMPT_QUESTIONS) throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        return rows.mapTo(HashSet()) { it.preguntaId }
    }

    /** D2-12: el resumen por etiqueta congelado al finalizar. Pide un maximo + 1. */
    fun tagSummary(attemptId: String): List<TagSummaryRow> {
        require(attemptId.isUuid()) { "attemptId" }
        return decodeList(
            upstream.get(
                "/rest/v1/resumen_intento_etiqueta?select=etiqueta_id,correctas,falladas,sin_responder,tiempo_total_segundos,etiquetas(nombre)" +
                    "&intento_id=eq.$attemptId&order=etiqueta_id.asc&limit=${MAX_ATTEMPT_QUESTIONS + 1}",
            ),
        )
    }

    /** `soluciones_pregunta` embebida: objeto (relacion 1:1 por la FK unica) o lista de una fila. */
    fun correctOptionId(question: QuestionRef?): String? {
        val solution = when (val raw = question?.soluciones) {
            is JsonObject -> raw
            is JsonArray -> raw.firstOrNull() as? JsonObject
            else -> null
        } ?: return null
        return json.decodeFromJsonElement<SolutionRef>(solution).opcionCorrectaId
    }

    companion object {
        /** `preguntas_examen_posicion_check` (1..200) y `maxItems: 200` del contrato. */
        const val MAX_ATTEMPT_QUESTIONS = 200
        const val MAX_PAGE_IDS = 50

        internal const val ATTEMPT_COLUMNS =
            "id,examen_id,estado,motivo_fin,iniciado_en,finalizado_en,duracion_segundos,total_preguntas,total_respondidas," +
                "total_correctas,total_falladas,total_sin_responder,tipo_examen_snapshot,especialidad_snapshot_id,ultima_pregunta_posicion"

        /** Contenido de pregunta que ve el opositor DURANTE el intento. Sin soluciones (SEC-004). */
        internal const val QUESTION_COLUMNS = "codigo_pregunta,tipo_pregunta,titulo,opciones_pregunta(id,letra,texto_opcion,posicion)"

        /**
         * Lo que el opositor ya sabe de su propia respuesta con el intento abierto: que marco, cuando y (DEC-015, decidida
         * 2026-10-13) si acerto y cual era la correcta, tal como quedo en SU fila al responder.
         */
        internal const val OPEN_ANSWER_COLUMNS = "pregunta_id,opcion_seleccionada_id,es_correcta,opcion_correcta_id,revision,respondida_en,creada_en"

        /** Con el intento FINALIZADO (D2-07 grid, D2-12, D2-65): ademas la correccion y la primera respuesta. */
        internal const val GRADED_ANSWER_COLUMNS =
            "pregunta_id,opcion_seleccionada_id,es_correcta,revision,respondida_en,actualizada_en,creada_en,tiempo_respuesta_ms," +
                "tiempo_respuesta_segundos,primera_opcion_id,primera_es_correcta,cambio_respuesta,es_primer_intento_pregunta"
    }
}
