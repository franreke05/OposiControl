package com.oposicontrol.backend

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SLICE 0 · sobre canonico de error. Monta el `installBackendStatusPages()` de
 * PRODUCCION y recorre TODAS sus ramas: cada non-2xx es `{code, message,
 * requestId, details?}` + `error` legacy, `requestId` == cabecera `X-Request-Id`,
 * y ningun 5xx lleva texto del upstream.
 */
class CanonicalErrorEnvelopeTest {

    private val upstreamLeak = "x-upstream-leak-c"

    private data class Case(
        val path: String,
        val status: HttpStatusCode,
        val code: String,
        val legacyError: String,
    )

    private val branches = listOf(
        Case("/unauthorized", HttpStatusCode.Unauthorized, "UNAUTHENTICATED", "unauthenticated"),
        Case("/forbidden", HttpStatusCode.Forbidden, "FORBIDDEN", "forbidden"),
        Case("/user-not-found", HttpStatusCode.NotFound, "NOT_FOUND", "not_found"),
        Case("/domain", HttpStatusCode.Conflict, "EXAM_REORDER_SET_MISMATCH", "exam_reorder_set_mismatch"),
        Case("/canonical", HttpStatusCode.Conflict, "ATTEMPT_ALREADY_FINISHED", "attempt_already_finished"),
        Case("/transient", HttpStatusCode.ServiceUnavailable, "DB_BUSY", "database_lock_timeout"),
        Case("/malformed-body", HttpStatusCode.BadRequest, "MALFORMED_BODY", "malformed_body"),
        Case("/unsupported-media", HttpStatusCode.UnsupportedMediaType, "UNSUPPORTED_MEDIA_TYPE", "unsupported_media_type"),
        Case("/contract", HttpStatusCode.BadGateway, "UPSTREAM_CONTRACT_ERROR", "upstream_contract_error"),
        Case("/illegal-argument", HttpStatusCode.BadRequest, "VALIDATION", "validation"),
        Case("/upstream", HttpStatusCode.BadGateway, "UPSTREAM_ERROR", "upstream_error"),
        Case("/upstream-timeout", HttpStatusCode.GatewayTimeout, "UPSTREAM_TIMEOUT", "upstream_timeout"),
        Case("/boom", HttpStatusCode.InternalServerError, "INTERNAL", "internal"),
        Case("/direct-legacy", HttpStatusCode.NotFound, "NOTICIA_NO_ENCONTRADA", "noticia_no_encontrada"),
    )

    @Test
    fun everyStatusPagesBranchReturnsTheEnvelopeWithRequestIdEqualToTheHeader() = envelopeApp {
        branches.forEach { case ->
            val requestId = "req-${case.path.trim('/')}-0001"
            val response = call(case.path, requestId)
            assertEquals(case.status, response.status, "${case.path}: ${response.bodyAsText()}")
            assertTrue(response.bodyAsText().isNotBlank(), "${case.path} responde sin cuerpo")
            val body = envelope(response)
            assertEquals(case.code, body.string("code"), case.path)
            assertTrue(body.string("code").isUpperSnakeCode(), case.path)
            assertTrue(body.string("message").isNotBlank(), case.path)
            assertEquals(requestId, body.string("requestId"), case.path)
            assertEquals(requestId, response.headers["X-Request-Id"], case.path)
            assertEquals(case.legacyError, body.string("error"), "${case.path}: el campo legacy `error` sigue presente")
        }
    }

    @Test
    fun serverErrorsCarryNoUpstreamTextAndEndWithTheReference() = envelopeApp {
        branches.filter { it.status.value >= 500 && it.code != "DB_BUSY" }.forEach { case ->
            val requestId = "ref-${case.path.trim('/')}-0001"
            val response = call(case.path, requestId)
            val raw = response.bodyAsText()
            listOf(upstreamLeak, "rest/v1", "Exception", "kotlinx", "hint").forEach { leak ->
                assertFalse(raw.contains(leak), "${case.path} filtra '$leak': $raw")
            }
            assertTrue(envelope(response).string("message").endsWith("Referencia: $requestId"), raw)
        }
    }

    @Test
    fun unauthorizedCarriesWwwAuthenticateBearerAndNeverTheVerifierMessage() = envelopeApp {
        val response = call("/unauthorized", "req-unauth-0001")
        assertEquals("Bearer", response.headers[HttpHeaders.WWWAuthenticate])
        assertFalse(response.bodyAsText().contains(upstreamLeak))
    }

    @Test
    fun transientDatabaseRejectionIsDbBusyWithRetryAfter() = envelopeApp {
        val response = call("/transient", "req-transient-0001")
        assertEquals("2", response.headers[HttpHeaders.RetryAfter])
    }

    @Test
    fun detailsAreTypedAndOnlyPresentWhenGiven() = envelopeApp {
        val validation = envelope(call("/validation-details", "req-details-0001"))
        assertEquals("VALIDATION", validation.string("code"))
        val issue = validation["details"]!!.jsonObject["issues"]!!.jsonArray.single().jsonObject
        assertEquals("limit", issue["field"]!!.jsonPrimitive.content)
        assertEquals("OUT_OF_RANGE", issue["issue"]!!.jsonPrimitive.content)
        assertEquals(setOf("issues"), validation["details"]!!.jsonObject.keys)

        assertNull(envelope(call("/forbidden", "req-details-0002"))["details"])
    }

    @Test
    fun aDirectLegacyErrorDtoGetsTheRequestIdAndACanonicalCode() = envelopeApp {
        val response = call("/direct-bad-request", "req-direct-0001")
        val body = envelope(response)
        assertEquals("VALIDATION", body.string("code"))
        assertEquals("bad_request", body.string("error"))
        assertEquals("req-direct-0001", body.string("requestId"))
    }

