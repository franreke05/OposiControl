package com.oposicontrol.backend

import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * LOOP 4, area KTOR_SEC:
 * - MASTER4-API-002: agotar los intentos condicionados de `updateUsuario` salia como `IllegalStateException` -> 502
 *   UPSTREAM_ERROR + evento SUPABASE_REQUEST_FAILED (upstream sano; codigo fuera de D1-25). Ahora 500 INTERNAL.
 * - MASTER4-OBS-001: el `antes` de la auditoria de D1-26 se leia fuera del cerrojo de la RPC. El resultado lleva ahora los
 *   conjuntos leidos BAJO el cerrojo por `admin_sync_roles_audited`.
 */
class AdminKtorSecLoop4Test {
    private val u1 = "11111111-1111-4111-8111-111111111111"
    private val superActor = "5a5a5a5a-0000-4000-8000-000000000001"
    private val plainUser = "5a5a5a5a-0000-4000-8000-000000000003"
    private val roleSuper = "aaaaaaaa-0000-4000-8000-00000000000a"
    private val roleAdmin = "aaaaaaaa-0000-4000-8000-00000000000b"
    private val roleUser = "aaaaaaaa-0000-4000-8000-00000000000c"
    private val roleMod = "aaaaaaaa-0000-4000-8000-00000000000d"

    private class Sent(val method: String, val uri: String, val body: String)

