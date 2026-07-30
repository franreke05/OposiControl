package com.oposicontrol.admin.data.local

import com.oposicontrol.admin.core.common.platform.AppPlatformContext
import com.oposicontrol.admin.domain.model.Session
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface SessionStore {
    fun read(): Session?
    fun write(session: Session)
    fun clear()
}

class SecureSessionStore(
    platformContext: AppPlatformContext,
) : SessionStore {
    private val platformStore = PlatformSecureValueStore(platformContext)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun read(): Session? {
        val encoded = platformStore.read() ?: return null
        return runCatching {
            json.decodeFromString<SecureSessionPayload>(encoded).toDomain()
        }.getOrElse {
            platformStore.clear()
            null
        }
    }

    override fun write(session: Session) {
        platformStore.write(json.encodeToString(SecureSessionPayload.from(session)))
    }

    override fun clear() {
        platformStore.clear()
    }
}

@Serializable
private data class SecureSessionPayload(
    val userId: String,
    val displayName: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: String,
    val roleIds: List<String>,
) {
    fun toDomain() = Session(
        userId = userId,
        displayName = displayName,
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAt = Instant.parse(expiresAt),
        roleIds = roleIds,
    )

    companion object {
        fun from(session: Session) = SecureSessionPayload(
            userId = session.userId,
            displayName = session.displayName,
            accessToken = session.accessToken,
            refreshToken = session.refreshToken,
            expiresAt = session.expiresAt.toString(),
            roleIds = session.roleIds,
        )
    }
}

internal expect class PlatformSecureValueStore(platformContext: AppPlatformContext) {
    fun read(): String?
    fun write(value: String)
    fun clear()
}
