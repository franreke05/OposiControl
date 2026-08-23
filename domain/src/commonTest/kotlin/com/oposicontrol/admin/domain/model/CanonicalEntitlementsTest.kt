package com.oposicontrol.admin.domain.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanonicalEntitlementsTest {
    private val free = CanonicalPlanEntitlements("free", emptySet())
    private val rafagas = CanonicalPlanEntitlements(
        "rafagas",
        setOf(CanonicalEntitlementPolicy.RAFAGA_BENEFIT),
    )
    private val razonados = CanonicalPlanEntitlements(
        "razonados",
        setOf(
            CanonicalEntitlementPolicy.RAFAGA_BENEFIT,
            CanonicalEntitlementPolicy.RAZONADO_BENEFIT,
        ),
    )
    private val inmersivo = CanonicalPlanEntitlements(
        "inmersivo",
        setOf(
            CanonicalEntitlementPolicy.RAFAGA_BENEFIT,
            CanonicalEntitlementPolicy.RAZONADO_BENEFIT,
            CanonicalEntitlementPolicy.INMERSIVO_BENEFIT,
        ),
    )

    @Test
    fun confirmedPlanHierarchyIsRepresentedByBenefits() {
        assertFalse(CanonicalEntitlementPolicy.canAccess(free, "rafaga"))
        assertTrue(CanonicalEntitlementPolicy.canAccess(rafagas, "rafaga"))
        assertFalse(CanonicalEntitlementPolicy.canAccess(rafagas, "razonado"))
        assertTrue(CanonicalEntitlementPolicy.canAccess(razonados, "razonado"))
        assertFalse(CanonicalEntitlementPolicy.canAccess(razonados, "inmersivo"))
        assertTrue(CanonicalEntitlementPolicy.canAccess(inmersivo, "inmersivo"))
        assertTrue(CanonicalEntitlementPolicy.canAccess(inmersivo, "razonado"))
    }

    @Test
    fun legacyAliasIsAcceptedOnlyAtTheBoundary() {
        assertTrue(CanonicalEntitlementPolicy.canAccess(razonados, "guiado"))
        assertTrue(CanonicalEntitlementPolicy.canAccess(razonados, "GUIADO"))
        assertFalse(CanonicalEntitlementPolicy.canAccess(razonados, "otro"))
    }
}
