package com.oposicontrol.backend

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Guarda de arranque y lectura de configuracion de pagos de tienda: nada de valores reales, solo nombres. */
class StorePaymentsConfigTest {
    private fun env(vararg pairs: Pair<String, String>): (String) -> String? = pairs.toMap()::get

    private class Exit(message: String) : RuntimeException(message)

    private fun guard(environment: (String) -> String?): String? = try {
        enforceStorePaymentsConfig(environment) { throw Exit(it) }
        null
    } catch (e: Exit) {
        e.message
    }

    @Test
    fun withNothingEnabledTheServerStartsAndNoPlatformIsBuilt() {
        assertNull(guard(env()))
        val settings = StorePaymentsSettings.fromEnvironment(env())
        assertNull(settings.google)
        assertNull(settings.apple)
    }

    @Test
    fun anEnabledPlatformWithMissingCredentialsRefusesToStartNamingOnlyTheVariables() {
        val message = guard(env(STORE_GOOGLE_ENABLED_ENV to "true", "PAYMENTS_STORE_PRODUCTS" to "google_play:ot_100=PACK_100:CONSUMABLE"))
        assertTrue(message!!.contains("PAYMENTS_GOOGLE_SERVICE_ACCOUNT_FILE") && message.contains("PAYMENTS_GOOGLE_PUBSUB_AUDIENCE"), message)
        val apple = guard(env(STORE_APPLE_ENABLED_ENV to "true", "PAYMENTS_APPLE_BUNDLE_ID" to "com.x", "PAYMENTS_STORE_PRODUCTS" to "app_store:a=PACK_100:CONSUMABLE"))
        assertTrue(apple!!.contains("PAYMENTS_APPLE_APP_APPLE_ID") && apple.contains("PAYMENTS_APPLE_ROOT_CA_PATHS"), apple)
    }

    @Test
    fun unreadableCredentialFilesAndAnEmptyCatalogAlsoRefuse() {
        val base = arrayOf(
            STORE_GOOGLE_ENABLED_ENV to "true", "PAYMENTS_GOOGLE_PACKAGE_NAME" to "com.x", "PAYMENTS_GOOGLE_PUBSUB_AUDIENCE" to "https://a",
            "PAYMENTS_GOOGLE_PUBSUB_SERVICE_ACCOUNT" to "s@x", "PAYMENTS_GOOGLE_SERVICE_ACCOUNT_FILE" to "/definitely/missing.json",
        )
        assertTrue(guard(env(*base, "PAYMENTS_STORE_PRODUCTS" to "google_play:ot_100=PACK_100:CONSUMABLE"))!!.contains("no se puede leer"))
        assertTrue(guard(env(*base))!!.contains("PAYMENTS_STORE_PRODUCTS"))
    }

    @Test
    fun acceptingSandboxIsOnlyPossibleLocally() {
        assertTrue(guard(env("PAYMENTS_STORE_SANDBOX" to "accept", "APP_ENV" to "production"))!!.contains("PAYMENTS_STORE_SANDBOX"))
        assertNull(guard(env("PAYMENTS_STORE_SANDBOX" to "accept", "APP_ENV" to "test")))
        assertTrue(guard(env("PAYMENTS_STORE_SANDBOX" to "maybe")) != null)
        assertTrue(guard(env("PAYMENTS_STORE_SANDBOX" to "allowlist", "PAYMENTS_STORE_SANDBOX_USERS" to "not-a-uuid")) != null)
    }

    @Test
    fun aCompleteConfigurationStarts() {
        val sa = File.createTempFile("svc-account", ".json").apply { writeText("{}"); deleteOnExit() }
        val root = File.createTempFile("apple-root", ".cer").apply { writeBytes(StoreTestPki().rootDer); deleteOnExit() }
        val settings = StorePaymentsSettings.fromEnvironment(
            env(
                STORE_GOOGLE_ENABLED_ENV to "true", "PAYMENTS_GOOGLE_PACKAGE_NAME" to "com.x", "PAYMENTS_GOOGLE_PUBSUB_AUDIENCE" to "https://a",
                "PAYMENTS_GOOGLE_PUBSUB_SERVICE_ACCOUNT" to "s@x", "PAYMENTS_GOOGLE_SERVICE_ACCOUNT_FILE" to sa.path,
                STORE_APPLE_ENABLED_ENV to "true", "PAYMENTS_APPLE_BUNDLE_ID" to "com.x", "PAYMENTS_APPLE_APP_APPLE_ID" to "123", "PAYMENTS_APPLE_ROOT_CA_PATHS" to root.path,
                "PAYMENTS_STORE_PRODUCTS" to "google_play:ot_100=PACK_100:CONSUMABLE,app_store:com.x.ot100=PACK_100:CONSUMABLE",
            ),
        )
        assertEquals("com.x", settings.google?.packageName)
        assertEquals(123L, settings.apple?.appAppleId)
        assertTrue(settings.apple!!.onlineChecks, "OCSP online checks are on unless explicitly disabled")
        assertEquals(StoreSandboxMode.REJECT, settings.sandbox.mode)
    }

    @Test
    fun theProductMapIsParsedStrictly() {
        val catalog = StoreCatalog.parse("google_play:premium/monthly=PREMIUM_M:SUBSCRIPTION, app_store:com.x.ot100=PACK_100:CONSUMABLE")
        assertEquals("PREMIUM_M", catalog.findSubscription(StorePlatform.GOOGLE_PLAY, "premium", "monthly")?.productCode)
        assertEquals("PACK_100", catalog.find(StorePlatform.APP_STORE, "com.x.ot100")?.productCode)
        listOf("stripe:a=B:CONSUMABLE", "google_play:a=B:OTHER", "google_play:a=B:CONSUMABLE,google_play:a=C:CONSUMABLE", "nonsense").forEach {
            assertFailsWith<IllegalArgumentException>(it) { StoreCatalog.parse(it) }
        }
    }

    @Test
    fun theSandboxPolicyAllowsOnlyWhatItSays() {
        val reject = StoreSandboxPolicy.parse(null, null)
        assertTrue(reject.allows(StoreEnvironment.PRODUCTION, null))
        assertFalse(reject.allows(StoreEnvironment.SANDBOX, TEST_UID))
        val allow = StoreSandboxPolicy.parse("allowlist", TEST_UID.uppercase())
        assertTrue(allow.allows(StoreEnvironment.SANDBOX, TEST_UID))
        assertFalse(allow.allows(StoreEnvironment.SANDBOX, "11111111-1111-4111-8111-111111111111"))
        assertFalse(allow.allows(StoreEnvironment.SANDBOX, null))
    }
}
