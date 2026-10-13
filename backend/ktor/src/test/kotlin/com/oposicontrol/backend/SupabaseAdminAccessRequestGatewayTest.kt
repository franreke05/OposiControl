package com.oposicontrol.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El gateway de solicitudes de acceso contra Supabase: la contrasena solo viaja a `POST /auth/v1/admin/users`, la fila
 * guardada no la lleva, y una cuenta sin solicitud nunca sobrevive (compensacion y reconciliacion del alta perdida).
 */
class SupabaseAdminAccessRequestGatewayTest {
    private val password = "S3creta-Larga-No-Repetible-9731"
    private val newUserId = "22222222-2222-4222-8222-222222222222"
    private val actorId = "5a5a5a5a-0000-4000-8000-000000000001"
    private val requestId = "77777777-7777-4777-8777-777777777777"

    private class Sent(val method: HttpMethod, val path: String, val query: String, val body: String, val headers: Map<String, String>)

    private class Wire(private val respond: suspend MockRequestHandleScope.(Sent) -> HttpResponseData) {
        val sent = CopyOnWriteArrayList<Sent>()
        val client = HttpClient(
            MockEngine { request: HttpRequestData ->
                val seen = Sent(
                    method = request.method,
                    path = request.url.encodedPath,
                    query = request.url.encodedQuery,
                    body = String(request.body.toByteArray(), Charsets.UTF_8),
                    headers = request.headers.entries().associate { it.key to it.value.joinToString(",") },
                )
                sent += seen
                respond(seen)
            },
        )

        fun calls(): List<String> = sent.map { "${it.method.value} ${it.path}" }
    }

    private fun MockRequestHandleScope.json(status: HttpStatusCode, body: String) =
        respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun gateway(wire: Wire) =
        SupabaseAdminAccessRequestGateway("https://example.supabase.co", "service-role-key", Json { ignoreUnknownKeys = true; explicitNulls = false }, wire.client)

    private fun submission() = AdminAccessSubmission(
        nombre = "Ada Lovelace",
        email = "ada@example.com",
        password = password,
        motivo = "Coordino el equipo de contenido.",
    )

    private fun row(estado: String = "pendiente", resolved: Boolean = false) =
        """{"id":"$requestId","usuario_id":"$newUserId","nombre":"Ada Lovelace","email":"ada@example.com","motivo":"Coordino","estado":"$estado",""" +
            """"creado_en":"2026-10-13T09:00:00+00:00","resuelto_en":${if (resolved) "\"2026-10-13T10:00:00+00:00\"" else "null"},""" +
            """"resuelto_por":${if (resolved) "\"$actorId\"" else "null"},"motivo_resolucion":null}"""

    // ------------------------------------------------------------------------------------------------ alta

    @Test
    fun submitCreatesTheAccountWithoutRoleAndStoresARowThatNeverCarriesThePassword() = runTest {
        val wire = Wire { sent ->
            when (sent.path) {
                "/auth/v1/admin/users" -> json(HttpStatusCode.OK, """{"id":"$newUserId","email":"ada@example.com"}""")
                else -> json(HttpStatusCode.Created, "")
            }
        }

        assertEquals(AdminAccessSubmitOutcome.CREATED, gateway(wire).submit(submission()))

        assertEquals(listOf("POST /auth/v1/admin/users", "POST /rest/v1/solicitudes_acceso_admin"), wire.calls())
        val auth = Json.parseToJsonElement(wire.sent[0].body).jsonObject
        assertEquals("ada@example.com", auth["email"]!!.jsonPrimitive.content)
        assertEquals(password, auth["password"]!!.jsonPrimitive.content)
        assertEquals("Ada Lovelace", auth["user_metadata"]!!.jsonObject["nombre"]!!.jsonPrimitive.content)
        // SIN confirmar: la cuenta no puede obtener sesion en ningun sitio hasta que un superadministrador la apruebe.
        assertEquals("false", auth["email_confirm"]!!.jsonPrimitive.content)
        assertFalse(auth["email_confirm"]!!.jsonPrimitive.isString)
        // El alta de administracion no envia correos; y aqui no se llama a nada que los envie.
        assertTrue(wire.sent.none { "/signup" in it.path || "/invite" in it.path || "/resend" in it.path || "/otp" in it.path || "/magiclink" in it.path })
        // Ni rol ni metadatos de aplicacion: la cuenta nace sin ningun privilegio.
        assertFalse("app_metadata" in auth.keys || "role" in auth.keys)

        val stored = Json.parseToJsonElement(wire.sent[1].body).jsonArray.single().jsonObject
        assertEquals(setOf("usuario_id", "nombre", "email", "motivo"), stored.keys)
        assertEquals(newUserId, stored["usuario_id"]!!.jsonPrimitive.content)
        assertFalse(password in wire.sent[1].body, "la fila guardada no lleva la contrasena")
        // El estado lo pone la base (`default 'pendiente'`): el backend no puede crear una solicitud ya aprobada.
        assertFalse("estado" in stored.keys)
        assertTrue(wire.sent.none { password in it.path || password in it.query }, "la contrasena nunca va en una URL")
        assertTrue(wire.sent.all { it.headers["apikey"] == "service-role-key" })
        assertTrue(wire.sent.none { sent -> sent.headers.values.any { password in it } }, "ni en una cabecera")
    }

