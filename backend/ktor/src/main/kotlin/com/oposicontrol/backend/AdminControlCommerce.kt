package com.oposicontrol.backend

import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
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
 * Economia por examen y pedidos, lado Control.
 *
 * ### Economia por examen
 * Cada examen lleva SU precio en OposiTickets (`precio_tickets`), si se puede comprar con tickets
 * (`comprable_con_tickets`), los dias de acceso de la compra (`dias_acceso_compra`) y SU comunidad (obligatoria). Nada de
 * esto tiene valor por defecto de producto: lo fija el administrador al crear el examen (decision del propietario
 * 2026-10-16). La funcion de canje del servidor lee estos tres campos DENTRO de su transaccion y responde
 * `NOT_PURCHASABLE` si falta el precio o los dias; por eso aqui "comprable" exige precio > 0 y dias > 0, y se comprueba
 * sobre el estado RESULTANTE (lo guardado + lo que cambia), no solo sobre el cuerpo.
 *
 * ### Pedidos
 * `compras_usuario` es la orden de pago. Los estados `en_revision` y los casos de reembolso (`refund_case`) son de solo
 * LECTURA aqui: la maquina de estados de la base solo permite que una funcion de administrador resuelva `en_revision`, y
 * esa funcion ("later" en la migracion 20261022100400) NO existe todavia (peticion a E1 en `E6-V1-PANEL-06.md`). Lo unico
 * que Control escribe es una NOTA interna auditada: no cambia ningun saldo, estado ni acceso.
 */
internal object ExamEconomyRules {
    const val MAX_PRICE = 1_000_000
    const val MAX_DAYS = 3650
    private val KEYS = setOf("comprableConTickets", "precioTickets", "diasAccesoCompra", "comunidadId")

    data class Current(val comprable: Boolean, val precio: Int?, val dias: Int, val comunidadId: String?)
    data class Patch(val comprable: Boolean?, val precio: Int?, val precioPresent: Boolean, val dias: Int?, val comunidadId: String?)

    fun parse(raw: JsonObject): Patch {
        raw.requireOnlyKeys(KEYS)
        if (raw.isEmpty()) controlBadBody()
        val comprable = raw.optionalBool("comprableConTickets")
        val precioPresent = raw.containsKey("precioTickets")
        val precio = raw.optionalInt("precioTickets")
        val dias = raw.optionalInt("diasAccesoCompra")
        if (raw.containsKey("diasAccesoCompra") && dias == null) controlBadBody()
        if (raw.containsKey("comprableConTickets") && comprable == null) controlBadBody()
        val comunidad = raw.optionalString("comunidadId")?.trim()
        if (raw.containsKey("comunidadId")) {
            if (comunidad.isNullOrEmpty()) {
                controlReject("EXAM_COMMUNITY_REQUIRED", "Todo examen necesita una comunidad: no se puede dejar vacía.")
            }
            if (!comunidad.isUuid()) controlBadBody()
        }
        if (precio != null && (precio < 0 || precio > MAX_PRICE)) {
            controlReject("EXAM_PRICE_INVALID", "El precio en OT va de 0 a $MAX_PRICE.")
        }
        if (dias != null && (dias < 1 || dias > MAX_DAYS)) {
            controlReject("EXAM_ACCESS_DAYS_INVALID", "Los días de acceso van de 1 a $MAX_DAYS.")
        }
        return Patch(comprable, precio, precioPresent, dias, comunidad?.lowercase())
    }

    /** El estado resultante tiene que ser canjeable si se marca como comprable. Devuelve las columnas a escribir. */
    fun resulting(current: Current, p: Patch): JsonObject {
        val comprable = p.comprable ?: current.comprable
        val precio = if (p.precioPresent) p.precio else current.precio
        val dias = p.dias ?: current.dias
        if (comprable && (precio == null || precio <= 0)) {
            controlReject("EXAM_NOT_PURCHASABLE_PRICE", "Para venderlo con tickets el examen necesita un precio mayor que 0.")
        }
        if (comprable && dias <= 0) {
            controlReject("EXAM_NOT_PURCHASABLE_DAYS", "Para venderlo con tickets el examen necesita días de acceso.")
        }
        return buildJsonObject {
            if (p.comprable != null) put("comprable_con_tickets", JsonPrimitive(p.comprable))
            if (p.precioPresent) put("precio_tickets", precio?.let(::JsonPrimitive) ?: JsonNull)
            if (p.dias != null) put("dias_acceso_compra", JsonPrimitive(p.dias))
            if (p.comunidadId != null) put("comunidad_id", JsonPrimitive(p.comunidadId))
        }
    }
}

