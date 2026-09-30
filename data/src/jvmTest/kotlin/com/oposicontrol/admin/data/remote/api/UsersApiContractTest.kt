package com.oposicontrol.admin.data.remote.api

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.core.common.config.FeatureFlags
import com.oposicontrol.admin.core.database.AdminDatabase
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.dto.UserDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** MASTER-API-011 (paginacion de D1-22) y MASTER-API-016 (cuerpo del PATCH de D1-25). */
class UsersApiContractTest {
    private fun item(n: Int) = """{"id":"00000000-0000-4000-8000-${n.toString().padStart(12, '0')}","roleIds":[],"accountStatus":"activa","createdAt":"2026-01-01T00:00:00Z"}"""

    private fun page(from: Int, count: Int, total: Int?, limit: Int = USERS_PAGE_LIMIT, offset: Int = from) =
        AdminUsersV1Response(
            items = (from until from + count).map { Json.decodeFromString<AdminUserV1Dto>(item(it)) },
            total = total,
            limit = limit,
            offset = offset,
        )

    // ---------------------------------------------------------------- API-011

    @Test
    fun theWireEnvelopeKeepsTotalLimitAndOffset() {
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<AdminUsersV1Response>(
            """{"items":[${item(1)}],"total":451,"limit":200,"offset":0}""",
        )
        assertEquals(451, decoded.total)
        assertEquals(200, decoded.limit)
        assertEquals(0, decoded.offset)
    }

    @Test
    fun everyPageIsFetchedSoAListLargerThanTheServerDefaultIsNotTruncated() = runTest {
        val calls = mutableListOf<Pair<Int, Int>>()
        val users = collectAllUsers { limit, offset ->
            calls += limit to offset
            when (offset) {
                0 -> page(0, 200, total = 450)
                200 -> page(200, 200, total = 450)
                else -> page(400, 50, total = 450)
            }
        }
        assertEquals(450, users.size)
        assertEquals(listOf(200 to 0, 200 to 200, 200 to 400), calls)
    }

    @Test
    fun aNullTotalNeverMakesAFullPageLookComplete() = runTest {
        val calls = mutableListOf<Int>()
        val users = collectAllUsers { _, offset ->
            calls += offset
            if (offset == 0) page(0, 200, total = null) else page(200, 3, total = null)
        }
        assertEquals(203, users.size)
        assertEquals(listOf(0, 200), calls, "con total null se sigue pidiendo mientras la pagina venga llena")
    }

    @Test
    fun aServerThatClampsTheLimitStillGetsPagedByTheReturnedLimit() = runTest {
        val users = collectAllUsers { _, offset ->
            when (offset) {
                0 -> page(0, 50, total = 120, limit = 50)
                50 -> page(50, 50, total = 120, limit = 50)
                else -> page(100, 20, total = 120, limit = 50)
            }
        }
        assertEquals(120, users.size)
    }

    @Test
    fun anEmptyFirstPageEndsTheLoop() = runTest {
        var calls = 0
        val users = collectAllUsers { _, _ -> calls++; page(0, 0, total = 0) }
        assertEquals(emptyList(), users)
        assertEquals(1, calls)
    }

    // ---------------------------------------------------------------- API-016

    private fun config() = AppConfig(
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

    @Test
    fun thePatchBodyIsExactlyNombreMostradoAndRolesGoThroughPostRoles() = runTest {
        val id = "11111111-1111-4111-8111-111111111111"
        val seen = mutableListOf<Triple<HttpMethod, String, String>>()
        val client = HttpClient(MockEngine { request: HttpRequestData ->
            seen += Triple(request.method, request.url.encodedPath, request.body.toByteArray().decodeToString())
            val body = if (request.method == HttpMethod.Get) item(1).replace("00000000-0000-4000-8000-000000000001", id) else "{}"
            respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val api = UsersApi(client, config(), session())

        api.upsertUser(UserDto(id = id, fullName = "Ana Lopez", email = "a@x.es", roleIds = listOf("r1"), isActive = false, createdAt = ""))

        val patch = seen.single { it.first == HttpMethod.Patch }
        assertEquals("/api/v1/admin/usuarios/$id", patch.second)
        assertEquals(setOf("nombreMostrar"), Json.parseToJsonElement(patch.third).jsonObject.keys, "additionalProperties:false: solo nombreMostrar")
        assertEquals("Ana Lopez", Json.parseToJsonElement(patch.third).jsonObject["nombreMostrar"]!!.toString().trim('"'))
        val roles = seen.single { it.second.endsWith("/roles") }
        assertEquals(setOf("roleIds"), Json.parseToJsonElement(roles.third).jsonObject.keys)
    }

    @Test
    fun getUsersSendsLimitAndOffsetOnTheWire() = runTest {
        val queries = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            queries += request.url.encodedQuery
            respond(ByteReadChannel("""{"items":[],"total":0,"limit":200,"offset":0}"""), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        UsersApi(client, config(), session()).getUsers()
        assertEquals(listOf("limit=200&offset=0"), queries)
    }
}
