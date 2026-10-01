package com.oposicontrol.backend.inventory

import com.oposicontrol.backend.openapi.OpenApiDocs
import io.ktor.server.routing.get
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * INV-NETWORK-COMPLETE / OPENAPI-GAPS: inventario DETERMINISTA de rutas.
 *
 * Recorre el arbol REAL de rutas ([installCombinedRoutes] + [configureConsumerAuthOnly]; sin copia a mano ni parser del
 * DSL) y lo compara con el manifiesto versionado `ops/inventory/routes.*.txt`. Las rutas que el OpenAPI congelado no
 * declara solo pueden existir si estan en el registro revisado `ops/inventory/undocumented-routes.tsv`: la lista solo puede
 * encogerse (una fila obsoleta falla y el `# max:` no puede crecer sin editarlo a la vista de la revision).
 *
 * Regenerar en local (nunca en CI): `INVENTORY_UPDATE=true ./gradlew :backend:ktor:test --tests "*RouteInventoryTest*"`.
 */
class RouteInventoryTest {

    private val docs = OpenApiDocs.locate()
    private val inventoryDir = File(docs.dir.parentFile, "ops/inventory")
    private val update = System.getenv("INVENTORY_UPDATE") == "true"

    private fun template(path: String) = path.replace(Regex("\\{[^}]+}"), "{}")
    private val documented: Set<String> by lazy { docs.operations().map { "${it.method.uppercase()} ${template(it.path)}" }.toSet() }

    // recorrido real: ver CombinedRouteFixture
    private val combined: List<WalkedRoute> by lazy { CombinedRouteFixture.walkCombined() }
    private val consumer: List<WalkedRoute> by lazy { CombinedRouteFixture.walkConsumer() }

    // ------------------------------------------------------------------ comparadores puros (los ejercitan los controles negativos)

    private data class Row(val method: String, val path: String, val cls: String, val ref: String, val consumer: String, val evidence: String) {
        val key get() = "$method $path"
    }

    private val allowedClasses = setOf("LEGACY_PUBLISHED_CLIENT_TELEMETRY_PENDING", "ADMIN_CONTRACT_GAP", "INFRA", "DEV_ONLY")
    private val allowedConsumers = setOf("OC", "OB", "BOTH", "NONE")

    private fun parseRegister(file: File): Pair<Int, List<Row>> {
        val lines = file.readLines()
        val max = lines.firstNotNullOf { l -> Regex("^#\\s*max:\\s*(\\d+)").find(l)?.groupValues?.get(1)?.toInt() }
        val rows = lines.filter { it.isNotBlank() && !it.startsWith("#") }.map { l ->
            val c = l.split('\t')
            require(c.size == 6) { "undocumented-routes.tsv: 6 tab-separated columns expected: $l" }
            Row(c[0].trim(), c[1].trim(), c[2].trim(), c[3].trim(), c[4].trim(), c[5].trim())
        }
        return max to rows
    }

    private fun manifestViolations(name: String, actual: Set<String>, committed: Set<String>): List<String> =
        (actual - committed).map { "$name: route served but absent from the committed manifest: $it" } +
            (committed - actual).map { "$name: route in the committed manifest but no longer served: $it" }

    private fun registerViolations(routes: Set<String>, documentedOps: Set<String>, max: Int, rows: List<Row>): List<String> {
        val out = mutableListOf<String>()
        val byKey = rows.groupBy { it.key }
        byKey.filterValues { it.size > 1 }.keys.forEach { out += "duplicate register row: $it" }
        rows.forEach { r ->
            if (r.key !in routes) out += "stale register row (no longer routed): ${r.key}"
            if (r.key in documentedOps) out += "stale register row (now documented in openapi/): ${r.key}"
            if (r.cls !in allowedClasses) out += "unknown class '${r.cls}': ${r.key}"
            if (r.consumer !in allowedConsumers) out += "unknown consumer '${r.consumer}': ${r.key}"
            if (r.evidence.isBlank()) out += "empty evidence/justification: ${r.key}"
            if (r.cls == "LEGACY_PUBLISHED_CLIENT_TELEMETRY_PENDING" && r.ref.isBlank()) out += "legacy row without LegacyEndpoint id/successor ref: ${r.key}"
        }
        (routes - documentedOps - byKey.keys).sorted().forEach { out += "undocumented route without a register entry: $it" }
        if (rows.size > max) out += "register has ${rows.size} rows > '# max: $max' (the list can only shrink)"
        if (rows.size < max) out += "register has ${rows.size} rows < '# max: $max': lower the ratchet"
        return out
    }

    private val sentinels = listOf(
        "GET /admin/news-types", "GET /content/noticias", "GET /api/v1/admin/dashboard/overview", "GET /tags",
        "GET /health/live", "GET /metrics", "POST /v1/auth/login",
    )

    private fun sentinelViolations(routes: Set<String>): List<String> =
        sentinels.filter { it !in routes }.map { "sentinel route not mounted (a module mount was dropped?): $it" }

    private fun liveViolations(routes: Set<String>): List<String> =
        docs.operations().filter { it.op["x-implementation"] == "LIVE" }
            .map { "${it.method.uppercase()} ${template(it.path)}" }.filter { it !in routes }
            .map { "OpenAPI LIVE operation without a Ktor route: $it" }

