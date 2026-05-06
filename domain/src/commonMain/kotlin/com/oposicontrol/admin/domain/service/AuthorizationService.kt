package com.oposicontrol.admin.domain.service

import com.oposicontrol.admin.domain.model.Role
import com.oposicontrol.admin.domain.model.Session

class AuthorizationService {
    fun can(session: Session?, permissionKey: String, roles: List<Role>): Boolean {
        if (session == null) return false
        val rolePermissionKeys = roles
            .filter { it.id in session.roleIds }
            .flatMap { role -> role.permissions.map { permission -> permission.key } }
            .toSet()
        return permissionKey in rolePermissionKeys
    }
}
