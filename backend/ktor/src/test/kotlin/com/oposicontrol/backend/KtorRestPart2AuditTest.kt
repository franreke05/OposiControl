package com.oposicontrol.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Auditoria global, KTOR_REST parte 2: MASTER-ARCH-008, API-001/003/004/005/006/007/008/009/012/013/015/016 y
 * OPENAPI-002. Los modulos de prueba usan el MISMO Json que produccion (sin `encodeDefaults`), y las aserciones sobre
 * el cuerpo miran el JSON crudo, no una decodificacion que rellene los defectos.
 */
class KtorRestPart2AuditTest {

    private val u1 = "11111111-1111-4111-8111-111111111111"
    private val roleAdmin = "aaaaaaaa-0000-4000-8000-aaaaaaaaaaaa"
    private val roleUser = "bbbbbbbb-0000-4000-8000-bbbbbbbbbbbb"

    // ------------------------------------------------------------------ helpers (perfil canonico)

    private suspend fun HttpClient.call(
        method: HttpMethod,
        path: String,
        body: String? = null,
        token: String? = TEST_TOKEN,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse = request(path) {
        this.method = method
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        headers.forEach { (k, v) -> header(k, v) }
        if (body != null && method != HttpMethod.Get) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun env(setup: CanonicalTestEnv.() -> Unit = {}) = CanonicalTestEnv().apply {
        upstream.onGet = { path -> if ("perfiles_usuario" in path) profileRowJson() else "[]" }
        setup()
    }

    private fun ApplicationTestBuilder.mount(env: CanonicalTestEnv) = application { env.mount(this) }

    private fun legalRow(id: String, version: String, contenido: String, publicadoEn: String) =
        """{"id":"$id","tipo_documento":"terminos","version":"$version","titulo":"T","contenido":"$contenido","publicado_en":"$publicadoEn"}"""

    // ------------------------------------------------------------------ MASTER-ARCH-008

    private class Closing(var closed: Int = 0) : OposiBotsAuthRateLimiter {
        override suspend fun checkIp(operation: OposiBotsAuthOperation, clientIp: String) = OposiBotsRateLimitDecision(true)
        override suspend fun checkSubject(operation: OposiBotsAuthOperation, subject: String) = OposiBotsRateLimitDecision(true)
        override fun close() { closed++ }
    }

    @Test
    fun closingTheAuthRuntimeClosesTheRateLimiterAndTheOwnedGatewayEvenIfTheFirstCloseFails() {
        val limiter = Closing()
        val base = CanonicalTestEnv(limiter = limiter)
        val gatewayClosed = booleanArrayOf(false)
        val gateway = object : OposiBotsAuthGateway by NoAuthGateway, AutoCloseable {
            override fun close() { gatewayClosed[0] = true }
        }
        val runtime = OposiBotsAuthRuntime(testCanonicalConfiguration(), gateway, limiter, testJson, base.runtime().canonical)
        runtime.close()
        assertEquals(1, limiter.closed)
        assertTrue(gatewayClosed[0])

        val failing = object : OposiBotsAuthRateLimiter {
            override suspend fun checkIp(operation: OposiBotsAuthOperation, clientIp: String) = OposiBotsRateLimitDecision(true)
            override suspend fun checkSubject(operation: OposiBotsAuthOperation, subject: String) = OposiBotsRateLimitDecision(true)
            override fun close() = error("boom")
        }
        gatewayClosed[0] = false
        OposiBotsAuthRuntime(testCanonicalConfiguration(), gateway, failing, testJson, base.runtime().canonical).close()
        assertTrue(gatewayClosed[0], "un fallo al cerrar el limitador no debe dejar el gateway abierto")
    }

    // ------------------------------------------------------------------ MASTER-API-001

    @Test
    fun registerAcceptedBodyCarriesVerificationRequiredOnTheWireWithTheProductionJson() {
        val production = Json { ignoreUnknownKeys = true; explicitNulls = false; prettyPrint = false }
        val raw = production.encodeToString(OposiBotsRegisterResponse.serializer(), OposiBotsRegisterResponse())
        val element = Json.parseToJsonElement(raw).jsonObject
        assertEquals("true", element["verificationRequired"]?.jsonPrimitive?.content, "required en RegisterAccepted: no puede faltar ($raw)")
    }

    // ------------------------------------------------------------------ MASTER-API-003

    @Test
    fun aPostgrestOutageOnACanonicalReadIs503UpstreamUnavailableNot502() = testApplication {
        val env = env { upstream.onGet = { error("Supabase GET /rest/v1/perfiles_usuario -> 503: down") } }
        mount(env)
        val response = client.call(HttpMethod.Get, "/api/v1/app/me")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("UPSTREAM_UNAVAILABLE", response.json().str("code"))
    }

    @Test
    fun aPostgrestOutageInACommandRpcIs503UpstreamUnavailable() = testApplication {
        val env = env { upstream.onRpc = { _, _ -> SupabaseRawResponse(503, "upstream connect error") } }
        mount(env)
        val body = """{"username":"ada_l","nombre":"Ada","apellidos":"Lovelace","especialidadId":"$TEST_SPECIALTY","acceptedLegalDocumentIds":["$TEST_DOCUMENT"],"attribution":{"plataforma":"android"}}"""
        val response = client.call(HttpMethod.Post, "/api/v1/app/me/onboarding", body)
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("UPSTREAM_UNAVAILABLE", response.json().str("code"))
    }

    // ------------------------------------------------------------------ MASTER-API-006

    @Test
    fun anExplicitNullInOnboardingIs400InvalidFormatOnThatField() = testApplication {
        val env = env()
        mount(env)
        val body = """{"username":"ada_l","nombre":"Ada","apellidos":"L","especialidadId":null,"acceptedLegalDocumentIds":["$TEST_DOCUMENT"]}"""
        val response = client.call(HttpMethod.Post, "/api/v1/app/me/onboarding", body)
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val issue = response.json()["details"]!!.jsonObject["issues"]!!.jsonArray.single().jsonObject
        assertEquals("especialidadId", issue.str("field"))
        assertEquals("INVALID_FORMAT", issue.str("issue"))
        assertTrue(env.upstream.rpcCalls.isEmpty(), "nada llega a la base")
    }

    @Test
    fun anExplicitNullInAccountDeletionIs400AndNestedNullsReportTheirPath() = testApplication {
        val env = env()
        mount(env)
        val response = client.call(HttpMethod.Delete, "/api/v1/app/me", """{"confirmation":"ELIMINAR","currentPassword":null}""")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("currentPassword", response.json()["details"]!!.jsonObject["issues"]!!.jsonArray.single().jsonObject.str("field"))

        val nested = client.call(
            HttpMethod.Post,
            "/api/v1/app/me/onboarding",
            """{"username":"a","nombre":"A","apellidos":"B","acceptedLegalDocumentIds":["$TEST_DOCUMENT"],"attribution":{"fuente":null}}""",
        )
        assertEquals(HttpStatusCode.BadRequest, nested.status)
        assertEquals("attribution.fuente", nested.json()["details"]!!.jsonObject["issues"]!!.jsonArray.single().jsonObject.str("field"))
    }

    @Test
    fun theExplicitNullCheckNeverEchoesAnUnsafeClientKey() {
        val failure = assertFailsWith<BackendDomainException> {
            decodeCanonicalCommand(serializer<AccountDeletionRequest>(), """{"confirmation":"ELIMINAR","<script>":null}""".toByteArray())
        }
        assertEquals("VALIDATION", failure.errorCode)
        assertFalse(failure.details.toString().contains("script"))
    }

    // ------------------------------------------------------------------ MASTER-API-007

    @Test
    fun theLegalEtagChangesWhenContentOrIdChangeEvenIfNewestDateAndCountDoNot() {
        fun etag(id: String, contenido: String, version: String = "1") =
            legalDocumentsEtag(listOf(LegalDocument(id, "terminos", version, "T", contenido, "2026-09-01T00:00:00Z")))
        val base = etag(TEST_DOCUMENT, "texto")
        assertNotEquals(base, etag(TEST_DOCUMENT, "texto editado"))
        assertNotEquals(base, etag("dddddddd-dddd-4ddd-8ddd-dddddddddddd", "texto"))
        assertNotEquals(base, etag(TEST_DOCUMENT, "texto", version = "2"))
        assertEquals(base, etag(TEST_DOCUMENT, "texto"))
    }

    @Test
    fun legalDocumentsRevalidateWithWeakListAndStarIfNoneMatchAndAreNotCachedForAnHour() = testApplication {
        val row = legalRow(TEST_DOCUMENT, "2026-09", "texto", "2026-09-01T00:00:00+00:00")
        val env = env { upstream.onGet = { "[$row]" } }
        mount(env)
        val url = "/api/v1/app/legal/documents"
        val first = client.call(HttpMethod.Get, url, token = null)
        val etag = first.headers[HttpHeaders.ETag]!!
        assertEquals("public, max-age=300, must-revalidate", first.headers[HttpHeaders.CacheControl])

        for (header in listOf("W/$etag", "\"other\", $etag", "\"a\" , W/$etag", "*")) {
            assertEquals(HttpStatusCode.NotModified, client.call(HttpMethod.Get, url, token = null, headers = mapOf("If-None-Match" to header)).status, header)
        }
        assertEquals(HttpStatusCode.OK, client.call(HttpMethod.Get, url, token = null, headers = mapOf("If-None-Match" to "\"other\"")).status)

        // El texto legal se edita con la misma fecha y el mismo numero de documentos: el ETag anterior ya no vale.
        val edited = legalRow(TEST_DOCUMENT, "2026-09", "texto CORREGIDO", "2026-09-01T00:00:00+00:00")
        env.upstream.onGet = { "[$edited]" }
        val after = client.call(HttpMethod.Get, url, token = null, headers = mapOf("If-None-Match" to etag))
        assertEquals(HttpStatusCode.OK, after.status, "un ETag viejo no puede dar 304 sobre contenido distinto")
        assertNotEquals(etag, after.headers[HttpHeaders.ETag])
    }

    @Test
    fun ifNoneMatchParsingFollowsRfc9110() {
        val tag = "\"legal-abc\""
        assertTrue(ifNoneMatchMatches(tag, tag))
        assertTrue(ifNoneMatchMatches("W/$tag", tag))
        assertTrue(ifNoneMatchMatches("\"x\",$tag", tag))
        assertTrue(ifNoneMatchMatches("*", tag))
        assertFalse(ifNoneMatchMatches("\"x\"", tag))
        assertFalse(ifNoneMatchMatches("", tag))
        assertFalse(ifNoneMatchMatches(null, tag))
    }

    // ------------------------------------------------------------------ MASTER-API-013

    @Test
    fun introSeenAtIsNormalizedToZLikeRegisteredAt() = testApplication {
        val row = profileRowJson().replace("\"perfil_intro_visto_en\":null", "\"perfil_intro_visto_en\":\"2026-09-30T10:00:00.5+00:00\"")
        val env = env { upstream.onGet = { path -> if ("perfiles_usuario" in path) row else "[]" } }
        mount(env)
        val me = client.call(HttpMethod.Get, "/api/v1/app/me").json()
        assertEquals("2026-09-30T10:00:00.500Z", me["profile"]!!.jsonObject.str("introSeenAt"))
        assertEquals("2026-01-02T03:04:05.123456Z", me.str("registeredAt"))
    }

    // ------------------------------------------------------------------ admin harness (mismo Json que produccion)

    private class Sent(val method: String, val uri: String, val body: String? = null)

    private fun perfil(id: String, username: String, estado: String = "activa") =
        """{"usuario_id":"$id","username":"$username","nombre_mostrar":"N $username","es_invitado":false,"onboarding_completado":true,"estado_cuenta":"$estado","creado_en":"2026-01-02T03:04:05.123456+00:00"}"""

    private fun Application.adminModule(sent: CopyOnWriteArrayList<Sent>, responder: (String) -> V1StubResponse) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        val gateway = AdminV1Gateway.forTesting(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = V1StubHttpClient({ sent += Sent(it.method(), it.uri().toString()) }, responder),
        )
        routing { adminV1Routes(gateway) }
    }

    private fun issuesOf(body: String): List<Pair<String?, String?>> =
        Json.parseToJsonElement(body).jsonObject["details"]!!.jsonObject["issues"]!!.jsonArray.map { it.jsonObject.str("field") to it.jsonObject.str("issue") }

    private fun listResponder(holders: String = "[]"): (String) -> V1StubResponse = { uri ->
        when {
            "/rest/v1/perfiles_usuario?select=usuario_id" in uri -> V1StubResponse(200, "[]", contentRange = "0-0/1")
            "/rest/v1/perfiles_usuario" in uri -> V1StubResponse(200, "[${perfil(u1, "ada_l")}]")
            "/rest/v1/roles_app?select=id" in uri -> V1StubResponse(200, """[{"id":"$roleAdmin"}]""")
            "/rest/v1/roles_usuario?select=usuario_id" in uri -> V1StubResponse(200, holders)
            else -> V1StubResponse(200, "[]")
        }
    }

    // ------------------------------------------------------------------ MASTER-API-008

    @Test
    fun invalidLimitOrOffsetIs400OutOfRangeAndNeverReachesTheDatabase() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        application { adminModule(sent, listResponder()) }
        for (query in listOf("limit=abc", "limit=0", "limit=-3", "offset=-1", "offset=xyz", "offset=2147483647", "offset=100001")) {
            val response = client.get("/api/v1/admin/usuarios?$query")
            assertEquals(HttpStatusCode.BadRequest, response.status, query)
            assertEquals("OUT_OF_RANGE", issuesOf(response.bodyAsText()).single().second, query)
        }
        val both = client.get("/api/v1/admin/usuarios?limit=0&offset=-1")
        assertEquals(listOf("limit", "offset"), issuesOf(both.bodyAsText()).map { it.first })
        assertTrue(sent.isEmpty(), "ninguna peticion invalida sale a la base: ${sent.map { it.uri }}")
    }