@Serializable
data class AdminExamEconomyDto(
    val id: String,
    val codigoExamen: String = "",
    val titulo: String = "",
    val tipoExamen: String = "",
    val esOficial: Boolean = false,
    val publicado: Boolean = false,
    val comunidadId: String? = null,
    val comunidadNombre: String? = null,
    val comprableConTickets: Boolean = false,
    val precioTickets: Int? = null,
    val diasAccesoCompra: Int = 30,
)

@Serializable
data class AdminExamEconomyPageDto(val data: List<AdminExamEconomyDto>, val total: Int, val limit: Int, val offset: Int)

private const val ECONOMY_COLUMNS =
    "id,codigo_examen,titulo,tipo_examen,es_oficial,publicado_en,comunidad_id,comprable_con_tickets,precio_tickets,dias_acceso_compra," +
        "comunidades_autonomas(nombre)"

private fun JsonObject.toEconomy(): AdminExamEconomyDto? {
    val id = this["id"].contentOrNullSafe()?.takeIf(String::isUuid) ?: return null
    return AdminExamEconomyDto(
        id = id,
        codigoExamen = this["codigo_examen"].contentOrNullSafe().orEmpty().controlSanitized(60),
        titulo = this["titulo"].contentOrNullSafe().orEmpty().controlSanitized(200),
        tipoExamen = this["tipo_examen"].contentOrNullSafe().orEmpty(),
        esOficial = this["es_oficial"].boolOrFalse(),
        publicado = this["publicado_en"].contentOrNullSafe() != null,
        comunidadId = this["comunidad_id"].contentOrNullSafe(),
        comunidadNombre = (this["comunidades_autonomas"] as? JsonObject)?.get("nombre").contentOrNullSafe()?.controlSanitized(80),
        comprableConTickets = this["comprable_con_tickets"].boolOrFalse(),
        precioTickets = (this["precio_tickets"] as? JsonPrimitive)?.contentOrNullSafe()?.toIntOrNull(),
        diasAccesoCompra = this["dias_acceso_compra"].intOr(30),
    )
}

internal fun AdminControlV1Gateway.listExamEconomy(limit: Int, offset: Int, soloSinComunidad: Boolean): AdminExamEconomyPageDto {
    val filter = if (soloSinComunidad) "&comunidad_id=is.null" else ""
    val page = transport.getPage(
        "/rest/v1/examenes?select=$ECONOMY_COLUMNS$filter&order=creado_en.desc,id.desc&limit=$limit&offset=$offset",
    )
    val rows = (json.parseToJsonElement(page.body) as? JsonArray).orEmpty().mapNotNull { it.jsonObject.toEconomy() }
    return AdminExamEconomyPageDto(rows, page.total, limit, offset)
}

internal fun AdminControlV1Gateway.updateExamEconomy(id: String, patch: ExamEconomyRules.Patch): AdminExamEconomyDto? {
    requireActor()
    val currentRow = (json.parseToJsonElement(
        get("/rest/v1/examenes?select=$ECONOMY_COLUMNS&id=eq.$id&limit=1"),
    ) as? JsonArray)?.firstOrNull()?.jsonObject ?: return null
    val current = currentRow.toEconomy() ?: return null
    val columns = ExamEconomyRules.resulting(
        ExamEconomyRules.Current(current.comprableConTickets, current.precioTickets, current.diasAccesoCompra, current.comunidadId),
        patch,
    )
    if (columns.isEmpty()) return current
    send("PATCH", "/rest/v1/examenes?id=eq.$id", columns.toString(), prefer = "return=minimal")
    audit(
        "examen_economia_modificada",
        "examenes",
        id,
        antes = buildJsonObject {
            put("comprable_con_tickets", JsonPrimitive(current.comprableConTickets))
            put("precio_tickets", current.precioTickets?.let(::JsonPrimitive) ?: JsonNull)
            put("dias_acceso_compra", JsonPrimitive(current.diasAccesoCompra))
            put("comunidad_id", current.comunidadId?.let(::JsonPrimitive) ?: JsonNull)
        },
        despues = columns,
    )
    return (json.parseToJsonElement(get("/rest/v1/examenes?select=$ECONOMY_COLUMNS&id=eq.$id&limit=1")) as? JsonArray)
        ?.firstOrNull()?.jsonObject?.toEconomy()
}

