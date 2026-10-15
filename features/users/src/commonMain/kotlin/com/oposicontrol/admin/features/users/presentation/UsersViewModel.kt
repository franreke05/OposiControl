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
import com.oposicontrol.admin.domain.model.AccountStatus

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

    /** Lectura de la lista en curso: un refresco cancela la anterior en vez de dejar dos respuestas compitiendo. */
    private var usersJob: Job? = null

    /** Guardados en vuelo por usuario: un segundo Save del mismo usuario (aunque se cambie de fila y se vuelva) no se lanza. */
    private val savesInFlight = mutableSetOf<String>()
    private var warningInFlight = false
    private var accountStatusInFlight = false

    /** Último usuario por el que ya se releyó la lista al no encontrarlo: la relectura es una, no un bucle. */
    private var missingReloadFor: String? = null

    init {
        loadUsers()
        scope.launch {
            rolesRepository.observeRoles().collectLatest { result ->
                // Un fallo deja el catálogo como estaba (vacío la primera vez): la ficha ya trata «sin catálogo» como
                // «no se ha podido cargar» y la lista no afirma de nadie que sea opositor ni administrador.
                if (result is AppResult.Success) updateState { it.copy(availableRoles = result.value) }
            }
        }
    }

    /**
     * Lee la lista de usuarios. La primera vez no hay nada que enseñar y la lista pasa a «cargando»; después, lo ya leído
     * se queda en pantalla mientras se relee, y si la relectura falla se conserva y se avisa.
     */
    private fun loadUsers() {
        usersJob?.cancel()
        usersJob = scope.launch {
            val previous = currentState().loadedUsers.associateBy { it.id }
            val hadData = currentState().users.let { it is UiAsync.Success || it is UiAsync.Empty }
            updateState {
                if (hadData) it.copy(refreshing = true, refreshFailed = false) else it.copy(users = UiAsync.Loading, refreshFailed = false)
            }
            // Un fallo del backend ya NO se confunde con «no hay usuarios»: el
            // origen devuelve el resultado y aquí se separan los dos casos. Antes
            // se emitía una lista vacía ante cualquier error, así que una caída
            // del servidor se pintaba como censo vacío —con su botón de crear— y
            // nadie se enteraba de que el sistema estaba caído.
            usersRepository.observeUsers().collectLatest { result ->
                // Se mide ANTES de aplicar la lista nueva: contra ella, un nombre cambiado por otro administrador
                // parecería algo tecleado aquí, y guardar después lo pisaría sin que nadie lo hubiera pedido.
                val wasDirty = currentState().isDirty
                updateState { state ->
                    when (result) {
                        is AppResult.Failure ->
                            if (hadData) {
                                state.copy(refreshing = false, refreshFailed = true)
                            } else {
                                state.copy(users = UiAsync.Error(result.error), refreshing = false)
                            }
                        is AppResult.Success -> {
                            // Una fila releída sin saldo o sin nombre no pisa el dato que ya se conocía (MASTER2-OC-004).
                            val merged = result.value.map { fresh -> previous[fresh.id]?.let { fresh.keepingKnownFrom(it) } ?: fresh }
                            state.copy(
                                users = if (merged.isEmpty()) UiAsync.Empty else UiAsync.Success(merged),
                                refreshing = false,
                                refreshFailed = false,
                            )
                        }
                    }
                }
                if (result is AppResult.Success && !wasDirty) {
                    updateState { state ->
                        val user = state.selectedUser ?: return@updateState state
                        state.copy(editorName = user.fullName, editorRoleIds = user.roleIds.toSet())
                    }
                }
                openPendingUser()
            }
        }
    }

    override suspend fun handleIntent(intent: UsersIntent) {
        when (intent) {
            UsersIntent.Refresh -> loadUsers()

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

            UsersIntent.OpenWarningDialog -> updateState {
                it.copy(warningDialogOpen = true, warningInput = "", actionState = UiAsync.Idle)
            }
            UsersIntent.DismissWarningDialog -> updateState {
                it.copy(warningDialogOpen = false, warningInput = "")
            }
            is UsersIntent.UpdateWarningInput -> updateState { it.copy(warningInput = intent.value) }
            UsersIntent.ConfirmWarning -> if (!warningInFlight) sendWarning()

            UsersIntent.OpenAccountStatusDialog -> updateState { state ->
                when (state.selectedAccountStatus) {
                    AccountStatus.ACTIVE -> state.copy(accountStatusDialogOpen = true, accountStatusBlocks = true, accountStatusReason = "", actionState = UiAsync.Idle)
                    AccountStatus.BLOCKED -> state.copy(accountStatusDialogOpen = true, accountStatusBlocks = false, accountStatusReason = "", actionState = UiAsync.Idle)
                    // Eliminación pedida por su dueño, o estado desconocido: no hay nada que ofrecer.
                    else -> state
                }
            }
            UsersIntent.DismissAccountStatusDialog -> updateState { it.copy(accountStatusDialogOpen = false, accountStatusReason = "") }
            is UsersIntent.UpdateAccountStatusReason -> updateState {
                it.copy(accountStatusReason = intent.value.take(ACCOUNT_STATUS_REASON_MAX))
            }
            UsersIntent.ConfirmAccountStatus -> if (!accountStatusInFlight) changeAccountStatus()

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
        if (userId == state.selectedUserId) {
            updateState { it.copy(pendingOpenUserId = null, openUserMissing = false) }
            return
        }
        // Pedido desde otra pantalla antes de que la lista haya llegado: se guarda y se abre al cargar.
        if (state.loadedUsers.none { it.id == userId }) {
            val stillLoading = state.users is UiAsync.Loading || state.users is UiAsync.Idle
            // Con la lista caída no se afirma que el usuario «no está»: lo que falla es la lista, y ya lo dice ella.
            val listRead = state.users is UiAsync.Success || state.users is UiAsync.Empty
            // La lista se leyó antes en la sesión y puede ser anterior a esa cuenta (un administrador recién aprobado):
            // se relee UNA vez antes de afirmar que no está. [openPendingUser] decide al llegar la relectura.
            if (listRead && missingReloadFor != userId) {
                missingReloadFor = userId
                updateState { it.copy(pendingOpenUserId = userId, openUserMissing = false) }
                loadUsers()
                return
            }
            updateState { it.copy(pendingOpenUserId = userId.takeIf { stillLoading }, openUserMissing = listRead) }
            return
        }
        if (state.isDirty) {
            updateState { it.copy(pendingSelectionUserId = userId) }
            return
        }
        selectUser(userId)
    }

    /** Abre al usuario que se pidió desde otra pantalla, ahora que la lista ha llegado (o dice que no está). */
    private suspend fun openPendingUser() {
        val state = currentState()
        val wanted = state.pendingOpenUserId ?: return
        when {
            state.users is UiAsync.Loading || state.users is UiAsync.Idle -> Unit
            state.users is UiAsync.Error -> updateState { it.copy(pendingOpenUserId = null) }
            // La relectura está en curso: se espera a su respuesta.
            state.refreshing -> Unit
            // La relectura falló: lo que no se sabe es la lista, no que la persona falte.
            state.refreshFailed -> updateState { it.copy(pendingOpenUserId = null) }
            state.loadedUsers.any { it.id == wanted } -> {
                updateState { it.copy(pendingOpenUserId = null) }
                requestSelectUser(wanted)
            }
            else -> updateState { it.copy(pendingOpenUserId = null, openUserMissing = true) }
        }
    }

    private suspend fun selectUser(userId: String) {
        val user = currentState().loadedUsers.firstOrNull { it.id == userId } ?: return
        updateState {
            it.copy(
                selectedUserId = user.id,
                pendingOpenUserId = null,
                openUserMissing = false,
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
                        // Relectura silenciosa fallida: se conserva lo que ya se mostraba, salvo que cancelase una carga en
                        // curso (MASTER7-OC-006): entonces la ficha quedaria en Loading sin ningun job que la resuelva.
                        is AppResult.Failure -> if (silent && state.detail !is UiAsync.Loading) {
                            state
                        } else {
                            state.copy(detail = UiAsync.Error(result.error))
                        }
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
                    val fresh = refreshUserRow(user.id)
                    // RC-07 (FA8): un 409 VERSION_CONFLICT significa que otro admin cambio los roles. El editor vuelve a
                    // los roles releidos: si no, el siguiente Save mandaria la seleccion vieja con `expectedRoleIds`
                    // frescos y revertiria en silencio el cambio del otro admin. El nombre tecleado se conserva.
                    val conflict = (result.error as? AppError.Validation)?.code.equals("VERSION_CONFLICT", ignoreCase = true)
                    if (conflict && fresh != null) {
                        updateState { if (it.selectedUserId == fresh.id) it.copy(editorRoleIds = fresh.roleIds.toSet()) else it }
                    }
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

    private suspend fun refreshUserRow(userId: String): User? {
        val fresh = (usersRepository.getUser(userId) as? AppResult.Success)?.value ?: return null
        updateState { state ->
            if (state.loadedUsers.none { it.id == fresh.id }) return@updateState state
            state.copy(users = UiAsync.Success(state.loadedUsers.map { if (it.id == fresh.id) fresh.keepingKnownFrom(it) else it }))
        }
        return fresh
    }

    private suspend fun changeAccountStatus() {
        val state = currentState()
        if (!state.accountStatusDialogOpen) return
        val userId = state.selectedUserId ?: return
        val reason = state.accountStatusReason.trim().ifBlank { return }
        val blocks = state.accountStatusBlocks
        accountStatusInFlight = true
        try {
            updateState { it.copy(accountStatusDialogOpen = false, accountStatusReason = "", actionState = UiAsync.Loading) }
            when (val result = moderationRepository.setAccountBlocked(userId, blocks, reason)) {
                // El motivo del servidor (no es superadministrador, es su propia cuenta…) se enseña tal cual.
                is AppResult.Failure -> updateState {
                    if (it.selectedUserId == userId) it.copy(actionState = UiAsync.Error(result.error)) else it
                }
                is AppResult.Success -> {
                    analyticsTracker.trackAction(if (blocks) "user_blocked" else "user_unblocked", mapOf("user_id" to userId))
                    updateState { if (it.selectedUserId == userId) it.copy(actionState = UiAsync.Success(Unit)) else it }
                    // El estado nuevo lo dice el servidor: se relee la fila y la ficha, no se pinta lo que se pidió.
                    refreshUserRow(userId)
                    if (currentState().selectedUserId == userId) reloadUserDetail(userId, silent = true)
                }
            }
        } finally {
            accountStatusInFlight = false
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
