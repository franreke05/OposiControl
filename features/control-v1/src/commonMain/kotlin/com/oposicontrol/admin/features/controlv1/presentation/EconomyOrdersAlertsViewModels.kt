package com.oposicontrol.admin.features.controlv1.presentation

import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.Community
import com.oposicontrol.admin.domain.model.ExamEconomy
import com.oposicontrol.admin.domain.model.ExamEconomyDraft
import com.oposicontrol.admin.domain.model.ExamEconomyValidator
import com.oposicontrol.admin.domain.model.FastWindow
import com.oposicontrol.admin.domain.model.ForumCounterDrift
import com.oposicontrol.admin.domain.model.OrderItem
import com.oposicontrol.admin.domain.model.OrderDecision
import com.oposicontrol.admin.domain.model.OrderNote
import com.oposicontrol.admin.domain.model.OrderResolutionNote
import com.oposicontrol.admin.domain.model.OrderStates
import com.oposicontrol.admin.domain.repository.CommunitiesRepository
import com.oposicontrol.admin.domain.repository.ControlV1Repository

// ====================================================================================================================
// Comunidad obligatoria, precio en OT y días de acceso por examen
// ====================================================================================================================

data class ExamEconomyUiState(
    val exams: UiAsync<List<ExamEconomy>> = UiAsync.Loading,
    val total: Int = 0,
    val offset: Int = 0,
    val onlyWithoutCommunity: Boolean = false,
    val communities: UiAsync<List<Community>> = UiAsync.Loading,
    val selectedId: String? = null,
    val draft: ExamEconomyDraft? = null,
    val errors: Map<String, String> = emptyMap(),
    val save: UiAsync<Unit> = UiAsync.Idle,
) {
    val selected: ExamEconomy? get() = (exams as? UiAsync.Success)?.value?.firstOrNull { it.id == selectedId }
    val hasPrevious: Boolean get() = offset > 0
    val hasNext: Boolean get() = offset + PAGE < total

    companion object {
        const val PAGE = 25
    }
}

sealed interface ExamEconomyIntent {
    data object Refresh : ExamEconomyIntent
    data class SetOnlyWithoutCommunity(val value: Boolean) : ExamEconomyIntent
    data object NextPage : ExamEconomyIntent
    data object PreviousPage : ExamEconomyIntent
    data class Select(val id: String) : ExamEconomyIntent
    data class Update(val draft: ExamEconomyDraft) : ExamEconomyIntent
    data object Save : ExamEconomyIntent
    data object Discard : ExamEconomyIntent
}

/**
 * Qué cuesta cada examen en OposiTickets, cuántos días dura lo comprado y a qué comunidad pertenece.
 *
 * Cada examen lleva SU precio (lo fija el administrador; nada de importes de producto en el código). La comunidad es
 * obligatoria: no se ofrece «ninguna» y guardar sin ella no sale del formulario. El servidor y la base repiten ambas
 * reglas.
 */
class ExamEconomyViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: ControlV1Repository,
    private val communitiesRepository: CommunitiesRepository,
) : MviViewModel<ExamEconomyUiState, ExamEconomyIntent>(ExamEconomyUiState(), dispatcherProvider) {
    private var writing = false

    init {
        dispatch(ExamEconomyIntent.Refresh)
    }

    override suspend fun handleIntent(intent: ExamEconomyIntent) {
        when (intent) {
            ExamEconomyIntent.Refresh -> {
                loadCommunities()
                loadPage(currentState().offset)
            }
            is ExamEconomyIntent.SetOnlyWithoutCommunity -> {
                updateState { it.copy(onlyWithoutCommunity = intent.value, offset = 0, selectedId = null, draft = null) }
                loadPage(0)
            }
            ExamEconomyIntent.NextPage -> {
                val s = currentState()
                if (s.hasNext) loadPage(s.offset + ExamEconomyUiState.PAGE)
            }
            ExamEconomyIntent.PreviousPage -> {
                val s = currentState()
                if (s.hasPrevious) loadPage((s.offset - ExamEconomyUiState.PAGE).coerceAtLeast(0))
            }
            is ExamEconomyIntent.Select -> updateState { state ->
                val exam = (state.exams as? UiAsync.Success)?.value?.firstOrNull { it.id == intent.id }
                if (exam == null) state else state.copy(selectedId = exam.id, draft = ExamEconomyDraft.from(exam), errors = emptyMap(), save = UiAsync.Idle)
            }
            is ExamEconomyIntent.Update -> updateState { it.copy(draft = intent.draft, errors = emptyMap(), save = UiAsync.Idle) }
            ExamEconomyIntent.Discard -> updateState { it.copy(selectedId = null, draft = null, errors = emptyMap(), save = UiAsync.Idle) }
            ExamEconomyIntent.Save -> save()
        }
    }

    private suspend fun loadCommunities() {
        updateState { it.copy(communities = UiAsync.Loading) }
        when (val r = communitiesRepository.refreshCommunities()) {
            is AppResult.Failure -> updateState { it.copy(communities = UiAsync.Error(r.error)) }
            is AppResult.Success -> updateState {
                val active = r.value.filter { c -> c.isActive }
                it.copy(communities = if (active.isEmpty()) UiAsync.Empty else UiAsync.Success(active))
            }
        }
    }

    private suspend fun loadPage(offset: Int) {
        if (currentState().exams !is UiAsync.Success) updateState { it.copy(exams = UiAsync.Loading) }
        when (val r = repository.examEconomy(offset, currentState().onlyWithoutCommunity)) {
            is AppResult.Failure -> updateState { it.copy(exams = UiAsync.Error(r.error)) }
            is AppResult.Success -> updateState {
                val keep = it.selectedId?.takeIf { id -> r.value.items.any { e -> e.id == id } }
                it.copy(
                    exams = if (r.value.items.isEmpty()) UiAsync.Empty else UiAsync.Success(r.value.items),
                    total = r.value.total,
                    offset = r.value.offset,
                    selectedId = keep,
                    draft = if (keep == null) null else it.draft,
                )
            }
        }
    }

    private suspend fun save() {
        if (writing) return
        val state = currentState()
        val exam = state.selected ?: return
        val draft = state.draft ?: return
        val errors = ExamEconomyValidator.check(draft)
        if (errors.isNotEmpty()) {
            updateState { it.copy(errors = errors) }
            return
        }
        writing = true
        try {
            updateState { it.copy(save = UiAsync.Loading) }
            when (val r = repository.saveExamEconomy(exam.id, draft)) {
                is AppResult.Failure -> updateState { it.copy(save = UiAsync.Error(r.error)) }
                is AppResult.Success -> updateState { s ->
                    val list = (s.exams as? UiAsync.Success)?.value.orEmpty().map { e -> if (e.id == r.value.id) r.value else e }
                    s.copy(exams = UiAsync.Success(list), draft = ExamEconomyDraft.from(r.value), save = UiAsync.Success(Unit))
                }
            }
        } finally {
            writing = false
        }
    }
}

// ====================================================================================================================
// Pedidos: en revisión y casos de reembolso
// ====================================================================================================================

data class OrdersUiState(
    /** `null` = todos. Por defecto, lo que necesita una persona: `en_revision`. */
    val filter: String? = "en_revision",
    val orders: UiAsync<List<OrderItem>> = UiAsync.Loading,
    val total: Int = 0,
    val offset: Int = 0,
    val reviewCount: Int? = null,
    val selectedId: String? = null,
    val note: String = "",
    val noteError: String? = null,
    val noteResult: UiAsync<Unit> = UiAsync.Idle,
    /** `PAYMENTS_REFUNDS_ENABLED` del servidor; sin él el reembolso no se ofrece. */
    val refundsEnabled: Boolean = false,
    val decision: OrderDecision? = null,
    val resolveNote: String = "",
    val resolveError: String? = null,
    val confirmResolve: Boolean = false,
    val resolveResult: UiAsync<String> = UiAsync.Idle,
) {
    val selected: OrderItem? get() = (orders as? UiAsync.Success)?.value?.firstOrNull { it.id == selectedId }
    val hasPrevious: Boolean get() = offset > 0
    val hasNext: Boolean get() = offset + PAGE < total

    companion object {
        const val PAGE = 25
    }
}

sealed interface OrdersIntent {
    data object Refresh : OrdersIntent
    data class SetFilter(val state: String?) : OrdersIntent
    data object NextPage : OrdersIntent
    data object PreviousPage : OrdersIntent
    data class Select(val id: String) : OrdersIntent
    data class UpdateNote(val text: String) : OrdersIntent
    data object SaveNote : OrdersIntent
    data class SelectDecision(val decision: OrderDecision) : OrdersIntent
    data class UpdateResolveNote(val text: String) : OrdersIntent
    data object RequestResolve : OrdersIntent
    data object ConfirmResolve : OrdersIntent
    data object DismissResolve : OrdersIntent
}

