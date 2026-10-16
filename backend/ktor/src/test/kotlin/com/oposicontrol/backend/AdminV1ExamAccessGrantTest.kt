package com.oposicontrol.backend

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `POST /api/v1/admin/accesos`: el `motivo` que se escribe en
 * `contenido_desbloqueado_usuario` tiene un CHECK vivo
 * (`premium, compra, regalo, admin, promocion`). El escritorio antiguo mandaba
 * `manual` por defecto, que la base rechazaba SIEMPRE con `check_violation`.
 *
 * Se afirma sobre el cuerpo del POST que sale hacia PostgREST, no sobre lo que
 * devuelve la función: la respuesta de la base es fija en el stub.
 */
class AdminV1ExamAccessGrantTest {

    private val userId = "11111111-1111-4111-8111-111111111111"
    private val examenId = "22222222-2222-4222-8222-222222222222"
    private val accesoId = "33333333-3333-4333-8333-333333333333"

    /** [existing] = filas de la tabla tal como las devolveria PostgREST al LEER (por usuario+examen o por id). */
    private var rpcOutcome = "CREATED"

    private fun gatewayWith(existing: String = "[]", record: (HttpRequest) -> Unit): AdminV1Gateway = AdminV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient(record) { uri ->
            when {
                // V1: el regalo es UNA RPC atomica (un mes fijado por el servidor, puertas de especialidad y capacidad).
                uri.contains("/rest/v1/rpc/admin_grant_exam_access") -> V1StubResponse(
                    200,
                    """[{"outcome":"$rpcOutcome","grant_id":"$accesoId","valido_desde":"2026-10-16T10:00:00+00:00","valido_hasta":"2026-11-15T10:00:00+00:00"}]""",
                )
                uri.contains("/rest/v1/perfiles_usuario") -> V1StubResponse(
                    200,
                    """[{"usuario_id":"$userId","username":"ana","nombre_mostrar":"Ana"}]""",
                )
                uri.contains("/rest/v1/examenes") -> V1StubResponse(200, """[{"id":"$examenId","titulo":"Examen A"}]""")
                // Lecturas (llevan filtro): lo que haya en la tabla. La escritura no lleva filtro y devuelve la fila creada.
                uri.contains("/rest/v1/contenido_desbloqueado_usuario") && uri.contains("=eq.") -> V1StubResponse(200, existing)
                uri.contains("/rest/v1/contenido_desbloqueado_usuario") -> V1StubResponse(
                    200,
                    """[{"id":"$accesoId","usuario_id":"$userId","examen_id":"$examenId","motivo":"admin"}]""",
                )
                else -> V1StubResponse(200, "[]")
            }
        },
    )

    private fun input(accessType: String) = AdminExamAccessDto(
        id = accesoId,
        userName = "ana",
        examTitle = "Examen A",
        accessType = accessType,
        isActive = true,
    )

    private fun motivoWritten(upstream: List<HttpRequest>): String {
        val post = upstream.single { it.method() == "POST" && it.uri().rawPath.contains("admin_grant_exam_access") }
        return Json.parseToJsonElement(readRequestBody(post)).jsonObject.getValue("p_motivo").jsonPrimitive.content
    }

    @Test fun theLegacyManualAliasIsWrittenAsAdmin() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        gatewayWith(record = upstream::add).upsertExamAccess(input("manual"))
        assertEquals("admin", motivoWritten(upstream))
    }

    @Test fun manualIsCaseAndWhitespaceInsensitive() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        gatewayWith(record = upstream::add).upsertExamAccess(input("  Manual "))
        assertEquals("admin", motivoWritten(upstream))
    }

    @Test fun aBlankTypeIsWrittenAsAdmin() {
        listOf("", "   ").forEach { blank ->
            val upstream = CopyOnWriteArrayList<HttpRequest>()
            gatewayWith(record = upstream::add).upsertExamAccess(input(blank))
            assertEquals("admin", motivoWritten(upstream), "blank='$blank'")
        }
    }

    @Test fun everyCanonicalValueIsWrittenAsIs() {
        listOf("premium", "regalo", "admin", "promocion").forEach { value ->
            val upstream = CopyOnWriteArrayList<HttpRequest>()
            gatewayWith(record = upstream::add).upsertExamAccess(input(value))
            assertEquals(value, motivoWritten(upstream))
        }
    }

    @Test fun aPurchaseCannotBeGrantedByHand() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        val error = assertFailsWith<BackendDomainException> { gatewayWith(record = upstream::add).upsertExamAccess(input("compra")) }
        assertEquals("GRANT_REASON_NOT_ALLOWED", error.errorCode)
        assertTrue(upstream.isEmpty(), "no debe salir ninguna petición")
    }

    @Test fun theSwitchNoLongerPretendsToRevoke() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        val error = assertFailsWith<BackendDomainException> {
            gatewayWith(record = upstream::add).upsertExamAccess(input("admin").copy(isActive = false))
        }
        assertEquals("GRANT_REVOKE_USE_ROUTE", error.errorCode)
        assertTrue(upstream.isEmpty(), "antes concedía en silencio")
    }

    @Test fun theGiftGoesToOneAtomicRpcWithOnlyUserExamAndReasonSoTheClientCanNeitherSetAnIdNorATerm() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        rpcOutcome = "CREATED"
        val result = gatewayWith(record = upstream::add).upsertExamAccess(input("admin").copy(validUntil = "2099-01-01T00:00:00Z"))
        val post = upstream.single { it.method() == "POST" && it.uri().rawPath.contains("admin_grant_exam_access") }
        val body = Json.parseToJsonElement(readRequestBody(post)).jsonObject
        assertEquals(setOf("p_usuario", "p_examen", "p_motivo"), body.keys, "ni el id ni el plazo del cliente viajan: el mes lo fija el servidor")
        assertTrue(upstream.none { it.method() == "POST" && it.uri().rawPath.endsWith("/contenido_desbloqueado_usuario") }, "ninguna escritura directa a la tabla (podia crear un acceso ilimitado)")
        assertEquals(accesoId, result.id)
        assertEquals("2026-11-15T10:00:00+00:00", result.validUntil, "se devuelve el fin de vigencia que fijo la base")
        assertTrue(upstream.any { it.uri().rawPath.contains("auditoria_log") && it.method() == "POST" }, "conceder queda en auditoria")
    }

    @Test fun anAlreadyUnlimitedGrantOrAReplayWritesNoSecondAuditEntry() {
        for (outcome in listOf("UNLIMITED_EXISTS", "REPLAY")) {
            val upstream = CopyOnWriteArrayList<HttpRequest>()
            rpcOutcome = outcome
            val result = gatewayWith(record = upstream::add).upsertExamAccess(input("admin"))
            assertEquals(accesoId, result.id)
            assertTrue(upstream.none { it.uri().rawPath.contains("auditoria_log") }, "$outcome no audita otra concesion")
        }
        rpcOutcome = "CREATED"
    }

    @Test fun revokingClosesEveryActiveManualGrantOfThatUserAndExamAndAuditsIt() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        val twin = "44444444-4444-4444-8444-444444444444"
        val bought = "55555555-5555-4555-8555-555555555555"
        val rows = """[
            {"id":"$accesoId","usuario_id":"$userId","examen_id":"$examenId","motivo":"admin"},
            {"id":"$twin","usuario_id":"$userId","examen_id":"$examenId","motivo":"regalo"},
            {"id":"$bought","usuario_id":"$userId","examen_id":"$examenId","motivo":"compra"}
        ]"""
        val result = gatewayWith(rows, upstream::add).revokeExamAccess(accesoId)
        assertFalse(result.isActive)
        val patch = upstream.single { it.method() == "PATCH" }
        val target = patch.uri().toString()
        assertTrue(target.contains(accesoId) && target.contains(twin), "dos filas vigentes: cerrar una sola no retiraba nada")
        assertFalse(target.contains(bought), "una compra no se retira desde aquí")
        assertTrue(readRequestBody(patch).contains("valido_hasta"))
        assertTrue(
            upstream.any { it.method() == "POST" && readRequestBody(it).contains("acceso_examen_retirado") },
            "la retirada queda en auditoría: ${upstream.map { it.method() + " " + it.uri().rawPath }}",
        )
    }

    @Test fun revokingWhatIsAlreadyRevokedWritesNothing() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        val expired = """[{"id":"$accesoId","usuario_id":"$userId","examen_id":"$examenId","motivo":"admin","valido_hasta":"2020-01-01T00:00:00+00:00"}]"""
        val result = gatewayWith(expired, upstream::add).revokeExamAccess(accesoId)
        assertFalse(result.isActive)
        assertTrue(upstream.none { it.method() == "PATCH" || it.method() == "POST" })
    }

    @Test fun aPurchaseIsNotRevokedHereAndAnUnknownGrantIsNotFound() {
        val bought = """[{"id":"$accesoId","usuario_id":"$userId","examen_id":"$examenId","motivo":"compra"}]"""
        assertEquals("GRANT_IS_PURCHASE", assertFailsWith<BackendDomainException> { gatewayWith(bought) {}.revokeExamAccess(accesoId) }.errorCode)
        assertEquals("GRANT_NOT_FOUND", assertFailsWith<BackendDomainException> { gatewayWith {}.revokeExamAccess(accesoId) }.errorCode)
        assertFailsWith<IllegalArgumentException> { gatewayWith {}.revokeExamAccess("no-es-un-uuid") }
    }

    @Test fun theRevokeRouteAnswers200WithTheGrantNoLongerActive() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        val rows = """[{"id":"$accesoId","usuario_id":"$userId","examen_id":"$examenId","motivo":"admin"}]"""
        application { accessModule(upstream, rows) }
        val response = client.post("/api/v1/admin/accesos/$accesoId/revocar")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"isActive\":false"), response.bodyAsText())
    }

    @Test fun canonicalValuesAreTrimmedAndLowercased() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        gatewayWith(record = upstream::add).upsertExamAccess(input(" PREMIUM "))
        assertEquals("premium", motivoWritten(upstream))
    }

    @Test fun anUnknownTypeIsRejectedWithoutAnyUpstreamCall() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        val error = assertFailsWith<BackendDomainException> {
            gatewayWith(record = upstream::add).upsertExamAccess(input("inventado"))
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, error.status)
        assertEquals("EXAM_ACCESS_GRANT_TYPE_UNKNOWN", error.errorCode)
        assertTrue(error.message.startsWith("Tipo de acceso no válido. Valores admitidos:"), error.message)
        listOf("premium", "compra", "regalo", "admin", "promocion").forEach {
            assertTrue(error.message.contains(it), "el mensaje no nombra '$it': ${error.message}")
        }
        assertFalse(error.message.contains("inventado"), "no se devuelve la entrada del cliente")
        assertTrue(upstream.isEmpty(), "no debe salir ninguna petición: ${upstream.map { it.uri() }}")
    }

    @Test fun theRouteAnswers422AndNeverPostsAnUnknownType() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { accessModule(upstream) }

        val response = client.post("/api/v1/admin/accesos") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"$accesoId","userName":"ana","examTitle":"Examen A","accessType":"inventado","isActive":true}""")
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertTrue(upstream.none { it.method() == "POST" }, "se emitió un POST: ${upstream.map { it.uri() }}")
    }

    @Test fun theRouteAcceptsManualAndCreatesTheGrant() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        application { accessModule(upstream) }

        val response = client.post("/api/v1/admin/accesos") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"$accesoId","userName":"ana","examTitle":"Examen A","accessType":"manual","isActive":true}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals("admin", motivoWritten(upstream))
    }

    private fun Application.accessModule(upstream: MutableList<HttpRequest>, existing: String = "[]") {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false })
        }
        install(StatusPages) {
            exception<BackendDomainException> { call, cause ->
                call.respond(cause.status, ErrorDto(error = cause.errorCode.lowercase(), message = cause.message))
            }
            exception<IllegalArgumentException> { call, cause ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorDto(error = "bad_request", message = cause.message ?: "Invalid request"),
                )
            }
        }
        routing { adminV1Routes(gatewayWith(existing, upstream::add)) }
    }
}
