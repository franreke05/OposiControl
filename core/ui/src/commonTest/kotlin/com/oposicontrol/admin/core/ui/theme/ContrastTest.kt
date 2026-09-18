package com.oposicontrol.admin.core.ui.theme

import androidx.compose.ui.graphics.Color
import com.oposicontrol.admin.core.ui.components.ButtonVariant
import com.oposicontrol.admin.core.ui.components.DesktopTabsVariant
import com.oposicontrol.admin.core.ui.components.GlassBackdrops
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.StatusTone
import com.oposicontrol.admin.core.ui.components.semanticIconContainerColors
import com.oposicontrol.admin.core.ui.components.buttonPalette
import com.oposicontrol.admin.core.ui.components.fieldBorderColor
import com.oposicontrol.admin.core.ui.components.fieldSupportColor
import com.oposicontrol.admin.core.ui.components.formSectionDescriptionColor
import com.oposicontrol.admin.core.ui.components.formSectionTitleColor
import com.oposicontrol.admin.core.ui.components.glassBackdrops
import com.oposicontrol.admin.core.ui.components.pageHeaderColors
import com.oposicontrol.admin.core.ui.components.readOnlyFieldBorderColor
import com.oposicontrol.admin.core.ui.components.sectionLinkChevronTint
import com.oposicontrol.admin.core.ui.components.statusChipColors
import com.oposicontrol.admin.core.ui.components.statusDotChipColors
import com.oposicontrol.admin.core.ui.components.tabBackground
import com.oposicontrol.admin.core.ui.components.tabBadgeColors
import com.oposicontrol.admin.core.ui.components.tabIconTint
import com.oposicontrol.admin.core.ui.components.tabLabelColor
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Contraste (§47), en LOS DOS TEMAS.
 *
 * El contraste es de las pocas cosas de una interfaz que se puede comprobar con
 * una fórmula en vez de con una opinión, así que se comprueba. La alternativa es
 * mirar una captura y decir "se ve bien", que es exactamente como acaban
 * colándose los grises ilegibles.
 *
 * Ratios de WCAG 2.1: 4,5:1 para texto normal, 3:1 para texto grande y para
 * elementos de interfaz (bordes, iconos que portan significado).
 *
 * **Por qué mide dos paletas y no una.** Un tema oscuro no es una capa de pintura
 * encima del claro: invierte de qué lado del gris está cada superficie, y un
 * color que funciona en un sentido puede fallar en el otro sin que nada avise. El
 * caso concreto que obligó a esto es el coral de relleno — en claro es un coral
 * PROFUNDO que sostiene texto blanco, y ese mismo valor sobre navy da 3,2:1 como
 * texto—. Cada afirmación de este fichero se evalúa contra las dos paletas vivas
 * salvo cuando la afirmación es, ella misma, sobre la dirección del tema; en esos
 * casos se dice qué tema y por qué.
 *
 * **Por qué mide dos fondos y no uno.** Un texto que va sobre cristal o
 * directamente sobre el fondo no tiene UN color detrás: tiene un rango, porque el
 * degradado y los halos cambian de un punto de la ventana a otro. Este fichero
 * componía el cristal sólo sobre la parada más profunda, que es el peor caso para
 * un texto oscuro. En el tema oscuro el texto es claro y el peor caso es el
 * contrario —el pico del halo con el brillo del contenedor encima—, y ahí el
 * render daba 2,94:1 en una pestaña inactiva, 3,42:1 en un chip de estado y
 * 4,04:1 en las migas de pan con todo esto en verde. Ahora cada texto sin
 * superficie propia se mide contra los DOS extremos (`glassBackdrops`), leídos de
 * las mismas funciones que usa el renderer.
 */
class ContrastTest {

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
        val lighter = maxOf(a, b)
        val darker = minOf(a, b)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /** Dos decimales: más precisión finge una exactitud que el ojo no distingue. */
    private fun round2(value: Double): Double = (value * 100).toInt() / 100.0

    private fun assertContrast(
        foreground: Color,
        background: Color,
        minimum: Double,
        description: String,
    ) {
        val ratio = contrast(foreground, background)
        assertTrue(
            ratio >= minimum,
            "$description: ${round2(ratio)}:1, se requiere $minimum:1",
        )
    }

    /**
     * Distancia entre dos colores sumando la diferencia de sus tres canales.
     *
     * Deliberadamente tosca: no es una métrica perceptual, es la misma cuenta que
     * se hace mirando dos muestras en un cuentagotas. Sirve para lo único que se
     * le pide — detectar que dos tintes que deberían distinguirse se han vuelto
     * el mismo color.
     */
    private fun canalesDeDiferencia(a: Color, b: Color): Int {
        fun canal(x: Float, y: Float) = ((x - y) * 255).toInt().let { if (it < 0) -it else it }
        return canal(a.red, b.red) + canal(a.green, b.green) + canal(a.blue, b.blue)
    }

    /** Compone [this] sobre [background] con su propio alpha. Lo que hace el renderer. */
    private fun Color.over(background: Color): Color = Color(
        red = red * alpha + background.red * (1f - alpha),
        green = green * alpha + background.green * (1f - alpha),
        blue = blue * alpha + background.blue * (1f - alpha),
    )

    /**
     * Una paleta viva con sus fondos y cristales COMPUESTOS ya resueltos.
     *
     * **Los tokens VIVOS, no una copia.** Antes este fichero replicaba los valores
     * literales del tema «para que no divergieran», con un comentario que decía
     * que si divergían el test dejaba de proteger nada. Divergir era exactamente
     * lo que podían hacer: una prueba de mutación cambió `warning` en
     * `AdminTheme.kt` a `#FFF9C4` —un amarillo casi blanco, ~1,2:1 sobre
     * superficie clara— y este fichero siguió en verde, porque seguía midiendo su
     * propia copia del color anterior. El guardián declarado de la accesibilidad
     * del producto entero no guardaba nada.
     *
     * Ahora lee `LightGlassTokens` y `DarkGlassTokens`, y los fondos compuestos
     * salen de `glassBackdrops`, que usa las funciones del renderer. Un color que
     * empeore en cualquiera de los dos temas rompe el build.
     */
    private inner class Paleta(val nombre: String, val t: GlassTokens) {
        /** Los extremos reales de fondo y cristal de esta paleta. */
        val fondos: GlassBackdrops = glassBackdrops(t)

        /**
         * El cristal de sección sobre la parada MÁS PROFUNDA, sin brillo.
         *
         * Es el peor caso para un texto oscuro y se conserva con nombre propio
         * porque los invariantes de SUPERFICIE —el campo se distingue del cristal,
         * el tema oscuro es más oscuro que el claro— se afirman sobre él: son
         * propiedades del nivel 1 frente al nivel 2, no de un texto.
         */
        val cristal: Color = fondos.glassOverDeepest

        /** Los dos extremos del cristal. Todo TEXTO que va sobre cristal se mide contra los dos. */
        val cristales: List<Pair<String, Color>> = fondos.glass

        /** Los dos extremos del fondo desnudo, para el texto que no tiene un panel debajo. */
        val paginas: List<Pair<String, Color>> = fondos.page
    }