// ---------------------------------------------------------------- pedidos

internal object OrderRules {
    val ESTADOS: Set<String> = setOf("pendiente", "pagada", "cancelada", "reembolsada", "fallida", "expirada", "en_revision")
    const val NOTE_MIN = 3
    const val NOTE_MAX = 500
    const val DEFAULT_LIMIT = 25
    const val MAX_LIMIT = 100

    fun estado(raw: String?): String? {
        val v = raw?.trim()?.takeIf { it.isNotEmpty() && it != "todos" } ?: return null
        if (v !in ESTADOS) controlReject("ORDER_STATE_INVALID", "El estado del pedido no es válido.")
        return v
    }

    fun note(raw: String?): String {
        val n = raw?.controlSanitized(NOTE_MAX, allowNewlines = true).orEmpty()
        if (n.length < NOTE_MIN) controlReject("ORDER_NOTE_REQUIRED", "La nota necesita al menos $NOTE_MIN caracteres.")
        return n
    }
}

@Serializable
data class AdminOrderDto(
    val id: String,
    val estado: String,
    val proveedor: String = "",
    val productoCodigo: String = "",
    val tipoProducto: String = "",
    val cantidadTickets: Int? = null,
    val precioCentimos: Int? = null,
    val moneda: String = "",
    val identificadorTransaccion: String? = null,
    val usuarioId: String? = null,
    val usuarioHandle: String? = null,
    val creadoEn: String? = null,
    val pagadaEn: String? = null,
    val expiraEn: String? = null,
    val actualizadoEn: String? = null,
    val reembolso: AdminRefundCaseDto? = null,
)

@Serializable
data class AdminRefundCaseDto(
    val id: String,
    val estado: String,
    val otRevertidos: Int,
    val otGastados: Int,
    val politica: String? = null,
    val creadoEn: String? = null,
    val resueltoEn: String? = null,
    /** Detalle del caso como texto acotado: para leer, no para interpretar. */
    val detalle: String? = null,
)

@Serializable
data class AdminOrderPageDto(val data: List<AdminOrderDto>, val total: Int, val limit: Int, val offset: Int, val resumen: Map<String, Int> = emptyMap(), val reembolsosHabilitados: Boolean = false)

private const val ORDER_COLUMNS =
    "id,usuario_id,proveedor,producto_codigo,tipo_producto,estado,precio_centimos,moneda,identificador_transaccion,cantidad_tickets,expira_en,pagada_en,creado_en,actualizado_en"

private fun JsonObject.toOrder(handles: Map<String, String?>, refunds: Map<String, AdminRefundCaseDto>): AdminOrderDto? {
    val id = this["id"].contentOrNullSafe()?.takeIf(String::isUuid) ?: return null
    val uid = this["usuario_id"].contentOrNullSafe()
    return AdminOrderDto(
        id = id,
        estado = this["estado"].contentOrNullSafe().orEmpty(),
        proveedor = this["proveedor"].contentOrNullSafe().orEmpty().controlSanitized(40),
        productoCodigo = this["producto_codigo"].contentOrNullSafe().orEmpty().controlSanitized(80),
        tipoProducto = this["tipo_producto"].contentOrNullSafe().orEmpty().controlSanitized(40),
        cantidadTickets = (this["cantidad_tickets"] as? JsonPrimitive)?.contentOrNullSafe()?.toIntOrNull(),
        precioCentimos = (this["precio_centimos"] as? JsonPrimitive)?.contentOrNullSafe()?.toIntOrNull(),
        moneda = this["moneda"].contentOrNullSafe().orEmpty().controlSanitized(8),
        identificadorTransaccion = this["identificador_transaccion"].contentOrNullSafe()?.controlSanitized(120),
        usuarioId = uid,
        usuarioHandle = uid?.let { handles[it] }?.controlSanitized(40),
        creadoEn = this["creado_en"].contentOrNullSafe(),
        pagadaEn = this["pagada_en"].contentOrNullSafe(),
        expiraEn = this["expira_en"].contentOrNullSafe(),
        actualizadoEn = this["actualizado_en"].contentOrNullSafe(),
        reembolso = refunds[id],
    )
}

private fun AdminControlV1Gateway.handlesOf(userIds: List<String>): Map<String, String?> {
    val ids = userIds.filter(String::isUuid).distinct()
    if (ids.isEmpty()) return emptyMap()
    return (json.parseToJsonElement(get("/rest/v1/perfiles_usuario?select=usuario_id,username&usuario_id=in.(${ids.joinToString(",")})")) as? JsonArray)
        .orEmpty().associate { it.jsonObject["usuario_id"].contentOrNullSafe().orEmpty() to it.jsonObject["username"].contentOrNullSafe() }
}

