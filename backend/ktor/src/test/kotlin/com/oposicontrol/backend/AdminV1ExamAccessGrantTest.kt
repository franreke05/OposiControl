package com.oposicontrol.backend

import io.ktor.client.request.post
import io.ktor.client.request.setBody
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

    private fun gatewayWith(record: (HttpRequest) -> Unit): AdminV1Gateway = AdminV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient(record) { uri ->
            when {
                uri.contains("/rest/v1/perfiles_usuario") -> V1StubResponse(
                    200,
                    """[{"usuario_id":"$userId","username":"ana","nombre_mostrar":"Ana"}]""",
                )
                uri.contains("/rest/v1/examenes") -> V1StubResponse(200, """[{"id":"$examenId","titulo":"Examen A"}]""")
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
        val post = upstream.single { it.method() == "POST" && it.uri().rawPath.contains("contenido_desbloqueado_usuario") }
        return Json.parseToJsonElement(readRequestBody(post)).jsonArray.single().jsonObject.getValue("motivo").jsonPrimitive.content
    }

    @Test fun theLegacyManualAliasIsWrittenAsAdmin() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        gatewayWith(upstream::add).upsertExamAccess(input("manual"))
        assertEquals("admin", motivoWritten(upstream))
    }

    @Test fun manualIsCaseAndWhitespaceInsensitive() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        gatewayWith(upstream::add).upsertExamAccess(input("  Manual "))
        assertEquals("admin", motivoWritten(upstream))
    }

    @Test fun aBlankTypeIsWrittenAsAdmin() {
        listOf("", "   ").forEach { blank ->
            val upstream = CopyOnWriteArrayList<HttpRequest>()
            gatewayWith(upstream::add).upsertExamAccess(input(blank))
            assertEquals("admin", motivoWritten(upstream), "blank='$blank'")
        }
    }

    @Test fun everyCanonicalValueIsWrittenAsIs() {
        listOf("premium", "compra", "regalo", "admin", "promocion").forEach { value ->
            val upstream = CopyOnWriteArrayList<HttpRequest>()
            gatewayWith(upstream::add).upsertExamAccess(input(value))
            assertEquals(value, motivoWritten(upstream))
        }
    }

    @Test fun canonicalValuesAreTrimmedAndLowercased() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        gatewayWith(upstream::add).upsertExamAccess(input(" PREMIUM "))
        assertEquals("premium", motivoWritten(upstream))
    }

    @Test fun anUnknownTypeIsRejectedWithoutAnyUpstreamCall() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        val error = assertFailsWith<BackendDomainException> {
            gatewayWith(upstream::add).upsertExamAccess(input("inventado"))
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

    private fun Application.accessModule(upstream: MutableList<HttpRequest>) {
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
        routing { adminV1Routes(gatewayWith(upstream::add)) }
    }
}
