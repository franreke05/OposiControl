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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MASTER5-IDEM-001 (la clave de idempotencia sobrevive a un fallo ambiguo), MASTER5-OC-002 (resultados asincronos no
 * pisan otra seleccion, Save sin doble envio), MASTER5-OC-003 (la ficha se actualiza tras conceder/guardar) y
 * MASTER5-API-009 (el guardado informa de la fila previa para escribir solo lo que cambio).
 */
class UsersConcurrencyAndIdempotencyTest {
    private fun user(id: String, name: String) = User(
        id = id, fullName = name, email = id, roleIds = listOf("r1"),
        isRegistered = true, createdAt = null, ticketsBalance = 10,
    )

    private fun detail(id: String, balance: Int = 10) = UserDetail(
        profile = UserProfile(userId = id),
        economy = UserEconomy(ticketBalance = balance),
    )

    private class Repo(val rows: List<User>) : UsersRepository {
        val detailGates = mutableMapOf<String, CompletableDeferred<UserDetail>>()
        var detailCalls = 0
        /** Saldo vigente de la cartera tal como lo devuelve la ficha (el servidor lo mueve al conceder). */
        var walletBalance = 10
        var saveGate: CompletableDeferred<Unit>? = null
        val upserts = mutableListOf<User>()
        val previouses = mutableListOf<User?>()
        override fun observeUsers(): Flow<AppResult<List<User>>> = flowOf(AppResult.Success(rows))
        override suspend fun getUser(userId: String): AppResult<User> = AppResult.Success(rows.first { it.id == userId })
        override suspend fun upsertUser(user: User, previous: User?): AppResult<User> {
            upserts += user
            previouses += previous
            saveGate?.await()
            return AppResult.Success(user)
        }
        override suspend fun deleteUser(userId: String): AppResult<Unit> = error("unused")
        override suspend fun getUserDetail(userId: String): AppResult<UserDetail> {
            detailCalls++
            val gate = detailGates[userId] ?: return AppResult.Success(UserDetail(UserProfile(userId = userId), economy = UserEconomy(ticketBalance = walletBalance)))
            return AppResult.Success(gate.await())
        }
    }

    private object NoRoles : RolesRepository {
        override fun observeRoles(): Flow<AppResult<List<Role>>> = flowOf(AppResult.Success(emptyList()))
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

    private fun TestScope.vm(repo: Repo, moderation: Moderation = Moderation(ArrayDeque())) = UsersViewModel(
        TestDispatcherProvider(StandardTestDispatcher(testScheduler)), repo, NoRoles,
        UpsertUserUseCase(repo), moderation, NoOpAnalyticsTracker,
    )

    private fun TestScope.send(vm: UsersViewModel, vararg intents: UsersIntent) {
        for (i in intents) {
            vm.dispatch(i)
            testScheduler.advanceUntilIdle()
        }
    }

    // --- IDEM-001 -----------------------------------------------------------

    @Test fun retryAfterAnAmbiguousFailureReusesTheSameKey() = runTest {
        val moderation = Moderation(ArrayDeque(listOf(AppResult.Failure(AppError.Network("timeout")), AppResult.Success(15))))
        val repo = Repo(listOf(user("a", "Ana")))
        val vm = vm(repo, moderation)
        testScheduler.advanceUntilIdle()
        send(vm, UsersIntent.SelectUser("a"), UsersIntent.OpenTicketsDialog, UsersIntent.UpdateTicketsInput("5"), UsersIntent.ConfirmTickets)
        assertIs<UiAsync.Error>(vm.state.value.actionState)
        assertEquals(1, moderation.keys.size)

        // El admin reabre el dialogo: llega con el importe pendiente y confirma lo mismo.
        repo.walletBalance = 15 // el servidor aplico la concesion; la ficha se relee tras el exito (MASTER6-IDEM-001)
        send(vm, UsersIntent.OpenTicketsDialog)
        assertEquals("5", vm.state.value.ticketsInput)
        send(vm, UsersIntent.ConfirmTickets)

        assertEquals(2, moderation.keys.size)
        assertEquals(moderation.keys[0], moderation.keys[1], "el reintento reutiliza la clave: el backend no concede dos veces")
        assertNull(vm.state.value.pendingGrant, "el exito resuelve la operacion pendiente")
        assertEquals(15, vm.state.value.loadedUsers.single().ticketsBalance)
    }

