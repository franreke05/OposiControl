package com.oposicontrol.backend

import com.oposicontrol.backend.openapi.OpenApiDocs
import com.oposicontrol.backend.openapi.SchemaChecker
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.net.http.HttpRequest
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SLICE 7: D6-01 (`GET /api/v1/app/wallet`) y D6-02 (`GET /api/v1/app/wallet/movimientos`) de punta a punta (ruta -> servicio ->
 * pasarela -> transporte real) contra un upstream de pruebas que graba cada peticion. El cable se valida contra el OpenAPI
 * CONGELADO con [SchemaChecker]. Solo lecturas: el doble comprueba que TODA peticion saliente es un GET.
 */
class WalletRoutesTest {

    private val docs = OpenApiDocs.locate()
    private val checker = SchemaChecker(docs)

    // 2026-10-08 es jueves; Madrid va en CEST (UTC+2) hasta el 25-oct: hoy empieza el 07 22:00Z, la semana el 04 22:00Z, el mes el 09-30 22:00Z.
    private val now = Instant.parse("2026-10-08T12:00:00Z")
    private val other = "99999999-9999-4999-8999-999999999999"
    private val json = Json { ignoreUnknownKeys = true }
    private var status: String? = "activa"
    private var profileExists = true

    private val resolver = CanonicalIdentityResolver(
        verifySubject = { if (it == TEST_TOKEN) TEST_UID else error("firma invalida") },
        directory = { id -> if (profileExists) CanonicalUserIdentity(id, AccountStatus.fromWire(status), true) else null },
    )

    private data class Entry(
        val id: String,
        val at: String,
        val tipo: String = "recarga",
        val cantidad: Int = 10,
        val anterior: Int? = 0,
        val resultante: Int = 10,
        val refTipo: String? = null,
        val refId: String? = null,
        val descripcion: String? = null,
    ) {
        val instant: Instant get() = OffsetDateTime.parse(at).toInstant()
        val json: String
            get() = """{"id":"$id","tipo":"$tipo","cantidad":$cantidad,"saldo_anterior":${anterior ?: "null"},"saldo_resultante":$resultante,""" +
                """"referencia_tipo":${refTipo?.let { "\"$it\"" } ?: "null"},"referencia_id":${refId?.let { "\"$it\"" } ?: "null"},""" +
                """"descripcion":${descripcion?.let { "\"$it\"" } ?: "null"},"creado_en":"$at"}"""
    }

    private fun uuid(n: Int) = "dddddddd-dddd-4ddd-8ddd-%012d".format(n)

    /** Upstream de pruebas con la semantica de PostgREST que usa la pasarela: filtro por usuario/tipo, keyset descendente y limit. */
    private class Upstream {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val urls: List<String> get() = sent.map { URLDecoder.decode(it.uri().toString(), Charsets.UTF_8) }
        var balance = "[]"
        var ledger: List<Entry> = emptyList()
        var activations = "[]"
        var failOn: String? = null

        fun respond(rawUri: String): V1StubResponse {
            val uri = URLDecoder.decode(rawUri, Charsets.UTF_8)
            if (failOn != null && uri.contains(failOn!!)) return V1StubResponse(500, """{"message":"boom"}""")
            val body = when {
                uri.contains("/rest/v1/carteras_usuario") -> balance
                uri.contains("/rest/v1/activaciones_servicio_usuario") -> activations
                uri.contains("/rest/v1/transacciones_cartera") -> ledgerRows(uri)
                else -> error("peticion inesperada al upstream: $uri")
            }
            return V1StubResponse(200, body)
        }

        private fun ledgerRows(uri: String): String {
            val query = uri.substringAfter('?')
            var rows = ledger.asSequence()
            Regex("(?:^|&)tipo=eq\\.([a-z_]+)").find(query)?.let { m -> rows = rows.filter { it.tipo == m.groupValues[1] } }
            Regex("(?:^|&)tipo=in\\.\\(([a-z_,]+)\\)").find(query)?.let { m -> val set = m.groupValues[1].split(','); rows = rows.filter { it.tipo in set } }
            if (query.contains("&cantidad=gt.0")) rows = rows.filter { it.cantidad > 0 }
            Regex("creado_en=gte\\.([^&]+)").find(query)?.let { m -> val since = Instant.parse(m.groupValues[1]); rows = rows.filter { !it.instant.isBefore(since) } }
            Regex("&or=\\(creado_en\\.lt\\.([^,]+),and\\(creado_en\\.eq\\.([^,]+),id\\.lt\\.([^)]+)\\)\\)").find(query)?.let { m ->
                val at = OffsetDateTime.parse(m.groupValues[1]).toInstant()
                val id = m.groupValues[3]
                rows = rows.filter { it.instant.isBefore(at) || (it.instant == at && it.id < id) }
            }
            val limit = Regex("&limit=(\\d+)").find(query)!!.groupValues[1].toInt()
            val sorted = rows.sortedWith(compareByDescending<Entry> { it.instant }.thenByDescending { it.id }).take(limit).toList()
            return "[" + sorted.joinToString(",") { it.json } + "]"
        }
    }