/**
 * Pedidos de pago. Una nota interna auditada no cambia nada; RESOLVER un `en_revision` (acreditar, confirmar, rechazar,
 * reembolsar) llama a `admin_payment_order_resolve`: una sola transacción en el servidor, idempotente (repetir la
 * decisión no mueve nada) y auditada con tu cuenta. El panel no decide qué decisión encaja con el estado real del pedido:
 * el servidor lo comprueba y dice por qué no (pedido ya acreditado, ya resuelto, reembolsos apagados...).
 */
class OrdersViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: ControlV1Repository,
) : MviViewModel<OrdersUiState, OrdersIntent>(OrdersUiState(), dispatcherProvider) {
    private var writing = false

    init {
        dispatch(OrdersIntent.Refresh)
    }

    override suspend fun handleIntent(intent: OrdersIntent) {
        when (intent) {
            OrdersIntent.Refresh -> {
                loadCount()
                load(currentState().offset)
            }
            is OrdersIntent.SetFilter -> {
                if (intent.state != null && intent.state !in OrderStates.ALL) return
                updateState { it.copy(filter = intent.state, offset = 0, selectedId = null, orders = UiAsync.Loading) }
                load(0)
            }
            OrdersIntent.NextPage -> currentState().let { if (it.hasNext) load(it.offset + OrdersUiState.PAGE) }
            OrdersIntent.PreviousPage -> currentState().let { if (it.hasPrevious) load((it.offset - OrdersUiState.PAGE).coerceAtLeast(0)) }
            is OrdersIntent.Select -> updateState { state ->
                if ((state.orders as? UiAsync.Success)?.value?.any { it.id == intent.id } == true) {
                    state.copy(selectedId = intent.id, note = "", noteError = null, noteResult = UiAsync.Idle, decision = null, resolveNote = "", resolveError = null, confirmResolve = false, resolveResult = UiAsync.Idle)
                } else state
            }
            is OrdersIntent.UpdateNote -> updateState { it.copy(note = intent.text, noteError = null, noteResult = UiAsync.Idle) }
            OrdersIntent.SaveNote -> saveNote()
            is OrdersIntent.SelectDecision -> updateState {
                if (intent.decision.available(it.refundsEnabled)) it.copy(decision = intent.decision, resolveError = null, resolveResult = UiAsync.Idle) else it
            }
            is OrdersIntent.UpdateResolveNote -> updateState { it.copy(resolveNote = intent.text, resolveError = null, resolveResult = UiAsync.Idle) }
            OrdersIntent.RequestResolve -> requestResolve()
            OrdersIntent.ConfirmResolve -> resolve()
            OrdersIntent.DismissResolve -> updateState { it.copy(confirmResolve = false) }
        }
    }

    private suspend fun loadCount() {
        val r = repository.ordersInReviewCount()
        updateState { it.copy(reviewCount = (r as? AppResult.Success)?.value) }
    }

    private suspend fun load(offset: Int) {
        if (currentState().orders !is UiAsync.Success) updateState { it.copy(orders = UiAsync.Loading) }
        when (val r = repository.orders(currentState().filter, offset)) {
            is AppResult.Failure -> updateState { it.copy(orders = UiAsync.Error(r.error)) }
            is AppResult.Success -> updateState {
                val keep = it.selectedId?.takeIf { id -> r.value.items.any { o -> o.id == id } }
                it.copy(
                    orders = if (r.value.items.isEmpty()) UiAsync.Empty else UiAsync.Success(r.value.items),
                    total = r.value.total,
                    offset = r.value.offset,
                    refundsEnabled = r.value.refundsEnabled,
                    selectedId = keep,
                )
            }
        }
    }

    private fun requestResolve() {
        val state = currentState()
        val order = state.selected ?: return
        if (order.state != "en_revision") return
        val decision = state.decision
        val error = when {
            decision == null -> "Elige una decisión."
            !decision.available(state.refundsEnabled) -> "Los reembolsos están desactivados en este entorno."
            else -> OrderResolutionNote.check(state.resolveNote)
        }
        if (error != null) {
            updateState { it.copy(resolveError = error, confirmResolve = false) }
            return
        }
        updateState { it.copy(resolveError = null, confirmResolve = true) }
    }

    private suspend fun resolve() {
        if (writing) return
        val state = currentState()
        val order = state.selected ?: return
        val decision = state.decision ?: return
        writing = true
        try {
            updateState { it.copy(confirmResolve = false, resolveResult = UiAsync.Loading) }
            when (val r = repository.resolveOrder(order.id, decision, state.resolveNote)) {
                is AppResult.Failure -> updateState { it.copy(resolveResult = UiAsync.Error(r.error)) }
                is AppResult.Success -> {
                    updateState { it.copy(decision = null, resolveNote = "", resolveResult = UiAsync.Success(r.value.message())) }
                    // El pedido ya no está en revisión: se recarga la lista y el contador (la verdad está en el servidor).
                    loadCount()
                    load(currentState().offset)
                }
            }
        } finally {
            writing = false
        }
    }

    private suspend fun saveNote() {
        if (writing) return
        val state = currentState()
        val order = state.selected ?: return
        val error = OrderNote.check(state.note)
        if (error != null) {
            updateState { it.copy(noteError = error) }
            return
        }
        writing = true
        try {
            updateState { it.copy(noteResult = UiAsync.Loading) }
            when (val r = repository.addOrderNote(order.id, state.note)) {
                is AppResult.Failure -> updateState { it.copy(noteResult = UiAsync.Error(r.error)) }
                is AppResult.Success -> updateState { it.copy(note = "", noteResult = UiAsync.Success(Unit)) }
            }
        } finally {
            writing = false
        }
    }
}

