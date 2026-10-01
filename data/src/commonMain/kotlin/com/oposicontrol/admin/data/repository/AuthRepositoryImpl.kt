package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.source.AuthDataSource
import com.oposicontrol.admin.domain.model.AuthCredentials
import com.oposicontrol.admin.domain.model.Session
import com.oposicontrol.admin.domain.repository.AuthRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Refresco bajo demanda que usa el interceptor HTTP (MASTER6-AUTH-004): refresca SOLO si la sesion actual sigue
 * siendo la del token rechazado/caducado; si otra llamada ya la refresco, devuelve la vigente sin tocar la red.
 * [serverRejected] = el backend respondio 401 con ese token (no es solo un refresco preventivo por caducidad).
 */
fun interface StaleSessionRefresher {
    suspend fun refreshIfStale(staleAccessToken: String, serverRejected: Boolean): AppResult<Session>
}

/**
 * Ciclo de vida de la sesion admin.
 *
 * - MASTER6-AUTH-005: cada cambio de identidad (login, logout, limpieza por rechazo definitivo) incrementa [epoch].
 *   Un login o refresh que empezo antes de ese cambio no puede guardar su resultado al terminar: se descarta y se
 *   revoca (best-effort) en el servidor. La comprobacion y el guardado van bajo [stateLock].
 * - MASTER6-AUTH-004: los refrescos son single-flight ([refreshLock]); la llamada de red y su commit corren en
 *   `NonCancellable`, porque Supabase rota el refresh token: cancelar entre la respuesta y el guardado dejaria en
 *   memoria un token ya consumido.
 * - MASTER6-AUTH-003: solo un `AppError.Authentication` (rechazo definitivo del refresh token) limpia la sesion; un
 *   error de red, timeout, 5xx o 429 la conserva.
 * - MASTER6-AUTH-002: logout limpia la copia local PRIMERO (siempre, antes de cualquier suspension) y despues revoca
 *   en el servidor; si el access token ya caduco, refresca una vez (sin guardar) y reintenta.
 */
class AuthRepositoryImpl(
    private val remote: AuthDataSource,
    private val local: SessionLocalDataSource,
    private val now: () -> Instant = { Clock.System.now() },
) : AuthRepository, StaleSessionRefresher {
    private val stateLock = Mutex()
    private val refreshLock = Mutex()
    private var epoch = 0L

    override fun observeSession(): Flow<Session?> = local.observeSession()

    override suspend fun login(credentials: AuthCredentials): AppResult<Session> {
        val startEpoch = stateLock.withLock { epoch }
        val result = remote.login(credentials)
        if (result !is AppResult.Success) return result
        val committed = withContext(NonCancellable) {
            stateLock.withLock {
                if (epoch != startEpoch) return@withLock false
                epoch++
                local.save(result.value)
                true
            }
        }
        if (!committed) {
            revokeDropped(result.value)
            return AppResult.Failure(AppError.Authentication(SESSION_CLOSED_MESSAGE))
        }
        return result
    }

    override suspend fun refreshSession(): AppResult<Session> = refresh(staleAccessToken = null, serverRejected = false)

    override suspend fun refreshIfStale(staleAccessToken: String, serverRejected: Boolean): AppResult<Session> =
        refresh(staleAccessToken = staleAccessToken, serverRejected = serverRejected)

    /** El access token persistido caduca dentro de [skew] (o ya caduco). */
    fun isExpiring(session: Session, skew: Duration = EXPIRY_SKEW): Boolean = session.expiresAt <= now() + skew

    private suspend fun refresh(staleAccessToken: String?, serverRejected: Boolean): AppResult<Session> = refreshLock.withLock {
        val (startEpoch, current) = stateLock.withLock { epoch to local.currentSession() }
        if (current == null) return AppResult.Failure(AppError.Authentication(NO_SESSION_MESSAGE))
        if (staleAccessToken != null && current.accessToken != staleAccessToken) return AppResult.Success(current)
        if (current.refreshToken.isBlank()) {
            // MASTER6-AUTH-001: el refresh token no se persiste. Tras reiniciar la app solo queda el access token:
            // mientras siga vigente la sesion vale tal cual; cuando caduca no hay forma de renovarla.
            val stillUsable = if (staleAccessToken == null) !isExpiring(current) else !serverRejected && now() < current.expiresAt
            if (stillUsable) return AppResult.Success(current)
            clearIfStill(startEpoch, current)
            return AppResult.Failure(AppError.Authentication(EXPIRED_MESSAGE))
        }
        withContext(NonCancellable) {
            when (val result = remote.refreshSession(current.refreshToken)) {
                is AppResult.Failure -> {
                    if (result.error is AppError.Authentication) clearIfStill(startEpoch, current)
                    result
                }

                is AppResult.Success -> {
                    val committed = stateLock.withLock {
                        val stillSame = epoch == startEpoch && local.currentSession()?.refreshToken == current.refreshToken
                        if (stillSame) local.save(result.value)
                        stillSame
                    }
                    if (committed) {
                        result
                    } else {
                        revokeDropped(result.value)
                        AppResult.Failure(AppError.Authentication(SESSION_CLOSED_MESSAGE))
                    }
                }
            }
        }
    }

    override suspend fun logout(): AppResult<Unit> {
        val session = stateLock.withLock {
            val current = local.currentSession()
            epoch++
            local.clear()
            current
        } ?: return AppResult.Success(Unit)
        return withContext(NonCancellable) { revoke(session) }
    }

    private suspend fun revoke(session: Session): AppResult<Unit> {
        val first = remote.logout(session.accessToken)
        if (first !is AppResult.Failure || first.error !is AppError.Authentication) return first
        // Access token caducado: un refresh (sin guardar) da un token valido para revocar la MISMA sesion.
        if (session.refreshToken.isBlank()) {
            // Sin refresh token en ningun sitio de este equipo (MASTER6-AUTH-001) no queda credencial que revocar.
            return AppResult.Success(Unit)
        }
        return when (val refreshed = remote.refreshSession(session.refreshToken)) {
            is AppResult.Success -> remote.logout(refreshed.value.accessToken)
            // Refresh token rechazado de forma definitiva: el servidor ya no reconoce la sesion. Nada que revocar.
            is AppResult.Failure -> if (refreshed.error is AppError.Authentication) AppResult.Success(Unit) else refreshed
        }
    }

    private suspend fun clearIfStill(startEpoch: Long, session: Session) {
        stateLock.withLock {
            if (epoch == startEpoch && local.currentSession()?.accessToken == session.accessToken) {
                epoch++
                local.clear()
            }
        }
    }

    /** Un resultado que llego tarde (tras logout/login) no se guarda; se revoca para no dejarlo vivo en el servidor. */
    private suspend fun revokeDropped(session: Session) {
        withContext(NonCancellable) { remote.logout(session.accessToken) }
    }

    companion object {
        val EXPIRY_SKEW: Duration = 60.seconds
        const val SESSION_CLOSED_MESSAGE = "La sesión se cerró mientras se completaba la operación."
        const val NO_SESSION_MESSAGE = "No hay sesion activa para refrescar."
        const val EXPIRED_MESSAGE = "Tu sesión ha caducado. Vuelve a iniciar sesión."
    }
}
