package com.oposicontrol.backend

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guarda de correo de las solicitudes de acceso (B1 2026-10-13 + plan del propietario 2026-10-14). Recorre ruta -> guarda
 * REAL -> gateway REAL de solicitudes -> Supabase falso ([AccessRequestFakeSupabase], graba cada peticion) y ruta ->
 * gateway de auth REAL -> el mismo Supabase falso:
 *
 *  - solicitud VERIFICADA y no aprobada: retiene (exito normal, ninguna peticion de correo a GoTrue);
 *  - solicitud SIN VERIFICAR (caducada o no) con la cuenta nunca confirmada y sin roles: no reserva el correo. Se borra la
 *    cuenta (DELETE de administracion), se audita `solicitud_acceso_caducada` y la ruta sigue su flujo normal;
 *  - sin verificar pero con la cuenta confirmada o con roles: nunca se borra, se retiene y queda un WARN.
 */
class AccessRequestMailGuardTest {
    private val email = "ada@example.com"
    private val password = "S3creta-Larga-9731"

    private fun Application.module(fake: AccessRequestFakeSupabase, limiter: OposiBotsAuthRateLimiter = TestAllowAllLimiter) {
        install(ContentNegotiation) { json(testJson) }
        installRequestCorrelation()
        installBackendStatusPages()
        installRequestBodyLimit()
        val configuration = testCanonicalConfiguration()
        val runtime = OposiBotsAuthRuntime(
            configuration = configuration,
            gateway = authGatewayWithoutAccessRequests(configuration, testJson, fake.client),
            rateLimiter = limiter,
            json = testJson,
            canonical = CanonicalTestEnv().runtime().canonical,
            accessRequestMailGuard = fake.guard(),
        )
        routing { oposiBotsAuthRoutes(runtime) }
    }

    private data class MailRoute(val path: String, val goTruePath: String, val body: (String) -> String)

    private val mailRoutes = listOf(
        MailRoute("/v1/auth/register", "/auth/v1/signup") { """{"email":"$it","password":"$password"}""" },
        MailRoute("/v1/auth/email/resend", "/auth/v1/resend") { """{"email":"$it"}""" },
        MailRoute("/v1/auth/recover", "/auth/v1/recover") { """{"email":"$it"}""" },
    )

    private suspend fun ApplicationTestBuilder.call(route: MailRoute, email: String): HttpResponse =
        client.post(route.path) {
            contentType(ContentType.Application.Json)
            setBody(route.body(email))
        }

    /** Todo lo que ve el cliente salvo el id de peticion, que es distinto en cada llamada por construccion. */
    private suspend fun HttpResponse.wire(): Triple<HttpStatusCode, List<Byte>, Map<String, List<String>>> = Triple(
        status,
        readRawBytes().toList(),
        headers.entries().filterNot { it.key.equals("X-Request-Id", ignoreCase = true) }.associate { it.key to it.value },
    )

    private fun <T> capturingAllLogs(block: (ListAppender<ILoggingEvent>) -> T): T {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as LogbackLogger
        val appender = ListAppender<ILoggingEvent>().also { it.start(); root.addAppender(it) }
        return try {
            block(appender)
        } finally {
            root.detachAppender(appender)
        }
    }

    private fun ListAppender<ILoggingEvent>.everything(): String = list.joinToString("\n") { event ->
        event.formattedMessage + " " + event.argumentArray?.joinToString(" ").orEmpty() + " " +
            generateSequence(event.throwableProxy) { it.cause }.joinToString(" ") { "${it.className}: ${it.message}" }
    }

    private fun AccessRequestFakeSupabase.mailCalls(route: MailRoute) = sent.count { it.path == route.goTruePath }

    // ------------------------------------------------------------------------------------------------ verificada: retiene

    @Test
    fun aVerifiedPendingRequestHoldsTheMailOnTheThreeRoutesWithTheNormalSuccessAnswer() {
        for (route in mailRoutes) testApplication {
            val fake = AccessRequestFakeSupabase()
            val row = fake.seed(email, verified = true)
            application { module(fake) }

            val normal = call(route, "free@example.com")
            assertEquals(1, fake.mailCalls(route), route.path)
            val held = call(route, email)

            // Ni una peticion de correo mas a GoTrue, y la cuenta sigue ahi.
            assertEquals(1, fake.mailCalls(route), route.path)
            assertTrue(row.usuarioId in fake.accounts, route.path)
            assertTrue(fake.sent.none { it.method.value == "DELETE" }, route.path)
            // Byte a byte la misma respuesta: no revela que exista una solicitud.
            val normalWire = normal.wire()
            val heldWire = held.wire()
            assertEquals(normalWire.first, heldWire.first, route.path)
            assertContentEquals(normalWire.second, heldWire.second, route.path)
            assertEquals(normalWire.third, heldWire.third, route.path)
        }
    }