// ====================================================================================================================
// Alertas de solo lectura
// ====================================================================================================================

data class AlertsUiState(
    val fastWindows: UiAsync<List<FastWindow>> = UiAsync.Loading,
    val total: Int = 0,
    val offset: Int = 0,
    val thresholdMs: Int? = null,
    val drift: UiAsync<List<ForumCounterDrift>> = UiAsync.Loading,
) {
    val hasPrevious: Boolean get() = offset > 0
    val hasNext: Boolean get() = offset + PAGE < total

    companion object {
        const val PAGE = 25
    }
}

sealed interface AlertsIntent {
    data object Refresh : AlertsIntent
    data object NextPage : AlertsIntent
    data object PreviousPage : AlertsIntent
}

/**
 * Alertas: ventanas de ranking demasiado rápidas y desvíos de contadores del foro. Esta clase no tiene NINGUNA escritura
 * y no la va a tener: una ventana rápida es un indicio que revisa una persona con otras fuentes, no una sanción.
 */
class AlertsViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: ControlV1Repository,
) : MviViewModel<AlertsUiState, AlertsIntent>(AlertsUiState(), dispatcherProvider) {
    init {
        dispatch(AlertsIntent.Refresh)
    }

    override suspend fun handleIntent(intent: AlertsIntent) {
        when (intent) {
            AlertsIntent.Refresh -> {
                loadDrift()
                loadWindows(currentState().offset)
            }
            AlertsIntent.NextPage -> currentState().let { if (it.hasNext) loadWindows(it.offset + AlertsUiState.PAGE) }
            AlertsIntent.PreviousPage -> currentState().let { if (it.hasPrevious) loadWindows((it.offset - AlertsUiState.PAGE).coerceAtLeast(0)) }
        }
    }

    private suspend fun loadWindows(offset: Int) {
        if (currentState().fastWindows !is UiAsync.Success) updateState { it.copy(fastWindows = UiAsync.Loading) }
        when (val r = repository.fastWindows(offset)) {
            is AppResult.Failure -> updateState { it.copy(fastWindows = UiAsync.Error(r.error)) }
            is AppResult.Success -> updateState {
                it.copy(
                    fastWindows = if (r.value.items.isEmpty()) UiAsync.Empty else UiAsync.Success(r.value.items),
                    total = r.value.total,
                    offset = r.value.offset,
                    thresholdMs = r.value.thresholdMs,
                )
            }
        }
    }

    private suspend fun loadDrift() {
        when (val r = repository.forumCounterDrift()) {
            is AppResult.Failure -> updateState { it.copy(drift = UiAsync.Error(r.error)) }
            is AppResult.Success -> updateState { it.copy(drift = if (r.value.isEmpty()) UiAsync.Empty else UiAsync.Success(r.value)) }
        }
    }
}
