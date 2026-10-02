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
 *     El conteo es texto crudo sin receptor ni filtro de comentarios ([EGRESS_VERB]: multilinea y `with(client)` cuentan),
 *     menos [SITE_EXCLUSIONS] fijadas por fichero + patron + cuenta exacta + justificacion.
 *  3. Cobertura por punto de entrada (reflexion): todo metodo publico de la clase cliente (y su fachada `Kt`) tiene fila
 *     (`covers`), esta en [PENDING_METHODS] (solo encoge) o en [NON_HTTP_MEMBERS] con justificacion. Cierra la reutilizacion de
 *     ayudantes: un metodo nuevo sobre `getList` no anade sitios, pero no tiene fila y FALLA.
 *  4. Tripwire de ficheros: todo fichero de produccion (fuera de `backend/`) que toca el cliente Ktor es HTTPCALL o un
 *     no-cliente fijado en [HTTP_REFERENCE_NON_CLIENTS] con 0 sitios.
 *  5. Controles negativos: llamada no listada, ruta distinta, metodo distinto, metodo nuevo sin fila, y las tres evasiones del
 *     validador de la ronda 4 (multilinea, `with(client)`, reutilizacion de ayudante) FALLAN.
 */
class ClientCallInventoryTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "ops/inventory/routes.combined.txt").isFile }
    private val served: Set<String> = parseServedRoutes(File(repoRoot, "ops/inventory/routes.combined.txt").readLines())

    /** Sitios de egreso en TEXTO CRUDO ([EGRESS_VERB] menos [SITE_EXCLUSIONS] fijadas): multilinea y `with(client)` cuentan. */
    private fun sitesIn(file: String): Int = egressSites(file, File(repoRoot, file).readText(), SITE_EXCLUSIONS).first

    /** Ficheros HTTPCALL del modulo `data` segun el tripwire existente. */
    private fun tripwireClientFiles(): List<String> = File(repoRoot, "ops/inventory/direct-supabase-allowlist.tsv").readLines()
        .filter { it.startsWith("HTTPCALL\t") }.map { it.split('\t')[1] }.filter { it.startsWith("data/") }

    private fun sitesByFile(): Map<String, Int> = tripwireClientFiles().associateWith(::sitesIn)

    /** Clase cliente (+ su fachada `<Fichero>Kt` de funciones de nivel superior) a partir de la ruta del fichero. */
    private fun clientClasses(file: String): Array<Class<*>> {
        val fqn = file.substringAfter("/kotlin/").removeSuffix(".kt").replace('/', '.')
        return listOfNotNull(Class.forName(fqn), runCatching { Class.forName("${fqn}Kt") }.getOrNull()).toTypedArray()
    }

    private fun entryPointViolationsFor(file: String, publicMethods: Set<String>, rows: List<CallRow> = CLIENT_CALL_ROWS) =
        entryPointViolations(
            file, publicMethods, rows.filter { it.file == file }.flatMap { it.covers }.toSet(),
            PENDING_METHODS[file].orEmpty(), NON_HTTP_MEMBERS[file].orEmpty().keys,
        )

    /** Produccion fuera de `backend/` (cualquier `src/<x>Main`), sin `build/`. */
    private fun productionKotlinFiles(): List<String> = repoRoot.walkTopDown()
        .onEnter { it.name !in setOf("build", ".gradle", ".git", "node_modules") && it.relativeTo(repoRoot).invariantSeparatorsPath != "backend" }
        .filter { it.isFile && it.extension == "kt" }
        .map { it.relativeTo(repoRoot).invariantSeparatorsPath }
        .filter { Regex("/src/[A-Za-z]*[Mm]ain/").containsMatchIn(it) }
        .toList()

    /** Toca el cliente: nombra `HttpClient`, importa request/statement de Ktor o llama un verbo sobre `client` (multilinea incluida). */
    private val touchesHttpClient = Regex(
        """\bHttpClient\b|import io\.ktor\.client\.(request|statement)\.|\b(client|httpClient)\s*\.\s*""" + EGRESS_VERB.pattern.removePrefix("""\b"""),
    )

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
        // fail-closed por texto crudo en TODA la produccion (no solo data/): cada fichero que toca el cliente Ktor es un cliente
        // HTTPCALL o un no-cliente fijado con justificacion y 0 sitios de egreso.
        val touching = productionKotlinFiles().filter { touchesHttpClient.containsMatchIn(File(repoRoot, it).readText()) }.toSortedSet()
        assertEquals(
            tripwireClientFiles().toSortedSet().toList(), (touching - HTTP_REFERENCE_NON_CLIENTS.keys).toList(),
            "HTTPCALL tripwire vs real production files that touch the Ktor client",
        )
        assertTrue(touching.size >= 20, "scan found only ${touching.size} client files: vacuous")
        assertTrue((HTTP_REFERENCE_NON_CLIENTS.keys - touching).isEmpty(), "stale non-client entries: ${HTTP_REFERENCE_NON_CLIENTS.keys - touching}")
        val bad = HTTP_REFERENCE_NON_CLIENTS.filter { (f, why) -> why.isBlank() || sitesIn(f) != 0 }.keys
        assertTrue(bad.isEmpty(), "non-client files need a justification and 0 egress sites: $bad")
    }

    @Test
    fun siteExclusionsArePinnedExactlyAndJustified() {
        val v = SITE_EXCLUSIONS.map { it.file }.distinct().flatMap { egressSites(it, File(repoRoot, it).readText(), SITE_EXCLUSIONS).second }
        assertTrue(v.isEmpty(), v.joinToString("\n"))
        val orphan = SITE_EXCLUSIONS.map { it.file }.filter { it !in tripwireClientFiles() && it !in HTTP_REFERENCE_NON_CLIENTS }
        assertTrue(orphan.isEmpty(), "exclusions for files that are not client files: $orphan")
    }

    @Test
    fun everyPublicEntryPointOfEveryClientIsCoveredByARowOrAShrinkOnlyPendingMethod() {
        val v = tripwireClientFiles().flatMap { f -> entryPointViolationsFor(f, publicMethodNames(*clientClasses(f))) }
        assertTrue(v.isEmpty(), v.joinToString("\n"))
        val blankWhy = NON_HTTP_MEMBERS.flatMap { (f, m) -> m.filterValues { it.isBlank() }.keys.map { "$f#$it" } }
        assertTrue(blankWhy.isEmpty(), "non-HTTP members without justification: $blankWhy")
        val orphan = (NON_HTTP_MEMBERS.keys + PENDING_METHODS.keys) - tripwireClientFiles().toSet()
        assertTrue(orphan.isEmpty(), "entries for files that are not HTTPCALL clients: $orphan")
        // pendiente por sitio y por metodo van juntos: el mismo conjunto de ficheros
        assertEquals(PENDING_ROWS.keys.toSortedSet().toList(), PENDING_METHODS.filterValues { it.isNotEmpty() }.keys.toSortedSet().toList())
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

    // ------------------------------- R5-HARDEN: las tres evasiones del validador (sondas news_s2, news_s3, market_s4) FALLAN

    private val newsFile = "data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/KtorNewsRepositoryImpl.kt"
    private val marketFile = "data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/KtorMarketCatalogRepositoryImpl.kt"
    private val rankingsFile = "data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/KtorRankingsRepositoryImpl.kt"

    /** Sitios del fichero real con una sonda anadida (el conteo es de texto crudo: la posicion no importa). */
    private fun sitesWithProbe(file: String, probe: String) =
        egressSites(file, File(repoRoot, file).readText() + "\n" + probe + "\n", SITE_EXCLUSIONS)

    private fun completenessWithProbe(file: String, probe: String): List<String> {
        val (probed, exclusionViolations) = sitesWithProbe(file, probe)
        return exclusionViolations + completenessViolations(sitesByFile() + (file to probed), rowsByFile(), PENDING_ROWS)
    }

    @Test
    fun negativeControlMultiLineCallIsCountedAndFails() {
        // sonda news_s2 del validador: receptor y verbo en lineas distintas
        val v = completenessWithProbe(newsFile, "    suspend fun ghost() = client\n        .get(\"\${newsUrl()}/__ghost\")")
        assertTrue(v.any { "KtorNewsRepositoryImpl.kt: 6 HTTP call sites but 5 recorder rows" in it }, v.toString())
        assertTrue(touchesHttpClient.containsMatchIn("val x = client\n    .post(u)"), "multi-line receiver not seen by the file-set tripwire")
    }

    @Test
    fun negativeControlWithClientScopeIsCountedAndFails() {
        // sonda news_s3 del validador: verbo sin receptor dentro de with(client) / apply
        val v = completenessWithProbe(newsFile, "    suspend fun ghost() = with(client) { get(\"\${newsUrl()}/__ghost\") }")
        assertTrue(v.any { "KtorNewsRepositoryImpl.kt: 6 HTTP call sites but 5 recorder rows" in it }, v.toString())
        val applied = completenessWithProbe(newsFile, "    suspend fun ghost() = client.apply {\n        post<Unit>(\"x\")\n    }")
        assertTrue(applied.any { "KtorNewsRepositoryImpl.kt: 6 HTTP call sites" in it }, applied.toString())
        // un fichero nuevo que solo llama dentro de with(client) igual necesita importar el verbo: el tripwire de ficheros lo ve
        assertTrue(touchesHttpClient.containsMatchIn("import io.ktor.client.request.get\nfun f() = with(c) { get(u) }"))
    }

    @Test
    fun negativeControlHelperReuseByANewPublicMethodFails() {
        // sonda market_s4 del validador: getGhosts() reutiliza el ayudante getList -> el CONTEO no cambia (por eso evadia)...
        val probe = "    suspend fun getGhosts(): List<PackDto> = getList<PackDto, PacksResponse>(\"\$MARKET/__ghost\")"
        assertEquals(sitesIn(marketFile), sitesWithProbe(marketFile, probe).first)
        // ...pero el punto de entrada nuevo no tiene fila: la cobertura por reflexion FALLA
        val real = publicMethodNames(*clientClasses(marketFile))
        assertEquals(emptyList(), entryPointViolationsFor(marketFile, real))
        val v = entryPointViolationsFor(marketFile, real + "getGhosts")
        assertTrue(v.any { "#getGhosts: public method without a recorder row" in it }, v.toString())
        // otra invocacion del ayudante privado `get` de Rankings no la absorbe su exclusion fijada (5): falla la exclusion
        val r = sitesWithProbe(rankingsFile, "    suspend fun ghost() = get<RankingPageDto>(\"/__ghost\")").second
        assertTrue(r.any { "matches 6 times but is pinned to 5" in it }, r.toString())
        // y una llamada con receptor nunca la absorbe una exclusion (lookbehind): sube los sitios
        assertEquals(sitesIn(rankingsFile) + 1, sitesWithProbe(rankingsFile, "    suspend fun ghost() = client.get<RankingPageDto>(\"/x\")").first)
    }

    /** Clase de ensayo: la reflexion ve TODO punto de entrada publico, use with(client), multilinea o un ayudante privado. */
    private class GhostFixtureClient(private val client: io.ktor.client.HttpClient) {
        suspend fun covered() = client.get("http://backend.test/api/v1/admin/auditoria")
        suspend fun ghostWith() = with(client) { get("http://backend.test/__ghost") }
        suspend fun ghostMultiLine() = client
            .get("http://backend.test/__ghost")
        suspend fun ghostHelper() = fetch("http://backend.test/__ghost")
        private suspend fun fetch(u: String) = client.get(u)
    }

    @Test
    fun negativeControlReflectionSeesEveryNewEntryPointWhateverItsShape() {
        val methods = publicMethodNames(GhostFixtureClient::class.java)
        assertEquals(setOf("covered", "ghostWith", "ghostMultiLine", "ghostHelper"), methods)
        val v = entryPointViolations("Fixture.kt", methods, covered = setOf("covered"), pending = emptySet(), nonHttp = emptySet())
        assertEquals(3, v.size, v.toString())
        // y las entradas obsoletas tambien fallan
        assertTrue(entryPointViolations("F.kt", methods, setOf("covered", "gone"), setOf("ghostWith", "covered"), setOf("ghostHelper", "x"))
            .let { o -> listOf("#gone: a row covers", "stale pending method (it has a row", "#x: stale non-HTTP", "#ghostMultiLine: public method").all { k -> o.any { k in it } } })
    }
}
