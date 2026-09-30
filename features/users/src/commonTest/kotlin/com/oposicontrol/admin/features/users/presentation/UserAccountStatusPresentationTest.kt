package com.oposicontrol.admin.features.users.presentation

import com.oposicontrol.admin.domain.model.AccountStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** MASTER-OC-001: el estado de cuenta canonico se ve, y la copia falsa «No existe bloqueo» ya no existe. */
class UserAccountStatusPresentationTest {

    @Test
    fun everyCanonicalAccountStatusHasItsOwnLabel() {
        assertEquals("Activa", userAccountStatusLabel(AccountStatus.ACTIVE))
        assertEquals("Bloqueada", userAccountStatusLabel(AccountStatus.BLOCKED))
        assertEquals("Eliminación pendiente", userAccountStatusLabel(AccountStatus.DELETION_PENDING))
        // Un estado ausente o no reconocido nunca se presenta como activo.
        assertEquals(USER_NO_DATA, userAccountStatusLabel(AccountStatus.UNKNOWN))
        assertNotEquals("Activa", userAccountStatusLabel(AccountStatus.UNKNOWN))
    }

    @Test
    fun blockedAndDeletionPendingAskForAttentionWhileUnknownIsNeverPositive() {
        assertEquals(UserPillTone.POSITIVE, userAccountStatusTone(AccountStatus.ACTIVE))
        assertEquals(UserPillTone.ATTENTION, userAccountStatusTone(AccountStatus.BLOCKED))
        assertEquals(UserPillTone.ATTENTION, userAccountStatusTone(AccountStatus.DELETION_PENDING))
        assertEquals(UserPillTone.NEUTRAL, userAccountStatusTone(AccountStatus.UNKNOWN))
    }

    @Test
    fun mobileRowAttentionRuleMatchesTheDesktopPill() {
        // MASTER4-OC-004: la fila móvil usa el estado canónico; desconocido nunca es positivo ni pide atención falsa.
        assertTrue(userAccountStatusNeedsAttention(AccountStatus.BLOCKED))
        assertTrue(userAccountStatusNeedsAttention(AccountStatus.DELETION_PENDING))
        assertFalse(userAccountStatusNeedsAttention(AccountStatus.ACTIVE))
        assertFalse(userAccountStatusNeedsAttention(AccountStatus.UNKNOWN))
        assertEquals("Sin dato", userAccountStatusLabel(AccountStatus.UNKNOWN))
    }

    @Test
    fun theCapabilityMatrixNoLongerClaimsThatBlockingDoesNotExist() {
        val capability = userCapability("Bloquear o desbloquear")!!
        assertFalse(capability.note.contains("No existe bloqueo", ignoreCase = true))
        assertTrue(capability.note.contains("estado de la cuenta", ignoreCase = true))
        assertEquals(UserCapabilityLevel.NOT_FOUND, capability.level, "cambiar el estado sigue sin estar disponible en el panel")
        assertFalse(USER_ACCOUNT_STATUS_READ_ONLY_NOTE.contains("No existe bloqueo", ignoreCase = true))
    }
}
