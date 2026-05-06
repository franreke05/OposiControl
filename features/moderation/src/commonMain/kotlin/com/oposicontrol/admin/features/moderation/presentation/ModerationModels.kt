package com.oposicontrol.admin.features.moderation.presentation

data class ModerationActionRow(
    val id: String,
    val actionType: String,
    val targetUser: String,
    val executor: String,
    val reason: String,
    val createdAt: String,
)
