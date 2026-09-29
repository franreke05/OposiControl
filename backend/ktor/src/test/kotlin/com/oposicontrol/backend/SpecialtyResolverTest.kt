package com.oposicontrol.backend

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SLICE 1 · DEC-002: texto legacy de especialidad -> id, y los adaptadores legacy de perfil (EP-124, EP-218). */
class SpecialtyResolverTest {

    private val enfermeria = "11111111-1111-4111-8111-111111111111"
    private val celador = "22222222-2222-4222-8222-222222222222"
    private val candidates = listOf(
        Triple(enfermeria, "Enfermería", "enfermeria"),
        Triple(celador, "Celador/a", "celador"),
    )

    // ------------------------------------------------------------------ regla de coincidencia

    @Test
    fun matchIsExactCaseAndAccentInsensitiveOnNameOrSlug() {
        listOf("Enfermería", "enfermeria", "ENFERMERIA", "  enfermería  ", "Enfermeria", "enFERmerÍa").forEach {
            assertEquals(enfermeria, matchSpecialty(normalizeSpecialtyText(it), candidates), it)
        }
        assertEquals(celador, matchSpecialty(normalizeSpecialtyText("CELADOR"), candidates), "por slug")
        assertEquals(celador, matchSpecialty(normalizeSpecialtyText("celador/a"), candidates), "por nombre")
    }

    @Test
    fun noMatchOrPartialOrEmptyIsNullNeverAGuess() {
        listOf("Enferm", "Enfermeria general", "Medicina", "", "   ", "enfermeria celador").forEach {
            assertNull(matchSpecialty(normalizeSpecialtyText(it), candidates), "'$it'")
        }
    }

    @Test
    fun anAmbiguousTextThatMatchesTwoSpecialtiesIsNullButTheSameRowMatchingByNameAndSlugIsNotAmbiguous() {
        val ambiguous = listOf(
            Triple(enfermeria, "Enfermería", "enf-1"),
            Triple(celador, "Enfermeria", "enf-2"),
        )
        assertNull(matchSpecialty(normalizeSpecialtyText("enfermeria"), ambiguous))
        // nombre y slug del MISMO registro coinciden (`enfermeria`/`enfermeria`): un solo id.
        assertEquals(enfermeria, matchSpecialty("enfermeria", listOf(Triple(enfermeria, "Enfermería", "enfermeria"))))
    }

    @Test
    fun normalizationStripsAccentsCollapsesWhitespaceAndLowercases() {
        assertEquals("tecnico en cuidados", normalizeSpecialtyText("  Técnico   en\tCuidados "))
    }

    // ------------------------------------------------------------------ lectura

    @Test
    fun theResolverReadsOnlyActiveSpecialtiesAndResolvesThroughTheMatchRule() = runBlocking {
        val upstream = FakeUpstream()
        upstream.onGet = { """[{"id":"$enfermeria","nombre":"Enfermería","slug":"enfermeria"},{"id":"$celador","nombre":"Celador/a","slug":"celador"}]""" }
        val resolver = PostgrestSpecialtyResolver(upstream, testJson)

        assertEquals(enfermeria, resolver.resolve("enfermeria"))
        assertNull(resolver.resolve("otra"))
        assertNull(resolver.resolve("   "))
        val query = upstream.log.first()
        assertTrue(query.startsWith("GET /rest/v1/especialidades?"), query)
        assertTrue("activa=eq.true" in query, "una especialidad inactiva nunca se elige: $query")
        assertEquals(2, upstream.log.size, "un texto en blanco ni consulta")
    }

    // ------------------------------------------------------------------ adaptadores legacy

    private val identity = CanonicalUserIdentity(CanonicalUserId.fromJwtSubject(TEST_UID), AccountStatus.ACTIVA, true)
    private val context = OposiBotsAuthContext("req-1", "127.0.0.1")

    private fun adapter(
        upstream: FakeUpstream,
        resolve: (String) -> String? = { if (it == "Enfermería") enfermeria else null },
    ): LegacyProfileAdapter {
        val profiles = SupabaseCanonicalProfileService(upstream, FakeAuthProvider(), testJson)
        return LegacyProfileAdapter(profiles) { resolve(it) }
    }

