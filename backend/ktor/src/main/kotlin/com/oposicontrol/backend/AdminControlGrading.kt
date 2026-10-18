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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Reglas de puntuacion de un examen (V1): `regla_anuladas`, penalizacion racional o fraccion fija, y el rastro de
 * auditoria. Contrato de servidor: `E1-V1-CONTROL-SPEC-OFFICIAL-EXAMS.md` y `E1-V1-PENALTY-CONTRACT.md`.
 *
 * ### Exclusion mutua
 * La penalizacion racional (1/(n-1) por fallo) y la fraccion fija no se mezclan: el CHECK `examenes_penalizacion_chk`
 * rechaza `penalizacion_racional AND penalizacion_num > 0`. Aqui se rechaza ANTES (422 `GRADING_PENALTY_CONFLICT`) y
 * nada se normaliza en silencio: quien manda las dos cosas recibe el error, no una de ellas.
 *
 * ### Cuando afecta
 * Cambiar una regla solo afecta a los intentos que EMPIEZAN despues (la regla se congela al empezar el intento).
 */
internal object GradingRules {
    val REGLAS_ANULADAS: Set<String> = setOf("REEMPLAZO_POR_RESERVA", "NO_PUNTUA")
    const val MAX_DEN = 100
    const val MOTIVO_MAX = 300
    const val MOTIVO_MIN = 3

    val AUDIT_ACTIONS: List<String> = listOf("examen_reglas_puntuacion_modificadas", "examen_reglas_puntuacion_motivo")

    data class Input(
        val reglaAnuladas: String?,
        val penalizacionRacional: Boolean,
        val penalizacionNum: Int,
        val penalizacionDen: Int,
        val motivo: String?,
    )

    private val KEYS = setOf("reglaAnuladas", "penalizacionRacional", "penalizacionNum", "penalizacionDen", "motivo")

    /** Valida el cuerpo ENTERO antes de salir a la red. Todas las claves de regla son obligatorias (sin defaults mudos). */
    fun parse(raw: JsonObject): Input {
        raw.requireOnlyKeys(KEYS)
        if (!raw.containsKey("reglaAnuladas")) controlBadBody()
        val regla = raw.optionalString("reglaAnuladas")?.trim()?.ifEmpty { null }
        if (regla != null && regla !in REGLAS_ANULADAS) {
            controlReject("GRADING_RULE_INVALID", "La regla de anuladas no es valida. Usa REEMPLAZO_POR_RESERVA, NO_PUNTUA o la regla derivada.")
        }
        val racional = raw.requiredBool("penalizacionRacional")
        val num = raw.requiredInt("penalizacionNum")
        val den = raw.requiredInt("penalizacionDen")
        if (racional && num != 0) {
            controlReject(
                "GRADING_PENALTY_CONFLICT",
                "La penalizacion racional y la fraccion fija son excluyentes: con racional la fraccion tiene que ser 0.",
            )
        }
        if (den < 1 || den > MAX_DEN || num < 0 || num > den) {
            controlReject(
                "GRADING_FRACTION_INVALID",
                "La fraccion de penalizacion no es valida: el denominador va de 1 a $MAX_DEN y el numerador de 0 al denominador.",
            )
        }
        if (racional && den != 1) {
            controlReject("GRADING_FRACTION_INVALID", "Con penalizacion racional la fraccion tiene que ser 0/1.")
        }
        val motivo = raw.optionalString("motivo")?.controlSanitized(MOTIVO_MAX)?.ifEmpty { null }
        return Input(regla, racional, num, den, motivo)
    }

    /** El motivo es obligatorio cuando el examen ya esta publicado: cambiar reglas de un examen vivo debe poder explicarse. */
    fun requireReasonWhenPublished(published: Boolean, motivo: String?) {
        if (published && (motivo == null || motivo.length < MOTIVO_MIN)) {
            controlReject("GRADING_REASON_REQUIRED", "El examen esta publicado: indica el motivo del cambio de reglas (minimo $MOTIVO_MIN caracteres).")
        }
    }
}

