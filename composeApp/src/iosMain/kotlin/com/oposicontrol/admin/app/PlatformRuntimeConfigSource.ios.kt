package com.oposicontrol.admin.app.bootstrap

import com.oposicontrol.admin.core.common.config.RuntimeConfigSource
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.Foundation.NSBundle
import platform.Foundation.NSProcessInfo
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen

actual fun platformRuntimeConfigSource(): RuntimeConfigSource {
    val environment = NSProcessInfo.processInfo.environment
    val localSecrets = readLocalSecrets()
    return object : RuntimeConfigSource {
        override fun read(key: String): String? {
            return (environment[key] as? String)?.takeIf { it.isConcreteValue() }
                ?: (NSBundle.mainBundle.objectForInfoDictionaryKey(key) as? String)
                    ?.takeIf { it.isConcreteValue() }
                ?: localSecrets[key]?.takeIf { it.isConcreteValue() }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readLocalSecrets(): Map<String, String> {
    val path = NSBundle.mainBundle.pathForResource("LocalSecrets", ofType = "env") ?: return emptyMap()
    val file = fopen(path, "r") ?: return emptyMap()
    return try {
        memScoped {
            val values = mutableMapOf<String, String>()
            val buffer = allocArray<ByteVar>(4096)
            while (fgets(buffer, 4096, file) != null) {
                val line = buffer.toKString().trim()
                if (line.isBlank() || line.startsWith("#") || !line.contains("=")) continue
                values[line.substringBefore("=").trim()] = line.substringAfter("=").trim()
            }
            values
        }
    } finally {
        fclose(file)
    }
}

private fun String.isConcreteValue(): Boolean {
    val trimmed = trim()
    return trimmed.isNotEmpty() && !trimmed.startsWith("\$(")
}
