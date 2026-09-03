package com.oposicontrol.admin.core.ui.layout

import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.theme.Spacing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopBreakpointTest {

    @Test
    fun `las resoluciones objetivo caen en el regimen esperado`() {
        assertEquals(DesktopBreakpoint.STANDARD, desktopBreakpointFor(1366.dp))
        assertEquals(DesktopBreakpoint.STANDARD, desktopBreakpointFor(1440.dp))
        assertEquals(DesktopBreakpoint.WIDE, desktopBreakpointFor(1600.dp))
        assertEquals(DesktopBreakpoint.WIDE, desktopBreakpointFor(1920.dp))
        assertEquals(DesktopBreakpoint.WIDE, desktopBreakpointFor(2560.dp))
    }

    @Test
    fun `una ventana reducida a mano baja a compacto`() {
        assertEquals(DesktopBreakpoint.COMPACT, desktopBreakpointFor(900.dp))
        assertEquals(DesktopBreakpoint.COMPACT, desktopBreakpointFor(1099.dp))
        assertEquals(DesktopBreakpoint.STANDARD, desktopBreakpointFor(1100.dp))
    }

    @Test
    fun `el limite entre estandar y ancho no deja hueco`() {
        assertEquals(DesktopBreakpoint.STANDARD, desktopBreakpointFor(1499.dp))
        assertEquals(DesktopBreakpoint.WIDE, desktopBreakpointFor(1500.dp))
    }

    @Test
    fun `el margen exterior crece con la ventana`() {
        assertEquals(Spacing.screenCompact, DesktopBreakpoint.COMPACT.screenPadding)
        assertEquals(Spacing.screenStandard, DesktopBreakpoint.STANDARD.screenPadding)
        assertEquals(Spacing.screenWide, DesktopBreakpoint.WIDE.screenPadding)
    }

    @Test
    fun `caben mas KPIs por fila cuanto mas ancha es la ventana`() {
        val columns = DesktopBreakpoint.entries.map { it.kpiColumns }
        assertEquals(columns.sorted(), columns, "kpiColumns debe crecer de COMPACT a WIDE")
        assertEquals(2, DesktopBreakpoint.COMPACT.kpiColumns)
    }

    @Test
    fun `el master-detail solo se ofrece cuando hay ancho para las dos columnas`() {
        assertFalse(DesktopBreakpoint.COMPACT.supportsMasterDetail)
        assertTrue(DesktopBreakpoint.STANDARD.supportsMasterDetail)
        assertTrue(DesktopBreakpoint.WIDE.supportsMasterDetail)
    }
}
