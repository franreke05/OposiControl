package com.oposicontrol.admin.data.inventory

import io.ktor.client.request.get
import io.ktor.client.request.post
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * INV-NETWORK-COMPLETE (parte cliente, registrador en tiempo de ejecucion). SOLO PRUEBAS, sin produccion, sin parser de Kotlin.
 *
 *  1. Cada fila de [CLIENT_CALL_ROWS] ejecuta su metodo contra MockEngine y la secuencia grabada (metodo + plantilla) es la
 *     declarada y existe en `ops/inventory/routes.combined.txt`.
 *  2. Completitud: por cada fichero del tripwire `HTTPCALL` (direct-supabase-allowlist.tsv), sitios de llamada == filas + pendientes.
 *     El conteo es texto crudo (lineas que no son comentario con `client.<verbo>(`), el mismo patron del tripwire.
 *  3. Controles negativos: llamada no listada, ruta distinta, metodo distinto y metodo nuevo sin fila FALLAN.
 */
class ClientCallInventoryTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "ops/inventory/routes.combined.txt").isFile }
    private val served: Set<String> = parseServedRoutes(File(repoRoot, "ops/inventory/routes.combined.txt").readLines())

    private val callSite = Regex("(client|httpClient)\\.(get|post|put|delete|patch|head|options|request|submitForm|submitFormWithBinaryData)\\(")

    private fun sitesIn(file: String): Int = File(repoRoot, file).readLines()
        .filterNot { it.trimStart().let { t -> t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") } }
        .sumOf { callSite.findAll(it).count() }

    /** Ficheros HTTPCALL del modulo `data` segun el tripwire existente. */
    private fun tripwireClientFiles(): List<String> = File(repoRoot, "ops/inventory/direct-supabase-allowlist.tsv").readLines()
        .filter { it.startsWith("HTTPCALL\t") }.map { it.split('\t')[1] }.filter { it.startsWith("data/") }

    private fun sitesByFile(): Map<String, Int> = tripwireClientFiles().associateWith(::sitesIn)

    private fun rowsByFile(rows: List<CallRow> = CLIENT_CALL_ROWS): Map<String, Int> = rows.groupingBy { it.file }.eachCount()

    // ------------------------------------------------------------------------------------------------ puertas reales

    @Test
    fun everyRecorderRowSendsTheDeclaredCallAndItIsARoutedBackendRoute() = runTest {
        val all = CLIENT_CALL_ROWS.flatMap { rowViolations(it, it.record(), served) }
        assertTrue(all.isEmpty(), all.joinToString("\n"))
        assertTrue(CLIENT_CALL_ROWS.size >= 6, "the worked example rows disappeared")
    }

    @Test
    fun rowNamesAreUniquePerFile() {
        val dup = CLIENT_CALL_ROWS.groupBy { it.file to it.name }.filterValues { it.size > 1 }.keys
        assertTrue(dup.isEmpty(), "duplicate recorder rows: $dup")
    }

    @Test
    fun theHttpCallSitesOfEveryClientFileAreCoveredByRowsOrAShrinkOnlyPendingList() {
        val v = completenessViolations(sitesByFile(), rowsByFile(), PENDING_ROWS)
        assertTrue(v.isEmpty(), v.joinToString("\n"))
    }

    @Test
    fun theTripwireFileSetIsExactlyTheClientFilesThatCallHttp() {
        val real = File(repoRoot, "data/src/commonMain").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(repoRoot).invariantSeparatorsPath }.filter { sitesIn(it) > 0 }.toSortedSet()
        assertEquals(real.toList(), tripwireClientFiles().toSortedSet().toList(), "HTTPCALL tripwire vs real client files in data/")
        assertTrue(real.size >= 20, "scan found only ${real.size} client files: vacuous")
    }

    // ------------------------------------------------------------------------- controles negativos (cada regla DISPARA)

    private val auditRow = CLIENT_CALL_ROWS.first { it.name == "getEvents" }

    @Test
    fun negativeControlUnlistedCallFails() = runTest {
        // el cliente llama a una ruta que el backend NO sirve: la fila la declara bien pero no esta en routes.combined
        val ghost = CallRow(auditRow.file, "ghost", get("/api/v1/admin/__not_routed")) { client.get("http://backend.test/api/v1/admin/__not_routed") }
        val v = rowViolations(ghost, ghost.record(), served)
        assertTrue(v.any { "is not a route in routes.combined.txt" in it }, v.toString())
    }

    @Test
    fun negativeControlWrongPathFails() = runTest {
        val wrong = CallRow(auditRow.file, "getEvents", get("/api/v1/admin/roles"), auditRow.body)
        val v = rowViolations(wrong, wrong.record(), served)
        assertTrue(v.any { "the row declares" in it }, v.toString())
    }

    @Test
    fun negativeControlMethodMismatchFails() = runTest {
        // la fila declara POST pero el cliente envia GET
        val wrong = CallRow(auditRow.file, "getEvents", post("/api/v1/admin/auditoria"), auditRow.body)
        assertTrue(rowViolations(wrong, wrong.record(), served).any { "the row declares" in it })
        // el cliente envia POST a una ruta que solo sirve GET: el metodo no esta en routes.combined aunque la fila lo declare
        val posting = CallRow(auditRow.file, "postAudit", post("/api/v1/admin/auditoria")) { client.post("http://backend.test/api/v1/admin/auditoria") }
        val v = rowViolations(posting, posting.record(), served)
        assertTrue(v.any { "POST /api/v1/admin/auditoria is not a route" in it }, v.toString())
    }

    @Test
    fun negativeControlUnknownHostAndDirectSupabaseBusinessCallFail() {
        val row = CallRow(auditRow.file, "x", Call("GET", "/rest/v1/t", HostClass.SUPABASE)) {}
        assertTrue(rowViolations(row, listOf(Call("GET", "/rest/v1/t", HostClass.SUPABASE)), served).any { "identity allowlist" in it })
        assertTrue(rowViolations(row, listOf(Call("GET", "/x", HostClass.UNKNOWN)), served).any { "unknown host" in it })
        assertTrue(rowViolations(row, emptyList(), served).any { "no call at all" in it })
    }

    @Test
    fun negativeControlNewMethodWithoutARowFails() {
        val sites = sitesByFile()
        // un metodo nuevo en un fichero ya completo (AuditApi) sube sus sitios a 2 con una sola fila
        val grown = sites + (auditRow.file to sites.getValue(auditRow.file) + 1)
        assertTrue(completenessViolations(grown, rowsByFile(), PENDING_ROWS).any { "AuditApi.kt" in it })
        // un fichero HTTP nuevo, sin filas ni pendiente
        val extra = sites + ("data/src/commonMain/NewApi.kt" to 1)
        assertTrue(completenessViolations(extra, rowsByFile(), PENDING_ROWS).any { "NewApi.kt" in it })
        // una fila de menos (borrada) tambien falla; una pendiente resuelta y sobrante tambien
        assertTrue(completenessViolations(sites, rowsByFile(CLIENT_CALL_ROWS.drop(1)), PENDING_ROWS).any { "AuditApi.kt" in it })
        assertTrue(completenessViolations(sites, rowsByFile(), PENDING_ROWS + (auditRow.file to 0)).any { "stale pending" in it })
        assertEquals(emptyList(), completenessViolations(sites, rowsByFile(), PENDING_ROWS))
    }
}
