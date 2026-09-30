package com.oposicontrol.admin.app.bootstrap

import com.oposicontrol.admin.core.common.config.RuntimeConfigSource
import java.io.File

actual fun platformRuntimeConfigSource(): RuntimeConfigSource = JvmRuntimeConfigSource()

/**
 * JVM config resolution, checked in this order:
 * 1. `-Doposi.<KEY>` system property — what Gradle's `:composeApp:run`,
 *    `:composeApp:jvmRun` and the packaged native distribution's baked-in
 *    `jvmArgs` set (see composeApp/build.gradle.kts).
 * 2. `<KEY>` OS environment variable — a shell/CI that exported it directly.
 * 3. `.env` at the repo root, located by walking up from the JVM process's
 *    working directory. Covers running `MainKt` directly (e.g. Android
 *    Studio's gutter "Run" on `fun main()`), which bypasses Gradle entirely
 *    and therefore never receives 1 or 2.
 *
 * Never logs a resolved value (some of these are secrets). Exposed as an
 * open class with injectable lookups (not a private object calling
 * System.* directly) so every layer is unit-testable without touching real
 * process state — see PlatformRuntimeConfigSourceJvmTest.
 */
internal open class JvmRuntimeConfigSource(
    private val systemProperty: (String) -> String? = System::getProperty,
    private val systemEnv: (String) -> String? = System::getenv,
    private val startDir: File = File(System.getProperty("user.dir")),
) : RuntimeConfigSource {
    private val dotEnv: Map<String, String> by lazy { loadDotEnv(startDir) }

    override fun read(key: String): String? {
        // MASTER5-OC-005: una capa en blanco (`-Doposi.BASE_URL=`, `BASE_URL=` exportada vacia) es "no definida" y
        // deja pasar a la siguiente capa; los valores se devuelven recortados.
        systemProperty("oposi.$key")?.normalized()?.let { return it }
        systemEnv(key)?.normalized()?.let { return it }
        return dotEnv[key]?.normalized()
    }
}

private fun String.normalized(): String? = trim().ifBlank { null }

internal fun loadDotEnv(startDir: File, maxDepth: Int = 8): Map<String, String> {
    val file = findDotEnvFile(startDir, maxDepth) ?: return emptyMap()
    return parseDotEnv(file.readText())
}

internal fun findDotEnvFile(startDir: File, maxDepth: Int): File? {
    var dir: File? = startDir.absoluteFile
    var depth = 0
    while (dir != null && depth <= maxDepth) {
        val candidate = File(dir, ".env")
        if (candidate.isFile) return candidate
        dir = dir.parentFile
        depth++
    }
    return null
}

internal fun parseDotEnv(text: String): Map<String, String> {
    val values = mutableMapOf<String, String>()
    for (rawLine in text.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val separator = line.indexOf('=')
        if (separator <= 0) continue
        val key = line.substring(0, separator).trim()
        var value = line.substring(separator + 1).trim()
        if (value.length >= 2 &&
            ((value.first() == '"' && value.last() == '"') || (value.first() == '\'' && value.last() == '\''))
        ) {
            value = value.substring(1, value.length - 1)
        }
        if (key.isNotEmpty()) values[key] = value
    }
    return values
}
