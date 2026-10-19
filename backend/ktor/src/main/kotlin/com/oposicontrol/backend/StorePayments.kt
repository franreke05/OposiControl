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
import kotlin.system.exitProcess

// =====================================================================
// Pagos de tienda V1 (Google Play + App Store). Contrato: docs/payments/STORE_PAYMENTS_CONTRACT.md.
//
//  - [StorePaymentProvider] es la costura por tienda: verifica la prueba del cliente o autentica un aviso y devuelve HECHOS verificados
//    ([StorePurchaseFacts]). Nunca toca la base. Quien acredita OT o escribe la suscripcion es la base (`app_store_*`, SECURITY DEFINER).
//  - El [PaymentProvider] de pedidos (sandbox) sigue siendo el del flujo "pedido primero"; la tienda es "compra primero" (el pedido se crea al
//    verificar, con la clave de la tienda), por eso es un puerto hermano y no una implementacion mas de aquel.
//  - Ningun id de producto, precio ni secreto vive en el codigo: el mapa de productos es `PAYMENTS_STORE_PRODUCTS`, las credenciales son ficheros.
//  - Todo aviso se guarda en la bandeja (`app_store_inbox_put`) ANTES de responder 2xx (ECO-02); si procesarlo falla queda `pendiente` y lo
//    reintenta [StorePaymentsJob].
// =====================================================================

internal enum class StorePlatform(val provider: String) {
    GOOGLE_PLAY("google_play"),
    APP_STORE("app_store"),
    ;

    companion object {
        fun parse(value: String?): StorePlatform? = entries.firstOrNull { it.name == value?.trim() }
        fun fromProvider(value: String?): StorePlatform? = entries.firstOrNull { it.provider == value?.trim()?.lowercase() }
    }
}

internal enum class StoreKind { CONSUMABLE, SUBSCRIPTION }

internal enum class StoreEnvironment(val db: String) { PRODUCTION("produccion"), SANDBOX("sandbox") }

internal enum class StorePurchaseState { PURCHASED, PENDING, CANCELLED, REVOKED }

/** Estado de una suscripcion ya traducido al vocabulario de `suscripciones` (estado + fin_motivo). */
@Serializable
internal data class StoreSubscriptionFacts(
    val estado: String,
    val inicio: String? = null,
    val fin: String? = null,
    val renovacion: String? = null,
    val finMotivo: String? = null,
    val linkedKey: String? = null,
)

/** Lo que una tienda dice de una compra, YA VERIFICADO (firma / API oficial). Se guarda tal cual en la bandeja para reprocesar. */
@Serializable
internal data class StorePurchaseFacts(
    val platform: StorePlatform,
    val kind: StoreKind,
    val storeProductId: String,
    /** Google `purchaseToken`; Apple `transactionId` (consumible) u `originalTransactionId` (suscripcion). */
    val purchaseKey: String,
    /** `obfuscatedExternalAccountId` / `appAccountToken`: el token opaco de la cuenta, o null si la tienda no lo trae. */
    val accountToken: String? = null,
    val environment: StoreEnvironment,
    val quantity: Int = 1,
    val state: StorePurchaseState,
    /** Version del hecho (ms): lectura de la API (Google) o `signedDate` (Apple). Un hecho mas viejo nunca pisa uno mas nuevo. */
    val versionMillis: Long,
    val subscription: StoreSubscriptionFacts? = null,
    /** Play: falta consumir / reconocer. */
    val needsFinish: Boolean = false,
    /** Solo auditoria (precio y moneda de la tienda, id de pedido de la tienda). Nunca decide el credito (PAY-R1, D1). */
    val audit: Map<String, String> = emptyMap(),
)

internal enum class StoreEventType(val inbox: String) {
    PURCHASE("COMPRA"),
    REFUND("REEMBOLSO"),
    REFUND_REVERSED("REEMBOLSO_REVERTIDO"),
    SUBSCRIPTION("SUSCRIPCION"),
    CONSUMPTION_REQUEST("CONSUMO"),
    TEST("PRUEBA"),
    IGNORED("IGNORADO"),
    ;

    companion object {
        fun fromInbox(value: String): StoreEventType = entries.firstOrNull { it.inbox == value } ?: IGNORED
    }
}

/**
 * Un aviso AUTENTICADO, listo para la bandeja. [data] es lo minimo para procesarlo despues sin el cuerpo original: Google guarda el token y el
 * tipo (los hechos se releen de la API); Apple guarda los hechos ya verificados de la transaccion firmada.
 */
internal data class StoreEvent(
    val platform: StorePlatform,
    val eventId: String,
    val type: StoreEventType,
    val purchaseKey: String?,
    val environment: StoreEnvironment?,
    val data: JsonObject,
)

internal interface StorePaymentProvider {
    val platform: StorePlatform

    /** Verifica la prueba que manda el cliente. Firma / API mala: [CanonicalErrorCode.STORE_PURCHASE_INVALID]; tienda caida: 503. */
    suspend fun verify(storeProductId: String, kind: StoreKind, token: String): StorePurchaseFacts

    /** Autentica el aviso (JWS de Apple / OIDC de Pub/Sub) y lo convierte en un evento. Autenticacion mala: 401 sin escribir nada. */
    suspend fun parseNotification(header: (String) -> String?, rawBody: ByteArray, now: Instant): StoreEvent

    /** Hechos autoritativos de un evento ya guardado (Google relee la API; Apple usa los hechos firmados guardados). null = nada que aplicar. */
    suspend fun factsFor(type: StoreEventType, purchaseKey: String?, data: JsonObject): StorePurchaseFacts?

    /** Play: consume (pack) o acknowledge (suscripcion) DESPUES del credito. Apple: nada (el cliente hace `finish`). */
    suspend fun finish(kind: StoreKind, storeProductId: String, purchaseKey: String)
}

