package com.oposicontrol.admin.data.local

import com.oposicontrol.admin.core.api.AdminApiDevelopment
import com.oposicontrol.admin.domain.model.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Instant

class SessionLocalDataSource(
    private val secureStore: SessionStore,
    private val fallbackSession: Session? = null,
) {
    private val state = MutableStateFlow(fallbackSession ?: secureStore.read())

    fun observeSession(): Flow<Session?> = state.asStateFlow()

    fun currentSession(): Session? = state.value

    fun save(session: Session) {
        secureStore.write(session)
        state.value = session
    }

    fun clear() {
        secureStore.clear()
        state.value = fallbackSession
    }
}

internal fun developmentOpenAccessSession() = Session(
    userId = "development-admin",
    displayName = "Administrador local",
    accessToken = AdminApiDevelopment.OPEN_ACCESS_TOKEN,
    refreshToken = AdminApiDevelopment.OPEN_ACCESS_TOKEN,
    expiresAt = Instant.parse("2100-01-01T00:00:00Z"),
    roleIds = listOf("development-superadmin"),
)
