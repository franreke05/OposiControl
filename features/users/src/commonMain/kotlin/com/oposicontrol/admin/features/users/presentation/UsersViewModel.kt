package com.oposicontrol.admin.features.users.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.User
import com.oposicontrol.admin.domain.usecase.users.DeleteUserUseCase
import com.oposicontrol.admin.domain.usecase.users.ObserveUsersUseCase
import com.oposicontrol.admin.domain.usecase.users.UpsertUserUseCase
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.time.Clock

class UsersViewModel(
    dispatcherProvider: DispatcherProvider,
    observeUsersUseCase: ObserveUsersUseCase,
    private val upsertUserUseCase: UpsertUserUseCase,
    private val deleteUserUseCase: DeleteUserUseCase,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<UsersState, UsersIntent>(
    initialState = UsersState(),
    dispatcherProvider = dispatcherProvider,
) {
    init {
        scope.launch {
            observeUsersUseCase().collectLatest { users ->
                updateState {
                    it.copy(
                        users = if (users.isEmpty()) UiAsync.Empty else UiAsync.Success(users),
                    )
                }
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
                    editorRoleIds = "",
                    editorIsActive = true,
                    actionState = UiAsync.Idle,
                )
            }

            UsersIntent.DeleteSelected -> deleteSelected()
            UsersIntent.Save -> save()
            is UsersIntent.SelectUser -> selectUser(intent.userId)
            is UsersIntent.ToggleActive -> updateState { it.copy(editorIsActive = intent.value) }
            is UsersIntent.UpdateEmail -> updateState { it.copy(editorEmail = intent.value) }
            is UsersIntent.UpdateName -> updateState { it.copy(editorName = intent.value) }
            is UsersIntent.UpdateRoleIds -> updateState { it.copy(editorRoleIds = intent.value) }
        }
    }

    private fun selectUser(userId: String) {
        val user = (currentState().users as? UiAsync.Success)?.value?.firstOrNull { it.id == userId } ?: return
        updateState {
            it.copy(
                selectedUserId = user.id,
                editorName = user.fullName,
                editorEmail = user.email,
                editorRoleIds = user.roleIds.joinToString(","),
                editorIsActive = user.isActive,
                actionState = UiAsync.Idle,
            )
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
            roleIds = currentState().editorRoleIds.split(",").map(String::trim).filter(String::isNotBlank),
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
        when (val result = deleteUserUseCase(selectedUserId)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> updateState {
                it.copy(
                    selectedUserId = null,
                    editorName = "",
                    editorEmail = "",
                    editorRoleIds = "",
                    editorIsActive = true,
                    actionState = UiAsync.Success(Unit),
                )
            }
        }
    }
}