// ---------- catalogo (config) ----------

internal data class StoreProduct(val platform: StorePlatform, val storeProductId: String, val productCode: String, val kind: StoreKind)

/**
 * `PAYMENTS_STORE_PRODUCTS`: lista separada por comas de `plataforma:idTienda=codigoProducto:TIPO`, p. ej.
 * `google_play:ot_100=PACK_100:CONSUMABLE,google_play:premium/monthly=PREMIUM_M:SUBSCRIPTION,app_store:com.x.ot100=PACK_100:CONSUMABLE`.
 * En Play una suscripcion puede mapearse por `productId/basePlanId` (mas especifico) o por `productId`.
 */
internal class StoreCatalog(val products: List<StoreProduct>) {
    private val byKey = products.associateBy { it.platform to it.storeProductId }

    fun find(platform: StorePlatform, storeProductId: String): StoreProduct? = byKey[platform to storeProductId]

    /** Play suscripcion: primero `productId/basePlanId`, luego `productId`. */
    fun findSubscription(platform: StorePlatform, productId: String, basePlanId: String?): StoreProduct? =
        basePlanId?.let { find(platform, "$productId/$it") } ?: find(platform, productId)

    companion object {
        fun parse(raw: String?): StoreCatalog {
            if (raw.isNullOrBlank()) return StoreCatalog(emptyList())
            val products = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { entry ->
                val platformSep = entry.indexOf(':')
                val eq = entry.indexOf('=')
                val kindSep = entry.lastIndexOf(':')
                require(platformSep > 0 && eq > platformSep + 1 && kindSep > eq + 1) { "PAYMENTS_STORE_PRODUCTS: entrada mal formada (posicion ${raw.indexOf(entry)})" }
                val platform = StorePlatform.fromProvider(entry.substring(0, platformSep)) ?: throw IllegalArgumentException("PAYMENTS_STORE_PRODUCTS: plataforma desconocida")
                val kind = StoreKind.entries.firstOrNull { it.name == entry.substring(kindSep + 1) } ?: throw IllegalArgumentException("PAYMENTS_STORE_PRODUCTS: tipo desconocido")
                StoreProduct(platform, entry.substring(platformSep + 1, eq), entry.substring(eq + 1, kindSep), kind)
            }
            require(products.map { it.platform to it.storeProductId }.toSet().size == products.size) { "PAYMENTS_STORE_PRODUCTS: producto de tienda repetido" }
            return StoreCatalog(products)
        }
    }
}

// ---------- politica de sandbox (D4) ----------

internal enum class StoreSandboxMode { REJECT, ALLOWLIST, ACCEPT }

internal class StoreSandboxPolicy(val mode: StoreSandboxMode, private val allowedUsers: Set<String>) {
    /** Compra del cliente: [userId] es el `sub` del JWT. */
    fun allows(environment: StoreEnvironment, userId: String?): Boolean = when {
        environment == StoreEnvironment.PRODUCTION -> true
        mode == StoreSandboxMode.ACCEPT -> true
        mode == StoreSandboxMode.ALLOWLIST -> userId != null && userId.lowercase() in allowedUsers
        else -> false
    }

    companion object {
        fun parse(mode: String?, users: String?): StoreSandboxPolicy {
            val m = when (mode?.trim()?.lowercase()) {
                null, "", "reject" -> StoreSandboxMode.REJECT
                "allowlist" -> StoreSandboxMode.ALLOWLIST
                "accept" -> StoreSandboxMode.ACCEPT
                else -> throw IllegalArgumentException("PAYMENTS_STORE_SANDBOX debe ser reject, allowlist o accept")
            }
            val ids = users.orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            require(ids.all { it.isUuid() }) { "PAYMENTS_STORE_SANDBOX_USERS: solo ids de usuario (uuid)" }
            return StoreSandboxPolicy(m, ids)
        }
    }
}

// ---------- configuracion y guarda de arranque ----------

internal const val STORE_CONFIG_EXIT_CODE = 78
internal const val STORE_GOOGLE_ENABLED_ENV = "PAYMENTS_STORE_GOOGLE_PLAY_ENABLED"
internal const val STORE_APPLE_ENABLED_ENV = "PAYMENTS_STORE_APP_STORE_ENABLED"

internal class StoreConfigException(message: String) : IllegalStateException(message)

/**
 * F8: huella SHA-256 (DER) de Apple Root CA - G3, la raiz que firma la cadena de StoreKit 2 / App Store Server Notifications. Valor publicado por
 * Apple en su lista de raices de confianza (https://support.apple.com/en-am/121672, consultada 2026-10-19). Es publica: no es un secreto.
 */
internal val APPLE_ROOT_CA_SHA256_PINS: Set<String> = setOf("63343abfb89a6a03ebb57e9b3f5fa7be7c4f5c756f3017b3a8c488c3653e9179")

internal data class GooglePlayConfig(
    val packageName: String,
    val serviceAccountFile: String,
    val pubsubAudience: String,
    val pubsubServiceAccount: String,
    /** F7 (auditoria Control 2026-10-19): obligatoria; un push de otra suscripcion del proyecto es 401. */
    val pubsubSubscription: String,
)

internal data class AppStoreConfig(
    val bundleId: String,
    val appAppleId: Long?,
    val rootCaPaths: List<String>,
    val onlineChecks: Boolean,
)

