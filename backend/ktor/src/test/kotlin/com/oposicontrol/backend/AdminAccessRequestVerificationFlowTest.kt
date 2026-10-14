package com.oposicontrol.backend

import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.http.HttpRequest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Propiedad del correo en las solicitudes de acceso (plan del propietario 2026-10-14), de punta a punta: ruta publica ->
 * gateway REAL ([SupabaseAdminAccessRequestGateway]) -> Supabase falso que graba cada peticion ([AccessRequestFakeSupabase]).
 *
 *  - el alta pide a GoTrue EXACTAMENTE un correo (`resend` de tipo signup) y ningun otro;
 *  - `verify` con el codigo bueno: 204, la sesion de GoTrue se revoca en global y no sale nunca, `correo_verificado_en` y
 *    auditoria; cualquier fallo (codigo, correo, sin solicitud, caducada, ya verificada...) es EL MISMO 400;
 *  - `resend-code` es 202 siempre y solo reenvia a una solicitud pendiente, sin verificar y sin caducar;
 *  - aprobar exige la verificacion y nunca confirma el correo (ningun PUT a la API de administracion).
 */
class AdminAccessRequestVerificationFlowTest {
    private val email = "ada@example.com"
    private val password = "S3creta-Larga-No-Repetible-9731"
    private val code = "482915"

    private fun Application.module(fake: AccessRequestFakeSupabase, limiter: OposiBotsAuthRateLimiter = TestAllowAllLimiter) {
        install(ContentNegotiation) { json(testJson) }
        installRequestCorrelation()
        installBackendStatusPages()
        installRequestBodyLimit()
        val runtime = CanonicalTestEnv(limiter = limiter).runtime()
        routing { adminAccessRequestPublicRoute(runtime, fake.accessGateway()) }
    }

