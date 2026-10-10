package com.oposicontrol.admin.features.store.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.CommercialPlan
import com.oposicontrol.admin.domain.model.CommercialProduct
import com.oposicontrol.admin.domain.repository.CommercialCatalogRepository

sealed interface CommercialCatalogIntent {
    data object Refresh : CommercialCatalogIntent

    /** Los planes cambiaron en la pestaña Planes: el selector de una suscripción los recibe sin otra petición. */
    data class SyncPlans(val plans: List<CommercialPlan>) : CommercialCatalogIntent

    /** Abre un producto (o, con `null`, un borrador nuevo). Si hay cambios sin guardar, pide confirmación antes. */
    data class Open(val productId: String?) : CommercialCatalogIntent
    data object ConfirmOpen : CommercialCatalogIntent
    data object DismissOpen : CommercialCatalogIntent

    data class UpdateSearch(val value: String) : CommercialCatalogIntent
    data class UpdateTypeFilter(val type: String?) : CommercialCatalogIntent
    data class SetPage(val pageIndex: Int) : CommercialCatalogIntent
    data class SelectTab(val tab: ProductTab) : CommercialCatalogIntent

    data class UpdateCode(val value: String) : CommercialCatalogIntent
    data class UpdateName(val value: String) : CommercialCatalogIntent
    data class UpdateType(val value: String) : CommercialCatalogIntent
    data class UpdateDescription(val value: String) : CommercialCatalogIntent

    /** Precio tal y como se teclea («12,99»); se convierte a céntimos al guardar. */
    data class UpdatePrice(val value: String) : CommercialCatalogIntent
    data class UpdateTicketAmount(val value: String) : CommercialCatalogIntent
    data class UpdatePlan(val planId: String?) : CommercialCatalogIntent
    data class UpdatePromotionalLabel(val value: String) : CommercialCatalogIntent
    data class UpdatePosition(val value: String) : CommercialCatalogIntent
    data class ToggleActive(val value: Boolean) : CommercialCatalogIntent
    data class ToggleVisible(val value: Boolean) : CommercialCatalogIntent
    data class ToggleHighlighted(val value: Boolean) : CommercialCatalogIntent
    data object Save : CommercialCatalogIntent
    data object Discard : CommercialCatalogIntent

    /** Abre el formulario de una tarifa existente o, con `null`, el de una nueva. */
    data class EditTariff(val tariffId: String?) : CommercialCatalogIntent
    data class UpdateTariff(val draft: TariffDraft) : CommercialCatalogIntent
    data object SaveTariff : CommercialCatalogIntent
    data object CancelTariff : CommercialCatalogIntent
}

data class CommercialCatalogState(
    /** Todos los productos descargados (el repositorio recorre las páginas del servidor). */
    val products: UiAsync<List<CommercialProduct>> = UiAsync.Loading,
    /** Planes para el selector de una suscripción. Vacío si no se pudieron leer: el catálogo sigue funcionando. */
    val plans: List<CommercialPlan> = emptyList(),
    val plansUnavailable: Boolean = false,
    val searchQuery: String = "",
    val typeFilter: String? = null,
    val pageIndex: Int = 0,
    val selectedId: String? = null,
    val creatingNew: Boolean = false,
    val tab: ProductTab = ProductTab.PRODUCT,
    val draft: ProductDraft = ProductDraft(),
    val tariffDraft: TariffDraft? = null,
    /** Destino pendiente de confirmar cuando se intenta salir de una ficha con cambios. */
    val pendingOpen: PendingOpen? = null,
    val actionState: UiAsync<Unit> = UiAsync.Idle,
    val tariffActionState: UiAsync<Unit> = UiAsync.Idle,
    /** Aviso que deja el último guardado (ver [productKeptByServer]). */
    val notice: String? = null,
    /** Se está releyendo un catálogo que ya estaba en pantalla: se sigue viendo mientras tanto. */
    val refreshing: Boolean = false,
    /** La última relectura falló: se conserva lo que ya se había leído, y se dice. */
    val refreshFailed: Boolean = false,
) {
    val allProducts: List<CommercialProduct> get() = (products as? UiAsync.Success)?.value.orEmpty()
    val selected: CommercialProduct? get() = allProducts.firstOrNull { it.id == selectedId }
    val visibleProducts: List<CommercialProduct> get() = allProducts.filter { productMatches(it, searchQuery, typeFilter) }

    /** Hay algo en la ficha que no está en el servidor. */
    val dirty: Boolean
        get() = when {
            creatingNew -> draft != ProductDraft()
            else -> selected?.let { it.toDraft() != draft } ?: false
        }

    val problem: String?
        get() = if (selectedId == null && !creatingNew) null else productProblem(
            draft = draft,
            isNew = creatingNew,
            existingCodes = allProducts.filter { it.id != selectedId }.map { it.code }.toSet(),
        )
}

