package com.oposicontrol.admin.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.layout.DesktopBreakpoint
import com.oposicontrol.admin.core.ui.layout.desktopBreakpointFor
import com.oposicontrol.admin.core.ui.layout.supportsMasterDetail
import com.oposicontrol.admin.core.ui.theme.Spacing

/**
 * Lista a la izquierda, detalle a la derecha.
 *
 * El reparto es por peso, no por un ancho fijo. Antes la lista medía 460dp
 * exactos: en 1366 se comía un tercio de la pantalla y en 2560 se quedaba
 * ridícula al lado de un detalle de 2100dp. Con pesos, la proporción se mantiene
 * y el `widthIn` sólo evita los dos extremos absurdos.
 *
 * Por debajo de 1100dp se apila: dos columnas ahí no dejan sitio para trabajar en
 * ninguna de las dos.
 */
@Composable
fun CrudSplitLayout(
    modifier: Modifier = Modifier,
    listPane: @Composable () -> Unit,
    detailPane: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val breakpoint = desktopBreakpointFor(maxWidth)
        if (!breakpoint.supportsMasterDetail) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                listPane()
                detailPane()
            }
        } else {
            // En pantallas anchas el detalle se lleva más sitio: la lista sólo
            // necesita ser navegable, el detalle es donde se trabaja.
            val listWeight = if (breakpoint == DesktopBreakpoint.WIDE) 0.32f else 0.4f
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                Column(
                    modifier = Modifier
                        .weight(listWeight)
                        .widthIn(min = 280.dp, max = 560.dp)
                        .fillMaxSize(),
                ) {
                    listPane()
                }
                Column(modifier = Modifier.weight(1f - listWeight).fillMaxSize()) {
                    detailPane()
                }
            }
        }
    }
}
