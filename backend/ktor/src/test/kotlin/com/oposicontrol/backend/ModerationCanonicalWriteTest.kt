package com.oposicontrol.backend

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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * RC-01 (MASTERF2-API-001 / MASTERF1-API-007 / MASTERF2-API-007): UN solo comando canonico de moderacion.
 *
 * - `POST /api/v1/admin/moderacion/actions` acepta EXACTAMENTE el cuerpo que envia el cliente OC (`actionType`,
 *   `targetUserId`, `reason`): 201 con la fila de auditoria firmada por el `sub` del JWT y el uuid objetivo.
 * - El adaptador legacy EP-144 (`POST /admin/moderation/actions`) usa el MISMO servicio: el objetivo debe ser un uuid
 *   (nada de buscar por nombre/email en `public.usuario`) y un `executor` falsificado NO cambia el actor.
 * - Tipos sin efecto (`suspension`, `ban`, ...) = 422, nunca un 201 sin aplicacion. El token estatico no firma: 403.
 *
 * Control negativo: con AdminGateway.kt/AdminV1Routes.kt de HEAD, el adaptador resuelve `executor` por texto libre en
 * `public.usuario` (y responde con el executor del cliente), `ban` es 201 y el token estatico escribe sin actor.
 */
class ModerationCanonicalWriteTest {
    private val actor = "6b6b6b6b-0000-4000-8000-000000000001"
    private val victim = "6b6b6b6b-0000-4000-8000-000000000002"
    private val target = "6b6b6b6b-0000-4000-8000-000000000003"

    private class Stub {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val legacySent = CopyOnWriteArrayList<String>()
        var noticeStatus = 201
    }

