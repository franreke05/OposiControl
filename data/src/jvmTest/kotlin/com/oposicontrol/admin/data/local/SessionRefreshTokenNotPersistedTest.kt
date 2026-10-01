package com.oposicontrol.admin.data.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.oposicontrol.admin.core.database.AdminDatabase
import com.oposicontrol.admin.domain.model.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** MASTER6-AUTH-001: el refresh token del admin nunca llega a `session_cache` (SQLite en claro). */
class SessionRefreshTokenNotPersistedTest {
    private val session = Session(
        userId = "admin-1",
        displayName = "Admin",
        accessToken = "access-1",
        refreshToken = "refresh-secret",
        expiresAt = Instant.parse("2026-10-01T01:00:00Z"),
        roleIds = listOf("admin"),
    )

    private fun database(): AdminDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AdminDatabase.Schema.create(driver)
        return AdminDatabase(driver)
    }

    private fun storedRefreshTokens(db: AdminDatabase): List<String> =
        db.sessionQueries.selectSession { _, _, _, refresh, _, _ -> refresh }.executeAsList()

    @Test
    fun saveKeepsTheRefreshTokenInMemoryOnly() {
        val db = database()
        val store = SessionLocalDataSource(db)
        store.save(session)

        assertEquals("refresh-secret", store.currentSession()?.refreshToken, "la sesion en curso puede refrescar")
        assertEquals(listOf(""), storedRefreshTokens(db), "el disco no guarda el refresh token")
        assertEquals(
            "",
            SessionLocalDataSource(db).currentSession()?.refreshToken,
            "una sesion reabierta desde disco no tiene refresh token",
        )
    }

    @Test
    fun aLegacyRowWithARefreshTokenIsScrubbedOnOpen() {
        val db = database()
        db.sessionQueries.upsertSession("admin-1", "Admin", "access-1", "legacy-refresh", "2026-10-01T01:00:00Z", "admin")

        val store = SessionLocalDataSource(db)

        assertEquals("legacy-refresh", store.currentSession()?.refreshToken, "se usa una ultima vez en memoria")
        assertEquals(listOf(""), storedRefreshTokens(db))
    }

    @Test
    fun savingAnotherUserLeavesASingleRow() {
        val db = database()
        val store = SessionLocalDataSource(db)
        store.save(session)
        store.save(session.copy(userId = "admin-2", accessToken = "access-2"))

        assertEquals(1, storedRefreshTokens(db).size)
        assertEquals("admin-2", SessionLocalDataSource(db).currentSession()?.userId)
    }
}
