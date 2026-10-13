package com.oposicontrol.admin.features.audit.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.AuditDetail
import com.oposicontrol.admin.domain.model.AuditFilter
import com.oposicontrol.admin.domain.repository.AuditRepository
import kotlinx.datetime.TimeZone

/**
 * Auditoría: el registro de lo que se ha hecho desde el panel, de solo lectura.
 *
 * Tres reglas gobiernan la clase:
 *
 * 1. **Los filtros los aplica el servidor.** Acción, persona y fechas viajan en la petición y la lista se pide de nuevo;
 *    solo el buscador mira lo ya cargado. Antes se bajaban las 100 últimas filas y se filtraban aquí, así que un movimiento
 *    de hace un mes no existía para esta pantalla.
 * 2. **Una respuesta que llega tarde no pisa una pregunta más reciente.** Cada intención corre en su propia corrutina
 *    ([MviViewModel.dispatch]), así que dos lecturas pueden cruzarse: cada lista lleva un número de petición y cada ficha
 *    el identificador de su movimiento, y lo que vuelve para una pregunta que ya no es la actual se descarta.
 * 3. **El antes y el después se piden bajo demanda.** La lista no los descarga (pueden ser grandes y la cuota de tráfico
 *    de la base es limitada); se piden para UN movimiento, cuando quien mira lo pide, y se recuerdan mientras dure la
 *    pantalla: un movimiento ya escrito no cambia.
 */
