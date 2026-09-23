package com.oposicontrol.admin.features.specialties.presentation

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.glassBackdrops
import com.oposicontrol.admin.core.ui.theme.AdminTheme
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.GlassTokens
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contraste de los colores que ELIGE la pantalla de Especialidades, con la
 * paleta VIVA de los dos temas.
 *
 * **Mide la elección de la pantalla, no un par de tokens.** `ContrastTest`
 * compara pares de tokens y no puede saber de qué color pinta un componente su
 * texto. Aquí el composable y el test llaman a las MISMAS funciones puras
 * (`specialty*Colors`), así que devolver un color que no llega pone esto en
 * rojo. Es la razón por la que ningún `when` de color vive dentro de un
 * `@Composable` en esta vertical.
 *
 * **Sobre qué se mide.** Lo opaco, contra su propia superficie. Lo que va sobre
 * el cristal de una sección —el nombre de la columna, el nombre de la
 * especialidad abierta, el recuento del catálogo, la barra de guardado en
 * reposo— contra los DOS extremos reales del cristal (`glassBackdrops`): la
 * parada más profunda del fondo y el pico del halo con el brillo del contenedor
 * encima.
 *
 * **Por qué los tokens se leen componiendo `AdminTheme`.** `LightGlassTokens` y
 * `DarkGlassTokens` son `internal` de `core/ui` y este módulo no los ve. En vez
 * de abrirlos, se leen por el mismo camino que producción.
 */
@OptIn(ExperimentalTestApi::class)
class SpecialtiesContrastTest {

    @Test
    fun `el texto sobre el cristal de una seccion se lee en los dos temas`() {
        temas.forEach { (nombre, t) ->
            val palette = specialtyGlassText(t)
            glassBackdrops(t).glass.forEach { (fondo, color) ->
                assertContrast(palette.primary, color, 4.5, "[$nombre] Texto primario sobre $fondo")
                assertContrast(palette.secondary, color, 4.5, "[$nombre] Texto secundario sobre $fondo")
            }
        }
    }

    @Test
    fun `una pildora sobre cristal se lee compuesta sobre el cristal`() {
        temas.forEach { (nombre, t) ->
            val fill = specialtyGlassPillFill(t)
            val palette = specialtyGlassText(t)
            glassBackdrops(t).glass.forEach { (fondo, color) ->
                val compuesto = fill.over(color)
                assertContrast(palette.primary, compuesto, 4.5, "[$nombre] Texto de una píldora sobre $fondo")
            }
        }
    }

    /**
     * La barra de guardado, SÓLO en las combinaciones que la pantalla produce.
     *
     * Las parejas (tono, forma) se sacan de [specialtySaveBarStatus] sobre los
     * estados que esta pantalla puede tener, no del producto cartesiano de los
     * dos enums. Medir el cartesiano parece más riguroso y es lo contrario:
     * `SUCCESS` sólo existe en la forma de reposo —«Sin cambios pendientes»— y
     * el verde sobre el relleno ámbar de la forma prominente se queda en 2,64:1
     * en el tema claro, así que el test fallaba por una barra que nadie puede
     * llegar a ver.
     */
    @Test
    fun `la barra de guardado se lee en las formas que la pantalla produce`() {
        val formas = formasDeLaBarra()
        temas.forEach { (nombre, t) ->
            val prominentFill = specialtySaveBarProminentFill(t)
            formas.forEach { (tone, prominent) ->
                glassBackdrops(t).glass.forEach { (fondo, cristal) ->
                    // Prominente: relleno ámbar al 10% sobre el cristal del editor.
                    // En reposo la barra es transparente, así que el fondo ES el
                    // cristal.
                    val background = if (prominent) prominentFill.over(cristal) else cristal
                    val forma = if (prominent) "prominente" else "en reposo"
                    assertContrast(
                        foreground = specialtySaveBarTextColor(tone, prominent, t),
                        background = background,
                        minimum = 4.5,
                        description = "[$nombre] Texto de la barra $forma ('$tone') sobre $fondo",
                    )
                    assertContrast(
                        foreground = specialtySaveBarToneColor(tone, t),
                        background = background,
                        minimum = 3.0,
                        description = "[$nombre] Marca de la barra $forma ('$tone') sobre $fondo",
                    )
                }
            }
        }
    }

