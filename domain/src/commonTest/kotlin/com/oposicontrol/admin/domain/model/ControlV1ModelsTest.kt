package com.oposicontrol.admin.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControlV1ModelsTest {
    private val entry = ConfigEntry("referido_ot_invitador", "referido", "OT", null, 0, 1000, 10, null)

    @Test
    fun configValuesAreCheckedAgainstTheirRange() {
        assertNull(ConfigValidator.check(entry, " 50 "))
        assertNotNull(ConfigValidator.check(entry, "1001"))
        assertNotNull(ConfigValidator.check(entry, "-1"))
        assertNotNull(ConfigValidator.check(entry, "uno"))
        assertNotNull(ConfigValidator.check(entry, ""))
        assertTrue(entry.copy(max = 1).isSwitch)
        assertFalse(entry.isSwitch)
    }

    @Test
    fun forumActionsDependOnTheStateOfTheContent() {
        assertEquals(listOf(ForumAction.APPROVE, ForumAction.REJECT, ForumAction.MUTE), ForumModeration.actionsFor("pendiente_moderacion"))
        assertEquals(listOf(ForumAction.RESTORE), ForumModeration.actionsFor("oculta"))
        assertEquals(listOf(ForumAction.RESTORE), ForumModeration.actionsFor("oculto"))
        assertTrue(ForumModeration.actionsFor("eliminada").isEmpty())
    }

    @Test
    fun destructiveForumActionsNeedAReasonAndMuteNeedsBoundedDays() {
        assertNull(ForumModeration.check(ForumAction.APPROVE, "", ""))
        assertNotNull(ForumModeration.check(ForumAction.REJECT, "ab", ""))
        assertNull(ForumModeration.check(ForumAction.REJECT, "spam claro", ""))
        assertNotNull(ForumModeration.check(ForumAction.MUTE, "reincidente", "0"))
        assertNotNull(ForumModeration.check(ForumAction.MUTE, "reincidente", "366"))
        assertNotNull(ForumModeration.check(ForumAction.MUTE, "reincidente", "x"))
        assertNull(ForumModeration.check(ForumAction.MUTE, "reincidente", "30"))
        assertNotNull(ForumModeration.check(ForumAction.HIDE, "x".repeat(201), ""))
    }

    @Test
    fun hiddenCommentsUseTheCommentWireState() {
        assertEquals("oculto", ForumQueueState.HIDDEN.wireFor(ForumContentType.COMMENT))
        assertEquals("oculta", ForumQueueState.HIDDEN.wireFor(ForumContentType.POST))
        assertEquals("reportada", ForumQueueState.REPORTED.wireFor(ForumContentType.COMMENT))
    }

    @Test
    fun channelDraftValidationAndSlugSuggestion() {
        assertTrue(ForumChannelValidator.check(ForumChannelDraft.EMPTY).keys.containsAll(listOf("name", "slug")))
        assertTrue(ForumChannelValidator.check(ForumChannelDraft("General", "general", "", true, "1", null, false)).isEmpty())
        assertTrue("position" in ForumChannelValidator.check(ForumChannelDraft("General", "general", "", true, "0", null, false)))
        assertEquals("oposiciones-de-enfermeria-2026", ForumChannelValidator.suggestSlug("  Oposiciones de Enfermería 2026! "))
        assertTrue("slug" in ForumChannelValidator.check(ForumChannelDraft("General", "Con Espacios", "", true, "1", null, false)))
    }

    @Test
    fun blockPatternsAreNormalizedAndBounded() {
        assertEquals("casino", ForumBlockPattern.normalize("  CaSiNo "))
        assertNotNull(ForumBlockPattern.check("a"))
        assertNull(ForumBlockPattern.check("ab"))
        assertNotNull(ForumBlockPattern.check("x".repeat(101)))
    }

    @Test
    fun sellableNeedsAPositivePriceAndEveryExamNeedsACommunity() {
        val ok = ExamEconomyDraft(true, "1300", "30", "c1")
        assertTrue(ExamEconomyValidator.check(ok).isEmpty())
        assertTrue("price" in ExamEconomyValidator.check(ok.copy(price = "")))
        assertTrue("price" in ExamEconomyValidator.check(ok.copy(price = "0")))
        assertTrue(ExamEconomyValidator.check(ok.copy(sellable = false, price = "")).isEmpty())
        assertTrue("community" in ExamEconomyValidator.check(ok.copy(communityId = null)))
        assertTrue("community" in ExamEconomyValidator.check(ok.copy(communityId = " ")))
        assertTrue("days" in ExamEconomyValidator.check(ok.copy(days = "0")))
        assertTrue("days" in ExamEconomyValidator.check(ok.copy(days = "3651")))
        assertTrue("price" in ExamEconomyValidator.check(ok.copy(price = "1000001")))
    }

    @Test
    fun referralHelpersCoverEveryServerState() {
        ReferralStates.ALL.forEach { assertFalse(ReferralStates.label(it) == "Desconocido", it) }
        assertEquals("Desconocido", ReferralStates.label("inventado"))
        assertFalse(ReferralStates.canRevoke("revocada"))
        assertFalse(ReferralStates.canRevoke("rechazada"))
        assertTrue(ReferralStates.canRevoke("premiada"))
        assertNotNull(ReferralReason.check("ab"))
        assertNull(ReferralReason.check("duplicadas"))
        assertEquals("Ráfaga desde la misma IP", ReferralStates.flagLabel("ip_burst"))
    }

    @Test
    fun orderStatesAndNotes() {
        OrderStates.ALL.forEach { assertFalse(OrderStates.label(it) == "Desconocido", it) }
        assertEquals("Desconocido", OrderStates.label("x"))
        assertNotNull(OrderNote.check("  "))
        assertNull(OrderNote.check("Verificado con Google Play"))
        assertNotNull(OrderNote.check("x".repeat(501)))
    }
}