    private val upstream = Upstream()

    private fun Application.walletModule(limit: suspend (CanonicalUserId) -> Unit = {}) {
        install(ContentNegotiation) { json(testJson) }
        installRequestCorrelation()
        installBackendStatusPages()
        val transport = SupabaseRequestTransport.forTesting(
            "https://stub.supabase.test",
            "stub-key",
            V1StubHttpClient({ upstream.sent += it }, upstream::respond),
        )
        val gateway = WalletGateway(upstream = { path -> transport.get(path) })
        routing { walletRoutes(WalletRuntime(resolver, WalletService(gateway) { now }, limit)) }
    }

    private suspend fun HttpClient.getAs(path: String, token: String? = TEST_TOKEN): HttpResponse =
        get(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }

    private fun obj(body: String): JsonObject = json.parseToJsonElement(body).jsonObject

    private fun code(body: String): String = obj(body)["code"]!!.jsonPrimitive.content

    private fun assertConforms(contractId: String, body: String) {
        val op = docs.operations().first { it.contractId == contractId }
        val response = docs.responses(op).getValue("200")
        val schema = ((response.value as Map<*, *>)["content"] as Map<*, *>).let { (it["application/json"] as Map<*, *>)["schema"] }
        val problems = mutableListOf<String>()
        checker.validate(OpenApiDocs.Node(schema, response.file), json.parseToJsonElement(body), "$", problems)
        assertTrue(problems.isEmpty(), "$contractId does not conform to the frozen OpenAPI:\n${problems.joinToString("\n")}\n$body")
    }

    private fun assertOwnWalletOnly() {
        assertTrue(upstream.sent.isNotEmpty())
        assertTrue(upstream.sent.all { it.method() == "GET" }, "cartera es de SOLO lectura: ${upstream.sent.map { it.method() }}")
        upstream.urls.forEach { url ->
            assertTrue(url.contains("usuario_id=eq.$TEST_UID"), "toda lectura va acotada al sub del JWT: $url")
            assertFalse(url.contains(other), "el uuid ajeno nunca sale: $url")
        }
    }

    private val summaryPath = "/api/v1/app/wallet"
    private val listPath = "/api/v1/app/wallet/movimientos"

    // =================================================================== D6-01

    @Test
    fun walletReadsRejectUnknownQueryParametersLikeTheCatalog() = testApplication {
        application { walletModule() }
        for (path in listOf("/api/v1/app/wallet?foo=1", "/api/v1/app/wallet?usuarioId=dddddddd-dddd-4ddd-8ddd-000000000009", "/api/v1/app/wallet/movimientos?usuario=x")) {
            val response = client.getAs(path)
            assertEquals(HttpStatusCode.BadRequest, response.status, "$path -> ${response.bodyAsText()}")
            assertTrue(response.bodyAsText().contains("UNKNOWN_FIELD"), response.bodyAsText())
        }
        assertTrue(upstream.sent.isEmpty(), "una consulta con parametros desconocidos no debe llegar a la base: ${upstream.urls}")
        assertEquals(HttpStatusCode.OK, client.getAs("/api/v1/app/wallet/movimientos?limit=5&tipo=recarga&origen=compras").status)
    }

    // E2-P02 / FA20: limite por usuario WALLET_READ (60 / 60 s), un solo cubo para D6-01 y D6-02, ANTES de leer nada.
    @Test
    fun walletReadsShareOnePerUserBucketAnd429WithRetryAfterBeforeAnyUpstreamRead() = testApplication {
        val limiter = InMemoryOposiBotsAuthRateLimiter()
        application { walletModule(limiter.subjectLimit(OposiBotsAuthOperation.WALLET_READ)) }
        repeat(OposiBotsAuthOperation.WALLET_READ.subjectLimit) { i ->
            val path = if (i % 2 == 0) "/api/v1/app/wallet" else "/api/v1/app/wallet/movimientos"
            assertTrue(client.getAs(path).status != HttpStatusCode.TooManyRequests, "peticion $i dentro del limite")
        }
        val before = upstream.sent.size
        for (path in listOf("/api/v1/app/wallet", "/api/v1/app/wallet/movimientos")) {
            val response = client.getAs(path)
            assertEquals(HttpStatusCode.TooManyRequests, response.status, path)
            assertEquals("RATE_LIMITED", code(response.bodyAsText()))
            assertTrue(response.headers[HttpHeaders.RetryAfter] != null)
        }
        assertEquals(before, upstream.sent.size, "un limite agotado no paga lecturas upstream")
        assertEquals(60, OposiBotsAuthOperation.WALLET_READ.subjectLimit)
        assertEquals(60L, OposiBotsAuthOperation.WALLET_READ.windowSeconds)
    }

