package com.oposicontrol.backend

import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F-02 · F-17: la lectura de rol que decide CADA petición administrativa.
 *
 * `AppV1Gateway.hasAdminRole` corre delante de todas las rutas `/api/v1/admin/…`.
 * Antes eran dos lecturas por el camino legacy —`Bearer` sin mirar la forma de la
 * clave, `HttpClient` propio, fallos de red sin traducir— con una caché de 30 s.
 * Ahora es una lectura con embed, por el transporte, con caché de 90 s.
 */
class AppV1GatewayAuthorizationTest {

    private val adminId = "11111111-1111-4111-8111-111111111111"
    private val plainUserId = "22222222-2222-4222-8222-222222222222"

    // -----------------------------------------------------------------
    // Una lectura, con `!inner`
    // -----------------------------------------------------------------

    /**
     * La consulta EXACTA. `!inner` no es sintaxis opcional: sin él PostgREST filtra
     * sólo el recurso embebido y devuelve la fila de `roles_usuario` con
     * `"roles_app": null` para cualquier usuario con cualquier rol.
     */
    @Test
    fun theRoleCheckIsOneEmbeddedReadWithAnInnerJoinOnTheAdminCodes() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(record = sent::add) { V1StubResponse(200, "[]") }

        gateway.hasAdminRole(adminId)

