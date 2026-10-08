package com.oposicontrol.backend.inventory

import com.oposicontrol.backend.ServerProfile
import com.oposicontrol.backend.openapi.OpenApiDocs
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Puerta ruta -> perfil. `ops/inventory/route-profiles.tsv` es la matriz versionada (clasificacion EXPLICITA por ruta, nunca
 * inferida del prefijo de la URL). Se cruza con el arbol REAL de Ktor de cada perfil (`configureServerProfile`, el mismo
 * cableado que `module()`):
 *  (a) toda ruta de `combined` esta en la matriz exactamente una vez y no hay filas obsoletas (UNCLASSIFIED_ROUTES = 0);
 *  (b) lo montado por `bot` / `control` == filas cuya clase pertenece al conjunto del perfil;
 *  (c) bot U control == combined menos INTERNAL (rutas dev-only solo de `combined`), y ninguna ruta CONTROL/INTERNAL llega a `bot`.
 */
class RouteProfileGateTest {

    private data class MatrixRow(val method: String, val path: String, val profile: String, val auth: String, val purpose: String, val client: String) {
        val key get() = "$method $path"
    }

    private val inventoryDir = File(OpenApiDocs.locate().dir.parentFile, "ops/inventory")
    private val classes = setOf("BOT", "CONTROL", "SHARED", "AUTH", "INTERNAL", "HEALTH", "METRICS")
    private val authValues = setOf("none", "user-jwt", "admin", "static-token-optional")
    private val clients = setOf("OposiBot", "OposiControl", "none")
    private val botClasses = setOf("BOT", "SHARED", "AUTH", "HEALTH", "METRICS")
    private val controlClasses = setOf("CONTROL", "SHARED", "HEALTH", "METRICS")

    private val combined by lazy { CombinedRouteFixture.walkCombined().map { it.key }.toSortedSet() }
    private val bot by lazy { CombinedRouteFixture.walkProfile(ServerProfile.BOT).map { it.key }.toSortedSet() }
    private val control by lazy { CombinedRouteFixture.walkProfile(ServerProfile.CONTROL).map { it.key }.toSortedSet() }
    private val combinedViaProfile by lazy { CombinedRouteFixture.walkProfile(ServerProfile.COMBINED).map { it.key }.toSortedSet() }

    private fun parse(file: File): List<MatrixRow> =
        file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { l ->
            val c = l.split('\t')
            require(c.size == 6) { "route-profiles.tsv: 6 tab-separated columns expected: $l" }
            MatrixRow(c[0].trim(), c[1].trim(), c[2].trim(), c[3].trim(), c[4].trim(), c[5].trim())
        }

    private val matrix by lazy { parse(File(inventoryDir, "route-profiles.tsv")) }

    private fun matrixViolations(routes: Set<String>, rows: List<MatrixRow>): List<String> {
        val out = mutableListOf<String>()
        val byKey = rows.groupBy { it.key }
        byKey.filterValues { it.size > 1 }.keys.forEach { out += "duplicate matrix row: $it" }
        rows.forEach { r ->
            if (r.key !in routes) out += "stale matrix row (no longer routed): ${r.key}"
            if (r.profile !in classes) out += "unknown PROFILE '${r.profile}': ${r.key}"
            if (r.auth !in authValues) out += "unknown AUTH '${r.auth}': ${r.key}"
            if (r.client !in clients) out += "unknown CLIENT_CONSUMER '${r.client}': ${r.key}"
            if (r.purpose.isBlank()) out += "empty PURPOSE: ${r.key}"
        }
        (routes - byKey.keys).sorted().forEach { out += "UNCLASSIFIED_ROUTE (not in route-profiles.tsv): $it" }
        return out
    }

    private fun mountedViolations(name: String, mounted: Set<String>, rows: List<MatrixRow>, allowed: Set<String>): List<String> {
        val expected = rows.filter { it.profile in allowed }.map { it.key }.toSet()
        return (mounted - expected).map { "$name mounts a route whose class is not allowed there: $it" } +
            (expected - mounted).map { "$name does not mount a route its classes require: $it" }
    }

    @Test
    fun everyCombinedRouteIsClassifiedExactlyOnceAndThereAreNoStaleRows() {
        val v = matrixViolations(combined, matrix)
        assertTrue(v.isEmpty(), "UNCLASSIFIED_ROUTES=${v.count { it.startsWith("UNCLASSIFIED") }}\n" + v.joinToString("\n"))
    }

    @Test
    fun theCombinedProfileMountsExactlyTheHistoricalRouteSet() {
        assertEquals(combined, combinedViaProfile)
    }

