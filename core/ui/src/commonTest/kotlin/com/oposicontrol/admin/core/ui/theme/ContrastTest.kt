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
    private val insight = Color(0xFF6D46B8)
    private val accentInk = Color(0xFFC2521F)
    private val commerce = Color(0xFF0C6E77)
    private val disabledContainer = Color(0xFFEDEFF2)
    private val disabledContent = Color(0xFF6B7684)
    private val backgroundBottom = Color(0xFFD6E4F2)
    private val backgroundDeep = Color(0xFFCEDFF0)
    private val glassFill = Color(0x66FFFFFF)
    private val glassFillStrong = Color(0x88FFFFFF)
    private val sectionGlassToken = Color(0x94FFFFFF)

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
     * Fondo EFECTIVO de un contenedor de sección de cristal.
     *
     * `GlassSectionSurface` pinta el token `sectionGlass` —blanco al 58%— sobre el
     * degradado atmosférico. Se compone contra [backgroundDeep], que es la parada
     * MÁS PROFUNDA de ese degradado y por tanto el peor caso para un texto oscuro.
     *
     * Que sea `backgroundDeep` y no `backgroundBottom` importa: el escritorio
     * dibuja tres paradas y la última baja por debajo de `backgroundBottom`. Medir
     * contra la parada intermedia daría un número mejor que el real, que es la
     * forma más silenciosa que tiene un test de contraste de dejar de proteger.
     * Los tres halos son blanco, azul y coral a alpha muy bajo, y sólo pueden
     * aclarar esta cifra, nunca oscurecerla por debajo.
     */
    private val sectionGlass = sectionGlassToken.over(backgroundDeep)

    /**
     * Fondo EFECTIVO de una píldora de estado del `SystemStrip`.
     *
     * `StatusDotChip` pinta `glassFillStrong` —blanco al 53%— y va SOBRE el
     * cristal de sección, así que se compone en dos pasos, igual que el renderer.
     * Es el fondo más claro del panel y por tanto el peor caso para el punto de
     * color, que es lo único que se mide aquí.
     */
    private val statusPill = glassFillStrong.over(sectionGlass)

    @Test
    fun `el texto principal es legible sobre toda superficie de escritorio`() {
        listOf(
            surfaceSolid to "superficie sólida",
            surfaceMuted to "superficie hundida",
            surfaceSelected to "fila seleccionada",
            backgroundTop to "fondo atmosférico",
            backgroundDeep to "fondo atmosférico profundo",
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
            // La parada más profunda del degradado de escritorio. Importa porque
            // `DesktopPageHeader` pinta la descripción de la pantalla
            // DIRECTAMENTE sobre el fondo, sin panel debajo: es el único texto de
            // escritorio que no tiene una superficie propia que lo sostenga, y es
            // lo que acota cuánto puede profundizar el degradado.
            backgroundDeep to "fondo atmosférico profundo",
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
    fun `el violeta de la familia de analisis aguanta texto, no solo icono`() {
        // `insight` es el rol nuevo (Analítica y OposiTester). Se mide a 4,5:1 y
        // no a 3:1 aunque hoy sólo lo lleve un icono: un color de familia acaba
        // tarde o temprano en la etiqueta de esa familia, y es mejor que llegue
        // ahí ya medido que descubrirlo cuando alguien lo escriba.
        assertContrast(insight, surfaceSolid, 4.5, "Violeta de análisis sobre superficie sólida")
        assertContrast(insight, sectionGlass, 4.5, "Violeta de análisis sobre cristal de sección")
        // Y como relleno con texto blanco encima, por si un día es un botón.
        assertContrast(Color.White, insight, 4.5, "Blanco sobre violeta de análisis")
    }

    @Test
    fun `el punto de una pildora de estado se distingue sobre cristal`() {
        // El `SystemStrip` pinta sus estados como punto de color dentro de una
        // píldora blanca translúcida sobre el cristal de sección: es el fondo más
        // claro del panel, y por tanto el peor caso. El punto es un elemento de
        // interfaz que porta significado → 3:1.
        //
        // El texto de la píldora NO depende de este color: dice «Ktor operativo»,
        // así que el punto refuerza, no informa por su cuenta.
        listOf(
            success to "operativo",
            warning to "degradado",
            danger to "caído",
            info to "informativo",
            textSecondary to "desconocido",
        ).forEach { (color, name) ->
            assertContrast(color, statusPill, 3.0, "Punto de estado '$name' en la píldora del strip")
        }
        assertContrast(textPrimary, statusPill, 4.5, "Etiqueta de la píldora del strip")
    }

    @Test
    fun `el texto deshabilitado sigue siendo legible`() {
        // Un control deshabilitado se lee menos, pero se lee: si no, no hay forma
        // de saber qué botón está esperando a que rellenes algo.
        assertContrast(disabledContent, disabledContainer, 3.0, "Texto de botón deshabilitado")
    }

    @Test
    fun `el cristal de un contenedor de seccion sostiene su texto`() {
        // El panel de escritorio es la primera pantalla que pone texto DIRECTAMENTE
        // sobre cristal: las cabeceras de sus cinco franjas y sus enlaces. Hasta
        // ahora el único fondo translúcido medido era `backgroundTop`, que no es lo
        // mismo — un contenedor de cristal aclara lo que tiene detrás.
        assertContrast(textPrimary, sectionGlass, 4.5, "Título de sección sobre cristal")
        assertContrast(textSecondary, sectionGlass, 4.5, "Subtítulo de sección sobre cristal")
    }

    @Test
    fun `el coral de enlace vale como texto sobre solido y solo como icono sobre cristal`() {
        // Ésta es la medición que decide el color de los enlaces del panel.
        //
        // Durante la pasada visual se oscureció `accentInk` a `#B04A1B` para que
        // el coral pasara el umbral de TEXTO también sobre cristal y hubiera un
        // único color de enlace en toda la pantalla. **Esa decisión se revirtió
        // por decisión de marca**: `accentInk` es el relleno del botón primario de
        // toda la aplicación, móvil incluido, y un rediseño de una pantalla no
        // justifica moverlo. El valor canónico vuelve a ser `#C2521F`.
        //
        // La consecuencia es que la excepción sigue viva y hay que medirla: sobre
        // superficie sólida el coral sostiene texto; sobre el cristal de una
        // sección **no llega** a 4,5:1, así que allí el texto del enlace se pinta
        // en `textPrimary` y el coral se queda en el chevron, que es un elemento
        // de interfaz y le basta con 3:1.
        assertContrast(accentInk, surfaceSolid, 4.5, "Texto de enlace sobre superficie sólida")
        assertContrast(accentInk, sectionGlass, 3.0, "Chevron de enlace sobre cristal de sección")
        assertContrast(textPrimary, sectionGlass, 4.5, "Texto de enlace de sección sobre cristal")

        // Y se fija el motivo de la excepción, no sólo su consecuencia: si algún
        // día el coral llegara a 4,5:1 sobre cristal, esta prueba avisa de que la
        // excepción se puede retirar en vez de dejarla fosilizada.
        val sobreCristal = contrast(accentInk, sectionGlass)
        assertTrue(
            sobreCristal < 4.5,
            "El coral de enlace ya sostiene texto sobre cristal (${round2(sobreCristal)}:1): " +
                "se puede unificar el color de enlace y retirar la excepción de `textPrimary`",
        )
    }

    @Test
    fun `el margen del coral de enlace sobre blanco es estrecho y queda medido`() {
        // `accentInk` #C2521F sobre blanco da 4,56:1: pasa AA para texto normal
        // por SEIS CENTÉSIMAS. Es el valor canónico de marca y se conserva por
        // decisión explícita del propietario del producto, así que lo que procede
        // no es forzar un suelo que el color no puede cumplir, sino **dejar el
        // margen medido y a la vista**.
        //
        // Lo que esta prueba protege es el suelo real de accesibilidad, y avisa
        // por arriba: si alguien vuelve a oscurecerlo, no falla —sería una mejora—
        // pero el número de este comentario deja de ser cierto.
        val ratio = contrast(accentInk, Color.White)
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

    @Test
    fun `el teal comercial se distingue del rojo de peligro tambien como tinte`() {
        // El motivo por el que existe `commerce`. Los contenedores de icono pintan
        // el color de su familia al 12% sobre la superficie; con el coral de marca,
        // «Suscripciones que vencen» daba (237,208,196) y «Reportes pendientes»
        // (237,202,199): el mismo tinte para dos cosas opuestas, en tarjetas
        // contiguas. Se mide la distancia entre los DOS TINTES, que es lo que el
        // ojo compara, y no entre los colores plenos.
        val tinteComercial = commerce.copy(alpha = 0.12f).over(surfaceSolid)
        val tintePeligro = danger.copy(alpha = 0.12f).over(surfaceSolid)
        val distancia = canalesDeDiferencia(tinteComercial, tintePeligro)
        assertTrue(
            distancia >= 24,
            "Los tintes de comercial y peligro se han vuelto a acercar ($distancia puntos de " +
                "diferencia sumando canales): en la franja de atención se verían iguales",
        )

        // Y como color pleno tiene que aguantar texto, no sólo icono: se usa en la
        // cifra de las filas de Economía.
        assertContrast(commerce, surfaceSolid, 4.5, "Teal comercial sobre superficie sólida")
        assertContrast(commerce, sectionGlass, 4.5, "Teal comercial sobre cristal de sección")
    }

    @Test
    fun `el coral de marca no porta significado sobre cristal`() {
        // El coral de marca no llega ni a elemento de interfaz: sobre cristal no
        // puede portar significado por sí solo, ni siquiera como icono.
        val marca = contrast(accent, sectionGlass)
        assertTrue(
            marca < 3.0,
            "El coral de marca ha cambiado y ahora sí se distingue sobre cristal " +
                "(${(marca * 100).toInt() / 100.0}:1): revisar dónde puede portar significado",
        )
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
