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
import kotlin.test.assertEquals
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
        assertEquals("Bearer service-role-key", requests.single().headers[HttpHeaders.Authorization])
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
    fun deleteUserRemovesUsuarioRowThenAuthIdentityInThatOrder() = runTest {
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
        assertEquals(2, requests.size)
        assertTrue(requests[0].url.encodedPath.endsWith("/rest/v1/usuario"), "usuario row must be deleted first")
        assertTrue(requests[1].url.encodedPath.endsWith("/auth/v1/admin/users/22222222-2222-2222-2222-222222222222"), "auth identity deleted second")
    }

    @Test
    fun deleteUserReturnsFalseWhenUserDoesNotExist() = runTest {
        val client = HttpClient(MockEngine { respond(content = ByteReadChannel("[]"), status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json")) })
        val gateway = SupabaseOposiBotsUserAdminGateway("https://example.supabase.co", "service-role-key", Json { ignoreUnknownKeys = true }, client)

        assertEquals(false, gateway.deleteUser(999))
    }
}
