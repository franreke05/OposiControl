package com.oposicontrol.backend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * D1-19 `DELETE /api/v1/app/me`: SOLICITUD de borrado, no borrado.
 *
 * Lo unico que hace es lo que el contrato marca como sincrono (pasos 1 y 2):
 *
 * 0. valida `confirmation`;
 * 1. reautentica ANTES de tocar nada (contrasena via GoTrue, u OAuth con autenticacion reciente);
 * 2. UNA transaccion en la base ([RPC_REQUEST]): estado `eliminacion_pendiente` + fila de
 *    solicitud + auditoria; repetir devuelve el `requestedAt` ORIGINAL sin volver a escribir;
 * 3. tras el commit, logout global de GoTrue con el bearer del llamante, en mejor esfuerzo
 *    (su resultado se anota en la fila; un fallo NUNCA cambia el 202).
 *
 * Los pasos fisicos (banear y limpiar la identidad de Auth, anonimizar el perfil, borrar
 * preferencias, desvincular analitica) son DEFERRED_EXECUTION: NO hay worker, cron ni cascada
 * destructiva aqui, y la respuesta jamas dice que el borrado este completado. La cuenta queda
 * inutilizable por la puerta de estado (auth 403, rutas canonicas 403, guarda de la base).
 */
internal class AccountDeletionService(
    private val upstream: CanonicalUpstream,
    private val authProvider: AuthProviderClient,
    private val clock: () -> Instant = Instant::now,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    @Serializable
    private data class RequestRow(
        @SerialName("estado_cuenta") val estadoCuenta: String,
        @SerialName("solicitada_en") val solicitadaEn: String,
        @SerialName("ya_pendiente") val yaPendiente: Boolean = false,
    )

    suspend fun request(
        identity: CanonicalUserIdentity,
        accessToken: String,
        claims: VerifiedJwtClaims,
        request: AccountDeletionRequest,
        context: OposiBotsAuthContext,
    ): AccountDeletionAccepted {
        if (request.confirmation != CONFIRMATION_LITERAL) {
            throw validationError(ErrorIssue("confirmation", ISSUE_BAD_CONFIRMATION))
        }
        if ((request.currentPassword?.length ?: 0) > MAX_PASSWORD_LENGTH) {
            throw validationError(ErrorIssue("currentPassword", CanonicalIssue.OUT_OF_RANGE))
        }
        // Una cuenta que YA esta pendiente no cambia de estado: no hay nada que proteger con una
        // reautenticacion (y su sesion de GoTrue puede haber sido revocada por el primer intento).
        if (identity.accountStatus == AccountStatus.ACTIVA) reauthenticate(accessToken, claims, request, context)

        val row = requestDeletion(identity)
        // MASTER-AUTH-015: una repeticion vuelve a intentar la revocacion si la anterior NO consta como `hecha`
        // (fallo, o el intento se corto antes de registrarse). Toda la revocacion corre en NonCancellable: que el
        // cliente cierre la conexion ya no deja la sesion viva sin anotar.
        withContext(NonCancellable) {
            if (!row.yaPendiente) {
                revokeSessionsBestEffort(identity, accessToken, context, retry = false)
            } else if (revocationPending(identity, context)) {
                revokeSessionsBestEffort(identity, accessToken, context, retry = true)
            }
        }
        return AccountDeletionAccepted(accountStatus = STATUS_PENDING, requestedAt = normalizeInstant(row.solicitadaEn))
    }

    /**
     * ¿La revocacion de sesiones de la solicitud sigue sin constar como `hecha`? Lectura con la clave de servicio
     * (la tabla solo concede SELECT a service_role). Si no se puede leer, no se reintenta: reintentar sobre una
     * sesion ya revocada anotaria `fallida` encima de un `hecha` real.
     */
    private suspend fun revocationPending(identity: CanonicalUserIdentity, context: OposiBotsAuthContext): Boolean = try {
        val body = canonicalUpstreamIo {
            upstream.get(
                "/rest/v1/solicitudes_eliminacion_cuenta?select=revocacion_sesiones&usuario_id=eq.${identity.userId.value}&limit=1",
            )
        }
        val estado = json.decodeFromString(ListSerializer(RevocationRow.serializer()), body).singleOrNull()?.revocacionSesiones
        estado != null && estado != REVOKE_DONE
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Logger.warn("reading session revocation state failed requestId={} type={}", context.requestId, failure::class.simpleName)
        false
    }

    @Serializable
    private data class RevocationRow(@SerialName("revocacion_sesiones") val revocacionSesiones: String? = null)

    private suspend fun reauthenticate(
        accessToken: String,
        claims: VerifiedJwtClaims,
        request: AccountDeletionRequest,
        context: OposiBotsAuthContext,
    ) {
        val user = authProvider.user(accessToken, context)
        if (user.hasPassword) {
            val password = request.currentPassword?.takeIf { it.isNotEmpty() }
                ?: throw validationError(ErrorIssue("currentPassword", ISSUE_CURRENT_PASSWORD_REQUIRED))
            val email = user.email ?: throw CanonicalErrorCode.REAUTH_FAILED.exception()
            authProvider.verifyPassword(email, password, context)
        } else {
            // OAuth: la ultima autenticacion (amr) tiene que ser de los ultimos 5 minutos. Sin marca
            // conocida se falla CERRADO (R6): nunca se asume reciente.
            val latest = claims.authTimes.maxOrNull()
            if (latest == null || clock().epochSecond - latest > RECENT_AUTH_SECONDS) throw CanonicalErrorCode.STALE_AUTH.exception()
        }
    }

    private suspend fun requestDeletion(identity: CanonicalUserIdentity): RequestRow {
        val args = buildJsonObject { put("p_usuario_id", identity.userId.value) }
        val response = canonicalUpstreamIo { upstream.rpc(RPC_REQUEST, args.toString()) }
        if (!response.ok) throw ProfileRpcErrors.failure(RPC_REQUEST, response)
        val row = try {
            json.decodeFromString(ListSerializer(RequestRow.serializer()), response.body).singleOrNull()
        } catch (e: IllegalArgumentException) {
            Logger.warn("deletion request row could not be decoded: {}", e.redactedMessage("decode error"))
            null
        }
        // Nunca se finge exito: sin la fila confirmada por la base, o con otro estado, es un error de contrato.
        if (row == null || row.estadoCuenta != STATUS_PENDING) throw CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.exception()
        return row
    }

    private suspend fun revokeSessionsBestEffort(
        identity: CanonicalUserIdentity,
        accessToken: String,
        context: OposiBotsAuthContext,
        retry: Boolean,
    ) {
        val outcome = try {
            authProvider.logout(accessToken, context)
            REVOKE_DONE
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (retry && failure.meansSessionAlreadyGone()) {
                // MASTER2-AUTH-009: en un REINTENTO, GoTrue dice explicitamente que la sesion ya no existe
                // (`session_not_found`, MASTER3-AUTH-001): el logout anterior ya la revoco. Anotar `fallida` aqui no
                // convergeria nunca. Cualquier otro 4xx sigue siendo `fallida`.
                Logger.info("session already revoked on retry requestId={}", context.requestId)
                REVOKE_DONE
            } else {
                Logger.warn("session revocation after deletion request failed requestId={} type={}", context.requestId, failure::class.simpleName)
                REVOKE_FAILED
            }
        }
        try {
            val args = buildJsonObject {
                put("p_usuario_id", identity.userId.value)
                put("p_resultado", outcome)
            }
            val recorded = withUpstreamIo { upstream.rpc(RPC_RECORD_REVOCATION, args.toString()) }
            // MASTER-OBS-006: un 4xx/5xx de la RPC (p. ej. sin solicitud de borrado) ya no pasa en silencio.
            if (!recorded.ok) {
                Logger.warn("recording session revocation rejected requestId={} status={}", context.requestId, recorded.status)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Logger.warn("recording session revocation failed requestId={} type={}", context.requestId, failure::class.simpleName)
        }
    }

    /**
     * MASTER3-AUTH-001: SOLO cuando el proveedor dijo explicitamente que la sesion del bearer ya no existe
     * ([OposiBotsAuthProblem.upstreamSessionGone], 401/403 + `session_not_found`). El estado HTTP mapeado no basta:
     * un 400/422, una apikey mala o un 403 ajeno tambien acaban en 401 y NO prueban que la sesion este revocada; esos
     * siguen anotando `fallida` para que un reintento posterior vuelva a intentarlo (`hecha` es irreversible).
     */
    private fun Exception.meansSessionAlreadyGone(): Boolean = this is OposiBotsAuthProblem && upstreamSessionGone

    companion object {
        const val CONFIRMATION_LITERAL = "ELIMINAR"
        const val ISSUE_BAD_CONFIRMATION = "BAD_CONFIRMATION"
        const val ISSUE_CURRENT_PASSWORD_REQUIRED = "CURRENT_PASSWORD_REQUIRED"
        const val STATUS_PENDING = "eliminacion_pendiente"
        const val RPC_REQUEST = "app_solicitar_eliminacion_cuenta"
        const val RPC_RECORD_REVOCATION = "app_registrar_revocacion_sesiones"
        const val RECENT_AUTH_SECONDS = 300L
        private const val MAX_PASSWORD_LENGTH = 1_024
        private const val REVOKE_DONE = "hecha"
        private const val REVOKE_FAILED = "fallida"
        private val Logger = LoggerFactory.getLogger("OC-KTOR-ACCOUNT-DELETION")
    }
}