        val request = sent.single()
        assertEquals("/rest/v1/roles_usuario", request.uri().rawPath)
        assertEquals(
            "select=rol_id,roles_app!inner(codigo)&usuario_id=eq.$adminId&roles_app.codigo=in.(superadmin,admin)&limit=1",
            request.uri().rawQuery,
        )
        assertEquals("GET", request.method())
    }

    @Test
    fun anAdminOrSuperadminRowGrantsAccessAndNoRowsDoesNot() {
        val gateway = gatewayWith { uri -> postgrestRoles(uri) }

        assertTrue(gateway.hasAdminRole(adminId))
        assertFalse(gateway.hasAdminRole(plainUserId), "sólo rol `usuario`: la consulta con !inner no devuelve filas")
    }

    /**
     * Defensa en profundidad: aunque la respuesta trajera una fila —porque alguien
     * quitó `!inner`, o por un cambio de PostgREST—, sin un `codigo` de admin en la
     * fila la decisión es NO.
     */
    @Test
    fun aRowWithoutAnAdminCodeNeverGrantsAccess() {
        listOf(
            """[{"rol_id":"r1","roles_app":null}]""",
            """[{"rol_id":"r1","roles_app":{"codigo":"usuario"}}]""",
            """[{"rol_id":"r1","roles_app":{"codigo":"moderador"}}]""",
            """[{"rol_id":"r1"}]""",
        ).forEach { body ->
            val gateway = gatewayWith { V1StubResponse(200, body) }

            assertFalse(gateway.hasAdminRole(plainUserId), "concedió admin con $body")
        }
    }

    // -----------------------------------------------------------------
    // Caché de 90 s (F-17)
    // -----------------------------------------------------------------

    @Test
    fun theDecisionIsCachedForNinetySecondsAndReadAgainAfterwards() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        var now = 1_000_000L
        val gateway = gatewayWith(record = sent::add, clock = { now }) { uri -> postgrestRoles(uri) }

        assertTrue(gateway.hasAdminRole(adminId))
        now += 45_000
        assertTrue(gateway.hasAdminRole(adminId))
        now += 44_999
        assertTrue(gateway.hasAdminRole(adminId))
        assertEquals(1, sent.size, "dentro del TTL no se vuelve a preguntar: el refresco de 60 s del panel paga 0 lecturas")

        now += 1
        assertTrue(gateway.hasAdminRole(adminId))
        assertEquals(2, sent.size, "pasado el TTL se vuelve a leer: revocar un rol tiene que surtir efecto")
        assertEquals(90_000L, AppV1Gateway.ADMIN_ROLE_CACHE_TTL_MILLIS)
    }

    @Test
    fun aFailedRoleReadIsNotCachedAndSurfacesAsAnUpstreamFailure() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        var failing = true
        val gateway = gatewayWith(record = sent::add) { uri ->
            if (failing) V1StubResponse(500, """{"message":"boom"}""") else postgrestRoles(uri)
        }

        assertFailsWith<IllegalStateException> { gateway.hasAdminRole(adminId) }
        failing = false

        assertTrue(gateway.hasAdminRole(adminId), "un fallo no se cachea como `false`")
        assertEquals(2, sent.size)
    }

    // -----------------------------------------------------------------
    // Transporte (F-02)
    // -----------------------------------------------------------------

    /**
     * Un fallo de RED sale como `IllegalStateException`, que StatusPages publica como
     * 500 con referencia. Antes era un `IOException` crudo que acababa en
     * `UNCAUGHT_EXCEPTION`, mal clasificado.
     */
    @Test
    fun aNetworkFailureIsATranslatedUpstreamErrorAndNotARawIOException() {
        val gateway = gatewayWith { throw IOException("connection reset by peer") }

        val error = assertFailsWith<IllegalStateException> { gateway.hasAdminRole(adminId) }

        assertTrue(error.message.orEmpty().contains("/rest/v1/roles_usuario"), error.message)
    }

    @Test
    fun aSecretKeyTravelsOnlyAsApiKeyAndALegacyJwtAlsoAsBearer() {
        listOf("sb_secret_roles_fixture" to null, "aaa.bbb.ccc" to "Bearer aaa.bbb.ccc").forEach { (key, bearer) ->
            val sent = CopyOnWriteArrayList<HttpRequest>()
            val gateway = gatewayWith(key = key, record = sent::add) { V1StubResponse(200, "[]") }

            gateway.hasAdminRole(adminId)

            val headers = sent.single().headers()
            assertEquals(key, headers.firstValue("apikey").orElse(null))
            assertEquals(bearer, headers.firstValue("Authorization").orElse(null), "clave $key")
            // Nunca dos identidades en la misma petición.
            assertEquals(if (bearer == null) 0 else 1, headers.allValues("Authorization").size, "clave $key")
        }
    }

    @Test
    fun aSubjectThatIsNotAUuidIsRejectedBeforeAnyRead() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(record = sent::add) { V1StubResponse(200, "[]") }

        assertFailsWith<IllegalArgumentException> { gateway.hasAdminRole("no-soy-un-uuid") }
        assertTrue(sent.isEmpty(), "un sub que no es UUID no puede llegar a un filtro de PostgREST")
    }

    /** `record_event` responde un OBJETO: el paso al transporte no puede romper esa forma. */
    @Test
    fun anRpcThatAnswersAnObjectIsStillReadAsOneRow() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(record = sent::add) { V1StubResponse(200, """{"event_id":"ev-1"}""") }

        val eventId = gateway.registrarEvento(
            adminId,
            AppEventoRequest(nombreEvento = "pantalla_vista", idempotencyKey = "idem-1"),
        )

        assertEquals("ev-1", eventId)
        assertEquals("/rest/v1/rpc/record_event", sent.single().uri().rawPath)
        assertEquals("POST", sent.single().method())
    }

    /**
     * Una escritura pide la fila creada. Sin `Prefer: return=representation` PostgREST
     * responde 201 con el cuerpo VACÍO, y el `first()` de quien espera el id revienta
     * después de haber escrito: el cliente ve un 500 y el feedback ya está guardado.
     */
    @Test
    fun aWriteAsksPostgrestToReturnTheCreatedRow() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gatewayWith(record = sent::add) { uri ->
            when {
                "/rest/v1/intentos_examen" in uri -> V1StubResponse(200, """[{"examen_id":"99999999-9999-4999-8999-999999999999"}]""")
                "/rest/v1/preguntas_examen" in uri -> V1StubResponse(200, """[{"pregunta_id":"33333333-3333-4333-8333-333333333333"}]""")
                else -> V1StubResponse(201, """[{"id":"fb-1"}]""")
            }
        }

        val feedbackId = gateway.crearFeedback(
            adminId,
            AppFeedbackRequest(preguntaId = "33333333-3333-4333-8333-333333333333", intentoId = "44444444-4444-4444-8444-444444444444"),
        )

        assertEquals("fb-1", feedbackId)
        val write = sent.single { it.method() == "POST" }
        assertEquals("POST", write.method())
        assertEquals("/rest/v1/feedback_pregunta", write.uri().rawPath)
        assertEquals("return=representation", write.headers().firstValue("Prefer").orElse(null))
    }

    // -----------------------------------------------------------------
    // Andamiaje
    // -----------------------------------------------------------------

    /**
     * PostgREST con la consulta de rol: con `!inner` y el filtro por código, sólo el
     * administrador tiene fila. Sin `!inner`, devolvería la fila del usuario normal
     * con `roles_app: null` —la trampa que `aRowWithoutAnAdminCodeNeverGrantsAccess`
     * cubre—.
     */
    private fun postgrestRoles(uri: String): V1StubResponse = when {
        uri.contains("usuario_id=eq.$adminId") && uri.contains("roles_app!inner") ->
            V1StubResponse(200, """[{"rol_id":"r-admin","roles_app":{"codigo":"admin"}}]""")
        uri.contains("usuario_id=eq.$plainUserId") && !uri.contains("roles_app!inner") ->
            V1StubResponse(200, """[{"rol_id":"r-usuario","roles_app":null}]""")
        else -> V1StubResponse(200, "[]")
    }

    private fun gatewayWith(
        key: String = "stub-key",
        record: (HttpRequest) -> Unit = {},
        clock: () -> Long = System::currentTimeMillis,
        responder: (String) -> V1StubResponse,
    ): AppV1Gateway = AppV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = key,
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient(record, responder),
        clock = clock,
    )
}