    private fun gateway(sent: MutableList<Sent>, responder: (method: String, uri: String) -> V1StubResponse): AdminV1Gateway {
        var lastMethod = ""
        return AdminV1Gateway.forTesting(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = V1StubHttpClient({ request: HttpRequest ->
                lastMethod = request.method()
                sent += Sent(request.method(), request.uri().toString(), readRequestBody(request))
            }) { uri -> responder(lastMethod, uri) },
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

    private fun List<Sent>.audits() = filter { it.method == "POST" && "/rest/v1/auditoria_log" in it.uri }
        .map { Json.parseToJsonElement(it.body).jsonArray.single().jsonObject }

    private fun JsonObject.ids(side: String, key: String = "roleIds") =
        this[side]!!.jsonObject[key]!!.jsonArray.map { it.jsonPrimitive.content }

    // ------------------------------------------------------------------ MASTER4-API-002

    private fun alwaysChangingRow(sent: MutableList<Sent>): AdminV1Gateway {
        val reads = AtomicInteger()
        return gateway(sent) { method, uri ->
            when {
                method == "GET" && "/rest/v1/perfiles_usuario" in uri -> {
                    val n = reads.getAndIncrement()
                    V1StubResponse(
                        200,
                        """[{"usuario_id":"$u1","username":"ada_l","nombre_mostrar":"X$n","es_invitado":false,"onboarding_completado":true,""" +
                            """"estado_cuenta":"activa","creado_en":"2026-01-02T03:04:05.123456+00:00","actualizado_en":"2026-09-30T10:00:0$n.000000+00:00"}]""",
                    )
                }
                else -> V1StubResponse(200, "[]")
            }
        }
    }

    @Test
    fun exhaustingTheConditionalWriteAttemptsIsADomainInternalErrorNotAnUpstreamFailure() {
        val sent = CopyOnWriteArrayList<Sent>()
        val failure = assertFailsWith<BackendDomainException> { alwaysChangingRow(sent).updateUsuario(u1, "Z") }
        assertEquals(CanonicalErrorCode.INTERNAL.name, failure.errorCode)
        assertEquals(HttpStatusCode.InternalServerError, failure.status)
        assertEquals(ADMIN_USER_UPDATE_ATTEMPTS, sent.count { it.method == "PATCH" })
        assertTrue(sent.audits().isEmpty(), "nada se escribio: sin fila de auditoria")
    }

    @Test
    fun theD125RouteAnswers500InternalAndLogsItAsAServerFailureNotAsASupabaseFailure() {
        val logger = LoggerFactory.getLogger("OC-KTOR-OBSERVABILITY") as LogbackLogger
        val appender = ListAppender<ILoggingEvent>().also { it.start(); logger.addAppender(it) }
        try {
            testApplication {
                val sent = CopyOnWriteArrayList<Sent>()
                application { adminModule(alwaysChangingRow(sent)) }
                val response = client.patch("/api/v1/admin/usuarios/$u1") {
                    header(HttpHeaders.Authorization, "Bearer $superActor")
                    contentType(ContentType.Application.Json)
                    setBody("""{"nombreMostrar":"Z"}""")
                }
                val body = response.bodyAsText()
                assertEquals(HttpStatusCode.InternalServerError, response.status, body)
                assertEquals("INTERNAL", Json.parseToJsonElement(body).jsonObject["code"]!!.jsonPrimitive.content, body)
                val requestId = response.headers["X-Request-Id"]!!
                val events = appender.list.map { it.formattedMessage }.filter { "request_id=$requestId" in it }
                assertTrue(events.any { "event=UNCAUGHT_EXCEPTION" in it && "error_type=INTERNAL" in it }, events.toString())
                assertTrue(events.none { "event=SUPABASE_REQUEST_FAILED" in it || "event=UPSTREAM_TIMEOUT" in it }, events.toString())
            }
        } finally {
            logger.detachAppender(appender)
        }
    }

    // ------------------------------------------------------------------ MASTER4-OBS-001

    /**
     * La lectura previa (fuera del cerrojo) ve [usuario]; mientras espera el cerrojo otra sincronizacion deja
     * [usuario, moderador]. La RPC devuelve lo que vio BAJO el cerrojo: la fila de resultado lo registra; la de intencion
     * conserva la foto previa (lo que el actor vio y se autorizo).
     */
    private fun rolesGateway(sent: MutableList<Sent>, rpcAnswer: V1StubResponse): AdminV1Gateway = gateway(sent) { _, uri ->
        when {
            "/rest/v1/roles_app" in uri -> V1StubResponse(
                200,
                """[{"id":"$roleSuper","codigo":"superadmin","nombre":"S"},{"id":"$roleAdmin","codigo":"admin","nombre":"A"},""" +
                    """{"id":"$roleUser","codigo":"usuario","nombre":"U"},{"id":"$roleMod","codigo":"moderador","nombre":"M"}]""",
            )
            "/rest/v1/roles_usuario?select=rol_id&usuario_id=eq.$superActor" in uri ->
                V1StubResponse(200, """[{"usuario_id":"$superActor","rol_id":"$roleSuper"}]""")
            "/rest/v1/roles_usuario?select=rol_id&usuario_id=eq.$plainUser" in uri ->
                V1StubResponse(200, """[{"usuario_id":"$plainUser","rol_id":"$roleUser"}]""")
            "/rest/v1/rpc/admin_sync_roles_audited" in uri -> rpcAnswer
            "/rest/v1/auditoria_log" in uri -> V1StubResponse(201, "")
            else -> V1StubResponse(200, "[]")
        }
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.grantAdmin() =
        client.post("/api/v1/admin/usuarios/$plainUser/roles") {
            header(HttpHeaders.Authorization, "Bearer $superActor")
            contentType(ContentType.Application.Json)
            setBody("""{"roleIds":["$roleUser","$roleAdmin"]}""")
        }

    @Test
    fun theOutcomeRowCarriesTheRoleSetsReadUnderTheLockAndTheIntentKeepsThePreLockSnapshot() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        val g = rolesGateway(sent, V1StubResponse(200, """{"antes":["$roleMod","$roleUser"],"despues":["$roleAdmin","$roleUser"]}"""))
        application { adminModule(g) }
        val response = grantAdmin()
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals(1, sent.count { "/rest/v1/rpc/admin_sync_roles_audited" in it.uri }, "la RPC que devuelve los conjuntos bajo cerrojo")
        val (intent, outcome) = sent.audits()
        assertEquals(ACTION_ROLES_SYNC_INTENT, intent["accion"]!!.jsonPrimitive.content)
        assertEquals(listOf(roleUser), intent.ids("antes"), "la intencion: foto previa al cerrojo")
        assertEquals(listOf(roleUser, roleAdmin), intent.ids("despues"), "la intencion: lo pedido")
        assertEquals(ACTION_ROLES_SYNCED, outcome["accion"]!!.jsonPrimitive.content)
        assertEquals(listOf(roleMod, roleUser), outcome.ids("antes"), "el resultado: lo que esta sincronizacion reemplazo de verdad")
        assertEquals(listOf(roleAdmin, roleUser), outcome.ids("despues"), "el resultado: lo que quedo")
        assertEquals(listOf(roleUser, roleAdmin), outcome.ids("despues", "roleIdsSolicitados"))
        assertEquals(true, outcome["despues"]!!.jsonObject["bajoCerrojo"]!!.jsonPrimitive.boolean)
        assertEquals(response.headers["X-Request-Id"], outcome["despues"]!!.jsonObject["requestId"]!!.jsonPrimitive.content)
    }

    @Test
    fun anUndecodableAnswerAfterACommittedSyncIsNotAnErrorAndTheOutcomeSaysItIsNotTheLockedView() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        val g = rolesGateway(sent, V1StubResponse(200, "not json"))
        application { adminModule(g) }
        val response = grantAdmin()
        assertEquals(HttpStatusCode.OK, response.status, "la sincronizacion ya se confirmo: ${response.bodyAsText()}")
        val outcome = sent.audits().last()
        assertEquals(ACTION_ROLES_SYNCED, outcome["accion"]!!.jsonPrimitive.content)
        assertEquals(false, outcome["despues"]!!.jsonObject["bajoCerrojo"]!!.jsonPrimitive.boolean)
        assertEquals(listOf(roleUser), outcome.ids("antes"))
    }

    @Test
    fun aRejectedAuditedSyncStillLeavesTheFailureCounterpart() = testApplication {
        val sent = CopyOnWriteArrayList<Sent>()
        val g = rolesGateway(sent, V1StubResponse(400, """{"code":"P0001","message":"ROLE_SYNC_LAST_SUPERADMIN"}"""))
        application { adminModule(g) }
        val response = grantAdmin()
        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        assertEquals(listOf(ACTION_ROLES_SYNC_INTENT, ACTION_ROLES_SYNC_FAILED), sent.audits().map { it["accion"]!!.jsonPrimitive.content })
    }
}
