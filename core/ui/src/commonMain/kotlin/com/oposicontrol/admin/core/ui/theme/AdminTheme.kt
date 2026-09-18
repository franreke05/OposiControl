package com.oposicontrol.admin.core.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.oposicontrol.admin.core.ui.components.GlassBackground
import com.oposicontrol.admin.core.ui.layout.AppBreakpoint
import com.oposicontrol.admin.core.ui.layout.AppLayout
import com.oposicontrol.admin.core.ui.layout.LocalDesktopBreakpoint
import com.oposicontrol.admin.core.ui.layout.ProvideAppLayoutMetrics
import com.oposicontrol.admin.core.ui.layout.appBreakpointFor
import com.oposicontrol.admin.core.ui.layout.desktopBreakpointFor
import com.oposicontrol.admin.core.ui.layout.rememberAppLayoutMetrics
import oposicontrol.core.ui.generated.resources.Res
import oposicontrol.core.ui.generated.resources.manrope_regular
import oposicontrol.core.ui.generated.resources.manrope_semibold
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.Font
import kotlin.math.pow

@Immutable
data class GlassTokens(
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val glassFill: Color,
    val glassFillStrong: Color,
    val glassStroke: Color,
    val glassHighlight: Color,
    val accent: Color,
    val success: Color,
    val warning: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val divider: Color,
    val shadowAmbient: Color,
    val shadowSpot: Color,
    // --- Roles añadidos para escritorio -------------------------------------
    // El escritorio necesita superficies que no dependan de lo que haya detrás:
    // una tabla de 40 filas sobre cristal translúcido es preciosa en una captura
    // e ilegible cuando trabajas ocho horas con ella.
    /** Superficie opaca para tablas, formularios y paneles densos. */
    val surfaceSolid: Color,
    /** Superficie opaca ligeramente hundida: cabeceras de tabla, barras de filtro. */
    val surfaceMuted: Color,
    /** Realce bajo el puntero. Sutil a propósito: confirma, no llama la atención. */
    val surfaceHover: Color,
    /** Fila o item de navegación seleccionado. */
    val surfaceSelected: Color,
    /** Borde de 1dp visible sobre superficie opaca. */
    val border: Color,
    /** Borde de separación fuerte (contorno de tabla, divisorias de shell). */
    val borderStrong: Color,
    /**
     * Borde de un CAMPO de formulario en reposo: lo que dice «aquí se escribe».
     *
     * Existe aparte de [border] porque un campo de escritorio tiene el MISMO
     * relleno que la sección que lo contiene (`surfaceSolid` los dos), así que su
     * única frontera es el borde, y WCAG 1.4.11 pide 3:1 a la frontera de un
     * control. `border` daba 1,25:1 en claro y 1,41:1 en oscuro: el campo se leía
     * como texto suelto en la sección. Subir `border` habría endurecido todas las
     * divisorias y contornos de panel de la aplicación, que no son controles.
     *
     * Por qué el borde y no un relleno propio: contra `#FCFDFE`, un relleno sólo
     * llega a 3:1 si es un gris medio (luminancia ≤ 0,29), que no es un campo.
     */
    val fieldBorder: Color,
    /** Anillo de foco de teclado. Es el mismo coral: el foco es una acción. */
    val focusRing: Color,
    /** Acción destructiva. */
    val danger: Color,
    /** Información neutra (chips de estado desconocido, ayuda). */
    val info: Color,

    // --- Tinta de estado: el CONTENIDO de un chip -----------------------------
    // `success`, `warning`, `danger` e `info` son el color de la FAMILIA: van en
    // puntos, iconos, bordes y en el tinte al 12 % de un chip, donde basta 3:1.
    // Estas cuatro son su tinta: la misma familia con la luz justa para leerse
    // como TEXTO (4,5:1) sobre ese tinte. Es el mismo reparto que `accent` /
    // `accentInk`: el tono identifica, la tinta se lee.
    //
    // Existen porque en claro el tono base no llegaba como texto de chip —verde
    // 2,92:1, ámbar 3,79:1, rojo 4,47:1 y azul 3,77:1 sobre superficie sólida— y
    // oscurecer el tono base habría apagado también los puntos, los iconos y los
    // bordes que ya cumplían. Son tokens y no una derivación con `shade()` porque
    // cada familia necesita una cantidad distinta de oscuridad: la derivación
    // tendría que llevar cuatro constantes en el código, una paleta escondida.
    /** Tinta de éxito: texto e icono de un chip positivo. */
    val successInk: Color,
    /** Tinta de aviso: texto e icono de un chip en atención. */
    val warningInk: Color,
    /** Tinta de peligro: texto e icono de un chip negativo. */
    val dangerInk: Color,
    /** Tinta informativa: texto e icono de un chip neutro. */
    val infoInk: Color,

    /**
     * Violeta: la familia de herramientas de LECTURA del producto —Analítica y
     * OposiTester—.
     *
     * No es un estado: no dice si algo va bien o mal, dice de qué familia es. Se
     * añade porque sin él las seis acciones rápidas del panel se pintaban las
     * seis en coral y el color dejaba de significar nada; con `success`,
     * `info`, `danger`, `warning` y `accentInk` se cubrían cinco de las seis
     * familias y faltaba exactamente ésta.
     *
     * Medido: 6,4:1 sobre `surfaceSolid` y 5,8:1 sobre el cristal de sección, así
     * que aguanta texto y no sólo icono (`ContrastTest`).
     */
    val insight: Color,

    /**
     * Parada más PROFUNDA del degradado atmosférico de escritorio.
     *
     * `backgroundTop` → `backgroundBottom` es un rango de 20 puntos de azul, y en
     * una ventana de 1080 se reparte tan despacio que el fondo se lee plano.
     * Plano no sería un problema estético sino funcional: un panel de cristal
     * sobre un color uniforme se ve exactamente igual que uno opaco, y el nivel 1
     * deja de existir para el ojo.
     *
     * El valor está acotado por arriba, no elegido: `textSecondary` sobre él da
     * 4,67:1, y el encabezado de página pinta su descripción DIRECTAMENTE sobre
     * este fondo. Un azul más profundo se vería mejor y dejaría ese texto por
     * debajo de 4,5:1 (`ContrastTest`).
     */
    val backgroundDeep: Color,

    // --- Cristal de NIVEL 1 -------------------------------------------------
    // El panel de escritorio tiene dos superficies distintas y conviene que los
    // tokens lo digan: el CONTENEDOR de sección deja ver el fondo (nivel 1) y
    // toda superficie que lleva una cifra es opaca (`surfaceSolid`, nivel 2).
    // `GlassSurface` sigue resolviendo a opaco en escritorio y estos tokens no
    // lo contradicen: son para el marco, nunca para lo que va dentro.
    /** Relleno del contenedor de sección. Blanco translúcido: aclara el fondo sin taparlo. */
    val sectionGlass: Color,

    /** Rim del contenedor de sección: azul muy pálido, cierra la forma sobre el degradado. */
    val sectionGlassStroke: Color,

    /**
     * Verde azulado de lo COMERCIAL: suscripciones, planes, productos, tickets.
     *
     * Existe porque la familia comercial no tenía color propio y pedía prestado
     * `accent`, que es el coral de la marca y de la acción primaria. Prestarlo
     * tenía un coste medible: al 12% de alpha, el coral y el rojo de peligro dan
     * (237,208,196) y (237,202,199) — dos tintes que a simple vista son el mismo.
     * En la franja de atención eso ponía «Reportes pendientes» y «Suscripciones
     * que vencen» del mismo color siendo cosas distintas, que es justo lo que un
     * sistema de color existe para evitar.
     *
     * El teal está lo bastante lejos del verde de éxito para no leerse como «todo
     * bien» —una suscripción que vence no es una buena noticia— y da 5,8:1 sobre
     * superficie sólida, así que también vale como texto y no sólo como icono.
     */
    val commerce: Color,

    /**
     * Coral oscurecido: rellenos con texto blanco encima, y texto de enlace.
     *
     * El coral de marca `#FF8A5B` es demasiado claro para sostener texto blanco:
     * medido da 2,32:1, por debajo incluso del 3:1 que WCAG pide a un elemento de
     * interfaz, y muy por debajo del 4,5:1 que corresponde a un texto de 14sp
     * como el de un botón. Es un caso en el que dos cosas que se pidieron —coral
     * `#FF8A5B` y texto blanco— no pueden cumplirse a la vez.
     *
     * La identidad se conserva donde se ve: `accent` sigue siendo el coral exacto
     * en el item activo de la sidebar, indicadores, iconos, bordes y realces. Sólo
     * cambia el relleno del botón primario, que pasa a este tono más profundo del
     * mismo color.
     *
     * **El margen es estrecho, y se conserva a sabiendas.** `#C2521F` da 4,56:1
     * sobre blanco: pasa el umbral AA de texto normal por seis centésimas, y el
     * texto donde se usa mide 14sp. Durante el rediseño del Dashboard se oscureció
     * a `#B04A1B` (5,47:1) para ganar holgura, y **se revirtió por decisión del
     * propietario del producto**: este token es el relleno del botón primario de
     * toda la aplicación, móvil incluido, y el rediseño de una pantalla no
     * justifica mover la marca. El valor canónico es `#C2521F`.
     *
     * Consecuencia que hay que respetar: sobre el **cristal** de un contenedor de
     * sección este coral se queda en ~4,2:1, por debajo del 4,5:1 de texto normal.
     * Ahí sirve para un chevron o un icono —elemento de interfaz, 3:1— pero no
     * para el texto, que va en `textPrimary`. Medido y fijado en `ContrastTest`.
     */
    val accentInk: Color,

    // --- Roles de control apagado -------------------------------------------
    // Los tres existen porque estaban escritos a mano —veinte literales
    // hexadecimales repartidos entre `DesktopButton` y `GlassActionButton`— y un
    // literal claro no tiene tema oscuro: en navy, un botón deshabilitado se
    // dibujaba como un bloque gris claro, más llamativo que los que sí se pueden
    // pulsar. `ContrastTest` los replicaba a sabiendas y decía por qué; ahora los
    // lee, que es la única forma de que los mida de verdad en los dos temas.
    /** Relleno de un control apagado. Opaco: un deshabilitado translúcido deja el texto flotando. */
    val surfaceDisabled: Color,

    /** Texto de un control apagado. Se lee menos, pero se lee: 3:1 como mínimo. */
    val textDisabled: Color,

    /** Borde de un control apagado. Cierra la forma sin insinuar que se puede pulsar. */
    val borderDisabled: Color,

    /**
     * Si esta paleta es la oscura.
     *
     * No es un color y no se pinta: es la DIRECCIÓN del tema, y existe para que
     * el Design System pueda decidir hacia dónde va un realce sin preguntarle al
     * sistema operativo desde dentro de un componente. Oscurecer un relleno bajo
     * el puntero es lo correcto en claro y lo contrario de lo correcto en oscuro,
     * donde el hover tiene que ACLARAR; con este dato, [shade] resuelve las dos
     * direcciones con la misma llamada y ninguna pantalla necesita escribir un
     * `if (oscuro)`.
     */
    val isDark: Boolean,
)

