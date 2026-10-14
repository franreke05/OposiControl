package com.oposicontrol.admin.features.auth.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.AccessRequest
import com.oposicontrol.admin.domain.model.AccessRequestCodeOutcome
import com.oposicontrol.admin.domain.model.AccessRequestRules
import com.oposicontrol.admin.domain.model.AccessRequestStatus
import com.oposicontrol.admin.domain.model.AccessRequestSubmission
import com.oposicontrol.admin.domain.repository.AccessRequestsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Formulario publico «Solicitar acceso»: valida antes de enviar y pasa a «verifica tu correo». Revision de seguridad
 * 2026-10-14: la contrasena ya NO va en la solicitud; se elige en el paso de verificacion, junto al codigo, y no se conserva
 * mas alla de esa llamada.
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

        /** (correo, codigo, contrasena) de cada verificacion. */
        val verifications = mutableListOf<Triple<String, String, String>>()
        var verifyResults = ArrayDeque<AppResult<AccessRequestCodeOutcome>>()

        /** Si no es nulo, la verificacion queda en vuelo hasta completarlo. */
        var verifyGate: CompletableDeferred<Unit>? = null
        val resends = mutableListOf<String>()
        var resendResult: AppResult<AccessRequestCodeOutcome> = AppResult.Success(AccessRequestCodeOutcome.Accepted)

        override suspend fun verifyEmail(email: String, code: String, password: String): AppResult<AccessRequestCodeOutcome> {
            verifications += Triple(email, code, password)
            verifyGate?.await()
            return verifyResults.removeFirstOrNull() ?: AppResult.Success(AccessRequestCodeOutcome.Accepted)
        }

        override suspend fun resendCode(email: String): AppResult<AccessRequestCodeOutcome> {
            resends += email
            return resendResult
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
        reason: String = "  Coordino el equipo de contenido. ",
    ) {
        dispatch(AccessRequestIntent.UpdateName(name))
        dispatch(AccessRequestIntent.UpdateEmail(email))
        dispatch(AccessRequestIntent.UpdateReason(reason))
    }

    /** El paso de verificacion: el codigo y la contrasena elegida (dos veces). */
    private fun AccessRequestViewModel.fillVerify(
        code: String = "654321",
        password: String = this@AccessRequestViewModelTest.password,
        confirmation: String = password,
    ) {
        dispatch(AccessRequestIntent.UpdateCode(code))
        dispatch(AccessRequestIntent.UpdatePassword(password))
        dispatch(AccessRequestIntent.UpdatePasswordConfirmation(confirmation))
    }

    @Test
    fun `a valid form is sent trimmed and canonical without any password and the screen moves to the verify step`() = runTest {
        val repository = FakeRepository()
        val vm = viewModel(repository)

        vm.fill(email = " Ada@Example.COM ")
        vm.dispatch(AccessRequestIntent.Submit)
        advanceUntilIdle()

        val sent = repository.submissions.single()
        assertEquals("Ada Lovelace", sent.name)
        assertEquals("ada@example.com", sent.email)
        assertEquals("Coordino el equipo de contenido.", sent.reason)
        val state = vm.state.value
        assertTrue(state.isSent)
        assertEquals(AccessRequestStep.VERIFY, state.step)
        // Enviada: no queda nada del formulario en memoria salvo el correo canonico al que va el codigo.
        assertEquals(AccessRequestState(submitState = UiAsync.Success(Unit), step = AccessRequestStep.VERIFY, verificationEmail = "ada@example.com"), state)
    }

    @Test
    fun `the answer is the same whether or not the email already had an account`() = runTest {
        // El servidor responde 202 en los dos casos: el repositorio da Success y la pantalla no puede distinguirlos.
        val fresh = viewModel(FakeRepository()).also { it.fill(email = "misma@example.com") }
        val existing = viewModel(FakeRepository()).also { it.fill(email = "misma@example.com") }

        fresh.dispatch(AccessRequestIntent.Submit)
        existing.dispatch(AccessRequestIntent.Submit)
        advanceUntilIdle()

        assertEquals(fresh.state.value, existing.state.value)
        assertEquals(AccessRequestStep.VERIFY, fresh.state.value.step)
        assertFalse(accessVerifyIntro("misma@example.com").contains("ya exist", ignoreCase = true))
        assertTrue(ACCESS_REQUEST_VERIFIED_TITLE.contains("superadministrador"))
    }

    @Test
    fun `nothing is sent while the form is invalid and each field says what is wrong`() = runTest {
        val repository = FakeRepository()
        val vm = viewModel(repository)

        vm.dispatch(AccessRequestIntent.Submit)
        advanceUntilIdle()
        assertEquals(setOf(AccessRequestField.NAME, AccessRequestField.EMAIL, AccessRequestField.REASON), vm.state.value.fieldErrors.keys)

        vm.fill(email = "sin-arroba")
        vm.dispatch(AccessRequestIntent.Submit)
        advanceUntilIdle()
        assertEquals(setOf(AccessRequestField.EMAIL), vm.state.value.fieldErrors.keys)

        assertTrue(repository.submissions.isEmpty())
        assertEquals(UiAsync.Idle, vm.state.value.submitState)
    }

    @Test
    fun `the limits are the ones the server enforces`() {
        fun errorsOf(state: AccessRequestState) = validateAccessRequest(state).keys
        val valid = AccessRequestState(name = "Ada", email = "ada@example.com", reason = "m")
        assertEquals(emptySet(), errorsOf(valid))
        assertEquals(emptySet(), errorsOf(valid.copy(name = "a".repeat(120), reason = "m".repeat(1_000))))
        assertEquals(setOf(AccessRequestField.NAME), errorsOf(valid.copy(name = "a".repeat(121))))
        assertEquals(setOf(AccessRequestField.REASON), errorsOf(valid.copy(reason = "m".repeat(1_001))))
        assertEquals(setOf(AccessRequestField.EMAIL), errorsOf(valid.copy(email = "a b@example.com")))

        fun passwordErrorsOf(state: AccessRequestState) = validateAccessPassword(state).keys
        val chosen = AccessRequestState(password = "exacta-12-ch", passwordConfirmation = "exacta-12-ch")
        assertEquals(emptySet(), passwordErrorsOf(chosen))
        val long = "p".repeat(73)
        assertEquals(setOf(AccessRequestField.PASSWORD), passwordErrorsOf(chosen.copy(password = long, passwordConfirmation = long)))
        assertEquals(setOf(AccessRequestField.PASSWORD_CONFIRMATION), passwordErrorsOf(chosen.copy(passwordConfirmation = "otra-distinta")))
        assertEquals(setOf(AccessRequestField.PASSWORD), passwordErrorsOf(AccessRequestState()))
    }

    @Test
    fun `editing a field clears only its own error`() = runTest {
        val vm = viewModel(FakeRepository())
        vm.dispatch(AccessRequestIntent.Submit)
        advanceUntilIdle()

        vm.dispatch(AccessRequestIntent.UpdateName("Ada"))
        advanceUntilIdle()

        assertEquals(setOf(AccessRequestField.EMAIL, AccessRequestField.REASON), vm.state.value.fieldErrors.keys)
    }

    @Test
    fun `password intents do nothing on the request form`() = runTest {
        val vm = viewModel(FakeRepository())

        vm.dispatch(AccessRequestIntent.UpdatePassword(password))
        vm.dispatch(AccessRequestIntent.UpdatePasswordConfirmation(password))
        advanceUntilIdle()

        assertEquals("", vm.state.value.password)
        assertEquals("", vm.state.value.passwordConfirmation)
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
    fun `after a failure the form keeps its fields and can be sent again`() = runTest {
        val repository = FakeRepository().apply { result = AppResult.Failure(AppError.Validation("Demasiadas peticiones. Vuelve a intentarlo en unos segundos.")) }
        val vm = viewModel(repository)
        vm.fill()
        vm.dispatch(AccessRequestIntent.Submit)
        advanceUntilIdle()
        assertEquals(
            "Demasiadas peticiones. Vuelve a intentarlo en unos segundos.",
            (vm.state.value.submitState as UiAsync.Error).error.message,
        )
        assertEquals("  Ada Lovelace ", vm.state.value.name)
        assertEquals(" ada@example.com ", vm.state.value.email)

        repository.result = AppResult.Success(Unit)
        vm.dispatch(AccessRequestIntent.Submit)
        advanceUntilIdle()

        assertEquals(2, repository.submissions.size)
        assertTrue(vm.state.value.isSent)
    }

    @Test
    fun `reset empties the form`() = runTest {
        val vm = viewModel(FakeRepository())
        vm.fill()
        advanceUntilIdle()

        vm.dispatch(AccessRequestIntent.Reset)
        advanceUntilIdle()
        assertEquals(AccessRequestState(), vm.state.value)
    }

    // ------------------------------------------------------------------------------------------------ verificacion

    /** Envia una solicitud valida y deja la pantalla en el paso de verificacion (con el enfriamiento ya agotado). */
    private fun TestScope.verifyStep(repository: FakeRepository): AccessRequestViewModel {
        val vm = viewModel(repository)
        vm.fill()
        vm.dispatch(AccessRequestIntent.Submit)
        advanceUntilIdle()
        assertEquals(AccessRequestStep.VERIFY, vm.state.value.step)
        return vm
    }

    @Test
    fun `the code field keeps only digits and at most six`() = runTest {
        val vm = verifyStep(FakeRepository())

        vm.dispatch(AccessRequestIntent.UpdateCode("12a 3-45678"))
        advanceUntilIdle()
        assertEquals("123456", vm.state.value.code)
        assertTrue(vm.state.value.canVerify)

        vm.dispatch(AccessRequestIntent.UpdateCode("12345"))
        advanceUntilIdle()
        assertFalse(vm.state.value.canVerify)
        vm.dispatch(AccessRequestIntent.Verify)
        advanceUntilIdle()
        assertEquals(CODE_INCOMPLETE_MESSAGE, vm.state.value.codeError)
        assertFalse("12345" in vm.state.value.toString(), "el codigo no se imprime")
    }

    @Test
    fun `a good code verifies the email with the submitted address`() = runTest {
        val repository = FakeRepository()
        val vm = verifyStep(repository)

        vm.fillVerify()
        vm.dispatch(AccessRequestIntent.Verify)
        advanceUntilIdle()

        assertEquals(listOf(Triple("ada@example.com", "654321", password)), repository.verifications)
        assertEquals(AccessRequestStep.VERIFIED, vm.state.value.step)
        assertEquals("", vm.state.value.code)
        assertEquals("Correo verificado. Un superadministrador revisará tu solicitud.", ACCESS_REQUEST_VERIFIED_TITLE)
    }

    @Test
    fun `a rejected code says so generically clears the field and hints at expiry after repeated failures`() = runTest {
        val repository = FakeRepository().apply {
            verifyResults = ArrayDeque(List(2) { AppResult.Success(AccessRequestCodeOutcome.InvalidCode) })
        }
        val vm = verifyStep(repository)

        vm.fillVerify(code = "111111")
        vm.dispatch(AccessRequestIntent.Verify)
        advanceUntilIdle()
        assertEquals(AccessVerificationFeedback.InvalidCode, vm.state.value.verificationFeedback)
        assertEquals("", vm.state.value.code)
        assertFalse(vm.state.value.showExpiryHint)
        assertEquals(
            "El código no es válido o ha caducado. Revisa el correo o pide otro. Tras cinco intentos fallidos la solicitud se anula y hay que enviarla de nuevo.",
            accessVerificationNotice(AccessVerificationFeedback.InvalidCode, "ada@example.com").second,
        )

        vm.fillVerify(code = "222222")
        vm.dispatch(AccessRequestIntent.Verify)
        advanceUntilIdle()
        assertEquals(AccessRequestStep.VERIFY, vm.state.value.step)
        assertTrue(vm.state.value.showExpiryHint)
    }

    @Test
    fun `too many attempts uses the retry after and an outage is said as unavailable`() = runTest {
        val repository = FakeRepository().apply {
            verifyResults = ArrayDeque(
                listOf(
                    AppResult.Success(AccessRequestCodeOutcome.RateLimited(retryAfterSeconds = 600)),
                    AppResult.Failure(AppError.Network("Server response failed", 503)),
                    AppResult.Failure(AppError.Network("Unable to reach the server")),
                ),
            )
        }
        val vm = verifyStep(repository)

        vm.fillVerify(code = "123456")
        vm.dispatch(AccessRequestIntent.Verify)
        advanceUntilIdle()
        val limited = vm.state.value.verificationFeedback
        assertEquals(AccessVerificationFeedback.RateLimited(600), limited)
        assertEquals("Vuelve a intentarlo dentro de 10 minutos.", accessVerificationNotice(limited!!, "ada@example.com").third)
        // El codigo bueno no se pierde por un 429: se puede reintentar luego.
        assertEquals("123456", vm.state.value.code)

        // La contrasena salio del estado con la llamada: se vuelve a escribir para reintentar.
        vm.fillVerify(code = "123456")
        vm.dispatch(AccessRequestIntent.Verify)
        advanceUntilIdle()
        assertEquals(AccessVerificationFeedback.Unavailable, vm.state.value.verificationFeedback)

        vm.fillVerify(code = "123456")
        vm.dispatch(AccessRequestIntent.Verify)
        advanceUntilIdle()
        assertIs<AccessVerificationFeedback.Failed>(vm.state.value.verificationFeedback)
        assertEquals(AccessRequestStep.VERIFY, vm.state.value.step)
    }

    @Test
    fun `retry after labels`() {
        assertEquals("dentro de unos minutos", accessRetryAfterLabel(null))
        assertEquals("dentro de 1 segundo", accessRetryAfterLabel(1))
        assertEquals("dentro de 30 segundos", accessRetryAfterLabel(30))
        assertEquals("dentro de 1 minuto", accessRetryAfterLabel(60))
        assertEquals("dentro de 2 minutos", accessRetryAfterLabel(61))
    }

    @Test
    fun `resend waits sixty seconds after the submit and again after each resend`() = runTest {
        val repository = FakeRepository()
        val vm = viewModel(repository)
        vm.fill()
        vm.dispatch(AccessRequestIntent.Submit)
        runCurrent()

        assertEquals(AccessRequestState.RESEND_COOLDOWN_SECONDS, vm.state.value.resendCooldownSeconds)
        assertFalse(vm.state.value.canResend)
        assertEquals("Reenviar código (60 s)", accessResendLabel(vm.state.value.resendCooldownSeconds))
        vm.dispatch(AccessRequestIntent.ResendCode)
        runCurrent()
        assertTrue(repository.resends.isEmpty(), "en enfriamiento no se pide nada")

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(30, vm.state.value.resendCooldownSeconds)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(0, vm.state.value.resendCooldownSeconds)
        assertTrue(vm.state.value.canResend)
        assertEquals("Reenviar código", accessResendLabel(0))

        vm.dispatch(AccessRequestIntent.ResendCode)
        runCurrent()
        assertEquals(listOf("ada@example.com"), repository.resends)
        assertEquals(AccessVerificationFeedback.CodeResent, vm.state.value.verificationFeedback)
        assertEquals(AccessRequestState.RESEND_COOLDOWN_SECONDS, vm.state.value.resendCooldownSeconds)
        advanceUntilIdle()
        assertEquals(0, vm.state.value.resendCooldownSeconds)
    }

    @Test
    fun `a rate limited resend waits what the server says`() = runTest {
        val repository = FakeRepository().apply { resendResult = AppResult.Success(AccessRequestCodeOutcome.RateLimited(retryAfterSeconds = 300)) }
        val vm = verifyStep(repository)

        vm.dispatch(AccessRequestIntent.ResendCode)
        runCurrent()

        assertEquals(AccessVerificationFeedback.RateLimited(300), vm.state.value.verificationFeedback)
        assertEquals(300, vm.state.value.resendCooldownSeconds)
    }

    @Test
    fun `going back keeps the submitted email so a new request can be sent`() = runTest {
        val vm = verifyStep(FakeRepository())

        vm.dispatch(AccessRequestIntent.BackToForm)
        advanceUntilIdle()

        assertEquals(AccessRequestState(email = "ada@example.com"), vm.state.value)
    }

    // ------------------------------------------------------------------------------------------------ contrasena al verificar

    @Test
    fun `the verify step asks for the password and checks it locally before calling the server`() = runTest {
        val repository = FakeRepository()
        val vm = verifyStep(repository)

        vm.dispatch(AccessRequestIntent.UpdateCode("654321"))
        vm.dispatch(AccessRequestIntent.Verify)
        advanceUntilIdle()
        assertEquals(setOf(AccessRequestField.PASSWORD), vm.state.value.fieldErrors.keys)

        vm.fillVerify(password = "corta-11ch!", confirmation = "otra-cosa-distinta")
        vm.dispatch(AccessRequestIntent.Verify)
        advanceUntilIdle()
        assertEquals(setOf(AccessRequestField.PASSWORD, AccessRequestField.PASSWORD_CONFIRMATION), vm.state.value.fieldErrors.keys)
        assertEquals(PASSWORD_RULE_MESSAGE, vm.state.value.fieldErrors[AccessRequestField.PASSWORD])

        // Editar la contrasena limpia SOLO su error.
        vm.dispatch(AccessRequestIntent.UpdatePassword(password))
        advanceUntilIdle()
        assertEquals(emptySet(), vm.state.value.fieldErrors.keys - AccessRequestField.PASSWORD_CONFIRMATION)

        assertTrue(repository.verifications.isEmpty(), "nada invalido llega al servidor")
        assertEquals(AccessRequestStep.VERIFY, vm.state.value.step)
    }

    @Test
    fun `the password leaves the state before the verify request is in flight and never comes back`() = runTest {
        val repository = FakeRepository().apply {
            verifyGate = CompletableDeferred()
            verifyResults = ArrayDeque(listOf(AppResult.Failure(AppError.Network("Server response failed", 503))))
        }
        val vm = verifyStep(repository)
        vm.fillVerify()
        advanceUntilIdle()
        assertEquals(password, vm.state.value.password)
        assertFalse(password in vm.state.value.toString(), "el estado no imprime la contrasena")

        vm.dispatch(AccessRequestIntent.Verify)
        runCurrent()

        // En vuelo: el repositorio ya tiene la contrasena y el estado ya no.
        assertEquals(password, repository.verifications.single().third)
        val inFlight = vm.state.value
        assertTrue(inFlight.verifying)
        assertEquals("", inFlight.password)
        assertEquals("", inFlight.passwordConfirmation)

        repository.verifyGate!!.complete(Unit)
        advanceUntilIdle()

        val failed = vm.state.value
        assertEquals(AccessVerificationFeedback.Unavailable, failed.verificationFeedback)
        assertEquals("", failed.password)
        assertEquals("", failed.passwordConfirmation)
        assertEquals("654321", failed.code, "el codigo se conserva para reintentar")
        assertFalse(password in failed.toString())
    }

    @Test
    fun `a password the server rejects is said on the password field with the rule and keeps the code`() = runTest {
        val repository = FakeRepository().apply { verifyResults = ArrayDeque(listOf(AppResult.Success(AccessRequestCodeOutcome.InvalidPassword))) }
        val vm = verifyStep(repository)

        vm.fillVerify()
        vm.dispatch(AccessRequestIntent.Verify)
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(mapOf(AccessRequestField.PASSWORD to PASSWORD_RULE_MESSAGE), state.fieldErrors)
        assertEquals(AccessRequestStep.VERIFY, state.step)
        assertEquals("654321", state.code)
        assertNull(state.verificationFeedback, "no es el aviso del codigo")
        assertEquals(0, state.failedVerifications)
        assertFalse(state.verifying)
    }

    @Test
    fun `the verify step copy asks for the code then the panel password and warns about links`() {
        assertEquals("Elige la contraseña con la que entrarás al panel.", ACCESS_REQUEST_CHOOSE_PASSWORD)
        assertEquals("No pulses ningún enlace del correo: usa solo el código.", ACCESS_REQUEST_NO_LINKS_NOTE)
        assertTrue(accessVerifyIntro("ada@example.com").contains("código"))
    }

    // ------------------------------------------------------------------------------------------------ reglas

    @Test
    fun `new password rule is 12 to 72 printable ascii`() {
        assertFalse(AccessRequestRules.isValidNewPassword("a".repeat(11)))
        assertTrue(AccessRequestRules.isValidNewPassword("a".repeat(12)))
        assertTrue(AccessRequestRules.isValidNewPassword("a".repeat(72)))
        assertFalse(AccessRequestRules.isValidNewPassword("a".repeat(73)))
        assertTrue(AccessRequestRules.isValidNewPassword(" ~!Aa0 espacio~"))
        assertFalse(AccessRequestRules.isValidNewPassword("contraseñalarga"))
        assertFalse(AccessRequestRules.isValidNewPassword("contrasenalarga😀"))
        assertFalse(AccessRequestRules.isValidNewPassword("contrasena\tlarga"))

        val withEnye = "contraseñalarga"
        assertEquals(
            PASSWORD_RULE_MESSAGE,
            validateAccessPassword(AccessRequestState(password = withEnye, passwordConfirmation = withEnye))[AccessRequestField.PASSWORD],
        )
        assertEquals("La contraseña debe tener entre 12 y 72 caracteres, sin tildes, eñes ni emojis.", PASSWORD_RULE_MESSAGE)
    }

    @Test
    fun `email canonicalization matches the server`() {
        assertEquals("ada@example.com", AccessRequestRules.canonicalEmail("  Ada@EXAMPLE.com "))
        assertEquals("ada@example.com", AccessRequestRules.canonicalEmail(" ada@example.com\t"))
        // Solo A-Z: la I con punto se queda tal cual (lowercase() la partiria en dos puntos de codigo).
        assertEquals("İstanbul@example.com", AccessRequestRules.canonicalEmail("İSTANBUL@EXAMPLE.COM"))
        assertEquals(1, AccessRequestRules.canonicalEmail("İ")!!.length)
        assertEquals("Émile@example.com", AccessRequestRules.canonicalEmail("ÉMILE@example.com"))
        assertNull(AccessRequestRules.canonicalEmail("a b@example.com"))
        assertNull(AccessRequestRules.canonicalEmail("ab@exa\u0007mple.com"))
        assertNull(AccessRequestRules.canonicalEmail("ab@exa mple.com"))
        assertEquals("", AccessRequestRules.canonicalEmail("   "))

        val valid = AccessRequestState(name = "Ada", password = "exacta-12-ch", passwordConfirmation = "exacta-12-ch", reason = "m")
        assertEquals(setOf(AccessRequestField.EMAIL), validateAccessRequest(valid.copy(email = "a b@example.com")).keys)
        assertEquals(emptySet(), validateAccessRequest(valid.copy(email = " İnfo@Example.com ")).keys)
    }
}
