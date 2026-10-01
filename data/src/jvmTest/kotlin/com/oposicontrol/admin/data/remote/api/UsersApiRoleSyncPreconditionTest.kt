package com.oposicontrol.admin.data.remote.api

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.core.common.config.FeatureFlags
import com.oposicontrol.admin.core.database.AdminDatabase
import com.oposicontrol.admin.core.network.AdminApiJson
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.dto.UserDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** MASTER6-IDEM-003 (cliente): el POST /roles lleva `expectedRoleIds` = los roles que el editor vio (FA8). */
class UsersApiRoleSyncPreconditionTest {
    private val id = "11111111-1111-4111-8111-111111111111"

    private fun session(): SessionLocalDataSource {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AdminDatabase.Schema.create(driver)
        return SessionLocalDataSource(AdminDatabase(driver))
    }

    private fun config() = AppConfig(
        environment = Environment.DEV, baseUrl = "http://localhost:8080", backendToken = "t",
        supabaseUrl = "https://example.supabase.co", supabaseAnonKey = "k", supabaseBucketExamResources = "b",
        featureFlags = FeatureFlags(enableLogs = false, enableAnalytics = false), appName = "x", appVersion = "t",
    )

    private fun user(roles: List<String>) = UserDto(id = id, fullName = "Ana", email = "", roleIds = roles, isActive = null, createdAt = "")

    private suspend fun run(
        next: UserDto,
        previous: UserDto?,
        rolesStatus: HttpStatusCode = HttpStatusCode.OK,
        rolesBody: String = "{}",
    ): JsonObject? {
        var rolesRequest: JsonObject? = null
        val client = HttpClient(MockEngine { request ->
            val raw = request.body.toByteArray().decodeToString()
            val isRoles = request.method == HttpMethod.Post
            if (isRoles) rolesRequest = Json.parseToJsonElement(raw).jsonObject
            val (status, body) = when {
                isRoles -> rolesStatus to rolesBody
                request.method == HttpMethod.Get -> HttpStatusCode.OK to """{"id":"$id","roleIds":["r1"]}"""
                else -> HttpStatusCode.OK to "{}"
            }
            respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(AdminApiJson) } }
        UsersApi(client, config(), session()).upsertUser(next, previous)
        return rolesRequest
    }

    @Test fun rolesPostCarriesTheRolesTheEditorSaw() = runTest {
        val body = run(next = user(listOf("r1", "r2")), previous = user(listOf("r1")))!!
        assertEquals(JsonArray(listOf(JsonPrimitive("r1"), JsonPrimitive("r2"))), body["roleIds"])
        assertEquals(JsonArray(listOf(JsonPrimitive("r1"))), body["expectedRoleIds"])
    }

    @Test fun anEditorThatSawNoRolesSendsAnEmptyExpectedSetNotNoPrecondition() = runTest {
        val body = run(next = user(listOf("r1")), previous = user(emptyList()))!!
        assertEquals(JsonArray(emptyList()), body["expectedRoleIds"])
    }

    @Test fun unknownPreviousRowSendsNoPrecondition() = runTest {
        val body = run(next = user(listOf("r1")), previous = null)!!
        assertFalse("expectedRoleIds" in body, "sin fila previa no hay nada que exigir")
    }

    @Test fun aVersionConflictSurfacesAsAFailureCarryingTheBackendStatus() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            run(
                next = user(listOf("r2")), previous = user(listOf("r1")), rolesStatus = HttpStatusCode.Conflict,
                rolesBody = """{"error":"VERSION_CONFLICT","message":"Los roles del usuario cambiaron."}""",
            )
        }
        assertTrue(e.message!!.startsWith("backend 409"))
    }
}
