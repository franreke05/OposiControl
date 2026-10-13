package com.oposicontrol.admin.features.store.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.PlanBenefit
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

/** Planes y beneficios: las reglas (sin Compose) y el comportamiento del ViewModel contra un repositorio en memoria. */
@OptIn(ExperimentalCoroutinesApi::class)
class CommercialPlansTest {

    private fun TestScope.viewModel(repository: FakeCatalogRepository) = CommercialPlansViewModel(
        dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        repository = repository,
        analyticsTracker = NoOpAnalyticsTracker,
    )

    // ---------- reglas ----------

    @Test
    fun `a plan needs a code when new, a name, a level in range and a position`() {
        val valid = PlanDraft(code = "Premium", name = "Premium", level = 3, position = "4")
        assertNull(planProblem(valid, isNew = true, existingCodes = emptySet()))
        assertEquals("El plan necesita un código.", planProblem(valid.copy(code = " "), isNew = true, existingCodes = emptySet()))
        // El servidor guarda el código en minúsculas: «Premium» choca con «premium».
        assertEquals("Ya existe un plan con ese código.", planProblem(valid, isNew = true, existingCodes = setOf("premium")))
        assertEquals("El plan necesita un nombre.", planProblem(valid.copy(name = ""), isNew = false, existingCodes = emptySet()))
        assertNotNull(planProblem(valid.copy(level = 4), isNew = false, existingCodes = emptySet()))
        assertNotNull(planProblem(valid.copy(position = "-1"), isNew = false, existingCodes = emptySet()))
    }

    @Test
    fun `a visible plan must be active, as the server requires`() {
        val draft = PlanDraft(code = "p", name = "P", active = false, visible = true)
        assertNotNull(planProblem(draft, isNew = false, existingCodes = emptySet()))
        assertNull(planProblem(draft.copy(visible = false), isNew = false, existingCodes = emptySet()))
    }

    @Test
    fun `the plan that is sent keeps its id, its code and its benefits`() {
        val base = samplePlans()[1]
        val plan = assertNotNull(base.toDraft().copy(code = "otro", name = " Razonado+ ", description = " ").toPlan(base))
        assertEquals(PLAN_PRO, plan.id)
        assertEquals("razonado", plan.code)
        assertEquals("Razonado+", plan.name)
        assertNull(plan.description)
        assertEquals(2, plan.benefits.size)
        assertEquals("premium", assertNotNull(PlanDraft(code = " Premium ", name = "P").toPlan(null)).code)
    }

    @Test
    fun `a benefit carries exactly one value, the one of its kind`() {
        val integer = assertNotNull(BenefitDraft(code = " tests ", kind = BenefitKind.INTEGER, text = " 10 ").toBenefit("plan"))
        assertEquals("tests", integer.code)
        assertEquals(10, integer.integerValue)
        assertEquals(1, integer.filledValueCount)

        val numeric = assertNotNull(BenefitDraft(code = "d", kind = BenefitKind.NUMERIC, text = "0,15").toBenefit("plan"))
        assertEquals(0.15, numeric.numericValue)
        assertEquals(1, numeric.filledValueCount)

        // Un booleano en «No» sigue siendo un valor: `false` no es «sin valor».
        val no = assertNotNull(BenefitDraft(code = "b", kind = BenefitKind.BOOLEAN, booleanValue = false, text = "resto").toBenefit("plan"))
        assertEquals(false, no.booleanValue)
        assertEquals(1, no.filledValueCount)

        assertEquals("hola", assertNotNull(BenefitDraft(code = "t", kind = BenefitKind.TEXT, text = " hola ").toBenefit("plan")).textValue)
        assertNull(BenefitDraft(code = "t", kind = BenefitKind.TEXT, text = "  ").toBenefit("plan"))
        assertNull(BenefitDraft(code = "i", kind = BenefitKind.INTEGER, text = "1,5").toBenefit("plan"))
    }