private fun AdminControlV1Gateway.refundsOf(orderIds: List<String>): Map<String, AdminRefundCaseDto> {
    val ids = orderIds.filter(String::isUuid)
    if (ids.isEmpty()) return emptyMap()
    return try {
        (json.parseToJsonElement(
            get("/rest/v1/refund_case?select=id,compra_id,estado,ot_revertidos,ot_gastados,politica,creado_en,resuelto_en,detalle&compra_id=in.(${ids.joinToString(",")})"),
        ) as? JsonArray).orEmpty().mapNotNull { e ->
            val r = e.jsonObject
            val compra = r["compra_id"].contentOrNullSafe() ?: return@mapNotNull null
            compra to AdminRefundCaseDto(
                id = r["id"].contentOrNullSafe().orEmpty(),
                estado = r["estado"].contentOrNullSafe().orEmpty(),
                otRevertidos = r["ot_revertidos"].intOr(0),
                otGastados = r["ot_gastados"].intOr(0),
                politica = r["politica"].contentOrNullSafe()?.controlSanitized(80),
                creadoEn = r["creado_en"].contentOrNullSafe(),
                resueltoEn = r["resuelto_en"].contentOrNullSafe(),
                detalle = null, // payload libre del proveedor: no se expone
            )
        }.toMap()
    } catch (_: IllegalStateException) {
        // La tabla puede no existir todavia en este servidor: el pedido se sirve sin su caso, no se rompe la lista.
        emptyMap()
    }
}

internal fun AdminControlV1Gateway.listOrders(estado: String?, limit: Int, offset: Int): AdminOrderPageDto {
    val filter = estado?.let { "&estado=eq.$it" }.orEmpty()
    val page = transport.getPage("/rest/v1/compras_usuario?select=$ORDER_COLUMNS$filter&order=creado_en.desc,id.desc&limit=$limit&offset=$offset")
    val rows = (json.parseToJsonElement(page.body) as? JsonArray).orEmpty().map { it.jsonObject }
    val handles = handlesOf(rows.mapNotNull { it["usuario_id"].contentOrNullSafe() })
    val refunds = refundsOf(rows.mapNotNull { it["id"].contentOrNullSafe() })
    return AdminOrderPageDto(rows.mapNotNull { it.toOrder(handles, refunds) }, page.total, limit, offset, reembolsosHabilitados = refundsEnabled())
}

internal fun AdminControlV1Gateway.orderReviewCount(): Int =
    transport.count("/rest/v1/compras_usuario?select=id&estado=eq.en_revision")

internal fun AdminControlV1Gateway.getOrder(id: String): AdminOrderDto? {
    val row = (json.parseToJsonElement(get("/rest/v1/compras_usuario?select=$ORDER_COLUMNS&id=eq.$id&limit=1")) as? JsonArray)
        ?.firstOrNull()?.jsonObject ?: return null
    return row.toOrder(handlesOf(listOfNotNull(row["usuario_id"].contentOrNullSafe())), refundsOf(listOf(id)))
}

internal fun AdminControlV1Gateway.addOrderNote(id: String, note: String): Boolean {
    requireActor()
    val exists = (json.parseToJsonElement(get("/rest/v1/compras_usuario?select=id&id=eq.$id&limit=1")) as? JsonArray).orEmpty().isNotEmpty()
    if (!exists) return false
    audit("pedido_nota_interna", "compras_usuario", id, despues = buildJsonObject { put("nota", JsonPrimitive(note)) })
    return true
}

internal object OrderResolution {
    val DECISIONES: Set<String> = setOf("acreditar", "confirmar", "rechazar", "reembolsar")
    const val NOTE_MIN = 3
    const val NOTE_MAX = 300

    fun decision(raw: String?): String {
        val v = raw?.trim().orEmpty()
        if (v !in DECISIONES) controlReject("ORDER_DECISION_INVALID", "La decisión sobre el pedido no es válida.")
        return v
    }

    fun note(raw: String?): String {
        val n = raw?.controlSanitized(NOTE_MAX).orEmpty()
        if (n.length < NOTE_MIN) controlReject("REASON_INVALID", "Indica el motivo de la decisión (entre $NOTE_MIN y $NOTE_MAX caracteres).")
        return n
    }
}

