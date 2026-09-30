package com.oposicontrol.admin.data.remote.api

import com.oposicontrol.admin.core.network.AdminApiJson
import com.oposicontrol.admin.domain.model.AccountStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** MASTER5-OC-004: el perfil de la ficha lee el canon (D1-24) y no fabrica false para campos ausentes. */
class UserProfileCanonicalWireTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private fun profile(perfil: String) = AdminApiJson
        .decodeFromString<UserDetailEnvelope>("""{"data":{"perfil":$perfil}}""").data.toDomain().profile

    @Test fun canonicalOnlyProfileIsReadWithoutLegacyKeys() {
        val p = profile("""{"id":"$id","fullName":"Ana","onboardingCompleted":true,"createdAt":"2026-01-01T00:00:00Z","accountStatus":"activa"}""")
        assertEquals(id, p.userId)
        assertEquals("Ana", p.displayName)
        assertEquals(true, p.onboardingCompleted)
        assertEquals("2026-01-01T00:00:00Z", p.createdAt)
        assertEquals(AccountStatus.ACTIVE, p.accountStatus)
    }

    @Test fun canonicalWinsOverLegacy() {
        val p = profile("""{"usuario_id":"$id","onboardingCompleted":true,"onboarding_completado":false,"createdAt":"2026-02-02T00:00:00Z","creado_en":"2020-01-01"}""")
        assertEquals(true, p.onboardingCompleted)
        assertEquals("2026-02-02T00:00:00Z", p.createdAt)
    }

    @Test fun legacyOnlyStillWorks() {
        val p = profile("""{"usuario_id":"$id","onboarding_completado":false,"es_invitado":true,"creado_en":"2026-01-23"}""")
        assertEquals(false, p.onboardingCompleted)
        assertEquals(true, p.isGuest)
        assertEquals("2026-01-23", p.createdAt)
    }

    @Test fun absentBooleansStayUnknownNotFalse() {
        val p = profile("""{"usuario_id":"$id"}""")
        assertNull(p.onboardingCompleted, "ausente = sin dato, no 'Pendiente'")
        assertNull(p.isGuest)
        assertNull(p.createdAt)
    }

    @Test fun blankAccountStatusDoesNotBlockTheLegacyFallback() {
        assertEquals(AccountStatus.BLOCKED, profile("""{"usuario_id":"$id","accountStatus":"","estado_cuenta":"bloqueada"}""").accountStatus)
        assertEquals(AccountStatus.BLOCKED, profile("""{"usuario_id":"$id","accountStatus":"  ","estado_cuenta":"bloqueada"}""").accountStatus)
        assertEquals(AccountStatus.UNKNOWN, profile("""{"usuario_id":"$id","accountStatus":""}""").accountStatus)
    }
}
