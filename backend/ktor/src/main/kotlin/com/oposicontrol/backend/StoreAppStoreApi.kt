package com.oposicontrol.backend

import com.apple.itunes.storekit.client.APIException
import com.apple.itunes.storekit.client.BaseAppStoreServerAPIClient
import com.apple.itunes.storekit.model.Environment
import com.apple.itunes.storekit.model.NotificationHistoryRequest
import com.apple.itunes.storekit.model.Status
import java.io.IOException
import java.io.StringReader
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

// =====================================================================
// App Store Server API (OPCIONAL: solo con la clave In-App Purchase de App Store Connect) con la App Store Server Library oficial de Apple
// (`BaseAppStoreServerAPIClient`: JWT ES256 del emisor, rutas, modelos y errores de Apple). Lo unico propio es el transporte ([StoreHttp], el
// mismo puerto que Google): en las pruebas se sustituye y nunca hay red. Lo que devuelve la API son JWS que se verifican igual que los del
// cliente y los avisos ([AppStoreProvider], `SignedDataVerifier`). Sin la clave el servidor usa solo los JWS firmados (cliente y avisos).
//  - Get Transaction Info          GET /inApps/v1/transactions/{transactionId}
//  - Get All Subscription Statuses GET /inApps/v1/subscriptions/{transactionId}
//  - Get Notification History      POST /inApps/v1/notifications/history (recuperar avisos perdidos; cursor persistido en tienda_sondeo)
// =====================================================================

internal data class AppStoreApiConfig(val keyFile: String, val keyId: String, val issuerId: String)

/** Un elemento de Get All Subscription Statuses (ya sin verificar: el proveedor verifica los JWS). */
internal data class AppleSubscriptionStatus(val status: Int?, val originalTransactionId: String?, val signedTransaction: String?, val signedRenewal: String?)

internal data class AppleNotificationPage(val signedPayloads: List<String>, val nextToken: String?)

/** La parte de la App Store Server API que usa el servidor. Errores ya traducidos al sobre canonico. */
internal interface AppStoreServerApi {
    fun transactionInfo(environment: StoreEnvironment, transactionId: String): String
    fun subscriptionStatuses(environment: StoreEnvironment, transactionId: String): List<AppleSubscriptionStatus>
    fun notificationHistory(environment: StoreEnvironment, startMillis: Long, endMillis: Long, paginationToken: String?): AppleNotificationPage
}

internal class LibraryAppStoreServerApi(
    signingKeyPem: String,
    keyId: String,
    issuerId: String,
    bundleId: String,
    http: StoreHttp = JavaStoreHttp(),
) : AppStoreServerApi {
    private val production = Client(signingKeyPem, keyId, issuerId, bundleId, Environment.PRODUCTION, http)
    private val sandbox = Client(signingKeyPem, keyId, issuerId, bundleId, Environment.SANDBOX, http)

    private fun client(environment: StoreEnvironment) = if (environment == StoreEnvironment.PRODUCTION) production else sandbox

    override fun transactionInfo(environment: StoreEnvironment, transactionId: String): String = call {
        client(environment).getTransactionInfo(transactionId).signedTransactionInfo
    } ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()

    override fun subscriptionStatuses(environment: StoreEnvironment, transactionId: String): List<AppleSubscriptionStatus> = call {
        client(environment).getAllSubscriptionStatuses(transactionId, null).data.orEmpty().flatMap { group ->
            group.lastTransactions.orEmpty().map { AppleSubscriptionStatus(it.rawStatus, it.originalTransactionId, it.signedTransactionInfo, it.signedRenewalInfo) }
        }
    }

    override fun notificationHistory(environment: StoreEnvironment, startMillis: Long, endMillis: Long, paginationToken: String?): AppleNotificationPage = call {
        val response = client(environment).getNotificationHistory(paginationToken, NotificationHistoryRequest().startDate(startMillis).endDate(endMillis))
        AppleNotificationPage(
            response.notificationHistory.orEmpty().mapNotNull { it.signedPayload },
            response.paginationToken?.takeIf { response.hasMore == true },
        )
    }

    private fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: APIException) {
        throw appleApiFailure(e.httpStatusCode)
    } catch (e: IOException) {
        throw CanonicalErrorCode.UPSTREAM_UNAVAILABLE.exception(cause = e)
    } catch (e: BackendDomainException) {
        throw e
    } catch (e: RuntimeException) {
        throw CanonicalErrorCode.UPSTREAM_UNAVAILABLE.exception(cause = e)
    }

    /** El cliente oficial con el transporte cambiado por [StoreHttp]: la ruta, la consulta y las cabeceras (Bearer ES256) las pone la libreria. */
    private class Client(key: String, keyId: String, issuerId: String, bundleId: String, environment: Environment, private val http: StoreHttp) :
        BaseAppStoreServerAPIClient(key, keyId, issuerId, bundleId, environment) {
        override fun makeRequest(
            path: String,
            method: String,
            queryParameters: Map<String, List<String>>,
            headers: Map<String, String>,
            contentType: String?,
            body: ByteArray?,
        ): HttpResponseInterface {
            val query = queryParameters.flatMap { (k, values) -> values.map { "${enc(k)}=${enc(it)}" } }.joinToString("&")
            val target = url.trimEnd('/') + path + if (query.isEmpty()) "" else "?$query"
            val all = if (contentType != null) headers + ("Content-Type" to contentType) else headers
            val response = http.send(method, target, all, body?.toString(StandardCharsets.UTF_8))
            return object : HttpResponseInterface {
                override fun statusCode(): Int = response.status
                override fun body(): java.io.Reader = StringReader(response.body)
                override fun close() = Unit
            }
        }

        private fun enc(v: String) = URLEncoder.encode(v, StandardCharsets.UTF_8)
    }

    companion object {
        fun fromConfig(config: AppStoreApiConfig, bundleId: String): LibraryAppStoreServerApi {
            val pem = try {
                java.io.File(config.keyFile).readText()
            } catch (_: IOException) {
                throw StoreConfigException("PAYMENTS_APPLE_API_KEY_FILE no se puede leer")
            }
            return try {
                LibraryAppStoreServerApi(pem, config.keyId, config.issuerId, bundleId)
            } catch (_: Exception) {
                throw StoreConfigException("PAYMENTS_APPLE_API_KEY_FILE no es una clave PKCS#8 EC valida")
            }
        }
    }
}

/** 404 / 400 (id desconocido o de otra app): definitivo. 401 / 403 (credenciales), 429 y 5xx: transitorio, se reintenta. */
internal fun appleApiFailure(status: Int): BackendDomainException = when (status) {
    400, 404 -> CanonicalErrorCode.STORE_PURCHASE_INVALID.exception()
    else -> CanonicalErrorCode.UPSTREAM_UNAVAILABLE.exception()
}

/** `status` de Apple (1 activa, 2 caducada, 3 reintento de cobro, 4 periodo de gracia, 5 revocada). */
internal val APPLE_STATUS_ACTIVE = Status.ACTIVE.value
internal val APPLE_STATUS_EXPIRED = Status.EXPIRED.value
internal val APPLE_STATUS_BILLING_RETRY = Status.BILLING_RETRY.value
internal val APPLE_STATUS_GRACE = Status.BILLING_GRACE_PERIOD.value
internal val APPLE_STATUS_REVOKED = Status.REVOKED.value
