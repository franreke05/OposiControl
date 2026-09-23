package com.oposicontrol.admin.core.network

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.core.common.config.FeatureFlags
import com.oposicontrol.admin.core.common.logging.NoOpLogger
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpSendPipeline
import io.ktor.client.request.get
import io.ktor.client.request.post
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * F-04 · El presupuesto por defecto del cliente REAL de la app.
 *
 * Con 6 s para todo, el cliente declaraba fallo mientras Ktor seguía trabajando
 * (el servidor admite hasta 20 s por cada llamada a Supabase) y el reintento del
 * administrador duplicaba la escritura. Se comprueba sobre el cliente que construye
 * [ApiClientFactory], no sobre una copia: la petición se detiene justo antes de
 * salir, cuando `HttpTimeout` ya ha fijado el presupuesto efectivo, y nunca llega a
 * la red.
 */
class ApiClientFactoryTimeoutTest {

    @Test
    fun aRequestWithoutItsOwnBudgetGetsThirtySecondsAndAShortConnect() = runTest {
        val budget = assertNotNull(effectiveBudget {})

        assertEquals(30_000L, budget.requestTimeoutMillis)
        assertEquals(30_000L, budget.socketTimeoutMillis)
        assertEquals(4_000L, budget.connectTimeoutMillis, "si no conecta en 4 s, no va a conectar")
    }

    /** Lo que hacen las operaciones largas: amplían lo suyo y el connect sigue corto. */
    @Test
    fun aRequestThatWidensItsBudgetKeepsTheShortConnect() = runTest {
        val budget = assertNotNull(
            effectiveBudget {
                timeout {
                    requestTimeoutMillis = 300_000
                    socketTimeoutMillis = 300_000
                }
            },
        )

        assertEquals(300_000L, budget.requestTimeoutMillis)
        assertEquals(300_000L, budget.socketTimeoutMillis)
        assertEquals(4_000L, budget.connectTimeoutMillis)
    }

    /**
     * Ampliar el presupuesto sólo es seguro porque el cliente NO reintenta por su
     * cuenta: un POST repetido tras un corte es exactamente el lote o la pregunta
     * duplicados que F-04 quiere evitar. Una petición que falla sale una sola vez, y
     * repetirla lo decide quien la hizo.
     */
    @Test
    fun aFailedPostLeavesTheClientExactlyOnce() = runTest {
        val client = ApiClientFactory(APP_CONFIG, NoOpLogger).create()
        var attempts = 0
        client.sendPipeline.intercept(HttpSendPipeline.Before) {
            attempts++
            error("La petición de la prueba no sale del cliente.")
        }
        try {
            runCatching { client.post("http://localhost:1/api/v1/admin/importaciones/L-1/importar") }
        } finally {
            client.close()
        }

        assertEquals(1, attempts, "el cliente reintentó un POST por su cuenta")
    }

    // -----------------------------------------------------------------

    private suspend fun effectiveBudget(configure: HttpRequestBuilder.() -> Unit): HttpTimeoutConfig? {
        val client = ApiClientFactory(APP_CONFIG, NoOpLogger).create()
        var captured: HttpTimeoutConfig? = null
        client.sendPipeline.intercept(HttpSendPipeline.Before) {
            captured = context.getCapabilityOrNull(HttpTimeoutCapability)
            error("La petición de la prueba no sale del cliente.")
        }
        try {
            runCatching { client.get("http://localhost:1/sonda", configure) }
        } finally {
            client.close()
        }
        return captured
    }

    private companion object {
        val APP_CONFIG = AppConfig(
            environment = Environment.DEV,
            baseUrl = "http://localhost:1",
            backendToken = "",
            supabaseUrl = "https://example.supabase.co",
            supabaseAnonKey = "anon-key",
            supabaseBucketExamResources = "exam-resources",
            featureFlags = FeatureFlags(enableLogs = false, enableAnalytics = false),
            appName = "OposiControl Admin",
            appVersion = "test",
        )
    }
}
