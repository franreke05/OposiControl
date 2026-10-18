package com.oposicontrol.admin.features.controlv1.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.Community
import com.oposicontrol.admin.domain.model.ConfigEntry
import com.oposicontrol.admin.domain.model.ExamEconomy
import com.oposicontrol.admin.domain.model.ExamEconomyDraft
import com.oposicontrol.admin.domain.model.ExamEconomyPage
import com.oposicontrol.admin.domain.model.ForumAction
import com.oposicontrol.admin.domain.model.ForumChannel
import com.oposicontrol.admin.domain.model.ForumChannelDraft
import com.oposicontrol.admin.domain.model.ForumContentType
import com.oposicontrol.admin.domain.model.ForumQueuePage
import com.oposicontrol.admin.domain.model.ForumQueueState
import com.oposicontrol.admin.domain.model.ForumRestriction
import com.oposicontrol.admin.domain.model.OrderItem
import com.oposicontrol.admin.domain.model.OrderPage
import com.oposicontrol.admin.domain.model.ReferralPage
import com.oposicontrol.admin.domain.model.ReferralRevokeResult
import com.oposicontrol.admin.domain.model.FastWindow
import com.oposicontrol.admin.domain.model.FastWindowPage
import com.oposicontrol.admin.domain.repository.CommunitiesRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ControlV1ViewModelsTest {
    private fun TestScope.dispatchers() = TestDispatcherProvider(StandardTestDispatcher(testScheduler))

    // ------------------------------------------------------------------------------------------------ configuración

    private fun entry(key: String, value: Int? = 10, min: Int = 0, max: Int = 1000) =
        ConfigEntry(key, "referido", key, null, min, max, value, null)

    @Test
    fun anInvalidConfigValueNeverReachesTheRepository() = runTest {
        val repo = FakeControlV1Repository().apply { config = listOf(entry("referido_ot_invitador")) }
        val vm = ConfigEditorViewModel(dispatchers(), repo, "referido")
        advanceUntilIdle()
        vm.dispatch(ConfigEditorIntent.Edit("referido_ot_invitador", "5000"))
        vm.dispatch(ConfigEditorIntent.Save("referido_ot_invitador"))
        advanceUntilIdle()
        assertTrue("referido_ot_invitador" in vm.state.value.errors)
        assertTrue(repo.calls.none { it.startsWith("setConfig") }, repo.calls.toString())
    }

    @Test
    fun aValidConfigValueIsSavedAndTheDraftClears() = runTest {
        val repo = FakeControlV1Repository().apply { config = listOf(entry("referido_ot_invitador")) }
        val vm = ConfigEditorViewModel(dispatchers(), repo, "referido")
        advanceUntilIdle()
        vm.dispatch(ConfigEditorIntent.Edit("referido_ot_invitador", " 50 "))
        vm.dispatch(ConfigEditorIntent.Save("referido_ot_invitador"))
        advanceUntilIdle()
        assertTrue("setConfig:referido_ot_invitador=50" in repo.calls)
        val state = vm.state.value
        assertTrue(state.drafts.isEmpty())
        assertEquals(50, assertIs<UiAsync.Success<List<ConfigEntry>>>(state.entries).value.single().value)
        assertIs<UiAsync.Success<Unit>>(state.feedback)
    }

    @Test
    fun aFailedSaveKeepsTheDraftAndShowsTheError() = runTest {
        val repo = FakeControlV1Repository().apply { config = listOf(entry("referido_ot_invitador")) }
        val vm = ConfigEditorViewModel(dispatchers(), repo, "referido")
        advanceUntilIdle()
        vm.dispatch(ConfigEditorIntent.Edit("referido_ot_invitador", "50"))
        repo.failNext = "setConfig:referido_ot_invitador=50"
        vm.dispatch(ConfigEditorIntent.Save("referido_ot_invitador"))
        advanceUntilIdle()
        assertEquals("50", vm.state.value.drafts["referido_ot_invitador"])
        assertIs<UiAsync.Error>(vm.state.value.feedback)
    }

    @Test
    fun aSwitchNeedsConfirmationAndNothingIsWrittenUntilThen() = runTest {
        val repo = FakeControlV1Repository().apply { config = listOf(entry("referido_activo", value = 0, max = 1)) }
        val vm = ConfigEditorViewModel(dispatchers(), repo, "referido")
        advanceUntilIdle()
        vm.dispatch(ConfigEditorIntent.RequestSwitch("referido_activo", true))
        advanceUntilIdle()
        assertEquals(PendingSwitch("referido_activo", "referido_activo", true), vm.state.value.pendingSwitch)
        assertTrue(repo.calls.none { it.startsWith("setConfig") })
        vm.dispatch(ConfigEditorIntent.DismissSwitch)
        advanceUntilIdle()
        assertNull(vm.state.value.pendingSwitch)
        assertTrue(repo.calls.none { it.startsWith("setConfig") })
        vm.dispatch(ConfigEditorIntent.RequestSwitch("referido_activo", true))
        vm.dispatch(ConfigEditorIntent.ConfirmSwitch)
        advanceUntilIdle()
        assertTrue("setConfig:referido_activo=1" in repo.calls)
    }

    @Test
    fun aNonSwitchKeyCannotBeToggled() = runTest {
        val repo = FakeControlV1Repository().apply { config = listOf(entry("referido_ot_invitador")) }
        val vm = ConfigEditorViewModel(dispatchers(), repo, "referido")
        advanceUntilIdle()
        vm.dispatch(ConfigEditorIntent.RequestSwitch("referido_ot_invitador", true))
        advanceUntilIdle()
        assertNull(vm.state.value.pendingSwitch)
    }

    // ------------------------------------------------------------------------------------------------ invitaciones

    @Test
    fun revokingNeedsAReasonAndAConfirmationAndReportsWhatCouldNotBeReverted() = runTest {
        val repo = FakeControlV1Repository().apply {
            referrals = ReferralPage(listOf(referralItem("r1"), referralItem("r2", "rechazada")), null, mapOf("premiada" to 1))
            revokeResult = ReferralRevokeResult(reverted = 60, notRevertible = 40)
        }
        val vm = ReferralViewModel(dispatchers(), repo)
        advanceUntilIdle()
        // una rechazada no se puede revocar
        vm.dispatch(ReferralIntent.RequestRevoke("r2"))
        advanceUntilIdle()
        assertNull(vm.state.value.revokeTarget)

        vm.dispatch(ReferralIntent.RequestRevoke("r1"))
        vm.dispatch(ReferralIntent.UpdateReason("ab"))
        vm.dispatch(ReferralIntent.ConfirmRevoke)
        advanceUntilIdle()
        assertNotNull(vm.state.value.revokeError)
        assertTrue(repo.calls.none { it.startsWith("revokeReferral") })

        vm.dispatch(ReferralIntent.UpdateReason("Cuentas duplicadas"))
        vm.dispatch(ReferralIntent.ConfirmRevoke)
        advanceUntilIdle()
        assertTrue("revokeReferral:r1:Cuentas duplicadas" in repo.calls)
        val note = assertIs<UiAsync.Success<String>>(vm.state.value.action).value
        assertTrue(note.contains("60") && note.contains("40") && note.contains("no se pueden recuperar"), note)
        assertNull(vm.state.value.revokeTarget)
    }

    @Test
    fun theReferralListPaginatesWithoutDuplicates() = runTest {
        val repo = FakeControlV1Repository().apply {
            referralPages = mutableListOf(
                ReferralPage(listOf(referralItem("r1"), referralItem("r2")), "c1", emptyMap()),
                ReferralPage(listOf(referralItem("r2"), referralItem("r3")), null, emptyMap()),
            )
        }
        val vm = ReferralViewModel(dispatchers(), repo)
        advanceUntilIdle()
        vm.dispatch(ReferralIntent.LoadMore)
        advanceUntilIdle()
        val ids = assertIs<UiAsync.Success<List<com.oposicontrol.admin.domain.model.ReferralItem>>>(vm.state.value.items).value.map { it.id }
        assertEquals(listOf("r1", "r2", "r3"), ids)
        assertNull(vm.state.value.nextCursor)
    }

    @Test
    fun anUnprovisionedListIsAnErrorStateNotAnEmptyOne() = runTest {
        val repo = FakeControlV1Repository().apply { failNext = "listReferrals:null:null" }
        val vm = ReferralViewModel(dispatchers(), repo)
        advanceUntilIdle()
        assertIs<UiAsync.Error>(vm.state.value.items)
    }

    @Test
    fun anUnknownFilterIsIgnored() = runTest {
        val repo = FakeControlV1Repository()
        val vm = ReferralViewModel(dispatchers(), repo)
        advanceUntilIdle()
        val before = repo.calls.size
        vm.dispatch(ReferralIntent.SetFilter("inventado"))
        advanceUntilIdle()
        assertEquals(before, repo.calls.size)
        assertNull(vm.state.value.filter)
    }

    // ------------------------------------------------------------------------------------------------ foro

    @Test
    fun onlyActionsThatFitTheStateAreOfferedAndNothingRunsWithoutConfirm() = runTest {
        val repo = FakeControlV1Repository().apply { queue = ForumQueuePage(listOf(queueItem("p1")), null) }
        val vm = ForumQueueViewModel(dispatchers(), repo)
        advanceUntilIdle()
        // restaurar no tiene sentido sobre un pendiente
        vm.dispatch(ForumQueueIntent.Choose("p1", ForumAction.RESTORE))
        advanceUntilIdle()
        assertNull(vm.state.value.pending)
        vm.dispatch(ForumQueueIntent.Choose("p1", ForumAction.REJECT))
        advanceUntilIdle()
        assertNotNull(vm.state.value.pending)
        assertTrue(repo.calls.none { it.startsWith("moderateForum") })
        vm.dispatch(ForumQueueIntent.Confirm)
        advanceUntilIdle()
        assertNotNull(vm.state.value.pending?.error, "rechazar exige motivo")
        assertTrue(repo.calls.none { it.startsWith("moderateForum") })
    }

    @Test
    fun rejectingSendsTheReasonAndRefreshesTheQueue() = runTest {
        val repo = FakeControlV1Repository().apply { queue = ForumQueuePage(listOf(queueItem("p1")), null) }
        val vm = ForumQueueViewModel(dispatchers(), repo)
        advanceUntilIdle()
        vm.dispatch(ForumQueueIntent.Choose("p1", ForumAction.REJECT))
        vm.dispatch(ForumQueueIntent.UpdateReason("  spam evidente "))
        repo.queue = ForumQueuePage(emptyList(), null)
        vm.dispatch(ForumQueueIntent.Confirm)
        advanceUntilIdle()
        assertTrue("moderateForum:publicacion:p1:rechazar:spam evidente:null" in repo.calls, repo.calls.toString())
        assertEquals(UiAsync.Empty, vm.state.value.items)
        assertNull(vm.state.value.pending)
    }

    @Test
    fun mutingNeedsBoundedDays() = runTest {
        val repo = FakeControlV1Repository().apply { queue = ForumQueuePage(listOf(queueItem("p1")), null) }
        val vm = ForumQueueViewModel(dispatchers(), repo)
        advanceUntilIdle()
        vm.dispatch(ForumQueueIntent.Choose("p1", ForumAction.MUTE))
        vm.dispatch(ForumQueueIntent.UpdateReason("reincidente"))
        vm.dispatch(ForumQueueIntent.UpdateDays("999"))
        vm.dispatch(ForumQueueIntent.Confirm)
        advanceUntilIdle()
        assertNotNull(vm.state.value.pending?.error)
        vm.dispatch(ForumQueueIntent.UpdateDays("30"))
        vm.dispatch(ForumQueueIntent.Confirm)
        advanceUntilIdle()
        assertTrue(repo.calls.any { it.startsWith("moderateForum:publicacion:p1:silenciar:reincidente:30") }, repo.calls.toString())
    }

    @Test
    fun aFailedActionKeepsTheItemInTheQueue() = runTest {
        val repo = FakeControlV1Repository().apply { queue = ForumQueuePage(listOf(queueItem("p1")), null) }
        val vm = ForumQueueViewModel(dispatchers(), repo)
        advanceUntilIdle()
        vm.dispatch(ForumQueueIntent.Choose("p1", ForumAction.APPROVE))
        repo.failNext = "moderateForum:publicacion:p1:aprobar:null:null"
        vm.dispatch(ForumQueueIntent.Confirm)
        advanceUntilIdle()
        assertIs<UiAsync.Error>(vm.state.value.feedback)
        assertIs<UiAsync.Success<*>>(vm.state.value.items)
    }

    @Test
    fun switchingToHiddenCommentsAsksForTheCommentState() = runTest {
        val repo = FakeControlV1Repository()
        val vm = ForumQueueViewModel(dispatchers(), repo)
        advanceUntilIdle()
        vm.dispatch(ForumQueueIntent.SetType(ForumContentType.COMMENT))
        vm.dispatch(ForumQueueIntent.SetQueue(ForumQueueState.HIDDEN))
        advanceUntilIdle()
        assertTrue("forumQueue:comentario:oculto:null" in repo.calls, repo.calls.toString())
    }

    @Test
    fun theChannelSlugFollowsTheNameUntilSomeoneEditsIt() = runTest {
        val repo = FakeControlV1Repository()
        val vm = ForumChannelsViewModel(dispatchers(), repo)
        advanceUntilIdle()
        vm.dispatch(ForumChannelsIntent.New)
        vm.dispatch(ForumChannelsIntent.Update(ForumChannelDraft.EMPTY.copy(name = "Oposiciones Madrid")))
        advanceUntilIdle()
        assertEquals("oposiciones-madrid", vm.state.value.draft.slug)
        vm.dispatch(ForumChannelsIntent.Update(vm.state.value.draft.copy(slug = "madrid"), slugEdited = true))
        advanceUntilIdle()
        vm.dispatch(ForumChannelsIntent.Update(vm.state.value.draft.copy(name = "Otro nombre")))
        advanceUntilIdle()
        assertEquals("madrid", vm.state.value.draft.slug)
    }

    @Test
    fun anInvalidChannelIsNotSentAndAValidOneIsCreated() = runTest {
        val repo = FakeControlV1Repository()
        val vm = ForumChannelsViewModel(dispatchers(), repo)
        advanceUntilIdle()
        vm.dispatch(ForumChannelsIntent.New)
        vm.dispatch(ForumChannelsIntent.Save)
        advanceUntilIdle()
        assertTrue(vm.state.value.errors.isNotEmpty())
        assertTrue(repo.calls.none { it.startsWith("createForumChannel") })
        vm.dispatch(ForumChannelsIntent.Update(ForumChannelDraft.EMPTY.copy(name = "General")))
        vm.dispatch(ForumChannelsIntent.Save)
        advanceUntilIdle()
        assertTrue("createForumChannel:general" in repo.calls, repo.calls.toString())
        assertFalse(vm.state.value.editing)
    }

    @Test
    fun editingAnExistingChannelUpdatesItInsteadOfCreating() = runTest {
        val repo = FakeControlV1Repository().apply {
            channels = listOf(ForumChannel("c1", "General", "general", null, true, 1, null, false))
        }
        val vm = ForumChannelsViewModel(dispatchers(), repo)
        advanceUntilIdle()
        vm.dispatch(ForumChannelsIntent.Select("c1"))
        advanceUntilIdle()
        vm.dispatch(ForumChannelsIntent.Update(vm.state.value.draft.copy(readOnly = true)))
        vm.dispatch(ForumChannelsIntent.Save)
        advanceUntilIdle()
        assertTrue("updateForumChannel:c1:general" in repo.calls, repo.calls.toString())
    }

    @Test
    fun blockPatternsAreValidatedAddedAndRemovedOnlyAfterConfirmation() = runTest {
        val repo = FakeControlV1Repository().apply { blocks = listOf("casino") }
        val vm = ForumBlocksViewModel(dispatchers(), repo)
        advanceUntilIdle()
        vm.dispatch(ForumBlocksIntent.UpdatePattern("a"))
        vm.dispatch(ForumBlocksIntent.Add)
        advanceUntilIdle()
        assertNotNull(vm.state.value.patternError)
        assertTrue(repo.calls.none { it.startsWith("addForumBlock") })
        vm.dispatch(ForumBlocksIntent.UpdatePattern("  APUESTAS "))
        vm.dispatch(ForumBlocksIntent.Add)
        advanceUntilIdle()
        assertTrue("addForumBlock:apuestas" in repo.calls)
        vm.dispatch(ForumBlocksIntent.RequestRemove("casino"))
        advanceUntilIdle()
        assertTrue(repo.calls.none { it.startsWith("removeForumBlock") })
        vm.dispatch(ForumBlocksIntent.ConfirmRemove)
        advanceUntilIdle()
        assertTrue("removeForumBlock:casino" in repo.calls)
    }

    @Test
    fun liftingARestrictionNeedsConfirmation() = runTest {
        val repo = FakeControlV1Repository().apply {
            restrictions = listOf(ForumRestriction("u1", "marta", "2026-11-01T00:00:00Z", "spam", null))
        }
        val vm = ForumBlocksViewModel(dispatchers(), repo)
        advanceUntilIdle()
        vm.dispatch(ForumBlocksIntent.RequestLift("u1"))
        advanceUntilIdle()
        assertTrue(repo.calls.none { it.startsWith("liftForumRestriction") })
        vm.dispatch(ForumBlocksIntent.ConfirmLift)
        advanceUntilIdle()
        assertTrue("liftForumRestriction:u1" in repo.calls)
        assertEquals(UiAsync.Empty, vm.state.value.restrictions)
    }

    @Test
    fun aMissingServerFunctionShowsAnErrorInThatListOnly() = runTest {
        val repo = FakeControlV1Repository().apply { failNext = "forumBlockList" }
        val vm = ForumBlocksViewModel(dispatchers(), repo)
        advanceUntilIdle()
        assertIs<UiAsync.Error>(vm.state.value.patterns)
        assertIs<UiAsync.Empty>(vm.state.value.restrictions)
    }

    // ------------------------------------------------------------------------------------------------ economía

    private fun exam(id: String, communityId: String? = "c1") = ExamEconomy(
        id = id, code = "EX-$id", title = "Examen $id", examType = "inmersivo", isOfficial = true, isPublished = false,
        communityId = communityId, communityName = communityId?.let { "Comunidad de Madrid" }, sellableWithTickets = false,
        priceTickets = null, accessDays = 30,
    )

    private class FakeCommunities(private val list: List<Community> = emptyList()) : CommunitiesRepository {
        override fun observeCommunities(): Flow<List<Community>> = flowOf(list)
        override suspend fun refreshCommunities() = com.oposicontrol.admin.core.common.result.AppResult.Success(list)
        override suspend fun upsertCommunity(community: Community) = com.oposicontrol.admin.core.common.result.AppResult.Success(community)
        override suspend fun deleteCommunity(communityId: String) = com.oposicontrol.admin.core.common.result.AppResult.Success(Unit)
    }

    @Test
    fun theEconomyFormRefusesASellableExamWithoutPriceOrAnyExamWithoutCommunity() = runTest {
        val repo = FakeControlV1Repository().apply { economy = ExamEconomyPage(listOf(exam("e1")), 1, 25, 0) }
        val vm = ExamEconomyViewModel(dispatchers(), repo, FakeCommunities())
        advanceUntilIdle()
        vm.dispatch(ExamEconomyIntent.Select("e1"))
        vm.dispatch(ExamEconomyIntent.Update(ExamEconomyDraft(sellable = true, price = "", days = "30", communityId = "c1")))
        vm.dispatch(ExamEconomyIntent.Save)
        advanceUntilIdle()
        assertTrue("price" in vm.state.value.errors)
        vm.dispatch(ExamEconomyIntent.Update(ExamEconomyDraft(sellable = false, price = "", days = "30", communityId = null)))
        vm.dispatch(ExamEconomyIntent.Save)
        advanceUntilIdle()
        assertTrue("community" in vm.state.value.errors)
        assertTrue(repo.calls.none { it.startsWith("saveExamEconomy") }, repo.calls.toString())
    }

    @Test
    fun aValidEconomyDraftIsSavedAndTheRowUpdates() = runTest {
        val repo = FakeControlV1Repository().apply { economy = ExamEconomyPage(listOf(exam("e1")), 1, 25, 0) }
        val vm = ExamEconomyViewModel(dispatchers(), repo, FakeCommunities())
        advanceUntilIdle()
        vm.dispatch(ExamEconomyIntent.Select("e1"))
        vm.dispatch(ExamEconomyIntent.Update(ExamEconomyDraft(true, "1300", "30", "c1")))
        vm.dispatch(ExamEconomyIntent.Save)
        advanceUntilIdle()
        assertTrue("saveExamEconomy:e1:true:1300:30:c1" in repo.calls)
        val saved = assertIs<UiAsync.Success<List<ExamEconomy>>>(vm.state.value.exams).value.single()
        assertEquals(1300, saved.priceTickets)
        assertTrue(saved.sellableWithTickets)
    }

    @Test
    fun theEconomyPagerNeverPassesTheEnds() = runTest {
        val repo = FakeControlV1Repository().apply { economy = ExamEconomyPage(listOf(exam("e1")), 30, 25, 0) }
        val vm = ExamEconomyViewModel(dispatchers(), repo, FakeCommunities())
        advanceUntilIdle()
        vm.dispatch(ExamEconomyIntent.PreviousPage)
        advanceUntilIdle()
        assertEquals(0, vm.state.value.offset)
        vm.dispatch(ExamEconomyIntent.NextPage)
        advanceUntilIdle()
        assertEquals(25, vm.state.value.offset)
        vm.dispatch(ExamEconomyIntent.NextPage)
        advanceUntilIdle()
        assertEquals(25, vm.state.value.offset, "no hay página tras la última")
    }

    // ------------------------------------------------------------------------------------------------ pedidos

    private fun sampleOrder(id: String, state: String = "en_revision") = OrderItem(
        id = id, state = state, provider = "google_play", productCode = "pack_100", productType = "pack", tickets = 100,
        priceCents = 100, currency = "EUR", transactionId = "GPA.1", userId = "u1", userHandle = "pepe",
        createdAt = "2026-10-17T10:00:00Z", paidAt = null, expiresAt = null, refund = null,
    )

    @Test
    fun ordersOpenOnTheReviewQueueAndTheNoteIsValidatedAndAudited() = runTest {
        val repo = FakeControlV1Repository().apply {
            orders = OrderPage(listOf(sampleOrder("o1")), 1, 25, 0)
            reviewCount = 1
        }
        val vm = OrdersViewModel(dispatchers(), repo)
        advanceUntilIdle()
        assertTrue("orders:en_revision:0" in repo.calls)
        assertEquals(1, vm.state.value.reviewCount)
        vm.dispatch(OrdersIntent.Select("o1"))
        vm.dispatch(OrdersIntent.UpdateNote("x"))
        vm.dispatch(OrdersIntent.SaveNote)
        advanceUntilIdle()
        assertNotNull(vm.state.value.noteError)
        assertTrue(repo.calls.none { it.startsWith("addOrderNote") })
        vm.dispatch(OrdersIntent.UpdateNote("Verificado con Google Play"))
        vm.dispatch(OrdersIntent.SaveNote)
        advanceUntilIdle()
        assertTrue("addOrderNote:o1:Verificado con Google Play" in repo.calls)
        assertEquals("", vm.state.value.note)
    }

    @Test
    fun anUnknownOrderFilterIsIgnoredAndTheFilterResetsThePage() = runTest {
        val repo = FakeControlV1Repository().apply { orders = OrderPage(listOf(sampleOrder("o1")), 60, 25, 0) }
        val vm = OrdersViewModel(dispatchers(), repo)
        advanceUntilIdle()
        vm.dispatch(OrdersIntent.NextPage)
        advanceUntilIdle()
        assertEquals(25, vm.state.value.offset)
        vm.dispatch(OrdersIntent.SetFilter("hackeado"))
        advanceUntilIdle()
        assertEquals("en_revision", vm.state.value.filter)
        vm.dispatch(OrdersIntent.SetFilter(null))
        advanceUntilIdle()
        assertEquals(0, vm.state.value.offset)
        assertNull(vm.state.value.filter)
    }

    // ------------------------------------------------------------------------------------------------ alertas

    @Test
    fun alertsOnlyReadAndNeverCallAnythingElse() = runTest {
        val repo = FakeControlV1Repository().apply {
            fast = FastWindowPage(listOf(FastWindow("u1", "veloz", "Liga 50", 50, 420, null)), 1, 25, 0, 1000)
        }
        val vm = AlertsViewModel(dispatchers(), repo)
        advanceUntilIdle()
        assertEquals(1000, vm.state.value.thresholdMs)
        assertTrue(repo.calls.all { it.startsWith("fastWindows") || it == "forumCounterDrift" }, repo.calls.toString())
        assertEquals(UiAsync.Empty, vm.state.value.drift)
    }
}
