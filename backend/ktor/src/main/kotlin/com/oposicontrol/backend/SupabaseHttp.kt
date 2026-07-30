package com.oposicontrol.backend

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.UUID

internal data class SupabaseHttpResponse(
    val status: HttpStatusCode,
    val body: String,
)

internal class SupabaseHttp(
    private val configuration: BackendConfiguration,
    val json: Json,
    private val client: HttpClient = defaultSupabaseHttpClient(json),
) {
    suspend fun actorRequest(
        method: HttpMethod,
        path: String,
        actorAccessToken: String,
        body: JsonElement? = null,
        prefer: String? = null,
    ): SupabaseHttpResponse = request(
        method = method,
        path = path,
        authorizationToken = actorAccessToken,
        body = body,
        prefer = prefer,
    )

    suspend fun internalRequest(
        method: HttpMethod,
        path: String,
        body: JsonElement? = null,
        prefer: String? = null,
    ): SupabaseHttpResponse = request(
        method = method,
        path = path,
        authorizationToken = configuration.supabaseSecretKey,
        body = body,
        prefer = prefer,
    )

    suspend fun actorBinaryRequest(
        method: HttpMethod,
        path: String,
        actorAccessToken: String,
        bytes: ByteArray,
        mimeType: String,
    ): SupabaseHttpResponse {
        require(path.startsWith('/')) { "La ruta Supabase debe ser relativa." }
        val response = try {
            client.request("${configuration.supabaseUrl}$path") {
                this.method = method
                header("apikey", configuration.supabaseSecretKey)
                actorAccessToken.serverAuthorizationToken()?.let { token ->
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
                header(HttpHeaders.Accept, ContentType.Application.Json)
                header("x-upsert", "false")
                contentType(ContentType.parse(mimeType))
                setBody(bytes)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw UpstreamProblem(cause = error)
        }
        val responseBody = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw response.toProblem(responseBody)
        }
        return SupabaseHttpResponse(status = response.status, body = responseBody)
    }

    suspend fun publicGet(path: String): String {
        val response = try {
            client.request("${configuration.supabaseUrl}$path") {
                method = HttpMethod.Get
                header("apikey", configuration.supabaseSecretKey)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw UpstreamProblem(cause = error)
        }
        if (!response.status.isSuccess()) {
            throw UpstreamProblem()
        }
        return response.bodyAsText()
    }

    suspend fun isUserSessionActive(
        accessToken: String,
        expectedAuthUserId: UUID,
    ): Boolean {
        val response = try {
            client.request("${configuration.supabaseUrl}/auth/v1/user") {
                method = HttpMethod.Get
                header("apikey", configuration.supabaseSecretKey)
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header(HttpHeaders.Accept, ContentType.Application.Json)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw UpstreamProblem(cause = error)
        }
        if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) {
            return false
        }
        if (!response.status.isSuccess()) {
            throw UpstreamProblem("No se pudo validar la sesiÃ³n administrativa.")
        }
        val authUserId = runCatching {
            UUID.fromString(decode<SupabaseAuthUserResponse>(response.bodyAsText()).id)
        }.getOrElse { error ->
            throw UpstreamProblem("Supabase devolviÃ³ una sesiÃ³n no vÃ¡lida.", error)
        }
        return authUserId == expectedAuthUserId
    }

    inline fun <reified T> decode(body: String): T = json.decodeFromString(body)

    inline fun <reified T> decodeList(body: String): List<T> = json.decodeFromString(body)

    private suspend fun request(
        method: HttpMethod,
        path: String,
        authorizationToken: String,
        body: JsonElement?,
        prefer: String?,
    ): SupabaseHttpResponse {
        require(path.startsWith('/')) { "La ruta Supabase debe ser relativa." }
        val response = try {
            client.request("${configuration.supabaseUrl}$path") {
                this.method = method
                header("apikey", configuration.supabaseSecretKey)
                authorizationToken.serverAuthorizationToken()?.let { token ->
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
                header(HttpHeaders.Accept, ContentType.Application.Json)
                if (prefer != null) header("Prefer", prefer)
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw UpstreamProblem(cause = error)
        }
        val responseBody = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw response.toProblem(responseBody)
        }
        return SupabaseHttpResponse(status = response.status, body = responseBody)
    }

    private fun io.ktor.client.statement.HttpResponse.toProblem(responseBody: String): ApiProblem {
        val errorCode = runCatching {
            json.decodeFromString<JsonObject>(responseBody)["code"]?.toString()?.trim('"')
        }.getOrNull()
        return when {
            errorCode == "P0002" ->
                NotFoundProblem()
            status == HttpStatusCode.Conflict || errorCode in CONFLICT_CODES ->
                ConflictProblem()
            status == HttpStatusCode.NotFound ->
                NotFoundProblem()
            status == HttpStatusCode.BadRequest ->
                BadRequestProblem("Supabase rechazó la solicitud de datos.")
            status == HttpStatusCode.Unauthorized ->
                UpstreamProblem("Supabase rechazó las credenciales del servidor.")
            status == HttpStatusCode.Forbidden ->
                ForbiddenProblem()
            else ->
                UpstreamProblem()
        }
    }

    companion object {
        private val CONFLICT_CODES = setOf("23505", "23P01")
    }

    private fun String.serverAuthorizationToken(): String? =
        takeUnless { token ->
            token == configuration.supabaseSecretKey &&
                token.count { character -> character == '.' } != 2
        }
}

private fun defaultSupabaseHttpClient(json: Json): HttpClient = HttpClient(Java) {
    expectSuccess = false
    install(ContentNegotiation) {
        json(json)
    }
    install(HttpRequestRetry) {
        retryOnServerErrors(maxRetries = 2)
        exponentialDelay()
    }
    install(HttpTimeout) {
        connectTimeoutMillis = 5_000
        requestTimeoutMillis = 15_000
        socketTimeoutMillis = 15_000
    }
}

private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299

@kotlinx.serialization.Serializable
private data class SupabaseAuthUserResponse(val id: String)
