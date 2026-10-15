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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * E2 (2026-10-15): la respuesta de soporte y la advertencia de moderacion se DUPLICABAN al reintentar (un 5xx tras
 * escribir, un doble clic, un reintento de red). Ahora aceptan `Idempotency-Key` OPCIONAL: con clave, la repeticion
 * devuelve la respuesta guardada sin volver a escribir; sin clave, el comportamiento de siempre (el panel actual no la
 * envia). Ademas, la auditoria de la respuesta de soporte va ANTES del insert: si no se puede auditar, no hay mensaje.
 */
class AdminWriteIdempotencyTest {
    private val admin = "7a7a7a7a-0000-4000-8000-000000000001"
    private val target = "7a7a7a7a-0000-4000-8000-000000000002"
    private val conversation = "7b7b7b7b-0000-4000-8000-000000000001"
    private val key = "5f5f5f5f-0000-4000-8000-000000000001"

    private class Sent(val method: String, val uri: String, val body: String)

    private class Stub(val auditFails: Boolean = false) {
        val sent = CopyOnWriteArrayList<Sent>()
        fun posts(table: String) = sent.filter { it.method == "POST" && "/rest/v1/$table" in it.uri }

        fun respond(method: String, uri: String): V1StubResponse = when {
            auditFails && method == "POST" && "/rest/v1/auditoria_log" in uri -> V1StubResponse(500, """{"message":"down"}""")
            method == "POST" && "/rest/v1/mensajes_soporte" in uri -> {
                val row = Json.parseToJsonElement(sent.last().body).jsonArray.first().jsonObject
                val id = row["id"]?.jsonPrimitive?.content ?: "7c7c7c7c-0000-4000-8000-000000000001"
                V1StubResponse(
                    201,
                    """[{"id":"$id","conversacion_id":"${row["conversacion_id"]!!.jsonPrimitive.content}",""" +
                        """"remitente_id":"${row["remitente_id"]!!.jsonPrimitive.content}","cuerpo":"hola","es_staff":true,""" +
                        """"creado_en":"2026-10-01T00:00:00Z"}]""",
                )
            }
            method == "POST" -> V1StubResponse(201, "")
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

    private fun Application.adminModule(g: AdminV1Gateway, store: IdempotencyStore? = InMemoryIdempotencyStore()) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        install(Authentication) { bearer("gateway-auth") { authenticate { UserIdPrincipal(it.token) } } }
        routing {
            authenticate("gateway-auth") {
                installAdminAuditActor()
                adminV1Routes(g, store)
            }
        }
    }

    private suspend fun ApplicationTestBuilder.postJson(path: String, body: String, idemKey: String?, token: String = admin): HttpResponse =
        client.post(path) {
            header(HttpHeaders.Authorization, "Bearer $token")
            if (idemKey != null) header(IdempotencyHeaders.KEY, idemKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.reply(idemKey: String?, text: String = "hola", token: String = admin) =
        postJson("/api/v1/admin/soporte/conversaciones/$conversation/mensajes", """{"assignee":"","text":"$text","internal":false}""", idemKey, token)

    private suspend fun ApplicationTestBuilder.warn(idemKey: String?, token: String = admin) =
        postJson("/api/v1/admin/moderacion/actions", """{"targetUserId":"$target","actionType":"advertencia","reason":"spam"}""", idemKey, token)

    @Test
    fun aRepeatedSupportReplyWithTheSameKeyIsStoredOnce() = testApplication {
        val stub = Stub()
        application { adminModule(gateway(stub)) }
        val first = reply(key)
        val second = reply(key)
        assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())
        assertEquals(HttpStatusCode.Created, second.status)
        assertEquals("true", second.headers[IdempotencyHeaders.REPLAYED])
        assertEquals(first.bodyAsText(), second.bodyAsText())
        assertEquals(1, stub.posts("mensajes_soporte").size, "un solo mensaje para dos envios con la misma clave")
        assertEquals(1, stub.posts("auditoria_log").size)
    }

    @Test
    fun theSameKeyWithAnotherTextIsRejected() = testApplication {
        val stub = Stub()
        application { adminModule(gateway(stub)) }
        assertEquals(HttpStatusCode.Created, reply(key).status)
        val other = reply(key, text = "otro")
        assertEquals(HttpStatusCode.UnprocessableEntity, other.status)
        assertEquals(1, stub.posts("mensajes_soporte").size)
    }

    @Test
    fun withoutAKeyTheReplyBehavesAsBefore() = testApplication {
        val stub = Stub()
        application { adminModule(gateway(stub)) }
        assertEquals(HttpStatusCode.Created, reply(null).status)
        assertEquals(HttpStatusCode.Created, reply(null).status)
        assertEquals(2, stub.posts("mensajes_soporte").size)
    }

    @Test
    fun aRepeatedWarningWithTheSameKeyIsWrittenOnce() = testApplication {
        val stub = Stub()
        application { adminModule(gateway(stub)) }
        val first = warn(key)
        val second = warn(key)
        assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())
        assertEquals(HttpStatusCode.Created, second.status)
        assertEquals("true", second.headers[IdempotencyHeaders.REPLAYED])
        assertEquals(1, stub.posts("auditoria_log").size, "una sola advertencia auditada")
        assertEquals(1, stub.posts("avisos_usuario").size, "un solo aviso para el usuario")
    }

