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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Instant
import java.time.OffsetDateTime
import org.slf4j.LoggerFactory

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

/** MASTER7-IDEM-001: `GET /auth/v1/admin/users` (lista paginada de GoTrue); solo lo necesario para reconciliar. */
@Serializable
private data class GoTrueAdminUserListWire(val users: List<GoTrueAdminUserWire> = emptyList())

@Serializable
private data class GoTrueAdminUserWire(
    val id: String,
    val email: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

/**
 * MASTER7-IDEM-001: margen de reloj entre este backend y GoTrue/Postgres al comparar el `created_at` de la identidad con
 * el instante previo al POST. Pequeño a proposito: una identidad mas antigua puede ser un usuario real y nunca se borra.
 */
private const val LOST_AUTH_CREATE_CLOCK_SKEW_MS = 5_000L

private val UserAdminLogger = LoggerFactory.getLogger("OC-KTOR-USER-ADMIN")

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
        // MASTER5-API-008: las dos escrituras (identidad de Auth + fila `usuario`) y su compensacion corren como UNA
        // unidad NO cancelable. Antes, si el cliente se desconectaba entre ambas, la compensacion (DELETE de la
        // identidad) se lanzaba en el contexto ya cancelado, fallaba al instante y quedaba una identidad de Auth
        // huerfana; y cancelar DURANTE el POST de Auth podia dejarla creada sin que nadie supiera su id. Cada peticion
        // esta acotada por el HttpTimeout del cliente (15 s), asi que la unidad termina siempre.
        return withContext(NonCancellable) { createUserAtomically(request, authBody) }
    }

    private suspend fun createUserAtomically(request: OposiBotsUserAdminCreateRequest, authBody: String): OposiBotsUserAdminDto {
        // MASTER7-IDEM-001: instante previo al POST de Auth; solo una identidad creada DESPUES (menos el margen de reloj)
        // puede ser la que este POST creo y cuya respuesta se perdio.
        val startedAt = Instant.now()
        val authUserId = json.decodeFromString<SupabaseAuthAdminUserWire>(
            try {
                request(HttpMethod.Post, "/auth/v1/admin/users", authBody)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (rejected: SupabaseHttpStatusException) {
                // MASTER6-API-004: un rechazo de GoTrue causado por el cliente no es un fallo del upstream (502).
                goTrueCreateRejection(rejected)?.let { throw it }
                // MASTER7-IDEM-001: un 5xx puede llegar DESPUES de que GoTrue confirmara la identidad (resultado ambiguo).
                if (rejected.status >= 500) reconcileLostAuthCreate(request.email.trim(), startedAt)
                throw rejected
            } catch (lost: IllegalStateException) {
                // MASTER7-IDEM-001: transporte/timeout: la respuesta se perdio y la identidad pudo quedar creada.
                reconcileLostAuthCreate(request.email.trim(), startedAt)
                throw lost
            },
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

    /**
     * MASTER7-IDEM-001: el POST de Auth fallo con resultado AMBIGUO (transporte, timeout o 5xx): GoTrue pudo crear la
     * identidad sin que conozcamos su id. Sin reconciliar quedaba una identidad fantasma (el reintento daba 409 y el
     * borrado 404, porque no hay fila `usuario`). Se busca por email y solo se borra si TODO cuadra (deny-by-default):
     * - exactamente UNA identidad con el email EXACTO (sin mayusculas/espacios; nunca una coincidencia parcial);
     * - creada en o despues de [startedAt] menos [LOST_AUTH_CREATE_CLOCK_SKEW_MS] (una anterior puede ser un usuario real);
     * - con id uuid valido y SIN fila `usuario` enlazada.
     * Cualquier otra cosa, o un fallo de la propia busqueda, no borra nada (solo WARN). Nunca se adopta la identidad: el
     * llamador relanza SIEMPRE el error original (502) y, borrada la fantasma, el reintento funciona. Los logs llevan un
     * prefijo SHA-256 del email en minusculas, nunca el email ni la contrasena. Si el borrado de reconciliacion falla, se
     * lanza el mismo estilo de error que la compensacion existente, con el auth_user_id para limpieza manual.
     */
    private suspend fun reconcileLostAuthCreate(email: String, startedAt: Instant) {
        val emailRef = sha256Hex(email.lowercase().toByteArray(StandardCharsets.UTF_8)).take(12)
        val notBefore = startedAt.minusMillis(LOST_AUTH_CREATE_CLOCK_SKEW_MS)
        val ghostId = try {
            val path = "/auth/v1/admin/users?filter=${URLEncoder.encode(email, StandardCharsets.UTF_8)}&per_page=50"
            val matches = json.decodeFromString<GoTrueAdminUserListWire>(request(HttpMethod.Get, path, null)).users
                .filter { it.email?.trim()?.equals(email, ignoreCase = true) == true }
            val candidate = matches.singleOrNull()
            val createdAt = candidate?.createdAt?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
            if (candidate == null || !candidate.id.isUuid() || createdAt == null || createdAt.isBefore(notBefore)) {
                UserAdminLogger.warn(
                    "lost auth create: no reconcilable identity emailRef={} exactMatches={} (nothing deleted)",
                    emailRef,
                    matches.size,
                )
                return
            }
            val linked = request(HttpMethod.Get, "/rest/v1/usuario?select=id&auth_user_id=eq.${candidate.id}&limit=1", null)
                .decodeList<JsonObject>().isNotEmpty()
            if (linked) {
                UserAdminLogger.warn("lost auth create: identity already linked emailRef={} authUserId={} (nothing deleted)", emailRef, candidate.id)
                return
            }
            candidate.id
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (lookupFailure: Exception) {
            UserAdminLogger.warn(
                "lost auth create: lookup failed emailRef={} error={} (nothing deleted)",
                emailRef,
                lookupFailure::class.simpleName,
            )
            return
        }
        try {
            request(HttpMethod.Delete, "/auth/v1/admin/users/$ghostId", null)
            UserAdminLogger.warn("lost auth create: ghost identity deleted emailRef={} authUserId={}", emailRef, ghostId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (gone: SupabaseHttpStatusException) {
            if (gone.status == 404) return
            throw ghostDeletionFailed(ghostId, gone)
        } catch (deletionFailure: Exception) {
            throw ghostDeletionFailed(ghostId, deletionFailure)
        }
    }

    private fun ghostDeletionFailed(authUserId: String, cause: Exception) = IllegalStateException(
        "El alta de usuario fallo con resultado ambiguo y la identidad de Supabase Auth $authUserId creada por ella " +
            "no pudo eliminarse (${cause::class.simpleName}). Reintentar manualmente borrando ese auth_user_id.",
        cause,
    )

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

    /**
     * MASTER5-API-008: orden identidad de Auth -> fila `usuario`, y ambos pasos como una unidad NO cancelable.
     *
     * Antes se borraba primero la fila y despues la identidad: si el segundo paso fallaba (o el cliente se desconectaba
     * entre ambos), la identidad seguia pudiendo iniciar sesion y el reintento ya no encontraba la fila (404) ni
     * llegaba nunca al borrado de Auth. Ahora:
     * - se lee la fila (sin fila -> `false`, 404);
     * - se borra la identidad de Auth (un 404 de Auth = ya no existe = exito idempotente);
     * - se borra la fila. Si este ultimo paso falla, la fila SIGUE ahi y el reintento repite todo (Auth responde 404,
     *   que es exito) hasta completarlo: ningun estado intermedio deja de ser reintentable por esta misma ruta.
     * Si la fila ya no esta al borrarla (p. ej. un FK en cascada desde `auth.users`), el objetivo esta cumplido: `true`.
     */
    override suspend fun deleteUser(id: Int): Boolean = withContext(NonCancellable) {
        val existing = request(HttpMethod.Get, "/rest/v1/usuario?select=$UsuarioSelectColumns&id=eq.$id&limit=1", null)
            .decodeList<OposiBotsUsuarioRow>().firstOrNull() ?: return@withContext false
        existing.authUserId?.let { authUserId ->
            // MASTER6-DB-001: `aceptaciones_legales` y `solicitudes_eliminacion_cuenta` referencian `auth.users` con
            // ON DELETE NO ACTION a proposito (retencion legal; migraciones 20260930100100/100300): GoTrue no puede borrar
            // esa identidad (la transaccion se aborta: nada se borra) y respondia un 5xx opaco (-> 502). Ante un 5xx de
            // GoTrue se comprueba si hay filas retenidas: si las hay, 409 CONFLICT (no es un fallo del upstream ni se
            // arregla reintentando); si no, el 5xx sigue siendo un fallo del upstream. La fila `usuario` no se toca.
            try {
                request(HttpMethod.Delete, "/auth/v1/admin/users/$authUserId", null)
            } catch (error: SupabaseHttpStatusException) {
                // 404 de Auth: la identidad ya no existe (borrado previo o reintento) -> exito idempotente.
                if (error.status == 404) return@let
                if (error.status >= 500 && hasLegalRetentionOrFalse(authUserId)) {
                    throw legalRetentionConflict(error)
                }
                throw error
            }
        }
        request(HttpMethod.Delete, "/rest/v1/usuario?id=eq.$id", null, prefer = "return=representation")
        true
    }

    /**
     * MASTER6-DB-001: ¿hay filas de retencion legal (FK NO ACTION a `auth.users`) para esta identidad? Una tabla que no
     * existe (404: base sin las migraciones de Slice 1) no tiene FK que bloquee: no cuenta. Si la comprobacion misma
     * falla, `false`: el 5xx original de GoTrue se propaga tal cual (nunca se inventa un 409).
     */
    private suspend fun hasLegalRetentionOrFalse(authUserId: String): Boolean {
        if (!authUserId.isUuid()) return false
        val id = authUserId.lowercase()
        return try {
            LEGAL_RETENTION_TABLES.any { table ->
                try {
                    request(HttpMethod.Get, "/rest/v1/$table?select=usuario_id&usuario_id=eq.$id&limit=1", null)
                        .decodeList<JsonObject>().isNotEmpty()
                } catch (missing: SupabaseHttpStatusException) {
                    if (missing.status == 404) false else throw missing
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private fun legalRetentionConflict(cause: Throwable): BackendDomainException =
        CanonicalErrorCode.CONFLICT.exception(
            message = "La cuenta tiene aceptaciones legales o una solicitud de eliminacion que deben conservarse: " +
                "no se puede borrar por esta ruta; usa el flujo de eliminacion de cuenta.",
            cause = cause,
        )

    /**
     * MASTER6-API-004: rechazos de `POST /auth/v1/admin/users` causados por la PETICION (email ya registrado, contrasena
     * debil, email invalido): 409 CONFLICT para el duplicado, 400 VALIDATION para el resto. 5xx/transporte siguen
     * siendo fallo del upstream. Solo el `error_code` de GoTrue decide (nunca su texto libre, que no se reenvia).
     */
    private fun goTrueCreateRejection(rejected: SupabaseHttpStatusException): BackendDomainException? {
        if (rejected.status !in setOf(400, 409, 422)) return null
        val errorCode = rejected.goTrueErrorCode
        return if (rejected.status == 409 || errorCode in GOTRUE_DUPLICATE_ERROR_CODES) {
            CanonicalErrorCode.CONFLICT.exception(message = "Ya existe un usuario con ese email.", cause = rejected)
        } else {
            CanonicalErrorCode.VALIDATION.exception(
                message = "Supabase Auth rechazo los datos del usuario (${errorCode ?: "sin codigo"}).",
                cause = rejected,
            )
        }
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
            // Special-case 409 Conflict responses as IllegalArgumentException (maps to 400). MASTER6-API-004: salvo en
            // GoTrue (rutas /auth/), cuyo rechazo clasifica el llamador por error_code ([goTrueCreateRejection]).
            if (statusCode == 409 && !path.startsWith("/auth/")) {
                throw IllegalArgumentException("Ya existe un usuario con esos datos (posible duplicado de email o username).")
            }
            val goTrueErrorCode = if (path.startsWith("/auth/")) {
                runCatching { json.parseToJsonElement(responseBody).jsonObject["error_code"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            } else {
                null
            }
            throw SupabaseHttpStatusException(
                statusCode,
                "Supabase $method ${path.substringBefore('?')} -> $statusCode: ${responseBody.take(300)}",
                goTrueErrorCode = goTrueErrorCode,
            )
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

/**
 * Respuesta no 2xx de Supabase con su estado. Sigue siendo [IllegalStateException] (StatusPages: 502, como antes);
 * el estado explicito sustituye a buscar "404" dentro del mensaje, que tambien casaba con un uuid que contuviera "404".
 */
internal class SupabaseHttpStatusException(
    val status: Int,
    message: String,
    /** MASTER6-API-004: error_code de una respuesta de GoTrue (p. ej. email_exists, weak_password), o null. */
    val goTrueErrorCode: String? = null,
) : IllegalStateException(message)

/** MASTER6-API-004: error_code de GoTrue que significan «ya existe». */
internal val GOTRUE_DUPLICATE_ERROR_CODES: Set<String> = setOf("email_exists", "user_already_exists", "phone_exists")

/** MASTER6-DB-001: tablas con FK ON DELETE NO ACTION a auth.users (retencion legal). */
internal val LEGAL_RETENTION_TABLES: List<String> = listOf("aceptaciones_legales", "solicitudes_eliminacion_cuenta")

private fun defaultUserAdminHttpClient(): HttpClient = HttpClient(Java) {
    expectSuccess = false
    install(HttpTimeout) {
        connectTimeoutMillis = 5_000
        requestTimeoutMillis = 15_000
        socketTimeoutMillis = 15_000
    }
}
