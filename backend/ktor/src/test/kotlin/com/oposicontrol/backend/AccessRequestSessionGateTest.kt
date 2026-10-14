package com.oposicontrol.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * La puerta de sesion de las solicitudes de acceso (revision de seguridad independiente de B1, 2026-10-13, y plan del
 * propietario 2026-10-14) en el UNICO punto donde se entregan sesiones (`enforceAccountGate`). Recorre ruta -> gateway de
 * auth REAL -> GoTrue y `perfiles_usuario` (falsos, graban las peticiones) y gateway -> puerta REAL
 * ([PostgrestAccessRequestMailGuard]) -> gateway REAL de solicitudes -> Supabase falso ([AccessRequestFakeSupabase]).
 *
 *  - solicitud VERIFICADA y no aprobada: 403 FORBIDDEN, revocacion global, como hasta ahora;
 *  - solicitud SIN VERIFICAR: GoTrue ya entrego la sesion (la cuenta esta confirmada por otra via). Contrasena/refresh:
 *    se deniega y se neutraliza la credencial. OAuth/enlace (el titular): se neutraliza la credencial del creador, se
 *    revocan sus demas sesiones (`scope=others`), se borra SOLO la fila (condicionada a seguir sin verificar), se audita y
 *    la sesion se entrega;
 *  - M-1: la decision no escribe; la limpieza va despues de la lectura reintentada, una vez y sin cancelarse a medias.
 *
 * Ademas: la regla de contrasena NUEVA (12..72 ASCII imprimible) en registro y reset, y el login con su regla de siempre.
 */
class AccessRequestSessionGateTest {
    private val context = OposiBotsAuthContext("request-session-gate", "127.0.0.1")
    private val pendingEmail = "ada@example.com"
    private val sessionLookup = accessRequestSessionPath(UID)

    /** GoTrue, `perfiles_usuario`, el reclamo OAuth y Apple, todos falsos detras del gateway real. */
    private class Upstream(profile: String = profileRow("activa"), othersLogoutStatus: HttpStatusCode = HttpStatusCode.NoContent) {
        val requests = CopyOnWriteArrayList<HttpRequestData>()
        val client = HttpClient(MockEngine { request ->
            requests += request
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            val path = request.url.encodedPath
            when {
                request.url.host == "appleid.apple.com" -> respond(ByteReadChannel("""{"id_token":"$ID_TOKEN"}"""), HttpStatusCode.OK, json)
                path == "/auth/v1/token" || path == "/auth/v1/verify" -> respond(ByteReadChannel(SESSION), HttpStatusCode.OK, json)
                path == "/auth/v1/signup" -> respond(ByteReadChannel("""{"user":{"id":"user"}}"""), HttpStatusCode.OK, json)
                path == "/auth/v1/user" && request.method == HttpMethod.Put -> respond(ByteReadChannel("{}"), HttpStatusCode.OK, json)
                path == "/auth/v1/logout" && request.url.encodedQuery == "scope=others" -> respond(ByteReadChannel(""), othersLogoutStatus, json)
                path == "/auth/v1/logout" -> respond(ByteReadChannel(""), HttpStatusCode.NoContent, json)
                path == "/rest/v1/rpc/oposibots_claim_oauth_credential" -> respond(ByteReadChannel("{}"), HttpStatusCode.NoContent, json)
                path == "/rest/v1/perfiles_usuario" -> respond(ByteReadChannel(profile), HttpStatusCode.OK, json)
                else -> error("Unexpected request: ${request.method} ${request.url}")
            }
        })

        fun paths() = requests.map { it.url.encodedPath }
        fun logouts() = requests.filter { it.url.encodedPath == "/auth/v1/logout" }
        fun claims() = requests.count { it.url.encodedPath == "/rest/v1/rpc/oposibots_claim_oauth_credential" }
        fun appleCalls() = requests.count { it.url.host == "appleid.apple.com" }
    }

    private fun configuration() = testCanonicalConfiguration().copy(appleClientId = "apple-client", appleClientSecret = "apple-secret")

    private fun Application.module(fake: AccessRequestFakeSupabase, upstream: Upstream) {
        install(ContentNegotiation) { json(testJson) }
        installRequestCorrelation()
        installBackendStatusPages()
        installRequestBodyLimit()
        val configuration = configuration()
        val guard = fake.guard()
        val runtime = OposiBotsAuthRuntime(
            configuration = configuration,
            gateway = SupabaseOposiBotsAuthGateway(configuration, testJson, guard, upstream.client),
            rateLimiter = TestAllowAllLimiter,
            json = testJson,
            canonical = CanonicalTestEnv().runtime().canonical,
            accessRequestMailGuard = guard,
        )
        routing { oposiBotsAuthRoutes(runtime) }
    }

    /** [transientRevocation]: lo que revoca la puerta ante un fallo de LECTURA (refresh no revoca nada, MASTER-AUTH-010). */
    private data class SessionRoute(val path: String, val body: String, val transientRevocation: String?, val oauth: Boolean = false)

    private val sessionRoutes = listOf(
        SessionRoute("/v1/auth/login", """{"email":"ada@example.com","password":"S3creta-Larga-9731"}""", "local"),
        SessionRoute("/v1/auth/refresh", """{"refreshToken":"old-refresh-token"}""", null),
        SessionRoute("/v1/auth/email/exchange", """{"code":"confirmation-token-hash"}""", "local"),
        SessionRoute("/v1/auth/oauth/google", """{"idToken":"$ID_TOKEN"}""", "local", oauth = true),
        SessionRoute("/v1/auth/oauth/apple", """{"identityToken":"$ID_TOKEN","authorizationCode":"single-use-code"}""", "local", oauth = true),
    )

