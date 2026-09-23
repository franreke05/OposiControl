package com.oposicontrol.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdminV1ForoTest {
    @Test
    fun foroCursor_roundTripsCreadoEnAndId() {
        val creadoEn = "2026-08-22T10:00:00Z"
        val id = "123e4567-e89b-12d3-a456-426614174000"

        val cursor = encodeForoCursor(creadoEn, id)
        val (decodedCreadoEn, decodedId) = decodeForoCursor(cursor)

        assertEquals(creadoEn, decodedCreadoEn)
        assertEquals(id, decodedId)
    }

    @Test
    fun decodeForoCursor_rejectsGarbageInsteadOfGuessingAPage() {
        assertFailsWith<IllegalArgumentException> { decodeForoCursor("not-valid-base64!!") }
    }

    @Test
    fun decodeForoCursor_rejectsAPayloadWithNoSeparator() {
        val noSeparator = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("2026-08-22T10:00:00Z".toByteArray())
        assertFailsWith<IllegalArgumentException> { decodeForoCursor(noSeparator) }
    }

    @Test
    fun decodeForoCursor_rejectsANonIsoCreadoEn() {
        val bogusDate = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("not-a-date|123e4567-e89b-12d3-a456-426614174000".toByteArray())
        assertFailsWith<IllegalArgumentException> { decodeForoCursor(bogusDate) }
    }

    @Test
    fun decodeForoCursor_rejectsANonUuidId() {
        val bogusId = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("2026-08-22T10:00:00Z|not-a-uuid".toByteArray())
        assertFailsWith<IllegalArgumentException> { decodeForoCursor(bogusId) }
    }

    @Test
    fun toForoSnippet_leavesShortContentUntouched() {
        assertEquals("Hola mundo", "Hola mundo".toForoSnippet())
    }

    @Test
    fun toForoSnippet_truncatesLongContentWithEllipsis() {
        val long = "a".repeat(300)
        val snippet = long.toForoSnippet(maxLength = 240)
        assertEquals(241, snippet.length) // 240 chars + the ellipsis character
        assertEquals("…", snippet.last().toString())
    }

    @Test
    fun toForoSnippet_trimsSurroundingWhitespaceFirst() {
        assertEquals("Hola", "   Hola   ".toForoSnippet())
    }
}