    /**
     * Las parejas (tono, prominente) que [specialtySaveBarStatus] devuelve de
     * verdad, recorriendo los estados que esta pantalla puede tener.
     *
     * Con la comprobación de que salen las cuatro clases: sin esto, un cambio que
     * dejara la lista corta pasaría el test midiendo menos de lo que cree.
     */
    private fun formasDeLaBarra(): Set<Pair<SpecialtySaveBarTone, Boolean>> {
        val abierta = SpecialtiesFixtures.especialidadAbierta()
        val estados = listOf(
            SpecialtiesState(),
            abierta,
            SpecialtiesFixtures.cambiosSinGuardar(),
            SpecialtiesFixtures.sinTiposAbiertos(),
            SpecialtiesFixtures.nuevaEspecialidad().copy(editorName = "Nueva"),
            abierta.copy(editorName = "Otro nombre", actionState = UiAsync.Loading),
        )
        val formas = estados
            .map { specialtySaveBarStatus(it) }
            .mapNotNull { status -> status.message?.let { it.tone to status.prominent } }
            .toSet()
        assertTrue(
            formas.containsAll(
                listOf(
                    SpecialtySaveBarTone.SUCCESS to false,
                    SpecialtySaveBarTone.INFO to false,
                    SpecialtySaveBarTone.WARNING to true,
                    SpecialtySaveBarTone.DANGER to true,
                ),
            ),
            "Los estados de prueba ya no producen las formas esperadas de la barra: $formas",
        )
        return formas
    }

    /**
     * Una fila del catálogo, en sus cuatro estados.
     *
     * El HAIRLINE de 1dp no se mide aquí: sin seleccionar es `tokens.border`
     * sobre `surfaceSolid`/`surfaceHover`, un par de tokens del sistema que esta
     * pantalla no elige y que protege el `ContrastTest` de `core/ui`. Lo que sí
     * se mide es lo que esta pantalla decide: los dos textos, la barra coral de
     * 3dp que porta la selección y que el relleno de la fila abierta sea de
     * verdad distinto del de una cerrada.
     */
    @Test
    fun `una fila del catalogo se lee seleccionada y sin seleccionar`() {
        temas.forEach { (nombre, t) ->
            listOf(false, true).forEach { selected ->
                listOf(false, true).forEach { hovered ->
                    val c = specialtyCatalogRowColors(selected, hovered, t)
                    val estado = "seleccionada=$selected, sobrevolada=$hovered"
                    assertContrast(c.title, c.background, 4.5, "[$nombre] Nombre de una fila ($estado)")
                    assertContrast(c.description, c.background, 4.5, "[$nombre] Descripción de una fila ($estado)")
                }
            }
            val abierta = specialtyCatalogRowColors(selected = true, hovered = false, tokens = t)
            val cerrada = specialtyCatalogRowColors(selected = false, hovered = false, tokens = t)
            assertContrast(
                foreground = specialtyCatalogRowBar(selected = true, tokens = t),
                background = abierta.background,
                minimum = 3.0,
                description = "[$nombre] Barra coral de la fila abierta",
            )
            assertContrast(abierta.mark, abierta.background, 3.0, "[$nombre] Marca de la fila abierta")
            assertTrue(
                abierta.background != cerrada.background,
                "[$nombre] La fila abierta tiene que pintarse distinta de una cerrada",
            )
            assertTrue(
                abierta.border != cerrada.border,
                "[$nombre] El borde de la fila abierta tiene que pintarse distinto de una cerrada",
            )
        }
    }

    @Test
    fun `una tarjeta de tipo se lee abierta cerrada y cerrada con examenes`() {
        temas.forEach { (nombre, t) ->
            listOf(true to false, false to false, false to true).forEach { (enabled, hasExams) ->
                val c = specialtyTypeCardColors(enabled, hasExams, t)
                val estado = "abierto=$enabled, con exámenes=$hasExams"
                assertContrast(c.title, c.background, 4.5, "[$nombre] Título de una tarjeta de tipo ($estado)")
                assertContrast(c.description, c.background, 4.5, "[$nombre] Descripción de una tarjeta ($estado)")
                assertContrast(c.mark, c.background, 3.0, "[$nombre] Marca de una tarjeta de tipo ($estado)")
            }
        }
    }

