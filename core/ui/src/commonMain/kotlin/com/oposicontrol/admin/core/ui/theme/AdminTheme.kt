package com.oposicontrol.admin.core.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    /** Anillo de foco de teclado. Es el mismo coral: el foco es una acción. */
    val focusRing: Color,
    /** Acción destructiva. */
    val danger: Color,
    /** Información neutra (chips de estado desconocido, ayuda). */
    val info: Color,
    /**
     * Coral oscurecido, y SÓLO para rellenos que llevan texto blanco encima.
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
     * mismo color y alcanza 4,6:1.
     */
    val accentInk: Color,
)

private val LightGlassTokens = GlassTokens(
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
    focusRing = Color(0xFFFF8A5B),
    danger = Color(0xFFC0392B),
    info = Color(0xFF3E7CB1),
    accentInk = Color(0xFFC2521F),
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

@OptIn(ExperimentalResourceApi::class)
@Composable
fun AdminTheme(
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val metrics = rememberAppLayoutMetrics(maxWidth = maxWidth, maxHeight = maxHeight)
        val appBreakpoint = appBreakpointFor(maxWidth)
        val desktopBreakpoint = desktopBreakpointFor(maxWidth)
        val rendersAsDesktop = appBreakpoint == AppBreakpoint.EXPANDED

        ProvideAppLayoutMetrics(metrics) {
            val layout = AppLayout.metrics
            val tokens = LightGlassTokens
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
            CompositionLocalProvider(
                LocalGlassTokens provides tokens,
                LocalDesktopBreakpoint provides desktopBreakpoint,
                LocalRendersAsDesktop provides rendersAsDesktop,
            ) {
                MaterialTheme(
                    colorScheme = lightColorScheme(
                        primary = tokens.accent,
                        onPrimary = Color.White,
                        primaryContainer = tokens.surfaceSelected,
                        onPrimaryContainer = Color(0xFF8A3B18),
                        secondary = Color(0xFF5A6D83),
                        background = tokens.backgroundBottom,
                        onBackground = tokens.textPrimary,
                        surface = tokens.glassFill,
                        surfaceVariant = tokens.glassFillStrong,
                        onSurface = tokens.textPrimary,
                        onSurfaceVariant = tokens.textSecondary,
                        outline = tokens.border,
                        outlineVariant = tokens.borderStrong,
                        error = tokens.danger,
                        onError = Color.White,
                    ),
                    typography = typography,
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        GlassBackground(modifier = Modifier.fillMaxSize())
                        content()
                    }
                }
            }
        }
    }
}