    private suspend fun ApplicationTestBuilder.postJson(path: String, body: String): HttpResponse =
        client.post(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.submit(address: String = " Ada@Example.com "): HttpResponse = postJson(
        "/v1/auth/admin-access-requests",
        """{"nombre":"Ada Lovelace","email":"$address","motivo":"Coordino el equipo de contenido."}""",
    )

    private suspend fun ApplicationTestBuilder.verify(address: String = email, value: String = code, chosen: String = password): HttpResponse =
        postJson("/v1/auth/admin-access-requests/verify", """{"email":"$address","code":"$value","password":${JsonPrimitive(chosen)}}""")

    private suspend fun ApplicationTestBuilder.resendCode(address: String = email): HttpResponse =
        postJson("/v1/auth/admin-access-requests/resend-code", """{"email":"$address"}""")

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun <T> capturingAllLogs(block: (ListAppender<ILoggingEvent>) -> T): T {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as LogbackLogger
        val appender = ListAppender<ILoggingEvent>().also { it.start(); root.addAppender(it) }
        return try {
            block(appender)
        } finally {
            root.detachAppender(appender)
        }
    }

    // ------------------------------------------------------------------------------------------------ alta

    @Test
    fun aNewRequestCreatesTheAccountAndTheRowAndAsksForExactlyOneSignupCodeMail() = testApplication {
        val fake = AccessRequestFakeSupabase()
        application { module(fake) }

        val response = submit()

        assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
        assertEquals("""{"estado":"recibida"}""", response.bodyAsText())
        assertEquals(
            listOf(
                "GET /rest/v1/$ADMIN_ACCESS_TABLE",
                "POST /auth/v1/admin/users",
                "POST /rest/v1/$ADMIN_ACCESS_TABLE",
                "POST /auth/v1/otp",
            ),
            fake.calls(),
        )
        // H1: un unico correo, el OTP (plantilla «Magic Link», solo `{{ .Token }}`) sin crear usuario, al correo canonico.
        // NUNCA el «Confirm signup» (resend/signup), cuyo enlace confirmaria la cuenta sin pasar por la verificacion.
        val mail = fake.mails().single()
        assertEquals("/auth/v1/otp", mail.path)
        assertEquals("false", mail.jsonBody()["create_user"]!!.jsonPrimitive.content)
        assertEquals(email, mail.jsonBody()["email"]!!.jsonPrimitive.content)
        assertTrue(fake.sent.none { it.path == "/auth/v1/resend" || it.path == "/auth/v1/signup" })
        // La cuenta nace sin confirmar y la fila sin verificar.
        assertEquals("false", fake.sent[1].jsonBody()["email_confirm"]!!.jsonPrimitive.content)
        val row = fake.rows.single()
        assertEquals(email, row.email)
        assertEquals(null, row.verificadoEn)
        assertFalse(fake.accounts.getValue(row.usuarioId).confirmed)
    }

    @Test
    fun aLiveUnverifiedOrAVerifiedRequestForTheSameEmailCreatesNothingAndSendsNothing() {
        for (verified in listOf(false, true)) testApplication {
            val fake = AccessRequestFakeSupabase()
            fake.seed(email, createdAgoSeconds = 60, verified = verified)
            application { module(fake) }

            val response = submit()

            assertEquals(HttpStatusCode.Accepted, response.status)
            assertEquals("""{"estado":"recibida"}""", response.bodyAsText())
            assertEquals(listOf("GET /rest/v1/$ADMIN_ACCESS_TABLE"), fake.calls(), "verificada=$verified")
        }
    }

    /** Una solicitud sin verificar CADUCADA se limpia con la regla de la guarda y el alta sigue. */
    @Test
    fun anExpiredUnverifiedRequestIsClearedFirstAndTheNewRequestProceeds() = testApplication {
        val fake = AccessRequestFakeSupabase()
        val old = fake.seed(email, createdAgoSeconds = ADMIN_ACCESS_UNVERIFIED_TTL_SECONDS + 60)
        application { module(fake) }

        val response = submit()

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals(
            listOf(
                "GET /rest/v1/$ADMIN_ACCESS_TABLE",
                "GET /auth/v1/admin/users/${old.usuarioId}",
                "GET /rest/v1/roles_usuario",
                "DELETE /auth/v1/admin/users/${old.usuarioId}",
                "POST /rest/v1/auditoria_log",
                "POST /auth/v1/admin/users",
                "POST /rest/v1/$ADMIN_ACCESS_TABLE",
                "POST /auth/v1/otp",
            ),
            fake.calls(),
        )
        val audit = fake.audits().single()
        assertEquals(ACTION_ACCESS_REQUEST_EXPIRED, audit["accion"]!!.jsonPrimitive.content)
        assertEquals(AccessRequestReleaseMotive.EXPIRED.wire, audit["despues"]!!.jsonObject["motivo"]!!.jsonPrimitive.content)
        assertEquals("true", audit["despues"]!!.jsonObject["caducada"]!!.jsonPrimitive.content)
        assertFalse(old.usuarioId in fake.accounts)
        assertEquals(1, fake.rows.size)
    }

    /**
     * Caducada con la cuenta CONFIRMADA y sin roles (H1): la cuenta no se borra (es del titular del buzon), la credencial
     * del creador se neutraliza y la fila se va; el alta nueva choca con la cuenta existente (ALREADY_REGISTERED): no se
     * crea nada ni sale ningun codigo (misma 202).
     */
    @Test
    fun anExpiredRequestWhoseAccountIsConfirmedIsNeutralisedAndNothingNewIsCreated() = testApplication {
        val fake = AccessRequestFakeSupabase()
        val old = fake.seed(email, createdAgoSeconds = ADMIN_ACCESS_UNVERIFIED_TTL_SECONDS + 60, accountConfirmed = true)
        application { module(fake) }

        assertEquals(HttpStatusCode.Accepted, submit().status)

        assertTrue(old.usuarioId in fake.accounts)
        assertEquals(1, fake.accounts.size)
        assertEquals(1, fake.accounts.getValue(old.usuarioId).passwordChanges.size)
        assertTrue(fake.rows.isEmpty())
        assertTrue(fake.sent.none { it.path.startsWith("/auth/v1/admin/users/") && it.method.value == "DELETE" })
        assertTrue(fake.mails().isEmpty())
    }

    /** Caducada con roles: nunca se toca y no se crea nada. */
    @Test
    fun anExpiredRequestWhoseAccountHasRolesIsNotClearedAndNothingIsCreated() = testApplication {
        val fake = AccessRequestFakeSupabase()
        val old = fake.seed(email, createdAgoSeconds = ADMIN_ACCESS_UNVERIFIED_TTL_SECONDS + 60, roles = setOf("aaaaaaaa-0000-4000-8000-00000000000b"))
        application { module(fake) }

        assertEquals(HttpStatusCode.Accepted, submit().status)

        assertTrue(old.usuarioId in fake.accounts)
        assertTrue(fake.sent.none { it.method.value == "DELETE" || it.method.value == "PUT" || it.call == "POST /auth/v1/admin/users" })
        assertTrue(fake.mails().isEmpty())
    }

    /** Si el correo con el codigo no sale (GoTrue 5xx), la respuesta no cambia: el solicitante puede pedir el reenvio. */
    @Test
    fun aFailedCodeMailDoesNotChangeTheAnswer() = testApplication {
        val fake = AccessRequestFakeSupabase()
        fake.override = { if (it.path == "/auth/v1/otp") HttpStatusCode.BadGateway else null }
        application { module(fake) }

        val response = submit()

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals(1, fake.rows.size)
    }

    // ------------------------------------------------------------------------------------------------ verificar

    @Test
    fun theRightCodeVerifiesRevokesTheSessionAndNeverReturnsIt() {
        val logs = capturingAllLogs { appender ->
            testApplication {
                val fake = AccessRequestFakeSupabase()
                application { module(fake) }
                submit()
                fake.codes[email] = code
                val row = fake.rows.single()
                val before = fake.sent.size

                val response = verify(address = " ADA@example.com ")

                assertEquals(HttpStatusCode.NoContent, response.status, response.bodyAsText())
                assertEquals("", response.bodyAsText())
                assertTrue(response.headers.entries().none { (_, values) -> values.any { "access" in it || "refresh" in it } })
                val calls = fake.sent.drop(before)
                assertEquals(
                    listOf(
                        "GET /rest/v1/$ADMIN_ACCESS_TABLE",
                        "POST /auth/v1/verify",
                        "POST /auth/v1/logout",
                        "PUT /auth/v1/admin/users/${row.usuarioId}",
                        "POST /rest/v1/rpc/$ADMIN_PURGE_USER_SESSIONS_RPC",
                        "PUT /auth/v1/admin/users/${row.usuarioId}",
                        "PATCH /rest/v1/$ADMIN_ACCESS_TABLE",
                        "POST /rest/v1/auditoria_log",
                    ),
                    calls.map { it.call },
                )
                // M1: baneada ANTES de quedar verificada, y solo con `ban_duration`.
                assertEquals(mapOf("ban_duration" to "876000h"), calls[3].jsonBody().mapValues { it.value.jsonPrimitive.content })
                assertTrue(fake.accounts.getValue(row.usuarioId).banned)
                // Revision #3 L1: justo despues del baneo, la purga de TODAS sus sesiones (el baneo no las borra).
                assertEquals(mapOf("p_user_id" to row.usuarioId), calls[4].jsonBody().mapValues { it.value.jsonPrimitive.content })
                // Despues del baneo, la contrasena ELEGIDA al verificar (antes la cuenta solo tenia la aleatoria del servidor).
                assertEquals(mapOf("password" to password), calls[5].jsonBody().mapValues { it.value.jsonPrimitive.content })
                assertEquals(password, fake.accounts.getValue(row.usuarioId).password)
                // GoTrue con type email (OTP de correo), el correo CANONICO y el codigo.
                val goTrue = calls[1].jsonBody()
                assertEquals("email", goTrue["type"]!!.jsonPrimitive.content)
                assertEquals(email, goTrue["email"]!!.jsonPrimitive.content)
                assertEquals(code, goTrue["token"]!!.jsonPrimitive.content)
                // La sesion de GoTrue se revoca en GLOBAL con su propio token.
                val logout = calls[2]
                assertEquals("scope=global", logout.query)
                assertEquals("Bearer verify.access.jwt", logout.headers[HttpHeaders.Authorization])
                // La marca, condicionada a esa fila, ese usuario, pendiente y sin verificar.
                val patch = calls[6]
                assertEquals("eq.${row.id}", patch.param("id"))
                assertEquals("eq.${row.usuarioId}", patch.param("usuario_id"))
                assertEquals("eq.pendiente", patch.param("estado"))
                assertEquals("is.null", patch.param("correo_verificado_en"))
                assertTrue(row.verificadoEn != null)
                assertTrue(fake.accounts.getValue(row.usuarioId).confirmed, "GoTrue confirmo la cuenta al verificar")
                val audit = fake.audits().single()
                assertEquals(ACTION_ACCESS_REQUEST_EMAIL_VERIFIED, audit["accion"]!!.jsonPrimitive.content)
                assertEquals(row.id, audit["registro_id"]!!.jsonPrimitive.content)
                assertFalse(code in audit.toString() || email in audit.toString())
            }
            appender.list.joinToString("\n") { it.formattedMessage + " " + it.argumentArray?.joinToString(" ").orEmpty() }
        }
        assertTrue("admin access request email verified" in logs, logs)
        assertFalse(code in logs, "el codigo no se escribe en ningun log")
        assertFalse("verify.access.jwt" in logs, "ni el token de la sesion")
        assertFalse(password in logs)
        assertFalse(email in logs)
    }

    /**
     * EL MISMO 400 (todo igual salvo `requestId`) para: codigo erroneo, correo sin cuenta, sin solicitud, aprobada,
     * rechazada, caducada, ya verificada y codigos con otra forma. Solo la forma del CORREO puede dar otro 400.
     */
    @Test
    fun everyFailureIsTheSameGeneric400() = testApplication {
        val fake = AccessRequestFakeSupabase()
        fake.seed("pending@example.com").also { fake.codes["pending@example.com"] = code }
        fake.seed("approved@example.com", estado = ADMIN_ACCESS_ESTADO_APROBADA, verified = true)
        fake.seed("rejected@example.com", estado = ADMIN_ACCESS_ESTADO_RECHAZADA).also { fake.codes["rejected@example.com"] = code }
        fake.seed("expired@example.com", createdAgoSeconds = ADMIN_ACCESS_UNVERIFIED_TTL_SECONDS + 1).also { fake.codes["expired@example.com"] = code }
        fake.seed("verified@example.com", verified = true)
        application { module(fake) }

        val cases = listOf(
            "codigo erroneo" to verify("pending@example.com", "000000"),
            "correo sin cuenta" to verify("nobody@example.com"),
            "aprobada" to verify("approved@example.com"),
            "rechazada" to verify("rejected@example.com"),
            "caducada" to verify("expired@example.com"),
            "ya verificada" to verify("verified@example.com"),
            "5 cifras" to verify("pending@example.com", "12345"),
            "7 cifras" to verify("pending@example.com", "1234567"),
            "letras" to verify("pending@example.com", "abcdef"),
            "cifras no ASCII" to verify("pending@example.com", "١٢٣٤٥٦"),
            "vacio" to verify("pending@example.com", ""),
        )
        val reference = cases.first().second.json() - "requestId"
        assertEquals("VALIDATION", reference["code"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("credential" to "INVALID_CREDENTIAL"),
            reference["details"]!!.jsonObject["issues"]!!.jsonArray.map {
                it.jsonObject["field"]!!.jsonPrimitive.content to it.jsonObject["issue"]!!.jsonPrimitive.content
            },
        )
        for ((name, response) in cases) {
            assertEquals(HttpStatusCode.BadRequest, response.status, name)
            assertEquals(reference, response.json() - "requestId", name)
        }
        // GoTrue SOLO se consulto para la solicitud viva (codigo erroneo): sin solicitud viva no se llama, y con codigo de
        // otra forma tampoco.
        assertEquals(1, fake.sent.count { it.path == "/auth/v1/verify" })
        // M2: el unico PATCH es el contador de fallos de la solicitud viva (codigo erroneo); nada se marca verificado.
        val patch = fake.sent.single { it.method.value == "PATCH" }
        assertEquals(setOf("intentos_verificacion"), patch.jsonBody().keys)
        assertEquals(1, fake.rows.single { it.email == "pending@example.com" }.intentos)
        assertTrue(fake.rows.all { it.email == "approved@example.com" || it.email == "verified@example.com" || it.verificadoEn == null })
        assertTrue(fake.logouts().isEmpty())
        assertTrue(fake.audits().isEmpty())
        assertTrue(fake.sent.none { it.method.value == "PUT" })
    }

    /**
     * M2: cinco codigos erroneos agotan la solicitud. Las cinco respuestas son EL MISMO 400; tras la quinta la solicitud se
     * libera (cuenta sin confirmar borrada, auditoria `intentos_de_verificacion_agotados`), el codigo bueno ya no vale y
     * hay que volver a pedir el acceso, que manda un codigo NUEVO.
     */
    @Test
    fun fiveWrongCodesExhaustTheRequestAndTheApplicantMustAskAgain() = testApplication {
        val fake = AccessRequestFakeSupabase()
        val row = fake.seed(email).also { fake.codes[email] = code }
        application { module(fake) }

        val responses = (1..ADMIN_ACCESS_VERIFY_MAX_FAILURES).map { verify(value = "00000$it") }
        val bodies = responses.map { it.json() - "requestId" }

        assertTrue(responses.all { it.status == HttpStatusCode.BadRequest })
        assertEquals(1, bodies.distinct().size, "las cinco respuestas son identicas")
        assertFalse(row.usuarioId in fake.accounts, "agotada: se libera como una caducada")
        assertTrue(fake.rows.isEmpty())
        val audit = fake.audits().single()
        assertEquals(ACTION_ACCESS_REQUEST_EXPIRED, audit["accion"]!!.jsonPrimitive.content)
        assertEquals(AccessRequestReleaseMotive.VERIFY_ATTEMPTS_EXHAUSTED.wire, audit["despues"]!!.jsonObject["motivo"]!!.jsonPrimitive.content)
        assertEquals("5", audit["despues"]!!.jsonObject["intentosVerificacion"]!!.jsonPrimitive.content)

        // El codigo bueno ya no sirve (no hay solicitud viva: ni siquiera se pregunta a GoTrue) y es el mismo 400.
        val before = fake.sent.count { it.path == "/auth/v1/verify" }
        val late = verify()
        assertEquals(HttpStatusCode.BadRequest, late.status)
        assertEquals(bodies.first(), late.json() - "requestId")
        assertEquals(before, fake.sent.count { it.path == "/auth/v1/verify" })

        // Volver a pedirlo crea otra cuenta y manda un codigo nuevo.
        assertEquals(HttpStatusCode.Accepted, submit().status)
        assertEquals(1, fake.rows.size)
        assertEquals(listOf("/auth/v1/otp"), fake.mails().map { it.path })
    }

    /** M2: si la escritura del contador falla, 503 (falla cerrado: el contador no se puede saltar). */
    @Test
    fun theFailedAttemptCounterFailsClosed() = testApplication {
        val fake = AccessRequestFakeSupabase()
        fake.seed(email).also { fake.codes[email] = code }
        fake.override = { if (it.method.value == "PATCH") HttpStatusCode.ServiceUnavailable else null }
        application { module(fake) }

        val response = verify(value = "000000")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("UPSTREAM_UNAVAILABLE", response.json()["code"]!!.jsonPrimitive.content)
    }

    /** El usuario de la sesion de GoTrue no es el de la solicitud: el 400 unico, sesion revocada, ni baneo ni marca. */
    @Test
    fun aVerifiedUserThatDoesNotMatchTheRequestIsTheGeneric400() = testApplication {
        val fake = AccessRequestFakeSupabase()
        val row = fake.seed(email).also { fake.codes[email] = code }
        // La cuenta que GoTrue tiene con ese correo es OTRA (p. ej. la fila apunta a una identidad anterior).
        val other = "66666666-6666-4666-8666-666666666666"
        fake.accounts.remove(row.usuarioId)
        fake.accounts[other] = AccessRequestFakeSupabase.Account(other, email)
        application { module(fake) }

        val response = verify()

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("VALIDATION", response.json()["code"]!!.jsonPrimitive.content)
        assertEquals("scope=global", fake.logouts().single().query)
        assertTrue(fake.sent.none { it.method.value == "PUT" || it.method.value == "PATCH" })
        assertEquals(null, row.verificadoEn)
    }

    /** Carrera `marked == null`: otra peticion la verifico a la vez -> 204; la fila desaparecio entre medias -> el 400 unico. */
    @Test
    fun aConcurrentVerificationIsAcceptedAndAVanishedRowIsTheGeneric400() {
        for (vanished in listOf(false, true)) testApplication {
            val fake = AccessRequestFakeSupabase()
            val row = fake.seed(email).also { fake.codes[email] = code }
            fake.override = { sent ->
                if (sent.method.value == "PATCH" && sent.param("correo_verificado_en") == "is.null") {
                    if (vanished) fake.rows.remove(row) else row.verificadoEn = java.time.Instant.now()
                }
                null
            }
            application { module(fake) }

            val response = verify()

            assertEquals(if (vanished) HttpStatusCode.BadRequest else HttpStatusCode.NoContent, response.status, "vanished=$vanished")
            assertTrue(fake.audits().isEmpty(), "la auditoria la escribe quien gano la marca")
        }
    }

    /** El 429 de GoTrue (su limite, compartido por la IP del backend) es 503 UPSTREAM_UNAVAILABLE con Retry-After y no cuenta como fallo. */
    @Test
    fun aGoTrueThrottleIs503WithRetryAfterAndIsNotAFailedAttempt() = testApplication {
        val fake = AccessRequestFakeSupabase()
        val row = fake.seed(email).also { fake.codes[email] = code }
        fake.override = { if (it.path == "/auth/v1/verify") HttpStatusCode.TooManyRequests else null }
        application { module(fake) }

        val response = verify()

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("UPSTREAM_UNAVAILABLE", response.json()["code"]!!.jsonPrimitive.content)
        assertEquals("60", response.headers[HttpHeaders.RetryAfter])
        assertEquals(0, row.intentos)
        assertTrue(fake.sent.none { it.method.value == "PATCH" || it.method.value == "PUT" })
    }

    /** M1: si el baneo falla, 503 y la solicitud sigue SIN verificar (el solicitante pide otro codigo y repite). */
    @Test
    fun aFailedBanIs503AndLeavesTheRequestUnverified() = testApplication {
        val fake = AccessRequestFakeSupabase()
        val row = fake.seed(email).also { fake.codes[email] = code }
        fake.override = { if (it.method.value == "PUT") HttpStatusCode.InternalServerError else null }
        application { module(fake) }

        val response = verify()

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals(null, row.verificadoEn)
        assertTrue(fake.sent.none { it.method.value == "PATCH" })
        assertTrue(fake.audits().isEmpty())
        // Se puede pedir otro codigo: la solicitud sigue viva.
        assertEquals(HttpStatusCode.Accepted, resendCode().status)
        assertEquals(listOf("/auth/v1/otp"), fake.mails().map { it.path })
    }

    /**
     * Revision 2026-10-14 (DO_NOT_SHIP): si el baneo sale bien pero la contrasena elegida no se puede fijar, 503 y la solicitud
     * sigue SIN verificar. La cuenta queda confirmada (GoTrue la confirmo al aceptar el codigo) con la contrasena ALEATORIA
     * del servidor: nadie puede entrar con contrasena. El solicitante pide otro codigo y repite.
     */
    @Test
    fun aFailedPasswordSetIs503AndLeavesTheRequestUnverifiedWithNoKnownPassword() = testApplication {
        val fake = AccessRequestFakeSupabase()
        application { module(fake) }
        submit()
        val row = fake.rows.single()
        val serverPassword = fake.accounts.getValue(row.usuarioId).password
        fake.codes[email] = code
        fake.override = { if (it.method.value == "PUT" && "\"password\"" in it.body) HttpStatusCode.InternalServerError else null }

        val response = verify()

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals(null, row.verificadoEn)
        assertTrue(fake.sent.none { it.method.value == "PATCH" })
        assertTrue(fake.audits().isEmpty())
        val account = fake.accounts.getValue(row.usuarioId)
        assertTrue(account.confirmed)
        assertEquals(serverPassword, account.password, "sigue la aleatoria del servidor, que nadie conoce")
        assertFalse(account.password == password)
        // Otro codigo y se repite: ahora si.
        fake.override = { null }
        assertEquals(HttpStatusCode.Accepted, resendCode().status)
        // La cuenta esta baneada desde el primer intento: el GoTrue falso no acepta codigos de una cuenta baneada, asi que
        // el reintento real depende de DEBT-118; aqui basta con que la solicitud siga viva y admita reenvio.
        assertEquals(listOf("/auth/v1/otp", "/auth/v1/otp"), fake.mails().map { it.path })
    }

    /**
     * La contrasena elegida se valida ANTES de nada (ni limites que la cuenten como intento de codigo, ni lecturas, ni GoTrue):
     * 400 VALIDATION con la incidencia de CAMPO `password` (`INVALID_PASSWORD` / `BLANK_PASSWORD`), distinto del 400 unico del
     * codigo. No revela nada: depende solo de lo enviado.
     */
    @Test
    fun anInvalidChosenPasswordIsAFieldErrorDecidedBeforeAnything() = testApplication {
        val fake = AccessRequestFakeSupabase()
        val row = fake.seed(email).also { fake.codes[email] = code }
        application { module(fake) }

        for ((chosen, issue) in listOf("a".repeat(11) to "INVALID_PASSWORD", "a".repeat(73) to "INVALID_PASSWORD", "ñ".repeat(12) to "INVALID_PASSWORD", "" to "BLANK_PASSWORD")) {
            val response = verify(chosen = chosen)
            assertEquals(HttpStatusCode.BadRequest, response.status, chosen)
            val body = response.json()
            assertEquals("VALIDATION", body["code"]!!.jsonPrimitive.content, chosen)
            assertTrue(issue in body.toString() && "\"password\"" in body.toString(), "$chosen: $body")
            assertFalse("INVALID_CREDENTIAL" in body.toString(), "no es el 400 del codigo: $body")
        }
        assertEquals(emptyList(), fake.calls(), "ni la solicitud ni GoTrue")
        assertEquals(0, row.intentos)
        assertEquals(HttpStatusCode.NoContent, verify(chosen = "a".repeat(12)).status)
    }

    /**
     * La raiz del DO_NOT_SHIP: el alta ya no fija ninguna contrasena del solicitante (aleatoria del servidor, distinta en cada
     * alta) y la del panel la elige quien demuestra el buzon. Una fila ANTERIOR a este cambio (cuenta con la contrasena del
     * creador) tambien queda con la del verificador.
     */
    @Test
    fun onlyTheMailboxOwnerChoosesThePasswordAtVerification() = testApplication {
        val fake = AccessRequestFakeSupabase()
        application { module(fake) }
        submit()
        submit(address = "otra@example.com")
        val first = fake.accounts.values.first { it.email == email }
        val second = fake.accounts.values.first { it.email == "otra@example.com" }
        assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(first.password!!))
        assertFalse(first.password == second.password)
        assertTrue(fake.sent.none { password in it.body })
    }

    /** Fila anterior al cambio (la cuenta lleva la contrasena del creador): al verificar queda la del verificador. */
    @Test
    fun aRequestCreatedBeforeThisChangeEndsWithTheVerifierPassword() = testApplication {
        val legacy = AccessRequestFakeSupabase()
        val legacyRow = legacy.seed(email).also { legacy.codes[email] = code }
        assertEquals(AccessRequestFakeSupabase.ATTACKER_PASSWORD, legacy.accounts.getValue(legacyRow.usuarioId).password)
        application { module(legacy) }

        assertEquals(HttpStatusCode.NoContent, verify().status)

        assertEquals(password, legacy.accounts.getValue(legacyRow.usuarioId).password)
    }

    @Test
    fun aMalformedEmailIsTheUsualIdentifierErrorAndTouchesNothing() = testApplication {
        val fake = AccessRequestFakeSupabase()
        application { module(fake) }

        for (address in listOf("sin-arroba", "a b@example.com", "")) {
            val response = verify(address)
            assertEquals(HttpStatusCode.BadRequest, response.status, address)
            assertTrue("IDENTIFIER" in response.bodyAsText(), address)
        }
        assertEquals(emptyList(), fake.calls())
    }

    @Test
    fun aGoTrueOutageIs503AndMarksNothing() = testApplication {
        val fake = AccessRequestFakeSupabase()
        fake.seed(email).also { fake.codes[email] = code }
        fake.override = { if (it.path == "/auth/v1/verify") HttpStatusCode.InternalServerError else null }
        application { module(fake) }

        val response = verify()

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(fake.sent.none { it.method.value == "PATCH" })
        assertTrue(fake.rows.single().verificadoEn == null)
    }

    /**
     * Freno de la fuerza bruta: 5 intentos por (correo, IP) cada 15 min (429 despues), con el limitador REAL. Los cinco
     * fallos agotan ademas la solicitud (M2).
     */
    @Test
    fun theVerifyLimiterStopsTheSixthAttemptForTheSameEmailAndIp() = testApplication {
        val fake = AccessRequestFakeSupabase()
        fake.seed(email).also { fake.codes[email] = code }
        application { module(fake, InMemoryOposiBotsAuthRateLimiter()) }

        val statuses = (1..6).map { verify(value = "00000$it").status }
        // El septimo, con otra forma de escribir el MISMO correo desde la misma IP: la clave es (correo canonico, IP).
        val canonicalAlias = verify(address = " ADA@EXAMPLE.COM ", value = code)

        assertEquals(List(5) { HttpStatusCode.BadRequest } + HttpStatusCode.TooManyRequests, statuses)
        assertEquals(HttpStatusCode.TooManyRequests, canonicalAlias.status)
        assertTrue(canonicalAlias.headers[HttpHeaders.RetryAfter] != null)
        assertEquals(5, fake.sent.count { it.path == "/auth/v1/verify" })
        assertTrue(fake.rows.isEmpty(), "los cinco fallos agotaron la solicitud")
        assertEquals(OposiBotsAuthOperation.ADMIN_ACCESS_VERIFY.subjectLimit, 5)
        assertEquals(OposiBotsAuthOperation.ADMIN_ACCESS_VERIFY.ipLimit, 10)
        assertEquals(OposiBotsAuthOperation.ADMIN_ACCESS_VERIFY.windowSeconds, 900L)
        assertEquals(OposiBotsAuthOperation.ADMIN_ACCESS_VERIFY_DAILY.subjectLimit, 20)
        assertEquals(OposiBotsAuthOperation.ADMIN_ACCESS_VERIFY_DAILY.windowSeconds, 86_400L)
    }

    /** M2: el cupo de 15 min es por (correo, IP): agotado desde otra IP no bloquea; el techo diario por correo si. */
    @Test
    fun theFifteenMinuteKeyIncludesTheIpAndTheDailyCeilingIsPerEmail() = testApplication {
        val fake = AccessRequestFakeSupabase()
        val limiter = TestCountingLimiter()
        application { module(fake, limiter) }

        verify(address = " Ada@Example.com ")

        val checks = limiter.subjectChecks.toList()
        assertEquals(listOf(OposiBotsAuthOperation.ADMIN_ACCESS_VERIFY, OposiBotsAuthOperation.ADMIN_ACCESS_VERIFY_DAILY), checks.map { it.first })
        assertTrue(checks[0].second.startsWith("$email|") && checks[0].second.length > email.length + 1, checks[0].second)
        assertEquals(email, checks[1].second)
    }

    @Test
    fun theVerifyAndResendLimitersUseTheirOwnOperationsAndTheCanonicalEmail() = testApplication {
        val fake = AccessRequestFakeSupabase()
        val limiter = TestCountingLimiter()
        application { module(fake, limiter) }

        verify(address = " Ada@Example.com ")
        resendCode(address = " Ada@Example.com ")

        assertEquals(
            listOf(
                OposiBotsAuthOperation.ADMIN_ACCESS_VERIFY to limiter.subjectChecks[0].second,
                OposiBotsAuthOperation.ADMIN_ACCESS_VERIFY_DAILY to email,
                OposiBotsAuthOperation.ADMIN_ACCESS_RESEND_CODE to email,
            ),
            limiter.subjectChecks.toList(),
        )
        assertEquals(listOf(OposiBotsAuthOperation.ADMIN_ACCESS_VERIFY, OposiBotsAuthOperation.ADMIN_ACCESS_RESEND_CODE), limiter.ipChecks.toList())
    }

    // ------------------------------------------------------------------------------------------------ reenviar el codigo

    @Test
    fun resendCodeOnlyMailsALivePendingUnverifiedRequestAndAlwaysAnswersTheSame() = testApplication {
        val fake = AccessRequestFakeSupabase()
        fake.seed("live@example.com")
        fake.seed("verified@example.com", verified = true)
        fake.seed("expired@example.com", createdAgoSeconds = ADMIN_ACCESS_UNVERIFIED_TTL_SECONDS + 1)
        fake.seed("approved@example.com", estado = ADMIN_ACCESS_ESTADO_APROBADA, verified = true)
        application { module(fake) }

        val responses = listOf("live@example.com", "verified@example.com", "expired@example.com", "approved@example.com", "nobody@example.com")
            .map { resendCode(it) }

        for (response in responses) {
            assertEquals(HttpStatusCode.Accepted, response.status)
            assertEquals("""{"estado":"recibida"}""", response.bodyAsText())
        }
        val mails = fake.mails()
        assertEquals(1, mails.size)
        assertEquals("/auth/v1/otp", mails.single().path)
        assertEquals("live@example.com", mails.single().jsonBody()["email"]!!.jsonPrimitive.content)
        assertEquals("false", mails.single().jsonBody()["create_user"]!!.jsonPrimitive.content)
        // Reenviar nunca borra ni crea nada.
        assertTrue(fake.sent.none { it.method.value == "DELETE" || it.call == "POST /auth/v1/admin/users" })
    }

    @Test
    fun resendCodeIsSilentAtTheEmailCeilingAndFailsClosedOnALookupFailure() = testApplication {
        val fake = AccessRequestFakeSupabase()
        fake.seed(email)
        application { module(fake, TestCountingLimiter(subjectAllowed = false)) }

        assertEquals(HttpStatusCode.Accepted, resendCode().status)
        assertEquals(emptyList(), fake.calls())
    }

    @Test
    fun resendCodeFailsClosedWhenTheLookupFails() = testApplication {
        val fake = AccessRequestFakeSupabase()
        fake.override = { if (it.call == "GET /rest/v1/$ADMIN_ACCESS_TABLE") HttpStatusCode.InternalServerError else null }
        application { module(fake) }

        assertEquals(HttpStatusCode.ServiceUnavailable, resendCode().status)
        assertTrue(fake.mails().isEmpty())
    }

    // ------------------------------------------------------------------------------------------------ aprobar, de punta a punta

    private val superActor = "5a5a5a5a-0000-4000-8000-000000000001"
    private val roleSuper = "aaaaaaaa-0000-4000-8000-00000000000a"
    private val roleAdmin = "aaaaaaaa-0000-4000-8000-00000000000b"

    /** Supabase falso del gateway de roles (catalogo, roles por usuario, RPC auditada, auditoria). */
    private inner class RolesStub {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val rolesOf = ConcurrentHashMap(mapOf(superActor to listOf(roleSuper)))

        fun respond(uri: String, body: String): V1StubResponse = when {
            "/rest/v1/roles_app" in uri -> V1StubResponse(
                200,
                """[{"id":"$roleSuper","codigo":"superadmin","nombre":"Superadmin"},{"id":"$roleAdmin","codigo":"admin","nombre":"Admin"}]""",
            )
            "/rest/v1/roles_usuario?select=rol_id&usuario_id=eq." in uri -> {
                val id = uri.substringAfter("usuario_id=eq.").substringBefore('&')
                V1StubResponse(200, rolesOf[id].orEmpty().joinToString(",", "[", "]") { """{"rol_id":"$it"}""" })
            }
            "/rest/v1/rpc/admin_sync_roles_audited" in uri -> {
                val args = Json.parseToJsonElement(body).jsonObject
                rolesOf[args["p_usuario_id"]!!.jsonPrimitive.content] = args["p_role_ids"]!!.jsonArray.map { it.jsonPrimitive.content }
                V1StubResponse(200, """{"antes":[],"despues":[]}""")
            }
            else -> V1StubResponse(201, "")
        }
    }

    private fun Application.flowModule(fake: AccessRequestFakeSupabase, roles: RolesStub) {
        install(ContentNegotiation) { json(testJson) }
        installRequestCorrelation()
        installBackendStatusPages()
        installRequestBodyLimit()
        install(Authentication) { bearer("gateway-auth") { authenticate { UserIdPrincipal(it.token) } } }
        val gateway = fake.accessGateway()
        val adminRoles = AdminV1Gateway.forTesting(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = V1StubHttpClient({ roles.sent += it }, { uri -> roles.respond(uri, roles.sent.lastOrNull()?.let(::readRequestBody).orEmpty()) }),
        )
        val runtime = CanonicalTestEnv().runtime()
        routing {
            adminAccessRequestPublicRoute(runtime, gateway)
            authenticate("gateway-auth") {
                installAdminAuditActor()
                adminAccessRequestRoutes(gateway, adminRoles)
            }
        }
    }

    private suspend fun ApplicationTestBuilder.approve(id: String): HttpResponse =
        client.post("/api/v1/admin/solicitudes-acceso/$id/aprobar") { header(HttpHeaders.Authorization, "Bearer $superActor") }

    /** Pedir -> aprobar (409, sin verificar) -> verificar -> aprobar (200). Ninguna peticion confirma el correo por la API de administracion. */
    @Test
    fun approvalRequiresTheVerifiedMailboxAndNeverConfirmsTheEmailItself() = testApplication {
        val fake = AccessRequestFakeSupabase()
        val roles = RolesStub()
        application { flowModule(fake, roles) }

        submit()
        val row = fake.rows.single()
        val early = approve(row.id)
        assertEquals(HttpStatusCode.Conflict, early.status, early.bodyAsText())
        assertEquals("CONFLICT", early.json()["code"]!!.jsonPrimitive.content)
        assertTrue(roles.sent.none { "admin_sync_roles_audited" in it.uri().toString() }, "sin verificar no se concede ningun rol")

        fake.codes[email] = code
        assertEquals(HttpStatusCode.NoContent, verify().status)
        val approved = approve(row.id)

        assertEquals(HttpStatusCode.OK, approved.status, approved.bodyAsText())
        val body = approved.json()
        assertEquals("aprobada", body["estado"]!!.jsonPrimitive.content)
        assertEquals("true", body["correoVerificado"]!!.jsonPrimitive.content)
        assertEquals("false", body["caducada"]!!.jsonPrimitive.content)
        assertEquals(listOf(roleAdmin), roles.rolesOf[row.usuarioId])
        // La doble comprobacion leyo la cuenta. Los UNICOS PUT de administracion son el baneo y la contrasena elegida
        // (verificar) y el desbaneo (aprobar, antes del rol): nunca `email_confirm` ni ningun otro campo.
        assertTrue(fake.sent.any { it.call == "GET /auth/v1/admin/users/${row.usuarioId}" })
        assertEquals(
            listOf(mapOf("ban_duration" to "876000h"), mapOf("password" to password), mapOf("ban_duration" to "none")),
            fake.adminPuts(row.usuarioId).map { body -> body.mapValues { it.value.jsonPrimitive.content } },
        )
        assertFalse(fake.accounts.getValue(row.usuarioId).banned)
        assertTrue(fake.sent.none { "email_confirm\":true" in it.body })
    }

    /**
     * M1 de punta a punta: verificada y sin aprobar, la contrasena contra GoTrue directo no da sesion (baneada); tras aprobar
     * si. Y la aprobacion desbanea ANTES de conceder el rol.
     */
    @Test
    fun aVerifiedButUnapprovedAccountCannotObtainADirectGoTrueSession() = testApplication {
        val fake = AccessRequestFakeSupabase()
        val roles = RolesStub()
        application { flowModule(fake, roles) }
        submit()
        val row = fake.rows.single()
        fake.codes[email] = code
        assertEquals(HttpStatusCode.NoContent, verify().status)

        // GoTrue directo con la clave anonima (simulado sobre el falso, que aplica el baneo): sin sesion.
        suspend fun directLogin() = fake.client.post("https://example.supabase.co/auth/v1/token?grant_type=password") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"$password"}""")
        }
        assertTrue(fake.accounts.getValue(row.usuarioId).banned)
        assertEquals(HttpStatusCode.BadRequest, directLogin().status)

        assertEquals(HttpStatusCode.OK, approve(row.id).status)
        assertFalse(fake.accounts.getValue(row.usuarioId).banned)
        assertEquals(listOf(roleAdmin), roles.rolesOf[row.usuarioId])
        assertEquals(HttpStatusCode.OK, directLogin().status)
    }

    /**
     * Revision L-a: una solicitud YA aprobada cuya cuenta se quedo sin rol de administracion (la aprobacion marco la fila pero
     * el desbaneo o el rol fallaron y la reversion tampoco pudo hacerse). Repetir la aprobacion termina el trabajo: desbaneo,
     * `admin` por la RPC auditada y auditoria `solicitud_acceso_aprobada` con `reanudada: true`.
     */
    @Test
    fun approvingAStuckApprovedRequestWithoutAdminRoleFinishesTheJob() = testApplication {
        val fake = AccessRequestFakeSupabase()
        val row = fake.seed(email, estado = ADMIN_ACCESS_ESTADO_APROBADA, verified = true)
        fake.accounts.getValue(row.usuarioId).banned = true // la reversion volvio a banear, o nunca se desbaneo
        val roles = RolesStub()
        application { flowModule(fake, roles) }

        val response = approve(row.id)

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals("aprobada", response.json()["estado"]!!.jsonPrimitive.content)
        assertFalse(fake.accounts.getValue(row.usuarioId).banned)
        assertEquals(listOf(mapOf("ban_duration" to "none")), fake.adminPuts(row.usuarioId).map { body -> body.mapValues { it.value.jsonPrimitive.content } })
        assertEquals(listOf(roleAdmin), roles.rolesOf[row.usuarioId])
        val audit = roles.sent.map(::readRequestBody).single { ACTION_ACCESS_REQUEST_APPROVED in it }
        assertTrue("\"reanudada\":true" in audit, audit)

        // Ya con el rol: repetirla otra vez no toca nada mas.
        val before = fake.sent.size
        val rpcBefore = roles.sent.count { "admin_sync_roles_audited" in it.uri().toString() }
        assertEquals(HttpStatusCode.OK, approve(row.id).status)
        assertTrue(fake.sent.drop(before).none { it.method.value == "PUT" })
        assertEquals(rpcBefore, roles.sent.count { "admin_sync_roles_audited" in it.uri().toString() })
    }

    /** L-a: sigue exigiendo el correo verificado (y superadmin): una aprobada sin verificar no recibe el rol. */
    @Test
    fun aStuckApprovedRequestWithoutVerifiedMailboxIsNotFinished() = testApplication {
        val fake = AccessRequestFakeSupabase()
        val row = fake.seed(email, estado = ADMIN_ACCESS_ESTADO_APROBADA, verified = false)
        val roles = RolesStub()
        application { flowModule(fake, roles) }

        val response = approve(row.id)
        val plainAdmin = client.post("/api/v1/admin/solicitudes-acceso/${row.id}/aprobar") {
            header(HttpHeaders.Authorization, "Bearer 5a5a5a5a-0000-4000-8000-0000000000ff")
        }

        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        assertEquals(HttpStatusCode.Forbidden, plainAdmin.status)
        assertEquals("SUPERADMIN_REQUIRED", plainAdmin.json()["code"]!!.jsonPrimitive.content)
        assertTrue(fake.adminPuts(row.usuarioId).isEmpty())
        assertTrue(roles.sent.none { "admin_sync_roles_audited" in it.uri().toString() })
    }

    // ------------------------------------------------------------------------------------------------ revision #3

    /** Solicitud sin verificar cuya cuenta quedo CONFIRMADA y BANEADA (una verificacion que fallo despues del baneo). */
    private fun AccessRequestFakeSupabase.seedBannedUnverified(createdAgoSeconds: Long = 60): AccessRequestFakeSupabase.Row =
        seed(email, accountConfirmed = true, createdAgoSeconds = createdAgoSeconds).also { accounts.getValue(it.usuarioId).banned = true }

    private fun AccessRequestFakeSupabase.assertDeletedAsBanned(row: AccessRequestFakeSupabase.Row) {
        assertFalse(row.usuarioId in accounts, "la cuenta baneada sin verificar se borra")
        assertTrue(rows.none { it.id == row.id }, "la fila se va en cascada")
        assertTrue(sent.none { it.path == "/auth/v1/token" }, "nunca se intenta la neutralizacion (GoTrue la rechaza: baneada)")
        val audit = audits().single { it["accion"]!!.jsonPrimitive.content == ACTION_ACCESS_REQUEST_EXPIRED }["despues"]!!.jsonObject
        assertEquals("true", audit["cuentaEliminada"]!!.jsonPrimitive.content)
        assertEquals("true", audit["cuentaBaneada"]!!.jsonPrimitive.content)
        assertEquals("false", audit["credencialNeutralizada"]!!.jsonPrimitive.content)
    }

    /** M1: `register` (libera cualquier sin verificar) y `recover` (cuenta confirmada) BORRAN la cuenta baneada: sin 503. */
    @Test
    fun aBannedUnverifiedAccountIsDeletedByRegisterAndRecoverInsteadOfLockingTheMailbox() {
        for (operation in listOf(OposiBotsAuthOperation.REGISTER, OposiBotsAuthOperation.RECOVER)) {
            val fake = AccessRequestFakeSupabase()
            val row = fake.seedBannedUnverified()

            val held = kotlinx.coroutines.runBlocking { fake.guard().holdsMail(email, operation) }

            assertFalse(held, "$operation: el correo queda libre")
            fake.assertDeletedAsBanned(row)
        }
    }

    /** M1: una nueva solicitud ante una muerta (caducada) con la cuenta baneada la borra y sigue con el alta. */
    @Test
    fun aDeadBannedUnverifiedRequestIsDeletedAndANewSubmitProceeds() = testApplication {
        val fake = AccessRequestFakeSupabase()
        val old = fake.seedBannedUnverified(createdAgoSeconds = ADMIN_ACCESS_UNVERIFIED_TTL_SECONDS + 60)
        application { module(fake) }

        assertEquals(HttpStatusCode.Accepted, submit().status)

        fake.assertDeletedAsBanned(old)
        val fresh = fake.rows.single()
        assertFalse(fresh.usuarioId == old.usuarioId)
        assertEquals(listOf("/auth/v1/otp"), fake.mails().map { it.path })
    }

    /**
     * M1 de punta a punta: GoTrue rechaza la contrasena elegida (422 `weak_password`) despues del baneo. La respuesta es el
     * 400 de CAMPO `password`/`INVALID_PASSWORD` (no el 400 del codigo, no un 503) y no suma intentos. La cuenta queda
     * confirmada, baneada y sin verificar; despues el titular del correo se registra y la cuenta se BORRA (no 503).
     */
    @Test
    fun aGoTrueRejectedPasswordIsTheFieldErrorAndTheLeftoverBannedAccountIsReleasedByRegister() = testApplication {
        val fake = AccessRequestFakeSupabase()
        val row = fake.seed(email).also { fake.codes[email] = code }
        fake.override = { if (it.method.value == "PUT" && "\"password\"" in it.body) HttpStatusCode.UnprocessableEntity else null }
        application { module(fake) }

        val response = verify()

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        val body = response.json()
        assertEquals("VALIDATION", body["code"]!!.jsonPrimitive.content)
        assertTrue("INVALID_PASSWORD" in body.toString() && "\"password\"" in body.toString(), body.toString())
        assertFalse("INVALID_CREDENTIAL" in body.toString())
        assertEquals(0, row.intentos, "no cuenta como codigo erroneo")
        assertEquals(null, row.verificadoEn)
        assertTrue(fake.sent.none { it.method.value == "PATCH" })
        val account = fake.accounts.getValue(row.usuarioId)
        assertTrue(account.confirmed && account.banned)

        fake.override = { null }
        assertFalse(kotlinx.coroutines.runBlocking { fake.guard().holdsMail(email, OposiBotsAuthOperation.REGISTER) })
        fake.assertDeletedAsBanned(row)
    }

    /** M1: un 5xx (o un 4xx que no es de la contrasena) al fijarla sigue siendo 503. */
    @Test
    fun aGoTrueOutageOrNonPasswordRejectionOnThePasswordPutIsStill503() {
        for (status in listOf(HttpStatusCode.InternalServerError, HttpStatusCode.NotFound, HttpStatusCode.TooManyRequests)) {
            testApplication {
                val fake = AccessRequestFakeSupabase()
                val row = fake.seed(email).also { fake.codes[email] = code }
                fake.override = { if (it.method.value == "PUT" && "\"password\"" in it.body) status else null }
                application { module(fake) }

                assertEquals(HttpStatusCode.ServiceUnavailable, verify().status, "$status")
                assertEquals(0, row.intentos)
                assertEquals(null, row.verificadoEn)
            }
        }
    }

    /** L1: si la purga de sesiones falla tras el baneo, 503: ni contrasena, ni marca, ni auditoria (falla cerrado). */
    @Test
    fun aFailedSessionPurgeAtVerificationIs503AndMarksNothing() = testApplication {
        val fake = AccessRequestFakeSupabase()
        val row = fake.seed(email).also { fake.codes[email] = code }
        fake.override = { if (it.path == "/rest/v1/rpc/$ADMIN_PURGE_USER_SESSIONS_RPC") HttpStatusCode.InternalServerError else null }
        application { module(fake) }

        val response = verify()

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals(null, row.verificadoEn)
        assertEquals(listOf(mapOf("ban_duration" to "876000h")), fake.adminPuts(row.usuarioId).map { b -> b.mapValues { it.value.jsonPrimitive.content } })
        assertTrue(fake.sent.none { it.method.value == "PATCH" })
        assertTrue(fake.audits().isEmpty())
    }

    /** L1: GoTrue acepta el codigo sin devolver sesion: no hay logout que hacer, pero la purga se hace IGUAL. */
    @Test
    fun aVerificationWithoutSessionStillPurgesTheSessions() = testApplication {
        val fake = AccessRequestFakeSupabase().apply { verifyWithoutSession = true }
        val row = fake.seed(email).also { fake.codes[email] = code }
        application { module(fake) }

        assertEquals(HttpStatusCode.NoContent, verify().status)

        assertTrue(fake.logouts().isEmpty())
        assertEquals(listOf(row.usuarioId), fake.purgedSessions.toList())
        val calls = fake.calls()
        assertTrue(
            calls.indexOf("PUT /auth/v1/admin/users/${row.usuarioId}") < calls.indexOf("POST /rest/v1/rpc/$ADMIN_PURGE_USER_SESSIONS_RPC"),
            "la purga va despues del baneo: $calls",
        )
        assertTrue(row.verificadoEn != null)
    }

    /** L1 de punta a punta: verificar purga tras el baneo y aprobar purga justo ANTES del desbaneo. */
    @Test
    fun approvalPurgesTheSessionsRightBeforeTheUnban() = testApplication {
        val fake = AccessRequestFakeSupabase()
        val roles = RolesStub()
        application { flowModule(fake, roles) }
        submit()
        val row = fake.rows.single()
        fake.codes[email] = code
        assertEquals(HttpStatusCode.NoContent, verify().status)
        val before = fake.sent.size

        assertEquals(HttpStatusCode.OK, approve(row.id).status)

        assertEquals(listOf(row.usuarioId, row.usuarioId), fake.purgedSessions.toList())
        val approveCalls = fake.sent.drop(before).map { it.call }
        val purge = approveCalls.indexOf("POST /rest/v1/rpc/$ADMIN_PURGE_USER_SESSIONS_RPC")
        val unban = approveCalls.indexOf("PUT /auth/v1/admin/users/${row.usuarioId}")
        assertTrue(purge in 0 until unban, "purga y despues desbaneo: $approveCalls")
    }

    /** L1 en la reanudacion de punta a punta: la purga va antes del desbaneo. */
    @Test
    fun resumingAStuckApprovalPurgesBeforeUnbanning() = testApplication {
        val fake = AccessRequestFakeSupabase()
        val row = fake.seed(email, estado = ADMIN_ACCESS_ESTADO_APROBADA, verified = true)
        fake.accounts.getValue(row.usuarioId).banned = true
        val roles = RolesStub()
        application { flowModule(fake, roles) }

        assertEquals(HttpStatusCode.OK, approve(row.id).status)

        val calls = fake.calls()
        assertEquals(listOf(row.usuarioId), fake.purgedSessions.toList())
        assertTrue(
            calls.indexOf("POST /rest/v1/rpc/$ADMIN_PURGE_USER_SESSIONS_RPC") < calls.indexOf("PUT /auth/v1/admin/users/${row.usuarioId}"),
            "$calls",
        )
        // Revision #3 (c): la reanudacion leyo la cuenta de Auth antes de nada.
        assertTrue(calls.indexOf("GET /auth/v1/admin/users/${row.usuarioId}") in 0 until calls.indexOf("POST /rest/v1/rpc/$ADMIN_PURGE_USER_SESSIONS_RPC"))
    }
}