    private val claro = Paleta("tema claro", LightGlassTokens)
    private val oscuro = Paleta("tema oscuro", DarkGlassTokens)
    private val ambos = listOf(claro, oscuro)

    // -----------------------------------------------------------------------
    // Texto
    // -----------------------------------------------------------------------

    @Test
    fun `el texto principal es legible sobre toda superficie de escritorio`() {
        ambos.forEach { p ->
            (
                listOf(
                    "superficie sólida" to p.t.surfaceSolid,
                    "superficie hundida" to p.t.surfaceMuted,
                    "fila seleccionada" to p.t.surfaceSelected,
                ) + p.paginas + p.cristales
                ).forEach { (name, background) ->
                    assertContrast(p.t.textPrimary, background, 4.5, "[${p.nombre}] Texto principal sobre $name")
                }
        }
    }

    @Test
    fun `el texto secundario cumple texto normal, no solo texto grande`() {
        // Se usa en descripciones y celdas de tabla a 13-14sp, que es texto
        // normal: el umbral aplicable es 4,5:1, no 3:1.
        //
        // Los dos extremos del fondo importan por `DesktopPageHeader`, que pinta
        // la descripción de la pantalla y las migas DIRECTAMENTE sobre el fondo,
        // sin panel debajo, y justo donde cae el halo claro. En el claro lo que
        // acota es la parada más profunda; en el oscuro, el pico del halo.
        ambos.forEach { p ->
            (
                listOf(
                    "superficie sólida" to p.t.surfaceSolid,
                    "superficie hundida" to p.t.surfaceMuted,
                ) + p.paginas + p.cristales
                ).forEach { (name, background) ->
                    assertContrast(p.t.textSecondary, background, 4.5, "[${p.nombre}] Texto secundario sobre $name")
                }
        }
    }

    /**
     * El secundario no puede alcanzar al principal.
     *
     * Aclarar `textSecondary` es el primer reflejo cuando el texto de apoyo no
     * llega sobre un fondo claro, y tiene un límite: si se acerca demasiado al
     * principal, la pantalla pierde su segundo nivel de lectura. 1,5:1 entre los
     * dos es el suelo; en claro hay 2,56 y en oscuro, tras el ajuste del halo, 1,5.
     */
    @Test
    fun `el texto secundario no alcanza al principal`() {
        ambos.forEach { p ->
            val ratio = contrast(p.t.textPrimary, p.t.textSecondary)
            assertTrue(
                ratio >= 1.5,
                "[${p.nombre}] textPrimary y textSecondary se han acercado a ${round2(ratio)}:1: " +
                    "el texto de apoyo ya no se distingue del principal",
            )
        }
    }

    /**
     * El PLACEHOLDER de un campo, que es donde el gris se cuela más fácil.
     *
     * `GlassTextField` lo pinta en `textSecondary` sobre el relleno resuelto del
     * campo, que en escritorio es `surfaceSolid`. Se mide aparte del texto
     * secundario general porque es el caso que la gente mira mientras escribe y
     * porque un placeholder ilegible no se nota revisando capturas: se nota
     * cuando alguien no sabe qué va en un campo vacío.
     */
    @Test
    fun `el texto de sugerencia de un campo vacio se lee`() {
        ambos.forEach { p ->
            assertContrast(p.t.textSecondary, p.t.surfaceSolid, 4.5, "[${p.nombre}] Placeholder de campo")
            assertContrast(p.t.textPrimary, p.t.surfaceSolid, 4.5, "[${p.nombre}] Valor escrito en un campo")
        }
    }

    /**
     * La cabecera de un bloque de formulario, con la función que usa `FormSection`.
     *
     * El título no pasaba `color` y heredaba el color de contenido por defecto:
     * negro, 1,72:1 sobre la superficie sólida del tema oscuro. La herencia la
     * protege `AdminThemeContentColorTest`; esto protege la elección explícita.
     */
    @Test
    fun `la cabecera de un bloque de formulario se lee sobre su superficie`() {
        ambos.forEach { p ->
            assertContrast(formSectionTitleColor(p.t), p.t.surfaceSolid, 4.5, "[${p.nombre}] Título de FormSection")
            assertContrast(formSectionDescriptionColor(p.t), p.t.surfaceSolid, 4.5, "[${p.nombre}] Descripción de FormSection")
        }
    }

    /**
     * El encabezado de página, con la función que usa `DesktopPageHeader`.
     *
     * Es el único texto de escritorio que va directamente sobre el fondo, y cae
     * sobre el halo claro. Medido en el render oscuro antes del ajuste: migas
     * 4,04:1 y descripción 4,29:1.
     */
    @Test
    fun `el encabezado de pagina se lee sobre el fondo en sus dos extremos`() {
        ambos.forEach { p ->
            val colores = pageHeaderColors(p.t)
            p.paginas.forEach { (nombre, fondo) ->
                assertContrast(colores.crumb, fondo, 4.5, "[${p.nombre}] Miga de pan sobre $nombre")
                assertContrast(colores.currentCrumb, fondo, 4.5, "[${p.nombre}] Miga actual sobre $nombre")
                assertContrast(colores.title, fondo, 4.5, "[${p.nombre}] Título de página sobre $nombre")
                assertContrast(colores.description, fondo, 4.5, "[${p.nombre}] Descripción de página sobre $nombre")
            }
        }
    }

