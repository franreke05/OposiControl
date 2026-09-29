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

    /** Gate B: deny by default. Solo `activa` exacto es ACTIVA; todo lo demas cae en UNRECOGNIZED (o su estado congelado). */
    @Test
    fun accountStatusIsDenyByDefault() {
        assertEquals(AccountStatus.ACTIVA, AccountStatus.fromWire("activa"))
        assertEquals(AccountStatus.BLOQUEADA, AccountStatus.fromWire("bloqueada"))
        assertEquals(AccountStatus.ELIMINACION_PENDIENTE, AccountStatus.fromWire("eliminacion_pendiente"))
        listOf(
            null, "", " ", "\u0000unrecognized", " activa", "activa ", "ACTIVA", "Activa", "activo",
            "ACTIVE", "active", "DISABLED", "disabled", "DELETED", "deleted", "ANONYMIZED", "anonymized",
            "desactivada", "eliminada", "anonimizada", "otra",
        ).forEach {
            assertEquals(AccountStatus.UNRECOGNIZED, AccountStatus.fromWire(it), "'$it' no puede ser activa")
        }
    }

    @Test
    fun requireActiveAllowsOnlyExplicitActiveAndDeniesEveryOtherStatus() {
        val id = CanonicalUserId.fromJwtSubject(uid)
        val expected = mapOf(
            AccountStatus.BLOQUEADA to "ACCOUNT_BLOCKED",
            AccountStatus.ELIMINACION_PENDIENTE to "ACCOUNT_DELETION_PENDING",
            AccountStatus.UNRECOGNIZED to "FORBIDDEN",
        )
        // Exhaustivo: un estado nuevo en el enum sin decision de acceso rompe este test.
        assertEquals(AccountStatus.entries.toSet() - AccountStatus.ACTIVA, expected.keys)
        expected.forEach { (status, code) ->
            val error = assertFailsWith<BackendDomainException>("$status") {
                CanonicalUserIdentity(id, status, onboardingCompleted = true).requireActive()
            }
            assertEquals(code, error.errorCode)
            assertEquals(HttpStatusCode.Forbidden, error.status)
        }
    }

    /** Fila con estado_cuenta null / ausente / desconocido / futuro: el directorio no la promociona a activa y `activeIdentity` la deniega. */
    @Test
    fun aProfileRowWithoutAnExplicitActiveStatusIsDeniedEndToEnd() {
        fun rowJson(estado: String?) = if (estado == null) {
            """[{"usuario_id":"${uid.lowercase()}","onboarding_completado":true}]"""
        } else {
            """[{"usuario_id":"${uid.lowercase()}","estado_cuenta":$estado,"onboarding_completado":true}]"""
        }
        val cases = mapOf(
            "columna ausente" to rowJson(null),
            "null" to rowJson("null"),
            "vacio" to rowJson("\"\""),
            "disabled" to rowJson("\"DISABLED\""),
            "deleted" to rowJson("\"deleted\""),
            "anonymized" to rowJson("\"ANONYMIZED\""),
            "desconocido" to rowJson("\"otra\""),
        )
        cases.forEach { (label, json) ->
            val directory = PostgrestCanonicalProfileDirectory(
                SupabaseRequestTransport.forTesting("https://stub.supabase.test", "sb_secret_test_only", V1StubHttpClient({}) { V1StubResponse(200, json) }),
            )
            val identity = runBlocking { directory.find(CanonicalUserId.fromJwtSubject(uid)) }!!
            assertEquals(AccountStatus.UNRECOGNIZED, identity.accountStatus, label)
            val error = assertFailsWith<BackendDomainException>(label) { identity.requireActive() }
            assertEquals("FORBIDDEN", error.errorCode, label)
        }
    }

    @Test
    fun everyNonActiveStatusIsDeniedByTheResolverWith403() = testApplication {
        val statuses = mapOf(
            "bloqueada" to "ACCOUNT_BLOCKED",
            "eliminacion_pendiente" to "ACCOUNT_DELETION_PENDING",
            "DELETED" to "FORBIDDEN",
            "anonymized" to "FORBIDDEN",
            "disabled" to "FORBIDDEN",
            "" to "FORBIDDEN",
        )
        var current: String? = "activa"
        val resolver = CanonicalIdentityResolver(
            verifySubject = { uid },
            directory = { CanonicalUserIdentity(it, AccountStatus.fromWire(current), false) },
        )
        application {
            install(ContentNegotiation) { json() }
            installRequestCorrelation()
            installBackendStatusPages()
            routing { get("/probe") { call.respondText(resolver.activeIdentity(call).userId.value) } }
        }
        assertEquals(HttpStatusCode.OK, client.get("/probe") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
        statuses.forEach { (wire, code) ->
            current = wire
            val response = client.get("/probe") { header(HttpHeaders.Authorization, "Bearer $token") }
            assertEquals(HttpStatusCode.Forbidden, response.status, wire)
            assertEquals(code, Json.parseToJsonElement(response.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content, wire)
        }
        current = null
        assertEquals(HttpStatusCode.Forbidden, client.get("/probe") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
    }

    /** RB-1-06: caida del JWKS (I/O) = 503 reintentable; token invalido = 401. */
    @Test
    fun aJwksOutageIs503WhileAnInvalidTokenIs401() = testApplication {
        var failure: () -> Nothing = { throw java.io.IOException("jwks down") }
        val resolver = CanonicalIdentityResolver(verifySubject = { failure() }, directory = { null })
        application {
            install(ContentNegotiation) { json() }
            installRequestCorrelation()
            installBackendStatusPages()
            routing { get("/probe") { call.respondText(resolver.userId(call).value) } }
        }
        val outage = client.get("/probe") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.ServiceUnavailable, outage.status)
        assertEquals("UPSTREAM_UNAVAILABLE", Json.parseToJsonElement(outage.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)
        assertTrue(!outage.bodyAsText().contains("jwks down"))
        failure = { throw java.net.http.HttpTimeoutException("timeout") }
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/probe") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
        failure = { error("firma invalida") }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/probe") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
    }

    /** Sin fila de perfil: el contrato calla; se deniega con PROFILE_MISSING (403), nunca se autocrea ni se opera. */
    @Test
    fun aMissingProfileRowIsDeniedWithProfileMissing() = testApplication {
        val resolver = CanonicalIdentityResolver(verifySubject = { uid }, directory = { null })
        application {
            install(ContentNegotiation) { json() }
            installRequestCorrelation()
            installBackendStatusPages()
            routing {
                get("/identity") { call.respondText(resolver.identity(call).userId.value) }
                get("/active") { call.respondText(resolver.activeIdentity(call).userId.value) }
            }
        }
        listOf("/identity", "/active").forEach { path ->
            val response = client.get(path) { header(HttpHeaders.Authorization, "Bearer $token") }
            assertEquals(HttpStatusCode.Forbidden, response.status, path)
            assertEquals("PROFILE_MISSING", Json.parseToJsonElement(response.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content, path)
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
}
