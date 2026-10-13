package com.oposicontrol.admin.features.auth.presentation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.ui.theme.AdminTheme
import com.oposicontrol.admin.domain.model.AccessRequest
import com.oposicontrol.admin.domain.model.AccessRequestStatus
import com.oposicontrol.admin.domain.model.AccessRequestSubmission
import com.oposicontrol.admin.domain.model.AuthCredentials
import com.oposicontrol.admin.domain.model.Session
import com.oposicontrol.admin.domain.repository.AccessRequestsRepository
import com.oposicontrol.admin.domain.repository.AuthRepository
import com.oposicontrol.admin.domain.usecase.auth.LoginUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La pantalla de acceso REAL ([AuthScreen]) con el enlace «Solicitar acceso»: el enlace abre el formulario, el formulario
 * valida antes de enviar, un envio aceptado ensena el aviso de «pendiente de aprobacion» y se puede volver al inicio de
 * sesion. Repositorios falsos: ningun byte sale de la maquina. Deja capturas en `build/visual-harness/`.
 */
@OptIn(ExperimentalTestApi::class)
class AccessRequestFormUiTest {
    private val password = "S3creta-Larga-No-Repetible-9731"

    private object Unconfined : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private class NoSession(private val loginResult: AppResult<Session>? = null) : AuthRepository {
        override fun observeSession(): Flow<Session?> = MutableStateFlow(null)
        override suspend fun login(credentials: AuthCredentials): AppResult<Session> = loginResult ?: error("no interviene")
        override suspend fun refreshSession(): AppResult<Session> = error("no interviene")
        override suspend fun logout(): AppResult<Unit> = AppResult.Success(Unit)
    }

    private class Requests : AccessRequestsRepository {
        val submissions = mutableListOf<AccessRequestSubmission>()
        override suspend fun submitRequest(submission: AccessRequestSubmission): AppResult<Unit> {
            submissions += submission
            return AppResult.Success(Unit)
        }

        override suspend fun listRequests(status: AccessRequestStatus?): AppResult<List<AccessRequest>> = error("no interviene")
        override suspend fun approve(requestId: String): AppResult<AccessRequest> = error("no interviene")
        override suspend fun reject(requestId: String, reason: String?): AppResult<AccessRequest> = error("no interviene")
    }

