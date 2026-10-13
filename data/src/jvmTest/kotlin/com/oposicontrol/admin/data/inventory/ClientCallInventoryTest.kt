package com.oposicontrol.admin.data.inventory

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
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
 *  1. Cada fila de [CLIENT_CALL_ROWS] ejecuta sus metodos contra MockEngine y la secuencia grabada (metodo + plantilla con el
 *     ORDEN de los ids + nombres de la consulta) es la declarada y existe en `ops/inventory/routes.combined.txt`.
 *  2. Completitud POR FUNCION: en cada fichero del tripwire `HTTPCALL`, filas con `site=f` == sitios de egreso de la funcion f.
 *     El conteo es texto crudo ([EGRESS_VERB]: `(` o lambda `{`, multilinea, `with(client)`/`apply`, sin filtro de comentarios)
 *     menos [SITE_EXCLUSIONS] fijadas por fichero + patron + cuenta exacta + justificacion. No hay lista de pendientes.
 *  3. Cobertura por FIRMA (reflexion): toda firma publica de la clase cliente, de su fachada `Kt` y de toda clase del fichero o
 *     anidada (inner, companion) que alcanza el cliente tiene fila (`covers`) o justificacion en [NON_HTTP_MEMBERS].
 *  4. Rutas y filtros en linea: cada literal de ruta y cada `parameter("x"` de una funcion lo graba una fila de esa funcion.
 *  5. Tripwire de ficheros: todo fichero de produccion (fuera de `backend/`) que toca el cliente Ktor es HTTPCALL o un
 *     no-cliente fijado en [HTTP_REFERENCE_NON_CLIENTS] con 0 sitios.
 *  6. Contadores (decision del propietario): TOTAL_NETWORK_CALL_SITES == TOTAL_RECORDED_ROWS, UNMAPPED_CALLS == 0,
 *     UNKNOWN_CALLS == 0, impresos en una linea `NETWORK_RECORDER_COUNTERS ...` legible por maquina.
 *  7. Controles negativos: cada regla DISPARA con una sonda real (sobre el texto del fichero real o una clase de ensayo).
 */
class ClientCallInventoryTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "ops/inventory/routes.combined.txt").isFile }
    private val served: Set<String> = parseServedRoutes(File(repoRoot, "ops/inventory/routes.combined.txt").readLines())

    private fun textOf(file: String) = File(repoRoot, file).readText()

    /** Ficheros HTTPCALL del modulo `data` segun el tripwire existente. */
    private fun tripwireClientFiles(): List<String> = File(repoRoot, "ops/inventory/direct-supabase-allowlist.tsv").readLines()
        .filter { it.startsWith("HTTPCALL\t") }.map { it.split('\t')[1] }.filter { it.startsWith("data/") }

    private fun sitesIn(file: String, text: String = textOf(file)): Int = egressSites(file, text, SITE_EXCLUSIONS).first

    /** fichero -> funcion -> sitios de egreso (texto crudo). */
    private fun sitesByFunction(): Map<String, Map<String, Int>> =
        tripwireClientFiles().associateWith { egressSitesByFunction(it, textOf(it), SITE_EXCLUSIONS).first }

    /** Firmas de los puntos de entrada del fichero cliente (clase principal, fachada `Kt` y clases que alcanzan el cliente). */
    private fun entryPoints(file: String, text: String = textOf(file)): Set<String> {
        val fqn = file.substringAfter("/kotlin/").removeSuffix(".kt").replace('/', '.')
        val pkg = fqn.substringBeforeLast('.')
        val loader = javaClass.classLoader
        val main = Class.forName(fqn, false, loader)
        val facade = runCatching { Class.forName("${fqn}Kt", false, loader) }.getOrNull()
        val others = TOP_LEVEL_CLASS.findAll(text).map { it.groupValues[1] }.filter { it != main.simpleName }
            .map { Class.forName("$pkg.$it", false, loader) }.toList()
        return entryPointSignatures(main, facade, others)
    }

    private fun entryPointViolationsFor(file: String, entries: Set<String>, rows: List<CallRow> = CLIENT_CALL_ROWS) =
        entryPointViolations(file, entries, rows.filter { it.file == file }.flatMap { it.covers }.toSet(), NON_HTTP_MEMBERS[file].orEmpty().keys)

    /** Produccion fuera de `backend/` (cualquier `src/<x>Main`), sin `build/`. */
    private fun productionKotlinFiles(): List<String> = repoRoot.walkTopDown()
        .onEnter { it.name !in setOf("build", ".gradle", ".git", "node_modules") && it.relativeTo(repoRoot).invariantSeparatorsPath != "backend" }
        .filter { it.isFile && it.extension == "kt" }
        .map { it.relativeTo(repoRoot).invariantSeparatorsPath }
        .filter { Regex("/src/[A-Za-z]*[Mm]ain/").containsMatchIn(it) }
        .toList()

    /** Toca el cliente: nombra `HttpClient`, importa request/statement de Ktor o llama un verbo (`(` o `{`) sobre `client`. */
    private val touchesHttpClient = Regex(
        """\bHttpClient\b|import io\.ktor\.client\.(request|statement)\.|\b(client|httpClient)\s*\.\s*""" + EGRESS_VERB.pattern.removePrefix("""\b"""),
    )

    private suspend fun recordAll(rows: List<CallRow> = CLIENT_CALL_ROWS): Map<CallRow, List<Call>> = rows.associateWith { it.record() }

    // ------------------------------------------------------------------------------------------------ contadores

    private data class Counters(val sites: Int, val rows: Int, val unmapped: Int, val unknown: Int) {
        fun line() = "NETWORK_RECORDER_COUNTERS TOTAL_NETWORK_CALL_SITES=$sites TOTAL_RECORDED_ROWS=$rows UNMAPPED_CALLS=$unmapped UNKNOWN_CALLS=$unknown"
    }

    /**
     * TOTAL_NETWORK_CALL_SITES: sitios de egreso contados en los ficheros HTTPCALL. TOTAL_RECORDED_ROWS: filas.
     * UNMAPPED_CALLS: sitios sin fila (deficit por funcion) + firmas publicas sin fila ni justificacion + rutas/filtros en linea
     * que ninguna fila de su funcion graba. UNKNOWN_CALLS: llamadas grabadas fuera de rutas servidas / identidad / host conocido
     * + filas cuya secuencia grabada difiere de la declarada + filas sin sitio (sobrantes por funcion) + filas de relleno.
     */
    private fun counters(
        sites: Map<String, Map<String, Int>>,
        rows: List<CallRow>,
        recorded: Map<CallRow, List<Call>>,
        entryViolations: Int,
        staticViolations: Int,
    ): Counters {
        var deficit = 0
        var surplus = 0
        sites.forEach { (file, byFun) ->
            val bySite = rows.filter { it.file == file }.groupingBy { it.site }.eachCount()
            (byFun.keys + bySite.keys).forEach { f ->
                val d = (byFun[f] ?: 0) - (bySite[f] ?: 0)
                if (d > 0) deficit += d else surplus -= d
            }
        }
        surplus += rows.count { it.file !in sites }
        val unknownCalls = recorded.entries.sumOf { (r, calls) -> calls.count { unknownCallViolation(r.name, it, served) != null } }
        val mismatched = recorded.count { (r, calls) -> !sameSequence(r.expect, calls) || calls.isEmpty() }
        val padding = paddingViolations(sites, recorded).size
        return Counters(
            sites = sites.values.sumOf { it.values.sum() },
            rows = rows.size,
            unmapped = deficit + entryViolations + staticViolations,
            unknown = unknownCalls + mismatched + surplus + padding,
        )
    }

    // ------------------------------------------------------------------------------------------------ puertas reales

    @Test
    fun networkRecorderCountersAreBalanced() = runTest {
        val sites = sitesByFunction()
        val recorded = recordAll()
        val entry = tripwireClientFiles().flatMap { entryPointViolationsFor(it, entryPoints(it)) }
        val static = tripwireClientFiles().flatMap { staticTargetViolations(it, textOf(it), recorded) }
        val c = counters(sites, CLIENT_CALL_ROWS, recorded, entry.size, static.size)
        println(c.line())
        assertEquals(c.sites, c.rows, c.line())
        assertEquals(0, c.unmapped, c.line() + "\n" + (entry + static).joinToString("\n"))
        assertEquals(0, c.unknown, c.line())
        // 121 desde la integracion de la rama de contenido (retiro createQuestionResource, un sitio menos).
        assertTrue(c.sites >= 121,"the counter found only ${c.sites} sites: vacuous")
    }

    @Test
    fun everyRecorderRowSendsTheDeclaredCallAndItIsARoutedBackendRoute() = runTest {
        val all = recordAll().flatMap { (r, calls) -> rowViolations(r, calls, served) }
        assertTrue(all.isEmpty(), all.joinToString("\n"))
        assertTrue(CLIENT_CALL_ROWS.size >= 6, "the worked example rows disappeared")
    }

    @Test
    fun rowNamesAreUniquePerFile() {
        val dup = CLIENT_CALL_ROWS.groupBy { it.file to it.name }.filterValues { it.size > 1 }.keys
        assertTrue(dup.isEmpty(), "duplicate recorder rows: $dup")
    }

    @Test
    fun theHttpCallSitesOfEveryClientFunctionAreCoveredByItsOwnRows() = runTest {
        val sites = sitesByFunction()
        val v = completenessViolations(sites, CLIENT_CALL_ROWS) + paddingViolations(sites, recordAll())
        assertTrue(v.isEmpty(), v.joinToString("\n"))
    }

    @Test
    fun everyInlinePathAndQueryFilterIsExercisedByARowOfItsFunction() = runTest {
        val recorded = recordAll()
        val v = tripwireClientFiles().flatMap { staticTargetViolations(it, textOf(it), recorded) }
        assertTrue(v.isEmpty(), v.joinToString("\n"))
        val targets = tripwireClientFiles().sumOf { staticTargets(textOf(it)).size }
        assertTrue(targets >= 60, "only $targets inline paths/filters found: vacuous")
    }

    @Test
    fun theTripwireFileSetIsExactlyTheClientFilesThatCallHttp() {
        // fail-closed por texto crudo en TODA la produccion (no solo data/): cada fichero que toca el cliente Ktor es un cliente
        // HTTPCALL o un no-cliente fijado con justificacion y 0 sitios de egreso.
        val touching = productionKotlinFiles().filter { touchesHttpClient.containsMatchIn(textOf(it)) }.toSortedSet()
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
        val v = SITE_EXCLUSIONS.map { it.file }.distinct().flatMap { egressSites(it, textOf(it), SITE_EXCLUSIONS).second }
        assertTrue(v.isEmpty(), v.joinToString("\n"))
        val orphan = SITE_EXCLUSIONS.map { it.file }.filter { it !in tripwireClientFiles() && it !in HTTP_REFERENCE_NON_CLIENTS }
        assertTrue(orphan.isEmpty(), "exclusions for files that are not client files: $orphan")
    }

    @Test
    fun everyPublicEntryPointSignatureOfEveryClientIsCoveredByARow() {
        val v = tripwireClientFiles().flatMap { f -> entryPointViolationsFor(f, entryPoints(f)) }
        assertTrue(v.isEmpty(), v.joinToString("\n"))
        val blankWhy = NON_HTTP_MEMBERS.flatMap { (f, m) -> m.filterValues { it.isBlank() }.keys.map { "$f#$it" } }
        assertTrue(blankWhy.isEmpty(), "non-HTTP members without justification: $blankWhy")
        val orphan = NON_HTTP_MEMBERS.keys - tripwireClientFiles().toSet()
        assertTrue(orphan.isEmpty(), "entries for files that are not HTTPCALL clients: $orphan")
    }

    // ------------------------------------------------------------------------- controles negativos (cada regla DISPARA)

    private val auditRow = CLIENT_CALL_ROWS.first { it.name == "getMovementDetail" }

    @Test
    fun negativeControlUnlistedCallFails() = runTest {
        // el cliente llama a una ruta que el backend NO sirve: la fila la declara bien pero no esta en routes.combined
        val ghost = CallRow(auditRow.file, "ghost", get("/api/v1/admin/__not_routed")) { client.get("http://backend.test/api/v1/admin/__not_routed") }
        val v = rowViolations(ghost, ghost.record(), served)
        assertTrue(v.any { "is not a route in routes.combined.txt" in it }, v.toString())
    }

    @Test
    fun negativeControlWrongPathFails() = runTest {
        val wrong = CallRow(auditRow.file, "getMovementDetail", get("/api/v1/admin/roles"), body = auditRow.body)
        val v = rowViolations(wrong, wrong.record(), served)
        assertTrue(v.any { "the row declares" in it }, v.toString())
    }

    @Test
    fun negativeControlMethodMismatchFails() = runTest {
        // la fila declara POST pero el cliente envia GET
        val wrong = CallRow(auditRow.file, "getMovementDetail", post("/api/v1/admin/auditoria"), body = auditRow.body)
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
        val sites = sitesByFunction()
        assertEquals(emptyList(), completenessViolations(sites, CLIENT_CALL_ROWS))
        // un metodo nuevo en un fichero ya completo (AuditApi): su funcion tiene un sitio y ninguna fila
        val grown = sites + (auditRow.file to sites.getValue(auditRow.file) + ("getGhosts" to 1))
        val v = completenessViolations(grown, CLIENT_CALL_ROWS)
        assertTrue(v.any { "AuditApi.kt: 3 HTTP call sites but 2 recorder rows" in it }, v.toString())
        assertTrue(v.any { "AuditApi.kt#getGhosts: 1 HTTP call sites in this function but 0" in it }, v.toString())
        // un fichero HTTP nuevo, sin filas
        val extra = sites + ("data/src/commonMain/NewApi.kt" to mapOf("f" to 1))
        assertTrue(completenessViolations(extra, CLIENT_CALL_ROWS).any { "NewApi.kt" in it })
        // una fila de menos (borrada) falla; una fila movida a otra funcion (relleno) falla aunque el total por fichero cuadre
        assertTrue(completenessViolations(sites, CLIENT_CALL_ROWS.drop(1)).any { "AuditApi.kt" in it })
        val tags = CLIENT_CALL_ROWS.filter { it.name == "upsertTag(update)" }.single()
        val moved = CLIENT_CALL_ROWS - tags + CallRow(tags.file, "deleteTag(copy)", tags.expect, body = tags.body)
        val m = completenessViolations(sites, moved)
        assertTrue(m.any { "KtorTagsRepositoryImpl.kt#upsertTag: 2 HTTP call sites in this function but 1" in it }, m.toString())
        assertTrue(m.none { "KtorTagsRepositoryImpl.kt: " in it }, "the per-file total still matches: only the per-function key catches it")
    }

    // ------------------------------- evasiones de los validadores (rondas 4 y 5): cada sonda FALLA

    private val newsFile = "data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/KtorNewsRepositoryImpl.kt"
    private val marketFile = "data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/KtorMarketCatalogRepositoryImpl.kt"
    private val rankingsFile = "data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/KtorRankingsRepositoryImpl.kt"
    private val forumFile = "data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/KtorForumRepositoryImpl.kt"

    /** Texto real del fichero con una sonda anadida al final. */
    private fun probed(file: String, probe: String) = textOf(file) + "\n" + probe + "\n"

    private fun sitesWithProbe(file: String, probe: String) = egressSites(file, probed(file, probe), SITE_EXCLUSIONS)

    private fun completenessWithProbe(file: String, probe: String): List<String> {
        val (byFun, exclusionViolations) = egressSitesByFunction(file, probed(file, probe), SITE_EXCLUSIONS)
        return exclusionViolations + completenessViolations(sitesByFunction() + (file to byFun), CLIENT_CALL_ROWS)
    }

    @Test
    fun negativeControlMultiLineCallIsCountedAndFails() {
        // sonda news_s2 del validador: receptor y verbo en lineas distintas
        val v = completenessWithProbe(newsFile, "    suspend fun ghost() = client\n        .get(\"\${newsUrl()}/__ghost\")")
        assertTrue(v.any { "KtorNewsRepositoryImpl.kt: 9 HTTP call sites but 8 recorder rows" in it }, v.toString())
        assertTrue(v.any { "KtorNewsRepositoryImpl.kt#ghost: 1 HTTP call sites in this function but 0" in it }, v.toString())
        assertTrue(touchesHttpClient.containsMatchIn("val x = client\n    .post(u)"), "multi-line receiver not seen by the file-set tripwire")
    }

    @Test
    fun negativeControlWithClientScopeIsCountedAndFails() {
        // sonda news_s3 del validador: verbo sin receptor dentro de with(client) / apply
        val v = completenessWithProbe(newsFile, "    suspend fun ghost() = with(client) { get(\"\${newsUrl()}/__ghost\") }")
        assertTrue(v.any { "KtorNewsRepositoryImpl.kt: 9 HTTP call sites but 8 recorder rows" in it }, v.toString())
        val applied = completenessWithProbe(newsFile, "    suspend fun ghost() = client.apply {\n        post<Unit>(\"x\")\n    }")
        assertTrue(applied.any { "KtorNewsRepositoryImpl.kt: 9 HTTP call sites" in it }, applied.toString())
        // un fichero nuevo que solo llama dentro de with(client) igual necesita importar el verbo: el tripwire de ficheros lo ve
        assertTrue(touchesHttpClient.containsMatchIn("import io.ktor.client.request.get\nfun f() = with(c) { get(u) }"))
    }

    @Test
    fun negativeControlBuilderLambdaFormIsCountedAndFails() {
        // sonda news_r5_lambda_overload del validador (ronda 5): `client.delete { url(..) }` sin parentesis tras el verbo
        val probe = "    suspend fun deleteNews(newsId: String, hard: Boolean) = adapter.safeCall { client.delete { url(\"\${newsUrl()}/__ghost/\$newsId\") } }"
        val v = completenessWithProbe(newsFile, probe)
        assertTrue(v.any { "KtorNewsRepositoryImpl.kt: 9 HTTP call sites but 8 recorder rows" in it }, v.toString())
        // misma funcion que la fila existente: el sitio nuevo es de deleteNews, que ya tenia su unica fila
        assertTrue(v.any { "KtorNewsRepositoryImpl.kt#deleteNews: 2 HTTP call sites in this function but 1" in it }, v.toString())
        // todas las formas constructoras cuentan, en texto sintetico
        val forms = listOf("client.get { url(u) }", "client.request {\n url(u) }", "client.prepareRequest { }", "httpClient.preparePost{ }", "with(client) { delete { } }")
        forms.forEach { f -> assertEquals(1, egressSites("X.kt", f, emptyList()).first, "not counted: $f") }
        forms.take(4).forEach { f -> assertTrue(touchesHttpClient.containsMatchIn(f), "file-set tripwire misses: $f") }
    }

    @Test
    fun negativeControlHelperReuseByANewPublicMethodFails() {
        // sonda market_s4 del validador: getGhosts() reutiliza el ayudante getList -> el CONTEO no cambia (por eso evadia)...
        val probe = "    suspend fun getGhosts(): List<PackDto> = getList<PackDto, PacksResponse>(\"\$MARKET/__ghost\")"
        assertEquals(sitesIn(marketFile), sitesWithProbe(marketFile, probe).first)
        // ...pero el punto de entrada nuevo no tiene fila: la cobertura por reflexion FALLA
        val real = entryPoints(marketFile)
        assertEquals(emptyList(), entryPointViolationsFor(marketFile, real))
        val v = entryPointViolationsFor(marketFile, real + "getGhosts()")
        assertTrue(v.any { "#getGhosts(): public method without a recorder row" in it }, v.toString())
        // otra invocacion del ayudante privado `get` de Rankings no la absorbe su exclusion fijada (5): falla la exclusion
        val r = sitesWithProbe(rankingsFile, "    suspend fun ghost() = get<RankingPageDto>(\"/__ghost\")").second
        assertTrue(r.any { "matches 6 times but is pinned to 5" in it }, r.toString())
        // y una llamada con receptor nunca la absorbe una exclusion (lookbehind): sube los sitios
        assertEquals(sitesIn(rankingsFile) + 1, sitesWithProbe(rankingsFile, "    suspend fun ghost() = client.get<RankingPageDto>(\"/x\")").first)
    }

    @Test
    fun negativeControlNewOverloadOfACoveredNameFails() {
        // sonda market_r5_overload del validador: getServices(ghost: Boolean) sobre el ayudante. Mismo nombre, firma nueva.
        val real = entryPoints(marketFile)
        assertTrue("getServices()" in real, real.toString())
        val v = entryPointViolationsFor(marketFile, real + "getServices(boolean)")
        assertTrue(v.any { "#getServices: overloaded name" in it }, v.toString())
        assertTrue(v.any { "#getServices(): public method without a recorder row" in it }, v.toString())
        // la reflexion distingue sobrecargas reales (clase de ensayo)
        val fixture = entryPointSignatures(GhostFixtureClient::class.java, null, emptyList())
        assertTrue(setOf("covered()", "covered(String)").all { it in fixture }, fixture.toString())
    }

    @Test
    fun negativeControlInnerClassAndCompanionEntryPointsFail() {
        // sonda market_r5_inner del validador: `inner class Ghost { suspend fun ghosts() = .. }` y un companion con cliente
        val entries = entryPointSignatures(GhostFixtureClient::class.java, null, emptyList())
        assertTrue("Ghost.ghosts()" in entries, entries.toString())
        assertTrue("Companion.viaCompanion(HttpClient)" in entries, entries.toString())
        assertTrue(entries.none { it.startsWith("PureDto.") }, "a nested class that cannot reach the client is not an entry point: $entries")
        val v = entryPointViolations("Fixture.kt", entries, covered = setOf("covered(String)", "covered()"), nonHttp = emptySet())
        assertTrue(v.any { "#Ghost.ghosts(): public method without" in it }, v.toString())
        assertTrue(v.any { "#Companion.viaCompanion(HttpClient): public method without" in it }, v.toString())
    }

    @Test
    fun negativeControlNewBranchWithAnInlinePathOrFilterFails() = runTest {
        // sonda market_r5_branch del validador: getServices elige `"$MARKET/__ghost"` en una rama; 0 sitios nuevos
        val recorded = recordAll(CLIENT_CALL_ROWS.filter { it.file == marketFile || it.file == forumFile })
        val text = textOf(marketFile)
        assertEquals(emptyList(), staticTargetViolations(marketFile, text, recorded))
        val branched = text.replace(
            "allPages<ServiceDto, ServicesResponse>(\"\$MARKET/servicios\")",
            "allPages<ServiceDto, ServicesResponse>(if (appConfig.baseUrl.endsWith(\"/ghost\")) \"\$MARKET/__ghost\" else \"\$MARKET/servicios\")",
        )
        assertTrue(branched != text, "probe did not apply")
        assertEquals(sitesIn(marketFile), sitesIn(marketFile, branched), "a helper branch adds no egress site")
        val v = staticTargetViolations(marketFile, branched, recorded)
        assertTrue(v.any { "#getServices: \"\$MARKET/__ghost\" is not exercised" in it }, v.toString())
        // filtro de consulta nuevo (o en una rama que ninguna fila ejecuta) en una funcion ya cubierta
        val f = textOf(forumFile).replaceFirst("parameter(\"limit\", pageSize)", "parameter(\"limit\", pageSize); parameter(\"ghost\", 1)")
        assertTrue(f != textOf(forumFile), "probe did not apply")
        val q = staticTargetViolations(forumFile, f, recorded)
        assertTrue(q.any { "#listThreads: parameter(\"ghost\" is not exercised" in it }, q.toString())
    }

    @Test
    fun negativeControlSwappedIdsAndDroppedOrRenamedQueryFiltersFail() = runTest {
        // ids cambiados de sitio: deleteQuestionResource(q=ID0, r=ID1) enviando /preguntas/ID1/recursos/ID0
        val q = "/api/v1/admin/preguntas"
        val swapped = CallRow(auditRow.file, "swapped", delete("$q/{}/recursos/{}")) { client.delete("http://backend.test$q/ID1/recursos/ID0") }
        assertTrue(rowViolations(swapped, swapped.record(), served).any { "the row declares" in it })
        val ordered = CallRow(auditRow.file, "ordered", delete("$q/{}/recursos/{}")) { client.delete("http://backend.test$q/ID0/recursos/ID1") }
        assertEquals(emptyList(), rowViolations(ordered, ordered.record(), served))
        assertTrue(pathMatches("/a/{1}/b/{0}", "/a/{1}/b/{0}") && !pathMatches("/a/{}/b/{}", "/a/{1}/b/{0}") && !pathMatches("/a/{0}/b/{1}", "/a/{1}/b/{0}"))
        // filtro renombrado (q -> search) y filtro quitado: los NOMBRES de la consulta se graban y se comparan con la fila
        val renamed = CallRow(auditRow.file, "renamed", get("$q?q")) { client.get("http://backend.test$q?search=x") }
        assertTrue(rowViolations(renamed, renamed.record(), served).any { "the row declares" in it })
        val dropped = CallRow(auditRow.file, "dropped", get("$q?limit&offset")) { client.get("http://backend.test$q?limit=1") }
        assertTrue(rowViolations(dropped, dropped.record(), served).any { "the row declares" in it })
        val same = CallRow(auditRow.file, "same", get("$q?limit&offset")) { client.get("http://backend.test$q?offset=0&limit=1") }
        assertEquals(emptyList(), rowViolations(same, same.record(), served))
    }

    @Test
    fun negativeControlCountersDetectEveryImbalance() = runTest {
        val sites = sitesByFunction()
        val recorded = recordAll()
        val ok = counters(sites, CLIENT_CALL_ROWS, recorded, 0, 0)
        assertEquals(ok.sites, ok.rows)
        // sitio nuevo sin fila: TOTAL sube y UNMAPPED > 0
        val (newsByFun, _) = egressSitesByFunction(newsFile, probed(newsFile, "    suspend fun ghost() = client.get { url(\"x\") }"), SITE_EXCLUSIONS)
        val grown = counters(sites + (newsFile to newsByFun), CLIENT_CALL_ROWS, recorded, 0, 0)
        assertTrue(grown.sites == ok.sites + 1 && grown.unmapped == 1, grown.line())
        // fila de relleno movida a otra funcion: totales iguales pero UNMAPPED = 1 y UNKNOWN = 1 (fila sin sitio)
        val tags = CLIENT_CALL_ROWS.single { it.name == "upsertTag(update)" }
        val moved = CLIENT_CALL_ROWS - tags + CallRow(tags.file, "deleteTag(copy)", tags.expect, body = tags.body)
        val m = counters(sites, moved, recorded - tags, 0, 0)
        assertTrue(m.sites == m.rows && m.unmapped == 1 && m.unknown >= 1, m.line())
        // llamada grabada que no es ruta servida / secuencia distinta de la declarada: UNKNOWN > 0
        val bad = recorded + (auditRow to listOf(Call("GET", "/__ghost")))
        assertTrue(counters(sites, CLIENT_CALL_ROWS, bad, 0, 0).unknown >= 2)
        // punto de entrada sin fila y ruta en linea sin ejecutar suman a UNMAPPED
        assertEquals(ok.unmapped + 2, counters(sites, CLIENT_CALL_ROWS, recorded, 1, 1).unmapped)
    }

    @Test
    fun negativeControlReflectionSeesEveryNewEntryPointWhateverItsShape() {
        val methods = entryPointSignatures(GhostFixtureClient::class.java, null, emptyList())
        val top = methods.filter { '.' !in it.substringBefore('(') }.toSet()
        assertEquals(setOf("covered()", "covered(String)", "ghostWith()", "ghostMultiLine()", "ghostHelper()"), top)
        val v = entryPointViolations("Fixture.kt", top, covered = setOf("covered()", "covered(String)"), nonHttp = emptySet())
        assertEquals(3, v.size, v.toString())
        // y las entradas obsoletas tambien fallan
        val o = entryPointViolations("F.kt", top, setOf("covered()", "gone"), setOf("ghostHelper", "x", "covered()"))
        listOf("#gone: a row covers", "#x: a stale non-HTTP", "#ghostMultiLine(): public method", "#covered(): non-HTTP entry that also has a row")
            .forEach { k -> assertTrue(o.any { k in it }, "$k not in $o") }
    }

    /** Clase de ensayo: la reflexion ve TODO punto de entrada publico (sobrecarga, inner, companion con cliente). */
    @Suppress("unused")
    private class GhostFixtureClient(private val client: HttpClient) {
        suspend fun covered() = client.get("http://backend.test/api/v1/admin/auditoria")
        suspend fun covered(id: String) = fetch("http://backend.test/__ghost/$id")
        suspend fun ghostWith() = with(client) { get("http://backend.test/__ghost") }
        suspend fun ghostMultiLine() = client
            .get("http://backend.test/__ghost")
        suspend fun ghostHelper() = fetch("http://backend.test/__ghost")
        private suspend fun fetch(u: String) = client.get(u)

        inner class Ghost {
            suspend fun ghosts() = fetch("http://backend.test/__ghost")
        }

        data class PureDto(val id: String)

        companion object {
            suspend fun viaCompanion(c: HttpClient) = c.get("http://backend.test/__ghost")
        }
    }
}
