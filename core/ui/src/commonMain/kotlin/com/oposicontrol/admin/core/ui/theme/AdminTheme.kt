package com.oposicontrol.admin.core.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.oposicontrol.admin.core.ui.components.GlassBackground
import com.oposicontrol.admin.core.ui.layout.AppLayout
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
    val textPrimary: Color,
    val textSecondary: Color,
    val divider: Color,
    val shadowAmbient: Color,
    val shadowSpot: Color,
)

private val LightGlassTokens = GlassTokens(
    backgroundTop = Color(0xFFEAF1F8),
    backgroundBottom = Color(0xFFD6E4F2),
    glassFill = Color(0x66FFFFFF),
    glassFillStrong = Color(0x88FFFFFF),
    glassStroke = Color(0x80FFFFFF),
    glassHighlight = Color(0xCCFFFFFF),
    accent = Color(0xFFFF8A5B),
    textPrimary = Color(0xFF142230),
    textSecondary = Color(0xFF516173),
    divider = Color(0x33FFFFFF),
    shadowAmbient = Color(0x22000000),
    shadowSpot = Color(0x33000000),
)

private var currentGlassTokens: GlassTokens = LightGlassTokens

object GlassTheme {
    val tokens: GlassTokens
        @Composable get() = currentGlassTokens
}

@OptIn(ExperimentalResourceApi::class)
@Composable
fun AdminTheme(
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val metrics = rememberAppLayoutMetrics(maxWidth = maxWidth, maxHeight = maxHeight)
        ProvideAppLayoutMetrics(metrics) {
            val layout = AppLayout.metrics
            val tokens = LightGlassTokens
            val manrope = FontFamily(
                Font(Res.font.manrope_regular, FontWeight.Normal),
                Font(Res.font.manrope_semibold, FontWeight.SemiBold),
            )
            val typography = Typography(
                displayLarge = TextStyle(
                    fontFamily = manrope,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = layout.text(32.sp, 28.sp),
                    letterSpacing = (-0.5f * layout.contentScale).sp,
                ),
                headlineSmall = TextStyle(
                    fontFamily = manrope,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = layout.text(24.sp, 21.sp),
                ),
                titleLarge = TextStyle(
                    fontFamily = manrope,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = layout.text(20.sp, 18.sp),
                ),
                titleMedium = TextStyle(
                    fontFamily = manrope,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = layout.text(16.sp, 15.sp),
                ),
                bodyLarge = TextStyle(
                    fontFamily = manrope,
                    fontWeight = FontWeight.Normal,
                    fontSize = layout.text(15.sp, 14.sp),
                ),
                bodyMedium = TextStyle(
                    fontFamily = manrope,
                    fontWeight = FontWeight.Normal,
                    fontSize = layout.text(13.sp, 12.sp),
                ),
                bodySmall = TextStyle(
                    fontFamily = manrope,
                    fontWeight = FontWeight.Normal,
                    fontSize = layout.text(12.sp, 11.sp),
                ),
                labelLarge = TextStyle(
                    fontFamily = manrope,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = layout.text(12.sp, 11.sp),
                ),
                labelMedium = TextStyle(
                    fontFamily = manrope,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = layout.text(11.sp, 10.sp),
                ),
            )
            currentGlassTokens = tokens
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = tokens.accent,
                    onPrimary = Color.White,
                    secondary = Color(0xFF5A6D83),
                    background = tokens.backgroundBottom,
                    onBackground = tokens.textPrimary,
                    surface = tokens.glassFill,
                    surfaceVariant = tokens.glassFillStrong,
                    onSurface = tokens.textPrimary,
                    onSurfaceVariant = tokens.textSecondary,
                    error = Color(0xFFB84B3B),
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
