package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BackendErrorsTest {
    @Test
    fun mapsKnownSupabaseDomainErrorsWithoutLeakingDbDetails() {
        val result = mapSupabaseDomainError(400, "{\"message\":\"WALLET_IDEMPOTENCY_CONFLICT: payload mismatch\"}")

        assertEquals("WALLET_IDEMPOTENCY_CONFLICT", result?.errorCode)
        assertEquals(HttpStatusCode.Conflict, result?.status)
        assertEquals("La clave de idempotencia ya fue utilizada con otros datos.", result?.message)
    }

    @Test
    fun mapsAnswerIntegrityErrorsToUnprocessableEntity() {
        val result = mapSupabaseDomainError(400, "RECORD_ANSWER_OPTION_NOT_IN_QUESTION")

        assertEquals(HttpStatusCode.UnprocessableEntity, result?.status)
        assertEquals("record_answer_option_not_in_question", result?.errorCode?.lowercase())
    }

    @Test
    fun leavesUnknownSupabaseErrorsForInternalHandling() {
        assertNull(mapSupabaseDomainError(500, "unexpected database failure"))
    }
}
