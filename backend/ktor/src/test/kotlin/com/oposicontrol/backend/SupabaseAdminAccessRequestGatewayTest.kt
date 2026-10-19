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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
        // Revision 2026-10-14 (DO_NOT_SHIP): contrasena ALEATORIA del servidor (43 caracteres base64url, 256 bits), nunca
        // una elegida por quien pide: la suya la fija al verificar el codigo.
        val serverPassword = auth["password"]!!.jsonPrimitive.content
        assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(serverPassword), "contrasena aleatoria del servidor")
        assertFalse(serverPassword in wire.sent[1].body, "la fila guardada no lleva la contrasena")
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

    // ------------------------------------------------------------------------------------------------ codigo de verificacion

    /**
     * H1: el correo con el codigo es el OTP de GoTrue (`/otp`, plantilla «Magic Link») con `create_user:false`; NUNCA el
     * `resend` de tipo signup (plantilla «Confirm signup», con el enlace del consumidor). Sin redireccion ni contrasena.
     */
    @Test
    fun sendingTheCodeIsOneOtpWithoutUserCreationAndWithTheServiceKey() = runTest {
        val wire = Wire { json(HttpStatusCode.OK, "{}") }

        gateway(wire).sendAccessCode("ada@example.com")

        assertEquals(listOf("POST /auth/v1/otp"), wire.calls())
        val sent = wire.sent.single()
        assertEquals("", sent.query)
        val body = Json.parseToJsonElement(sent.body).jsonObject
        assertEquals(setOf("email", "create_user"), body.keys)
        assertEquals("false", body["create_user"]!!.jsonPrimitive.content)
        assertFalse(body["create_user"]!!.jsonPrimitive.isString)
        assertEquals("ada@example.com", body["email"]!!.jsonPrimitive.content)
        assertEquals("service-role-key", sent.headers["apikey"])
        assertTrue(wire.sent.none { it.path == "/auth/v1/resend" || it.path == "/auth/v1/signup" })
    }

    /** 429 (un correo por minuto) u otro 4xx no lanzan: no sale correo y la respuesta publica no cambia. 5xx y red, si. */
    @Test
    fun aRejectedCodeMailIsSwallowedButAServerFailurePropagates() = runTest {
        gateway(Wire { json(HttpStatusCode.TooManyRequests, """{"code":429,"error_code":"over_email_send_rate_limit"}""") }).sendAccessCode("ada@example.com")
        gateway(Wire { json(HttpStatusCode.BadRequest, """{"code":400,"error_code":"captcha_failed"}""") }).sendAccessCode("ada@example.com")

        val broken = assertFailsWith<SupabaseHttpStatusException> {
            gateway(Wire { json(HttpStatusCode.BadGateway, "{}") }).sendAccessCode("ada@example.com")
        }
        assertEquals(502, broken.status)
    }

    @Test
    fun verifyingTheCodeSendsTypeEmailWithEmailAndTokenAndReturnsTheSessionToRevoke() = runTest {
        val wire = Wire {
            json(HttpStatusCode.OK, """{"access_token":"verify-access","refresh_token":"verify-refresh","user":{"id":"$newUserId","email":"ada@example.com"}}""")
        }

        val verified = gateway(wire).verifyAccessCode("ada@example.com", "123456")!!

        assertEquals(newUserId, verified.userId)
        assertEquals("verify-access", verified.accessToken)
        assertFalse("verify-access" in verified.toString(), "toString no imprime la sesion")
        val body = Json.parseToJsonElement(wire.sent.single().body).jsonObject
        assertEquals(setOf("type", "email", "token"), body.keys)
        assertEquals("email", body["type"]!!.jsonPrimitive.content)
        assertEquals("ada@example.com", body["email"]!!.jsonPrimitive.content)
        assertEquals("123456", body["token"]!!.jsonPrimitive.content)
        assertEquals("POST /auth/v1/verify", wire.calls().single())
    }

    @Test
    fun aRejectedCodeIsNullAThrottledOneIsUpstreamUnavailableAndAServerFailurePropagates() = runTest {
        for (status in listOf(HttpStatusCode.Forbidden, HttpStatusCode.BadRequest, HttpStatusCode.UnprocessableEntity, HttpStatusCode.NotFound)) {
            assertNull(gateway(Wire { json(status, """{"error_code":"otp_expired"}""") }).verifyAccessCode("ada@example.com", "123456"), status.toString())
        }
        // El 429 de GoTrue es su limite (compartido por la IP del backend), no el del solicitante: 503 con Retry-After.
        val throttled = assertFailsWith<OposiBotsAuthProblem> {
            gateway(Wire { json(HttpStatusCode.TooManyRequests, "{}") }).verifyAccessCode("ada@example.com", "123456")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, throttled.status)
        assertEquals(CanonicalErrorCode.UPSTREAM_UNAVAILABLE, throttled.canonicalCode())
        assertEquals(60L, throttled.retryAfterSeconds)
        assertFailsWith<SupabaseHttpStatusException> {
            gateway(Wire { json(HttpStatusCode.InternalServerError, "{}") }).verifyAccessCode("ada@example.com", "123456")
        }
    }

    /** La sesion de la verificacion se revoca con SU token (no con la clave de servicio) y en global. */
    @Test
    fun revokingTheVerifySessionIsAGlobalLogoutWithTheUserToken() = runTest {
        val token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIyMjIyIn0.c2lnbmF0dXJl"
        val wire = Wire { json(HttpStatusCode.NoContent, "") }

        assertTrue(gateway(wire).revokeSession(token))

        val sent = wire.sent.single()
        assertEquals("POST /auth/v1/logout", "${sent.method.value} ${sent.path}")
        assertEquals("scope=global", sent.query)
        assertEquals("Bearer $token", sent.headers[HttpHeaders.Authorization])
        assertEquals("service-role-key", sent.headers["apikey"])
        // 401/404 = la sesion ya no existe: objetivo cumplido. Otro fallo: false, nunca una excepcion.
        assertTrue(gateway(Wire { json(HttpStatusCode.Unauthorized, "{}") }).revokeSession(token))
        assertFalse(gateway(Wire { json(HttpStatusCode.InternalServerError, "{}") }).revokeSession(token))
    }

    /** La marca de verificado esta CONDICIONADA: esa fila, ese usuario, pendiente y todavia sin verificar. */
    @Test
    fun markingTheEmailVerifiedIsAConditionalPatch() = runTest {
        val wire = Wire { json(HttpStatusCode.OK, "[${row()}]") }

        val marked = gateway(wire).markEmailVerified(requestId, newUserId.uppercase())

        assertEquals(requestId, marked!!.id)
        val patch = wire.sent.single()
        assertEquals(HttpMethod.Patch, patch.method)
        assertEquals("id=eq.$requestId&usuario_id=eq.$newUserId&estado=eq.pendiente&correo_verificado_en=is.null", patch.query)
        assertEquals("return=representation", patch.headers["Prefer"])
        assertEquals(setOf("correo_verificado_en"), Json.parseToJsonElement(patch.body).jsonObject.keys)
        assertNull(gateway(Wire { json(HttpStatusCode.OK, "[]") }).markEmailVerified(requestId, newUserId))
    }

    // ------------------------------------------------------------------------------------------------ guarda

    @Test
    fun theAccountStateComesFromTheAdminApiAndAMissingAccountIsNull() = runTest {
        val confirmed = Wire { json(HttpStatusCode.OK, """{"id":"$newUserId","email_confirmed_at":"2026-10-14T10:00:00Z"}""") }
        assertEquals(AuthAccountState(emailConfirmed = true), gateway(confirmed).authAccount(newUserId))
        assertEquals("GET /auth/v1/admin/users/$newUserId", confirmed.calls().single())
        assertEquals(
            AuthAccountState(emailConfirmed = false),
            gateway(Wire { json(HttpStatusCode.OK, """{"id":"$newUserId","email_confirmed_at":null}""") }).authAccount(newUserId),
        )
        assertNull(gateway(Wire { json(HttpStatusCode.NotFound, """{"error_code":"user_not_found"}""") }).authAccount(newUserId))
        // Otro usuario en la respuesta no es esta cuenta: falla cerrado.
        assertFailsWith<IllegalStateException> {
            gateway(Wire { json(HttpStatusCode.OK, """{"id":"$actorId","email_confirmed_at":null}""") }).authAccount(newUserId)
        }
        assertFailsWith<Exception> { gateway(confirmed).authAccount("../otra-ruta") }
    }

    /** Revision #3 M1: `banned_until` en el futuro = baneada; pasado o ausente = no; presente e ilegible = baneada. */
    @Test
    fun theAccountStateReadsTheBanFromBannedUntil() = runTest {
        suspend fun stateWith(bannedUntil: String?) = gateway(
            Wire {
                json(
                    HttpStatusCode.OK,
                    """{"id":"$newUserId","email_confirmed_at":"2026-10-14T10:00:00Z","banned_until":${bannedUntil?.let { "\"$it\"" } ?: "null"}}""",
                )
            },
        ).authAccount(newUserId)
        assertEquals(AuthAccountState(emailConfirmed = true, banned = true), stateWith("2126-10-14T10:00:00Z"))
        assertEquals(AuthAccountState(emailConfirmed = true, banned = true), stateWith("2126-10-14T10:00:00.123456+00:00"))
        assertEquals(AuthAccountState(emailConfirmed = true, banned = false), stateWith("2001-01-01T00:00:00Z"))
        assertEquals(AuthAccountState(emailConfirmed = true, banned = false), stateWith(null))
        assertEquals(AuthAccountState(emailConfirmed = true, banned = true), stateWith("no-es-una-fecha"))
    }

    /** Revision #3 L1: la purga es la RPC con la clave de servicio y solo `p_user_id`; una respuesta no entera falla cerrado. */
    @Test
    fun purgingTheSessionsCallsTheServiceRoleRpc() = runTest {
        val wire = Wire { json(HttpStatusCode.OK, "2") }

        assertEquals(2, gateway(wire).purgeSessions(newUserId.uppercase()))

        val sent = wire.sent.single()
        assertEquals("POST /rest/v1/rpc/$ADMIN_PURGE_USER_SESSIONS_RPC", "${sent.method.value} ${sent.path}")
        assertEquals(mapOf("p_user_id" to newUserId), Json.parseToJsonElement(sent.body).jsonObject.mapValues { it.value.jsonPrimitive.content })
        assertEquals("service-role-key", sent.headers["apikey"])
        assertFailsWith<IllegalStateException> { gateway(Wire { json(HttpStatusCode.OK, "{}") }).purgeSessions(newUserId) }
        assertFailsWith<SupabaseHttpStatusException> { gateway(Wire { json(HttpStatusCode.Forbidden, "{}") }).purgeSessions(newUserId) }
        assertFailsWith<Exception> { gateway(wire).purgeSessions("../otra-ruta") }
    }

    @Test
    fun deletingAnUnverifiedRowIsConditionalAndAuditRowsCarryNoActor() = runTest {
        val wire = Wire { sent -> json(HttpStatusCode.OK, if (sent.method == HttpMethod.Delete) """[{"id":"$requestId"}]""" else "") }

        assertTrue(gateway(wire).deleteUnverifiedRequest(requestId))
        gateway(wire).audit(ACTION_ACCESS_REQUEST_EXPIRED, requestId, buildJsonObject { put("motivo", "x") })

        val delete = wire.sent[0]
        assertEquals("id=eq.$requestId&correo_verificado_en=is.null&estado=neq.aprobada", delete.query)
        assertEquals("return=representation", delete.headers["Prefer"])
        val audit = wire.sent[1]
        assertEquals("POST /rest/v1/auditoria_log", "${audit.method.value} ${audit.path}")
        val row = Json.parseToJsonElement(audit.body).jsonArray.single().jsonObject
        assertEquals(setOf("accion", "tabla_afectada", "registro_id", "despues"), row.keys)
        assertEquals(ACTION_ACCESS_REQUEST_EXPIRED, row["accion"]!!.jsonPrimitive.content)
    }

    @Test
    fun theListComputesVerifiedAndExpiredFlags() = runTest {
        val old = row().replace("\"creado_en\":\"2026-10-13T09:00:00+00:00\"", "\"creado_en\":\"2000-01-01T00:00:00+00:00\"")
        val verified = row().replace("\"estado\":\"pendiente\"", "\"estado\":\"pendiente\",\"correo_verificado_en\":\"2026-10-13T09:05:00+00:00\"")
        val wire = Wire { json(HttpStatusCode.OK, "[$old,$verified]") }

        val items = gateway(wire).list(null, 10, 0)

        assertEquals(listOf(false, true), items.map { it.correoVerificado })
        assertEquals(listOf(true, false), items.map { it.caducada })
        assertTrue("correo_verificado_en" in wire.sent.single().query)
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

    // ------------------------------------------------------------------------------------------------ revision de seguridad

    /** M1: banear y desbanear son un PUT de administracion con `ban_duration` (876000h / none) y nada mas. */
    @Test
    fun banAndUnbanAreOneAdminPutWithTheBanDuration() = runTest {
        val wire = Wire { json(HttpStatusCode.OK, """{"id":"$newUserId"}""") }

        gateway(wire).setBanned(newUserId.uppercase(), banned = true)
        gateway(wire).setBanned(newUserId, banned = false)

        assertEquals(listOf("PUT /auth/v1/admin/users/$newUserId", "PUT /auth/v1/admin/users/$newUserId"), wire.calls())
        assertEquals(listOf("876000h", "none"), wire.sent.map { Json.parseToJsonElement(it.body).jsonObject.getValue("ban_duration").jsonPrimitive.content })
        assertTrue(wire.sent.all { Json.parseToJsonElement(it.body).jsonObject.keys == setOf("ban_duration") })
        assertEquals(500, assertFailsWith<SupabaseHttpStatusException> {
            gateway(Wire { json(HttpStatusCode.InternalServerError, "{}") }).setBanned(newUserId, banned = true)
        }.status)
        assertFailsWith<Exception> { gateway(wire).setBanned("../otra", banned = true) }
    }

    /** La contrasena elegida al verificar: UN PUT de administracion con `password` y nada mas; nunca en URL, cabecera ni error. */
    @Test
    fun settingTheChosenPasswordIsOneAdminPutWithOnlyThePassword() = runTest {
        val wire = Wire { json(HttpStatusCode.OK, """{"id":"$newUserId"}""") }

        gateway(wire).setPassword(newUserId.uppercase(), password)

        assertEquals(listOf("PUT /auth/v1/admin/users/$newUserId"), wire.calls())
        assertEquals(mapOf("password" to password), Json.parseToJsonElement(wire.sent.single().body).jsonObject.mapValues { it.value.jsonPrimitive.content })
        assertTrue(wire.sent.none { password in it.path || password in it.query || it.headers.values.any { value -> password in value } })
        val failure = assertFailsWith<SupabaseHttpStatusException> {
            gateway(Wire { json(HttpStatusCode.UnprocessableEntity, """{"error_code":"weak_password","msg":"$password"}""") }).setPassword(newUserId, password)
        }
        assertEquals(422, failure.status)
        assertFalse(password in failure.message.orEmpty())
        assertFailsWith<Exception> { gateway(wire).setPassword("../otra", password) }
    }

    /** H1: contrasena aleatoria del servidor (PUT), inicio con ELLA y cierre global; nunca la contrasena en URL ni en cabeceras. */
    @Test
    fun neutralisingTheCredentialRotatesThePasswordAndRevokesEverySession() = runTest {
        val wire = Wire { sent ->
            when (sent.path) {
                "/auth/v1/admin/users/$newUserId" -> json(HttpStatusCode.OK, """{"id":"$newUserId","email":"ada@example.com"}""")
                "/auth/v1/token" -> json(HttpStatusCode.OK, """{"access_token":"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIyMjIyIn0.bmV1dHJhbGlzZQ","refresh_token":"r","user":{"id":"$newUserId"}}""")
                else -> json(HttpStatusCode.NoContent, "")
            }
        }

        gateway(wire).neutraliseCredential(newUserId, revokeSessions = true)

        assertEquals(listOf("PUT /auth/v1/admin/users/$newUserId", "POST /auth/v1/token", "POST /auth/v1/logout"), wire.calls())
        val newPassword = Json.parseToJsonElement(wire.sent[0].body).jsonObject.getValue("password").jsonPrimitive.content
        assertTrue(newPassword.length >= 43 && newPassword.all { it.code in 0x21..0x7E }, "256 bits ASCII")
        assertEquals("grant_type=password", wire.sent[1].query)
        assertEquals(newPassword, Json.parseToJsonElement(wire.sent[1].body).jsonObject.getValue("password").jsonPrimitive.content)
        assertEquals("scope=global", wire.sent[2].query)
        assertEquals("Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIyMjIyIn0.bmV1dHJhbGlzZQ", wire.sent[2].headers[HttpHeaders.Authorization])
        assertTrue(wire.sent.none { newPassword in it.query || it.headers.values.any { v -> newPassword in v } })
        // Dos llamadas, dos contrasenas distintas.
        val again = Wire { json(HttpStatusCode.OK, """{"id":"$newUserId","email":"ada@example.com"}""") }
        gateway(again).neutraliseCredential(newUserId, revokeSessions = false)
        assertEquals(listOf("PUT /auth/v1/admin/users/$newUserId"), again.calls())
        assertFalse(newPassword == Json.parseToJsonElement(again.sent.single().body).jsonObject.getValue("password").jsonPrimitive.content)
        // Si no se pueden revocar las sesiones, falla (la guarda falla cerrado y lo reintenta todo).
        val noLogout = Wire { sent ->
            when (sent.path) {
                "/auth/v1/logout" -> json(HttpStatusCode.InternalServerError, "{}")
                "/auth/v1/token" -> json(HttpStatusCode.OK, """{"access_token":"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIyMjIyIn0.dA","refresh_token":"r"}""")
                else -> json(HttpStatusCode.OK, """{"id":"$newUserId","email":"ada@example.com"}""")
            }
        }
        assertFailsWith<IllegalStateException> { gateway(noLogout).neutraliseCredential(newUserId, revokeSessions = true) }
    }

    /** M2: el contador de fallos es un PATCH condicionado al valor leido; ante una carrera se relee y se reintenta. */
    @Test
    fun theFailedAttemptCounterIsAnOptimisticConditionalPatch() = runTest {
        val reservation = AccessRequestReservation(requestId, newUserId, "pendiente", null, "2026-10-13T09:00:00Z", intentosVerificacion = 2)
        val wire = Wire { sent ->
            when {
                sent.method == HttpMethod.Patch && "intentos_verificacion=eq.2" in sent.query -> json(HttpStatusCode.OK, "[]")
                sent.method == HttpMethod.Get -> json(
                    HttpStatusCode.OK,
                    """[{"id":"$requestId","usuario_id":"$newUserId","estado":"pendiente","creado_en":"2026-10-13T09:00:00Z","intentos_verificacion":3}]""",
                )
                else -> json(HttpStatusCode.OK, "[${row()}]")
            }
        }

        assertEquals(4, gateway(wire).recordFailedVerification(reservation))

        assertEquals(listOf("PATCH /rest/v1/solicitudes_acceso_admin", "GET /rest/v1/solicitudes_acceso_admin", "PATCH /rest/v1/solicitudes_acceso_admin"), wire.calls())
        assertEquals(
            "id=eq.$requestId&estado=eq.pendiente&correo_verificado_en=is.null&intentos_verificacion=eq.2",
            wire.sent[0].query,
        )
        assertEquals("3", Json.parseToJsonElement(wire.sent[0].body).jsonObject.getValue("intentos_verificacion").jsonPrimitive.content)
        assertTrue("intentos_verificacion=eq.3" in wire.sent[2].query)
        // La fila ya no esta viva: null.
        assertNull(gateway(Wire { json(HttpStatusCode.OK, "[]") }).recordFailedVerification(reservation))
    }

    /** L1: revertir la aprobacion vuelve a `pendiente` condicionado a `aprobada` y al mismo actor, con la resolucion vacia. */
    @Test
    fun revertingAnApprovalIsConditionalOnApprovedAndTheActor() = runTest {
        val wire = Wire { json(HttpStatusCode.OK, "[${row()}]") }

        assertEquals("pendiente", gateway(wire).revertApproval(requestId, actorId.uppercase())!!.estado)

        val patch = wire.sent.single()
        assertEquals("id=eq.$requestId&estado=eq.aprobada&resuelto_por=eq.$actorId", patch.query)
        val body = Json.parseToJsonElement(patch.body).jsonObject
        assertEquals("pendiente", body["estado"]!!.jsonPrimitive.content)
        assertEquals(setOf("estado", "resuelto_en", "resuelto_por", "motivo_resolucion"), body.keys)
        assertTrue(listOf("resuelto_en", "resuelto_por", "motivo_resolucion").all { body[it] is kotlinx.serialization.json.JsonNull })
        assertNull(gateway(Wire { json(HttpStatusCode.OK, "[]") }).revertApproval(requestId, actorId))
    }

    /** L2: la fila guarda el correo que devuelve GoTrue; si su minusculado no es el canonico, no se guarda nada (y se borra la cuenta). */
    @Test
    fun theRowStoresTheUpstreamEmailAndADivergentSpellingIsDiscarded() = runTest {
        val same = Wire { sent ->
            if (sent.path == "/auth/v1/admin/users") json(HttpStatusCode.OK, """{"id":"$newUserId","email":"ada@example.com"}""") else json(HttpStatusCode.Created, "")
        }
        assertEquals(AdminAccessSubmitOutcome.CREATED, gateway(same).submit(submission()))
        assertEquals("ada@example.com", Json.parseToJsonElement(same.sent[1].body).jsonArray.single().jsonObject["email"]!!.jsonPrimitive.content)

        val divergent = Wire { sent ->
            if (sent.method == HttpMethod.Post) json(HttpStatusCode.OK, """{"id":"$newUserId","email":"i̇nfo@example.com"}""") else json(HttpStatusCode.OK, "{}")
        }
        assertEquals(AdminAccessSubmitOutcome.REJECTED_BY_AUTH, gateway(divergent).submit(submission()))
        assertEquals(listOf("POST /auth/v1/admin/users", "DELETE /auth/v1/admin/users/$newUserId"), divergent.calls())
    }

    /** L6: fuera de desarrollo el gateway exige el secreto del seudonimo; en desarrollo conserva el respaldo sin clave. */
    @Test
    fun outsideDevelopmentTheEmailPseudonymSecretIsRequired() {
        val base = mapOf("SUPABASE_URL" to "https://example.supabase.co", "SUPABASE_SECRET_KEY" to "service-role-key")
        val json = Json { ignoreUnknownKeys = true }
        for (env in listOf(null, "production", "staging")) {
            val failure = assertFailsWith<IllegalStateException>(env.toString()) {
                SupabaseAdminAccessRequestGateway.fromEnvironment(json) { (base + listOfNotNull(env?.let { "APP_ENV" to it })).toMap()[it] }
            }
            assertTrue("AUTH_RATE_LIMIT_HMAC_SECRET" in failure.message.orEmpty())
        }
        (SupabaseAdminAccessRequestGateway.fromEnvironment(json) { (base + ("APP_ENV" to "development"))[it] } as AutoCloseable).close()
        (SupabaseAdminAccessRequestGateway.fromEnvironment(json) { (base + ("AUTH_RATE_LIMIT_HMAC_SECRET" to "x".repeat(32)))[it] } as AutoCloseable).close()
        // Sin URL ni clave sigue siendo null (rutas fallan cerradas), sin exigir nada mas.
        assertNull(SupabaseAdminAccessRequestGateway.fromEnvironment(json) { null })
    }

    /** RB-14: `control` en produccion arranca con SU secreto (F-09b: no recibe el del consumidor); uno corto no vale. */
    @Test
    fun controlInProductionUsesItsOwnAdminSecretForTheEmailPseudonym() {
        val base = mapOf("SUPABASE_URL" to "https://example.supabase.co", "SUPABASE_SECRET_KEY" to "service-role-key", "APP_ENV" to "production")
        val json = Json { ignoreUnknownKeys = true }
        (SupabaseAdminAccessRequestGateway.fromEnvironment(json) { (base + (ADMIN_RATE_LIMIT_HMAC_SECRET_ENV to "a".repeat(32)))[it] } as AutoCloseable).close()
        val short = assertFailsWith<IllegalStateException> {
            SupabaseAdminAccessRequestGateway.fromEnvironment(json) { (base + (ADMIN_RATE_LIMIT_HMAC_SECRET_ENV to "corto"))[it] }
        }
        assertTrue(ADMIN_RATE_LIMIT_HMAC_SECRET_ENV in short.message.orEmpty())
        // El seudonimo cambia con la clave: el de control no coincide con el de bot para el mismo correo (documentado).
        assertNotEquals(authLogEmailRef("a".repeat(32), "ada@example.com"), authLogEmailRef("b".repeat(32), "ada@example.com"))
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