    @Test fun aDifferentAmountAfterAFailureIsANewOperationWithANewKey() = runTest {
        val moderation = Moderation(ArrayDeque(listOf(AppResult.Failure(AppError.Network("timeout")), AppResult.Success(17))))
        val vm = vm(Repo(listOf(user("a", "Ana"))), moderation)
        testScheduler.advanceUntilIdle()
        send(vm, UsersIntent.SelectUser("a"), UsersIntent.OpenTicketsDialog, UsersIntent.UpdateTicketsInput("5"), UsersIntent.ConfirmTickets)
        send(vm, UsersIntent.OpenTicketsDialog, UsersIntent.UpdateTicketsInput("7"), UsersIntent.ConfirmTickets)
        assertEquals(listOf(5, 7), moderation.amounts)
        assertNotEquals(moderation.keys[0], moderation.keys[1])
    }

    @Test fun dismissingTheDialogDoesNotForgetAnAmbiguousGrant() = runTest {
        val moderation = Moderation(ArrayDeque(listOf(AppResult.Failure(AppError.Network("timeout")), AppResult.Success(15))))
        val vm = vm(Repo(listOf(user("a", "Ana"))), moderation)
        testScheduler.advanceUntilIdle()
        send(vm, UsersIntent.SelectUser("a"), UsersIntent.OpenTicketsDialog, UsersIntent.UpdateTicketsInput("5"), UsersIntent.ConfirmTickets)
        send(vm, UsersIntent.OpenTicketsDialog, UsersIntent.DismissTicketsDialog, UsersIntent.OpenTicketsDialog, UsersIntent.ConfirmTickets)
        assertEquals(moderation.keys[0], moderation.keys[1])
    }

    @Test fun aSecondIndependentGrantAfterSuccessGetsAFreshKey() = runTest {
        val moderation = Moderation(ArrayDeque(listOf(AppResult.Success(15), AppResult.Success(20))))
        val vm = vm(Repo(listOf(user("a", "Ana"))), moderation)
        testScheduler.advanceUntilIdle()
        send(vm, UsersIntent.SelectUser("a"), UsersIntent.OpenTicketsDialog, UsersIntent.UpdateTicketsInput("5"), UsersIntent.ConfirmTickets)
        send(vm, UsersIntent.OpenTicketsDialog, UsersIntent.UpdateTicketsInput("5"), UsersIntent.ConfirmTickets)
        assertNotEquals(moderation.keys[0], moderation.keys[1])
    }

    // --- OC-003 -------------------------------------------------------------

    @Test fun aSuccessfulGrantUpdatesTheDossierBalance() = runTest {
        val repo = Repo(listOf(user("a", "Ana")))
        val vm = vm(repo, Moderation(ArrayDeque(listOf(AppResult.Success(15)))))
        testScheduler.advanceUntilIdle()
        send(vm, UsersIntent.SelectUser("a"))
        repo.walletBalance = 15 // el servidor aplica la concesion (MASTER6-IDEM-001: la ficha se relee tras conceder)
        assertEquals(10, (vm.state.value.detail as UiAsync.Success).value.economy?.ticketBalance)
        send(vm, UsersIntent.OpenTicketsDialog, UsersIntent.UpdateTicketsInput("5"), UsersIntent.ConfirmTickets)
        assertEquals(15, (vm.state.value.detail as UiAsync.Success).value.economy?.ticketBalance)
    }