    @Test
    fun `benefit validation says the first thing that is wrong`() {
        val others = listOf(PlanBenefit("b1", "plan", "tests", integerValue = 10))
        assertEquals("El beneficio necesita un código.", benefitProblem(BenefitDraft(), others))
        assertEquals("Este plan ya tiene un beneficio con ese código.", benefitProblem(BenefitDraft(code = "tests"), others))
        assertNotNull(benefitProblem(BenefitDraft(code = "con espacio"), others))
        assertEquals("El valor debe ser un número entero.", benefitProblem(BenefitDraft(code = "n", kind = BenefitKind.INTEGER, text = "x"), others))
        assertNotNull(benefitProblem(BenefitDraft(code = "n", kind = BenefitKind.NUMERIC, text = "1.2.3"), others))
        assertEquals("El beneficio necesita un valor.", benefitProblem(BenefitDraft(code = "n", kind = BenefitKind.TEXT), others))
        assertNull(benefitProblem(BenefitDraft(code = "n", kind = BenefitKind.BOOLEAN), others))
    }

    @Test
    fun `a saved benefit knows its kind, and one without a single value is flagged instead of guessed`() {
        assertEquals(BenefitKind.BOOLEAN, PlanBenefit("1", "p", "a", booleanValue = false).kind())
        assertEquals(BenefitKind.NUMERIC, PlanBenefit("1", "p", "a", numericValue = 1.5).kind())
        assertEquals("1,5", benefitValueLabel(PlanBenefit("1", "p", "a", numericValue = 1.5)))
        assertEquals("10", benefitValueLabel(PlanBenefit("1", "p", "a", numericValue = 10.0)))
        assertEquals("No", benefitValueLabel(PlanBenefit("1", "p", "a", booleanValue = false)))

        val none = PlanBenefit("1", "p", "a")
        assertNull(none.kind())
        assertEquals("Sin valor", benefitValueLabel(none))
        val two = PlanBenefit("1", "p", "a", booleanValue = true, integerValue = 3)
        assertEquals("Valor ambiguo", benefitValueLabel(two))
        // El tipo queda libre sólo cuando no se pudo deducir; si se dedujo, no se cambia.
        assertFalse(two.toDraft().kindLocked)
        assertTrue(PlanBenefit("1", "p", "a", integerValue = 3).toDraft().kindLocked)
    }

    @Test
    fun `plans filter by what the user would see`() {
        val plans = samplePlans() + samplePlans()[0].copy(id = "h", code = "oculto", visible = false) +
            samplePlans()[0].copy(id = "i", code = "inactivo", active = false, visible = false)
        assertEquals(listOf("gratuito", "razonado"), plans.filter { planMatches(it, "", PLAN_FILTER_ACTIVE) }.map { it.code })
        assertEquals(listOf("oculto"), plans.filter { planMatches(it, "", PLAN_FILTER_HIDDEN) }.map { it.code })
        assertEquals(listOf("inactivo"), plans.filter { planMatches(it, "", PLAN_FILTER_INACTIVE) }.map { it.code })
        assertEquals(listOf("razonado"), plans.filter { planMatches(it, "RAZ", null) }.map { it.code })
    }

    // ---------- ViewModel ----------

    @Test
    fun `opening loads the plans with their benefits and opens the first`() = runTest {
        val vm = viewModel(FakeCatalogRepository())
        advanceUntilIdle()

        assertEquals(PLAN_FREE, vm.state.value.selectedId)
        assertEquals(2, vm.state.value.allPlans.size)
        assertEquals(2, vm.state.value.allPlans[1].benefits.size)
    }

    @Test
    fun `no plans is empty and a failed read is an error`() = runTest {
        val empty = viewModel(FakeCatalogRepository(plans = emptyList()))
        advanceUntilIdle()
        assertIs<UiAsync.Empty>(empty.state.value.plans)

        val down = viewModel(FakeCatalogRepository().apply { plansFailure = AppError.Network("sin red") })
        advanceUntilIdle()
        assertIs<UiAsync.Error>(down.state.value.plans)
    }

