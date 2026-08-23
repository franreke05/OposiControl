package com.oposicontrol.admin.data.remote.api

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.network.AuthCredentialsException
import com.oposicontrol.admin.data.remote.dto.LoginRequestDto
import com.oposicontrol.admin.data.remote.dto.RefreshSessionRequestDto
import com.oposicontrol.admin.data.remote.dto.SessionDto
import com.oposicontrol.admin.data.remote.dto.SupabaseAuthErrorDto
import com.oposicontrol.admin.data.remote.dto.SupabaseAuthResponseDto
import com.oposicontrol.admin.data.remote.dto.toFriendlyMessage
import com.oposicontrol.admin.data.remote.dto.toSessionDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess

class AuthApi(
    private val client: HttpClient,
    private val appConfig: AppConfig,
) {
    suspend fun login(request: LoginRequestDto): SessionDto {
        val response = client.post("${requiredSupabaseUrl()}/auth/v1/token?grant_type=password") {
            header("apikey", requiredAnonKey())
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        requireAuthSuccess(response, isRefresh = false)
        return response.body<SupabaseAuthResponseDto>().toSessionDto()
    }

    suspend fun refresh(request: RefreshSessionRequestDto): SessionDto {
        val response = client.post("${requiredSupabaseUrl()}/auth/v1/token?grant_type=refresh_token") {
            header("apikey", requiredAnonKey())
            contentType(ContentType.Application.Json)
            setBody(mapOf("refresh_token" to request.refreshToken))
        }
        requireAuthSuccess(response, isRefresh = true)
        return response.body<SupabaseAuthResponseDto>().toSessionDto()
    }

    /**
     * Si Supabase Auth no respondió con éxito, nunca se intenta decodificar el
     * cuerpo como si fuera una sesión válida — eso es exactamente lo que
     * producía el `JsonConvertException` en crudo ("Fields [access_token,
     * refresh_token, expires_in, user] are required...") cuando el login
     * fallaba: se traduce el error real a un mensaje humano en su lugar.
     */
    private suspend fun requireAuthSuccess(response: HttpResponse, isRefresh: Boolean) {
        if (response.status.isSuccess()) return
        val friendlyMessage = runCatching { response.body<SupabaseAuthErrorDto>() }
            .getOrNull()
            ?.toFriendlyMessage(isRefresh)
            ?: if (isRefresh) "Tu sesión ha caducado. Vuelve a iniciar sesión." else "No se pudo iniciar sesión. Inténtalo de nuevo."
        throw AuthCredentialsException(friendlyMessage)
    }

    suspend fun logout(token: String?) {
        client.post("${requiredSupabaseUrl()}/auth/v1/logout") {
            header("apikey", requiredAnonKey())
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

    private fun requiredAnonKey(): String {
        return appConfig.supabaseAnonKey.ifBlank {
            throw IllegalStateException("SUPABASE_ANON_KEY no esta configurado.")
        }
    }
}
