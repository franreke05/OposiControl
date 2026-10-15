package com.oposicontrol.admin.features.users.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.AccountStatus
import com.oposicontrol.admin.domain.model.ForumReport
import com.oposicontrol.admin.domain.model.ForumReportDetail
import com.oposicontrol.admin.domain.model.Role
import com.oposicontrol.admin.domain.model.User
import com.oposicontrol.admin.domain.model.UserDetail
import com.oposicontrol.admin.domain.model.UserProfile
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Dar de baja y reactivar una cuenta desde la ficha: pide motivo, no escribe hasta confirmar, y el estado que se pinta
 * después es el que devuelve el servidor, no el que se pidió.
 */
class UsersAccountStatusTest {
    private fun user(id: String, status: AccountStatus) = User(
        id = id, fullName = "Ana", email = id, roleIds = listOf("r1"),
        isRegistered = true, createdAt = null, ticketsBalance = 0, accountStatus = status,
    )

    /** El «servidor»: guarda el estado de cada cuenta y lo devuelve en la fila y en la ficha. */
    private class Repo(initial: User) : UsersRepository {
        var row: User = initial
        override fun observeUsers(): Flow<AppResult<List<User>>> = flowOf(AppResult.Success(listOf(row)))
        override suspend fun getUser(userId: String): AppResult<User> = AppResult.Success(row)
        override suspend fun upsertUser(user: User, previous: User?): AppResult<User> = error("unused")
        override suspend fun getUserDetail(userId: String): AppResult<UserDetail> =
            AppResult.Success(UserDetail(UserProfile(userId = userId, accountStatus = row.accountStatus)))
    }

    private object NoRoles : RolesRepository {
        override fun observeRoles(): Flow<AppResult<List<Role>>> = flowOf(AppResult.Success(emptyList()))
    }

    private class Moderation(private val repo: Repo, private val failure: AppError? = null) : ModerationRepository {
        val calls = mutableListOf<Triple<String, Boolean, String>>()
        override suspend fun listPendingReports(): AppResult<List<ForumReport>> = error("unused")
        override suspend fun listReports(includeResolved: Boolean): AppResult<List<ForumReport>> = error("unused")
        override suspend fun getReport(reportId: String): AppResult<ForumReportDetail> = error("unused")
        override suspend fun resolveReport(reportId: String, action: String, notes: String?, ticketsReporter: Int): AppResult<Unit> = error("unused")
        override suspend fun grantTickets(userId: String, amount: Int, reason: String, idempotencyKey: String): AppResult<Int> = error("unused")
        override suspend fun warnUser(userId: String, message: String): AppResult<Unit> = error("unused")
        override suspend fun setAccountBlocked(userId: String, blocked: Boolean, reason: String): AppResult<Unit> {
            calls += Triple(userId, blocked, reason)
            failure?.let { return AppResult.Failure(it) }
            repo.row = repo.row.copy(accountStatus = if (blocked) AccountStatus.BLOCKED else AccountStatus.ACTIVE)
            return AppResult.Success(Unit)
        }
    }

    private fun TestScope.vm(repo: Repo, moderation: Moderation) = UsersViewModel(
        TestDispatcherProvider(StandardTestDispatcher(testScheduler)), repo, NoRoles,
        UpsertUserUseCase(repo), moderation, NoOpAnalyticsTracker,
    )

    private fun TestScope.send(vm: UsersViewModel, vararg intents: UsersIntent) {
        for (i in intents) {
            vm.dispatch(i)
            testScheduler.advanceUntilIdle()
        }
    }

