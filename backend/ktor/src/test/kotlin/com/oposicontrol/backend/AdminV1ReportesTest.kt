package com.oposicontrol.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdminV1ReportesTest {
    @Test
    fun actorIdFromPrincipalName_acceptsARealJwtUuid() {
        val uuid = "123e4567-e89b-12d3-a456-426614174000"
        assertEquals(uuid, actorIdFromPrincipalName(uuid))
    }

    @Test
    fun actorIdFromPrincipalName_neverFabricatesAnActor_forTheLegacyStaticTokenPrincipal() {
        assertNull(actorIdFromPrincipalName("oposicontrol-admin"))
    }

    @Test
    fun actorIdFromPrincipalName_returnsNull_whenThereIsNoPrincipalAtAll() {
        assertNull(actorIdFromPrincipalName(null))
    }

    @Test
    fun actorIdFromPrincipalName_returnsNull_forBlankOrMalformedValues() {
        assertNull(actorIdFromPrincipalName(""))
        assertNull(actorIdFromPrincipalName("not-a-uuid"))
    }
}
