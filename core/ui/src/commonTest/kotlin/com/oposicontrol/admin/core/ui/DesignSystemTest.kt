package com.oposicontrol.admin.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.layout.AppWindowClass
import com.oposicontrol.admin.core.ui.layout.UiSizes
import com.oposicontrol.admin.core.ui.layout.appWindowClassForWidth
import com.oposicontrol.admin.core.ui.theme.LightGlassTokens
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesignSystemTest {

    @Test
    fun windowBreakpoints_areExactAndStable() {
        assertEquals(AppWindowClass.Mobile, appWindowClassForWidth(599.dp))
        assertEquals(AppWindowClass.Tablet, appWindowClassForWidth(600.dp))
        assertEquals(AppWindowClass.Tablet, appWindowClassForWidth(839.dp))
        assertEquals(AppWindowClass.DesktopCompact, appWindowClassForWidth(840.dp))
        assertEquals(AppWindowClass.DesktopCompact, appWindowClassForWidth(1_199.dp))
        assertEquals(AppWindowClass.DesktopWide, appWindowClassForWidth(1_200.dp))
    }

    @Test
    fun interactiveControls_meetMinimumTarget() {
        assertTrue(UiSizes.buttonHeight >= 48.dp)
        assertTrue(UiSizes.inputHeight >= 48.dp)
        assertTrue(UiSizes.chipMinHeight >= 44.dp)
        assertTrue(UiSizes.touchTarget >= 48.dp)
    }

    @Test
    fun primaryAction_usesAccessibleTextContrast() {
        val ratio = contrastRatio(
            foreground = LightGlassTokens.textPrimary,
            background = LightGlassTokens.accent,
        )

        assertTrue(ratio >= 4.5, "Primary action contrast was $ratio")
    }
}

private fun contrastRatio(foreground: Color, background: Color): Double {
    val lighter = max(relativeLuminance(foreground), relativeLuminance(background))
    val darker = min(relativeLuminance(foreground), relativeLuminance(background))
    return (lighter + 0.05) / (darker + 0.05)
}

private fun relativeLuminance(color: Color): Double {
    fun channel(value: Float): Double {
        val component = value.toDouble()
        return if (component <= 0.04045) {
            component / 12.92
        } else {
            ((component + 0.055) / 1.055).pow(2.4)
        }
    }

    return (0.2126 * channel(color.red)) +
        (0.7152 * channel(color.green)) +
        (0.0722 * channel(color.blue))
}