    /**
     * El texto de un botón fantasma: sin relleno propio, se lee sobre lo que haya
     * detrás. En escritorio, «Limpiar» del catálogo va sobre cristal.
     */
    @Test
    fun `el texto de un boton fantasma se lee sobre lo que tenga detras`() {
        ambos.forEach { p ->
            val ghost = buttonPalette(ButtonVariant.GHOST, p.t)
            p.fondos.all.forEach { (nombre, fondo) ->
                assertContrast(ghost.content, ghost.container.over(fondo), 4.5, "[${p.nombre}] Botón fantasma sobre $nombre")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Rellenos de acción
    // -----------------------------------------------------------------------

    /**
     * El texto de un botón se lee, y lo elige el tema.
     *
     * Antes esto medía `Color.White` contra tres rellenos, porque los botones
     * escribían el blanco a mano. Eso sólo era correcto por accidente: en el tema
     * claro todos los rellenos de acción son oscuros. En el oscuro son claros
     * —el coral de relleno tiene 0,47 de luminancia— y el blanco encima da 2,0:1.
     *
     * Ahora se mide exactamente lo que el botón va a pintar: `contentOn(relleno)`.
     * Si algún día alguien mueve un relleno hasta la franja donde ni el blanco ni
     * la tinta oscura llegan a 4,5:1, esto se pone rojo — que es justo lo que hay
     * que saber antes de que llegue a un botón.
     */
    @Test
    fun `el texto sobre los rellenos de accion es legible`() {
        ambos.forEach { p ->
            listOf(
                p.t.accentInk to "coral de relleno",
                p.t.danger to "peligro",
                p.t.insight to "violeta de análisis",
            ).forEach { (fill, name) ->
                assertContrast(
                    p.t.contentOn(fill),
                    fill,
                    4.5,
                    "[${p.nombre}] Texto de botón sobre $name",
                )
            }
            // El verde de éxito se usa como relleno de botón y como texto de chip;
            // en relleno con su contenido encima llega al umbral de elemento de
            // interfaz.
            assertContrast(p.t.contentOn(p.t.success), p.t.success, 3.0, "[${p.nombre}] Texto de botón sobre éxito")
        }
    }

    @Test
    fun `el coral de marca no se usa nunca como relleno bajo texto blanco`() {
        // Ésta es la razón de que exista `accentInk`, y conviene que quede
        // medida: el coral de identidad no puede sostener texto blanco. Es el
        // MISMO color en los dos temas —la marca no cambia con la luz— así que la
        // afirmación vale para los dos.
        ambos.forEach { p ->
            val ratio = contrast(Color.White, p.t.accent)
            assertTrue(
                ratio < 3.0,
                "[${p.nombre}] El coral de marca ha cambiado y ahora sí admite texto blanco " +
                    "(${round2(ratio)}:1): se puede retirar accentInk",
            )
        }
    }

    // -----------------------------------------------------------------------
    // Estados
    // -----------------------------------------------------------------------

    @Test
    fun `los colores de estado se distinguen del fondo del chip`() {
        // Como ELEMENTO de interfaz: el tono contra la superficie, 3:1. El texto
        // del chip se mide aparte, sobre su propio tinte y a 4,5:1.
        ambos.forEach { p ->
            listOf(
                p.t.success to "éxito",
                p.t.warning to "aviso",
                p.t.danger to "peligro",
                p.t.info to "informativo",
                p.t.textSecondary to "desconocido",
            ).forEach { (color, name) ->
                assertContrast(color, p.t.surfaceSolid, 3.0, "[${p.nombre}] Chip de estado '$name'")
            }
        }
    }

    /** Las superficies sobre las que vive un chip de estado: opacas y, en una cabecera, cristal. */
    private fun basesDeChip(p: Paleta): List<Pair<String, Color>> = listOf(
        "superficie sólida" to p.t.surfaceSolid,
        "superficie hundida" to p.t.surfaceMuted,
        "fila seleccionada" to p.t.surfaceSelected,
    ) + p.cristales

    /**
     * El TEXTO de un chip de estado se lee sobre su propio tinte, en los DOS temas.
     *
     * Se medía como elemento de interfaz —el tono contra la superficie, 3:1— y es
     * texto de 13sp sobre ese mismo tono al 12 %. En oscuro el tinte ACLARA el
     * fondo del chip: «Archivado» daba 3,92:1 sobre superficie sólida y «Borrador»
     * 3,56:1 sobre el cristal de la cabecera. En claro lo OSCURECE, y el tono base
     * —pensado para puntos, iconos y bordes, donde basta 3:1— no llegaba como
     * texto: verde 2,92:1, ámbar 3,79:1, rojo 4,47:1 y azul 3,77:1 sobre superficie
     * sólida, y menos sobre cristal. Se mide con `statusChipColors`, que es lo que
     * pinta `StatusChip`, sobre todo lo que puede tener detrás.
     */
    @Test
    fun `el texto de un chip de estado se lee sobre todo lo que puede tener detras`() {
        ambos.forEach { p ->
            StatusTone.entries.forEach { tone ->
                val chip = statusChipColors(p.t, tone)
                basesDeChip(p).forEach { (nombre, base) ->
                    assertContrast(
                        chip.content,
                        chip.fill.over(base),
                        4.5,
                        "[${p.nombre}] Texto de un chip de estado $tone sobre $nombre",
                    )
                }
            }
        }
    }

    /**
     * Un color de estado usado como TEXTO DE CUERPO se mide a 4,5:1, no a 3:1.
     *
     * El assert anterior mide estos colores como chip y como punto, que son
     * elementos de interfaz y por eso van a 3:1. Pero `danger` se usa además para
     * párrafos completos —el mensaje de error de la barra de guardar, el fallo de
     * una subida—, y ahí el umbral es otro.
     */
    @Test
    fun `los colores que sostienen texto de cuerpo llegan a 4,5`() {
        ambos.forEach { p ->
            listOf(
                p.t.textSecondary to "secundario",
                p.t.danger to "peligro",
            ).forEach { (color, name) ->
                assertContrast(color, p.t.surfaceSolid, 4.5, "[${p.nombre}] Texto de cuerpo '$name' sobre superficie sólida")
                assertContrast(color, p.t.surfaceMuted, 4.5, "[${p.nombre}] Texto de cuerpo '$name' sobre superficie hundida")
            }
            // `danger` es también el color de un vínculo roto en la cabecera de un
            // examen, que va sobre cristal.
            p.cristales.forEach { (nombre, cristal) ->
                assertContrast(p.t.danger, cristal, 4.5, "[${p.nombre}] Texto de cuerpo 'peligro' sobre $nombre")
            }
        }
    }

    /**
     * El ámbar y el verde como ELEMENTO DE INTERFAZ: los dos temas.
     *
     * En los dos cumplen de sobra como chip, como punto y como icono. Lo que
     * cambia entre temas es si además valen para texto de cuerpo, y eso se afirma
     * abajo, tema a tema, en vez de dejarlo en un comentario que envejece.
     */
    @Test
    fun `el ambar y el verde funcionan como elemento de interfaz en los dos temas`() {
        ambos.forEach { p ->
            listOf(p.t.warning to "ámbar", p.t.success to "verde de éxito").forEach { (color, name) ->
                assertContrast(color, p.t.surfaceMuted, 3.0, "[${p.nombre}] El $name como elemento de interfaz")
            }
        }
    }

    /**
     * En el tema CLARO, el ámbar y el verde no valen para texto de cuerpo.
     *
     * Medido sobre los tokens reales: el ámbar da **4,10:1 sobre `surfaceMuted`**
     * y el verde de éxito 3,08:1. Ninguno llega al 4,5:1 que WCAG pide a un texto
     * de cuerpo, que es donde se estaban usando (el motivo de un tipo bloqueado, el
     * aviso de precio inerte). Esos textos pasaron a `textSecondary`.
     *
     * El test afirma la carencia en lugar de callarla. Si alguien oscurece uno de
     * los dos hasta que sí llegue, esto se pone rojo y le obliga a actualizar la
     * regla en vez de dejar un comentario que ya no es verdad.
     */
    @Test
    fun `en el tema claro el ambar y el verde no valen para texto de cuerpo`() {
        listOf(claro.t.warning to "ámbar", claro.t.success to "verde de éxito").forEach { (color, name) ->
            val ratio = contrast(color, claro.t.surfaceMuted)
            assertTrue(
                ratio < 4.5,
                "El $name del tema claro ha llegado a ${round2(ratio)}:1 sobre superficie hundida. Ya vale " +
                    "para texto de cuerpo: actualiza la regla en vez de dejar obsoleto este recordatorio.",
            )
        }
    }

    /**
     * En el tema OSCURO sí valen, y eso también se fija.
     *
     * No es una casualidad afortunada: sobre navy, un ámbar y un verde que
     * cumplieran sólo 3:1 serían tan apagados que se leerían como gris. Los dos se
     * aclararon hasta pasar de 4,5:1 a propósito, y este assert impide que alguien
     * los apague «para que se parezcan más al tema claro» — parecerse al tema
     * claro en oscuro significa exactamente lo contrario.
     */
    @Test
    fun `en el tema oscuro el ambar y el verde si sostienen texto de cuerpo`() {
        listOf(oscuro.t.warning to "ámbar", oscuro.t.success to "verde de éxito").forEach { (color, name) ->
            assertContrast(color, oscuro.t.surfaceMuted, 4.5, "El $name del tema oscuro como texto de cuerpo")
            assertContrast(color, oscuro.t.surfaceSolid, 4.5, "El $name del tema oscuro sobre superficie sólida")
        }
    }

    @Test
    fun `el violeta de la familia de analisis aguanta texto, no solo icono`() {
        // `insight` es el rol de Analítica y OposiTester. Se mide a 4,5:1 y no a
        // 3:1 aunque hoy sólo lo lleve un icono: un color de familia acaba tarde o
        // temprano en la etiqueta de esa familia.
        ambos.forEach { p ->
            assertContrast(p.t.insight, p.t.surfaceSolid, 4.5, "[${p.nombre}] Violeta de análisis sobre superficie sólida")
            p.cristales.forEach { (nombre, cristal) ->
                assertContrast(p.t.insight, cristal, 4.5, "[${p.nombre}] Violeta de análisis sobre $nombre")
            }
        }
    }

    @Test
    fun `el punto de una pildora de estado se distingue sobre cristal`() {
        // El `SystemStrip` pinta sus estados como punto de color dentro de una
        // píldora translúcida sobre el cristal de sección. El punto es un elemento
        // de interfaz que porta significado → 3:1. La etiqueta es texto → 4,5:1.
        //
        // El texto de la píldora NO depende de este color: dice «Ktor operativo»,
        // así que el punto refuerza, no informa por su cuenta.
        ambos.forEach { p ->
            p.cristales.forEach { (nombreCristal, cristal) ->
                StatusTone.entries.forEach { tone ->
                    val colores = statusDotChipColors(p.t, tone)
                    val pildora = colores.fill.over(cristal)
                    assertContrast(colores.dot, pildora, 3.0, "[${p.nombre}] Punto de estado $tone en la píldora, sobre $nombreCristal")
                    assertContrast(colores.label, pildora, 4.5, "[${p.nombre}] Etiqueta de la píldora, sobre $nombreCristal")
                }
            }
        }
    }

    @Test
    fun `el texto deshabilitado sigue siendo legible`() {
        // Un control deshabilitado se lee menos, pero se lee: si no, no hay forma
        // de saber qué botón está esperando a que rellenes algo.
        //
        // Estos dos ya NO se replican. Eran dos literales copiados de la paleta de
        // `DesktopButton` con un comentario que reconocía la copia; ahora son
        // tokens (`surfaceDisabled`, `textDisabled`) precisamente porque un literal
        // claro no tiene versión oscura y el botón apagado del tema oscuro se
        // dibujaba como un bloque gris claro, más llamativo que los pulsables.
        ambos.forEach { p ->
            assertContrast(p.t.textDisabled, p.t.surfaceDisabled, 3.0, "[${p.nombre}] Texto de botón deshabilitado")
        }
    }

    // -----------------------------------------------------------------------
    // Cristal, superficies y bordes
    // -----------------------------------------------------------------------

    @Test
    fun `el cristal de un contenedor de seccion sostiene su texto`() {
        ambos.forEach { p ->
            p.cristales.forEach { (nombre, cristal) ->
                assertContrast(p.t.textPrimary, cristal, 4.5, "[${p.nombre}] Título de sección sobre $nombre")
                assertContrast(p.t.textSecondary, cristal, 4.5, "[${p.nombre}] Subtítulo de sección sobre $nombre")
            }
        }
    }

    /**
     * **Un campo es siempre más opaco que el cristal que lo contiene.**
     *
     * Es la regla que hace que un formulario se pueda usar durante horas, y la
     * única del sistema de superficies que se puede comprobar con una fórmula: si
     * el relleno del campo y el valor compuesto de su contenedor se acercan, el
     * campo deja de tener bordes propios y el formulario se lee como un bloque de
     * texto con cosas escritas encima.
     *
     * Se mide en los dos temas porque es donde un tema oscuro falla por defecto:
     * el reflejo natural es «oscurecer todo» y el campo termina siendo el mismo
     * navy que su panel. El 1,08 no es una cifra de WCAG —esto no es un texto—
     * sino el suelo por debajo del cual la separación deja de verse.
     *
     * Se afirma sobre la parada más profunda, como siempre. Sobre el pico del halo
     * con el brillo encima el cristal claro llega a igualar al dato (1,01:1) y el
     * oscuro también (~1,00:1): ahí la separación la da el borde del campo, que es
     * lo que mide `el borde de un campo se distingue de su fondo`.
     */
    @Test
    fun `el relleno de un campo se distingue del cristal que lo contiene`() {
        ambos.forEach { p ->
            val ratio = contrast(p.t.surfaceSolid, p.cristal)
            assertTrue(
                ratio >= 1.08,
                "[${p.nombre}] El relleno del campo y el cristal del contenedor se han igualado " +
                    "(${round2(ratio)}:1): un input que se funde con su panel deja de parecer un input",
            )
        }
    }

    /**
     * **El borde de un campo es su frontera, y se ve: 3:1 (WCAG 1.4.11).**
     *
     * En escritorio el campo tiene el MISMO relleno que la sección que lo contiene,
     * así que su única frontera es el borde. Se medía con `border`, que es el filete
     * de una divisoria: 1,25:1 en claro y 1,41:1 en oscuro. Se mide con la función
     * que usan `GlassTextField`, `GlassSearchBar` y `DropdownField`, en reposo,
     * enfocado y con error, contra todo lo que puede tener a los lados: su propio
     * relleno y la sección sólida, una superficie hundida, y el cristal por el que
     * pasa el buscador del catálogo.
     */
    @Test
    fun `el borde de un campo se distingue de su fondo`() {
        ambos.forEach { p ->
            val fondos = listOf(
                "relleno del campo y sección sólida" to p.t.surfaceSolid,
                "superficie hundida" to p.t.surfaceMuted,
            ) + p.cristales
            listOf(false, true).forEach { focused ->
                listOf(false, true).forEach { isError ->
                    val borde = fieldBorderColor(p.t, focused = focused, isError = isError)
                    val estado = when {
                        focused -> "enfocado"
                        isError -> "con error"
                        else -> "en reposo"
                    }
                    fondos.forEach { (nombre, fondo) ->
                        assertContrast(borde, fondo, 3.0, "[${p.nombre}] Borde de campo $estado sobre $nombre")
                    }
                }
            }
        }
    }

    /**
     * **El estado de error de un campo se ve, y se ve en los dos temas.**
     *
     * Son las dos señales que marcan EL campo culpable de un rechazo, y sin ellas
     * el único aviso es una línea al pie del formulario que no dice cuál es:
     *
     *  - la línea de apoyo en rojo, que es TEXTO de 13sp (4,5:1) y cae sobre la
     *    superficie sólida de la sección que contiene el campo;
     *  - el borde de un campo de SÓLO LECTURA, que es la única frontera que ese
     *    campo puede cambiar —su relleno hundido se queda como está, porque es lo
     *    que dice que no se escribe— y como elemento de interfaz pide 3:1 contra
     *    ese relleno y contra la sección que tiene al lado.
     *
     * El borde de un campo escribible ya lo mide `el borde de un campo se
     * distingue de su fondo`, que pasa por la misma `fieldBorderColor`.
     */
    @Test
    fun `el estado de error de un campo se ve en los dos temas`() {
        ambos.forEach { p ->
            assertContrast(
                fieldSupportColor(p.t, isError = true),
                p.t.surfaceSolid,
                4.5,
                "[${p.nombre}] Apoyo de un campo con error sobre sección sólida",
            )
            assertContrast(
                fieldSupportColor(p.t, isError = true),
                p.t.surfaceMuted,
                4.5,
                "[${p.nombre}] Apoyo de un campo con error sobre superficie hundida",
            )
            listOf(
                "su propio relleno hundido" to p.t.surfaceMuted,
                "la sección sólida de al lado" to p.t.surfaceSolid,
            ).forEach { (donde, fondo) ->
                assertContrast(
                    readOnlyFieldBorderColor(p.t, isError = true),
                    fondo,
                    3.0,
                    "[${p.nombre}] Borde de un campo de sólo lectura con error sobre $donde",
                )
            }
        }
    }

    /**
     * Un borde fuerte se ve. Sobre navy es donde esto se pierde primero.
     *
     * `borderStrong` es lo que dibuja el contorno de una tabla y el marcador de la
     * zona de portada — una caja vacía cuyo ÚNICO contenido es su propio borde—.
     * Un borde que no se ve convierte esa zona en un hueco, que es exactamente el
     * defecto que el tema oscuro tiende a producir: en claro un gris azulado se
     * separa del blanco casi solo, y en oscuro hay que subirlo a mano.
     */
    @Test
    fun `un borde fuerte se distingue de la superficie que enmarca`() {
        ambos.forEach { p ->
            val ratio = contrast(p.t.borderStrong, p.t.surfaceSolid)
            assertTrue(
                ratio >= 1.4,
                "[${p.nombre}] El borde fuerte ha dejado de verse sobre superficie sólida " +
                    "(${round2(ratio)}:1): las tablas y el marcador de portada se quedan sin contorno",
            )
        }
    }

    // -----------------------------------------------------------------------
    // El coral, que es donde los dos temas se separan
    // -----------------------------------------------------------------------

    @Test
    fun `el coral de enlace vale como texto sobre superficie solida en los dos temas`() {
        ambos.forEach { p ->
            assertContrast(p.t.accentInk, p.t.surfaceSolid, 4.5, "[${p.nombre}] Texto de enlace sobre superficie sólida")
            p.cristales.forEach { (nombre, cristal) ->
                assertContrast(sectionLinkChevronTint(p.t), cristal, 3.0, "[${p.nombre}] Chevron de enlace sobre $nombre")
                // Consecuencia práctica: el TEXTO del enlace se pinta en
                // `textPrimary` en los dos temas —no cambia con la luz, que es lo
                // que hace que la pantalla se reconozca—, así que ese es el color
                // que tiene que llegar.
                assertContrast(p.t.textPrimary, cristal, 4.5, "[${p.nombre}] Texto de enlace de sección sobre $nombre")
            }
        }
    }

    /**
     * En CLARO el coral de enlace no llega a texto sobre cristal. Ésa es la
     * excepción que obliga a que el texto del enlace vaya en `textPrimary`.
     *
     * Durante la pasada visual se oscureció `accentInk` a `#B04A1B` para que
     * pasara el umbral de TEXTO también sobre cristal y hubiera un único color de
     * enlace. **Esa decisión se revirtió por decisión de marca**: `accentInk` es
     * el relleno del botón primario de toda la aplicación, móvil incluido, y un
     * rediseño de una pantalla no justifica moverlo. El valor canónico es
     * `#C2521F`, que sobre cristal da 4,11:1.
     */
    @Test
    fun `en el tema claro el coral de enlace no llega a texto sobre cristal`() {
        val ratio = contrast(claro.t.accentInk, claro.cristal)
        assertTrue(
            ratio < 4.5,
            "El coral de enlace del tema claro ya sostiene texto sobre cristal (${round2(ratio)}:1): " +
                "se puede unificar el color de enlace y retirar la excepción de `textPrimary`",
        )
    }

    /**
     * En OSCURO sí llega, en los dos extremos del cristal, y **no se aprovecha**.
     *
     * Podría: sobre navy el coral claro sostiene texto de sobra, así que los
     * enlaces podrían ir en coral en el tema oscuro y en `textPrimary` en el
     * claro. No se hace, a propósito. Un observador tiene que reconocer la misma
     * pantalla en los dos temas, y un enlace que cambia de color según la luz es
     * justo el tipo de divergencia que convierte un tema en otro diseño.
     *
     * Lo que sí se fija aquí es el margen, para que quien decida algún día
     * unificar los enlaces sepa cuál de los dos temas es el que manda.
     */
    @Test
    fun `en el tema oscuro el coral de enlace si llegaria a texto sobre cristal`() {
        oscuro.cristales.forEach { (nombre, cristal) ->
            assertContrast(oscuro.t.accentInk, cristal, 4.5, "Coral de enlace del tema oscuro sobre $nombre")
        }
    }

    @Test
    fun `el margen del coral de enlace del tema claro es estrecho y queda medido`() {
        // `accentInk` #C2521F sobre blanco da 4,56:1: pasa AA para texto normal
        // por SEIS CENTÉSIMAS. Es el valor canónico de marca y se conserva por
        // decisión explícita del propietario del producto, así que lo que procede
        // no es forzar un suelo que el color no puede cumplir, sino **dejar el
        // margen medido y a la vista**.
        //
        // Es una afirmación del tema CLARO porque habla del coral sobre papel
        // blanco. El tema oscuro no tiene papel blanco.
        val ratio = contrast(claro.t.accentInk, Color.White)
        assertTrue(
            ratio >= 4.5,
            "El coral de enlace ha bajado a ${round2(ratio)}:1 sobre blanco y ya no pasa " +
                "el umbral AA de 4,5:1 para texto normal",
        )
        assertTrue(
            ratio < 5.0,
            "El coral de enlace ha subido a ${round2(ratio)}:1: si el cambio es deliberado, " +
                "revisa si ya sostiene texto sobre cristal y la excepción de `textPrimary` sobra",
        )
    }

    /**
     * El coral sobre la fila SELECCIONADA: el sitio donde hoy hay una carencia
     * real en el tema claro, y se afirma en vez de callarse.
     *
     * `surfaceSelected` es el fondo de la pestaña activa del workspace y de las
     * tarjetas elegidas del editor. `accentInk` encima, a 14sp —texto normal—, da
     * **4,21:1 en claro**, por debajo de 4,5:1. En oscuro da 7,03:1.
     *
     * Ya no lo usa nadie para el TEXTO: la pestaña activa y las tarjetas de tipo y
     * de acceso pintan su etiqueta en `textPrimary` y dejan el coral en el icono,
     * el borde y el check, que como elementos de interfaz sólo piden 3:1. Lo miden
     * los tests de pestañas de abajo y `ExamCheckContrastTest`, sobre las mismas
     * funciones que pintan los componentes. Este test se queda de guardia: si
     * alguien devuelve `accentInk` a un texto sobre `surfaceSelected`, el número
     * sigue aquí con su porqué.
     *
     * No se arregla subiendo el coral: `accentInk` es la marca y ya se revirtió
     * una vez por eso.
     */
    @Test
    fun `el coral sobre la fila seleccionada esta medido en los dos temas`() {
        val enClaro = contrast(claro.t.accentInk, claro.t.surfaceSelected)
        assertTrue(
            enClaro < 4.5,
            "El coral sobre fila seleccionada del tema claro ha llegado a ${round2(enClaro)}:1: " +
                "la carencia documentada ya no existe, actualiza la regla",
        )
        assertContrast(claro.t.accentInk, claro.t.surfaceSelected, 3.0, "[tema claro] Coral sobre fila seleccionada")
        assertContrast(oscuro.t.accentInk, oscuro.t.surfaceSelected, 4.5, "[tema oscuro] Coral sobre fila seleccionada")
        // Y el texto que SÍ porta el significado llega en los dos.
        ambos.forEach { p ->
            assertContrast(p.t.textPrimary, p.t.surfaceSelected, 4.5, "[${p.nombre}] Texto del item activo de la sidebar")
        }
    }

    // -----------------------------------------------------------------------
    // Pestañas: se mide lo que PINTA el componente, no un par de tokens
    // -----------------------------------------------------------------------
    //
    // Un test de pares de tokens no protege un componente: si alguien vuelve a
    // poner el número del contador en `accent`, ningún par cambia y todo lo de
    // arriba sigue en verde. Estos tests llaman a las MISMAS funciones con las que
    // `DesktopTabs` elige cada color, con la paleta viva de los dos temas.

    /**
     * Sobre qué puede quedar una pestaña: los cuatro extremos.
     *
     * La fila de un workspace va dentro de un contenedor de cristal y la de un hub
     * directamente sobre el fondo; una pestaña transparente deja ver cualquiera de
     * los dos. La activa de un workspace tiene fondo propio y opaco, y entonces la
     * base no cuenta: `tabBackground` la tapa.
     *
     * Antes aquí sólo estaban el cristal sobre la parada profunda y el fondo
     * profundo, y el comentario reconocía el agujero: en el render oscuro, la
     * etiqueta de una pestaña INACTIVA medía 2,6–3,4:1 bajo el brillo del
     * contenedor. Con los extremos claros incluidos, ese caso es el que manda.
     */
    private fun basesDePestana(p: Paleta): List<Pair<Color, String>> =
        p.fondos.all.map { (nombre, color) -> color to nombre }

    private fun estadoDePestana(selected: Boolean, hovered: Boolean = false): String =
        (if (selected) "activa" else "inactiva") + (if (hovered) ", bajo el puntero" else "")

    /**
     * El número del contador se lee sobre su píldora.
     *
     * Antes iba en `accent` sobre ese mismo coral al 15%: 1,89:1 en claro sobre la
     * pestaña activa, ni el 3:1 de un elemento de interfaz. El relleno se compone
     * sobre el fondo real de la pestaña, igual que el renderer.
     */
    @Test
    fun `el numero del contador de una pestana se lee sobre su pildora`() {
        ambos.forEach { p ->
            val badge = tabBadgeColors(p.t)
            DesktopTabsVariant.entries.forEach { variant ->
                listOf(true, false).forEach { selected ->
                    basesDePestana(p).forEach { (base, nombreBase) ->
                        val relleno = badge.fill.over(tabBackground(p.t, selected, variant).over(base))
                        assertContrast(
                            badge.content,
                            relleno,
                            4.5,
                            "[${p.nombre}] Número del contador ($variant, ${estadoDePestana(selected)}, sobre $nombreBase)",
                        )
                    }
                }
            }
        }
    }

    /**
     * La etiqueta de una pestaña se lee en todos sus estados.
     *
     * El caso que motivó esto es la pestaña activa del workspace: su texto iba en
     * `accentInk` sobre `surfaceSelected`, 4,21:1 en claro para un texto de 14sp.
     * Se mide cada combinación de variante, selección y puntero, para que ninguna
     * se quede fuera por no haberla pensado.
     */
    @Test
    fun `la etiqueta de una pestana se lee en todos sus estados`() {
        ambos.forEach { p ->
            DesktopTabsVariant.entries.forEach { variant ->
                listOf(true, false).forEach { selected ->
                    listOf(true, false).forEach { hovered ->
                        basesDePestana(p).forEach { (base, nombreBase) ->
                            assertContrast(
                                tabLabelColor(p.t, selected, hovered, variant),
                                tabBackground(p.t, selected, variant).over(base),
                                4.5,
                                "[${p.nombre}] Etiqueta de pestaña ($variant, " +
                                    "${estadoDePestana(selected, hovered)}, sobre $nombreBase)",
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * El icono se distingue en todos sus estados, y en la activa del workspace
     * conserva el coral.
     *
     * Un icono es un elemento de interfaz: 3:1. `accentInk` sobre `surfaceSelected`
     * da 4,21:1 en claro y 7,03:1 en oscuro, así que el coral se queda aquí, en el
     * subrayado y en el relleno, que es donde sí puede estar.
     */
    @Test
    fun `el icono de una pestana se distingue en todos sus estados`() {
        ambos.forEach { p ->
            DesktopTabsVariant.entries.forEach { variant ->
                listOf(true, false).forEach { selected ->
                    listOf(true, false).forEach { hovered ->
                        basesDePestana(p).forEach { (base, nombreBase) ->
                            assertContrast(
                                tabIconTint(p.t, selected, hovered, variant),
                                tabBackground(p.t, selected, variant).over(base),
                                3.0,
                                "[${p.nombre}] Icono de pestaña ($variant, " +
                                    "${estadoDePestana(selected, hovered)}, sobre $nombreBase)",
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * En CLARO el coral de marca no porta significado sobre cristal; en OSCURO sí.
     *
     * Es la única asimetría de color deliberada entre los dos temas, y viene de
     * que `accent` es EXACTAMENTE el mismo `#FF8A5B` en ambos: sobre un cristal
     * claro se pierde (2,4:1) y sobre uno navy destaca. Se deja dicha en los dos
     * sentidos para que nadie «arregle» el tema oscuro apagando el coral hasta
     * igualar la carencia del claro.
     */
    @Test
    fun `el coral de marca porta significado sobre cristal solo en el tema oscuro`() {
        val enClaro = contrast(claro.t.accent, claro.cristal)
        assertTrue(
            enClaro < 3.0,
            "El coral de marca del tema claro ha cambiado y ahora sí se distingue sobre cristal " +
                "(${round2(enClaro)}:1): revisar dónde puede portar significado",
        )
        oscuro.cristales.forEach { (nombre, cristal) ->
            assertContrast(oscuro.t.accent, cristal, 3.0, "[tema oscuro] Coral de marca sobre $nombre")
        }
    }

    // -----------------------------------------------------------------------
    // Familias de color
    // -----------------------------------------------------------------------

    @Test
    fun `el teal comercial se distingue del rojo de peligro tambien como tinte`() {
        // El motivo por el que existe `commerce`. Los contenedores de icono pintan
        // el color de su familia al 12% sobre la superficie; con el coral de marca,
        // «Suscripciones que vencen» daba (237,208,196) y «Reportes pendientes»
        // (237,202,199): el mismo tinte para dos cosas opuestas, en tarjetas
        // contiguas. Se mide la distancia entre los DOS TINTES, que es lo que el
        // ojo compara, y no entre los colores plenos.
        ambos.forEach { p ->
            val tinteComercial = p.t.commerce.copy(alpha = 0.12f).over(p.t.surfaceSolid)
            val tintePeligro = p.t.danger.copy(alpha = 0.12f).over(p.t.surfaceSolid)
            val distancia = canalesDeDiferencia(tinteComercial, tintePeligro)
            assertTrue(
                distancia >= 24,
                "[${p.nombre}] Los tintes de comercial y peligro se han vuelto a acercar ($distancia puntos " +
                    "de diferencia sumando canales): en la franja de atención se verían iguales",
            )

            // Y como color pleno tiene que aguantar texto, no sólo icono: se usa en
            // la cifra de las filas de Economía.
            assertContrast(p.t.commerce, p.t.surfaceSolid, 4.5, "[${p.nombre}] Teal comercial sobre superficie sólida")
            p.cristales.forEach { (nombre, cristal) ->
                assertContrast(p.t.commerce, cristal, 4.5, "[${p.nombre}] Teal comercial sobre $nombre")
            }
        }
    }

    /**
     * Los siete colores de familia siguen siendo siete colores distintos.
     *
     * Un tema oscuro se construye aclarando colores, y aclarar los desatura: el
     * riesgo real no es que uno falle de contraste sino que el verde, el teal y el
     * ámbar converjan hacia el mismo pastel y el sistema de color deje de
     * informar. Se mide entre todos los pares, en los dos temas.
     */
    @Test
    fun `las familias de color no convergen en ningun tema`() {
        ambos.forEach { p ->
            val familias = listOf(
                "éxito" to p.t.success,
                "aviso" to p.t.warning,
                "peligro" to p.t.danger,
                "informativo" to p.t.info,
                "análisis" to p.t.insight,
                "comercial" to p.t.commerce,
                "marca" to p.t.accent,
            )
            familias.forEachIndexed { i, (nombreA, colorA) ->
                familias.drop(i + 1).forEach { (nombreB, colorB) ->
                    val distancia = canalesDeDiferencia(colorA, colorB)
                    assertTrue(
                        distancia >= 60,
                        "[${p.nombre}] '$nombreA' y '$nombreB' se han acercado a $distancia puntos " +
                            "sumando canales: dos familias que se ven iguales dejan de informar",
                    )
                }
            }
        }
    }

    /**
     * El icono de un contenedor semántico se distingue de su propio tinte: 3:1.
     *
     * `SemanticIconContainer` abre cada sección del workspace y cada tarjeta del
     * panel: un icono de la familia sobre esa misma familia al 12 %. Es un
     * elemento no textual, así que pide 3:1 frente a lo que tiene detrás, y se
     * mide con la función que usa el componente, sobre la superficie sólida de
     * las tarjetas y sobre los dos extremos del cristal de las secciones.
     */
    @Test
    fun `el icono de un contenedor semantico se distingue de su tinte`() {
        ambos.forEach { p ->
            val fondos = listOf("superficie sólida" to p.t.surfaceSolid) + p.cristales
            SemanticRole.entries.forEach { role ->
                val colores = semanticIconContainerColors(p.t, role)
                fondos.forEach { (nombre, fondo) ->
                    assertContrast(
                        colores.icon,
                        colores.fill.over(fondo),
                        3.0,
                        "[${p.nombre}] Icono de rol $role sobre su tinte, sobre $nombre",
                    )
                }
            }
        }
    }

    /**
     * Las cuatro tintas de estado siguen siendo cuatro colores distintos.
     *
     * Oscurecer para que un color aguante texto lo acerca a los demás colores
     * oscurecidos: el riesgo de las tintas no es el contraste —eso lo mide el test
     * del chip— sino que el verde y el azul, o el ámbar y el rojo, acaben leyéndose
     * como el mismo tono en dos chips contiguos. Mismo suelo que las familias.
     */
    @Test
    fun `las tintas de estado no convergen en ningun tema`() {
        ambos.forEach { p ->
            val tintas = listOf(
                "éxito" to p.t.successInk,
                "aviso" to p.t.warningInk,
                "peligro" to p.t.dangerInk,
                "informativo" to p.t.infoInk,
            )
            tintas.forEachIndexed { i, (nombreA, colorA) ->
                tintas.drop(i + 1).forEach { (nombreB, colorB) ->
                    val distancia = canalesDeDiferencia(colorA, colorB)
                    assertTrue(
                        distancia >= 60,
                        "[${p.nombre}] Las tintas de '$nombreA' y '$nombreB' se han acercado a $distancia puntos " +
                            "sumando canales: dos chips de estado distintos se leerían iguales",
                    )
                }
            }
        }
    }

    /**
     * El tema oscuro es oscuro, y no es negro.
     *
     * Dos afirmaciones en una, y las dos son sobre la dirección del tema, no sobre
     * un color concreto:
     *
     *  - Toda superficie de la paleta oscura es más oscura que su homóloga clara.
     *    Suena obvio; deja de serlo en cuanto alguien aclara una superficie «para
     *    que se vea mejor» y la deja por encima del tema claro.
     *  - El fondo más profundo no es negro puro. El negro absoluto produce halo
     *    alrededor del texto claro y cansa en una jornada de trabajo; el mínimo de
     *    luminancia lo fija este assert, no una opinión.
     */
    @Test
    fun `el tema oscuro es mas oscuro que el claro sin llegar a negro`() {
        listOf(
            "fondo profundo" to (claro.t.backgroundDeep to oscuro.t.backgroundDeep),
            "fondo superior" to (claro.t.backgroundTop to oscuro.t.backgroundTop),
            "superficie sólida" to (claro.t.surfaceSolid to oscuro.t.surfaceSolid),
            "superficie hundida" to (claro.t.surfaceMuted to oscuro.t.surfaceMuted),
            "cristal compuesto" to (claro.cristal to oscuro.cristal),
            "cristal en el pico del halo" to (claro.fondos.glassOverLightest to oscuro.fondos.glassOverLightest),
        ).forEach { (nombre, par) ->
            val (enClaro, enOscuro) = par
            assertTrue(
                enOscuro.relativeLuminance() < enClaro.relativeLuminance(),
                "La superficie '$nombre' del tema oscuro no es más oscura que la del claro",
            )
        }
        val fondo = oscuro.t.backgroundDeep.relativeLuminance()
        assertTrue(
            fondo > 0.004,
            "El fondo más profundo del tema oscuro se ha acercado al negro puro (luminancia $fondo): " +
                "el navy existe para que el texto claro no produzca halo",
        )
        // Y el texto principal del tema oscuro es claro, no un gris a medias.
        assertTrue(
            oscuro.t.textPrimary.relativeLuminance() > 0.6,
            "El texto principal del tema oscuro ha dejado de ser casi blanco",
        )
    }
}
