package com.oposicontrol.admin.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.database.AdminDatabase
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.source.AuthDataSource
import com.oposicontrol.admin.domain.model.AuthCredentials
import com.oposicontrol.admin.domain.model.Session
import kotlinx.coroutines.CompletableDeferred
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

internal val T0: Instant = Instant.parse("2026-10-01T00:00:00Z")

internal fun session(access: String, refresh: String = "r-$access", expiresAt: Instant = T0 + 1.hours) = Session(
    userId = "admin-1",
    displayName = "Admin",
    accessToken = access,
    refreshToken = refresh,
    expiresAt = expiresAt,
    roleIds = listOf("admin"),
)

internal fun inMemoryDatabase(): AdminDatabase {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    AdminDatabase.Schema.create(driver)
    return AdminDatabase(driver)
}

internal fun sessionStore(initial: Session? = null): SessionLocalDataSource =
    SessionLocalDataSource(inMemoryDatabase()).also { store -> initial?.let(store::save) }

internal val authFailure = AppResult.Failure(AppError.Authentication("rechazado"))
internal val networkFailure = AppResult.Failure(AppError.Network("Unable to reach the server"))

/**
 * AuthDataSource programable. Cada llamada queda registrada (sin tokens en logs: solo en memoria del test) y, si se
 * configura una puerta, se queda suspendida hasta que el test la abre, para reproducir carreras deterministas.
 */
internal class ScriptedAuthDataSource : AuthDataSource {
    val calls = mutableListOf<String>()
    var loginResult: AppResult<Session> = AppResult.Success(session("login-access"))
    val refreshResults = ArrayDeque<AppResult<Session>>()
    val logoutResults = ArrayDeque<AppResult<Unit>>()
    var refreshGate: CompletableDeferred<Unit>? = null
    var loginGate: CompletableDeferred<Unit>? = null
    val refreshStarted = CompletableDeferred<Unit>()
    val loginStarted = CompletableDeferred<Unit>()

    override suspend fun login(credentials: AuthCredentials): AppResult<Session> {
        calls += "login"
        loginStarted.complete(Unit)
        loginGate?.await()
        return loginResult
    }

    override suspend fun refreshSession(refreshToken: String): AppResult<Session> {
        calls += "refresh:$refreshToken"
        refreshStarted.complete(Unit)
        refreshGate?.await()
        return refreshResults.removeFirstOrNull() ?: error("refresh no esperado")
    }

    override suspend fun logout(accessToken: String?): AppResult<Unit> {
        calls += "logout:$accessToken"
        return logoutResults.removeFirstOrNull() ?: AppResult.Success(Unit)
    }
}
