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
import kotlin.test.Test
import kotlin.test.assertEquals

/** MASTER4-API-001: un usuario sin nombre puede guardar solo roles; la edicion de nombre sigue haciendo PATCH. */
class UsersApiRoleOnlyEditTest {
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

    private suspend fun run(user: UserDto): List<Pair<HttpMethod, String>> {
        val seen = mutableListOf<Pair<HttpMethod, String>>()
        val client = HttpClient(MockEngine { request ->
            seen += request.method to request.url.encodedPath
            request.body.toByteArray()
            val body = if (request.method == HttpMethod.Get) """{"id":"$id","roleIds":["r1"]}""" else "{}"
            respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(AdminApiJson) } }
        UsersApi(client, config(), session()).upsertUser(user)
        return seen
    }

    @Test fun namelessUserRoleOnlyEditSkipsThePatchAndStillPostsRoles() = runTest {
        val seen = run(UserDto(id = id, fullName = "", email = "", roleIds = listOf("r1"), isActive = null, createdAt = ""))
        assertEquals(listOf(HttpMethod.Post, HttpMethod.Get), seen.map { it.first })
        assertEquals("/api/v1/admin/usuarios/$id/roles", seen.first().second)
    }

    @Test fun whitespaceOnlyNameIsTreatedAsBlank() = runTest {
        val seen = run(UserDto(id = id, fullName = "   ", email = "", roleIds = listOf("r1"), isActive = null, createdAt = ""))
        assertEquals(listOf(HttpMethod.Post, HttpMethod.Get), seen.map { it.first })
    }

    @Test fun nameEditStillPatchesFirstThenPostsRoles() = runTest {
        val seen = run(UserDto(id = id, fullName = "Ana", email = "", roleIds = listOf("r1"), isActive = null, createdAt = ""))
        assertEquals(listOf(HttpMethod.Patch, HttpMethod.Post, HttpMethod.Get), seen.map { it.first })
    }
}
