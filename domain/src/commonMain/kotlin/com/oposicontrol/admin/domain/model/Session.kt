package com.oposicontrol.admin.domain.model

import kotlinx.datetime.Instant

data class Session(
    val userId: String,
    val displayName: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Instant,
    val roleIds: List<String>,
)
