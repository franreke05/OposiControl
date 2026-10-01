package com.oposicontrol.admin.app

import com.oposicontrol.admin.app.bootstrap.JvmRuntimeConfigSource
import com.oposicontrol.admin.core.common.config.AppConfigResolver
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * MASTER6-AUTH-012: el `-Doposi.BACKEND_TOKEN=` en blanco de la distribucion de escritorio es un pin: ni la variable
 * de entorno `BACKEND_TOKEN` ni un `.env` pueden volver a meter un token estatico. Sin romper MASTER5-OC-005: un
 * BASE_URL en blanco sigue cayendo a la siguiente capa y PROD sin BASE_URL sigue fallando cerrado.
 */
class BackendTokenBlankPinTest {
    private fun withDotEnv(content: String, block: (File) -> Unit) {
        val dir = createTempDirectory("cfg-pin").toFile()
        try {
            File(dir, ".env").writeText(content)
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aBlankBackendTokenPropertyWinsOverEnvAndDotEnv() = withDotEnv("BACKEND_TOKEN=from-dot-env\n") { dir ->
        for (pin in listOf("", "  ")) {
            val source = JvmRuntimeConfigSource(
                systemProperty = { key -> if (key == "oposi.BACKEND_TOKEN") pin else null },
                systemEnv = { key -> if (key == "BACKEND_TOKEN") "from-env" else null },
                startDir = dir,
            )
            assertNull(source.read("BACKEND_TOKEN"), "pin '$pin'")
            assertEquals("", AppConfigResolver.resolve(source).backendToken)
        }
    }

    @Test
    fun withoutThePropertyTheTokenStillResolvesFromLowerLayers() = withDotEnv("BACKEND_TOKEN=from-dot-env\n") { dir ->
        assertEquals("from-env", JvmRuntimeConfigSource({ null }, { k -> if (k == "BACKEND_TOKEN") "from-env" else null }, dir).read("BACKEND_TOKEN"))
        assertEquals("from-dot-env", JvmRuntimeConfigSource({ null }, { null }, dir).read("BACKEND_TOKEN"))
    }

    @Test
    fun blankBaseUrlStillFallsThroughAndProdStillFailsClosed() = withDotEnv("BASE_URL=https://from-dot-env\n") { dir ->
        assertEquals("https://from-dot-env", JvmRuntimeConfigSource({ "" }, { null }, dir).read("BASE_URL"))
        withDotEnv("APP_ENV=prod\n") { empty ->
            val source = JvmRuntimeConfigSource(
                systemProperty = { key -> if (key == "oposi.BASE_URL") "" else null },
                systemEnv = { null },
                startDir = empty,
            )
            assertFailsWith<IllegalArgumentException> { AppConfigResolver.resolve(source) }
        }
    }
}
