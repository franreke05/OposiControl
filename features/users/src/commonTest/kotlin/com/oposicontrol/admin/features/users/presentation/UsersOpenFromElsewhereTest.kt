package com.oposicontrol.admin.features.users.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Abrir una ficha desde OTRA pantalla (OposiTickets, un uso de promoción). El ViewModel de Usuarios se crea al pulsar, así
 * que la petición llega antes que la lista: antes se perdía en silencio y se aterrizaba en la ficha de otra persona.
 */
class UsersOpenFromElsewhereTest {
    private fun user(id: String, name: String) =
        User(id = id, fullName = name, email = name.lowercase(), roleIds = emptyList(), isRegistered = true, createdAt = null)

    private val ana = user("u-ana", "Ana")
    private val luis = user("u-luis", "Luis")

    /** La lista llega cuando la prueba lo decide, como una red lenta. */
    private class Repo : UsersRepository {
        val users = MutableSharedFlow<AppResult<List<User>>>(replay = 1)
        override fun observeUsers(): Flow<AppResult<List<User>>> = users
        override suspend fun getUser(userId: String): AppResult<User> = AppResult.Failure(AppError.Network("unused"))
        override suspend fun upsertUser(user: User, previous: User?): AppResult<User> = error("unused")
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

    private fun TestScope.viewModel(repo: Repo) = UsersViewModel(
        TestDispatcherProvider(StandardTestDispatcher(testScheduler)), repo, NoRoles,
        UpsertUserUseCase(repo), NoModeration, NoOpAnalyticsTracker,
    )

    @Test
    fun `a user asked for before the list arrives is opened when it does`() = runTest {
        val repo = Repo()
        val vm = viewModel(repo)
        vm.dispatch(UsersIntent.SelectUser(luis.id))
        testScheduler.advanceUntilIdle()

        // Todavía no hay lista: la petición se guarda, no se pierde.
        assertEquals(luis.id, vm.state.value.pendingOpenUserId)
        assertNull(vm.state.value.selectedUserId)

        repo.users.emit(AppResult.Success(listOf(ana, luis)))
        testScheduler.advanceUntilIdle()

        assertEquals(luis.id, vm.state.value.selectedUserId)
        assertEquals("Luis", vm.state.value.editorName)
        assertNull(vm.state.value.pendingOpenUserId)
        assertFalse(vm.state.value.openUserMissing)
    }

    @Test
    fun `a user that is not in the list is said to be missing instead of showing someone else`() = runTest {
        val repo = Repo()
        val vm = viewModel(repo)
        vm.dispatch(UsersIntent.SelectUser("u-fantasma"))
        testScheduler.advanceUntilIdle()
        repo.users.emit(AppResult.Success(listOf(ana, luis)))
        testScheduler.advanceUntilIdle()

        assertTrue(vm.state.value.openUserMissing)
        assertNull(vm.state.value.selectedUserId)
        assertNull(vm.state.value.pendingOpenUserId)

        // Con la lista ya cargada se dice al momento, sin esperar a nada.
        vm.dispatch(UsersIntent.SelectUser(ana.id))
        testScheduler.advanceUntilIdle()
        assertFalse(vm.state.value.openUserMissing)
        vm.dispatch(UsersIntent.SelectUser("u-otro-fantasma"))
        testScheduler.advanceUntilIdle()
        assertTrue(vm.state.value.openUserMissing)
        // La ficha que estaba abierta no se cambia por la de nadie.
        assertEquals(ana.id, vm.state.value.selectedUserId)
    }

    @Test
    fun `a failed list drops the pending request without claiming the user does not exist`() = runTest {
        val repo = Repo()
        val vm = viewModel(repo)
        vm.dispatch(UsersIntent.SelectUser(luis.id))
        testScheduler.advanceUntilIdle()
        repo.users.emit(AppResult.Failure(AppError.Network("sin red")))
        testScheduler.advanceUntilIdle()

        assertNull(vm.state.value.pendingOpenUserId)
        // Lo que falló es la lista, y la lista ya lo dice: no se afirma que la persona no exista.
        assertFalse(vm.state.value.openUserMissing)
    }

    @Test
    fun `sentinel states are said in words and subscription codes are readable`() {
        assertEquals("Sin dato", NO_DATA_TEXT)
        assertEquals("No disponible", NOT_AVAILABLE_TEXT)
        assertEquals("Google Play", subscriptionProviderLabel("google_play"))
        assertEquals("pasarela_nueva", subscriptionProviderLabel("pasarela_nueva"))
        assertEquals("Activa", subscriptionStatusLabel("activa"))
        assertEquals("En periodo de gracia", subscriptionStatusLabel("en_periodo_de_gracia"))
    }
}