    @Test fun aSuccessfulSaveReloadsTheDossier() = runTest {
        val repo = Repo(listOf(user("a", "Ana")))
        val vm = vm(repo)
        testScheduler.advanceUntilIdle()
        send(vm, UsersIntent.SelectUser("a"))
        assertEquals(1, repo.detailCalls)
        send(vm, UsersIntent.UpdateName("Ana Nueva"), UsersIntent.Save)
        assertEquals(2, repo.detailCalls)
    }

    // --- API-009 ------------------------------------------------------------

    @Test fun saveTellsTheRepositoryWhichRowTheEditorStartedFrom() = runTest {
        val repo = Repo(listOf(user("a", "Ana")))
        val vm = vm(repo)
        testScheduler.advanceUntilIdle()
        send(vm, UsersIntent.SelectUser("a"), UsersIntent.UpdateName("Ana Nueva"), UsersIntent.Save)
        assertEquals("Ana", repo.previouses.single()?.fullName)
        assertEquals(listOf("r1"), repo.previouses.single()?.roleIds)
        assertEquals("Ana Nueva", repo.upserts.single().fullName)
    }

    // --- OC-002 -------------------------------------------------------------

    @Test fun aSlowDetailForADoesNotOverwriteTheDetailOfB() = runTest {
        val repo = Repo(listOf(user("a", "Ana"), user("b", "Beto")))
        repo.detailGates["a"] = CompletableDeferred()
        repo.detailGates["b"] = CompletableDeferred()
        val vm = vm(repo)
        testScheduler.advanceUntilIdle()
        send(vm, UsersIntent.SelectUser("a"), UsersIntent.SelectUser("b"))
        repo.detailGates.getValue("b").complete(detail("b"))
        testScheduler.advanceUntilIdle()
        repo.detailGates.getValue("a").complete(detail("a"))
        testScheduler.advanceUntilIdle()
        assertEquals("b", (vm.state.value.detail as UiAsync.Success).value.profile.userId)
        assertEquals("b", vm.state.value.selectedUserId)
    }

    @Test fun twoRapidSavesRunOneUpsert() = runTest {
        val repo = Repo(listOf(user("a", "Ana")))
        repo.saveGate = CompletableDeferred()
        val vm = vm(repo)
        testScheduler.advanceUntilIdle()
        send(vm, UsersIntent.SelectUser("a"), UsersIntent.UpdateName("Ana Nueva"))
        vm.dispatch(UsersIntent.Save)
        vm.dispatch(UsersIntent.Save)
        testScheduler.advanceUntilIdle()
        assertEquals(1, repo.upserts.size, "el segundo Save no se lanza mientras el primero vuela")
        assertTrue(!vm.state.value.canSave, "canSave se apaga mientras hay una accion en vuelo")
        repo.saveGate!!.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(1, repo.upserts.size)
        assertIs<UiAsync.Success<Unit>>(vm.state.value.actionState)
    }

    @Test fun aSlowSaveOfADoesNotPullTheEditorBackAfterSwitchingToB() = runTest {
        val repo = Repo(listOf(user("a", "Ana"), user("b", "Beto")))
        repo.saveGate = CompletableDeferred()
        val vm = vm(repo)
        testScheduler.advanceUntilIdle()
        send(vm, UsersIntent.SelectUser("a"), UsersIntent.UpdateName("Ana Nueva"))
        vm.dispatch(UsersIntent.Save)
        testScheduler.advanceUntilIdle()
        // Con cambios sin guardar el salto pide confirmacion; se descarta y se pasa a B.
        send(vm, UsersIntent.SelectUser("b"), UsersIntent.ConfirmDiscardChanges)
        assertEquals("b", vm.state.value.selectedUserId)
        repo.saveGate!!.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals("b", vm.state.value.selectedUserId)
        assertEquals("Beto", vm.state.value.editorName)
        assertEquals("Ana Nueva", vm.state.value.loadedUsers.first { it.id == "a" }.fullName, "la fila de A si se refresca")
    }
}