/**
 * Un realce de interacción, en la dirección que corresponda al tema.
 *
 * En claro un hover oscurece; en oscuro, aclara. Es la misma operación
 * conceptual —«separa este control de su estado de reposo»— y por eso es una
 * sola función y no dos ramas escritas en cada componente.
 */
fun GlassTokens.shade(base: Color, amount: Float): Color =
    lerp(base, if (isDark) Color.White else Color.Black, amount)

/**
 * El color de texto que de verdad se lee sobre un relleno.
 *
 * Sustituye al `Color.White` que los botones llevaban escrito a mano. Aquel
 * funcionaba porque en el tema claro todos los rellenos de acción son oscuros;
 * en el oscuro son claros —un coral de 0,47 de luminancia— y el blanco encima da
 * 2,0:1, ilegible. En vez de duplicar la paleta de botones por tema, se elige
 * entre el blanco y la tinta oscura del tema el que más contraste dé, que es la
 * misma decisión que se tomaría a mano y no puede quedarse obsoleta cuando
 * alguien mueva un color.
 *
 * La tinta oscura es [backgroundDeep] en el tema oscuro —el fondo más profundo,
 * que ya está en la paleta— y [textPrimary] en el claro.
 */
fun GlassTokens.contentOn(fill: Color): Color {
    val ink = if (isDark) backgroundDeep else textPrimary
    return if (contrastRatio(Color.White, fill) >= contrastRatio(ink, fill)) Color.White else ink
}

