package com.oposicontrol.admin.features.users.presentation

import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.AccessRequest
import com.oposicontrol.admin.domain.model.AccessRequestRules
import com.oposicontrol.admin.domain.model.AccessRequestStatus
import com.oposicontrol.admin.domain.repository.AccessRequestsRepository
import com.oposicontrol.admin.domain.repository.AuthRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

sealed interface AccessRequestsIntent {
    /** Vuelve a preguntar al servidor (al abrir el hub de Sistema y con el boton de actualizar). */
    data object Refresh : AccessRequestsIntent
    data class UpdateFilter(val filter: AccessRequestFilter) : AccessRequestsIntent
    data class Select(val requestId: String) : AccessRequestsIntent

    /** Motivo OPCIONAL del rechazo de la solicitud abierta (se escribe en la ficha, antes de pulsar «Rechazar»). */
    data class UpdateRejectionReason(val value: String) : AccessRequestsIntent

    /** Abre la confirmacion de aprobar / rechazar. Nada se envia hasta [ConfirmDecision]. */
    data class AskApprove(val requestId: String) : AccessRequestsIntent
    data class AskReject(val requestId: String) : AccessRequestsIntent
    data object ConfirmDecision : AccessRequestsIntent
    data object DismissDecision : AccessRequestsIntent
}

enum class AccessRequestFilter(val label: String, val status: AccessRequestStatus?) {
    ALL("Todas", null),
    PENDING("Pendientes", AccessRequestStatus.PENDING),
    APPROVED("Aprobadas", AccessRequestStatus.APPROVED),
    REJECTED("Rechazadas", AccessRequestStatus.REJECTED),
}

/**
 * ¿Puede esta sesion revisar solicitudes? Lo dice el SERVIDOR, nunca el cliente:
 *  - [UNKNOWN]  todavia no ha respondido (o no se pudo preguntar): la pestana NO se ensena.
 *  - [ALLOWED]  el listado respondio: quien llama es superadministrador.
 *  - [DENIED]   el listado respondio 403 (`SUPERADMIN_REQUIRED`): la pestana no existe para esta persona.
 */
enum class AccessReviewAvailability { UNKNOWN, ALLOWED, DENIED }

enum class AccessDecisionKind { APPROVE, REJECT }

/** La decision que espera confirmacion. [reason] solo cuenta al rechazar (opcional; el borrador de la ficha, ya recortado). */
data class PendingAccessDecision(
    val request: AccessRequest,
    val kind: AccessDecisionKind,
    val reason: String = "",
)

data class AccessRequestsState(
    val availability: AccessReviewAvailability = AccessReviewAvailability.UNKNOWN,
    val filter: AccessRequestFilter = AccessRequestFilter.ALL,
    /** Lo ultimo leido del servidor, sin filtrar (pendientes primero, como llega). */
    val allRequests: List<AccessRequest> = emptyList(),
    /** Lo que ensena el catalogo: [allRequests] pasado por [filter]. */
    val requests: UiAsync<List<AccessRequest>> = UiAsync.Idle,
    val selectedId: String? = null,
    /** Borrador del motivo de rechazo de la solicitud abierta. Se vacia al abrir otra y tras decidir. */
    val rejectionReason: String = "",
    val pendingDecision: PendingAccessDecision? = null,
    /** `Success` lleva el mensaje de lo que se acaba de hacer. */
    val decisionState: UiAsync<String> = UiAsync.Idle,
    val refreshing: Boolean = false,
) {
    val canReview: Boolean get() = availability == AccessReviewAvailability.ALLOWED
    val selected: AccessRequest? get() = allRequests.firstOrNull { it.id == selectedId }
    val pendingCount: Int get() = allRequests.count { it.status == AccessRequestStatus.PENDING }
    val isDeciding: Boolean get() = decisionState is UiAsync.Loading
}

/** Pendientes primero y, dentro de cada grupo, la mas reciente arriba. El servidor ya ordena asi; aqui se fija tras una decision local. */
internal fun orderAccessRequests(requests: List<AccessRequest>): List<AccessRequest> =
    requests.sortedWith(
        compareBy<AccessRequest> { if (it.status == AccessRequestStatus.PENDING) 0 else 1 }
            .thenByDescending { if (it.status == AccessRequestStatus.PENDING) it.createdAt else it.resolvedAt.orEmpty() },
    )

/**
 * «Solicitudes de acceso», dentro de Sistema > Administradores (solo superadministradores).
 *
 * La visibilidad de la pestana sale de [AccessRequestsState.availability], que SOLO cambia con una respuesta del servidor
 * al listado. Al cambiar la sesion (otra persona entra en este equipo) el estado se vacia y se vuelve a preguntar: lo que
 * vio un superadministrador no se le ensena a quien entre despues.
 */
class AccessRequestsViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: AccessRequestsRepository,
    authRepository: AuthRepository,
) : MviViewModel<AccessRequestsState, AccessRequestsIntent>(
    initialState = AccessRequestsState(),
    dispatcherProvider = dispatcherProvider,
) {
    private var loadJob: Job? = null

    init {
        scope.launch {
            authRepository.observeSession().map { it?.userId }.distinctUntilChanged().collectLatest { userId ->
                loadJob?.cancel()
                updateState { AccessRequestsState() }
                if (userId != null) load()
            }
        }
    }

    override suspend fun handleIntent(intent: AccessRequestsIntent) {
        when (intent) {
            AccessRequestsIntent.Refresh -> load()
            is AccessRequestsIntent.UpdateFilter -> updateState { it.copy(filter = intent.filter).withVisibleRequests() }
            is AccessRequestsIntent.Select -> updateState { state ->
                when {
                    state.allRequests.none { it.id == intent.requestId } -> state
                    state.selectedId == intent.requestId -> state
                    else -> state.copy(selectedId = intent.requestId, rejectionReason = "", decisionState = UiAsync.Idle)
                }
            }
            is AccessRequestsIntent.AskApprove -> ask(intent.requestId, AccessDecisionKind.APPROVE)
            is AccessRequestsIntent.AskReject -> ask(intent.requestId, AccessDecisionKind.REJECT)
            is AccessRequestsIntent.UpdateRejectionReason -> updateState { state ->
                if (state.isDeciding) state else state.copy(rejectionReason = intent.value.take(AccessRequestRules.RESOLUTION_REASON_MAX))
            }
            AccessRequestsIntent.ConfirmDecision -> confirm()
            AccessRequestsIntent.DismissDecision -> if (!currentState().isDeciding) updateState { it.copy(pendingDecision = null) }
        }
    }

    /**
     * Solo una solicitud PENDIENTE admite decision, y solo si el servidor dijo que esta sesion puede revisar. Aprobar exige
     * ademas el correo verificado y la solicitud sin caducar ([AccessRequest.canBeApproved]; el servidor lo repite con un
     * 409). La excepcion es volver a RECHAZAR una ya rechazada cuya cuenta sigue existiendo (rechazos antiguos, o un
     * borrado que fallo): eso termina de borrar la cuenta.
     */
    private fun ask(requestId: String, kind: AccessDecisionKind) {
        updateState { state ->
            val request = state.allRequests.firstOrNull {
                it.id == requestId &&
                    when (kind) {
                        AccessDecisionKind.APPROVE -> it.canBeApproved
                        AccessDecisionKind.REJECT -> it.status == AccessRequestStatus.PENDING || it.status == AccessRequestStatus.REJECTED
                    }
            }
            if (!state.canReview || state.isDeciding || request == null) return@updateState state
            val reason = if (kind == AccessDecisionKind.REJECT && state.selectedId == requestId) state.rejectionReason.trim() else ""
            state.copy(pendingDecision = PendingAccessDecision(request, kind, reason), decisionState = UiAsync.Idle)
        }
    }

    private fun load() {
        loadJob?.cancel()
        loadJob = scope.launch {
            val hadData = currentState().requests.let { it is UiAsync.Success || it is UiAsync.Empty }
            updateState { if (hadData) it.copy(refreshing = true) else it.copy(requests = UiAsync.Loading) }
            when (val result = repository.listRequests()) {
                is AppResult.Success -> updateState { state ->
                    val ordered = orderAccessRequests(result.value)
                    val selectedId = state.selectedId?.takeIf { id -> ordered.any { it.id == id } } ?: ordered.firstOrNull()?.id
                    state.copy(
                        availability = AccessReviewAvailability.ALLOWED,
                        allRequests = ordered,
                        selectedId = selectedId,
                        // El borrador del motivo es de UNA solicitud: si la abierta cambia, no se arrastra a otra.
                        rejectionReason = if (selectedId == state.selectedId) state.rejectionReason else "",
                        refreshing = false,
                    ).withVisibleRequests()
                }
                is AppResult.Failure -> updateState { state ->
                    if (result.error is AppError.Authorization) {
                        // El servidor ha dicho que NO: la pestana desaparece y no queda nada leido en memoria.
                        AccessRequestsState(availability = AccessReviewAvailability.DENIED)
                    } else if (hadData) {
                        // No se pudo releer: lo ya leido se queda y la disponibilidad no cambia (no hay respuesta nueva).
                        state.copy(refreshing = false)
                    } else {
                        state.copy(requests = UiAsync.Error(result.error), refreshing = false)
                    }
                }
            }
        }
    }

    private suspend fun confirm() {
        val state = currentState()
        val pending = state.pendingDecision ?: return
        if (state.isDeciding || !state.canReview) return
        updateState { it.copy(pendingDecision = null, decisionState = UiAsync.Loading) }
        val result = when (pending.kind) {
            AccessDecisionKind.APPROVE -> repository.approve(pending.request.id)
            AccessDecisionKind.REJECT -> repository.reject(pending.request.id, pending.reason.trim().takeIf { it.isNotEmpty() })
        }
        when (result) {
            is AppResult.Success -> updateState { current ->
                val resolved = result.value
                val approved = resolved.status == AccessRequestStatus.APPROVED
                // Rechazar borra la cuenta y, con ella, la solicitud: la fila sale de la lista en vez de quedarse como
                // «rechazada» apuntando a una cuenta que ya no existe.
                val merged = if (approved) {
                    orderAccessRequests(current.allRequests.map { if (it.id == resolved.id) resolved else it })
                } else {
                    current.allRequests.filterNot { it.id == resolved.id }
                }
                current.copy(
                    allRequests = merged,
                    selectedId = if (approved) resolved.id else merged.firstOrNull()?.id,
                    rejectionReason = "",
                    decisionState = UiAsync.Success(
                        if (approved) {
                            "Solicitud aprobada: ${resolved.name.ifBlank { resolved.email }} ya es administrador."
                        } else {
                            "Solicitud rechazada y cuenta eliminada."
                        },
                    ),
                ).withVisibleRequests()
            }
            is AppResult.Failure -> {
                updateState { it.copy(decisionState = UiAsync.Error(result.error)) }
                // 403: ya no es superadministrador. Conflicto o no encontrada: otro la resolvio. En ambos casos manda el servidor.
                load()
            }
        }
    }

    private fun AccessRequestsState.withVisibleRequests(): AccessRequestsState {
        val visible = allRequests.filter { filter.status == null || it.status == filter.status }
        return copy(requests = if (visible.isEmpty()) UiAsync.Empty else UiAsync.Success(visible))
    }
}
