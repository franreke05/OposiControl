package com.oposicontrol.backend

enum class CanonicalRole(val code: String) {
    SUPERADMIN("superadmin"),
    ADMIN("admin"),
    MODERADOR("moderador"),
    USUARIO("usuario"),
}

object CanonicalAuthorization {
    val adminRoleCodes: Set<String> = setOf(CanonicalRole.SUPERADMIN.code, CanonicalRole.ADMIN.code)
    val moderatorOrAdminRoleCodes: Set<String> = adminRoleCodes + CanonicalRole.MODERADOR.code

    fun hasAdmin(grantedRoles: Iterable<String>): Boolean =
        grantedRoles.any { it.trim().lowercase() in adminRoleCodes }

    fun hasSuperAdmin(grantedRoles: Iterable<String>): Boolean =
        grantedRoles.any { it.trim().lowercase() == CanonicalRole.SUPERADMIN.code }

    fun hasModeratorOrAdmin(grantedRoles: Iterable<String>): Boolean =
        grantedRoles.any { it.trim().lowercase() in moderatorOrAdminRoleCodes }
}