internal data class StorePaymentsSettings(
    val google: GooglePlayConfig?,
    val apple: AppStoreConfig?,
    val catalog: StoreCatalog,
    val sandbox: StoreSandboxPolicy,
    val jobSeconds: Long,
    /** NEW-4: intentos fallidos tras los que un aviso pasa a `muerto` (0 = nunca, por defecto). */
    val deadLetterAttempts: Int = 0,
    /** NEW-4: dias de retencion de la bandeja procesada; null = no se borra nada (decision del propietario / legal). */
    val retentionDays: Int? = null,
) {
    companion object {
        /** Lee y valida. Una plataforma activada con algo que falta lanza [StoreConfigException] (sin valores, solo nombres de variable). */
        fun fromEnvironment(environment: (String) -> String? = System::getenv, appleRootPins: Set<String> = APPLE_ROOT_CA_SHA256_PINS): StorePaymentsSettings {
            fun flag(name: String) = environment(name)?.trim()?.lowercase() == "true"
            fun required(name: String, missing: MutableList<String>): String = environment(name)?.trim().orEmpty().also { if (it.isEmpty()) missing += name }
            val missing = mutableListOf<String>()
            val google = if (flag(STORE_GOOGLE_ENABLED_ENV)) {
                GooglePlayConfig(
                    packageName = required("PAYMENTS_GOOGLE_PACKAGE_NAME", missing),
                    serviceAccountFile = required("PAYMENTS_GOOGLE_SERVICE_ACCOUNT_FILE", missing),
                    pubsubAudience = required("PAYMENTS_GOOGLE_PUBSUB_AUDIENCE", missing),
                    pubsubServiceAccount = required("PAYMENTS_GOOGLE_PUBSUB_SERVICE_ACCOUNT", missing),
                    pubsubSubscription = required("PAYMENTS_GOOGLE_PUBSUB_SUBSCRIPTION", missing),
                )
            } else null
            val apple = if (flag(STORE_APPLE_ENABLED_ENV)) {
                val appId = environment("PAYMENTS_APPLE_APP_APPLE_ID")?.trim().orEmpty()
                if (appId.isEmpty()) missing += "PAYMENTS_APPLE_APP_APPLE_ID"
                AppStoreConfig(
                    bundleId = required("PAYMENTS_APPLE_BUNDLE_ID", missing),
                    appAppleId = appId.toLongOrNull() ?: if (appId.isNotEmpty()) throw StoreConfigException("PAYMENTS_APPLE_APP_APPLE_ID no es numerico") else null,
                    rootCaPaths = required("PAYMENTS_APPLE_ROOT_CA_PATHS", missing).split(',').map { it.trim() }.filter { it.isNotEmpty() },
                    onlineChecks = environment("PAYMENTS_APPLE_ONLINE_CHECKS")?.trim()?.lowercase() != "false",
                )
            } else null
            if (missing.isNotEmpty()) throw StoreConfigException("Pagos de tienda activados sin configuracion completa: faltan ${missing.joinToString()}")
            val catalog = try {
                StoreCatalog.parse(environment("PAYMENTS_STORE_PRODUCTS"))
            } catch (e: IllegalArgumentException) {
                throw StoreConfigException(e.message ?: "PAYMENTS_STORE_PRODUCTS no valido")
            }
            if ((google != null || apple != null) && catalog.products.isEmpty()) throw StoreConfigException("Pagos de tienda activados sin PAYMENTS_STORE_PRODUCTS")
            google?.let { g -> if (!java.io.File(g.serviceAccountFile).canRead()) throw StoreConfigException("PAYMENTS_GOOGLE_SERVICE_ACCOUNT_FILE no se puede leer") }
            // F8 + NEW-7 (auditorias Control 2026-10-19): cada raiz configurada se LEE como certificado X.509 (DER o PEM) y la huella SHA-256 de su
            // DER tiene que ser la fijada de Apple Root CA - G3; ilegible, no certificado u otra raiz: no arranca.
            apple?.let { a ->
                a.rootCaPaths.forEach { path ->
                    val der = readCertificateDer(path) ?: throw StoreConfigException("PAYMENTS_APPLE_ROOT_CA_PATHS: un certificado no se puede leer")
                    if (sha256Hex(der) !in appleRootPins) {
                        throw StoreConfigException("PAYMENTS_APPLE_ROOT_CA_PATHS: la huella SHA-256 de un certificado no es la de Apple Root CA - G3")
                    }
                }
            }
            val sandbox = try {
                StoreSandboxPolicy.parse(environment("PAYMENTS_STORE_SANDBOX"), environment("PAYMENTS_STORE_SANDBOX_USERS"))
            } catch (e: IllegalArgumentException) {
                throw StoreConfigException(e.message ?: "PAYMENTS_STORE_SANDBOX no valido")
            }
            val appEnv = environment("APP_ENV")
            if (sandbox.mode == StoreSandboxMode.ACCEPT && appEnv != "local" && appEnv != "test") {
                throw StoreConfigException("PAYMENTS_STORE_SANDBOX=accept solo con APP_ENV=local o APP_ENV=test")
            }
            val jobSeconds = environment("PAYMENTS_STORE_JOB_SECONDS")?.trim()?.toLongOrNull() ?: 300L
            val deadLetter = environment("PAYMENTS_STORE_DEAD_LETTER_ATTEMPTS")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: throw StoreConfigException("PAYMENTS_STORE_DEAD_LETTER_ATTEMPTS debe ser un entero >= 0")
            } ?: 0
            val retention = environment("PAYMENTS_STORE_INBOX_RETENTION_DAYS")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                it.toIntOrNull()?.takeIf { n -> n >= 30 } ?: throw StoreConfigException("PAYMENTS_STORE_INBOX_RETENTION_DAYS debe ser un entero >= 30")
            }
            return StorePaymentsSettings(google, apple, catalog, sandbox, jobSeconds, deadLetter, retention)
        }
    }
}

