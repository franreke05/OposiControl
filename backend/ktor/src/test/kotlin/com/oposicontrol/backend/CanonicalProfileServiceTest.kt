package com.oposicontrol.backend

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SLICE 1 · [SupabaseCanonicalProfileService] sobre Supabase y GoTrue falsos (sin HTTP). */
class CanonicalProfileServiceTest {

    private val context = OposiBotsAuthContext("req-1", "127.0.0.1")
    private val identity = CanonicalUserIdentity(CanonicalUserId.fromJwtSubject(TEST_UID), AccountStatus.ACTIVA, true)

    private fun service(upstream: FakeUpstream = FakeUpstream(), auth: FakeAuthProvider = FakeAuthProvider()) =
        Triple(SupabaseCanonicalProfileService(upstream, auth, testJson), upstream, auth)

    // ------------------------------------------------------------------ RPC: nombres, argumentos y clave de servicio

    @Test
    fun onboardingCallsAppCompletarOnboardingWithExactlyTheDocumentedArguments() = runBlocking {
        val (svc, upstream, _) = service()
        upstream.onRpc = { _, _ -> rpcOk(profileRpcJson()) }
        svc.completeOnboarding(
            identity,
            "token",
            OnboardingRequest("ada_l", " Ada ", " Lovelace ", null, emptyList()),
            context,
        )
        assertEquals(listOf("app_completar_onboarding"), upstream.rpcNames())
        val args = upstream.lastArgs("app_completar_onboarding")
        assertEquals(
            setOf("p_usuario_id", "p_username", "p_nombre", "p_apellidos", "p_especialidad_id", "p_documentos", "p_plataforma", "p_attribution", "p_referral_code"),
            args.keys,
        )
        assertEquals(TEST_UID, (args["p_usuario_id"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("Ada", (args["p_nombre"] as kotlinx.serialization.json.JsonPrimitive).content, "se recorta")
        assertEquals(JsonNull, args["p_especialidad_id"])
        assertEquals(JsonNull, args["p_plataforma"])
        assertEquals(JsonNull, args["p_attribution"])
        assertEquals(JsonNull, args["p_referral_code"])
        assertEquals(0, args["p_documentos"]!!.jsonArray.size)
    }

    @Test
    fun theCallerIdIsNeverTakenFromTheBodyOnlyFromTheResolvedIdentity() = runBlocking {
        val (svc, upstream, _) = service()
        upstream.onRpc = { _, _ -> rpcOk(profileRpcJson()) }
        val other = CanonicalUserIdentity(CanonicalUserId.fromJwtSubject("11111111-1111-4111-8111-111111111111"), AccountStatus.ACTIVA, true)
        svc.updateProfile(other, "token", ProfilePatchRequest(nombre = "Ada"), null, context)
        assertEquals("11111111-1111-4111-8111-111111111111", (upstream.lastArgs("app_actualizar_perfil")["p_usuario_id"] as kotlinx.serialization.json.JsonPrimitive).content)
        // Los DTO de comando no tienen ningun campo con el id del llamante.
        listOf(OnboardingRequest.serializer(), ProfilePatchRequest.serializer(), LegalAcceptanceRequest.serializer(), AccountDeletionRequest.serializer()).forEach { serializer ->
            val names = (0 until serializer.descriptor.elementsCount).map { serializer.descriptor.getElementName(it).lowercase() }
            assertTrue(names.none { it in setOf("usuarioid", "userid", "id", "actorid") }, "${serializer.descriptor.serialName}: $names")
        }
    }

    @Test
    fun aNullPatchValueCanNeverClearASpecialtyBecauseNullsAreRejectedBeforeTheService() {
        assertEquals("especialidadId", firstExplicitNull(Json.parseToJsonElement("""{"especialidadId":null}""")))
        assertEquals("detail.ciudad", firstExplicitNull(Json.parseToJsonElement("""{"detail":{"ciudad":null}}""")))
        assertEquals("targetCommunityIds[1]", firstExplicitNull(Json.parseToJsonElement("""{"targetCommunityIds":["a",null]}""")))
        assertNull(firstExplicitNull(Json.parseToJsonElement("""{"nombre":"Ada","introSeen":false}""")))
    }

    @Test
    fun theProductionAdapterCallsTheRpcEndpointWithTheServiceKeyAndReturnsErrorsWithoutThrowing() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val transport = SupabaseRequestTransport.forTesting(
            "https://stub.supabase.test",
            "sb_secret_test_only",
            V1StubHttpClient({ sent += it }) { V1StubResponse(409, """{"code":"23505","message":"USERNAME_TAKEN","details":null,"hint":null}""") },
        )
        val response = TransportCanonicalUpstream(transport).rpc("app_completar_onboarding", """{"p_usuario_id":"$TEST_UID"}""")

        assertEquals(409, response.status)
        assertFalse(response.ok)
        assertTrue(response.body.contains("USERNAME_TAKEN"))
        val request = sent.single()
        assertEquals("POST", request.method())
        assertEquals("/rest/v1/rpc/app_completar_onboarding", request.uri().rawPath)
        assertEquals("sb_secret_test_only", request.headers().firstValue("apikey").orElse(null))
        assertTrue(request.headers().firstValue("Authorization").isEmpty, "una clave no JWT no viaja como Bearer (F-02)")
    }

    // ------------------------------------------------------------------ traduccion de errores

    @Test
    fun everyStableKeyMapsToItsCanonicalCodeAndStatus() {
        val table = mapOf(
            rpcError(409, "23505", "USERNAME_TAKEN") to ("USERNAME_TAKEN" to 409),
            rpcError(400, "22023", "UNKNOWN_SPECIALTY") to ("UNKNOWN_SPECIALTY" to 422),
            rpcError(400, "22023", "UNKNOWN_COMMUNITY") to ("UNKNOWN_COMMUNITY" to 422),
            rpcError(400, "22023", "DOCUMENT_NOT_ACTIVE") to ("DOCUMENT_NOT_ACTIVE" to 422),
            rpcError(400, "OC409", "ALREADY_ONBOARDED_CONFLICT") to ("ALREADY_ONBOARDED_CONFLICT" to 409),
            rpcError(400, "OC412", "PRECONDITION_FAILED") to ("PRECONDITION_FAILED" to 412),
            rpcError(400, "OC404", "PROFILE_MISSING") to ("PROFILE_MISSING" to 403),
            rpcError(403, "42501", "ACCOUNT_BLOCKED") to ("ACCOUNT_BLOCKED" to 403),
            rpcError(403, "42501", "ACCOUNT_DELETION_PENDING") to ("ACCOUNT_DELETION_PENDING" to 403),
            rpcError(403, "42501", "FORBIDDEN") to ("FORBIDDEN" to 403),
        )
        table.forEach { (response, expected) ->
            val failure = assertIs<BackendDomainException>(ProfileRpcErrors.failure("fn", response), response.body)
            assertEquals(expected.first, failure.errorCode)
            assertEquals(expected.second, failure.status.value)
        }
    }

    @Test
    fun invalidArgumentAndUnknownFieldCarryTheOffendingFieldFromTheHintButNeverAnUnsafeOne() {
        val invalid = assertIs<BackendDomainException>(ProfileRpcErrors.failure("fn", rpcError(400, "22023", "INVALID_ARGUMENT", "edad")))
        assertEquals("VALIDATION", invalid.errorCode)
        assertEquals(listOf(ErrorIssue("edad", CanonicalIssue.INVALID_FORMAT)), invalid.details!!.issues)
        val unknown = assertIs<BackendDomainException>(ProfileRpcErrors.failure("fn", rpcError(400, "22023", "UNKNOWN_FIELD", "detail.zzz")))
        assertEquals(listOf(ErrorIssue("detail.zzz", CanonicalIssue.UNKNOWN_FIELD)), unknown.details!!.issues)
        val hostile = assertIs<BackendDomainException>(ProfileRpcErrors.failure("fn", rpcError(400, "22023", "INVALID_ARGUMENT", "<script>alert(1)</script>")))
        assertEquals(listOf(ErrorIssue("body", CanonicalIssue.INVALID_FORMAT)), hostile.details!!.issues)
    }

    @Test
    fun documentNotActiveIsLegalVersionNotActiveOnlyWhereTheContractSaysSo() {
        val response = rpcError(400, "22023", "DOCUMENT_NOT_ACTIVE")
        assertEquals("LEGAL_VERSION_NOT_ACTIVE", assertIs<BackendDomainException>(ProfileRpcErrors.failure("fn", response, CanonicalErrorCode.LEGAL_VERSION_NOT_ACTIVE)).errorCode)
        assertEquals("DOCUMENT_NOT_ACTIVE", assertIs<BackendDomainException>(ProfileRpcErrors.failure("fn", response)).errorCode)
    }

    @Test
    fun transientDatabaseFailuresAreDbBusyAndAnythingUnknownIsRedactedNotLeaked() {
        val deadlock = assertIs<BackendDomainException>(ProfileRpcErrors.failure("fn", rpcError(500, "40P01", "deadlock detected")))
        assertEquals(503, deadlock.status.value)
        assertTrue(deadlock.retryAfterSeconds != null)
        // Sin mapeo: IllegalStateException (StatusPages lo redacta a 502); permisos revocados tambien.
        assertIs<IllegalStateException>(ProfileRpcErrors.failure("fn", rpcError(401, "42501", "permission denied for function app_x")))
        // MASTER-API-003 (cambio deliberado): un 5xx de PostgREST/gateway es una caida, no un 502 redactado: 503 UPSTREAM_UNAVAILABLE.
        val outage = assertIs<BackendDomainException>(ProfileRpcErrors.failure("fn", SupabaseRawResponse(502, "<html>bad gateway</html>")))
        assertEquals(503, outage.status.value)
        assertEquals("UPSTREAM_UNAVAILABLE", outage.errorCode)
        assertFalse(outage.message.contains("html"), "el cuerpo del upstream no se filtra")
    }

    // ------------------------------------------------------------------ lectura y degradacion

    @Test
    fun getMeWithoutARowIsProfileMissingAndAnUnrecognizedStoredStatusIsForbiddenNeverActive() = runBlocking {
        val (svc, upstream, _) = service()
        upstream.onGet = { "[]" }
        assertEquals("PROFILE_MISSING", assertFailsWith<BackendDomainException> { svc.me(identity, "t", context) }.errorCode)
        upstream.onGet = { profileRowJson(estado = "ANONYMIZED") }
        assertEquals("FORBIDDEN", assertFailsWith<BackendDomainException> { svc.me(identity, "t", context) }.errorCode)
    }

    @Test
    fun afterACommittedWriteAGoTrueOutageDegradesButARevokedSessionIs401() = runBlocking {
        val (svc, upstream, auth) = service()
        upstream.onRpc = { _, _ -> rpcOk(profileRpcJson()) }
        auth.userFailure = CanonicalErrorCode.UPSTREAM_UNAVAILABLE.exception()

        val degraded = svc.updateProfile(identity, "t", ProfilePatchRequest(nombre = "Ada"), null, context).me
        assertNull(degraded.email)
        assertEquals(emptyList(), degraded.authProviders)
        assertEquals("2026-01-02T03:04:05.123456Z", degraded.registeredAt, "sin GoTrue se usa creado_en del perfil")

        auth.userFailure = CanonicalErrorCode.UNAUTHENTICATED.exception()
        assertEquals("UNAUTHENTICATED", assertFailsWith<BackendDomainException> { svc.updateProfile(identity, "t", ProfilePatchRequest(nombre = "Ada"), null, context) }.errorCode)
    }

    @Test
    fun theRawStoredAvatarNeverReachesTheResponse() = runBlocking {
        val (svc, upstream, _) = service()
        upstream.onRpc = { _, _ -> rpcOk(profileRpcJson()) }
        val me = svc.completeOnboarding(identity, "t", OnboardingRequest("ada_l", "Ada", "L", null, emptyList()), context).me
        assertNull(me.profile.avatarUrl)
        assertFalse(testJson.encodeToString(Me.serializer(), me).contains("secret-path"))
    }

    // ------------------------------------------------------------------ documentos legales

    @Test
    fun legalDocumentsAreBoundedToTwentyAndOnlyTheContractTypes() = runBlocking {
        val (svc, upstream, _) = service()
        val result = svc.listLegalDocuments(null)
        assertEquals(emptyList(), result.items)
        val query = upstream.log.single()
        assertTrue("limit=20" in query, query)
        assertTrue("tipo_documento=in.(terminos,privacidad)" in query, query)
        assertEquals(20, SupabaseCanonicalProfileService.MAX_LEGAL_DOCUMENTS)
        assertEquals("VALIDATION", assertFailsWith<BackendDomainException> { svc.listLegalDocuments("cookies") }.errorCode)
    }

    @Test
    fun theLegalEtagChangesWhenTheNewestPublicationChanges() = runBlocking {
        val (svc, upstream, _) = service()
        fun rows(publicado: String) = """[{"id":"$TEST_DOCUMENT","tipo_documento":"terminos","version":"1","titulo":"T","contenido":"c","publicado_en":"$publicado"}]"""
        upstream.onGet = { rows("2026-09-01T00:00:00Z") }
        val first = svc.listLegalDocuments(null).etag
        upstream.onGet = { rows("2026-09-02T00:00:00Z") }
        val second = svc.listLegalDocuments(null).etag
        assertTrue(first != second)
    }

    // ------------------------------------------------------------------ utilidades

    @Test
    fun normalizeInstantHandlesOffsetsMicrosecondsAndLeavesGarbageAlone() {
        assertEquals("2026-09-30T10:00:00.123456Z", normalizeInstant("2026-09-30T10:00:00.123456+00:00"))
        assertEquals("2026-09-30T10:00:00Z", normalizeInstant("2026-09-30T12:00:00+02:00"))
        assertEquals("not-a-date", normalizeInstant("not-a-date"))
    }
}
