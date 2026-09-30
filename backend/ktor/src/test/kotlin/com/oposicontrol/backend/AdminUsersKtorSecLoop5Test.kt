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
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * LOOP 5, area KTOR_SEC (superficie admin de usuarios, D1-22..D1-26):
 * - MASTER5-API-004: una auditoria OBLIGATORIA que no se escribe es 500 INTERNAL (declarado), no 502 UPSTREAM_ERROR.
 * - MASTER5-API-010: toda llamada al gateway de `/usuarios` corre FUERA del hilo de la peticion, y el actor de
 *   auditoria sobrevive al salto de hilo.
 * - MASTER5-API-011: D1-26 responde el `despues` leido BAJO el cerrojo de la RPC, no una re-lectura posterior.
 * - MASTER5-OBS-004: las denegaciones 401/403 de dominio y AppForbidden dejan evento con codigo y sujeto.
 */
class AdminUsersKtorSecLoop5Test {
    private val superActor = "5a5a5a5a-0000-4000-8000-000000000001"
    private val plainUser = "5a5a5a5a-0000-4000-8000-000000000003"
    private val roleSuper = "aaaaaaaa-0000-4000-8000-00000000000a"
    private val roleAdmin = "aaaaaaaa-0000-4000-8000-00000000000b"
    private val roleUser = "aaaaaaaa-0000-4000-8000-00000000000c"
    private val roleMod = "aaaaaaaa-0000-4000-8000-00000000000d"
    private val txId = "cccccccc-0000-4000-8000-000000000001"

    private class Sent(val method: String, val uri: String, val body: String)

    private class Stub {
        val sent = CopyOnWriteArrayList<Sent>()
        var auditStatus = 201
        var rpcAnswer = V1StubResponse(200, "")

        /** Lo que `roles_usuario` devuelve para el usuario objetivo (la "re-lectura"). */
        var targetRolesRead = "[]"
    }

    private fun Stub.responder(method: String, uri: String): V1StubResponse = when {
        "/rest/v1/roles_app" in uri -> V1StubResponse(
            200,
            """[{"id":"$roleSuper","codigo":"superadmin","nombre":"S"},{"id":"$roleAdmin","codigo":"admin","nombre":"A"},""" +
                """{"id":"$roleUser","codigo":"usuario","nombre":"U"},{"id":"$roleMod","codigo":"moderador","nombre":"M"}]""",
        )
        "/rest/v1/roles_usuario?select=rol_id&usuario_id=eq.$superActor" in uri ->
            V1StubResponse(200, """[{"usuario_id":"$superActor","rol_id":"$roleSuper"}]""")
        "/rest/v1/roles_usuario?select=rol_id&usuario_id=eq.$plainUser" in uri -> V1StubResponse(200, targetRolesRead)
        "/rest/v1/rpc/admin_sync_roles_audited" in uri -> rpcAnswer
        "/rest/v1/rpc/admin_grant_tickets" in uri ->
            V1StubResponse(200, """[{"transaction_id":"$txId","saldo_resultante":15}]""")
        method == "POST" && "/rest/v1/auditoria_log" in uri ->
            V1StubResponse(auditStatus, if (auditStatus >= 400) """{"message":"audit down"}""" else "")
        method == "GET" && "/rest/v1/perfiles_usuario" in uri -> V1StubResponse(
            200,
            """[{"usuario_id":"$plainUser","username":"ada_l","nombre_mostrar":"Ada","es_invitado":false,"onboarding_completado":true,""" +
                """"estado_cuenta":"activa","creado_en":"2026-01-02T03:04:05+00:00","actualizado_en":"2026-09-30T10:00:00+00:00"}]""",
        )
        method == "PATCH" && "/rest/v1/perfiles_usuario" in uri -> V1StubResponse(
            200,
            """[{"usuario_id":"$plainUser","username":"ada_l","nombre_mostrar":"Ada L","es_invitado":false,"onboarding_completado":true,""" +
                """"estado_cuenta":"activa","creado_en":"2026-01-02T03:04:05+00:00","actualizado_en":"2026-09-30T10:00:01+00:00"}]""",
        )
        else -> V1StubResponse(200, "[]")
    }

    private fun gateway(stub: Stub, onSend: (HttpRequest) -> Unit = {}): AdminV1Gateway {
        var lastMethod = ""
        return AdminV1Gateway.forTesting(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = V1StubHttpClient({ request: HttpRequest ->
                lastMethod = request.method()
                stub.sent += Sent(request.method(), request.uri().toString(), readRequestBody(request))
                onSend(request)
            }) { uri -> stub.responder(lastMethod, uri) },
        )
    }