    @Test
    fun validPagingStillWorksAndALimitAboveTheMaxIsClamped() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        application { adminModule(sent, listResponder()) }
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/usuarios").status)
        val page = Json.parseToJsonElement(client.get("/api/v1/admin/usuarios?limit=5000&offset=100000").bodyAsText()).jsonObject
        assertEquals("200", page["limit"]!!.jsonPrimitive.content)
        assertEquals("100000", page["offset"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------ MASTER-API-009 / 015

    @Test
    fun theRoleHolderCapKeepsTheFilterUrlShortAndRejectsAboveIt() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        val holders = { n: Int -> (1..n).joinToString(",", "[", "]") { """{"usuario_id":"$u1"}""" } }
        application { adminModule(sent, listResponder(holders(101))) }
        val over = client.get("/api/v1/admin/usuarios?roleCode=admin")
        assertEquals(HttpStatusCode.BadRequest, over.status)
        assertEquals("TOO_MANY_MATCHES", issuesOf(over.bodyAsText()).single().second)
        assertTrue(sent.none { "/rest/v1/perfiles_usuario" in it.uri })

        val ids = List(100) { u1 }
        val url = AdminUserListFilter().postgrestFilters(ids)
        assertTrue(url.length < 4_096, "100 UUID caben holgados en cualquier limite de URL de 8KB (${url.length})")
    }

    @Test
    fun aRoleWithExactlyTheCapOfHoldersIsStillServed() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        val holders = (1..100).joinToString(",", "[", "]") { """{"usuario_id":"$u1"}""" }
        application { adminModule(sent, listResponder(holders)) }
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/usuarios?roleCode=admin").status)
        assertTrue(sent.any { "/rest/v1/perfiles_usuario?select=*" in it.uri })
    }