/**
 * El color que hereda un texto que no pide ninguno.
 *
 * Es `textPrimary` en los dos temas. Es una función y no un token suelto en
 * [AdminTheme] para que el test que lo protege compruebe la misma decisión que
 * toma el tema, y no una copia de ella.
 */
internal fun defaultContentColor(tokens: GlassTokens): Color = tokens.textPrimary

/** WCAG 2.1 relative luminance. Vive aquí porque [contentOn] la necesita en producción, no sólo en test. */
private fun Color.relativeLuminance(): Float {
    fun channel(value: Float): Float {
        val c = value.toDouble()
        return (if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)).toFloat()
    }
    return 0.2126f * channel(red) + 0.7152f * channel(green) + 0.0722f * channel(blue)
}

private fun contrastRatio(foreground: Color, background: Color): Float {
    val a = foreground.relativeLuminance()
    val b = background.relativeLuminance()
    return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
}

/**
 * La paleta viva del panel.
 *
 * `internal` y no `private` para que `ContrastTest` pueda LEERLA en vez de
 * replicar sus valores. Mientras los replicaba, el test no protegía nada:
 * cambiar `warning` aquí a un amarillo casi blanco dejaba el test en verde,
 * porque seguía midiendo su propia copia del color viejo.
 */
internal val LightGlassTokens = GlassTokens(
    backgroundTop = Color(0xFFEAF1F8),
    backgroundBottom = Color(0xFFD6E4F2),
    glassFill = Color(0x66FFFFFF),
    glassFillStrong = Color(0x88FFFFFF),
    glassStroke = Color(0x80FFFFFF),
    glassHighlight = Color(0xCCFFFFFF),
    accent = Color(0xFFFF8A5B),
    success = Color(0xFF2E9E6B),
    // Ambar oscurecido: el anterior (#D9920A) daba 2,55:1 sobre superficie clara,
    // ilegible como texto de chip. Este da 4,4:1.
    warning = Color(0xFFA66A00),
    textPrimary = Color(0xFF142230),
    textSecondary = Color(0xFF516173),
    divider = Color(0x33FFFFFF),
    shadowAmbient = Color(0x22000000),
    shadowSpot = Color(0x33000000),
    surfaceSolid = Color(0xFFFCFDFE),
    surfaceMuted = Color(0xFFF1F5FA),
    surfaceHover = Color(0xFFE9EFF7),
    surfaceSelected = Color(0xFFFFF1EA),
    border = Color(0xFFDCE5EF),
    borderStrong = Color(0xFFC3D1E0),
    // El mismo tono que `border`, sólo con menos luz (OKLAB), lo justo para 3:1
    // contra el relleno del campo y contra el cristal por el que pasa el buscador:
    // 3,34:1 sobre `surfaceSolid`, 3,01:1 sobre el cristal en su parada profunda.
    // Es el único token CLARO que cambia en esta pasada, y cambia por la excepción
    // de WCAG 1.4.11 (el borde anterior daba 1,25:1).
    fieldBorder = Color(0xFF848C95),
    focusRing = Color(0xFFFF8A5B),
    danger = Color(0xFFC0392B),
    info = Color(0xFF3E7CB1),
    // Cada tinta es su tono base con MENOS LUZ en OKLAB —mismo tono, mismo croma—
    // y es la mínima que llega a 4,5:1 sobre su tinte en la superficie más
    // exigente, el cristal en su parada profunda: verde 4,54 · ámbar 4,53 · rojo
    // 4,52 · azul 4,55. Con el tono base daban ahí 2,65 · 3,44 · 4,05 · 3,43. El
    // ámbar se queda en #915600 y no en #915700 porque Compose guarda el 12 % del
    // tinte como 31/255: con #915700 el chip se quedaba en 4,49:1.
    successInk = Color(0xFF007545),
    warningInk = Color(0xFF915600),
    dangerInk = Color(0xFFB73023),
    infoInk = Color(0xFF29689C),
    insight = Color(0xFF6D46B8),
    backgroundDeep = Color(0xFFCEDFF0),
    sectionGlass = Color(0x94FFFFFF),
    sectionGlassStroke = Color(0x8C9EBFDE),
    commerce = Color(0xFF0C6E77),
    accentInk = Color(0xFFC2521F),
    surfaceDisabled = Color(0xFFEDEFF2),
    textDisabled = Color(0xFF6B7684),
    borderDisabled = Color(0xFFE2E6EB),
    isDark = false,
)