    @Test
    fun withoutAKeyTheWarningBehavesAsBefore() = testApplication {
        val stub = Stub()
        application { adminModule(gateway(stub)) }
        assertEquals(HttpStatusCode.Created, warn(null).status)
        assertEquals(HttpStatusCode.Created, warn(null).status)
        assertEquals(2, stub.posts("avisos_usuario").size)
    }

    @Test
    fun theStaticTokenStillGets403WithOrWithoutKeyAndTouchesNothing() = testApplication {
        val stub = Stub()
        application { adminModule(gateway(stub)) }
        assertEquals(HttpStatusCode.Forbidden, reply(key, token = STATIC_TOKEN_PRINCIPAL).status)
        assertEquals(HttpStatusCode.Forbidden, warn(key, token = STATIC_TOKEN_PRINCIPAL).status)
        assertEquals(HttpStatusCode.Forbidden, warn(null, token = STATIC_TOKEN_PRINCIPAL).status)
        assertTrue(stub.sent.isEmpty(), "${stub.sent.map { it.uri }}")
    }

    @Test
    fun aKeyWithoutAConfiguredStoreFailsClosedWithoutWriting() = testApplication {
        val stub = Stub()
        application { adminModule(gateway(stub), store = null) }
        assertEquals(HttpStatusCode.InternalServerError, reply(key).status)
        assertTrue(stub.posts("mensajes_soporte").isEmpty())
        assertEquals(HttpStatusCode.Created, reply(null).status, "sin clave sigue funcionando")
    }

    /** Auditoria fail-closed de verdad: la fila va ANTES del insert y lleva el id del mensaje que se va a crear. */
    @Test
    fun theSupportAuditIsWrittenBeforeTheMessageAndNamesItsId() = testApplication {
        val stub = Stub()
        application { adminModule(gateway(stub)) }
        assertEquals(HttpStatusCode.Created, reply(null).status)
        val audit = stub.posts("auditoria_log").single()
        val insert = stub.posts("mensajes_soporte").single()
        assertTrue(stub.sent.indexOf(audit) < stub.sent.indexOf(insert), "la auditoria va antes del insert")
        val auditedId = Json.parseToJsonElement(audit.body).jsonArray.single().jsonObject["registro_id"]!!.jsonPrimitive.content
        val insertedId = Json.parseToJsonElement(insert.body).jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(auditedId, insertedId)
    }

    @Test
    fun aFailedSupportAuditLeavesNoMessage() = testApplication {
        val stub = Stub(auditFails = true)
        application { adminModule(gateway(stub)) }
        val response = reply(null)
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertTrue(stub.posts("mensajes_soporte").isEmpty(), "sin auditoria no hay mensaje")
        assertNull(response.headers[IdempotencyHeaders.REPLAYED])
    }
}
