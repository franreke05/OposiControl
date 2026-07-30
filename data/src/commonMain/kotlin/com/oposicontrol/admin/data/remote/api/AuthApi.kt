package com.oposicontrol.admin.data.remote.api

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.data.remote.dto.LoginRequestDto
import com.oposicontrol.admin.data.remote.dto.RefreshSessionRequestDto
import com.oposicontrol.admin.data.remote.dto.SessionDto
import com.oposicontrol.admin.data.remote.dto.SupabaseAuthResponseDto
import com.oposicontrol.admin.data.remote.dto.toSessionDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType

class AuthApi(
    private val client: HttpClient,
    private val appConfig: AppConfig,
) {
    suspend fun login(request: LoginRequestDto): SessionDto {
        return client.post("${requiredSupabaseUrl()}/auth/v1/token?grant_type=password") {
            header("apikey", requiredPublishableKey())
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body<SupabaseAuthResponseDto>().toSessionDto()
    }

    suspend fun refresh(request: RefreshSessionRequestDto): SessionDto {
        return client.post("${requiredSupabaseUrl()}/auth/v1/token?grant_type=refresh_token") {
            header("apikey", requiredPublishableKey())
            contentType(ContentType.Application.Json)
            setBody(mapOf("refresh_token" to request.refreshToken))
        }.body<SupabaseAuthResponseDto>().toSessionDto()
    }

    suspend fun logout(token: String?) {
        client.post("${requiredSupabaseUrl()}/auth/v1/logout") {
            header("apikey", requiredPublishableKey())
            header(HttpHeaders.Authorization, "Bearer ${token.orEmpty()}")
            contentType(ContentType.Application.Json)
            setBody(emptyMap<String, String>())
        }
    }

    private fun requiredSupabaseUrl(): String {
        return appConfig.supabaseUrl.ifBlank {
            throw IllegalStateException("SUPABASE_URL no esta configurado.")
        }
    }

    private fun requiredPublishableKey(): String {
        return appConfig.supabasePublishableKey.ifBlank {
            throw IllegalStateException("SUPABASE_PUBLISHABLE_KEY no esta configurado.")
        }
    }
}
