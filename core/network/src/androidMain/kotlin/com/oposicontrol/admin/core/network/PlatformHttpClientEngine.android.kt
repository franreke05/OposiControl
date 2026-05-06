package com.oposicontrol.admin.core.network

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp

actual fun platformHttpClientEngineFactory(): HttpClientEngineFactory<*> = OkHttp
