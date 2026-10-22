package com.oposicontrol.backend.inventory

import com.oposicontrol.backend.AppV1Gateway
import com.oposicontrol.backend.LegacyEndpoints
import com.oposicontrol.backend.OposiBotsUserAdminLegacyEndpoints
import com.oposicontrol.backend.SupabaseJwtVerifier
import com.oposicontrol.backend.V1StubHttpClient
import com.oposicontrol.backend.V1StubResponse
import com.oposicontrol.backend.allowsDevBypass
import com.oposicontrol.backend.appV1Routes
import com.oposicontrol.backend.installBackendStatusPages
import com.oposicontrol.backend.installRequestCorrelation
import com.oposicontrol.backend.openapi.OpenApiDocs
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * LEGACY-ROUTES-CLASSIFY: el registro `docs/integration/api-redesign/legacy-route-register.tsv` clasifica CADA ruta legacy
 * (RETIRE_AFTER_TELEMETRY / KEEP_FOR_PUBLISHED_CLIENTS / UNKNOWN con propietario) con su evidencia y su contador, SIN borrar
 * ninguna. Toda ruta marcada con `legacyRoute(...)` debe estar en el registro y toda fila debe seguir servida.
 */
class LegacyRouteRegisterTest {

    private val repoRoot = OpenApiDocs.locate().dir.parentFile
    private val registerFile = File(repoRoot, "docs/integration/api-redesign/legacy-route-register.tsv")
    private val undocumentedFile = File(repoRoot, "ops/inventory/undocumented-routes.tsv")

    private data class Row(
        val method: String, val path: String, val endpointId: String, val cls: String,
        val counter: String, val owner: String, val evidence: String,
    ) {
        val key get() = "$method $path"
    }

    private val classes = setOf("RETIRE_AFTER_TELEMETRY", "KEEP_FOR_PUBLISHED_CLIENTS", "UNKNOWN")

    private fun rows(file: File = registerFile): List<Row> = file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { l ->
        val c = l.split('\t')
        require(c.size == 7) { "legacy-route-register.tsv: 7 tab-separated columns expected: $l" }
        Row(c[0], c[1], c[2], c[3], c[4], c[5], c[6])
    }

    private val knownEndpointIds: Set<String> = (
        LegacyEndpoints.SLICE_0 + LegacyEndpoints.SLICE_1 + LegacyEndpoints.SLICE_1_APP + LegacyEndpoints.RETIRED_ADMIN_WRITERS +
            LegacyEndpoints.RETIRED_LEGACY_WRITERS + LegacyEndpoints.RETIRED_PUBLIC_READS + listOf(
            OposiBotsUserAdminLegacyEndpoints.BOT_ADMIN_001, OposiBotsUserAdminLegacyEndpoints.BOT_ADMIN_001_POST,
            OposiBotsUserAdminLegacyEndpoints.BOT_ADMIN_002, OposiBotsUserAdminLegacyEndpoints.BOT_ADMIN_002_DELETE,
        )
        ).map { it.endpointId }.toSet()

    /** [markedRoutes]: `METHOD path` de las hojas con el plugin `legacyRoute`; [served]: todas las rutas servidas. */
    private fun violations(rows: List<Row>, markedRoutes: Set<String>, served: Set<String>, legacyKeysInInventory: Set<String>): List<String> {
        val out = mutableListOf<String>()
        val byKey = rows.associateBy { it.key }
        markedRoutes.filter { it !in byKey }.forEach { out += "legacy-marked route without a register row: $it" }
        rows.forEach { r ->
            if (r.key !in served) out += "register row for a route that is no longer served (deleting a legacy route needs zero telemetry first): ${r.key}"
            if (r.cls !in classes) out += "unknown class '${r.cls}': ${r.key}"
            if (r.cls == "UNKNOWN" && !r.owner.startsWith("owner:")) out += "UNKNOWN row without 'owner:': ${r.key}"
            if (r.evidence.isBlank()) out += "empty evidence: ${r.key}"
            if (r.key in markedRoutes) {
                if (r.endpointId !in knownEndpointIds) out += "endpoint id '${r.endpointId}' is not a LegacyEndpoint: ${r.key}"
                if (r.counter != "legacy_route_requests_total{endpoint=\"${r.endpointId}\"}") out += "wrong counter label for ${r.key}: ${r.counter}"
            } else if (!r.counter.startsWith("NONE")) out += "unmarked route must declare counter NONE until .legacyRoute is added: ${r.key}"
        }
        (legacyKeysInInventory - byKey.keys).forEach { out += "route classed legacy in undocumented-routes.tsv but missing from the register: $it" }
        (byKey.keys - legacyKeysInInventory).forEach { out += "register row not classed legacy in undocumented-routes.tsv: $it" }
        return out
    }

