package com.oposicontrol.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Instant

interface OposiBotsUserAdminGateway {
    suspend fun listUsers(limit: Int, offset: Int, search: String?): List<OposiBotsUserAdminDto>
    suspend fun getUser(id: Int): OposiBotsUserAdminDto?
    suspend fun createUser(request: OposiBotsUserAdminCreateRequest): OposiBotsUserAdminDto
    suspend fun updateUser(id: Int, request: OposiBotsUserAdminUpdateRequest): OposiBotsUserAdminDto?
    suspend fun deleteUser(id: Int): Boolean
}

data class OposiBotsUserAdminDto(
    val id: Int,
    val authUserId: String?,
    val email: String?,
    val nombre: String?,
    val apellidos: String?,
    val username: String?,
    val especialidadRegistro: String?,
    val perfilCompleto: Boolean,
    val perfilCompletadoAt: String?,
    val createdAt: String,
    val updatedAt: String,
)

data class OposiBotsUserAdminCreateRequest(
    val email: String,
    val password: String?,
    val nombre: String,
    val apellidos: String,
    val username: String?,
    val especialidadRegistro: String,
)

data class OposiBotsUserAdminUpdateRequest(
    val nombre: String? = null,
    val apellidos: String? = null,
    val username: String? = null,
    val especialidadRegistro: String? = null,
    val perfilCompleto: Boolean? = null,
)

private const val UsuarioSelectColumns =
    "id,auth_user_id,email,nombre,apellidos,username,especialidad_registro,perfil_completo,perfil_completado_at,created_at,updated_at"

