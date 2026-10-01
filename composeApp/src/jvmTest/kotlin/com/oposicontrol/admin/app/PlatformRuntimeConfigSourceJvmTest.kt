package com.oposicontrol.admin.app

import com.oposicontrol.admin.app.bootstrap.JvmRuntimeConfigSource
import com.oposicontrol.admin.app.bootstrap.findDotEnvFile
import com.oposicontrol.admin.app.bootstrap.parseDotEnv
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlatformRuntimeConfigSourceJvmTest {
    private lateinit var tempRoot: File

    @BeforeTest
    fun setUp() {
        tempRoot = Files.createTempDirectory("oposi-config-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    @Test
    fun systemPropertyWinsOverEnvVarAndDotEnv() {
        val source = JvmRuntimeConfigSource(
            systemProperty = { key -> if (key == "oposi.BASE_URL") "https://from-system-property" else null },
            systemEnv = { key -> if (key == "BASE_URL") "https://from-env-var" else null },
            startDir = tempRoot,
        )
        assertEquals("https://from-system-property", source.read("BASE_URL"))
    }

    @Test
    fun envVarWinsOverDotEnvWhenNoSystemProperty() {
        val source = JvmRuntimeConfigSource(
            systemProperty = { null },
            systemEnv = { key -> if (key == "BASE_URL") "https://from-env-var" else null },
            startDir = tempRoot,
        )
        assertEquals("https://from-env-var", source.read("BASE_URL"))
    }

    @Test
    fun dotEnvIsTheLastResortFallback() {
        File(tempRoot, ".env").writeText("BASE_URL=https://from-dot-env\n")
        val source = JvmRuntimeConfigSource(
            systemProperty = { null },
            systemEnv = { null },
            startDir = tempRoot,
        )
        assertEquals("https://from-dot-env", source.read("BASE_URL"))
    }

    @Test
    fun missingKeyReturnsNullFromEveryLayer() {
        val source = JvmRuntimeConfigSource(
            systemProperty = { null },
            systemEnv = { null },
            startDir = tempRoot,
        )
        assertNull(source.read("SUPABASE_URL"))
    }

    @Test
    fun parseDotEnvSkipsBlankAndCommentLines() {
        val values = parseDotEnv(
            """
            # a comment

            APP_ENV=dev
            # another comment
            BASE_URL=http://localhost:8080
            """.trimIndent(),
        )
        assertEquals(mapOf("APP_ENV" to "dev", "BASE_URL" to "http://localhost:8080"), values)
    }

    @Test
    fun parseDotEnvStripsSurroundingQuotes() {
        val values = parseDotEnv("SUPABASE_ANON_KEY=\"quoted-value\"")
        assertEquals("quoted-value", values["SUPABASE_ANON_KEY"])
    }

    @Test
    fun parseDotEnvKeepsEqualsSignsInsideTheValue() {
        val values = parseDotEnv("BACKEND_TOKEN=abc=def==")
        assertEquals("abc=def==", values["BACKEND_TOKEN"])
    }

    @Test
    fun findDotEnvFileWalksUpParentDirectories() {
        val nested = File(tempRoot, "a/b/c").apply { mkdirs() }
        File(tempRoot, ".env").writeText("APP_ENV=dev\n")
        val found = findDotEnvFile(nested, maxDepth = 8)
        assertEquals(File(tempRoot, ".env").absolutePath, found?.absolutePath)
    }

    @Test
    fun findDotEnvFileReturnsNullWhenNoneExistsWithinMaxDepth() {
        val nested = File(tempRoot, "a/b/c").apply { mkdirs() }
        assertNull(findDotEnvFile(nested, maxDepth = 8))
    }

    private val bakedKeys = listOf(
        "SUPABASE_URL", "SUPABASE_ANON_KEY", "SENTRY_DESKTOP_DSN", "SENTRY_DESKTOP_ENVIRONMENT", "SENTRY_DESKTOP_RELEASE",
    )

    @Test
    fun aBlankBakedPropertyPinsEveryBakedKeyAgainstEnvAndDotEnv() {
        File(tempRoot, ".env").writeText(bakedKeys.joinToString("\n") { "$it=from-dot-env" })
        for (key in bakedKeys) {
            val source = JvmRuntimeConfigSource(
                systemProperty = { k -> if (k == "oposi.$key") "" else null },
                systemEnv = { k -> if (k == key) "from-env" else null },
                startDir = tempRoot,
            )
            assertNull(source.read(key), key)
        }
    }

    @Test
    fun anAbsentBakedPropertyStillFallsThroughAndUnpinnedBlankKeysStillFallThrough() {
        File(tempRoot, ".env").writeText("SUPABASE_URL=from-dot-env\nENABLE_ANALYTICS=true\n")
        val absent = JvmRuntimeConfigSource({ null }, { k -> if (k == "SUPABASE_URL") "from-env" else null }, tempRoot)
        assertEquals("from-env", absent.read("SUPABASE_URL"))
        val unpinned = JvmRuntimeConfigSource({ k -> if (k == "oposi.ENABLE_ANALYTICS") "" else null }, { null }, tempRoot)
        assertEquals("true", unpinned.read("ENABLE_ANALYTICS"))
    }
}
