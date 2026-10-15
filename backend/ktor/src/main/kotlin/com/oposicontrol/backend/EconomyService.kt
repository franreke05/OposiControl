package com.oposicontrol.backend

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.OffsetDateTime

// =====================================================================
// Economia V1 (OposiTickets): canje de examen, paquetes, pedidos de pago y avisos del proveedor.
// Diseno: E1-V1-MINIDESIGN (co-firmado por E2). TODA la logica economica vive en las funciones SQL `app_*` (libro primero, un cerrojo por
// cartera, idempotencia en la base, reloj de la base); esto solo autentica, valida la forma, llama a la RPC y traduce errores.
//
//  - El usuario es SIEMPRE el `sub` del JWT. Ningun comando lleva usuario, saldo, precio pagado, cantidad de OT ni importe.
//  - `expectedPrice` del canje es una GUARDA ("este es el precio que vi"), nunca el precio: si no coincide, 409 PRICE_CHANGED.
//  - Precios, cantidades y moneda salen de `examenes` / `productos_comerciales`; un valor sin fijar es "no comprable", no un invento.
// =====================================================================

// ---------- comandos (cuerpo de peticion) ----------

/** D6-03. El examen y, opcionalmente, el precio que el cliente mostro al usuario. */
@Serializable
@CanonicalCommand
internal data class ExamRedeemRequest(val examId: String, val expectedPrice: Int? = null)

/** D6-12. Paquete (codigo del catalogo) y proveedor (lista blanca del servidor). Nada de importes. */
@Serializable
@CanonicalCommand
internal data class PaymentOrderRequest(val productCode: String, val provider: String)

/** D6-14. Justificante opaco del proveedor; la verificacion y el credito los decide el servidor. */
@Serializable
@CanonicalCommand
internal data class PaymentVerifyRequest(val receipt: String)

// ---------- cable (respuestas) ----------

@Serializable
internal data class ExamRedeemDto(
    val transactionId: String,
    val examId: String,
    val pricePaid: Int,
    val currency: String,
    val purchasedAt: String,
    val validFrom: String,
    val validUntil: String,
    val saldo: Int,
)

@Serializable
internal data class ExamRedeemResponse(val canje: ExamRedeemDto)

internal data class ExamRedeemResult(val response: ExamRedeemResponse, val replayed: Boolean)

@Serializable
internal data class TicketPackDto(
    val code: String,
    val name: String,
    val description: String? = null,
    val tickets: Int,
    val priceCents: Int,
    val currency: String,
    val featured: Boolean,
    val promoLabel: String? = null,
)

@Serializable
internal data class TicketPacksResponse(val items: List<TicketPackDto>)

@Serializable
internal data class PaymentOrderDto(
    val id: String,
    val status: String,
    val productCode: String,
    val tickets: Int,
    val amountCents: Int,
    val currency: String,
    val provider: String,
    val expiresAt: String?,
    val paidAt: String?,
    val balance: Int,
)

@Serializable
internal data class PaymentOrderResponse(val order: PaymentOrderDto)

internal data class PaymentOrderResult(val response: PaymentOrderResponse, val created: Boolean, val replayed: Boolean)

@Serializable
internal data class WebhookAckDto(val result: String)

// ---------- filas (snake_case, tal cual salen de las RPC) ----------

@Serializable
internal data class RedeemRow(
    val outcome: String,
    @SerialName("transaccion_id") val transaccionId: String,
    @SerialName("examen_id") val examenId: String,
    @SerialName("precio_pagado") val precioPagado: Int,
    @SerialName("valido_desde") val validoDesde: String,
    @SerialName("valido_hasta") val validoHasta: String,
    @SerialName("comprado_en") val compradoEn: String,
    val saldo: Int,
)

@Serializable
internal data class OrderRow(
    val outcome: String,
    @SerialName("orden_id") val ordenId: String,
    val estado: String,
    @SerialName("producto_codigo") val productoCodigo: String,
    @SerialName("cantidad_tickets") val cantidadTickets: Int,
    @SerialName("importe_centimos") val importeCentimos: Int,
    val moneda: String,
    val proveedor: String,
    @SerialName("expira_en") val expiraEn: String? = null,
    @SerialName("pagada_en") val pagadaEn: String? = null,
    @SerialName("transaccion_id") val transaccionId: String? = null,
    val saldo: Int,
)