    @Test
    fun theHeldAnswersAreTheDocumentedSuccessShapes() = testApplication {
        val fake = AccessRequestFakeSupabase()
        fake.seed(email, verified = true)
        application { module(fake) }

        val register = call(mailRoutes[0], email)
        val resend = call(mailRoutes[1], email)
        val recover = call(mailRoutes[2], email)

        assertEquals(HttpStatusCode.Accepted, register.status)
        assertEquals("""{"verificationRequired":true}""", register.bodyAsText())
        assertEquals(HttpStatusCode.NoContent, resend.status)
        assertEquals("", resend.bodyAsText())
        assertEquals(HttpStatusCode.NoContent, recover.status)
        assertEquals("", recover.bodyAsText())
        assertEquals(emptyList(), fake.mails().map { it.path })
    }

    @Test
    fun aVerifiedRejectedRequestWhoseAccountWasNotRemovedStillHoldsTheMail() {
        for (route in mailRoutes) testApplication {
            val fake = AccessRequestFakeSupabase()
            fake.seed(email, estado = ADMIN_ACCESS_ESTADO_RECHAZADA, verified = true)
            application { module(fake) }

            val response = call(route, email)

            assertTrue(response.status == HttpStatusCode.Accepted || response.status == HttpStatusCode.NoContent, route.path)
            assertEquals(emptyList(), fake.mails().map { it.path }, route.path)
        }
    }

    // ------------------------------------------------------------------------------------------------ sin verificar: libera

    /**
     * El titular del correo no se queda fuera por una solicitud que nadie verifico: `register` la libera caducada o no;
     * `resend` y `recover` solo caducada (M4, abajo la viva).
     */
    @Test
    fun anUnverifiedRequestWithANeverConfirmedAccountWithoutRolesIsReleasedAndTheNormalFlowFollows() {
        val cases = listOf(60L to mailRoutes[0]) + mailRoutes.map { 7_200L to it }
        for ((createdAgo, route) in cases) testApplication {
            val fake = AccessRequestFakeSupabase()
            val row = fake.seed(email, createdAgoSeconds = createdAgo)
            application { module(fake) }
            val label = "${route.path} (creada hace $createdAgo s)"

            val response = call(route, email)

            assertTrue(response.status == HttpStatusCode.Accepted || response.status == HttpStatusCode.NoContent, label)
            // El orden exacto: la solicitud, la cuenta (nunca confirmada), sus roles (ninguno), el BORRADO, la auditoria y
            // SOLO DESPUES la peticion normal de correo a GoTrue.
            assertEquals(
                listOf(
                    "GET /rest/v1/$ADMIN_ACCESS_TABLE",
                    "GET /auth/v1/admin/users/${row.usuarioId}",
                    "GET /rest/v1/roles_usuario",
                    "DELETE /auth/v1/admin/users/${row.usuarioId}",
                    "POST /rest/v1/auditoria_log",
                    "POST ${route.goTruePath}",
                ),
                fake.calls(),
                label,
            )
            assertFalse(row.usuarioId in fake.accounts, label)
            assertTrue(fake.rows.isEmpty(), "la fila se fue con la cuenta (cascada): $label")
            val audit = fake.audits().single()
            assertEquals(ACTION_ACCESS_REQUEST_EXPIRED, audit["accion"]!!.jsonPrimitive.content, label)
            assertEquals(row.id, audit["registro_id"]!!.jsonPrimitive.content, label)
            assertEquals(ADMIN_ACCESS_TABLE, audit["tabla_afectada"]!!.jsonPrimitive.content, label)
            assertFalse("usuario_id" in audit, "plano publico: sin actor ($label)")
            val despues = audit["despues"]!!.jsonObject
            assertEquals(row.usuarioId, despues["usuarioId"]!!.jsonPrimitive.content, label)
            assertEquals(AccessRequestReleaseMotive.MAILBOX_OWNER.wire, despues["motivo"]!!.jsonPrimitive.content, label)
            assertEquals("true", despues["cuentaEliminada"]!!.jsonPrimitive.content, label)
            assertEquals((createdAgo > ADMIN_ACCESS_UNVERIFIED_TTL_SECONDS).toString(), despues["caducada"]!!.jsonPrimitive.content, label)
            assertFalse(email in audit.toString(), "la auditoria no copia el correo ($label)")
        }
    }

