package com.oposicontrol.backend

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CERT-LOAD-1000: drenaje al apagar (ShutdownDrain.kt). Sin salud pasiva en Caddy, una replica que se para tiene que salir de
 * rotacion por la sonda activa ANTES de cerrar el puerto.
 */
class ShutdownDrainTest {
    @AfterTest
    fun reset() = ShutdownDrain.reset()

    @Test
    fun liveIsUpUntilTheDrainStartsAndThen503Draining() = testApplication {
        val draining = AtomicBoolean(false)
        application {
            install(ContentNegotiation) { json() }
            routing { healthLiveRoute { draining.get() } }
        }
        val up = client.get("/health/live")
        assertEquals(HttpStatusCode.OK, up.status)
        assertEquals("UP", Json.parseToJsonElement(up.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)
        draining.set(true)
        val drain = client.get("/health/live")
        assertEquals(HttpStatusCode.ServiceUnavailable, drain.status)
        assertEquals("DRAINING", Json.parseToJsonElement(drain.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun theProductionRouteFollowsTheGlobalDrainFlag() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { healthLiveRoute() }
        }
        assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
        ShutdownDrain.begin()
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/health/live").status)
    }

    @Test
    fun drainThenStopFlipsTheProbeFirstKeepsServingForTheDrainAndOnlyThenStops() {
        val events = mutableListOf<String>()
        val plan = ShutdownPlan(drainMillis = 10_000, graceMillis = 5_000, timeoutMillis = 20_000)
        ShutdownDrain.drainThenStop(
            plan,
            sleep = { ms -> events += "sleep $ms draining=${ShutdownDrain.draining}" },
        ) { grace, timeout -> events += "stop $grace $timeout draining=${ShutdownDrain.draining}" }
        assertEquals(listOf("sleep 10000 draining=true", "stop 5000 20000 draining=true"), events)
    }

    @Test
    fun withoutDrainItStopsAtOnce() {
        val events = mutableListOf<String>()
        ShutdownDrain.drainThenStop(ShutdownPlan(0, 5_000, 25_000), sleep = { events += "sleep" }) { _, _ -> events += "stop" }
        assertEquals(listOf("stop"), events)
    }

    @Test
    fun defaultsPerProfileAndExplicitValues() {
        assertEquals(ShutdownPlan(10_000, 5_000, 20_000), ShutdownPlan.fromEnvironment(mapOf("SERVER_PROFILE" to "bot")::get))
        assertEquals(ShutdownPlan(0, 5_000, 25_000), ShutdownPlan.fromEnvironment(mapOf("SERVER_PROFILE" to "control")::get))
        assertEquals(ShutdownPlan(0, 5_000, 25_000), ShutdownPlan.fromEnvironment { null })
        assertEquals(
            ShutdownPlan(12_000, 3_000, 18_000),
            ShutdownPlan.fromEnvironment(
                mapOf("SERVER_PROFILE" to "bot", "SHUTDOWN_DRAIN_MS" to "12000", "SHUTDOWN_GRACE_PERIOD_MS" to "3000", "SHUTDOWN_TIMEOUT_MS" to "18000")::get,
            ),
        )
        assertEquals(0, ShutdownPlan.fromEnvironment(mapOf("SERVER_PROFILE" to "bot", "SHUTDOWN_DRAIN_MS" to "0")::get).drainMillis)
    }

    /**
     * Cruce con la configuracion versionada: el drenaje por defecto del bot supera intervalo + tope de la sonda activa del
     * pool bot en Caddy, y drenaje + tope de cierre cabe en `stop_grace_period` (si no, Docker mata la JVM con SIGKILL).
     */
    @Test
    fun theBotDefaultsFitTheCaddyProbeAndTheComposeStopGracePeriod() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "openapi/root.yaml").isFile }
            ?: fail("repo root not found")
        val plan = ShutdownPlan.fromEnvironment(mapOf("SERVER_PROFILE" to "bot")::get)
        for (caddy in listOf("ops/caddy/Caddyfile.production", "ops/caddy/Caddyfile.hostcaddy")) {
            val text = File(root, caddy).readText().replace("\r\n", "\n")
            val bot = text.substringAfter("handle @bot {").substringBefore("\n\t\t}")
            val interval = seconds(bot, "health_interval")
            val timeout = seconds(bot, "health_timeout")
            assertTrue(plan.drainMillis > (interval + timeout) * 1_000, "$caddy: drenaje ${plan.drainMillis} ms <= sonda ${interval}s + ${timeout}s")
        }
        val compose = File(root, "ops/deploy/compose.production.yaml").readText().replace("\r\n", "\n")
        val xktor = compose.substringAfter("\nx-ktor: &ktor").substringBefore("\nservices:")
        val stopGrace = Regex("""(?m)^  stop_grace_period:\s*(\d+)s""").find(xktor)?.groupValues?.get(1)?.toLong() ?: fail("stop_grace_period")
        assertTrue(plan.worstCaseMillis < stopGrace * 1_000, "drenaje + cierre ${plan.worstCaseMillis} ms >= stop_grace_period ${stopGrace}s")
    }

    private fun seconds(block: String, directive: String): Long =
        Regex("""(?m)^\s*$directive\s+(\d+)s\s*$""").find(block)?.groupValues?.get(1)?.toLong() ?: fail("$directive no encontrado")
}