data class PendingOpen(val productId: String?)

/**
 * Catálogo comercial: productos (planes, packs de OposiTickets, exámenes, servicios) y sus tarifas.
 *
 * Edita el catálogo que OposiBot enseña a los usuarios, de modo que cambiar un precio o los OT de un pack no requiere un
 * despliegue. Todo pasa por el límite canónico de Ktor.
 */
class CommercialCatalogViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: CommercialCatalogRepository,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<CommercialCatalogState, CommercialCatalogIntent>(
    initialState = CommercialCatalogState(),
    dispatcherProvider = dispatcherProvider,
) {
    init {
        analyticsTracker.trackScreen("commercial_catalog")
        dispatch(CommercialCatalogIntent.Refresh)
    }

    override suspend fun handleIntent(intent: CommercialCatalogIntent) {
        when (intent) {
            CommercialCatalogIntent.Refresh -> refresh()
            is CommercialCatalogIntent.SyncPlans -> updateState { it.copy(plans = intent.plans, plansUnavailable = false) }
            is CommercialCatalogIntent.Open -> requestOpen(intent.productId)
            CommercialCatalogIntent.ConfirmOpen -> currentState().pendingOpen?.let { open(it.productId) }
            CommercialCatalogIntent.DismissOpen -> updateState { it.copy(pendingOpen = null) }
            is CommercialCatalogIntent.UpdateSearch -> updateState { it.copy(searchQuery = intent.value, pageIndex = 0) }
            is CommercialCatalogIntent.UpdateTypeFilter -> updateState { it.copy(typeFilter = intent.type, pageIndex = 0) }
            is CommercialCatalogIntent.SetPage -> updateState { it.copy(pageIndex = intent.pageIndex) }
            is CommercialCatalogIntent.SelectTab -> updateState { it.copy(tab = intent.tab) }
            is CommercialCatalogIntent.UpdateCode -> edit { it.copy(code = intent.value) }
            is CommercialCatalogIntent.UpdateName -> edit { it.copy(name = intent.value) }
            is CommercialCatalogIntent.UpdateType -> edit { it.copy(type = intent.value) }
            is CommercialCatalogIntent.UpdateDescription -> edit { it.copy(description = intent.value) }
            is CommercialCatalogIntent.UpdatePrice -> edit { it.copy(price = intent.value) }
            is CommercialCatalogIntent.UpdateTicketAmount -> edit { it.copy(ticketAmount = intent.value) }
            is CommercialCatalogIntent.UpdatePlan -> edit { it.copy(planId = intent.planId) }
            is CommercialCatalogIntent.UpdatePromotionalLabel -> edit { it.copy(promotionalLabel = intent.value) }
            is CommercialCatalogIntent.UpdatePosition -> edit { it.copy(position = intent.value) }
            is CommercialCatalogIntent.ToggleActive -> edit { it.copy(active = intent.value) }
            is CommercialCatalogIntent.ToggleVisible -> edit { it.copy(visible = intent.value) }
            is CommercialCatalogIntent.ToggleHighlighted -> edit { it.copy(highlighted = intent.value) }
            CommercialCatalogIntent.Save -> save()
            CommercialCatalogIntent.Discard -> updateState { state ->
                state.copy(draft = state.selected?.toDraft() ?: ProductDraft(), actionState = UiAsync.Idle, notice = null)
            }
            is CommercialCatalogIntent.EditTariff -> editTariff(intent.tariffId)
            is CommercialCatalogIntent.UpdateTariff -> updateState { it.copy(tariffDraft = intent.draft, tariffActionState = UiAsync.Idle) }
            CommercialCatalogIntent.SaveTariff -> saveTariff()
            CommercialCatalogIntent.CancelTariff -> updateState { it.copy(tariffDraft = null, tariffActionState = UiAsync.Idle) }
        }
    }

    private fun edit(change: (ProductDraft) -> ProductDraft) {
        updateState { it.copy(draft = change(it.draft), actionState = UiAsync.Idle, notice = null) }
    }

    private suspend fun refresh() {
        // Lo ya leído se queda en pantalla mientras se relee: un refresco no debe vaciar la ficha que se está mirando.
        val hadData = currentState().products.let { it is UiAsync.Success || it is UiAsync.Empty }
        updateState { if (hadData) it.copy(refreshing = true, refreshFailed = false) else it.copy(products = UiAsync.Loading, refreshFailed = false) }
        when (val result = repository.getProducts()) {
            is AppResult.Failure -> updateState {
                if (hadData) it.copy(refreshing = false, refreshFailed = true) else it.copy(products = UiAsync.Error(result.error), refreshing = false)
            }
            is AppResult.Success -> {
                // Se mide contra la lista ANTERIOR: contra la nueva, un cambio hecho por otro administrador parecería algo
                // tecleado aquí, y guardar después lo pisaría sin que nadie lo hubiera pedido.
                val wasDirty = currentState().dirty
                updateState {
                    it.copy(
                        products = if (result.value.isEmpty()) UiAsync.Empty else UiAsync.Success(result.value),
                        refreshing = false,
                        refreshFailed = false,
                    )
                }
                val state = currentState()
                when {
                    state.creatingNew -> Unit
                    // El producto abierto ya no existe: se abre el primero (o nada, si el catálogo quedó vacío).
                    state.selectedId != null && state.selected == null ->
                        result.value.firstOrNull()?.let { open(it.id) } ?: updateState { it.copy(selectedId = null, draft = ProductDraft()) }
                    // Se relee la ficha abierta sólo si no había cambios: un refresco no puede tirar lo que se está escribiendo.
                    state.selectedId != null -> if (!wasDirty) open(state.selectedId, keepTab = true)
                    else -> result.value.firstOrNull()?.let { open(it.id) }
                }
            }
        }
        // Los planes sólo alimentan el selector de una suscripción: si fallan, el catálogo sigue siendo editable.
        when (val plans = repository.getPlans()) {
            is AppResult.Failure -> updateState { it.copy(plans = emptyList(), plansUnavailable = true) }
            is AppResult.Success -> updateState { it.copy(plans = plans.value, plansUnavailable = false) }
        }
    }

    private fun requestOpen(productId: String?) {
        val state = currentState()
        if (productId != null && productId == state.selectedId && !state.creatingNew) return
        if (state.dirty) {
            updateState { it.copy(pendingOpen = PendingOpen(productId)) }
        } else {
            open(productId)
        }
    }

    private fun open(productId: String?, keepTab: Boolean = false) {
        val product = productId?.let { id -> currentState().allProducts.firstOrNull { it.id == id } }
        updateState {
            it.copy(
                selectedId = product?.id,
                creatingNew = productId == null,
                draft = product?.toDraft() ?: ProductDraft(),
                tab = if (keepTab && product != null) it.tab else ProductTab.PRODUCT,
                tariffDraft = null,
                pendingOpen = null,
                actionState = UiAsync.Idle,
                tariffActionState = UiAsync.Idle,
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
        val product = state.draft.toProduct(base = state.selected) ?: return
        updateState { it.copy(actionState = UiAsync.Loading, notice = null) }
        when (val result = repository.saveProduct(product)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("commercial_product_save", mapOf("product_code" to result.value.code))
                val saved = result.value
                updateState { current ->
                    val others = current.allProducts.filter { it.id != saved.id }
                    val merged = if (current.allProducts.any { it.id == saved.id }) {
                        current.allProducts.map { if (it.id == saved.id) saved else it }
                    } else {
                        others + saved
                    }
                    current.copy(
                        products = UiAsync.Success(merged),
                        selectedId = saved.id,
                        creatingNew = false,
                        draft = saved.toDraft(),
                        actionState = UiAsync.Success(Unit),
                        notice = keptByServerNotice(productKeptByServer(product, saved)),
                    )
                }
            }
        }
    }

    private fun editTariff(tariffId: String?) {
        val product = currentState().selected ?: return
        val draft = tariffId?.let { id -> product.tariffs.firstOrNull { it.id == id }?.toDraft() } ?: TariffDraft()
        updateState { it.copy(tariffDraft = draft, tariffActionState = UiAsync.Idle) }
    }

    private suspend fun saveTariff() {
        val state = currentState()
        val product = state.selected ?: return
        val draft = state.tariffDraft ?: return
        tariffProblem(draft, product.tariffs.filter { it.id != draft.id })?.let { problem ->
            updateState { it.copy(tariffActionState = UiAsync.Error(AppError.Validation(problem))) }
            return
        }
        val tariff = draft.toTariff(product.id, product.currency) ?: return
        updateState { it.copy(tariffActionState = UiAsync.Loading) }
        when (val result = repository.saveTariff(product.id, tariff)) {
            is AppResult.Failure -> updateState { it.copy(tariffActionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("commercial_tariff_save", mapOf("product_code" to product.code))
                val saved = result.value
                updateState { current ->
                    val updated = current.allProducts.map { existing ->
                        if (existing.id != product.id) {
                            existing
                        } else {
                            val tariffs = if (existing.tariffs.any { it.id == saved.id }) {
                                existing.tariffs.map { if (it.id == saved.id) saved else it }
                            } else {
                                existing.tariffs + saved
                            }
                            existing.copy(tariffs = tariffs.sortedBy { it.position })
                        }
                    }
                    current.copy(products = UiAsync.Success(updated), tariffDraft = null, tariffActionState = UiAsync.Success(Unit))
                }
            }
        }
    }
}

/**
 * Convierte lo tecleado ("12,99", "12.99", "13") a céntimos enteros.
 *
 * Devuelve null si no es un importe válido, para que quien llame muestre un error
 * en vez de guardar un precio equivocado. Se hace con aritmética entera a
 * propósito: pasar por `Double` reintroduce justo el error de coma flotante que
 * el modelo en céntimos existe para evitar.
 */
internal fun parsePriceToCents(input: String): Int? {
    val normalised = input.trim().replace(',', '.')
    if (normalised.isEmpty() || normalised.startsWith("-")) return null

    val parts = normalised.split('.')
    if (parts.size > 2) return null

    val unitsText = parts[0].ifEmpty { "0" }
    if (!unitsText.all { it.isDigit() }) return null
    val units = unitsText.toLongOrNull() ?: return null

    val centsText = when {
        parts.size == 1 -> "00"
        // Se aceptan 0, 1 o 2 decimales. "12,999" es casi siempre un error de
        // tecleo, y redondearlo en silencio cambiaría el precio sin avisar.
        parts[1].length <= 2 -> parts[1].padEnd(2, '0')
        else -> return null
    }
    if (centsText.isEmpty() || !centsText.all { it.isDigit() }) return null

    val total = units * 100 + centsText.toLong()
    return if (total > Int.MAX_VALUE) null else total.toInt()
}

/** Céntimos enteros como texto editable ("1299" -> "12,99"). */
internal fun centsToInput(priceCents: Int): String {
    val cents = priceCents % 100
    return "${priceCents / 100},${cents.toString().padStart(2, '0')}"
}

/**
 * Coste por ticket con cuatro decimales: a 100 OT por euro las diferencias entre
 * packs viven en la tercera cifra, y redondear a dos las borraria justo cuando
 * se necesitan para comparar.
 */
internal fun formatCentsPerTicket(centsPerTicket: Double): String {
    // Se redondea, no se trunca: truncar sesga siempre a la baja y haría parecer
    // que un pack sale algo más barato de lo que es.
    val tenThousandths = kotlin.math.round(centsPerTicket * 10_000).toLong()
    val units = tenThousandths / 10_000
    val fraction = (tenThousandths % 10_000).toString().padStart(4, '0')
    return "$units,$fraction"
}
