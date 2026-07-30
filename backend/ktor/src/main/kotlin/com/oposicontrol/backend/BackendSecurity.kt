package com.oposicontrol.backend

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.oposicontrol.admin.core.api.AdminApiDevelopment
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.Principal
import io.ktor.server.auth.principal
import io.ktor.server.request.httpMethod
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class AdminPrincipal(
    val authUserId: UUID,
    val sessionId: UUID,
    val userId: Long,
    val displayName: String,
    val email: String,
    val roleIds: List<Long>,
    val permissions: Set<String>,
    internal val accessToken: String,
) : Principal {
    override fun toString(): String = "AdminPrincipal(authUserId=$authUserId,userId=$userId)"
}

internal fun interface AdminTokenAuthenticator {
    suspend fun authenticate(accessToken: String): AdminPrincipal?
}

internal data class AuthorizationSnapshot(
    val userId: Long,
    val displayName: String,
    val email: String,
    val roleIds: List<Long>,
    val permissions: Set<String>,
)

internal interface AuthorizationResolver {
    suspend fun resolve(authUserId: UUID): AuthorizationSnapshot
}

internal fun interface AdminSessionValidator {
    suspend fun isActive(accessToken: String, authUserId: UUID): Boolean
}

internal class SupabaseAdminSessionValidator(
    private val supabaseHttp: SupabaseHttp,
) : AdminSessionValidator {
    override suspend fun isActive(accessToken: String, authUserId: UUID): Boolean =
        supabaseHttp.isUserSessionActive(accessToken, authUserId)
}

