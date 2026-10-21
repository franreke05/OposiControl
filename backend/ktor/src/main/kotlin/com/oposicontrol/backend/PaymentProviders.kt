package com.oposicontrol.backend

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.system.exitProcess

// =====================================================================
// Fundamento de pagos V1 (E1-V1-MINIDESIGN seccion 2). NEUTRAL: nada de aqui conoce a Apple, Google ni a una pasarela.
//
//  - [PaymentProvider] es la unica costura: dado un aviso firmado (webhook) o un justificante opaco, devuelve un
//    [PaymentReceipt] VERIFICADO. Quien acredita OT es la base de datos (`app_credit_payment_order`), nunca el proveedor ni el cliente.
//  - [PaymentStatus] / [PaymentOrder] / [WalletCredit] son el vocabulario del servicio; los importes y la cantidad de OT salen SIEMPRE de
//    `productos_comerciales` en la base (congelados en el pedido). El cliente jamas envia un precio, una cantidad ni un saldo.
//  - [SandboxPaymentProvider] existe SOLO para pruebas locales. No se registra salvo que `PAYMENTS_SANDBOX_SECRET` este definido Y
//    `APP_ENV` valga exactamente `local` o `test` (ver [sandboxGuardViolation]); en cualquier otro entorno el proceso ni arranca.
//  - Los adaptadores reales (APPLE_STORE, GOOGLE_PLAY, WEB_EXTERNAL) NO existen: se anaden implementando [PaymentProvider] cuando el dueno
//    elija proveedor (ND-4). Nada de su configuracion ni de sus credenciales se inventa aqui.
// =====================================================================

internal enum class PaymentStatus(val wire: String) {
    PENDING("pendiente"),
    PAID("pagada"),
    FAILED("fallida"),
    CANCELLED("cancelada"),
    EXPIRED("expirada"),
    REFUNDED("reembolsada"),
    IN_REVIEW("en_revision"),
    ;

    companion object {
        fun fromWire(value: String): PaymentStatus =
            entries.firstOrNull { it.wire == value } ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
    }
}

/** Un pedido de pago tal como lo ve el consumidor. `balance` es el saldo actual del propio usuario. */
internal data class PaymentOrder(
    val id: String,
    val status: PaymentStatus,
    val productCode: String,
    val tickets: Int,
    val amountCents: Int,
    val currency: String,
    val provider: String,
    val expiresAt: String?,
    val paidAt: String?,
    val ledgerTransactionId: String?,
    val balance: Int,
)

/** Lo que un aviso verificado dice que ocurrio. La base lo compara con el pedido congelado antes de acreditar nada. */
internal enum class PaymentEventKind { PAID, REFUNDED, IGNORED }

internal data class PaymentReceipt(
    val provider: String,
    val eventId: String,
    val kind: PaymentEventKind,
    val orderId: String,
    val transactionId: String,
    val amountCents: Int,
    val currency: String,
    val productCode: String,
)

/** Resultado de acreditar (o no) un aviso: lo decide la base. `outcome` es CREDITED, CREDITED_LATE, REPLAY, EN_REVISION, REFUNDED, ... */
internal data class WalletCredit(val outcome: String, val order: PaymentOrder)

internal interface PaymentProvider {
    val id: String

    /** Valida firma, frescura y forma del aviso. Firma mala: 401 [CanonicalErrorCode.WEBHOOK_SIGNATURE_INVALID] sin tocar nada. */
    fun parseWebhook(header: (String) -> String?, rawBody: ByteArray, now: Instant): PaymentReceipt

    /** Valida un justificante opaco ATADO al usuario y al pedido. Cualquier otro usuario / pedido: [CanonicalErrorCode.PAYMENT_RECEIPT_INVALID]. */
    fun verifyReceipt(opaque: String, userId: String, orderId: String): PaymentReceipt
}

internal class PaymentProviders(private val byId: Map<String, PaymentProvider>) {
    /** Lista blanca: un proveedor desconocido NUNCA llega a la base. El id se normaliza a minusculas. */
    fun find(id: String): PaymentProvider? = byId[id.trim().lowercase()]

