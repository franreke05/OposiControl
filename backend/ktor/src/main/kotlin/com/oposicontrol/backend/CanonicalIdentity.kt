package com.oposicontrol.backend

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

// =====================================================================
// IDENTIDAD CANONICA (SLICE 0, `specs.identity`, DEC-001 ADOPTED)
//
// auth.users.id (uuid) = perfiles_usuario.usuario_id = todo FK de dominio
// usuario_id. Ktor la toma SOLO del `sub` del JWT; la fila de perfil la crea el
// trigger handle_new_auth_user. `public.usuario` (int, legacy) no interviene.
//
// Este fichero NO cambia el comportamiento actual de auth ni de perfil: las
// rutas existentes siguen resolviendo como hasta ahora (`usuarioId` de
// AppV1Routes, `/v1/auth/*`). Es la pieza que usaran las rutas canonicas desde
// SLICE 1.
// =====================================================================

/** El id canonico del usuario: el uuid de auth.users, en minusculas. */
@JvmInline
value class CanonicalUserId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        /** El `sub` de un JWT ya verificado. Un `sub` que no es uuid es 401: no es una identidad canonica. */
        fun fromJwtSubject(subject: String?): CanonicalUserId {
            val trimmed = subject?.trim().orEmpty()
            if (!UUID.matches(trimmed)) throw CanonicalErrorCode.UNAUTHENTICATED.exception()
            return CanonicalUserId(trimmed.lowercase())
        }
    }
}

/**
 * `perfiles_usuario.estado_cuenta`. DENY BY DEFAULT (Gate B): solo un `activa`
 * explicito y exacto deja operar. `bloqueada` y `eliminacion_pendiente` son los
 * estados congelados (CHECK de la migracion de SLICE 0) y tienen su 403
 * especifico. Cualquier otra cosa -- `null`, ausente, vacio, mayusculas,
 * `disabled`/`deleted`/`anonymized` u otro valor futuro -- es [UNRECOGNIZED]:
 * denegado con 403 FORBIDDEN, nunca "activa" por defecto (DEC-008: una cuenta
 * borrada/anonimizada no recupera acceso).
 */
enum class AccountStatus(val wire: String) {
    ACTIVA("activa"),
    BLOQUEADA("bloqueada"),
    ELIMINACION_PENDIENTE("eliminacion_pendiente"),

    /** Sentinela interno: no es un valor de la base ni se serializa. */
    UNRECOGNIZED("\u0000unrecognized"),
    ;

    companion object {
        /** Coincidencia EXACTA (sin trim ni case-folding). Todo lo demas es [UNRECOGNIZED]. */
        fun fromWire(value: String?): AccountStatus =
            entries.firstOrNull { it != UNRECOGNIZED && it.wire == value } ?: UNRECOGNIZED
    }
}

/** Lo minimo que una ruta canonica necesita saber del usuario autenticado. */
data class CanonicalUserIdentity(
    val userId: CanonicalUserId,
    val accountStatus: AccountStatus,
    val onboardingCompleted: Boolean,
)

/** Lectura del perfil canonico por id. `null` = no hay fila de perfil. */
fun interface CanonicalProfileDirectory {
    suspend fun find(userId: CanonicalUserId): CanonicalUserIdentity?
}

/**
 * `perfiles_usuario` via PostgREST con la clave de servicio: una fila, tres
 * columnas, filtro por el uuid ya validado.
 *
 * Requiere la migracion de expansion de SLICE 0 (`estado_cuenta`). No lo usa
 * ninguna ruta actual.
 */