    private val served: Set<String> by lazy { (CombinedRouteFixture.walkCombined() + CombinedRouteFixture.walkConsumer()).map { it.key }.toSet() }
    private val marked: Set<String> by lazy {
        (CombinedRouteFixture.walkCombined() + CombinedRouteFixture.walkConsumer()).filter { it.legacyMarked }.map { it.key }.toSet()
    }
    private val inventoryLegacy: Set<String> by lazy {
        undocumentedFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
            .filter { it[2] == "LEGACY_PUBLISHED_CLIENT_TELEMETRY_PENDING" }.map { "${it[0]} ${it[1]}" }.toSet()
    }

    @Test
    fun everyLegacyRouteIsClassifiedAndNoneIsRetiredWithoutTelemetry() {
        val v = violations(rows(), marked, served, inventoryLegacy)
        assertTrue(v.isEmpty(), v.joinToString("\n"))
        assertTrue(marked.size >= 25, "the 25 legacyRoute(...) leaves must be found, got ${marked.size}")
    }

    @Test
    fun negativeControlAnUnregisteredLegacyRouteStaleRowAndMissingOwnerAreReported() {
        val real = rows()
        assertTrue(violations(real, marked + "GET /__probe_legacy", served + "GET /__probe_legacy", inventoryLegacy)
            .any { "legacy-marked route without a register row: GET /__probe_legacy" in it })
        assertTrue(violations(real + real.first().copy(method = "GET", path = "/gone"), marked, served, inventoryLegacy)
            .any { "no longer served" in it })
        val unknown = real.first { it.cls == "UNKNOWN" }
        assertTrue(violations(real - unknown + unknown.copy(owner = "-"), marked, served, inventoryLegacy)
            .any { "UNKNOWN row without 'owner:'" in it })
        assertTrue(violations(real.map { if (it.key == real.first().key) it.copy(cls = "RETIRE_NOW_SAFE") else it }, marked, served, inventoryLegacy)
            .any { "unknown class 'RETIRE_NOW_SAFE'" in it })
        assertTrue(violations(real.drop(1), marked, served, inventoryLegacy).isNotEmpty())
    }

    // ------------------------------------------------------------------ allowDevUser a nivel de cable

    private fun statusOf(appEnv: String): HttpStatusCode {
        var status = HttpStatusCode.OK
        testApplication {
            application {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                installRequestCorrelation()
                installBackendStatusPages()
                val gateway = AppV1Gateway.forTesting(
                    "https://stub.invalid", "stub-key", Json { ignoreUnknownKeys = true },
                    V1StubHttpClient({ }) { V1StubResponse(200, "[]") },
                )
                // El flag se resuelve con la MISMA funcion que usa Application.module (entorno + OPOSICONTROL_ALLOW_DEV_USER=true).
                routing { appV1Routes(gateway, SupabaseJwtVerifier("https://stub.invalid", Json { ignoreUnknownKeys = true }), allowsDevBypass(appEnv, true)) }
            }
            status = client.get("/api/v1/app/home") { header("X-Usuario-Id", "11111111-1111-1111-1111-111111111111") }.status
        }
        return status
    }

    @Test
    fun theDevUserHeaderIsRejectedOnTheWireOutsideDevelopmentAndHonouredInIt() {
        assertEquals(HttpStatusCode.Unauthorized, statusOf("production"), "X-Usuario-Id sin JWT en produccion")
        assertEquals(HttpStatusCode.Unauthorized, statusOf(""), "entorno en blanco = produccion (fail-closed)")
        // Control positivo: el mismo montaje en desarrollo SI acepta la cabecera (no es un 401 vacuo).
        assertNotEquals(HttpStatusCode.Unauthorized, statusOf("dev"))
    }
}