@Serializable
data class AdminGradingRulesDto(
    val examenId: String,
    val reglaAnuladas: String? = null,
    val penalizacionRacional: Boolean = false,
    val penalizacionNum: Int = 0,
    val penalizacionDen: Int = 1,
    val esOficial: Boolean = false,
    val publicado: Boolean = false,
    val reservas: Int = 0,
    val anuladasPrincipales: Int = 0,
    /** Resultado de `admin_official_rules_check`; `null` = no se pudo comprobar (el panel lo dice, no lo inventa). */
    val chequeo: AdminGradingRulesCheckDto? = null,
)

@Serializable
data class AdminGradingRulesCheckDto(
    val problema: String? = null,
    val avisos: List<String> = emptyList(),
    val regla: String? = null,
)

@Serializable
data class AdminGradingAuditEntryDto(
    val id: String,
    val creadoEn: String? = null,
    val actorId: String? = null,
    val motivo: String? = null,
    val antes: AdminGradingSnapshotDto? = null,
    val despues: AdminGradingSnapshotDto? = null,
)

@Serializable
data class AdminGradingSnapshotDto(
    val reglaAnuladas: String? = null,
    val penalizacionRacional: Boolean = false,
    val penalizacionNum: Int = 0,
    val penalizacionDen: Int = 1,
)

@Serializable
data class AdminGradingAuditResponse(val data: List<AdminGradingAuditEntryDto>)

private const val GRADING_AUDIT_LIMIT = 50

internal fun AdminControlV1Gateway.getGradingRules(examenId: String): AdminGradingRulesDto? {
    val rows = json.parseToJsonElement(
        get(
            "/rest/v1/examenes?select=id,es_oficial,publicado_en,regla_anuladas,penalizacion_num,penalizacion_den,penalizacion_racional" +
                "&id=eq.$examenId&limit=1",
        ),
    ) as? JsonArray ?: return null
    val exam = rows.firstOrNull()?.jsonObject ?: return null
    val counts = json.parseToJsonElement(
        get("/rest/v1/preguntas_examen?select=es_reserva,anulada&examen_id=eq.$examenId&limit=1000"),
    ) as? JsonArray ?: JsonArray(emptyList())
    val reservas = counts.count { it.jsonObject["es_reserva"].boolOrFalse() }
    val anuladas = counts.count { !it.jsonObject["es_reserva"].boolOrFalse() && it.jsonObject["anulada"].boolOrFalse() }
    return AdminGradingRulesDto(
        examenId = examenId,
        reglaAnuladas = exam["regla_anuladas"].contentOrNullSafe(),
        penalizacionRacional = exam["penalizacion_racional"].boolOrFalse(),
        penalizacionNum = exam["penalizacion_num"].intOr(0),
        penalizacionDen = exam["penalizacion_den"].intOr(1),
        esOficial = exam["es_oficial"].boolOrFalse(),
        publicado = exam["publicado_en"].contentOrNullSafe() != null,
        reservas = reservas,
        anuladasPrincipales = anuladas,
        chequeo = officialRulesCheck(examenId),
    )
}

/** `null` si la funcion no esta (aun) en el servidor o falla: el chequeo es informativo, nunca bloquea la lectura. */
internal fun AdminControlV1Gateway.officialRulesCheck(examenId: String): AdminGradingRulesCheckDto? = try {
    val body = rpcOrNotProvisioned("admin_official_rules_check", buildJsonObject { put("p_examen", JsonPrimitive(examenId)) })
    val obj = json.parseToJsonElement(body).jsonObject
    AdminGradingRulesCheckDto(
        problema = obj["problema"].contentOrNullSafe(),
        avisos = (obj["avisos"] as? JsonArray)?.mapNotNull { it.contentOrNullSafe() }.orEmpty(),
        regla = obj["regla"].contentOrNullSafe(),
    )
} catch (_: BackendDomainException) {
    null
} catch (_: IllegalStateException) {
    null
}

/**
 * Guarda las reglas. 404 (`null`) si el examen no existe. El PATCH es una sola sentencia con las cuatro columnas: la
 * base valida (CHECK + trigger diferido `OFFICIAL_RULES_INVALID`) y su trigger de auditoria deja la fila antes/despues.
 * El motivo y el actor, que el trigger no conoce (la llamada es de servicio), se escriben en una segunda fila.
 */