    @Test
    fun anUnderscoreInQIsEscapedSoItIsNotAOneCharacterWildcard() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        application { adminModule(sent, listResponder()) }
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/usuarios?q=ada_l").status)
        val uri = sent.first { "/rest/v1/perfiles_usuario?select=*" in it.uri }.uri
        assertTrue("username.ilike.ada%5C_l*" in uri, "el _ va escapado como \\_ (%5C_): $uri")
        assertFalse("ada_l*" in uri.replace("ada%5C_l*", ""), uri)
        assertTrue(AdminUserListFilter(q = "a_b").postgrestFilters().contains("a%5C_b*"))
    }

    // ------------------------------------------------------------------ MASTER-API-004 / OPENAPI-002

    private fun detailResponder(): (String) -> V1StubResponse = { uri ->
        when {
            "/rest/v1/perfiles_usuario?select=*" in uri -> V1StubResponse(200, "[${perfil(u1, "ada_l")}]")
            "/auth/v1/admin/users" in uri -> V1StubResponse(200, """{"id":"$u1","email":"ada@example.com"}""")
            else -> V1StubResponse(200, "[]")
        }
    }

    @Test
    fun theDetailAlwaysEmitsItsRequiredArraysEvenWhenTheyAreEmpty() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        application { adminModule(sent, detailResponder()) }
        val body = Json.parseToJsonElement(client.get("/api/v1/admin/usuarios/$u1/detalle").bodyAsText()).jsonObject["data"]!!.jsonObject
        for (key in listOf("suscripciones", "debilidades", "seccionesNoDisponibles")) {
            assertTrue(key in body, "$key es required en AdminUserDetail y no puede faltar cuando esta vacio")
            assertEquals(0, body[key]!!.jsonArray.size, key)
        }
    }

    @Test
    fun sectionsReadsOnlyTheRequestedSectionsAndRejectsUnknownValues() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        application { adminModule(sent, detailResponder()) }
        val ok = client.get("/api/v1/admin/usuarios/$u1/detalle?sections=racha,economia")
        assertEquals(HttpStatusCode.OK, ok.status)
        val uris = sent.map { it.uri }
        assertTrue(uris.any { "/rest/v1/rachas_usuario" in it })
        assertTrue(uris.any { "/rest/v1/carteras_usuario" in it })
        for (table in listOf("estadisticas_usuario", "estado_competitivo_usuario", "suscripciones", "estado_opositester_usuario", "v_usuario_debilidades_etiqueta", "perfiles_usuario_detalle", "roles_usuario")) {
            assertTrue(uris.none { "/rest/v1/$table" in it }, "no se pidio $table")
        }
    }

    @Test
    fun anUnknownSectionIs400OnSectionsWithNoUpstreamCall() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        application { adminModule(sent, detailResponder()) }
        for (raw in listOf("racha,bogus", "detalle", "PERFIL")) {
            val response = client.get("/api/v1/admin/usuarios/$u1/detalle?sections=$raw")
            assertEquals(HttpStatusCode.BadRequest, response.status, raw)
            assertEquals(listOf("sections" to "INVALID_FORMAT"), issuesOf(response.bodyAsText()), raw)
        }
        assertTrue(sent.isEmpty(), "un valor invalido no llega a la base")
    }

    @Test
    fun withoutSectionsEverySectionIsStillRead() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        application { adminModule(sent, detailResponder()) }
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/admin/usuarios/$u1/detalle").status)
        val uris = sent.map { it.uri }
        for (table in listOf("perfiles_usuario_detalle", "estadisticas_usuario", "estado_competitivo_usuario", "rachas_usuario", "carteras_usuario", "suscripciones", "estado_opositester_usuario", "v_usuario_debilidades_etiqueta")) {
            assertTrue(uris.any { "/rest/v1/$table" in it }, table)
        }
    }

    // ------------------------------------------------------------------ MASTER-API-005 / 012 (D1-26)

    private fun rolesResponder(): (String) -> V1StubResponse = { uri ->
        when {
            "/rest/v1/roles_usuario" in uri -> V1StubResponse(200, """[{"usuario_id":"$u1","rol_id":"$roleUser"}]""")
            "/rest/v1/rpc/admin_sync_roles" in uri -> V1StubResponse(204, "")
            else -> V1StubResponse(200, "[]")
        }
    }

    private suspend fun ApplicationTestBuilder.postRoles(path: String, body: String): HttpResponse =
        client.post(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun theRolesBodyIsStrictUnknownKeysMissingRoleIdsAndOversizedListsAre400() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        application { adminModule(sent, rolesResponder()) }
        val path = "/api/v1/admin/usuarios/$u1/roles"

        val unknown = postRoles(path, """{"roleIds":["$roleUser"],"extra":1}""")
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
        assertEquals(listOf("extra" to "UNKNOWN_FIELD"), issuesOf(unknown.bodyAsText()))

        val missing = postRoles(path, "{}")
        assertEquals(HttpStatusCode.BadRequest, missing.status)
        assertEquals(listOf("roleIds" to "REQUIRED"), issuesOf(missing.bodyAsText()))
        assertEquals("VALIDATION", Json.parseToJsonElement(missing.bodyAsText()).jsonObject.str("code"))

        val many = (1..51).joinToString(",", "[", "]") { "\"$roleUser\"" }
        val oversized = postRoles(path, """{"roleIds":$many}""")
        assertEquals(HttpStatusCode.BadRequest, oversized.status)
        assertEquals(listOf("roleIds" to "OUT_OF_RANGE"), issuesOf(oversized.bodyAsText()))

        val nonUuidRole = postRoles(path, """{"roleIds":["1-1-1-1-1"]}""")
        assertEquals(HttpStatusCode.BadRequest, nonUuidRole.status, "UUID.fromString aceptaba esta forma corta")

        assertTrue(sent.isEmpty(), "ningun cuerpo invalido llega a la base: ${sent.map { it.uri }}")
        assertEquals(HttpStatusCode.OK, postRoles(path, """{"roleIds":["$roleUser"]}""").status)
    }

    @Test
    fun aNonUuidUserIdInTheRolesPathIsANonUuidValidationErrorWithNoUpstreamCall() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        application { adminModule(sent, rolesResponder()) }
        for (bad in listOf("not-a-uuid", "1-1-1-1-1", "ROLE_SYNC_LAST_SUPERADMIN")) {
            val response = postRoles("/api/v1/admin/usuarios/$bad/roles", """{"roleIds":["$roleUser"]}""")
            assertEquals(HttpStatusCode.BadRequest, response.status, bad)
            assertEquals(listOf("id" to "NON_UUID"), issuesOf(response.bodyAsText()), bad)
        }
        assertTrue(sent.isEmpty())
    }

    @Test
    fun validatedUuidIsStrictAndNormalizesToLowercase() {
        assertEquals(u1, u1.uppercase().validatedUuid("x"))
        assertFailsWith<IllegalArgumentException> { "1-1-1-1-1".validatedUuid("x") }
        assertFailsWith<IllegalArgumentException> { "".validatedUuid("x") }
        assertFailsWith<IllegalArgumentException> { "$u1 ".validatedUuid("x") }
    }

    // ------------------------------------------------------------------ MASTER-API-016 (backend de D1-25)

    @Test
    fun patchAcceptsNombreMostradoAndTheLegacyFullNameAndNeverBlanksTheNameSilently() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            installRequestCorrelation()
            installBackendStatusPages()
            val gateway = AdminV1Gateway.forTesting(
                supabaseUrl = "https://stub.supabase.test",
                serviceRoleKey = "stub-key",
                json = Json { ignoreUnknownKeys = true },
                client = V1StubHttpClient({ sent += Sent(it.method(), it.uri().toString()) }) { uri ->
                    when {
                        "/rest/v1/roles_usuario" in uri || "/rest/v1/carteras_usuario" in uri -> V1StubResponse(200, "[]")
                        "/rest/v1/perfiles_usuario" in uri && "select=" in uri -> V1StubResponse(200, "[${perfil(u1, "ada_l")}]")
                        else -> V1StubResponse(204, "")
                    }
                },
            )
            routing { adminV1Routes(gateway) }
        }
        val path = "/api/v1/admin/usuarios/$u1"
        val canonical = client.patch(path) { contentType(ContentType.Application.Json); setBody("""{"nombreMostrar":"Ada Nueva"}""") }
        assertEquals(HttpStatusCode.OK, canonical.status)
        val legacy = client.patch(path) { contentType(ContentType.Application.Json); setBody("""{"id":"$u1","fullName":"Ada Legacy","email":"a@x.es","roleIds":[],"isActive":true}""") }
        assertEquals(HttpStatusCode.OK, legacy.status)
        assertEquals(2, sent.count { it.method == "PATCH" })

        val neither = client.patch(path) { contentType(ContentType.Application.Json); setBody("""{"email":"a@x.es"}""") }
        assertEquals(HttpStatusCode.BadRequest, neither.status)
        assertEquals(listOf("nombreMostrar" to "REQUIRED"), issuesOf(neither.bodyAsText()))
        assertEquals(2, sent.count { it.method == "PATCH" }, "sin nombre no se escribe nada")
    }
}
