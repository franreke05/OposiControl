package com.oposicontrol.admin.features.users.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.AccessRequest
import com.oposicontrol.admin.domain.model.AccessRequestStatus
import com.oposicontrol.admin.domain.model.AccessRequestSubmission
import com.oposicontrol.admin.domain.model.AuthCredentials
import com.oposicontrol.admin.domain.model.Session
import com.oposicontrol.admin.domain.repository.AccessRequestsRepository
import com.oposicontrol.admin.domain.repository.AuthRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Pestana «Solicitudes de acceso»: la visibilidad la decide el servidor (403 = no existe), pendientes primero, y aprobar
 * o rechazar solo salen tras confirmar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccessRequestsViewModelTest {
    private fun request(
        id: String,
        status: AccessRequestStatus = AccessRequestStatus.PENDING,
        createdAt: String = "2026-10-13T09:00:00Z",
        resolvedAt: String? = null,
        name: String = "Persona $id",
    ) = AccessRequest(
        id = id,
        userId = "user-$id",
        name = name,
        email = "$id@example.com",
        reason = "Motivo de $id",
        status = status,
        createdAt = createdAt,
        resolvedAt = resolvedAt,
    )

    private class FakeRepository(initial: List<AccessRequest>) : AccessRequestsRepository {
        var rows = initial
        var listFailure: AppError? = null
        var decisionFailure: AppError? = null
        var listCalls = 0
        val approved = mutableListOf<String>()
        val rejected = mutableListOf<Pair<String, String?>>()

        override suspend fun submitRequest(submission: AccessRequestSubmission): AppResult<Unit> = error("no interviene")

        override suspend fun listRequests(status: AccessRequestStatus?): AppResult<List<AccessRequest>> {
            listCalls++
            listFailure?.let { return AppResult.Failure(it) }
            return AppResult.Success(rows.filter { status == null || it.status == status })
        }

        override suspend fun approve(requestId: String): AppResult<AccessRequest> = decide(requestId, AccessRequestStatus.APPROVED, null)
            .also { if (it is AppResult.Success) approved += requestId }

        override suspend fun reject(requestId: String, reason: String?): AppResult<AccessRequest> = decide(requestId, AccessRequestStatus.REJECTED, reason)
            .also { if (it is AppResult.Success) rejected += requestId to reason }

        private fun decide(id: String, status: AccessRequestStatus, reason: String?): AppResult<AccessRequest> {
            decisionFailure?.let { return AppResult.Failure(it) }
            val resolved = rows.first { it.id == id }.copy(status = status, resolvedAt = "2026-10-13T12:00:00Z", resolutionReason = reason)
            rows = rows.map { if (it.id == id) resolved else it }
            return AppResult.Success(resolved)
        }
    }

    private class FakeAuth(userId: String? = "super-1") : AuthRepository {
        val session = MutableStateFlow(userId?.let(::sessionOf))

        override fun observeSession(): Flow<Session?> = session
        override suspend fun login(credentials: AuthCredentials): AppResult<Session> = error("no interviene")
        override suspend fun refreshSession(): AppResult<Session> = error("no interviene")
        override suspend fun logout(): AppResult<Unit> = error("no interviene")

        companion object {
            fun sessionOf(userId: String) = Session(
                userId = userId,
                displayName = userId,
                accessToken = "token-$userId",
                refreshToken = "refresh",
                expiresAt = Clock.System.now(),
                // Lo que el cliente CREE de sus roles no decide nada: la prueba lo deja en `admin` a proposito.
                roleIds = listOf("admin"),
            )
        }
    }

    private val seed = listOf(
        request("old-approved", AccessRequestStatus.APPROVED, resolvedAt = "2026-10-10T10:00:00Z"),
        request("pending-old", createdAt = "2026-10-11T09:00:00Z"),
        request("rejected", AccessRequestStatus.REJECTED, resolvedAt = "2026-10-12T10:00:00Z"),
        request("pending-new", createdAt = "2026-10-13T09:00:00Z"),
    )

    private fun TestScope.viewModel(repository: FakeRepository, auth: FakeAuth = FakeAuth()) =
        AccessRequestsViewModel(TestDispatcherProvider(StandardTestDispatcher(testScheduler)), repository, auth)

    private fun AccessRequestsViewModel.visibleIds() = (state.value.requests as UiAsync.Success).value.map { it.id }

    // ------------------------------------------------------------------------------------------------ visibilidad

    @Test
    fun `the tab is hidden until the server answers and shown only when the list succeeds`() = runTest {
        val vm = viewModel(FakeRepository(seed))

        assertEquals(AccessReviewAvailability.UNKNOWN, vm.state.value.availability)
        assertFalse(vm.state.value.canReview, "sin respuesta del servidor no hay pestana")

        advanceUntilIdle()

        assertTrue(vm.state.value.canReview)
        assertEquals(2, vm.state.value.pendingCount)
    }

    @Test
    fun `a 403 from the server hides the tab and keeps nothing in memory`() = runTest {
        val repository = FakeRepository(seed).apply { listFailure = AppError.Authorization("No tienes permisos para realizar esta acción.") }
        val vm = viewModel(repository)
        advanceUntilIdle()

        assertEquals(AccessRequestsState(availability = AccessReviewAvailability.DENIED), vm.state.value)
        assertFalse(vm.state.value.canReview)
    }

    @Test
    fun `a network failure neither shows the tab nor claims the user is denied`() = runTest {
        val repository = FakeRepository(seed).apply { listFailure = AppError.Network("Unable to reach the server") }
        val vm = viewModel(repository)
        advanceUntilIdle()

        assertEquals(AccessReviewAvailability.UNKNOWN, vm.state.value.availability)
        assertFalse(vm.state.value.canReview)
        assertIs<UiAsync.Error>(vm.state.value.requests)

        // Vuelve la red: al reabrir el hub se pregunta otra vez y la pestana aparece.
        repository.listFailure = null
        vm.dispatch(AccessRequestsIntent.Refresh)
        advanceUntilIdle()
        assertTrue(vm.state.value.canReview)
    }

    @Test
    fun `losing the superadmin role removes the tab on the next refresh`() = runTest {
        val repository = FakeRepository(seed)
        val vm = viewModel(repository)
        advanceUntilIdle()
        assertTrue(vm.state.value.canReview)

        repository.listFailure = AppError.Authorization("No tienes permisos para realizar esta acción.")
        vm.dispatch(AccessRequestsIntent.Refresh)
        advanceUntilIdle()

        assertFalse(vm.state.value.canReview)
        assertTrue(vm.state.value.allRequests.isEmpty(), "lo leido como superadmin no se queda en memoria")
    }

    @Test
    fun `a failed refresh keeps what was already read and the tab it belongs to`() = runTest {
        val repository = FakeRepository(seed)
        val vm = viewModel(repository)
        advanceUntilIdle()

        repository.listFailure = AppError.Network("Server response failed", 502)
        vm.dispatch(AccessRequestsIntent.Refresh)
        advanceUntilIdle()

        assertTrue(vm.state.value.canReview)
        assertEquals(4, vm.state.value.allRequests.size)
        assertFalse(vm.state.value.refreshing)
    }

    @Test
    fun `another person signing in on this machine starts from nothing`() = runTest {
        val repository = FakeRepository(seed)
        val auth = FakeAuth("super-1")
        val vm = viewModel(repository, auth)
        advanceUntilIdle()
        assertTrue(vm.state.value.canReview)

        // Entra un administrador normal: el servidor le responde 403.
        repository.listFailure = AppError.Authorization("No tienes permisos para realizar esta acción.")
        auth.session.value = FakeAuth.sessionOf("admin-2")
        advanceUntilIdle()

        assertEquals(AccessRequestsState(availability = AccessReviewAvailability.DENIED), vm.state.value)

        // Sin sesion no se pregunta nada.
        val callsBefore = repository.listCalls
        auth.session.value = null
        advanceUntilIdle()
        assertEquals(AccessRequestsState(), vm.state.value)
        assertEquals(callsBefore, repository.listCalls)
    }

    // ------------------------------------------------------------------------------------------------ lista y filtro

    @Test
    fun `pending requests come first and the filter narrows the list without asking again`() = runTest {
        val repository = FakeRepository(seed)
        val vm = viewModel(repository)
        advanceUntilIdle()

        assertEquals(listOf("pending-new", "pending-old", "rejected", "old-approved"), vm.visibleIds())
        assertEquals("pending-new", vm.state.value.selectedId)

        val calls = repository.listCalls
        vm.dispatch(AccessRequestsIntent.UpdateFilter(AccessRequestFilter.PENDING))
        advanceUntilIdle()
        assertEquals(listOf("pending-new", "pending-old"), vm.visibleIds())
        vm.dispatch(AccessRequestsIntent.UpdateFilter(AccessRequestFilter.APPROVED))
        advanceUntilIdle()
        assertEquals(listOf("old-approved"), vm.visibleIds())
        vm.dispatch(AccessRequestsIntent.UpdateFilter(AccessRequestFilter.REJECTED))
        advanceUntilIdle()
        assertEquals(listOf("rejected"), vm.visibleIds())
        assertEquals(calls, repository.listCalls)
    }

    @Test
    fun `an empty queue and a filter without matches are shown as empty`() = runTest {
        val empty = viewModel(FakeRepository(emptyList()))
        advanceUntilIdle()
        assertEquals(UiAsync.Empty, empty.state.value.requests)
        assertTrue(empty.state.value.canReview, "sin solicitudes la pestana sigue existiendo para el superadmin")
        assertNull(empty.state.value.selectedId)

        val onlyPending = viewModel(FakeRepository(listOf(request("p"))))
        advanceUntilIdle()
        onlyPending.dispatch(AccessRequestsIntent.UpdateFilter(AccessRequestFilter.REJECTED))
        advanceUntilIdle()
        assertEquals(UiAsync.Empty, onlyPending.state.value.requests)
    }

    // ------------------------------------------------------------------------------------------------ decisiones

    @Test
    fun `approving asks for confirmation first and then grants through the repository`() = runTest {
        val repository = FakeRepository(seed)
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.dispatch(AccessRequestsIntent.AskApprove("pending-old"))
        advanceUntilIdle()
        val pending = vm.state.value.pendingDecision!!
        assertEquals(AccessDecisionKind.APPROVE, pending.kind)
        assertEquals("pending-old", pending.request.id)
        assertTrue(repository.approved.isEmpty(), "nada se envia sin confirmar")
        assertTrue("administrador" in accessDecisionMessage(pending))

        vm.dispatch(AccessRequestsIntent.ConfirmDecision)
        advanceUntilIdle()

        assertEquals(listOf("pending-old"), repository.approved)
        val state = vm.state.value
        assertNull(state.pendingDecision)
        assertEquals(AccessRequestStatus.APPROVED, state.selected!!.status)
        assertEquals("pending-old", state.selectedId)
        assertEquals(1, state.pendingCount)
        assertEquals("Solicitud aprobada: Persona pending-old ya es administrador.", (state.decisionState as UiAsync.Success).value)
        // La aprobada baja con las resueltas; la que queda pendiente sigue arriba.
        assertEquals(listOf("pending-new", "pending-old", "rejected", "old-approved"), vm.visibleIds())
    }

    @Test
    fun `dismissing the confirmation sends nothing`() = runTest {
        val repository = FakeRepository(seed)
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.dispatch(AccessRequestsIntent.AskReject("pending-new"))
        vm.dispatch(AccessRequestsIntent.DismissDecision)
        vm.dispatch(AccessRequestsIntent.ConfirmDecision)
        advanceUntilIdle()

        assertNull(vm.state.value.pendingDecision)
        assertTrue(repository.approved.isEmpty() && repository.rejected.isEmpty())
        assertEquals(2, vm.state.value.pendingCount)
    }

    @Test
    fun `rejecting carries the optional reason written in the request and clears it afterwards`() = runTest {
        val repository = FakeRepository(seed)
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.dispatch(AccessRequestsIntent.UpdateRejectionReason("  No pertenece al equipo.  "))
        vm.dispatch(AccessRequestsIntent.AskReject("pending-new"))
        advanceUntilIdle()
        val pending = vm.state.value.pendingDecision!!
        assertEquals("No pertenece al equipo.", pending.reason)
        assertTrue("Motivo: No pertenece al equipo." in accessDecisionMessage(pending))

        vm.dispatch(AccessRequestsIntent.ConfirmDecision)
        advanceUntilIdle()

        assertEquals<List<Pair<String, String?>>>(listOf("pending-new" to "No pertenece al equipo."), repository.rejected)
        // Rechazar borra la cuenta y su solicitud: la fila sale de la lista y se abre la siguiente.
        assertTrue(vm.state.value.allRequests.none { it.id == "pending-new" })
        assertEquals("", vm.state.value.rejectionReason)
        assertEquals("Solicitud rechazada y cuenta eliminada.", (vm.state.value.decisionState as UiAsync.Success).value)

        // Sin motivo viaja `null`, no una cadena vacia.
        vm.dispatch(AccessRequestsIntent.Select("pending-old"))
        vm.dispatch(AccessRequestsIntent.AskReject("pending-old"))
        vm.dispatch(AccessRequestsIntent.ConfirmDecision)
        advanceUntilIdle()
        assertEquals<Pair<String, String?>>("pending-old" to null, repository.rejected.last())
    }

    @Test
    fun `the rejection draft belongs to one request and is capped`() = runTest {
        val vm = viewModel(FakeRepository(seed))
        advanceUntilIdle()

        vm.dispatch(AccessRequestsIntent.UpdateRejectionReason("x".repeat(700)))
        advanceUntilIdle()
        assertEquals(500, vm.state.value.rejectionReason.length)

        vm.dispatch(AccessRequestsIntent.Select("pending-old"))
        advanceUntilIdle()
        assertEquals("", vm.state.value.rejectionReason, "el motivo escrito para una no se arrastra a otra")
    }

    @Test
    fun `only pending requests can be decided`() = runTest {
        val repository = FakeRepository(seed)
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.dispatch(AccessRequestsIntent.AskApprove("rejected"))
        vm.dispatch(AccessRequestsIntent.AskReject("old-approved"))
        vm.dispatch(AccessRequestsIntent.AskApprove("no-existe"))
        advanceUntilIdle()

        assertNull(vm.state.value.pendingDecision)
    }

    @Test
    fun `a failed decision is reported and the list is read again from the server`() = runTest {
        val repository = FakeRepository(seed).apply { decisionFailure = AppError.Validation("La solicitud ya fue rechazada.", code = "conflict") }
        val vm = viewModel(repository)
        advanceUntilIdle()
        val calls = repository.listCalls

        // Otro superadministrador la rechazo mientras tanto.
        repository.rows = repository.rows.map { if (it.id == "pending-new") it.copy(status = AccessRequestStatus.REJECTED, resolvedAt = "2026-10-13T11:00:00Z") else it }
        vm.dispatch(AccessRequestsIntent.AskApprove("pending-new"))
        vm.dispatch(AccessRequestsIntent.ConfirmDecision)
        advanceUntilIdle()

        assertEquals("La solicitud ya fue rechazada.", (vm.state.value.decisionState as UiAsync.Error).error.message)
        assertEquals(calls + 1, repository.listCalls)
        assertEquals(AccessRequestStatus.REJECTED, vm.state.value.allRequests.first { it.id == "pending-new" }.status)
        assertTrue(repository.approved.isEmpty())
    }

    @Test
    fun `a 403 on a decision removes the tab`() = runTest {
        val repository = FakeRepository(seed)
        val vm = viewModel(repository)
        advanceUntilIdle()

        val denied = AppError.Authorization("No tienes permisos para realizar esta acción.")
        repository.decisionFailure = denied
        repository.listFailure = denied
        vm.dispatch(AccessRequestsIntent.AskApprove("pending-new"))
        vm.dispatch(AccessRequestsIntent.ConfirmDecision)
        advanceUntilIdle()

        assertFalse(vm.state.value.canReview)
        assertTrue(vm.state.value.allRequests.isEmpty())
    }
}
