package com.oposicontrol.admin.data.di

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.core.common.config.FeatureFlags
import com.oposicontrol.admin.core.common.logging.Logger
import com.oposicontrol.admin.core.common.logging.NoOpLogger
import com.oposicontrol.admin.core.database.AdminDatabase
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.api.adminAuthHeaders
import com.oposicontrol.admin.data.repository.AuthRepositoryImpl
import com.oposicontrol.admin.data.repository.inMemoryDatabase
import com.oposicontrol.admin.data.repository.session
import com.oposicontrol.admin.domain.repository.AuthRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * MASTER6-AUTH-004: el cliente HTTP que reparte Koin lleva el refresco de sesion, y el ciclo
 * HttpClient -> (tarde) AuthRepositoryImpl -> AuthApi -> HttpClient se resuelve sin error.
 */
class AdminSessionRefreshWiringTest {
    private val config = AppConfig(
        environment = Environment.DEV,
        baseUrl = "http://127.0.0.1:1",
        backendToken = "",
        supabaseUrl = "http://127.0.0.1:1",
        supabaseAnonKey = "anon-key",
        supabaseBucketExamResources = "exam-resources",
        featureFlags = FeatureFlags(enableLogs = false, enableAnalytics = false),
        appName = "OposiControl Admin",
        appVersion = "test",
    )

    @Test
    fun theKoinHttpClientRunsTheSessionRefreshAgainstTheSingleRepository() = runTest {
        val koin = koinApplication {
            allowOverride(true)
            modules(
                dataModule,
                module {
                    single<AdminDatabase> { inMemoryDatabase() }
                    single { config }
                    single<Logger> { NoOpLogger }
                },
            )
        }.koin
        assertSame(koin.get<AuthRepositoryImpl>(), koin.get<AuthRepository>())

        val local = koin.get<SessionLocalDataSource>()
        // Sesion reabierta desde disco (sin refresh token) y ya caducada: el interceptor la descarta antes de enviar.
        local.save(session("expired-access", refresh = "", expiresAt = Clock.System.now() - 1.minutes))

        runCatching { koin.get<HttpClient>().get("${config.baseUrl}/api/v1/admin/dashboard") { adminAuthHeaders(config, local) } }

        assertNull(local.currentSession())
        koin.close()
    }
}
