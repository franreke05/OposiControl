package com.oposicontrol.backend

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Auditoria global, KTOR_REST parte 3/3: una prueba por hallazgo corregido. */
class KtorRestPart3AuditTest {

    private val u1 = "11111111-1111-4111-8111-111111111111"

    // ------------------------------------------------------------------ MASTER-OPENAPI-004

    private fun perfilRow(estado: String?) =
        """[{"usuario_id":"$u1","username":"ada","nombre_mostrar":"Ada L","es_invitado":false,"onboarding_completado":true,""" +
            """"estado_cuenta":${estado?.let { "\"$it\"" } ?: "null"},"creado_en":"2026-01-02T03:04:05.123456+00:00"}]"""

    private fun Application.detailModule(estado: String?) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        val gateway = AdminV1Gateway.forTesting(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = V1StubHttpClient({ }) { uri ->
                when {
                    "/rest/v1/roles_usuario" in uri -> V1StubResponse(500, """{"message":"boom"}""")
                    "/rest/v1/perfiles_usuario?select=*" in uri -> V1StubResponse(200, perfilRow(estado))
                    else -> V1StubResponse(200, "[]")
                }
            },
        )
        routing { adminV1Routes(gateway) }
    }

    @Test
    fun theDegradedPerfilFallbackStaysInsideTheAdminUserSchema() = testApplication {
        application { detailModule("bloqueada") }
        val detail = Json.parseToJsonElement(client.get("/api/v1/admin/usuarios/$u1/detalle").bodyAsText())
            .jsonObject["data"]!!.jsonObject
        val perfil = detail["perfil"]!!.jsonObject
        assertTrue(detail["seccionesNoDisponibles"]!!.jsonArray.any { it.jsonPrimitive.content == "perfil" })
        assertEquals(u1, perfil["id"]!!.jsonPrimitive.content)
        assertEquals("bloqueada", perfil["accountStatus"]!!.jsonPrimitive.content)
        assertEquals("2026-01-02T03:04:05.123456Z", perfil["createdAt"]!!.jsonPrimitive.content)
        assertEquals("Ada L", perfil["fullName"]!!.jsonPrimitive.content)
        assertEquals("true", perfil["onboardingCompleted"]!!.jsonPrimitive.content)
    }

    @Test
    fun anUnrecognisedAccountStatusIsNotSwallowedByTheDegradedFallback() = testApplication {
        application { detailModule("suspendida") }
        val response = client.get("/api/v1/admin/usuarios/$u1/detalle")
        assertEquals(HttpStatusCode.BadGateway, response.status, response.bodyAsText())
        assertEquals("UPSTREAM_CONTRACT_ERROR", Json.parseToJsonElement(response.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------ MASTER-OPENAPI-008

    @Test
    fun successorLinksNeverCarryOpenApiPathTemplates() {
        // MASTER4-API-004 (cambio deliberado): la plantilla ya no se trunca a la coleccion; se sustituye por el valor real
        // o, si no se puede resolver, no hay Link.
        assertEquals("</api/v1/admin/noticias/n%201>; rel=\"successor-version\"", LegacyHeaders.successorLink("/api/v1/admin/noticias/{noticiaId}") { if (it == "noticiaId") "n 1" else null })
        assertNull(LegacyHeaders.successorLink("/api/v1/admin/noticias/{noticiaId}"))
        assertNull(LegacyHeaders.successorLink("/api/v1/admin/noticias/{noticiaId}") { "" })
        assertEquals("</api/v1/app/me>; rel=\"successor-version\"", LegacyHeaders.successorLink("/api/v1/app/me"))
        (LegacyEndpoints.SLICE_0 + LegacyEndpoints.SLICE_1).filter { it.successorPath != null }.forEach {
            val link = LegacyHeaders.successorLink(it.successorPath!!) { "v" }
            assertFalse(link!!.contains('{') || link.contains('}'), "${it.endpointId}: $link")
        }
    }

    // ------------------------------------------------------------------ MASTER-IDEM-003

    private class RecordingStore(private val delegate: IdempotencyStore = InMemoryIdempotencyStore()) : IdempotencyStore {
        val completed = CopyOnWriteArrayList<StoredIdempotentResponse>()
        val released = CopyOnWriteArrayList<IdempotencyClaim>()
        override suspend fun begin(request: IdempotencyBeginRequest) = delegate.begin(request)
        override suspend fun complete(claim: IdempotencyClaim, response: StoredIdempotentResponse) {
            completed += response
            delegate.complete(claim, response)
        }
        override suspend fun release(claim: IdempotencyClaim) {
            released += claim
            delegate.release(claim)
        }
    }

    private val idemKey = "3F2504E0-4F89-41D3-9A0C-0305E82C3301"

    private fun idemRun(
        store: RecordingStore,
        respond: suspend ApplicationCall.() -> Unit,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { explicitNulls = false }) }
            installRequestCorrelation()
            installBackendStatusPages()
            routing {
                post("/api/v1/app/cosas") {
                    call.receiveCanonicalCommand<IdemProbeCommand>()
                    call.respond()
                }.install(CanonicalIdempotency) {
                    routeTemplate = "/api/v1/app/cosas"
                    this.store = store
                    actor = { u1 }
                }
            }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.send() = client.post("/api/v1/app/cosas") {
        header(IdempotencyHeaders.KEY, idemKey)
        contentType(ContentType.Application.Json)
        setBody("""{"modo":"NORMAL"}""")
    }

    @Test
    fun aStreamedResponseCannotBeReplayedSoItsClaimIsReleasedNotCompletedWithAnEmptyBody() {
        val store = RecordingStore()
        idemRun(store, { respondBytesWriter(ContentType.Application.Json, HttpStatusCode.Created) { writeStringUtf8("""{"id":"x"}""") } }) {
            assertEquals(HttpStatusCode.Created, send().status)
            assertTrue(store.completed.isEmpty(), "no se guarda un cuerpo vacio para reproducir")
            assertEquals(1, store.released.size)
            // El reintento con la misma clave vuelve a ejecutar (no hay Replay vacio).
            assertNull(send().headers[IdempotencyHeaders.REPLAYED])
        }
    }

    @Test
    fun aLiteralNullJsonBodyIsNotStoredAsAnEmptyReplay() {
        val store = RecordingStore()
        idemRun(store, { respondText("null", ContentType.Application.Json, HttpStatusCode.OK) }) {
            send()
            assertTrue(store.completed.isEmpty())
            assertEquals(1, store.released.size)
        }
    }

    @Test
    fun aNormalJsonBodyStillCompletesAndReplays() {
        val store = RecordingStore()
        idemRun(store, { respondText("""{"id":"x"}""", ContentType.Application.Json, HttpStatusCode.Created) }) {
            send()
            assertEquals(1, store.completed.size)
            assertTrue(store.released.isEmpty())
            assertEquals("true", send().headers[IdempotencyHeaders.REPLAYED])
        }
    }

    @Test
    fun anEmptyNoContentResponseIsStillStoredAsReplayable() {
        val store = RecordingStore()
        idemRun(store, { respond(HttpStatusCode.NoContent) }) {
            send()
            assertEquals(1, store.completed.size)
            assertNull(store.completed.single().body)
        }
    }

    // ------------------------------------------------------------------ MASTER-QUALITY-003

    @Test
    fun aCancelledJwtVerificationPropagatesInsteadOfBecoming401() = testApplication {
        val resolver = CanonicalIdentityResolver(
            verifySubject = { token -> if (token.startsWith("a.")) throw CancellationException("cancelled") else throw IllegalStateException("bad") },
            directory = { null },
        )
        application {
            install(ContentNegotiation) { json(Json { explicitNulls = false }) }
            installRequestCorrelation()
            installBackendStatusPages()
            routing {
                get("/probe") {
                    try {
                        resolver.userId(call)
                        call.respondText("ok")
                    } catch (cancelled: CancellationException) {
                        call.respondText("cancelled", status = HttpStatusCode.Accepted)
                    }
                }
            }
        }
        val cancelled = client.get("/probe") { header(HttpHeaders.Authorization, "Bearer a.b.c") }
        assertEquals(HttpStatusCode.Accepted, cancelled.status)
        // Un token realmente invalido sigue siendo 401.
        val invalid = client.get("/probe") { header(HttpHeaders.Authorization, "Bearer x.b.c") }
        assertEquals(HttpStatusCode.Unauthorized, invalid.status)
    }

    // ------------------------------------------------------------------ MASTER-QUALITY-007

    @Test
    fun aTruncatedSpecialtyReadFailsClosedInsteadOfResolvingOnIncompleteData() = runBlocking {
        val upstream = FakeUpstream()
        // MASTER2-API-003: se piden LIMIT+1 filas; solo MAS de LIMIT es lectura truncada (antes fallaba en exactamente LIMIT).
        val full = (1..SPECIALTY_FETCH_LIMIT + 1).joinToString(",", "[", "]") { """{"id":"id-$it","nombre":"Esp $it","slug":"esp-$it"}""" }
        upstream.onGet = { full }
        val resolver = PostgrestSpecialtyResolver(upstream, testJson)
        val failure = assertFailsWith<BackendDomainException> { resolver.resolve("esp 1") }
        assertEquals(CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR.name, failure.errorCode)
        assertTrue("limit=${SPECIALTY_FETCH_LIMIT + 1}" in upstream.log.single())
        // Un catalogo de exactamente LIMIT filas es una lectura completa y resuelve (MASTER2-API-003).
        val partial = (1..SPECIALTY_FETCH_LIMIT).joinToString(",", "[", "]") { """{"id":"id-$it","nombre":"Esp $it","slug":"esp-$it"}""" }
        upstream.onGet = { partial }
        assertEquals("id-1", resolver.resolve("esp 1"))
    }

    // ------------------------------------------------------------------ MASTER-QUALITY-008

    @Test
    fun theProfileStateReadIsOneSharedQueryAndRowMapping() {
        val user = CanonicalUserId.fromJwtSubject(u1)
        assertEquals(
            "/rest/v1/perfiles_usuario?select=usuario_id,estado_cuenta,onboarding_completado&usuario_id=eq.$u1&limit=1",
            PostgrestCanonicalProfileDirectory.profileStatePath(user),
        )
        val identity = PostgrestCanonicalProfileDirectory.ProfileRow(u1.uppercase(), "bloqueada", null)
            .toIdentity(user) { error("no mismatch expected") }
        assertEquals(AccountStatus.BLOQUEADA, identity.accountStatus)
        assertFalse(identity.onboardingCompleted)
        assertFailsWith<IllegalStateException> {
            PostgrestCanonicalProfileDirectory.ProfileRow("22222222-2222-4222-8222-222222222222", "activa", true)
                .toIdentity(user) { throw IllegalStateException("mismatch") }
        }
    }

    @Test
    fun oneUuidRuleServesTheSubjectAndTheIdempotencyKey() {
        assertFailsWith<BackendDomainException> { CanonicalUserId.fromJwtSubject("not-a-uuid") }
        assertFailsWith<BackendDomainException> { IdempotencyKey.parse("not-a-uuid", required = true) }
        assertEquals(idemKey.lowercase(), IdempotencyKey.parse(idemKey, required = true)!!.value)
        assertEquals(u1, CanonicalUserId.fromJwtSubject(u1.uppercase()).value)
    }
}
