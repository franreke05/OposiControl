package com.oposicontrol.backend

import com.oposicontrol.admin.core.api.AdminApiDevelopment
import com.oposicontrol.admin.core.api.AdminApiHeaders
import com.oposicontrol.admin.core.api.AdminApiJson
import com.oposicontrol.admin.core.api.ApiErrorResponse
import com.oposicontrol.admin.core.api.ExamAccessType
import com.oposicontrol.admin.core.api.ExamResponse
import com.oposicontrol.admin.core.api.ExamType
import com.oposicontrol.admin.core.api.ImportQuestionsRequest
import com.oposicontrol.admin.core.api.PublicationStatus
import com.oposicontrol.admin.core.api.QuestionOptionInput
import com.oposicontrol.admin.core.api.SpecialtyResponse
import com.oposicontrol.admin.core.api.UpsertExamRequest
import com.oposicontrol.admin.core.api.UpsertQuestionRequest
import com.oposicontrol.admin.core.api.UpsertSpecialtyRequest
import com.oposicontrol.admin.core.api.CursorPage
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BackendSecurityRoutesTest {
    @Test
    fun `consumer Supabase account is not eligible for the admin panel`() {
        val consumer = AuthorizationSnapshot(
            userId = 42,
            displayName = "Consumer",
            email = "consumer@example.com",
            roleIds = emptyList(),
            permissions = emptySet(),
        )

        assertFalse(consumer.adminEligible)
    }

    @Test
    fun `development open token grants local access`() = testApplication {
        application {
            configureTestableBackend(
                DevelopmentOpenAccessAdminTokenAuthenticator(
                    configuration = backendConfiguration(environment = "dev"),
                    delegate = AdminTokenAuthenticator { null },
                ),
                StubGateway(),
            )
        }

        assertEquals(
            HttpStatusCode.OK,
            client.get("/api/admin/v1/specialties") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${AdminApiDevelopment.OPEN_ACCESS_TOKEN}",
                )
            }.status,
        )
    }

    @Test
    fun `development open token is rejected outside dev`() = testApplication {
        application {
            configureTestableBackend(
                DevelopmentOpenAccessAdminTokenAuthenticator(
                    configuration = backendConfiguration(environment = "production"),
                    delegate = AdminTokenAuthenticator { null },
                ),
                StubGateway(),
            )
        }

        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/admin/v1/specialties") {
                header(
                    HttpHeaders.Authorization,
                    "Bearer ${AdminApiDevelopment.OPEN_ACCESS_TOKEN}",
                )
            }.status,
        )
    }

    @Test
    fun `live is public and every admin route requires a bearer token`() = testApplication {
        application {
            configureTestableBackend(testAuthenticator(), StubGateway())
        }

        assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/admin/v1/specialties").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/admin/v1/specialties") {
                header(HttpHeaders.Authorization, "Bearer invalid")
            }.status,
        )
    }

    @Test
    fun `insufficient permission returns safe 403`() = testApplication {
        application {
            configureTestableBackend(testAuthenticator(), StubGateway())
        }

        val response = client.post("/api/admin/v1/specialties") {
            header(HttpHeaders.Authorization, "Bearer reader")
            contentType(ContentType.Application.Json)
            setBody(
                AdminApiJson.format.encodeToString(
                    UpsertSpecialtyRequest(name = "Auxiliar", slug = "auxiliar"),
                ),
            )
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("forbidden", response.error().code)
    }

    @Test
    fun `unexpected gateway failure never leaks its message`() = testApplication {
        application {
            configureTestableBackend(
                testAuthenticator(),
                object : StubGateway() {
                    override suspend fun listSpecialties(
                        actor: AdminPrincipal,
                        cursor: Long?,
                        limit: Int,
                    ): CursorPage<SpecialtyResponse> {
                        error("SUPABASE_SECRET_KEY=do-not-leak")
                    }
                },
            )
        }

        val response = client.get("/api/admin/v1/specialties") {
            header(HttpHeaders.Authorization, "Bearer reader")
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val body = response.bodyAsText()
        assertFalse(body.contains("do-not-leak"))
        assertEquals("internal_error", AdminApiJson.format.decodeFromString<ApiErrorResponse>(body).code)
    }

    @Test
    fun `optimistic version conflict returns 409`() = testApplication {
        application {
            configureTestableBackend(
                testAuthenticator(),
                object : StubGateway() {
                    override suspend fun saveExam(
                        id: Long?,
                        request: UpsertExamRequest,
                        actor: AdminPrincipal,
                    ): ExamResponse {
                        throw ConflictProblem()
                    }
                },
            )
        }

        val response = client.patch("/api/admin/v1/exams/7") {
            header(HttpHeaders.Authorization, "Bearer editor")
            contentType(ContentType.Application.Json)
            setBody(AdminApiJson.format.encodeToString(validExamRequest(expectedVersion = 2)))
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("version_conflict", response.error().code)
    }

    @Test
    fun `question import requires idempotency key and rejects more than 50 rows`() = testApplication {
        application {
            configureTestableBackend(testAuthenticator(), StubGateway())
        }
        val single = ImportQuestionsRequest(listOf(validQuestion()))
        val missingKey = client.post("/api/admin/v1/exams/7/questions/import") {
            header(HttpHeaders.Authorization, "Bearer editor")
            contentType(ContentType.Application.Json)
            setBody(AdminApiJson.format.encodeToString(single))
        }
        assertEquals(HttpStatusCode.BadRequest, missingKey.status)

        val tooMany = client.post("/api/admin/v1/exams/7/questions/import") {
            header(HttpHeaders.Authorization, "Bearer editor")
            header(AdminApiHeaders.IDEMPOTENCY_KEY, "test-import-0001")
            contentType(ContentType.Application.Json)
            setBody(AdminApiJson.format.encodeToString(ImportQuestionsRequest(List(51) { validQuestion() })))
        }
        assertEquals(HttpStatusCode.BadRequest, tooMany.status)
    }

    private suspend fun io.ktor.client.statement.HttpResponse.error(): ApiErrorResponse =
        AdminApiJson.format.decodeFromString(bodyAsText())
}

private open class StubGateway : CanonicalAdminGateway() {
    override suspend fun listSpecialties(
        actor: AdminPrincipal,
        cursor: Long?,
        limit: Int,
    ): CursorPage<SpecialtyResponse> =
        CursorPage(emptyList())
}

private fun testAuthenticator(): AdminTokenAuthenticator = AdminTokenAuthenticator { token ->
    val permissions = when (token) {
        "reader" -> setOf("content.read")
        "editor" -> setOf("content.read", "content.write", "content.publish")
        else -> return@AdminTokenAuthenticator null
    }
    AdminPrincipal(
        authUserId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        sessionId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
        userId = 1,
        displayName = "Admin",
        email = "",
        roleIds = listOf(1),
        permissions = permissions,
        accessToken = token,
    )
}

private fun backendConfiguration(environment: String) = BackendConfiguration(
    supabaseUrl = "https://example.supabase.co",
    supabasePublishableKey = "test-publishable-key",
    supabaseSecretKey = "server-only-test-secret",
    jwtAudience = "authenticated",
    appEnvironment = environment,
    serviceVersion = "test",
    bootstrapAdminEmail = null,
    developmentAdminUserId = 1,
    oposibotsPasswordRecoveryRedirectUrl = null,
    appleClientId = null,
    appleClientSecret = null,
    trustProxyHeaders = false,
)

private fun validExamRequest(expectedVersion: Long?) = UpsertExamRequest(
    specialtyId = 1,
    code = "exam-1",
    title = "Examen",
    type = ExamType.IMMERSIVE,
    accessType = ExamAccessType.PREMIUM,
    priceTickets = 0,
    status = PublicationStatus.DRAFT,
    expectedVersion = expectedVersion,
)

private fun validQuestion() = UpsertQuestionRequest(
    title = "Pregunta",
    statement = "Enunciado",
    sortOrder = 0,
    options = listOf(
        QuestionOptionInput(label = "A", content = "Correcta", correct = true, sortOrder = 0),
        QuestionOptionInput(label = "B", content = "Incorrecta", correct = false, sortOrder = 1),
    ),
)