@Serializable
internal data class PackRow(
    val codigo: String,
    val nombre: String,
    val descripcion: String? = null,
    @SerialName("cantidad_tickets") val cantidadTickets: Int,
    @SerialName("precio_centimos") val precioCentimos: Int,
    val moneda: String,
    val destacado: Boolean = false,
    @SerialName("etiqueta_promocional") val etiquetaPromocional: String? = null,
)

// ---------- puerto hacia la base ----------

internal interface EconomyUpstream {
    fun get(path: String): String

    fun rpc(function: String, body: String): SupabaseRawResponse
}

internal class TransportEconomyUpstream(private val transport: SupabaseRequestTransport) : EconomyUpstream {
    override fun get(path: String): String = transport.get(path)

    override fun rpc(function: String, body: String): SupabaseRawResponse = transport.rpcRaw(function, body)

    companion object {
        fun fromEnvironment(environment: (String) -> String? = System::getenv): TransportEconomyUpstream? =
            SupabaseRequestTransport.fromEnvironment(environment)?.let(::TransportEconomyUpstream)
    }
}

/** Traduccion de las claves estables de las RPC economicas (`message`, con datos del PROPIO usuario en `detail`) al sobre canonico. */
internal object EconomyRpcErrors {
    fun failure(function: String, response: SupabaseRawResponse): Exception {
        val error = runCatching { Json.parseToJsonElement(response.body).jsonObject }.getOrNull()
        val key = error?.get("message")?.jsonPrimitive?.contentOrNull.orEmpty().trim().takeWhile { it.isLetterOrDigit() || it == '_' }
        val detail = error?.get("details")?.jsonPrimitive?.contentOrNull?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        return when (key) {
            "EXAM_NOT_FOUND" -> CanonicalErrorCode.EXAM_NOT_FOUND.exception()
            "NOT_PURCHASABLE" -> CanonicalErrorCode.NOT_PURCHASABLE.exception()
            "PRICE_CHANGED" -> CanonicalErrorCode.PRICE_CHANGED.exception(details = ErrorDetails(price = detail.int("price")))
            "ALREADY_ACCESSIBLE" -> CanonicalErrorCode.ALREADY_ACCESSIBLE.exception(details = ErrorDetails(accessState = detail?.get("state")?.jsonPrimitive?.contentOrNull))
            "INSUFFICIENT_BALANCE" -> CanonicalErrorCode.INSUFFICIENT_BALANCE.exception(
                details = ErrorDetails(balance = detail.int("balance"), price = detail.int("price")),
            )
            "IDEMPOTENCY_KEY_REUSED" -> CanonicalErrorCode.IDEMPOTENCY_KEY_REUSED.exception()
            "IDEMPOTENCY_KEY_REQUIRED" -> CanonicalErrorCode.IDEMPOTENCY_KEY_REQUIRED.exception()
            "PRODUCT_NOT_AVAILABLE" -> CanonicalErrorCode.PRODUCT_NOT_AVAILABLE.exception()
            "ORDER_NOT_FOUND" -> CanonicalErrorCode.ORDER_NOT_FOUND.exception()
            "ORDER_REQUEST_INVALID", "EVENT_INVALID" -> CanonicalErrorCode.VALIDATION.exception()
            // Perfil inexistente: el resolver de identidad ya lo habria cortado; aqui es una carrera con el borrado de cuenta.
            "WALLET_INVALID_USER" -> CanonicalErrorCode.PROFILE_MISSING.exception()
            else -> ProfileRpcErrors.failure(function, response)
        }
    }

    private fun JsonObject?.int(name: String): Int? = this?.get(name)?.jsonPrimitive?.contentOrNull?.toIntOrNull()
}

// ---------- servicio ----------