/**
 * La misma aplicación con la luz apagada.
 *
 * **La regla que gobierna cada valor**: un observador tiene que reconocer *la
 * misma pantalla* en los dos temas, no dos diseños. Por eso ninguna decisión de
 * estructura cambia aquí —el cristal sigue siendo cristal, el dato sigue siendo
 * opaco, la jerarquía de superficies es la misma— y lo único que se invierte es
 * de qué lado del gris está cada nivel.
 *
 * Las cinco decisiones que no se pueden deshacer sin romper el tema:
 *
 *  - **Nunca negro puro.** El fondo más profundo es `#0D141F`, un navy de
 *    grafito. El negro absoluto sobre un panel de datos produce halo alrededor
 *    del texto claro y, en una jornada de trabajo, cansa más que el navy.
 *  - **El dato es opaco y el marco es cristal, y el dato es el más claro de los
 *    dos en AMBOS temas.** Medido en el render de Información básica a 1920: en
 *    claro el marco compone entre `#EFF4F9` y `#F8FAFC` y el dato es `#FCFDFE`
 *    (1,03–1,09:1). En oscuro el marco compone entre `#222B39` (abajo) y
 *    `#2A3443` (arriba del catálogo, sobre el halo) y el dato es `#2A3648`
 *    (1,04–1,20:1). No siempre fue así: el cristal oscuro era un azul pálido al
 *    12 % que los halos y el brillo superior aclaraban hasta `#657488`, por
 *    encima del dato y del texto claro que lleva encima. Ahora es un navy denso
 *    (`sectionGlass`) y el brillo sale del propio cristal (`sectionSheen`), así
 *    que el halo se sigue viendo alrededor de los paneles pero ya no los
 *    enciende. `ContrastTest` mide la separación campo/cristal sobre la parada
 *    profunda (1,11:1 en claro, 1,17:1 en oscuro) y todo TEXTO sobre cristal
 *    contra los dos extremos (`glassBackdrops`).
 *  - **Los inputs, siempre más opacos que el cristal exterior.** Es la razón por
 *    la que `surfaceSolid` no puede acercarse al valor compuesto del cristal: un
 *    campo de texto que se funde con su contenedor deja de parecer un campo, y
 *    con este formulario se trabaja horas.
 *  - **Ningún color de estado es neón.** Verde, ámbar, rojo y azul están
 *    aclarados lo justo para sostener texto de cuerpo sobre superficie opaca
 *    (todos entre 4,7 y 5,9:1) y ni un punto más. Un `#00FF88` cumpliría el
 *    contraste y convertiría el panel en una consola de videojuego.
 *  - **El coral cambia de papel, no de identidad.** `accent` es EXACTAMENTE el
 *    mismo `#FF8A5B` de la marca en los dos temas. Lo que cambia es `accentInk`:
 *    en claro es un coral profundo porque tiene que sostener texto blanco encima
 *    y sobre papel blanco; en oscuro es un coral claro (`#FF9E73`) porque tiene
 *    que leerse COMO texto sobre navy. Los dos papeles no caben en un color:
 *    blanco sobre X pide luminancia ≤0,18 y X sobre navy pide ≥0,28. La
 *    contradicción es aritmética, y la resuelve [contentOn] eligiendo tinta
 *    oscura sobre los rellenos claros del tema oscuro.
 *
 * Efecto secundario que conviene conocer: en oscuro el coral **sí** sostiene
 * texto sobre cristal (7,06:1), así que la excepción del tema claro —enlaces en
 * `textPrimary` porque el coral no llegaba— aquí no hace falta. No se aprovecha
 * para cambiar el color de los enlaces: se dejan igual en los dos temas para que
 * la pantalla sea reconocible, y queda medido en `ContrastTest`.
 */
