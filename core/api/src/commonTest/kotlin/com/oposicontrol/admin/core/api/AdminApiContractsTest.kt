package com.oposicontrol.admin.core.api

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminApiContractsTest {
    private val json = AdminApiJson.format

    @Test
    fun errorEnvelopeEncodesStableDefaults() {
        val encoded = json.encodeToString(
            ApiErrorResponse(
                code = "forbidden",
                message = "Permission required",
                requestId = "request-1",
            ),
        )

        assertTrue(encoded.contains("\"fieldErrors\":[]"))
        assertTrue(encoded.contains("\"retryable\":false"))
        assertEquals(
            "forbidden",
            json.decodeFromString<ApiErrorResponse>(encoded).code,
        )
    }

    @Test
    fun decoderIgnoresFutureFields() {
        val decoded = json.decodeFromString<HealthResponse>(
            """{"status":"ok","service":"backend","version":"1","future":"value"}""",
        )

        assertEquals("backend", decoded.service)
    }

    @Test
    fun contentContractUsesCanonicalWireValues() {
        val response = ExamQuestionResponse(
            id = 10,
            examId = 3,
            title = "Pregunta 1",
            statement = "Enunciado",
            sortOrder = 0,
            options = listOf(
                QuestionOptionResponse(
                    id = 20,
                    label = "A",
                    content = "Respuesta",
                    correct = true,
                    sortOrder = 0,
                ),
            ),
            resources = listOf(
                QuestionResourceResponse(
                    id = 30,
                    type = QuestionResourceType.TEXT,
                    title = "Apoyo",
                    textBody = "Contenido",
                    sortOrder = 0,
                    version = 1,
                ),
            ),
            version = 4,
            createdAt = "2026-07-29T10:00:00Z",
            updatedAt = "2026-07-29T10:00:00Z",
        )

        val encoded = json.encodeToString(response)

        assertTrue(encoded.contains("\"type\":\"text\""))
        assertEquals(response, json.decodeFromString<ExamQuestionResponse>(encoded))
    }

    @Test
    fun operationalCommandsDoNotCarryActorIdentity() {
        val supportJson = json.encodeToString(
            SendSupportMessageRequest(
                body = "Respuesta",
                internalNote = true,
            ),
        )
        val moderationJson = json.encodeToString(
            CreateModerationActionRequest(
                targetUserId = 7,
                type = ModerationActionType.WARNING,
                reason = "Motivo",
                metadata = JsonObject(mapOf("source" to JsonPrimitive("admin"))),
            ),
        )

        assertFalse(supportJson.contains("actor", ignoreCase = true))
        assertFalse(supportJson.contains("sender", ignoreCase = true))
        assertFalse(moderationJson.contains("actor", ignoreCase = true))
        assertFalse(moderationJson.contains("executor", ignoreCase = true))
    }

    @Test
    fun routesAndHeadersRemainVersioned() {
        assertEquals("/api/admin/v1/exams/9/questions", AdminApiRoutes.examQuestions(9))
        assertEquals(
            "/api/admin/v1/wallets/12/adjustments",
            AdminApiRoutes.walletAdjustment(12),
        )
        assertEquals("Idempotency-Key", AdminApiHeaders.IDEMPOTENCY_KEY)
    }

    @Test
    fun ticketAmountsAreIntegralAndRoundTripAsLong() {
        val request = TicketAdjustmentRequest(
            delta = 3_000_000_000,
            reason = "Correccion administrativa",
        )

        val decoded = json.decodeFromString<TicketAdjustmentRequest>(json.encodeToString(request))

        assertEquals(3_000_000_000L, decoded.delta)
    }
}