    private fun writeOrRead(name: String, actual: Set<String>): Set<String> {
        val file = File(inventoryDir, name)
        if (update) {
            inventoryDir.mkdirs()
            file.writeText(
                "# GENERATED from the real Ktor route tree by RouteInventoryTest. Do not edit by hand.\n" +
                    "# Regenerate locally: INVENTORY_UPDATE=true ./gradlew :backend:ktor:test --tests \"*RouteInventoryTest*\"\n" +
                    actual.joinToString("\n", postfix = "\n"),
            )
        }
        assertTrue(file.isFile, "$name missing: generate it with INVENTORY_UPDATE=true")
        return file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.toSet()
    }

    // ------------------------------------------------------------------ pruebas

    @Test
    fun theRouteTreeEqualsTheCommittedManifests() {
        val combinedKeys = combined.map { it.key }.toSortedSet()
        val consumerKeys = consumer.map { it.key }.toSortedSet()
        val v = manifestViolations("routes.combined.txt", combinedKeys, writeOrRead("routes.combined.txt", combinedKeys)) +
            manifestViolations("routes.consumer.txt", consumerKeys, writeOrRead("routes.consumer.txt", consumerKeys))
        assertTrue(v.isEmpty(), v.joinToString("\n"))
    }

    @Test
    fun everyUndocumentedRouteIsInTheReviewedRegisterAndTheRegisterHasNoStaleRows() {
        val (max, rows) = parseRegister(File(inventoryDir, "undocumented-routes.tsv"))
        val routes = (combined + consumer).map { it.key }.toSet()
        val v = registerViolations(routes, documented, max, rows)
        assertTrue(v.isEmpty(), v.joinToString("\n"))
    }

    @Test
    fun everyLiveOpenApiOperationIsServedAndOneSentinelPerModuleIsMounted() {
        val routes = (combined + consumer).map { it.key }.toSet()
        val v = liveViolations(routes) + sentinelViolations(routes)
        assertTrue(v.isEmpty(), v.joinToString("\n"))
    }

    @Test
    fun everyLegacyMarkedRouteIsInTheRegisterWithALegacyClass() {
        val (_, rows) = parseRegister(File(inventoryDir, "undocumented-routes.tsv"))
        val byKey = rows.associateBy { it.key }
        val v = (combined + consumer).filter { it.legacyMarked }.map { it.key }.toSortedSet().mapNotNull { k ->
            val row = byKey[k]
            when {
                row == null && k !in documented -> "legacy-marked route not in the register: $k"
                row != null && row.cls != "LEGACY_PUBLISHED_CLIENT_TELEMETRY_PENDING" -> "legacy-marked route with class ${row.cls}: $k"
                else -> null
            }
        }
        assertTrue(v.isEmpty(), v.joinToString("\n"))
    }

    // ------------------------------------------------------------------ controles negativos

    @Test
    fun negativeControlAProbeRouteMountedNextToTheRealTreeIsReported() {
        val probe = CombinedRouteFixture.walkCombined { get("/__inventory_probe") { } }.map { it.key }.toSortedSet()
        val committed = writeOrRead("routes.combined.txt", combined.map { it.key }.toSortedSet())
        assertTrue("GET /__inventory_probe" in probe)
        assertTrue(manifestViolations("combined", probe, committed).any { "__inventory_probe" in it })
        val (max, rows) = parseRegister(File(inventoryDir, "undocumented-routes.tsv"))
        assertTrue(registerViolations(probe + consumer.map { it.key }, documented, max, rows).any { "GET /__inventory_probe" in it })
    }

    @Test
    fun negativeControlStaleDocumentedOrIncompleteRowsAreReported() {
        val routes = setOf("GET /a", "GET /b")
        fun row(m: String, p: String, cls: String = "ADMIN_CONTRACT_GAP", ref: String = "", consumer: String = "NONE", ev: String = "e") =
            Row(m, p, cls, ref, consumer, ev)
        assertTrue(registerViolations(routes, setOf("GET /b"), 1, listOf(row("GET", "/a"), row("GET", "/gone")))
            .any { "stale register row (no longer routed): GET /gone" in it })
        assertTrue(registerViolations(routes, setOf("GET /b"), 1, listOf(row("GET", "/a"), row("GET", "/b")))
            .any { "now documented" in it })
        assertTrue(registerViolations(routes, emptySet(), 1, listOf(row("GET", "/a"), row("GET", "/b")))
            .any { "max" in it })
        assertTrue(registerViolations(routes, setOf("GET /b"), 1, listOf(row("GET", "/a", ev = "")))
            .any { "empty evidence" in it })
        assertTrue(registerViolations(routes, setOf("GET /b"), 1, listOf(row("GET", "/a", cls = "LEGACY_PUBLISHED_CLIENT_TELEMETRY_PENDING")))
            .any { "without LegacyEndpoint id" in it })
        assertTrue(registerViolations(routes, setOf("GET /b"), 1, listOf(row("GET", "/a", cls = "WHATEVER")))
            .any { "unknown class" in it })
        assertTrue(registerViolations(routes, setOf("GET /b"), 1, emptyList()).any { "without a register entry: GET /a" in it })
        assertEquals(emptyList(), registerViolations(routes, setOf("GET /b"), 1, listOf(row("GET", "/a"))))
    }

    @Test
    fun negativeControlADroppedModuleMountFailsTheSentinels() {
        val real = combined.map { it.key }.toSet()
        assertEquals(emptyList(), sentinelViolations(real))
        assertTrue(sentinelViolations(real - "GET /tags").any { "GET /tags" in it })
        assertTrue(liveViolations(emptySet()).isNotEmpty())
    }
}
