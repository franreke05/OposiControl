package com.oposicontrol.admin.core.ui.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import androidx.compose.ui.unit.dp

class AppBreakpointTest {
    @Test
    fun mobileTestWidthsAreCompact() {
        assertEquals(AppBreakpoint.COMPACT, appBreakpointFor(320.dp))
        assertEquals(AppBreakpoint.COMPACT, appBreakpointFor(360.dp))
        assertEquals(AppBreakpoint.COMPACT, appBreakpointFor(390.dp))
        assertEquals(AppBreakpoint.COMPACT, appBreakpointFor(430.dp))
        assertEquals(AppBreakpoint.COMPACT, appBreakpointFor(480.dp))
    }

    @Test
    fun mediumAndExpandedWidthsRemainAvailable() {
        assertEquals(AppBreakpoint.MEDIUM, appBreakpointFor(800.dp))
        assertEquals(AppBreakpoint.EXPANDED, appBreakpointFor(1200.dp))
    }
}
