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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.net.http.HttpRequest
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SLICE 2: D2-01, D2-02, D2-13 y D7-02 de punta a punta (ruta -> servicio -> pasarela -> transporte real) contra un
 * upstream de pruebas que graba cada peticion. El cable se valida contra el OpenAPI CONGELADO con [SchemaChecker].
 */
class CatalogRoutesTest {

    private val docs = OpenApiDocs.locate()
    private val checker = SchemaChecker(docs)
    private val now = Instant.parse("2026-10-08T12:00:00Z")
    private val esp = "11111111-1111-4111-8111-111111111111"
    private val espB = "22222222-2222-4222-8222-222222222222"
    private val com = "33333333-3333-4333-8333-333333333333"
    private val e1 = "aaaaaaaa-aaaa-4aaa-8aaa-000000000001"
    private val e2 = "aaaaaaaa-aaaa-4aaa-8aaa-000000000002"
    private val e3 = "aaaaaaaa-aaaa-4aaa-8aaa-000000000003"
    private val e4 = "aaaaaaaa-aaaa-4aaa-8aaa-000000000004"
    private val intento = "bbbbbbbb-bbbb-4bbb-8bbb-000000000001"
    private val grantId = "cccccccc-cccc-4ccc-8ccc-000000000001"

    private val json = Json { ignoreUnknownKeys = true }
    private var status: String? = "activa"
    private var profileExists = true

    private val resolver = CanonicalIdentityResolver(
        verifySubject = { if (it == TEST_TOKEN) TEST_UID else error("firma invalida") },
        directory = { id -> if (profileExists) CanonicalUserIdentity(id, AccountStatus.fromWire(status), true) else null },
    )

    /** Upstream de pruebas: respuestas por fragmento de ruta (ya decodificada) y registro de TODO lo que sale. */
    private class Upstream {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val urls: List<String> get() = sent.map { URLDecoder.decode(it.uri().toString(), Charsets.UTF_8) }
        var exams: (String) -> String = { "[]" }
        var examById: (String) -> String = { "[]" }
        var facets = "[]"
        var attempts: (String) -> String = { "[]" }
        var openAttempt = "[]"
        var latestAttempts = "[]"
        var myGrants: (String) -> String = { "[]" }

        /** `app_my_exams_collection` (20261008100300): recibe el cuerpo JSON de la llamada y devuelve la respuesta de PostgREST. */
        var collection: (JsonObject) -> V1StubResponse = { V1StubResponse(200, """{"items":[],"grupos":[]}""") }
        val collectionCalls: List<JsonObject>
            get() = sent.filter { it.uri().toString().contains(COLLECTION_RPC) }
                .map { Json.parseToJsonElement(readRequestBody(it)).jsonObject }
        var grantFacts = "[]"
        var capabilities = "[]"
        var specialties = "[]"
        var premium = "[]"
        // Access v2: el plan solo cubre rafagas/razonados segun su nivel; por defecto un plan de nivel 3 (los tests que miran D7-02 fijan el suyo)
        var plan = """[{"planes_suscripcion":{"codigo":"pro","nivel":3}}]"""
        var credits = "[]"
        var signed = "[]"
        var communities = """[{"codigo":"MAD","nombre":"Comunidad de Madrid"},{"codigo":"AND","nombre":"Andalucía"}]"""
        var profileSpecialty = """[{"especialidad_principal_id":"11111111-1111-4111-8111-111111111111"}]"""
        var failOn: String? = null

        fun respond(rawUri: String): V1StubResponse {
            val uri = URLDecoder.decode(rawUri, Charsets.UTF_8)
            if (failOn != null && uri.contains(failOn!!)) return V1StubResponse(500, """{"message":"boom"}""")
            // El transporte graba la peticion ANTES de pedir la respuesta: la ultima grabada es la de esta llamada.
            if (uri.contains(COLLECTION_RPC)) return collection(Json.parseToJsonElement(readRequestBody(sent.last())).jsonObject)
            val body = when {
                uri.contains("/storage/v1/object/sign/") -> signed
                uri.contains("/rest/v1/perfiles_usuario?select=especialidad_principal_id") -> profileSpecialty
                uri.contains("/rest/v1/estado_premium_usuario") -> premium
                uri.contains("/rest/v1/productos_comerciales") -> plan
                uri.contains("/rest/v1/creditos_contenido_usuario") -> credits
                uri.contains("/rest/v1/capacidades_especialidad") -> capabilities
                uri.contains("/rest/v1/comunidades_autonomas?select=codigo,nombre") -> communities
                uri.contains("/rest/v1/especialidades?select=id,codigo,nombre,slug") -> specialties
                uri.contains("/rest/v1/contenido_desbloqueado_usuario?select=examen_id,motivo") -> grantFacts
                uri.contains("/rest/v1/contenido_desbloqueado_usuario?select=id,valido_desde") -> myGrants(uri)
                uri.contains("/rest/v1/intentos_examen?select=id,estado&usuario_id") -> openAttempt
                uri.contains("/rest/v1/intentos_examen?select=id,examen_id,estado") -> latestAttempts
                uri.contains("/rest/v1/intentos_examen?select=id,estado,total_respondidas") -> attempts(uri)
                uri.contains("/rest/v1/examenes?select=tipo_examen,anio,comunidades_autonomas") -> facets
                uri.contains("/rest/v1/examenes?select=id,codigo_examen") && uri.contains("&id=eq.") -> examById(uri)
                uri.contains("/rest/v1/examenes?select=id,codigo_examen") -> exams(uri)
                else -> error("peticion inesperada al upstream: $uri")
            }
            return V1StubResponse(200, body)
        }

        companion object {
            const val COLLECTION_RPC = "/rest/v1/rpc/app_my_exams_collection"
        }
    }

    private val upstream = Upstream()

    /** Una fila de `app_my_exams_collection`: el examen (metadatos), su clave de orden y su intento mas reciente (o ninguno). */
    private data class CollectionEntry(val examId: String, val ordenEn: String, val examJson: String, val attemptJson: String? = null) {
        val json: String get() = """{"orden_en":"$ordenEn","examenes":$examJson,"intento":${attemptJson ?: "null"}}"""
    }

    private fun attemptRef(id: String, estado: String, respondidas: Int, total: Int) =
        """{"id":"$id","estado":"$estado","total_respondidas":$respondidas,"total_preguntas":$total}"""

    /**
     * Doble de la funcion de lectura con SU contrato de paginacion (el real lo prueban `backend/dbtest` y el gate de PG17):
     * orden `(orden_en, id)` descendente, keyset estricto `< (p_despues_en, p_despues_id)`, `p_limite` filas y los grupos de
     * TODO el conjunto solo si `p_con_grupos`. Asi el viaje de ida y vuelta del cursor de Ktor se prueba de verdad.
     */
    private fun collectionOf(entries: List<CollectionEntry>, groups: String = "[]"): (JsonObject) -> V1StubResponse = { call ->
        val order = compareByDescending<CollectionEntry> { java.time.OffsetDateTime.parse(it.ordenEn).toInstant() }.thenByDescending { it.examId }
        val afterAt = call["p_despues_en"]?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content
        val afterId = call["p_despues_id"]?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content
        check((afterAt == null) == (afterId == null)) { "cursor incompleto: la funcion real lo rechaza (22023)" }
        val rows = entries.sortedWith(order)
            .filter { afterAt == null || order.compare(CollectionEntry(afterId!!, afterAt, "{}"), it) < 0 }
            .take(call.getValue("p_limite").jsonPrimitive.content.toInt())
        val withGroups = call.getValue("p_con_grupos").jsonPrimitive.content == "true"
        V1StubResponse(200, """{"items":[${rows.joinToString(",") { it.json }}],"grupos":${if (withGroups) groups else "null"}}""")
    }

    private suspend fun HttpClient.myExams(query: String): JsonObject {
        val response = getAs("/api/v1/app/me/examenes?$query")
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        assertConforms("D2-13", body)
        return JsonObjectOf(body)
    }

    private fun JsonObject.examIds(): List<String> = this["items"]!!.jsonArray.map { it.jsonObject["examen"]!!.jsonObject["id"]!!.jsonPrimitive.content }

    private fun Application.catalogModule(limit: suspend (CanonicalUserId) -> Unit = {}) {
        install(ContentNegotiation) { json(testJson) }
        installRequestCorrelation()
        installBackendStatusPages()
        val transport = SupabaseRequestTransport.forTesting(
            "https://stub.supabase.test",
            "stub-key",
            V1StubHttpClient({ upstream.sent += it }, upstream::respond),
        )
        val gateway = CatalogGateway(TransportCatalogUpstream(transport), json)
        val clock = { now }
        routing { catalogRoutes(CatalogRuntime(resolver, CatalogService(gateway, clock), EntitlementService(gateway, clock), limit)) }
    }