internal val DarkGlassTokens = GlassTokens(
    backgroundTop = Color(0xFF17212F),
    backgroundBottom = Color(0xFF111926),
    // Cristal navy translúcido: azul muy pálido a baja opacidad. Se define como
    // tinte y no como color sólido para que el degradado atmosférico siga
    // viéndose A TRAVÉS del panel, que es lo único que distingue el nivel 1 del
    // nivel 2 cuando los dos son oscuros.
    glassFill = Color(0x1FBDD5F2),
    // La píldora translúcida que va SOBRE el cristal —contador del catálogo,
    // metadatos de la cabecera, píldoras de estado del panel— bajó de 0x38 (22 %)
    // a 0x2A (16,5 %). Al 22 % aclaraba el cristal lo bastante para dejar el
    // texto secundario de la píldora en 3,93:1 sobre el pico del halo; 0x2A es el
    // alpha más alto que lo deja por encima de 4,5 (4,59; a 0x2C, 4,47). La
    // píldora sigue separándose del cristal (1,49:1; era 1,71).
    glassFillStrong = Color(0x2ABDD5F2),
    glassStroke = Color(0x4D8FB0D6),
    glassHighlight = Color(0x66A9C6E8),
    accent = Color(0xFFFF8A5B),
    // Los cuatro tonos de estado se aclararon LO MÍNIMO —mismo tono y croma en
    // OKLAB, sólo más luz— para que el TEXTO de un chip se lea sobre su propio
    // tinte al 12 %. En oscuro ese tinte aclara el fondo del chip, y así
    // «Archivado» daba 3,92:1 sobre superficie sólida y «Publicado» 4,46:1: el
    // test los medía como elemento (3:1) contra la superficie, sin el tinte.
    // Verde (#4CC38A) y ámbar (#E0A33C) se mueven un punto; rojo y azul, más.
    success = Color(0xFF4EC58C),
    warning = Color(0xFFE1A43D),
    textPrimary = Color(0xFFE9EFF7),
    // Aclarado de #A3B2C6 a #B3C2D6, y es lo mínimo que pasa: mismo tono, +0,05
    // de L en OKLAB. Lo acota el encabezado de página, el único texto que va
    // sobre el fondo DESNUDO y justo donde cae el halo claro: las migas daban
    // 4,04:1 y la descripción 4,29:1 en el render, y en el pico del halo se
    // quedan ahora en 4,5. La jerarquía se conserva: primario/secundario 1,56:1
    // (era 1,86), por encima del 1,5 que fija `ContrastTest`.
    textSecondary = Color(0xFFB3C2D6),
    divider = Color(0x1FFFFFFF),
    // Más profundas que en claro: sobre navy, una sombra al 13% no se ve.
    shadowAmbient = Color(0x66000000),
    shadowSpot = Color(0x80000000),
    surfaceSolid = Color(0xFF2A3648),
    surfaceMuted = Color(0xFF212C3C),
    // En claro el hover OSCURECE y en oscuro ACLARA. Es la misma intención
    // —«el puntero está aquí»— y el mismo salto de luminancia, en sentidos
    // opuestos porque el punto de partida lo es.
    surfaceHover = Color(0xFF33415A),
    surfaceSelected = Color(0xFF38271F),
    // Un punto por encima de la equivalencia aritmética con el tema claro, y a
    // propósito. El borde claro da 1,25:1 sobre su superficie y el primer valor
    // probado aquí daba 1,27 —el mismo número—, pero el ojo separa peor dos tonos
    // contiguos a baja luminancia que a alta: en la captura, el contorno de un
    // input sobre navy se leía como una insinuación. A 1,41 el campo vuelve a
    // tener bordes sin que el borde se convierta en un elemento por sí mismo, y
    // sigue por debajo de `borderStrong` (1,78) para que los dos roles no se
    // confundan.
    //
    // Los CAMPOS ya no usan este token: su frontera es `fieldBorder` (3:1, WCAG
    // 1.4.11), porque 1,41 era poco para lo único que separa un input de una
    // sección del mismo relleno. Éste se queda para divisorias y contornos.
    border = Color(0xFF3D4C67),
    borderStrong = Color(0xFF4A5B78),
    // El mismo tono que `border`, con más luz (OKLAB), lo justo para 3:1 contra el
    // relleno del campo (3,01:1) y contra el cristal en sus dos extremos. Era
    // `border`, 1,41:1.
    fieldBorder = Color(0xFF6E7F9C),
    focusRing = Color(0xFFFF8A5B),
    // Rojo, no coral. Es el mismo problema que `commerce` resolvió en claro: al
    // aclarar un rojo para que se lea sobre navy, se acerca al coral de marca.
    // El primer valor probado (#FB7B6F) quedaba a 37 puntos de `accent` sumando
    // canales —el test de convergencia de familias lo tumbó—, así que el tono se
    // desplaza hacia el rosa. Después se aclaró de #FF7086 a #FF8CA0 —mismo tono,
    // más luz— porque como TEXTO de un chip sobre su propio tinte daba 3,92:1
    // («Archivado», medido en el render). Queda a 71 puntos del coral.
    danger = Color(0xFFFF8CA0),
    // Aclarado de #6AA9DC por lo mismo que el rojo: el chip informativo daba
    // 3,96:1 sobre su tinte en superficie sólida.
    info = Color(0xFF78B7EB),
    // En oscuro el tono ya se lee como texto sobre su propio tinte en todas las
    // superficies (4,5:1 o más, medido en `ContrastTest`): la tinta ES el tono.
    successInk = Color(0xFF4EC58C),
    warningInk = Color(0xFFE1A43D),
    dangerInk = Color(0xFFFF8CA0),
    infoInk = Color(0xFF78B7EB),
    insight = Color(0xFFB49BEC),
    backgroundDeep = Color(0xFF0D141F),
    // Cristal de sección: navy al 80 %, no azul pálido al 12 %.
    //
    // El azul pálido ACLARABA lo que tenía detrás, y sobre el halo claro de
    // arriba a la izquierda —con el brillo del contenedor encima— el marco de un
    // panel llegaba a #657488: el texto claro se quedaba sin contraste (pestaña
    // inactiva 2,94:1, contador del catálogo 3,31:1). Un cristal oscuro no puede
    // dejar que los halos lo aclaren por encima de lo que su texto aguanta.
    //
    // El valor sale de una restricción, no de un gusto: sobre la parada PROFUNDA
    // compone exactamente lo mismo que el anterior (#222B39), así que donde el
    // cristal ya estaba bien no cambia nada; y 0,80 es el alpha MÍNIMO con el que
    // todos los roles de texto que van sobre cristal pasan en el pico del halo.
    // A 0,78–0,79 (que cuantizan al mismo compuesto) el chip de peligro se queda
    // en 4,4986:1 y el informativo en 4,4951:1; a 0,80, 4,54 y 4,58.
    sectionGlass = Color(0xCC273140),
    sectionGlassStroke = Color(0x995C7593),
    commerce = Color(0xFF43C6C0),
    accentInk = Color(0xFFFF9E73),
    surfaceDisabled = Color(0xFF232C3A),
    textDisabled = Color(0xFF7C8CA3),
    borderDisabled = Color(0xFF2C3648),
    isDark = true,
)

