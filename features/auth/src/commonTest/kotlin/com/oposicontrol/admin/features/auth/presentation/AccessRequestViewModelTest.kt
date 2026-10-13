package com.oposicontrol.admin.features.auth.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.AccessRequest
import com.oposicontrol.admin.domain.model.AccessRequestStatus
import com.oposicontrol.admin.domain.model.AccessRequestSubmission
import com.oposicontrol.admin.domain.repository.AccessRequestsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Formulario publico «Solicitar acceso»: valida antes de enviar, ensena el aviso de «pendiente de aprobacion» tras un
 * envio aceptado y no conserva la contrasena mas alla del envio.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccessRequestViewModelTest {
    private val password = "S3creta-Larga-No-Repetible-9731"

    private class FakeRepository : AccessRequestsRepository {
        val submissions = mutableListOf<AccessRequestSubmission>()
        var result: AppResult<Unit> = AppResult.Success(Unit)

        /** Si no es nulo, el envio queda en vuelo hasta completarlo: permite mirar el estado DURANTE la llamada. */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun submitRequest(submission: AccessRequestSubmission): AppResult<Unit> {
            submissions += submission
            gate?.await()
            return result
        }

        override suspend fun listRequests(status: AccessRequestStatus?): AppResult<List<AccessRequest>> = error("no interviene")
        override suspend fun approve(requestId: String): AppResult<AccessRequest> = error("no interviene")
        override suspend fun reject(requestId: String, reason: String?): AppResult<AccessRequest> = error("no interviene")
    }

    private fun TestScope.viewModel(repository: FakeRepository) =
        AccessRequestViewModel(TestDispatcherProvider(StandardTestDispatcher(testScheduler)), repository)

    private fun AccessRequestViewModel.fill(
        name: String = "  Ada Lovelace ",
        email: String = " ada@example.com ",
        password: String = this@AccessRequestViewModelTest.password,
        confirmation: String = password,
        reason: String = "  Coordino el equipo de contenido. ",
    ) {
        dispatch(AccessRequestIntent.UpdateName(name))
        dispatch(AccessRequestIntent.UpdateEmail(email))
        dispatch(AccessRequestIntent.UpdatePassword(password))
        dispatch(AccessRequestIntent.UpdatePasswordConfirmation(confirmation))
        dispatch(AccessRequestIntent.UpdateReason(reason))
    }

    @Test
    fun `a valid form is sent trimmed and the screen shows the pending approval state`() = runTest {
        val repository = FakeRepository()
        val vm = viewModel(repository)

        vm.fill()
        vm.dispatch(AccessRequestIntent.Submit)
        advanceUntilIdle()

        val sent = repository.submissions.single()
        assertEquals("Ada Lovelace", sent.name)
        assertEquals("ada@example.com", sent.email)
        assertEquals(password, sent.password)
        assertEquals("Coordino el equipo de contenido.", sent.reason)
        val state = vm.state.value
        assertTrue(state.isSent)
        assertEquals(UiAsync.Success(Unit), state.submitState)
        // Enviada: no queda nada del formulario en memoria.
        assertEquals(AccessRequestState(submitState = UiAsync.Success(Unit)), state)
    }

    @Test
    fun `the answer is the same whether or not the email already had an account`() = runTest {
        // El servidor responde 202 en los dos casos: el repositorio da Success y la pantalla no puede distinguirlos.
        val fresh = viewModel(FakeRepository()).also { it.fill(email = "nueva@example.com") }
        val existing = viewModel(FakeRepository()).also { it.fill(email = "ya-registrada@example.com") }

        fresh.dispatch(AccessRequestIntent.Submit)
        existing.dispatch(AccessRequestIntent.Submit)
        advanceUntilIdle()

        assertEquals(fresh.state.value, existing.state.value)
        assertTrue(ACCESS_REQUEST_SENT_DETAIL.contains("superadministrador"))
        assertFalse(ACCESS_REQUEST_SENT_DETAIL.contains("ya exist", ignoreCase = true))
    }

    @Test
    fun `nothing is sent while the form is invalid and each field says what is wrong`() = runTest {
        val repository = FakeRepository()
        val vm = viewModel(repository)

        vm.dispatch(AccessRequestIntent.Submit)
        advanceUntilIdle()
        assertEquals(
            setOf(AccessRequestField.NAME, AccessRequestField.EMAIL, AccessRequestField.PASSWORD, AccessRequestField.REASON),
            vm.state.value.fieldErrors.keys,
        )

        vm.fill(email = "sin-arroba", password = "corta-11ch!", confirmation = "otra-cosa-distinta")
        vm.dispatch(AccessRequestIntent.Submit)
        advanceUntilIdle()
        assertEquals(
            setOf(AccessRequestField.EMAIL, AccessRequestField.PASSWORD, AccessRequestField.PASSWORD_CONFIRMATION),
            vm.state.value.fieldErrors.keys,
        )

        vm.fill(confirmation = "$password-distinta")
        vm.dispatch(AccessRequestIntent.Submit)
        advanceUntilIdle()
        assertEquals(setOf(AccessRequestField.PASSWORD_CONFIRMATION), vm.state.value.fieldErrors.keys)

        assertTrue(repository.submissions.isEmpty())
        assertEquals(UiAsync.Idle, vm.state.value.submitState)
    }

    @Test
    fun `the limits are the ones the server enforces`() {
        fun errorsOf(state: AccessRequestState) = validateAccessRequest(state).keys
        val valid = AccessRequestState(name = "Ada", email = "ada@example.com", password = "exacta-12-ch", passwordConfirmation = "exacta-12-ch", reason = "m")
        assertEquals(emptySet(), errorsOf(valid))
        assertEquals(emptySet(), errorsOf(valid.copy(name = "a".repeat(120), reason = "m".repeat(1_000))))
        assertEquals(setOf(AccessRequestField.NAME), errorsOf(valid.copy(name = "a".repeat(121))))
        assertEquals(setOf(AccessRequestField.REASON), errorsOf(valid.copy(reason = "m".repeat(1_001))))
        val long = "p".repeat(129)
        assertEquals(setOf(AccessRequestField.PASSWORD), errorsOf(valid.copy(password = long, passwordConfirmation = long)))
        assertEquals(setOf(AccessRequestField.EMAIL), errorsOf(valid.copy(email = "a b@example.com")))
    }

    @Test
    fun `editing a field clears only its own error`() = runTest {
        val vm = viewModel(FakeRepository())
        vm.dispatch(AccessRequestIntent.Submit)
        advanceUntilIdle()

        vm.dispatch(AccessRequestIntent.UpdateName("Ada"))
        advanceUntilIdle()

        assertEquals(
            setOf(AccessRequestField.EMAIL, AccessRequestField.PASSWORD, AccessRequestField.REASON),
            vm.state.value.fieldErrors.keys,
        )
    }

    @Test
    fun `the password leaves the state before the request is in flight and never comes back`() = runTest {
        val repository = FakeRepository().apply { gate = CompletableDeferred() }
        val vm = viewModel(repository)
        vm.fill()

        vm.dispatch(AccessRequestIntent.Submit)
        runCurrent()

        // En vuelo: el repositorio ya tiene la contrasena y el estado ya no.
        assertEquals(password, repository.submissions.single().password)
        val inFlight = vm.state.value
        assertTrue(inFlight.isSubmitting)
        assertEquals("", inFlight.password)
        assertEquals("", inFlight.passwordConfirmation)

        repository.result = AppResult.Failure(AppError.Network("Server response failed", 503))
        repository.gate!!.complete(Unit)
        advanceUntilIdle()

        val failed = vm.state.value
        assertIs<UiAsync.Error>(failed.submitState)
        assertEquals("", failed.password)
        assertEquals("", failed.passwordConfirmation)
        // El resto se conserva para no reescribirlo.
        assertEquals("  Ada Lovelace ", failed.name)
        assertEquals(" ada@example.com ", failed.email)
        assertFalse(password in failed.toString())
    }

    @Test
    fun `a second submit while one is in flight does nothing`() = runTest {
        val repository = FakeRepository().apply { gate = CompletableDeferred() }
        val vm = viewModel(repository)
        vm.fill()

        vm.dispatch(AccessRequestIntent.Submit)
        vm.dispatch(AccessRequestIntent.Submit)
        runCurrent()
        repository.gate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, repository.submissions.size)
        assertTrue(vm.state.value.isSent)
    }

    @Test
    fun `after a failure the user retypes the password and can send again`() = runTest {
        val repository = FakeRepository().apply { result = AppResult.Failure(AppError.Validation("Demasiadas peticiones. Vuelve a intentarlo en unos segundos.")) }
        val vm = viewModel(repository)
        vm.fill()
        vm.dispatch(AccessRequestIntent.Submit)
        advanceUntilIdle()
        assertEquals(
            "Demasiadas peticiones. Vuelve a intentarlo en unos segundos.",
            (vm.state.value.submitState as UiAsync.Error).error.message,
        )

        // Sin volver a escribirla no hay nada que enviar.
        vm.dispatch(AccessRequestIntent.Submit)
        advanceUntilIdle()
        assertEquals(1, repository.submissions.size)
        assertTrue(AccessRequestField.PASSWORD in vm.state.value.fieldErrors)

        repository.result = AppResult.Success(Unit)
        vm.dispatch(AccessRequestIntent.UpdatePassword(password))
        vm.dispatch(AccessRequestIntent.UpdatePasswordConfirmation(password))
        vm.dispatch(AccessRequestIntent.Submit)
        advanceUntilIdle()

        assertEquals(2, repository.submissions.size)
        assertTrue(vm.state.value.isSent)
    }

    @Test
    fun `the state never prints the password and reset empties the form`() = runTest {
        val vm = viewModel(FakeRepository())
        vm.fill()
        advanceUntilIdle()

        assertEquals(password, vm.state.value.password)
        assertFalse(password in vm.state.value.toString())

        vm.dispatch(AccessRequestIntent.Reset)
        advanceUntilIdle()
        assertEquals(AccessRequestState(), vm.state.value)
    }
}
