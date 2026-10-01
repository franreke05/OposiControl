package com.oposicontrol.admin.app.root

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MobileReportRewardTest {
    @Test
    fun dismissingNeverAllowsOrSendsAReward() {
        assertFalse(rewardAllowedFor("descartar"))
        assertEquals(0, effectiveReward("descartar", 5))
        assertEquals(0, effectiveReward("descartar", 10))
    }

    @Test
    fun resolvingActionsKeepTheChosenReward() {
        listOf("ocultar", "avisar", "banear").forEach { action ->
            assertTrue(rewardAllowedFor(action), action)
            assertEquals(10, effectiveReward(action, 10), action)
        }
    }
}