private val LocalGlassTokens = staticCompositionLocalOf { LightGlassTokens }

/**
 * Si esta composición se está dibujando como panel de escritorio o como móvil.
 *
 * Existe porque hay componentes que comparten las dos plataformas —botones,
 * campos de texto— y que necesitan medidas distintas por una razón física: 48dp
 * es el mínimo para tocar con el dedo y 40dp es lo correcto para apuntar con un
 * ratón. Un único valor para los dos casos siempre está mal en uno de ellos.
 *
 * Es una divergencia de MEDIDA, no de comportamiento: la lógica sigue siendo la
 * misma en ambas plataformas.
 */
val LocalRendersAsDesktop = staticCompositionLocalOf { false }

object GlassTheme {
    val tokens: GlassTokens
        @Composable get() = LocalGlassTokens.current

    /** true en la ventana de escritorio, false en móvil. */
    val isDesktop: Boolean
        @Composable get() = LocalRendersAsDesktop.current
}

/**
 * Si esta composición se está dibujando con la paleta oscura.
 *
 * Se expone para que una pieza que necesite ELEGIR un recurso —no un color— sepa
 * cuál pedir: un logotipo, una imagen de estado vacío. Para color no hace falta:
 * los tokens ya vienen resueltos, y `if (oscuro) Color(0xFF…)` dentro de una
 * pantalla es exactamente la forma en que un sistema de temas deja de existir.
 */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

