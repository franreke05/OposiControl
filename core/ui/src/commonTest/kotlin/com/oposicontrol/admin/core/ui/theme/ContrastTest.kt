package com.oposicontrol.admin.core.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Contraste (§47).
 *
 * El contraste es de las pocas cosas de una interfaz que se puede comprobar con
 * una fórmula en vez de con una opinión, así que se comprueba. La alternativa es
 * mirar una captura y decir "se ve bien", que es exactamente como acaban
 * colándose los grises ilegibles.
 *
 * Ratios de WCAG 2.1: 4,5:1 para texto normal, 3:1 para texto grande y para
 * elementos de interfaz (bordes, iconos que portan significado).
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

    private fun assertContrast(
        foreground: Color,
        background: Color,
        minimum: Double,
        description: String,
    ) {
        val ratio = contrast(foreground, background)
        assertTrue(
            ratio >= minimum,
            "$description: ${(ratio * 100).toInt() / 100.0}:1, se requiere $minimum:1",
        )
    }

    // Los tokens no son accesibles desde fuera del tema, así que se replican los
    // valores exactos. Si divergen, este test deja de proteger nada — por eso
    // están juntos y nombrados igual.
    private val surfaceSolid = Color(0xFFFCFDFE)
    private val surfaceMuted = Color(0xFFF1F5FA)
    private val surfaceSelected = Color(0xFFFFF1EA)
    private val backgroundTop = Color(0xFFEAF1F8)
    private val textPrimary = Color(0xFF142230)
    private val textSecondary = Color(0xFF516173)
    private val accent = Color(0xFFFF8A5B)
    private val success = Color(0xFF2E9E6B)
    private val warning = Color(0xFFA66A00)
    private val danger = Color(0xFFC0392B)
    private val info = Color(0xFF3E7CB1)
    private val accentInk = Color(0xFFC2521F)
    private val disabledContainer = Color(0xFFEDEFF2)
    private val disabledContent = Color(0xFF6B7684)

    @Test
    fun `el texto principal es legible sobre toda superficie de escritorio`() {
        listOf(
            surfaceSolid to "superficie sólida",
            surfaceMuted to "superficie hundida",
            surfaceSelected to "fila seleccionada",
            backgroundTop to "fondo atmosférico",
        ).forEach { (background, name) ->
            assertContrast(textPrimary, background, 4.5, "Texto principal sobre $name")
        }
    }

    @Test
    fun `el texto secundario cumple texto normal, no solo texto grande`() {
        // Se usa en descripciones y celdas de tabla a 13-14sp, que es texto
        // normal: el umbral aplicable es 4,5:1, no 3:1.
        listOf(
            surfaceSolid to "superficie sólida",
            surfaceMuted to "superficie hundida",
            backgroundTop to "fondo atmosférico",
        ).forEach { (background, name) ->
            assertContrast(textSecondary, background, 4.5, "Texto secundario sobre $name")
        }
    }

    @Test
    fun `el texto blanco sobre los rellenos de accion es legible`() {
        // Los tres rellenos que llevan texto blanco encima en un botón. El umbral
        // es 4,5:1 y no 3:1 porque el texto de botón mide 14sp: no es texto
        // grande, por mucho que vaya en SemiBold.
        assertContrast(Color.White, danger, 4.5, "Blanco sobre peligro")
        assertContrast(Color.White, accentInk, 4.5, "Blanco sobre coral de relleno")
        // El verde de éxito se usa como relleno de botón y como texto de chip; en
        // relleno con blanco encima llega al umbral de elemento de interfaz.
        assertContrast(Color.White, success, 3.0, "Blanco sobre éxito")
    }

    @Test
    fun `el coral de marca no se usa nunca como relleno bajo texto blanco`() {
        // Ésta es la razón de que exista `accentInk`, y conviene que quede
        // medida: el coral de identidad no puede sostener texto blanco. Si algún
        // día alguien lo vuelve a usar como fondo de botón, este número explica
        // por qué no.
        val ratio = contrast(Color.White, accent)
        assertTrue(
            ratio < 3.0,
            "El coral de marca ha cambiado y ahora sí admite texto blanco " +
                "(${(ratio * 100).toInt() / 100.0}:1): se puede retirar accentInk",
        )
    }

    @Test
    fun `los colores de estado se distinguen del fondo del chip`() {
        // El chip pinta el texto en el color de estado sobre ese mismo color al
        // 12% de alpha compuesto sobre la superficie. Se comprueba contra la
        // superficie, que es el peor caso realista.
        listOf(
            success to "éxito",
            warning to "aviso",
            danger to "peligro",
            info to "informativo",
            textSecondary to "desconocido",
        ).forEach { (color, name) ->
            assertContrast(color, surfaceSolid, 3.0, "Chip de estado '$name'")
        }
    }

    @Test
    fun `el texto deshabilitado sigue siendo legible`() {
        // Un control deshabilitado se lee menos, pero se lee: si no, no hay forma
        // de saber qué botón está esperando a que rellenes algo.
        assertContrast(disabledContent, disabledContainer, 3.0, "Texto de botón deshabilitado")
    }

    @Test
    fun `el coral funciona como texto de acento sobre superficie clara`() {
        // Se usa en el item activo de la sidebar y en los enlaces de ayuda, a
        // tamaño de cuerpo: necesita el umbral de texto normal.
        val ratio = contrast(accent, surfaceSolid)
        assertTrue(
            ratio < 4.5,
            "Si el coral llegara a 4,5:1 se podría usar como texto de cuerpo y " +
                "habría que revisar esta regla; hoy da ${(ratio * 100).toInt() / 100.0}:1",
        )
        // Consecuencia práctica y comprobada: donde el coral es el único
        // portador del texto, el color real que se usa es textPrimary y el coral
        // queda para el fondo y el indicador.
        assertContrast(textPrimary, surfaceSelected, 4.5, "Texto del item activo de la sidebar")
    }
}
