package com.oposicontrol.admin.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Los menús de Material salen de la paleta, en los dos temas.
 *
 * Los selectores del producto —Especialidad y Comunidad en Información básica,
 * los de filtro del catálogo— abren un `DropdownMenu` de Material, y ése no lee
 * los tokens: toma su fondo de `colorScheme.surfaceContainer` y su texto de
 * `colorScheme.onSurface`. Si el esquema no mapea `surfaceContainer`, Material
 * pone el suyo por defecto —un gris lila en claro y en oscuro— y el menú sale de
 * otra aplicación.
 *
 * **Se mide lo que usa el menú, no un par de tokens**: `MenuDefaults.containerColor`
 * y `MenuDefaults.itemColors().textColor`, leídos DENTRO de `AdminTheme`. Así, si
 * alguien cambia el esquema o Material cambia de rol, esto lo ve.
 */
@OptIn(ExperimentalTestApi::class)
class MaterialMenuColorsTest {

    /**
     * El fondo de un menú es una superficie OPACA del tema, y se separa del panel
     * sobre el que se abre.
     *
     * En claro es la superficie sólida: el menú es una hoja blanca con sombra,
     * como cualquier popup del tema claro, y la sombra lo separa. En oscuro la
     * sombra de un popup no se ve sobre navy, así que el menú va un escalón por
     * encima del panel (`surfaceHover`): con el mismo `surfaceSolid` que la
     * sección, sus opciones se leían como texto suelto sobre el formulario.
     */
    @Test
    fun `el fondo de un menu de Material es una superficie del tema que se separa del panel`() {
        val claro = leerDentroDeAdminTheme(dark = false)
        assertEquals(
            hex(claro.tokens.surfaceSolid),
            hex(claro.contenedor),
            "[tema claro] El fondo de un DropdownMenu no es la superficie sólida del tema",
        )
        val oscuro = leerDentroDeAdminTheme(dark = true)
        assertEquals(
            hex(oscuro.tokens.surfaceHover),
            hex(oscuro.contenedor),
            "[tema oscuro] El fondo de un DropdownMenu no es la superficie elevada del tema",
        )
        val separacion = contrast(oscuro.contenedor, oscuro.tokens.surfaceSolid)
        assertTrue(
            separacion >= 1.1 && oscuro.contenedor.luminancia() > oscuro.tokens.surfaceSolid.luminancia(),
            "[tema oscuro] El menú no queda por encima del panel sobre el que se abre " +
                "(${(separacion * 100).toInt() / 100.0}:1)",
        )
    }

    @Test
    fun `el texto de un elemento de menu se lee sobre su fondo`() {
        listOf(false, true).forEach { dark ->
            val leido = leerDentroDeAdminTheme(dark)
            val ratio = contrast(leido.texto, leido.contenedor)
            assertTrue(
                ratio >= 4.5,
                "[${tema(dark)}] Texto de un elemento de menú ${hex(leido.texto)} sobre ${hex(leido.contenedor)}: " +
                    "${(ratio * 100).toInt() / 100.0}:1, se requiere 4.5:1",
            )
        }
    }

    /**
     * Los cinco contenedores de Material son superficies opacas de la paleta.
     *
     * Menús, diálogos, hojas y tarjetas rellenas de Material eligen entre estos
     * cinco. Ninguno puede ser el gris lila de Material ni un tinte translúcido: un
     * contenedor translúcido deja el texto flotando sobre lo que haya detrás.
     */
    @Test
    fun `los contenedores de Material son superficies opacas del tema`() {
        listOf(false, true).forEach { dark ->
            val leido = leerDentroDeAdminTheme(dark)
            val t = leido.tokens
            val superficies = setOf(hex(t.surfaceSolid), hex(t.surfaceMuted), hex(t.surfaceHover))
            val esquema = leido.esquema
            listOf(
                "surfaceContainerLowest" to esquema.surfaceContainerLowest,
                "surfaceContainerLow" to esquema.surfaceContainerLow,
                "surfaceContainer" to esquema.surfaceContainer,
                "surfaceContainerHigh" to esquema.surfaceContainerHigh,
                "surfaceContainerHighest" to esquema.surfaceContainerHighest,
            ).forEach { (nombre, color) ->
                assertEquals(1f, color.alpha, "[${tema(dark)}] $nombre es translúcido")
                assertTrue(
                    hex(color) in superficies,
                    "[${tema(dark)}] $nombre = ${hex(color)} no es una superficie del tema ($superficies)",
                )
            }
        }
    }

    // -----------------------------------------------------------------------

    private data class Leido(val tokens: GlassTokens, val contenedor: Color, val texto: Color, val esquema: ColorScheme)

    private fun leerDentroDeAdminTheme(dark: Boolean): Leido {
        var leido: Leido? = null
        runDesktopComposeUiTest(width = 320, height = 240) {
            setContent {
                AdminTheme(darkTheme = dark) {
                    val tokens = GlassTheme.tokens
                    val contenedor = MenuDefaults.containerColor
                    val texto = MenuDefaults.itemColors().textColor
                    val esquema = MaterialTheme.colorScheme
                    SideEffect { leido = Leido(tokens, contenedor, texto, esquema) }
                }
            }
            waitForIdle()
        }
        val resultado = checkNotNull(leido) { "AdminTheme no llegó a componer su contenido" }
        assertEquals(dark, resultado.tokens.isDark, "AdminTheme ha entregado la paleta del otro tema")
        return resultado
    }

    private fun tema(dark: Boolean) = if (dark) "tema oscuro" else "tema claro"

    private fun hex(c: Color): String = "#%02X%02X%02X%02X".format(
        (c.alpha * 255).roundToInt(), (c.red * 255).roundToInt(), (c.green * 255).roundToInt(), (c.blue * 255).roundToInt(),
    )

    private fun Color.luminancia(): Double {
        fun canal(v: Float): Double = v.toDouble().let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * canal(red) + 0.7152 * canal(green) + 0.0722 * canal(blue)
    }

    private fun contrast(a: Color, b: Color): Double =
        (maxOf(a.luminancia(), b.luminancia()) + 0.05) / (minOf(a.luminancia(), b.luminancia()) + 0.05)
}
