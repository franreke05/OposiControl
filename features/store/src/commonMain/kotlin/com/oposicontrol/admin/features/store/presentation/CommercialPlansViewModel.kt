package com.oposicontrol.admin.features.store.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.CommercialPlan
import com.oposicontrol.admin.domain.repository.CommercialCatalogRepository

sealed interface CommercialPlansIntent {
    data object Refresh : CommercialPlansIntent

    /** Abre un plan (o, con `null`, un borrador nuevo). Si hay cambios sin guardar, pide confirmación antes. */
    data class Open(val planId: String?) : CommercialPlansIntent
    data object ConfirmOpen : CommercialPlansIntent
    data object DismissOpen : CommercialPlansIntent

    data class UpdateSearch(val value: String) : CommercialPlansIntent
    data class UpdateFilter(val filter: String?) : CommercialPlansIntent
    data class SetPage(val pageIndex: Int) : CommercialPlansIntent
    data class SelectTab(val tab: PlanTab) : CommercialPlansIntent

    /** Sustituye el borrador del plan por [draft]: la ficha manda el borrador ya cambiado. */
    data class UpdateDraft(val draft: PlanDraft) : CommercialPlansIntent
    data object Save : CommercialPlansIntent
    data object Discard : CommercialPlansIntent

    /** Abre el formulario de un beneficio existente o, con `null`, el de uno nuevo. */
    data class EditBenefit(val benefitId: String?) : CommercialPlansIntent
    data class UpdateBenefit(val draft: BenefitDraft) : CommercialPlansIntent
    data object SaveBenefit : CommercialPlansIntent
    data object CancelBenefit : CommercialPlansIntent
}

data class CommercialPlansState(
    /** Todos los planes descargados (el repositorio recorre las páginas del servidor). */
    val plans: UiAsync<List<CommercialPlan>> = UiAsync.Loading,
    val searchQuery: String = "",
    val statusFilter: String? = null,
    val pageIndex: Int = 0,
    val selectedId: String? = null,
    val creatingNew: Boolean = false,
    /**
     * Cuenta las veces que se ABRE otro registro (o un borrador nuevo). Es la clave del movimiento de entrada de la ficha:
     * cambia al abrir, no al guardar, para que crear un registro no haga «entrar» otra vez el formulario que ya se ve.
     */
    val openSeq: Int = 0,
    val tab: PlanTab = PlanTab.PLAN,
    val draft: PlanDraft = PlanDraft(),
    val benefitDraft: BenefitDraft? = null,
    /** Destino pendiente de confirmar cuando se intenta salir de una ficha con cambios. */
    val pendingOpen: PendingPlanOpen? = null,
    val actionState: UiAsync<Unit> = UiAsync.Idle,
    val benefitActionState: UiAsync<Unit> = UiAsync.Idle,
    /** Aviso que deja el último guardado (ver [planKeptByServer]). */
    val notice: String? = null,
    /** Se está releyendo un catálogo que ya estaba en pantalla: se sigue viendo mientras tanto. */
    val refreshing: Boolean = false,
    /** La última relectura falló: se conserva lo que ya se había leído, y se dice. */
    val refreshFailed: Boolean = false,
) {
    val allPlans: List<CommercialPlan> get() = (plans as? UiAsync.Success)?.value.orEmpty()
    val selected: CommercialPlan? get() = allPlans.firstOrNull { it.id == selectedId }
    val visiblePlans: List<CommercialPlan> get() = allPlans.filter { planMatches(it, searchQuery, statusFilter) }

    /** Hay algo en la ficha que no está en el servidor. */
    val dirty: Boolean
        get() = when {
            creatingNew -> draft != PlanDraft()
            else -> selected?.let { it.toDraft() != draft } ?: false
        }

    val problem: String?
        get() = if (selectedId == null && !creatingNew) null else planProblem(
            draft = draft,
            isNew = creatingNew,
            existingCodes = allPlans.filter { it.id != selectedId }.map { it.code }.toSet(),
        )
}

data class PendingPlanOpen(val planId: String?)

