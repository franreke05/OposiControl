package com.oposicontrol.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SLICE 1 · D1-11/12/13/16/17: rutas canonicas de perfil, especialidad y documentos legales.
 * Perfil canonico REAL ([SupabaseCanonicalProfileService]) sobre Supabase y GoTrue falsos.
 */
class CanonicalProfileRoutesTest {

    private suspend fun HttpClient.call(
        method: HttpMethod,
        path: String,
        body: String? = null,
        token: String? = TEST_TOKEN,
        headers: Map<String, String> = emptyMap(),
        contentType: ContentType? = ContentType.Application.Json,
    ): HttpResponse = request(path) {
        this.method = method
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        headers.forEach { (k, v) -> header(k, v) }
        if (body != null && method != HttpMethod.Get) {
            contentType?.let { contentType(it) }
            setBody(body)
        }
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun env(setup: CanonicalTestEnv.() -> Unit = {}) = CanonicalTestEnv().apply {
        upstream.onGet = { path -> if ("perfiles_usuario" in path) profileRowJson() else "[]" }
        setup()
    }

    private fun ApplicationTestBuilder.mount(env: CanonicalTestEnv) = application { env.mount(this) }

    // ------------------------------------------------------------------ identidad / estado

    @Test
    fun everyAuthenticatedRouteAnswers401WithoutABearerAndNothingReachesSupabase() = testApplication {
        val env = env()
        mount(env)
        listOf(
            HttpMethod.Get to "/api/v1/app/me",
            HttpMethod.Post to "/api/v1/app/me/onboarding",
            HttpMethod.Patch to "/api/v1/app/me/profile",
            HttpMethod.Post to "/api/v1/app/me/legal-acceptances",
            HttpMethod.Delete to "/api/v1/app/me",
        ).forEach { (method, path) ->
            val response = client.call(method, path, body = "{}", token = null)
            assertEquals(HttpStatusCode.Unauthorized, response.status, "$method $path")
            assertEquals("Bearer", response.headers[HttpHeaders.WWWAuthenticate])
            assertEquals("UNAUTHENTICATED", response.json().str("code"))
        }
        // Un dev header no es identidad canonica y un token invalido tampoco.
        assertEquals(HttpStatusCode.Unauthorized, client.call(HttpMethod.Get, "/api/v1/app/me", token = null, headers = mapOf("X-Usuario-Id" to TEST_UID)).status)
        assertEquals(HttpStatusCode.Unauthorized, client.call(HttpMethod.Get, "/api/v1/app/me", token = "x.y.z").status)
        assertTrue(env.upstream.log.isEmpty() && env.auth.calls.isEmpty())
    }

    @Test
    fun everyNonActiveStateIsDeniedOn403WithItsCodeOnEveryActiveRouteAndNothingIsWritten() = testApplication {
        val states = mapOf(
            "bloqueada" to "ACCOUNT_BLOCKED",
            "eliminacion_pendiente" to "ACCOUNT_DELETION_PENDING",
            "DISABLED" to "FORBIDDEN",
            "deleted" to "FORBIDDEN",
            "ANONYMIZED" to "FORBIDDEN",
            "" to "FORBIDDEN",
        )
        val env = env()
        mount(env)
        val requests = listOf(
            HttpMethod.Get to ("/api/v1/app/me" to null),
            HttpMethod.Post to ("/api/v1/app/me/onboarding" to """{"username":"ada_l","nombre":"Ada","apellidos":"L","acceptedLegalDocumentIds":[]}"""),
            HttpMethod.Patch to ("/api/v1/app/me/profile" to """{"nombre":"Ada"}"""),
            HttpMethod.Post to ("/api/v1/app/me/legal-acceptances" to """{"documentIds":["$TEST_DOCUMENT"],"plataforma":"android"}"""),
        )
        states.forEach { (state, code) ->
            env.status = state
            requests.forEach { (method, target) ->
                val response = client.call(method, target.first, target.second)
                assertEquals(HttpStatusCode.Forbidden, response.status, "$state $method ${target.first}")
                assertEquals(code, response.json().str("code"), "$state ${target.first}")
            }
        }
        env.status = null // columna a NULL
        assertEquals("FORBIDDEN", client.call(HttpMethod.Get, "/api/v1/app/me").json().str("code"))
        env.status = "activa"
        env.profileExists = false
        val missing = client.call(HttpMethod.Get, "/api/v1/app/me")
        assertEquals(HttpStatusCode.Forbidden, missing.status)
        assertEquals("PROFILE_MISSING", missing.json().str("code"))
        assertTrue(env.upstream.rpcCalls.isEmpty() && env.upstream.log.isEmpty(), "sin identidad activa no se lee ni se escribe nada: ${env.upstream.log}")
    }

    // ------------------------------------------------------------------ D1-11 GET /me

    @Test
    fun getMeReadsPerfilesUsuarioByTheJwtSubjectAndReturnsTheFrozenShape() = testApplication {
        val env = env()
        mount(env)
        val response = client.call(HttpMethod.Get, "/api/v1/app/me")

        assertEquals(HttpStatusCode.OK, response.status)
        val me = response.json()
        assertEquals(TEST_UID, me.str("id"))
        assertEquals("person@example.com", me.str("email"))
        assertEquals(listOf("email"), me["authProviders"]!!.jsonArray.strings())
        assertEquals("2026-01-02T03:04:05.123456Z", me.str("registeredAt"))
        val profile = me["profile"]!!.jsonObject
        assertEquals("ada_l", profile.str("username"))
        assertEquals("Ada", profile.str("nombreMostrar"))
        assertEquals("Lovelace", profile.str("apellidos"))
        assertEquals(TEST_SPECIALTY, profile.str("especialidadPrincipalId"))
        assertEquals("true", profile["onboardingCompleted"]!!.jsonPrimitive.content)
        assertEquals("activa", profile.str("accountStatus"))
        assertNull(profile.str("avatarUrl"), "avatar aplazado (D1-14): nunca se expone la ruta guardada")
        assertEquals("private, no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals("\"2026-09-30T10:00:00.123456Z\"", response.headers[HttpHeaders.ETag])
        assertTrue(response.headers["X-Request-Id"]!!.isNotBlank())

        val read = env.upstream.log.single()
        assertTrue(read.startsWith("GET /rest/v1/perfiles_usuario?"), read)
        assertTrue("usuario_id=eq.$TEST_UID" in read, read)
        assertFalse("avatar_url" in read, "la columna del avatar ni se lee")
        assertFalse("/rest/v1/usuario?" in read, "nunca public.usuario")
    }

    @Test
    fun getMeMapsProvidersAndDropsUnknownOnesAndDegradesNothingSilently() = testApplication {
        val env = env()
        env.auth.user = AuthProviderUser(TEST_UID, null, listOf("apple", "google", "github", "email", "google"), null)
        mount(env)
        val me = client.call(HttpMethod.Get, "/api/v1/app/me").json()
        assertEquals(listOf("apple", "google", "email"), me["authProviders"]!!.jsonArray.strings())
        assertNull(me.str("email"))
        assertEquals("2026-01-02T03:04:05.123456Z", me.str("registeredAt"), "sin created_at de GoTrue se usa el del perfil")
    }

    @Test
    fun getMeWithARevokedGoTrueSessionIs401AndAnAuthOutageIs503() = testApplication {
        val env = env()
        mount(env)
        env.auth.userFailure = CanonicalErrorCode.UNAUTHENTICATED.exception()
        assertEquals(HttpStatusCode.Unauthorized, client.call(HttpMethod.Get, "/api/v1/app/me").status)
        env.auth.userFailure = CanonicalErrorCode.UPSTREAM_UNAVAILABLE.exception()
        val down = client.call(HttpMethod.Get, "/api/v1/app/me")
        assertEquals(HttpStatusCode.ServiceUnavailable, down.status)
        assertEquals("UPSTREAM_UNAVAILABLE", down.json().str("code"))
    }

    @Test
    fun aMissingProfileRowAtReadTimeIsProfileMissingNeverAutoCreated() = testApplication {
        val env = env { upstream.onGet = { "[]" } }
        mount(env)
        val response = client.call(HttpMethod.Get, "/api/v1/app/me")
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("PROFILE_MISSING", response.json().str("code"))
        assertTrue(env.upstream.rpcCalls.isEmpty())
    }

    // ------------------------------------------------------------------ D1-12 onboarding

    private val onboardingBody = """{"username":"ada_l","nombre":"Ada","apellidos":"Lovelace","especialidadId":"$TEST_SPECIALTY","acceptedLegalDocumentIds":["$TEST_DOCUMENT"],"attribution":{"fuente":"google","plataforma":"android"},"referralCode":"AMIGO-1"}"""

    @Test
    fun onboardingCallsOneRpcWithTheJwtSubjectAndReturnsMeWithoutTheRawAvatar() = testApplication {
        val env = env { upstream.onRpc = { _, _ -> rpcOk(profileRpcJson()) } }
        mount(env)
        val response = client.call(HttpMethod.Post, "/api/v1/app/me/onboarding", onboardingBody)

        assertEquals(HttpStatusCode.OK, response.status)
        val text = response.bodyAsText()
        assertFalse("secret-path" in text, "el avatar almacenado no se expone: $text")
        val me = Json.parseToJsonElement(text).jsonObject
        assertEquals(TEST_UID, me.str("id"))
        assertEquals("true", me["profile"]!!.jsonObject["onboardingCompleted"]!!.jsonPrimitive.content)
        assertEquals("\"$TEST_UPDATED_AT\"", response.headers[HttpHeaders.ETag])

        assertEquals(listOf("app_completar_onboarding"), env.upstream.rpcNames())
        val args = env.upstream.lastArgs("app_completar_onboarding")
        assertEquals(TEST_UID, args.str("p_usuario_id"), "identidad = sub del JWT")
        assertEquals("ada_l", args.str("p_username"))
        assertEquals(TEST_SPECIALTY, args.str("p_especialidad_id"))
        assertEquals(listOf(TEST_DOCUMENT), args["p_documentos"]!!.jsonArray.strings())
        assertEquals("android", args.str("p_plataforma"))
        assertEquals("google", args["p_attribution"]!!.jsonObject.str("fuente"))
        assertEquals("AMIGO-1", args.str("p_referral_code"))
        assertTrue(env.upstream.log.none { it.startsWith("PATCH") || "usuario?" in it })
    }

    @Test
    fun onboardingIsIdempotentOnAnIdenticalReplay() = testApplication {
        val env = env { upstream.onRpc = { _, _ -> rpcOk(profileRpcJson(replay = true)) } }
        mount(env)
        val first = client.call(HttpMethod.Post, "/api/v1/app/me/onboarding", onboardingBody)
        val second = client.call(HttpMethod.Post, "/api/v1/app/me/onboarding", onboardingBody)
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(first.bodyAsText(), second.bodyAsText())
    }

    @Test
    fun onboardingMapsEveryStableRpcErrorKeyToItsCanonicalCode() = testApplication {
        val env = env()
        mount(env)
        val cases = listOf(
            Triple(rpcError(409, "23505", "USERNAME_TAKEN"), HttpStatusCode.Conflict, "USERNAME_TAKEN"),
            Triple(rpcError(400, "OC409", "ALREADY_ONBOARDED_CONFLICT"), HttpStatusCode.Conflict, "ALREADY_ONBOARDED_CONFLICT"),
            Triple(rpcError(400, "22023", "UNKNOWN_SPECIALTY"), HttpStatusCode.UnprocessableEntity, "UNKNOWN_SPECIALTY"),
            Triple(rpcError(400, "22023", "DOCUMENT_NOT_ACTIVE"), HttpStatusCode.UnprocessableEntity, "LEGAL_VERSION_NOT_ACTIVE"),
            Triple(rpcError(403, "42501", "ACCOUNT_BLOCKED"), HttpStatusCode.Forbidden, "ACCOUNT_BLOCKED"),
            Triple(rpcError(403, "42501", "ACCOUNT_DELETION_PENDING"), HttpStatusCode.Forbidden, "ACCOUNT_DELETION_PENDING"),
            Triple(rpcError(400, "OC404", "PROFILE_MISSING"), HttpStatusCode.Forbidden, "PROFILE_MISSING"),
            Triple(rpcError(403, "42501", "FORBIDDEN"), HttpStatusCode.Forbidden, "FORBIDDEN"),
            Triple(rpcError(400, "22023", "INVALID_ARGUMENT", hint = "username"), HttpStatusCode.BadRequest, "VALIDATION"),
        )
        cases.forEach { (rpc, status, code) ->
            env.upstream.onRpc = { _, _ -> rpc }
            val response = client.call(HttpMethod.Post, "/api/v1/app/me/onboarding", onboardingBody)
            assertEquals(status, response.status, code)
            assertEquals(code, response.json().str("code"), code)
        }
    }

    @Test
    fun onboardingValidatesInputBeforeTheDatabase() = testApplication {
        val env = env()
        mount(env)
        val bad = mapOf(
            "username" to """{"username":"a b","nombre":"Ada","apellidos":"L","acceptedLegalDocumentIds":[]}""",
            "nombre" to """{"username":"ada_l","nombre":"   ","apellidos":"L","acceptedLegalDocumentIds":[]}""",
            "especialidadId" to """{"username":"ada_l","nombre":"Ada","apellidos":"L","especialidadId":"nope","acceptedLegalDocumentIds":[]}""",
            "acceptedLegalDocumentIds[0]" to """{"username":"ada_l","nombre":"Ada","apellidos":"L","acceptedLegalDocumentIds":["nope"]}""",
            "referralCode" to """{"username":"ada_l","nombre":"Ada","apellidos":"L","acceptedLegalDocumentIds":[],"referralCode":"has spaces!"}""",
            "attribution.plataforma" to """{"username":"ada_l","nombre":"Ada","apellidos":"L","acceptedLegalDocumentIds":["$TEST_DOCUMENT"]}""",
        )
        bad.forEach { (field, body) ->
            val response = client.call(HttpMethod.Post, "/api/v1/app/me/onboarding", body)
            assertEquals(HttpStatusCode.BadRequest, response.status, field)
            val json = response.json()
            assertEquals("VALIDATION", json.str("code"), field)
            assertTrue(field in json.issueFields(), "$field en ${json.issueFields()}")
        }
        // Campos obligatorios ausentes.
        val missing = client.call(HttpMethod.Post, "/api/v1/app/me/onboarding", "{}").json()
        assertEquals(setOf("username", "nombre", "apellidos", "acceptedLegalDocumentIds"), missing.issueFields().toSet())
        assertTrue(env.upstream.rpcCalls.isEmpty(), "ninguna peticion invalida llega a la base")
    }

    @Test
    fun onboardingRejectsServerOwnedFieldsAndACallerIdAsUnknownField() = testApplication {
        val env = env()
        mount(env)
        val fields = listOf(
            "usuarioId", "userId", "id", "roles", "roleIds", "accountStatus", "estado_cuenta", "onboardingCompleted",
            "es_invitado", "creadoEn", "aceptadoEn", "avatarUrl", "authProviders", "email", "saldo", "tickets", "racha", "ranking",
        )
        fields.forEach { field ->
            val response = client.call(
                HttpMethod.Post,
                "/api/v1/app/me/onboarding",
                """{"username":"ada_l","nombre":"Ada","apellidos":"L","acceptedLegalDocumentIds":[],"$field":"x"}""",
            )
            assertEquals(HttpStatusCode.BadRequest, response.status, field)
            val json = response.json()
            assertEquals("VALIDATION", json.str("code"), field)
            assertEquals(listOf(field), json.issueFields(), field)
            assertEquals(listOf("UNKNOWN_FIELD"), json.issueNames(), field)
        }
        assertTrue(env.upstream.rpcCalls.isEmpty())
    }

    @Test
    fun onboardingWithAListSpecialtyOrAWrongContentTypeIsRejected() = testApplication {
        val env = env()
        mount(env)
        val list = client.call(
            HttpMethod.Post,
            "/api/v1/app/me/onboarding",
            """{"username":"ada_l","nombre":"Ada","apellidos":"L","especialidadId":["$TEST_SPECIALTY"],"acceptedLegalDocumentIds":[]}""",
        )
        assertEquals(HttpStatusCode.BadRequest, list.status, "DEC-002: una sola especialidad, nunca una lista")
        val plain = client.call(HttpMethod.Post, "/api/v1/app/me/onboarding", onboardingBody, contentType = ContentType.Text.Plain)
        assertEquals(HttpStatusCode.UnsupportedMediaType, plain.status)
        assertTrue(env.upstream.rpcCalls.isEmpty())
    }

    // ------------------------------------------------------------------ D1-13 PATCH /me/profile (+ especialidad DEC-002)

    @Test
    fun patchSendsOnlyThePresentWritableKeysToOneRpcAndReturnsMe() = testApplication {
        val env = env { upstream.onRpc = { _, _ -> rpcOk(profileRpcJson(nombre = "Grace", comunidad = TEST_COMMUNITY)) } }
        mount(env)
        val response = client.call(
            HttpMethod.Patch,
            "/api/v1/app/me/profile",
            """{"nombre":"Grace","comunidadPrincipalId":"$TEST_COMMUNITY","detail":{"edad":30,"ciudad":"Madrid","estadoLaboral":"activo","fechaExamenObjetivo":"2027-06-01"},"targetCommunityIds":["$TEST_COMMUNITY"],"introSeen":true}""",
        )
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Grace", response.json()["profile"]!!.jsonObject.str("nombre"))
        assertEquals("\"$TEST_UPDATED_AT\"", response.headers[HttpHeaders.ETag])

        assertEquals(listOf("app_actualizar_perfil"), env.upstream.rpcNames())
        val args = env.upstream.lastArgs("app_actualizar_perfil")
        assertEquals(TEST_UID, args.str("p_usuario_id"))
        assertNull(args.str("p_if_match"))
        val patch = args["p_patch"]!!.jsonObject
        assertEquals(setOf("nombre", "comunidadPrincipalId", "detail", "targetCommunityIds", "introSeen"), patch.keys)
        assertEquals("Madrid", patch["detail"]!!.jsonObject.str("ciudad"))
        assertEquals("2027-06-01", patch["detail"]!!.jsonObject.str("fechaExamenObjetivo"))
    }

    @Test
    fun patchRejectsEveryServerOwnedOrDerivedFieldAsUnknownField() = testApplication {
        val env = env()
        mount(env)
        listOf(
            "usuarioId", "id", "roles", "roleIds", "accountStatus", "estadoCuenta", "estado_cuenta", "onboardingCompleted",
            "esInvitado", "creadoEn", "actualizadoEn", "introSeenAt", "avatarUrl", "authProviders", "email", "saldo", "tickets",
            "racha", "ranking", "nota", "liga", "stats", "especialidadPrincipalId", "especialidades",
        ).forEach { field ->
            val response = client.call(HttpMethod.Patch, "/api/v1/app/me/profile", """{"$field":"x"}""")
            assertEquals(HttpStatusCode.BadRequest, response.status, field)
            val json = response.json()
            assertEquals("VALIDATION", json.str("code"), field)
            assertEquals(listOf("UNKNOWN_FIELD"), json.issueNames(), field)
        }
        // Tambien dentro de `detail`.
        val nested = client.call(HttpMethod.Patch, "/api/v1/app/me/profile", """{"detail":{"ciudad":"x","creadoEn":"x"}}""")
        assertEquals(listOf("detail.creadoEn"), nested.json().issueFields())
        assertTrue(env.upstream.rpcCalls.isEmpty(), "nada llega a la base")
    }

    @Test
    fun changingTheSpecialtyReplacesTheSingleValueAndNeverReadsOrWritesHistory() = testApplication {
        val env = env { upstream.onRpc = { _, _ -> rpcOk(profileRpcJson(especialidad = TEST_SPECIALTY)) } }
        mount(env)
        val response = client.call(HttpMethod.Patch, "/api/v1/app/me/profile", """{"especialidadId":"$TEST_SPECIALTY"}""")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(TEST_SPECIALTY, response.json()["profile"]!!.jsonObject.str("especialidadPrincipalId"))
        assertEquals(listOf("app_actualizar_perfil"), env.upstream.rpcNames())
        assertEquals(TEST_SPECIALTY, env.upstream.lastArgs("app_actualizar_perfil")["p_patch"]!!.jsonObject.str("especialidadId"))
        val touched = env.upstream.log.joinToString(" ")
        listOf("intentos_examen", "respuestas", "intentos", "estadisticas", "ranking", "resultados").forEach {
            assertFalse(it in touched, "el cambio de especialidad no toca el historial: $touched")
        }
        assertTrue(env.upstream.log.none { it.startsWith("PATCH") }, "ninguna escritura directa de tablas")
    }

    @Test
    fun specialtyMustBeAScalarUuidNeverAListNeverNullNeverAnUnknownId() = testApplication {
        val env = env()
        mount(env)
        val list = client.call(HttpMethod.Patch, "/api/v1/app/me/profile", """{"especialidadId":["$TEST_SPECIALTY","$TEST_SPECIALTY"]}""")
        assertEquals(HttpStatusCode.BadRequest, list.status)
        assertEquals("VALIDATION", list.json().str("code"))
        val cleared = client.call(HttpMethod.Patch, "/api/v1/app/me/profile", """{"especialidadId":null}""")
        assertEquals(HttpStatusCode.BadRequest, cleared.status, "no se puede vaciar")
        assertEquals(listOf("especialidadId"), cleared.json().issueFields())
        val notUuid = client.call(HttpMethod.Patch, "/api/v1/app/me/profile", """{"especialidadId":"matematicas"}""")
        assertEquals(HttpStatusCode.BadRequest, notUuid.status)
        assertTrue(env.upstream.rpcCalls.isEmpty())

        env.upstream.onRpc = { _, _ -> rpcError(400, "22023", "UNKNOWN_SPECIALTY") }
        val unknown = client.call(HttpMethod.Patch, "/api/v1/app/me/profile", """{"especialidadId":"$TEST_SPECIALTY"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, unknown.status)
        assertEquals("UNKNOWN_SPECIALTY", unknown.json().str("code"))
    }

    @Test
    fun patchMapsCommunityUsernameAndArgumentErrors() = testApplication {
        val env = env()
        mount(env)
        val cases = listOf(
            Triple(rpcError(400, "22023", "UNKNOWN_COMMUNITY"), HttpStatusCode.UnprocessableEntity, "UNKNOWN_COMMUNITY"),
            Triple(rpcError(409, "23505", "USERNAME_TAKEN"), HttpStatusCode.Conflict, "USERNAME_TAKEN"),
            Triple(rpcError(400, "22023", "INVALID_ARGUMENT", hint = "edad"), HttpStatusCode.BadRequest, "VALIDATION"),
            Triple(rpcError(400, "22023", "UNKNOWN_FIELD", hint = "detail.zzz"), HttpStatusCode.BadRequest, "VALIDATION"),
        )
        cases.forEach { (rpc, status, code) ->
            env.upstream.onRpc = { _, _ -> rpc }
            val response = client.call(HttpMethod.Patch, "/api/v1/app/me/profile", """{"nombre":"Ada"}""")
            assertEquals(status, response.status, code)
            assertEquals(code, response.json().str("code"))
        }
        env.upstream.onRpc = { _, _ -> rpcError(400, "22023", "INVALID_ARGUMENT", hint = "edad") }
        val edad = client.call(HttpMethod.Patch, "/api/v1/app/me/profile", """{"detail":{"edad":3}}""").json()
        assertEquals(listOf("edad"), edad.issueFields(), "el campo culpable viaja en details.issues")
    }

    @Test
    fun ifMatchIsForwardedAsAnInstantAndAStaleVersionIs412() = testApplication {
        val env = env { upstream.onRpc = { _, _ -> rpcOk(profileRpcJson()) } }
        mount(env)
        client.call(HttpMethod.Patch, "/api/v1/app/me/profile", """{"nombre":"Ada"}""", headers = mapOf("If-Match" to "\"2026-09-30T10:00:00.123456+00:00\""))
        assertEquals("2026-09-30T10:00:00.123456Z", env.upstream.lastArgs("app_actualizar_perfil").str("p_if_match"))

        env.upstream.onRpc = { _, _ -> rpcError(400, "OC412", "PRECONDITION_FAILED") }
        val stale = client.call(HttpMethod.Patch, "/api/v1/app/me/profile", """{"nombre":"Ada"}""", headers = mapOf("If-Match" to "\"2020-01-01T00:00:00Z\""))
        assertEquals(HttpStatusCode.PreconditionFailed, stale.status)
        assertEquals("PRECONDITION_FAILED", stale.json().str("code"))

        val garbage = client.call(HttpMethod.Patch, "/api/v1/app/me/profile", """{"nombre":"Ada"}""", headers = mapOf("If-Match" to "not-a-date"))
        assertEquals(HttpStatusCode.BadRequest, garbage.status)
        assertEquals(listOf("If-Match"), garbage.json().issueFields())
        env.upstream.onRpc = { _, _ -> rpcOk(profileRpcJson()) }
        val star = client.call(HttpMethod.Patch, "/api/v1/app/me/profile", """{}""", headers = mapOf("If-Match" to "*"))
        assertEquals(HttpStatusCode.OK, star.status)
        assertNull(env.upstream.lastArgs("app_actualizar_perfil").str("p_if_match"), "* = sin precondicion")
    }

    @Test
    fun parseIfMatchAcceptsQuotedWeakAndOffsetFormsAndTreatsStarAsNoPrecondition() {
        assertEquals("2026-09-30T10:00:00.123456Z", parseIfMatch("\"2026-09-30T10:00:00.123456Z\""))
        assertEquals("2026-09-30T10:00:00.123456Z", parseIfMatch("W/\"2026-09-30T10:00:00.123456+00:00\""))
        assertNull(parseIfMatch("*"))
        assertNull(parseIfMatch(null))
        assertNull(parseIfMatch("  "))
    }

    // ------------------------------------------------------------------ D1-16 / D1-17 documentos legales

    @Test
    fun legalDocumentsArePublicBoundedActiveOnlyAndCacheable() = testApplication {
        val counting = TestCountingLimiter()
        val env = env {
            limiter = counting
            upstream.onGet = { """[{"id":"$TEST_DOCUMENT","tipo_documento":"terminos","version":"2026-09","titulo":"Terminos","contenido":"# md","publicado_en":"2026-09-01T00:00:00+00:00"}]""" }
        }
        mount(env)
        val response = client.call(HttpMethod.Get, "/api/v1/app/legal/documents", token = null)

        assertEquals(HttpStatusCode.OK, response.status)
        val item = response.json()["items"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("id", "tipo", "version", "titulo", "contenido", "publicadoEn"), item.keys)
        assertEquals("terminos", item.str("tipo"))
        assertEquals("2026-09-01T00:00:00Z", item.str("publicadoEn"))
        // MASTER-API-007: antes "public, max-age=3600" (una hora sin revalidar texto legal); ahora se revalida a los 5 min.
        assertEquals("public, max-age=300, must-revalidate", response.headers[HttpHeaders.CacheControl])
        val etag = response.headers[HttpHeaders.ETag]!!
        val query = env.upstream.log.single()
        assertTrue("activo=eq.true" in query && "limit=20" in query && "tipo_documento=in.(terminos,privacidad)" in query, query)
        assertEquals(listOf(OposiBotsAuthOperation.LEGAL_LIST), counting.ipChecks, "limite por IP, sin autenticacion")

        val conditional = client.call(HttpMethod.Get, "/api/v1/app/legal/documents", token = null, headers = mapOf("If-None-Match" to etag))
        assertEquals(HttpStatusCode.NotModified, conditional.status)
    }

    @Test
    fun legalDocumentsFilterByTipoAndRejectAnUnknownTipoAndAreRateLimited() = testApplication {
        val env = env { upstream.onGet = { "[]" } }
        mount(env)
        assertEquals(HttpStatusCode.OK, client.call(HttpMethod.Get, "/api/v1/app/legal/documents?tipo=privacidad", token = null).status)
        assertTrue("tipo_documento=eq.privacidad" in env.upstream.log.single())
        val bad = client.call(HttpMethod.Get, "/api/v1/app/legal/documents?tipo=cookies", token = null)
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertEquals(listOf("tipo"), bad.json().issueFields())
        assertEquals(1, env.upstream.log.size, "un tipo invalido no llega a la base")

    }

    @Test
    fun legalDocumentsAreRateLimitedByIpWith429AndRetryAfter() = testApplication {
        val env = env { limiter = TestCountingLimiter(ipAllowed = false) }
        mount(env)
        val response = client.call(HttpMethod.Get, "/api/v1/app/legal/documents", token = null)
        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals("42", response.headers[HttpHeaders.RetryAfter])
        assertEquals("RATE_LIMITED", response.json().str("code"))
        assertTrue(env.upstream.log.isEmpty())
    }

    @Test
    fun legalAcceptancesCallOneRpcWithTheJwtSubjectAndAreIdempotent() = testApplication {
        val env = env { upstream.onRpc = { _, _ -> rpcOk("""{"pendingLegalDocuments":[{"id":"$TEST_DOCUMENT","tipo":"privacidad","version":"2026-09"}]}""") } }
        mount(env)
        val body = """{"documentIds":["$TEST_DOCUMENT","$TEST_DOCUMENT"],"plataforma":"ios"}"""
        val first = client.call(HttpMethod.Post, "/api/v1/app/me/legal-acceptances", body)
        val second = client.call(HttpMethod.Post, "/api/v1/app/me/legal-acceptances", body)

        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(first.bodyAsText(), second.bodyAsText())
        val pending = first.json()["pendingLegalDocuments"]!!.jsonArray.single().jsonObject
        assertEquals("privacidad", pending.str("tipo"))
        val args = env.upstream.lastArgs("app_aceptar_documentos_legales")
        assertEquals(TEST_UID, args.str("p_usuario_id"))
        assertEquals(listOf(TEST_DOCUMENT), args["p_documentos"]!!.jsonArray.strings(), "ids repetidos se deduplican")
        assertEquals("ios", args.str("p_plataforma"))
        assertFalse(args.keys.any { it.contains("aceptado", ignoreCase = true) || it.contains("_en") }, "el servidor pone la hora (aceptado_en)")
    }

    @Test
    fun legalAcceptancesValidateAndMapDocumentNotActive() = testApplication {
        val env = env()
        mount(env)
        val cases = mapOf(
            """{"documentIds":[],"plataforma":"ios"}""" to "documentIds",
            """{"documentIds":["${TEST_DOCUMENT}","${TEST_DOCUMENT.replace('c', 'd')}","${TEST_DOCUMENT.replace('c', 'e')}","${TEST_DOCUMENT.replace('c', 'f')}","${TEST_DOCUMENT.replace('c', '1')}","${TEST_DOCUMENT.replace('c', '2')}"],"plataforma":"ios"}""" to "documentIds",
            """{"documentIds":["nope"],"plataforma":"ios"}""" to "documentIds[0]",
            """{"documentIds":["$TEST_DOCUMENT"],"plataforma":"web"}""" to "plataforma",
        )
        cases.forEach { (body, field) ->
            val response = client.call(HttpMethod.Post, "/api/v1/app/me/legal-acceptances", body)
            assertEquals(HttpStatusCode.BadRequest, response.status, body)
            assertTrue(field in response.json().issueFields(), "$field ${response.json().issueFields()}")
        }
        val unknownField = client.call(HttpMethod.Post, "/api/v1/app/me/legal-acceptances", """{"documentIds":["$TEST_DOCUMENT"],"plataforma":"ios","usuarioId":"$TEST_UID"}""")
        assertEquals(listOf("usuarioId"), unknownField.json().issueFields())
        assertTrue(env.upstream.rpcCalls.isEmpty())

        env.upstream.onRpc = { _, _ -> rpcError(400, "22023", "DOCUMENT_NOT_ACTIVE") }
        val inactive = client.call(HttpMethod.Post, "/api/v1/app/me/legal-acceptances", """{"documentIds":["$TEST_DOCUMENT"],"plataforma":"android"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, inactive.status)
        assertEquals("DOCUMENT_NOT_ACTIVE", inactive.json().str("code"))
    }

    // ------------------------------------------------------------------ transversales

    @Test
    fun writesAreRateLimitedPerUserWith429AndRetryAfterBeforeTouchingTheDatabase() = testApplication {
        val counting = TestCountingLimiter(subjectAllowed = false)
        val env = env { limiter = counting }
        mount(env)
        listOf(
            HttpMethod.Get to "/api/v1/app/me",
            HttpMethod.Patch to "/api/v1/app/me/profile",
            HttpMethod.Post to "/api/v1/app/me/onboarding",
            HttpMethod.Post to "/api/v1/app/me/legal-acceptances",
        ).forEach { (method, path) ->
            val response = client.call(method, path, "{}")
            assertEquals(HttpStatusCode.TooManyRequests, response.status, path)
            assertEquals("42", response.headers[HttpHeaders.RetryAfter])
            assertEquals("RATE_LIMITED", response.json().str("code"))
        }
        assertTrue(counting.subjectChecks.all { it.second == TEST_UID }, "la clave es el sub del JWT")
        assertTrue(env.upstream.log.isEmpty() && env.upstream.rpcCalls.isEmpty())
    }

    @Test
    fun authenticatedResponsesAreNeverCacheableAndErrorsUseTheCanonicalEnvelope() = testApplication {
        val env = env { upstream.onRpc = { _, _ -> rpcError(409, "23505", "USERNAME_TAKEN") } }
        mount(env)
        val response = client.call(HttpMethod.Patch, "/api/v1/app/me/profile", """{"username":"ada_l"}""", headers = mapOf("X-Request-Id" to "req-slice1-0001"))
        assertEquals("private, no-store", response.headers[HttpHeaders.CacheControl])
        val body = response.json()
        assertEquals("USERNAME_TAKEN", body.str("code"))
        assertEquals("req-slice1-0001", body.str("requestId"))
        assertEquals("req-slice1-0001", response.headers["X-Request-Id"])
        assertTrue(body.str("message")!!.isNotBlank())
    }

    @Test
    fun theDeferredProfileOperationsAreNotRegistered() = testApplication {
        val env = env()
        mount(env)
        listOf(
            HttpMethod.Put to "/api/v1/app/me/avatar",
            HttpMethod.Delete to "/api/v1/app/me/avatar",
            HttpMethod.Post to "/api/v1/app/me/credentials",
            HttpMethod.Get to "/api/v1/app/me/referrals",
        ).forEach { (method, path) ->
            assertEquals(HttpStatusCode.NotFound, client.call(method, path, "{}").status, "$method $path")
        }
    }
}
