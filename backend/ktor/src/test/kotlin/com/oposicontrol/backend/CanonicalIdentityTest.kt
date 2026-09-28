package com.oposicontrol.backend

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SLICE 0 · identidad canonica (`specs.identity`): JWT sub -> auth.users.id -> perfiles_usuario. */
class CanonicalIdentityTest {

    private val uid = "0f8FAD5B-D9CB-469F-A165-70867728950E"
    private val token = "aaa.bbb.ccc"

    @Test
    fun theCanonicalIdIsTheLowercaseUuidOfTheJwtSubject() {
        assertEquals(uid.lowercase(), CanonicalUserId.fromJwtSubject(uid).value)
        listOf(null, "", "42", "dev-user", "$uid-x").forEach {
            assertEquals("UNAUTHENTICATED", assertFailsWith<BackendDomainException>("$it") { CanonicalUserId.fromJwtSubject(it) }.errorCode)
        }
    }

    @Test
    fun accountStatusParsesTheFrozenValuesAndFailsClosedOnUnknown() {
        assertEquals(AccountStatus.ACTIVA, AccountStatus.fromWire(null), "fila anterior a la expansion")
        assertEquals(AccountStatus.BLOQUEADA, AccountStatus.fromWire("bloqueada"))
        assertEquals(AccountStatus.ELIMINACION_PENDIENTE, AccountStatus.fromWire("eliminacion_pendiente"))
        assertEquals("UPSTREAM_CONTRACT_ERROR", assertFailsWith<BackendDomainException> { AccountStatus.fromWire("otra") }.errorCode)
    }

    @Test
    fun requireActiveMapsBlockedAndDeletionPendingTo403() {
        val id = CanonicalUserId.fromJwtSubject(uid)
        val active = CanonicalUserIdentity(id, AccountStatus.ACTIVA, onboardingCompleted = true)
        assertEquals(active, active.requireActive())
        listOf(
            AccountStatus.BLOQUEADA to "ACCOUNT_BLOCKED",
            AccountStatus.ELIMINACION_PENDIENTE to "ACCOUNT_DELETION_PENDING",
        ).forEach { (status, code) ->
            val error = assertFailsWith<BackendDomainException> { active.copy(accountStatus = status).requireActive() }
            assertEquals(code, error.errorCode)
            assertEquals(HttpStatusCode.Forbidden, error.status)
        }
    }

    @Test
    fun thePostgrestDirectoryReadsOnlyPerfilesUsuarioByTheValidatedUuid() = runBlocking {
        val uris = CopyOnWriteArrayList<String>()
        val directory = PostgrestCanonicalProfileDirectory(
            SupabaseRequestTransport.forTesting(
                "https://stub.supabase.test",
                "sb_secret_test_only",
                V1StubHttpClient({ uris += it.uri().toString() }) {
                    V1StubResponse(200, """[{"usuario_id":"${uid.lowercase()}","estado_cuenta":"bloqueada","onboarding_completado":true}]""")
                },
            ),
        )
        val identity = directory.find(CanonicalUserId.fromJwtSubject(uid))!!
        assertEquals(AccountStatus.BLOQUEADA, identity.accountStatus)
        assertTrue(identity.onboardingCompleted)
        val uri = uris.single()
        assertTrue(uri.contains("/rest/v1/perfiles_usuario?"), uri)
        assertTrue(uri.contains("usuario_id=eq.${uid.lowercase()}"), uri)
        assertTrue(!uri.contains("/rest/v1/usuario?") && !uri.contains("oposibots_upsert_own_profile"), "nunca la tabla legacy")
    }

    @Test
    fun aMissingProfileRowIsNull() = runBlocking {
        val directory = PostgrestCanonicalProfileDirectory(
            SupabaseRequestTransport.forTesting("https://stub.supabase.test", "sb_secret_test_only", V1StubHttpClient({}) { V1StubResponse(200, "[]") }),
        )
        assertNull(directory.find(CanonicalUserId.fromJwtSubject(uid)))
    }

    @Test
    fun theResolverTakesIdentityOnlyFromTheVerifiedJwtSubject() = testApplication {
        val profiles = mapOf(
            uid.lowercase() to CanonicalUserIdentity(CanonicalUserId.fromJwtSubject(uid), AccountStatus.ACTIVA, false),
        )
        val resolver = CanonicalIdentityResolver(
            verifySubject = { jwt -> if (jwt == token) uid else error("firma invalida x-leak") },
            directory = { profiles[it.value] },
        )
        application {
            install(ContentNegotiation) { json() }
            installRequestCorrelation()
            installBackendStatusPages()
            routing {
                get("/probe") { call.respondText(resolver.activeIdentity(call).userId.value) }
            }
        }

        val ok = client.get("/probe") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals(uid.lowercase(), ok.bodyAsText())

        val devHeader = client.get("/probe") { header("X-Usuario-Id", uid) }
        assertEquals(HttpStatusCode.Unauthorized, devHeader.status, "sin atajo X-Usuario-Id en lo canonico")
        assertEquals("Bearer", devHeader.headers[HttpHeaders.WWWAuthenticate])

        val badSignature = client.get("/probe") { header(HttpHeaders.Authorization, "Bearer x.y.z") }
        assertEquals(HttpStatusCode.Unauthorized, badSignature.status)
        val body = Json.parseToJsonElement(badSignature.bodyAsText()).jsonObject
        assertEquals("UNAUTHENTICATED", body["code"]!!.jsonPrimitive.content)
        assertTrue(!badSignature.bodyAsText().contains("x-leak"))
    }

    @Test
    fun aValidJwtWithoutProfileRowIs403() = testApplication {
        val resolver = CanonicalIdentityResolver(verifySubject = { uid }, directory = { null })
        application {
            install(ContentNegotiation) { json() }
            installRequestCorrelation()
            installBackendStatusPages()
            routing { get("/probe") { call.respondText(resolver.identity(call).userId.value) } }
        }
        val response = client.get("/probe") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }
}
