package com.oposicontrol.admin.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.core.common.config.FeatureFlags
import com.oposicontrol.admin.core.common.logging.NoOpLogger
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.database.AdminDatabase
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.domain.model.ExamAccess
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RC-01 / RC-06 (cliente): el cuerpo REAL que salen los repositorios coincide con el contrato del backend. */
class ModerationWarnUserWireTest {
    private val appConfig = AppConfig(
        environment = Environment.DEV, baseUrl = "http://backend.test", backendToken = "t",
        supabaseUrl = "https://example.supabase.co", supabaseAnonKey = "anon-key",
        supabaseBucketExamResources = "b", featureFlags = FeatureFlags(enableLogs = false, enableAnalytics = false),
        appName = "x", appVersion = "test",
    )
    private val session = SessionLocalDataSource(AdminDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AdminDatabase.Schema.create(it) }))
    private val adapter = NetworkResultAdapter(NoOpLogger)

    private fun client(status: HttpStatusCode, response: String, onBody: (JsonObject) -> Unit) = HttpClient(
        MockEngine { request ->
            onBody(Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject)
            respond(response, status, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    @Test
    fun warnUserSendsTargetUserIdAndNotTheLegacyTargetUserKey() = runTest {
        var body: JsonObject? = null
        val repo = KtorModerationRepositoryImpl(client(HttpStatusCode.Created, "{}") { body = it }, appConfig, session, adapter)
        val uid = "00000000-0000-4000-8000-000000000009"
        assertTrue(repo.warnUser(uid, "motivo") is AppResult.Success)
        assertEquals(uid, body!!["targetUserId"]!!.jsonPrimitive.content)
        assertEquals("advertencia", body!!["actionType"]!!.jsonPrimitive.content)
        assertFalse("targetUser" in body!!, "el backend no conoce `targetUser`")
        assertFalse("executor" in body!!, "el actor sale del JWT, nunca del cliente")
    }

    @Test
    fun upsertExamAccessSendsUserIdAndExamIdWhenKnown() = runTest {
        var body: JsonObject? = null
        val repo = KtorAdminOperationsRepositoryImpl(
            client(HttpStatusCode.Created, """{"id":"a","userName":"u","examTitle":"e","accessType":"admin","isActive":true}""") { body = it },
            appConfig, session, adapter,
        )
        repo.upsertExamAccess(
            ExamAccess("a", "u", "e", "admin", true, userId = "00000000-0000-4000-8000-000000000001", examId = "00000000-0000-4000-8000-000000000002"),
        )
        assertEquals("00000000-0000-4000-8000-000000000001", body!!["userId"]!!.jsonPrimitive.content)
        assertEquals("00000000-0000-4000-8000-000000000002", body!!["examId"]!!.jsonPrimitive.content)
    }

    @Test
    fun anAmbiguousGrantConflictIsAFailureNotASuccess() = runTest {
        val repo = KtorAdminOperationsRepositoryImpl(
            client(HttpStatusCode.Conflict, """{"error":"AMBIGUOUS","message":"Varios usuarios coinciden."}""") { },
            appConfig, session, adapter,
        )
        assertTrue(repo.upsertExamAccess(ExamAccess("", "Ana", "e", "admin", true)) is AppResult.Failure)
    }
}
