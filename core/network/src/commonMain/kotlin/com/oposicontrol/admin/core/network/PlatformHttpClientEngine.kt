package com.oposicontrol.admin.core.network

import io.ktor.client.engine.HttpClientEngineFactory

expect fun platformHttpClientEngineFactory(): HttpClientEngineFactory<*>