    private fun exam(
        id: String,
        titulo: String = "Examen $id",
        tipo: String = "rafaga",
        acceso: String = "gratuito",
        especialidad: String = esp,
        publicado: String = "2026-09-30T10:00:00.123456+00:00",
        cover: String? = null,
        comunidad: String? = "MAD",
        demo: Boolean = false,
        anio: Int? = 2024,
        penalty: String = "",
    ): String =
        """{"id":"$id","codigo_examen":"EX-${id.takeLast(3)}","titulo":"$titulo","descripcion":"desc","tipo_examen":"$tipo","tipo_acceso":"$acceso",""" +
            """"especialidad_id":"$especialidad","total_preguntas":10,"duracion_minutos":60,"anio":${anio ?: "null"},""" +
            """"portada_storage_path":${cover?.let { "\"$it\"" } ?: "null"},"publicado_en":"$publicado","demo_disponible":$demo,$penalty""" +
            """"comunidades_autonomas":${comunidad?.let { """{"codigo":"$it"}""" } ?: "null"}}"""

    private fun premiumRow(end: String? = "2099-01-01T00:00:00+00:00", estado: String = "activa", flag: Boolean = true) =
        """[{"es_premium":$flag,"estado_suscripcion":"$estado","producto_codigo":"plan-pro","proveedor":"stripe",""" +
            """"inicio_en":"2026-01-01T00:00:00+00:00","fin_en":${end?.let { "\"$it\"" } ?: "null"},"renovacion_en":"2098-12-01T00:00:00+00:00"}]"""

    private fun grantFact(exam: String, desde: String = "2026-09-01T00:00:00+00:00", hasta: String? = null, motivo: String = "compra") =
        """{"examen_id":"$exam","motivo":"$motivo","valido_desde":"$desde","valido_hasta":${hasta?.let { "\"$it\"" } ?: "null"}}"""

    private suspend fun HttpClient.getAs(path: String, token: String? = TEST_TOKEN): HttpResponse =
        get(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }

    private fun JsonObjectOf(body: String): JsonObject = json.parseToJsonElement(body).jsonObject

    private fun code(body: String): String = JsonObjectOf(body)["code"]!!.jsonPrimitive.content

    private fun assertConforms(contractId: String, body: String) {
        val op = docs.operations().first { it.contractId == contractId }
        val response = docs.responses(op).getValue("200")
        val schema = ((response.value as Map<*, *>)["content"] as Map<*, *>).let { (it["application/json"] as Map<*, *>)["schema"] }
        val problems = mutableListOf<String>()
        checker.validate(OpenApiDocs.Node(schema, response.file), json.parseToJsonElement(body), "$", problems)
        assertTrue(problems.isEmpty(), "$contractId does not conform to the frozen OpenAPI:\n${problems.joinToString("\n")}\n$body")
    }

    private fun assertNoQuestionTables() {
        val leaked = upstream.urls.filter { url -> listOf("preguntas", "opciones_pregunta", "solucion", "respuestas").any { url.contains(it) } }
        // `total_preguntas` es una columna de metadatos del examen; nada mas con "preguntas" puede salir.
        assertTrue(leaked.all { it.replace("total_preguntas", "").let { rest -> !rest.contains("preguntas") && !rest.contains("opciones") && !rest.contains("solucion") } }, "SEC-004: $leaked")
    }

    // =================================================================== D2-01

    @Test
    fun listReturnsPublishedExamsWithEveryAccessStateAndConformsToTheFrozenWire() = testApplication {
        application { catalogModule() }
        upstream.exams = {
            "[" + listOf(
                exam(e1, acceso = "gratuito", cover = "exam-covers/$e1/a.png"),
                exam(e2, acceso = "premium", publicado = "2026-09-29T10:00:00+00:00"),
                exam(e3, acceso = "premium", publicado = "2026-09-28T10:00:00+00:00", comunidad = null, anio = null),
                exam(e4, acceso = "premium", publicado = "2026-09-27T10:00:00+00:00", demo = true),
            ).joinToString(",") + "]"
        }
        upstream.grantFacts = "[${grantFact(e2)}]"
        upstream.signed = """[{"path":"exam-covers/$e1/a.png","signedURL":"/object/sign/exam-resources/exam-covers/$e1/a.png?token=t"}]"""
        val response = client.getAs("/api/v1/app/examenes")
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        assertEquals("private, max-age=60", response.headers[HttpHeaders.CacheControl])
        assertConforms("D2-01", body)
        val items = JsonObjectOf(body)["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("FREE", "GRANTED", "LOCKED", "DEMO"),
            items.map { it["accessState"]!!.jsonPrimitive.content },
            "gratuito / concesion vigente / premium sin derecho / solo demo",
        )
        assertEquals("https://stub.supabase.test/storage/v1/object/sign/exam-resources/exam-covers/$e1/a.png?token=t", items[0]["portadaUrl"]!!.jsonPrimitive.content)
        assertNull(items[1]["portadaUrl"], "sin portada no hay URL inventada")
        assertNull(JsonObjectOf(body)["nextCursor"])
        assertNull(JsonObjectOf(body)["facets"], "sin includeFacets no hay facets")
        assertFalse(body.contains("\"data\""), "sin envoltorio legacy")
        assertNoQuestionTables()
    }

    @Test
    fun subscriptionGivesSubscriptionStateAndAnExpiredOneDoesNot() = testApplication {
        application { catalogModule() }
        upstream.exams = { "[${exam(e1, acceso = "premium")}]" }
        upstream.premium = premiumRow()
        var items = JsonObjectOf(client.getAs("/api/v1/app/examenes").bodyAsText())["items"]!!.jsonArray
        assertEquals("SUBSCRIPTION", items[0].jsonObject["accessState"]!!.jsonPrimitive.content)
        upstream.premium = premiumRow(end = "2026-10-01T00:00:00+00:00")
        items = JsonObjectOf(client.getAs("/api/v1/app/examenes").bodyAsText())["items"]!!.jsonArray
        assertEquals("LOCKED", items[0].jsonObject["accessState"]!!.jsonPrimitive.content, "fin_en vencido: no hay acceso aunque es_premium siga a true")
    }

    @Test
    fun anExpiredGrantAndADisabledCapabilityLockTheExam() = testApplication {
        application { catalogModule() }
        upstream.exams = { "[${exam(e1, acceso = "premium")},${exam(e2, acceso = "gratuito", tipo = "inmersivo", especialidad = espB)}]" }
        upstream.grantFacts = "[${grantFact(e1, hasta = "2026-10-01T00:00:00+00:00")}]"
        upstream.capabilities = """[{"especialidad_id":"$espB","permite_rafagas":true,"permite_razonados":true,"permite_inmersivos":false,"permite_demos":true}]"""
        val items = JsonObjectOf(client.getAs("/api/v1/app/examenes").bodyAsText())["items"]!!.jsonArray
        assertEquals(listOf("LOCKED", "LOCKED"), items.map { it.jsonObject["accessState"]!!.jsonPrimitive.content })
    }

    @Test
    fun theListQueryIsPublishedOnlyMetadataOnlyAndBatched() = testApplication {
        application { catalogModule() }
        upstream.exams = { "[${exam(e1, acceso = "premium")},${exam(e2, acceso = "premium")}]" }
        client.getAs("/api/v1/app/examenes?tipoExamen=guiado&especialidadId=$esp&comunidadId=$com&anio=2024")
        val list = upstream.urls.single { it.contains("/rest/v1/examenes?select=id,codigo_examen") }
        assertTrue(list.contains("&activo=eq.true&publicado_en=not.is.null&publicado_en=lte.2026-10-08T12:00:00Z"), list)
        assertTrue(list.contains("&tipo_examen=eq.razonado"), "el sinonimo legacy se normaliza y no se emite: $list")
        assertTrue(list.contains("&especialidad_id=eq.$esp&comunidad_id=eq.$com&anio=eq.2024"), list)
        assertTrue(list.contains("&order=publicado_en.desc,id.desc&limit=21"), list)
        assertFalse(list.contains("select=*"))
        // Hechos de acceso: una lectura por tabla para toda la pagina, no una por examen.
        assertEquals(1, upstream.urls.count { it.contains("/rest/v1/estado_premium_usuario") })
        assertEquals(1, upstream.urls.count { it.contains("/rest/v1/contenido_desbloqueado_usuario") })
        assertEquals(1, upstream.urls.count { it.contains("/rest/v1/capacidades_especialidad") })
        assertTrue(upstream.urls.single { it.contains("/rest/v1/contenido_desbloqueado_usuario") }.contains("usuario_id=eq.$TEST_UID&examen_id=in.($e1,$e2)"))
        assertNoQuestionTables()
    }