    private fun ComposeUiTest.capture(name: String) {
        val dir = File("build/visual-harness").also { it.mkdirs() }
        val file = File(dir, name)
        ImageIO.write(onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", file)
        assertTrue(file.exists() && file.length() > 0L)
    }

    private fun screen(
        requests: Requests,
        withRequestForm: Boolean = true,
        block: ComposeUiTest.(AccessRequestViewModel?) -> Unit,
    ) = runDesktopComposeUiTest(width = 1366, height = 1100) {
        val auth = NoSession()
        val authViewModel = AuthViewModel(Unconfined, LoginUseCase(auth), auth, NoOpAnalyticsTracker)
        val accessRequestViewModel = if (withRequestForm) AccessRequestViewModel(Unconfined, requests) else null
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                AdminTheme(darkTheme = false) {
                    AuthScreen(viewModel = authViewModel, onAuthenticated = {}, accessRequestViewModel = accessRequestViewModel)
                }
            }
        }
        waitForIdle()
        block(accessRequestViewModel)
    }

    @Test
    fun theLoginScreenOffersTheLinkAndItOpensTheFormAndBack() {
        screen(Requests()) {
            onNodeWithText("Entrar").assertExists()
            onNodeWithTag(ACCESS_REQUEST_FORM_TAG).assertDoesNotExist()
            capture("auth-login-with-request-link.png")

            onNodeWithTag(AUTH_REQUEST_ACCESS_LINK_TAG).performClick()
            waitForIdle()

            onNodeWithTag(ACCESS_REQUEST_FORM_TAG).assertExists()
            onNodeWithText("Entrar").assertDoesNotExist()
            onNodeWithText("Solicitar acceso").assertExists()
            onNodeWithText("Enviar solicitud").assertExists()
            capture("auth-access-request-form.png")

            onNodeWithText("Volver al inicio de sesión").performScrollTo().performClick()
            waitForIdle()
            onNodeWithText("Entrar").assertExists()
            onNodeWithTag(ACCESS_REQUEST_FORM_TAG).assertDoesNotExist()
        }
    }

    /** Quien pidio acceso y aun no esta aprobado: su cuenta no esta activada y la pantalla lo dice (no «sesión caducada»). */
    @Test
    fun signingInWithARequestStillPendingSaysItIsWaitingForApproval() {
        val pendingApproval =
            "Esta cuenta todavía no está activada. Si has solicitado acceso, un superadministrador tiene que aprobarlo antes de que puedas entrar."
        runDesktopComposeUiTest(width = 1366, height = 1100) {
            val auth = NoSession(loginResult = AppResult.Failure(AppError.Authentication(pendingApproval)))
            val authViewModel = AuthViewModel(Unconfined, LoginUseCase(auth), auth, NoOpAnalyticsTracker)
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                    AdminTheme(darkTheme = false) { AuthScreen(viewModel = authViewModel, onAuthenticated = {}) }
                }
            }
            authViewModel.dispatch(AuthIntent.UpdateEmail("ada@example.com"))
            authViewModel.dispatch(AuthIntent.UpdatePassword(password))
            waitForIdle()
            onNodeWithText("Entrar").performClick()
            waitForIdle()

            onNodeWithText(pendingApproval).assertExists()
            onNodeWithText("Tu sesión ha caducado. Vuelve a iniciar sesión.").assertDoesNotExist()
            capture("auth-login-pending-approval.png")
        }
    }

    @Test
    fun withoutTheViewModelThereIsNoLink() {
        screen(Requests(), withRequestForm = false) {
            onNodeWithText("Entrar").assertExists()
            onNodeWithTag(AUTH_REQUEST_ACCESS_LINK_TAG).assertDoesNotExist()
        }
    }

    @Test
    fun sendingAnEmptyFormShowsTheErrorsAndSendsNothing() {
        val requests = Requests()
        screen(requests) {
            onNodeWithTag(AUTH_REQUEST_ACCESS_LINK_TAG).performClick()
            waitForIdle()
            onNodeWithText("Enviar solicitud").performScrollTo().performClick()
            waitForIdle()

            onNodeWithText("Escribe tu nombre.").assertExists()
            onNodeWithText("Escribe tu correo.").assertExists()
            onNodeWithText("Elige una contraseña.").assertExists()
            onNodeWithText("Cuenta para qué necesitas el acceso.").assertExists()
            capture("auth-access-request-errors.png")
        }
        assertTrue(requests.submissions.isEmpty())
    }

    @Test
    fun anAcceptedRequestShowsThePendingApprovalNoticeInsteadOfTheForm() {
        val requests = Requests()
        screen(requests) { viewModel ->
            onNodeWithTag(AUTH_REQUEST_ACCESS_LINK_TAG).performClick()
            waitForIdle()
            viewModel!!.dispatch(AccessRequestIntent.UpdateName("Ada Lovelace"))
            viewModel.dispatch(AccessRequestIntent.UpdateEmail("ada@example.com"))
            viewModel.dispatch(AccessRequestIntent.UpdatePassword(password))
            viewModel.dispatch(AccessRequestIntent.UpdatePasswordConfirmation(password))
            viewModel.dispatch(AccessRequestIntent.UpdateReason("Coordino el equipo de contenido."))
            waitForIdle()
            capture("auth-access-request-filled.png")

            onNodeWithText("Enviar solicitud").performScrollTo().performClick()
            waitForIdle()

            onNodeWithTag(ACCESS_REQUEST_SENT_TAG).assertExists()
            onNodeWithText(ACCESS_REQUEST_SENT_TITLE).assertExists()
            onNodeWithText(ACCESS_REQUEST_SENT_DETAIL).assertExists()
            onNodeWithTag(ACCESS_REQUEST_FORM_TAG).assertDoesNotExist()
            capture("auth-access-request-sent.png")

            onNodeWithText("Volver al inicio de sesión").performClick()
            waitForIdle()
            onNodeWithText("Entrar").assertExists()
        }
        assertEquals("ada@example.com", requests.submissions.single().email)
    }
}