internal class EconomyService(
    private val upstream: EconomyUpstream,
    private val providers: PaymentProviders,
    private val clock: () -> Instant = Instant::now,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /** D6-03. Atomico e idempotente en la base: debito + derecho en UNA transaccion; la misma clave y el mismo cuerpo repiten el resultado. */
    suspend fun redeem(userId: CanonicalUserId, request: ExamRedeemRequest, key: IdempotencyKey): ExamRedeemResult {
        val examId = request.examId.trim()
        if (!examId.isUuid()) throw validationError(ErrorIssue("examId", CanonicalIssue.INVALID_FORMAT))
        request.expectedPrice?.let { if (it < 1) throw validationError(ErrorIssue("expectedPrice", CanonicalIssue.OUT_OF_RANGE)) }
        val rows = rpc<RedeemRow>(
            "app_redeem_exam_access",
            buildJsonObject {
                put("p_usuario", userId.value)
                put("p_examen", examId.lowercase())
                put("p_idem_key", key.value)
                request.expectedPrice?.let { put("p_expected_price", it) }
            }.toString(),
        )
        val row = rows.singleOrNull() ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        return ExamRedeemResult(
            ExamRedeemResponse(
                ExamRedeemDto(
                    transactionId = row.transaccionId,
                    examId = row.examenId,
                    pricePaid = row.precioPagado,
                    currency = OT_CURRENCY,
                    purchasedAt = iso(row.compradoEn),
                    validFrom = iso(row.validoDesde),
                    validUntil = iso(row.validoHasta),
                    saldo = row.saldo,
                ),
            ),
            replayed = row.outcome == "REPLAY",
        )
    }

    /** D6-11. Solo paquetes activos, visibles y con cantidad Y precio fijados: un paquete a medias no se vende. */
    suspend fun packs(): TicketPacksResponse {
        val body = canonicalUpstreamIo {
            upstream.get(
                "/rest/v1/productos_comerciales?select=codigo,nombre,descripcion,cantidad_tickets,precio_centimos,moneda,destacado,etiqueta_promocional" +
                    "&tipo_producto=eq.pack_tickets&activo=eq.true&visible=eq.true&cantidad_tickets=gt.0&precio_centimos=gt.0&order=posicion.asc,codigo.asc",
            )
        }
        val rows = decode<PackRow>(body)
        return TicketPacksResponse(
            rows.map {
                TicketPackDto(it.codigo, it.nombre, it.descripcion, it.cantidadTickets, it.precioCentimos, it.moneda, it.destacado, it.etiquetaPromocional)
            },
        )
    }

    /** D6-12. Importe, cantidad y moneda se congelan en la base desde el catalogo. Idempotente por (usuario, clave). */
    suspend fun createOrder(userId: CanonicalUserId, request: PaymentOrderRequest, key: IdempotencyKey): PaymentOrderResult {
        val productCode = request.productCode.trim()
        if (productCode.isEmpty() || productCode.length > MAX_CODE_LENGTH) throw validationError(ErrorIssue("productCode", CanonicalIssue.INVALID_FORMAT))
        val provider = providers.find(request.provider) ?: throw CanonicalErrorCode.PAYMENT_PROVIDER_UNAVAILABLE.exception()
        val row = rpc<OrderRow>(
            "app_create_payment_order",
            buildJsonObject {
                put("p_usuario", userId.value)
                put("p_producto", productCode)
                put("p_proveedor", provider.id)
                put("p_idem_key", key.value)
            }.toString(),
        ).singleOrNull() ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        return PaymentOrderResult(row.toResponse(), created = row.outcome == "CREATED", replayed = row.outcome == "REPLAY")
    }

    /** D6-13. Un pedido ajeno es 404 igual que uno inexistente. La caducidad se aplica al leer (reloj de la base). */
    suspend fun getOrder(userId: CanonicalUserId, orderId: String): PaymentOrderResponse {
        if (!orderId.isUuid()) throw validationError(ErrorIssue("orderId", CanonicalIssue.INVALID_FORMAT))
        return readOrder(userId, orderId.lowercase()).toResponse()
    }

    /**
     * D6-14. Confirma el pago con un justificante opaco atado al usuario y al pedido. El credito lo decide la base (una sola vez por evento
     * y por pedido); reintentar devuelve el mismo pedido. Un justificante de otro usuario o pedido es 422 PAYMENT_RECEIPT_INVALID.
     */
    suspend fun verify(userId: CanonicalUserId, orderId: String, request: PaymentVerifyRequest): PaymentOrderResponse {
        if (!orderId.isUuid()) throw validationError(ErrorIssue("orderId", CanonicalIssue.INVALID_FORMAT))
        if (request.receipt.isBlank() || request.receipt.length > MAX_RECEIPT_LENGTH) throw validationError(ErrorIssue("receipt", CanonicalIssue.INVALID_FORMAT))
        val id = orderId.lowercase()
        val order = readOrder(userId, id)
        val provider = providers.find(order.proveedor) ?: throw CanonicalErrorCode.PAYMENT_PROVIDER_UNAVAILABLE.exception()
        val receipt = provider.verifyReceipt(request.receipt, userId.value, id)
        return applyReceipt(receipt).order.toResponseDto()
    }

    /** Aviso del proveedor. Firma y frescura ya verificadas por [PaymentProvider.parseWebhook]: sin firma buena no se escribe NADA. */
    suspend fun webhook(providerId: String, header: (String) -> String?, rawBody: ByteArray): String {
        val provider = providers.find(providerId) ?: throw CanonicalErrorCode.NOT_FOUND.exception()
        val receipt = provider.parseWebhook(header, rawBody, clock())
        if (receipt.kind == PaymentEventKind.IGNORED) return "IGNORED"
        return applyReceipt(receipt).outcome
    }

    private suspend fun applyReceipt(receipt: PaymentReceipt): WalletCredit {
        if (!receipt.orderId.isUuid() || receipt.eventId.isBlank() || receipt.eventId.length > MAX_EVENT_ID_LENGTH) throw CanonicalErrorCode.VALIDATION.exception()
        val row = when (receipt.kind) {
            PaymentEventKind.PAID -> rpc<OrderRow>(
                "app_credit_payment_order",
                buildJsonObject {
                    put("p_proveedor", receipt.provider)
                    put("p_evento_id", receipt.eventId)
                    put("p_orden", receipt.orderId)
                    put("p_transaccion", receipt.transactionId)
                    put("p_importe", receipt.amountCents)
                    put("p_moneda", receipt.currency)
                    put("p_producto", receipt.productCode)
                }.toString(),
            )
            PaymentEventKind.REFUNDED -> rpc<OrderRow>(
                "app_refund_payment_order",
                buildJsonObject {
                    put("p_proveedor", receipt.provider)
                    put("p_evento_id", receipt.eventId)
                    put("p_orden", receipt.orderId)
                }.toString(),
            )
            PaymentEventKind.IGNORED -> throw IllegalStateException("IGNORED no se aplica")
        }.singleOrNull() ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        return WalletCredit(row.outcome, row.toOrder())
    }

    private suspend fun readOrder(userId: CanonicalUserId, orderId: String): OrderRow =
        rpc<OrderRow>(
            "app_get_payment_order",
            buildJsonObject {
                put("p_usuario", userId.value)
                put("p_orden", orderId)
            }.toString(),
        ).singleOrNull() ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()

    private suspend inline fun <reified T> rpc(function: String, body: String): List<T> {
        val response = canonicalUpstreamIo { upstream.rpc(function, body) }
        if (!response.ok) throw EconomyRpcErrors.failure(function, response)
        return decode(response.body)
    }

    private inline fun <reified T> decode(body: String): List<T> =
        runCatching { json.decodeFromString(ListSerializer(kotlinx.serialization.serializer<T>()), body) }
            .getOrElse { throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception(cause = it) }

    private fun OrderRow.toOrder() = PaymentOrder(
        id = ordenId,
        status = PaymentStatus.fromWire(estado),
        productCode = productoCodigo,
        tickets = cantidadTickets,
        amountCents = importeCentimos,
        currency = moneda,
        provider = proveedor,
        expiresAt = expiraEn?.let(::iso),
        paidAt = pagadaEn?.let(::iso),
        ledgerTransactionId = transaccionId,
        balance = saldo,
    )

    private fun OrderRow.toResponse() = PaymentOrderResponse(toOrder().toDto())

    private fun PaymentOrder.toResponseDto() = PaymentOrderResponse(toDto())

    private fun PaymentOrder.toDto() = PaymentOrderDto(id, status.name, productCode, tickets, amountCents, currency, provider, expiresAt, paidAt, balance)

    private fun iso(value: String): String =
        runCatching { OffsetDateTime.parse(value).toInstant().toString() }.getOrNull() ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()

    companion object {
        const val OT_CURRENCY = "OPOSITICKETS"
        const val MAX_CODE_LENGTH = 64
        const val MAX_RECEIPT_LENGTH = 4096
        const val MAX_EVENT_ID_LENGTH = 128
    }
}