    val ids: Set<String> get() = byId.keys

    companion object {
        val NONE = PaymentProviders(emptyMap())

        fun fromEnvironment(environment: (String) -> String? = System::getenv): PaymentProviders {
            val providers = mutableListOf<PaymentProvider>()
            if (!environment(SANDBOX_SECRET_ENV).isNullOrEmpty()) {
                // Defensa en profundidad: aunque main() ya lo haya comprobado, el registro se niega por su cuenta.
                sandboxGuardViolation(environment)?.let { throw IllegalStateException(it) }
                providers += SandboxPaymentProvider(environment(SANDBOX_SECRET_ENV)!!)
            }
            return PaymentProviders(providers.associateBy { it.id })
        }
    }
}

// ---------- guarda del proveedor sandbox ----------

internal const val SANDBOX_SECRET_ENV = "PAYMENTS_SANDBOX_SECRET"
internal const val SANDBOX_GUARD_EXIT_CODE = 78

/**
 * `MUST_NOT_START_OUTSIDE_LOCAL`: el proveedor sandbox solo puede existir con `APP_ENV` EXACTAMENTE `local` o `test` (sin recortar,
 * sin cambiar mayusculas: `Local`, ` local`, `production`, `prod`, `staging`, vacio o ausente NO valen). Devuelve el motivo si se viola
 * (sin ningun valor secreto) o `null`. Sin `PAYMENTS_SANDBOX_SECRET` no hay sandbox y no hay nada que guardar.
 */
internal fun sandboxGuardViolation(environment: (String) -> String? = System::getenv): String? {
    if (environment(SANDBOX_SECRET_ENV).isNullOrEmpty()) return null
    val appEnv = environment("APP_ENV")
    if (appEnv == "local" || appEnv == "test") return null
    return "El proveedor de pago sandbox solo puede configurarse con APP_ENV=local o APP_ENV=test. Quita $SANDBOX_SECRET_ENV del entorno."
}

/** Se llama en `main()` ANTES de enlazar el puerto: si se viola, el proceso termina con codigo distinto de cero y nunca escucha. */
internal fun enforceSandboxProviderGuard(
    environment: (String) -> String? = System::getenv,
    fail: (String) -> Nothing = { message ->
        System.err.println("FATAL: $message")
        exitProcess(SANDBOX_GUARD_EXIT_CODE)
    },
) {
    sandboxGuardViolation(environment)?.let { fail(it) }
}

// ---------- proveedor sandbox (SOLO local / pruebas) ----------

@Serializable
private data class SandboxEvent(
    val eventId: String,
    val type: String,
    val orderId: String,
    val transactionId: String,
    val amountCents: Int,
    val currency: String,
    val productCode: String,
)

@Serializable
private data class SandboxReceiptPayload(
    val eventId: String,
    val orderId: String,
    val transactionId: String,
    val amountCents: Int,
    val currency: String,
    val productCode: String,
)

/**
 * Proveedor de pruebas. Aviso: cabeceras `X-Sandbox-Timestamp` (epoch en segundos) y `X-Sandbox-Signature` = HMAC-SHA256 en hex de
 * `"<timestamp>.<cuerpo crudo>"`, tolerancia [TOLERANCE_SECONDS]. Justificante: `base64url(json).hmac` con el HMAC sobre
 * `"<usuario>|<base64url>"`, asi que un justificante de otro usuario o de otro pedido no valida. El secreto jamas se registra.
 */
internal class SandboxPaymentProvider(secret: String) : PaymentProvider {
    init {
        require(secret.length >= MIN_SECRET_LENGTH) { "$SANDBOX_SECRET_ENV debe tener al menos $MIN_SECRET_LENGTH caracteres." }
    }

    private val key = SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
    private val json = Json { ignoreUnknownKeys = false }

    override val id: String = ID

