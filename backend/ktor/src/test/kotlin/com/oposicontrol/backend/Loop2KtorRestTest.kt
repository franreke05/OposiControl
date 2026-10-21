package com.oposicontrol.backend

import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.protocol.Message
import io.sentry.protocol.Request
import io.sentry.protocol.SentryException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Flow
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * LOOP 2, area KTOR_REST: MASTER2-API-001/002/003/004, OPENAPI-003/006, SEC-002, OBS-002/004/005.
 * Cada prueba fija el comportamiento corregido; los hallazgos del validador estan citados en su cabecera.
 */
class Loop2KtorRestTest {
    private val u1 = "11111111-1111-4111-8111-111111111111"
    private val roleAdmin = "aaaaaaaa-0000-4000-8000-aaaaaaaaaaaa"

    private class Sent(val method: String, val uri: String, val prefer: String?, val body: String)

    private fun bodyOf(request: HttpRequest): String {
        val publisher = request.bodyPublisher().orElse(null) ?: return ""
        val out = StringBuilder()
        publisher.subscribe(object : Flow.Subscriber<ByteBuffer> {
            override fun onSubscribe(subscription: Flow.Subscription) = subscription.request(Long.MAX_VALUE)
            override fun onNext(item: ByteBuffer) { out.append(Charsets.UTF_8.decode(item)) }
            override fun onError(throwable: Throwable) = Unit
            override fun onComplete() = Unit
        })
        return out.toString()
    }

    private fun perfil(nombre: String = "Ada", estado: String? = "activa") =
        """{"usuario_id":"$u1","username":"ada_l","nombre_mostrar":"$nombre","es_invitado":false,"onboarding_completado":true,""" +
            """"estado_cuenta":${estado?.let { "\"$it\"" } ?: "null"},"creado_en":"2026-01-02T03:04:05.123456+00:00"}"""

