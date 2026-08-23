package com.oposicontrol.backend

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BackendSecurityTest {
    @Test
    fun developmentBypassesAreNeverAllowedInProduction() {
        assertEquals(true, allowsDevBypass("dev", enabled = true))
        assertEquals(true, allowsDevBypass("test", enabled = true))
        assertEquals(false, allowsDevBypass("prod", enabled = true))
        assertEquals(false, allowsDevBypass("production", enabled = true))
        assertEquals(false, allowsDevBypass("", enabled = true))
        assertEquals(false, allowsDevBypass("dev", enabled = false))
    }

    @Test
    fun staticTokenIsLimitedToLegacyDevelopmentSurfaces() {
        assertEquals(true, isLegacyStaticTokenPath("/content/noticias"))
        assertEquals(true, isLegacyStaticTokenPath("/admin/backend/restart"))
        assertEquals(false, isLegacyStaticTokenPath("/api/v1/admin/examenes"))
        assertEquals(false, isLegacyStaticTokenPath("/api/v1/app/home"))
    }

    @Test
    fun postgrestIdentifiersMustBeCanonicalUuids() {
        assertEquals(
            "123e4567-e89b-12d3-a456-426614174000",
            "123e4567-e89b-12d3-a456-426614174000".validatedUuid("id"),
        )
        assertFailsWith<IllegalArgumentException> { "not-a-uuid".validatedUuid("id") }
    }

    @Test
    fun newsKeepsBackgroundImageAndScheduleIndependent() {
        val input = NoticiaDto(
            id = "7",
            titulo = "Noticia",
            especialidad = "2",
            tipo = "3",
            fondoPath = "background.png",
            publicarDesde = "2026-07-01T08:00:00Z",
            texto = "Texto",
            imagenPath = "cover.png",
        )

        val row = input.toSupabaseRow(Json, String::toInt, String::toInt)
        assertEquals("background.png", row.backgroundPath)
        assertEquals("cover.png", row.imagenPath)
        assertEquals("2026-07-01T08:00:00Z", row.publicarDesde)

        val restored = row.toDto(Json)
        assertEquals(input.fondoPath, restored.fondoPath)
        assertEquals(input.imagenPath, restored.imagenPath)
        assertEquals(input.publicarDesde, restored.publicarDesde)
    }
}