    override fun parseWebhook(header: (String) -> String?, rawBody: ByteArray, now: Instant): PaymentReceipt {
        val timestamp = header(TIMESTAMP_HEADER)?.trim()?.toLongOrNull() ?: throw invalidSignature()
        val signature = header(SIGNATURE_HEADER)?.trim().orEmpty()
        if (kotlin.math.abs(now.epochSecond - timestamp) > TOLERANCE_SECONDS) throw invalidSignature()
        val expected = hmacHex("$timestamp.".toByteArray(StandardCharsets.UTF_8) + rawBody)
        if (!constantTimeEquals(expected, signature.lowercase())) throw invalidSignature()
        val event = try {
            json.decodeFromString(SandboxEvent.serializer(), String(rawBody, StandardCharsets.UTF_8))
        } catch (_: SerializationException) {
            throw CanonicalErrorCode.MALFORMED_BODY.exception()
        } catch (_: IllegalArgumentException) {
            throw CanonicalErrorCode.MALFORMED_BODY.exception()
        }
        val kind = when (event.type) {
            "payment.succeeded" -> PaymentEventKind.PAID
            "payment.refunded" -> PaymentEventKind.REFUNDED
            else -> PaymentEventKind.IGNORED
        }
        return PaymentReceipt(ID, event.eventId, kind, event.orderId.lowercase(), event.transactionId, event.amountCents, event.currency, event.productCode)
    }

    override fun verifyReceipt(opaque: String, userId: String, orderId: String): PaymentReceipt {
        val parts = opaque.trim().split('.')
        if (parts.size != 2) throw CanonicalErrorCode.PAYMENT_RECEIPT_INVALID.exception()
        val expected = hmacHex("$userId|${parts[0]}".toByteArray(StandardCharsets.UTF_8))
        if (!constantTimeEquals(expected, parts[1].lowercase())) throw CanonicalErrorCode.PAYMENT_RECEIPT_INVALID.exception()
        val payload = try {
            json.decodeFromString(SandboxReceiptPayload.serializer(), String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8))
        } catch (_: SerializationException) {
            throw CanonicalErrorCode.PAYMENT_RECEIPT_INVALID.exception()
        } catch (_: IllegalArgumentException) {
            throw CanonicalErrorCode.PAYMENT_RECEIPT_INVALID.exception()
        }
        if (!payload.orderId.equals(orderId, ignoreCase = true)) throw CanonicalErrorCode.PAYMENT_RECEIPT_INVALID.exception()
        return PaymentReceipt(ID, payload.eventId, PaymentEventKind.PAID, orderId.lowercase(), payload.transactionId, payload.amountCents, payload.currency, payload.productCode)
    }

    /** Solo para pruebas y para `ops/local-exam`: firma un aviso como lo haria el proveedor. */
    fun signWebhook(rawBody: ByteArray, timestamp: Long): String = hmacHex("$timestamp.".toByteArray(StandardCharsets.UTF_8) + rawBody)

    /** Solo para pruebas y para `ops/local-exam`: emite el justificante opaco de un pago de [userId]. */
    fun issueReceipt(userId: String, orderId: String, eventId: String, transactionId: String, amountCents: Int, currency: String, productCode: String): String {
        val body = json.encodeToString(
            SandboxReceiptPayload.serializer(),
            SandboxReceiptPayload(eventId, orderId, transactionId, amountCents, currency, productCode),
        )
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(body.toByteArray(StandardCharsets.UTF_8))
        return "$encoded.${hmacHex("$userId|$encoded".toByteArray(StandardCharsets.UTF_8))}"
    }

    private fun hmacHex(data: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        return mac.doFinal(data).joinToString("") { "%02x".format(it) }
    }

    private fun invalidSignature() = CanonicalErrorCode.WEBHOOK_SIGNATURE_INVALID.exception()

    companion object {
        const val ID = "sandbox"
        const val TIMESTAMP_HEADER = "X-Sandbox-Timestamp"
        const val SIGNATURE_HEADER = "X-Sandbox-Signature"
        const val TOLERANCE_SECONDS = 300L
        const val MIN_SECRET_LENGTH = 32
    }
}
