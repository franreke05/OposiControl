package com.oposicontrol.admin.features.controlv1.presentation

import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.ReferralItem
import com.oposicontrol.admin.domain.model.ReferralReason
import com.oposicontrol.admin.domain.model.ReferralStates
import com.oposicontrol.admin.domain.repository.ControlV1Repository

data class ReferralState(
    /** `null` = todas. */
    val filter: String? = null,
    val items: UiAsync<List<ReferralItem>> = UiAsync.Loading,
    val summary: Map<String, Int> = emptyMap(),
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    val revokeTarget: ReferralItem? = null,
    val revokeReason: String = "",
    val revokeError: String? = null,
    val action: UiAsync<String> = UiAsync.Idle,
)

sealed interface ReferralIntent {
    data object Refresh : ReferralIntent
    data class SetFilter(val state: String?) : ReferralIntent
    data object LoadMore : ReferralIntent
    data class RequestRevoke(val id: String) : ReferralIntent
    data class UpdateReason(val text: String) : ReferralIntent
    data object ConfirmRevoke : ReferralIntent
    data object DismissRevoke : ReferralIntent
}

/**
 * Lista de invitaciones de Invita y gana y su revocación.
 *
 * Revocar es la única escritura: exige motivo (3..200), confirmación explícita y solo se ofrece sobre los estados donde
 * tiene efecto ([ReferralStates.canRevoke]). Devuelve lo revertido y lo que NO se pudo revertir (saldo ya gastado): ese
 * resto se dice al operador, no se esconde.
 */
class ReferralViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: ControlV1Repository,
) : MviViewModel<ReferralState, ReferralIntent>(ReferralState(), dispatcherProvider) {
    private var revoking = false

    init {
        dispatch(ReferralIntent.Refresh)
    }

    override suspend fun handleIntent(intent: ReferralIntent) {
        when (intent) {
            ReferralIntent.Refresh -> load(reset = true)
            is ReferralIntent.SetFilter -> {
                if (intent.state != null && intent.state !in ReferralStates.ALL) return
                updateState { it.copy(filter = intent.state, items = UiAsync.Loading, nextCursor = null) }
                load(reset = true)
            }
            ReferralIntent.LoadMore -> load(reset = false)
            is ReferralIntent.RequestRevoke -> updateState { state ->
                val item = (state.items as? UiAsync.Success)?.value?.firstOrNull { it.id == intent.id }
                if (item == null || !ReferralStates.canRevoke(item.state)) state
                else state.copy(revokeTarget = item, revokeReason = "", revokeError = null, action = UiAsync.Idle)
            }
            is ReferralIntent.UpdateReason -> updateState { it.copy(revokeReason = intent.text, revokeError = null) }
            ReferralIntent.DismissRevoke -> updateState { it.copy(revokeTarget = null, revokeReason = "", revokeError = null) }
            ReferralIntent.ConfirmRevoke -> revoke()
        }
    }

    private suspend fun load(reset: Boolean) {
        val state = currentState()
        if (!reset) {
            val cursor = state.nextCursor ?: return
            if (state.loadingMore) return
            updateState { it.copy(loadingMore = true) }
            when (val r = repository.listReferrals(state.filter, cursor)) {
                is AppResult.Failure -> updateState { it.copy(loadingMore = false, action = UiAsync.Error(r.error)) }
                is AppResult.Success -> updateState {
                    val merged = (it.items as? UiAsync.Success)?.value.orEmpty() + r.value.items
                    it.copy(items = UiAsync.Success(merged.distinctBy { item -> item.id }), nextCursor = r.value.nextCursor, loadingMore = false)
                }
            }
            return
        }
        if (state.items !is UiAsync.Success) updateState { it.copy(items = UiAsync.Loading) }
        when (val r = repository.listReferrals(state.filter, null)) {
            is AppResult.Failure -> updateState { it.copy(items = UiAsync.Error(r.error), nextCursor = null, summary = emptyMap()) }
            is AppResult.Success -> updateState {
                it.copy(
                    items = if (r.value.items.isEmpty()) UiAsync.Empty else UiAsync.Success(r.value.items),
                    summary = r.value.summary,
                    nextCursor = r.value.nextCursor,
                )
            }
        }
    }

    private suspend fun revoke() {
        if (revoking) return
        val state = currentState()
        val target = state.revokeTarget ?: return
        val error = ReferralReason.check(state.revokeReason)
        if (error != null) {
            updateState { it.copy(revokeError = error) }
            return
        }
        revoking = true
        try {
            updateState { it.copy(action = UiAsync.Loading) }
            when (val r = repository.revokeReferral(target.id, state.revokeReason.trim())) {
                is AppResult.Failure -> updateState { it.copy(action = UiAsync.Error(r.error)) }
                is AppResult.Success -> {
                    val note = if (r.value.notRevertible > 0) {
                        "Revocada. Se revirtieron ${r.value.reverted} OT; ${r.value.notRevertible} OT ya estaban gastados y no se pueden recuperar."
                    } else {
                        "Revocada. Se revirtieron ${r.value.reverted} OT."
                    }
                    updateState { it.copy(revokeTarget = null, revokeReason = "", revokeError = null, action = UiAsync.Success(note)) }
                    load(reset = true)
                }
            }
        } finally {
            revoking = false
        }
    }
}
