package com.oposicontrol.admin.data.remote.api

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.repository.StaleSessionRefresher
import com.oposicontrol.admin.domain.model.Session
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodedPath
import io.ktor.http.content.OutgoingContent

/**
 * MASTER6-AUTH-004: mantiene vivo el access token de la sesion admin en TODAS las llamadas del cliente compartido.
 *
 * Solo actua sobre peticiones que llevan exactamente `Bearer <access token de la sesion actual>` (las que firma
 * [adminAuthHeaders] con sesion); el token estatico de desarrollo y los endpoints `/auth/v1/` de Supabase pasan sin
 * tocar.
 *
 * - Antes de enviar: si el token caduca ya ([isExpiring]), refresca (single-flight en el repositorio) y firma con el
 *   nuevo.
 * - Si la respuesta es 401: refresca una vez y reintenta UNA vez. Reintentar es seguro aqui aunque el cliente no
 *   reintente nada mas (ver ApiClientFactory, F-04): un 401 es un rechazo de autenticacion previo a cualquier efecto
 *   en el servidor. No se reintenta un cuerpo de un solo uso (`ReadChannelContent`).
 * - Si el refresco falla de forma definitiva, el repositorio ya limpio la sesion (la UI pasa a "sin sesion") y se
 *   devuelve el 401 original.
 */
fun HttpClient.installAdminSessionRefresh(
    sessionLocalDataSource: SessionLocalDataSource,
    isExpiring: (Session) -> Boolean,
    refresher: () -> StaleSessionRefresher,
) {
    plugin(HttpSend).intercept { request ->
        val session = sessionLocalDataSource.currentSession()
        if (session == null || !request.isSignedWith(session.accessToken) || request.isSupabaseAuthCall()) {
            return@intercept execute(request)
        }
        var usedToken = session.accessToken
        if (isExpiring(session)) {
            val renewed = refresher().refreshIfStale(usedToken, serverRejected = false)
            if (renewed is AppResult.Success && renewed.value.accessToken != usedToken) {
                usedToken = renewed.value.accessToken
                request.signWith(usedToken)
            }
        }
        val call = execute(request)
        if (call.response.status != HttpStatusCode.Unauthorized || !request.hasReplayableBody()) {
            return@intercept call
        }
        val refreshed = refresher().refreshIfStale(usedToken, serverRejected = true)
        if (refreshed !is AppResult.Success || refreshed.value.accessToken == usedToken) {
            return@intercept call
        }
        request.signWith(refreshed.value.accessToken)
        execute(request)
    }
}

private fun HttpRequestBuilder.isSignedWith(token: String): Boolean =
    token.isNotBlank() && headers[HttpHeaders.Authorization] == "Bearer $token"

private fun HttpRequestBuilder.signWith(token: String) {
    headers[HttpHeaders.Authorization] = "Bearer $token"
}

private fun HttpRequestBuilder.isSupabaseAuthCall(): Boolean = url.encodedPath.startsWith("/auth/v1/")

private fun HttpRequestBuilder.hasReplayableBody(): Boolean = body !is OutgoingContent.ReadChannelContent
