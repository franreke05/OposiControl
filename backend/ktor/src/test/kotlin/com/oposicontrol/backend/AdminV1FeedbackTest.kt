package com.oposicontrol.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdminV1FeedbackTest {
    @Test
    fun feedbackCursor_roundTripsCreadoEnAndId() {
        val creadoEn = "2026-08-23T10:00:00Z"
        val id = "123e4567-e89b-12d3-a456-426614174000"

        val cursor = encodeFeedbackCursor(creadoEn, id)
        val (decodedCreadoEn, decodedId) = decodeFeedbackCursor(cursor)

        assertEquals(creadoEn, decodedCreadoEn)
        assertEquals(id, decodedId)
    }

    @Test
    fun decodeFeedbackCursor_rejectsGarbageInsteadOfGuessingAPage() {
        assertFailsWith<IllegalArgumentException> { decodeFeedbackCursor("not-valid-base64!!") }
    }

    @Test
    fun decodeFeedbackCursor_rejectsAPayloadWithNoSeparator() {
        val noSeparator = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("2026-08-23T10:00:00Z".toByteArray())
        assertFailsWith<IllegalArgumentException> { decodeFeedbackCursor(noSeparator) }
    }

    @Test
    fun decodeFeedbackCursor_rejectsANonIsoCreadoEn() {
        val bogusDate = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("not-a-date|123e4567-e89b-12d3-a456-426614174000".toByteArray())
        assertFailsWith<IllegalArgumentException> { decodeFeedbackCursor(bogusDate) }
    }

    @Test
    fun decodeFeedbackCursor_rejectsANonUuidId() {
        val bogusId = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("2026-08-23T10:00:00Z|not-a-uuid".toByteArray())
        assertFailsWith<IllegalArgumentException> { decodeFeedbackCursor(bogusId) }
    }
}