    /** Con roles la cuenta nunca se toca: retiene (exito normal sin correo) y deja un WARN. Caducada o no, en las tres rutas. */
    @Test
    fun anUnverifiedRequestWhoseAccountHasRolesIsNeverTouchedAndHoldsWithAWarn() {
        for (route in mailRoutes) {
            val logs = capturingAllLogs { appender ->
                testApplication {
                    val fake = AccessRequestFakeSupabase()
                    val row = fake.seed(email, createdAgoSeconds = 7_200, accountConfirmed = true, roles = setOf("aaaaaaaa-0000-4000-8000-00000000000b"))
                    application { module(fake) }

                    val response = call(route, email)

                    assertTrue(response.status == HttpStatusCode.Accepted || response.status == HttpStatusCode.NoContent, route.path)
                    assertTrue(row.usuarioId in fake.accounts, route.path)
                    assertEquals(listOf(row), fake.rows.toList(), route.path)
                    assertTrue(fake.sent.none { it.method.value == "DELETE" || it.method.value == "PUT" }, route.path)
                    assertEquals(emptyList(), fake.mails().map { it.path }, route.path)
                    assertTrue(fake.audits().isEmpty(), route.path)
                }
                appender.list.filter { it.level == Level.WARN }.joinToString("\n") { it.formattedMessage }
            }
            assertTrue("unverified access request kept: its auth account has roles" in logs, "${route.path}: $logs")
            assertFalse(email in logs, logs)
        }
    }

    /**
     * H1: la cuenta del creador de la solicitud esta CONFIRMADA (el titular del buzon pulso un enlace o enlazo Google/Apple)
     * y no tiene roles: NO se retiene. Se neutraliza la credencial del creador (contrasena aleatoria + inicio con ella +
     * revocacion global), se borra SOLO la fila, se audita `credencial_neutralizada` y la ruta sigue: el correo SALE.
     * Tambien en `resend` y `recover` con la solicitud viva: una cuenta confirmada solo la confirma el titular (M4 intacto).
     */
    @Test
    fun aConfirmedAccountWithoutRolesHasItsCreatorCredentialNeutralisedAndTheOwnerMailGoesOut() {
        for (createdAgo in listOf(60L, 7_200L)) for (route in mailRoutes) testApplication {
            val fake = AccessRequestFakeSupabase()
            val row = fake.seed(email, createdAgoSeconds = createdAgo, accountConfirmed = true)
            application { module(fake) }
            val label = "${route.path} (hace $createdAgo s)"

            val response = call(route, email)

            assertTrue(response.status == HttpStatusCode.Accepted || response.status == HttpStatusCode.NoContent, label)
            val tail = listOf(
                "GET /auth/v1/admin/users/${row.usuarioId}",
                "GET /rest/v1/roles_usuario",
                "PUT /auth/v1/admin/users/${row.usuarioId}",
                "POST /auth/v1/token",
                "POST /auth/v1/logout",
                "DELETE /rest/v1/$ADMIN_ACCESS_TABLE",
                "POST /rest/v1/auditoria_log",
                "POST ${route.goTruePath}",
            )
            assertEquals(tail, fake.calls().takeLast(tail.size), label)
            val account = fake.accounts.getValue(row.usuarioId)
            assertEquals(1, account.passwordChanges.size, label)
            assertFalse(account.password == AccessRequestFakeSupabase.ATTACKER_PASSWORD, label)
            assertEquals("scope=global", fake.logouts().single().query, label)
            assertEquals("Bearer neutralise.access.jwt", fake.logouts().single().headers[io.ktor.http.HttpHeaders.Authorization], label)
            assertTrue(fake.rows.isEmpty(), label)
            assertTrue(row.usuarioId in fake.accounts, "la cuenta se queda para el titular ($label)")
            val despues = fake.audits().single()["despues"]!!.jsonObject
            assertEquals(ACCESS_REQUEST_CREDENTIAL_NEUTRALISED_MOTIVE, despues["motivo"]!!.jsonPrimitive.content, label)
            assertEquals(AccessRequestReleaseMotive.MAILBOX_OWNER.wire, despues["origen"]!!.jsonPrimitive.content, label)
            assertEquals("true", despues["credencialNeutralizada"]!!.jsonPrimitive.content, label)
            assertFalse(account.password!! in fake.sent.filter { it.path != "/auth/v1/admin/users/${row.usuarioId}" && it.path != "/auth/v1/token" }.joinToString { it.body + it.query }, label)
        }
    }

