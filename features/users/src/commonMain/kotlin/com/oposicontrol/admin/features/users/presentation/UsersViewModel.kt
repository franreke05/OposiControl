package com.oposicontrol.admin.features.users.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
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
            usersRepository.observeUsers().collectLatest { users ->
                updateState {
                    it.copy(
                        users = if (users.isEmpty()) UiAsync.Empty else UiAsync.Success(users),
                    )
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
            UsersIntent.CreateNew -> updateState {
                it.copy(
                    selectedUserId = null,
                    editorName = "",
                    editorEmail = "",
                    editorRoleIds = emptySet(),
                    editorIsActive = true,
                    actionState = UiAsync.Idle,
                )
            }

            UsersIntent.DeleteSelected -> deleteSelected()
            UsersIntent.Save -> save()
            is UsersIntent.GrantTickets -> grantTickets(intent.amount)
            is UsersIntent.SelectUser -> selectUser(intent.userId)
            is UsersIntent.ToggleActive -> updateState { it.copy(editorIsActive = intent.value) }
            is UsersIntent.UpdateEmail -> updateState { it.copy(editorEmail = intent.value) }
            is UsersIntent.UpdateName -> updateState { it.copy(editorName = intent.value) }
            is UsersIntent.ToggleRole -> updateState {
                val next = if (intent.roleId in it.editorRoleIds) it.editorRoleIds - intent.roleId else it.editorRoleIds + intent.roleId
                it.copy(editorRoleIds = next)
            }
        }
    }

    private fun selectUser(userId: String) {
        val user = (currentState().users as? UiAsync.Success)?.value?.firstOrNull { it.id == userId } ?: return
        updateState {
            it.copy(
                selectedUserId = user.id,
                editorName = user.fullName,
                editorEmail = user.email,
                editorRoleIds = user.roleIds.toSet(),
                editorIsActive = user.isActive,
                actionState = UiAsync.Idle,
            )
        }
    }

    private suspend fun grantTickets(amount: Int) {
        val userId = currentState().selectedUserId ?: return
        updateState { it.copy(actionState = UiAsync.Loading) }
        when (val result = moderationRepository.grantTickets(userId, amount, "Ajuste manual")) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("tickets_grant", mapOf("user_id" to userId, "amount" to amount.toString()))
                updateState { st ->
                    val updated = (st.users as? UiAsync.Success)?.value?.map { u ->
                        if (u.id == userId) u.copy(ticketsBalance = result.value) else u
                    }
                    st.copy(
                        users = if (updated != null) UiAsync.Success(updated) else st.users,
                        actionState = UiAsync.Success(Unit),
                    )
                }
            }
        }
    }

    private suspend fun save() {
        analyticsTracker.trackScreen("users")
        updateState { it.copy(actionState = UiAsync.Loading) }
        val existingUser = (currentState().users as? UiAsync.Success)?.value?.firstOrNull { it.id == currentState().selectedUserId }
        val user = User(
            id = existingUser?.id.orEmpty(),
            fullName = currentState().editorName,
            email = currentState().editorEmail,
            roleIds = currentState().editorRoleIds.toList(),
            isActive = currentState().editorIsActive,
            createdAt = existingUser?.createdAt ?: Clock.System.now(),
        )
        when (val result = upsertUserUseCase(user)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> updateState {
                it.copy(
                    selectedUserId = result.value.id,
                    actionState = UiAsync.Success(Unit),
                )
            }
        }
    }

    private suspend fun deleteSelected() {
        val selectedUserId = currentState().selectedUserId ?: return
        updateState { it.copy(actionState = UiAsync.Loading) }
        when (val result = usersRepository.deleteUser(selectedUserId)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> updateState {
                it.copy(
                    selectedUserId = null,
                    editorName = "",
                    editorEmail = "",
                    editorRoleIds = emptySet(),
                    editorIsActive = true,
                    actionState = UiAsync.Success(Unit),
                )
            }
        }
    }
}
