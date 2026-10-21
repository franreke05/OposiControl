package com.oposicontrol.admin.features.exams.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.OfficialQuestion
import com.oposicontrol.admin.domain.model.OfficialQuestionDraft
import com.oposicontrol.admin.domain.model.OfficialQuestionValidator
import com.oposicontrol.admin.domain.model.OfficialQuestions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ExamOfficialQuestionsViewModelTest {
    private fun q(id: String, pos: Int, reserve: Int? = null, annulled: Boolean = false, demo: Boolean = false) = OfficialQuestion(
        questionId = id, position = pos, title = "Pregunta $pos", annulled = annulled, annulmentReason = null,
        isReserve = reserve != null, reserveOrder = reserve, isDemo = demo,
    )

    private fun data(vararg qs: OfficialQuestion, reserves: Boolean = true) = OfficialQuestions("e1", qs.toList(), reserves, null)

    private fun TestScope.vm(repo: ExamsFakeControlRepo) =
        ExamOfficialQuestionsViewModel(TestDispatcherProvider(StandardTestDispatcher(testScheduler)), repo)

    private suspend fun TestScope.loaded(repo: ExamsFakeControlRepo): ExamOfficialQuestionsViewModel {
        val vm = vm(repo)
        vm.dispatch(OfficialQuestionsIntent.Load("e1"))
        advanceUntilIdle()
        return vm
    }

    @Test
    fun loadingListsTheQuestionsWithTheirMarks() = runTest {
        val repo = ExamsFakeControlRepo(official = data(q("a", 1), q("b", 2, annulled = true), q("r", 3, reserve = 1)))
        val vm = loaded(repo)
        val d = vm.state.value.current!!
        assertEquals(1, d.mainAnnulled)
        assertEquals(1, d.reserves.size)
    }

    @Test
    fun anEmptyExamIsEmptyNotAnError() = runTest {
        val vm = loaded(ExamsFakeControlRepo(official = data()))
        assertEquals(UiAsync.Empty, vm.state.value.data)
    }

    @Test
    fun markingAReserveProposesTheNextFreeOrderAndClearingItClearsTheOrder() = runTest {
        val repo = ExamsFakeControlRepo(official = data(q("a", 1), q("r1", 2, reserve = 1), q("r2", 3, reserve = 2)))
        val vm = loaded(repo)
        vm.dispatch(OfficialQuestionsIntent.Select("a"))
        vm.dispatch(OfficialQuestionsIntent.SetReserve(true))
        advanceUntilIdle()
        assertEquals("3", vm.state.value.draft?.order)
        vm.dispatch(OfficialQuestionsIntent.SetReserve(false))
        advanceUntilIdle()
        assertEquals("", vm.state.value.draft?.order)
    }

    @Test
    fun theOrderCannotBeTypedOnAMainQuestionAndMustBeInRange() = runTest {
        val repo = ExamsFakeControlRepo(official = data(q("a", 1), q("r1", 2, reserve = 1)))
        val vm = loaded(repo)
        vm.dispatch(OfficialQuestionsIntent.Select("a"))
        vm.dispatch(OfficialQuestionsIntent.SetOrder("5"))
        advanceUntilIdle()
        assertEquals("", vm.state.value.draft?.order, "una principal no lleva orden")
        vm.dispatch(OfficialQuestionsIntent.SetReserve(true))
        vm.dispatch(OfficialQuestionsIntent.SetOrder("11"))
        vm.dispatch(OfficialQuestionsIntent.Save)
        advanceUntilIdle()
        assertTrue("order" in vm.state.value.errors)
        assertTrue(repo.questionSaves.isEmpty())
    }

    @Test
    fun anExistingOrCleanedOrderIsAcceptedBecauseTheServerInsertsAndShifts() = runTest {
        val repo = ExamsFakeControlRepo(official = data(q("a", 1), q("r1", 2, reserve = 1)))
        val vm = loaded(repo)
        vm.dispatch(OfficialQuestionsIntent.Select("a"))
        vm.dispatch(OfficialQuestionsIntent.SetReserve(true))
        vm.dispatch(OfficialQuestionsIntent.SetOrder("1"))
        vm.dispatch(OfficialQuestionsIntent.Save)
        advanceUntilIdle()
        assertTrue(vm.state.value.errors.isEmpty(), vm.state.value.errors.toString())
        assertEquals(1, repo.questionSaves.size)
        assertEquals(OfficialQuestionValidator.check(OfficialQuestionDraft(false, "", true, ""), q("a", 1), emptyList()), emptyMap())
    }

    @Test
    fun aDemoQuestionCannotBeAnnulledOrReserve() = runTest {
        val repo = ExamsFakeControlRepo(official = data(q("d", 1, demo = true)))
        val vm = loaded(repo)
        vm.dispatch(OfficialQuestionsIntent.Select("d"))
        vm.dispatch(OfficialQuestionsIntent.SetAnnulled(true))
        vm.dispatch(OfficialQuestionsIntent.Save)
        advanceUntilIdle()
        assertTrue("demo" in vm.state.value.errors)
        assertTrue(repo.questionSaves.isEmpty())
    }

    @Test
    fun annullingKeepsTheReasonAndUnannullingDropsIt() = runTest {
        val repo = ExamsFakeControlRepo(official = data(q("a", 1)))
        val vm = loaded(repo)
        vm.dispatch(OfficialQuestionsIntent.Select("a"))
        vm.dispatch(OfficialQuestionsIntent.SetAnnulled(true))
        vm.dispatch(OfficialQuestionsIntent.SetReason("Tribunal"))
        advanceUntilIdle()
        assertEquals("Tribunal", vm.state.value.draft?.reason)
        vm.dispatch(OfficialQuestionsIntent.SetAnnulled(false))
        advanceUntilIdle()
        assertEquals("", vm.state.value.draft?.reason)
    }

    @Test
    fun savingSendsTheMarksAndRefreshesTheListFromTheServerAnswer() = runTest {
        val repo = ExamsFakeControlRepo(official = data(q("a", 1), q("b", 2)))
        val vm = loaded(repo)
        vm.dispatch(OfficialQuestionsIntent.Select("b"))
        vm.dispatch(OfficialQuestionsIntent.SetAnnulled(true))
        vm.dispatch(OfficialQuestionsIntent.SetReason("Error del enunciado"))
        vm.dispatch(OfficialQuestionsIntent.Save)
        advanceUntilIdle()
        assertEquals(listOf("b|true|Error del enunciado|false|"), repo.questionSaves)
        assertTrue(vm.state.value.current!!.questions.first { it.questionId == "b" }.annulled)
        assertIs<UiAsync.Success<Unit>>(vm.state.value.save)
    }

    @Test
    fun anUnprovisionedServerFunctionShowsTheMessageAndChangesNothing() = runTest {
        val repo = ExamsFakeControlRepo(official = data(q("a", 1))).apply { failQuestionSave = true }
        val vm = loaded(repo)
        vm.dispatch(OfficialQuestionsIntent.Select("a"))
        vm.dispatch(OfficialQuestionsIntent.SetAnnulled(true))
        vm.dispatch(OfficialQuestionsIntent.SetReason("Anulada por el tribunal"))
        vm.dispatch(OfficialQuestionsIntent.Save)
        advanceUntilIdle()
        assertIs<UiAsync.Error>(vm.state.value.save)
        assertFalse(vm.state.value.current!!.questions.single().annulled)
    }

    /** Decisión del propietario 2026-10-20: anular sin motivo no se envía; el formulario lo pide en el campo. */
    @Test
    fun annullingWithoutAReasonAsksForItAndSendsNothing() = runTest {
        val repo = ExamsFakeControlRepo(official = data(q("a", 1)))
        val vm = loaded(repo)
        vm.dispatch(OfficialQuestionsIntent.Select("a"))
        vm.dispatch(OfficialQuestionsIntent.SetAnnulled(true))
        vm.dispatch(OfficialQuestionsIntent.SetReason("   "))
        vm.dispatch(OfficialQuestionsIntent.Save)
        advanceUntilIdle()
        assertEquals(OfficialQuestionValidator.REASON_REQUIRED, vm.state.value.errors["reason"])
        assertIs<UiAsync.Idle>(vm.state.value.save)
        assertFalse(vm.state.value.current!!.questions.single().annulled, "nada cambia")
    }

    @Test
    fun reloadingTheSameExamDoesNotDiscardWhatIsOpen() = runTest {
        val repo = ExamsFakeControlRepo(official = data(q("a", 1)))
        val vm = loaded(repo)
        vm.dispatch(OfficialQuestionsIntent.Select("a"))
        vm.dispatch(OfficialQuestionsIntent.Load("e1"))
        advanceUntilIdle()
        assertEquals("a", vm.state.value.selectedId)
    }
}