    @Test
    fun anExistingEmailCreatesNothingAndTouchesNoExistingAccount() = runTest {
        val wire = Wire { json(HttpStatusCode.UnprocessableEntity, """{"code":422,"error_code":"email_exists","msg":"A user with this email address has already been registered"}""") }

        assertEquals(AdminAccessSubmitOutcome.ALREADY_REGISTERED, gateway(wire).submit(submission()))

        assertEquals(listOf("POST /auth/v1/admin/users"), wire.calls())
    }

    @Test
    fun aDataRejectionFromAuthCreatesNothingAndIsNotAnError() = runTest {
        val wire = Wire { json(HttpStatusCode.UnprocessableEntity, """{"code":422,"error_code":"weak_password","msg":"Password is too weak"}""") }

        assertEquals(AdminAccessSubmitOutcome.REJECTED_BY_AUTH, gateway(wire).submit(submission()))

        assertEquals(listOf("POST /auth/v1/admin/users"), wire.calls())
    }

    // ------------------------------------------------------------------------------------------------ cuenta fantasma

    @Test
    fun ifTheRequestRowCannotBeStoredTheNewAccountIsDeleted() = runTest {
        val wire = Wire { sent ->
            when {
                sent.path == "/auth/v1/admin/users" -> json(HttpStatusCode.OK, """{"id":"$newUserId"}""")
                sent.method == HttpMethod.Post -> json(HttpStatusCode.InternalServerError, """{"message":"Failing row contains ($password)"}""")
                else -> json(HttpStatusCode.OK, "{}")
            }
        }

        val failure = assertFailsWith<IllegalStateException> { gateway(wire).submit(submission()) }

        assertEquals(
            listOf("POST /auth/v1/admin/users", "POST /rest/v1/solicitudes_acceso_admin", "DELETE /auth/v1/admin/users/$newUserId"),
            wire.calls(),
        )
        // El cuerpo de la respuesta de Supabase NO viaja en la excepcion (ni aunque repitiera un dato sensible).
        assertFalse(password in failure.message.orEmpty())
        assertTrue("500" in failure.message.orEmpty())
    }

    @Test
    fun aLostAuthAnswerIsReconciledByDeletingOnlyTheIdentityThisRequestCreated() = runTest {
        val createdAt = Instant.now().plusSeconds(1).toString()
        val wire = Wire { sent ->
            when {
                sent.method == HttpMethod.Post && sent.path == "/auth/v1/admin/users" -> json(HttpStatusCode.BadGateway, "upstream died")
                sent.method == HttpMethod.Get && sent.path == "/auth/v1/admin/users" ->
                    json(HttpStatusCode.OK, """{"users":[{"id":"$newUserId","email":"Ada@Example.com","created_at":"$createdAt"}]}""")
                sent.method == HttpMethod.Get -> json(HttpStatusCode.OK, "[]")
                else -> json(HttpStatusCode.OK, "{}")
            }
        }

        val failure = assertFailsWith<SupabaseHttpStatusException> { gateway(wire).submit(submission()) }

        assertEquals(502, failure.status)
        assertEquals(
            listOf(
                "POST /auth/v1/admin/users",
                "GET /auth/v1/admin/users",
                "GET /rest/v1/solicitudes_acceso_admin",
                "GET /rest/v1/roles_usuario",
                "DELETE /auth/v1/admin/users/$newUserId",
            ),
            wire.calls(),
        )
    }

