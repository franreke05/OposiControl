package com.oposicontrol.admin.data.remote.api

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.core.common.config.FeatureFlags
import com.oposicontrol.admin.core.common.logging.NoOpLogger
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.AdminApiJson
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.remote.dto.ACCOUNT_NOT_ACTIVATED_MESSAGE
import com.oposicontrol.admin.data.remote.dto.SupabaseAuthErrorDto
import com.oposicontrol.admin.data.remote.dto.toFriendlyMessage
import com.oposicontrol.admin.data.remote.source.RealAuthDataSource
import com.oposicontrol.admin.domain.model.AuthCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Una cuenta creada por una solicitud de acceso nace SIN CONFIRMAR: Supabase no le da sesion hasta que un superadministrador
 * la aprueba. Lo que ve esa persona al intentar entrar tiene que ser verdad («no esta activada / falta la aprobacion»), no
 * «contraseña incorrecta» ni «confirma tu correo» (no se le envio ninguno).
 */
class AuthApiUnconfirmedAccountTest {
    private val config = AppConfig(
        environment = Environment.DEV,
        baseUrl = "http://localhost:8080",
        backendToken = "",
        supabaseUrl = "https://example.supabase.co",
        supabaseAnonKey = "anon-key",
        supabaseBucketExamResources = "exam-resources",
        featureFlags = FeatureFlags(enableLogs = false, enableAnalytics = false),
        appName = "OposiControl Admin",
        appVersion = "test",
    )

    private suspend fun loginMessage(body: String): String {
        val client = HttpClient(
            MockEngine { respond(body, HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json")) },
        ) { install(ContentNegotiation) { json(AdminApiJson) } }
        val result = RealAuthDataSource(AuthApi(client, config), NetworkResultAdapter(NoOpLogger))
            .login(AuthCredentials(email = "ada@example.com", password = "S3creta-Larga-No-Repetible-9731"))
        return assertIs<AppError.Authentication>(assertIs<AppResult.Failure>(result).error).message
    }

    @Test
    fun anUnconfirmedAccountIsToldItIsWaitingForApprovalInBothGoTrueErrorShapes() = runTest {
        // GoTrue actual: `error_code`.
        assertEquals(ACCOUNT_NOT_ACTIVATED_MESSAGE, loginMessage("""{"code":400,"error_code":"email_not_confirmed","msg":"Email not confirmed"}"""))
        // GoTrue antiguo: `invalid_grant` + descripcion. Antes caia en «Usuario o contraseña incorrectos».
        assertEquals(ACCOUNT_NOT_ACTIVATED_MESSAGE, loginMessage("""{"error":"invalid_grant","error_description":"Email not confirmed"}"""))
    }

    @Test
    fun theMessageNamesTheApprovalAndDoesNotAskToConfirmAnEmailNobodySent() {
        assertTrue("superadministrador" in ACCOUNT_NOT_ACTIVATED_MESSAGE)
        assertTrue("no está activada" in ACCOUNT_NOT_ACTIVATED_MESSAGE)
        assertFalse("confirma" in ACCOUNT_NOT_ACTIVATED_MESSAGE.lowercase())
        assertFalse("correo" in ACCOUNT_NOT_ACTIVATED_MESSAGE.lowercase())
    }

    @Test
    fun aWrongPasswordOrUnknownEmailStillGetsTheGenericAnswer() = runTest {
        // Supabase solo dice «sin confirmar» a quien acierta la contrasena: lo demas sigue siendo indistinguible.
        val generic = "Usuario o contraseña incorrectos."
        assertEquals(generic, loginMessage("""{"code":400,"error_code":"invalid_credentials","msg":"Invalid login credentials"}"""))
        assertEquals(generic, loginMessage("""{"error":"invalid_grant","error_description":"Invalid login credentials"}"""))
    }

    @Test
    fun aRefreshNeverShowsTheActivationMessage() {
        val dto = SupabaseAuthErrorDto(errorCode = "email_not_confirmed", msg = "Email not confirmed")
        assertEquals("Tu sesión ha caducado. Vuelve a iniciar sesión.", dto.toFriendlyMessage(isRefresh = true))
    }
}
