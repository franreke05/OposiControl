package com.oposicontrol.admin.app.bootstrap

import com.oposicontrol.admin.core.common.config.RuntimeConfigSource

actual fun platformRuntimeConfigSource(): RuntimeConfigSource {
    return object : RuntimeConfigSource {
        override fun read(key: String): String? {
            return System.getProperty("oposi.$key") ?: System.getenv(key)
        }
    }
}
