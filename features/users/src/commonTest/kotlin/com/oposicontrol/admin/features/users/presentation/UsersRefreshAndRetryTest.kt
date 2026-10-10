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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * La lista de usuarios se lee una vez al crear el ViewModel, que vive toda la sesión. Sin «Actualizar» un fallo pasajero
 * dejaba la pantalla sin datos hasta reiniciar la aplicación, y la lista nunca se ponía al día.
 */
class UsersRefreshAndRetryTest {
    private fun user(id: String, name: String, tickets: Int? = null) =
        User(id = id, fullName = name, email = name.lowercase(), roleIds = emptyList(), isRegistered = true, createdAt = null, ticketsBalance = tickets)

    /** Cada lectura devuelve la siguiente respuesta de la cola, como un servidor que cambia entre una y otra. */
    private class Repo(vararg answers: AppResult<List<User>>) : UsersRepository {
        private val queue = ArrayDeque(answers.toList())
        var reads = 0
        override fun observeUsers(): Flow<AppResult<List<User>>> = flow {
            reads++
            emit(if (queue.size > 1) queue.removeFirst() else queue.first())
        }
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

    private val down = AppResult.Failure(AppError.Network("sin red"))

    @Test
    fun `a failed first read can be retried from the screen`() = runTest {
        val repo = Repo(down, AppResult.Success(listOf(user("u1", "Ana"))))
        val vm = viewModel(repo)
        testScheduler.advanceUntilIdle()
        assertIs<UiAsync.Error>(vm.state.value.users)

        vm.dispatch(UsersIntent.Refresh)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("u1"), vm.state.value.loadedUsers.map { it.id })
        assertEquals(2, repo.reads)
        assertFalse(vm.state.value.refreshFailed)
    }

    @Test
    fun `a failed refresh keeps the list that was on screen and says so`() = runTest {
        val repo = Repo(AppResult.Success(listOf(user("u1", "Ana"))), down)
        val vm = viewModel(repo)
        testScheduler.advanceUntilIdle()
        vm.dispatch(UsersIntent.SelectUser("u1"))
        vm.dispatch(UsersIntent.Refresh)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("u1"), vm.state.value.loadedUsers.map { it.id })
        assertTrue(vm.state.value.refreshFailed)
        assertFalse(vm.state.value.refreshing)
        assertEquals("u1", vm.state.value.selectedUserId)
    }

    @Test
    fun `a refresh shows what another administrator saved, but never over what is being typed`() = runTest {
        val repo = Repo(
            AppResult.Success(listOf(user("u1", "Ana"))),
            AppResult.Success(listOf(user("u1", "Ana María"))),
            AppResult.Success(listOf(user("u1", "Ana Mª"))),
        )
        val vm = viewModel(repo)
        testScheduler.advanceUntilIdle()
        vm.dispatch(UsersIntent.SelectUser("u1"))
        testScheduler.advanceUntilIdle()

        // Sin cambios locales: el editor sigue a lo que llega y no queda «sucio» por un cambio ajeno.
        vm.dispatch(UsersIntent.Refresh)
        testScheduler.advanceUntilIdle()
        assertEquals("Ana María", vm.state.value.editorName)
        assertFalse(vm.state.value.isDirty)

        // Con un nombre a medias: el refresco no lo pisa.
        vm.dispatch(UsersIntent.UpdateName("Ana a medias"))
        testScheduler.advanceUntilIdle()
        vm.dispatch(UsersIntent.Refresh)
        testScheduler.advanceUntilIdle()
        assertEquals("Ana a medias", vm.state.value.editorName)
        assertEquals("Ana Mª", vm.state.value.selectedUser?.fullName)
    }

    @Test
    fun `a refreshed row without balance keeps the balance that was already known`() = runTest {
        val repo = Repo(
            AppResult.Success(listOf(user("u1", "Ana", tickets = 120))),
            AppResult.Success(listOf(user("u1", "Ana", tickets = null))),
        )
        val vm = viewModel(repo)
        testScheduler.advanceUntilIdle()
        vm.dispatch(UsersIntent.Refresh)
        testScheduler.advanceUntilIdle()

        assertEquals(120, vm.state.value.loadedUsers.single().ticketsBalance)
    }
}