    @Test
    fun anOlderIdentityWithThatEmailIsNeverDeletedByTheReconciliation() = runTest {
        val wire = Wire { sent ->
            when {
                sent.method == HttpMethod.Post -> json(HttpStatusCode.BadGateway, "upstream died")
                sent.path == "/auth/v1/admin/users" ->
                    json(HttpStatusCode.OK, """{"users":[{"id":"$newUserId","email":"ada@example.com","created_at":"2025-01-01T00:00:00Z"}]}""")
                else -> json(HttpStatusCode.OK, "[]")
            }
        }

        assertFailsWith<SupabaseHttpStatusException> { gateway(wire).submit(submission()) }

        assertTrue(wire.sent.none { it.method == HttpMethod.Delete }, "una cuenta anterior puede ser de un usuario real")
    }

    @Test
    fun anIdentityThatAlreadyHasRolesIsNeverDeletedByTheReconciliation() = runTest {
        val wire = Wire { sent ->
            when {
                sent.method == HttpMethod.Post -> json(HttpStatusCode.BadGateway, "upstream died")
                sent.path == "/auth/v1/admin/users" ->
                    json(HttpStatusCode.OK, """{"users":[{"id":"$newUserId","email":"ada@example.com","created_at":"${Instant.now().plusSeconds(1)}"}]}""")
                sent.path == "/rest/v1/roles_usuario" -> json(HttpStatusCode.OK, """[{"rol_id":"aaaaaaaa-0000-4000-8000-00000000000b"}]""")
                else -> json(HttpStatusCode.OK, "[]")
            }
        }

        assertFailsWith<SupabaseHttpStatusException> { gateway(wire).submit(submission()) }

        assertTrue(wire.sent.none { it.method == HttpMethod.Delete })
    }

    // ------------------------------------------------------------------------------------------------ lectura y resolucion

    @Test
    fun theListPutsPendingFirstAndFiltersByState() = runTest {
        val wire = Wire { json(HttpStatusCode.OK, "[${row()}]") }

        val all = gateway(wire).list(estado = null, limit = 500, offset = -3)
        gateway(wire).list(estado = "rechazada", limit = 20, offset = 40)
        gateway(wire).list(estado = "cualquiera'; drop", limit = 20, offset = 0)

        assertEquals("ada@example.com", all.single().email)
        assertEquals("pendiente", all.single().estado)
        assertNull(all.single().resueltoEn)
        val queries = wire.sent.map { java.net.URLDecoder.decode(it.query, Charsets.UTF_8) }
        assertTrue("order=resuelto_en.desc.nullsfirst,creado_en.desc,id.asc" in queries[0], queries[0])
        assertTrue("limit=200" in queries[0] && "offset=0" in queries[0], "limite y desplazamiento acotados: ${queries[0]}")
        assertFalse("estado=" in queries[0])
        assertTrue("estado=eq.rechazada" in queries[1] && "limit=20" in queries[1] && "offset=40" in queries[1], queries[1])
        assertFalse("estado=" in queries[2], "un estado desconocido no llega a PostgREST")
    }