    private suspend fun ApplicationTestBuilder.post(path: String, body: String): HttpResponse =
        client.post(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpResponse.jsonBody(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun assertNoSession(body: String, message: String) {
        assertFalse("minted-access" in body || "minted-refresh" in body || "accessToken" in body || "refreshToken" in body, "$message: $body")
    }

    private fun AccessRequestFakeSupabase.gateLookups() = sent.filter { it.call == "GET /rest/v1/$ADMIN_ACCESS_TABLE" }.map { "${it.path}?${it.query}" }

    // ------------------------------------------------------------------------------------------------ verificada: deniega

    @Test
    fun aVerifiedNonApprovedRequestGetsNoSessionOnAnyRouteThatHandsOneOut() {
        for (estado in listOf(ADMIN_ACCESS_ESTADO_PENDIENTE, ADMIN_ACCESS_ESTADO_RECHAZADA)) for (route in sessionRoutes) testApplication {
            val fake = AccessRequestFakeSupabase()
            fake.seed(pendingEmail, userId = UID, estado = estado, verified = true)
            val upstream = Upstream()
            application { module(fake, upstream) }

            val response = post(route.path, route.body)
            val text = response.bodyAsText()
            val message = "${route.path} ($estado)"

            assertEquals(HttpStatusCode.Forbidden, response.status, message)
            assertEquals("FORBIDDEN", Json.parseToJsonElement(text).jsonObject["code"]?.jsonPrimitive?.content, message)
            assertNoSession(text, message)
            // La lectura de la puerta: exactamente esa, por id de usuario, y nada mas (ni borrados ni auditoria).
            assertEquals(listOf(sessionLookup), fake.gateLookups(), message)
            assertEquals(listOf("GET /rest/v1/$ADMIN_ACCESS_TABLE"), fake.calls(), message)
            // Denegacion definitiva: se revocan TODAS las sesiones del usuario con el token recien acuñado.
            val logout = upstream.logouts().single()
            assertEquals("scope=global", logout.url.encodedQuery, message)
            assertEquals("Bearer minted-access", logout.headers[HttpHeaders.Authorization], message)
            // OAuth: ni se consume la credencial ni se canjea el codigo de Apple.
            assertEquals(0, upstream.claims(), message)
            assertEquals(0, upstream.appleCalls(), message)
        }
    }

    /** Se decide antes que el perfil: una cuenta de solicitud verificada sin fila de perfil es FORBIDDEN, no PROFILE_MISSING. */
    @Test
    fun theAccessRequestDenialDoesNotDependOnTheProfileRow() {
        for (profile in listOf("[]", profileRow("bloqueada"))) testApplication {
            val fake = AccessRequestFakeSupabase()
            fake.seed(pendingEmail, userId = UID, verified = true)
            val upstream = Upstream(profile)
            application { module(fake, upstream) }

            val response = post(sessionRoutes[0].path, sessionRoutes[0].body)

            assertEquals(HttpStatusCode.Forbidden, response.status, profile)
            assertEquals("FORBIDDEN", response.jsonBody()["code"]?.jsonPrimitive?.content, profile)
            assertEquals("scope=global", upstream.logouts().single().url.encodedQuery, profile)
        }
    }

    // ------------------------------------------------------------------------------------------------ sin verificar: libera la fila

    private val trustedRoutes get() = sessionRoutes.filter { it.path != "/v1/auth/login" && it.path != "/v1/auth/refresh" }
    private val untrustedRoutes get() = sessionRoutes.filter { it.path == "/v1/auth/login" || it.path == "/v1/auth/refresh" }

    /**
     * Una solicitud SIN verificar no reserva nada frente al titular. Con una sesion de OAuth o de enlace de correo (el
     * titular del buzon o de la identidad), revision 2026-10-14 (DO_NOT_SHIP): ANTES de entregar la sesion se neutraliza la
     * credencial del creador (contrasena aleatoria: las filas anteriores al cambio llevan la suya), se revocan sus DEMAS
     * sesiones con la recien acuñada (`scope=others`, la del titular se conserva), se borra SOLO la fila (la cuenta nunca) y
     * se audita.
     */
    @Test
    fun anUnverifiedRequestIsNeutralisedOtherSessionsRevokedRowRemovedAndTheSessionHandedOutForOAuthAndEmailLink() {
        for (createdAgo in listOf(60L, 7_200L)) for (route in trustedRoutes) testApplication {
            val fake = AccessRequestFakeSupabase()
            val row = fake.seed(pendingEmail, userId = UID, createdAgoSeconds = createdAgo, accountConfirmed = true)
            val upstream = Upstream()
            application { module(fake, upstream) }
            val message = "${route.path} (hace $createdAgo s)"

            val response = post(route.path, route.body)
            val body = response.jsonBody()

            assertEquals(HttpStatusCode.OK, response.status, "$message: $body")
            val session = if (route.oauth) body.getValue("session").jsonObject else body
            assertEquals("minted-access", session["accessToken"]?.jsonPrimitive?.content, message)
            assertEquals(
                listOf(
                    "GET /rest/v1/$ADMIN_ACCESS_TABLE",
                    "GET /rest/v1/roles_usuario",
                    "PUT /auth/v1/admin/users/$UID",
                    "DELETE /rest/v1/$ADMIN_ACCESS_TABLE",
                    "POST /rest/v1/auditoria_log",
                ),
                fake.calls(),
                message,
            )
            // La contrasena del creador ya no vale: solo la contrasena (ni baneo ni confirmacion), aleatoria del servidor.
            val account = fake.accounts.getValue(UID)
            assertEquals(1, account.passwordChanges.size, message)
            assertFalse(account.password == AccessRequestFakeSupabase.ATTACKER_PASSWORD, message)
            assertEquals(setOf("password"), fake.adminPuts(UID).single().keys, message)
            // El borrado esta CONDICIONADO a que siga sin verificar y no aprobada, y es de ESA fila.
            val delete = fake.sent[3]
            assertEquals("eq.${row.id}", delete.param("id"), message)
            assertEquals("is.null", delete.param("correo_verificado_en"), message)
            assertEquals("neq.aprobada", delete.param("estado"), message)
            assertTrue(fake.rows.isEmpty(), message)
            // La cuenta NUNCA se borra desde la puerta de sesion.
            assertTrue(UID in fake.accounts, message)
            assertTrue(fake.sent.none { it.call == "DELETE /auth/v1/admin/users/$UID" }, message)
            val audit = fake.audits().single()
            assertEquals(ACTION_ACCESS_REQUEST_EXPIRED, audit["accion"]!!.jsonPrimitive.content, message)
            val despues = audit["despues"]!!.jsonObject
            assertEquals(ACCESS_REQUEST_CREDENTIAL_NEUTRALISED_MOTIVE, despues["motivo"]!!.jsonPrimitive.content, message)
            assertEquals(AccessRequestReleaseMotive.SESSION_OWNER.wire, despues["origen"]!!.jsonPrimitive.content, message)
            assertEquals("true", despues["credencialNeutralizada"]!!.jsonPrimitive.content, message)
            assertEquals("false", despues["cuentaEliminada"]!!.jsonPrimitive.content, message)
            // Las DEMAS sesiones (las del creador) se revocan con la recien acuñada; la del titular sigue.
            val logout = upstream.logouts().single()
            assertEquals("scope=others", logout.url.encodedQuery, message)
            assertEquals("Bearer minted-access", logout.headers[HttpHeaders.Authorization], message)
        }
    }

    /**
     * Si la limpieza de una sesion del titular falla (contrasena, revocacion de las demas sesiones o borrado de la fila), la
     * sesion NO se entrega: 503 y revocacion global (nunca una sesion entregada con la credencial del creador viva).
     */
    @Test
    fun aFailedCleanupOnATrustedSessionIsA503WithGlobalRevokeAndNoSession() {
        val failures = listOf<Pair<String, (AccessRequestFakeSupabase.Sent) -> HttpStatusCode?>>(
            "contrasena" to { if (it.call == "PUT /auth/v1/admin/users/$UID") HttpStatusCode.InternalServerError else null },
            "borrado de la fila" to { if (it.call == "DELETE /rest/v1/$ADMIN_ACCESS_TABLE") HttpStatusCode.ServiceUnavailable else null },
        )
        for ((name, failure) in failures) for (route in trustedRoutes) testApplication {
            val fake = AccessRequestFakeSupabase()
            fake.seed(pendingEmail, userId = UID, accountConfirmed = true)
            fake.override = failure
            val upstream = Upstream()
            application { module(fake, upstream) }
            val message = "${route.path} ($name)"

            val response = post(route.path, route.body)
            val text = response.bodyAsText()

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status, "$message: $text")
            assertEquals("UPSTREAM_UNAVAILABLE", Json.parseToJsonElement(text).jsonObject["code"]?.jsonPrimitive?.content, message)
            assertNoSession(text, message)
            assertTrue(fake.rows.isNotEmpty(), message)
            assertTrue(fake.audits().isEmpty(), message)
            assertEquals("scope=global", upstream.logouts().last().url.encodedQuery, message)
            assertEquals(0, upstream.claims(), message)
        }
        // La revocacion de las demas sesiones falla: igual.
        for (route in trustedRoutes) testApplication {
            val fake = AccessRequestFakeSupabase()
            fake.seed(pendingEmail, userId = UID, accountConfirmed = true)
            val upstream = Upstream(othersLogoutStatus = HttpStatusCode.InternalServerError)
            application { module(fake, upstream) }

            val response = post(route.path, route.body)

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status, route.path)
            assertNoSession(response.bodyAsText(), route.path)
            assertTrue(fake.rows.isNotEmpty(), route.path)
            assertEquals(listOf("scope=others", "scope=global"), upstream.logouts().map { it.url.encodedQuery }, route.path)
        }
    }

