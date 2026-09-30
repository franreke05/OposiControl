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
                it.copy(
                    ticketsDialogOpen = true,
                    ticketsInput = "",
                    // Una clave por apertura de diálogo: estable durante toda la
                    // operación, distinta para la siguiente.
                    ticketsIdempotencyKey = "grant:${it.selectedUserId.orEmpty()}:${Clock.System.now().toEpochMilliseconds()}",
                    actionState = UiAsync.Idle,
                )
            }
            UsersIntent.DismissTicketsDialog -> updateState {
                it.copy(ticketsDialogOpen = false, ticketsInput = "", ticketsIdempotencyKey = null)
            }
            is UsersIntent.UpdateTicketsInput -> updateState { it.copy(ticketsInput = intent.value) }
            UsersIntent.ConfirmTickets -> {
                val amount = currentState().ticketsInput.toIntOrNull() ?: return
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
            UsersIntent.ConfirmWarning -> sendWarning()

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
        loadUserDetail(user.id)
    }

    private suspend fun loadUserDetail(userId: String) {
        updateState { it.copy(detail = UiAsync.Loading) }
        when (val result = usersRepository.getUserDetail(userId)) {
            is AppResult.Failure -> updateState { it.copy(detail = UiAsync.Error(result.error)) }
            is AppResult.Success -> updateState { it.copy(detail = UiAsync.Success(result.value)) }
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
        val existing = currentState().selectedUser ?: return
        val blockers = currentState().saveBlockers
        if (blockers.isNotEmpty()) {
            updateState { it.copy(actionState = UiAsync.Error(AppError.Validation(blockers.first()))) }
            return
        }
        updateState { it.copy(actionState = UiAsync.Loading) }
        val user = existing.copy(
            fullName = currentState().editorName.trim(),
            roleIds = currentState().editorRoleIds.toList(),
        )
        when (val result = upsertUserUseCase(user)) {
            is AppResult.Failure -> {
                updateState { it.copy(actionState = UiAsync.Error(result.error)) }
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
                        if (it.id == result.value.id) result.value else it
                    }
                    state.copy(
                        users = UiAsync.Success(updated),
                        selectedUserId = result.value.id,
                        editorName = result.value.fullName,
                        editorRoleIds = result.value.roleIds.toSet(),
                        actionState = UiAsync.Success(Unit),
                    )
                }
            }
        }
    }

    private suspend fun refreshUserRow(userId: String) {
        val fresh = (usersRepository.getUser(userId) as? AppResult.Success)?.value ?: return
        updateState { state ->
            if (state.loadedUsers.none { it.id == fresh.id }) return@updateState state
            state.copy(users = UiAsync.Success(state.loadedUsers.map { if (it.id == fresh.id) fresh.keepingKnownFrom(it) else it }))
        }
    }

    private suspend fun grantTickets(amount: Int) {
        val userId = currentState().selectedUserId ?: return
        // La clave se fija al abrir el diálogo, no al pulsar: así un doble clic o
        // un reintento de red reutilizan la misma y el backend devuelve el
        // movimiento original en lugar de conceder los tickets otra vez.
        val idempotencyKey = currentState().ticketsIdempotencyKey ?: return updateState {
            it.copy(
                actionState = UiAsync.Error(
                    AppError.Validation("Vuelve a abrir el diálogo para conceder OposiTickets."),
                ),
            )
        }
        updateState { it.copy(actionState = UiAsync.Loading) }
        when (val result = moderationRepository.grantTickets(userId, amount, "Ajuste manual", idempotencyKey)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("tickets_grant", mapOf("user_id" to userId, "amount" to amount.toString()))
                updateState { state ->
                    val updated = state.loadedUsers.map { user ->
                        if (user.id == userId) user.copy(ticketsBalance = result.value) else user
                    }
                    state.copy(
                        users = if (updated.isEmpty()) state.users else UiAsync.Success(updated),
                        actionState = UiAsync.Success(Unit),
                    )
                }
            }
        }
    }

    private suspend fun sendWarning() {
        val userId = currentState().selectedUserId ?: return
        val message = currentState().warningInput.trim().ifBlank { return }
        updateState { it.copy(warningDialogOpen = false, warningInput = "", actionState = UiAsync.Loading) }
        when (val result = moderationRepository.warnUser(userId, message)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("user_warned", mapOf("user_id" to userId))
                updateState { it.copy(actionState = UiAsync.Success(Unit)) }
            }
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
    createdAt = createdAt ?: previous.createdAt,
    isRegistered = isRegistered ?: previous.isRegistered,
)
