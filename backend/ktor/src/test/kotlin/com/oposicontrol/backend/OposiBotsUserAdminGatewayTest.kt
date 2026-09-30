package com.oposicontrol.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OposiBotsUserAdminGatewayTest {
    @Test
    fun listUsersQueriesUsuarioTableWithLimitOffsetAndOrder() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { request ->
            requests += request
            respond(
                content = ByteReadChannel(
                    """[{"id":1,"auth_user_id":"11111111-1111-1111-1111-111111111111","email":"a@example.com","nombre":"Ada","apellidos":"Lovelace","username":"ada","especialidad_registro":"Enfermeria","perfil_completo":true,"perfil_completado_at":"2026-08-01T00:00:00Z","created_at":"2026-08-01T00:00:00Z","updated_at":"2026-08-01T00:00:00Z"}]""",
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val gateway = SupabaseOposiBotsUserAdminGateway("https://example.supabase.co", "service-role-key", Json { ignoreUnknownKeys = true }, client)

        val result = gateway.listUsers(limit = 50, offset = 0, search = null)

        assertEquals(1, requests.size)
        assertTrue(requests.single().url.encodedPath.endsWith("/rest/v1/usuario"))
        assertEquals("id,auth_user_id,email,nombre,apellidos,username,especialidad_registro,perfil_completo,perfil_completado_at,created_at,updated_at", requests.single().url.parameters["select"])
        assertEquals("50", requests.single().url.parameters["limit"])
        assertEquals("0", requests.single().url.parameters["offset"])
        assertEquals("created_at.desc", requests.single().url.parameters["order"])
        assertEquals("service-role-key", requests.single().headers["apikey"])
        // F-02: la credencial la decide `SupabaseRequestAuthPolicy`. "service-role-key"
        // no tiene forma de JWT, así que NO viaja como `Bearer` (la matriz completa,
        // con clave JWT legacy incluida, vive en `SupabaseEnvironmentWiringTest`).
        assertEquals(null, requests.single().headers[HttpHeaders.Authorization])
        assertEquals(1, result.size)
        assertEquals("ada", result.single().username)
    }

    @Test
    fun getUserReturnsNullWhenPostgrestReturnsEmptyArray() = runTest {
        val client = HttpClient(MockEngine { respond(content = ByteReadChannel("[]"), status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json")) })
        val gateway = SupabaseOposiBotsUserAdminGateway("https://example.supabase.co", "service-role-key", Json { ignoreUnknownKeys = true }, client)

        assertEquals(null, gateway.getUser(999))
    }

    @Test
    fun createUserCallsAuthAdminThenInsertsUsuarioRowLinkedByAuthUserId() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { request ->
            requests += request
            when {
                request.url.encodedPath.endsWith("/auth/v1/admin/users") ->
                    respond(content = ByteReadChannel("""{"id":"22222222-2222-2222-2222-222222222222"}"""), status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                else ->
                    respond(
                        content = ByteReadChannel("""[{"id":42,"auth_user_id":"22222222-2222-2222-2222-222222222222","email":"new@example.com","nombre":"Grace","apellidos":"Hopper","username":null,"especialidad_registro":"Celador","perfil_completo":false,"perfil_completado_at":null,"created_at":"2026-08-01T00:00:00Z","updated_at":"2026-08-01T00:00:00Z"}]"""),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
            }
        })
        val gateway = SupabaseOposiBotsUserAdminGateway("https://example.supabase.co", "service-role-key", Json { ignoreUnknownKeys = true }, client)

        val created = gateway.createUser(
            OposiBotsUserAdminCreateRequest(
                email = "new@example.com",
                password = null,
                nombre = "Grace",
                apellidos = "Hopper",
                username = null,
                especialidadRegistro = "Celador",
            ),
        )

        assertEquals(2, requests.size)
        assertTrue(requests[0].url.encodedPath.endsWith("/auth/v1/admin/users"))
        assertTrue(requests[1].url.encodedPath.endsWith("/rest/v1/usuario"))
        assertEquals(42, created.id)
        assertEquals("22222222-2222-2222-2222-222222222222", created.authUserId)
    }

    @Test
    fun updateUserPatchesOnlyProvidedFields() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { request ->
            requests += request
            respond(
                content = ByteReadChannel("""[{"id":42,"auth_user_id":"22222222-2222-2222-2222-222222222222","email":"new@example.com","nombre":"Grace","apellidos":"Hopper","username":"grace","especialidad_registro":"Celador","perfil_completo":true,"perfil_completado_at":"2026-08-01T00:00:00Z","created_at":"2026-08-01T00:00:00Z","updated_at":"2026-08-01T00:00:01Z"}]"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val gateway = SupabaseOposiBotsUserAdminGateway("https://example.supabase.co", "service-role-key", Json { ignoreUnknownKeys = true }, client)

        val updated = gateway.updateUser(42, OposiBotsUserAdminUpdateRequest(username = "grace"))

        assertEquals(1, requests.size)
        assertTrue(requests.single().url.encodedPath.endsWith("/rest/v1/usuario"))
        assertEquals("eq.42", requests.single().url.parameters["id"])
        assertEquals("grace", updated?.username)
    }

    @Test
    fun updateUserReturnsNullWhenPostgrestReturnsEmptyArray() = runTest {
        val client = HttpClient(MockEngine { respond(content = ByteReadChannel("[]"), status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json")) })
        val gateway = SupabaseOposiBotsUserAdminGateway("https://example.supabase.co", "service-role-key", Json { ignoreUnknownKeys = true }, client)

        assertEquals(null, gateway.updateUser(999, OposiBotsUserAdminUpdateRequest(username = "ghost")))
    }

    @Test
    fun deleteUserReadsTheRowThenRemovesAuthIdentityThenUsuarioRow() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { request ->
            requests += request
            when {
                request.url.parameters["id"] == "eq.42" && request.url.encodedPath.endsWith("/rest/v1/usuario") ->
                    respond(
                        content = ByteReadChannel("""[{"id":42,"auth_user_id":"22222222-2222-2222-2222-222222222222","email":"new@example.com","nombre":"Grace","apellidos":"Hopper","username":"grace","especialidad_registro":"Celador","perfil_completo":true,"perfil_completado_at":"2026-08-01T00:00:00Z","created_at":"2026-08-01T00:00:00Z","updated_at":"2026-08-01T00:00:00Z"}]"""),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                else -> respond(content = ByteReadChannel(""), status = HttpStatusCode.NoContent, headers = headersOf())
            }
        })
        val gateway = SupabaseOposiBotsUserAdminGateway("https://example.supabase.co", "service-role-key", Json { ignoreUnknownKeys = true }, client)

        val deleted = gateway.deleteUser(42)

        assertEquals(true, deleted)
        // MASTER5-API-008 (cambio DELIBERADO de orden): antes fila -> Auth, lo que dejaba una identidad viva y NO
        // reintentable si el segundo paso fallaba. Ahora: leer la fila, borrar Auth, borrar la fila.
        assertEquals(
            listOf(
                "GET /rest/v1/usuario",
                "DELETE /auth/v1/admin/users/22222222-2222-2222-2222-222222222222",
                "DELETE /rest/v1/usuario",
            ),
            requests.map { "${it.method.value} ${it.url.encodedPath}" },
        )
    }

    @Test
    fun deleteUserReturnsFalseWhenUserDoesNotExist() = runTest {
        val client = HttpClient(MockEngine { respond(content = ByteReadChannel("[]"), status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json")) })
        val gateway = SupabaseOposiBotsUserAdminGateway("https://example.supabase.co", "service-role-key", Json { ignoreUnknownKeys = true }, client)

        assertEquals(false, gateway.deleteUser(999))
    }

    @Test
    fun listUsersEncodesSearchTermWithSpacesAndAccentedCharacters() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { request ->
            requests += request
            respond(
                content = ByteReadChannel("[]"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val gateway = SupabaseOposiBotsUserAdminGateway("https://example.supabase.co", "service-role-key", Json { ignoreUnknownKeys = true }, client)

        gateway.listUsers(limit = 50, offset = 0, search = "José García")

        assertEquals(1, requests.size)
        val queryString = requests.single().url.encodedQuery
        // Should contain encoded search term, not raw spaces or accents
        assertFalse(queryString.contains(" "), "Should not contain raw spaces in query string")
        assertTrue(queryString.contains("%"), "Should contain URL-encoded characters")
        // The actual accented characters in the input should not appear raw
        assertFalse(queryString.contains("é"), "Should not contain raw accented é from José")
        assertFalse(queryString.contains("í"), "Should not contain raw accented í from García")
    }

    @Test
    fun listUsersStripsPostgRESTMetacharactersFromSearch() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { request ->
            requests += request
            respond(
                content = ByteReadChannel("[]"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val gateway = SupabaseOposiBotsUserAdminGateway("https://example.supabase.co", "service-role-key", Json { ignoreUnknownKeys = true }, client)

        gateway.listUsers(limit = 50, offset = 0, search = "a,b)(test*100%)")

        assertEquals(1, requests.size)
        val fullUrl = requests.single().url.toString()
        // Metacharacters should be stripped before encoding
        // After stripping "a,b)(test*100%)" becomes "abtest100"
        assertTrue(fullUrl.contains("abtest100"), "Should contain stripped term after metacharacters removed")
        // The URL should not contain unencoded PostgREST filter metacharacters that would break the or=() clause
        assertFalse(fullUrl.contains("a%2Cb%29"), "Should not contain encoded metacharacters as part of the search value")
    }

    @Test
    fun updateUserThrowsOnBlankNombre() = runTest {
        val client = HttpClient(MockEngine { respond(content = ByteReadChannel("[]"), status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json")) })
        val gateway = SupabaseOposiBotsUserAdminGateway("https://example.supabase.co", "service-role-key", Json { ignoreUnknownKeys = true }, client)

        assertFailsWith<IllegalArgumentException> {
            gateway.updateUser(42, OposiBotsUserAdminUpdateRequest(nombre = "   "))
        }
    }

    @Test
    fun updateUserThrowsOnBlankApellidos() = runTest {
        val client = HttpClient(MockEngine { respond(content = ByteReadChannel("[]"), status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json")) })
        val gateway = SupabaseOposiBotsUserAdminGateway("https://example.supabase.co", "service-role-key", Json { ignoreUnknownKeys = true }, client)

        assertFailsWith<IllegalArgumentException> {
            gateway.updateUser(42, OposiBotsUserAdminUpdateRequest(apellidos = "   "))
        }
    }

    @Test
    fun updateUserThrowsOnBlankEspecialidadRegistro() = runTest {
        val client = HttpClient(MockEngine { respond(content = ByteReadChannel("[]"), status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json")) })
        val gateway = SupabaseOposiBotsUserAdminGateway("https://example.supabase.co", "service-role-key", Json { ignoreUnknownKeys = true }, client)

        assertFailsWith<IllegalArgumentException> {
            gateway.updateUser(42, OposiBotsUserAdminUpdateRequest(especialidadRegistro = "   "))
        }
    }

    @Test
    fun createUserCompensatesOrphanedAuthIdentityWhenUsuarioInsertFails() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { request ->
            requests += request
            when {
                request.url.encodedPath.endsWith("/auth/v1/admin/users") && requests.size == 1 ->
                    respond(content = ByteReadChannel("""{"id":"33333333-3333-3333-3333-333333333333"}"""), status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                request.url.encodedPath.endsWith("/rest/v1/usuario") && request.method == io.ktor.http.HttpMethod.Post ->
                    respond(content = ByteReadChannel("""{"code":"23505","message":"duplicate key"}"""), status = HttpStatusCode.Conflict, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                else -> respond(content = ByteReadChannel(""), status = HttpStatusCode.NoContent, headers = headersOf())
            }
        })
        val gateway = SupabaseOposiBotsUserAdminGateway("https://example.supabase.co", "service-role-key", Json { ignoreUnknownKeys = true }, client)

        assertFailsWith<IllegalArgumentException> {
            gateway.createUser(
                OposiBotsUserAdminCreateRequest(
                    email = "dup@example.com",
                    password = null,
                    nombre = "Test",
                    apellidos = "User",
                    username = null,
                    especialidadRegistro = "Sanidad",
                ),
            )
        }
        // Should have 3 requests: POST auth, POST usuario (fails), DELETE auth (compensation)
        assertEquals(3, requests.size)
        assertTrue(requests[0].url.encodedPath.endsWith("/auth/v1/admin/users"))
        assertTrue(requests[1].url.encodedPath.endsWith("/rest/v1/usuario"))
        assertTrue(requests[2].url.encodedPath.endsWith("/auth/v1/admin/users/33333333-3333-3333-3333-333333333333"))
    }

    @Test
    fun deleteUserTreats404FromAuthDeleteAsSuccess() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { request ->
            requests += request
            when {
                request.url.parameters["id"] == "eq.42" && request.url.encodedPath.endsWith("/rest/v1/usuario") ->
                    respond(
                        content = ByteReadChannel("""[{"id":42,"auth_user_id":"44444444-4444-4444-4444-444444444444","email":"test@example.com","nombre":"Test","apellidos":"User","username":"test","especialidad_registro":"Sanidad","perfil_completo":true,"perfil_completado_at":"2026-08-01T00:00:00Z","created_at":"2026-08-01T00:00:00Z","updated_at":"2026-08-01T00:00:00Z"}]"""),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                request.url.encodedPath.endsWith("/auth/v1/admin/users/44444444-4444-4444-4444-444444444444") ->
                    respond(content = ByteReadChannel("""{"message":"Not found"}"""), status = HttpStatusCode.NotFound, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                else -> respond(content = ByteReadChannel(""), status = HttpStatusCode.NoContent, headers = headersOf())
            }
        })
        val gateway = SupabaseOposiBotsUserAdminGateway("https://example.supabase.co", "service-role-key", Json { ignoreUnknownKeys = true }, client)

        val result = gateway.deleteUser(42)

        assertEquals(true, result)
        // MASTER5-API-008 (cambio DELIBERADO): lectura + DELETE de Auth (404 = exito) + DELETE de la fila = 3.
        assertEquals(3, requests.size)
        assertEquals("DELETE /rest/v1/usuario", requests.last().let { "${it.method.value} ${it.url.encodedPath}" })
    }
}