    @Test fun anActiveAccountIsBlockedOnlyAfterAReasonAndAConfirmation() = runTest {
        val repo = Repo(user("a", AccountStatus.ACTIVE))
        val moderation = Moderation(repo)
        val vm = vm(repo, moderation)
        testScheduler.advanceUntilIdle()

        send(vm, UsersIntent.SelectUser("a"), UsersIntent.OpenAccountStatusDialog)
        assertTrue(vm.state.value.accountStatusDialogOpen)
        assertTrue(vm.state.value.accountStatusBlocks)

        // Sin motivo no se envía nada, aunque se confirme.
        send(vm, UsersIntent.ConfirmAccountStatus)
        assertTrue(moderation.calls.isEmpty())

        send(vm, UsersIntent.UpdateAccountStatusReason("  Uso indebido.  "), UsersIntent.ConfirmAccountStatus)

        assertEquals(listOf(Triple("a", true, "Uso indebido.")), moderation.calls)
        assertFalse(vm.state.value.accountStatusDialogOpen)
        assertEquals(AccountStatus.BLOCKED, vm.state.value.selectedAccountStatus, "se pinta lo que devuelve el servidor")
        assertEquals("Reactivar cuenta", accountStatusActionLabel(vm.state.value.selectedAccountStatus))
    }

    @Test fun aBlockedAccountOffersToReactivateIt() = runTest {
        val repo = Repo(user("a", AccountStatus.BLOCKED))
        val moderation = Moderation(repo)
        val vm = vm(repo, moderation)
        testScheduler.advanceUntilIdle()

        send(vm, UsersIntent.SelectUser("a"), UsersIntent.OpenAccountStatusDialog)
        assertFalse(vm.state.value.accountStatusBlocks)
        send(vm, UsersIntent.UpdateAccountStatusReason("Aclarado."), UsersIntent.ConfirmAccountStatus)

        assertEquals(listOf(Triple("a", false, "Aclarado.")), moderation.calls)
        assertEquals(AccountStatus.ACTIVE, vm.state.value.selectedAccountStatus)
    }

    @Test fun aRefusalFromTheServerIsShownAndTheAccountKeepsItsState() = runTest {
        val repo = Repo(user("a", AccountStatus.ACTIVE))
        val moderation = Moderation(repo, failure = AppError.Authorization("Hace falta ser superadministrador."))
        val vm = vm(repo, moderation)
        testScheduler.advanceUntilIdle()

        send(vm, UsersIntent.SelectUser("a"), UsersIntent.OpenAccountStatusDialog, UsersIntent.UpdateAccountStatusReason("x"), UsersIntent.ConfirmAccountStatus)

        assertIs<UiAsync.Error>(vm.state.value.actionState)
        assertEquals(AccountStatus.ACTIVE, vm.state.value.selectedAccountStatus)
    }

    @Test fun anAccountWithARequestedDeletionOffersNothing() = runTest {
        val repo = Repo(user("a", AccountStatus.DELETION_PENDING))
        val moderation = Moderation(repo)
        val vm = vm(repo, moderation)
        testScheduler.advanceUntilIdle()

        send(vm, UsersIntent.SelectUser("a"), UsersIntent.OpenAccountStatusDialog)

        assertFalse(vm.state.value.accountStatusDialogOpen)
        assertNull(accountStatusActionLabel(AccountStatus.DELETION_PENDING))
        assertNull(accountStatusActionLabel(AccountStatus.UNKNOWN))
    }

    @Test fun dismissingTheDialogWritesNothingAndTheReasonIsCapped() = runTest {
        val repo = Repo(user("a", AccountStatus.ACTIVE))
        val moderation = Moderation(repo)
        val vm = vm(repo, moderation)
        testScheduler.advanceUntilIdle()

        send(vm, UsersIntent.SelectUser("a"), UsersIntent.OpenAccountStatusDialog, UsersIntent.UpdateAccountStatusReason("x".repeat(900)))
        assertEquals(ACCOUNT_STATUS_REASON_MAX, vm.state.value.accountStatusReason.length)
        send(vm, UsersIntent.DismissAccountStatusDialog, UsersIntent.ConfirmAccountStatus)

        assertTrue(moderation.calls.isEmpty())
    }
}