    @Test
    fun withoutAnIncomingIdTheGeneratedOneIsStillEqualInBodyAndHeader() = envelopeApp {
        val response = client.get("/boom")
        val header = assertNotNull(response.headers["X-Request-Id"])
        assertEquals(header, envelope(response).string("requestId"))
    }

    @Test
    fun legacyErrorCodesMapToTheFrozenCanonicalCodes() {
        assertEquals("UNAUTHENTICATED", canonicalCodeForLegacyError("unauthorized"))
        assertEquals("FORBIDDEN", canonicalCodeForLegacyError("forbidden"))
        assertEquals("NOT_FOUND", canonicalCodeForLegacyError("not_found"))
        assertEquals("VALIDATION", canonicalCodeForLegacyError("bad_request"))
        assertEquals("UPSTREAM_CONTRACT_ERROR", canonicalCodeForLegacyError("backend_contract_error"))
        assertEquals("UPSTREAM_ERROR", canonicalCodeForLegacyError("backend_supabase_error"))
        assertEquals("PDF_UNPROCESSABLE", canonicalCodeForLegacyError("pdf_unprocessable"))
    }

    @Test
    fun theCatalogIsUpperSnakeAndNeverUsesRetiredSynonyms() {
        val codes = CanonicalErrorCode.entries.map { it.name }
        assertTrue(codes.all { it.isUpperSnakeCode() })
        assertTrue(codes.none { it in RETIRED_ERROR_CODES }, "retirados: ${codes.filter { it in RETIRED_ERROR_CODES }}")
        assertTrue(supabaseDomainErrorCodes().none { it in RETIRED_ERROR_CODES })
        assertEquals(HttpStatusCode.Conflict, CanonicalErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS.status)
        assertEquals(HttpStatusCode.UnprocessableEntity, CanonicalErrorCode.IDEMPOTENCY_KEY_REUSED.status)
        assertEquals(HttpStatusCode.GatewayTimeout, CanonicalErrorCode.UPSTREAM_TIMEOUT.status)
        // Los 14 codigos de intento congelados estan en el catalogo.
        listOf(
            "EXAM_NOT_FOUND", "EXAM_LOCKED", "SPECIALTY_TYPE_NOT_ALLOWED", "EXAM_EMPTY", "ATTEMPT_NOT_FOUND",
            "ATTEMPT_NOT_IN_PROGRESS", "ATTEMPT_EXPIRED", "ATTEMPT_ABANDONED", "ATTEMPT_ALREADY_FINISHED",
            "ATTEMPT_NOT_FINISHED", "ANSWER_LOCKED", "REVISION_CONFLICT", "QUESTION_NOT_IN_ATTEMPT",
            "OPTION_NOT_IN_QUESTION",
        ).forEach { assertTrue(it in codes, it) }
    }

    // -----------------------------------------------------------------

    private suspend fun ApplicationTestBuilder.call(path: String, requestId: String): HttpResponse = when (path) {
        "/malformed-body" -> client.post(path) {
            header("X-Request-Id", requestId)
            contentType(ContentType.Application.Json)
            setBody("""{"no_es":""")
        }
        "/unsupported-media" -> client.post(path) {
            header("X-Request-Id", requestId)
            contentType(ContentType.Text.Plain)
            setBody("hola")
        }
        else -> client.get(path) { header("X-Request-Id", requestId) }
    }

    private suspend fun envelope(response: HttpResponse): JsonObject =
        Json.parseToJsonElement(response.bodyAsText()).jsonObject

    private fun JsonObject.string(key: String): String = assertNotNull(this[key], "falta $key: $this").jsonPrimitive.content

    private fun envelopeApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            installRequestCorrelation()
            installBackendStatusPages()
            routing {
                get("/unauthorized") { throw AppUnauthorized("JWT invalido: $upstreamLeak") }
                get("/forbidden") { throw AppForbidden("No tienes permisos para esta accion.") }
                get("/user-not-found") { throw OposiBotsUserAdminNotFoundException("Usuario no encontrado.") }
                get("/domain") { throw domainError("EXAM_REORDER_SET_MISMATCH") }
                get("/canonical") { throw CanonicalErrorCode.ATTEMPT_ALREADY_FINISHED.exception() }
                get("/transient") {
                    throw checkNotNull(
                        mapSupabaseDomainError(503, """{"code":"55P03","message":"canceling statement $upstreamLeak"}"""),
                    )
                }
                post("/malformed-body") { call.respond(call.receive<StatusPagesProbe>()) }
                post("/unsupported-media") { call.respond(call.receive<StatusPagesProbe>()) }
                get("/contract") { call.respond(Json.decodeFromString<StatusPagesProbe>("""{"$upstreamLeak":1}""")) }
                get("/illegal-argument") { throw IllegalArgumentException("limit debe ser un numero") }
                get("/upstream") {
                    throw IllegalStateException("""Supabase GET /rest/v1/examenes -> 400: {"hint":"$upstreamLeak"}""")
                }
                get("/upstream-timeout") {
                    throw IllegalStateException("Supabase network error on GET /rest/v1/x: HttpTimeoutException: request timed out $upstreamLeak")
                }
                get("/boom") { throw UnsupportedOperationException(upstreamLeak) }
                get("/direct-legacy") {
                    call.respond(HttpStatusCode.NotFound, ErrorDto(error = "noticia_no_encontrada", message = "La noticia no existe."))
                }
                get("/direct-bad-request") {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "examenId es obligatorio"))
                }
                get("/validation-details") { throw validationError(ErrorIssue("limit", CanonicalIssue.OUT_OF_RANGE)) }
            }
        }
        block()
    }
}