/**
 * Cómo se elige el tema.
 *
 * Por defecto, lo que diga el sistema operativo: [isSystemInDarkTheme] es el
 * mecanismo canónico de Compose y el que respeta la decisión que el usuario ya
 * ha tomado una vez para todo su escritorio. El parámetro existe para poder
 * FIJARLO —el banco de pruebas visual captura los dos temas sin depender de la
 * configuración de la máquina que genere las capturas—, no para que cada
 * pantalla se invente el suyo.
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun AdminTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val metrics = rememberAppLayoutMetrics(maxWidth = maxWidth, maxHeight = maxHeight)
        val appBreakpoint = appBreakpointFor(maxWidth)
        val desktopBreakpoint = desktopBreakpointFor(maxWidth)
        val rendersAsDesktop = appBreakpoint == AppBreakpoint.EXPANDED

        ProvideAppLayoutMetrics(metrics) {
            val layout = AppLayout.metrics
            val tokens = if (darkTheme) DarkGlassTokens else LightGlassTokens
            val manrope = FontFamily(
                Font(Res.font.manrope_regular, FontWeight.Normal),
                Font(Res.font.manrope_semibold, FontWeight.SemiBold),
            )
            // Manrope Medium (500) no está en el repo: sólo hay Regular y SemiBold.
            // Compose resuelve un FontWeight.Medium pedido a Regular, así que la
            // jerarquía se construye deliberadamente con esos dos pesos en vez de
            // pedir un Medium que se renderizaría idéntico al cuerpo de texto.
            val typography = remember(rendersAsDesktop, layout) {
                if (rendersAsDesktop) desktopTypography(manrope) else mobileTypography(manrope, layout)
            }
            // Material se alimenta de los MISMOS tokens en los dos temas: sólo
            // cambia la fábrica, para que los componentes de Material3 que el
            // proyecto todavía usa —menús desplegables, diálogos— hereden la
            // paleta correcta en vez de traer sus valores por defecto.
            val colors = if (darkTheme) {
                darkColorScheme(
                    primary = tokens.accent,
                    onPrimary = tokens.contentOn(tokens.accent),
                    primaryContainer = tokens.surfaceSelected,
                    onPrimaryContainer = tokens.accentInk,
                    secondary = tokens.textSecondary,
                    background = tokens.backgroundBottom,
                    onBackground = tokens.textPrimary,
                    surface = tokens.surfaceSolid,
                    surfaceVariant = tokens.surfaceMuted,
                    // Los contenedores de Material —menús, diálogos, hojas— son la
                    // superficie opaca del tema, en el orden de énfasis que espera
                    // Material (en oscuro, más alto = más claro). Sin esto, un
                    // `DropdownMenu` salía con el gris lila por defecto de Material.
                    //
                    // El menú (`surfaceContainer`) y el diálogo (`High`) van un
                    // escalón por ENCIMA del panel, en `surfaceHover`: en navy la
                    // sombra de un popup casi no se ve, y con el mismo `surfaceSolid`
                    // que la sección sobre la que se abre, las opciones del menú se
                    // leían como texto suelto encima del formulario (visto en la
                    // captura). 1,19:1 contra el panel; el texto, 8,9:1.
                    surfaceContainerLowest = tokens.surfaceMuted,
                    surfaceContainerLow = tokens.surfaceSolid,
                    surfaceContainer = tokens.surfaceHover,
                    surfaceContainerHigh = tokens.surfaceHover,
                    surfaceContainerHighest = tokens.surfaceHover,
                    onSurface = tokens.textPrimary,
                    onSurfaceVariant = tokens.textSecondary,
                    outline = tokens.border,
                    outlineVariant = tokens.borderStrong,
                    error = tokens.danger,
                    onError = tokens.contentOn(tokens.danger),
                )
            } else {
                lightColorScheme(
                    primary = tokens.accent,
                    onPrimary = Color.White,
                    primaryContainer = tokens.surfaceSelected,
                    onPrimaryContainer = Color(0xFF8A3B18),
                    secondary = Color(0xFF5A6D83),
                    background = tokens.backgroundBottom,
                    onBackground = tokens.textPrimary,
                    surface = tokens.glassFill,
                    surfaceVariant = tokens.glassFillStrong,
                    // Mismo criterio que en oscuro: los contenedores de Material son
                    // la superficie opaca del tema, en su orden de énfasis (en claro,
                    // más alto = más oscuro). Un `DropdownMenu` salía con el gris
                    // lila por defecto de Material (#F3EDF7). `surface` y
                    // `surfaceVariant` NO se tocan: varias pantallas los usan como
                    // tinte directo y el menú no los necesita.
                    surfaceContainerLowest = tokens.surfaceSolid,
                    surfaceContainerLow = tokens.surfaceSolid,
                    surfaceContainer = tokens.surfaceSolid,
                    surfaceContainerHigh = tokens.surfaceSolid,
                    surfaceContainerHighest = tokens.surfaceMuted,
                    onSurface = tokens.textPrimary,
                    onSurfaceVariant = tokens.textSecondary,
                    outline = tokens.border,
                    outlineVariant = tokens.borderStrong,
                    error = tokens.danger,
                    onError = Color.White,
                )
            }
            CompositionLocalProvider(
                LocalGlassTokens provides tokens,
                LocalDesktopBreakpoint provides desktopBreakpoint,
                LocalRendersAsDesktop provides rendersAsDesktop,
                LocalIsDarkTheme provides darkTheme,
            ) {
                MaterialTheme(
                    colorScheme = colors,
                    typography = typography,
                ) {
                    // El color de texto POR DEFECTO es el del tema, no el negro.
                    //
                    // Material3 no fija `LocalContentColor` en `MaterialTheme`: sólo
                    // lo hace `Surface`, y este proyecto no usa `Surface` de Material.
                    // Sin esto, un `Text` al que nadie pasa `color` —y la tipografía
                    // no lleva color— heredaba el valor por defecto del local, que es
                    // `Color.Black`: medido en el tema oscuro, los títulos de
                    // `FormSection` salían `#000000` sobre `#2A3648`, 1,72:1. En claro
                    // no se notaba porque el negro sobre blanco se lee, pero tampoco
                    // era `textPrimary` (#142230): era un color fuera de la paleta.
                    //
                    // Se provee DENTRO de `MaterialTheme` para que nada entre los dos
                    // lo pise, y con el token de los dos temas.
                    CompositionLocalProvider(LocalContentColor provides defaultContentColor(tokens)) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            GlassBackground(modifier = Modifier.fillMaxSize())
                            content()
                        }
                    }
                }
            }
        }
    }
}
