package com.oposicontrol.backend

import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Marcas de cada pregunta de un examen OFICIAL: anulada (con motivo) y reserva (con su orden).
 *
 * ### Reglas del servidor (E1-V1-CONTROL-SPEC-OFFICIAL-EXAMS.md, columnas de `preguntas_examen`)
 * - Una reserva lleva `orden_reserva >= 1`; una principal lo lleva a `null` (CHECK de la base).
 * - Una reserva va SIEMPRE tras la última principal; el orden de las reservas es 1..R sin huecos.
 * - Una pregunta de demostración no puede estar anulada ni ser reserva.
 * - Un examen publicado no puede quedarse con más anuladas que reservas bajo la regla de reemplazo.
 *
 * Todo eso lo valida y lo ejecuta la base. Aquí NO se escribe `preguntas_examen` con un PATCH: la escritura es la función
 * `admin_set_exam_question_flags(p_actor, p_examen, p_pregunta, p_anulada, p_motivo, p_es_reserva, p_orden_reserva)`, que
 * recoloca la posición y deja el rastro de auditoría en la misma transacción. Esa función NO existe todavía en la rama de
 * servidor (peticion a E1 en `E6-V1-PANEL-02.md`): mientras tanto la ruta contesta 501 `CONTROL_RPC_NOT_PROVISIONED` y la
 * LECTURA sigue funcionando.
 */
internal object OfficialQuestionFlags {
    const val MOTIVO_MAX = 500
    const val ORDEN_MAX = 10
    private val KEYS = setOf("anulada", "motivoAnulacion", "esReserva", "ordenReserva")

    data class Input(val anulada: Boolean, val motivo: String?, val esReserva: Boolean, val ordenReserva: Int?)

    fun parse(raw: JsonObject): Input {
        raw.requireOnlyKeys(KEYS)
        val anulada = raw.requiredBool("anulada")
        val esReserva = raw.requiredBool("esReserva")
        val motivo = raw.optionalString("motivoAnulacion")?.controlSanitized(MOTIVO_MAX, allowNewlines = true)?.ifEmpty { null }
        if (motivo != null && !anulada) {
            controlReject("ANNULMENT_REASON_WITHOUT_ANNULMENT", "El motivo de anulación solo se admite si la pregunta está anulada.")
        }
        val rawOrden = raw.optionalInt("ordenReserva")
        if (esReserva) {
            if (rawOrden == null || rawOrden < 1 || rawOrden > ORDEN_MAX) {
                controlReject("RESERVE_ORDER_INVALID", "Una reserva necesita un orden de 1 a $ORDEN_MAX.")
            }
        } else if (rawOrden != null) {
            controlReject("RESERVE_ORDER_INVALID", "Una pregunta principal no lleva orden de reserva.")
        }
        return Input(anulada, motivo, esReserva, rawOrden)
    }
}

@Serializable
data class AdminOfficialQuestionDto(
    val preguntaId: String,
    val posicion: Int,
    val titulo: String = "",
    val anulada: Boolean = false,
    val motivoAnulacion: String? = null,
    val esReserva: Boolean = false,
    val ordenReserva: Int? = null,
    val esDemo: Boolean = false,
)

@Serializable
data class AdminOfficialQuestionsDto(
    val examenId: String,
    val preguntas: List<AdminOfficialQuestionDto>,
    /** `false` = el servidor conectado no tiene aún las columnas de reserva: se muestran las anuladas y se avisa. */
    val reservasDisponibles: Boolean,
    val chequeo: AdminGradingRulesCheckDto? = null,
)

private const val OFFICIAL_COLUMNS_FULL = "pregunta_id,posicion,anulada,motivo_anulacion,es_reserva,orden_reserva,es_demo,preguntas(titulo)"
private const val OFFICIAL_COLUMNS_LEGACY = "pregunta_id,posicion,anulada,motivo_anulacion,preguntas(titulo)"