    @Test
    fun `a new plan is sent with a blank id and a lower-case code, then stays open`() = runTest {
        val repository = FakeCatalogRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.dispatch(CommercialPlansIntent.Open(null))
        vm.dispatch(CommercialPlansIntent.UpdateDraft(PlanDraft(code = "Premium", name = "Premium", level = 3, position = "3")))
        vm.dispatch(CommercialPlansIntent.Save)
        advanceUntilIdle()

        val sent = repository.savedPlans.single()
        assertEquals("", sent.id)
        assertEquals("premium", sent.code)
        assertEquals(3, sent.level)
        assertFalse(vm.state.value.creatingNew)
        assertEquals("premium", vm.state.value.selected?.code)
        assertEquals(3, vm.state.value.allPlans.size)
    }

    @Test
    fun `a visible but inactive plan is not sent`() = runTest {
        val repository = FakeCatalogRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.dispatch(CommercialPlansIntent.UpdateDraft(vm.state.value.draft.copy(active = false)))
        vm.dispatch(CommercialPlansIntent.Save)
        advanceUntilIdle()

        assertTrue(repository.savedPlans.isEmpty())
        assertIs<UiAsync.Error>(vm.state.value.actionState)
    }

    @Test
    fun `leaving a plan with unsaved changes asks first`() = runTest {
        val vm = viewModel(FakeCatalogRepository())
        advanceUntilIdle()
        vm.dispatch(CommercialPlansIntent.UpdateDraft(vm.state.value.draft.copy(name = "A medias")))
        vm.dispatch(CommercialPlansIntent.Open(PLAN_PRO))
        advanceUntilIdle()

        assertEquals(PLAN_FREE, vm.state.value.selectedId)
        assertEquals(PendingPlanOpen(PLAN_PRO), vm.state.value.pendingOpen)

        vm.dispatch(CommercialPlansIntent.ConfirmOpen)
        advanceUntilIdle()
        assertEquals(PLAN_PRO, vm.state.value.selectedId)
        assertNull(vm.state.value.pendingOpen)
    }

    @Test
    fun `a new benefit is sent to its plan with one value and joins the plan in code order`() = runTest {
        val repository = FakeCatalogRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()
        vm.dispatch(CommercialPlansIntent.Open(PLAN_PRO))
        vm.dispatch(CommercialPlansIntent.SelectTab(PlanTab.BENEFITS))
        vm.dispatch(CommercialPlansIntent.EditBenefit(null))
        vm.dispatch(CommercialPlansIntent.UpdateBenefit(BenefitDraft(code = "examenes_mensuales", kind = BenefitKind.INTEGER, text = "5")))
        vm.dispatch(CommercialPlansIntent.SaveBenefit)
        advanceUntilIdle()

        assertTrue(repository.savedBenefits.isEmpty())
        assertIs<UiAsync.Error>(vm.state.value.benefitActionState)

        vm.dispatch(CommercialPlansIntent.UpdateBenefit(BenefitDraft(code = "descuento_market", kind = BenefitKind.NUMERIC, text = "0,15")))
        vm.dispatch(CommercialPlansIntent.SaveBenefit)
        advanceUntilIdle()

        val (planId, benefit) = repository.savedBenefits.single()
        assertEquals(PLAN_PRO, planId)
        assertEquals("", benefit.id)
        assertEquals(1, benefit.filledValueCount)
        assertEquals(
            listOf("descuento_market", "examenes_mensuales", "sin_publicidad"),
            vm.state.value.selected?.benefits?.map { it.code },
        )
        assertNull(vm.state.value.benefitDraft)
        assertEquals(PlanTab.BENEFITS, vm.state.value.tab)
    }

    @Test
    fun `editing a benefit changes its value and keeps its id, code and kind`() = runTest {
        val repository = FakeCatalogRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()
        vm.dispatch(CommercialPlansIntent.Open(PLAN_PRO))
        vm.dispatch(CommercialPlansIntent.EditBenefit("b1"))
        advanceUntilIdle()
        val draft = assertNotNull(vm.state.value.benefitDraft)
        assertTrue(draft.kindLocked)
        assertEquals("10", draft.text)

        vm.dispatch(CommercialPlansIntent.UpdateBenefit(draft.copy(text = "15")))
        vm.dispatch(CommercialPlansIntent.SaveBenefit)
        advanceUntilIdle()

        val benefit = repository.savedBenefits.single().second
        assertEquals("b1", benefit.id)
        assertEquals("examenes_mensuales", benefit.code)
        assertEquals(15, benefit.integerValue)
        assertNull(benefit.booleanValue)
    }

