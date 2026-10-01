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
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MASTER7-API-001: un id bien formado pero inexistente (el upstream devuelve 0 filas) es 404 NOT_FOUND y NO emite el
 * evento falso SUPABASE_REQUEST_FAILED; un fallo real del upstream sigue siendo 502 UPSTREAM_ERROR.
 */
class Master7AdminNotFoundTest {
    private val admin = "7d7d7d7d-0000-4000-8000-000000000001"
    private val id = "7d7d7d7d-0000-4000-8000-000000000002"

    private fun gateway(upstreamStatus: Int, body: String): AdminV1Gateway =
        AdminV1Gateway.forTesting(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = V1StubHttpClient({ }) { _ -> V1StubResponse(upstreamStatus, body) },
        )

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

    private class Case(val name: String, val call: suspend ApplicationTestBuilder.() -> HttpResponse)

    private val cases = listOf(
        Case("reporte GET") { client.get("/api/v1/admin/reportes/$id") { header(HttpHeaders.Authorization, "Bearer $admin") } },
        Case("reporte resolver") { post("/api/v1/admin/reportes/$id/resolver", """{"accion":"descartar"}""") },
        Case("feedback resolver") { post("/api/v1/admin/feedback/preguntas/$id/resolver", """{"estado":"resuelto"}""") },
        Case("etiqueta PATCH") { patchJson("/api/v1/admin/etiquetas/$id", """{"nombre":"x"}""") },
        Case("comunidad PATCH") { patchJson("/api/v1/admin/comunidades-autonomas/$id", """{"nombre":"x"}""") },
    )

    private suspend fun ApplicationTestBuilder.post(path: String, json: String): HttpResponse =
        client.post(path) {
            header(HttpHeaders.Authorization, "Bearer $admin")
            contentType(ContentType.Application.Json)
            setBody(json)
        }

    private suspend fun ApplicationTestBuilder.patchJson(path: String, json: String): HttpResponse =
        client.patch(path) {
            header(HttpHeaders.Authorization, "Bearer $admin")
            contentType(ContentType.Application.Json)
            setBody(json)
        }

    private fun code(body: String) = Json.parseToJsonElement(body).jsonObject["code"]?.jsonPrimitive?.content

    private fun run(status: Int, body: String, assertCase: (String, HttpResponse, List<String>) -> Unit) {
        val logger = LoggerFactory.getLogger("OC-KTOR-OBSERVABILITY") as LogbackLogger
        for (case in cases) {
            val appender = ListAppender<ILoggingEvent>().also { it.start(); logger.addAppender(it) }
            try {
                testApplication {
                    application { adminModule(gateway(status, body)) }
                    val response = case.call(this)
                    assertCase(case.name, response, appender.list.map { it.formattedMessage })
                }
            } finally {
                logger.detachAppender(appender)
            }
        }
    }

    @Test
    fun anUnknownIdIs404NotFoundAndDoesNotLogAFalseUpstreamFailure() = run(200, "[]") { name, response, events ->
        val text = kotlinx.coroutines.runBlocking { response.bodyAsText() }
        assertEquals(HttpStatusCode.NotFound, response.status, "$name: $text")
        assertEquals("NOT_FOUND", code(text), "$name: $text")
        assertTrue(events.none { "event=SUPABASE_REQUEST_FAILED" in it }, "$name: $events")
    }

    @Test
    fun aRealUpstreamFailureIsStill502UpstreamError() = run(500, """{"message":"boom"}""") { name, response, events ->
        val text = kotlinx.coroutines.runBlocking { response.bodyAsText() }
        assertEquals(HttpStatusCode.BadGateway, response.status, "$name: $text")
        assertEquals("UPSTREAM_ERROR", code(text), "$name: $text")
        assertTrue(events.any { "event=SUPABASE_REQUEST_FAILED" in it }, "$name: $events")
    }
}