    /**
     * H1: una sesion de CONTRASENA (o refresh) sobre una solicitud sin verificar con la cuenta confirmada: la contrasena es
     * la del creador de la solicitud. 403 FORBIDDEN, revocacion global, contrasena del creador sustituida por una aleatoria
     * del servidor, fila borrada (la cuenta se queda para el titular) y auditoria `credencial_neutralizada`.
     */
    @Test
    fun aPasswordOrRefreshSessionOnAnUnverifiedRequestIsDeniedAndTheCreatorCredentialIsNeutralised() {
        for (route in untrustedRoutes) testApplication {
            val fake = AccessRequestFakeSupabase()
            val row = fake.seed(pendingEmail, userId = UID, accountConfirmed = true)
            val upstream = Upstream()
            application { module(fake, upstream) }
            val message = route.path

            val response = post(route.path, route.body)
            val text = response.bodyAsText()

            assertEquals(HttpStatusCode.Forbidden, response.status, "$message: $text")
            assertEquals("FORBIDDEN", Json.parseToJsonElement(text).jsonObject["code"]?.jsonPrimitive?.content, message)
            assertNoSession(text, message)
            assertEquals(
                listOf(
                    "GET /rest/v1/$ADMIN_ACCESS_TABLE",
                    "GET /rest/v1/roles_usuario",
                    "PUT /auth/v1/admin/users/$UID",
                    "DELETE /rest/v1/$ADMIN_ACCESS_TABLE",
                    "POST /rest/v1/auditoria_log",
                ),
                fake.calls(),
                message,
            )
            val account = fake.accounts.getValue(UID)
            assertEquals(1, account.passwordChanges.size, message)
            assertFalse(account.password == AccessRequestFakeSupabase.ATTACKER_PASSWORD, message)
            assertEquals(setOf("password"), fake.adminPuts(UID).single().keys, "solo la contrasena: ni baneo ni confirmacion ($message)")
            assertTrue(fake.rows.none { it.id == row.id }, message)
            assertTrue(UID in fake.accounts, "la cuenta NO se borra: es del titular del buzon ($message)")
            val despues = fake.audits().single()["despues"]!!.jsonObject
            assertEquals(ACCESS_REQUEST_CREDENTIAL_NEUTRALISED_MOTIVE, despues["motivo"]!!.jsonPrimitive.content, message)
            assertEquals(AccessRequestReleaseMotive.SESSION_PASSWORD_GRANT.wire, despues["origen"]!!.jsonPrimitive.content, message)
            assertEquals("true", despues["credencialNeutralizada"]!!.jsonPrimitive.content, message)
            assertEquals("false", despues["cuentaEliminada"]!!.jsonPrimitive.content, message)
            assertFalse(account.password!! in fake.audits().toString(), message)
            val logout = upstream.logouts().single()
            assertEquals("scope=global", logout.url.encodedQuery, message)
            assertEquals("Bearer minted-access", logout.headers[HttpHeaders.Authorization], message)
        }
    }