    @Test
    fun summaryReturnsBalanceEarningsRecentsAndConsumablesAndConformsToTheFrozenWire() = testApplication {
        application { walletModule() }
        upstream.balance = """[{"saldo_tickets":42}]"""
        upstream.ledger = listOf(
            Entry(uuid(1), "2026-10-08T09:00:00.123456+00:00", "bonus_racha", 5, 37, 42),
            Entry(uuid(2), "2026-10-07T22:30:00+00:00", "reto_diario", 3, 34, 37), // 00:30 del 8 en Madrid: cuenta como HOY
            Entry(uuid(3), "2026-10-07T21:30:00+00:00", "compra_examen", -10, 44, 34, refTipo = "examen", refId = uuid(900), descripcion = "Examen X"),
            Entry(uuid(4), "2026-10-05T10:00:00+00:00", "referido", 4, 40, 44), // lunes: semana, no hoy
            Entry(uuid(5), "2026-10-02T10:00:00+00:00", "recarga", 100, 0, 100), // mes, no semana
            Entry(uuid(6), "2026-09-30T21:00:00+00:00", "recarga", 7, 0, 7), // 23:00 del 30-sep en Madrid: mes anterior
            Entry(uuid(7), "2026-10-03T10:00:00+00:00", "ajuste", 2, 98, 100), // otros
        )
        upstream.activations = """[
            {"id":"${uuid(50)}","estado":"activa","activa_hasta":"2026-11-01T00:00:00+00:00","usos_disponibles":3,"usos_consumidos":1,"servicios":{"codigo":"potenciador-x2"}},
            {"id":"${uuid(51)}","estado":"usada","activa_hasta":null,"usos_disponibles":1,"usos_consumidos":1,"servicios":{"codigo":"protector-racha"}},
            {"id":"${uuid(52)}","estado":"activa","activa_hasta":"2026-10-01T00:00:00+00:00","usos_disponibles":1,"usos_consumidos":0,"servicios":{"codigo":"potenciador-x2"}}
        ]"""
        val response = client.getAs(summaryPath)
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        assertEquals("private, no-store", response.headers[HttpHeaders.CacheControl])
        assertConforms("D6-01", body)
        val summary = obj(body)
        assertEquals(42, summary["saldo"]!!.jsonPrimitive.content.toInt())
        assertFalse(body.contains("\"data\""), "objeto DESNUDO, sin envoltorio")
        assertNull(summary["recompensas"], "DEC-036: recompensas null (ausente) hasta que se defina")
        assertEquals("2026-10-08T12:00:00Z", summary["actualizadoEn"]!!.jsonPrimitive.content)

        val earnings = summary["ganancias"]!!.jsonObject
        assertEquals(8, earnings["hoy"]!!.jsonPrimitive.content.toInt(), "5 + 3: solo abonos, desde la medianoche de Madrid")
        assertEquals(12, earnings["semana"]!!.jsonPrimitive.content.toInt(), "desde el lunes de Madrid")
        assertEquals(114, earnings["mes"]!!.jsonPrimitive.content.toInt(), "5+3+4+100+2: el abono del 30-sep 23:00 Madrid no entra")
        val byOrigin = earnings["porOrigen"]!!.jsonArray.associate { it.jsonObject["origen"]!!.jsonPrimitive.content to it.jsonObject["cantidad"]!!.jsonPrimitive.content.toInt() }
        assertEquals(mapOf("compras" to 100, "rachas" to 5, "retos" to 3, "invitaciones" to 4, "otros" to 2), byOrigin)

        val recents = summary["recientes"]!!.jsonArray.map { it.jsonObject }
        assertEquals(5, recents.size, "las 5 mas recientes")
        assertEquals(listOf(uuid(1), uuid(2), uuid(3), uuid(4), uuid(7)), recents.map { it["id"]!!.jsonPrimitive.content })
        assertEquals("rachas", recents[0]["origen"]!!.jsonPrimitive.content)
        assertEquals("otros", recents[2]["origen"]!!.jsonPrimitive.content, "compra_examen -> otros")
        assertEquals("examen", recents[2]["referencia"]!!.jsonObject["tipo"]!!.jsonPrimitive.content)
        assertEquals(-10, recents[2]["cantidad"]!!.jsonPrimitive.content.toInt())

        val consumables = summary["consumibles"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("activa", "usada", "caducada"), consumables.map { it["estado"]!!.jsonPrimitive.content }, "una activa vencida se presenta como caducada")
        assertEquals(2, consumables[0]["usosRestantes"]!!.jsonPrimitive.content.toInt())
        assertEquals("potenciador-x2", consumables[0]["servicioCodigo"]!!.jsonPrimitive.content)
        assertEquals("2026-11-01T00:00:00Z", consumables[0]["activaHasta"]!!.jsonPrimitive.content)

        val creditQuery = upstream.urls.first { it.contains("cantidad=gt.0") }
        assertTrue(creditQuery.contains("creado_en=gte.2026-09-30T22:00:00Z"), "la ventana mas ancha es el mes de Madrid: $creditQuery")
        val activationQuery = upstream.urls.single { it.contains("activaciones_servicio_usuario") }
        assertTrue(activationQuery.contains("usado_en.gte.2026-09-08T12:00:00Z"), activationQuery)
        assertFalse(activationQuery.contains("cancelada"), "las canceladas no se piden")
        assertOwnWalletOnly()
    }

