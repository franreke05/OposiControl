package com.oposicontrol.backend

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * App Store: JWS de StoreKit 2 y ASSN V2 verificados con la libreria oficial de Apple contra una raiz de PRUEBA generada aqui
 * ([StoreTestPki]). Sin red (online checks apagados) y sin claves reales.
 */
class StoreAppStoreProviderTest {
    private val pki = StoreTestPki()
    private val bundle = "com.oposibot.test"
    private val appAppleId = 1234567890L
    private val account = "6f1c2c1e-1111-4a6b-9b1e-2a2b2c2d2e2f"
    private val now = Instant.now()
    private val provider = AppStoreProvider(AppStoreConfig(bundle, appAppleId, emptyList(), onlineChecks = false), listOf(pki.rootDer))

    private fun tx(
        type: String = "Consumable",
        product: String = "com.oposibot.ot100",
        env: String = "Production",
        bundleId: String = bundle,
        quantity: Int = 1,
        revocation: Long? = null,
        expires: Long? = null,
        token: String? = account,
        tid: String = "2000000001",
    ) = buildString {
        append("""{"transactionId":"$tid","originalTransactionId":"1000000001","bundleId":"$bundleId","productId":"$product",""")
        append(""""purchaseDate":${now.toEpochMilli() - 1000},"quantity":$quantity,"type":"$type","signedDate":${now.toEpochMilli()},""")
        append(""""environment":"$env","price":990,"currency":"EUR","storefront":"ESP"""")
        token?.let { append(""","appAccountToken":"$it"""") }
        revocation?.let { append(""","revocationDate":$it,"revocationReason":0""") }
        expires?.let { append(""","expiresDate":$it""") }
        append("}")
    }

    private fun renewal(autoRenew: Int = 1, grace: Long? = null, retry: Boolean = false, env: String = "Production") =
        """{"originalTransactionId":"1000000001","productId":"com.oposibot.premium","autoRenewStatus":$autoRenew,"isInBillingRetryPeriod":$retry,""" +
            """"signedDate":${now.toEpochMilli()},"environment":"$env"""" + (grace?.let { ""","gracePeriodExpiresDate":$it""" } ?: "") + "}"

    private fun notification(type: String, txJws: String?, subtype: String? = null, renewalJws: String? = null, env: String = "Production", bundleId: String = bundle, uuid: String = "uuid-1") =
        """{"notificationType":"$type",""" + (subtype?.let { """"subtype":"$it",""" } ?: "") +
            """"notificationUUID":"$uuid","version":"2.0","signedDate":${now.toEpochMilli()},"data":{"environment":"$env","bundleId":"$bundleId","appAppleId":$appAppleId""" +
            (txJws?.let { ""","signedTransactionInfo":"$it"""" } ?: "") + (renewalJws?.let { ""","signedRenewalInfo":"$it"""" } ?: "") + "}}"

    private suspend fun webhook(payload: String) = provider.parseNotification({ null }, """{"signedPayload":"$payload"}""".toByteArray(), now)

    private suspend fun rejectsVerify(jws: String, code: CanonicalErrorCode, product: String = "com.oposibot.ot100", kind: StoreKind = StoreKind.CONSUMABLE) {
        val e = assertFailsWith<BackendDomainException> { provider.verify(product, kind, jws) }
        assertEquals(code.name, e.errorCode)
    }

    @Test
    fun aValidConsumableTransactionIsVerifiedWithItsAccountTokenEnvironmentAndAuditPriceOnly() = runTest {
        val facts = provider.verify("com.oposibot.ot100", StoreKind.CONSUMABLE, pki.sign(tx()))
        assertEquals("2000000001", facts.purchaseKey)
        assertEquals(account, facts.accountToken)
        assertEquals(StoreEnvironment.PRODUCTION, facts.environment)
        assertEquals(StorePurchaseState.PURCHASED, facts.state)
        assertEquals("990", facts.audit["precioMilesimas"])  // milesimas de la moneda del storefront: solo auditoria (PAY-R1)
        assertEquals(1, facts.quantity)
    }

    @Test
    fun aSandboxTransactionIsVerifiedWithTheSandboxVerifierAndMarkedSandbox() = runTest {
        assertEquals(StoreEnvironment.SANDBOX, provider.verify("com.oposibot.ot100", StoreKind.CONSUMABLE, pki.sign(tx(env = "Sandbox"))).environment)
    }

    @Test
    fun aForgedSignatureIsRejected() = runTest {
        val jws = pki.sign(tx())
        val parts = jws.split('.')
        val forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(tx(quantity = 1, tid = "9999").toByteArray())
        rejectsVerify("${parts[0]}.$forgedPayload.${parts[2]}", CanonicalErrorCode.STORE_PURCHASE_INVALID)
    }

    @Test
    fun aChainThatDoesNotEndInTheConfiguredAppleRootIsRejected() = runTest {
        rejectsVerify(StoreTestPki().sign(tx()), CanonicalErrorCode.STORE_PURCHASE_INVALID)
    }

    @Test
    fun certificatesWithoutTheAppleOidsAreRejected() = runTest {
        val noLeafOid = StoreTestPki(withLeafOid = false)
        val p = AppStoreProvider(AppStoreConfig(bundle, appAppleId, emptyList(), false), listOf(noLeafOid.rootDer))
        assertFailsWith<BackendDomainException> { p.verify("com.oposibot.ot100", StoreKind.CONSUMABLE, noLeafOid.sign(tx())) }
        val noWwdr = StoreTestPki(withIntermediateOid = false)
        val q = AppStoreProvider(AppStoreConfig(bundle, appAppleId, emptyList(), false), listOf(noWwdr.rootDer))
        assertFailsWith<BackendDomainException> { q.verify("com.oposibot.ot100", StoreKind.CONSUMABLE, noWwdr.sign(tx())) }
    }

    @Test
    fun aJwsWithoutTheFullX5cChainIsRejected() = runTest {
        rejectsVerify(pki.sign(tx(), chain = listOf(pki.leaf, pki.intermediate)), CanonicalErrorCode.STORE_PURCHASE_INVALID)
    }

    @Test
    fun theWrongBundleIsRejected() = runTest {
        rejectsVerify(pki.sign(tx(bundleId = "com.other.app")), CanonicalErrorCode.STORE_PURCHASE_INVALID)
    }

    @Test
    fun xcodeAndLocalTestingEnvironmentsAreNeverAccepted() = runTest {
        rejectsVerify(pki.sign(tx(env = "Xcode")), CanonicalErrorCode.STORE_PURCHASE_INVALID)
        rejectsVerify(pki.sign(tx(env = "LocalTesting")), CanonicalErrorCode.STORE_PURCHASE_INVALID)
        rejectsVerify("not-a-jws", CanonicalErrorCode.STORE_PURCHASE_INVALID)
    }

    @Test
    fun anotherProductOrKindThanTheOneClaimedIsRejected() = runTest {
        rejectsVerify(pki.sign(tx()), CanonicalErrorCode.STORE_PRODUCT_UNKNOWN, product = "com.oposibot.ot500")
        rejectsVerify(pki.sign(tx()), CanonicalErrorCode.STORE_PRODUCT_UNKNOWN, kind = StoreKind.SUBSCRIPTION)
        rejectsVerify(pki.sign(tx(type = "Non-Consumable")), CanonicalErrorCode.STORE_PRODUCT_UNKNOWN)
    }

    @Test
    fun quantityRevocationAndMissingAccountTokenAreReportedForTheServiceToRefuse() = runTest {
        assertEquals(3, provider.verify("com.oposibot.ot100", StoreKind.CONSUMABLE, pki.sign(tx(quantity = 3))).quantity)
        assertEquals(StorePurchaseState.REVOKED, provider.verify("com.oposibot.ot100", StoreKind.CONSUMABLE, pki.sign(tx(revocation = now.toEpochMilli()))).state)
        assertNull(provider.verify("com.oposibot.ot100", StoreKind.CONSUMABLE, pki.sign(tx(token = null))).accountToken)
    }

    @Test
    fun subscriptionsUseTheOriginalTransactionIdAndExpiry() = runTest {
        val facts = provider.verify("com.oposibot.premium", StoreKind.SUBSCRIPTION,
            pki.sign(tx(type = "Auto-Renewable Subscription", product = "com.oposibot.premium", expires = now.plusSeconds(86_400).toEpochMilli())))
        assertEquals("1000000001", facts.purchaseKey)
        assertEquals("activa", facts.subscription?.estado)
        val expired = provider.verify("com.oposibot.premium", StoreKind.SUBSCRIPTION,
            pki.sign(tx(type = "Auto-Renewable Subscription", product = "com.oposibot.premium", expires = now.minusSeconds(60).toEpochMilli())))
        assertEquals("caducada", expired.subscription?.estado)
        assertEquals("expiro", expired.subscription?.finMotivo)
    }

    // ---------- ASSN V2 ----------

    @Test
    fun aOneTimeChargeNotificationIsAuthenticatedAndKeyedByNotificationUuid() = runTest {
        val event = webhook(pki.sign(notification("ONE_TIME_CHARGE", pki.sign(tx()))))
        assertEquals("assn:uuid-1", event.eventId)
        assertEquals(StoreEventType.PURCHASE, event.type)
        assertEquals("2000000001", event.purchaseKey)
        assertEquals(StoreEnvironment.PRODUCTION, event.environment)
        assertEquals("2000000001", provider.factsFor(event.type, event.purchaseKey, event.data)?.purchaseKey)
    }

    @Test
    fun aNotificationWithAForgedSignatureOrAForeignChainOrWrongBundleIs401() = runTest {
        val good = pki.sign(notification("ONE_TIME_CHARGE", pki.sign(tx())))
        val parts = good.split('.')
        val forged = "${parts[0]}.${java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(notification("REFUND", pki.sign(tx())).toByteArray())}.${parts[2]}"
        for (payload in listOf(forged, StoreTestPki().sign(notification("ONE_TIME_CHARGE", pki.sign(tx()))), pki.sign(notification("ONE_TIME_CHARGE", pki.sign(tx()), bundleId = "com.other")))) {
            val e = assertFailsWith<BackendDomainException> { webhook(payload) }
            assertEquals("WEBHOOK_SIGNATURE_INVALID", e.errorCode)
        }
        // la transaccion anidada tambien se verifica: firmada por otra cadena -> 401
        val e = assertFailsWith<BackendDomainException> { webhook(pki.sign(notification("ONE_TIME_CHARGE", StoreTestPki().sign(tx())))) }
        assertEquals("WEBHOOK_SIGNATURE_INVALID", e.errorCode)
        assertEquals("MALFORMED_BODY", assertFailsWith<BackendDomainException> { provider.parseNotification({ null }, "{}".toByteArray(), now) }.errorCode)
    }

    @Test
    fun refundRevokeAndRefundReversedAreReDerivedFromTheSignedTransaction() = runTest {
        val refund = webhook(pki.sign(notification("REFUND", pki.sign(tx(revocation = now.toEpochMilli())), uuid = "r1")))
        assertEquals(StoreEventType.REFUND, refund.type)
        assertEquals("CONSUMABLE", refund.data["kind"]?.jsonPrimitive?.content)
        val subRevoked = webhook(pki.sign(notification("REVOKE",
            pki.sign(tx(type = "Auto-Renewable Subscription", product = "com.oposibot.premium", revocation = now.toEpochMilli(), expires = now.plusSeconds(86_400).toEpochMilli())), uuid = "r2")))
        val revokedFacts = provider.factsFor(subRevoked.type, subRevoked.purchaseKey, subRevoked.data)!!
        assertEquals("caducada", revokedFacts.subscription?.estado)
        assertEquals("revocada", revokedFacts.subscription?.finMotivo)
        val reversed = webhook(pki.sign(notification("REFUND_REVERSED",
            pki.sign(tx(type = "Auto-Renewable Subscription", product = "com.oposibot.premium", expires = now.plusSeconds(86_400).toEpochMilli())), uuid = "r3")))
        assertEquals(StoreEventType.REFUND_REVERSED, reversed.type)
        assertEquals("activa", provider.factsFor(reversed.type, reversed.purchaseKey, reversed.data)?.subscription?.estado)
    }

    @Test
    fun graceBillingRetryAndAutoRenewOffMapToAccessStates() = runTest {
        val sub = { expires: Long -> pki.sign(tx(type = "Auto-Renewable Subscription", product = "com.oposibot.premium", expires = expires)) }
        val grace = webhook(pki.sign(notification("DID_FAIL_TO_RENEW", sub(now.minusSeconds(60).toEpochMilli()), "GRACE_PERIOD",
            pki.sign(renewal(grace = now.plusSeconds(3 * 86_400).toEpochMilli(), retry = true)), uuid = "g1")))
        assertEquals("activa", provider.factsFor(grace.type, grace.purchaseKey, grace.data)?.subscription?.estado)
        val hold = webhook(pki.sign(notification("DID_FAIL_TO_RENEW", sub(now.minusSeconds(60).toEpochMilli()), null, pki.sign(renewal(retry = true)), uuid = "g2")))
        assertEquals("pausada", provider.factsFor(hold.type, hold.purchaseKey, hold.data)?.subscription?.estado)
        val off = webhook(pki.sign(notification("DID_CHANGE_RENEWAL_STATUS", sub(now.plusSeconds(86_400).toEpochMilli()), "AUTO_RENEW_DISABLED", pki.sign(renewal(autoRenew = 0)), uuid = "g3")))
        assertEquals("cancelada", provider.factsFor(off.type, off.purchaseKey, off.data)?.subscription?.estado)
        val expired = webhook(pki.sign(notification("EXPIRED", sub(now.minusSeconds(60).toEpochMilli()), "VOLUNTARY", pki.sign(renewal(autoRenew = 0)), uuid = "g4")))
        assertEquals("caducada", provider.factsFor(expired.type, expired.purchaseKey, expired.data)?.subscription?.estado)
    }

    @Test
    fun testAndConsumptionRequestNotificationsAreRecognised() = runTest {
        assertEquals(StoreEventType.TEST, webhook(pki.sign(notification("TEST", null, uuid = "t1"))).type)
        assertEquals(StoreEventType.CONSUMPTION_REQUEST, webhook(pki.sign(notification("CONSUMPTION_REQUEST", pki.sign(tx()), uuid = "t2"))).type)
    }
}
