package com.oposicontrol.admin.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.core.common.config.FeatureFlags
import com.oposicontrol.admin.core.common.logging.NoOpLogger
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.database.AdminDatabase
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.domain.model.ExamEconomyDraft
import com.oposicontrol.admin.domain.model.ForumAction
import com.oposicontrol.admin.domain.model.ForumContentType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** El repositorio de Control V1 visto desde la red: qué envía exactamente y cómo traduce lo que recibe. */
class KtorControlV1RepositoryImplTest {
    private fun appConfig() = AppConfig(
        environment = Environment.DEV,
        baseUrl = "http://localhost:8080",
        backendToken = "test-token",
        supabaseUrl = "https://example.supabase.co",
        supabaseAnonKey = "anon-key",
        supabaseBucketExamResources = "exam-resources",
        featureFlags = FeatureFlags(enableLogs = false, enableAnalytics = false),
        appName = "OposiControl Admin",
        appVersion = "test",
    )

    private fun session(): SessionLocalDataSource {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AdminDatabase.Schema.create(driver)
        return SessionLocalDataSource(AdminDatabase(driver))
    }

    private fun repo(
        requests: MutableList<HttpRequestData>,
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = "{}",
    ): KtorControlV1RepositoryImpl {
        val client = HttpClient(
            MockEngine { request ->
                requests += request
                respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        return KtorControlV1RepositoryImpl(client, appConfig(), session(), NetworkResultAdapter(NoOpLogger))
    }

    private fun HttpRequestData.text(): String = (body as TextContent).text

    @Test
    fun savingRulesSendsAStrictPutWithOnlyTheRuleKeys() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        repo(requests, body = """{"examenId":"e1","reglaAnuladas":"NO_PUNTUA","penalizacionRacional":true}""")
            .saveGradingRules("e1", "NO_PUNTUA", true, 0, 1, "  Por el tribunal ")
        val sent = requests.single()
        assertEquals("PUT", sent.method.value)
        assertTrue(sent.url.encodedPath.endsWith("/api/v1/admin/examenes/e1/reglas-puntuacion"))
        val text = sent.text()
        assertTrue(text.contains("\"reglaAnuladas\":\"NO_PUNTUA\"") && text.contains("\"penalizacionRacional\":true"), text)
        assertTrue(text.contains("\"penalizacionNum\":0") && text.contains("\"penalizacionDen\":1"), text)
        assertTrue(text.contains("\"motivo\":\"Por el tribunal\""), text)
        assertTrue(text.contains("\"actor") .not() && text.contains("usuarioId").not(), "el actor lo pone el servidor desde el JWT")
    }

    @Test
    fun moderationNeverSendsAnActor() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        repo(requests).moderateForum(ForumContentType.COMMENT, "c1", ForumAction.MUTE, " reincidente ", 30)
        val text = requests.single().text()
        assertTrue(text.contains("\"tipo\":\"comentario\"") && text.contains("\"accion\":\"silenciar\"") && text.contains("\"dias\":30"), text)
        assertTrue(text.contains("\"motivo\":\"reincidente\""), text)
        assertTrue(!text.contains("actor"), text)
    }

    @Test
    fun theEconomyPatchCarriesTheCommunityIdNeverACodeLikeESMD() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        repo(requests, body = """{"id":"e1"}""").saveExamEconomy("e1", ExamEconomyDraft(true, "1300", "30", "c-uuid"))
        val text = requests.single().text()
        assertTrue(text.contains("\"comunidadId\":\"c-uuid\"") && text.contains("\"precioTickets\":1300") && text.contains("\"diasAccesoCompra\":30"), text)
        assertEquals("PATCH", requests.single().method.value)
    }

    @Test
    fun aBearerTokenAccompaniesEveryRequest() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        repo(requests, body = """{"data":[]}""").forumChannels()
        assertEquals("Bearer test-token", requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun a501BecomesAReadableMessageNotAnOpaqueServerError() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val result = repo(requests, HttpStatusCode.NotImplemented, """{"error":"CONTROL_RPC_NOT_PROVISIONED","message":"x"}""").forumBlockList()
        val failure = assertIs<AppResult.Failure>(result)
        val error = assertIs<AppError.Validation>(failure.error)
        assertEquals(KtorControlV1RepositoryImpl.NOT_PROVISIONED_MESSAGE, error.message)
    }

    @Test
    fun a422KeepsTheServerMessageAndCode() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val body = """{"error":"GRADING_PENALTY_CONFLICT","message":"La penalizacion racional y la fraccion fija son excluyentes."}"""
        val result = repo(requests, HttpStatusCode.UnprocessableEntity, body).saveGradingRules("e1", null, true, 1, 3, null)
        val error = assertIs<AppError.Validation>(assertIs<AppResult.Failure>(result).error)
        assertTrue(error.message.contains("excluyentes"), error.message)
        assertEquals("GRADING_PENALTY_CONFLICT", error.code)
    }

    @Test
    fun a403IsAnAuthorizationErrorNotAGenericFailure() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val result = repo(requests, HttpStatusCode.Forbidden, """{"error":"FORBIDDEN","message":"no"}""").liftForumRestriction("u1")
        assertIs<AppError.Authorization>(assertIs<AppResult.Failure>(result).error)
    }
}
