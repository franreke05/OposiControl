package com.oposicontrol.admin.features.users.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.User
import com.oposicontrol.admin.domain.repository.UsersRepository
import com.oposicontrol.admin.domain.usecase.users.DeleteUserUseCase
import com.oposicontrol.admin.domain.usecase.users.ObserveUsersUseCase
import com.oposicontrol.admin.domain.usecase.users.UpsertUserUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class UsersViewModelTest {
    @Test
    fun `double save persists active state and distinct roles once`() = runTest {
        val repository = FakeUsersRepository()
        val viewModel = UsersViewModel(
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            observeUsersUseCase = ObserveUsersUseCase(repository),
            upsertUserUseCase = UpsertUserUseCase(repository),
            deleteUserUseCase = DeleteUserUseCase(repository),
            analyticsTracker = NoOpAnalyticsTracker,
        )
        advanceUntilIdle()

        viewModel.dispatch(UsersIntent.SelectUser(USER_ID))
        viewModel.dispatch(UsersIntent.ToggleActive(false))
        viewModel.dispatch(UsersIntent.UpdateRoleIds("auditor, support, auditor"))
        viewModel.dispatch(UsersIntent.Save)
        viewModel.dispatch(UsersIntent.Save)
        advanceUntilIdle()

        val saved = repository.users.value.single()
        assertEquals(1, repository.upsertCalls)
        assertFalse(saved.isActive)
        assertEquals(listOf("auditor", "support"), saved.roleIds)
        assertIs<UiAsync.Success<Unit>>(viewModel.state.value.actionState)
        assertEquals(saved.roleIds.joinToString(","), viewModel.state.value.editorRoleIds)
        viewModel.clear()
    }
}

private class FakeUsersRepository : UsersRepository {
    val users = MutableStateFlow(
        listOf(
            User(
                id = USER_ID,
                fullName = "Admin",
                email = "admin@oposicontrol.local",
                roleIds = listOf("support"),
                isActive = true,
                createdAt = Instant.parse("2026-07-29T08:00:00Z"),
            ),
        ),
    )
    var upsertCalls = 0
        private set

    override fun observeUsers(): Flow<List<User>> = users

    override suspend fun getUser(userId: String): AppResult<User> {
        return AppResult.Success(users.value.first { it.id == userId })
    }

    override suspend fun upsertUser(user: User): AppResult<User> {
        upsertCalls += 1
        users.value = users.value
            .filterNot { it.id == user.id }
            .plus(user)
        return AppResult.Success(user)
    }

    override suspend fun deleteUser(userId: String): AppResult<Unit> {
        users.value = users.value.filterNot { it.id == userId }
        return AppResult.Success(Unit)
    }
}

private const val USER_ID = "user-1"
