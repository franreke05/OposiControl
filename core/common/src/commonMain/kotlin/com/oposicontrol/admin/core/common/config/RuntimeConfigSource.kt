package com.oposicontrol.admin.core.common.config

interface RuntimeConfigSource {
    fun read(key: String): String?
}

class MapRuntimeConfigSource(
    private val values: Map<String, String>,
) : RuntimeConfigSource {
    override fun read(key: String): String? = values[key]
}
