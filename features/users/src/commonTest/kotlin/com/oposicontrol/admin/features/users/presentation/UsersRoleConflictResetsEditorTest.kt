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

/** RC-07 (FA8): tras un 409 VERSION_CONFLICT el editor no conserva los roles viejos (no hay sobrescritura silenciosa). */
class UsersRoleConflictResetsEditorTest {

    private fun user(roles: List<String>) = User(
        id = "u-1", fullName = "Ana", email = "ana", roleIds = roles, isRegistered = true, createdAt = null,
    )

    private class Repo(val listed: User, var server: User) : UsersRepository {
        val upserts = mutableListOf<User>()
        override fun observeUsers(): Flow<AppResult<List<User>>> = flowOf(AppResult.Success(listOf(listed)))
        override suspend fun getUser(userId: String): AppResult<User> = AppResult.Success(server)
        override suspend fun upsertUser(user: User, previous: User?): AppResult<User> {
            upserts += user
            return AppResult.Failure(AppError.Validation("Los roles cambiaron.", code = "VERSION_CONFLICT"))
        }
        override suspend fun deleteUser(userId: String): AppResult<Unit> = error("unused")
        override suspend fun getUserDetail(userId: String): AppResult<UserDetail> = AppResult.Failure(AppError.Network("unused"))
    }

    private object NoRoles : RolesRepository {
        override fun observeRoles(): Flow<AppResult<List<Role>>> = flowOf(AppResult.Success(emptyList()))
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
    fun afterAConflictTheEditorHoldsTheRefreshedRolesAndASecondSaveCannotRevertThem() = runTest {
        // El admin X vio [r1]; el admin Y dejo el servidor en [r3].
        val repo = Repo(listed = user(listOf("r1")), server = user(listOf("r3")))
        val vm = UsersViewModel(
            TestDispatcherProvider(StandardTestDispatcher(testScheduler)), repo, NoRoles,
            UpsertUserUseCase(repo), NoModeration, NoOpAnalyticsTracker,
        )
        testScheduler.advanceUntilIdle()
        vm.dispatch(UsersIntent.SelectUser("u-1"))
        testScheduler.advanceUntilIdle()
        vm.dispatch(UsersIntent.ToggleRole("r2"))
        testScheduler.advanceUntilIdle()
        vm.dispatch(UsersIntent.Save)
        testScheduler.advanceUntilIdle()

        assertIs<UiAsync.Error>(vm.state.value.actionState)
        assertEquals(setOf("r3"), vm.state.value.editorRoleIds, "el editor vuelve a los roles releidos")

        vm.dispatch(UsersIntent.Save)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("r1", "r2"), repo.upserts.first().roleIds.sorted(), "el primer intento llevaba la seleccion del admin X")
        assertEquals(listOf("r3"), repo.upserts.last().roleIds.sorted(), "el segundo Save lleva los roles de Y, no revierte")
    }
}
