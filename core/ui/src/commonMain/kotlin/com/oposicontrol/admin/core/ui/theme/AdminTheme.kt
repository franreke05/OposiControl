package com.oposicontrol.admin.core.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oposicontrol.admin.core.ui.components.GlassBackground
import com.oposicontrol.admin.core.ui.layout.ProvideAppLayoutMetrics
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
    val slate: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val error: Color,
    val divider: Color,
    val shadowAmbient: Color,
    val shadowSpot: Color,
)

val LightGlassTokens = GlassTokens(
    backgroundTop = Color(0xFFEAF1F8),
    backgroundBottom = Color(0xFFD6E4F2),
    glassFill = Color(0x66FFFFFF),
    glassFillStrong = Color(0x88FFFFFF),
    glassStroke = Color(0x80FFFFFF),
    glassHighlight = Color(0xCCFFFFFF),
    accent = Color(0xFFFF8A5B),
    slate = Color(0xFF5A6D83),
    textPrimary = Color(0xFF142230),
    textSecondary = Color(0xFF516173),
    error = Color(0xFFB84B3B),
    divider = Color(0x33FFFFFF),
    shadowAmbient = Color(0x1F516173),
    shadowSpot = Color(0x2B516173),
)

private val LocalGlassTokens = staticCompositionLocalOf { LightGlassTokens }

object GlassTheme {
    val tokens: GlassTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalGlassTokens.current
}

@OptIn(ExperimentalResourceApi::class)
@Composable
fun AdminTheme(
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val metrics = rememberAppLayoutMetrics(maxWidth = maxWidth, maxHeight = maxHeight)
        ProvideAppLayoutMetrics(metrics) {
            val tokens = LightGlassTokens
            val manrope = FontFamily(
                Font(Res.font.manrope_regular, FontWeight.Normal),
                Font(Res.font.manrope_semibold, FontWeight.SemiBold),
            )
            val typography = Typography(
                displayLarge = TextStyle(
                    fontFamily = manrope,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 28.sp,
                    lineHeight = 36.sp,
                    letterSpacing = (-0.4).sp,
                ),
                displayMedium = TextStyle(
                    fontFamily = manrope,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 28.sp,
                    lineHeight = 36.sp,
                ),
                displaySmall = TextStyle(
                    fontFamily = manrope,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 28.sp,
                    lineHeight = 36.sp,
                ),
                headlineLarge = TextStyle(
                    fontFamily = manrope,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 28.sp,
                    lineHeight = 36.sp,
                ),
                headlineMedium = TextStyle(
                    fontFamily = manrope,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                    lineHeight = 28.sp,
                ),
                headlineSmall = TextStyle(
                    fontFamily = manrope,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                    lineHeight = 28.sp,
                ),
                titleLarge = TextStyle(
                    fontFamily = manrope,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                    lineHeight = 28.sp,
                ),
                titleMedium = TextStyle(
                    fontFamily = manrope,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                ),
                titleSmall = TextStyle(
                    fontFamily = manrope,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                ),
                bodyLarge = TextStyle(
                    fontFamily = manrope,
                    fontWeight = FontWeight.Normal,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                ),
                bodyMedium = TextStyle(
                    fontFamily = manrope,
                    fontWeight = FontWeight.Normal,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                ),
                bodySmall = TextStyle(
                    fontFamily = manrope,
                    fontWeight = FontWeight.Normal,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                ),
                labelLarge = TextStyle(
                    fontFamily = manrope,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                ),
                labelMedium = TextStyle(
                    fontFamily = manrope,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                ),
                labelSmall = TextStyle(
                    fontFamily = manrope,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                ),
            )
            val colorScheme = lightColorScheme(
                    primary = tokens.accent,
                    onPrimary = tokens.textPrimary,
                    primaryContainer = Color(0xFFFFE1D5),
                    onPrimaryContainer = tokens.textPrimary,
                    inversePrimary = Color(0xFFFFB79B),
                    secondary = tokens.slate,
                    onSecondary = Color.White,
                    secondaryContainer = Color(0xFFDDE5ED),
                    onSecondaryContainer = tokens.textPrimary,
                    tertiary = tokens.textSecondary,
                    onTertiary = Color.White,
                    tertiaryContainer = Color(0xFFDFE6ED),
                    onTertiaryContainer = tokens.textPrimary,
                    background = tokens.backgroundBottom,
                    onBackground = tokens.textPrimary,
                    surface = Color(0xFFF3F7FB),
                    onSurface = tokens.textPrimary,
                    surfaceVariant = Color(0xFFE1EAF2),
                    onSurfaceVariant = tokens.textSecondary,
                    surfaceTint = tokens.accent,
                    inverseSurface = tokens.textPrimary,
                    inverseOnSurface = tokens.backgroundTop,
                    error = tokens.error,
                    onError = Color.White,
                    errorContainer = Color(0xFFF9DFDB),
                    onErrorContainer = Color(0xFF5B1E17),
                    outline = Color(0xFF6D7E90),
                    outlineVariant = Color(0xFFBCC9D6),
                    scrim = Color(0x99000000),
                )
            val shapes = Shapes(
                extraSmall = RoundedCornerShape(8.dp),
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(16.dp),
                large = RoundedCornerShape(20.dp),
                extraLarge = RoundedCornerShape(24.dp),
            )
            CompositionLocalProvider(LocalGlassTokens provides tokens) {
                MaterialTheme(
                    colorScheme = colorScheme,
                    typography = typography,
                    shapes = shapes,
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
