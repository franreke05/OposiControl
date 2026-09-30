package com.oposicontrol.admin.features.users.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.ForumReport
import com.oposicontrol.admin.domain.model.ForumReportDetail
import com.oposicontrol.admin.domain.model.Role
import com.oposicontrol.admin.domain.model.User
import com.oposicontrol.admin.domain.model.UserDetail
import com.oposicontrol.admin.domain.repository.ModerationRepository
import com.oposicontrol.admin.domain.repository.RolesRepository
import com.oposicontrol.admin.domain.repository.UsersRepository
import com.oposicontrol.admin.domain.usecase.users.UpsertUserUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** MASTER-OC-003: un guardado parcial (PATCH ok, roles fallan) no deja lista y servidor divergentes. */
class UsersSavePartialFailureTest {

    private fun user(name: String) = User(
        id = "u-1", fullName = name, email = "ana", roleIds = listOf("r1"),
        isRegistered = true, createdAt = null,
    )

    private class Repo(val listed: User, var server: User) : UsersRepository {
        var getUserCalls = 0
        override fun observeUsers(): Flow<AppResult<List<User>>> = flowOf(AppResult.Success(listOf(listed)))
        override suspend fun getUser(userId: String): AppResult<User> { getUserCalls++; return AppResult.Success(server) }
        override suspend fun upsertUser(user: User): AppResult<User> {
            // El PATCH del nombre llego al servidor, los roles no.
            server = server.copy(fullName = user.fullName)
            return AppResult.Failure(AppError.Network("roles 500"))
        }
        override suspend fun deleteUser(userId: String): AppResult<Unit> = error("unused")
        override suspend fun getUserDetail(userId: String): AppResult<UserDetail> = AppResult.Failure(AppError.Network("unused"))
    }

    private object NoRoles : RolesRepository {
        override fun observeRoles(): Flow<List<Role>> = flowOf(emptyList())
        override suspend fun upsertRole(role: Role): AppResult<Role> = error("unused")
    }

    private object NoModeration : ModerationRepository {
        override suspend fun listPendingReports(): AppResult<List<ForumReport>> = error("unused")
        override suspend fun listReports(includeResolved: Boolean): AppResult<List<ForumReport>> = error("unused")
        override suspend fun getReport(reportId: String): AppResult<ForumReportDetail> = error("unused")
        override suspend fun resolveReport(reportId: String, action: String, notes: String?, ticketsReporter: Int): AppResult<Unit> = error("unused")
        override suspend fun grantTickets(userId: String, amount: Int, reason: String, idempotencyKey: String): AppResult<Int> = error("unused")
        override suspend fun warnUser(userId: String, message: String): AppResult<Unit> = error("unused")
    }

    @Test
    fun aFailedSaveRereadsTheRowSoTheListShowsWhatIsPersisted() = runTest {
        val repo = Repo(listed = user("Ana"), server = user("Ana"))
        val vm = UsersViewModel(
            TestDispatcherProvider(StandardTestDispatcher(testScheduler)), repo, NoRoles,
            UpsertUserUseCase(repo), NoModeration, NoOpAnalyticsTracker,
        )
        testScheduler.advanceUntilIdle()
        vm.dispatch(UsersIntent.SelectUser("u-1"))
        testScheduler.advanceUntilIdle()
        vm.dispatch(UsersIntent.UpdateName("Ana Nueva"))
        testScheduler.advanceUntilIdle()
        vm.dispatch(UsersIntent.Save)
        testScheduler.advanceUntilIdle()

        val state = vm.state.value
        assertIs<UiAsync.Error>(state.actionState)
        assertEquals(1, repo.getUserCalls, "el fallo relee la fila")
        assertEquals("Ana Nueva", state.loadedUsers.single().fullName, "la lista refleja el PATCH que si persistio")
        assertEquals("Ana Nueva", state.editorName, "lo tecleado se conserva para reintentar")
    }
}
