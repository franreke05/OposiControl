package com.oposicontrol.backend

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

// =====================================================================
// ADMIN · USUARIOS (SLICE 1, D1-22..D1-26, `openapi/profiles/profiles.yaml`)
//
// `perfiles_usuario` + `roles_usuario` (+ GoTrue admin para email y proveedores)
// -> AdminUserSummary / AdminUser. `accountStatus` sale SIEMPRE de
// `AccountStatus.fromWire`: un valor no reconocido NUNCA se presenta como `activa`
// (se descarta del resumen). No hay endpoints nuevos ni campos que el contrato no
// congele (la especialidad NO esta en AdminUser).
// =====================================================================

/** `AdminUserSummary` (D1-22). `email` es `null` en la lista: `service_role` no lee `auth.users` y N+1 a GoTrue esta rechazado. */
@Serializable
data class AdminUserSummaryDto(
    val id: String,
    val username: String? = null,
    val fullName: String? = null,
    val email: String? = null,
    val roleIds: List<String>,
    val accountStatus: String,
    val createdAt: String,
    val ticketsBalance: Int? = null,
)

@Serializable
data class AdminUserBlockDto(
    val motivo: String? = null,
    val en: String? = null,
    val por: String? = null,
)

/**
 * `AdminUser` (D1-23) + el duplicado LEGACY `data` (mismo cuerpo que servia `GET /usuarios/{id}`: `email` = username,
 * `isActive`...) hasta que OC migre. `bloqueo` es siempre `null` en SLICE 1 (no hay columnas de bloqueo; D1-27/28 sin calendario).
 */
@Serializable
data class AdminCanonicalUserDto(
    val id: String,
    val username: String? = null,
    val fullName: String? = null,
    val email: String? = null,
    val roleIds: List<String>,
    val accountStatus: String,
    val bloqueo: AdminUserBlockDto? = null,
    val onboardingCompleted: Boolean,
    val createdAt: String,
    val authProviders: List<String>,
    val data: AdminV1UserDto? = null,
)

/** El id de la ruta `{field}` no es un UUID: 400 VALIDATION con el issue NON_UUID (sigue siendo una IllegalArgumentException para los arneses que solo la mapean a 400). */
class NonUuidPathException(val field: String) : IllegalArgumentException("$field debe ser un UUID valido.")

/** Lo que GoTrue admin sabe de un usuario. `null` si no respondio: el `AdminUser` degrada a email `null` y sin proveedores. */
data class AdminAuthInfo(val email: String?, val providers: List<String>)

/** Filtros de `GET /api/v1/admin/usuarios` (D1-22), ya validados. */
data class AdminUserListFilter(
    val q: String? = null,
    val roleCode: String? = null,
    val accountStatus: String? = null,
    val sort: String? = null,
) {
    companion object {
        val ROLE_CODES = setOf("superadmin", "admin", "moderador", "usuario")
        val ACCOUNT_STATUSES = setOf("activa", "bloqueada", "eliminacion_pendiente")
        val SORTS = setOf("creadoEn_desc", "username_asc")
        private val Q_ALLOWED = Regex("^[\\p{L}\\p{N} ._@+-]{1,100}$")

        /** 400 VALIDATION con el campo culpable; ningun valor del cliente se repite en el mensaje. */
        fun parse(q: String?, roleCode: String?, accountStatus: String?, sort: String?): AdminUserListFilter {
            val issues = mutableListOf<ErrorIssue>()
            val query = q?.trim()?.takeIf { it.isNotEmpty() }
            if (query != null && !Q_ALLOWED.matches(query)) issues += ErrorIssue("q", CanonicalIssue.INVALID_FORMAT)
            if (roleCode != null && roleCode !in ROLE_CODES) issues += ErrorIssue("roleCode", CanonicalIssue.INVALID_FORMAT)
            if (accountStatus != null && accountStatus !in ACCOUNT_STATUSES) issues += ErrorIssue("accountStatus", CanonicalIssue.INVALID_FORMAT)
            if (sort != null && sort !in SORTS) issues += ErrorIssue("sort", CanonicalIssue.INVALID_FORMAT)
            if (issues.isNotEmpty()) throw validationError(*issues.toTypedArray())
            return AdminUserListFilter(query, roleCode, accountStatus, sort)
        }
    }

    /** Filtros PostgREST de `perfiles_usuario` (sin `select`, `order`, `limit`, `offset`). */
    fun postgrestFilters(userIds: List<String>? = null): String = buildString {
        accountStatus?.let { append("&estado_cuenta=eq.").append(it) }
        q?.let {
            // `_` es comodin de un caracter en ilike: se escapa con `\` para que case solo el guion bajo literal
            // (`%` y `\` no pasan Q_ALLOWED, asi que no hay doble escape ni comodines del cliente).
            val pattern = URLEncoder.encode(it.replace("_", "\\_"), StandardCharsets.UTF_8).replace("+", "%20")
            append("&or=(username.ilike.").append(pattern).append("*,nombre_mostrar.ilike.").append(pattern).append("*)")
        }
        userIds?.let { append("&usuario_id=in.(").append(it.joinToString(",")).append(")") }
    }

    fun order(): String = if (sort == "username_asc") "username.asc.nullslast,usuario_id.asc" else "creado_en.desc,usuario_id.asc"
}