    /** H1, caso del titular: confirmo la cuenta que creo el atacante y pide recuperar la contrasena: el correo sale ya. */
    @Test
    fun ownerRecoverAfterConfirmationSendsTheMail() = testApplication {
        val fake = AccessRequestFakeSupabase()
        val row = fake.seed(email)
        fake.accounts.getValue(row.usuarioId).confirmed = true
        application { module(fake) }

        val response = call(mailRoutes[2], email)

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals(listOf("/auth/v1/recover"), fake.mails().map { it.path })
        assertEquals(email, fake.mails().single().jsonBody()["email"]!!.jsonPrimitive.content)
        assertTrue(fake.rows.isEmpty())
        assertFalse(fake.accounts.getValue(row.usuarioId).password == AccessRequestFakeSupabase.ATTACKER_PASSWORD)
    }

    /**
     * M4: un tercero llama a `resend` o `recover` con el correo de un solicitante cuya solicitud esta VIVA (sin verificar,
     * sin caducar, cuenta sin confirmar): retenido (exito normal, sin correo) y la solicitud y su cuenta siguen intactas.
     * `register` (el titular) si la libera al instante.
     */
    @Test
    fun aThirdPartyCannotWipeALiveRequestThroughResendOrRecover() {
        for (route in mailRoutes.drop(1)) testApplication {
            val fake = AccessRequestFakeSupabase()
            val row = fake.seed(email, createdAgoSeconds = 60)
            application { module(fake) }

            val response = call(route, email)

            assertEquals(HttpStatusCode.NoContent, response.status, route.path)
            assertEquals(listOf("GET /rest/v1/$ADMIN_ACCESS_TABLE", "GET /auth/v1/admin/users/${row.usuarioId}"), fake.calls(), route.path)
            assertEquals(listOf(row), fake.rows.toList(), route.path)
            assertTrue(row.usuarioId in fake.accounts, route.path)
            assertTrue(fake.mails().isEmpty() && fake.audits().isEmpty(), route.path)
        }
    }

    /** M4: una solicitud AGOTADA (5 codigos erroneos) cuenta como muerta: `resend` y `recover` la liberan. */
    @Test
    fun aFailedOutRequestIsReleasedByResendAndRecover() {
        for (route in mailRoutes.drop(1)) testApplication {
            val fake = AccessRequestFakeSupabase()
            val row = fake.seed(email, createdAgoSeconds = 60, attempts = ADMIN_ACCESS_VERIFY_MAX_FAILURES)
            application { module(fake) }

            call(route, email)

            assertFalse(row.usuarioId in fake.accounts, route.path)
            assertEquals(listOf(route.goTruePath), fake.mails().map { it.path }, route.path)
        }
    }

    // ------------------------------------------------------------------------------------------------ no retiene

    @Test
    fun anApprovedRequestOrNoRequestCallsGoTrueAsBefore() {
        for (route in mailRoutes) testApplication {
            val fake = AccessRequestFakeSupabase()
            fake.seed("approved@example.com", estado = ADMIN_ACCESS_ESTADO_APROBADA, verified = true)
            application { module(fake) }

            val approved = call(route, "approved@example.com")
            val none = call(route, "nobody@example.com")

            assertEquals(2, fake.mailCalls(route), route.path)
            assertEquals(approved.status, none.status, route.path)
            assertTrue(approved.status == HttpStatusCode.Accepted || approved.status == HttpStatusCode.NoContent, route.path)
            assertTrue(fake.sent.none { it.method.value == "DELETE" }, route.path)
        }
    }

    // ------------------------------------------------------------------------------------------------ correo canonico

    /**
     * La consulta de la guarda Y lo que se envia a GoTrue usan el correo canonico: espacios Unicode de los extremos fuera,
     * solo A-Z en minusculas, el resto tal cual (`İ` no se convierte). Antes resend y recover enviaban el correo solo recortado.
     */
    @Test
    fun theLookupAndTheGoTrueCallUseTheCanonicalEmail() {
        val cases = listOf(
            "  Ada@EXAMPLE.com " to "ada@example.com",
            " Ada@Example.com " to "ada@example.com",
            "\tADA@example.COM\t" to "ada@example.com",
            "İnfo@Example.com" to "İnfo@example.com",
        )
        for ((raw, canonical) in cases) for (route in mailRoutes) testApplication {
            val fake = AccessRequestFakeSupabase()
            application { module(fake) }

            val response = call(route, Json.encodeToString(kotlinx.serialization.serializer<String>(), raw).trim('"'))

            assertTrue(response.status.value in 200..299, "$raw ${route.path}: ${response.bodyAsText()}")
            val lookup = fake.sent.first()
            assertEquals("GET /rest/v1/$ADMIN_ACCESS_TABLE", lookup.call)
            assertEquals("eq.$canonical", lookup.param("email"), "$raw ${route.path}")
            val mail = fake.sent.single { it.path == route.goTruePath }
            assertEquals(canonical, mail.jsonBody()["email"]!!.jsonPrimitive.content, "$raw ${route.path}")
        }
    }