@Serializable
data class AdminOrderResolutionDto(
    val outcome: String,
    val estado: String,
    val ordenId: String,
    val casoReembolso: Boolean = false,
)

/** Resuelve un pedido en revision con `admin_payment_order_resolve` (idempotente, auditada en la misma transaccion). */
internal fun AdminControlV1Gateway.resolveOrder(id: String, decision: String, nota: String): AdminOrderResolutionDto {
    val actor = requireActor()
    val body = rpcOrNotProvisioned(
        "admin_payment_order_resolve",
        buildJsonObject {
            put("p_actor", JsonPrimitive(actor))
            put("p_orden", JsonPrimitive(id))
            put("p_decision", JsonPrimitive(decision))
            put("p_nota", JsonPrimitive(nota))
            put("p_reembolsos_habilitados", JsonPrimitive(refundsEnabled()))
        },
    )
    val obj = json.parseToJsonElement(body) as? JsonObject ?: throw domainError(CONTROL_RPC_NOT_PROVISIONED)
    return AdminOrderResolutionDto(
        outcome = obj["outcome"].contentOrNullSafe()?.controlSanitized(30).orEmpty(),
        estado = obj["estado"].contentOrNullSafe()?.takeIf { it in OrderRules.ESTADOS } ?: "",
        ordenId = id,
        casoReembolso = obj["casoReembolso"].boolOrFalse(),
    )
}

fun Route.adminControlCommerceRoutes(gateway: AdminControlV1Gateway) {
    route("/api/v1/admin/examenes-economia") {
        get {
            val params = call.request.queryParameters
            val limit = ModerationQueries.limit(params["limit"], 25, 100)
            val offset = params["offset"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
                it.toIntOrNull()?.takeIf { v -> v >= 0 } ?: throw CanonicalErrorCode.VALIDATION.exception()
            } ?: 0
            val sinComunidad = params["sinComunidad"] == "true"
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(withUpstreamIo { gateway.listExamEconomy(limit, offset, sinComunidad) })
        }
        patch("/{id}") {
            val id = call.requiredPath("id").validatedUuid("id")
            val patch = ExamEconomyRules.parse(call.receive<JsonObject>())
            val saved = withUpstreamIo { gateway.updateExamEconomy(id, patch) }
                ?: throw CanonicalErrorCode.NOT_FOUND.exception("Examen no encontrado.")
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(saved)
        }
    }
    route("/api/v1/admin/pedidos") {
        get {
            val params = call.request.queryParameters
            val estado = OrderRules.estado(params["estado"])
            val limit = ModerationQueries.limit(params["limit"], OrderRules.DEFAULT_LIMIT, OrderRules.MAX_LIMIT)
            val offset = params["offset"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
                it.toIntOrNull()?.takeIf { v -> v >= 0 } ?: throw CanonicalErrorCode.VALIDATION.exception()
            } ?: 0
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(withUpstreamIo { gateway.listOrders(estado, limit, offset) })
        }
        get("/en-revision/total") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(AdminOrderReviewCountDto(withUpstreamIo { gateway.orderReviewCount() }))
        }
        get("/{id}") {
            val id = call.requiredPath("id").validatedUuid("id")
            val order = withUpstreamIo { gateway.getOrder(id) } ?: throw CanonicalErrorCode.NOT_FOUND.exception("Pedido no encontrado.")
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(order)
        }
        post("/{id}/nota") {
            val id = call.requiredPath("id").validatedUuid("id")
            val body = call.receive<JsonObject>()
            body.requireOnlyKeys(setOf("nota"))
            val note = OrderRules.note(body.optionalString("nota"))
            if (!withUpstreamIo { gateway.addOrderNote(id, note) }) throw CanonicalErrorCode.NOT_FOUND.exception("Pedido no encontrado.")
            call.respond(AdminOrderNoteResultDto(true))
        }
        post("/{id}/resolver") {
            val id = call.requiredPath("id").validatedUuid("id")
            val body = call.receive<JsonObject>()
            body.requireOnlyKeys(setOf("decision", "nota"))
            val decision = OrderResolution.decision(body.optionalString("decision"))
            val nota = OrderResolution.note(body.optionalString("nota"))
            val result = withUpstreamIo { gateway.resolveOrder(id, decision, nota) }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(result)
        }
    }
}

@Serializable
data class AdminOrderReviewCountDto(val total: Int)

@Serializable
data class AdminOrderNoteResultDto(val registrada: Boolean)