    private fun Application.module(stub: Stub) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        install(Authentication) { bearer("gateway-auth") { authenticate { UserIdPrincipal(it.token) } } }
        val v1 = AdminV1Gateway.forTesting(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = V1StubHttpClient({ stub.sent += it }) { uri ->
                when {
                    "/rest/v1/auditoria_log" in uri -> V1StubResponse(201, "")
                    "/rest/v1/avisos_usuario" in uri -> V1StubResponse(stub.noticeStatus, "")
                    else -> V1StubResponse(200, "[]")
                }
            },
        )
        // El gateway legacy contestaria con una fila de `public.usuario` cuyo nombre es el executor falsificado: si el
        // adaptador volviera a resolver por texto libre, se veria aqui.
        val legacy = SupabaseAdminGateway(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = V1StubHttpClient({ request -> stub.legacySent += "${request.method()} ${request.uri()}" }) {
                V1StubResponse(200, """[{"id":9,"email":"victima@example.test","nombre":"$victim"}]""")
            },
        )
        routing {
            authenticate("gateway-auth") {
                installAdminAuditActor()
                adminRoutes(legacy, v1)
                adminV1Routes(v1)
            }
        }
    }

    private suspend fun ApplicationTestBuilder.post(path: String, token: String, body: String): HttpResponse =
        client.post(path) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun Stub.auditRows(): List<JsonObject> = sent.filter { "/rest/v1/auditoria_log" in it.uri().toString() }
        .map { Json.parseToJsonElement(readRequestBody(it)).jsonArray.single().jsonObject }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content

    /** El cuerpo EXACTO que serializa el cliente OC (`WarnUserBody` con `@SerialName("targetUserId")`). */
    private fun ocClientBody(targetUserId: String, reason: String = "spam reiterado") =
        """{"actionType":"advertencia","targetUserId":"$targetUserId","reason":"$reason"}"""

    @Test
    fun theOcClientBodyRoundTripsThroughTheCanonicalRouteAndIsAuditedWithTheJwtActor() = testApplication {
        val stub = Stub()
        application { module(stub) }
        // El mismo cuerpo decodifica con la forma del servidor (contrato de campos).
        val decoded = Json.decodeFromString(WarnUserRequest.serializer(), ocClientBody(target))
        assertEquals(target, decoded.targetUserId)

        val response = post("/api/v1/admin/moderacion/actions", actor, ocClientBody(target))
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val row = stub.auditRows().single()
        assertEquals(actor, row.str("usuario_id"), "actor = sub del JWT")
        assertEquals(target, row.str("registro_id"), "objetivo = uuid validado")
        assertEquals("advertencia_usuario", row.str("accion"))
    }

    @Test
    fun aWarningIsAlsoStoredAsANoticeForTheUserAfterTheAuditRow() = testApplication {
        val stub = Stub()
        application { module(stub) }

        val response = post("/api/v1/admin/moderacion/actions", actor, ocClientBody(target, reason = "  Respeta las normas del foro.  "))

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val uris = stub.sent.map { it.uri().toString() }
        val notice = stub.sent.single { "/rest/v1/avisos_usuario" in it.uri().toString() }
        val row = Json.parseToJsonElement(readRequestBody(notice)).jsonArray.single().jsonObject
        assertEquals(target, row.str("usuario_id"))
        assertEquals("advertencia", row.str("tipo"))
        assertEquals("Respeta las normas del foro.", row.str("mensaje"), "es lo que leera el usuario: sin espacios sobrantes")
        assertEquals(actor, row.str("creado_por"))
        assertTrue(uris.indexOfFirst { "auditoria_log" in it } < uris.indexOfFirst { "avisos_usuario" in it }, "primero la auditoria")
    }

    @Test
    fun ifTheNoticeCannotBeStoredTheAdminIsToldInsteadOfSeeingItAsDone() = testApplication {
        val stub = Stub()
        stub.noticeStatus = 500
        application { module(stub) }

        val response = post("/api/v1/admin/moderacion/actions", actor, ocClientBody(target))

        assertTrue(response.status.value >= 500, "sin aviso guardado no hay 201: al usuario no le llegaria")
    }

    @Test
    fun theOldClientKeyTargetUserIsRejectedAndNothingIsWritten() = testApplication {
        val stub = Stub()
        application { module(stub) }
        val response = post("/api/v1/admin/moderacion/actions", actor, """{"actionType":"advertencia","targetUser":"$target","reason":"x"}""")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(stub.sent.isEmpty(), "sin escritura: ${stub.sent.map { it.uri() }}")
    }

    @Test
    fun typesWithoutEnforcementAre422OnBothRoutesAndNothingIsAudited() = testApplication {
        val stub = Stub()
        application { module(stub) }
        for (type in listOf("suspension", "ban")) {
            val r = post("/api/v1/admin/moderacion/actions", actor, """{"actionType":"$type","targetUserId":"$target","reason":"x"}""")
            assertEquals(HttpStatusCode.UnprocessableEntity, r.status, type)
            assertEquals("MODERATION_ACTION_NOT_ENFORCED", Json.parseToJsonElement(r.bodyAsText()).jsonObject.str("code"))
        }
        for (type in listOf("ban", "mute", "close_conversation", "internal_note")) {
            val r = post("/admin/moderation/actions", actor, """{"id":"","actionType":"$type","targetUser":"$target","executor":"","reason":"x","createdAt":""}""")
            assertEquals(HttpStatusCode.UnprocessableEntity, r.status, "EP-144 $type")
        }
        assertTrue(stub.auditRows().isEmpty())
    }

    @Test
    fun theStaticTokenCannotSignAModerationActionOnEitherRoute() = testApplication {
        val stub = Stub()
        application { module(stub) }
        assertEquals(HttpStatusCode.Forbidden, post("/api/v1/admin/moderacion/actions", "oposicontrol-admin", ocClientBody(target)).status)
        assertEquals(
            HttpStatusCode.Forbidden,
            post("/admin/moderation/actions", "oposicontrol-admin", """{"id":"","actionType":"warning","targetUser":"$target","executor":"$actor","reason":"x","createdAt":""}""").status,
        )
        assertTrue(stub.sent.isEmpty() && stub.legacySent.isEmpty(), "403 antes de cualquier I/O")
    }

    @Test
    fun ep144IsAThinAdapterAForgedExecutorCannotChangeTheActor() = testApplication {
        val stub = Stub()
        application { module(stub) }
        val response = post(
            "/admin/moderation/actions",
            actor,
            """{"id":"","actionType":"warning","targetUser":"$target","executor":"$victim","reason":"spam","createdAt":""}""",
        )
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        assertTrue(response.headers[LegacyHeaders.DEPRECATION] != null, "EP-144 sigue contada")
        val row = stub.auditRows().single()
        assertEquals(actor, row.str("usuario_id"), "el executor del cliente se ignora: actor = JWT")
        assertEquals(target, row.str("registro_id"))
        assertEquals("advertencia_usuario", row.str("accion"), "mismo servicio y vocabulario que la ruta canonica")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(actor, body.str("executor"), "la respuesta devuelve el actor real, no el falsificado")
        assertTrue(stub.legacySent.isEmpty(), "sin busqueda en public.usuario ni tabla legacy: ${stub.legacySent}")
    }

    @Test
    fun ep144RejectsAFreeTextTargetInsteadOfResolvingItByName() = testApplication {
        val stub = Stub()
        application { module(stub) }
        for (freeText in listOf("victima@example.test", "Ana Garcia", "9")) {
            val r = post("/admin/moderation/actions", actor, """{"id":"","actionType":"warning","targetUser":"$freeText","executor":"","reason":"x","createdAt":""}""")
            assertEquals(HttpStatusCode.BadRequest, r.status, freeText)
        }
        assertTrue(stub.sent.isEmpty() && stub.legacySent.isEmpty())
    }

    @Test
    fun theServiceRefusesAnActorThatIsNotTheAuditedRequestActor() {
        val stub = Stub()
        val gateway = AdminV1Gateway.forTesting(
            "https://stub.supabase.test", "stub-key", Json { ignoreUnknownKeys = true },
            V1StubHttpClient({ stub.sent += it }) { V1StubResponse(201, "") },
        )
        val command = ModerationCommand.validated(target, "advertencia", "x")
        runBlocking {
            withContext(AdminAuditActor.contextElement(actor)) {
                assertFailsWith<IllegalStateException> { gateway.applyModerationAction(victim, command) }
            }
        }
        assertTrue(stub.sent.isEmpty(), "un actor cambiado no escribe nada")
        runBlocking { withContext(AdminAuditActor.contextElement(actor)) { gateway.applyModerationAction(actor, command) } }
        // La fila de auditoria y, desde 2026-10-14, el aviso que se le deja al usuario.
        assertEquals(2, stub.sent.size)
    }
}