    /** H1 con roles: la cuenta no se toca (ni contrasena ni fila), pero la sesion de contrasena se deniega igual. */
    @Test
    fun aPasswordSessionOnAnUnverifiedRequestWhoseAccountHasRolesIsDeniedWithoutTouchingIt() {
        for (route in untrustedRoutes) testApplication {
            val fake = AccessRequestFakeSupabase()
            val row = fake.seed(pendingEmail, userId = UID, accountConfirmed = true, roles = setOf("aaaaaaaa-0000-4000-8000-00000000000b"))
            val upstream = Upstream()
            application { module(fake, upstream) }

            val response = post(route.path, route.body)

            assertEquals(HttpStatusCode.Forbidden, response.status, route.path)
            assertEquals(listOf("GET /rest/v1/$ADMIN_ACCESS_TABLE", "GET /rest/v1/roles_usuario"), fake.calls(), route.path)
            assertEquals(listOf(row), fake.rows.toList(), route.path)
            assertTrue(fake.accounts.getValue(UID).passwordChanges.isEmpty(), route.path)
            assertEquals("scope=global", upstream.logouts().single().url.encodedQuery, route.path)
        }
    }

    /**
     * H1 de punta a punta: el atacante pide acceso con el correo de la victima (cuenta sin confirmar con SU contrasena); la
     * victima confirma la cuenta por otra via (enlace o Google); el atacante inicia sesion con su contrasena -> 403, su
     * contrasena ya no vale y la fila desaparece. Un segundo intento con la contrasena vieja contra GoTrue ya no tendria exito.
     */
    @Test
    fun victimConfirmsThenAttackerPasswordLoginIsDeniedAndThePasswordIsRotated() = testApplication {
        val fake = AccessRequestFakeSupabase()
        fake.seed(pendingEmail, userId = UID)
        fake.accounts.getValue(UID).confirmed = true // la victima pulso el enlace / enlazo su identidad
        val upstream = Upstream()
        application { module(fake, upstream) }

        val attacker = post("/v1/auth/login", """{"email":"$pendingEmail","password":"${AccessRequestFakeSupabase.ATTACKER_PASSWORD}"}""")

        assertEquals(HttpStatusCode.Forbidden, attacker.status)
        assertNoSession(attacker.bodyAsText(), "atacante")
        assertTrue(fake.rows.isEmpty())
        assertFalse(fake.accounts.getValue(UID).password == AccessRequestFakeSupabase.ATTACKER_PASSWORD)
        assertEquals("scope=global", upstream.logouts().single().url.encodedQuery)
    }

    /**
     * H1 + revision 2026-10-14: la victima entra con Google sobre esa cuenta (titular de la identidad) -> sesion entregada y
     * fila borrada, PERO antes la contrasena del atacante se sustituye por una aleatoria y sus demas sesiones se revocan
     * (`scope=others`): el atacante ya no puede entrar con su contrasena ni seguir con una sesion acuñada antes.
     */
    @Test
    fun victimOAuthOnAnUnverifiedRequestIsAllowedAfterRotatingTheAttackerPasswordAndRevokingOtherSessions() = testApplication {
        val fake = AccessRequestFakeSupabase()
        fake.seed(pendingEmail, userId = UID, accountConfirmed = true)
        val upstream = Upstream()
        application { module(fake, upstream) }

        val response = post("/v1/auth/oauth/google", """{"idToken":"$ID_TOKEN"}""")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals("minted-access", response.jsonBody().getValue("session").jsonObject["accessToken"]?.jsonPrimitive?.content)
        assertTrue(fake.rows.isEmpty())
        val account = fake.accounts.getValue(UID)
        assertEquals(1, account.passwordChanges.size, "la contrasena del atacante se rota")
        assertFalse(account.password == AccessRequestFakeSupabase.ATTACKER_PASSWORD)
        assertEquals(AccessRequestReleaseMotive.SESSION_OWNER.wire, fake.audits().single()["despues"]!!.jsonObject["origen"]!!.jsonPrimitive.content)
        assertEquals(listOf("scope=others"), upstream.logouts().map { it.url.encodedQuery })
        assertEquals("Bearer minted-access", upstream.logouts().single().headers[HttpHeaders.Authorization])
        // El atacante intenta entrar despues con SU contrasena directamente contra GoTrue (el falso aplica la contrasena).
        val direct = fake.client.post("https://example.supabase.co/auth/v1/token?grant_type=password") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$pendingEmail","password":"${AccessRequestFakeSupabase.ATTACKER_PASSWORD}"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, direct.status)
    }

    /** La puerta sola recibe el tipo de concesion de cada ruta (H1): contrasena, refresh, enlace de correo y OAuth. */
    @Test
    fun eachRoutePassesItsGrantTypeToTheGate() {
        val expected = mapOf(
            "/v1/auth/login" to AccessRequestSessionGrant.PASSWORD,
            "/v1/auth/refresh" to AccessRequestSessionGrant.REFRESH,
            "/v1/auth/email/exchange" to AccessRequestSessionGrant.EMAIL_LINK,
            "/v1/auth/oauth/google" to AccessRequestSessionGrant.OAUTH,
            "/v1/auth/oauth/apple" to AccessRequestSessionGrant.OAUTH,
        )
        for (route in sessionRoutes) testApplication {
            val seen = CopyOnWriteArrayList<AccessRequestSessionGrant>()
            val upstream = Upstream()
            application {
                install(ContentNegotiation) { json(testJson) }
                installRequestCorrelation()
                installBackendStatusPages()
                installRequestBodyLimit()
                val configuration = configuration()
                val runtime = OposiBotsAuthRuntime(
                    configuration = configuration,
                    gateway = SupabaseOposiBotsAuthGateway(configuration, testJson, { _, grant -> seen += grant; AccessRequestGateDecision.Allow }, upstream.client),
                    rateLimiter = TestAllowAllLimiter,
                    json = testJson,
                    canonical = CanonicalTestEnv().runtime().canonical,
                    accessRequestMailGuard = NoAccessRequestsMailGuard,
                )
                routing { oposiBotsAuthRoutes(runtime) }
            }

            assertEquals(HttpStatusCode.OK, post(route.path, route.body).status, route.path)
            assertEquals(listOf(expected.getValue(route.path)), seen.toList(), route.path)
        }
        assertFalse(AccessRequestSessionGrant.PASSWORD.trustsMailboxOwner)
        assertFalse(AccessRequestSessionGrant.REFRESH.trustsMailboxOwner)
        assertTrue(AccessRequestSessionGrant.OAUTH.trustsMailboxOwner)
        assertTrue(AccessRequestSessionGrant.EMAIL_LINK.trustsMailboxOwner)
    }

