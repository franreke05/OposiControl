package com.oposicontrol.admin.core.ui.components

import com.oposicontrol.admin.core.common.result.AppError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ErrorPresentationTest {

    @Test
    fun `ningun mensaje visible expone jerga tecnica`() {
        val errors = listOf(
            AppError.Network("Connection reset by peer", code = 500),
            AppError.Authentication("JWT expired at 2026-09-03T10:00:00Z"),
            AppError.Authorization("RLS policy denied for role admin"),
            AppError.NotFound("PGRST116: no rows returned"),
            AppError.Unknown("kotlin.IllegalStateException: null"),
        )
        val jerga = listOf(
            "exception", "null", "jwt", "rls", "pgrst", "stack",
            "http", "401", "403", "404", "500", "peer",
        )

        errors.forEach { error ->
            val visible = error.humanMessage().lowercase()
            jerga.forEach { termino ->
                assertFalse(
                    visible.contains(termino),
                    "El mensaje de $error enseña '$termino': «$visible»",
                )
            }
        }
    }

    @Test
    fun `un error de validacion conserva su texto porque ya esta escrito para el usuario`() {
        val message = "El precio no puede ser negativo"
        assertEquals(message, AppError.Validation(message).humanMessage())
    }

    @Test
    fun `solo se ofrece reintentar cuando reintentar puede cambiar algo`() {
        assertTrue(AppError.Network("x").isRetryable())
        assertTrue(AppError.Unknown("x").isRetryable())

        // Volver a pulsar aquí daría exactamente el mismo resultado, así que el
        // botón sería una mentira.
        assertFalse(AppError.Authorization("x").isRetryable())
        assertFalse(AppError.Validation("x").isRetryable())
        assertFalse(AppError.NotFound("x").isRetryable())
        assertFalse(AppError.Authentication("x").isRetryable())
    }

    @Test
    fun `un timeout se distingue de una caida del servidor`() {
        assertNotEqualsIgnoringCase(
            AppError.Network("t", code = 504).humanMessage(),
            AppError.Network("s", code = 503).humanMessage(),
        )
        assertTrue(AppError.Network("t", code = 408).humanMessage().contains("tardado"))
    }

    @Test
    fun `sin codigo, el mensaje apunta a la conexion del propio panel`() {
        assertTrue(AppError.Network("offline").humanMessage().contains("conexión"))
    }

    private fun assertNotEqualsIgnoringCase(a: String, b: String) {
        assertTrue(!a.equals(b, ignoreCase = true), "Se esperaban mensajes distintos, ambos eran «$a»")
    }
}
