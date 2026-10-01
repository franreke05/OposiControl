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
import com.oposicontrol.admin.domain.model.UserEconomy
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * MASTER6-IDEM-001 (cliente), MASTER6-IDEM-002, MASTER6-QUALITY-005, -010 y -011.
 */
class UsersLoop6GrantAndPresentationTest {
    private fun user(id: String, name: String, username: String? = null, email: String = id, status: AccountStatus = AccountStatus.ACTIVE) = User(
        id = id, fullName = name, email = email, roleIds = listOf("r1"),
        isRegistered = true, createdAt = null, ticketsBalance = 10, username = username, accountStatus = status,
    )

    private class Repo(val rows: List<User>) : UsersRepository {
        var walletBalance = 10
        var failDetail = false
        override fun observeUsers(): Flow<AppResult<List<User>>> = flowOf(AppResult.Success(rows))
        override suspend fun getUser(userId: String): AppResult<User> = AppResult.Success(rows.first { it.id == userId })
        override suspend fun upsertUser(user: User, previous: User?): AppResult<User> = AppResult.Success(user)
        override suspend fun deleteUser(userId: String): AppResult<Unit> = error("unused")
        override suspend fun getUserDetail(userId: String): AppResult<UserDetail> =
            if (failDetail) {
                AppResult.Failure(AppError.Network("down"))
            } else {
                AppResult.Success(UserDetail(UserProfile(userId = userId), economy = UserEconomy(ticketBalance = walletBalance)))
            }
    }

    private object NoRoles : RolesRepository {
        override fun observeRoles(): Flow<List<Role>> = flowOf(emptyList())
        override suspend fun upsertRole(role: Role): AppResult<Role> = error("unused")
    }

    private class Moderation(val results: ArrayDeque<AppResult<Int>>) : ModerationRepository {
        val keys = mutableListOf<String>()
        val amounts = mutableListOf<Int>()
        override suspend fun listPendingReports(): AppResult<List<ForumReport>> = error("unused")
        override suspend fun listReports(includeResolved: Boolean): AppResult<List<ForumReport>> = error("unused")
        override suspend fun getReport(reportId: String): AppResult<ForumReportDetail> = error("unused")
        override suspend fun resolveReport(reportId: String, action: String, notes: String?, ticketsReporter: Int): AppResult<Unit> = error("unused")
        override suspend fun grantTickets(userId: String, amount: Int, reason: String, idempotencyKey: String): AppResult<Int> {
            keys += idempotencyKey
            amounts += amount
            return results.removeFirst()
        }
        override suspend fun warnUser(userId: String, message: String): AppResult<Unit> = error("unused")
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

    private fun timeout() = AppResult.Failure(AppError.Network("timeout"))

    // --- IDEM-002 -----------------------------------------------------------

    @Test fun aSuccessfulGrantOfAnotherAmountDoesNotForgetTheKeyOfAnEarlierAmbiguousGrant() = runTest {
        // 5 -> resultado desconocido (k1); 10 -> exito; reintento de 5 debe REUTILIZAR k1 (si no, doble abono).
        val moderation = Moderation(ArrayDeque(listOf(timeout(), AppResult.Success(20), AppResult.Success(25))))
        val vm = vm(Repo(listOf(user("a", "Ana"))), moderation)
        testScheduler.advanceUntilIdle()
        send(vm, UsersIntent.SelectUser("a"), UsersIntent.OpenTicketsDialog, UsersIntent.UpdateTicketsInput("5"), UsersIntent.ConfirmTickets)
        send(vm, UsersIntent.OpenTicketsDialog, UsersIntent.UpdateTicketsInput("10"), UsersIntent.ConfirmTickets)
        assertNotEquals(moderation.keys[0], moderation.keys[1])
        assertEquals(listOf(5), vm.state.value.pendingGrants.map { it.amount }, "la pendiente de 5 sigue viva")

        send(vm, UsersIntent.OpenTicketsDialog, UsersIntent.UpdateTicketsInput("5"), UsersIntent.ConfirmTickets)
        assertEquals(listOf(5, 10, 5), moderation.amounts)
        assertEquals(moderation.keys[0], moderation.keys[2], "el reintento de 5 reutiliza la clave original")
        assertTrue(vm.state.value.pendingGrants.isEmpty())
    }

    @Test fun aFailedGrantOfAnotherAmountDoesNotReplaceTheEarlierPendingKey() = runTest {
        val moderation = Moderation(ArrayDeque(listOf(timeout(), timeout(), AppResult.Success(15))))
        val vm = vm(Repo(listOf(user("a", "Ana"))), moderation)
        testScheduler.advanceUntilIdle()
        send(vm, UsersIntent.SelectUser("a"), UsersIntent.OpenTicketsDialog, UsersIntent.UpdateTicketsInput("5"), UsersIntent.ConfirmTickets)
        send(vm, UsersIntent.OpenTicketsDialog, UsersIntent.UpdateTicketsInput("7"), UsersIntent.ConfirmTickets)
        assertEquals(setOf(5, 7), vm.state.value.pendingGrants.map { it.amount }.toSet())
        send(vm, UsersIntent.OpenTicketsDialog, UsersIntent.UpdateTicketsInput("5"), UsersIntent.ConfirmTickets)
        assertEquals(moderation.keys[0], moderation.keys[2])
    }

