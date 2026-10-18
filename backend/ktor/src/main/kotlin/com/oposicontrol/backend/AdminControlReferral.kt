package com.oposicontrol.backend

import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Invita y gana, lado Control: lista de invitaciones y revocacion. La configuracion (`referido_*`) se edita por
 * [adminControlConfigRoutes] (`grupo=referido`).
 *
 * ### Contrato de servidor (E1-V1-W5-REFERRAL-CONTRACT.md)
 * Las tablas de invitaciones no tienen NINGUN permiso para `service_role` (RLS sin politicas): solo se llega a ellas por
 * funciones `SECURITY DEFINER`. Existe `admin_referral_revoke(p_actor, p_invitacion, p_motivo)`. La LISTA necesita
 * `admin_referral_list(p_estado text, p_cursor text, p_limite integer) returns jsonb` y NO existe todavia: esta ruta la
 * llama y, si el servidor no la tiene, contesta 501 `CONTROL_RPC_NOT_PROVISIONED` (el panel lo dice, no inventa una
 * lista vacia). Forma pedida a E1 en `E6-V1-PANEL-03.md`.
 *
 * ### Privacidad
 * Solo `handle` (nombre de usuario publico) y estados. Nunca email ni nombre real. El motivo de una revocacion es texto
 * libre acotado y saneado; el texto del invitado nunca se refleja.
 */
internal object ReferralRules {
    val ESTADOS: Set<String> = setOf("registrada", "email_verificado", "diferida", "premiada", "rechazada", "revocada", "error")
    const val MOTIVO_MIN = 3
    const val MOTIVO_MAX = 200
    const val DEFAULT_LIMIT = 25
    const val MAX_LIMIT = 50

    fun estado(raw: String?): String? {
        val v = raw?.trim()?.takeIf { it.isNotEmpty() && it != "todas" } ?: return null
        if (v !in ESTADOS) controlReject("REFERRAL_STATE_INVALID", "El estado de invitación no es válido.")
        return v
    }

    fun motivo(raw: String?): String {
        val clean = raw?.controlSanitized(MOTIVO_MAX).orEmpty()
        if (clean.length < MOTIVO_MIN) controlReject("REFERRAL_REASON_REQUIRED", "Indica el motivo de la revocación (mínimo $MOTIVO_MIN caracteres).")
        return clean
    }
}

@Serializable
data class AdminReferralItemDto(
    val id: String,
    val estado: String,
    val creadoEn: String? = null,
    val invitadorHandle: String? = null,
    val invitadoHandle: String? = null,
    val pagoInvitador: String? = null,
    val pagoInvitado: String? = null,
    val intentosPago: Int = 0,
    val ultimoError: String? = null,
    val diferidaHasta: String? = null,
    val premiadaEn: String? = null,
    val motivo: String? = null,
    /** Claves de las marcas (`mismo_dominio_raiz`, `ip_burst`, `inviter_cap`...) que valen `true`; nunca valores. */
    val marcas: List<String> = emptyList(),
)

@Serializable
data class AdminReferralPageDto(
    val items: List<AdminReferralItemDto>,
    val nextCursor: String? = null,
    val resumen: Map<String, Int> = emptyMap(),
)

@Serializable
data class AdminReferralRevokeResultDto(val estado: String, val revertido: Int, val noRevertible: Int)

