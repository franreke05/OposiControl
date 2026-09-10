package com.oposicontrol.admin.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesignTokensTest {

    @Test
    fun `los botones de escritorio caben en la escala acordada`() {
        // §13: compacto 32, normal 40, grande 44-48. Nada de botones de 60dp.
        assertEquals(32.dp, Sizing.buttonCompact)
        assertEquals(40.dp, Sizing.buttonNormal)
        assertTrue(Sizing.buttonLarge in 44.dp..48.dp)
    }

    @Test
    fun `la sidebar no invade el espacio de trabajo`() {
        // §57: desplegada 220-260, plegada 64-76.
        assertTrue(Sizing.sidebarExpanded in 220.dp..260.dp, "sidebar desplegada fuera de rango")
        assertTrue(Sizing.sidebarCollapsed in 64.dp..76.dp, "sidebar plegada fuera de rango")
    }

    @Test
    fun `la cabecera no es una franja gigante`() {
        // §58: 56-64dp.
        assertTrue(Sizing.headerHeight in 56.dp..64.dp)
    }

    @Test
    fun `las filas de tabla son compactas pero pinchables`() {
        // §22: 44-52dp de alto de fila.
        assertTrue(Sizing.tableRowHeight in 44.dp..52.dp)
        assertTrue(Sizing.tableHeaderHeight <= Sizing.tableRowHeight, "la cabecera no debe ser más alta que una fila")
    }

    @Test
    fun `el margen de pantalla crece de forma monotona`() {
        assertTrue(Spacing.screenCompact < Spacing.screenStandard)
        assertTrue(Spacing.screenStandard < Spacing.screenWide)
        assertTrue(Spacing.screenWide <= 32.dp)
    }

    @Test
    fun `la escala de espaciado no tiene valores sueltos`() {
        // El sistema es 4/8/12/16/20/24/32/40/48: cualquier valor debe ser
        // múltiplo de 4, o vuelven los 18/22/30dp inventados por pantalla.
        val escala = listOf(
            Spacing.xxs, Spacing.xs, Spacing.sm, Spacing.md, Spacing.lg,
            Spacing.xl, Spacing.xxl, Spacing.xxxl, Spacing.huge,
        )
        escala.forEach { valor ->
            assertEquals(0f, valor.value % 4f, "$valor no es múltiplo de 4")
        }
        assertEquals(escala.sortedBy { it.value }, escala, "la escala debe ser creciente")
    }

    @Test
    fun `el radio de tarjeta de dato tiene token propio`() {
        // El 12dp de SummaryCard, GlassSurface-escritorio y ErrorStateCard estaba
        // escrito a mano en tres sitios porque la escala saltaba de 10 a 14.
        // Ahora tiene nombre, y queda entre los dos.
        assertEquals(RoundedCornerShape(12.dp), Radius.card)
        assertEquals(RoundedCornerShape(10.dp), Radius.md, "md no debe moverse: es aditivo")
        assertEquals(RoundedCornerShape(14.dp), Radius.lg, "lg no debe moverse: es aditivo")
    }

    @Test
    fun `los anchos de formulario evitan campos de una pantalla entera`() {
        // §29: texto 320-480, descripción 600-720.
        assertTrue(Sizing.inputTextMaxWidth in 320.dp..480.dp)
        assertTrue(Sizing.inputDescriptionMaxWidth in 600.dp..720.dp)
        // §65: la columna del login, topada.
        assertTrue(Sizing.formColumnMaxWidth in 420.dp..480.dp)
    }
}