internal class ProductionAdminTokenAuthenticator(
    private val jwtVerifier: SupabaseJwtVerifier,
    private val sessionValidator: AdminSessionValidator,
    private val authorizationResolver: AuthorizationResolver,
) : AdminTokenAuthenticator {
    override suspend fun authenticate(accessToken: String): AdminPrincipal? {
        val identity = try {
            jwtVerifier.verify(accessToken)
        } catch (_: JWTVerificationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (!sessionValidator.isActive(accessToken, identity.authUserId)) return null
        val authorization = authorizationResolver.resolve(identity.authUserId)
        // A valid Supabase user session is not sufficient for the invitation-
        // only admin panel. Consumer accounts must have an active admin role
        // and at least one effective permission.
        if (!authorization.adminEligible) return null
        return AdminPrincipal(
            authUserId = identity.authUserId,
            sessionId = identity.sessionId,
            userId = authorization.userId,
            displayName = authorization.displayName,
            email = authorization.email,
            roleIds = authorization.roleIds,
            permissions = authorization.permissions,
            accessToken = accessToken,
        )
    }
}

internal val AuthorizationSnapshot.adminEligible: Boolean
    get() = userId > 0 && roleIds.isNotEmpty() && permissions.isNotEmpty()

internal class DevelopmentOpenAccessAdminTokenAuthenticator(
    private val configuration: BackendConfiguration,
    private val delegate: AdminTokenAuthenticator,
) : AdminTokenAuthenticator {
    override suspend fun authenticate(accessToken: String): AdminPrincipal? {
        if (
            !configuration.developmentOpenAccess ||
            accessToken != AdminApiDevelopment.OPEN_ACCESS_TOKEN
        ) {
            return delegate.authenticate(accessToken)
        }
        return AdminPrincipal(
            authUserId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            sessionId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
            userId = configuration.developmentAdminUserId,
            displayName = "Administrador local",
            email = "",
            roleIds = listOf(1L),
            permissions = DEVELOPMENT_OPEN_ACCESS_PERMISSIONS,
            // WHY: downstream Supabase calls remain server-side and use the
            // secret only after Ktor has accepted the local development token.
            accessToken = configuration.supabaseSecretKey,
        )
    }
}

internal data class VerifiedSupabaseIdentity(
    val authUserId: UUID,
    val sessionId: UUID,
)

internal class SupabaseJwtVerifier(
    private val configuration: BackendConfiguration,
    private val supabaseHttp: SupabaseHttp,
) {
    private val jwksMutex = Mutex()

    @Volatile
    private var cachedJwks: CachedJwks? = null

    suspend fun verify(accessToken: String): VerifiedSupabaseIdentity {
        require(accessToken.length in 40..16_384) { "JWT no válido." }
        val unverified = JWT.decode(accessToken)
        val keyId = unverified.keyId?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("JWT sin kid.")
        val algorithmName = unverified.algorithm?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("JWT sin alg.")
        require(algorithmName in ALLOWED_ALGORITHMS) { "Algoritmo JWT no permitido." }
        val jwk = jwks().keys.firstOrNull { it.kid == keyId }
            ?: refreshJwks(force = true).keys.firstOrNull { it.kid == keyId }
            ?: throw IllegalArgumentException("JWT kid desconocido.")
        require(jwk.alg == null || jwk.alg == algorithmName) { "Algoritmo JWT no coincide con JWKS." }
        val algorithm = jwk.toAlgorithm(algorithmName)
        val verified = JWT.require(algorithm)
            .withIssuer(configuration.jwtIssuer)
            .withAudience(configuration.jwtAudience)
            .withClaimPresence("exp")
            .withClaimPresence("sub")
            .withClaimPresence("session_id")
            .acceptLeeway(15)
            .build()
            .verify(accessToken)
        val authUserId = UUID.fromString(verified.subject)
        val sessionId = UUID.fromString(
            verified.getClaim("session_id").asString()
                ?: throw IllegalArgumentException("JWT sin session_id."),
        )
        return VerifiedSupabaseIdentity(authUserId = authUserId, sessionId = sessionId)
    }

    private suspend fun jwks(): JwksDocument {
        val current = cachedJwks
        if (current != null && current.expiresAtMillis > System.currentTimeMillis()) return current.document
        return refreshJwks(force = false)
    }

    private suspend fun refreshJwks(force: Boolean): JwksDocument = jwksMutex.withLock {
        val current = cachedJwks
        if (!force && current != null && current.expiresAtMillis > System.currentTimeMillis()) {
            return@withLock current.document
        }
        val document = supabaseHttp.decode<JwksDocument>(
            supabaseHttp.publicGet("/auth/v1/.well-known/jwks.json"),
        )
        require(document.keys.isNotEmpty()) { "JWKS vacío." }
        cachedJwks = CachedJwks(
            document = document,
            expiresAtMillis = System.currentTimeMillis() + JWKS_CACHE_MILLIS,
        )
        document
    }

    companion object {
        private const val JWKS_CACHE_MILLIS = 10 * 60 * 1_000L
        private val ALLOWED_ALGORITHMS = setOf("RS256", "RS384", "RS512", "ES256", "ES384", "ES512")
    }
}

internal class SupabaseAuthorizationResolver(
    private val supabaseHttp: SupabaseHttp,
    private val ttlMillis: Long = 30_000,
) : AuthorizationResolver {
    private val cache = ConcurrentHashMap<UUID, CachedAuthorization>()
    private val refreshMutex = Mutex()

    override suspend fun resolve(authUserId: UUID): AuthorizationSnapshot {
        val cached = cache[authUserId]
        if (cached != null && cached.expiresAtMillis > System.currentTimeMillis()) return cached.snapshot
        return refreshMutex.withLock {
            val refreshed = cache[authUserId]
            if (refreshed != null && refreshed.expiresAtMillis > System.currentTimeMillis()) {
                return@withLock refreshed.snapshot
            }
            load(authUserId).also { snapshot ->
                cache[authUserId] = CachedAuthorization(
                    snapshot = snapshot,
                    expiresAtMillis = System.currentTimeMillis() + ttlMillis,
                )
            }
        }
    }

    private suspend fun load(authUserId: UUID): AuthorizationSnapshot {
        val permissions = supabaseHttp.internalRequest(
            method = HttpMethod.Post,
            path = "/rest/v1/rpc/admin_permissions_for_subject",
            body = buildJsonObject { put("p_auth_user_id", authUserId.toString()) },
        ).body
            .let { supabaseHttp.decodeList<PermissionCodeRow>(it) }
            .map(PermissionCodeRow::permissionCode)
            .toSet()
        val user = supabaseHttp.internalRequest(
            method = HttpMethod.Get,
            path = "/rest/v1/usuario?select=id,nombre,email&auth_user_id=eq.$authUserId&deleted_at=is.null&limit=1",
        ).body
            .let { supabaseHttp.decodeList<AdminIdentityRow>(it) }
            .firstOrNull()
        val roleIds = if (user == null) {
            emptyList()
        } else {
            supabaseHttp.internalRequest(
                method = HttpMethod.Get,
                path = "/rest/v1/user_role?select=role_id&usuario_id=eq.${user.id}",
            ).body
                .let { supabaseHttp.decodeList<AdminRoleIdRow>(it) }
                .map(AdminRoleIdRow::roleId)
                .distinct()
                .sorted()
        }
        return AuthorizationSnapshot(
            userId = user?.id ?: 0,
            displayName = user?.name.orEmpty(),
            email = user?.email.orEmpty(),
            roleIds = roleIds,
            permissions = permissions,
        )
    }
}

internal fun ApplicationCall.adminPrincipal(): AdminPrincipal =
    principal<AdminPrincipal>() ?: throw UnauthorizedProblem()

internal fun ApplicationCall.requirePermission(permission: String): AdminPrincipal {
    val principal = adminPrincipal()
    if (permission !in principal.permissions) throw ForbiddenProblem(permission)
    return principal
}

internal fun ApplicationCall.requirePermissionForMethod(
    readPermission: String,
    writePermission: String,
): AdminPrincipal = if (request.httpMethod == HttpMethod.Get || request.httpMethod == HttpMethod.Head) {
    requirePermission(readPermission)
} else {
    requirePermission(writePermission)
}

@Serializable
private data class JwksDocument(val keys: List<JwkEntry>)

@Serializable
private data class JwkEntry(
    val kid: String,
    val kty: String,
    val alg: String? = null,
    val n: String? = null,
    val e: String? = null,
    val crv: String? = null,
    val x: String? = null,
    val y: String? = null,
) {
    fun toAlgorithm(algorithmName: String): Algorithm {
        return when (kty) {
            "RSA" -> rsaAlgorithm(algorithmName)
            "EC" -> ecAlgorithm(algorithmName)
            else -> throw IllegalArgumentException("Tipo de clave JWT no permitido.")
        }
    }

    private fun rsaAlgorithm(algorithmName: String): Algorithm {
        val modulus = n?.decodeUnsignedBigInteger() ?: throw IllegalArgumentException("JWKS RSA sin n.")
        val exponent = e?.decodeUnsignedBigInteger() ?: throw IllegalArgumentException("JWKS RSA sin e.")
        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(modulus, exponent)) as RSAPublicKey
        return when (algorithmName) {
            "RS256" -> Algorithm.RSA256(publicKey, null)
            "RS384" -> Algorithm.RSA384(publicKey, null)
            "RS512" -> Algorithm.RSA512(publicKey, null)
            else -> throw IllegalArgumentException("Algoritmo RSA no permitido.")
        }
    }

    private fun ecAlgorithm(algorithmName: String): Algorithm {
        val curveName = when (crv) {
            "P-256" -> "secp256r1"
            "P-384" -> "secp384r1"
            "P-521" -> "secp521r1"
            else -> throw IllegalArgumentException("Curva JWT no permitida.")
        }
        val expectedAlgorithm = when (crv) {
            "P-256" -> "ES256"
            "P-384" -> "ES384"
            "P-521" -> "ES512"
            else -> error("Curva validada.")
        }
        require(algorithmName == expectedAlgorithm) { "Algoritmo y curva JWT incompatibles." }
        val parameters = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec(curveName))
        }
        val publicKey = KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(
                ECPoint(
                    x?.decodeUnsignedBigInteger() ?: throw IllegalArgumentException("JWKS EC sin x."),
                    y?.decodeUnsignedBigInteger() ?: throw IllegalArgumentException("JWKS EC sin y."),
                ),
                parameters.getParameterSpec(ECParameterSpec::class.java),
            ),
        ) as ECPublicKey
        return when (algorithmName) {
            "ES256" -> Algorithm.ECDSA256(publicKey, null)
            "ES384" -> Algorithm.ECDSA384(publicKey, null)
            "ES512" -> Algorithm.ECDSA512(publicKey, null)
            else -> throw IllegalArgumentException("Algoritmo EC no permitido.")
        }
    }
}