    /** Si entre la lectura y el borrado alguien la verifico, no se borra nada y decide la fila de ahora: se deniega. */
    @Test
    fun anUnverifiedRowThatGetsVerifiedBeforeTheDeleteIsDenied() = testApplication {
        val fake = AccessRequestFakeSupabase()
        val row = fake.seed(pendingEmail, userId = UID, accountConfirmed = true)
        fake.override = { sent ->
            // La verificacion «gana» justo antes del DELETE condicionado.
            if (sent.call == "DELETE /rest/v1/$ADMIN_ACCESS_TABLE") row.verificadoEn = java.time.Instant.now()
            null
        }
        val upstream = Upstream()
        application { module(fake, upstream) }

        // Enlace de correo: el camino que borra la fila y entregaria la sesion.
        val response = post(sessionRoutes[2].path, sessionRoutes[2].body)

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(listOf(row), fake.rows.toList())
        assertTrue(fake.audits().isEmpty())
        assertEquals(listOf("scope=others", "scope=global"), upstream.logouts().map { it.url.encodedQuery })
    }

    // ------------------------------------------------------------------------------------------------ no deniega

    @Test
    fun anApprovedRequestNoRequestOrSomeoneElsesRequestKeepsTheUsualSession() {
        val cases = listOf<Pair<String, (AccessRequestFakeSupabase) -> Unit>>(
            "sin solicitud" to {},
            "aprobada" to { it.seed(pendingEmail, userId = UID, estado = ADMIN_ACCESS_ESTADO_APROBADA, verified = true) },
            "verificada de otro usuario" to { it.seed("other@example.com", userId = OTHER_UID, verified = true) },
        )
        for ((name, seed) in cases) for (route in sessionRoutes) testApplication {
            val fake = AccessRequestFakeSupabase()
            seed(fake)
            val upstream = Upstream()
            application { module(fake, upstream) }

            val response = post(route.path, route.body)
            val body = response.jsonBody()
            val message = "${route.path} ($name)"

            assertEquals(HttpStatusCode.OK, response.status, message)
            val session = if (route.oauth) body.getValue("session").jsonObject else body
            assertEquals("minted-access", session["accessToken"]?.jsonPrimitive?.content, message)
            assertEquals("minted-refresh", session["refreshToken"]?.jsonPrimitive?.content, message)
            // La puerta pregunta SIEMPRE, tambien cuando deja pasar.
            assertEquals(listOf(sessionLookup), fake.gateLookups(), message)
            assertEquals(emptyList(), upstream.logouts().map { it.url.encodedQuery }, message)
            assertEquals(if (route.oauth) 1 else 0, upstream.claims(), message)
        }
    }

    /** La puerta de siempre sigue igual: una cuenta bloqueada sin solicitud es ACCOUNT_BLOCKED. */
    @Test
    fun aBlockedAccountWithoutRequestIsStillAccountBlocked() = testApplication {
        val fake = AccessRequestFakeSupabase()
        val upstream = Upstream(profileRow("bloqueada"))
        application { module(fake, upstream) }

        val response = post(sessionRoutes[0].path, sessionRoutes[0].body)

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("ACCOUNT_BLOCKED", response.jsonBody()["code"]?.jsonPrimitive?.content)
        assertEquals("scope=global", upstream.logouts().single().url.encodedQuery)
    }

    // ------------------------------------------------------------------------------------------------ falla cerrado

    /**
     * M-1: la limpieza de una DENEGACION (contrasena o refresh sobre una solicitud sin verificar) es de mejor esfuerzo y va
     * despues de la lectura: si falla, la sesion se deniega igual (403 y revocacion global), nunca un 503 que el cliente
     * reintente hasta que una lectura posterior la deje pasar.
     */
    @Test
    fun aFailedCleanupOnADeniedSessionStillDeniesWithGlobalRevoke() {
        val failures = listOf<Pair<String, (AccessRequestFakeSupabase.Sent) -> HttpStatusCode?>>(
            "contrasena" to { if (it.call == "PUT /auth/v1/admin/users/$UID") HttpStatusCode.InternalServerError else null },
            "borrado de la fila" to { if (it.call == "DELETE /rest/v1/$ADMIN_ACCESS_TABLE") HttpStatusCode.ServiceUnavailable else null },
        )
        for ((name, failure) in failures) for (route in untrustedRoutes) testApplication {
            val fake = AccessRequestFakeSupabase()
            fake.seed(pendingEmail, userId = UID, accountConfirmed = true)
            fake.override = failure
            val upstream = Upstream()
            application { module(fake, upstream) }
            val message = "${route.path} ($name)"

            val response = post(route.path, route.body)
            val text = response.bodyAsText()

            assertEquals(HttpStatusCode.Forbidden, response.status, "$message: $text")
            assertEquals("FORBIDDEN", Json.parseToJsonElement(text).jsonObject["code"]?.jsonPrimitive?.content, message)
            assertNoSession(text, message)
            assertEquals(listOf("scope=global"), upstream.logouts().map { it.url.encodedQuery }, message)
        }
    }

