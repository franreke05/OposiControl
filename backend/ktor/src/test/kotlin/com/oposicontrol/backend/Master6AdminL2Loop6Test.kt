package com.oposicontrol.backend

import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.delete
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
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * LOOP 6, area L2 (OposiControl backend/ktor, plano admin):
 * MASTER6-API-001 (resolverReporte), MASTER6-API-003 (withUpstreamIo en todas las rutas admin), MASTER6-SEC-001 (ids de
 * ruta), MASTER6-SEC-002 (nombreMostrar sin formato invisible), MASTER6-IDEM-001 (saldo actual tras la concesion legacy),
 * MASTER6-API-007 (el asiento de ESTA concesion), MASTER6-OBS-006 (fallo de auth.users con evento), MASTER6-IDEM-003
 * (precondicion `expectedRoleIds`).
 */
class Master6AdminL2Loop6Test {
    private val admin = "6a6a6a6a-0000-4000-8000-000000000001"
    private val user = "6a6a6a6a-0000-4000-8000-000000000003"
    private val reporter = "6a6a6a6a-0000-4000-8000-000000000004"
    private val reportId = "6b6b6b6b-0000-4000-8000-000000000001"
    private val tx = "6c6c6c6c-0000-4000-8000-000000000001"
    private val otherTx = "6c6c6c6c-0000-4000-8000-000000000002"
    private val roleUsuario = "aaaaaaaa-0000-4000-8000-00000000000c"

    private class Sent(val method: String, val uri: String, val body: String)

    /** Supabase de mentira: cada respuesta se puede reprogramar por prueba. */
    private inner class Stub {
        val sent = CopyOnWriteArrayList<Sent>()
        var reportState: String = "pendiente"
        var reportReporter: String? = reporter
        var reportReviewer: String? = null
        var reportReviewedAt: String? = null
        /**
         * Round 3 (gate 102000): columna `accion_pendiente` del reporte. null = la fila no la trae (PostgREST anterior a
         * la migracion); false = cierre historico o resolucion completa; true = resolucion del Ktor nuevo en vuelo.
         */
        var reportPending: Boolean? = null
        var moderationActions = "[]"
        /** MASTER6-API-001: `admin_record_report_action` (true = accion insertada; false = ya la tenia otra llamada). */
        var actionInsert = V1StubResponse(200, "true")
        var syncRpc: V1StubResponse? = null
        var grantRpc = V1StubResponse(200, """[{"transaction_id":"$tx","saldo_resultante":15}]""")
        var casPatch = V1StubResponse(200, """[{"id":"$reportId"}]""")
        var priorRewardLedger = "[]"
        var wallet = V1StubResponse(200, """[{"usuario_id":"$user","saldo_tickets":42}]""")
        var authUser = V1StubResponse(200, """{"id":"$user","email":"a@b.c"}""")
        var rolesOfUser = "[]"
        var lockedAntes: String? = null

        fun of(method: String, fragment: String) = sent.filter { it.method == method && fragment in it.uri }
        fun rpcCalls(name: String) = of("POST", "/rest/v1/rpc/$name").size
        fun upstreamCalls() = sent.size
        fun actionCalls() = of("POST", "/rest/v1/rpc/admin_record_report_action")

        fun respond(method: String, uri: String): V1StubResponse = when {
            "/rest/v1/rpc/admin_grant_tickets" in uri -> grantRpc
            "/rest/v1/rpc/admin_sync_roles_audited" in uri -> syncRpc ?:
                V1StubResponse(200, """{"antes":${lockedAntes ?: rolesOfUser},"despues":["$roleUsuario"]}""")
            method == "GET" && "/rest/v1/reportes_contenido" in uri -> V1StubResponse(
                200,
                """[{"id":"$reportId","tipo_entidad":"publicacion_oposired","estado":"$reportState",""" +
                    """"reportado_por":${reportReporter?.let { "\"$it\"" } ?: "null"},""" +
                    """"revisado_por":${reportReviewer?.let { "\"$it\"" } ?: "null"},""" +
                    """"revisado_en":${reportReviewedAt?.let { "\"$it\"" } ?: "null"}""" +
                    (reportPending?.let { ""","accion_pendiente":$it""" } ?: "") + "}]",
            )
            method == "PATCH" && "/rest/v1/reportes_contenido" in uri && "estado=in." in uri -> casPatch
            method == "PATCH" && "/rest/v1/reportes_contenido" in uri -> V1StubResponse(204, "")
            method == "GET" && "/rest/v1/transacciones_cartera" in uri && "idempotency_key=eq." in uri ->
                V1StubResponse(200, priorRewardLedger)
            method == "GET" && "/rest/v1/transacciones_cartera" in uri && "id=eq.$tx" in uri -> V1StubResponse(
                200,
                """[{"id":"$tx","usuario_id":"$user","tipo":"ajuste","cantidad":5,"descripcion":"m","creado_en":"2026-10-01T00:00:00Z"}]""",
            )
            method == "GET" && "/rest/v1/transacciones_cartera" in uri -> V1StubResponse(
                200,
                """[{"id":"$otherTx","usuario_id":"$user","tipo":"ajuste","cantidad":99,"descripcion":"otra","creado_en":"2026-10-01T01:00:00Z"}]""",
            )
            method == "GET" && "/rest/v1/carteras_usuario" in uri -> wallet
            "/auth/v1/admin/users/" in uri -> authUser
            "/rest/v1/roles_app" in uri -> V1StubResponse(
                200,
                """[{"id":"aaaaaaaa-0000-4000-8000-00000000000a","codigo":"superadmin","nombre":"S"},""" +
                    """{"id":"$roleUsuario","codigo":"usuario","nombre":"U"}]""",
            )
            method == "GET" && "/rest/v1/roles_usuario" in uri ->
                V1StubResponse(200, Json.parseToJsonElement(rolesOfUser).jsonArray.joinToString(",", "[", "]") { """{"usuario_id":"$user","rol_id":$it}""" })
            "/rest/v1/perfiles_usuario" in uri -> V1StubResponse(
                200,
                """[{"usuario_id":"$user","username":"ada_l","nombre_mostrar":"Ada","es_invitado":false,"onboarding_completado":true,""" +
                    """"estado_cuenta":"activa","creado_en":"2026-01-02T03:04:05+00:00","actualizado_en":"2026-09-30T10:00:00+00:00"}]""",
            )
            method == "GET" && "/rest/v1/acciones_moderacion" in uri -> V1StubResponse(200, moderationActions)
            "/rest/v1/rpc/admin_record_report_action" in uri -> actionInsert
            method == "POST" && "/rest/v1/auditoria_log" in uri -> V1StubResponse(201, "")
            else -> V1StubResponse(200, "[]")
        }
    }

