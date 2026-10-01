package com.oposicontrol.admin.data.remote.api

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.core.common.config.FeatureFlags
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.repository.AuthRepositoryImpl
import com.oposicontrol.admin.data.repository.ScriptedAuthDataSource
import com.oposicontrol.admin.data.repository.T0
import com.oposicontrol.admin.data.repository.authFailure
import com.oposicontrol.admin.data.repository.session
import com.oposicontrol.admin.data.repository.sessionStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/** MASTER6-AUTH-004: el cliente admin renueva el access token antes de caducar y ante un 401, single-flight. */
class AdminSessionRefreshTest {
    private val config = AppConfig(
        environment = Environment.DEV,
        baseUrl = "http://localhost:8080",
        backendToken = "static-dev-token",
        supabaseUrl = "https://example.supabase.co",
        supabaseAnonKey = "anon-key",
        supabaseBucketExamResources = "exam-resources",
        featureFlags = FeatureFlags(enableLogs = false, enableAnalytics = false),
        appName = "OposiControl Admin",
        appVersion = "test",
    )

    private class Harness(
        val client: HttpClient,
        val local: SessionLocalDataSource,
        val remote: ScriptedAuthDataSource,
        val seenTokens: MutableList<String?>,
    )

    private fun harness(
        initial: com.oposicontrol.admin.domain.model.Session?,
        validToken: String = "new-access",
        on401: () -> Unit = {},
    ): Harness {
        val local = sessionStore(initial)
        val remote = ScriptedAuthDataSource()
        val repo = AuthRepositoryImpl(remote, local, now = { T0 })
        val seen = Collections.synchronizedList(mutableListOf<String?>())
        val client = HttpClient(
            MockEngine { request ->
                val auth = request.headers[HttpHeaders.Authorization]
                seen += auth
                if (auth == "Bearer $validToken") {
                    respond("{}", HttpStatusCode.OK)
                } else {
                    on401()
                    respond("{}", HttpStatusCode.Unauthorized)
                }
            },
        )
        client.installAdminSessionRefresh(local, repo::isExpiring) { repo }
        return Harness(client, local, remote, seen)
    }

    private suspend fun Harness.call(path: String = "/api/v1/admin/dashboard") =
        client.get("http://localhost:8080$path") { adminAuthHeaders(config, local) }.status

    @Test
    fun a401RefreshesOnceAndRetriesWithTheNewToken() = runTest {
        val h = harness(session("old-access"))
        h.remote.refreshResults += AppResult.Success(session("new-access"))

        assertEquals(HttpStatusCode.OK, h.call())
        assertEquals(listOf<String?>("Bearer old-access", "Bearer new-access"), h.seenTokens)
        assertEquals(listOf("refresh:r-old-access"), h.remote.calls)
        assertEquals("new-access", h.local.currentSession()?.accessToken)
    }

    @Test
    fun concurrent401sShareASingleRefresh() = runTest {
        val parallel = 5
        val rejected = AtomicInteger()
        val allRejected = CompletableDeferred<Unit>()
        val h = harness(session("old-access")) { if (rejected.incrementAndGet() == parallel) allRejected.complete(Unit) }
        h.remote.refreshResults += AppResult.Success(session("new-access"))
        h.remote.refreshGate = allRejected

        val statuses = (1..parallel).map { async { h.call() } }.awaitAll()

        assertEquals(List(parallel) { HttpStatusCode.OK }, statuses)
        assertEquals(1, h.remote.calls.count { it.startsWith("refresh:") }, "Supabase rota el refresh token: una sola vez")
    }

    @Test
    fun anExpiringTokenIsRefreshedBeforeTheRequestIsSent() = runTest {
        val h = harness(session("old-access", expiresAt = T0 + 30.seconds))
        h.remote.refreshResults += AppResult.Success(session("new-access"))

        assertEquals(HttpStatusCode.OK, h.call())
        assertEquals(listOf<String?>("Bearer new-access"), h.seenTokens)
    }

    @Test
    fun aDefinitiveRefreshRejectionSignsOutAndReturnsTheOriginal401() = runTest {
        val h = harness(session("old-access"))
        h.remote.refreshResults += authFailure

        assertEquals(HttpStatusCode.Unauthorized, h.call())
        assertEquals(listOf<String?>("Bearer old-access"), h.seenTokens, "sin token nuevo no se reintenta")
        assertNull(h.local.currentSession(), "la UI pasa a sin sesion")
    }

    @Test
    fun requestsWithoutTheSessionBearerAreNotTouched() = runTest {
        val h = harness(initial = null)

        assertEquals(HttpStatusCode.Unauthorized, h.call())
        assertEquals(listOf<String?>("Bearer static-dev-token"), h.seenTokens)
        assertEquals(emptyList<String>(), h.remote.calls)
    }

    @Test
    fun supabaseAuthEndpointsAreNeverIntercepted() = runTest {
        val h = harness(session("old-access"))

        assertEquals(HttpStatusCode.Unauthorized, h.call("/auth/v1/logout"))
        assertEquals(emptyList<String>(), h.remote.calls)
    }
}
