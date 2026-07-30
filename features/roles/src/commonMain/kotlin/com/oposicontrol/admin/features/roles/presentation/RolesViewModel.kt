package com.oposicontrol.admin.features.roles.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.Permission
import com.oposicontrol.admin.domain.model.Role
import com.oposicontrol.admin.domain.usecase.roles.ObserveRolesUseCase
import com.oposicontrol.admin.domain.usecase.roles.UpsertRoleUseCase
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class RolesViewModel(
    dispatcherProvider: DispatcherProvider,
    observeRolesUseCase: ObserveRolesUseCase,
    private val upsertRoleUseCase: UpsertRoleUseCase,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<RolesState, RolesIntent>(
    initialState = RolesState(),
    dispatcherProvider = dispatcherProvider,
) {
    private var lastSuccessfulSave: RolesSaveKey? = null

    init {
        scope.launch {
            observeRolesUseCase().collectLatest { roles ->
                updateState {
                    it.copy(roles = if (roles.isEmpty()) UiAsync.Empty else UiAsync.Success(roles))
                }
            }
        }
    }

    override suspend fun handleIntent(intent: RolesIntent) {
        when (intent) {
            RolesIntent.CreateNew -> {
                lastSuccessfulSave = null
                updateState {
                    it.copy(
                        selectedRoleId = null,
                        editorName = "",
                        editorPermissions = "",
                        actionState = UiAsync.Idle,
                    )
                }
            }

            RolesIntent.Save -> save()
            is RolesIntent.SelectRole -> selectRole(intent.roleId)
            is RolesIntent.UpdateName -> updateEditor { it.copy(editorName = intent.value) }
            is RolesIntent.UpdatePermissions -> updateEditor { it.copy(editorPermissions = intent.value) }
        }
    }

    private fun selectRole(roleId: String) {
        val role = (currentState().roles as? UiAsync.Success)?.value?.firstOrNull { it.id == roleId } ?: return
        lastSuccessfulSave = null
        updateState {
            it.copy(
                selectedRoleId = role.id,
                editorName = role.name,
                editorPermissions = role.permissions.joinToString(",") { permission -> permission.key },
                actionState = UiAsync.Idle,
            )
        }
    }

    private suspend fun save() {
        val state = currentState()
        if (state.actionState == UiAsync.Loading) return
        val permissionKeys = state.editorPermissions
            .split(",")
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        val saveKey = RolesSaveKey(
            selectedRoleId = state.selectedRoleId,
            name = state.editorName.trim(),
            permissionKeys = permissionKeys,
        )
        if (saveKey == lastSuccessfulSave) return

        analyticsTracker.trackScreen("roles")
        updateState { it.copy(actionState = UiAsync.Loading) }
        val role = Role(
            id = state.selectedRoleId ?: saveKey.name.lowercase().replace(" ", "-"),
            name = saveKey.name,
            permissions = saveKey.permissionKeys.map { Permission(it, it) },
        )
        when (val result = upsertRoleUseCase(role)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                val saved = result.value
                lastSuccessfulSave = RolesSaveKey(
                    selectedRoleId = saved.id,
                    name = saved.name,
                    permissionKeys = saved.permissions.map { it.key },
                )
                updateState {
                    it.copy(
                        selectedRoleId = saved.id,
                        editorName = saved.name,
                        editorPermissions = saved.permissions.joinToString(",") { permission -> permission.key },
                        actionState = UiAsync.Success(Unit),
                    )
                }
            }
        }
    }

    private fun updateEditor(transform: (RolesState) -> RolesState) {
        lastSuccessfulSave = null
        updateState { transform(it).copy(actionState = UiAsync.Idle) }
    }
}

private data class RolesSaveKey(
    val selectedRoleId: String?,
    val name: String,
    val permissionKeys: List<String>,
)
