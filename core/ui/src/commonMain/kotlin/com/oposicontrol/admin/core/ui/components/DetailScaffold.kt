package com.oposicontrol.admin.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.theme.GlassTheme

/**
 * Tarjeta de sección con título en acento, para una pantalla de detalle
 * (informe, reporte de OposiTester...). Estaba escrita dos veces, byte a byte.
 */
@Composable
fun DetailSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = GlassTheme.tokens.accent,
            )
            content()
        }
    }
}

/**
 * Esqueleto de una pantalla de detalle (cabecera + cuerpo + pie). Estaba
 * escrito dos veces, byte a byte. Distinto de [LoadingList] (3 filas iguales
 * para un listado): aquí las filas son 2/3/2 porque simulan cabecera, cuerpo y
 * pie, no elementos repetidos de una lista.
 */
@Composable
fun LoadingDetail() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LoadingStateCard(rows = 2)
        LoadingStateCard(rows = 3)
        LoadingStateCard(rows = 2)
    }
}

/**
 * Píldora de estado de una pantalla de detalle: punto + etiqueta sobre cristal
 * teñido con [color]. La carcasa estaba escrita dos veces, byte a byte; el
 * mapeo de cada estado a su color se queda en cada pantalla porque los
 * vocabularios de estado son distintos (uno de ellos tiene un estado más) y
 * decidir su color es una decisión de esa pantalla, no de este componente.
 */
@Composable
fun DetailStatusPill(label: String, color: Color) {
    GlassSurface(
        modifier = Modifier.defaultMinSize(minHeight = 32.dp),
        shape = RoundedCornerShape(16.dp),
        tint = color.copy(alpha = 0.16f),
        strokeColor = color.copy(alpha = 0.45f),
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = color)
        }
    }
}
