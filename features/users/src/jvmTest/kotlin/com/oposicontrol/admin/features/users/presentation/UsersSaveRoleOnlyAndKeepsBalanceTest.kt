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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** MASTER4-API-001 (solo roles sin nombre) y MASTER4-OC-001 (guardar conserva el saldo conocido). */
class UsersSaveRoleOnlyAndKeepsBalanceTest {
    private fun user(name: String, tickets: Int? = 42) = User(
        id = "u-1", fullName = name, email = "ana", roleIds = listOf("r1"),
        isRegistered = true, createdAt = null, ticketsBalance = tickets,
    )

    /** El servidor devuelve la fila de la lectura admin: SIN saldo. */
    private class Repo(val listed: User) : UsersRepository {
        val upserts = mutableListOf<User>()
        override fun observeUsers(): Flow<AppResult<List<User>>> = flowOf(AppResult.Success(listOf(listed)))
        override suspend fun getUser(userId: String): AppResult<User> = AppResult.Success(listed)
        override suspend fun upsertUser(user: User, previous: User?): AppResult<User> {
            upserts += user
            return AppResult.Success(user.copy(ticketsBalance = null))
        }
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

    private fun TestScope.vm(repo: Repo) = UsersViewModel(
        TestDispatcherProvider(StandardTestDispatcher(testScheduler)), repo, NoRoles,
        UpsertUserUseCase(repo), NoModeration, NoOpAnalyticsTracker,
    )

    private fun TestScope.select(vm: UsersViewModel) {
        testScheduler.advanceUntilIdle()
        vm.dispatch(UsersIntent.SelectUser("u-1"))
        testScheduler.advanceUntilIdle()
    }

    @Test fun aNamelessUserCanSaveARoleOnlyChange() = runTest {
        val repo = Repo(user(""))
        val vm = vm(repo)
        select(vm)
        vm.dispatch(UsersIntent.ToggleRole("r2"))
        testScheduler.advanceUntilIdle()
        assertTrue(vm.state.value.saveBlockers.isEmpty())
        assertTrue(vm.state.value.canSave)
        vm.dispatch(UsersIntent.Save)
        testScheduler.advanceUntilIdle()
        assertIs<UiAsync.Success<Unit>>(vm.state.value.actionState)
        assertEquals("", repo.upserts.single().fullName)
        assertEquals(setOf("r1", "r2"), repo.upserts.single().roleIds.toSet())
    }

    @Test fun blankingAnExistingNameStaysBlockedAndNothingIsSent() = runTest {
        val repo = Repo(user("Ana"))
        val vm = vm(repo)
        select(vm)
        vm.dispatch(UsersIntent.UpdateName("   "))
        testScheduler.advanceUntilIdle()
        assertEquals(1, vm.state.value.saveBlockers.size)
        vm.dispatch(UsersIntent.Save)
        testScheduler.advanceUntilIdle()
        assertIs<UiAsync.Error>(vm.state.value.actionState)
        assertEquals(0, repo.upserts.size)
    }

    @Test fun savingKeepsTheKnownTicketsBalanceInsteadOfShowingSinDato() = runTest {
        val repo = Repo(user("Ana", tickets = 42))
        val vm = vm(repo)
        select(vm)
        vm.dispatch(UsersIntent.UpdateName("Ana Nueva"))
        testScheduler.advanceUntilIdle()
        vm.dispatch(UsersIntent.Save)
        testScheduler.advanceUntilIdle()
        val row = vm.state.value.loadedUsers.single()
        assertEquals("Ana Nueva", row.fullName)
        assertEquals(42, row.ticketsBalance)
    }
}
