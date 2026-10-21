package com.oposicontrol.admin.features.auth.presentation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.ui.theme.AdminTheme
import com.oposicontrol.admin.domain.model.AuthCredentials
import com.oposicontrol.admin.domain.model.Session
import com.oposicontrol.admin.domain.repository.AuthRepository
import com.oposicontrol.admin.domain.usecase.auth.LoginUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Inicio de sesion REAL ([AuthScreen] + [AuthViewModel] + [LoginUseCase]) contra un [AuthRepository] falso: lo que ve el
 * operador al entrar, al equivocarse y cuando el servidor le niega el panel. Se escribe en los campos de verdad
 * (`performTextInput`), no se despachan intents a mano.
 */
@OptIn(ExperimentalTestApi::class)
class AuthLoginUiTest {
    private val email = "ada@example.com"
    private val password = "S3creta-Larga-No-Repetible-9731"

    private object Unconfined : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private class FakeAuth(private val loginResult: AppResult<Session>) : AuthRepository {
        val logins = mutableListOf<AuthCredentials>()
        override fun observeSession(): Flow<Session?> = MutableStateFlow(null)
        override suspend fun login(credentials: AuthCredentials): AppResult<Session> {
            logins += credentials
            return loginResult
        }
        override suspend fun refreshSession(): AppResult<Session> = error("no interviene")
        override suspend fun logout(): AppResult<Unit> = AppResult.Success(Unit)
    }

    private val session = Session(
        userId = "u-1",
        displayName = "Ada",
        accessToken = "access",
        refreshToken = "refresh",
        expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
        roleIds = listOf("admin"),
    )

    private fun login(
        auth: FakeAuth,
        block: ComposeUiTest.(entered: () -> Int) -> Unit,
    ) = runDesktopComposeUiTest(width = 1366, height = 1100) {
        var authenticated = 0
        val viewModel = AuthViewModel(Unconfined, LoginUseCase(auth), auth, NoOpAnalyticsTracker)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                AdminTheme(darkTheme = false) { AuthScreen(viewModel = viewModel, onAuthenticated = { authenticated++ }) }
            }
        }
        waitForIdle()
        block { authenticated }
    }

    private fun ComposeUiTest.typeCredentials(user: String = email, pass: String = password) {
        val fields = onAllNodes(hasSetTextAction())
        fields[0].performTextInput(user)
        fields[1].performTextInput(pass)
        waitForIdle()
    }

    @Test
    fun goodCredentialsEnterThePanel() {
        val auth = FakeAuth(AppResult.Success(session))
        login(auth) { entered ->
            typeCredentials()
            onNodeWithText("Entrar").performClick()
            waitForIdle()
            assertEquals(1, entered())
        }
        assertEquals(listOf(AuthCredentials(email, password)), auth.logins)
    }

    @Test
    fun wrongCredentialsShowTheRepositoryMessageAndStayOnLogin() {
        val wrong = "Usuario o contraseña incorrectos."
        val auth = FakeAuth(AppResult.Failure(AppError.Authentication(wrong)))
        login(auth) { entered ->
            typeCredentials()
            onNodeWithText("Entrar").performClick()
            waitForIdle()
            onNodeWithText(wrong).assertExists()
            onNodeWithText("Entrar").assertExists()
            assertEquals(0, entered())
        }
        assertEquals(1, auth.logins.size)
    }

    /**
     * 403 del backend para quien no es administrador (o esta bloqueado): se ensena el texto del servidor y no entra.
     */
    @Test
    fun aNonAdminIsToldItHasNoPermissionAndDoesNotEnter() {
        val backend = "No tienes permisos para acceder al panel de administración."
        val auth = FakeAuth(AppResult.Failure(AppError.Authorization(backend)))
        login(auth) { entered ->
            typeCredentials()
            onNodeWithText("Entrar").performClick()
            waitForIdle()
            onNodeWithText(backend).assertExists()
            onNodeWithText("Tu usuario no tiene permiso para ver esto.").assertDoesNotExist()
            onNodeWithText("Tu sesión ha caducado. Vuelve a iniciar sesión.").assertDoesNotExist()
            onNodeWithText("Entrar").assertExists()
            assertEquals(0, entered())
        }
        assertEquals(1, auth.logins.size)
    }

    @Test
    fun emptyFieldsCannotSubmit() {
        val auth = FakeAuth(AppResult.Success(session))
        login(auth) { entered ->
            onNodeWithText("Entrar").assertIsNotEnabled()
            onAllNodes(hasSetTextAction())[0].performTextInput(email)
            waitForIdle()
            onNodeWithText("Entrar").assertIsNotEnabled()
            onNodeWithText("Entrar").performClick()
            waitForIdle()
            onAllNodes(hasSetTextAction())[1].performTextInput(password)
            waitForIdle()
            onNodeWithText("Entrar").assertIsEnabled()
            assertEquals(0, entered())
        }
        assertTrue(auth.logins.isEmpty())
    }
}