    @Test
    fun legacyMeIsTheCanonicalMeInTheLegacyShapeWithTheSupersetFlags() = runBlocking {
        val upstream = FakeUpstream().apply { onGet = { profileRowJson(onboarding = true) } }
        val me = adapter(upstream).me(identity, "token", context).user
        assertEquals(TEST_UID, me.id)
        assertEquals("person@example.com", me.email)
        assertTrue(me.profileComplete)
        assertTrue(me.onboardingCompleted)
        assertEquals("activa", me.accountStatus)
        assertTrue(upstream.log.single().contains("perfiles_usuario"), "lee perfiles_usuario, no public.usuario")
        assertTrue(upstream.log.none { it.contains("/rest/v1/usuario?") })
    }

    @Test
    fun legacySaveProfileResolvesTheSpecialtyTextAndWritesThroughTheCanonicalOnboardingRpc() = runBlocking {
        val upstream = FakeUpstream().apply { onRpc = { _, _ -> rpcOk(profileRpcJson()) } }
        adapter(upstream).saveProfile(identity, "token", OposiBotsProfileRequest("Ada", "Lovelace", "ada_l", "Enfermería"), context)

        assertEquals(listOf("app_completar_onboarding"), upstream.rpcNames(), "nunca oposibots_upsert_own_profile ni public.usuario")
        val args = upstream.lastArgs("app_completar_onboarding")
        assertEquals(enfermeria, args["p_especialidad_id"]!!.jsonPrimitive.content)
        assertEquals("ada_l", args["p_username"]!!.jsonPrimitive.content)
        assertEquals(0, args["p_documentos"]!!.jsonArray.size, "el cable legacy no lleva aceptacion legal (R3, decision de producto pendiente)")
        assertEquals(TEST_UID, args["p_usuario_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun legacySaveProfileWithAnUnresolvableSpecialtyIsSpecialtyNotSelectedAndWritesNothing() = runBlocking {
        val upstream = FakeUpstream()
        val failure = assertFailsWith<OposiBotsAuthProblem> {
            adapter(upstream).saveProfile(identity, "token", OposiBotsProfileRequest("Ada", "L", "ada_l", "Medicina"), context)
        }
        assertEquals(setOf(OposiBotsValidationIssue.SPECIALTY_NOT_SELECTED), failure.issues)
        assertTrue(upstream.rpcCalls.isEmpty())
    }

    @Test
    fun legacyUsernameIsOptionalOnTheWireSoTheStoredOneIsReusedAndWithoutAnyItIsInvalidUsername() = runBlocking {
        val withStored = FakeUpstream().apply {
            onGet = { profileRowJson() }
            onRpc = { _, _ -> rpcOk(profileRpcJson()) }
        }
        adapter(withStored).saveProfile(identity, "token", OposiBotsProfileRequest("Ada", "L", null, "Enfermería"), context)
        assertEquals("ada_l", withStored.lastArgs("app_completar_onboarding")["p_username"]!!.jsonPrimitive.content)

        val without = FakeUpstream().apply {
            onGet = { profileRowJson().replace("\"username\":\"ada_l\"", "\"username\":null") }
        }
        val failure = assertFailsWith<OposiBotsAuthProblem> {
            adapter(without).saveProfile(identity, "token", OposiBotsProfileRequest("Ada", "L", "  ", "Enfermería"), context)
        }
        assertEquals(setOf(OposiBotsValidationIssue.INVALID_USERNAME), failure.issues)
        assertTrue(without.rpcCalls.isEmpty())
    }

    @Test
    fun legacySaveProfilePropagatesCanonicalDomainErrors() = runBlocking {
        val upstream = FakeUpstream().apply { onRpc = { _, _ -> rpcError(409, "23505", "USERNAME_TAKEN") } }
        val failure = assertFailsWith<BackendDomainException> {
            adapter(upstream).saveProfile(identity, "token", OposiBotsProfileRequest("Ada", "L", "ada_l", "Enfermería"), context)
        }
        assertEquals("USERNAME_TAKEN", failure.errorCode)
    }
}