@Serializable
private data class OposiBotsUsuarioRow(
    val id: Int,
    @SerialName("auth_user_id") val authUserId: String? = null,
    val email: String? = null,
    val nombre: String? = null,
    val apellidos: String? = null,
    val username: String? = null,
    @SerialName("especialidad_registro") val especialidadRegistro: String? = null,
    @SerialName("perfil_completo") val perfilCompleto: Boolean = false,
    @SerialName("perfil_completado_at") val perfilCompletadoAt: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
) {
    fun toDto() = OposiBotsUserAdminDto(
        id = id,
        authUserId = authUserId,
        email = email,
        nombre = nombre,
        apellidos = apellidos,
        username = username,
        especialidadRegistro = especialidadRegistro,
        perfilCompleto = perfilCompleto,
        perfilCompletadoAt = perfilCompletadoAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

@Serializable
private data class SupabaseAuthAdminUserWire(val id: String)

private fun requireValidUsername(username: String) {
    require(username.length in 3..30 && username.matches(Regex("^[A-Za-z0-9._-]+$"))) {
        "Username invalido: debe tener 3-30 caracteres alfanumericos/._-."
    }
}

private fun String.encodeForPostgrestSearch(): String {
    // Escape PostgREST filter metacharacters to prevent query injection/breakage
    val escaped = this.replace(Regex("[,()%*]"), "")
    // URL-encode to handle spaces, accents, and other special characters
    return URLEncoder.encode(escaped, StandardCharsets.UTF_8)
}

private fun generateRandomPassword(): String {
    val allowed = ('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('!', '#', '%', '&', '*', '-', '_')
    val random = SecureRandom()
    return (1..24).map { allowed[random.nextInt(allowed.size)] }.joinToString("")
}

class SupabaseOposiBotsUserAdminGateway(
    private val supabaseUrl: String,
    private val serviceRoleKey: String,
    private val json: Json,
    private val client: HttpClient = defaultUserAdminHttpClient(),
) : OposiBotsUserAdminGateway {

    override suspend fun listUsers(limit: Int, offset: Int, search: String?): List<OposiBotsUserAdminDto> {
        val clampedLimit = limit.coerceIn(1, 200)
        val clampedOffset = offset.coerceAtLeast(0)
        val searchClause = search?.trim()?.takeIf(String::isNotBlank)?.let {
            val encoded = it.encodeForPostgrestSearch()
            "&or=(email.ilike.*$encoded*,username.ilike.*$encoded*,nombre.ilike.*$encoded*,apellidos.ilike.*$encoded*)"
        }.orEmpty()
        val path = "/rest/v1/usuario?select=$UsuarioSelectColumns&order=created_at.desc&limit=$clampedLimit&offset=$clampedOffset$searchClause"
        return request(HttpMethod.Get, path, null).decodeList<OposiBotsUsuarioRow>().map { it.toDto() }
    }

    override suspend fun getUser(id: Int): OposiBotsUserAdminDto? {
        val path = "/rest/v1/usuario?select=$UsuarioSelectColumns&id=eq.$id&limit=1"
        return request(HttpMethod.Get, path, null).decodeList<OposiBotsUsuarioRow>().firstOrNull()?.toDto()
    }

    override suspend fun createUser(request: OposiBotsUserAdminCreateRequest): OposiBotsUserAdminDto {
        require(request.email.isNotBlank()) { "El usuario necesita email." }
        require(request.nombre.isNotBlank()) { "El usuario necesita nombre." }
        require(request.apellidos.isNotBlank()) { "El usuario necesita apellidos." }
        require(request.especialidadRegistro.isNotBlank()) { "El usuario necesita especialidad." }
        request.username?.let(::requireValidUsername)

        val authPassword = request.password?.takeIf(String::isNotBlank) ?: generateRandomPassword()
        val authBody = json.encodeToString(
            buildJsonObject {
                put("email", request.email.trim())
                put("password", authPassword)
                put("email_confirm", true)
            },
        )
        val authUserId = json.decodeFromString<SupabaseAuthAdminUserWire>(
            request(HttpMethod.Post, "/auth/v1/admin/users", authBody),
        ).id

        val usuarioBody = json.encodeToString(
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("auth_user_id", authUserId)
                        put("email", request.email.trim())
                        put("nombre", request.nombre.trim())
                        put("apellidos", request.apellidos.trim())
                        request.username?.trim()?.let { put("username", it) }
                        put("especialidad_registro", request.especialidadRegistro.trim())
                        put("perfil_completo", true)
                        put("perfil_completado_at", Instant.now().toString())
                    },
                )
            },
        )
        return try {
            request(HttpMethod.Post, "/rest/v1/usuario", usuarioBody, prefer = "return=representation")
                .decodeList<OposiBotsUsuarioRow>().firstOrNull()?.toDto()
                ?: throw IllegalStateException("El perfil de usuario no fue creado (respuesta vacía de Supabase).")
        } catch (error: Exception) {
            // Compensate: delete the orphaned Auth identity
            try {
                request(HttpMethod.Delete, "/auth/v1/admin/users/$authUserId", null)
            } catch (compensationError: Exception) {
                throw IllegalStateException(
                    "El perfil de usuario NO pudo crearse pero la identidad de Supabase Auth $authUserId " +
                        "tampoco pudo eliminarse (${compensationError.message}). Reintentar manualmente " +
                        "borrando ese auth_user_id.",
                    compensationError,
                )
            }
            throw error
        }
    }

    override suspend fun updateUser(id: Int, request: OposiBotsUserAdminUpdateRequest): OposiBotsUserAdminDto? {
        request.username?.let(::requireValidUsername)
        request.nombre?.let { require(it.isNotBlank()) { "El nombre no puede estar vacío." } }
        request.apellidos?.let { require(it.isNotBlank()) { "Los apellidos no pueden estar vacíos." } }
        request.especialidadRegistro?.let { require(it.isNotBlank()) { "La especialidad no puede estar vacía." } }
        var fieldsPresent = 0
        val fieldObject = buildJsonObject {
            request.nombre?.let {
                put("nombre", it.trim())
                fieldsPresent++
            }
            request.apellidos?.let {
                put("apellidos", it.trim())
                fieldsPresent++
            }
            request.username?.let {
                put("username", it.trim())
                fieldsPresent++
            }
            request.especialidadRegistro?.let {
                put("especialidad_registro", it.trim())
                fieldsPresent++
            }
            request.perfilCompleto?.let {
                put("perfil_completo", it)
                fieldsPresent++
            }
            put("updated_at", Instant.now().toString())
        }
        if (fieldsPresent == 0) return getUser(id) // only updated_at present -> nothing to change, just re-read
        val body = json.encodeToString(fieldObject)
        return request(HttpMethod.Patch, "/rest/v1/usuario?id=eq.$id", body, prefer = "return=representation")
            .decodeList<OposiBotsUsuarioRow>().firstOrNull()?.toDto()
    }

    override suspend fun deleteUser(id: Int): Boolean {
        // Order matters: usuario row first, then the Supabase Auth identity.
        // If deleting the auth identity fails partway through, the profile is
        // already gone (an orphaned auth-only account can't do much and is
        // still addressable by an admin via auth_user_id) rather than leaving
        // a dangling usuario row that blocks future re-registration under the
        // same email/username with no account left to retry deleting.
        val deletedRows = request(HttpMethod.Delete, "/rest/v1/usuario?id=eq.$id", null, prefer = "return=representation")
            .decodeList<OposiBotsUsuarioRow>()
        if (deletedRows.isEmpty()) return false
        val existing = deletedRows.first().toDto()
        existing.authUserId?.let { authUserId ->
            try {
                request(HttpMethod.Delete, "/auth/v1/admin/users/$authUserId", null)
            } catch (error: Exception) {
                // 404 from Auth DELETE means the identity was already removed (idempotent success)
                if (error is IllegalStateException && error.message?.contains("404") == true) {
                    return@let // Treat 404 as success
                }
                throw IllegalStateException(
                    "El perfil de usuario $id fue eliminado pero la identidad de Supabase Auth " +
                        "$authUserId NO pudo eliminarse (${error.message}). Reintentar manualmente " +
                        "borrando ese auth_user_id.",
                    error,
                )
            }
        }
        return true
    }

    private suspend fun request(method: HttpMethod, path: String, body: String?, prefer: String? = null): String {
        val response = try {
            client.request("$supabaseUrl$path") {
                this.method = method
                // F-02: la credencial la decide la política (`Bearer` sólo con clave JWT).
                SupabaseRequestAuthPolicy(serviceRoleKey).elevatedHeaders().forEach { (name, value) -> header(name, value) }
                header(HttpHeaders.Accept, ContentType.Application.Json)
                prefer?.let { header("Prefer", it) }
                body?.let {
                    contentType(ContentType.Application.Json)
                    setBody(it)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw IllegalStateException("Supabase network error on $method ${path.substringBefore('?')}: ${error::class.simpleName}: ${error.message}", error)
        }
        if (response.status.value !in 200..299) {
            val statusCode = response.status.value
            val responseBody = response.bodyAsText()
            // Special-case 409 Conflict responses as IllegalArgumentException (maps to 400)
            if (statusCode == 409) {
                throw IllegalArgumentException("Ya existe un usuario con esos datos (posible duplicado de email o username).")
            }
            throw IllegalStateException("Supabase $method ${path.substringBefore('?')} -> $statusCode: ${responseBody.take(300)}")
        }
        return response.bodyAsText()
    }

    private inline fun <reified T> String.decodeList(): List<T> =
        if (isBlank()) emptyList() else json.decodeFromString(this)

    companion object {
        /**
         * F-01: la clave la resuelve [SupabaseRequestAuthPolicy.secretFromEnvironment]
         * (`SUPABASE_SECRET_KEY`, con la legacy como fallback). Leía sólo la legacy y,
         * con la configuración canónica, `Application.module()` no arrancaba.
         */
        fun fromEnvironment(json: Json, environment: (String) -> String? = System::getenv): OposiBotsUserAdminGateway? {
            val url = environment("SUPABASE_URL")?.trimEnd('/').orEmpty()
            val serviceRoleKey = SupabaseRequestAuthPolicy.secretFromEnvironment(environment)
            return if (url.isBlank() || serviceRoleKey.isBlank()) null else SupabaseOposiBotsUserAdminGateway(url, serviceRoleKey, json)
        }
    }
}

private fun defaultUserAdminHttpClient(): HttpClient = HttpClient(Java) {
    expectSuccess = false
    install(HttpTimeout) {
        connectTimeoutMillis = 5_000
        requestTimeoutMillis = 15_000
        socketTimeoutMillis = 15_000
    }
}