    private fun gateway(stub: Stub): AdminV1Gateway {
        var lastMethod = ""
        return AdminV1Gateway.forTesting(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = V1StubHttpClient({ request ->
                lastMethod = request.method()
                stub.sent += Sent(request.method(), request.uri().toString(), readRequestBody(request))
            }) { uri -> stub.respond(lastMethod, uri) },
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

    private suspend fun ApplicationTestBuilder.postJson(path: String, body: String): HttpResponse =
        client.post(path) {
            header(HttpHeaders.Authorization, "Bearer $admin")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpResponse.code() = Json.parseToJsonElement(bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content

    private fun Sent.json() = Json.parseToJsonElement(body).jsonObject

    // ------------------------------------------------------------------ MASTER6-API-001

    @Test
    fun aNegativeReportRewardIsRejectedBeforeAnyRequest() {
        val stub = Stub()
        val failure = assertFailsWith<BackendDomainException> {
            gateway(stub).resolverReporte(reportId, ResolverReporteBody(accion = "ocultar", ticketsReporter = -1), actorId = null)
        }
        assertEquals("WALLET_AMOUNT_INVALID", failure.errorCode)
        assertEquals(0, stub.upstreamCalls(), "ni lectura ni escritura: ${stub.sent.map { it.method + " " + it.uri }}")
    }

    @Test
    fun aZeroRewardIsValidAndRecordsNoReward() {
        val stub = Stub()
        gateway(stub).resolverReporte(reportId, ResolverReporteBody(accion = "descartar", ticketsReporter = 0), actorId = admin)
        assertEquals(0, stub.rpcCalls("admin_grant_tickets"))
        val cas = stub.of("PATCH", "/rest/v1/reportes_contenido").single().json()
        assertEquals("descartado", cas["estado"]!!.jsonPrimitive.content)
        assertEquals("false", cas["recompensa_entregada"]!!.jsonPrimitive.content)
        assertEquals("0", cas["tickets_otorgados"]!!.jsonPrimitive.content)
        assertEquals(1, stub.actionCalls().size)
    }

    @Test
    fun aReportWithoutReporterNeverClaimsARewardItDidNotGrant() {
        val stub = Stub().apply { reportReporter = null }
        gateway(stub).resolverReporte(reportId, ResolverReporteBody(accion = "ocultar", ticketsReporter = 5), actorId = admin)
        assertEquals(0, stub.rpcCalls("admin_grant_tickets"), "sin reportante no hay concesion")
        val cas = stub.of("PATCH", "/rest/v1/reportes_contenido").single().json()
        assertEquals("false", cas["recompensa_entregada"]!!.jsonPrimitive.content, "antes: true sin acreditar nada")
        assertEquals("0", cas["tickets_otorgados"]!!.jsonPrimitive.content)
    }

    @Test
    fun aFailedGrantAfterTheCommitLeavesAnIncompleteResolutionThatTheSameModeratorsRetryResumes() {
        val stub = Stub().apply { grantRpc = V1StubResponse(500, """{"message":"boom"}""") }
        val g = gateway(stub)
        assertFailsWith<Exception> {
            g.resolverReporte(reportId, ResolverReporteBody(accion = "ocultar", ticketsReporter = 5), actorId = admin)
        }
        val cas = stub.of("PATCH", "/rest/v1/reportes_contenido").single()
        assertEquals("false", cas.json()["recompensa_entregada"]!!.jsonPrimitive.content, "la marca solo tras concesion confirmada")
        assertEquals("0", cas.json()["tickets_otorgados"]!!.jsonPrimitive.content)
        assertTrue(stub.actionCalls().isEmpty(), "sin accion: resolucion incompleta")
        // El reporte quedo cerrado por ESTE moderador, sin accion. Su reintento reanuda (no 409) y concede con la misma clave.
        stub.reportState = "resuelto"
        stub.reportReviewer = admin
        stub.reportPending = true // la marca que dejo el compare-and-set
        stub.grantRpc = V1StubResponse(200, """[{"transaction_id":"$tx","saldo_resultante":15}]""")
        g.resolverReporte(reportId, ResolverReporteBody(accion = "ocultar", ticketsReporter = 5), actorId = admin)
        assertEquals(1, stub.of("PATCH", "estado=in.").size, "el reintento no repite la transicion")
        val stamp = stub.of("PATCH", "estado=eq.resuelto").single().json()
        assertEquals("true", stamp["recompensa_entregada"]!!.jsonPrimitive.content)
        assertEquals("5", stamp["tickets_otorgados"]!!.jsonPrimitive.content)
        assertEquals(1, stub.actionCalls().size)
        val keys = stub.of("POST", "admin_grant_tickets").map { it.json()["p_idempotency_key"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf("report-reward:$reportId"), keys)
    }

    @Test
    fun aFailedActionInsertAfterTheCommitIsResumedByTheRetryInsteadOf409() {
        val stub = Stub().apply { actionInsert = V1StubResponse(500, """{"message":"down"}""") }
        val g = gateway(stub)
        assertFailsWith<Exception> {
            g.resolverReporte(reportId, ResolverReporteBody(accion = "descartar", ticketsReporter = 0), actorId = admin)
        }
        stub.reportState = "descartado"
        stub.reportReviewer = admin
        stub.reportPending = true // la marca que dejo el compare-and-set
        stub.actionInsert = V1StubResponse(200, "true")
        g.resolverReporte(reportId, ResolverReporteBody(accion = "descartar", ticketsReporter = 0), actorId = admin)
        assertEquals(2, stub.actionCalls().size, "1 fallida + 1 reanudada")
        assertEquals(1, stub.of("PATCH", "estado=in.").size)
        assertEquals(1, stub.of("POST", "/rest/v1/auditoria_log").size, "auditoria solo de la resolucion completa")
    }

    @Test
    fun aClosedReportIsNeverResumedByAnotherModeratorOrAfterItsActionExists() {
        // Otro moderador cerro el reporte (mismo destino, sin accion aun): 409 sin efectos.
        val other = Stub().apply { reportState = "resuelto"; reportReviewer = "6a6a6a6a-0000-4000-8000-000000000009"; reportPending = true }
        assertEquals(
            "CONFLICT",
            assertFailsWith<BackendDomainException> {
                gateway(other).resolverReporte(reportId, ResolverReporteBody(accion = "ocultar", ticketsReporter = 5), actorId = admin)
            }.errorCode,
        )
        assertTrue(other.sent.none { it.method != "GET" }, other.sent.map { it.method + " " + it.uri }.toString())
        // El mismo moderador, resolucion ya completa (hay accion): 409 sin efectos.
        val done = Stub().apply {
            reportState = "resuelto"; reportReviewer = admin; reportPending = false; moderationActions = """[{"reporte_id":"$reportId"}]"""
        }
        assertFailsWith<BackendDomainException> {
            gateway(done).resolverReporte(reportId, ResolverReporteBody(accion = "ocultar", ticketsReporter = 5), actorId = admin)
        }
        assertTrue(done.sent.none { it.method != "GET" })
        // El mismo moderador con OTRO destino: 409.
        val flip = Stub().apply { reportState = "resuelto"; reportReviewer = admin; reportPending = true }
        assertFailsWith<BackendDomainException> {
            gateway(flip).resolverReporte(reportId, ResolverReporteBody(accion = "descartar", ticketsReporter = 0), actorId = admin)
        }
        assertTrue(flip.sent.none { it.method != "GET" })
    }

    @Test
    fun theTransitionCommitsFirstThenTheGrantThenTheStampThenTheAction() {
        val stub = Stub()
        gateway(stub).resolverReporte(reportId, ResolverReporteBody(accion = "ocultar", ticketsReporter = 5), actorId = admin)
        val order = stub.sent.map { it.method + " " + it.uri.substringAfter(".test") }
        val cas = order.indexOfFirst { it.startsWith("PATCH /rest/v1/reportes_contenido") && "estado=in." in it }
        val rpc = order.indexOfFirst { it.startsWith("POST /rest/v1/rpc/admin_grant_tickets") }
        val stamp = order.indexOfFirst { it.startsWith("PATCH /rest/v1/reportes_contenido") && "estado=eq.resuelto" in it }
        val action = order.indexOfFirst { it.startsWith("POST /rest/v1/rpc/admin_record_report_action") }
        assertTrue(cas in 0 until rpc && rpc < stamp && stamp < action, order.toString())
        val casBody = stub.of("PATCH", "estado=in.(pendiente,revisado)").single().json()
        assertEquals("false", casBody["recompensa_entregada"]!!.jsonPrimitive.content)
        assertEquals("0", casBody["tickets_otorgados"]!!.jsonPrimitive.content)
        val stampBody = stub.of("PATCH", "estado=eq.resuelto").single().json()
        assertEquals("true", stampBody["recompensa_entregada"]!!.jsonPrimitive.content)
        assertEquals("5", stampBody["tickets_otorgados"]!!.jsonPrimitive.content)
        assertEquals(setOf("tickets_otorgados", "recompensa_entregada"), stampBody.keys, "la marca no toca el estado")
        val rpcBody = stub.of("POST", "admin_grant_tickets").single().json()
        assertEquals("report-reward:$reportId", rpcBody["p_idempotency_key"]!!.jsonPrimitive.content)
        assertEquals(reporter, rpcBody["p_usuario_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun aReplayOnAResolvedReportIs409WithoutAnySideEffect() = testApplication {
        val stub = Stub().apply { reportState = "resuelto" }
        application { adminModule(gateway(stub)) }
        val response = postJson("/api/v1/admin/reportes/$reportId/resolver", """{"accion":"ocultar","ticketsReporter":5}""")
        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        assertEquals("CONFLICT", response.code())
        assertEquals(0, stub.rpcCalls("admin_grant_tickets"))
        assertTrue(stub.sent.none { it.method != "GET" }, "solo la lectura: ${stub.sent.map { it.method + " " + it.uri }}")
    }

    @Test
    fun theConcurrentLoserOfTheTransitionCreditsNothingAndWritesNothingElse() {
        // Moderador A pide 5 OT mientras B descarta y gana el compare-and-set.
        val stub = Stub().apply { casPatch = V1StubResponse(200, "[]") }
        val failure = assertFailsWith<BackendDomainException> {
            gateway(stub).resolverReporte(reportId, ResolverReporteBody(accion = "ocultar", ticketsReporter = 5), actorId = admin)
        }
        assertEquals("CONFLICT", failure.errorCode)
        assertEquals(0, stub.rpcCalls("admin_grant_tickets"), "antes: el perdedor ya habia acreditado 5 OT")
        assertTrue(stub.actionCalls().isEmpty())
        assertEquals(1, stub.of("PATCH", "/rest/v1/reportes_contenido").size, "solo el intento de transicion; ninguna marca")
    }

    @Test
    fun aRetryAfterATransitionFailureDoesNotDuplicateTheActionOrTheGrant() {
        val stub = Stub().apply { casPatch = V1StubResponse(500, """{"message":"down"}""") }
        val g = gateway(stub)
        assertFailsWith<Exception> {
            g.resolverReporte(reportId, ResolverReporteBody(accion = "ocultar", ticketsReporter = 5), actorId = admin)
        }
        assertTrue(stub.actionCalls().isEmpty(), "antes: la accion ya estaba insertada")
        assertEquals(0, stub.rpcCalls("admin_grant_tickets"), "nada se acredita antes del compromiso")
        stub.casPatch = V1StubResponse(200, """[{"id":"$reportId"}]""")
        g.resolverReporte(reportId, ResolverReporteBody(accion = "ocultar", ticketsReporter = 5), actorId = admin)
        assertEquals(1, stub.actionCalls().size)
        assertEquals(1, stub.rpcCalls("admin_grant_tickets"))
        val keys = stub.of("POST", "admin_grant_tickets").map { it.json()["p_idempotency_key"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf("report-reward:$reportId"), keys)
    }

    @Test
    fun aZeroRewardAfterAnEarlierConfirmedGrantTakesTheFlagFromTheLedger() {
        val stub = Stub().apply { priorRewardLedger = """[{"id":"$tx","usuario_id":"$reporter","tipo":"reporte_validado","cantidad":5}]""" }
        gateway(stub).resolverReporte(reportId, ResolverReporteBody(accion = "ocultar", ticketsReporter = 0), actorId = admin)
        val stamp = stub.of("PATCH", "estado=eq.resuelto").single().json()
        assertEquals("true", stamp["recompensa_entregada"]!!.jsonPrimitive.content)
        assertEquals("5", stamp["tickets_otorgados"]!!.jsonPrimitive.content)
        assertTrue(stub.of("GET", "idempotency_key=eq.report-reward%3A$reportId").isNotEmpty())
    }

    // ------------------------------------------------------------------ MASTER6-API-001 (remediacion completa)

    @Test
    fun theActionIsWrittenOnlyThroughTheAtomicRpcWithTheResolutionItCompletes() {
        val stub = Stub()
        gateway(stub).resolverReporte(reportId, ResolverReporteBody(accion = "ocultar", notas = "n", ticketsReporter = 0), actorId = admin)
        assertTrue(stub.sent.none { it.method == "POST" && "/rest/v1/acciones_moderacion" in it.uri }, "nunca un POST directo a la tabla")
        val body = stub.actionCalls().single().json()
        assertEquals(reportId, body["p_reporte_id"]!!.jsonPrimitive.content)
        assertEquals("resuelto", body["p_estado"]!!.jsonPrimitive.content)
        assertEquals(admin, body["p_moderador_id"]!!.jsonPrimitive.content)
        assertEquals("ocultar", body["p_tipo_accion"]!!.jsonPrimitive.content)
        assertEquals("n", body["p_notas"]!!.jsonPrimitive.content)
        assertEquals(1, stub.of("POST", "/rest/v1/auditoria_log").size)
    }

    @Test
    fun aConcurrentResumeThatFindsTheActionAlreadyRecordedIs409AndAuditsNothing() {
        // Dos reanudaciones del mismo moderador: esta pierde dentro de la RPC (la otra ya inserto la accion).
        val stub = Stub().apply { reportState = "resuelto"; reportReviewer = admin; reportPending = true; actionInsert = V1StubResponse(200, "false") }
        val failure = assertFailsWith<BackendDomainException> {
            gateway(stub).resolverReporte(reportId, ResolverReporteBody(accion = "ocultar", ticketsReporter = 0), actorId = admin)
        }
        assertEquals("CONFLICT", failure.errorCode)
        assertEquals(1, stub.actionCalls().size)
        assertTrue(stub.of("POST", "/rest/v1/auditoria_log").isEmpty(), "la auditoria solo la escribe quien inserto la accion")
    }

    @Test
    fun aCallerWithoutActorNeverResumesAClosedReportEvenIfItWasClosedWithoutActor() {
        val stub = Stub().apply { reportState = "resuelto"; reportReviewer = null; reportPending = true }
        val failure = assertFailsWith<BackendDomainException> {
            gateway(stub).resolverReporte(reportId, ResolverReporteBody(accion = "ocultar", ticketsReporter = 5), actorId = null)
        }
        assertEquals("CONFLICT", failure.errorCode)
        assertTrue(stub.sent.none { it.method != "GET" }, "sin escrituras: ${stub.sent.map { it.method + " " + it.uri }}")
        assertEquals(0, stub.rpcCalls("admin_grant_tickets"))
        assertTrue(stub.actionCalls().isEmpty())
    }

    // ------------------------------------------------------------------ Gate 102000 (round 3): marca en vuelo
    // Cambio DELIBERADO: las dos pruebas del round 2 (`anEarlierActionTakenWhileOpenOrByOtherStaffDoesNotBlockTheResume`
    // con su GET `creado_en=gte.` y `theResolversOwnActionAtOrAfterTheCloseCompletesTheResolution`) fijaban la clave de
    // tiempo que malinterpretaba los cierres historicos. Se sustituyen por la marca explicita `accion_pendiente`.

    @Test
    fun anEarlierActionTakenWhileOpenOrByOtherStaffDoesNotBlockTheResume() {
        // Cerrado por admin con la marca en vuelo y acciones anteriores/de otro staff: solo la marca decide, sin GET previo.
        val stub = Stub().apply {
            reportState = "resuelto"; reportReviewer = admin; reportReviewedAt = "2026-10-01T10:00:00.123456+00:00"
            reportPending = true
            moderationActions = """[{"reporte_id":"$reportId"},{"reporte_id":"$reportId"}]"""
        }
        gateway(stub).resolverReporte(reportId, ResolverReporteBody(accion = "ocultar", ticketsReporter = 0), actorId = admin)
        assertTrue(stub.of("GET", "/rest/v1/acciones_moderacion").isEmpty(), "ninguna clave de tiempo: la marca y la RPC deciden")
        assertEquals(1, stub.actionCalls().size, "antes: 409 permanente por la accion previa")
        assertEquals(1, stub.of("POST", "/rest/v1/auditoria_log").size)
    }

    /**
     * MEDIUM (regresion del round 2): un reporte cerrado por el Ktor de MAIN (accion PRIMERO, despues PATCH con
     * `revisado_en` del reloj de Ktor y `revisado_por` = actor JWT) no lleva la marca. El mismo moderador NUNCA lo
     * reanuda: 409 sin escrituras, sin concesion, sin segunda accion, auditoria ni evento, tenga o no acciones, en
     * cualquier orden, y tanto si PostgREST trae la columna en false como si no la trae.
     */
    @Test
    fun aHistoricalClosureByTheOldKtorIsNeverResumedByItsResolver() {
        val closedAt = "2026-10-01T10:00:00+00:00"
        val histories = listOf("[]", """[{"reporte_id":"$reportId"}]""", """[{"reporte_id":"$reportId"},{"reporte_id":"$reportId"}]""")
        for (pending in listOf(false, null)) for (estado in listOf("resuelto", "descartado")) for (actions in histories) {
            val stub = Stub().apply {
                reportState = estado; reportReviewer = admin; reportReviewedAt = closedAt
                reportPending = pending; moderationActions = actions
            }
            val accion = if (estado == "descartado") "descartar" else "ocultar"
            val failure = assertFailsWith<BackendDomainException> {
                gateway(stub).resolverReporte(reportId, ResolverReporteBody(accion = accion, ticketsReporter = 0), actorId = admin)
            }
            assertEquals("CONFLICT", failure.errorCode, "$pending/$estado/$actions")
            assertTrue(stub.sent.none { it.method != "GET" }, "sin escrituras ($pending/$estado/$actions): ${stub.sent.map { it.method + " " + it.uri }}")
            assertTrue(stub.actionCalls().isEmpty())
            assertEquals(0, stub.rpcCalls("admin_grant_tickets"))
        }
    }

    @Test
    fun theTransitionSetsTheInFlightMarkerThatOnlyTheRpcClears() {
        val stub = Stub()
        gateway(stub).resolverReporte(reportId, ResolverReporteBody(accion = "ocultar", ticketsReporter = 5), actorId = admin)
        val cas = stub.of("PATCH", "estado=in.(pendiente,revisado)").single().json()
        assertEquals("true", cas["accion_pendiente"]!!.jsonPrimitive.content)
        val stamp = stub.of("PATCH", "estado=eq.resuelto").single().json()
        assertFalse("accion_pendiente" in stamp.keys, "la marca solo la limpia admin_record_report_action")
    }

    // ------------------------------------------------------------------ MASTER7-API-004 / MASTER7-API-002
    // (cambio DELIBERADO en este fichero: las pruebas anteriores usaban `accion = "resolver"`, un valor que ningun
    // cliente envia y que ahora es 422; pasan a `ocultar`, una accion real, sin debilitar ninguna asercion.)

    @Test
    fun anUnknownOrTypoedActionIs422AndNeverGrantsOrWritesAnything() {
        for (bad in listOf("descartr", "resolver", "", "DESCARTAR")) {
            val stub = Stub()
            val failure = assertFailsWith<BackendDomainException> {
                gateway(stub).resolverReporte(reportId, ResolverReporteBody(accion = bad, ticketsReporter = 5), actorId = admin)
            }
            assertEquals("REPORT_ACTION_INVALID", failure.errorCode, bad)
            assertEquals(0, stub.upstreamCalls(), "ni lectura ni escritura ni concesion: ${stub.sent.map { it.method + " " + it.uri }}")
        }
    }

    @Test
    fun everyRealClientActionStillResolves() {
        // R2-API004: cable del cliente -> valor que escribe la RPC. TODO valor escrito esta en el CHECK de
        // acciones_moderacion.tipo_accion (ocultar, restaurar, eliminar, advertir, bloquear_usuario, descartar).
        val dbCheck = setOf("ocultar", "restaurar", "eliminar", "advertir", "bloquear_usuario", "descartar")
        val expected = mapOf(
            "descartar" to "descartar", "ocultar" to "ocultar", "restaurar" to "restaurar", "eliminar" to "eliminar",
            "advertir" to "advertir", "bloquear_usuario" to "bloquear_usuario",
            "avisar" to "advertir", "banear" to "bloquear_usuario", "resuelto_sin_accion" to "descartar",
        )
        for ((wire, db) in expected) {
            val stub = Stub()
            gateway(stub).resolverReporte(reportId, ResolverReporteBody(accion = wire, ticketsReporter = 0), actorId = admin)
            val written = stub.actionCalls().single().json()["p_tipo_accion"]!!.jsonPrimitive.content
            assertEquals(db, written, wire)
            assertTrue(written in dbCheck, "$wire -> $written viola el CHECK")
            val estado = stub.actionCalls().single().json()["p_estado"]!!.jsonPrimitive.content
            assertEquals(if (wire == "descartar") "descartado" else "resuelto", estado, wire)
        }
    }

    @Test
    fun resolvingWithoutActionKeepsTheRewardAndResolvedState() {
        val stub = Stub()
        gateway(stub).resolverReporte(reportId, ResolverReporteBody(accion = "resuelto_sin_accion", ticketsReporter = 5), actorId = admin)
        assertEquals("resuelto", stub.actionCalls().single().json()["p_estado"]!!.jsonPrimitive.content)
    }

    @Test
    fun notesAreCappedAt1000() {
        val stub = Stub()
        val failure = assertFailsWith<BackendDomainException> {
            gateway(stub).resolverReporte(reportId, ResolverReporteBody(accion = "ocultar", notas = "n".repeat(1_001)), actorId = admin)
        }
        assertEquals("REPORT_NOTES_TOO_LONG", failure.errorCode)
        assertEquals(0, stub.upstreamCalls())
        gateway(Stub()).resolverReporte(reportId, ResolverReporteBody(accion = "ocultar", notas = "n".repeat(1_000)), actorId = admin)
    }

    @Test
    fun dismissingWithARewardIs422WithoutAnyGrant() {
        val stub = Stub()
        val failure = assertFailsWith<BackendDomainException> {
            gateway(stub).resolverReporte(reportId, ResolverReporteBody(accion = "descartar", ticketsReporter = 5), actorId = admin)
        }
        assertEquals("WALLET_AMOUNT_INVALID", failure.errorCode)
        assertEquals(0, stub.upstreamCalls())
        assertEquals(0, stub.rpcCalls("admin_grant_tickets"))
    }

    private fun listUris(stub: Stub) = stub.of("GET", "/rest/v1/reportes_contenido?select=*").map { it.uri }

    @Test
    fun theReportListFiltersByEachValidEstadoAndKeepsTheDesktopDefault() = testApplication {
        val stub = Stub()
        application { adminModule(gateway(stub)) }
        suspend fun get(q: String) = client.get("/api/v1/admin/reportes$q") { header(HttpHeaders.Authorization, "Bearer $admin") }
        assertEquals(HttpStatusCode.OK, get("").status)
        assertTrue("estado=eq.pendiente" in listUris(stub).last(), "defecto de escritorio")
        assertEquals(HttpStatusCode.OK, get("?estado=revisado").status)
        assertTrue("estado=eq.revisado" in listUris(stub).last())
        assertEquals(HttpStatusCode.OK, get("?estado=todos").status)
        assertFalse("estado=" in listUris(stub).last(), "todos = sin filtro")
        assertTrue("limit=200&offset=0" in listUris(stub).last())
    }

    @Test
    fun anUnknownEstadoIs400AndNoUpstreamCallIsMade() = testApplication {
        val stub = Stub()
        application { adminModule(gateway(stub)) }
        val r = client.get("/api/v1/admin/reportes?estado=bogus") { header(HttpHeaders.Authorization, "Bearer $admin") }
        assertEquals(HttpStatusCode.BadRequest, r.status, r.bodyAsText())
        assertEquals(0, stub.upstreamCalls())
    }

    @Test
    fun theReportListPaginatesAndCoercesLimit() = testApplication {
        val stub = Stub()
        application { adminModule(gateway(stub)) }
        suspend fun get(q: String) = client.get("/api/v1/admin/reportes$q") { header(HttpHeaders.Authorization, "Bearer $admin") }
        assertEquals(HttpStatusCode.OK, get("?limit=0&offset=30").status)
        assertTrue("limit=1&offset=30" in listUris(stub).last(), listUris(stub).last())
        assertEquals(HttpStatusCode.OK, get("?limit=999&offset=-5").status)
        assertTrue("limit=200&offset=0" in listUris(stub).last(), listUris(stub).last())
        val before = stub.upstreamCalls()
        assertEquals(HttpStatusCode.BadRequest, get("?limit=abc").status)
        assertEquals(HttpStatusCode.BadRequest, get("?offset=1;drop").status)
        assertEquals(before, stub.upstreamCalls())
    }

    // ------------------------------------------------------------------ MASTER6-API-003

    /**
     * Escaneo del codigo de `adminV1Routes`: cada `gateway.` (fuera de comentarios) tiene que estar DENTRO de un bloque
     * `withUpstreamIo {` / `withUpstreamIoParallel {` abierto. Se lleva la profundidad de llaves real.
     */
    @Test
    fun everyAdminGatewayCallRunsInsideWithUpstreamIo() {
        val source = File("src/main/kotlin/com/oposicontrol/backend/AdminV1Routes.kt").readText()
        val start = source.indexOf("fun Route.adminV1Routes(gateway: AdminV1Gateway")
        assertTrue(start >= 0)
        val offenders = unwrappedGatewayCalls(source.substring(start))
        assertEquals(emptyList(), offenders, "llamadas bloqueantes en el hilo de Netty")
    }

    @Test
    fun theScanCatchesAnUnwrappedCall() {
        val bad = "fun Route.adminV1Routes(gateway: AdminV1Gateway) {\n  get { call.respond(gateway.listRoles()) }\n" +
            "  get { call.respond(withUpstreamIo { gateway.listRoles() }) }\n}\n"
        assertEquals(1, unwrappedGatewayCalls(bad).size)
    }

    private fun unwrappedGatewayCalls(code: String): List<String> {
        val offenders = mutableListOf<String>()
        val ioDepths = ArrayDeque<Int>()
        var depth = 0
        var pendingIo = false
        code.lineSequence().forEachIndexed { lineNo, raw ->
            val line = raw.substringBefore("//")
            var i = 0
            while (i < line.length) {
                if (line.startsWith("withUpstreamIo", i)) {
                    pendingIo = true
                    i += "withUpstreamIo".length
                    continue
                }
                if (line.startsWith("gateway.", i) && ioDepths.isEmpty()) offenders += "${lineNo + 1}: ${raw.trim()}"
                when (line[i]) {
                    '{' -> {
                        depth++
                        if (pendingIo) {
                            ioDepths.addLast(depth)
                            pendingIo = false
                        }
                    }
                    '}' -> {
                        if (ioDepths.lastOrNull() == depth) ioDepths.removeLast()
                        depth--
                        if (depth == 0) return offenders
                    }
                }
                i++
            }
        }
        return offenders
    }

    // ------------------------------------------------------------------ MASTER6-SEC-001

    @Test
    fun injectedPathIdsAreRejectedBeforeReachingPostgrest() = testApplication {
        val stub = Stub()
        application { adminModule(gateway(stub)) }
        val evil = "7%26nombre=eq.x"
        suspend fun check(response: HttpResponse) {
            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        }
        check(client.get("/api/v1/admin/reportes/$evil") { header(HttpHeaders.Authorization, "Bearer $admin") })
        check(postJson("/api/v1/admin/reportes/$evil/resolver", """{"accion":"descartar"}"""))
        check(client.get("/api/v1/admin/soporte/conversaciones/$evil/mensajes") { header(HttpHeaders.Authorization, "Bearer $admin") })
        check(postJson("/api/v1/admin/soporte/conversaciones/$evil/mensajes", """{"text":"hola"}"""))
        check(client.get("/api/v1/admin/moderacion/hilos/$evil") { header(HttpHeaders.Authorization, "Bearer $admin") })
        check(client.delete("/api/v1/admin/etiquetas/$evil") { header(HttpHeaders.Authorization, "Bearer $admin") })
        check(
            client.patch("/api/v1/admin/etiquetas/$evil") {
                header(HttpHeaders.Authorization, "Bearer $admin")
                contentType(ContentType.Application.Json)
                setBody("""{"nombre":"x"}""")
            },
        )
        assertEquals(0, stub.upstreamCalls(), "salieron: ${stub.sent.map { it.method + " " + it.uri }}")
    }

    @Test
    fun theGatewayMethodsValidateTheirIdsToo() {
        val stub = Stub()
        val g = gateway(stub)
        val evil = "7&nombre=eq.x"
        assertFailsWith<IllegalArgumentException> { g.deleteEtiqueta(evil) }
        assertFailsWith<IllegalArgumentException> { g.updateEtiqueta(evil, AdminEtiquetaDto(id = "", nombre = "x", slug = "x")) }
        assertFailsWith<IllegalArgumentException> { g.getReporte(evil) }
        assertFailsWith<IllegalArgumentException> { g.listSupportMessages(evil) }
        assertFailsWith<IllegalArgumentException> { g.getForoHilo(evil) }
        assertFailsWith<IllegalArgumentException> { g.resolverReporte(evil, ResolverReporteBody(), actorId = null) }
        assertEquals(0, stub.upstreamCalls())
        // Control positivo: un UUID real sale con su filtro.
        g.deleteEtiqueta(reportId)
        assertTrue(stub.of("DELETE", "/rest/v1/etiquetas?id=eq.$reportId").isNotEmpty())
    }

    // ------------------------------------------------------------------ MASTER6-SEC-002

    private suspend fun ApplicationTestBuilder.patchName(name: String): HttpResponse =
        client.patch("/api/v1/admin/usuarios/$user") {
            header(HttpHeaders.Authorization, "Bearer $admin")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("nombreMostrar", JsonPrimitive(name)) }.toString())
        }

    @Test
    fun displayNameRejectsInvisibleFormatAndBidiCharacters() = testApplication {
        val stub = Stub()
        application { adminModule(gateway(stub)) }
        for (bad in listOf("Ada‮L", "Ada​L", "﻿Ada", "Ada L", "Ada L", "Ada⁦L", "Ada‍L")) {
            val r = patchName(bad)
            val body = r.bodyAsText()
            assertEquals(HttpStatusCode.BadRequest, r.status, "rechaza ${bad.map { it.code }}: $body")
            assertTrue("\"issue\":\"INVALID_FORMAT\"" in body, body)
        }
        // Un sustituto suelto no se puede codificar en UTF-8 en el cuerpo: viaja como escape JSON.
        val lone = client.patch("/api/v1/admin/usuarios/$user") {
            header(HttpHeaders.Authorization, "Bearer $admin")
            contentType(ContentType.Application.Json)
            setBody("""{"nombreMostrar":"Ada\uD800L"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, lone.status, lone.bodyAsText())
        assertTrue(stub.sent.none { it.method == "PATCH" }, "ninguna escritura")
        assertEquals(HttpStatusCode.OK, patchName("Ada Lñ 😀 José-María").status)
    }

    @Test
    fun theDisplayNameValidatorUnit() {
        assertTrue(isAcceptableDisplayName("Ada Lovelace 😀"))
        assertFalse(isAcceptableDisplayName("a‮b"))
        assertFalse(isAcceptableDisplayName("a\u0085b"))
        assertFalse(isAcceptableDisplayName("a\uDC00"))
    }

    // ------------------------------------------------------------------ MASTER6-IDEM-001

    @Test
    fun theLegacyGrantAnswersTheCurrentBalanceNotTheReplayedLedgerBalance() = testApplication {
        val stub = Stub() // el RPC (repeticion) dice saldo_resultante=15; la cartera hoy tiene 42
        application { adminModule(gateway(stub)) }
        val response = postJson("/api/v1/admin/usuarios/$user/tickets", """{"cantidad":5,"motivo":"m","idempotencyKey":"k-1"}""")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals(42, Json.parseToJsonElement(response.bodyAsText()).jsonObject["saldo"]!!.jsonPrimitive.content.toInt())
        val order = stub.sent.map { it.method + " " + it.uri.substringAfter(".test").substringBefore('?') }
        assertTrue(order.indexOf("POST /rest/v1/rpc/admin_grant_tickets") < order.indexOf("GET /rest/v1/carteras_usuario"), order.toString())
    }

    @Test
    fun aFailedBalanceReadNeverFallsBackToTheLedgerBalance() = testApplication {
        val stub = Stub().apply { wallet = V1StubResponse(500, """{"message":"down"}""") }
        application { adminModule(gateway(stub)) }
        val response = postJson("/api/v1/admin/usuarios/$user/tickets", """{"cantidad":5,"motivo":"m","idempotencyKey":"k-1"}""")
        assertTrue(response.status.value >= 500, "${response.status}: ${response.bodyAsText()}")
        assertFalse("\"saldo\":15" in response.bodyAsText())
    }

    // ------------------------------------------------------------------ MASTER6-API-007

    @Test
    fun theLegacyAdjustmentReturnsTheLedgerRowOfThisGrantNotTheNewestOne() = testApplication {
        val stub = Stub()
        application { adminModule(gateway(stub)) }
        val response = postJson("/api/v1/admin/tickets/$user/transacciones", """{"amount":5,"reason":"m","idempotencyKey":"k-7"}""")
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val row = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(tx, row["id"]!!.jsonPrimitive.content, "antes: el movimiento mas reciente del usuario ($otherTx)")
        assertTrue(stub.of("GET", "transacciones_cartera?select=*&id=eq.$tx").isNotEmpty())
    }

    // ------------------------------------------------------------------ MASTER6-OBS-006

    private fun <T> captureEvents(block: () -> T): Pair<T, List<String>> {
        val logger = LoggerFactory.getLogger("OC-KTOR-OBSERVABILITY") as LogbackLogger
        val appender = ListAppender<ILoggingEvent>().also { it.start(); logger.addAppender(it) }
        return try {
            block() to appender.list.map { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
        }
    }

    @Test
    fun aFailedAuthUsersLookupLeavesAnEventButAMissingIdentityDoesNot() {
        val failing = Stub().apply { authUser = V1StubResponse(500, """{"msg":"secret-ish body a@b.c"}""") }
        val (user1, lines) = captureEvents { gateway(failing).getUsuarioAdmin(user) }
        assertTrue(user1 != null, "la ficha se sigue sirviendo, degradada")
        val event = lines.single { "event=SUPABASE_REQUEST_FAILED" in it && "operation=read_auth_info" in it }
        assertTrue("error_type=IllegalStateException:500" in event, event)
        assertFalse("a@b.c" in event, "nunca el cuerpo ni el email")

        val missing = Stub().apply { authUser = V1StubResponse(404, """{"msg":"User not found"}""") }
        val (_, quiet) = captureEvents { gateway(missing).getUsuarioAdmin(user) }
        assertTrue(quiet.none { "operation=read_auth_info" in it }, "un 404 es dato, no fallo: $quiet")
    }

    // ------------------------------------------------------------------ MASTER6-IDEM-003

    @Test
    fun aStaleExpectedRoleSetIs409AndWritesNothing() = testApplication {
        val stub = Stub().apply { rolesOfUser = """["aaaaaaaa-0000-4000-8000-00000000000a"]""" }
        application { adminModule(gateway(stub)) }
        val response = postJson(
            "/api/v1/admin/usuarios/$user/roles",
            """{"roleIds":["$roleUsuario"],"expectedRoleIds":["$roleUsuario"]}""",
        )
        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        assertEquals("VERSION_CONFLICT", response.code())
        assertEquals(0, stub.rpcCalls("admin_sync_roles_audited"), "ninguna sincronizacion")
        assertTrue(stub.of("POST", "/rest/v1/auditoria_log").isEmpty(), "ni siquiera la fila de intencion")
    }

    @Test
    fun aMatchingOrAbsentExpectedRoleSetStillSyncs() = testApplication {
        val stub = Stub().apply { rolesOfUser = """["$roleUsuario"]""" }
        application { adminModule(gateway(stub)) }
        val matching = postJson("/api/v1/admin/usuarios/$user/roles", """{"roleIds":["$roleUsuario"],"expectedRoleIds":["$roleUsuario"]}""")
        assertEquals(HttpStatusCode.OK, matching.status, matching.bodyAsText())
        val absent = postJson("/api/v1/admin/usuarios/$user/roles", """{"roleIds":["$roleUsuario"]}""")
        assertEquals(HttpStatusCode.OK, absent.status, absent.bodyAsText())
        assertEquals(2, stub.rpcCalls("admin_sync_roles_audited"))
        val badId = postJson("/api/v1/admin/usuarios/$user/roles", """{"roleIds":["$roleUsuario"],"expectedRoleIds":["no-uuid"]}""")
        assertEquals(HttpStatusCode.BadRequest, badId.status)
        assertEquals(2, stub.rpcCalls("admin_sync_roles_audited"))
    }

    /** Owner FA8 (D1-26): expectedRoleIds es una lista uuid de como mucho 50; 50 pasa, 51 es 400 sin tocar la base. */
    @Test
    fun expectedRoleIdsIsCappedAtFiftyUuids() = testApplication {
        val stub = Stub().apply { rolesOfUser = """["$roleUsuario"]""" }
        application { adminModule(gateway(stub)) }
        fun body(n: Int) = """{"roleIds":["$roleUsuario"],"expectedRoleIds":[${(1..n).joinToString(",") { "\"$roleUsuario\"" }}]}"""
        val over = postJson("/api/v1/admin/usuarios/$user/roles", body(51))
        assertEquals(HttpStatusCode.BadRequest, over.status, over.bodyAsText())
        assertEquals(0, stub.rpcCalls("admin_sync_roles_audited"))
    }

    /** Owner (ZWJ): los emojis compuestos con ZWJ y cualquier invisible/bidi/formato siguen rechazados; sin soporte de grafemas. */
    @Test
    fun composedEmojiNeedingZwjAndInvisibleFormatCharactersStayRejected() {
        assertFalse(isAcceptableDisplayName("Ada 👩‍👩‍👧"))
        assertFalse(isAcceptableDisplayName("Ada⁠L"))
        assertFalse(isAcceptableDisplayName("Ada‎L"))
        assertFalse(isAcceptableDisplayName("Ada­L"))
        assertTrue(isAcceptableDisplayName("Ada 👩 José"))
    }

    /**
     * MASTER6-IDEM-003 (mitigacion sin migracion): la precondicion pasa antes del cerrojo, pero la RPC lee BAJO el cerrojo
     * otro conjunto (otra sincronizacion entro en la ventana). La escritura no se puede impedir sin migracion; queda en la
     * fila DURADERA de resultado, no solo en un WARN.
     */
    @Test
    fun aPreconditionRacedUnderTheLockIsRecordedInTheDurableOutcomeRow() = testApplication {
        val superadmin = "aaaaaaaa-0000-4000-8000-00000000000a"
        val stub = Stub().apply { rolesOfUser = """["$roleUsuario"]"""; lockedAntes = """["$roleUsuario","$superadmin"]""" }
        application { adminModule(gateway(stub)) }
        val response = postJson("/api/v1/admin/usuarios/$user/roles", """{"roleIds":["$roleUsuario"],"expectedRoleIds":["$roleUsuario"]}""")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val outcome = stub.of("POST", "/rest/v1/auditoria_log").map { it.body }.single { "roleIdsSolicitados" in it }
        assertTrue(""""precondicionViolada":true""" in outcome, outcome)
        assertTrue("roleIdsEsperados" in outcome && superadmin in outcome, outcome)
    }

    @Test
    fun aPreconditionThatHoldsUnderTheLockIsNotFlagged() = testApplication {
        val stub = Stub().apply { rolesOfUser = """["$roleUsuario"]""" }
        application { adminModule(gateway(stub)) }
        val response = postJson("/api/v1/admin/usuarios/$user/roles", """{"roleIds":["$roleUsuario"],"expectedRoleIds":["$roleUsuario"]}""")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val outcome = stub.of("POST", "/rest/v1/auditoria_log").map { it.body }.single { "roleIdsSolicitados" in it }
        assertFalse("precondicionViolada" in outcome, outcome)
    }

    // ------------------------------------------------------------------ MASTER6-IDEM-003 (comparacion bajo el cerrojo)

    @Test
    fun theRpcAlwaysCarriesTheExpectedSetOrJsonNull() = testApplication {
        val stub = Stub().apply { rolesOfUser = """["$roleUsuario"]""" }
        application { adminModule(gateway(stub)) }
        postJson("/api/v1/admin/usuarios/$user/roles", """{"roleIds":["$roleUsuario"],"expectedRoleIds":["$roleUsuario","$roleUsuario"]}""")
        postJson("/api/v1/admin/usuarios/$user/roles", """{"roleIds":["$roleUsuario"]}""")
        val bodies = stub.of("POST", "/rest/v1/rpc/admin_sync_roles_audited").map { it.json() }
        assertEquals(2, bodies.size)
        assertEquals(listOf(roleUsuario), bodies[0]["p_expected_role_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(bodies[1].containsKey("p_expected_role_ids"), "presente aunque sea null: resuelve la sobrecarga de 4 argumentos")
        assertEquals(kotlinx.serialization.json.JsonNull, bodies[1]["p_expected_role_ids"])
    }

    @Test
    fun aConflictRaisedUnderTheLockIs409VersionConflictAndLeavesARejectedFailureRow() = testApplication {
        // La precondicion pasa fuera del cerrojo; la RPC ve otro conjunto y levanta ROLE_SYNC_VERSION_CONFLICT.
        val stub = Stub().apply {
            rolesOfUser = """["$roleUsuario"]"""
            syncRpc = V1StubResponse(400, """{"code":"P0001","details":null,"hint":null,"message":"ROLE_SYNC_VERSION_CONFLICT"}""")
        }
        application { adminModule(gateway(stub)) }
        val response = postJson("/api/v1/admin/usuarios/$user/roles", """{"roleIds":["$roleUsuario"],"expectedRoleIds":["$roleUsuario"]}""")
        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        assertEquals("VERSION_CONFLICT", response.code(), "codigo canonico FA8, no el interno de la base")
        val audits = stub.of("POST", "/rest/v1/auditoria_log").map { it.body }
        assertTrue(audits.none { ACTION_ROLES_SYNCED in it && "roleIdsSolicitados" in it }, audits.toString())
        val failed = audits.single { ACTION_ROLES_SYNC_FAILED in it }
        assertTrue(""""resultado":"rechazada"""" in failed, failed)
        assertTrue(""""error":"VERSION_CONFLICT"""" in failed, failed)
    }
}