    @Test fun successClearsOnlyThePendingWhoseKeyWasSent() = runTest {
        val moderation = Moderation(ArrayDeque(listOf(timeout(), AppResult.Success(15))))
        val vm = vm(Repo(listOf(user("a", "Ana"))), moderation)
        testScheduler.advanceUntilIdle()
        send(vm, UsersIntent.SelectUser("a"), UsersIntent.OpenTicketsDialog, UsersIntent.UpdateTicketsInput("5"), UsersIntent.ConfirmTickets)
        val pendingKey = vm.state.value.pendingGrants.single().key
        send(vm, UsersIntent.OpenTicketsDialog, UsersIntent.UpdateTicketsInput("9"), UsersIntent.ConfirmTickets)
        assertEquals(listOf(pendingKey), vm.state.value.pendingGrants.map { it.key })
    }

    // --- IDEM-001 (cliente) -------------------------------------------------

    @Test fun theBalanceShownAfterAGrantIsReReadFromTheWalletNotTakenFromTheGrantAnswer() = runTest {
        // La concesion responde 15 (p. ej. un asiento repetido: saldo ORIGINAL), pero la cartera vale ya 40.
        val repo = Repo(listOf(user("a", "Ana")))
        val vm = vm(repo, Moderation(ArrayDeque(listOf(AppResult.Success(15)))))
        testScheduler.advanceUntilIdle()
        send(vm, UsersIntent.SelectUser("a"))
        repo.walletBalance = 40
        send(vm, UsersIntent.OpenTicketsDialog, UsersIntent.UpdateTicketsInput("5"), UsersIntent.ConfirmTickets)
        assertEquals(40, (vm.state.value.detail as UiAsync.Success).value.economy?.ticketBalance)
        assertEquals(40, vm.state.value.loadedUsers.single().ticketsBalance)
    }

    @Test fun aFailedBalanceReReadKeepsWhatWasShown() = runTest {
        val repo = Repo(listOf(user("a", "Ana")))
        val vm = vm(repo, Moderation(ArrayDeque(listOf(AppResult.Success(15)))))
        testScheduler.advanceUntilIdle()
        send(vm, UsersIntent.SelectUser("a"))
        repo.failDetail = true
        send(vm, UsersIntent.OpenTicketsDialog, UsersIntent.UpdateTicketsInput("5"), UsersIntent.ConfirmTickets)
        assertTrue(vm.state.value.detail is UiAsync.Success, "una relectura silenciosa fallida no vacia la ficha")
        assertEquals(15, vm.state.value.loadedUsers.single().ticketsBalance)
    }

    // --- QUALITY-005 --------------------------------------------------------

    @Test fun nameLimitCountsCodePointsLikeTheServer() {
        val sixtyEmoji = "😀".repeat(60) // 60 puntos de codigo, 120 unidades UTF-16
        assertEquals(60, userNameLength(sixtyEmoji))
        assertTrue(userSaveBlockers(sixtyEmoji, "Ana").isEmpty(), "el servidor acepta 60 emoji (<= 100 puntos de codigo)")
        assertTrue(userSaveBlockers("😀".repeat(100), "Ana").isEmpty())
        assertEquals(1, userSaveBlockers("😀".repeat(101), "Ana").size)
        assertEquals(1, userNameLength("\uDE00"), "un sustituto suelto cuenta como un punto de codigo")
    }

    // --- QUALITY-010 --------------------------------------------------------

    @Test fun freshDetailStatusOverridesAStaleListRow() {
        val row = user("a", "Ana", status = AccountStatus.ACTIVE)
        fun detail(status: AccountStatus) = UserDetail(UserProfile(userId = "a", accountStatus = status))
        assertEquals(AccountStatus.BLOCKED, userEffectiveAccountStatus(row, detail(AccountStatus.BLOCKED)))
        assertEquals(AccountStatus.DELETION_PENDING, userEffectiveAccountStatus(row, detail(AccountStatus.DELETION_PENDING)))
        assertEquals(AccountStatus.ACTIVE, userEffectiveAccountStatus(row, detail(AccountStatus.UNKNOWN)), "UNKNOWN no tapa a la fila")
        assertEquals(AccountStatus.ACTIVE, userEffectiveAccountStatus(row, null), "sin ficha se mantiene la fila")
    }

    // --- QUALITY-011 --------------------------------------------------------

    @Test fun searchMatchesTheDisplayedUsernameEvenWhenTheHiddenEmailDiffers() {
        val saved = user("a", "Ana Garcia", username = "ana.g", email = "ana@real.example")
        assertTrue(matchesSearch(saved, "ana.g"))
        assertTrue(matchesSearch(saved, "real.example"))
        assertTrue(matchesSearch(saved, "Garcia"))
        assertFalse(matchesSearch(saved, "otra"))
    }
}