    @Test
    fun aFailedLookupAnswers503AndHandsOutNoSession() {
        val failures = listOf<Pair<String, (AccessRequestFakeSupabase.Sent) -> HttpStatusCode?>>(
            "lectura" to { if (it.call == "GET /rest/v1/$ADMIN_ACCESS_TABLE") HttpStatusCode.InternalServerError else null },
            "lectura de roles" to { if (it.call == "GET /rest/v1/roles_usuario") HttpStatusCode.InternalServerError else null },
        )
        for ((name, failure) in failures) for (route in sessionRoutes) testApplication {
            val fake = AccessRequestFakeSupabase()
            fake.seed(pendingEmail, userId = UID, accountConfirmed = true)
            fake.override = failure
            val upstream = Upstream()
            application { module(fake, upstream) }

            val response = post(route.path, route.body)
            val text = response.bodyAsText()
            val message = "${route.path} ($name)"

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status, message)
            assertEquals("UPSTREAM_UNAVAILABLE", Json.parseToJsonElement(text).jsonObject["code"]?.jsonPrimitive?.content, message)
            assertNoSession(text, message)
            // Fallo transitorio: nunca en global (MASTER-AUTH-010); refresh no revoca nada y el resto solo la sesion acuñada.
            assertEquals(listOfNotNull(route.transientRevocation?.let { "scope=$it" }), upstream.logouts().map { it.url.encodedQuery }, message)
            assertEquals(0, upstream.claims(), message)
            assertEquals(0, upstream.appleCalls(), message)
        }
    }

    /** Un almacen guionizado para las pruebas de reintento y de plazo (la guarda REAL encima). */
    private open class ScriptedStore : AccessRequestReservationStore {
        override suspend fun nonApprovedByEmail(email: String): List<AccessRequestReservation> = error("no interviene")
        override suspend fun nonApprovedByUser(userId: String): AccessRequestReservation? = error("no interviene")
        override suspend fun authAccount(usuarioId: String): AuthAccountState? = error("no interviene")
        override suspend fun hasAnyRole(usuarioId: String): Boolean = error("no interviene")
        override suspend fun deleteAccount(usuarioId: String) = error("no interviene")
        override suspend fun deleteUnverifiedRequest(id: String): Boolean = error("no interviene")
        override suspend fun neutraliseCredential(usuarioId: String, revokeSessions: Boolean) = error("no interviene")
        override suspend fun audit(accion: String, registroId: String, despues: JsonObject) = error("no interviene")
    }

    private fun verifiedPending() = AccessRequestReservation("77777777-7777-4777-8777-000000000001", UID, "pendiente", "2026-10-14T10:00:00Z", "2026-10-14T09:59:00Z")

    /** MASTER2-AUTH-003: en refresh la lectura de la puerta se reintenta; un fallo pasajero de ESTA lectura tambien. */
    @Test
    fun aTransientLookupFailureOnRefreshIsRetriedAndThenDecides() = runBlocking {
        val calls = AtomicInteger()
        val upstream = Upstream()
        val flaky = PostgrestAccessRequestMailGuard(
            store = object : ScriptedStore() {
                override suspend fun nonApprovedByUser(userId: String): AccessRequestReservation? =
                    if (calls.incrementAndGet() == 1) error("Supabase GET -> 503") else verifiedPending()
            },
            emailRef = configuration()::authLogEmailRef,
        )
        val gateway = SupabaseOposiBotsAuthGateway(configuration(), testJson, flaky, upstream.client)

        val denied = assertFailsWith<BackendDomainException> { gateway.refresh(OposiBotsRefreshRequest("old-refresh-token"), context) }

        assertEquals(CanonicalErrorCode.FORBIDDEN.name, denied.errorCode)
        assertEquals(2, calls.get())
        assertEquals(listOf("scope=global"), upstream.logouts().map { it.url.encodedQuery })
    }

    /**
     * MASTER3-AUTH-005: si la lectura de la puerta se cuelga, el limite por intento y el plazo global de refresh la cortan
     * igual que a la del perfil (la lectura es cancelable): 503 dentro del plazo y sin revocar nada. Reloj real.
     */
    @Test
    fun aHangingLookupOnRefreshIsCutByTheGateDeadlineAndRevokesNothing() = runBlocking {
        val cancelled = AtomicInteger()
        val upstream = Upstream()
        val hanging = PostgrestAccessRequestMailGuard(
            store = object : ScriptedStore() {
                override suspend fun nonApprovedByUser(userId: String): AccessRequestReservation? {
                    try {
                        delay(60_000)
                        return null
                    } catch (cancellation: CancellationException) {
                        cancelled.incrementAndGet()
                        throw cancellation
                    }
                }
            },
            emailRef = configuration()::authLogEmailRef,
        )
        val gateway = SupabaseOposiBotsAuthGateway(configuration(), testJson, hanging, upstream.client)

        val started = System.nanoTime()
        val failure = assertFailsWith<OposiBotsAuthProblem> { gateway.refresh(OposiBotsRefreshRequest("old-refresh-token"), context) }
        val elapsed = (System.nanoTime() - started) / 1_000_000

        assertEquals(HttpStatusCode.ServiceUnavailable, failure.status, "plazo agotado = transitorio, nunca fail-open")
        assertTrue(elapsed < REFRESH_GATE_DEADLINE_MS + 2_000, "acotado por el plazo global: $elapsed ms")
        assertTrue(cancelled.get() >= 2, "cada intento colgado se cancela: ${cancelled.get()}")
        assertTrue(upstream.logouts().isEmpty(), "refresh no revoca por un fallo transitorio (MASTER-AUTH-010)")
    }

    // ------------------------------------------------------------------------------------------------ M-1: efectos fuera de los reintentos

    /** El gateway real de refresh sobre la guarda REAL con un almacen envuelto (para frenar o espiar sus escrituras). */
    private fun refreshGateway(store: AccessRequestReservationStore, upstream: Upstream, profiles: CanonicalProfileDirectory? = null) =
        SupabaseOposiBotsAuthGateway(
            configuration(),
            testJson,
            PostgrestAccessRequestMailGuard(store = store, emailRef = configuration()::authLogEmailRef),
            upstream.client,
            profiles,
        )

    /**
     * M-1: en refresh la lectura de la puerta se reintenta. Antes la decision ESCRIBIA dentro de esa lectura: un primer
     * intento que neutralizaba y borraba la fila y luego fallaba (aqui, el perfil) dejaba al segundo intento sin fila, y la
     * denegacion se convertia en una sesion entregada. Ahora la lectura no escribe: el segundo intento vuelve a decidir
     * denegar y la limpieza se hace UNA vez, despues.
     */
    @Test
    fun aTransientProfileFailureOnRefreshNeverTurnsADenyIntoAnAllow() = runBlocking {
        val fake = AccessRequestFakeSupabase()
        val row = fake.seed(pendingEmail, userId = UID, accountConfirmed = true)
        val upstream = Upstream()
        val wrote = CompletableDeferred<Unit>()
        val delegate = fake.accessGateway()
        val store = object : AccessRequestReservationStore by delegate {
            override suspend fun deleteUnverifiedRequest(id: String): Boolean = delegate.deleteUnverifiedRequest(id).also { wrote.complete(Unit) }
        }
        val profileCalls = AtomicInteger()
        val profiles = CanonicalProfileDirectory { id ->
            if (profileCalls.incrementAndGet() == 1) {
                // Primer intento: espera (acotado, por debajo del limite por intento) a que la puerta escriba algo y falla.
                withTimeoutOrNull(1_000) { wrote.await() }
                throw OposiBotsAuthProblem.unavailable()
            }
            CanonicalUserIdentity(id, AccountStatus.ACTIVA, onboardingCompleted = true)
        }

        val denied = assertFailsWith<BackendDomainException> { refreshGateway(store, upstream, profiles).refresh(OposiBotsRefreshRequest("old-refresh-token"), context) }

        assertEquals(CanonicalErrorCode.FORBIDDEN.name, denied.errorCode)
        assertTrue(profileCalls.get() >= 2, "hubo reintento: ${profileCalls.get()}")
        assertTrue(fake.rows.none { it.id == row.id }, "la limpieza se hizo, despues de la lectura")
        assertEquals(1, fake.accounts.getValue(UID).passwordChanges.size, "y una sola vez")
        assertEquals(listOf("scope=global"), upstream.logouts().map { it.url.encodedQuery })
    }

    /**
     * M-1: una limpieza LENTA (mas que el limite por intento de la lectura) ya no la corta el limite ni la repite el
     * reintento: corre fuera, sin poder cancelarse, y la sesion se deniega (403, global), nunca un 503 ni una entrega.
     */
    @Test
    fun aSlowCleanupOnRefreshCompletesOutsideTheRetriesAndStillDenies() = runBlocking {
        val fake = AccessRequestFakeSupabase()
        val row = fake.seed(pendingEmail, userId = UID, accountConfirmed = true)
        val upstream = Upstream()
        val delegate = fake.accessGateway()
        val neutralisations = AtomicInteger()
        val store = object : AccessRequestReservationStore by delegate {
            override suspend fun neutraliseCredential(usuarioId: String, revokeSessions: Boolean) {
                neutralisations.incrementAndGet()
                delay(REFRESH_GATE_ATTEMPT_TIMEOUT_MS + 500)
                delegate.neutraliseCredential(usuarioId, revokeSessions)
            }
        }

        val denied = assertFailsWith<BackendDomainException> { refreshGateway(store, upstream).refresh(OposiBotsRefreshRequest("old-refresh-token"), context) }

        assertEquals(CanonicalErrorCode.FORBIDDEN.name, denied.errorCode)
        assertEquals(1, neutralisations.get())
        assertTrue(fake.rows.none { it.id == row.id })
        assertFalse(fake.accounts.getValue(UID).password == AccessRequestFakeSupabase.ATTACKER_PASSWORD)
        assertEquals(listOf("scope=global"), upstream.logouts().map { it.url.encodedQuery })
    }

    /**
     * M-1: si quien llama se cancela (cliente desconectado) mientras corre la limpieza, la limpieza termina igual (no se
     * queda a medias: contrasena rotada, fila borrada, revocacion global) y no sale ninguna sesion.
     */
    @Test
    fun aCancellationDuringTheCleanupOnRefreshNeitherHandsOutASessionNorLeavesItHalfDone() = runBlocking {
        val fake = AccessRequestFakeSupabase()
        val row = fake.seed(pendingEmail, userId = UID, accountConfirmed = true)
        val upstream = Upstream()
        val delegate = fake.accessGateway()
        val started = CompletableDeferred<Unit>()
        val store = object : AccessRequestReservationStore by delegate {
            override suspend fun neutraliseCredential(usuarioId: String, revokeSessions: Boolean) {
                started.complete(Unit)
                delay(300)
                delegate.neutraliseCredential(usuarioId, revokeSessions)
            }
        }
        val gateway = refreshGateway(store, upstream)

        // Ambito propio: el fallo de la llamada cancelada no debe tumbar la prueba.
        val call = CoroutineScope(SupervisorJob() + Dispatchers.Default).async { gateway.refresh(OposiBotsRefreshRequest("old-refresh-token"), context) }
        started.await()
        call.cancel()
        val outcome = runCatching { call.await() }

        assertTrue(outcome.isFailure, "ninguna sesion: $outcome")
        assertTrue(fake.rows.none { it.id == row.id }, "la limpieza termino")
        assertFalse(fake.accounts.getValue(UID).password == AccessRequestFakeSupabase.ATTACKER_PASSWORD)
        assertEquals(listOf("scope=global"), upstream.logouts().map { it.url.encodedQuery })
    }

    // ------------------------------------------------------------------------------------------------ la puerta sola

    @Test
    fun theSessionLookupIsKeyedByTheLowercasedUserIdAndNothingElse() {
        assertEquals(
            "/rest/v1/solicitudes_acceso_admin?select=id,usuario_id,estado,correo_verificado_en,creado_en,intentos_verificacion&usuario_id=eq.$UID&estado=neq.aprobada&limit=1",
            accessRequestSessionPath(UID.uppercase()),
        )
    }

    @Test
    fun aUserIdThatIsNotAUuidNeverReachesTheUpstreamAndFailsClosed() = runTest {
        for (userId in listOf("", "not-a-uuid", "$UID&estado=eq.aprobada", "$UID,or=(estado.eq.aprobada)")) {
            val fake = AccessRequestFakeSupabase()

            val problem = assertFailsWith<OposiBotsAuthProblem> { fake.guard().decide(userId, AccessRequestSessionGrant.PASSWORD) }

            assertEquals(HttpStatusCode.ServiceUnavailable, problem.status, userId)
            assertEquals(emptyList(), fake.calls(), userId)
        }
    }

    // ------------------------------------------------------------------------------------------------ contrasena nueva

    private fun registerBody(email: String, password: String) =
        """{"email":"$email","password":${Json.encodeToString(kotlinx.serialization.serializer<String>(), password)}}"""

    /** Fuera de `^[\x20-\x7E]{12,72}$`: cortas, largas, con cualquier caracter no ASCII o de control. */
    private val rejectedPasswords = listOf(
        "a".repeat(11),
        "a".repeat(73),
        "a".repeat(100),
        "a".repeat(128),
        "ñ".repeat(12), // 12 caracteres pero no ASCII
        "S3creta-Larga-ñ",
        "S3creta-Larga-😀", // emoji
        "S3creta-Larga- x", // espacio no ASCII
        "S3creta\tLarga-9", // tabulador (control)
        "S3creta-Larga-\u007F", // DEL (control, fuera del rango imprimible)
    )

    /** Dentro: los extremos de longitud y de rango (espacio y tilde incluidos). */
    private val acceptedPasswords = listOf(
        "a".repeat(12),
        "a".repeat(72),
        " " + "b".repeat(10) + "~",
        "~".repeat(12),
        "Contraseña".replace("ñ", "n") + " ok!",
    )

    /**
     * El registro de un correo RETENIDO contesta 202 sin llegar a GoTrue: si la regla local no fuera la de GoTrue, una
     * contrasena distinguiria retenido de libre. Con la regla unica los dos reciben el MISMO 400 local, antes de la guarda.
     */
    @Test
    fun aRejectedNewPasswordIsTheSameValidationErrorForAHeldAndAFreeEmail() {
        for (password in rejectedPasswords) testApplication {
            val fake = AccessRequestFakeSupabase()
            fake.seed(pendingEmail, userId = UID, verified = true)
            val upstream = Upstream()
            application { module(fake, upstream) }
            val label = "${password.length} caracteres: ${password.map { it.code }}"

            val held = post("/v1/auth/register", registerBody(pendingEmail, password))
            val free = post("/v1/auth/register", registerBody("free@example.com", password))
            val heldBody = held.jsonBody()
            val freeBody = free.jsonBody()

            assertEquals(HttpStatusCode.BadRequest, held.status, label)
            assertEquals(HttpStatusCode.BadRequest, free.status, label)
            assertEquals("VALIDATION", heldBody["code"]?.jsonPrimitive?.content, label)
            assertTrue("INVALID_PASSWORD" in heldBody.toString(), "$label: $heldBody")
            // Lo mismo salvo el id de peticion, distinto en cada llamada por construccion.
            assertEquals(JsonObject(freeBody - "requestId"), JsonObject(heldBody - "requestId"), label)
            // Ni la guarda ni GoTrue: se decide antes de las dos.
            assertEquals(emptyList(), fake.calls(), label)
            assertEquals(emptyList(), upstream.paths(), label)
        }
    }

    @Test
    fun anAcceptedNewPasswordReachesGoTrueForAFreeEmail() {
        for (password in acceptedPasswords) testApplication {
            val fake = AccessRequestFakeSupabase()
            fake.seed(pendingEmail, userId = UID, verified = true)
            val upstream = Upstream()
            application { module(fake, upstream) }

            val held = post("/v1/auth/register", registerBody(pendingEmail, password))
            val free = post("/v1/auth/register", registerBody("free@example.com", password))

            assertEquals(HttpStatusCode.Accepted, held.status, password)
            assertEquals(HttpStatusCode.Accepted, free.status, password)
            assertEquals(held.bodyAsText(), free.bodyAsText())
            // Solo el correo libre llega a GoTrue; el retenido se queda en la guarda.
            assertEquals(listOf("/auth/v1/signup"), upstream.paths(), password)
        }
    }

    @Test
    fun resetPasswordAppliesTheSameRuleBeforeSpendingTheToken() {
        for (password in rejectedPasswords) testApplication {
            val upstream = Upstream()
            application { module(AccessRequestFakeSupabase(), upstream) }

            val response = post(
                "/v1/auth/reset-password",
                """{"resetToken":"recovery-token-hash","newPassword":${Json.encodeToString(kotlinx.serialization.serializer<String>(), password)}}""",
            )
            val body = response.jsonBody()

            assertEquals(HttpStatusCode.BadRequest, response.status, password)
            assertEquals("VALIDATION", body["code"]?.jsonPrimitive?.content, password)
            assertTrue("INVALID_PASSWORD" in body.toString(), body.toString())
            // El token de recuperacion (un solo uso) no se gasta en una contrasena que no cumple la regla.
            assertEquals(emptyList(), upstream.paths(), password)
        }
        for (password in acceptedPasswords) testApplication {
            val upstream = Upstream()
            application { module(AccessRequestFakeSupabase(), upstream) }

            val response = post(
                "/v1/auth/reset-password",
                """{"resetToken":"recovery-token-hash","newPassword":${Json.encodeToString(kotlinx.serialization.serializer<String>(), password)}}""",
            )

            assertEquals(HttpStatusCode.NoContent, response.status, password)
            assertEquals(listOf("/auth/v1/verify", "/auth/v1/user", "/auth/v1/logout"), upstream.paths(), password)
        }
    }

    /** El login no fija contrasena: conserva su regla (12..128 caracteres, cualquier caracter) y sigue llegando a GoTrue. */
    @Test
    fun loginKeepsItsOwnLenientPasswordRule() {
        for (password in listOf("a".repeat(100), "a".repeat(128), "ñ".repeat(20), "S3creta-Larga-ñ")) testApplication {
            val upstream = Upstream()
            application { module(AccessRequestFakeSupabase(), upstream) }

            val response = post("/v1/auth/login", registerBody("free@example.com", password))

            assertEquals(HttpStatusCode.OK, response.status, password)
            assertEquals("/auth/v1/token", upstream.paths().first(), password)
        }
    }

    private companion object {
        const val UID = AccessRequestFakeSupabase.UID_A
        const val OTHER_UID = "44444444-4444-4444-8444-444444444444"

        const val SESSION =
            """{"access_token":"minted-access","refresh_token":"minted-refresh","expires_in":3600,"user":{"id":"$UID","email":"ada@example.com"}}"""

        /** Un id_token con forma de JWT (3 segmentos, `sub` y `exp` futuros); GoTrue falso no lo verifica. */
        val ID_TOKEN: String = run {
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val header = encoder.encodeToString("""{"alg":"none"}""".toByteArray())
            val payload = encoder.encodeToString("""{"sub":"provider-subject","exp":4102444800}""".toByteArray())
            "$header.$payload.signature"
        }

        fun profileRow(estado: String) =
            """[{"usuario_id":"$UID","estado_cuenta":"$estado","onboarding_completado":true}]"""
    }
}
