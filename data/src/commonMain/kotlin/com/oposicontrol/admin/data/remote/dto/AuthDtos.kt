package com.oposicontrol.admin.data.remote.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

@Serializable
data class LoginRequestDto(
    val email: String,
    val password: String,
)

@Serializable
data class RefreshSessionRequestDto(
    val refreshToken: String,
)

@Serializable
data class SessionDto(
    val userId: String,
    val displayName: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: String,
    val roleIds: List<String>,
)

@Serializable
data class SupabaseAuthResponseDto(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String,
    @SerialName("expires_in")
    val expiresInSeconds: Long,
    val user: SupabaseUserDto,
)

@Serializable
data class SupabaseUserDto(
    val id: String,
    val email: String? = null,
    @SerialName("user_metadata")
    val userMetadata: JsonObject = JsonObject(emptyMap()),
)

fun SupabaseAuthResponseDto.toSessionDto(): SessionDto {
    val displayName = user.userMetadata["display_name"]?.jsonPrimitive?.contentOrNull
        ?: user.userMetadata["name"]?.jsonPrimitive?.contentOrNull
        ?: user.email
        ?: "Administrador"
    return SessionDto(
        userId = user.id,
        displayName = displayName,
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAt = (Clock.System.now() + expiresInSeconds.seconds).toString(),
        roleIds = listOf("admin"),
    )
}
