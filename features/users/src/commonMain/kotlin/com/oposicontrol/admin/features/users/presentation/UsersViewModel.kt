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
    private var lastSuccessfulSave: UsersSaveKey? = null

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
            UsersIntent.CreateNew -> {
                lastSuccessfulSave = null
                updateState {
                    it.copy(
                        selectedUserId = null,
                        editorName = "",
                        editorEmail = "",
                        editorRoleIds = "",
                        editorIsActive = true,
                        actionState = UiAsync.Idle,
                    )
                }
            }

            UsersIntent.DeleteSelected -> deleteSelected()
            UsersIntent.Save -> save()
            is UsersIntent.SelectUser -> selectUser(intent.userId)
            is UsersIntent.ToggleActive -> updateEditor { it.copy(editorIsActive = intent.value) }
            is UsersIntent.UpdateEmail -> updateEditor { it.copy(editorEmail = intent.value) }
            is UsersIntent.UpdateName -> updateEditor { it.copy(editorName = intent.value) }
            is UsersIntent.UpdateRoleIds -> updateEditor { it.copy(editorRoleIds = intent.value) }
        }
    }

    private fun selectUser(userId: String) {
        val user = (currentState().users as? UiAsync.Success)?.value?.firstOrNull { it.id == userId } ?: return
        lastSuccessfulSave = null
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
        val state = currentState()
        if (state.actionState == UiAsync.Loading) return
        val roleIds = state.editorRoleIds
            .split(",")
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        val saveKey = UsersSaveKey(
            selectedUserId = state.selectedUserId,
            fullName = state.editorName.trim(),
            email = state.editorEmail.trim(),
            roleIds = roleIds,
            isActive = state.editorIsActive,
        )
        if (saveKey == lastSuccessfulSave) return

        analyticsTracker.trackScreen("users")
        updateState { it.copy(actionState = UiAsync.Loading) }
        val existingUser = (state.users as? UiAsync.Success)?.value?.firstOrNull { it.id == state.selectedUserId }
        val user = User(
            id = existingUser?.id.orEmpty(),
            fullName = saveKey.fullName,
            email = saveKey.email,
            roleIds = saveKey.roleIds,
            isActive = saveKey.isActive,
            createdAt = existingUser?.createdAt ?: Clock.System.now(),
        )
        when (val result = upsertUserUseCase(user)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                val saved = result.value
                lastSuccessfulSave = UsersSaveKey(
                    selectedUserId = saved.id,
                    fullName = saved.fullName,
                    email = saved.email,
                    roleIds = saved.roleIds,
                    isActive = saved.isActive,
                )
                updateState {
                    it.copy(
                        selectedUserId = saved.id,
                        editorName = saved.fullName,
                        editorEmail = saved.email,
                        editorRoleIds = saved.roleIds.joinToString(","),
                        editorIsActive = saved.isActive,
                        actionState = UiAsync.Success(Unit),
                    )
                }
            }
        }
    }

    private suspend fun deleteSelected() {
        if (currentState().actionState == UiAsync.Loading) return
        val selectedUserId = currentState().selectedUserId ?: return
        updateState { it.copy(actionState = UiAsync.Loading) }
        when (val result = deleteUserUseCase(selectedUserId)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                lastSuccessfulSave = null
                updateState {
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

    private fun updateEditor(transform: (UsersState) -> UsersState) {
        lastSuccessfulSave = null
        updateState { transform(it).copy(actionState = UiAsync.Idle) }
    }
}

private data class UsersSaveKey(
    val selectedUserId: String?,
    val fullName: String,
    val email: String,
    val roleIds: List<String>,
    val isActive: Boolean,
)