    // ------------------------------------------------------------------------------------------------ falla cerrado

    @Test
    fun aFailedStepAnswers503UpstreamUnavailableAndNeverCallsGoTrue() {
        val failures = listOf<(AccessRequestFakeSupabase.Sent) -> HttpStatusCode?>(
            { if (it.call == "GET /rest/v1/$ADMIN_ACCESS_TABLE") HttpStatusCode.InternalServerError else null },
            { if (it.path.startsWith("/auth/v1/admin/users/") && it.method.value == "GET") HttpStatusCode.BadGateway else null },
            { if (it.call == "GET /rest/v1/roles_usuario") HttpStatusCode.ServiceUnavailable else null },
            { if (it.path.startsWith("/auth/v1/admin/users/") && it.method.value == "DELETE") HttpStatusCode.InternalServerError else null },
        )
        for ((index, failure) in failures.withIndex()) for (route in mailRoutes) testApplication {
            val fake = AccessRequestFakeSupabase()
            fake.seed(email, createdAgoSeconds = 7_200)
            fake.override = failure
            application { module(fake) }

            val response = call(route, email)

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status, "fallo $index ${route.path}")
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("UPSTREAM_UNAVAILABLE", body["code"]?.jsonPrimitive?.content, "fallo $index ${route.path}")
            assertEquals(emptyList(), fake.mails().map { it.path }, "fallo $index ${route.path}")
        }
    }

    @Test
    fun theSilentPerEmailLimitStillWinsAndSkipsTheLookup() {
        for (route in mailRoutes) testApplication {
            val fake = AccessRequestFakeSupabase()
            fake.seed(email)
            application { module(fake, limiter = TestCountingLimiter(subjectAllowed = false)) }

            val response = call(route, email)

            assertTrue(response.status == HttpStatusCode.Accepted || response.status == HttpStatusCode.NoContent, route.path)
            assertEquals(emptyList(), fake.calls(), route.path)
        }
    }

    // ------------------------------------------------------------------------------------------------ logs

    @Test
    fun neitherTheHoldNorTheReleaseNorTheFailureWritesTheEmailInTheLogs() {
        val logs = capturingAllLogs { appender ->
            testApplication {
                val fake = AccessRequestFakeSupabase()
                fake.seed(email, verified = true)
                fake.seed("other@example.com")
                application { module(fake) }
                for (route in mailRoutes) call(route, email)
                call(mailRoutes[0], "other@example.com")
                fake.override = { if (it.call == "GET /rest/v1/$ADMIN_ACCESS_TABLE") HttpStatusCode.InternalServerError else null }
                for (route in mailRoutes) call(route, email)
            }
            appender.everything()
        }

        assertFalse(email in logs, logs)
        assertFalse("other@example.com" in logs, logs)
        assertTrue("held by a non-approved admin access request" in logs, logs)
        assertTrue("unverified access request released" in logs, logs)
        assertTrue("access request mail guard lookup failed" in logs, logs)
        // Seudonimo CON CLAVE (HMAC con el secreto del limitador), no el prefijo SHA-256 sin clave de antes.
        assertTrue("emailRef=${testCanonicalConfiguration().authLogEmailRef(email)}" in logs, logs)
        assertFalse(adminAccessEmailRef(email) in logs, logs)
    }

    @Test
    fun theLogPseudonymDependsOnTheSecretAndOnTheCanonicalEmail() {
        val configuration = testCanonicalConfiguration()
        val ref = configuration.authLogEmailRef(email)

        assertEquals(12, ref.length)
        assertEquals(ref, configuration.authLogEmailRef("  ADA@example.com "))
        assertEquals(ref, configuration.authLogEmailRef(" ADA@example.com "))
        assertFalse(ref == configuration.copy(rateLimitHmacSecret = "another-rate-limit-secret-32-chars!!").authLogEmailRef(email))
        assertFalse(ref == adminAccessEmailRef(email))
        // Separado de las claves del limitador: no es el HMAC del correo a secas.
        assertFalse(ref == RateLimitKeyHasher(configuration.rateLimitHmacSecret).hash(email).take(12))
    }

    // ------------------------------------------------------------------------------------------------ la guarda sola

    @Test
    fun theLookupPathEncodesTheEmailSoItCannotAddFilters() {
        val path = accessRequestHoldPath("a+b&estado=eq.aprobada@x.io")

        assertEquals(
            "/rest/v1/solicitudes_acceso_admin?select=id,usuario_id,estado,correo_verificado_en,creado_en,intentos_verificacion" +
                "&email=eq.a%2Bb%26estado%3Deq.aprobada%40x.io&estado=neq.aprobada&order=creado_en.asc&limit=10",
            path,
        )
    }

    /** Un correo con espacios dentro (ASCII o Unicode) o controles es un 400 de identificador invalido y no se consulta nada. */
    @Test
    fun aMalformedEmailNeverReachesTheUpstreamAndIsAValidationError() = runTest {
        for (bad in listOf("a b@example.com", "a b@example.com", "a b@example.com", "a\u0000b@example.com", "ab", "a".repeat(321))) {
            val fake = AccessRequestFakeSupabase()

            val problem = assertFailsWith<OposiBotsAuthProblem> { fake.guard().holdsMail(bad, OposiBotsAuthOperation.REGISTER) }

            assertEquals(HttpStatusCode.BadRequest, problem.status, bad)
            assertEquals(CanonicalErrorCode.VALIDATION, problem.canonicalCode(), bad)
            assertEquals(setOf(OposiBotsValidationIssue.INVALID_IDENTIFIER), problem.issues, bad)
            assertEquals(emptyList(), fake.calls(), bad)
        }
    }

    /** Las tres rutas contestan el 400 de siempre ANTES de la guarda: ni lectura, ni GoTrue, ni linea de fallo. */
    @Test
    fun anEmailWithInternalWhitespaceIsTheUsualValidationErrorOnTheThreeRoutes() {
        val logs = capturingAllLogs { appender ->
            for (route in mailRoutes) testApplication {
                val fake = AccessRequestFakeSupabase()
                application { module(fake) }

                val unicode = call(route, "a da@example.com")
                val tab = call(route, "a\\tda@example.com")
                val asciiSpace = call(route, "a da@example.com")

                for (response in listOf(unicode, tab, asciiSpace)) {
                    assertEquals(HttpStatusCode.BadRequest, response.status, route.path)
                    val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    assertEquals("VALIDATION", body["code"]?.jsonPrimitive?.content, route.path)
                    assertTrue("INVALID_IDENTIFIER" in body.toString(), route.path)
                }
                assertEquals(emptyList(), fake.calls(), route.path)
            }
            appender.everything()
        }

        assertFalse("lookup failed" in logs, logs)
    }

    // ------------------------------------------------------------------------------------------------ cableado

    /**
     * El runtime que monta `fromEnvironment` (todo menos leer el entorno) usa la guarda REAL en los dos sitios: la de
     * correo del runtime y la puerta de sesion del gateway son la MISMA instancia de [PostgrestAccessRequestMailGuard].
     * No abre ninguna conexion al construirse.
     */
    @Test
    fun theProductionWiringRoutesBothTheMailGuardAndTheSessionGateThroughTheRealGuard() {
        val runtime = OposiBotsAuthRuntime.fromConfiguration(testCanonicalConfiguration(), testJson)
        try {
            val mailGuard = runtime.accessRequestMailGuard
            val sessionGate = (runtime.gateway as SupabaseOposiBotsAuthGateway).accessRequestSessionGate

            assertTrue(mailGuard is PostgrestAccessRequestMailGuard, mailGuard::class.toString())
            assertTrue(sessionGate is PostgrestAccessRequestMailGuard, sessionGate::class.toString())
            assertTrue(mailGuard === sessionGate, "una sola guarda real para el correo y para la sesion")
        } finally {
            runtime.close()
        }
    }

    @Test
    fun theProductionFactoryBuildsThePostgrestGuard() {
        val guard: Any = OposiBotsAuthRuntime.accessRequestMailGuard(testCanonicalConfiguration())
        try {
            assertTrue(guard is AccessRequestMailGuard && guard is AccessRequestSessionGate)
        } finally {
            (guard as AutoCloseable).close()
        }
    }
}
