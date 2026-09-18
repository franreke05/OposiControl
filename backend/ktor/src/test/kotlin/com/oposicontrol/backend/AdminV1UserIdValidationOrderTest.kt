package com.oposicontrol.backend

import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.net.http.HttpRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ORDEN DE VALIDACION DE LOS IDENTIFICADORES DE USUARIO.
 *
 * Que `validatedUuid` APAREZCA en una funcion no prueba nada: puede estar
 * despues de la llamada al upstream. Un test que solo mirase el 400 tampoco,
 * porque una implementacion que valida DESPUES de haber llamado a Supabase
 * devuelve el mismo 400. Lo que se fija aqui es que **no sale ni una peticion**.
 *
 * Mismo criterio y mismo arnes que `AdminV1FilterTruncationValidationTest`, que
 * cubre esta clase en las verticales de examenes y preguntas. Este cubre la
 * superficie de Usuarios y la economia por usuario.
 *
 * Dos entradas hostiles distintas, porque fallan de forma distinta:
 *
 * - `ROLE_SYNC_LAST_SUPERADMIN` — el codigo de una guarda de dominio. Si llega a
 *   PostgREST, Postgres lo repite en «invalid input syntax for type uuid» y el
 *   backend puede traducirlo a un 409 de dominio que NINGUNA guarda levanto. El
 *   409 dejaria de ser prueba de que la invariante salto.
 * - `abc&select=*` — inyeccion de parametros de PostgREST. El segmento se
 *   interpola en `usuario_id=eq.$id`, asi que un `&` anade filtros propios a una
 *   peticion hecha con la credencial elevada.
 */
class AdminV1UserIdValidationOrderTest {

    private val usuarioValido = "77777777-7777-4777-8777-777777777777"
    private val rolValido = "88888888-8888-4888-8888-888888888888"

    /** El codigo de dominio, tal cual, como segmento de path. */
    private val codigoDeGuarda = "ROLE_SYNC_LAST_SUPERADMIN"

    /** `abc&select=*` percent-encoded. */
    private val inyeccion = "abc%26select=%2A"

    // ---- /usuarios --------------------------------------------------------

    @Test fun readingAUserWithANonUuidPathNeverReachesSupabase() = testApplication {
        val upstream = mutableListOf<HttpRequest>()
        application { usersTestModule(upstream) }

        val response = client.get("/api/v1/admin/usuarios/$codigoDeGuarda")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(upstream.isEmpty(), "salieron: ${upstream.map { it.uri() }}")
    }

    @Test fun readingTheUserDetailWithANonUuidPathNeverReachesSupabase() = testApplication {
        val upstream = mutableListOf<HttpRequest>()
        application { usersTestModule(upstream) }

        val response = client.get("/api/v1/admin/usuarios/$inyeccion/detalle")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(upstream.isEmpty(), "el detalle abre diez lecturas: no debe salir ninguna")
    }

    @Test fun updatingAUserWithANonUuidPathNeverReachesSupabase() = testApplication {
        val upstream = mutableListOf<HttpRequest>()
        application { usersTestModule(upstream) }

        val response = client.patch("/api/v1/admin/usuarios/$inyeccion") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"$usuarioValido","nombre":"x"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(upstream.isEmpty(), "salieron: ${upstream.map { it.uri() }}")
    }

    // ---- El vector del falso 409 ------------------------------------------

    @Test fun aDomainGuardCodeInTheRolesPathIsRejectedAsInputAndNeverReachesTheRpc() = testApplication {
        val upstream = mutableListOf<HttpRequest>()
        application { usersTestModule(upstream) }

        val response = client.post("/api/v1/admin/usuarios/$codigoDeGuarda/roles") {
            contentType(ContentType.Application.Json)
            setBody("""{"roleIds":["$rolValido"]}""")
        }

        assertEquals(
            HttpStatusCode.BadRequest,
            response.status,
            "tiene que ser un error de ENTRADA; un 409 aqui seria la guarda falsificada",
        )
        assertEquals(
            HttpStatusCode.Conflict != response.status,
            true,
            "un 409 solo puede significar que la invariante salto de verdad",
        )
        assertTrue(upstream.isEmpty(), "la RPC no puede llegar a llamarse: ${upstream.map { it.uri() }}")
    }

    // ---- Economia por usuario ---------------------------------------------

    @Test fun listingTicketTransactionsWithANonUuidPathNeverReachesSupabase() = testApplication {
        val upstream = mutableListOf<HttpRequest>()
        application { usersTestModule(upstream) }

        val response = client.get("/api/v1/admin/tickets/$codigoDeGuarda/transacciones")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(upstream.isEmpty(), "salieron: ${upstream.map { it.uri() }}")
    }

    @Test fun aPostgrestInjectionInTheTicketsPathNeverReachesSupabase() = testApplication {
        val upstream = mutableListOf<HttpRequest>()
        application { usersTestModule(upstream) }

        val response = client.get("/api/v1/admin/tickets/$inyeccion/transacciones")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(
            upstream.isEmpty(),
            "un `&` en el segmento anade filtros propios a la consulta elevada: ${upstream.map { it.uri() }}",
        )
    }

    @Test fun appendingATicketAdjustmentWithANonUuidPathNeverReachesSupabase() = testApplication {
        val upstream = mutableListOf<HttpRequest>()
        application { usersTestModule(upstream) }

        val response = client.post("/api/v1/admin/tickets/$inyeccion/transacciones") {
            contentType(ContentType.Application.Json)
            setBody("""{"amount":1.0,"reason":"ajuste","idempotencyKey":"k-1"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(upstream.isEmpty(), "salieron: ${upstream.map { it.uri() }}")
    }

    @Test fun grantingTicketsWithANonUuidPathNeverReachesSupabase() = testApplication {
        val upstream = mutableListOf<HttpRequest>()
        application { usersTestModule(upstream) }

        val response = client.post("/api/v1/admin/usuarios/$inyeccion/tickets") {
            contentType(ContentType.Application.Json)
            setBody("""{"cantidad":5,"motivo":"premio","idempotencyKey":"k-2"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(upstream.isEmpty(), "salieron: ${upstream.map { it.uri() }}")
    }

    // ---- Control positivo: el test no es vacuo ----------------------------

    @Test fun aValidUserIdStillReachesSupabaseWithItsOwnFilter() = testApplication {
        val upstream = mutableListOf<HttpRequest>()
        application { usersTestModule(upstream) }

        val response = client.get("/api/v1/admin/tickets/$usuarioValido/transacciones")

        assertEquals(HttpStatusCode.OK, response.status)
        val lectura = upstream.single { it.uri().rawPath.contains("transacciones_cartera") }
        val query = lectura.uri().rawQuery.orEmpty()
        assertTrue(query.contains("usuario_id=eq.$usuarioValido"), query)
        assertTrue(query.contains("limit=100"), "la cota de la consulta sigue en su sitio: $query")
    }

    // -----------------------------------------------------------------

    private fun Application.usersTestModule(upstream: MutableList<HttpRequest>) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false })
        }
        install(StatusPages) {
            exception<IllegalArgumentException> { call, cause ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorDto(error = "bad_request", message = cause.message ?: "Invalid request"),
                )
            }
        }
        routing { adminV1Routes(gatewayRecording(upstream)) }
    }

    private fun gatewayRecording(upstream: MutableList<HttpRequest>): AdminV1Gateway =
        AdminV1Gateway.forTesting(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = V1StubHttpClient({ upstream += it }) { V1StubResponse(200, "[]") },
        )
}
