package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.AuthCredentials
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** MASTER6-AUTH-002/003/005 (y el refresco sin refresh token persistido de MASTER6-AUTH-001). */
class AuthRepositoryLifecycleTest {

    // ---- MASTER6-AUTH-005: un refresh/login en vuelo no resucita la sesion tras logout ----

    @Test
    fun aRefreshInFlightDuringLogoutCannotResurrectTheSession() = runTest {
        val remote = ScriptedAuthDataSource().apply {
            refreshGate = CompletableDeferred()
            refreshResults += AppResult.Success(session("late-access"))
        }
        val local = sessionStore(session("old-access"))
        val repo = AuthRepositoryImpl(remote, local, now = { T0 })

        val refreshing = async { repo.refreshSession() }
        remote.refreshStarted.await()
        assertEquals(AppResult.Success(Unit), repo.logout())
        remote.refreshGate!!.complete(Unit)
        val late = refreshing.await()

        assertIs<AppResult.Failure>(late)
        assertNull(local.currentSession(), "el refresh tardio no debe volver a guardar la sesion")
        // El resultado descartado se revoca en el servidor.
        assertEquals("logout:late-access", remote.calls.last())
    }

    @Test
    fun aLoginInFlightDuringLogoutIsDroppedAndRevoked() = runTest {
        val remote = ScriptedAuthDataSource().apply { loginGate = CompletableDeferred() }
        val local = sessionStore()
        val repo = AuthRepositoryImpl(remote, local, now = { T0 })

        val login = async { repo.login(AuthCredentials("a@b.c", "x")) }
        remote.loginStarted.await()
        repo.logout()
        remote.loginGate!!.complete(Unit)

        assertIs<AppResult.Failure>(login.await())
        assertNull(local.currentSession())
        assertEquals("logout:login-access", remote.calls.last())
    }

    // ---- MASTER6-AUTH-004 (repositorio): single-flight ----

    @Test
    fun concurrentStaleRefreshesHitTheNetworkOnce() = runTest {
        val remote = ScriptedAuthDataSource().apply {
            refreshGate = CompletableDeferred()
            refreshResults += AppResult.Success(session("new-access"))
        }
        val local = sessionStore(session("old-access"))
        val repo = AuthRepositoryImpl(remote, local, now = { T0 })

        val first = async { repo.refreshIfStale("old-access", serverRejected = true) }
        val second = async { repo.refreshIfStale("old-access", serverRejected = true) }
        remote.refreshStarted.await()
        yield()
        remote.refreshGate!!.complete(Unit)

        assertEquals("new-access", (first.await() as AppResult.Success).value.accessToken)
        assertEquals("new-access", (second.await() as AppResult.Success).value.accessToken)
        assertEquals(1, remote.calls.count { it.startsWith("refresh:") })
        assertEquals("new-access", local.currentSession()?.accessToken)
    }

    // ---- MASTER6-AUTH-003: solo un rechazo definitivo borra la sesion ----

    @Test
    fun transientRefreshFailureKeepsThePersistedSession() = runTest {
        val remote = ScriptedAuthDataSource().apply { refreshResults += networkFailure }
        val local = sessionStore(session("old-access"))
        val repo = AuthRepositoryImpl(remote, local, now = { T0 })

        assertEquals(networkFailure, repo.refreshSession())
        assertEquals("old-access", local.currentSession()?.accessToken)
        assertEquals(listOf("refresh:r-old-access"), remote.calls, "un fallo transitorio no revoca nada")
    }

    @Test
    fun definitiveRefreshRejectionClearsTheSessionWithoutRevoking() = runTest {
        val remote = ScriptedAuthDataSource().apply { refreshResults += authFailure }
        val local = sessionStore(session("old-access"))
        val repo = AuthRepositoryImpl(remote, local, now = { T0 })

        assertEquals(authFailure, repo.refreshSession())
        assertNull(local.currentSession())
        assertEquals(listOf("refresh:r-old-access"), remote.calls)
    }

    // ---- MASTER6-AUTH-002: logout revoca de verdad ----

    @Test
    fun logoutWithExpiredAccessTokenRefreshesOnceAndRevokesWithTheNewToken() = runTest {
        val remote = ScriptedAuthDataSource().apply {
            logoutResults += authFailure
            refreshResults += AppResult.Success(session("fresh-access"))
            logoutResults += AppResult.Success(Unit)
        }
        val local = sessionStore(session("old-access"))
        val repo = AuthRepositoryImpl(remote, local, now = { T0 })

        assertEquals(AppResult.Success(Unit), repo.logout())
        assertEquals(listOf("logout:old-access", "refresh:r-old-access", "logout:fresh-access"), remote.calls)
        assertNull(local.currentSession(), "el token refrescado para revocar no se guarda")
    }

    @Test
    fun logoutClearsLocallyButReportsFailureWhenTheServerCouldNotRevoke() = runTest {
        val remote = ScriptedAuthDataSource().apply { logoutResults += networkFailure }
        val local = sessionStore(session("old-access"))
        val repo = AuthRepositoryImpl(remote, local, now = { T0 })

        assertEquals(networkFailure, repo.logout())
        assertNull(local.currentSession())
    }

    @Test
    fun logoutDoesNotRetryMoreThanOnce() = runTest {
        val remote = ScriptedAuthDataSource().apply {
            logoutResults += authFailure
            refreshResults += AppResult.Success(session("fresh-access"))
            logoutResults += authFailure
        }
        val repo = AuthRepositoryImpl(remote, sessionStore(session("old-access")), now = { T0 })

        val result = repo.logout()
        assertIs<AppResult.Failure>(result)
        assertIs<AppError.Authentication>(result.error)
        assertEquals(3, remote.calls.size)
    }

    @Test
    fun logoutWhoseRefreshTokenIsAlreadyRejectedHasNothingLeftToRevoke() = runTest {
        val remote = ScriptedAuthDataSource().apply {
            logoutResults += authFailure
            refreshResults += authFailure
        }
        val repo = AuthRepositoryImpl(remote, sessionStore(session("old-access")), now = { T0 })
        assertEquals(AppResult.Success(Unit), repo.logout())
    }

    // ---- MASTER6-AUTH-001: sin refresh token persistido (sesion reabierta tras reiniciar) ----

    @Test
    fun restoredSessionWithoutRefreshTokenIsUsableUntilItsAccessTokenExpires() = runTest {
        val remote = ScriptedAuthDataSource()
        val local = sessionStore(session("disk-access", refresh = "", expiresAt = T0 + 30.minutes))
        val repo = AuthRepositoryImpl(remote, local, now = { T0 })

        assertEquals("disk-access", (repo.refreshSession() as AppResult.Success).value.accessToken)
        assertEquals(emptyList<String>(), remote.calls, "nunca se envia un refresh token vacio")
    }

    @Test
    fun restoredSessionWithoutRefreshTokenEndsWhenTheAccessTokenIsRejected() = runTest {
        val remote = ScriptedAuthDataSource()
        val local = sessionStore(session("disk-access", refresh = "", expiresAt = T0 + 30.seconds))
        val repo = AuthRepositoryImpl(remote, local, now = { T0 })

        assertIs<AppResult.Failure>(repo.refreshIfStale("disk-access", serverRejected = true))
        assertNull(local.currentSession())
        assertEquals(emptyList<String>(), remote.calls)
    }
}
