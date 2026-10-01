package com.oposicontrol.admin.core.common.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** INV-NETWORK-COMPLETE: outside DEV a missing/empty/http SUPABASE_URL is a LOUD config error; http media only via the explicit DEV flag. */
class AppConfigResolverSupabaseUrlTest {
    private fun resolve(vararg pairs: Pair<String, String>) = AppConfigResolver.resolve(MapRuntimeConfigSource(mapOf(*pairs)))
    private val nonDev = listOf("staging", "prod")

    @Test fun nonDevWithoutSupabaseUrlFailsLoudly() {
        for (env in nonDev) {
            for (value in listOf(null, "", "   ")) {
                val pairs = buildList {
                    add("APP_ENV" to env); add("BASE_URL" to "https://api.example.com")
                    if (value != null) add("SUPABASE_URL" to value)
                }.toTypedArray()
                val error = assertFailsWith<IllegalArgumentException>("$env/$value") { resolve(*pairs) }
                assertTrue(error.message.orEmpty().contains("SUPABASE_URL"), error.message)
            }
        }
    }

    @Test fun nonDevWithHttpOrLocalhostSupabaseUrlFailsLoudly() {
        for (env in nonDev) {
            for (value in listOf("http://proj.supabase.co", "https://localhost:54321")) {
                assertFailsWith<IllegalArgumentException>("$env/$value") {
                    resolve("APP_ENV" to env, "BASE_URL" to "https://api.example.com", "SUPABASE_URL" to value)
                }
            }
        }
    }

    @Test fun nonDevWithHttpsSupabaseUrlResolvesAndNeverEnablesInsecureMedia() {
        val cfg = resolve("APP_ENV" to "prod", "BASE_URL" to "https://api.example.com", "SUPABASE_URL" to "https://proj.supabase.co/")
        assertEquals("https://proj.supabase.co", cfg.supabaseUrl)
        assertFalse(cfg.allowInsecureDevMedia)
    }

    @Test fun insecureDevMediaFlagIsRejectedOutsideDev() {
        for (env in nonDev) {
            assertFailsWith<IllegalArgumentException>(env) {
                resolve(
                    "APP_ENV" to env, "BASE_URL" to "https://api.example.com", "SUPABASE_URL" to "https://proj.supabase.co",
                    AppConfigResolver.KEY_ALLOW_INSECURE_DEV_MEDIA to "true",
                )
            }
        }
    }

    @Test fun devKeepsEmptyAndHttpSupabaseUrlAndTheFlagIsOptIn() {
        assertEquals("", resolve().supabaseUrl)
        assertFalse(resolve("SUPABASE_URL" to "http://10.0.2.2:54321").allowInsecureDevMedia)
        val cfg = resolve("SUPABASE_URL" to "http://10.0.2.2:54321", AppConfigResolver.KEY_ALLOW_INSECURE_DEV_MEDIA to "true")
        assertTrue(cfg.allowInsecureDevMedia)
        assertFalse(resolve(AppConfigResolver.KEY_ALLOW_INSECURE_DEV_MEDIA to "yes").allowInsecureDevMedia)
    }
}