    @Test
    fun resolvingIsConditionalOnThePendingStateAndRecordsTheReviewer() = runTest {
        val wire = Wire { sent -> json(HttpStatusCode.OK, if ("pendiente" in sent.query) "[${row("rechazada", resolved = true)}]" else "[]") }

        val resolved = gateway(wire).resolve(requestId, "rechazada", actorId, "No pertenece al equipo")

        assertEquals("rechazada", resolved!!.estado)
        val patch = wire.sent.single()
        assertEquals(HttpMethod.Patch, patch.method)
        assertEquals("id=eq.$requestId&estado=eq.pendiente", patch.query)
        assertEquals("return=representation", patch.headers["Prefer"])
        val body = Json.parseToJsonElement(patch.body).jsonObject
        assertEquals("rechazada", body["estado"]!!.jsonPrimitive.content)
        assertEquals(actorId, body["resuelto_por"]!!.jsonPrimitive.content)
        assertEquals("No pertenece al equipo", body["motivo_resolucion"]!!.jsonPrimitive.content)
        assertTrue(body["resuelto_en"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun confirmingTheEmailIsOneAdminPutThatOnlySetsEmailConfirm() = runTest {
        val wire = Wire { json(HttpStatusCode.OK, """{"id":"$newUserId","email_confirmed_at":"2026-10-13T10:00:00Z"}""") }

        gateway(wire).confirmEmail(newUserId)
        // Confirmar una cuenta ya confirmada es la misma llamada y GoTrue la acepta: repetirla no falla.
        gateway(wire).confirmEmail(newUserId.uppercase())

        assertEquals(listOf("PUT /auth/v1/admin/users/$newUserId", "PUT /auth/v1/admin/users/$newUserId"), wire.calls())
        val body = Json.parseToJsonElement(wire.sent.first().body).jsonObject
        // Solo la confirmacion: ni contrasena, ni correo, ni metadatos (no se puede usar para cambiar la cuenta).
        assertEquals(setOf("email_confirm"), body.keys)
        assertEquals("true", body["email_confirm"]!!.jsonPrimitive.content)
        assertEquals("service-role-key", wire.sent.first().headers["apikey"])
    }

    @Test
    fun aFailedConfirmationPropagatesWithItsStatusAndAnInvalidIdNeverLeaves() = runTest {
        val wire = Wire { json(HttpStatusCode.NotFound, """{"code":404,"error_code":"user_not_found","msg":"User not found"}""") }

        val failure = assertFailsWith<SupabaseHttpStatusException> { gateway(wire).confirmEmail(newUserId) }
        assertEquals(404, failure.status)
        assertFailsWith<Exception> { gateway(wire).confirmEmail("../otra-ruta") }

        assertEquals(1, wire.sent.size)
    }

    @Test
    fun deletingTheAccountIsOneAdminDeleteAndAnAccountAlreadyGoneIsNotAnError() = runTest {
        val wire = Wire { json(HttpStatusCode.OK, "{}") }
        gateway(wire).deleteAccount(newUserId)
        assertEquals(listOf("DELETE /auth/v1/admin/users/$newUserId"), wire.calls())

        val gone = Wire { json(HttpStatusCode.NotFound, """{"code":404,"error_code":"user_not_found","msg":"User not found"}""") }
        gateway(gone).deleteAccount(newUserId)

        val broken = Wire { json(HttpStatusCode.InternalServerError, """{"msg":"Database error deleting user"}""") }
        assertEquals(500, assertFailsWith<SupabaseHttpStatusException> { gateway(broken).deleteAccount(newUserId) }.status)
        assertFailsWith<Exception> { gateway(wire).deleteAccount("../otra-ruta") }
    }

    @Test
    fun resolvingARequestThatIsNoLongerPendingReturnsNull() = runTest {
        val wire = Wire { json(HttpStatusCode.OK, "[]") }
        assertNull(gateway(wire).resolve(requestId, "aprobada", actorId, null))
    }

    @Test
    fun identifiersAreValidatedBeforeTheyReachAUrl() = runTest {
        val wire = Wire { json(HttpStatusCode.OK, "[]") }
        assertFailsWith<Exception> { gateway(wire).find("1 or 1=1") }
        assertFailsWith<Exception> { gateway(wire).resolve(requestId, "aprobada", "no-es-uuid", null) }
        assertFailsWith<IllegalArgumentException> { gateway(wire).resolve(requestId, "superadmin", actorId, null) }
        assertTrue(wire.sent.isEmpty())
    }
}
