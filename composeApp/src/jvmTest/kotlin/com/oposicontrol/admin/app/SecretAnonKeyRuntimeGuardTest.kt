package com.oposicontrol.admin.app

import com.oposicontrol.admin.app.bootstrap.JvmRuntimeConfigSource
import com.oposicontrol.admin.core.common.config.AppConfigResolver
import java.io.File
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** sb_secret runtime guard: the desktop -D / env / .env fallback must reject secret-shaped SUPABASE_ANON_KEY like the build check. */
class SecretAnonKeyRuntimeGuardTest {
    private fun jwt(role: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = enc.encodeToString("""{"iss":"supabase","role":"$role"}""".toByteArray())
        return "$header.$payload.fake-signature"
    }

    private fun resolveVia(source: JvmRuntimeConfigSource) = AppConfigResolver.resolve(source)

    private val fakeSecret = "sb_secret_FAKE_for_test_only"

    @Test fun systemPropertyLayerRejectsASecretKey() {
        val source = JvmRuntimeConfigSource(
            systemProperty = { key -> if (key == "oposi.SUPABASE_ANON_KEY") fakeSecret else null },
            systemEnv = { null },
            startDir = createTempDirectory("cfg-none").toFile(),
        )
        val error = assertFailsWith<IllegalArgumentException> { resolveVia(source) }
        assertFalse(error.message.orEmpty().contains(fakeSecret), "the message must not echo the value")
    }

    @Test fun envVarLayerRejectsASecretKeyCaseInsensitively() {
        val source = JvmRuntimeConfigSource(
            systemProperty = { null },
            systemEnv = { key -> if (key == "SUPABASE_ANON_KEY") "SB_SECRET_FAKE" else null },
            startDir = createTempDirectory("cfg-none").toFile(),
        )
        assertFailsWith<IllegalArgumentException> { resolveVia(source) }
    }

    @Test fun dotEnvLayerRejectsASecretKey() {
        val dir = createTempDirectory("cfg").toFile()
        try {
            File(dir, ".env").writeText("SUPABASE_ANON_KEY=$fakeSecret\n")
            val source = JvmRuntimeConfigSource(systemProperty = { null }, systemEnv = { null }, startDir = dir)
            assertFailsWith<IllegalArgumentException> { resolveVia(source) }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun serviceRoleJwtIsRejectedButAnonJwtAndPublishableKeyResolve() {
        fun resolve(key: String) = AppConfigResolver.resolve(
            com.oposicontrol.admin.core.common.config.MapRuntimeConfigSource(mapOf("SUPABASE_ANON_KEY" to key)),
        )
        assertFailsWith<IllegalArgumentException> { resolve(jwt("service_role")) }
        assertEquals(jwt("anon"), resolve(jwt("anon")).supabaseAnonKey)
        assertEquals("sb_publishable_FAKE", resolve("sb_publishable_FAKE").supabaseAnonKey)
        assertEquals("not.a.jwt", resolve("not.a.jwt").supabaseAnonKey)
    }
}
