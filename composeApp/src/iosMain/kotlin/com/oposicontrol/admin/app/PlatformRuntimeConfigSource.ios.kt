package com.oposicontrol.admin.app.bootstrap

import com.oposicontrol.admin.core.common.config.RuntimeConfigSource
import platform.Foundation.NSProcessInfo

actual fun platformRuntimeConfigSource(): RuntimeConfigSource {
    val environment = NSProcessInfo.processInfo.environment
    return object : RuntimeConfigSource {
        override fun read(key: String): String? = environment[key] as? String
    }
}
