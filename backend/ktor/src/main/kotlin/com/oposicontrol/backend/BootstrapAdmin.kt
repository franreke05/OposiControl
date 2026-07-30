package com.oposicontrol.backend

import io.ktor.http.HttpMethod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal class BootstrapAdmin(
    private val configuration: BackendConfiguration,
    private val supabaseHttp: SupabaseHttp,
) {
    private val state = AtomicReference(BootstrapState.READY)

    suspend fun initialize() {
        val email = configuration.bootstrapAdminEmail ?: return
        if (configuration.production) {
            state.set(BootstrapState.DEGRADED_BOOTSTRAP_ENV)
            System.err.println("[bootstrap-admin] disabled in production; remove BOOTSTRAP_ADMIN_EMAIL")
            return
        }
        if (!email.matches(EMAIL_PATTERN)) {
            state.set(BootstrapState.DEGRADED_FAILED)
            System.err.println("[bootstrap-admin] invalid BOOTSTRAP_ADMIN_EMAIL")
            return
        }
        try {
            val authUserId = findAuthUser(email) ?: inviteAuthUser(email)
            val userId = linkApplicationUser(authUserId, email)
            val changed = assignSuperadmin(userId)
            if (changed) appendBootstrapAudit(authUserId, userId)
            state.set(BootstrapState.DEGRADED_BOOTSTRAP_ENV)
            System.err.println("[bootstrap-admin] bootstrap complete; remove BOOTSTRAP_ADMIN_EMAIL")
        } catch (problem: ApiProblem) {
            state.set(BootstrapState.DEGRADED_FAILED)
            System.err.println("[bootstrap-admin] bootstrap failed code=${problem.code}")
        } catch (error: Exception) {
            state.set(BootstrapState.DEGRADED_FAILED)
            System.err.println("[bootstrap-admin] bootstrap failed type=${error::class.simpleName}")
        }
    }

    fun readiness(): ReadinessSnapshot = when (state.get()) {
        BootstrapState.READY -> ReadinessSnapshot(ready = true, status = "ready")
        BootstrapState.DEGRADED_BOOTSTRAP_ENV ->
            ReadinessSnapshot(ready = false, status = "degraded_bootstrap_environment")
        BootstrapState.DEGRADED_FAILED ->
            ReadinessSnapshot(ready = false, status = "degraded_bootstrap_failed")
    }

    private suspend fun findAuthUser(email: String): UUID? {
        val response = supabaseHttp.internalRequest(
            method = HttpMethod.Get,
            path = "/auth/v1/admin/users?page=1&per_page=1000",
        ).body.let { supabaseHttp.decode<AuthUsersPage>(it) }
        return response.users.firstOrNull { it.email.equals(email, ignoreCase = true) }
            ?.id
            ?.let(UUID::fromString)
    }

    private suspend fun inviteAuthUser(email: String): UUID {
        val response = supabaseHttp.internalRequest(
            method = HttpMethod.Post,
            path = "/auth/v1/invite",
            body = buildJsonObject { put("email", email) },
        ).body.let { supabaseHttp.decode<InvitedAuthUser>(it) }
        return UUID.fromString(response.id)
    }

    private suspend fun linkApplicationUser(authUserId: UUID, email: String): Long {
        val existing = supabaseHttp.internalRequest(
            method = HttpMethod.Get,
            path = "/rest/v1/usuario?select=id&auth_user_id=eq.$authUserId&limit=1",
        ).body.let { supabaseHttp.decodeList<BootstrapUserRow>(it) }.firstOrNull()
        if (existing != null) return existing.id
        val response = supabaseHttp.internalRequest(
            method = HttpMethod.Post,
            path = "/rest/v1/usuario",
            body = buildJsonObject {
                put("auth_user_id", authUserId.toString())
                put("email", email)
                put("nombre", "Administrador")
                put("activo", true)
            },
            prefer = "return=representation",
        ).body
        return supabaseHttp.decodeList<BootstrapUserRow>(response).firstOrNull()?.id
            ?: throw UpstreamProblem("No se pudo vincular el administrador.")
    }

    private suspend fun assignSuperadmin(userId: Long): Boolean {
        val roleId = supabaseHttp.internalRequest(
            method = HttpMethod.Get,
            path = "/rest/v1/app_role?select=id&name=eq.superadmin&deleted_at=is.null&limit=1",
        ).body.let { supabaseHttp.decodeList<BootstrapRoleRow>(it) }.firstOrNull()?.id
            ?: throw UpstreamProblem("No existe el rol superadmin.")
        val exists = supabaseHttp.internalRequest(
            method = HttpMethod.Get,
            path = "/rest/v1/user_role?select=id&usuario_id=eq.$userId&role_id=eq.$roleId&limit=1",
        ).body.let { supabaseHttp.decodeList<BootstrapRoleAssignmentRow>(it) }.isNotEmpty()
        if (exists) return false
        supabaseHttp.internalRequest(
            method = HttpMethod.Post,
            path = "/rest/v1/user_role",
            body = buildJsonObject {
                put("usuario_id", userId)
                put("role_id", roleId)
            },
            prefer = "return=minimal",
        )
        return true
    }

    private suspend fun appendBootstrapAudit(authUserId: UUID, userId: Long) {
        supabaseHttp.internalRequest(
            method = HttpMethod.Post,
            path = "/rest/v1/audit_log",
            body = JsonArray(
                listOf(
                    buildJsonObject {
                        put("actor_auth_user_id", authUserId.toString())
                        put("actor_usuario_id", userId)
                        put("action", "BOOTSTRAP_SUPERADMIN")
                        put("resource", "usuario")
                        put("resource_id", userId.toString())
                        put("metadata", buildJsonObject { put("source", "startup") })
                    },
                ),
            ),
            prefer = "return=minimal",
        )
    }

    companion object {
        private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}

internal class ProductionReadinessProvider(
    private val bootstrapAdmin: BootstrapAdmin,
    private val supabaseHttp: SupabaseHttp,
) : ReadinessProvider {
    override suspend fun snapshot(): ReadinessSnapshot {
        val bootstrap = bootstrapAdmin.readiness()
        if (!bootstrap.ready) return bootstrap
        return try {
            supabaseHttp.internalRequest(
                method = HttpMethod.Get,
                path = "/rest/v1/especialidad?select=id&limit=1",
            )
            ReadinessSnapshot(ready = true, status = "ready")
        } catch (_: ApiProblem) {
            ReadinessSnapshot(ready = false, status = "degraded_supabase")
        }
    }
}

private enum class BootstrapState {
    READY,
    DEGRADED_BOOTSTRAP_ENV,
    DEGRADED_FAILED,
}

@Serializable
private data class AuthUsersPage(
    val users: List<AuthUserSummary> = emptyList(),
)

@Serializable
private data class AuthUserSummary(
    val id: String,
    val email: String? = null,
)

@Serializable
private data class InvitedAuthUser(
    val id: String,
)

@Serializable
private data class BootstrapUserRow(
    val id: Long,
)

@Serializable
private data class BootstrapRoleRow(
    val id: Long,
)

@Serializable
private data class BootstrapRoleAssignmentRow(
    val id: Long,
)
