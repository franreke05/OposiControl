package com.oposicontrol.admin.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.core.common.config.FeatureFlags
import com.oposicontrol.admin.core.common.logging.NoOpLogger
import com.oposicontrol.admin.core.database.AdminDatabase
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.domain.model.CommerceStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KtorCommerceRepositoryImplTest {
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

    private fun sessionLocalDataSource(): SessionLocalDataSource {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AdminDatabase.Schema.create(driver)
        return SessionLocalDataSource(AdminDatabase(driver))
    }

    @Test
    fun refreshCommerceOnlyCallsTheKtorBackendNeverSupabaseDirectly() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { request ->
            requests += request
            respond(content = ByteReadChannel("{\"data\":[]}"), status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val config = appConfig()
        val repository = KtorCommerceRepositoryImpl(client, config, sessionLocalDataSource(), NetworkResultAdapter(NoOpLogger))

        repository.refreshCommerce()

        assertEquals(4, requests.size)
        requests.forEach { request ->
            assertTrue(
                request.url.toString().startsWith(config.baseUrl),
                "Store request must go through the Ktor backend (${config.baseUrl}), not Supabase directly: ${request.url}",
            )
            assertTrue(
                !request.url.host.contains("supabase", ignoreCase = true),
                "Store must never call Supabase directly from Desktop: ${request.url}",
            )
        }
        assertTrue(requests.any { it.url.encodedPath == "/api/v1/admin/tienda/articulos" })
        assertTrue(requests.any { it.url.encodedPath == "/api/v1/admin/tienda/ofertas" })
        assertTrue(requests.any { it.url.encodedPath == "/api/v1/admin/tienda/recursos-temporales" })
        assertTrue(requests.any { it.url.encodedPath == "/api/v1/admin/tienda/rafagas" })
    }

    @Test
    fun refreshCommerceSendsABearerTokenNeverASupabaseApiKey() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { request ->
            requests += request
            respond(content = ByteReadChannel("{\"data\":[]}"), status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val repository = KtorCommerceRepositoryImpl(client, appConfig(), sessionLocalDataSource(), NetworkResultAdapter(NoOpLogger))

        repository.refreshCommerce()

        requests.forEach { request ->
            assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])
            assertNull(request.headers["apikey"])
        }
    }

    @Test
    fun aFailingSubListFailsTheRefreshInsteadOfPublishingAnEmptyList() = runTest {
        // Antes ofertas/recursos/rafagas se tragaban su error y el refresco era un exito con listas vacias: Inicio
        // contaba 0 ofertas sin haber podido preguntarlo.
        val client = HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/api/v1/admin/tienda/ofertas") {
                respond(content = ByteReadChannel("boom"), status = HttpStatusCode.InternalServerError)
            } else {
                respond(
                    content = ByteReadChannel("{\"data\":[{\"id\":1,\"nombre\":\"Escudo\",\"tipo_articulo\":\"protector\",\"precio_tickets\":50}]}"),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val repository = KtorCommerceRepositoryImpl(client, appConfig(), sessionLocalDataSource(), NetworkResultAdapter(NoOpLogger))

        val result = repository.refreshCommerce()

        assertTrue(result is com.oposicontrol.admin.core.common.result.AppResult.Failure, "El fallo de ofertas tiene que llegar")
        // Nada a medias: ni siquiera los articulos que si llegaron se publican.
        assertTrue(repository.observeStoreItems().first().isEmpty())
    }

    @Test
    fun refreshCommerceToleratesBackendOmittedDefaultedFields() = runTest {
        // Mirrors a realistic backend response for /articulos: the server's
        // response Json does not set encodeDefaults, so a field whose value
        // equals its Kotlin default (activo = true) is omitted entirely --
        // no "activo" key at all, not even `"activo":true`. The client-side
        // row DTO must tolerate that the same way the server DTO defaults it.
        val client = HttpClient(MockEngine { request ->
            val body = if (request.url.encodedPath == "/api/v1/admin/tienda/articulos") {
                "{\"data\":[{\"id\":1,\"nombre\":\"Escudo\",\"tipo_articulo\":\"protector\",\"precio_tickets\":50}]}"
            } else {
                "{\"data\":[]}"
            }
            respond(content = ByteReadChannel(body), status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val repository = KtorCommerceRepositoryImpl(client, appConfig(), sessionLocalDataSource(), NetworkResultAdapter(NoOpLogger))

        repository.refreshCommerce()

        val item = repository.observeStoreItems().first().single()
        assertEquals("Escudo", item.name)
        assertEquals(50, item.priceTickets)
        assertEquals(CommerceStatus.ACTIVE, item.status)
    }
}
