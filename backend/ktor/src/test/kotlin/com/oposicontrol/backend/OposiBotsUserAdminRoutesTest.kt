package com.oposicontrol.backend

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.patch
import io.ktor.client.request.delete
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OposiBotsUserAdminRoutesTest {
    private val testJson = Json { ignoreUnknownKeys = true }

    private fun Application.userAdminTestModule(gateway: OposiBotsUserAdminGateway) {
        install(ContentNegotiation) { json(testJson) }
        install(StatusPages) {
            exception<IllegalArgumentException> { call, cause -> call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", cause.message ?: "Invalid request")) }
            exception<IllegalStateException> { call, cause -> call.respond(HttpStatusCode.InternalServerError, ErrorDto("backend_supabase_error", cause.message ?: "Backend error")) }
            exception<OposiBotsUserAdminNotFoundException> { call, cause -> call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", cause.message ?: "Not found")) }
        }
        routing { oposiBotsUserAdminRoutes(gateway) }
    }

    @Test
    fun listReturnsDataEnvelope() = testApplication {
        val fake = FakeUserAdminGateway()
        application { userAdminTestModule(fake) }

        val response = client.get("/api/v1/admin/oposibots/usuarios")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"data\""))
        assertEquals(50, fake.lastListLimit)
        assertEquals(0, fake.lastListOffset)
    }

    @Test
    fun createReturns201WithCreatedUser() = testApplication {
        val fake = FakeUserAdminGateway()
        application { userAdminTestModule(fake) }

        val response = client.post("/api/v1/admin/oposibots/usuarios") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"new@example.com","nombre":"Grace","apellidos":"Hopper","especialidadRegistro":"Celador"}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        assertTrue(response.bodyAsText().contains("new@example.com"))
    }

    @Test
    fun getMissingUserReturns404() = testApplication {
        val fake = FakeUserAdminGateway()
        application { userAdminTestModule(fake) }

        val response = client.get("/api/v1/admin/oposibots/usuarios/999")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun updateAppliesPartialChangesAndReturnsUpdatedUser() = testApplication {
        val fake = FakeUserAdminGateway()
        application { userAdminTestModule(fake) }
        val created = client.post("/api/v1/admin/oposibots/usuarios") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"new@example.com","nombre":"Grace","apellidos":"Hopper","especialidadRegistro":"Celador"}""")
        }
        val createdId = testJson.decodeFromString<OposiBotsUserAdminSingleResponse>(created.bodyAsText()).data.id

        val response = client.patch("/api/v1/admin/oposibots/usuarios/$createdId") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"ghopper"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("ghopper"))
        assertTrue(response.bodyAsText().contains("Grace"), "unchanged fields must be preserved")
    }

    @Test
    fun deleteExistingUserReturns204AndMissingUserReturns404() = testApplication {
        val fake = FakeUserAdminGateway()
        application { userAdminTestModule(fake) }
        val created = client.post("/api/v1/admin/oposibots/usuarios") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"new@example.com","nombre":"Grace","apellidos":"Hopper","especialidadRegistro":"Celador"}""")
        }
        val createdId = testJson.decodeFromString<OposiBotsUserAdminSingleResponse>(created.bodyAsText()).data.id

        val firstDelete = client.delete("/api/v1/admin/oposibots/usuarios/$createdId")
        val secondDelete = client.delete("/api/v1/admin/oposibots/usuarios/$createdId")

        assertEquals(HttpStatusCode.NoContent, firstDelete.status)
        assertEquals(HttpStatusCode.NotFound, secondDelete.status)
    }
}

private class FakeUserAdminGateway : OposiBotsUserAdminGateway {
    var lastListLimit: Int? = null
    var lastListOffset: Int? = null
    var users = mutableMapOf<Int, OposiBotsUserAdminDto>()
    var nextId = 1

    override suspend fun listUsers(limit: Int, offset: Int, search: String?): List<OposiBotsUserAdminDto> {
        lastListLimit = limit
        lastListOffset = offset
        return users.values.toList()
    }

    override suspend fun getUser(id: Int): OposiBotsUserAdminDto? = users[id]

    override suspend fun createUser(request: OposiBotsUserAdminCreateRequest): OposiBotsUserAdminDto {
        val dto = OposiBotsUserAdminDto(
            id = nextId++,
            authUserId = "auth-${System.nanoTime()}",
            email = request.email,
            nombre = request.nombre,
            apellidos = request.apellidos,
            username = request.username,
            especialidadRegistro = request.especialidadRegistro,
            perfilCompleto = true,
            perfilCompletadoAt = "2026-08-01T00:00:00Z",
            createdAt = "2026-08-01T00:00:00Z",
            updatedAt = "2026-08-01T00:00:00Z",
        )
        users[dto.id] = dto
        return dto
    }

    override suspend fun updateUser(id: Int, request: OposiBotsUserAdminUpdateRequest): OposiBotsUserAdminDto? {
        val existing = users[id] ?: return null
        val updated = existing.copy(
            nombre = request.nombre ?: existing.nombre,
            apellidos = request.apellidos ?: existing.apellidos,
            username = request.username ?: existing.username,
            especialidadRegistro = request.especialidadRegistro ?: existing.especialidadRegistro,
            perfilCompleto = request.perfilCompleto ?: existing.perfilCompleto,
        )
        users[id] = updated
        return updated
    }

    override suspend fun deleteUser(id: Int): Boolean = users.remove(id) != null
}
