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
    fun theCapabilityMatrixSaysWhatSuspendingDoesAndWhoCanDoIt() {
        val capability = userCapability("Dar de baja o reactivar")!!
        // Parcial y no «disponible»: sólo la puede usar un superadministrador.
        assertEquals(UserCapabilityLevel.PARTIAL, capability.level)
        assertTrue(capability.note.contains("no borra la cuenta", ignoreCase = true))
        assertTrue(capability.note.contains("superadministradores", ignoreCase = true))
        // Borrar sigue sin ofrecerse: se dice que la salida es dar de baja.
        assertEquals(UserCapabilityLevel.BLOCKED, userCapability("Eliminar usuario")!!.level)
    }
}