/** Se llama en `main()` ANTES de enlazar el puerto: pagos activados sin credenciales = el proceso no arranca (codigo 78). */
internal fun enforceStorePaymentsConfig(
    environment: (String) -> String? = System::getenv,
    fail: (String) -> Nothing = { message ->
        System.err.println("FATAL: $message")
        exitProcess(STORE_CONFIG_EXIT_CODE)
    },
) {
    try {
        StorePaymentsSettings.fromEnvironment(environment)
    } catch (e: StoreConfigException) {
        fail(e.message ?: "Configuracion de pagos de tienda no valida")
    }
}

// ---------- cable ----------

@Serializable
@CanonicalCommand
internal data class StoreVerifyRequest(
    val platform: String,
    val storeProductId: String,
    val kind: String,
    val token: String,
    /** E4: informativo (Apple `transactionId`); la clave la saca el servidor de la prueba verificada. */
    val transactionId: String? = null,
    /** E4: el token de cuenta que el cliente paso a la tienda; si viene y no coincide con el que firma la tienda, STORE_ACCOUNT_MISMATCH. */
    val accountToken: String? = null,
)

@Serializable
@CanonicalCommand
internal data class StoreRestoreRequest(val platform: String, val purchases: List<StoreRestoreItem>)

@Serializable
internal data class StoreRestoreItem(val storeProductId: String, val kind: String, val token: String)

@Serializable
internal data class StoreAccountTokenResponse(val accountToken: String)

@Serializable
internal data class StoreProductDto(val storeProductId: String, val platform: String, val kind: String, val productCode: String, val basePlanId: String? = null)

@Serializable
internal data class StoreProductsResponse(val items: List<StoreProductDto>)

@Serializable
internal data class StorePremiumDto(
    val active: Boolean,
    val state: String,
    val productCode: String? = null,
    val provider: String? = null,
    val until: String? = null,
)

@Serializable
internal data class StoreEntitlementsResponse(val balance: Int, val premium: StorePremiumDto)

@Serializable
internal data class StoreVerifyResponse(
    val result: String,
    val kind: String,
    val productCode: String,
    val balance: Int,
    val premium: StorePremiumDto,
    /** E4 `premiumUntil`: fin del acceso premium (= premium.until). */
    val premiumUntil: String? = null,
)

@Serializable
internal data class StoreRestoreItemResult(val storeProductId: String, val result: String, val code: String? = null)

@Serializable
internal data class StoreRestoreResponse(val items: List<StoreRestoreItemResult>)

// ---------- filas de las RPC ----------

@Serializable
private data class StoreOrderRow(
    val outcome: String,
    @SerialName("orden_id") val ordenId: String? = null,
    val estado: String? = null,
    val saldo: Int? = null,
)

@Serializable
private data class StoreSubscriptionRow(
    val outcome: String,
    val estado: String? = null,
    @SerialName("fin_en") val finEn: String? = null,
    @SerialName("es_premium") val esPremium: Boolean = false,
)

@Serializable
private data class StoreInboxPutRow(
    val nuevo: Boolean,
    val estado: String,
    val resultado: String? = null,
    @SerialName("misma_huella") val mismaHuella: Boolean = true,
    /** NEW-3: reclamo de la fila para ESTA llamada (null = otro trabajador la tiene). */
    val reclamo: String? = null,
)

@Serializable
internal data class StoreInboxRow(
    val proveedor: String,
    @SerialName("evento_id") val eventoId: String,
    val tipo: String,
    @SerialName("clave_compra") val claveCompra: String? = null,
    val entorno: String? = null,
    val datos: JsonObject = JsonObject(emptyMap()),
    val intentos: Int = 0,
    /** NEW-3: reclamo con el que se marca la fila (lo da app_store_inbox_put o app_store_inbox_pending). */
    val reclamo: String? = null,
)

@Serializable
private data class StoreInboxStatsRow(
    val estado: String,
    val n: Long,
    @SerialName("mas_antiguo") val masAntiguo: String? = null,
    @SerialName("max_intentos") val maxIntentos: Int? = null,
)

internal data class StoreInboxBacklog(val estado: String, val rows: Long, val oldestSeconds: Long, val maxAttempts: Int)

@Serializable
private data class StoreFinalizeRow(
    val tipo: String,
    val proveedor: String,
    val clave: String,
    @SerialName("tienda_producto_id") val tiendaProductoId: String,
)

@Serializable
private data class StoreEntitlementsRow(
    val saldo: Int,
    @SerialName("es_premium") val esPremium: Boolean,
    @SerialName("estado_suscripcion") val estadoSuscripcion: String,
    @SerialName("producto_codigo") val productoCodigo: String? = null,
    val proveedor: String? = null,
    @SerialName("fin_en") val finEn: String? = null,
)

