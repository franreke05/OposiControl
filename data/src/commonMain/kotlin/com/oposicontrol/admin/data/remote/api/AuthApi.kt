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
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.ServerResponseException
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
        // MASTER6-AUTH-003: en un refresh, solo un 4xx de rechazo (400 invalid_grant, 401, 403...) dice que el refresh
        // token ya no vale. Un 5xx, un 429 o un 408 son transitorios: se lanzan como error de transporte
        // (AppError.Network) para que nadie borre la sesion por una caida del proveedor o un limite de peticiones.
        if (isRefresh && !isDefinitiveAuthRejection(response.status.value)) throw transportFailure(response)
        val friendlyMessage = runCatching { response.body<SupabaseAuthErrorDto>() }
            .getOrNull()
            ?.toFriendlyMessage(isRefresh)
            ?: if (isRefresh) "Tu sesión ha caducado. Vuelve a iniciar sesión." else "No se pudo iniciar sesión. Inténtalo de nuevo."
        throw AuthCredentialsException(friendlyMessage)
    }

    /**
     * MASTER6-AUTH-002: revoca la sesion de ESTE equipo (`scope=local`) y comprueba la respuesta.
     *
     * - 2xx: revocada.
     * - 401/403: el access token ya no vale (caducado); se lanza [AuthCredentialsException]
     *   (`AppError.Authentication`) para que el repositorio refresque y reintente una vez.
     * - Cualquier otro estado: error de transporte (`AppError.Network`): la revocacion NO se ha completado.
     *
     * `scope=local` y no el `global` por defecto de Supabase: cerrar sesion aqui no debe expulsar al mismo admin de
     * otros equipos, ni matar una sesion NUEVA que el mismo usuario haya abierto en este equipo mientras la
     * revocacion de la anterior seguia en vuelo.
     */
    suspend fun logout(token: String?) {
        val response = client.post("${requiredSupabaseUrl()}/auth/v1/logout?scope=local") {
            header("apikey", requiredAnonKey())
            header(HttpHeaders.Authorization, "Bearer ${token.orEmpty()}")
            contentType(ContentType.Application.Json)
            setBody(emptyMap<String, String>())
        }
        if (response.status.isSuccess()) return
        val status = response.status.value
        if (status == 401 || status == 403) {
            throw AuthCredentialsException("Tu sesión ha caducado. Vuelve a iniciar sesión.")
        }
        throw transportFailure(response)
    }

    /** El cuerpo NO viaja en la excepcion: puede traer detalles del proveedor; el mapeo a AppError usa el estado. */
    private fun transportFailure(response: HttpResponse): ResponseException = when (response.status.value) {
        in 500..599 -> ServerResponseException(response, "")
        in 400..499 -> ClientRequestException(response, "")
        else -> ResponseException(response, "")
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

    companion object {
        /**
         * Un 4xx de Supabase Auth que rechaza la credencial de forma definitiva. Quedan fuera los 4xx transitorios:
         * 408 (timeout), 425 (too early) y 429 (rate limit).
         */
        fun isDefinitiveAuthRejection(status: Int): Boolean = status in 400..499 && status !in TRANSIENT_4XX

        private val TRANSIENT_4XX = setOf(408, 425, 429)
    }
}