internal fun AdminControlV1Gateway.listReferrals(estado: String?, cursor: String?, limit: Int): AdminReferralPageDto {
    val body = rpcOrNotProvisioned(
        "admin_referral_list",
        buildJsonObject {
            put("p_estado", estado?.let(::JsonPrimitive) ?: JsonNull)
            put("p_cursor", cursor?.let(::JsonPrimitive) ?: JsonNull)
            put("p_limite", JsonPrimitive(limit))
        },
    )
    val obj = json.parseToJsonElement(body) as? JsonObject ?: throw domainError("CONTROL_RPC_NOT_PROVISIONED")
    val items = (obj["items"] as? JsonArray).orEmpty().mapNotNull { e ->
        val r = e as? JsonObject ?: return@mapNotNull null
        val id = r["id"].contentOrNullSafe()?.takeIf { it.isUuid() } ?: return@mapNotNull null
        AdminReferralItemDto(
            id = id,
            estado = r["estado"].contentOrNullSafe()?.takeIf { it in ReferralRules.ESTADOS } ?: return@mapNotNull null,
            creadoEn = r["creadoEn"].contentOrNullSafe(),
            invitadorHandle = r["invitadorHandle"].contentOrNullSafe()?.controlSanitized(40),
            invitadoHandle = r["invitadoHandle"].contentOrNullSafe()?.controlSanitized(40),
            pagoInvitador = r["pagoInvitador"].contentOrNullSafe(),
            pagoInvitado = r["pagoInvitado"].contentOrNullSafe(),
            intentosPago = r["intentosPago"].intOr(0),
            ultimoError = r["ultimoError"].contentOrNullSafe()?.controlSanitized(120),
            diferidaHasta = r["diferidaHasta"].contentOrNullSafe(),
            premiadaEn = r["premiadaEn"].contentOrNullSafe(),
            motivo = r["motivo"].contentOrNullSafe()?.controlSanitized(ReferralRules.MOTIVO_MAX),
            marcas = (r["marcas"] as? JsonObject)?.filter { (it.value as? JsonPrimitive)?.contentOrNullSafe() == "true" }?.keys?.sorted().orEmpty(),
        )
    }
    val resumen = (obj["resumen"] as? JsonObject)?.mapNotNull { (k, v) ->
        k.takeIf { it in ReferralRules.ESTADOS }?.let { it to v.intOr(0) }
    }?.toMap().orEmpty()
    return AdminReferralPageDto(items, obj["nextCursor"].contentOrNullSafe(), resumen)
}

internal fun AdminControlV1Gateway.revokeReferral(id: String, motivo: String): AdminReferralRevokeResultDto {
    val actor = requireActor()
    val body = rpcOrNotProvisioned(
        "admin_referral_revoke",
        buildJsonObject {
            put("p_actor", JsonPrimitive(actor))
            put("p_invitacion", JsonPrimitive(id))
            put("p_motivo", JsonPrimitive(motivo))
        },
    )
    val obj = json.parseToJsonElement(body).jsonObject
    return AdminReferralRevokeResultDto(
        estado = obj["estado"].contentOrNullSafe() ?: "revocada",
        revertido = obj["revertido"].intOr(0),
        noRevertible = obj["noRevertible"].intOr(0),
    )
}

fun Route.adminControlReferralRoutes(gateway: AdminControlV1Gateway) {
    route("/api/v1/admin/referidos") {
        get("/invitaciones") {
            val params = call.request.queryParameters
            val estado = ReferralRules.estado(params["estado"])
            val limit = ModerationQueries.limit(params["limit"], ReferralRules.DEFAULT_LIMIT, ReferralRules.MAX_LIMIT)
            val cursor = params["cursor"]?.takeIf { it.isNotBlank() }?.also {
                if (it.length > 300 || !it.all { c -> c.isLetterOrDigit() || c in "-_=.~" }) throw CanonicalErrorCode.INVALID_CURSOR.exception()
            }
            val page = withUpstreamIo { gateway.listReferrals(estado, cursor, limit) }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(page)
        }
        post("/invitaciones/{id}/revocar") {
            val id = call.requiredPath("id").validatedUuid("id")
            val body = call.receive<JsonObject>()
            body.requireOnlyKeys(setOf("motivo"))
            val motivo = ReferralRules.motivo(body.optionalString("motivo"))
            val result = withUpstreamIo { gateway.revokeReferral(id, motivo) }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(result)
        }
    }
}
