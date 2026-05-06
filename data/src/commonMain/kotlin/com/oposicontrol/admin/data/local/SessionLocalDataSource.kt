package com.oposicontrol.admin.data.local

import com.oposicontrol.admin.core.database.AdminDatabase
import com.oposicontrol.admin.domain.model.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Instant

class SessionLocalDataSource(
    private val database: AdminDatabase,
) {
    private val state = MutableStateFlow(readSession())

    fun observeSession(): Flow<Session?> = state.asStateFlow()

    fun currentSession(): Session? = state.value

    fun save(session: Session) {
        database.sessionQueries.upsertSession(
            user_id = session.userId,
            display_name = session.displayName,
            access_token = session.accessToken,
            refresh_token = session.refreshToken,
            expires_at = session.expiresAt.toString(),
            role_ids_csv = session.roleIds.joinToString(","),
        )
        state.value = session
    }

    fun clear() {
        database.sessionQueries.clearSession()
        state.value = null
    }

    private fun readSession(): Session? {
        return database.sessionQueries.selectSession(
            mapper = { userId, displayName, accessToken, refreshToken, expiresAt, roleIdsCsv ->
                Session(
                    userId = userId,
                    displayName = displayName,
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresAt = Instant.parse(expiresAt),
                    roleIds = roleIdsCsv.split(",").filter { it.isNotBlank() },
                )
            },
        ).executeAsOneOrNull()
    }
}
