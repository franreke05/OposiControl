package com.oposicontrol.admin.data.remote.api

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders

internal fun HttpRequestBuilder.adminAuthHeaders(
    @Suppress("UNUSED_PARAMETER")
    appConfig: AppConfig,
    sessionLocalDataSource: SessionLocalDataSource,
) {
    val token = sessionLocalDataSource.currentSession()?.accessToken?.takeIf { it.isNotBlank() } ?: return
    header(HttpHeaders.Authorization, "Bearer $token")
}