/**
 * Planes de suscripción y sus beneficios.
 *
 * Es el catálogo canónico (`planes_suscripcion` + `beneficios_plan`) que lee OposiBot. Sustituye a la pestaña heredada de
 * la tienda, que escribía en tablas que producción no tiene.
 */
class CommercialPlansViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: CommercialCatalogRepository,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<CommercialPlansState, CommercialPlansIntent>(
    initialState = CommercialPlansState(),
    dispatcherProvider = dispatcherProvider,
) {
    init {
        analyticsTracker.trackScreen("commercial_plans")
        dispatch(CommercialPlansIntent.Refresh)
    }

    override suspend fun handleIntent(intent: CommercialPlansIntent) {
        when (intent) {
            CommercialPlansIntent.Refresh -> refresh()
            is CommercialPlansIntent.Open -> requestOpen(intent.planId)
            CommercialPlansIntent.ConfirmOpen -> currentState().pendingOpen?.let { open(it.planId) }
            CommercialPlansIntent.DismissOpen -> updateState { it.copy(pendingOpen = null) }
            is CommercialPlansIntent.UpdateSearch -> updateState { it.copy(searchQuery = intent.value, pageIndex = 0) }
            is CommercialPlansIntent.UpdateFilter -> updateState { it.copy(statusFilter = intent.filter, pageIndex = 0) }
            is CommercialPlansIntent.SetPage -> updateState { it.copy(pageIndex = intent.pageIndex) }
            is CommercialPlansIntent.SelectTab -> updateState { it.copy(tab = intent.tab) }
            is CommercialPlansIntent.UpdateDraft -> updateState { it.copy(draft = intent.draft, actionState = UiAsync.Idle, notice = null) }
            CommercialPlansIntent.Save -> save()
            CommercialPlansIntent.Discard -> updateState { state ->
                state.copy(draft = state.selected?.toDraft() ?: PlanDraft(), actionState = UiAsync.Idle, notice = null)
            }
            is CommercialPlansIntent.EditBenefit -> editBenefit(intent.benefitId)
            is CommercialPlansIntent.UpdateBenefit -> updateState { it.copy(benefitDraft = intent.draft, benefitActionState = UiAsync.Idle) }
            CommercialPlansIntent.SaveBenefit -> saveBenefit()
            CommercialPlansIntent.CancelBenefit -> updateState { it.copy(benefitDraft = null, benefitActionState = UiAsync.Idle) }
        }
    }

    private suspend fun refresh() {
        // Lo ya leído se queda en pantalla mientras se relee: un refresco no debe vaciar la ficha que se está mirando.
        val hadData = currentState().plans.let { it is UiAsync.Success || it is UiAsync.Empty }
        updateState { if (hadData) it.copy(refreshing = true, refreshFailed = false) else it.copy(plans = UiAsync.Loading, refreshFailed = false) }
        when (val result = repository.getPlans()) {
            is AppResult.Failure -> updateState {
                if (hadData) it.copy(refreshing = false, refreshFailed = true) else it.copy(plans = UiAsync.Error(result.error), refreshing = false)
            }
            is AppResult.Success -> {
                // Se mide contra la lista ANTERIOR: contra la nueva, un cambio hecho por otro administrador parecería algo
                // tecleado aquí, y guardar después lo pisaría sin que nadie lo hubiera pedido.
                val wasDirty = currentState().dirty
                updateState {
                    it.copy(
                        plans = if (result.value.isEmpty()) UiAsync.Empty else UiAsync.Success(result.value),
                        refreshing = false,
                        refreshFailed = false,
                    )
                }
                val state = currentState()
                when {
                    state.creatingNew -> Unit
                    // El plan abierto ya no existe: se abre el primero (o nada, si el catálogo quedó vacío).
                    state.selectedId != null && state.selected == null ->
                        result.value.firstOrNull()?.let { open(it.id) } ?: updateState { it.copy(selectedId = null, draft = PlanDraft()) }
                    // Se relee la ficha abierta sólo si no había cambios: un refresco no puede tirar lo que se está escribiendo.
                    state.selectedId != null -> if (!wasDirty) open(state.selectedId, keepTab = true)
                    else -> result.value.firstOrNull()?.let { open(it.id) }
                }
            }
        }
    }

    private fun requestOpen(planId: String?) {
        val state = currentState()
        if (planId != null && planId == state.selectedId && !state.creatingNew) return
        if (state.dirty) {
            updateState { it.copy(pendingOpen = PendingPlanOpen(planId)) }
        } else {
            open(planId)
        }
    }

    private fun open(planId: String?, keepTab: Boolean = false) {
        val plan = planId?.let { id -> currentState().allPlans.firstOrNull { it.id == id } }
        updateState {
            it.copy(
                selectedId = plan?.id,
                creatingNew = planId == null,
                openSeq = if (plan?.id == it.selectedId && (planId == null) == it.creatingNew) it.openSeq else it.openSeq + 1,
                draft = plan?.toDraft() ?: PlanDraft(),
                tab = if (keepTab && plan != null) it.tab else PlanTab.PLAN,
                benefitDraft = null,
                pendingOpen = null,
                actionState = UiAsync.Idle,
                benefitActionState = UiAsync.Idle,
                notice = null,
            )
        }
    }

    private suspend fun save() {
        val state = currentState()
        if (state.selectedId == null && !state.creatingNew) return
        state.problem?.let { problem ->
            updateState { it.copy(actionState = UiAsync.Error(AppError.Validation(problem))) }
            return
        }
        val plan = state.draft.toPlan(base = state.selected) ?: return
        updateState { it.copy(actionState = UiAsync.Loading, notice = null) }
        when (val result = repository.savePlan(plan)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("commercial_plan_save", mapOf("plan_code" to result.value.code))
                val saved = result.value
                updateState { current ->
                    val merged = if (current.allPlans.any { it.id == saved.id }) {
                        current.allPlans.map { if (it.id == saved.id) saved else it }
                    } else {
                        current.allPlans + saved
                    }
                    current.copy(
                        plans = UiAsync.Success(merged),
                        selectedId = saved.id,
                        creatingNew = false,
                        draft = saved.toDraft(),
                        actionState = UiAsync.Success(Unit),
                        notice = keptByServerNotice(planKeptByServer(plan, saved)),
                    )
                }
            }
        }
    }

    private fun editBenefit(benefitId: String?) {
        val plan = currentState().selected ?: return
        val draft = benefitId?.let { id -> plan.benefits.firstOrNull { it.id == id }?.toDraft() } ?: BenefitDraft()
        updateState { it.copy(benefitDraft = draft, benefitActionState = UiAsync.Idle) }
    }

    private suspend fun saveBenefit() {
        val state = currentState()
        val plan = state.selected ?: return
        val draft = state.benefitDraft ?: return
        benefitProblem(draft, plan.benefits.filter { it.id != draft.id })?.let { problem ->
            updateState { it.copy(benefitActionState = UiAsync.Error(AppError.Validation(problem))) }
            return
        }
        val benefit = draft.toBenefit(plan.id) ?: return
        updateState { it.copy(benefitActionState = UiAsync.Loading) }
        when (val result = repository.savePlanBenefit(plan.id, benefit)) {
            is AppResult.Failure -> updateState { it.copy(benefitActionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("commercial_plan_benefit_save", mapOf("plan_code" to plan.code))
                val saved = result.value
                updateState { current ->
                    val updated = current.allPlans.map { existing ->
                        if (existing.id != plan.id) {
                            existing
                        } else {
                            val benefits = if (existing.benefits.any { it.id == saved.id }) {
                                existing.benefits.map { if (it.id == saved.id) saved else it }
                            } else {
                                existing.benefits + saved
                            }
                            existing.copy(benefits = benefits.sortedBy { it.code })
                        }
                    }
                    current.copy(plans = UiAsync.Success(updated), benefitDraft = null, benefitActionState = UiAsync.Success(Unit))
                }
            }
        }
    }
}