class AuditViewModel(
    dispatcherProvider: DispatcherProvider,
    private val auditRepository: AuditRepository,
    private val analyticsTracker: AnalyticsTracker,
    /** La zona en la que «un día» empieza y acaba al filtrar por fecha: la del equipo. Las pruebas fijan una. */
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) : MviViewModel<AuditState, AuditIntent>(
    initialState = AuditState(),
    dispatcherProvider = dispatcherProvider,
) {
    /** Número de la última lectura de la lista pedida. Una respuesta con otro número llega tarde. */
    private var listRequest = 0

    /** El rango de fechas con el que se pidió la lista que se ve (los campos pueden estar a medio escribir). */
    private var appliedRange = AuditDateRange()

    /** Los filtros con los que se pidió la lista que se ve: «Cargar más» continúa ESA lista. */
    private var appliedFilter = AuditFilter()

    private val detailCache = mutableMapOf<String, AuditDetail>()

    init {
        analyticsTracker.trackScreen("audit")
        dispatch(AuditIntent.Refresh)
    }

    override suspend fun handleIntent(intent: AuditIntent) {
        when (intent) {
            AuditIntent.Refresh -> reload(keepShown = true)
            is AuditIntent.UpdateSearch -> {
                updateState { it.copy(searchQuery = intent.value) }
                publish()
            }
            is AuditIntent.SelectAction -> {
                if (intent.code == currentState().actionFilter) return
                updateState { it.copy(actionFilter = intent.code) }
                reload()
            }
            is AuditIntent.SelectActor -> {
                if (intent.actorId == currentState().actorFilter) return
                updateState { it.copy(actorFilter = intent.actorId) }
                reload()
            }
            is AuditIntent.UpdateFromDate -> {
                updateState { it.copy(fromDate = intent.value) }
                reloadIfTheRangeChanged()
            }
            is AuditIntent.UpdateUntilDate -> {
                updateState { it.copy(untilDate = intent.value) }
                reloadIfTheRangeChanged()
            }
            AuditIntent.ClearFilters -> {
                val state = currentState()
                val serverFiltersChanged = state.actionFilter != null || state.actorFilter != null || appliedRange != AuditDateRange()
                updateState { it.copy(searchQuery = "", actionFilter = null, actorFilter = null, fromDate = "", untilDate = "") }
                // Si solo había buscador, no hace falta volver al servidor: lo cargado ya es la lista sin filtrar.
                if (serverFiltersChanged) reload() else publish()
            }
            AuditIntent.LoadMore -> loadMore()
            is AuditIntent.Select -> select(intent.id)
            AuditIntent.LoadDetail -> loadDetail()
        }
    }

    /** Vuelve a pintar el catálogo: lo cargado, pasado por el buscador local. */
    private fun publish() {
        val state = currentState()
        // Antes de la primera respuesta no hay nada que filtrar: se respeta el estado de carga o de error.
        if (state.movements is UiAsync.Loading || (state.movements is UiAsync.Error && state.allMovements.isEmpty())) return
        val shown = state.allMovements.filter { auditMatches(it, state.searchQuery) }
        updateState { it.copy(movements = if (shown.isEmpty()) UiAsync.Empty else UiAsync.Success(shown)) }
    }

    private suspend fun reloadIfTheRangeChanged() {
        val state = currentState()
        // Una fecha a medio escribir o que no existe no es una pregunta todavía: no se manda nada al servidor.
        val range = auditDateRange(state.fromDate, state.untilDate, timeZone) ?: return
        if (range != appliedRange) reload()
    }

    /**
     * Pide la primera página con los filtros actuales.
     *
     * [keepShown]: es la MISMA lista releída («Actualizar»); lo que se ve sigue en pantalla hasta que llega la respuesta.
     * Con un filtro nuevo no: lo que se veía respondía a otra pregunta, y dejarlo mientras carga lo haría pasar por el
     * resultado del filtro recién puesto.
     */
    private suspend fun reload(keepShown: Boolean = false) {
        val request = ++listRequest
        val state = currentState()
        val range = auditDateRange(state.fromDate, state.untilDate, timeZone) ?: appliedRange
        val filter = AuditFilter(action = state.actionFilter, actorId = state.actorFilter, from = range.from, until = range.until)
        val keeps = keepShown && state.allMovements.isNotEmpty()
        updateState {
            if (keeps) {
                // Sin cursor mientras se relee: un «Cargar más» en ese hueco pegaría la continuación de la lista VIEJA a
                // la primera página nueva y se saltaría las filas de en medio.
                it.copy(nextCursor = null, loadingMore = false, loadMoreError = null)
            } else {
                it.copy(
                    movements = UiAsync.Loading,
                    allMovements = emptyList(),
                    nextCursor = null,
                    loadingMore = false,
                    loadMoreError = null,
                    selectedId = null,
                    detail = UiAsync.Idle,
                )
            }
        }
        // Se anotan al PEDIR, no al recibir: mientras llega la respuesta, teclear otra vez la misma fecha no debe lanzar
        // una segunda lectura idéntica.
        appliedRange = range
        appliedFilter = filter
        val result = auditRepository.fetchMovements(filter, cursor = null)
        // Entre tanto se cambió un filtro o se pidió otra lectura: esta respuesta es de una pregunta que ya no se hace.
        if (request != listRequest) return
        when (result) {
            is AppResult.Failure -> updateState {
                it.copy(movements = UiAsync.Error(result.error), allMovements = emptyList(), nextCursor = null, selectedId = null, detail = UiAsync.Idle)
            }
            is AppResult.Success -> {
                val rows = result.value.items
                updateState { current ->
                    val keepsSelection = current.selectedId != null && rows.any { it.id == current.selectedId }
                    val selectedId = if (keepsSelection) current.selectedId else rows.firstOrNull()?.id
                    current.copy(
                        allMovements = rows,
                        movements = UiAsync.Success(rows),
                        nextCursor = result.value.nextCursor,
                        actorOptions = auditActorOptions(rows, current.actorOptions),
                        selectedId = selectedId,
                        detail = if (keepsSelection) current.detail else detailFor(selectedId),
                    )
                }
                publish()
            }
        }
    }

    private suspend fun loadMore() {
        val state = currentState()
        val cursor = state.nextCursor ?: return
        if (state.loadingMore) return
        val request = listRequest
        updateState { it.copy(loadingMore = true, loadMoreError = null) }
        val result = auditRepository.fetchMovements(appliedFilter, cursor)
        // La lista se volvió a pedir mientras llegaba esta página: es la continuación de una lista que ya no se ve.
        if (request != listRequest) return
        when (result) {
            is AppResult.Failure -> updateState { it.copy(loadingMore = false, loadMoreError = result.error) }
            is AppResult.Success -> {
                updateState { current ->
                    val known = current.allMovements.mapTo(HashSet()) { it.id }
                    // Por si una fila llegara repetida entre dos páginas: una fila, una vez.
                    val all = current.allMovements + result.value.items.filter { it.id !in known }
                    current.copy(
                        allMovements = all,
                        nextCursor = result.value.nextCursor,
                        loadingMore = false,
                        actorOptions = auditActorOptions(result.value.items, current.actorOptions),
                    )
                }
                publish()
            }
        }
    }

    private fun select(id: String) {
        updateState {
            when {
                it.allMovements.none { movement -> movement.id == id } -> it
                it.selectedId == id -> it
                else -> it.copy(selectedId = id, detail = detailFor(id))
            }
        }
    }

    /** Lo que ya se sabe del antes y el después de [id]: lo recordado, o «todavía no se ha pedido». */
    private fun detailFor(id: String?): UiAsync<AuditDetail> = id?.let { detailCache[it] }?.let { UiAsync.Success(it) } ?: UiAsync.Idle

    private suspend fun loadDetail() {
        val state = currentState()
        val id = state.selectedId ?: return
        if (state.detail is UiAsync.Loading || state.detail is UiAsync.Success) return
        updateState { if (it.selectedId == id) it.copy(detail = UiAsync.Loading) else it }
        val loaded: UiAsync<AuditDetail> = when (val result = auditRepository.fetchDetail(id)) {
            is AppResult.Failure -> UiAsync.Error(result.error)
            is AppResult.Success -> {
                detailCache[id] = result.value
                UiAsync.Success(result.value)
            }
        }
        // Si entre tanto se abrió otro movimiento, este antes y después no son los suyos: se guardan, pero no se pintan.
        updateState { if (it.selectedId == id) it.copy(detail = loaded) else it }
    }
}