    @Test
    fun anEmptyWalletIsAValidZeroSummary() = testApplication {
        application { walletModule() }
        val response = client.getAs(summaryPath)
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        assertConforms("D6-01", body)
        val summary = obj(body)
        assertEquals(0, summary["saldo"]!!.jsonPrimitive.content.toInt(), "sin fila de cartera: saldo 0")
        assertEquals(0, summary["ganancias"]!!.jsonObject["mes"]!!.jsonPrimitive.content.toInt())
        assertTrue(summary["ganancias"]!!.jsonObject["porOrigen"]!!.jsonArray.isEmpty())
        assertTrue(summary["recientes"]!!.jsonArray.isEmpty())
        assertTrue(summary["consumibles"]!!.jsonArray.isEmpty())
        assertOwnWalletOnly()
    }

    @Test
    fun aMonthWithMoreCreditsThanOnePageIsSummedAcrossKeysetPages() = testApplication {
        application { walletModule() }
        upstream.ledger = (1..501).map { n -> Entry(uuid(n), Instant.parse("2026-10-02T00:00:00Z").plusSeconds(n.toLong()).toString(), "recarga", 1, 0, 1) }
        val body = client.getAs(summaryPath).bodyAsText()
        assertEquals(501, obj(body)["ganancias"]!!.jsonObject["mes"]!!.jsonPrimitive.content.toInt())
        val creditCalls = upstream.urls.filter { it.contains("cantidad=gt.0") }
        assertEquals(2, creditCalls.size)
        assertTrue(creditCalls[0].contains("&limit=501") && !creditCalls[0].contains("&or=("), creditCalls[0])
        assertTrue(creditCalls[1].contains("&or=(creado_en.lt."), "la segunda pagina sigue el keyset: ${creditCalls[1]}")
    }

    @Test
    fun aMonthTooBigToSumFailsClosedInsteadOfShowingAShortTotal() = testApplication {
        application { walletModule() }
        upstream.ledger = (1..(WalletService.EARNINGS_PAGE_ROWS * WalletService.EARNINGS_PAGES_MAX + 1)).map { n ->
            Entry(uuid(n), Instant.parse("2026-10-02T00:00:00Z").plusSeconds(n.toLong()).toString(), "recarga", 1, 0, 1)
        }
        val response = client.getAs(summaryPath)
        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertEquals("UPSTREAM_CONTRACT_ERROR", code(response.bodyAsText()))
    }

