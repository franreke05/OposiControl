package com.oposicontrol.admin.core.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class AdminPrincipalResponse(
    val authUserId: String,
    val userId: Long,
    val displayName: String,
    val email: String,
    val roleIds: List<Long>,
    val permissions: Set<String>,
)

@Serializable
data class PermissionResponse(
    val code: String,
    val description: String,
)

@Serializable
data class RoleResponse(
    val id: Long,
    val name: String,
    val description: String? = null,
    val permissionCodes: Set<String>,
    val version: Long,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class UpsertRoleRequest(
    val name: String,
    val description: String? = null,
    val permissionCodes: Set<String>,
    val expectedVersion: Long? = null,
)

@Serializable
data class UserResponse(
    val id: Long,
    val authUserId: String? = null,
    val email: String,
    val displayName: String,
    val specialtyId: Long? = null,
    val roleIds: List<Long>,
    val active: Boolean,
    val version: Long,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class InviteUserRequest(
    val email: String,
    val displayName: String,
    val specialtyId: Long? = null,
    val roleIds: List<Long>,
)

@Serializable
data class UpdateUserRequest(
    val displayName: String,
    val specialtyId: Long? = null,
    val roleIds: List<Long>,
    val active: Boolean,
    val expectedVersion: Long,
)

@Serializable
data class AdminPreferenceResponse(
    val displayName: String,
    val receiveReleaseNotes: Boolean,
    val version: Long,
    val updatedAt: String,
)

@Serializable
data class UpdateAdminPreferenceRequest(
    val displayName: String,
    val receiveReleaseNotes: Boolean,
    val expectedVersion: Long,
)

@Serializable
data class AuditEventResponse(
    val id: Long,
    val actorUserId: Long? = null,
    val actorDisplayName: String? = null,
    val action: String,
    val resource: String,
    val resourceId: String? = null,
    val metadata: JsonObject,
    val requestId: String? = null,
    val occurredAt: String,
)
