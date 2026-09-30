package com.oposicontrol.admin.app

import com.oposicontrol.admin.core.common.config.AppConfigResolver
import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.core.common.config.MapRuntimeConfigSource
import com.oposicontrol.admin.app.bootstrap.JvmRuntimeConfigSource
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** MASTER5-OC-005: valores en blanco = ausentes; STAGING/PROD sin BASE_URL fallan cerrado; esquema https sin distinguir mayusculas. */
class BlankConfigFailsClosedTest {
    private fun resolve(vararg pairs: Pair<String, String>) =
        AppConfigResolver.resolve(MapRuntimeConfigSource(mapOf(*pairs)))

    @Test fun blankBaseUrlInDevFallsBackToTheLocalDefault() {
        assertEquals("http://localhost:8080", resolve("BASE_URL" to "").baseUrl)
        assertEquals("http://localhost:8080", resolve("APP_ENV" to "dev", "BASE_URL" to "   ").baseUrl)
    }

    @Test fun prodAndStagingWithoutAnExplicitBaseUrlFailClosed() {
        for (env in listOf("prod", "staging")) {
            assertFailsWith<IllegalArgumentException> { resolve("APP_ENV" to env) }
            assertFailsWith<IllegalArgumentException> { resolve("APP_ENV" to env, "BASE_URL" to "") }
            assertFailsWith<IllegalArgumentException> { resolve("APP_ENV" to env, "BASE_URL" to "  \t ") }
        }
    }

    @Test fun prodWithAnExplicitHttpsBaseUrlResolves() {
        val cfg = resolve("APP_ENV" to "prod", "BASE_URL" to "  https://api.example.com ")
        assertEquals(Environment.PROD, cfg.environment)
        assertEquals("https://api.example.com", cfg.baseUrl)
    }

    @Test fun uppercaseSchemeIsAcceptedAndPlainHttpStillRejectedInProd() {
        assertEquals("HTTPS://api.example.com", resolve("APP_ENV" to "prod", "BASE_URL" to "HTTPS://api.example.com").baseUrl)
        assertFailsWith<IllegalArgumentException> { resolve("APP_ENV" to "prod", "BASE_URL" to "HTTP://api.example.com") }
        assertFailsWith<IllegalArgumentException> { resolve("APP_ENV" to "prod", "BASE_URL" to "https://LOCALHOST:8080") }
    }

    @Test fun blankBackendTokenStaysEmptyNotWhitespace() {
        assertEquals("", resolve("BACKEND_TOKEN" to "   ").backendToken)
        assertEquals("tok", resolve("BACKEND_TOKEN" to " tok ").backendToken)
    }

    @Test fun aBlankLayerFallsThroughToTheNextJvmLayer() {
        val dir = createTempDirectory("cfg").toFile()
        try {
            File(dir, ".env").writeText("BASE_URL=https://from-dot-env\n")
            val source = JvmRuntimeConfigSource(
                systemProperty = { "   " },
                systemEnv = { key -> if (key == "BASE_URL") "" else null },
                startDir = dir,
            )
            assertEquals("https://from-dot-env", source.read("BASE_URL"))
        } finally {
            dir.deleteRecursively()
        }
        val empty = createTempDirectory("cfg-empty").toFile()
        try {
            assertNull(JvmRuntimeConfigSource({ "" }, { " " }, empty).read("BASE_URL"))
            File(empty, ".env").writeText("BASE_URL=   ")
            assertNull(JvmRuntimeConfigSource({ null }, { null }, empty).read("BASE_URL"), "un .env en blanco tambien es ausente")
        } finally {
            empty.deleteRecursively()
        }
    }
}