    @Test
    fun aNullSaldoAnteriorIsDerivedFromSaldoResultanteMinusCantidadAndStillConforms() = testApplication {
        application { walletModule() }
        upstream.ledger = listOf(
            Entry(uuid(1), "2026-10-08T09:00:00+00:00", "recarga", 30, null, 50),
            Entry(uuid(2), "2026-10-07T09:00:00+00:00", "gasto", -5, null, 20),
        )
        val summaryBody = client.getAs(summaryPath).bodyAsText()
        assertConforms("D6-01", summaryBody)
        val recents = obj(summaryBody)["recientes"]!!.jsonArray.map { it.jsonObject }
        assertEquals(20, recents[0]["saldoAnterior"]!!.jsonPrimitive.content.toInt(), "50 - 30")
        assertEquals(25, recents[1]["saldoAnterior"]!!.jsonPrimitive.content.toInt(), "20 - (-5)")
        val listBody = client.getAs(listPath).bodyAsText()
        assertConforms("D6-02", listBody)
        assertEquals(20, obj(listBody)["items"]!!.jsonArray[0].jsonObject["saldoAnterior"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun aStoredSaldoAnteriorIsTrustedAndANullOneThatCannotBeDerivedFailsClosed() = testApplication {
        application { walletModule() }
        upstream.ledger = listOf(Entry(uuid(1), "2026-10-08T09:00:00+00:00", "recarga", 10, 3, 13))
        assertEquals(3, obj(client.getAs(listPath).bodyAsText())["items"]!!.jsonArray[0].jsonObject["saldoAnterior"]!!.jsonPrimitive.content.toInt())
        // resultante 5 con cantidad 10 y anterior nulo daria -5: dato corrupto, nunca un 0 inventado.
        upstream.ledger = listOf(Entry(uuid(2), "2026-10-08T09:00:00+00:00", "recarga", 10, null, 5))
        listOf(listPath, summaryPath).forEach {
            val response = client.getAs(it)
            assertEquals(HttpStatusCode.BadGateway, response.status, it)
            assertEquals("UPSTREAM_CONTRACT_ERROR", code(response.bodyAsText()), it)
        }
    }

    // =================================================================== D6-02

    @Test
    fun movementsListConformsAndMapsEveryLedgerTypeToItsOrigin() = testApplication {
        application { walletModule() }
        val tipos = WalletTipo.entries.map { it.wire }
        upstream.ledger = tipos.mapIndexed { i, tipo -> Entry(uuid(i + 1), "2026-10-0${(i % 7) + 1}T10:00:00+00:00", tipo, 1, 0, 1) }
        val response = client.getAs("$listPath?limit=50")
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        assertEquals("private, no-store", response.headers[HttpHeaders.CacheControl])
        assertConforms("D6-02", body)
        val items = obj(body)["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(tipos.size, items.size)
        val origins = items.associate { it["tipo"]!!.jsonPrimitive.content to it["origen"]!!.jsonPrimitive.content }
        assertEquals(
            mapOf(
                "recarga" to "compras", "pack_tickets" to "compras", "gasto" to "otros", "compra_examen" to "otros", "compra_servicio" to "otros",
                "ajuste" to "otros", "devolucion" to "otros", "regalo" to "recompensas", "reto_diario" to "retos", "bonus_racha" to "rachas",
                "referido" to "invitaciones", "reporte_validado" to "opositester", "potenciador" to "otros", "protector" to "otros",
            ),
            origins,
        )
        assertFalse(body.contains("idempotency") || body.contains("metadatos") || body.contains("compra_id"), "columnas internas fuera del cable")
        assertOwnWalletOnly()
    }

    @Test
    fun theReferenceIsNullWithoutOneAndKeepsTypeAndIdWhenPresent() = testApplication {
        application { walletModule() }
        upstream.ledger = listOf(
            Entry(uuid(1), "2026-10-08T09:00:00+00:00", "compra_examen", -5, 15, 10, refTipo = "examen", refId = uuid(901), descripcion = "Compra"),
            Entry(uuid(2), "2026-10-07T09:00:00+00:00", "recarga", 15, 0, 15),
        )
        val items = obj(client.getAs(listPath).bodyAsText())["items"]!!.jsonArray.map { it.jsonObject }
        val ref = items[0]["referencia"]!!.jsonObject
        assertEquals("examen", ref["tipo"]!!.jsonPrimitive.content)
        assertEquals(uuid(901), ref["id"]!!.jsonPrimitive.content)
        assertEquals("Compra", items[0]["descripcion"]!!.jsonPrimitive.content)
        assertNull(items[1]["referencia"])
        assertNull(items[1]["descripcion"])
    }

    @Test
    fun theCursorRoundTripsThroughEveryPageWithoutGapsOrRepeatsEvenOnATie() = testApplication {
        application { walletModule() }
        // Tres con el MISMO instante (el id desempata) y microsegundos en otro: la clave del cursor es exacta.
        upstream.ledger = listOf(
            Entry(uuid(1), "2026-10-08T09:00:00.654321+00:00"),
            Entry(uuid(2), "2026-10-07T09:00:00+00:00"),
            Entry(uuid(3), "2026-10-07T09:00:00+00:00"),
            Entry(uuid(4), "2026-10-07T09:00:00+00:00"),
            Entry(uuid(5), "2026-10-01T09:00:00+00:00"),
        )
        val seen = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val response = client.getAs("$listPath?limit=2" + (cursor?.let { "&cursor=$it" } ?: ""))
            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.OK, response.status, body)
            assertConforms("D6-02", body)
            seen += obj(body)["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
            cursor = obj(body)["nextCursor"]?.jsonPrimitive?.content
            pages += 1
        } while (cursor != null && pages < 10)
        assertEquals(listOf(uuid(1), uuid(4), uuid(3), uuid(2), uuid(5)), seen, "creado_en desc, id desc, sin huecos ni repetidos")
        assertEquals(3, pages)
        val keyset = upstream.urls.filter { it.contains("transacciones_cartera") }[1]
        assertTrue(keyset.contains("&or=(creado_en.lt.2026-10-07T09:00:00+00:00,and(creado_en.eq.2026-10-07T09:00:00+00:00,id.lt.${uuid(4)}))"), keyset)
        assertTrue(upstream.urls.filter { it.contains("transacciones_cartera") }.all { it.contains("&limit=3") }, "limit + 1 para saber si hay mas")
        assertOwnWalletOnly()
    }

    @Test
    fun tipoAndOrigenFiltersNarrowTheLedgerAndRideInTheCursor() = testApplication {
        application { walletModule() }
        upstream.ledger = listOf(
            Entry(uuid(1), "2026-10-08T09:00:00+00:00", "recarga"),
            Entry(uuid(2), "2026-10-07T09:00:00+00:00", "pack_tickets"),
            Entry(uuid(3), "2026-10-06T09:00:00+00:00", "bonus_racha"),
            Entry(uuid(4), "2026-10-05T09:00:00+00:00", "recarga"),
        )
        fun ids(body: String) = obj(body)["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf(uuid(1), uuid(4)), ids(client.getAs("$listPath?tipo=recarga").bodyAsText()))
        assertEquals(listOf(uuid(1), uuid(2), uuid(4)), ids(client.getAs("$listPath?origen=compras").bodyAsText()))
        assertEquals(listOf(uuid(3)), ids(client.getAs("$listPath?origen=rachas").bodyAsText()))
        assertEquals(listOf(uuid(2)), ids(client.getAs("$listPath?origen=compras&tipo=pack_tickets").bodyAsText()))
        assertTrue(ids(client.getAs("$listPath?origen=compras&tipo=bonus_racha").bodyAsText()).isEmpty(), "tipo y origen se combinan con AND")
        val inQuery = upstream.urls.first { it.contains("tipo=in.(") }
        assertTrue(inQuery.contains("tipo=in.(recarga,pack_tickets)"), inQuery)
        // El cursor de un filtro no vale con otro filtro (ni sin filtro).
        val first = obj(client.getAs("$listPath?tipo=recarga&limit=1").bodyAsText())
        val cursor = first["nextCursor"]!!.jsonPrimitive.content
        assertEquals(listOf(uuid(4)), ids(client.getAs("$listPath?tipo=recarga&limit=1&cursor=$cursor").bodyAsText()))
        listOf("$listPath?limit=1&cursor=$cursor", "$listPath?tipo=pack_tickets&limit=1&cursor=$cursor", "$listPath?origen=compras&tipo=recarga&limit=1&cursor=$cursor").forEach {
            val response = client.getAs(it)
            assertEquals(HttpStatusCode.BadRequest, response.status, it)
            assertEquals("INVALID_CURSOR", code(response.bodyAsText()), it)
        }
    }

    @Test
    fun unknownFiltersAre400InvalidFilterAndNeverTouchTheUpstream() = testApplication {
        application { walletModule() }
        listOf("tipo=nope", "tipo=", "tipo=RECARGA", "tipo=recarga%27", "origen=otros", "origen=nope", "origen=", "origen=Compras", "tipo=recarga&origen=otros").forEach {
            val response = client.getAs("$listPath?$it")
            assertEquals(HttpStatusCode.BadRequest, response.status, it)
            assertEquals("INVALID_FILTER", code(response.bodyAsText()), it)
        }
        val issues = obj(client.getAs("$listPath?tipo=nope&origen=nope").bodyAsText())["details"]!!.jsonObject["issues"]!!.jsonArray
        assertEquals(setOf("tipo", "origen"), issues.map { it.jsonObject["field"]!!.jsonPrimitive.content }.toSet())
        assertTrue(upstream.sent.isEmpty())
    }

    @Test
    fun anInvalidLimitIs400ValidationAndALargeOneIsCappedAt50() = testApplication {
        application { walletModule() }
        listOf("limit=0", "limit=-3", "limit=abc", "limit=1.5").forEach {
            val response = client.getAs("$listPath?$it")
            assertEquals(HttpStatusCode.BadRequest, response.status, it)
            assertEquals("VALIDATION", code(response.bodyAsText()), it)
            assertEquals("limit", obj(response.bodyAsText())["details"]!!.jsonObject["issues"]!!.jsonArray[0].jsonObject["field"]!!.jsonPrimitive.content)
        }
        assertTrue(upstream.sent.isEmpty())
        assertEquals(HttpStatusCode.OK, client.getAs("$listPath?limit=500").status)
        assertTrue(upstream.urls.single().contains("&limit=51"), "limit acotado a 50 (+1): ${upstream.urls}")
        client.getAs(listPath)
        assertTrue(upstream.urls.last().contains("&limit=21"), "limit por defecto 20 (+1)")
    }

    @Test
    fun garbageOrForgedCursorsAre400InvalidCursorAndNeverTouchTheUpstream() = testApplication {
        application { walletModule() }
        val scope = CursorScope(listOf("creado_en desc", "id desc"), mapOf("tipo" to null, "origen" to null))
        val forged = listOf(
            PageCursorCodec.encode(listOf(JsonPrimitive("2026-10-07T09:00:00+00:00)),x"), JsonPrimitive(uuid(1))), scope.fingerprint()),
            PageCursorCodec.encode(listOf(JsonPrimitive("2026-10-07T09:00:00+00:00"), JsonPrimitive("1) or (1=1")), scope.fingerprint()),
            PageCursorCodec.encode(listOf(JsonPrimitive("2026-10-07T09:00:00+00:00")), scope.fingerprint()),
        )
        (listOf("not-a-cursor", "A".repeat(3000)) + forged).forEach {
            val response = client.getAs("$listPath?cursor=$it")
            assertEquals(HttpStatusCode.BadRequest, response.status, it.take(40))
            assertEquals("INVALID_CURSOR", code(response.bodyAsText()), it.take(40))
        }
        assertTrue(upstream.sent.isEmpty(), "ningun cursor invalido llega al upstream: ${upstream.urls}")
        val ok = PageCursorCodec.encode(listOf(JsonPrimitive("2026-10-07T09:00:00+00:00"), JsonPrimitive(uuid(1))), scope.fingerprint())
        assertEquals(HttpStatusCode.OK, client.getAs("$listPath?cursor=$ok").status, "control: la huella construida aqui es la de la ruta")
    }

    // =================================================================== un usuario solo lee SU cartera

    @Test
    fun noQueryParameterCanSelectAnotherUsersWallet() = testApplication {
        application { walletModule() }
        upstream.ledger = listOf(Entry(uuid(1), "2026-10-08T09:00:00+00:00"))
        // V1: un usuario en el query ya no se ignora en silencio, se RECHAZA (400 UNKNOWN_FIELD) y no llega ninguna lectura a la base.
        listOf("usuarioId", "userId", "usuario_id", "sub", "user").forEach { param ->
            assertEquals(HttpStatusCode.BadRequest, client.getAs("$summaryPath?$param=$other").status, param)
            assertEquals(HttpStatusCode.BadRequest, client.getAs("$listPath?$param=$other").status, param)
        }
        assertTrue(upstream.sent.isEmpty(), "ninguna consulta con usuario forjado llega a la base: ${upstream.urls}")
        assertEquals(HttpStatusCode.OK, client.getAs(summaryPath).status)
        assertOwnWalletOnly()
    }

    @Test
    fun theCursorDoesNotCarryTheOwnerSoItCannotMoveAcrossUsers() = testApplication {
        application { walletModule() }
        upstream.ledger = listOf(Entry(uuid(1), "2026-10-08T09:00:00+00:00"), Entry(uuid(2), "2026-10-07T09:00:00+00:00"))
        val cursor = obj(client.getAs("$listPath?limit=1").bodyAsText())["nextCursor"]!!.jsonPrimitive.content
        val decoded = String(java.util.Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8)
        assertFalse(decoded.contains(TEST_UID), "el cursor solo lleva claves de orden: $decoded")
        client.getAs("$listPath?limit=1&cursor=$cursor")
        assertOwnWalletOnly()
    }

    // =================================================================== fallos

    @Test
    fun anUpstreamFailureIs502WithTheCanonicalEnvelopeAndNoUpstreamDetail() = testApplication {
        application { walletModule() }
        upstream.failOn = "/rest/v1/transacciones_cartera"
        listOf(summaryPath, listPath).forEach {
            val response = client.getAs(it)
            assertEquals(HttpStatusCode.BadGateway, response.status, it)
            assertEquals("UPSTREAM_ERROR", code(response.bodyAsText()), it)
            assertFalse(response.bodyAsText().contains("boom"), "el detalle del upstream no sale")
        }
        upstream.failOn = "/rest/v1/carteras_usuario"
        assertEquals(HttpStatusCode.BadGateway, client.getAs(summaryPath).status)
        upstream.failOn = "/rest/v1/activaciones_servicio_usuario"
        assertEquals(HttpStatusCode.BadGateway, client.getAs(summaryPath).status)
    }

    @Test
    fun anActivationWithoutAServiceOrWithAnUnknownStateFailsClosed() = testApplication {
        application { walletModule() }
        upstream.activations = """[{"id":"${uuid(50)}","estado":"activa","usos_disponibles":1,"usos_consumidos":0,"servicios":null}]"""
        assertEquals("UPSTREAM_CONTRACT_ERROR", code(client.getAs(summaryPath).bodyAsText()))
        upstream.activations = """[{"id":"${uuid(50)}","estado":"rara","usos_disponibles":1,"usos_consumidos":0,"servicios":{"codigo":"x"}}]"""
        assertEquals("UPSTREAM_CONTRACT_ERROR", code(client.getAs(summaryPath).bodyAsText()))
        // Una cancelada que se colara NO sale nunca.
        upstream.activations = """[{"id":"${uuid(50)}","estado":"cancelada","usos_disponibles":1,"usos_consumidos":0,"servicios":{"codigo":"x"}}]"""
        assertTrue(obj(client.getAs(summaryPath).bodyAsText())["consumibles"]!!.jsonArray.isEmpty())
    }

    // =================================================================== autenticacion y cuenta

    private val allPaths = listOf(summaryPath, listPath, "$listPath?tipo=recarga&limit=5")

    @Test
    fun withoutAValidTokenEveryRouteIs401AndTheUpstreamIsNeverTouched() = testApplication {
        application { walletModule() }
        allPaths.forEach { path ->
            listOf(null, "x.y.z", "nodots").forEach { token ->
                val response = client.getAs(path, token)
                assertEquals(HttpStatusCode.Unauthorized, response.status, "$path $token")
                assertEquals("UNAUTHENTICATED", code(response.bodyAsText()), "$path $token")
            }
        }
        assertTrue(upstream.sent.isEmpty())
    }

    @Test
    fun aBlockedPendingDeletionOrUnknownAccountFailsClosedOnEveryRouteWithoutReadingTheWallet() = testApplication {
        application { walletModule() }
        val expected = mapOf("bloqueada" to "ACCOUNT_BLOCKED", "eliminacion_pendiente" to "ACCOUNT_DELETION_PENDING", "disabled" to "FORBIDDEN", "" to "FORBIDDEN")
        allPaths.forEach { path ->
            expected.forEach { (state, code) ->
                status = state
                val response = client.getAs(path)
                assertEquals(HttpStatusCode.Forbidden, response.status, "$path $state")
                assertEquals(code, code(response.bodyAsText()), "$path $state")
            }
            status = null
            assertEquals(HttpStatusCode.Forbidden, client.getAs(path).status, "$path null")
            status = "activa"
            profileExists = false
            assertEquals("PROFILE_MISSING", code(client.getAs(path).bodyAsText()), path)
            profileExists = true
        }
        assertTrue(upstream.sent.isEmpty(), "identidad denegada: ni una lectura de cartera: ${upstream.urls}")
    }

    @Test
    fun identityIsCheckedBeforeTheQueryIsValidated() = testApplication {
        application { walletModule() }
        status = "bloqueada"
        assertEquals("ACCOUNT_BLOCKED", code(client.getAs("$listPath?tipo=nope&limit=0&cursor=zzz").bodyAsText()))
        assertEquals("UNAUTHENTICATED", code(client.getAs("$listPath?tipo=nope", null).bodyAsText()))
    }

    @Test
    fun aMissingRuntimeFailsClosedInsteadOfServing() = testApplication {
        application {
            install(ContentNegotiation) { json(testJson) }
            installRequestCorrelation()
            installBackendStatusPages()
            routing { walletRoutes(null) }
        }
        allPaths.forEach { assertEquals(HttpStatusCode.InternalServerError, client.getAs(it).status, it) }
    }
}
