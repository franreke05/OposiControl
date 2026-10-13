package com.oposicontrol.backend

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
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * LOOP 5 (KTOR_REST): MASTER5-API-001 / API-003 / API-005 / DB-001 / OPENAPI-002 / SEC-003.
 */
class Master5KtorRestLoop5Test {
    private val admin = "5a5a5a5a-0000-4000-8000-000000000001"
    private val user = "5a5a5a5a-0000-4000-8000-000000000003"
    private val tx = "cccccccc-0000-4000-8000-000000000001"

    private class Sent(val method: String, val uri: String, val body: String)

    private class Stub {
        val sent = CopyOnWriteArrayList<Sent>()
        var rolesRpc = V1StubResponse(200, "[]")
        fun rpcCalls(name: String) = sent.count { "/rest/v1/rpc/$name" in it.uri }
        fun audits() = sent.filter { it.method == "POST" && "/rest/v1/auditoria_log" in it.uri }
            .map { Json.parseToJsonElement(it.body).jsonArray.single().jsonObject }
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
            }) { uri ->
                when {
                    "/rest/v1/roles_app" in uri -> V1StubResponse(
                        200,
                        """[{"id":"aaaaaaaa-0000-4000-8000-00000000000a","codigo":"superadmin","nombre":"S"},""" +
                            """{"id":"aaaaaaaa-0000-4000-8000-00000000000c","codigo":"usuario","nombre":"U"}]""",
                    )
                    "/rest/v1/roles_usuario" in uri -> V1StubResponse(200, "[]")
                    "/rest/v1/rpc/admin_sync_roles_audited" in uri -> stub.rolesRpc
                    "/rest/v1/rpc/admin_grant_tickets" in uri -> V1StubResponse(200, """[{"transaction_id":"$tx","saldo_resultante":15}]""")
                    // MASTER6-IDEM-001 / MASTER6-API-007: saldo actual tras la concesion y el asiento de ESTA concesion.
                    "/rest/v1/carteras_usuario" in uri -> V1StubResponse(200, """[{"usuario_id":"$user","saldo_tickets":15}]""")
                    "/rest/v1/transacciones_cartera?select=*&id=eq.$tx" in uri -> V1StubResponse(
                        200,
                        """[{"id":"$tx","usuario_id":"$user","tipo":"ajuste","cantidad":10000,"descripcion":"m"}]""",
                    )
                    "/rest/v1/perfiles_usuario" in uri -> V1StubResponse(
                        200,
                        """[{"usuario_id":"$user","username":"ada_l","nombre_mostrar":"Ada","es_invitado":false,"onboarding_completado":true,""" +
                            """"estado_cuenta":"activa","creado_en":"2026-01-02T03:04:05+00:00","actualizado_en":"2026-09-30T10:00:00+00:00"}]""",
                    )
                    lastMethod == "POST" && "/rest/v1/auditoria_log" in uri -> V1StubResponse(201, "")
                    else -> V1StubResponse(200, "[]")
                }
            },
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

    // ------------------------------------------------------------------ MASTER5-API-001

    @Test
    fun legacyUserTicketRouteRejectsOutOfBoundsGrantsBeforeTheRpc() = testApplication {
        val stub = Stub()
        application { adminModule(gateway(stub)) }
        val path = "/api/v1/admin/usuarios/$user/tickets"
        val zero = postJson(path, """{"cantidad":0,"motivo":"m","idempotencyKey":"k"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, zero.status)
        assertEquals("WALLET_AMOUNT_INVALID", zero.code())
        val longMotivo = "x".repeat(AdminGrantTicketsRequestDto.MAX_MOTIVO_LENGTH + 1)
        assertEquals(HttpStatusCode.UnprocessableEntity, postJson(path, """{"cantidad":5,"motivo":"$longMotivo","idempotencyKey":"k"}""").status)
        assertEquals(HttpStatusCode.UnprocessableEntity, postJson(path, """{"cantidad":5,"motivo":"   ","idempotencyKey":"k"}""").status)
        val longKey = "k".repeat(AdminGrantTicketsRequestDto.MAX_IDEMPOTENCY_KEY_LENGTH + 1)
        assertEquals(HttpStatusCode.UnprocessableEntity, postJson(path, """{"cantidad":5,"motivo":"m","idempotencyKey":"$longKey"}""").status)
        assertEquals(0, stub.rpcCalls("admin_grant_tickets"), "ninguna peticion fuera de cota llego al RPC")
    }

    @Test
    fun legacyUserTicketRouteStillAcceptsTheBoundaries() = testApplication {
        val stub = Stub()
        application { adminModule(gateway(stub)) }
        val path = "/api/v1/admin/usuarios/$user/tickets"
        // Sin tope de cantidad en un ajuste manual: una cifra grande llega al RPC igual que una pequeña.
        val atCap = 2_000_000_000
        val okKey = "k".repeat(AdminGrantTicketsRequestDto.MAX_IDEMPOTENCY_KEY_LENGTH)
        val okMotivo = "m".repeat(AdminGrantTicketsRequestDto.MAX_MOTIVO_LENGTH)
        assertEquals(HttpStatusCode.OK, postJson(path, """{"cantidad":$atCap,"motivo":"$okMotivo","idempotencyKey":"$okKey"}""").status)
        assertEquals(HttpStatusCode.OK, postJson(path, """{"cantidad":-$atCap,"motivo":"m","idempotencyKey":"k2"}""").status)
        assertEquals(2, stub.rpcCalls("admin_grant_tickets"))
    }

    @Test
    fun legacyTransactionsRouteRejectsOutOfBoundsAdjustmentsBeforeTheRpc() = testApplication {
        val stub = Stub()
        application { adminModule(gateway(stub)) }
        val path = "/api/v1/admin/tickets/$user/transacciones"
        val zero = postJson(path, """{"amount":0,"reason":"m","idempotencyKey":"k"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, zero.status, zero.bodyAsText())
        val longReason = "x".repeat(AdminGrantTicketsRequestDto.MAX_MOTIVO_LENGTH + 1)
        assertEquals(HttpStatusCode.UnprocessableEntity, postJson(path, """{"amount":5,"reason":"$longReason","idempotencyKey":"k"}""").status)
        val longKey = "k".repeat(AdminGrantTicketsRequestDto.MAX_IDEMPOTENCY_KEY_LENGTH + 1)
        assertEquals(HttpStatusCode.UnprocessableEntity, postJson(path, """{"amount":5,"reason":"m","idempotencyKey":"$longKey"}""").status)
        assertEquals(0, stub.rpcCalls("admin_grant_tickets"))
        assertEquals(HttpStatusCode.Created, postJson(path, """{"amount":2000000000,"reason":"m","idempotencyKey":"k"}""").status)
        assertEquals(1, stub.rpcCalls("admin_grant_tickets"))
    }

    @Test
    fun canonicalGrantRouteRejectsAnOversizedMotivoAndKeyToo() = testApplication {
        val stub = Stub()
        application { adminModule(gateway(stub)) }
        val path = "/api/v1/admin/tickets/conceder"
        val longMotivo = "x".repeat(AdminGrantTicketsRequestDto.MAX_MOTIVO_LENGTH + 1)
        assertEquals(
            HttpStatusCode.UnprocessableEntity,
            postJson(path, """{"usuarioId":"$user","cantidad":5,"motivo":"$longMotivo","idempotencyKey":"k"}""").status,
        )
        val longKey = "k".repeat(AdminGrantTicketsRequestDto.MAX_IDEMPOTENCY_KEY_LENGTH + 1)
        assertEquals(
            HttpStatusCode.UnprocessableEntity,
            postJson(path, """{"usuarioId":"$user","cantidad":5,"motivo":"m","idempotencyKey":"$longKey"}""").status,
        )
        assertEquals(0, stub.rpcCalls("admin_grant_tickets"))
    }

    @Test
    fun aReportRewardAboveTheCapIsRejectedBeforeTheModerationActionIsWritten() {
        val stub = Stub()
        val g = gateway(stub)
        val failure = assertFailsWith<BackendDomainException> {
            g.resolverReporte("r-1", ResolverReporteBody(accion = "ocultar", ticketsReporter = 2_000_000_000), actorId = null)
        }
        assertEquals("WALLET_AMOUNT_INVALID", failure.errorCode)
        assertTrue(stub.sent.none { it.method == "POST" }, "ni accion de moderacion ni RPC: ${stub.sent.map { it.uri }}")
    }

    @Test
    fun postgresNumericOutOfRangeIsA422NotA500() {
        val mapped = mapSupabaseDomainError(400, """{"code":"22003","message":"integer out of range"}""")
        assertNotNull(mapped)
        assertEquals(HttpStatusCode.UnprocessableEntity, mapped.status)
        assertEquals("NUMERIC_VALUE_OUT_OF_RANGE", mapped.errorCode)
    }

    // ------------------------------------------------------------------ MASTER5-API-003 / OPENAPI-002

    @Test
    fun successorLinkIgnoresAQueryParameterThatShadowsThePathVariable() = testApplication {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val endpoint = LegacyEndpoint(
            "EP-998", "GET", "/legacy/cosas/{cosaId}", "/api/v1/app/cosas/{cosaId}",
            java.time.Instant.parse("2026-09-28T00:00:00Z"),
        )
        application {
            install(ContentNegotiation) { json() }
            routing { get("/legacy/cosas/{cosaId}") { call.respond(listOf("a")) }.legacyRoute(endpoint, registry) }
        }
        assertEquals(
            "</api/v1/app/cosas/real>; rel=\"successor-version\"",
            client.get("/legacy/cosas/real?cosaId=evil").headers[HttpHeaders.Link],
        )
    }

    @Test
    fun aQueryParameterCannotSupplyAVariableTheLegacyPathDoesNotCarry() = testApplication {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val endpoint = LegacyEndpoint(
            "EP-997", "GET", "/legacy/sin-param", "/api/v1/app/cosas/{cosaId}",
            java.time.Instant.parse("2026-09-28T00:00:00Z"),
        )
        application {
            install(ContentNegotiation) { json() }
            routing { get("/legacy/sin-param") { call.respond(listOf("a")) }.legacyRoute(endpoint, registry) }
        }
        val response = client.get("/legacy/sin-param?cosaId=evil")
        assertNotNull(response.headers[LegacyHeaders.DEPRECATION])
        assertNull(response.headers[HttpHeaders.Link], "la query no puede elegir el sucesor")
    }

    /** Sucesoras que realmente estan ruteadas (AdminV1Routes / rutas canonicas de perfil). */
    private val routedSuccessors = mapOf(
        "/api/v1/admin/dashboard/overview" to "get(\"/overview\")",
        "/api/v1/admin/moderacion/actions" to "post(\"/actions\")",
        "/api/v1/app/me" to "route(\"/me\")",
        "/api/v1/app/me/onboarding" to "post(\"/onboarding\")",
        // GATE SLICE 3: sucesoras de las dos rutas legacy de intento retiradas (D2-03 y D2-09, AttemptRoutes.kt).
        "/api/v1/app/examenes/{id}/intentos" to "post(\"/examenes/{examId}/intentos\")",
        "/api/v1/app/intentos/{id}/respuestas/{preguntaId}" to "put(\"/respuestas/{preguntaId}\")",
        // FA11: sucesoras de noticias y tipos de noticia (D4-01..D4-11, NewsRoutes.kt).
        "/api/v1/admin/noticias" to "route(\"/noticias\")",
        "/api/v1/admin/noticias/{noticiaId}" to "route(\"/{noticiaId}\")",
        "/api/v1/admin/noticias/assets" to "routeTemplate = \"/api/v1/admin/noticias/assets\"",
        "/api/v1/admin/tipos-noticia" to "route(\"/tipos-noticia\")",
        "/api/v1/admin/tipos-noticia/{codigo}" to "put(\"/{codigo}\")",
    )

    @Test
    fun everyAdvertisedSuccessorPathIsARoutedPath() {
        val all = LegacyEndpoints.SLICE_0 + LegacyEndpoints.SLICE_1 + LegacyEndpoints.SLICE_1_APP
        val advertised = all.mapNotNull { it.successorPath }.toSet()
        assertEquals(emptySet<String>(), advertised - routedSuccessors.keys, "sucesora anunciada sin ruta conocida")
        // Las sucesoras conocidas siguen montadas en el codigo de rutas (la marca es un literal de la ruta).
        val sources = File("src/main/kotlin/com/oposicontrol/backend").walkTopDown().filter { it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        routedSuccessors.forEach { (successor, marker) -> assertTrue(marker in sources, "sucesora $successor: marca $marker no encontrada") }
        // FA11: las 7 de noticias/tipos-noticia YA tienen sucesora ruteada: ninguna EP legacy queda sin sucesora.
        assertEquals(emptySet<String>(), all.filter { it.successorPath == null && it.endpointId.startsWith("EP-") }.map { it.endpointId }.toSet())
        assertTrue(advertised.any { "noticias" in it })
    }

    // ------------------------------------------------------------------ MASTER5-API-005

    private suspend fun ApplicationTestBuilder.patchName(name: String): HttpResponse =
        client.patch("/api/v1/admin/usuarios/$user") {
            header(HttpHeaders.Authorization, "Bearer $admin")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("nombreMostrar", JsonPrimitive(name)) }.toString())
        }

    private fun HttpResponse.issueOf(body: String) = body.substringAfter("\"issue\":\"").substringBefore('"')

    @Test
    fun displayNameLengthCountsCodePointsNotUtf16Units() = testApplication {
        val stub = Stub()
        application { adminModule(gateway(stub)) }
        val emoji = "😀"
        // 60 emojis = 60 puntos de codigo (120 unidades UTF-16): dentro de maxLength 100 del contrato.
        val ok = patchName(emoji.repeat(60))
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        // Exactamente 100 puntos de codigo (200 unidades): aceptado.
        assertEquals(HttpStatusCode.OK, patchName(emoji.repeat(100)).status)
        // 101 puntos de codigo: fuera.
        val tooLong = patchName(emoji.repeat(101))
        val body = tooLong.bodyAsText()
        assertEquals(HttpStatusCode.BadRequest, tooLong.status, body)
        assertEquals("OUT_OF_RANGE", tooLong.issueOf(body))
    }

    @Test
    fun displayNameRejectsControlCharacters() = testApplication {
        val stub = Stub()
        application { adminModule(gateway(stub)) }
        for (bad in listOf("Ada\nL", "Ada\u0000L", "Ada\tL", "Ada\u007fL", "Ada\u0085L")) {
            val r = patchName(bad)
            val body = r.bodyAsText()
            assertEquals(HttpStatusCode.BadRequest, r.status, "rechaza ${bad.map { it.code }}: $body")
            assertEquals("INVALID_FORMAT", r.issueOf(body))
        }
        assertTrue(stub.sent.none { it.method == "PATCH" }, "ninguna escritura")
        assertEquals(HttpStatusCode.OK, patchName("Ada Lñ").status)
    }

    // ------------------------------------------------------------------ MASTER5-DB-001

    @Test
    fun aMissingSyncRolesRpcIsAuditedAsRpcAusenteNotRechazada() = testApplication {
        val stub = Stub().apply {
            rolesRpc = V1StubResponse(
                404,
                """{"code":"PGRST202","details":"Searched for the function","hint":null,"message":"Could not find the function public.admin_sync_roles_audited"}""",
            )
        }
        application { adminModule(gateway(stub)) }
        val response = postJson("/api/v1/admin/usuarios/$user/roles", """{"roleIds":["aaaaaaaa-0000-4000-8000-00000000000c"]}""")
        assertTrue(response.status.value >= 400, "la peticion falla: ${response.status}")
        val rows = stub.audits()
        assertEquals(listOf(ACTION_ROLES_SYNC_INTENT, ACTION_ROLES_SYNC_FAILED), rows.map { it["accion"]!!.jsonPrimitive.content })
        assertEquals("rpc_ausente", rows.last()["despues"]!!.jsonObject["resultado"]!!.jsonPrimitive.content)
    }

    @Test
    fun aDomainRejectionOfSyncRolesIsStillRechazada() = testApplication {
        val stub = Stub().apply {
            rolesRpc = V1StubResponse(400, """{"code":"P0001","message":"ROLE_SYNC_EMPTY_ROLES"}""")
        }
        application { adminModule(gateway(stub)) }
        postJson("/api/v1/admin/usuarios/$user/roles", """{"roleIds":["aaaaaaaa-0000-4000-8000-00000000000c"]}""")
        val last = stub.audits().last()["despues"]!!.jsonObject["resultado"]!!.jsonPrimitive.content
        assertEquals("rechazada", last)
    }

    // ------------------------------------------------------------------ MASTER5-SEC-003

    @Test
    fun theRuntimeImageKeepsApplicationCodeRootOwned() {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "ops/deploy/Dockerfile.backend") }.firstOrNull { it.isFile }
        assertNotNull(file, "Dockerfile.backend")
        val instructions = file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue(instructions.none { it.startsWith("RUN") && "chown" in it && "/app" in it }, "el codigo de /app no es del usuario app")
        assertTrue(instructions.any { it.startsWith("RUN") && "chmod" in it && "go-w" in it && "/app" in it }, "/app no escribible por grupo/otros")
        assertEquals("USER app", instructions.last { it.startsWith("USER") }, "el proceso corre sin privilegios")
        assertTrue(instructions.indexOfLast { it.startsWith("USER") } > instructions.indexOfFirst { it.startsWith("COPY backend") })
    }
}
