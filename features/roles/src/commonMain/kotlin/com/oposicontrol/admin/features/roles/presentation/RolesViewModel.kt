package com.oposicontrol.admin.features.roles.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.Permission
import com.oposicontrol.admin.domain.model.Role
import com.oposicontrol.admin.domain.repository.RolesRepository
import com.oposicontrol.admin.domain.usecase.roles.UpsertRoleUseCase
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class RolesViewModel(
    dispatcherProvider: DispatcherProvider,
    rolesRepository: RolesRepository,
    private val upsertRoleUseCase: UpsertRoleUseCase,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<RolesState, RolesIntent>(
    initialState = RolesState(),
    dispatcherProvider = dispatcherProvider,
) {
    init {
        scope.launch {
            rolesRepository.observeRoles().collectLatest { roles ->
                updateState {
                    it.copy(roles = if (roles.isEmpty()) UiAsync.Empty else UiAsync.Success(roles))
                }
            }
        }
    }

    override suspend fun handleIntent(intent: RolesIntent) {
        when (intent) {
            RolesIntent.CreateNew -> updateState {
                it.copy(
                    selectedRoleId = null,
                    editorName = "",
                    editorPermissions = "",
                    actionState = UiAsync.Idle,
                )
            }

            RolesIntent.Save -> save()
            is RolesIntent.SelectRole -> selectRole(intent.roleId)
            is RolesIntent.UpdateName -> updateState { it.copy(editorName = intent.value) }
            is RolesIntent.UpdatePermissions -> updateState { it.copy(editorPermissions = intent.value) }
        }
    }

    private fun selectRole(roleId: String) {
        val role = (currentState().roles as? UiAsync.Success)?.value?.firstOrNull { it.id == roleId } ?: return
        updateState {
            it.copy(
                selectedRoleId = role.id,
                editorName = role.name,
                editorPermissions = role.permissions.joinToString(",") { permission -> permission.key },
            )
        }
    }

    private suspend fun save() {
        analyticsTracker.trackScreen("roles")
        updateState { it.copy(actionState = UiAsync.Loading) }
        val role = Role(
            id = currentState().selectedRoleId ?: currentState().editorName.lowercase().replace(" ", "-"),
            name = currentState().editorName,
            permissions = currentState()
                .editorPermissions
                .split(",")
                .map(String::trim)
                .filter(String::isNotBlank)
                .map { Permission(it, it) },
        )
        when (val result = upsertRoleUseCase(role)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> updateState {
                it.copy(
                    selectedRoleId = result.value.id,
                    actionState = UiAsync.Success(Unit),
                )
            }
        }
    }
}
