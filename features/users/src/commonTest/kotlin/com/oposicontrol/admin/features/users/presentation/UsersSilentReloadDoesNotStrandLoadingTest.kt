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
import com.oposicontrol.admin.domain.model.UserEconomy
import com.oposicontrol.admin.domain.model.UserProfile
import com.oposicontrol.admin.domain.repository.ModerationRepository
import com.oposicontrol.admin.domain.repository.RolesRepository
import com.oposicontrol.admin.domain.repository.UsersRepository
import com.oposicontrol.admin.domain.usecase.users.UpsertUserUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs

/** MASTER7-OC-006: a silent reload that cancels an in-flight detail load must not leave the detail in Loading. */
class UsersSilentReloadDoesNotStrandLoadingTest {
    private val row = User(
        id = "a", fullName = "Ana", email = "a", roleIds = listOf("r1"),
        isRegistered = true, createdAt = null, ticketsBalance = 10,
    )

    private class Repo(val row: User) : UsersRepository {
        /** The first detail call hangs (in-flight non-silent load); later ones fail or succeed per [laterFails]. */
        val firstCall = CompletableDeferred<UserDetail>()
        var laterFails = true
        private var calls = 0
        override fun observeUsers(): Flow<AppResult<List<User>>> = flowOf(AppResult.Success(listOf(row)))
        override suspend fun getUser(userId: String): AppResult<User> = AppResult.Success(row)
        override suspend fun upsertUser(user: User, previous: User?): AppResult<User> = AppResult.Success(user)
        override suspend fun deleteUser(userId: String): AppResult<Unit> = error("unused")
        override suspend fun getUserDetail(userId: String): AppResult<UserDetail> {
            if (++calls == 1) return AppResult.Success(firstCall.await())
            return if (laterFails) {
                AppResult.Failure(AppError.Network("boom"))
            } else {
                AppResult.Success(UserDetail(UserProfile(userId = userId), economy = UserEconomy(ticketBalance = 15)))
            }
        }
    }

    private object NoRoles : RolesRepository {
        override fun observeRoles(): Flow<List<Role>> = flowOf(emptyList())
        override suspend fun upsertRole(role: Role): AppResult<Role> = error("unused")
    }

    private object GrantOk : ModerationRepository {
        override suspend fun listPendingReports(): AppResult<List<ForumReport>> = error("unused")
        override suspend fun listReports(includeResolved: Boolean): AppResult<List<ForumReport>> = error("unused")
        override suspend fun getReport(reportId: String): AppResult<ForumReportDetail> = error("unused")
        override suspend fun resolveReport(reportId: String, action: String, notes: String?, ticketsReporter: Int): AppResult<Unit> = error("unused")
        override suspend fun grantTickets(userId: String, amount: Int, reason: String, idempotencyKey: String): AppResult<Int> = AppResult.Success(15)
        override suspend fun warnUser(userId: String, message: String): AppResult<Unit> = error("unused")
    }

    private fun TestScope.vm(repo: Repo) = UsersViewModel(
        TestDispatcherProvider(StandardTestDispatcher(testScheduler)), repo, NoRoles,
        UpsertUserUseCase(repo), GrantOk, NoOpAnalyticsTracker,
    )

    private fun TestScope.send(vm: UsersViewModel, vararg intents: UsersIntent) {
        for (i in intents) {
            vm.dispatch(i)
            testScheduler.advanceUntilIdle()
        }
    }

    private suspend fun TestScope.grantWhileDetailIsLoading(repo: Repo): UsersViewModel {
        val vm = vm(repo)
        testScheduler.advanceUntilIdle()
        send(vm, UsersIntent.SelectUser("a"))
        assertIs<UiAsync.Loading>(vm.state.value.detail)
        send(vm, UsersIntent.OpenTicketsDialog, UsersIntent.UpdateTicketsInput("5"), UsersIntent.ConfirmTickets)
        return vm
    }

    @Test fun aFailedSilentReloadOverACancelledLoadSurfacesAnErrorNotLoading() = runTest {
        val vm = grantWhileDetailIsLoading(Repo(row))
        assertIs<UiAsync.Error>(vm.state.value.detail)
    }

    @Test fun aSilentReloadAfterACancelledLoadRecoversTheDetail() = runTest {
        val vm = grantWhileDetailIsLoading(Repo(row).apply { laterFails = false })
        assertIs<UiAsync.Success<UserDetail>>(vm.state.value.detail)
    }

    @Test fun aFailedSilentReloadKeepsAnAlreadyLoadedDetail() = runTest {
        val repo = Repo(row)
        val vm = vm(repo)
        testScheduler.advanceUntilIdle()
        send(vm, UsersIntent.SelectUser("a"))
        repo.firstCall.complete(UserDetail(UserProfile(userId = "a"), economy = UserEconomy(ticketBalance = 10)))
        testScheduler.advanceUntilIdle()
        assertIs<UiAsync.Success<UserDetail>>(vm.state.value.detail)
        send(vm, UsersIntent.OpenTicketsDialog, UsersIntent.UpdateTicketsInput("5"), UsersIntent.ConfirmTickets)
        assertIs<UiAsync.Success<UserDetail>>(vm.state.value.detail)
    }
}