@Serializable
private data class PermissionCodeRow(
    @SerialName("permission_code") val permissionCode: String,
)

@Serializable
private data class AdminIdentityRow(
    val id: Long,
    val nombre: String? = null,
    val email: String? = null,
) {
    val name: String
        get() = nombre.orEmpty().ifBlank { "Administrador" }
}

@Serializable
private data class AdminRoleIdRow(
    @SerialName("role_id") val roleId: Long,
)

private data class CachedJwks(
    val document: JwksDocument,
    val expiresAtMillis: Long,
)

private data class CachedAuthorization(
    val snapshot: AuthorizationSnapshot,
    val expiresAtMillis: Long,
)

private val DEVELOPMENT_OPEN_ACCESS_PERMISSIONS = setOf(
    "maintenance",
    "dashboard.read",
    "users.read",
    "users.write",
    "roles.read",
    "roles.write",
    "content.read",
    "content.write",
    "content.publish",
    "exam_access.read",
    "exam_access.write",
    "commerce.read",
    "commerce.write",
    "tickets.read",
    "tickets.adjust",
    "support.read",
    "support.reply",
    "support.internal_note",
    "moderation.read",
    "moderation.write",
    "audit.read",
    "settings.read",
    "settings.write",
    "search.read",
)

private fun String.decodeUnsignedBigInteger(): BigInteger =
    BigInteger(1, Base64.getUrlDecoder().decode(this))
