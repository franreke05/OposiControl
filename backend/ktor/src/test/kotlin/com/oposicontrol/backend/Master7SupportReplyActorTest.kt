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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MASTER7-API-006: la respuesta de soporte se atribuye SOLO al actor verificado (sub del JWT), nunca al `assignee`
 * de texto libre ni al perfil mas reciente; el token estatico legacy (actor nulo) se rechaza con 403 sin tocar Supabase.
 */
class Master7SupportReplyActorTest {
    private val admin = "7a7a7a7a-0000-4000-8000-000000000001"
    private val victim = "7a7a7a7a-0000-4000-8000-000000000002"
    private val newest = "7a7a7a7a-0000-4000-8000-000000000003"
    private val conversation = "7b7b7b7b-0000-4000-8000-000000000001"

    private class Sent(val method: String, val uri: String, val body: String)

    private inner class Stub(val auditFails: Boolean = false) {
        val sent = CopyOnWriteArrayList<Sent>()
        fun inserts() = sent.filter { it.method == "POST" && "/rest/v1/mensajes_soporte" in it.uri }
        fun audits() = sent.filter { it.method == "POST" && "/rest/v1/auditoria_log" in it.uri }

        fun respond(method: String, uri: String): V1StubResponse = when {
            auditFails && method == "POST" && "/rest/v1/auditoria_log" in uri -> V1StubResponse(500, """{"message":"down"}""")
            // Orden creado_en.desc: `newest` es el primero (la antigua caida `firstOrNull`), `victim` coincide con el assignee.
            "/rest/v1/perfiles_usuario" in uri -> V1StubResponse(
                200,
                """[{"usuario_id":"$newest","username":"nuevo","nombre_mostrar":"Nuevo","es_invitado":false,"onboarding_completado":true,"estado_cuenta":"activa"},""" +
                    """{"usuario_id":"$victim","username":"victima","nombre_mostrar":"Victima","es_invitado":false,"onboarding_completado":true,"estado_cuenta":"activa"},""" +
                    """{"usuario_id":"$admin","username":"admin_real","nombre_mostrar":"Admin Real","es_invitado":false,"onboarding_completado":true,"estado_cuenta":"activa"}]""",
            )
            method == "POST" && "/rest/v1/mensajes_soporte" in uri -> {
                val row = Json.parseToJsonElement(sent.last().body).jsonArray.first().jsonObject
                V1StubResponse(
                    201,
                    """[{"id":"7c7c7c7c-0000-4000-8000-000000000001","conversacion_id":"$conversation",""" +
                        """"remitente_id":"${row["remitente_id"]!!.jsonPrimitive.content}","cuerpo":"hola",""" +
                        """"es_staff":${row["es_staff"]!!.jsonPrimitive.content},"creado_en":"2026-10-01T00:00:00Z"}]""",
                )
            }
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

    private suspend fun ApplicationTestBuilder.reply(token: String, body: String): HttpResponse =
        client.post("/api/v1/admin/soporte/conversaciones/$conversation/mensajes") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun insertedSender(stub: Stub): String =
        Json.parseToJsonElement(stub.inserts().single().body).jsonArray.single().jsonObject["remitente_id"]!!.jsonPrimitive.content

    @Test
    fun anAssigneeNamingAnotherUserCannotSpoofTheAuthor() = testApplication {
        val stub = Stub()
        application { adminModule(gateway(stub)) }
        val response = reply(admin, """{"assignee":"victima","text":"hola","internal":false}""")
        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(admin, insertedSender(stub))
        val dto = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Admin Real", dto["authorName"]!!.jsonPrimitive.content)
    }

    @Test
    fun anAssigneeGivenAsAnotherUuidIsIgnored() = testApplication {
        val stub = Stub()
        application { adminModule(gateway(stub)) }
        val response = reply(admin, """{"assignee":"$victim","text":"hola","internal":false}""")
        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(admin, insertedSender(stub))
    }

    @Test
    fun aBlankAssigneeNeverFallsBackToTheNewestProfile() = testApplication {
        val stub = Stub()
        application { adminModule(gateway(stub)) }
        val response = reply(admin, """{"assignee":"","text":"hola","internal":false}""")
        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(admin, insertedSender(stub))
    }

    @Test
    fun anUnknownAssigneeDoesNotFailTheReplyOfARealActor() = testApplication {
        val stub = Stub()
        application { adminModule(gateway(stub)) }
        val response = reply(admin, """{"assignee":"nadie-con-este-nombre","text":"hola","internal":false}""")
        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(admin, insertedSender(stub))
    }

    @Test
    fun theStaticTokenActorIsRejectedWith403BeforeAnyUpstreamCall() = testApplication {
        val stub = Stub()
        application { adminModule(gateway(stub)) }
        val response = reply("oposicontrol-admin", """{"assignee":"victima","text":"hola","internal":false}""")
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("FORBIDDEN", Json.parseToJsonElement(response.bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content)
        assertTrue(stub.sent.isEmpty(), "ninguna peticion a Supabase con actor nulo")
    }

    /**
     * CAMBIO DELIBERADO (MASTER7-API-006): el test anterior (`theReplyIsStoredAsStaffRegardlessOfTheInternalFlag`)
     * codificaba el defecto: una nota pensada como interna se guardaba como respuesta de staff VISIBLE para el usuario.
     * Sin columna de nota interna (PRODUCT_BLOCKED) la unica opcion segura es rechazarla: 422 antes de cualquier I/O.
     */
    @Test
    fun anInternalNoteIsRejectedWith422BeforeAnyUpstreamCall() = testApplication {
        val stub = Stub()
        application { adminModule(gateway(stub)) }
        val response = reply(admin, """{"assignee":"victima","text":"hola","internal":true}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertEquals(
            "SUPPORT_INTERNAL_NOTE_UNSUPPORTED",
            Json.parseToJsonElement(response.bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content,
        )
        assertTrue(stub.sent.isEmpty(), "ninguna peticion a Supabase (ni lectura, ni insert, ni auditoria): ${stub.sent.map { it.uri }}")
    }

    @Test
    fun aPublicReplyIsPersistedAsStaffAndReportedAsNotInternal() = testApplication {
        val stub = Stub()
        application { adminModule(gateway(stub)) }
        val response = reply(admin, """{"assignee":"","text":"hola","internal":false}""")
        assertEquals(HttpStatusCode.Created, response.status)
        val row = Json.parseToJsonElement(stub.inserts().single().body).jsonArray.single().jsonObject
        assertEquals("true", row["es_staff"]!!.jsonPrimitive.content)
        assertEquals(admin, row["remitente_id"]!!.jsonPrimitive.content)
        val dto = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("false", dto["internal"]!!.jsonPrimitive.content)
    }

    @Test
    fun aReplyWritesABoundedAuditRowAttributedToTheVerifiedActor() = testApplication {
        val stub = Stub()
        application { adminModule(gateway(stub)) }
        val response = reply(admin, """{"assignee":"victima","text":"cuerpo-secreto-del-mensaje","internal":false}""")
        assertEquals(HttpStatusCode.Created, response.status)
        val audit = stub.audits().single()
        val row = Json.parseToJsonElement(audit.body).jsonArray.single().jsonObject
        assertEquals("soporte_respuesta_enviada", row["accion"]!!.jsonPrimitive.content)
        assertEquals("mensajes_soporte", row["tabla_afectada"]!!.jsonPrimitive.content)
        assertEquals("7c7c7c7c-0000-4000-8000-000000000001", row["registro_id"]!!.jsonPrimitive.content)
        assertEquals(admin, row["usuario_id"]!!.jsonPrimitive.content)
        val despues = row["despues"]!!.jsonObject
        assertEquals(setOf("conversacion_id", "remitente_id"), despues.keys)
        assertEquals(conversation, despues["conversacion_id"]!!.jsonPrimitive.content)
        assertEquals(admin, despues["remitente_id"]!!.jsonPrimitive.content)
        assertTrue("cuerpo-secreto-del-mensaje" !in audit.body, "la auditoria nunca lleva el cuerpo del mensaje")
        // Orden: el insert va antes que la fila de auditoria (la fila referencia el id del mensaje).
        assertTrue(stub.sent.indexOf(stub.inserts().single()) < stub.sent.indexOf(audit))
    }

    /**
     * required = true: si la fila de auditoria no se puede escribir la respuesta FALLA (500 INTERNAL), nunca 201.
     * RESIDUAL documentado: el mensaje ya insertado permanece (no atomico sin RPC/migracion).
     */
    @Test
    fun aFailedAuditWriteFailsTheReply() = testApplication {
        val stub = Stub(auditFails = true)
        application { adminModule(gateway(stub)) }
        val response = reply(admin, """{"assignee":"","text":"hola","internal":false}""")
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("INTERNAL", Json.parseToJsonElement(response.bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content)
        assertEquals(1, stub.audits().size)
        assertEquals(1, stub.inserts().size, "residual: el insert ya ocurrio antes de la auditoria fallida")
    }
}
