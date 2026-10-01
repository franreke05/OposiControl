package com.oposicontrol.admin.data.local

import com.oposicontrol.admin.core.database.AdminDatabase
import com.oposicontrol.admin.domain.model.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Instant

/**
 * Sesion del panel admin.
 *
 * MASTER6-AUTH-001: el refresh token NUNCA se escribe en disco. `session_cache` es SQLite en claro y el proyecto no
 * tiene almacen protegido por el sistema operativo (Keystore/Keychain/DPAPI), y anadir uno seria una dependencia
 * nueva. El refresh token es la credencial de larga vida, asi que vive solo en memoria ([state]); en disco queda la
 * columna `refresh_token` vacia y el access token, de vida corta (expira en `expires_at`). Al reiniciar la app la
 * sesion se puede reanudar mientras ese access token siga vigente; despues hay que volver a iniciar sesion.
 *
 * Instalaciones anteriores tenian el refresh token en disco: al abrir se lee una ultima vez (para no expulsar al
 * usuario en la actualizacion) y se reescribe la fila sin el.
 */
class SessionLocalDataSource(
    private val database: AdminDatabase,
) {
    private val state = MutableStateFlow(readSessionAndScrubLegacyRefreshToken())

    fun observeSession(): Flow<Session?> = state.asStateFlow()

    fun currentSession(): Session? = state.value

    fun save(session: Session) {
        persist(session)
        state.value = session
    }

    fun clear() {
        database.sessionQueries.clearSession()
        state.value = null
    }

    private fun persist(session: Session) {
        // Una sola fila: la clave es user_id, asi que un INSERT OR REPLACE de otro usuario dejaba la fila anterior
        // (con sus tokens) y `selectSession ... LIMIT 1` podia devolverla.
        database.transaction {
            database.sessionQueries.clearSession()
            database.sessionQueries.upsertSession(
                user_id = session.userId,
                display_name = session.displayName,
                access_token = session.accessToken,
                refresh_token = NOT_PERSISTED,
                expires_at = session.expiresAt.toString(),
                role_ids_csv = session.roleIds.joinToString(","),
            )
        }
    }

    private fun readSessionAndScrubLegacyRefreshToken(): Session? {
        val stored = database.sessionQueries.selectSession(
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
        ).executeAsOneOrNull() ?: return null
        if (stored.refreshToken.isNotEmpty()) persist(stored)
        return stored
    }

    private companion object {
        const val NOT_PERSISTED = ""
    }
}
