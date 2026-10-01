package com.oposicontrol.admin.features.users.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.User
import com.oposicontrol.admin.domain.repository.ModerationRepository
import com.oposicontrol.admin.domain.repository.RolesRepository
import com.oposicontrol.admin.domain.repository.UsersRepository
import com.oposicontrol.admin.domain.usecase.users.UpsertUserUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.time.Clock

class UsersViewModel(
    dispatcherProvider: DispatcherProvider,
    private val usersRepository: UsersRepository,
    rolesRepository: RolesRepository,
    private val upsertUserUseCase: UpsertUserUseCase,
    private val moderationRepository: ModerationRepository,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<UsersState, UsersIntent>(
    initialState = UsersState(),
    dispatcherProvider = dispatcherProvider,
) {
    /** Carga de la ficha en curso: se cancela al cambiar de usuario (MASTER5-OC-002). */
    private var detailJob: Job? = null

    /** Guardados en vuelo por usuario: un segundo Save del mismo usuario (aunque se cambie de fila y se vuelva) no se lanza. */
    private val savesInFlight = mutableSetOf<String>()
    private var grantInFlight = false
    private var warningInFlight = false
    private var keySequence = 0

    init {
        scope.launch {
            // Un fallo del backend ya NO se confunde con «no hay usuarios»: el
            // origen devuelve el resultado y aquí se separan los dos casos. Antes
            // se emitía una lista vacía ante cualquier error, así que una caída
            // del servidor se pintaba como censo vacío —con su botón de crear— y
            // nadie se enteraba de que el sistema estaba caído.
            usersRepository.observeUsers().collectLatest { result ->
                updateState { state ->
                    when (result) {
                        is AppResult.Failure -> state.copy(users = UiAsync.Error(result.error))
                        is AppResult.Success -> state.copy(
                            users = if (result.value.isEmpty()) {
                                UiAsync.Empty
                            } else {
                                UiAsync.Success(result.value)
                            },
                        )
                    }
                }
            }
        }
        scope.launch {
            rolesRepository.observeRoles().collectLatest { roles ->
                updateState { it.copy(availableRoles = roles) }
            }
        }
    }

    override suspend fun handleIntent(intent: UsersIntent) {
        when (intent) {
            // El alta no se hace desde el panel. Se explica en vez de abrir un
            // editor vacío que al guardar devolvería un error del cliente.
            UsersIntent.CreateNew -> updateState { it.copy(createNoticeVisible = true) }
            UsersIntent.DismissCreateNotice -> updateState { it.copy(createNoticeVisible = false) }

            is UsersIntent.SelectUser -> requestSelectUser(intent.userId)
            UsersIntent.ConfirmDiscardChanges -> {
                val pending = currentState().pendingSelectionUserId ?: return
                updateState { it.copy(pendingSelectionUserId = null) }
                selectUser(pending)
            }
            UsersIntent.CancelPendingSelection -> updateState { it.copy(pendingSelectionUserId = null) }

            is UsersIntent.UpdateName -> updateState { it.copy(editorName = intent.value) }
            is UsersIntent.ToggleRole -> updateState {
                val next = if (intent.roleId in it.editorRoleIds) {
                    it.editorRoleIds - intent.roleId
                } else {
                    it.editorRoleIds + intent.roleId
                }
                it.copy(editorRoleIds = next, actionState = UiAsync.Idle)
            }
            UsersIntent.Save -> save()
            UsersIntent.DiscardChanges -> updateState { state ->
                val user = state.selectedUser ?: return@updateState state
                state.copy(
                    editorName = user.fullName,
                    editorRoleIds = user.roleIds.toSet(),
                    actionState = UiAsync.Idle,
                )
            }

            is UsersIntent.GrantTickets -> grantTickets(intent.amount)
            UsersIntent.OpenTicketsDialog -> updateState {
                // Si hay una concesión con resultado desconocido para ESTE usuario, se reabre con su importe y su clave:
                // confirmar lo mismo la reenvía idempotente (MASTER5-IDEM-001). Si no, una clave nueva por apertura.
                val pending = it.pendingGrant
                it.copy(
                    ticketsDialogOpen = true,
                    ticketsInput = pending?.amount?.toString().orEmpty(),
                    ticketsIdempotencyKey = pending?.key ?: mintGrantKey(it.selectedUserId.orEmpty(), null),
                    actionState = UiAsync.Idle,
                )
            }
            UsersIntent.DismissTicketsDialog -> updateState {
                // pendingGrant NO se toca: cerrar el diálogo no dice si el servidor aplicó la concesión.
                it.copy(ticketsDialogOpen = false, ticketsInput = "", ticketsIdempotencyKey = null)
            }
            is UsersIntent.UpdateTicketsInput -> updateState { it.copy(ticketsInput = intent.value) }
            UsersIntent.ConfirmTickets -> {
                val amount = currentState().ticketsInput.toIntOrNull() ?: return
                if (grantInFlight) return
                updateState { it.copy(ticketsDialogOpen = false, ticketsInput = "") }
                grantTickets(amount)
            }

            UsersIntent.OpenWarningDialog -> updateState {
                it.copy(warningDialogOpen = true, warningInput = "", actionState = UiAsync.Idle)
            }
            UsersIntent.DismissWarningDialog -> updateState {
                it.copy(warningDialogOpen = false, warningInput = "")
            }
            is UsersIntent.UpdateWarningInput -> updateState { it.copy(warningInput = intent.value) }
            UsersIntent.ConfirmWarning -> if (!warningInFlight) sendWarning()

            is UsersIntent.UpdateSearch -> updateState { it.copy(searchQuery = intent.value) }
        }
    }

    /**
     * Cambio de usuario desde la lista.
     *
     * Si hay cambios sin guardar la selección queda **en espera** y la pantalla
     * pregunta. La versión anterior sobrescribía el formulario en silencio: un
     * nombre a medio corregir desaparecía al pulsar otra fila sin ningún aviso.
     */
    private suspend fun requestSelectUser(userId: String) {
        val state = currentState()
        if (userId == state.selectedUserId) return
        if (state.isDirty) {
            updateState { it.copy(pendingSelectionUserId = userId) }
            return
        }
        selectUser(userId)
    }

    private suspend fun selectUser(userId: String) {
        val user = currentState().loadedUsers.firstOrNull { it.id == userId } ?: return
        updateState {
            it.copy(
                selectedUserId = user.id,
                editorName = user.fullName,
                editorRoleIds = user.roleIds.toSet(),
                actionState = UiAsync.Idle,
                createNoticeVisible = false,
            )
        }
        // La ficha se carga aparte del formulario de edición: es sólo lectura,
        // tiene su propio estado de carga y su propio error, y no debe impedir
        // editar nombre/roles si alguna de sus secciones falla.
        reloadUserDetail(user.id)
    }

    /**
     * Lanza la carga de la ficha cancelando la anterior, y descarta el resultado si entre tanto se seleccionó otro usuario
     * (MASTER5-OC-002: una respuesta lenta de A ya no pisa la ficha de B).
     */
    private fun reloadUserDetail(userId: String, silent: Boolean = false) {
        detailJob?.cancel()
        if (!silent) updateState { it.copy(detail = UiAsync.Loading) }
        detailJob = scope.launch {
            val result = usersRepository.getUserDetail(userId)
            updateState { state ->
                if (state.selectedUserId != userId) {
                    state
                } else {
                    when (result) {
                        // Relectura silenciosa fallida: se conserva lo que ya se mostraba.
                        is AppResult.Failure -> if (silent) state else state.copy(detail = UiAsync.Error(result.error))
                        is AppResult.Success -> {
                            val balance = result.value.economy?.ticketBalance
                            val rows = if (silent && balance != null) {
                                state.loadedUsers.map { if (it.id == userId) it.copy(ticketsBalance = balance) else it }
                            } else {
                                null
                            }
                            state.copy(
                                detail = UiAsync.Success(result.value),
                                users = if (rows.isNullOrEmpty()) state.users else UiAsync.Success(rows),
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Guarda nombre y roles del usuario seleccionado.
     *
     * Se parte de la fila existente con `copy` en vez de construir un `User`
     * nuevo: así los campos que esta pantalla no edita —alta, saldo, tipo de
     * cuenta— viajan tal cual en lugar de fabricarse. La versión anterior ponía
     * `createdAt = Clock.System.now()` cuando no encontraba el usuario, es decir,
     * inventaba una fecha de alta.
     */
    private suspend fun save() {
        val snapshot = currentState()
        val existing = snapshot.selectedUser ?: return
        // Sin guardado doble: un segundo Save del mismo usuario mientras el primero vuela no lanza otro upsert.
        if (existing.id in savesInFlight || snapshot.actionState is UiAsync.Loading) return
        val blockers = snapshot.saveBlockers
        if (blockers.isNotEmpty()) {
            updateState { it.copy(actionState = UiAsync.Error(AppError.Validation(blockers.first()))) }
            return
        }
        savesInFlight += existing.id
        try {
            updateState { it.copy(actionState = UiAsync.Loading) }
            val user = existing.copy(
                fullName = snapshot.editorName.trim(),
                roleIds = snapshot.editorRoleIds.toList(),
            )
            // `previous` = la fila que el editor leyó: el guardado sólo escribe lo que cambió (MASTER5-API-009).
            when (val result = upsertUserUseCase(user, previous = existing)) {
                is AppResult.Failure -> {
                    // Sólo se informa en el editor si sigue mostrando a este usuario (MASTER5-OC-002).
                    updateState { if (it.selectedUserId == user.id) it.copy(actionState = UiAsync.Error(result.error)) else it }
                    // El guardado no es atomico (PATCH nombre, POST roles, GET): un fallo parcial deja el servidor con
                    // parte del cambio. Se relee la fila para que la lista y la ficha no diverjan; el editor conserva
                    // lo tecleado para poder reintentar (el "sucio" se calcula contra la fila releida).
                    refreshUserRow(user.id)
                }
                is AppResult.Success -> {
                    analyticsTracker.trackAction("user_saved", mapOf("user_id" to user.id))
                    updateState { state ->
                        // El flujo de la lista no vuelve a emitir tras un guardado, así
                        // que la fila se refresca aquí; si no, la tabla seguiría
                        // enseñando el nombre antiguo hasta recargar la pantalla.
                        val updated = state.loadedUsers.map {
                            if (it.id == result.value.id) result.value.keepingKnownFrom(it) else it
                        }
                        val withRows = state.copy(users = UiAsync.Success(updated))
                        if (state.selectedUserId == result.value.id) {
                            // El editor sólo se repone si sigue en este usuario: un save(A) lento ya no arrastra el
                            // editor de vuelta a A tras cambiar a B.
                            withRows.copy(
                                editorName = result.value.fullName,
                                editorRoleIds = result.value.roleIds.toSet(),
                                actionState = UiAsync.Success(Unit),
                            )
                        } else {
                            withRows
                        }
                    }
                    // MASTER5-OC-003: la ficha (nombre/roles/estado) se relee tras guardar, si sigue seleccionado.
                    if (currentState().selectedUserId == user.id) reloadUserDetail(user.id)
                }
            }
        } finally {
            savesInFlight -= existing.id
        }
    }

    private suspend fun refreshUserRow(userId: String) {
        val fresh = (usersRepository.getUser(userId) as? AppResult.Success)?.value ?: return
        updateState { state ->
            if (state.loadedUsers.none { it.id == fresh.id }) return@updateState state
            state.copy(users = UiAsync.Success(state.loadedUsers.map { if (it.id == fresh.id) fresh.keepingKnownFrom(it) else it }))
        }
    }

    /** Clave nueva: usuario, importe (si se conoce), instante y secuencia local (dos aperturas en el mismo ms no colisionan). */
    private fun mintGrantKey(userId: String, amount: Int?): String =
        "grant:$userId:${amount ?: "_"}:${Clock.System.now().toEpochMilliseconds()}:${++keySequence}"

    private suspend fun grantTickets(amount: Int) {
        if (grantInFlight) return
        val snapshot = currentState()
        val userId = snapshot.selectedUserId ?: return
        // MASTER5-IDEM-001: la clave identifica la OPERACIÓN, no el intento ni la apertura del diálogo.
        //  - Hay una concesión con resultado desconocido para este usuario y el importe es el mismo: se reutiliza su clave
        //    (un reintento tras un timeout no concede dos veces; el backend devuelve el movimiento original).
        //  - Importe distinto: es otra operación, clave nueva.
        //  - Sin pendiente: la clave fijada al abrir el diálogo (doble clic = misma clave).
        val userPendings = snapshot.pendingGrants.filter { it.userId == userId }
        val sameAmount = userPendings.lastOrNull { it.amount == amount }
        val idempotencyKey = when {
            sameAmount != null -> sameAmount.key
            userPendings.isNotEmpty() -> mintGrantKey(userId, amount)
            else -> snapshot.ticketsIdempotencyKey
        } ?: return updateState {
            it.copy(
                actionState = UiAsync.Error(
                    AppError.Validation("Vuelve a abrir el diálogo para conceder OposiTickets."),
                ),
            )
        }
        grantInFlight = true
        try {
            updateState { it.copy(actionState = UiAsync.Loading) }
            when (val result = moderationRepository.grantTickets(userId, amount, "Ajuste manual", idempotencyKey)) {
                is AppResult.Failure -> updateState {
                    // El resultado es desconocido (puede haberse aplicado): se conserva la clave para el reintento.
                    it.copy(
                        // Una pendiente por (usuario, importe): la de otro importe sigue viva (MASTER6-IDEM-002).
                        pendingGrants = it.pendingGrants.filterNot { p -> p.userId == userId && p.amount == amount } +
                            PendingGrant(userId, amount, idempotencyKey),
                        actionState = if (it.selectedUserId == userId) UiAsync.Error(result.error) else it.actionState,
                    )
                }
                is AppResult.Success -> {
                    analyticsTracker.trackAction("tickets_grant", mapOf("user_id" to userId, "amount" to amount.toString()))
                    updateState { state ->
                        val updated = state.loadedUsers.map { user ->
                            if (user.id == userId) user.copy(ticketsBalance = result.value) else user
                        }
                        // MASTER5-OC-003: la ficha (saldo de Economía) se actualiza con el saldo devuelto.
                        val detail = (state.detail as? UiAsync.Success)?.value
                        val economy = detail?.economy
                        val patchedDetail = if (detail != null && economy != null && state.selectedUserId == userId) {
                            UiAsync.Success(detail.copy(economy = economy.copy(ticketBalance = result.value)))
                        } else {
                            state.detail
                        }
                        state.copy(
                            users = if (updated.isEmpty()) state.users else UiAsync.Success(updated),
                            detail = patchedDetail,
                            // Solo se resuelve la concesion que SE ha enviado (por su clave), no las demas del usuario.
                            pendingGrants = state.pendingGrants.filterNot { it.key == idempotencyKey },
                            // Concesión resuelta: su clave no puede reutilizarse para otra operación.
                            ticketsIdempotencyKey = if (state.ticketsDialogOpen) state.ticketsIdempotencyKey else null,
                            actionState = if (state.selectedUserId == userId) UiAsync.Success(Unit) else state.actionState,
                        )
                    }
                    // MASTER6-IDEM-001: el saldo devuelto por la concesion es el del asiento; en una repeticion idempotente
                    // es el ORIGINAL. Se relee el saldo vigente de la ficha sin tocar la pantalla (el backend ya devuelve el
                    // saldo actual, pero el cliente no presenta como vigente lo que no ha leido de la cartera).
                    if (currentState().selectedUserId == userId) reloadUserDetail(userId, silent = true)
                }
            }
        } finally {
            grantInFlight = false
        }
    }

    private suspend fun sendWarning() {
        val userId = currentState().selectedUserId ?: return
        val message = currentState().warningInput.trim().ifBlank { return }
        warningInFlight = true
        try {
            updateState { it.copy(warningDialogOpen = false, warningInput = "", actionState = UiAsync.Loading) }
            when (val result = moderationRepository.warnUser(userId, message)) {
                is AppResult.Failure -> updateState {
                    if (it.selectedUserId == userId) it.copy(actionState = UiAsync.Error(result.error)) else it
                }
                is AppResult.Success -> {
                    analyticsTracker.trackAction("user_warned", mapOf("user_id" to userId))
                    updateState { if (it.selectedUserId == userId) it.copy(actionState = UiAsync.Success(Unit)) else it }
                }
            }
        } finally {
            warningInFlight = false
        }
    }
}

/**
 * Una fila refrescada puede venir sin campos que la lista si trae (p. ej. `ticketsBalance`, ausente en `AdminUser`):
 * lo que falta (null / vacio) se conserva de la fila previa en vez de sobrescribirlo con un dato fabricado.
 */
internal fun User.keepingKnownFrom(previous: User): User = copy(
    ticketsBalance = ticketsBalance ?: previous.ticketsBalance,
    fullName = fullName.ifBlank { previous.fullName },
    email = email.ifBlank { previous.email },
    username = username ?: previous.username,
    createdAt = createdAt ?: previous.createdAt,
    isRegistered = isRegistered ?: previous.isRegistered,
)