    @Test
    fun keysetPaginationRoundTripsAndEndsOnTheLastPage() = testApplication {
        application { catalogModule() }
        val page1 = listOf(exam(e1, publicado = "2026-09-30T10:00:00.123456+00:00"), exam(e2, publicado = "2026-09-29T10:00:00+00:00"), exam(e3, publicado = "2026-09-28T10:00:00+00:00"))
        upstream.exams = { uri ->
            if (uri.contains("or=(publicado_en.lt.")) "[${exam(e3, publicado = "2026-09-28T10:00:00+00:00")}]" else "[${page1.joinToString(",")}]"
        }
        val first = JsonObjectOf(client.getAs("/api/v1/app/examenes?limit=2").bodyAsText())
        assertEquals(listOf(e1, e2), first["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertTrue(upstream.urls.last { it.contains("/rest/v1/examenes?") }.contains("&limit=3"), "limit + 1 para saber si hay mas")
        val cursor = first["nextCursor"]!!.jsonPrimitive.content
        val secondResponse = client.getAs("/api/v1/app/examenes?limit=2&cursor=$cursor")
        val second = JsonObjectOf(secondResponse.bodyAsText())
        assertEquals(HttpStatusCode.OK, secondResponse.status)
        assertEquals(listOf(e3), second["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertNull(second["nextCursor"], "ultima pagina")
        val keyset = upstream.urls.last { it.contains("/rest/v1/examenes?select=id,codigo_examen") }
        assertTrue(keyset.contains("&or=(publicado_en.lt.2026-09-29T10:00:00+00:00,and(publicado_en.eq.2026-09-29T10:00:00+00:00,id.lt.$e2))"), keyset)
    }

    @Test
    fun theCursorOfAnotherFilterOrGarbageIs400InvalidCursorAndNeverTouchesTheCatalog() = testApplication {
        application { catalogModule() }
        upstream.exams = { "[${exam(e1)},${exam(e2)},${exam(e3)}]" }
        val cursor = JsonObjectOf(client.getAs("/api/v1/app/examenes?limit=2").bodyAsText())["nextCursor"]!!.jsonPrimitive.content
        val before = upstream.sent.size
        listOf(
            "/api/v1/app/examenes?limit=2&cursor=$cursor&anio=2020",
            "/api/v1/app/examenes?cursor=not-a-cursor",
            "/api/v1/app/examenes?cursor=${"A".repeat(3000)}",
        ).forEach {
            val response = client.getAs(it)
            assertEquals(HttpStatusCode.BadRequest, response.status, it)
            assertEquals("INVALID_CURSOR", code(response.bodyAsText()), it)
        }
        // Un cursor bien formado y de la lista correcta pero con claves que no son [instante, id] tampoco llega al SQL.
        val forged = PageCursorCodec.encode(
            listOf(kotlinx.serialization.json.JsonPrimitive("2026-09-29T10:00:00+00:00)),x"), kotlinx.serialization.json.JsonPrimitive(e1)),
            CursorScope(listOf("publicado_en desc", "id desc"), mapOf("tipoExamen" to null)).fingerprint(),
        )
        val response = client.getAs("/api/v1/app/examenes?cursor=$forged")
        assertEquals("INVALID_CURSOR", code(response.bodyAsText()))
        assertEquals(before, upstream.sent.size, "ningun cursor invalido llega al upstream")
    }

    @Test
    fun invalidLimitAndFiltersAreCanonical400ValidationErrors() = testApplication {
        application { catalogModule() }
        listOf("limit=0", "limit=-3", "limit=abc").forEach {
            val response = client.getAs("/api/v1/app/examenes?$it")
            assertEquals(HttpStatusCode.BadRequest, response.status, it)
            assertEquals("VALIDATION", code(response.bodyAsText()), it)
        }
        val response = client.getAs("/api/v1/app/examenes?tipoExamen=foo&especialidadId=x&comunidadId=y&anio=abc&includeFacets=maybe")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val issues = JsonObjectOf(response.bodyAsText())["details"]!!.jsonObject["issues"]!!.jsonArray.map { it.jsonObject["field"]!!.jsonPrimitive.content }
        assertEquals(listOf("tipoExamen", "especialidadId", "comunidadId", "anio", "includeFacets"), issues)
        assertTrue(upstream.sent.isEmpty(), "una peticion invalida no llega al upstream")
    }

    @Test
    fun anOversizedLimitIsClampedToFifty() = testApplication {
        application { catalogModule() }
        assertEquals(HttpStatusCode.OK, client.getAs("/api/v1/app/examenes?limit=500").status)
        val examsUrl = upstream.urls.single { it.contains("select=id,codigo_examen") }
        assertTrue(examsUrl.contains("&limit=51"), examsUrl)
    }

    @Test
    fun facetsAreReturnedOnlyOnRequestAndConform() = testApplication {
        application { catalogModule() }
        upstream.exams = { "[${exam(e1)}]" }
        upstream.facets = """[{"tipo_examen":"inmersivo","anio":2023,"comunidades_autonomas":{"codigo":"MAD"}},{"tipo_examen":"rafaga","anio":2024,"comunidades_autonomas":{"codigo":"AND"}},""" +
            """{"tipo_examen":"rafaga","anio":2024,"comunidades_autonomas":null}]"""
        val body = client.getAs("/api/v1/app/examenes?includeFacets=true").bodyAsText()
        assertConforms("D2-01", body)
        val facets = JsonObjectOf(body)["facets"]!!.jsonObject
        assertEquals(listOf("AND", "MAD"), facets["comunidades"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("2024", "2023"), facets["anios"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("rafaga", "inmersivo"), facets["tipos"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun aCoverSigningFailureDoesNotBreakTheList() = testApplication {
        application { catalogModule() }
        upstream.exams = { "[${exam(e1, cover = "exam-covers/$e1/a.png")}]" }
        upstream.failOn = "/storage/v1/object/sign/"
        val response = client.getAs("/api/v1/app/examenes")
        assertEquals(HttpStatusCode.OK, response.status)
        assertNull(JsonObjectOf(response.bodyAsText())["items"]!!.jsonArray[0].jsonObject["portadaUrl"])
    }

    @Test
    fun anUpstreamFailureIs502WithTheCanonicalEnvelope() = testApplication {
        application { catalogModule() }
        upstream.failOn = "/rest/v1/examenes"
        val response = client.getAs("/api/v1/app/examenes")
        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertEquals("UPSTREAM_ERROR", code(response.bodyAsText()))
        assertFalse(response.bodyAsText().contains("boom"), "el detalle del upstream no sale")
    }

    // =================================================================== V1: la especialidad es una PUERTA (propietario 2026-10-16)

    private val otherSpecialty = "22222222-2222-4222-8222-222222222222"

    @Test
    fun anExamOutsideTheUsersSpecialtyDoesNotExistForThemEvenIfTheyOwnIt() = testApplication {
        application { catalogModule() }
        upstream.profileSpecialty = """[{"especialidad_principal_id":"$otherSpecialty"}]"""
        upstream.examById = { "[${exam(e1, acceso = "premium")}]" }
        upstream.grantFacts = "[${grantFact(e1, motivo = "compra")}]" // a stored purchase does not open it while the specialty differs
        val response = client.getAs("/api/v1/app/examenes/$e1")
        assertEquals(HttpStatusCode.NotFound, response.status, response.bodyAsText())
        assertEquals("EXAM_NOT_FOUND", code(response.bodyAsText()))
        upstream.profileSpecialty = """[{"especialidad_principal_id":"$esp"}]"""
        assertEquals(HttpStatusCode.OK, client.getAs("/api/v1/app/examenes/$e1").status)
        upstream.profileSpecialty = "[]" // no specialty chosen yet: denied too (E2-V1-E06)
        assertEquals(HttpStatusCode.NotFound, client.getAs("/api/v1/app/examenes/$e1").status)
    }

    @Test
    fun listsAreScopedToTheUsersSpecialtyAndAnotherSpecialtyIsEmpty() = testApplication {
        application { catalogModule() }
        upstream.profileSpecialty = """[{"especialidad_principal_id":"$esp"}]"""
        upstream.exams = { "[${exam(e1)}]" }
        assertEquals(HttpStatusCode.OK, client.getAs("/api/v1/app/examenes").status)
        assertTrue(upstream.urls.last { it.contains("/rest/v1/examenes?select=id,codigo_examen") }.contains("especialidad_id=eq.$esp"), "scoped to the own specialty without the client asking")
        val sentBefore = upstream.urls.size
        val other = client.getAs("/api/v1/app/examenes?especialidadId=$otherSpecialty")
        assertEquals(HttpStatusCode.OK, other.status)
        assertEquals(0, JsonObjectOf(other.bodyAsText())["items"]!!.jsonArray.size)
        assertTrue(upstream.urls.drop(sentBefore).none { it.contains("/rest/v1/examenes?select=id,codigo_examen") }, "another specialty never reaches the catalog read")
        val mine = client.getAs("/api/v1/app/me/examenes?vista=EN_CURSO&especialidadId=$otherSpecialty")
        assertEquals(0, JsonObjectOf(mine.bodyAsText())["items"]!!.jsonArray.size)
    }

    // =================================================================== D2-02

    @Test
    fun detailReturnsMetadataOnlyWithAccessStateOpenAttemptAndNullPrice() = testApplication {
        application { catalogModule() }
        upstream.examById = { "[${exam(e1, acceso = "premium", cover = "exam-covers/$e1/a.png")}]" }
        upstream.openAttempt = """[{"id":"$intento","estado":"en_progreso"}]"""
        upstream.grantFacts = "[${grantFact(e1, motivo = "regalo")}]"
        upstream.signed = """[{"path":"exam-covers/$e1/a.png","signedURL":"https://stub.supabase.test/storage/v1/object/sign/exam-resources/exam-covers/$e1/a.png?token=t"}]"""
        val response = client.getAs("/api/v1/app/examenes/${e1.uppercase()}")
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        assertEquals("private, no-store", response.headers[HttpHeaders.CacheControl])
        assertConforms("D2-02", body)
        val exam = JsonObjectOf(body)["examen"]!!.jsonObject
        assertEquals("GRANTED", exam["accessState"]!!.jsonPrimitive.content)
        assertEquals(intento, exam["intentoAbiertoId"]!!.jsonPrimitive.content)
        assertNull(exam["precio"], "DEC-035: precio null en Slice 2")
        assertEquals("EX-001", exam["codigoExamen"]!!.jsonPrimitive.content)
        listOf("preguntas", "opciones", "totalEvaluables", "demoDisponible", "precioRef").forEach { assertFalse(body.contains("\"$it\""), "$it no forma parte del detalle") }
        val byId = upstream.urls.single { it.contains("/rest/v1/examenes?") }
        assertTrue(byId.contains("&id=eq.$e1&activo=eq.true&publicado_en=not.is.null&publicado_en=lte.2026-10-08T12:00:00Z&limit=1"), byId)
        assertTrue(upstream.urls.single { it.contains("intentos_examen") }.contains("usuario_id=eq.$TEST_UID&examen_id=eq.$e1&origen_intento=eq.examen&creado_por_comando=not.is.null&es_demo=eq.false&estado=in.(en_progreso,finalizado)&order=estado.asc,finalizado_en.desc.nullslast,id.desc&limit=2"))
        assertNull(exam["ultimoIntentoFinalizadoId"], "sin intento finalizado: el campo no viaja")
        assertNoQuestionTables()
    }

    @Test
    fun anUnknownQueryParameterIsRejectedInsteadOfSilentlyIgnored() = testApplication {
        application { catalogModule() }
        for (path in listOf("/api/v1/app/examenes?especialidad=Local", "/api/v1/app/me/examenes?vista=EN_CURSO&foo=1")) {
            val response = client.getAs(path)
            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.BadRequest, response.status, "$path -> $body")
            assertTrue(body.contains("UNKNOWN_FIELD"), body)
        }
        assertTrue(upstream.sent.isEmpty(), "un parametro desconocido no debe producir ninguna lectura upstream: ${upstream.urls}")
    }

    // =================================================================== V1 STEP 2: comunidad legible

    @Test
    fun theReadableCommunityNameIsServedOnListDetailAndCollectionNeverTheRawCode() = testApplication {
        application { catalogModule() }
        val e1 = "e1111111-1111-4111-8111-111111111111"
        val e2 = "e2222222-2222-4222-8222-222222222222"
        upstream.exams = { "[${exam(e1, comunidad = "MAD")},${exam(e2, comunidad = null)}]" }
        val list = JsonObjectOf(client.getAs("/api/v1/app/examenes").bodyAsText())["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals("Comunidad de Madrid", list.first { it["id"]!!.jsonPrimitive.content == e1 }["comunidadNombre"]!!.jsonPrimitive.content)
        assertNull(list.first { it["id"]!!.jsonPrimitive.content == e2 }["comunidadNombre"], "un examen heredado sin comunidad no inventa nombre")

        upstream.examById = { "[${exam(e1, comunidad = "MAD")}]" }
        val detail = client.getAs("/api/v1/app/examenes/$e1")
        assertConforms("D2-02", detail.bodyAsText())
        assertEquals("Comunidad de Madrid", JsonObjectOf(detail.bodyAsText())["examen"]!!.jsonObject["comunidadNombre"]!!.jsonPrimitive.content)
    }

    @Test
    fun aFailureReadingCommunityNamesNeverBreaksTheCatalog() = testApplication {
        application { catalogModule() }
        val e1 = "e1111111-1111-4111-8111-111111111111"
        upstream.exams = { "[${exam(e1, comunidad = "MAD")}]" }
        upstream.failOn = "comunidades_autonomas?select=codigo,nombre"
        val response = client.getAs("/api/v1/app/examenes")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val item = JsonObjectOf(response.bodyAsText())["items"]!!.jsonArray[0].jsonObject
        assertEquals("MAD", item["comunidadCodigo"]!!.jsonPrimitive.content)
        assertNull(item["comunidadNombre"], "sin nombre el cliente no muestra cabecera: nunca el codigo crudo")
    }

    // =================================================================== E2-P02 / FA20

    @Test
    fun catalogAndEntitlementReadsShareOnePerUserBucketAnd429WithRetryAfter() = testApplication {
        val limiter = InMemoryOposiBotsAuthRateLimiter()
        application { catalogModule(limiter.subjectLimit(OposiBotsAuthOperation.CATALOG_READ)) }
        val paths = listOf("/api/v1/app/examenes", "/api/v1/app/me/entitlement", "/api/v1/app/me/examenes?vista=EN_CURSO")
        repeat(OposiBotsAuthOperation.CATALOG_READ.subjectLimit) { i ->
            val response = client.getAs(paths[i % paths.size])
            assertTrue(response.status != HttpStatusCode.TooManyRequests, "peticion $i dentro del limite")
        }
        for (path in paths + "/api/v1/app/examenes/00000000-0000-4000-8000-000000000001") {
            val response = client.getAs(path)
            assertEquals(HttpStatusCode.TooManyRequests, response.status, path)
            assertEquals("RATE_LIMITED", code(response.bodyAsText()))
            assertNotNull(response.headers[HttpHeaders.RetryAfter])
        }
        assertEquals(120, OposiBotsAuthOperation.CATALOG_READ.subjectLimit)
        assertEquals(60L, OposiBotsAuthOperation.CATALOG_READ.windowSeconds)
    }

    @Test
    fun publicSpecialtiesAreNotUserLimited() = testApplication {
        application { catalogModule { throw CanonicalErrorCode.RATE_LIMITED.exception(retryAfterSeconds = 7) } }
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/public/especialidades").status)
        assertEquals(HttpStatusCode.TooManyRequests, client.getAs("/api/v1/app/examenes").status)
    }

    // =================================================================== D2-17

    @Test
    fun publicSpecialtiesServeRealIdsWithCapabilitiesWithoutSessionAndCached() = testApplication {
        application { catalogModule() }
        val s1 = "5a000000-0000-4000-8000-000000000001"
        val s2 = "5a000000-0000-4000-8000-000000000002"
        upstream.specialties = """[{"id":"$s1","codigo":"EF","nombre":"Educacion Fisica","slug":"ef"},{"id":"$s2","codigo":"ING","nombre":"Ingles","slug":"ing"}]"""
        upstream.capabilities = """[{"especialidad_id":"$s2","permite_rafagas":true,"permite_razonados":false,"permite_inmersivos":false,"permite_demos":true}]"""
        val response = client.get("/api/v1/public/especialidades")
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        assertEquals("public, max-age=300", response.headers[HttpHeaders.CacheControl])
        assertConforms("D2-17", body)
        val items = JsonObjectOf(body)["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(s1, s2), items.map { it["id"]!!.jsonPrimitive.content })
        assertEquals("true", items[0]["capacidades"]!!.jsonObject["permiteInmersivos"]!!.jsonPrimitive.content, "sin fila de capacidades: abierto")
        assertEquals("false", items[1]["capacidades"]!!.jsonObject["permiteRazonados"]!!.jsonPrimitive.content)
        assertTrue(upstream.urls.single { it.contains("/rest/v1/especialidades?") }.contains("activa=eq.true&order=posicion.asc,nombre.asc,id.asc&limit=101"))
        val before = upstream.sent.size
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/public/especialidades").status)
        assertEquals(before, upstream.sent.size, "segunda lectura servida por la cache de proceso")
    }

    @Test
    fun theCoverExposesThePenaltyRuleOfTheExam() = testApplication {
        application { catalogModule() }
        suspend fun penalty(row: String): JsonObject? {
            upstream.examById = { "[$row]" }
            val body = client.getAs("/api/v1/app/examenes/$e1").bodyAsText()
            assertConforms("D2-02", body)
            return JsonObjectOf(body)["examen"]!!.jsonObject["penalizacion"] as? JsonObject
        }
        assertNull(penalty(exam(e1)), "no penalty: the field does not travel")
        assertEquals("RACIONAL", penalty(exam(e1, penalty = """"penalizacion_num":0,"penalizacion_den":1,"penalizacion_racional":true,"es_oficial":true,"""))!!["tipo"]!!.jsonPrimitive.content)
        val fixed = penalty(exam(e1, penalty = """"penalizacion_num":1,"penalizacion_den":3,"penalizacion_racional":false,"es_oficial":true,"""))!!
        assertEquals("FIJA", fixed["tipo"]!!.jsonPrimitive.content); assertEquals("1", fixed["numerador"]!!.jsonPrimitive.content); assertEquals("3", fixed["denominador"]!!.jsonPrimitive.content)
    }

    /** 20261022103400 (propietario 2026-10-17): la penalizacion es solo de examenes OFICIALES. Una fila no oficial con la marca no la anuncia. */
    @Test
    fun aNonOfficialExamNeverAnnouncesAPenalty() = testApplication {
        application { catalogModule() }
        suspend fun penalty(row: String): JsonObject? {
            upstream.examById = { "[$row]" }
            val body = client.getAs("/api/v1/app/examenes/$e1").bodyAsText()
            assertConforms("D2-02", body)
            return JsonObjectOf(body)["examen"]!!.jsonObject["penalizacion"] as? JsonObject
        }
        assertNull(penalty(exam(e1, penalty = """"penalizacion_num":0,"penalizacion_den":1,"penalizacion_racional":true,"es_oficial":false,""")), "non-official: rational never travels")
        assertNull(penalty(exam(e1, penalty = """"penalizacion_num":1,"penalizacion_den":3,"penalizacion_racional":false,""")), "es_oficial missing = not official: fraction never travels")
        assertTrue(upstream.urls.any { it.contains("/rest/v1/examenes?") && it.contains("es_oficial") }, "the cover reads es_oficial")
    }

    @Test
    fun detailCarriesTheLastFinishedAttemptInTheSameBoundedReadPerf01() = testApplication {
        application { catalogModule() }
        upstream.examById = { "[${exam(e1)}]" }
        val done = "cccccccc-0000-4000-8000-000000000002"
        // Sin intento abierto: la fila 1 ya es la ultima finalizada (el orden lo da PostgREST).
        upstream.openAttempt = """[{"id":"$done","estado":"finalizado"},{"id":"cccccccc-0000-4000-8000-000000000001","estado":"finalizado"}]"""
        val exam = JsonObjectOf(client.getAs("/api/v1/app/examenes/$e1").bodyAsText())["examen"]!!.jsonObject
        assertEquals(done, exam["ultimoIntentoFinalizadoId"]!!.jsonPrimitive.content)
        assertNull(exam["intentoAbiertoId"])
        // Con abierto + finalizado: los dos, en la MISMA lectura (una sola peticion a intentos_examen).
        upstream.openAttempt = """[{"id":"$intento","estado":"en_progreso"},{"id":"$done","estado":"finalizado"}]"""
        val body = client.getAs("/api/v1/app/examenes/$e1").bodyAsText()
        assertConforms("D2-02", body)
        val both = JsonObjectOf(body)["examen"]!!.jsonObject
        assertEquals(intento, both["intentoAbiertoId"]!!.jsonPrimitive.content)
        assertEquals(done, both["ultimoIntentoFinalizadoId"]!!.jsonPrimitive.content)
        assertEquals(2, upstream.urls.count { it.contains("/rest/v1/intentos_examen") }, "una lectura por portada, no paginas de D2-13")
        assertTrue(upstream.urls.none { it.contains("app_my_exams_collection") || it.contains("contenido_desbloqueado_usuario?select=id,valido_desde") })
    }

    @Test
    fun aLockedExamIsStill200WithLockedState() = testApplication {
        application { catalogModule() }
        upstream.examById = { "[${exam(e1, acceso = "premium")}]" }
        val response = client.getAs("/api/v1/app/examenes/$e1")
        assertEquals(HttpStatusCode.OK, response.status)
        val exam = JsonObjectOf(response.bodyAsText())["examen"]!!.jsonObject
        assertEquals("LOCKED", exam["accessState"]!!.jsonPrimitive.content)
        assertNull(exam["intentoAbiertoId"])
    }

    @Test
    fun anUnpublishedDraftArchivedOrUnknownExamIs404ExamNotFoundWithoutLeakingExistence() = testApplication {
        application { catalogModule() }
        upstream.examById = { "[]" } // el filtro de publicado en la consulta deja fuera borradores y archivados
        val unknown = client.getAs("/api/v1/app/examenes/$e1")
        assertEquals(HttpStatusCode.NotFound, unknown.status)
        assertEquals("EXAM_NOT_FOUND", code(unknown.bodyAsText()))
        val body = unknown.bodyAsText()
        assertFalse(body.contains("borrador", ignoreCase = true) || body.contains("archiv", ignoreCase = true) || body.contains("publicad", ignoreCase = true))
        val query = upstream.urls.single()
        assertTrue(query.contains("&activo=eq.true&publicado_en=not.is.null&publicado_en=lte."), "solo publicados: $query")
        assertTrue(upstream.urls.none { it.contains("intentos_examen") || it.contains("estado_premium") }, "ni siquiera se consultan derechos de un examen inexistente")
    }

    @Test
    fun aNonUuidExamIdIs400ValidationBeforeAnyRead() = testApplication {
        application { catalogModule() }
        val response = client.getAs("/api/v1/app/examenes/not-a-uuid")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("VALIDATION", code(response.bodyAsText()))
        assertEquals("examId", JsonObjectOf(response.bodyAsText())["details"]!!.jsonObject["issues"]!!.jsonArray[0].jsonObject["field"]!!.jsonPrimitive.content)
        assertTrue(upstream.sent.isEmpty())
    }

    // =================================================================== D2-13

    @Test
    fun enCursoListsOpenAttemptsWithProgressAndConforms() = testApplication {
        application { catalogModule() }
        upstream.attempts = {
            """[{"id":"$intento","estado":"en_progreso","total_respondidas":5,"total_preguntas":10,"actualizado_en":"2026-10-07T09:00:00.5+00:00","examenes":${exam(e1, acceso = "premium")}}]"""
        }
        upstream.premium = premiumRow()
        val response = client.getAs("/api/v1/app/me/examenes?vista=EN_CURSO")
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        assertEquals("private, max-age=30", response.headers[HttpHeaders.CacheControl])
        assertConforms("D2-13", body)
        val item = JsonObjectOf(body)["items"]!!.jsonArray[0].jsonObject
        assertEquals(intento, item["intentoId"]!!.jsonPrimitive.content)
        assertEquals("en_progreso", item["estadoIntento"]!!.jsonPrimitive.content)
        assertEquals(0.5, item["progreso"]!!.jsonPrimitive.content.toDouble())
        assertEquals("SUBSCRIPTION", item["accessState"]!!.jsonPrimitive.content)
        assertNull(item["nota"], "DEC-013: sin nota en Slice 2")
        assertNull(item["calificacion"])
        assertNull(JsonObjectOf(body)["grupos"])
        val url = upstream.urls.single { it.contains("/rest/v1/intentos_examen?select=id,estado") }
        assertTrue(url.contains("&usuario_id=eq.$TEST_UID&estado=eq.en_progreso&origen_intento=eq.examen"), url)
        assertTrue(url.contains("&examenes.activo=eq.true&examenes.publicado_en=not.is.null&examenes.publicado_en=lte."), "solo examenes publicados: $url")
        assertTrue(url.contains("&order=actualizado_en.desc,id.desc&limit=21"), url)
        assertNoQuestionTables()
    }

    @Test
    fun myExamsUsesTheJwtSubjectOnlyAndRejectsAnyUserInTheQuery() = testApplication {
        application { catalogModule() }
        // V1 (E5): un usuario en el query ya no se ignora en silencio, se RECHAZA (400 UNKNOWN_FIELD) y no sale ninguna lectura.
        val forged = client.getAs("/api/v1/app/me/examenes?vista=EN_CURSO&usuarioId=dddddddd-dddd-4ddd-8ddd-000000000009&userId=x")
        assertEquals(HttpStatusCode.BadRequest, forged.status, forged.bodyAsText())
        assertTrue(upstream.sent.isEmpty(), "una consulta con usuario forjado no debe llegar a la base: ${upstream.urls}")
        client.getAs("/api/v1/app/me/examenes?vista=EN_CURSO")
        val url = upstream.urls.single { it.contains("intentos_examen") }
        assertTrue(url.contains("usuario_id=eq.$TEST_UID"))
        assertFalse(url.contains("dddddddd"))
    }

    @Test
    fun adquiridosListsActiveGrantsWithTheLatestAttemptAndHasNoGroups() = testApplication {
        application { catalogModule() }
        upstream.myGrants = {
            "[" + """{"id":"$grantId","valido_desde":"2026-09-20T10:00:00+00:00","examenes":${exam(e1, acceso = "premium", comunidad = "MAD")}},""" +
                """{"id":"cccccccc-cccc-4ccc-8ccc-000000000002","valido_desde":"2026-09-10T10:00:00+00:00","examenes":${exam(e2, acceso = "premium", comunidad = "MAD")}}]"""
        }
        upstream.grantFacts = "[${grantFact(e1)},${grantFact(e2)}]"
        upstream.latestAttempts = """[{"id":"$intento","examen_id":"$e1","estado":"finalizado","total_respondidas":10,"total_preguntas":10}]"""
        val adquiridos = client.getAs("/api/v1/app/me/examenes?vista=ADQUIRIDOS").bodyAsText()
        assertConforms("D2-13", adquiridos)
        val items = JsonObjectOf(adquiridos)["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("GRANTED", "GRANTED"), items.map { it["accessState"]!!.jsonPrimitive.content })
        assertEquals("finalizado", items[0]["estadoIntento"]!!.jsonPrimitive.content)
        assertEquals(1.0, items[0]["progreso"]!!.jsonPrimitive.content.toDouble())
        assertNull(items[1]["intentoId"])
        assertNull(JsonObjectOf(adquiridos)["grupos"], "los grupos son de COLECCION")
        val grants = upstream.urls.last { it.contains("/rest/v1/contenido_desbloqueado_usuario?select=id,valido_desde") }
        assertTrue(grants.contains("&usuario_id=eq.$TEST_UID&examen_id=not.is.null"), grants)
        assertTrue(grants.contains("&valido_desde=lte.2026-10-08T12:00:00Z&and=(or(valido_hasta.is.null,valido_hasta.gt.2026-10-08T12:00:00Z))"), "solo concesiones vigentes: $grants")
        assertTrue(grants.contains("&order=valido_desde.desc,id.desc&limit=21"), grants)
        assertTrue(upstream.collectionCalls.isEmpty(), "ADQUIRIDOS no usa la funcion de la coleccion")
    }

    // ------------------------------------------------------------------- D2-13 vista = COLECCION (20261008100300)

    @Test
    fun coleccionIsTheUnionOfGrantedAndAttemptedExamsWithThePolicyAccessStateAndExactGroups() = testApplication {
        application { catalogModule() }
        // Lo que devuelve la funcion: UN elemento por examen, ya ordenado. e1 concedido + intento abierto; e2 SOLO intentado
        // (sin concesion: antes no aparecia nunca en COLECCION); e3 SOLO concedido; e4 intentado y gratuito, sin comunidad.
        upstream.collection = collectionOf(
            listOf(
                CollectionEntry(e1, "2026-10-07T09:00:00.5+00:00", exam(e1, acceso = "premium", comunidad = "MAD", cover = "exam-covers/$e1/a.png"), attemptRef(intento, "en_progreso", 4, 8)),
                CollectionEntry(e2, "2026-10-06T09:00:00+00:00", exam(e2, acceso = "premium", comunidad = "MAD"), attemptRef("bbbbbbbb-bbbb-4bbb-8bbb-000000000002", "finalizado", 10, 10)),
                CollectionEntry(e3, "2026-09-20T10:00:00+00:00", exam(e3, acceso = "premium", comunidad = "AND")),
                CollectionEntry(e4, "2026-09-01T10:00:00+00:00", exam(e4, acceso = "gratuito", comunidad = null), attemptRef("bbbbbbbb-bbbb-4bbb-8bbb-000000000004", "abandonado", 0, 0)),
            ),
            groups = """[{"comunidad_codigo":"AND","total":1},{"comunidad_codigo":"MAD","total":2}]""",
        )
        upstream.grantFacts = "[${grantFact(e1)},${grantFact(e3, motivo = "regalo")}]"
        upstream.signed = """[{"path":"exam-covers/$e1/a.png","signedURL":"/object/sign/exam-resources/exam-covers/$e1/a.png?token=t"}]"""
        val response = client.getAs("/api/v1/app/me/examenes?vista=COLECCION")
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        assertEquals("private, max-age=30", response.headers[HttpHeaders.CacheControl])
        assertConforms("D2-13", body)
        val page = JsonObjectOf(body)
        val items = page["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(e1, e2, e3, e4), page.examIds(), "un elemento por examen, en el orden de la funcion")
        // accessState sale de la MISMA EntitlementPolicy: concedido / intentado pero ya sin derecho / concedido / gratuito.
        assertEquals(listOf("GRANTED", "LOCKED", "GRANTED", "FREE"), items.map { it["accessState"]!!.jsonPrimitive.content })
        assertEquals(listOf(intento, "bbbbbbbb-bbbb-4bbb-8bbb-000000000002", null, "bbbbbbbb-bbbb-4bbb-8bbb-000000000004"), items.map { it["intentoId"]?.jsonPrimitive?.content })
        assertEquals(listOf("en_progreso", "finalizado", null, "abandonado"), items.map { it["estadoIntento"]?.jsonPrimitive?.content })
        assertEquals(listOf(0.5, 1.0, null, null), items.map { it["progreso"]?.jsonPrimitive?.content?.toDouble() }, "sin intento o sin preguntas no se inventa progreso")
        assertTrue(items.all { it["nota"] == null && it["calificacion"] == null }, "DEC-013 / Q-08: sin nota ni calificacion")
        assertEquals("https://stub.supabase.test/storage/v1/object/sign/exam-resources/exam-covers/$e1/a.png?token=t", items[0]["examen"]!!.jsonObject["portadaUrl"]!!.jsonPrimitive.content)
        assertNull(items[3]["examen"]!!.jsonObject["comunidadCodigo"])
        assertEquals(setOf("id", "titulo", "tipoExamen", "comunidadCodigo", "comunidadNombre", "portadaUrl"), items[0]["examen"]!!.jsonObject.keys, "solo los campos del contrato")
        assertEquals(
            listOf("AND" to 1, "MAD" to 2),
            page["grupos"]!!.jsonArray.map { it.jsonObject["comunidadCodigo"]!!.jsonPrimitive.content to it.jsonObject["total"]!!.jsonPrimitive.content.toInt() },
            "los grupos son los de la funcion (todo el conjunto filtrado), no un recuento de la pagina",
        )
        assertNull(page["nextCursor"])

        // UNA llamada a la funcion, con el `sub` del JWT y sin filtros; ninguna lectura directa "parcial" de concesiones o intentos.
        val call = upstream.collectionCalls.single()
        assertEquals(
            setOf("p_usuario_id", "p_tipo_examen", "p_comunidad_id", "p_anio", "p_despues_en", "p_despues_id", "p_limite", "p_con_grupos", "p_especialidad_id"),
            call.keys,
        )
        assertEquals(TEST_UID, call["p_usuario_id"]!!.jsonPrimitive.content)
        listOf("p_tipo_examen", "p_comunidad_id", "p_anio", "p_despues_en", "p_despues_id").forEach {
            assertEquals(kotlinx.serialization.json.JsonNull, call[it], it)
        }
        assertEquals("21", call["p_limite"]!!.jsonPrimitive.content, "limit + 1 para saber si hay mas")
        assertEquals("true", call["p_con_grupos"]!!.jsonPrimitive.content, "primera pagina: con grupos")
        assertTrue(upstream.urls.none { it.contains("contenido_desbloqueado_usuario?select=id,valido_desde") || it.contains("/rest/v1/intentos_examen") }, upstream.urls.toString())
        // Hechos de acceso por LOTES para la pagina (una lectura por tabla).
        assertEquals(1, upstream.urls.count { it.contains("/rest/v1/estado_premium_usuario") })
        assertTrue(upstream.urls.single { it.contains("/rest/v1/contenido_desbloqueado_usuario") }.contains("usuario_id=eq.$TEST_UID&examen_id=in.($e1,$e2,$e3,$e4)"))
        assertNoQuestionTables()
    }

    @Test
    fun coleccionSendsTheValidatedFiltersAndOnlyTheJwtSubjectToTheReadRpc() = testApplication {
        application { catalogModule() }
        val foreign = "dddddddd-dddd-4ddd-8ddd-000000000009"
        val page = client.myExams("vista=COLECCION&tipoExamen=guiado&comunidadId=${com.uppercase()}&anio=2024&limit=500&especialidadId=$foreign")
        assertEquals(emptyList(), page.examIds())
        assertEquals(JsonArray(emptyList()), page["grupos"], "coleccion vacia: grupos vacios, no null")
        val call = upstream.collectionCalls.single()
        assertEquals(TEST_UID, call["p_usuario_id"]!!.jsonPrimitive.content, "el usuario es SIEMPRE el sub del JWT")
        assertEquals("razonado", call["p_tipo_examen"]!!.jsonPrimitive.content, "el sinonimo legacy se normaliza")
        assertEquals(com, call["p_comunidad_id"]!!.jsonPrimitive.content)
        assertEquals("2024", call["p_anio"]!!.jsonPrimitive.content)
        assertEquals("51", call["p_limite"]!!.jsonPrimitive.content, "limit recortado a 50 (+1)")
        assertFalse(call.toString().contains("dddddddd"), "ningun usuario del query llega a la funcion: $call")
        assertEquals(2, upstream.sent.size, "coleccion vacia: la lectura de la especialidad del usuario y la funcion; ni derechos ni portadas")
    }

    @Test
    fun coleccionCursorWalksEveryPageWithTiesWithoutDuplicatesOrGapsAndIsBoundToViewAndFilters() = testApplication {
        application { catalogModule() }
        // Cinco examenes; tres comparten EXACTAMENTE la misma clave de orden (el desempate es el id del examen).
        val tie = "2026-10-05T08:00:00.123456+00:00"
        val entries = listOf(
            CollectionEntry(e2, tie, exam(e2)),
            CollectionEntry(e4, "2026-10-07T09:00:00+00:00", exam(e4), attemptRef(intento, "en_progreso", 1, 4)),
            CollectionEntry(e1, tie, exam(e1)),
            CollectionEntry(e3, tie, exam(e3)),
            CollectionEntry("aaaaaaaa-aaaa-4aaa-8aaa-000000000005", "2026-09-01T00:00:00+00:00", exam("aaaaaaaa-aaaa-4aaa-8aaa-000000000005")),
        )
        upstream.collection = collectionOf(entries, groups = """[{"comunidad_codigo":"MAD","total":5}]""")
        val seen = mutableListOf<String>()
        val cursors = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = client.myExams("vista=COLECCION&limit=2" + (cursor?.let { "&cursor=$it" } ?: ""))
            pages++
            assertTrue(page.examIds().size <= 2, "nunca mas de limit")
            // Los grupos (de TODO el conjunto) solo con la primera pagina; despues null.
            if (cursor == null) assertEquals("5", page["grupos"]!!.jsonArray[0].jsonObject["total"]!!.jsonPrimitive.content) else assertNull(page["grupos"])
            seen += page.examIds()
            cursor = page["nextCursor"]?.jsonPrimitive?.content?.also { cursors += it }
        } while (cursor != null && pages < 10)
        assertEquals(listOf(e4, e3, e2, e1, "aaaaaaaa-aaaa-4aaa-8aaa-000000000005"), seen, "todas, una vez, en orden (orden_en desc, id desc)")
        assertEquals(3, pages)
        val calls = upstream.collectionCalls
        assertEquals(listOf("3", "3", "3"), calls.map { it["p_limite"]!!.jsonPrimitive.content })
        assertEquals(listOf("true", "false", "false"), calls.map { it["p_con_grupos"]!!.jsonPrimitive.content })
        // El cursor lleva la clave EXACTA (microsegundos incluidos) del ultimo elemento servido: aqui, en mitad del empate.
        assertEquals(tie, calls[1]["p_despues_en"]!!.jsonPrimitive.content)
        assertEquals(e3, calls[1]["p_despues_id"]!!.jsonPrimitive.content)
        assertEquals(tie, calls[2]["p_despues_en"]!!.jsonPrimitive.content)
        assertEquals(e1, calls[2]["p_despues_id"]!!.jsonPrimitive.content)

        // El cursor de COLECCION no vale en otra vista ni con otros filtros, y nada de eso llega al upstream.
        val before = upstream.sent.size
        listOf("vista=ADQUIRIDOS&limit=2", "vista=EN_CURSO&limit=2", "vista=COLECCION&limit=2&anio=2020", "vista=COLECCION&limit=2&tipoExamen=rafaga").forEach {
            val response = client.getAs("/api/v1/app/me/examenes?$it&cursor=${cursors.first()}")
            assertEquals(HttpStatusCode.BadRequest, response.status, it)
            assertEquals("INVALID_CURSOR", code(response.bodyAsText()), it)
        }
        // Un cursor bien firmado para COLECCION pero con claves que no son [instante, uuid] tampoco llega a la funcion.
        val scope = CursorScope(
            listOf("orden_en desc", "id desc"),
            mapOf("vista" to "COLECCION", "tipoExamen" to null, "especialidadId" to null, "comunidadId" to null, "anio" to null),
        )
        val wellFormed = PageCursorCodec.encode(listOf(kotlinx.serialization.json.JsonPrimitive(tie), kotlinx.serialization.json.JsonPrimitive(e3)), scope.fingerprint())
        assertEquals(HttpStatusCode.OK, client.getAs("/api/v1/app/me/examenes?vista=COLECCION&cursor=$wellFormed").status, "control: la huella construida aqui es la de la ruta")
        val accepted = upstream.sent.size
        listOf(
            listOf(kotlinx.serialization.json.JsonPrimitive("2026-10-05T08:00:00+00:00'); drop"), kotlinx.serialization.json.JsonPrimitive(e3)),
            listOf(kotlinx.serialization.json.JsonPrimitive(tie), kotlinx.serialization.json.JsonPrimitive("not-a-uuid")),
            listOf(kotlinx.serialization.json.JsonPrimitive(tie)),
        ).forEach { keys ->
            val response = client.getAs("/api/v1/app/me/examenes?vista=COLECCION&cursor=${PageCursorCodec.encode(keys, scope.fingerprint())}")
            assertEquals("INVALID_CURSOR", code(response.bodyAsText()), keys.toString())
        }
        assertEquals(accepted, upstream.sent.size, "ningun cursor invalido llega al upstream")
        assertTrue(accepted > before, "solo el cursor valido de control llego")
    }

    @Test
    fun coleccionWithoutTheReadRpcInstalledIs503AndNeverFallsBackToThePartialAnswer() = testApplication {
        application { catalogModule() }
        // Hay concesiones que la respuesta parcial antigua habria servido: no se sirven.
        upstream.myGrants = { """[{"id":"$grantId","valido_desde":"2026-09-20T10:00:00+00:00","examenes":${exam(e1)}}]""" }
        upstream.collection = {
            V1StubResponse(404, """{"code":"PGRST202","details":"Searched for the function public.app_my_exams_collection","hint":null,"message":"Could not find the function"}""")
        }
        val response = client.getAs("/api/v1/app/me/examenes?vista=COLECCION")
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status, body)
        assertEquals("UPSTREAM_UNAVAILABLE", code(body))
        assertFalse(body.contains("PGRST202") || body.contains("app_my_exams_collection") || body.contains("items"), "ni detalle del upstream ni datos parciales: $body")
        assertEquals(2, upstream.sent.size, "la especialidad del usuario y la funcion: sin lectura de respaldo")
        assertTrue(upstream.urls.single { it.contains(Upstream.COLLECTION_RPC) }.endsWith(Upstream.COLLECTION_RPC))
        // Las otras dos vistas no dependen de la funcion.
        assertEquals(HttpStatusCode.OK, client.getAs("/api/v1/app/me/examenes?vista=ADQUIRIDOS").status)
        assertEquals(HttpStatusCode.OK, client.getAs("/api/v1/app/me/examenes?vista=EN_CURSO").status)
    }

    @Test
    fun coleccionUpstreamFailuresAndMalformedRowsAreCanonicalErrorsWithoutPartialData() = testApplication {
        application { catalogModule() }
        // Un PGRST202 que solo aparece como TEXTO (eco de un dato) no es "falta la funcion": es un fallo normal del upstream.
        upstream.collection = { V1StubResponse(500, """{"code":"XX000","details":"PGRST202","hint":null,"message":"boom"}""") }
        var response = client.getAs("/api/v1/app/me/examenes?vista=COLECCION")
        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertEquals("UPSTREAM_ERROR", code(response.bodyAsText()))
        assertFalse(response.bodyAsText().contains("boom"), "el detalle del upstream no sale")

        val good = CollectionEntry(e1, "2026-10-07T09:00:00+00:00", exam(e1)).json
        listOf(
            """[]""",
            """"nope"""",
            """{"grupos":[]}""",
            """{"items":[{"orden_en":"ayer","examenes":${exam(e1)},"intento":null}],"grupos":null}""",
            """{"items":[{"orden_en":"2026-10-07T09:00:00+00:00","examenes":${exam("not-a-uuid")},"intento":null}],"grupos":null}""",
            """{"items":[$good,{"orden_en":"2026-10-07T09:00:00+00:00","intento":null}],"grupos":null}""",
        ).forEach { malformed ->
            upstream.collection = { V1StubResponse(200, malformed) }
            response = client.getAs("/api/v1/app/me/examenes?vista=COLECCION")
            assertEquals(HttpStatusCode.BadGateway, response.status, malformed)
            assertEquals("UPSTREAM_CONTRACT_ERROR", code(response.bodyAsText()), malformed)
        }
        // Un tipo de examen que el contrato no conoce tampoco se sirve.
        upstream.collection = { V1StubResponse(200, """{"items":[{"orden_en":"2026-10-07T09:00:00+00:00","examenes":${exam(e1, tipo = "otro")},"intento":null}],"grupos":null}""") }
        assertEquals("UPSTREAM_CONTRACT_ERROR", code(client.getAs("/api/v1/app/me/examenes?vista=COLECCION").bodyAsText()))
        // Un estado de intento desconocido no se emite (el contrato cierra el enum): el elemento sale sin estado.
        upstream.collection = collectionOf(listOf(CollectionEntry(e1, "2026-10-07T09:00:00+00:00", exam(e1), attemptRef(intento, "raro", 1, 2))))
        val item = client.myExams("vista=COLECCION")["items"]!!.jsonArray[0].jsonObject
        assertNull(item["estadoIntento"])
        assertEquals(intento, item["intentoId"]!!.jsonPrimitive.content)
    }

    @Test
    fun myExamsCursorRoundTripsAndIsBoundToTheView() = testApplication {
        application { catalogModule() }
        val rows = listOf(
            """{"id":"$grantId","valido_desde":"2026-09-20T10:00:00.250000+00:00","examenes":${exam(e1)}}""",
            """{"id":"cccccccc-cccc-4ccc-8ccc-000000000002","valido_desde":"2026-09-10T10:00:00+00:00","examenes":${exam(e2)}}""",
        )
        upstream.myGrants = { uri -> if (uri.contains("valido_desde.lt.")) "[${rows[1]}]" else "[${rows.joinToString(",")}]" }
        val first = JsonObjectOf(client.getAs("/api/v1/app/me/examenes?vista=ADQUIRIDOS&limit=1").bodyAsText())
        val cursor = first["nextCursor"]!!.jsonPrimitive.content
        val second = client.getAs("/api/v1/app/me/examenes?vista=ADQUIRIDOS&limit=1&cursor=$cursor")
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(e2, JsonObjectOf(second.bodyAsText())["items"]!!.jsonArray[0].jsonObject["examen"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertNull(JsonObjectOf(second.bodyAsText())["nextCursor"])
        val keyset = upstream.urls.last { it.contains("/rest/v1/contenido_desbloqueado_usuario?select=id,valido_desde") }
        assertTrue(keyset.contains("or(valido_desde.lt.2026-09-20T10:00:00.250000+00:00,and(valido_desde.eq.2026-09-20T10:00:00.250000+00:00,id.lt.$grantId))"), keyset)
        // El mismo cursor en OTRA vista (u otro filtro) es 400 INVALID_CURSOR.
        listOf("vista=COLECCION&cursor=$cursor", "vista=EN_CURSO&cursor=$cursor", "vista=ADQUIRIDOS&anio=2020&cursor=$cursor").forEach {
            val response = client.getAs("/api/v1/app/me/examenes?$it")
            assertEquals("INVALID_CURSOR", code(response.bodyAsText()), it)
        }
    }

    @Test
    fun myExamsValidatesVistaAndEveryOtherParameter() = testApplication {
        application { catalogModule() }
        val missing = client.getAs("/api/v1/app/me/examenes")
        assertEquals(HttpStatusCode.BadRequest, missing.status)
        assertEquals("VALIDATION", code(missing.bodyAsText()))
        listOf("vista=en_curso", "vista=TODOS", "vista=EN_CURSO&limit=0", "vista=EN_CURSO&tipoExamen=x", "vista=EN_CURSO&comunidadId=x", "vista=EN_CURSO&anio=x").forEach {
            val response = client.getAs("/api/v1/app/me/examenes?$it")
            assertEquals(HttpStatusCode.BadRequest, response.status, it)
            assertEquals("VALIDATION", code(response.bodyAsText()), it)
        }
        assertTrue(upstream.sent.isEmpty())
    }

    // =================================================================== D7-02

    @Test
    fun entitlementWithoutASubscriptionIsSinSuscripcionWithCredits() = testApplication {
        application { catalogModule() }
        upstream.credits = """[{"tipo_examen":"inmersivo","cantidad_total":3,"cantidad_usada":1,"valido_hasta":"2026-11-01T00:00:00+00:00"},""" +
            """{"tipo_examen":"inmersivo","cantidad_total":2,"cantidad_usada":2,"valido_hasta":"2026-12-01T00:00:00+00:00"},""" +
            """{"tipo_examen":"rafaga","cantidad_total":5,"cantidad_usada":9,"valido_hasta":"2026-12-01T00:00:00+00:00"}]"""
        val response = client.getAs("/api/v1/app/me/entitlement")
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        assertEquals("private, no-store", response.headers[HttpHeaders.CacheControl])
        assertConforms("D7-02", body)
        val ent = JsonObjectOf(body)
        assertFalse(ent.containsKey("data"), "objeto desnudo, sin envoltorio data")
        assertEquals("false", ent["isPremium"]!!.jsonPrimitive.content)
        assertEquals("sin_suscripcion", ent["status"]!!.jsonPrimitive.content)
        val credits = ent["credits"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("rafaga", "inmersivo"), credits.map { it["tipoExamen"]!!.jsonPrimitive.content })
        assertEquals(listOf(0, 2), credits.map { it["restantes"]!!.jsonPrimitive.content.toInt() }, "usadas > total se acota: restantes nunca negativo")
        assertEquals(listOf(5, 5), credits.map { it["total"]!!.jsonPrimitive.content.toInt() })
        assertEquals(listOf(5, 3), credits.map { it["usados"]!!.jsonPrimitive.content.toInt() })
        assertEquals("2026-11-01T00:00:00Z", credits[1]["validoHasta"]!!.jsonPrimitive.content, "vence antes el primer lote vigente")
        val creditsUrl = upstream.urls.single { it.contains("creditos_contenido_usuario") }
        assertTrue(creditsUrl.contains("usuario_id=eq.$TEST_UID&valido_desde=lte.2026-10-08T12:00:00Z&valido_hasta=gt.2026-10-08T12:00:00Z"), creditsUrl)
    }

    @Test
    fun anActiveSubscriptionIsReportedWithItsPlan() = testApplication {
        application { catalogModule() }
        upstream.premium = premiumRow()
        upstream.plan = """[{"planes_suscripcion":{"codigo":"pro","nivel":2}}]"""
        val body = client.getAs("/api/v1/app/me/entitlement").bodyAsText()
        assertConforms("D7-02", body)
        val ent = JsonObjectOf(body)
        assertEquals("true", ent["isPremium"]!!.jsonPrimitive.content)
        assertEquals("activa", ent["status"]!!.jsonPrimitive.content)
        assertEquals("pro", ent["planCodigo"]!!.jsonPrimitive.content)
        assertEquals("2", ent["planNivel"]!!.jsonPrimitive.content)
        assertEquals("stripe", ent["proveedor"]!!.jsonPrimitive.content)
        assertEquals("2099-01-01T00:00:00Z", ent["endsAt"]!!.jsonPrimitive.content)
        assertEquals(JsonArray(emptyList()), ent["credits"])
        assertTrue(upstream.urls.single { it.contains("productos_comerciales") }.contains("codigo=eq.plan-pro"))
    }

    @Test
    fun aLapsedSubscriptionIsNotPremiumAndIsReportedAsCaducada() = testApplication {
        application { catalogModule() }
        upstream.premium = premiumRow(end = "2026-10-01T00:00:00+00:00")
        val body = client.getAs("/api/v1/app/me/entitlement").bodyAsText()
        assertConforms("D7-02", body)
        val ent = JsonObjectOf(body)
        assertEquals("false", ent["isPremium"]!!.jsonPrimitive.content)
        assertEquals("caducada", ent["status"]!!.jsonPrimitive.content)
        assertNull(ent["renewsAt"])
    }

    @Test
    fun theEntitlementAndAccessStateShareTheSamePremiumRule() = testApplication {
        application { catalogModule() }
        upstream.exams = { "[${exam(e1, acceso = "premium")}]" }
        listOf(premiumRow(), premiumRow(end = "2026-10-01T00:00:00+00:00"), premiumRow(estado = "pausada"), premiumRow(flag = false)).forEach { row ->
            upstream.premium = row
            val premium = JsonObjectOf(client.getAs("/api/v1/app/me/entitlement").bodyAsText())["isPremium"]!!.jsonPrimitive.content == "true"
            val state = JsonObjectOf(client.getAs("/api/v1/app/examenes").bodyAsText())["items"]!!.jsonArray[0].jsonObject["accessState"]!!.jsonPrimitive.content
            assertEquals(premium, state == "SUBSCRIPTION", row)
        }
    }

    // =================================================================== autenticacion y cuenta (todas las rutas)

    private val allPaths = listOf(
        "/api/v1/app/examenes",
        "/api/v1/app/examenes/$e1",
        "/api/v1/app/me/examenes?vista=EN_CURSO",
        "/api/v1/app/me/examenes?vista=COLECCION",
        "/api/v1/app/me/entitlement",
    )

    @Test
    fun withoutAValidTokenEveryRouteIs401AndTheUpstreamIsNeverTouched() = testApplication {
        application { catalogModule() }
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
    fun aBlockedPendingDeletionOrUnknownAccountFailsClosedOnEveryRouteWithoutReadingCatalogData() = testApplication {
        application { catalogModule() }
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
        assertTrue(upstream.sent.isEmpty(), "identidad denegada: ni una lectura de catalogo ni de derechos: ${upstream.urls}")
    }

    @Test
    fun aMissingRuntimeFailsClosedInsteadOfServing() = testApplication {
        application {
            install(ContentNegotiation) { json(testJson) }
            installRequestCorrelation()
            installBackendStatusPages()
            routing { catalogRoutes(null) }
        }
        allPaths.forEach { assertEquals(HttpStatusCode.InternalServerError, client.getAs(it).status, it) }
    }

    @Test
    fun noListQueryEverNamesQuestionTablesAcrossAllRoutes() = testApplication {
        application { catalogModule() }
        upstream.exams = { "[${exam(e1, acceso = "premium")}]" }
        upstream.examById = { "[${exam(e1, acceso = "premium")}]" }
        upstream.attempts = { """[{"id":"$intento","estado":"en_progreso","total_respondidas":1,"total_preguntas":2,"actualizado_en":"2026-10-07T09:00:00+00:00","examenes":${exam(e1)}}]""" }
        upstream.myGrants = { """[{"id":"$grantId","valido_desde":"2026-09-20T10:00:00+00:00","examenes":${exam(e1)}}]""" }
        upstream.collection = collectionOf(listOf(CollectionEntry(e1, "2026-10-07T09:00:00+00:00", exam(e1), attemptRef(intento, "finalizado", 2, 2))))
        allPaths.plus("/api/v1/app/me/examenes?vista=ADQUIRIDOS").plus("/api/v1/app/examenes?includeFacets=true").forEach {
            assertEquals(HttpStatusCode.OK, client.getAs(it).status, it)
        }
        assertNoQuestionTables()
        assertNotNull(upstream.sent.firstOrNull())
    }
}