class PostgrestCanonicalProfileDirectory(
    private val transport: SupabaseRequestTransport,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : CanonicalProfileDirectory {

    @Serializable
    internal data class ProfileRow(
        @SerialName("usuario_id") val usuarioId: String,
        @SerialName("estado_cuenta") val estadoCuenta: String? = null,
        @SerialName("onboarding_completado") val onboardingCompletado: Boolean? = null,
    )

    override suspend fun find(userId: CanonicalUserId): CanonicalUserIdentity? {
        val body = withUpstreamIo {
            transport.get(
                "/rest/v1/perfiles_usuario?select=usuario_id,estado_cuenta,onboarding_completado" +
                    "&usuario_id=eq.${userId.value}&limit=1",
            )
        }
        val row = json.decodeFromString(ListSerializer(ProfileRow.serializer()), body).firstOrNull() ?: return null
        if (!row.usuarioId.equals(userId.value, ignoreCase = true)) {
            throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        }
        return CanonicalUserIdentity(
            userId = userId,
            accountStatus = AccountStatus.fromWire(row.estadoCuenta),
            onboardingCompleted = row.onboardingCompletado ?: false,
        )
    }

    companion object {
        fun fromEnvironment(environment: (String) -> String? = System::getenv): PostgrestCanonicalProfileDirectory? =
            SupabaseRequestTransport.fromEnvironment(environment)?.let(::PostgrestCanonicalProfileDirectory)
    }
}

/**
 * JWT -> [CanonicalUserId] -> [CanonicalUserIdentity].
 *
 * [verifySubject] es el verificador existente (`SupabaseJwtVerifier::subFrom`);
 * bloquea al descargar el JWKS, asi que se llama en el pool IO. Sin el atajo de
 * desarrollo `X-Usuario-Id`: la identidad canonica es solo el `sub`.
 */
class CanonicalIdentityResolver(
    private val verifySubject: (String) -> String,
    private val directory: CanonicalProfileDirectory,
) {
    /** 401 UNAUTHENTICATED si falta el Bearer o no verifica. */
    suspend fun userId(call: ApplicationCall): CanonicalUserId {
        val header = call.request.headers[HttpHeaders.Authorization].orEmpty()
        if (!header.startsWith("Bearer ", ignoreCase = true)) throw CanonicalErrorCode.UNAUTHENTICATED.exception()
        val token = header.substring("Bearer ".length).trim()
        if (token.count { it == '.' } != 2) throw CanonicalErrorCode.UNAUTHENTICATED.exception()
        val subject = try {
            withUpstreamIo { verifySubject(token) }
        } catch (domain: BackendDomainException) {
            throw domain
        } catch (_: java.io.IOException) {
            // RB-1-06: JWKS caido / timeout no es un token invalido: 503 (reintentable), no 401 (que cerraria sesion).
            throw CanonicalErrorCode.UPSTREAM_UNAVAILABLE.exception()
        } catch (_: Exception) {
            throw CanonicalErrorCode.UNAUTHENTICATED.exception()
        }
        return CanonicalUserId.fromJwtSubject(subject)
    }

    /**
     * La identidad completa, SIN comprobar el estado (para operar usa [activeIdentity]).
     *
     * Sin fila de perfil -> 403 PROFILE_MISSING. El contrato congelado calla este
     * caso (el trigger `handle_new_auth_user` garantiza la fila), asi que se
     * deniega con un codigo propio: ni se autocrea la fila ni se opera como activo.
     */
    suspend fun identity(call: ApplicationCall): CanonicalUserIdentity {
        val userId = userId(call)
        return directory.find(userId) ?: throw CanonicalErrorCode.PROFILE_MISSING.exception()
    }

    /**
     * Identidad de una cuenta ACTIVA explicita. Bloqueada -> 403 ACCOUNT_BLOCKED,
     * pendiente de borrado -> 403 ACCOUNT_DELETION_PENDING, cualquier otro estado
     * o estado ausente -> 403 FORBIDDEN.
     */
    suspend fun activeIdentity(call: ApplicationCall): CanonicalUserIdentity = identity(call).requireActive()
}

fun CanonicalUserIdentity.requireActive(): CanonicalUserIdentity = when (accountStatus) {
    AccountStatus.ACTIVA -> this
    AccountStatus.BLOQUEADA -> throw CanonicalErrorCode.ACCOUNT_BLOCKED.exception()
    AccountStatus.ELIMINACION_PENDIENTE -> throw CanonicalErrorCode.ACCOUNT_DELETION_PENDING.exception()
    AccountStatus.UNRECOGNIZED -> throw CanonicalErrorCode.FORBIDDEN.exception()
}