class ControlV1ResolutionModelsTest {
    @Test
    fun onlyRefundDependsOnTheServerSwitch() {
        assertEquals(listOf("acreditar", "confirmar", "rechazar"), OrderDecision.entries.filter { it.available(false) }.map { it.wire })
        assertEquals(4, OrderDecision.entries.count { it.available(true) })
    }

    @Test
    fun theResolutionNoteBoundsMatchTheServer() {
        assertTrue(OrderResolutionNote.check("ab") != null)
        assertNull(OrderResolutionNote.check("abc"))
        assertNull(OrderResolutionNote.check("x".repeat(300)))
        assertTrue(OrderResolutionNote.check("x".repeat(301)) != null)
    }

    @Test
    fun everyOutcomeHasAHumanSentenceAndAnUnknownOneIsNotInvented() {
        listOf("CREDITED", "CONFIRMED", "REJECTED", "REFUNDED", "REFUNDED_P2", "REPLAY").forEach {
            assertTrue(OrderResolution(it, "pagada", false).message().length > 15, it)
        }
        assertTrue(OrderResolution("REFUNDED_P2", "reembolsada", true).message().contains("caso de reembolso"))
        assertEquals("Decisión registrada.", OrderResolution("OTRO", "", false).message())
    }

    @Test
    fun aReserveOrderIsOptionalButBoundedAndNeverRepeatedChecks() {
        val q = OfficialQuestion("a", 1, "t", false, null, false, null, false)
        val other = OfficialQuestion("b", 2, "t", false, null, true, 1, false)
        assertTrue(OfficialQuestionValidator.check(OfficialQuestionDraft(false, "", true, ""), q, listOf(q, other)).isEmpty())
        assertTrue(OfficialQuestionValidator.check(OfficialQuestionDraft(false, "", true, "1"), q, listOf(q, other)).isEmpty())
        assertTrue("order" in OfficialQuestionValidator.check(OfficialQuestionDraft(false, "", true, "0"), q, listOf(q)))
        assertTrue("order" in OfficialQuestionValidator.check(OfficialQuestionDraft(false, "", false, "2"), q, listOf(q)))
    }

    @Test
    fun theImagePurgeSummaryNamesTheStuckOnes() {
        assertTrue(ForumImagePurge(0, 0, null).summary().startsWith("No hay"))
        assertTrue(ForumImagePurge(3, 0, null).summary().startsWith("3 imágenes"))
        assertTrue(ForumImagePurge(3, 2, null).summary().contains("2 llevan 10 intentos"))
    }
}
