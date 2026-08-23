package com.oposicontrol.backend

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanonicalAuthorizationTest {
    @Test
    fun adminRequiresAnExplicitAdminRole() {
        assertTrue(CanonicalAuthorization.hasAdmin(listOf("admin")))
        assertTrue(CanonicalAuthorization.hasAdmin(listOf("superadmin")))
        assertFalse(CanonicalAuthorization.hasAdmin(listOf("moderador")))
        assertFalse(CanonicalAuthorization.hasAdmin(listOf("usuario")))
    }

    @Test
    fun roleMatchingIsCaseAndWhitespaceTolerantAtTheBoundary() {
        assertTrue(CanonicalAuthorization.hasAdmin(listOf(" ADMIN ")))
    }

    @Test
    fun moderatorPolicyIsExplicitlySeparateFromAdminPolicy() {
        assertTrue(CanonicalAuthorization.hasModeratorOrAdmin(listOf("moderador")))
        assertFalse(CanonicalAuthorization.hasSuperAdmin(listOf("admin", "moderador")))
    }
}