/** Claves estables de las RPC `app_store_*` -> sobre canonico. El resto, como la economia. */
internal object StoreRpcErrors {
    fun failure(function: String, response: SupabaseRawResponse): Exception {
        val key = runCatching { Json.parseToJsonElement(response.body).jsonObject["message"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            .orEmpty().trim().takeWhile { it.isLetterOrDigit() || it == '_' }
        return when (key) {
            "STORE_REQUEST_INVALID" -> CanonicalErrorCode.VALIDATION.exception()
            "STORE_PRODUCT_UNKNOWN" -> CanonicalErrorCode.STORE_PRODUCT_UNKNOWN.exception()
            "STORE_PURCHASE_INVALID" -> CanonicalErrorCode.STORE_PURCHASE_INVALID.exception()
            "STORE_ACCOUNT_MISMATCH" -> CanonicalErrorCode.STORE_ACCOUNT_MISMATCH.exception()
            "STORE_PURCHASE_BOUND_TO_OTHER" -> CanonicalErrorCode.STORE_PURCHASE_BOUND_TO_OTHER.exception()
            "STORE_EVENT_NOT_IN_INBOX" -> CanonicalErrorCode.INTERNAL.exception()
            else -> EconomyRpcErrors.failure(function, response)
        }
    }
}

/** El aviso se guardo pero no se pudo aplicar AHORA (reembolsos apagados, tienda caida): queda `pendiente` para el trabajo. */
internal class StoreDeferred(val reason: String) : RuntimeException(reason)

// ---------- servicio ----------

internal class StorePaymentsService(
    private val upstream: EconomyUpstream,
    private val providers: Map<StorePlatform, StorePaymentProvider>,
    private val catalog: StoreCatalog,
    private val sandbox: StoreSandboxPolicy,
    private val refundsEnabled: Boolean,
    private val clock: () -> Instant = Instant::now,
    private val log: (String) -> Unit = {},
    private val json: Json = Json { ignoreUnknownKeys = true },
    /** NEW-4: intentos fallidos tras los que un aviso pasa a `muerto` (0 = nunca). */
    private val deadLetterAttempts: Int = 0,
) {
    private fun provider(platform: StorePlatform): StorePaymentProvider =
        providers[platform] ?: throw CanonicalErrorCode.PAYMENT_PROVIDER_UNAVAILABLE.exception()

    suspend fun accountToken(userId: CanonicalUserId): StoreAccountTokenResponse {
        val body = buildJsonObject { put("p_usuario", userId.value) }.toString()
        val response = canonicalUpstreamIo { upstream.rpc("app_store_account_token", body) }
        if (!response.ok) throw StoreRpcErrors.failure("app_store_account_token", response)
        val token = runCatching { json.parseToJsonElement(response.body).jsonPrimitive.content }.getOrNull()
        if (token == null || !token.isUuid()) throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        return StoreAccountTokenResponse(token.lowercase())
    }

    fun products(): StoreProductsResponse = StoreProductsResponse(
        catalog.products.filter { it.platform in providers.keys }.map { StoreProductDto(it.storeProductId.substringBefore('/'), it.platform.name, it.kind.name, it.productCode, it.storeProductId.substringAfter('/', "").ifEmpty { null }) },
    )

    suspend fun entitlements(userId: CanonicalUserId): StoreEntitlementsResponse {
        val row = rpc<StoreEntitlementsRow>("app_store_entitlements", buildJsonObject { put("p_usuario", userId.value) }.toString()).singleOrNull()
            ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        return StoreEntitlementsResponse(row.saldo, StorePremiumDto(row.esPremium, row.estadoSuscripcion, row.productoCodigo, row.proveedor, row.finEn?.let(::iso)))
    }

    /** S-03. El usuario es el `sub` del JWT; la cuenta que trae la tienda tiene que ser la suya (lo comprueba la base). */
    suspend fun verify(userId: CanonicalUserId, request: StoreVerifyRequest): StoreVerifyResponse {
        val platform = StorePlatform.parse(request.platform) ?: throw validationError(ErrorIssue("platform", CanonicalIssue.INVALID_FORMAT))
        val kind = StoreKind.entries.firstOrNull { it.name == request.kind.trim() } ?: throw validationError(ErrorIssue("kind", CanonicalIssue.INVALID_FORMAT))
        val productId = request.storeProductId.trim()
        if (productId.isEmpty() || productId.length > MAX_PRODUCT_ID_LENGTH) throw validationError(ErrorIssue("storeProductId", CanonicalIssue.INVALID_FORMAT))
        if (request.token.isBlank() || request.token.length > MAX_STORE_TOKEN_LENGTH) throw validationError(ErrorIssue("token", CanonicalIssue.INVALID_FORMAT))
        // Play suscripcion: el cliente manda el productId; el plan base concreto lo dice la API (mapa `productId/basePlanId`).
        val product = catalog.products.firstOrNull { it.platform == platform && it.kind == kind && (it.storeProductId == productId || (kind == StoreKind.SUBSCRIPTION && it.storeProductId.startsWith("$productId/"))) }
            ?: throw CanonicalErrorCode.STORE_PRODUCT_UNKNOWN.exception()
        request.accountToken?.let { if (!it.trim().isUuid()) throw validationError(ErrorIssue("accountToken", CanonicalIssue.INVALID_FORMAT)) }
        val facts = provider(platform).verify(productId, kind, request.token.trim())
        if (!sandbox.allows(facts.environment, userId.value)) throw CanonicalErrorCode.STORE_SANDBOX_REJECTED.exception()
        if (facts.accountToken == null || !facts.accountToken.isUuid()) throw CanonicalErrorCode.STORE_ACCOUNT_MISMATCH.exception()
        if (request.accountToken != null && !request.accountToken.trim().equals(facts.accountToken, ignoreCase = true)) throw CanonicalErrorCode.STORE_ACCOUNT_MISMATCH.exception()
        val result = apply(facts, userId)
        val ent = entitlements(userId)
        val code = catalog.find(platform, facts.storeProductId)?.productCode ?: product.productCode
        return StoreVerifyResponse(result, kind.name, code, ent.balance, ent.premium, ent.premium.until.takeIf { ent.premium.active })
    }

    /** S-04. Cada compra como S-03; un fallo definitivo de una no tumba las demas (5xx si se propaga: el cliente reintenta). */
    suspend fun restore(userId: CanonicalUserId, request: StoreRestoreRequest): StoreRestoreResponse {
        if (request.purchases.isEmpty() || request.purchases.size > MAX_RESTORE_ITEMS) throw validationError(ErrorIssue("purchases", CanonicalIssue.OUT_OF_RANGE))
        return StoreRestoreResponse(
            request.purchases.map { item ->
                try {
                    val r = verify(userId, StoreVerifyRequest(request.platform, item.storeProductId, item.kind, item.token))
                    StoreRestoreItemResult(item.storeProductId, r.result)
                } catch (e: BackendDomainException) {
                    if (e.status.value >= 500) throw e
                    StoreRestoreItemResult(item.storeProductId, "REJECTED", e.errorCode)
                }
            },
        )
    }

    /**
     * Aplica hechos verificados. [userId] = usuario del JWT (verificacion del cliente) o null (aviso: la base resuelve el dueno por el token de
     * cuenta o por la compra ya ligada). Devuelve GRANTED | PENDING o lanza el rechazo canonico.
     */
    suspend fun apply(facts: StorePurchaseFacts, userId: CanonicalUserId?): String {
        val product = when (facts.kind) {
            StoreKind.CONSUMABLE -> catalog.find(facts.platform, facts.storeProductId)
            StoreKind.SUBSCRIPTION -> catalog.find(facts.platform, facts.storeProductId)
                ?: catalog.find(facts.platform, facts.storeProductId.substringBefore('/'))
        }?.takeIf { it.kind == facts.kind } ?: throw CanonicalErrorCode.STORE_PRODUCT_UNKNOWN.exception()
        if (facts.state == StorePurchaseState.PENDING) return "PENDING"
        val token = facts.accountToken?.takeIf { it.isUuid() }?.lowercase()
        return when (facts.kind) {
            StoreKind.CONSUMABLE -> {
                if (facts.state != StorePurchaseState.PURCHASED) throw CanonicalErrorCode.STORE_PURCHASE_INVALID.exception()
                if (facts.quantity != 1) throw CanonicalErrorCode.STORE_PURCHASE_INVALID.exception()
                val row = rpc<StoreOrderRow>(
                    "app_store_credit_purchase",
                    buildJsonObject {
                        put("p_usuario", userId?.value)
                        put("p_cuenta_token", token)
                        put("p_proveedor", facts.platform.provider)
                        put("p_entorno", facts.environment.db)
                        put("p_producto", product.productCode)
                        put("p_tienda_producto", facts.storeProductId)
                        put("p_clave", facts.purchaseKey)
                        put("p_auditoria", buildJsonObject { facts.audit.forEach { (k, v) -> put(k, v) } })
                    }.toString(),
                ).singleOrNull() ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
                when (row.estado) {
                    "pagada" -> {
                        if (facts.needsFinish) finishAndMark(facts, "compra")
                        "GRANTED"
                    }
                    "en_revision" -> throw CanonicalErrorCode.STORE_PURCHASE_IN_REVIEW.exception()
                    else -> throw CanonicalErrorCode.STORE_PURCHASE_INVALID.exception()
                }
            }
            StoreKind.SUBSCRIPTION -> {
                val sub = facts.subscription ?: throw CanonicalErrorCode.STORE_PURCHASE_INVALID.exception()
                val row = rpc<StoreSubscriptionRow>(
                    "app_store_upsert_subscription",
                    buildJsonObject {
                        put("p_usuario", userId?.value)
                        put("p_cuenta_token", token)
                        put("p_proveedor", facts.platform.provider)
                        put("p_entorno", facts.environment.db)
                        put("p_producto", product.productCode)
                        put("p_tienda_producto", facts.storeProductId)
                        put("p_id_externo", facts.purchaseKey)
                        put("p_estado", sub.estado)
                        put("p_inicio", sub.inicio)
                        put("p_fin", sub.fin)
                        put("p_renovacion", sub.renovacion)
                        put("p_fin_motivo", sub.finMotivo)
                        put("p_version", facts.versionMillis)
                        put("p_id_externo_previo", sub.linkedKey)
                    }.toString(),
                ).singleOrNull() ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
                val access = row.estado in ACCESS_STATES
                if (facts.needsFinish && facts.state == StorePurchaseState.PURCHASED) finishAndMark(facts, "suscripcion")
                if (userId != null && !access) throw CanonicalErrorCode.STORE_PURCHASE_INVALID.exception()
                if (row.outcome == "STALE") "STALE" else "GRANTED"
            }
        }
    }

    /** Play: consumir / reconocer DESPUES del credito. Si falla, queda en `app_store_pending_finalize` para el trabajo (3 dias de Play). */
    private suspend fun finishAndMark(facts: StorePurchaseFacts, tipo: String) {
        try {
            provider(facts.platform).finish(facts.kind, facts.storeProductId.substringBefore('/'), facts.purchaseKey)
            rpcRaw("app_store_mark_finalized", buildJsonObject { put("p_proveedor", facts.platform.provider); put("p_tipo", tipo); put("p_clave", facts.purchaseKey) }.toString())
        } catch (e: Exception) {
            log("store finish deferred (${facts.platform.provider}/$tipo): ${e::class.simpleName}")
        }
    }

    /**
     * W-02 / W-03. Orden fijo: autenticacion (sin escribir nada si falla), bandeja (ECO-02), proceso. Una vez en la bandeja la respuesta es 2xx
     * aunque procesar falle: el trabajo lo reintenta. Si la propia bandeja falla, 503 y la tienda reintenta (nada se perdio ni se acuso).
     */
    suspend fun webhook(platform: StorePlatform, header: (String) -> String?, rawBody: ByteArray): String {
        val provider = provider(platform)
        val event = provider.parseNotification(header, rawBody, clock())
        val put = rpc<StoreInboxPutRow>(
            "app_store_inbox_put",
            buildJsonObject {
                put("p_proveedor", platform.provider)
                put("p_evento_id", event.eventId)
                put("p_tipo", event.type.inbox)
                put("p_clave", event.purchaseKey)
                put("p_entorno", event.environment?.db)
                put("p_huella", sha256Hex(rawBody))
                put("p_datos", event.data)
            }.toString(),
        ).singleOrNull() ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        if (!put.nuevo && !put.mismaHuella) log("store inbox: same event id with a different body (${platform.provider})")
        return claimedOrNot(put) ?: processInbox(StoreInboxRow(platform.provider, event.eventId, event.type.inbox, event.purchaseKey, event.environment?.db, event.data, reclamo = put.reclamo))
    }

    /** Procesa una fila de la bandeja y deja constancia. Nunca lanza por un fallo de proceso: devuelve el resultado guardado. */
    suspend fun processInbox(row: StoreInboxRow, renew: Boolean = false): String {
        // NEW-3: una fila del trabajo renueva su reclamo antes de procesarla; si lo perdio (otro la tiene), no se toca.
        if (renew && row.reclamo != null) {
            val held = runCatching {
                rpcScalar("app_store_inbox_renew", buildJsonObject { put("p_proveedor", row.proveedor); put("p_evento_id", row.eventoId); put("p_reclamo", row.reclamo) }.toString())
            }.getOrNull() == "true"
            if (!held) return "SKIPPED"
        }
        val (estado, resultado, error) = try {
            val result = process(row)
            Triple(if (result == "DISCARDED") "descartado" else "procesado", result, null)
        } catch (e: StoreDeferred) {
            // F4: reembolsos apagados = RETENIDO (no cuenta como intento, nunca se descarta); cualquier otro aplazamiento = fallo con backoff.
            Triple(if (e.reason == "REFUNDS_DISABLED") "retenido" else "pendiente", null, e.reason)
        } catch (e: BackendDomainException) {
            if (e.status.value >= 500) Triple("pendiente", null, e.errorCode) else Triple("descartado", e.errorCode, e.errorCode)
        } catch (e: Exception) {
            Triple("pendiente", null, e::class.simpleName ?: "error")
        }
        runCatching {
            rpcRaw(
                "app_store_inbox_mark",
                buildJsonObject {
                    put("p_proveedor", row.proveedor)
                    put("p_evento_id", row.eventoId)
                    put("p_estado", estado)
                    put("p_resultado", resultado)
                    put("p_error", error)
                    put("p_reclamo", row.reclamo)
                    put("p_max_intentos", deadLetterAttempts)
                }.toString(),
            )
        }.onFailure { log("store inbox mark failed: ${it::class.simpleName}") }
        return resultado ?: "DEFERRED"
    }

    private suspend fun process(row: StoreInboxRow): String {
        val platform = StorePlatform.fromProvider(row.proveedor) ?: return "DISCARDED"
        val type = StoreEventType.fromInbox(row.tipo)
        val environment = StoreEnvironment.entries.firstOrNull { it.db == row.entorno }
        // Sandbox en un aviso: sin usuario del JWT no hay lista blanca que comprobar; solo `accept` lo aplica (D4). Se registra, no se aplica.
        if (environment == StoreEnvironment.SANDBOX && sandbox.mode != StoreSandboxMode.ACCEPT) return "SANDBOX_IGNORED"
        return when (type) {
            StoreEventType.TEST, StoreEventType.IGNORED -> "IGNORED"
            StoreEventType.CONSUMPTION_REQUEST -> "RECORDED_NO_CONSENT"   // D7: no se envian datos de consumo sin consentimiento
            StoreEventType.PURCHASE, StoreEventType.SUBSCRIPTION -> {
                val facts = provider(platform).factsFor(type, row.claveCompra, row.datos) ?: return "IGNORED"
                if (facts.environment == StoreEnvironment.SANDBOX && sandbox.mode != StoreSandboxMode.ACCEPT) return "SANDBOX_IGNORED"
                apply(facts, null)
            }
            StoreEventType.REFUND -> {
                if (!refundsEnabled) throw StoreDeferred("REFUNDS_DISABLED")
                val kind = row.datos["kind"]?.jsonPrimitive?.contentOrNull
                if (kind == StoreKind.SUBSCRIPTION.name) {
                    val facts = provider(platform).factsFor(type, row.claveCompra, row.datos) ?: return "IGNORED"
                    apply(facts, null)
                } else {
                    val key = row.claveCompra ?: return "DISCARDED"
                    rpc<StoreOrderRow>(
                        "app_store_refund_purchase",
                        buildJsonObject { put("p_proveedor", platform.provider); put("p_evento_id", row.eventoId); put("p_clave", key) }.toString(),
                    ).singleOrNull()?.outcome ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
                }
            }
            StoreEventType.REFUND_REVERSED -> {
                val kind = row.datos["kind"]?.jsonPrimitive?.contentOrNull
                if (kind == StoreKind.SUBSCRIPTION.name) {
                    val facts = provider(platform).factsFor(type, row.claveCompra, row.datos) ?: return "IGNORED"
                    apply(facts, null)
                } else {
                    val key = row.claveCompra ?: return "DISCARDED"
                    val response = canonicalUpstreamIo {
                        upstream.rpc("app_store_refund_reversed", buildJsonObject { put("p_proveedor", platform.provider); put("p_evento_id", row.eventoId); put("p_clave", key) }.toString())
                    }
                    if (!response.ok) throw StoreRpcErrors.failure("app_store_refund_reversed", response)
                    runCatching { json.parseToJsonElement(response.body).jsonPrimitive.content }.getOrElse { throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception() }
                }
            }
        }
    }

    // ---------- reconciliacion (lo llama [StorePaymentsJob]) ----------

    /** Reintenta la bandeja `pendiente`. Devuelve cuantas filas se intentaron. */
    suspend fun reprocessPending(limit: Int = 50): Int {
        // F4: los retenidos (reembolsos apagados) solo se vuelven a pedir cuando los reembolsos estan encendidos.
        val rows = rpc<StoreInboxRow>("app_store_inbox_pending", buildJsonObject { put("p_limit", limit); put("p_incluir_retenidos", refundsEnabled) }.toString())
        rows.forEach { processInbox(it, renew = true) }
        return rows.size
    }

    /** F4: atasco de la bandeja por estado (`pendiente` / `retenido`): filas, edad de la mas antigua (s) e intentos maximos. Para metrica y alerta. */
    suspend fun inboxStats(): List<StoreInboxBacklog> =
        rpc<StoreInboxStatsRow>("app_store_inbox_stats", "{}").map { row ->
            val oldest = row.masAntiguo?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
            StoreInboxBacklog(row.estado, row.n, oldest?.let { java.time.Duration.between(it, clock()).seconds.coerceAtLeast(0) } ?: 0, row.maxIntentos ?: 0)
        }

    /** Play: consume / acknowledge lo acreditado y aun no terminado. */
    suspend fun finishPending(limit: Int = 50): Int {
        val rows = rpc<StoreFinalizeRow>("app_store_pending_finalize", buildJsonObject { put("p_limit", limit) }.toString())
        var done = 0
        rows.forEach { r ->
            val platform = StorePlatform.fromProvider(r.proveedor) ?: return@forEach
            val kind = if (r.tipo == "compra") StoreKind.CONSUMABLE else StoreKind.SUBSCRIPTION
            try {
                provider(platform).finish(kind, r.tiendaProductoId.substringBefore('/'), r.clave)
                rpcRaw("app_store_mark_finalized", buildJsonObject { put("p_proveedor", r.proveedor); put("p_tipo", r.tipo); put("p_clave", r.clave) }.toString())
                done++
            } catch (e: Exception) {
                log("store finish retry failed (${r.proveedor}/${r.tipo}): ${e::class.simpleName}")
            }
        }
        return done
    }

    /** Pone en la bandeja un evento descubierto por sondeo (Voided Purchases) y lo procesa como un aviso. */
    suspend fun ingest(event: StoreEvent, fingerprint: String): String {
        val put = rpc<StoreInboxPutRow>(
            "app_store_inbox_put",
            buildJsonObject {
                put("p_proveedor", event.platform.provider)
                put("p_evento_id", event.eventId)
                put("p_tipo", event.type.inbox)
                put("p_clave", event.purchaseKey)
                put("p_entorno", event.environment?.db)
                put("p_huella", fingerprint)
                put("p_datos", event.data)
            }.toString(),
        ).singleOrNull() ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        return claimedOrNot(put) ?: processInbox(StoreInboxRow(event.platform.provider, event.eventId, event.type.inbox, event.purchaseKey, event.environment?.db, event.data, reclamo = put.reclamo))
    }

    /**
     * NEW-3: un aviso ya procesado (o descartado / muerto) es REPLAY; uno que espera trabajo pero lo tiene reclamado OTRO trabajador no se procesa
     * en paralelo (IN_PROGRESS: el que lo tiene lo termina, o el trabajo lo reintenta cuando caduque su reclamo). null = procesar con este reclamo.
     */
    private fun claimedOrNot(put: StoreInboxPutRow): String? = when {
        !put.nuevo && put.estado != "pendiente" && put.estado != "retenido" -> "REPLAY"
        !put.nuevo && put.reclamo == null -> "IN_PROGRESS"
        else -> null
    }

    /**
     * NEW-1 / NEW-2 / NEW-4 (mantenimiento del trabajo): re-enlaza los huerfanos cuya compra ya existe (los reembolsos solo con reembolsos
     * encendidos) y, SOLO si se configuro una retencion (decision del propietario / legal), purga la bandeja procesada mas vieja.
     */
    suspend fun maintenance(retentionDays: Int?) {
        rpcScalar("app_store_reconcile_orphans", buildJsonObject { put("p_limit", 50); put("p_reembolsos_habilitados", refundsEnabled) }.toString())
            ?.toIntOrNull()?.takeIf { it > 0 }?.let { log("store inbox: $it orphan notification(s) re-linked to their order") }
        retentionDays?.let { days -> rpcScalar("app_store_inbox_purge", buildJsonObject { put("p_dias", days) }.toString()) }
    }

    private suspend fun rpcScalar(function: String, body: String): String? {
        val response = canonicalUpstreamIo { upstream.rpc(function, body) }
        if (!response.ok) throw StoreRpcErrors.failure(function, response)
        return runCatching { json.parseToJsonElement(response.body).jsonPrimitive.contentOrNull }.getOrNull()
    }

    private suspend fun rpcRaw(function: String, body: String) {
        val response = canonicalUpstreamIo { upstream.rpc(function, body) }
        if (!response.ok) throw StoreRpcErrors.failure(function, response)
    }

    private suspend inline fun <reified T> rpc(function: String, body: String): List<T> {
        val response = canonicalUpstreamIo { upstream.rpc(function, body) }
        if (!response.ok) throw StoreRpcErrors.failure(function, response)
        return runCatching { json.decodeFromString(ListSerializer(kotlinx.serialization.serializer<T>()), response.body) }
            .getOrElse { throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception(cause = it) }
    }

    private fun iso(value: String): String =
        runCatching { OffsetDateTime.parse(value).toInstant().toString() }.getOrNull() ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()

    companion object {
        /** PAY-R2: un JWS de StoreKit 2 con su cadena `x5c` (3 certificados) ocupa varios KB; 4096 no basta. */
        const val MAX_STORE_TOKEN_LENGTH = 32 * 1024
        const val MAX_PRODUCT_ID_LENGTH = 150
        const val MAX_RESTORE_ITEMS = 20
        val ACCESS_STATES = setOf("activa", "periodo_prueba", "cancelada")
    }
}


/** NEW-7: lee un certificado X.509 (DER o PEM) y devuelve su DER; null si no se puede leer o no es un certificado. */
internal fun readCertificateDer(path: String): ByteArray? = runCatching {
    java.io.FileInputStream(path).use { input -> java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(input).encoded }
}.getOrNull()
