package com.oposicontrol.admin.data.remote.api

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.core.common.config.FeatureFlags
import com.oposicontrol.admin.core.database.AdminDatabase
import com.oposicontrol.admin.core.network.AdminApiJson
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.mapper.toDomain
import com.oposicontrol.admin.domain.model.AccountStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MASTER-OC-006: `UsersApi` y `UserDetailApi` ejercitados de extremo a extremo (HTTP -> ContentNegotiation -> DTO -> dominio)
 * con el `Json` de PRODUCCION ([AdminApiJson], el mismo que instala `ApiClientFactory`), no con un `Json` propio de la prueba.
 */
class UsersApiProductionJsonTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val id2 = "22222222-2222-4222-8222-222222222222"

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

    private fun client(status: HttpStatusCode = HttpStatusCode.OK, respondWith: (String) -> String): HttpClient =
        HttpClient(MockEngine { request ->
            respond(
                ByteReadChannel(respondWith(request.url.encodedPath)),
                status,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }) {
            install(ContentNegotiation) { json(AdminApiJson) }
        }

    @Test
    fun theProductionJsonIsTheOneApiClientFactoryInstalls() {
        assertTrue(AdminApiJson.configuration.ignoreUnknownKeys)
        assertTrue(AdminApiJson.configuration.isLenient)
        assertFalse(AdminApiJson.configuration.explicitNulls)
    }

    @Test
    fun getUsersDecodesTheCanonicalListWithNullsAndUnknownFields() = runTest {
        val body = """{"items":[
            {"id":"$id","username":null,"fullName":"Ana","email":null,"roleIds":["r1"],"accountStatus":"bloqueada","createdAt":"2026-01-01T00:00:00Z","ticketsBalance":null,"campoFuturo":{"x":1}},
            {"id":"$id2","roleIds":[],"accountStatus":"suspendida","createdAt":"2026-01-02T00:00:00Z"}
        ],"total":2,"limit":200,"offset":0}"""
        val users = UsersApi(client { body }, config(), session()).getUsers().map { it.toDomain() }
        assertEquals(listOf(id, id2), users.map { it.id })
        assertEquals(AccountStatus.BLOCKED, users[0].accountStatus)
        assertEquals(AccountStatus.UNKNOWN, users[1].accountStatus, "un estado no reconocido nunca se toma por activo")
        assertEquals("Ana", users[0].fullName)
    }

    @Test
    fun getUserDecodesTheCanonicalAdminUserAndKeepsTheLegacyRegisteredFlag() = runTest {
        val body = """{"id":"$id","username":"ana","email":"a@x.es","roleIds":["r"],"accountStatus":"eliminacion_pendiente",
            "onboardingCompleted":true,"createdAt":"2026-01-01T00:00:00Z","authProviders":["google"],
            "data":{"id":"$id","fullName":"Ana","email":"a@x.es","roleIds":["r"],"isActive":false,"createdAt":"2026-01-01T00:00:00Z"}}"""
        var seenPath = ""
        val user = UsersApi(client { path -> seenPath = path; body }, config(), session()).getUser(id).toDomain()
        assertEquals("/api/v1/admin/usuarios/$id", seenPath)
        assertEquals(AccountStatus.DELETION_PENDING, user.accountStatus)
        assertEquals(false, user.isRegistered, "isActive (legacy) = !es_invitado")
        assertEquals(listOf("google"), user.authProviders)
    }

    @Test
    fun getUserDetailDecodesTheDossierWithItsUnavailableSections() = runTest {
        val body = """{"data":{"perfil":{"usuario_id":"$id","id":"$id","accountStatus":"bloqueada","estado_cuenta":"activa",
            "authProviders":["password"],"roleIds":[],"onboardingCompleted":true,"createdAt":"2026-01-01T00:00:00Z","fullName":null},
            "detalle":null,"seccionesNoDisponibles":["rendimiento"]}}"""
        var seenPath = ""
        val detail = UserDetailApi(client { path -> seenPath = path; body }, config(), session()).getUserDetail(id)
        assertEquals("/api/v1/admin/usuarios/$id/detalle", seenPath)
        assertEquals(AccountStatus.BLOCKED, detail.profile.accountStatus, "accountStatus manda sobre estado_cuenta")
        assertNull(detail.extendedProfile)
        assertTrue(detail.isUnavailable("rendimiento"))
    }

    @Test
    fun aBackendErrorIsSurfacedNotDecodedAsAnEmptyList() = runTest {
        val api = UsersApi(client(HttpStatusCode.BadGateway) { """{"code":"UPSTREAM_CONTRACT_ERROR"}""" }, config(), session())
        val failure = assertFailsWith<IllegalStateException> { api.getUsers() }
        assertTrue("502" in failure.message.orEmpty(), failure.message)
    }
}
