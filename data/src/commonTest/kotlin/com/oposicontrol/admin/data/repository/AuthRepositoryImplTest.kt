package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.api.AdminApiDevelopment
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.local.SessionStore
import com.oposicontrol.admin.data.remote.source.AuthDataSource
import com.oposicontrol.admin.domain.model.AuthCredentials
import com.oposicontrol.admin.domain.model.Session
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthRepositoryImplTest {
    @Test
    fun concurrentRefreshesShareOneRemoteRotation() = runTest {
        val initial = session(accessToken = "access-1", refreshToken = "refresh-1")
        val store = InMemorySessionStore(initial)
        val remote = CountingAuthDataSource(
            refreshResult = AppResult.Success(
                session(accessToken = "access-2", refreshToken = "refresh-2"),
            ),
        )
        val repository = AuthRepositoryImpl(remote, SessionLocalDataSource(store))

        val results = List(12) {
            async { repository.refreshSession() }
        }.awaitAll()

        assertEquals(1, remote.refreshCalls)
        assertTrue(results.all { it is AppResult.Success })
        assertEquals("refresh-2", store.read()?.refreshToken)
    }

    @Test
    fun logoutClearsLocalSessionEvenWhenRemoteFails() = runTest {
        val store = InMemorySessionStore(session("access-1", "refresh-1"))
        val remote = CountingAuthDataSource(
            logoutResult = AppResult.Failure(AppError.Network("offline")),
        )
        val repository = AuthRepositoryImpl(remote, SessionLocalDataSource(store))

        repository.logout()

        assertNull(store.read())
    }

    @Test
    fun developmentOpenAccessNeverCallsSupabaseAuthAndSurvivesLogout() = runTest {
        val developmentSession = session(
            accessToken = AdminApiDevelopment.OPEN_ACCESS_TOKEN,
            refreshToken = AdminApiDevelopment.OPEN_ACCESS_TOKEN,
        )
        val store = InMemorySessionStore(null)
        val local = SessionLocalDataSource(store, fallbackSession = developmentSession)
        val remote = CountingAuthDataSource()
        val repository = AuthRepositoryImpl(remote, local)

        assertEquals(AppResult.Success(developmentSession), repository.refreshSession())
        assertEquals(AppResult.Success(Unit), repository.logout())

        assertEquals(0, remote.refreshCalls)
        assertEquals(0, remote.logoutCalls)
        assertEquals(developmentSession, local.currentSession())
        assertNull(store.read())
    }

    private fun session(accessToken: String, refreshToken: String) = Session(
        userId = "user-1",
        displayName = "Admin",
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAt = Instant.parse("2026-07-30T00:00:00Z"),
        roleIds = listOf("superadmin"),
    )
}

private class InMemorySessionStore(
    private var value: Session?,
) : SessionStore {
    override fun read(): Session? = value

    override fun write(session: Session) {
        value = session
    }

    override fun clear() {
        value = null
    }
}

private class CountingAuthDataSource(
    private val refreshResult: AppResult<Session> = AppResult.Failure(
        AppError.Authentication("not configured"),
    ),
    private val logoutResult: AppResult<Unit> = AppResult.Success(Unit),
) : AuthDataSource {
    var refreshCalls: Int = 0
        private set
    var logoutCalls: Int = 0
        private set

    override suspend fun login(credentials: AuthCredentials): AppResult<Session> = refreshResult

    override suspend fun refreshSession(refreshToken: String): AppResult<Session> {
        refreshCalls += 1
        delay(50)
        return refreshResult
    }

    override suspend fun logout(accessToken: String?): AppResult<Unit> {
        logoutCalls += 1
        return logoutResult
    }
}