internal object AdminUserProfileMapper {
    /** `null` si el estado no es uno de los tres congelados: nunca se presenta como `activa`. */
    fun accountStatusWire(estadoCuenta: String?): String? =
        AccountStatus.fromWire(estadoCuenta).takeIf { it != AccountStatus.UNRECOGNIZED }?.wire

    fun summary(
        usuarioId: String,
        username: String?,
        nombreMostrar: String?,
        estadoCuenta: String?,
        creadoEn: String?,
        roleIds: List<String>,
        ticketsBalance: Int?,
    ): AdminUserSummaryDto? {
        val status = accountStatusWire(estadoCuenta) ?: return null
        return AdminUserSummaryDto(
            id = usuarioId,
            username = username,
            fullName = nombreMostrar,
            email = null,
            roleIds = roleIds,
            accountStatus = status,
            createdAt = normalizeInstant(creadoEn.orEmpty()),
            ticketsBalance = ticketsBalance,
        )
    }

    /** `AdminUser`. Estado no reconocido -> 502 UPSTREAM_CONTRACT_ERROR (no se inventa `activa`). */
    fun user(
        usuarioId: String,
        username: String?,
        nombreMostrar: String?,
        estadoCuenta: String?,
        onboardingCompleted: Boolean,
        creadoEn: String?,
        roleIds: List<String>,
        auth: AdminAuthInfo?,
        legacy: AdminV1UserDto? = null,
    ): AdminCanonicalUserDto = AdminCanonicalUserDto(
        id = usuarioId,
        username = username,
        fullName = nombreMostrar,
        email = auth?.email,
        roleIds = roleIds,
        accountStatus = accountStatusWire(estadoCuenta) ?: throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception(),
        bloqueo = null,
        onboardingCompleted = onboardingCompleted,
        createdAt = normalizeInstant(creadoEn.orEmpty()),
        authProviders = auth?.providers.orEmpty(),
        data = legacy,
    )

    @Serializable
    private data class GoTrueAdminUser(
        val email: String? = null,
        @SerialName("app_metadata") val appMetadata: AppMetadata = AppMetadata(),
        val identities: List<Identity> = emptyList(),
    ) {
        @Serializable data class AppMetadata(val providers: List<String> = emptyList())
        @Serializable data class Identity(val provider: String? = null)
    }

    /** `GET /auth/v1/admin/users/{id}` -> email y `authProviders` (`email|apple|google`, sin repetidos). */
    fun authInfo(body: String, json: Json = Json { ignoreUnknownKeys = true }): AdminAuthInfo {
        val user = json.decodeFromString(GoTrueAdminUser.serializer(), body)
        val providers = (user.appMetadata.providers + user.identities.mapNotNull { it.provider })
            .map { it.lowercase() }
            .filter { it in AuthProviderUser.CANONICAL_PROVIDERS }
            .distinct()
        return AdminAuthInfo(user.email?.takeIf { it.isNotBlank() }, providers)
    }
}