    @Test
    fun `una comprobacion pendiente se lee en los dos temas`() {
        temas.forEach { (nombre, t) ->
            SpecialtyCheckStatus.entries.forEach { status ->
                // El detalle es `bodySmall`, 13sp: texto normal, 4,5:1. El panel es
                // `SpecialtySolidPanel`, que pinta `surfaceSolid`.
                assertContrast(
                    foreground = specialtyCheckDetailColor(status, t),
                    background = t.surfaceSolid,
                    minimum = 4.5,
                    description = "[$nombre] Detalle de una comprobación '$status'",
                )
                // El punto de 7dp porta el estado en color: elemento de interfaz.
                assertContrast(
                    foreground = specialtyCheckDotColor(status, t),
                    background = t.surfaceSolid,
                    minimum = 3.0,
                    description = "[$nombre] Punto de una comprobación '$status'",
                )
            }
        }
    }

    @Test
    fun `las pildoras de declaracion y de filtro se leen`() {
        temas.forEach { (nombre, t) ->
            SpecialtyDeclaration.entries.forEach { declaration ->
                val p = specialtyDeclarationPillColors(declaration, t)
                assertContrast(p.content, p.background, 4.5, "[$nombre] Texto de la píldora '$declaration'")
                assertContrast(p.icon, p.background, 3.0, "[$nombre] Icono de la píldora '$declaration'")
            }
            listOf(false, true).forEach { active ->
                val p = specialtyStatusFilterColors(active, t)
                assertContrast(p.content, p.background, 4.5, "[$nombre] Texto del filtro (activo=$active)")
                assertContrast(p.icon, p.background, 3.0, "[$nombre] Marca del filtro (activo=$active)")
            }
        }
    }

    @Test
    fun `un aviso del formulario se lee en los tres tonos`() {
        temas.forEach { (nombre, t) ->
            SpecialtyNoticeTone.entries.forEach { tone ->
                val c = specialtyNoticeColors(tone, t)
                assertContrast(c.content, c.background, 4.5, "[$nombre] Título de un aviso '$tone'")
                assertContrast(c.icon, c.background, 3.0, "[$nombre] Icono de un aviso '$tone'")
            }
        }
    }

    @Test
    fun `un valor de la vista rapida se lee sea cual sea su procedencia`() {
        temas.forEach { (nombre, t) ->
            SpecialtyFactOrigin.entries.forEach { origin ->
                val style = specialtyFactStyle(origin, t)
                assertContrast(
                    foreground = style.color,
                    background = t.surfaceSolid,
                    minimum = 4.5,
                    description = "[$nombre] Valor de procedencia '$origin' sobre superficie sólida",
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // Fórmula
    // -----------------------------------------------------------------------

    private fun assertContrast(foreground: Color, background: Color, minimum: Double, description: String) {
        val ratio = contrast(foreground.over(background), background)
        assertTrue(ratio >= minimum, "$description: ${round2(ratio)}:1, se requiere $minimum:1")
    }

    private fun Color.relativeLuminance(): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)
    }

    private fun contrast(foreground: Color, background: Color): Double {
        val a = foreground.relativeLuminance()
        val b = background.relativeLuminance()
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    /** Compone [this] sobre [background] con su propio alpha. Lo que hace el renderer. */
    private fun Color.over(background: Color): Color = Color(
        red = red * alpha + background.red * (1f - alpha),
        green = green * alpha + background.green * (1f - alpha),
        blue = blue * alpha + background.blue * (1f - alpha),
    )

    private fun round2(value: Double): Double = (value * 100).toInt() / 100.0

    private companion object {
        /**
         * Las dos paletas vivas, leídas una sola vez por clase.
         *
         * Se comprueba que de verdad son dos: si `AdminTheme` ignorara el
         * parámetro, se mediría dos veces la misma paleta y la mitad de las
         * comprobaciones pasarían sin haberse hecho.
         */
        val temas: List<Pair<String, GlassTokens>> by lazy {
            val claro = tokensDelTema(dark = false)
            val oscuro = tokensDelTema(dark = true)
            assertFalse(claro.isDark, "AdminTheme(darkTheme = false) ha entregado la paleta oscura")
            assertTrue(oscuro.isDark, "AdminTheme(darkTheme = true) ha entregado la paleta clara")
            listOf("tema claro" to claro, "tema oscuro" to oscuro)
        }

        fun tokensDelTema(dark: Boolean): GlassTokens {
            var leidos: GlassTokens? = null
            runDesktopComposeUiTest(width = 320, height = 240) {
                setContent {
                    AdminTheme(darkTheme = dark) {
                        val tokens = GlassTheme.tokens
                        SideEffect { leidos = tokens }
                    }
                }
                waitForIdle()
            }
            return checkNotNull(leidos) { "AdminTheme no llegó a componer su contenido" }
        }
    }
}