    private fun Application.adminModule(g: AdminV1Gateway) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        install(Authentication) { bearer("gateway-auth") { authenticate { UserIdPrincipal(it.token) } } }
        routing {
            authenticate("gateway-auth") {
                installAdminAuditActor()
                adminV1Routes(g)
            }
        }
    }

    private suspend fun ApplicationTestBuilder.grantAdmin(): HttpResponse =
        client.post("/api/v1/admin/usuarios/$plainUser/roles") {
            header(HttpHeaders.Authorization, "Bearer $superActor")
            contentType(ContentType.Application.Json)
            setBody("""{"roleIds":["$roleUser","$roleAdmin"]}""")
        }

    private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText()).jsonObject
    private fun Stub.audits() = sent.filter { it.method == "POST" && "/rest/v1/auditoria_log" in it.uri }
        .map { Json.parseToJsonElement(it.body).jsonArray.single().jsonObject }
    private fun Stub.targetRoleReads() = sent.count { it.method == "GET" && "roles_usuario?select=rol_id&usuario_id=eq.$plainUser" in it.uri }

    // ------------------------------------------------------------------ MASTER5-API-011

    @Test
    fun theRolesResponseIsTheLockedAfterSetNotALaterReRead() = testApplication {
        val stub = Stub().apply {
            targetRolesRead = """[{"usuario_id":"$plainUser","rol_id":"$roleUser"}]"""
            // Bajo el cerrojo otra sincronizacion ya habia dejado `moderador`: el resultado real incluye los tres.
            rpcAnswer = V1StubResponse(200, """{"antes":["$roleMod","$roleUser"],"despues":["$roleAdmin","$roleMod","$roleUser"]}""")
        }
        application { adminModule(gateway(stub)) }
        val response = grantAdmin()
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val answered = response.json()["roleIds"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf(roleAdmin, roleMod, roleUser), answered, "la respuesta es el `despues` bajo cerrojo")
        val outcome = stub.audits().last()
        assertEquals(ACTION_ROLES_SYNCED, outcome["accion"]!!.jsonPrimitive.content)
        assertEquals(answered, outcome["despues"]!!.jsonObject["roleIds"]!!.jsonArray.map { it.jsonPrimitive.content }, "respuesta == auditoria")
        // Solo la lectura PREVIA (autorizacion + intencion); ninguna re-lectura tras la RPC.
        val rpcIndex = stub.sent.indexOfFirst { "/rest/v1/rpc/admin_sync_roles_audited" in it.uri }
        assertTrue(stub.sent.drop(rpcIndex + 1).none { "roles_usuario?select=rol_id&usuario_id=eq.$plainUser" in it.uri }, "sin re-lectura tras la RPC")
        assertEquals(1, stub.targetRoleReads())
    }

    @Test
    fun anUndecodableRpcAnswerFallsBackToTheReRead() = testApplication {
        val stub = Stub().apply {
            targetRolesRead = """[{"usuario_id":"$plainUser","rol_id":"$roleUser"}]"""
            rpcAnswer = V1StubResponse(200, "not json")
        }
        application { adminModule(gateway(stub)) }
        val response = grantAdmin()
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals(listOf(roleUser), response.json()["roleIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(2, stub.targetRoleReads(), "previa + re-lectura de respaldo")
    }

    // ------------------------------------------------------------------ MASTER5-API-004

    @Test
    fun aRequiredIntentAuditFailureIsADeclared500InternalAndTheRolesDoNotChange() = testApplication {
        val stub = Stub().apply {
            auditStatus = 500
            targetRolesRead = """[{"usuario_id":"$plainUser","rol_id":"$roleUser"}]"""
        }
        application { adminModule(gateway(stub)) }
        val response = grantAdmin()
        assertEquals(HttpStatusCode.InternalServerError, response.status, response.bodyAsText())
        assertEquals("INTERNAL", response.json()["code"]!!.jsonPrimitive.content)
        assertEquals(0, stub.sent.count { "/rest/v1/rpc/admin_sync_roles" in it.uri }, "sin auditoria de intencion no hay RPC")
    }

    @Test
    fun aRequiredTicketsAuditFailureIsAlso500InternalNot502() = testApplication {
        val stub = Stub().apply { auditStatus = 500 }
        application { adminModule(gateway(stub)) }
        val response = client.post("/api/v1/admin/usuarios/$plainUser/tickets") {
            header(HttpHeaders.Authorization, "Bearer $superActor")
            contentType(ContentType.Application.Json)
            setBody("""{"cantidad":5,"motivo":"m","idempotencyKey":"k-1"}""")
        }
        assertEquals(HttpStatusCode.InternalServerError, response.status, response.bodyAsText())
        assertEquals("INTERNAL", response.json()["code"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------ MASTER5-API-010

    @Test
    fun everyUsersRouteCallsTheGatewayOffTheRequestThreadAndKeepsTheAuditActor() {
        RequestThreadProbe("oc-loop5-users").use { probe ->
            testApplication {
                val stub = Stub().apply {
                    targetRolesRead = """[{"usuario_id":"$plainUser","rol_id":"$roleUser"}]"""
                    rpcAnswer = V1StubResponse(200, """{"antes":["$roleUser"],"despues":["$roleAdmin","$roleUser"]}""")
                }
                val g = gateway(stub) { probe.record(it.method() + " " + it.uri().rawPath) }
                application {
                    pinPipelineTo(probe)
                    adminModule(g)
                }
                suspend fun check(label: String, call: suspend () -> HttpResponse) {
                    probe.clear()
                    val response = call()
                    assertTrue(response.status.value in 200..299, "$label: ${response.status} ${response.bodyAsText()}")
                    probe.assertRanOffTheRequestThread(label)
                }
                val auth: io.ktor.client.request.HttpRequestBuilder.() -> Unit = { header(HttpHeaders.Authorization, "Bearer $superActor") }
                check("GET /usuarios") { client.get("/api/v1/admin/usuarios", auth) }
                check("GET /usuarios/{id}") { client.get("/api/v1/admin/usuarios/$plainUser", auth) }
                check("GET /usuarios/{id}/detalle") { client.get("/api/v1/admin/usuarios/$plainUser/detalle?sections=perfil", auth) }
                check("PATCH /usuarios/{id}") {
                    client.patch("/api/v1/admin/usuarios/$plainUser") {
                        auth()
                        contentType(ContentType.Application.Json)
                        setBody("""{"nombreMostrar":"Ada L"}""")
                    }
                }
                check("POST /usuarios/{id}/roles") { grantAdmin() }
                check("POST /usuarios/{id}/tickets") {
                    client.post("/api/v1/admin/usuarios/$plainUser/tickets") {
                        auth()
                        contentType(ContentType.Application.Json)
                        setBody("""{"cantidad":5,"motivo":"m","idempotencyKey":"k-2"}""")
                    }
                }
                // El actor (ThreadContextElement) cruza el salto al pool IO: todas las filas de auditoria lo llevan.
                val audits = stub.audits()
                assertTrue(audits.isNotEmpty(), "hubo auditoria")
                assertTrue(audits.all { it["usuario_id"]?.jsonPrimitive?.content == superActor }, "actor en cada fila: $audits")
            }
        }
    }

    // ------------------------------------------------------------------ MASTER5-OBS-004

    private fun <T> captureObservability(block: (ListAppender<ILoggingEvent>) -> T): T {
        val logger = LoggerFactory.getLogger("OC-KTOR-OBSERVABILITY") as LogbackLogger
        val appender = ListAppender<ILoggingEvent>().also { it.start(); logger.addAppender(it) }
        return try {
            block(appender)
        } finally {
            logger.detachAppender(appender)
        }
    }

    @Test
    fun appForbiddenDenialCarriesTheSubject() = captureObservability { appender ->
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installRequestCorrelation()
                installBackendStatusPages()
                install(Authentication) { bearer("gateway-auth") { authenticate { UserIdPrincipal(it.token) } } }
                routing {
                    authenticate("gateway-auth") {
                        get("/deny") { throw AppForbidden("no") }
                    }
                }
            }
            val response = client.get("/deny") { header(HttpHeaders.Authorization, "Bearer $plainUser") }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            val requestId = response.headers["X-Request-Id"]!!
            val events = appender.list.map { it.formattedMessage }.filter { "request_id=$requestId" in it }
            assertTrue(events.any { "event=AUTHORIZATION_DENIED" in it && "user_id=$plainUser" in it }, events.toString())
        }
    }

    @Test
    fun canonicalDomainDenialsLeaveAnEventWithTheCodeAndTheVerifiedSubject() = captureObservability { appender ->
        testApplication {
            val blocked = CanonicalIdentityResolver(
                verifySubject = { plainUser },
                directory = { CanonicalUserIdentity(it, AccountStatus.BLOQUEADA, onboardingCompleted = true) },
            )
            val rejecting = CanonicalIdentityResolver(verifySubject = { throw JwtRejectedException("kid no encontrado en JWKS") }, directory = { null })
            application {
                install(ContentNegotiation) { json() }
                installRequestCorrelation()
                installBackendStatusPages()
                routing {
                    get("/blocked") { call.respondText(blocked.activeIdentity(call).userId.value) }
                    get("/bad-token") { call.respondText(rejecting.userId(call).value) }
                }
            }
            val token = "aaaaaaaaaa.bbbbbbbbbb.cccccccccc"
            val forbidden = client.get("/blocked") { header(HttpHeaders.Authorization, "Bearer $token") }
            assertEquals(HttpStatusCode.Forbidden, forbidden.status)
            val forbiddenEvents = appender.list.map { it.formattedMessage }.filter { "request_id=${forbidden.headers["X-Request-Id"]}" in it }
            assertTrue(
                forbiddenEvents.any {
                    "event=AUTHORIZATION_DENIED" in it && "error_type=ACCOUNT_BLOCKED" in it && "user_id=$plainUser" in it && "result=403" in it
                },
                forbiddenEvents.toString(),
            )

            val unauthorized = client.get("/bad-token") { header(HttpHeaders.Authorization, "Bearer $token") }
            assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)
            val unauthorizedEvents = appender.list.map { it.formattedMessage }.filter { "request_id=${unauthorized.headers["X-Request-Id"]}" in it }
            assertTrue(
                unauthorizedEvents.any { "event=AUTH_LOGIN_FAILED" in it && "error_type=UNAUTHENTICATED" in it && "user_id=-" in it },
                "sin sujeto verificado no se inventa uno: $unauthorizedEvents",
            )
        }
    }
}