internal fun AdminControlV1Gateway.putGradingRules(examenId: String, input: GradingRules.Input): AdminGradingRulesDto? {
    val actor = requireActor()
    val current = getGradingRules(examenId) ?: return null
    GradingRules.requireReasonWhenPublished(current.publicado, input.motivo)
    // Camino principal: RPC transaccional. El actor y el motivo llegan al trigger de auditoria (usuario_id = admin,
    // despues.motivo = motivo): una sola fila de rastro y ninguna escritura posterior que pueda fallar (DEBT-159).
    val rpcDone = try {
        rpcOrNotProvisioned(
            "admin_set_exam_grading_rules",
            buildJsonObject {
                put("p_actor", JsonPrimitive(actor))
                put("p_examen", JsonPrimitive(examenId))
                put("p_regla_anuladas", input.reglaAnuladas?.let(::JsonPrimitive) ?: JsonNull)
                put("p_penalizacion_num", JsonPrimitive(input.penalizacionNum))
                put("p_penalizacion_den", JsonPrimitive(input.penalizacionDen))
                put("p_penalizacion_racional", JsonPrimitive(input.penalizacionRacional))
                put("p_motivo", input.motivo?.let(::JsonPrimitive) ?: JsonNull)
            },
        )
        true
    } catch (e: BackendDomainException) {
        // Solo la ausencia de la funcion degrada al camino antiguo; cualquier otro error de dominio se propaga.
        if (e.errorCode != CONTROL_RPC_NOT_PROVISIONED) throw e
        false
    }
    if (rpcDone) return getGradingRules(examenId)
    val before = AdminGradingSnapshotDto(current.reglaAnuladas, current.penalizacionRacional, current.penalizacionNum, current.penalizacionDen)
    val patch = buildJsonObject {
        put("regla_anuladas", input.reglaAnuladas?.let(::JsonPrimitive) ?: JsonNull)
        put("penalizacion_racional", JsonPrimitive(input.penalizacionRacional))
        put("penalizacion_num", JsonPrimitive(input.penalizacionNum))
        put("penalizacion_den", JsonPrimitive(input.penalizacionDen))
    }
    val updated = send("PATCH", "/rest/v1/examenes?id=eq.$examenId", patch.toString(), prefer = "return=representation")
    if ((json.parseToJsonElement(updated) as? JsonArray).isNullOrEmpty()) return null
    val changed = before != AdminGradingSnapshotDto(input.reglaAnuladas, input.penalizacionRacional, input.penalizacionNum, input.penalizacionDen)
    if (changed) {
        // Segunda fila, con actor y motivo: el trigger de la base escribe usuario_id nulo para una llamada de servicio.
        audit(
            accion = "examen_reglas_puntuacion_motivo",
            tabla = "examenes",
            registroId = examenId,
            antes = buildJsonObject {
                put("actor", JsonPrimitive(actor))
                put("publicado", JsonPrimitive(current.publicado))
            },
            despues = buildJsonObject { put("motivo", input.motivo?.let(::JsonPrimitive) ?: JsonNull) },
        )
    }
    return getGradingRules(examenId)
}

