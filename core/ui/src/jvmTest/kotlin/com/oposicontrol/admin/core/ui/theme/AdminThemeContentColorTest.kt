package com.oposicontrol.admin.core.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Un `Text` que no pide color hereda el `textPrimary` del tema. En los dos temas.
 *
 * **Por qué hace falta componer.** Es una propiedad de la COMPOSICIÓN, no de un
 * token: Material3 no fija `LocalContentColor` en `MaterialTheme` (sólo `Surface`
 * lo hace, y el proyecto no usa `Surface`), así que su valor por defecto es
 * `Color.Black`. Así fue como los títulos de todos los `FormSection` salían en
 * `#000000` sobre la superficie sólida del tema oscuro —1,72:1— sin que ningún test
 * de pares de tokens lo viera: los tokens estaban bien, el que no pintaba con
 * ellos era el `Text`.
 *
 * Se compara con el token de la paleta VIVA de cada tema, no con un literal.
 */
@OptIn(ExperimentalTestApi::class)
class AdminThemeContentColorTest {

    @Test
    fun `en el tema oscuro un texto sin color hereda textPrimary`() {
        val heredado = colorDeContenidoDentroDe(dark = true)
        assertNotEquals(Color.Black, heredado, "El tema oscuro deja el color de contenido en negro puro")
        assertEquals(
            DarkGlassTokens.textPrimary,
            heredado,
            "AdminTheme(darkTheme = true) no provee textPrimary como color de contenido",
        )
    }

    @Test
    fun `en el tema claro un texto sin color hereda textPrimary`() {
        val heredado = colorDeContenidoDentroDe(dark = false)
        assertNotEquals(Color.Black, heredado, "El tema claro deja el color de contenido en negro puro")
        assertEquals(
            LightGlassTokens.textPrimary,
            heredado,
            "AdminTheme(darkTheme = false) no provee textPrimary como color de contenido",
        )
    }

    /**
     * El color de contenido que ve un composable dentro de [AdminTheme], junto con
     * la paleta que ese mismo tema ha entregado: si el tema ignorase el parámetro,
     * el test compararía contra la paleta equivocada y lo diría.
     */
    private fun colorDeContenidoDentroDe(dark: Boolean): Color {
        var contenido: Color? = null
        var paleta: GlassTokens? = null
        runDesktopComposeUiTest(width = 320, height = 240) {
            setContent {
                AdminTheme(darkTheme = dark) {
                    val color = LocalContentColor.current
                    val tokens = GlassTheme.tokens
                    SideEffect {
                        contenido = color
                        paleta = tokens
                    }
                }
            }
            waitForIdle()
        }
        assertEquals(dark, checkNotNull(paleta) { "AdminTheme no llegó a componer su contenido" }.isDark)
        return checkNotNull(contenido)
    }
}