    private fun Application.adminModule(sent: CopyOnWriteArrayList<Sent>, responder: (String) -> V1StubResponse) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        val gateway = AdminV1Gateway.forTesting(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = V1StubHttpClient(
                { sent += Sent(it.method(), it.uri().toString(), it.headers().firstValue("Prefer").orElse(null), bodyOf(it)) },
                responder,
            ),
        )
        routing { adminV1Routes(gateway) }
    }

    private suspend fun HttpResponse.issues(): List<Pair<String?, String?>> =
        Json.parseToJsonElement(bodyAsText()).jsonObject["details"]!!.jsonObject["issues"]!!.jsonArray
            .map { it.jsonObject["field"]?.jsonPrimitive?.content to it.jsonObject["issue"]?.jsonPrimitive?.content }

    // ------------------------------------------------------------------ MASTER2-API-001

    private fun patchResponder(
        current: String?,
        written: String = "[${perfil("Ada Nueva")}]",
        vanishAfterFirstRead: Boolean = false,
    ): (String) -> V1StubResponse {
        val reads = java.util.concurrent.atomic.AtomicInteger()
        return { uri -> patchResponse(uri, if (vanishAfterFirstRead && reads.get() > 0) null else current, written, reads) }
    }

    private fun patchResponse(uri: String, current: String?, written: String, reads: java.util.concurrent.atomic.AtomicInteger): V1StubResponse =
        when {
            "/rest/v1/perfiles_usuario" in uri && "select=" in uri -> {
                reads.incrementAndGet()
                V1StubResponse(200, if (current == null) "[]" else "[$current]")
            }
            "/rest/v1/perfiles_usuario" in uri -> V1StubResponse(200, written)
            else -> V1StubResponse(200, "[]")
        }

    private suspend fun io.ktor.client.HttpClient.patchUser(body: String): HttpResponse =
        patch("/api/v1/admin/usuarios/$u1") { contentType(ContentType.Application.Json); setBody(body) }

    private fun CopyOnWriteArrayList<Sent>.writes() = filter { it.method == "PATCH" && "/rest/v1/perfiles_usuario" in it.uri }
    private fun CopyOnWriteArrayList<Sent>.audits() = filter { "/rest/v1/auditoria_log" in it.uri }

    @Test
    fun patchRejectsBlankOversizedAndUnknownFieldsBeforeAnyWrite() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        application { adminModule(sent, patchResponder(perfil())) }

        val blank = client.patchUser("""{"nombreMostrar":"   "}""")
        assertEquals(HttpStatusCode.BadRequest, blank.status)
        assertEquals(listOf("nombreMostrar" to "REQUIRED"), blank.issues())

        val tooLong = client.patchUser("""{"nombreMostrar":"${"x".repeat(ADMIN_USER_NAME_MAX + 1)}"}""")
        assertEquals(HttpStatusCode.BadRequest, tooLong.status)
        assertEquals(listOf("nombreMostrar" to "OUT_OF_RANGE"), tooLong.issues())

        for (extra in listOf("""{"nombreMostrar":"Ada","username":"otro"}""", """{"fullName":"Ada"}""")) {
            val unknown = client.patchUser(extra)
            assertEquals(HttpStatusCode.BadRequest, unknown.status, extra)
            assertTrue(unknown.issues().any { it.second == "UNKNOWN_FIELD" }, extra)
        }
        assertTrue(sent.writes().isEmpty() && sent.audits().isEmpty(), "ningun rechazo escribe ni audita")
    }

    @Test
    fun patchOfANonexistentUserIs404NotAFabricated200() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        application { adminModule(sent, patchResponder(current = null)) }
        val response = client.patchUser("""{"nombreMostrar":"Ada Nueva"}""")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(sent.writes().isEmpty() && sent.audits().isEmpty())
    }

    @Test
    fun patchWhoseRowVanishedBetweenReadAndWriteIs404AndUnaudited() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        // MASTER3-API-004 (cambio DELIBERADO del stub): la escritura es condicionada y 0 filas ya no implica "no existe"
        // (puede ser otra version); se relee. "Desaparecio" = la relectura tampoco la encuentra.
        application { adminModule(sent, patchResponder(perfil("Ada"), written = "[]", vanishAfterFirstRead = true)) }
        val response = client.patchUser("""{"nombreMostrar":"Ada Nueva"}""")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(sent.audits().isEmpty(), "sin fila escrita no hay auditoria")
    }

    @Test
    fun patchWithTheSameNameIsIdempotentNoWriteNoAudit() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        application { adminModule(sent, patchResponder(perfil("Ada L"))) }
        val response = client.patchUser("""{"nombreMostrar":"  Ada L  "}""")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Ada L", Json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]!!.jsonObject["fullName"]!!.jsonPrimitive.content)
        assertTrue(sent.writes().isEmpty() && sent.audits().isEmpty(), "sin cambio no hay escritura ni auditoria")
    }

    @Test
    fun aRealChangeWritesActualizadoEnAndAuditsBeforeAndAfter() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        // MASTER3-API-001: el 200 relee el perfil tras escribir; el stub devuelve el nombre nuevo solo despues del PATCH.
        var patched = false
        val base = patchResponder(perfil("Ada"))
        application {
            adminModule(sent) { uri ->
                if ("/rest/v1/perfiles_usuario" in uri && "select=" !in uri) patched = true
                if (patched && "/rest/v1/perfiles_usuario" in uri && "select=" in uri) V1StubResponse(200, "[${perfil("Ada Nueva")}]") else base(uri)
            }
        }
        val response = client.patchUser("""{"nombreMostrar":"  Ada Nueva "}""")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Ada Nueva", Json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]!!.jsonObject["fullName"]!!.jsonPrimitive.content)

        val write = sent.writes().single()
        assertEquals("return=representation", write.prefer)
        val written = Json.parseToJsonElement(write.body).jsonObject
        assertEquals("Ada Nueva", written["nombre_mostrar"]!!.jsonPrimitive.content, "recortado")
        assertNotNull(written["actualizado_en"], "el ETag de perfil (actualizado_en) sube con la escritura")

        val audit = Json.parseToJsonElement(sent.audits().single().body).jsonArray.single().jsonObject
        assertEquals("usuario_actualizado", audit["accion"]!!.jsonPrimitive.content)
        assertEquals("Ada", audit["antes"]!!.jsonObject["nombreMostrar"]!!.jsonPrimitive.content)
        assertEquals("Ada Nueva", audit["despues"]!!.jsonObject["nombreMostrar"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------ MASTER2-OPENAPI-003

    private fun detailResponder(estado: String? = "activa"): (String) -> V1StubResponse = { uri ->
        when {
            "/rest/v1/perfiles_usuario?select=*" in uri -> V1StubResponse(200, "[${perfil(estado = estado)}]")
            else -> V1StubResponse(200, "[]")
        }
    }

    @Test
    fun aSectionsSubsetStillEmitsAConformingAdminUserPerfil() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        application { adminModule(sent, detailResponder()) }
        val response = client.get("/api/v1/admin/usuarios/$u1/detalle?sections=racha")
        assertEquals(HttpStatusCode.OK, response.status)
        val perfil = Json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]!!.jsonObject["perfil"]!!.jsonObject
        assertEquals(u1, perfil["id"]!!.jsonPrimitive.content)
        assertEquals("activa", perfil["accountStatus"]!!.jsonPrimitive.content)
        assertTrue(perfil["createdAt"]!!.jsonPrimitive.content.startsWith("2026-01-02T03:04:05"), "createdAt viene de la fila, no vacio")
        assertEquals("true", perfil["onboardingCompleted"]!!.jsonPrimitive.content)
        assertEquals("Ada", perfil["fullName"]!!.jsonPrimitive.content)
        assertTrue(sent.none { "/rest/v1/roles_usuario" in it.uri || "/auth/v1/admin/users" in it.uri }, "camino barato: sin roles ni GoTrue")
    }

    @Test
    fun anUnrecognisedStatusIs502EvenWhenPerfilWasNotRequested() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        application { adminModule(sent, detailResponder(estado = "suspendida")) }
        val response = client.get("/api/v1/admin/usuarios/$u1/detalle?sections=racha")
        assertEquals(HttpStatusCode.BadGateway, response.status)
    }

    // ------------------------------------------------------------------ MASTER2-API-002 (contrato: tope documentado)

    @Test
    fun theRoleFilterCapIsAFrozenDocumentedContractRule() {
        val root = java.io.File("../../openapi").takeIf { it.isDirectory } ?: java.io.File("openapi")
        val profiles = java.io.File(root, "profiles/profiles.yaml").readText()
        assertTrue("TOO_MANY_MATCHES" in profiles, "profiles.yaml documenta roleCode/TOO_MANY_MATCHES")
        assertTrue("100 holders" in profiles)
        assertTrue("TOO_MANY_MATCHES" in java.io.File(root, "common/errors.yaml").readText())
        val moreThanCap = (1..101).joinToString(",", "[", "]") { """{"usuario_id":"$u1"}""" }
        testApplication {
            val sent = CopyOnWriteArrayList<Sent>()
            application {
                adminModule(sent) { uri ->
                    when {
                        "/rest/v1/roles_app?select=id" in uri -> V1StubResponse(200, """[{"id":"$roleAdmin"}]""")
                        "/rest/v1/roles_usuario?select=usuario_id" in uri -> V1StubResponse(200, moreThanCap)
                        else -> V1StubResponse(200, "[]")
                    }
                }
            }
            val response = client.get("/api/v1/admin/usuarios?roleCode=admin")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(listOf("roleCode" to "TOO_MANY_MATCHES"), response.issues())
        }
    }

    // ------------------------------------------------------------------ MASTER2-API-004

    @Test
    fun unroutedPathsAndMethodsRespondTheCanonicalEnvelopeWhileRouteBodiesAreKept() = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            installRequestCorrelation()
            installBackendStatusPages()
            routing {
                get("/own404") { call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", "Mensaje propio de la ruta.")) }
                get("/bare405") { call.respond(HttpStatusCode.MethodNotAllowed) }
            }
        }
        val missing = client.get("/no/existe")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        val missingBody = Json.parseToJsonElement(missing.bodyAsText()).jsonObject
        assertEquals("NOT_FOUND", missingBody["code"]!!.jsonPrimitive.content)
        assertNotNull(missingBody["requestId"]?.jsonPrimitive?.content)
        assertEquals(missing.headers["X-Request-Id"], missingBody["requestId"]!!.jsonPrimitive.content)

        val wrongMethod = client.post("/own404")
        assertTrue(wrongMethod.status == HttpStatusCode.NotFound || wrongMethod.status == HttpStatusCode.MethodNotAllowed)
        assertTrue("code" in Json.parseToJsonElement(wrongMethod.bodyAsText()).jsonObject, "el 404/405 de metodo lleva sobre")

        val own = client.get("/own404")
        assertEquals(HttpStatusCode.NotFound, own.status)
        assertEquals("Mensaje propio de la ruta.", Json.parseToJsonElement(own.bodyAsText()).jsonObject["message"]!!.jsonPrimitive.content, "el cuerpo de la ruta no se pisa")

        val bare405 = client.get("/bare405")
        assertEquals(HttpStatusCode.MethodNotAllowed, bare405.status)
        assertEquals("METHOD_NOT_ALLOWED", Json.parseToJsonElement(bare405.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------ MASTER2-SEC-002

    @Test
    fun aDeclaredBodyAboveTheCapIs413WithTheEnvelopeAndNeverReachesTheRoute() = testApplication {
        var reached = 0
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            installRequestCorrelation()
            installBackendStatusPages()
            installRequestBodyLimit()
            routing { post("/echo") { reached++; call.respond(call.receiveText().length.toString()) } }
        }
        val ok = client.post("/echo") { contentType(ContentType.Application.Json); setBody("x".repeat(1024)) }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals(1, reached)

        val big = client.post("/echo") { contentType(ContentType.Application.Json); setBody("x".repeat((REQUEST_BODY_MAX_BYTES + 1).toInt())) }
        assertEquals(HttpStatusCode.PayloadTooLarge, big.status)
        assertEquals("PAYLOAD_TOO_LARGE", Json.parseToJsonElement(big.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals(1, reached, "la ruta no llega a leer el cuerpo")

        val exactlyAtCap = client.post("/echo") { contentType(ContentType.Application.Json); setBody("x".repeat(REQUEST_BODY_MAX_BYTES.toInt())) }
        assertEquals(HttpStatusCode.OK, exactlyAtCap.status, "el tope es inclusivo")
    }

    @Test
    fun installingTheStatusPagesAloneDoesNotSilentlyInstallTheBodyCap() = testApplication {
        // MASTER3-OBS-003: el tope de cuerpo es una instalacion explicita (module()/configureConsumerAuthOnly), no un efecto
        // lateral de installBackendStatusPages().
        application {
            installRequestCorrelation()
            installBackendStatusPages()
            routing { post("/echo") { call.respond("ok") } }
        }
        val big = client.post("/echo") { contentType(ContentType.Application.Json); setBody("x".repeat((REQUEST_BODY_MAX_BYTES + 1).toInt())) }
        assertEquals(HttpStatusCode.OK, big.status)
    }

    @Test
    fun observabilityEnvironmentUsesTheSingleAppEnvironmentResolver() {
        // MASTER3-OBS-003: los logs y Sentry no pueden discrepar (antes logs "unknown" y Sentry "production" con APP_ENV vacio).
        assertEquals(AppEnvironment.fromSystemEnvironment(), ObservabilityConfig.environment)
        assertEquals("production", sentryEnvironment { null })
        assertEquals("staging", sentryEnvironment { if (it == "APP_ENV") " Staging " else null })
    }

    @Test
    fun multipartUploadsAreOutsideTheGlobalCapBecauseTheirRoutesBoundTheirOwnReads() = testApplication {
        application {
            installRequestCorrelation()
            installBackendStatusPages()
            installRequestBodyLimit()
            routing { post("/upload") { call.respond("ok") } }
        }
        val response = client.post("/upload") {
            header(HttpHeaders.ContentType, "multipart/form-data; boundary=x")
            setBody("x".repeat((REQUEST_BODY_MAX_BYTES + 1).toInt()))
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    private fun appGateway(sent: MutableList<HttpRequest>) = AppV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient({ sent += it }) { uri ->
            if ("/rest/v1/rpc/record_event" in uri) V1StubResponse(200, """{"event_id":"ev-1"}""") else V1StubResponse(200, "[]")
        },
    )

    @Test
    fun eventIdentifiersAndTimestampAreBoundedAndValidatedBeforeTheRpc() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val gateway = appGateway(sent)
        listOf(
            AppEventoRequest(nombreEvento = "ok", entidadId = "e".repeat(EVENT_TEXT_MAX + 1), idempotencyKey = "k"),
            AppEventoRequest(nombreEvento = "ok", occurredEn = "ayer por la tarde", idempotencyKey = "k"),
            AppEventoRequest(nombreEvento = "ok", occurredEn = "2026-09-30T10:00:00Z" + "0".repeat(EVENT_TEXT_MAX), idempotencyKey = "k"),
            AppEventoRequest(nombreEvento = "ok", especialidadId = "no-uuid", idempotencyKey = "k"),
            // MASTER3-API-005: entidadId viaja como uuid a la RPC; un no-UUID se rechaza aqui, no como 422 de Postgres.
            AppEventoRequest(nombreEvento = "ok", entidadId = "exam-1", idempotencyKey = "k"),
        ).forEach { request -> assertFailsWith<IllegalArgumentException>(request.toString().take(90)) { gateway.registrarEvento(u1, request) } }
        assertTrue(sent.isEmpty(), "nada sale hacia Supabase")

        // Formas validas: Z, con offset, y sin fecha (la pone el servidor).
        listOf("2026-09-30T10:00:00Z", "2026-09-30T12:00:00+02:00", null).forEach { at ->
            assertEquals("ev-1", gateway.registrarEvento(u1, AppEventoRequest(nombreEvento = "ok", occurredEn = at, entidadId = "44444444-4444-4444-8444-444444444444", idempotencyKey = "k")))
        }
    }

    @Test
    fun theCaddyBodyLimitSnippetExistsAndExcludesMultipart() {
        val root = java.io.File("../..").takeIf { java.io.File(it, "ops/caddy").isDirectory } ?: java.io.File(".")
        val snippet = java.io.File(root, "ops/caddy/body-limit.caddy").readText()
        assertTrue(Regex("(?m)^request_body @bounded").containsMatchIn(snippet))
        assertTrue(Regex("(?m)^\\s*max_size\\s+2MB").containsMatchIn(snippet))
        assertTrue("not header Content-Type multipart/*" in snippet)
    }

    // ------------------------------------------------------------------ MASTER2-OPENAPI-006

    @Test
    fun theEightLegacyAppRoutesCarryDeprecationAndOnlyARoutedResolvableSuccessorIsLinked() = testApplication {
        val gateway = appGateway(CopyOnWriteArrayList())
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            installRequestCorrelation()
            installBackendStatusPages()
            routing { appV1Routes(gateway, SupabaseJwtVerifier("https://stub.supabase.test", Json), allowDevUser = true) }
        }
        assertEquals(8, LegacyEndpoints.SLICE_1_APP.size) // D5-15 feedback/pregunta ya no es legacy (contrato D5-15, FA24)
        val expectedDeprecation = "@${LegacyEndpoints.SLICE_1_DEPRECATED_AT.epochSecond}"
        val attempt = "33333333-3333-4333-8333-333333333333"
        val calls = listOf(
            "GET" to "/api/v1/app/home", "GET" to "/api/v1/app/estadisticas", "GET" to "/api/v1/app/commercial/catalog",
            "GET" to "/api/v1/app/commercial/purchases", "GET" to "/api/v1/app/commercial/subscriptions",
            "POST" to "/api/v1/app/eventos",
            "POST" to "/api/v1/app/examenes/$attempt/iniciar", "POST" to "/api/v1/app/intentos/$attempt/responder",
        )
        val links = mutableMapOf<String, String?>()
        calls.forEach { (method, path) ->
            val response = if (method == "GET") client.get(path) { header("X-Usuario-Id", u1) } else client.post(path) {
                header("X-Usuario-Id", u1); contentType(ContentType.Application.Json); setBody("{}")
            }
            assertEquals(expectedDeprecation, response.headers[LegacyHeaders.DEPRECATION], "$method $path")
            links[path] = response.headers[HttpHeaders.Link]
        }
        // MASTER3-API-003: una ruta APP-* solo anuncia un Link cuando su sucesora existe como ruta. Desde Slice 3 existen las
        // dos de intento (D2-03, D2-09) y las rutas legacy estan retiradas (410, LegacyAttemptRoutesRetiredTest): `iniciar`
        // anuncia D2-03 con el id REAL del examen; `responder` no (la pregunta de D2-09 iba en el cuerpo legacy: sin URI
        // resoluble no hay Link). Las otras 7 siguen sin sucesora ruteada y sin Link.
        val startPath = "/api/v1/app/examenes/$attempt/iniciar"
        assertEquals("</api/v1/app/examenes/$attempt/intentos>; rel=\"successor-version\"", links[startPath])
        assertTrue((links - startPath).values.all { it == null }, links.toString())
        assertEquals(
            LegacyEndpoints.RETIRED_ATTEMPT_ROUTES.toSet(),
            LegacyEndpoints.SLICE_1_APP.filter { it.successorPath != null }.toSet(),
            "solo las dos rutas de intento retiradas registran sucesora",
        )
        assertNull(links["/api/v1/app/home"], "sin sucesora congelada no se anuncia un Link")
        assertTrue(LegacyEndpoints.SLICE_1_APP.all { it.sunsetAt == null })
        assertEquals(LegacyEndpoints.SLICE_1_APP.size, LegacyEndpoints.SLICE_1_APP.map { it.endpointId }.toSet().size)
    }

    // ------------------------------------------------------------------ MASTER2-OBS-002

    @Test
    fun uniqueViolationDetailsOfExpressionIndexesAndFailingRowsAreRedacted() {
        val expr = "duplicate key value violates unique constraint \"u\" Key (lower(username::text))=(bob_secret) already exists."
        val redacted = expr.redactSensitiveText()
        assertFalse("bob_secret" in redacted, redacted)
        assertTrue("Key (lower(username::text))=([redacted])" in redacted, redacted)

        val plain = "Key (username)=(carol_secret) already exists.".redactSensitiveText()
        assertFalse("carol_secret" in plain)

        val composite = "Key (usuario_id, rol_id)=(a-1, b-2) already exists".redactSensitiveText()
        assertFalse("a-1" in composite)

        val failing = "new row violates check constraint \"c\" Failing row contains (u-1, dave_secret, dave@example.com).".redactSensitiveText()
        assertFalse("dave_secret" in failing || "dave@example.com" in failing, failing)
        assertTrue("Failing row contains ([redacted])" in failing)
    }

    // ------------------------------------------------------------------ MASTER2-OBS-004

    @Test
    fun keysAndTokenFieldsAreRedactedAndTheEmailPatternIsLinear() {
        assertFalse("sb_secret_AbCdEf123456_xyz" in "auth failed apikey sb_secret_AbCdEf123456_xyz".redactSensitiveText())
        assertFalse("sb_publishable_AbCdEf123456" in "key=sb_publishable_AbCdEf123456".redactSensitiveText())
        val json = """{"refresh_token":"abcdEFGH1234567890","access_token": "zzzzYYYY99998888"}""".redactSensitiveText()
        assertFalse("abcdEFGH1234567890" in json || "zzzzYYYY99998888" in json, json)
        assertFalse("refresh_token=Rr12345678xyz" in "refresh_token=Rr12345678xyz".redactSensitiveText())

        val hostile = "a".repeat(200_000)
        val started = System.nanoTime()
        assertEquals(hostile, hostile.redactSensitiveText())
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000, "el patron de email no debe ser cuadratico")
    }

    @Test
    fun sentryEventsAndBreadcrumbsAreScrubbedNotOnlyExceptionsAndMessage() {
        val event = SentryEvent().apply {
            exceptions = listOf(SentryException().apply { value = "boom apikey=sb_secret_TOPSECRET99" })
            message = Message().apply { message = "mail ada@example.com"; formatted = "mail ada@example.com" }
            setExtra("detail", "Bearer abc.def.ghi-12345")
            request = Request().apply {
                url = "https://api/x?email=ada@example.com"
                queryString = "token=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghij"
                data = """{"password":"p"}"""
                cookies = "sid=1"
                headers = mutableMapOf("Authorization" to "Bearer zzz")
            }
            addBreadcrumb(Breadcrumb().apply { message = "login ada@example.com"; setData("sql", "Key (username)=(bob) already exists.") })
        }
        val scrubbed = scrubSentryEvent(event)
        val dump = listOf(
            scrubbed.exceptions!!.single().value, scrubbed.message!!.message, scrubbed.message!!.formatted,
            scrubbed.extras!!["detail"].toString(), scrubbed.request!!.url, scrubbed.request!!.queryString,
            scrubbed.breadcrumbs!!.single().message, scrubbed.breadcrumbs!!.single().data["sql"].toString(),
        ).joinToString("\n")
        for (secret in listOf("TOPSECRET99", "ada@example.com", "abc.def.ghi-12345", "eyJhbGciOiJIUzI1NiJ9", "=(bob)")) {
            assertFalse(secret in dump, "$secret sigue en: $dump")
        }
        assertNull(scrubbed.request!!.data)
        assertNull(scrubbed.request!!.cookies)
        assertNull(scrubbed.request!!.headers)

        val crumb = scrubBreadcrumb(Breadcrumb().apply { message = "Bearer aaa.bbb.ccc-99999"; setData("k", "x@y.es") })
        assertFalse("aaa.bbb.ccc-99999" in crumb.message!! || "x@y.es" in crumb.data["k"].toString())
    }

    @Test
    fun closeFailuresOfTheAuthRuntimeAreLoggedRedactedNotSwallowed() {
        val logger = LoggerFactory.getLogger("OC-KTOR-AUTH") as LogbackLogger
        val appender = ListAppender<ILoggingEvent>().also { it.start(); logger.addAppender(it) }
        try {
            val base = CanonicalTestEnv()
            val failing = object : OposiBotsAuthRateLimiter {
                override suspend fun checkIp(operation: OposiBotsAuthOperation, clientIp: String) = OposiBotsRateLimitDecision(true)
                override suspend fun checkSubject(operation: OposiBotsAuthOperation, subject: String) = OposiBotsRateLimitDecision(true)
                override fun close() = error("valkey down for ada@example.com")
            }
            OposiBotsAuthRuntime(testCanonicalConfiguration(), NoAuthGateway, failing, testJson, base.runtime().canonical, NoAccessRequestsMailGuard).close()
            val messages = appender.list.map { it.formattedMessage }
            assertTrue(messages.any { "close failed resource=rateLimiter" in it }, messages.toString())
            assertTrue(messages.none { "ada@example.com" in it }, "el fallo se registra redactado")
        } finally {
            logger.detachAppender(appender)
        }
    }

    // ------------------------------------------------------------------ MASTER2-OBS-005

    @Test
    fun sentryEnvironmentFollowsSentryEnvironmentThenAppEnvAndFailsClosedToProduction() {
        assertEquals("staging", sentryEnvironment { if (it == "SENTRY_ENVIRONMENT") "staging" else "production" })
        assertEquals("production", sentryEnvironment { if (it == "APP_ENV") "production" else null })
        assertEquals("production", sentryEnvironment { null }, "sin nada: fail-closed, nunca development")
        assertEquals("production", sentryEnvironment { if (it == "SENTRY_ENVIRONMENT") "  " else null })
        assertEquals("development", sentryEnvironment { if (it == "APP_ENV") " Development " else null })
    }

    @Test
    fun sentryEnvironmentIsDocumentedInEnvExampleAndDeployment() {
        val root = java.io.File("../..").takeIf { java.io.File(it, ".env.example").isFile } ?: java.io.File(".")
        assertTrue(Regex("(?m)^SENTRY_ENVIRONMENT=").containsMatchIn(java.io.File(root, ".env.example").readText()))
        assertTrue("SENTRY_ENVIRONMENT" in java.io.File(root, "docs/engineering/DEPLOYMENT.md").readText())
    }
}
