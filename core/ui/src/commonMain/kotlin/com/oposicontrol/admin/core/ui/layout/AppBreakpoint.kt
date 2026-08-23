package com.oposicontrol.admin.core.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class AppBreakpoint { COMPACT, MEDIUM, EXPANDED }

fun appBreakpointFor(width: Dp): AppBreakpoint = when {
    width < 720.dp -> AppBreakpoint.COMPACT
    width < 1040.dp -> AppBreakpoint.MEDIUM
    else -> AppBreakpoint.EXPANDED
}

@Composable
fun AppBreakpoint.isCompactOrMedium(): Boolean = this != AppBreakpoint.EXPANDED
