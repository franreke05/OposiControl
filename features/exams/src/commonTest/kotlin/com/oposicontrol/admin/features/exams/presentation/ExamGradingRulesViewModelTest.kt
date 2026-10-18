package com.oposicontrol.admin.features.exams.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.AnnulledRuleOption
import com.oposicontrol.admin.domain.model.ConfigEntry
import com.oposicontrol.admin.domain.model.ExamEconomy
import com.oposicontrol.admin.domain.model.ExamEconomyDraft
import com.oposicontrol.admin.domain.model.ExamEconomyPage
import com.oposicontrol.admin.domain.model.FastWindowPage
import com.oposicontrol.admin.domain.model.ForumAction
import com.oposicontrol.admin.domain.model.ForumChannel
import com.oposicontrol.admin.domain.model.ForumChannelDraft
import com.oposicontrol.admin.domain.model.ForumContentType
import com.oposicontrol.admin.domain.model.ForumCounterDrift
import com.oposicontrol.admin.domain.model.ForumQueuePage
import com.oposicontrol.admin.domain.model.ForumRestriction
import com.oposicontrol.admin.domain.model.GradingAuditEntry
import com.oposicontrol.admin.domain.model.GradingCheck
import com.oposicontrol.admin.domain.model.GradingRules
import com.oposicontrol.admin.domain.model.GradingSnapshot
import com.oposicontrol.admin.domain.model.GradingValidation
import com.oposicontrol.admin.domain.model.OrderItem
import com.oposicontrol.admin.domain.model.OrderPage
import com.oposicontrol.admin.domain.model.ReferralPage
import com.oposicontrol.admin.domain.model.ReferralRevokeResult
import com.oposicontrol.admin.domain.repository.ControlV1Repository
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class ExamGradingRulesViewModelTest {
    private fun rules(published: Boolean = false, official: Boolean = true, rational: Boolean = false) = GradingRules(
        examId = "e1", annulledRule = null, rationalPenalty = rational, fixedNumerator = 0, fixedDenominator = 1,
        isOfficial = official, isPublished = published, reserves = 3, annulledMain = 1,
        check = GradingCheck(problem = null, warnings = listOf("MORE_RESERVES_THAN_MAX"), effectiveRule = "REEMPLAZO_POR_RESERVA"),
    )

    private class Fake(var rules: GradingRules, var audit: List<GradingAuditEntry> = emptyList()) : ControlV1Repository {
        val saves = mutableListOf<String>()
        var failSave = false

        override suspend fun getGradingRules(examId: String) = AppResult.Success(rules)

        override suspend fun saveGradingRules(
            examId: String,
            annulledRule: String?,
            rationalPenalty: Boolean,
            numerator: Int,
            denominator: Int,
            reason: String?,
        ): AppResult<GradingRules> {
            saves += "$annulledRule|$rationalPenalty|$numerator/$denominator|$reason"
            if (failSave) return AppResult.Failure(AppError.Validation("rechazado por el servidor", "OFFICIAL_RULES_INVALID"))
            rules = rules.copy(annulledRule = annulledRule, rationalPenalty = rationalPenalty, fixedNumerator = numerator, fixedDenominator = denominator)
            audit = listOf(
                GradingAuditEntry("a1", "2026-10-18T10:00:00Z", "actor", reason, GradingSnapshot(null, false, 0, 1), GradingSnapshot(annulledRule, rationalPenalty, numerator, denominator)),
            ) + audit
            return AppResult.Success(rules)
        }

        override suspend fun getGradingAudit(examId: String) = AppResult.Success(audit)

        override suspend fun getConfig(group: String): AppResult<List<ConfigEntry>> = TODO()
        override suspend fun setConfig(key: String, value: Int): AppResult<ConfigEntry> = TODO()
        override suspend fun listReferrals(state: String?, cursor: String?): AppResult<ReferralPage> = TODO()
        override suspend fun revokeReferral(id: String, reason: String): AppResult<ReferralRevokeResult> = TODO()
        override suspend fun forumQueue(type: ForumContentType, state: String, cursor: String?): AppResult<ForumQueuePage> = TODO()
        override suspend fun moderateForum(type: ForumContentType, id: String, action: ForumAction, reason: String?, days: Int?): AppResult<Unit> = TODO()
        override suspend fun forumChannels(): AppResult<List<ForumChannel>> = TODO()
        override suspend fun createForumChannel(draft: ForumChannelDraft): AppResult<ForumChannel> = TODO()
        override suspend fun updateForumChannel(id: String, draft: ForumChannelDraft): AppResult<ForumChannel> = TODO()
        override suspend fun forumBlockList(): AppResult<List<String>> = TODO()
        override suspend fun addForumBlock(pattern: String): AppResult<Unit> = TODO()
        override suspend fun removeForumBlock(pattern: String): AppResult<Unit> = TODO()
        override suspend fun forumRestrictions(): AppResult<List<ForumRestriction>> = TODO()
        override suspend fun liftForumRestriction(userId: String): AppResult<Unit> = TODO()
        override suspend fun examEconomy(offset: Int, onlyWithoutCommunity: Boolean): AppResult<ExamEconomyPage> = TODO()
        override suspend fun saveExamEconomy(examId: String, draft: ExamEconomyDraft): AppResult<ExamEconomy> = TODO()
        override suspend fun orders(state: String?, offset: Int): AppResult<OrderPage> = TODO()
        override suspend fun ordersInReviewCount(): AppResult<Int> = TODO()
        override suspend fun order(id: String): AppResult<OrderItem> = TODO()
        override suspend fun addOrderNote(id: String, note: String): AppResult<Unit> = TODO()
        override suspend fun fastWindows(offset: Int): AppResult<FastWindowPage> = TODO()
        override suspend fun forumCounterDrift(): AppResult<List<ForumCounterDrift>> = TODO()
    }

    private fun TestScope.vm(repo: ControlV1Repository) =
        ExamGradingRulesViewModel(TestDispatcherProvider(StandardTestDispatcher(testScheduler)), repo)

    @Test
    fun loadingBuildsTheDraftFromTheServerAndShowsTheAudit() = runTest {
        val vm = vm(Fake(rules(), audit = listOf(GradingAuditEntry("a0", null, null, "x", null, null))))
        vm.dispatch(GradingRulesIntent.Load("e1"))
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals("0", s.draft?.numerator)
        assertFalse(s.isDirty)
        assertIs<UiAsync.Success<*>>(s.audit)
    }

    @Test
    fun turningRationalOnClearsTheFractionAndMakesTheFieldsInert() = runTest {
        val vm = vm(Fake(rules()))
        vm.dispatch(GradingRulesIntent.Load("e1"))
        advanceUntilIdle()
        vm.dispatch(GradingRulesIntent.SetNumerator("1"))
        vm.dispatch(GradingRulesIntent.SetDenominator("3"))
        advanceUntilIdle()
        assertEquals("1" to "3", vm.state.value.draft?.numerator to vm.state.value.draft?.denominator)
        vm.dispatch(GradingRulesIntent.SetRational(true))
        advanceUntilIdle()
        assertEquals("0" to "1", vm.state.value.draft?.numerator to vm.state.value.draft?.denominator)
        // con racional activo no se puede teclear una fracción
        vm.dispatch(GradingRulesIntent.SetNumerator("2"))
        advanceUntilIdle()
        assertEquals("0", vm.state.value.draft?.numerator)
    }

    @Test
    fun anInvalidFractionIsNotSent() = runTest {
        val repo = Fake(rules())
        val vm = vm(repo)
        vm.dispatch(GradingRulesIntent.Load("e1"))
        advanceUntilIdle()
        vm.dispatch(GradingRulesIntent.SetNumerator("5"))
        vm.dispatch(GradingRulesIntent.SetDenominator("3"))
        vm.dispatch(GradingRulesIntent.RequestSave)
        advanceUntilIdle()
        assertEquals(GradingValidation.Field.FRACTION, vm.state.value.validation?.field)
        assertTrue(repo.saves.isEmpty())
    }

    @Test
    fun aDraftExamSavesInOneStepAndRefreshesTheAudit() = runTest {
        val repo = Fake(rules())
        val vm = vm(repo)
        vm.dispatch(GradingRulesIntent.Load("e1"))
        advanceUntilIdle()
        vm.dispatch(GradingRulesIntent.SetRule(AnnulledRuleOption.NO_SCORE))
        vm.dispatch(GradingRulesIntent.SetRational(true))
        vm.dispatch(GradingRulesIntent.RequestSave)
        advanceUntilIdle()
        assertEquals(listOf("NO_PUNTUA|true|0/1|null"), repo.saves)
        assertFalse(vm.state.value.isDirty)
        assertIs<UiAsync.Success<Unit>>(vm.state.value.save)
        assertEquals(1, (vm.state.value.audit as UiAsync.Success).value.size)
    }

    @Test
    fun aPublishedExamNeedsAReasonThenAnExplicitConfirmation() = runTest {
        val repo = Fake(rules(published = true))
        val vm = vm(repo)
        vm.dispatch(GradingRulesIntent.Load("e1"))
        advanceUntilIdle()
        vm.dispatch(GradingRulesIntent.SetRational(true))
        vm.dispatch(GradingRulesIntent.RequestSave)
        advanceUntilIdle()
        assertEquals(GradingValidation.Field.REASON, vm.state.value.validation?.field)
        assertFalse(vm.state.value.confirming)

        vm.dispatch(GradingRulesIntent.SetReason("Criterio del tribunal"))
        vm.dispatch(GradingRulesIntent.RequestSave)
        advanceUntilIdle()
        assertTrue(vm.state.value.confirming)
        assertTrue(repo.saves.isEmpty(), "no se escribe hasta confirmar")

        vm.dispatch(GradingRulesIntent.DismissConfirm)
        advanceUntilIdle()
        vm.dispatch(GradingRulesIntent.ConfirmSave)
        advanceUntilIdle()
        assertTrue(repo.saves.isEmpty(), "confirmar sin diálogo abierto no escribe")

        vm.dispatch(GradingRulesIntent.RequestSave)
        vm.dispatch(GradingRulesIntent.ConfirmSave)
        advanceUntilIdle()
        assertEquals(listOf("null|true|0/1|Criterio del tribunal"), repo.saves)
    }

    @Test
    fun aServerRejectionKeepsTheDraftAndShowsTheError() = runTest {
        val repo = Fake(rules()).apply { failSave = true }
        val vm = vm(repo)
        vm.dispatch(GradingRulesIntent.Load("e1"))
        advanceUntilIdle()
        vm.dispatch(GradingRulesIntent.SetRule(AnnulledRuleOption.REPLACE))
        vm.dispatch(GradingRulesIntent.RequestSave)
        advanceUntilIdle()
        assertIs<UiAsync.Error>(vm.state.value.save)
        assertTrue(vm.state.value.isDirty, "el borrador sigue ahí para corregirlo")
        assertEquals("REEMPLAZO_POR_RESERVA", vm.state.value.draft?.annulledRule)
    }

    @Test
    fun discardingRestoresTheSavedValues() = runTest {
        val vm = vm(Fake(rules()))
        vm.dispatch(GradingRulesIntent.Load("e1"))
        advanceUntilIdle()
        vm.dispatch(GradingRulesIntent.SetRational(true))
        advanceUntilIdle()
        assertTrue(vm.state.value.isDirty)
        vm.dispatch(GradingRulesIntent.Discard)
        advanceUntilIdle()
        assertFalse(vm.state.value.isDirty)
        assertNull(vm.state.value.validation)
    }

    @Test
    fun reloadingTheSameExamKeepsWhatIsBeingTyped() = runTest {
        val vm = vm(Fake(rules()))
        vm.dispatch(GradingRulesIntent.Load("e1"))
        advanceUntilIdle()
        vm.dispatch(GradingRulesIntent.SetRational(true))
        advanceUntilIdle()
        vm.dispatch(GradingRulesIntent.Refresh)
        advanceUntilIdle()
        assertTrue(vm.state.value.draft?.rationalPenalty == true)
    }

    @Test
    fun theAuditTextDescribesOnlyWhatChanged() {
        val text = GradingChangeText.describe(GradingSnapshot(null, false, 0, 1), GradingSnapshot("NO_PUNTUA", true, 0, 1))
        assertTrue(text.contains("Anuladas") && text.contains("Penalización racional: no → sí"), text)
        assertFalse(text.contains("Fracción"), text)
        assertEquals("—", GradingChangeText.describe(null, null))
        assertEquals("Sin cambios visibles", GradingChangeText.describe(GradingSnapshot(null, false, 0, 1), GradingSnapshot(null, false, 0, 1)))
        assertNotNull(GradingChangeText.describe(GradingSnapshot(null, false, 1, 3), GradingSnapshot(null, false, 0, 1)))
    }
}