private fun JsonObject.toOfficial(): AdminOfficialQuestionDto? {
    val id = this["pregunta_id"].contentOrNullSafe()?.takeIf(String::isUuid) ?: return null
    return AdminOfficialQuestionDto(
        preguntaId = id,
        posicion = this["posicion"].intOr(0),
        titulo = ((this["preguntas"] as? JsonObject)?.get("titulo")).contentOrNullSafe().orEmpty().controlSanitized(200),
        anulada = this["anulada"].boolOrFalse(),
        motivoAnulacion = this["motivo_anulacion"].contentOrNullSafe()?.controlSanitized(OfficialQuestionFlags.MOTIVO_MAX, allowNewlines = true),
        esReserva = this["es_reserva"].boolOrFalse(),
        ordenReserva = (this["orden_reserva"] as? JsonPrimitive)?.contentOrNullSafe()?.toIntOrNull(),
        esDemo = this["es_demo"].boolOrFalse(),
    )
}

internal fun AdminControlV1Gateway.officialQuestions(examenId: String): AdminOfficialQuestionsDto? {
    val exists = (json.parseToJsonElement(get("/rest/v1/examenes?select=id&id=eq.$examenId&limit=1")) as? JsonArray).orEmpty().isNotEmpty()
    if (!exists) return null
    val base = "/rest/v1/preguntas_examen?examen_id=eq.$examenId&order=posicion.asc&limit=1000&select="
    var withReserves = true
    val raw = try {
        get(base + OFFICIAL_COLUMNS_FULL)
    } catch (failure: IllegalStateException) {
        // Solo "columna inexistente" (42703 / PGRST204) degrada; un timeout o un 5xx se propagan como lo que son.
        val text = failure.message.orEmpty()
        if (!text.contains("42703") && !text.contains("PGRST204")) throw failure
        // Las columnas de reserva/demo pueden no existir todavía en el servidor conectado (42703): se lee lo que hay.
        withReserves = false
        get(base + OFFICIAL_COLUMNS_LEGACY)
    }
    val rows = (json.parseToJsonElement(raw) as? JsonArray).orEmpty().mapNotNull { it.jsonObject.toOfficial() }
    return AdminOfficialQuestionsDto(examenId, rows, withReserves, if (withReserves) officialRulesCheck(examenId) else null)
}

internal fun AdminControlV1Gateway.setOfficialQuestionFlags(
    examenId: String,
    preguntaId: String,
    input: OfficialQuestionFlags.Input,
): AdminOfficialQuestionsDto? {
    val actor = requireActor()
    rpcOrNotProvisioned(
        "admin_set_exam_question_flags",
        buildJsonObject {
            put("p_actor", JsonPrimitive(actor))
            put("p_examen", JsonPrimitive(examenId))
            put("p_pregunta", JsonPrimitive(preguntaId))
            put("p_anulada", JsonPrimitive(input.anulada))
            put("p_motivo", input.motivo?.let(::JsonPrimitive) ?: JsonNull)
            put("p_es_reserva", JsonPrimitive(input.esReserva))
            put("p_orden_reserva", input.ordenReserva?.let(::JsonPrimitive) ?: JsonNull)
        },
    )
    return officialQuestions(examenId)
}

fun Route.adminControlOfficialQuestionsRoutes(gateway: AdminControlV1Gateway) {
    route("/api/v1/admin/examenes/{id}/preguntas-oficiales") {
        get {
            val id = call.requiredPath("id").validatedUuid("id")
            val dto = withUpstreamIo { gateway.officialQuestions(id) } ?: throw CanonicalErrorCode.NOT_FOUND.exception("Examen no encontrado.")
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(dto)
        }
        put("/{preguntaId}") {
            val id = call.requiredPath("id").validatedUuid("id")
            val preguntaId = call.requiredPath("preguntaId").validatedUuid("preguntaId")
            val input = OfficialQuestionFlags.parse(call.receive<JsonObject>())
            val dto = withUpstreamIo { gateway.setOfficialQuestionFlags(id, preguntaId, input) }
                ?: throw CanonicalErrorCode.NOT_FOUND.exception("Examen no encontrado.")
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(dto)
        }
    }
}