internal fun AdminControlV1Gateway.gradingAudit(examenId: String): List<AdminGradingAuditEntryDto> {
    val inList = GradingRules.AUDIT_ACTIONS.joinToString(",")
    val rows = json.parseToJsonElement(
        get(
            "/rest/v1/auditoria_log?select=id,usuario_id,accion,antes,despues,creado_en&tabla_afectada=eq.examenes" +
                "&registro_id=eq.$examenId&accion=in.($inList)&order=creado_en.desc,id.desc&limit=$GRADING_AUDIT_LIMIT",
        ),
    ) as? JsonArray ?: return emptyList()
    return rows.mapNotNull { element ->
        val row = element.jsonObject
        val id = row["id"].contentOrNullSafe() ?: return@mapNotNull null
        val accion = row["accion"].contentOrNullSafe()
        if (accion == "examen_reglas_puntuacion_motivo") {
            AdminGradingAuditEntryDto(
                id = id,
                creadoEn = row["creado_en"].contentOrNullSafe(),
                actorId = row["usuario_id"].contentOrNullSafe() ?: (row["antes"] as? JsonObject)?.get("actor").contentOrNullSafe(),
                motivo = ((row["despues"] as? JsonObject)?.get("motivo")).contentOrNullSafe()?.controlSanitized(GradingRules.MOTIVO_MAX),
            )
        } else {
            AdminGradingAuditEntryDto(
                id = id,
                creadoEn = row["creado_en"].contentOrNullSafe(),
                actorId = row["usuario_id"].contentOrNullSafe(),
                motivo = ((row["despues"] as? JsonObject)?.get("motivo")).contentOrNullSafe()?.controlSanitized(GradingRules.MOTIVO_MAX),
                antes = (row["antes"] as? JsonObject)?.toSnapshot(),
                despues = (row["despues"] as? JsonObject)?.toSnapshot(),
            )
        }
    }
}

private fun JsonObject.toSnapshot() = AdminGradingSnapshotDto(
    reglaAnuladas = this["regla_anuladas"].contentOrNullSafe(),
    penalizacionRacional = this["penalizacion_racional"].boolOrFalse(),
    penalizacionNum = this["penalizacion_num"].intOr(0),
    penalizacionDen = this["penalizacion_den"].intOr(1),
)

internal fun JsonElement?.boolOrFalse(): Boolean = (this as? JsonPrimitive)?.booleanOrNull ?: false

internal fun JsonElement?.intOr(default: Int): Int = (this as? JsonPrimitive)?.intOrNull ?: default

fun Route.adminControlGradingRoutes(gateway: AdminControlV1Gateway) {
    route("/api/v1/admin/examenes/{id}/reglas-puntuacion") {
        get {
            val id = call.requiredPath("id").validatedUuid("id")
            val rules = withUpstreamIo { gateway.getGradingRules(id) }
                ?: throw CanonicalErrorCode.NOT_FOUND.exception("Examen no encontrado.")
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(rules)
        }
        put {
            val id = call.requiredPath("id").validatedUuid("id")
            val input = GradingRules.parse(call.receive<JsonObject>())
            val saved = withUpstreamIo { gateway.putGradingRules(id, input) }
                ?: throw CanonicalErrorCode.NOT_FOUND.exception("Examen no encontrado.")
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(saved)
        }
        get("/auditoria") {
            val id = call.requiredPath("id").validatedUuid("id")
            val entries = withUpstreamIo { gateway.gradingAudit(id) }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(AdminGradingAuditResponse(entries))
        }
    }
}

/**
 * Valor por defecto de un examen OFICIAL recién creado: penalización racional encendida (E1-V1-PENALTY-CONTRACT,
 * «Control spec»). Solo toca la fila si sigue siendo oficial y con el valor de la base (apagada): nunca pisa una decisión.
 * Devuelve `true` si cambió algo. El trigger de la base deja la fila antes/después; aquí se añade el autor y el motivo.
 */
internal fun AdminControlV1Gateway.applyOfficialExamDefaults(examenId: String): Boolean {
    val updated = send(
        "PATCH",
        "/rest/v1/examenes?id=eq.$examenId&es_oficial=is.true&penalizacion_racional=is.false",
        buildJsonObject { put("penalizacion_racional", JsonPrimitive(true)) }.toString(),
        prefer = "return=representation",
    )
    if ((json.parseToJsonElement(updated) as? JsonArray).isNullOrEmpty()) return false
    audit(
        accion = "examen_reglas_puntuacion_motivo",
        tabla = "examenes",
        registroId = examenId,
        antes = buildJsonObject {
            put("actor", actorId()?.let(::JsonPrimitive) ?: JsonNull)
            put("publicado", JsonPrimitive(false))
        },
        despues = buildJsonObject { put("motivo", JsonPrimitive("Valor por defecto de un examen oficial: penalización racional activada")) },
    )
    return true
}
