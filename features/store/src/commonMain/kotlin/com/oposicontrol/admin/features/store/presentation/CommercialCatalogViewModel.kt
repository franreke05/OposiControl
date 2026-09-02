package com.oposicontrol.admin.features.store.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.CommercialProduct
import com.oposicontrol.admin.domain.repository.CommercialCatalogRepository

sealed interface CommercialCatalogIntent {
    data object Refresh : CommercialCatalogIntent
    data class Select(val productId: String) : CommercialCatalogIntent
    data class UpdateName(val value: String) : CommercialCatalogIntent

    /** Precio tal y como se teclea ("12,99"); se convierte a céntimos al guardar. */
    data class UpdatePrice(val value: String) : CommercialCatalogIntent
    data class UpdateTicketAmount(val value: String) : CommercialCatalogIntent
    data class UpdatePromotionalLabel(val value: String) : CommercialCatalogIntent
    data class ToggleActive(val value: Boolean) : CommercialCatalogIntent
    data class ToggleVisible(val value: Boolean) : CommercialCatalogIntent
    data class ToggleHighlighted(val value: Boolean) : CommercialCatalogIntent
    data object Save : CommercialCatalogIntent
}

data class CommercialCatalogState(
    val products: UiAsync<List<CommercialProduct>> = UiAsync.Loading,
    val selectedId: String? = null,
    val editorName: String = "",
    val editorPrice: String = "",
    val editorTicketAmount: String = "",
    val editorPromotionalLabel: String = "",
    val editorActive: Boolean = true,
    val editorVisible: Boolean = true,
    val editorHighlighted: Boolean = false,
    val actionState: UiAsync<Unit> = UiAsync.Idle,
) {
    val selected: CommercialProduct?
        get() = (products as? UiAsync.Success)?.value?.firstOrNull { it.id == selectedId }
}

/**
 * Catálogo comercial: planes, packs de OposiTickets y sus precios.
 *
 * Edita el catálogo que OposiBot enseña a los usuarios, de modo que cambiar un
 * precio o los OT de un pack deje de requerir un despliegue.
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
        dispatch(CommercialCatalogIntent.Refresh)
    }

    override suspend fun handleIntent(intent: CommercialCatalogIntent) {
        when (intent) {
            CommercialCatalogIntent.Refresh -> refresh()
            is CommercialCatalogIntent.Select -> select(intent.productId)
            is CommercialCatalogIntent.UpdateName -> updateState { it.copy(editorName = intent.value) }
            is CommercialCatalogIntent.UpdatePrice -> updateState { it.copy(editorPrice = intent.value) }
            is CommercialCatalogIntent.UpdateTicketAmount ->
                updateState { it.copy(editorTicketAmount = intent.value) }
            is CommercialCatalogIntent.UpdatePromotionalLabel ->
                updateState { it.copy(editorPromotionalLabel = intent.value) }
            is CommercialCatalogIntent.ToggleActive -> updateState { it.copy(editorActive = intent.value) }
            is CommercialCatalogIntent.ToggleVisible -> updateState { it.copy(editorVisible = intent.value) }
            is CommercialCatalogIntent.ToggleHighlighted ->
                updateState { it.copy(editorHighlighted = intent.value) }
            CommercialCatalogIntent.Save -> save()
        }
    }

    private suspend fun refresh() {
        analyticsTracker.trackScreen("commercial_catalog")
        updateState { it.copy(products = UiAsync.Loading) }
        when (val result = repository.getProducts()) {
            is AppResult.Failure -> updateState { it.copy(products = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                updateState {
                    it.copy(
                        products = if (result.value.isEmpty()) UiAsync.Empty else UiAsync.Success(result.value),
                    )
                }
                val current = currentState().selectedId
                if (current == null) {
                    result.value.firstOrNull()?.let { select(it.id) }
                } else {
                    select(current)
                }
            }
        }
    }

    private fun select(productId: String) {
        val product = (currentState().products as? UiAsync.Success)?.value
            ?.firstOrNull { it.id == productId } ?: return
        updateState {
            it.copy(
                selectedId = product.id,
                editorName = product.name,
                editorPrice = product.priceCents?.let(::centsToInput).orEmpty(),
                editorTicketAmount = product.ticketAmount?.toString().orEmpty(),
                editorPromotionalLabel = product.promotionalLabel.orEmpty(),
                editorActive = product.active,
                editorVisible = product.visible,
                editorHighlighted = product.highlighted,
                actionState = UiAsync.Idle,
            )
        }
    }

    private suspend fun save() {
        val product = currentState().selected
            ?: return updateState {
                it.copy(actionState = UiAsync.Error(AppError.Validation("Selecciona un producto.")))
            }
        val state = currentState()

        val priceCents = if (state.editorPrice.isBlank()) {
            null
        } else {
            parsePriceToCents(state.editorPrice)
                ?: return updateState {
                    it.copy(
                        actionState = UiAsync.Error(
                            AppError.Validation("El precio debe ser un importe válido, por ejemplo 12,99."),
                        ),
                    )
                }
        }

        val ticketAmount = if (state.editorTicketAmount.isBlank()) {
            null
        } else {
            state.editorTicketAmount.trim().toIntOrNull()
                ?: return updateState {
                    it.copy(
                        actionState = UiAsync.Error(
                            AppError.Validation("Los OposiTickets deben ser un número entero."),
                        ),
                    )
                }
        }

        updateState { it.copy(actionState = UiAsync.Loading) }
        val updated = product.copy(
            name = state.editorName.trim(),
            priceCents = priceCents,
            ticketAmount = ticketAmount,
            promotionalLabel = state.editorPromotionalLabel.trim().takeIf { it.isNotEmpty() },
            active = state.editorActive,
            visible = state.editorVisible,
            highlighted = state.editorHighlighted,
        )
        when (val result = repository.saveProduct(updated)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction(
                    "commercial_product_save",
                    mapOf("product_code" to result.value.code),
                )
                updateState { it.copy(actionState = UiAsync.Success(Unit)) }
                refresh()
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