    @Test
    fun `a refresh keeps the benefits tab and what is being typed`() = runTest {
        val repository = FakeCatalogRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()
        vm.dispatch(CommercialPlansIntent.Open(PLAN_PRO))
        vm.dispatch(CommercialPlansIntent.SelectTab(PlanTab.BENEFITS))
        vm.dispatch(CommercialPlansIntent.Refresh)
        advanceUntilIdle()
        assertEquals(PlanTab.BENEFITS, vm.state.value.tab)

        vm.dispatch(CommercialPlansIntent.UpdateDraft(vm.state.value.draft.copy(name = "A medias")))
        vm.dispatch(CommercialPlansIntent.Refresh)
        advanceUntilIdle()
        assertEquals("A medias", vm.state.value.draft.name)
        assertEquals(3, repository.planReads)
    }

    @Test
    fun `a refresh without local changes shows what another administrator saved`() = runTest {
        val repository = FakeCatalogRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()
        repository.plans = repository.plans.map { if (it.id == PLAN_FREE) it.copy(name = "Gratis") else it }
        vm.dispatch(CommercialPlansIntent.Refresh)
        advanceUntilIdle()

        assertEquals("Gratis", vm.state.value.draft.name)
        assertFalse(vm.state.value.dirty)

        repository.plansFailure = AppError.Network("sin red")
        vm.dispatch(CommercialPlansIntent.Refresh)
        advanceUntilIdle()
        assertIs<UiAsync.Success<*>>(vm.state.value.plans)
        assertTrue(vm.state.value.refreshFailed)
    }

    @Test
    fun `deleting a plan asks first, removes it and opens the next one`() = runTest {
        val repository = FakeCatalogRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()
        vm.dispatch(CommercialPlansIntent.Open(PLAN_PRO))
        vm.dispatch(CommercialPlansIntent.AskDeletePlan)
        advanceUntilIdle()
        assertEquals(PendingPlanDelete(PLAN_PRO), vm.state.value.pendingDelete)
        assertTrue(repository.deletedPlans.isEmpty(), "pedir confirmación no borra nada")

        vm.dispatch(CommercialPlansIntent.ConfirmDelete)
        vm.dispatch(CommercialPlansIntent.ConfirmDelete)
        advanceUntilIdle()

        assertEquals(listOf(PLAN_PRO), repository.deletedPlans)
        assertEquals(listOf(PLAN_FREE), vm.state.value.allPlans.map { it.id })
        assertEquals(PLAN_FREE, vm.state.value.selectedId)
        assertFalse(vm.state.value.deleting)
    }

    @Test
    fun `a plan the server refuses to delete stays with the server reason`() = runTest {
        val repository = FakeCatalogRepository().apply { deleteFailure = AppError.Validation("No se puede borrar: apuntan a este plan 1 productos.") }
        val vm = viewModel(repository)
        advanceUntilIdle()
        vm.dispatch(CommercialPlansIntent.Open(PLAN_PRO))
        vm.dispatch(CommercialPlansIntent.AskDeletePlan)
        vm.dispatch(CommercialPlansIntent.ConfirmDelete)
        advanceUntilIdle()

        assertEquals(2, vm.state.value.allPlans.size)
        assertTrue(assertIs<UiAsync.Error>(vm.state.value.actionState).error.message.contains("1 productos"))
    }

    @Test
    fun `deleting a benefit removes only that benefit`() = runTest {
        val repository = FakeCatalogRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()
        vm.dispatch(CommercialPlansIntent.Open(PLAN_PRO))
        vm.dispatch(CommercialPlansIntent.AskDeleteBenefit("no-existe"))
        advanceUntilIdle()
        assertNull(vm.state.value.pendingDelete)

        vm.dispatch(CommercialPlansIntent.AskDeleteBenefit("b1"))
        vm.dispatch(CommercialPlansIntent.ConfirmDelete)
        advanceUntilIdle()

        assertEquals(listOf(PLAN_PRO to "b1"), repository.deletedBenefits)
        assertEquals(listOf("b2"), vm.state.value.selected!!.benefits.map { it.id })
    }
}
