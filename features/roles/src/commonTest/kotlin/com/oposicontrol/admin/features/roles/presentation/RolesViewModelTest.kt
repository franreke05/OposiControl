package com.oposicontrol.admin.features.roles.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.Permission
import com.oposicontrol.admin.domain.model.Role
import com.oposicontrol.admin.domain.repository.RolesRepository
import com.oposicontrol.admin.domain.usecase.roles.ObserveRolesUseCase
import com.oposicontrol.admin.domain.usecase.roles.UpsertRoleUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class RolesViewModelTest {
    @Test
    fun `double save persists normalized permissions once`() = runTest {
        val repository = FakeRolesRepository()
        val viewModel = RolesViewModel(
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            observeRolesUseCase = ObserveRolesUseCase(repository),
            upsertRoleUseCase = UpsertRoleUseCase(repository),
            analyticsTracker = NoOpAnalyticsTracker,
        )
        advanceUntilIdle()

        viewModel.dispatch(RolesIntent.SelectRole(ROLE_ID))
        viewModel.dispatch(RolesIntent.UpdatePermissions("users.read, users.write, users.read"))
        viewModel.dispatch(RolesIntent.Save)
        viewModel.dispatch(RolesIntent.Save)
        advanceUntilIdle()

        val saved = repository.roles.value.single()
        assertEquals(1, repository.upsertCalls)
        assertEquals(listOf("users.read", "users.write"), saved.permissions.map { it.key })
        assertEquals("users.read,users.write", viewModel.state.value.editorPermissions)
        assertIs<UiAsync.Success<Unit>>(viewModel.state.value.actionState)
        viewModel.clear()
    }
}

private class FakeRolesRepository : RolesRepository {
    val roles = MutableStateFlow(
        listOf(
            Role(
                id = ROLE_ID,
                name = "Administracion",
                permissions = listOf(Permission("users.read", "Leer usuarios")),
            ),
        ),
    )
    var upsertCalls = 0
        private set

    override fun observeRoles(): Flow<List<Role>> = roles

    override suspend fun upsertRole(role: Role): AppResult<Role> {
        upsertCalls += 1
        roles.value = roles.value
            .filterNot { it.id == role.id }
            .plus(role)
        return AppResult.Success(role)
    }
}

private const val ROLE_ID = "admin"