    @Test
    fun botAndControlMountExactlyTheRoutesOfTheirClasses() {
        val v = mountedViolations("bot", bot, matrix, botClasses) + mountedViolations("control", control, matrix, controlClasses)
        assertTrue(v.isEmpty(), v.joinToString("\n"))
    }

    @Test
    fun botUnionControlLosesNothingAndNoControlOnlyRouteIsReachableInBot() {
        val internal = matrix.filter { it.profile == "INTERNAL" }.map { it.key }.toSet()
        assertEquals(combined - internal, bot + control, "bot U control must equal combined minus the dev-only INTERNAL routes")
        assertTrue(internal.none { it in bot || it in control }, "INTERNAL routes are mounted only by combined")
        val controlOnly = matrix.filter { it.profile == "CONTROL" || it.profile == "INTERNAL" }.map { it.key }
        assertTrue(controlOnly.none { it in bot }, "CONTROL routes reachable in bot: ${controlOnly.filter { it in bot }}")
        val botOnly = matrix.filter { it.profile == "BOT" || it.profile == "AUTH" }.map { it.key }
        assertTrue(botOnly.none { it in control }, "BOT/AUTH routes reachable in control: ${botOnly.filter { it in control }}")
        // Ninguna ruta administrativa, ni siquiera fuera de /admin, existe en bot.
        val adminShaped = bot.filter { k ->
            val p = k.substringAfter(' ')
            p.startsWith("/admin") || p.startsWith("/api/v1/admin") || p.startsWith("/content") ||
                p == "/tags" || p.startsWith("/search") || p == "/dashboard/metrics"
        }
        assertTrue(adminShaped.isEmpty(), "admin-shaped routes in bot: $adminShaped")
    }

    @Test
    fun theConsumerProfileStaysAStrictSubsetOfBot() {
        val consumer = CombinedRouteFixture.walkConsumer().map { it.key }.toSet()
        assertTrue(bot.containsAll(consumer), "consumer routes missing from bot: ${consumer - bot}")
        assertTrue(consumer.size < bot.size)
    }

    @Test
    fun everyProfileServesLiveAndReadyHealthAndMetrics() {
        listOf("GET /health/live", "GET /health/ready", "GET /metrics", "GET /health").forEach {
            assertTrue(it in bot, "bot: $it")
            assertTrue(it in control, "control: $it")
            assertTrue(it in combined, "combined: $it")
        }
    }

    // ------------------------------------------------------------------ controles negativos

    @Test
    fun negativeControlAnUnclassifiedOrStaleOrDuplicatedRowIsReported() {
        val routes = setOf("GET /a", "GET /b")
        fun row(p: String, cls: String = "CONTROL") = MatrixRow("GET", p, cls, "admin", "x", "none")
        assertTrue(matrixViolations(routes, listOf(row("/a"))).any { "UNCLASSIFIED_ROUTE" in it && "GET /b" in it })
        assertTrue(matrixViolations(routes, listOf(row("/a"), row("/b"), row("/gone"))).any { "stale matrix row" in it })
        assertTrue(matrixViolations(routes, listOf(row("/a"), row("/a"), row("/b"))).any { "duplicate matrix row" in it })
        assertTrue(matrixViolations(routes, listOf(row("/a"), row("/b", cls = "WHATEVER"))).any { "unknown PROFILE" in it })
        assertEquals(emptyList(), matrixViolations(routes, listOf(row("/a"), row("/b"))))
    }

    @Test
    fun negativeControlAControlRouteLeakedIntoBotIsReported() {
        val rows = listOf(MatrixRow("GET", "/x", "CONTROL", "admin", "x", "none"), MatrixRow("GET", "/y", "BOT", "user-jwt", "x", "none"))
        assertTrue(mountedViolations("bot", setOf("GET /x", "GET /y"), rows, botClasses).any { "GET /x" in it })
        assertTrue(mountedViolations("bot", setOf("GET /x"), rows, botClasses).any { "GET /y" in it })
    }

    @Test
    fun anUnknownServerProfileFailsStartupLoudlyAndBlankMeansCombined() {
        assertEquals(ServerProfile.COMBINED, ServerProfile.parse(null))
        assertEquals(ServerProfile.COMBINED, ServerProfile.parse("  "))
        assertEquals(ServerProfile.BOT, ServerProfile.parse("BOT"))
        assertEquals(ServerProfile.CONTROL, ServerProfile.parse("control"))
        assertEquals(ServerProfile.CONSUMER, ServerProfile.parse("consumer"))
        val e = assertFailsWith<IllegalStateException> { ServerProfile.parse("prod") }
        assertTrue("prod" in e.message!! && "bot" in e.message!!)
    }
}
