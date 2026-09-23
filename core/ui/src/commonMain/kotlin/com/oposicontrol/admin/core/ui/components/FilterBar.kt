package com.oposicontrol.admin.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import oposicontrol.core.ui.generated.resources.Res
import oposicontrol.core.ui.generated.resources.ic_filter

@Composable
fun FilterBar(
    searchValue: String,
    onSearchChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    filters: @Composable () -> Unit = {},
) {
    GlassSurface(modifier = modifier.fillMaxWidth()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            if (maxWidth < 620.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassSearchBar(
                        value = searchValue,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = placeholder,
                        onValueChange = onSearchChange,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        filters()
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassSearchBar(
                        value = searchValue,
                        modifier = Modifier.weight(1f),
                        placeholder = placeholder,
                        onValueChange = onSearchChange,
                    )
                    Row(
                        modifier = Modifier.wrapContentWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        filters()
                    }
                }
            }
        }
    }
}

/**
 * Botón cuadrado de 48dp para abrir un panel de filtros, con marca visual de
 * "hay filtros activos". Estaba escrito tres veces —Foro, OposiTester,
 * Informes—, byte a byte salvo el comentario: como hijo no ponderado de una
 * `Row` no ponderada, un tamaño exacto evita la ambigüedad de alto no acotado
 * que tendría un `fillMaxSize()` dentro de `GlassSurface` con sólo una
 * restricción mínima.
 */
@Composable
fun FilterIconButton(active: Boolean, onClick: () -> Unit) {
    GlassSurface(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = if (active) "Filtro, con filtros aplicados" else "Filtro"
            },
        shape = RoundedCornerShape(16.dp),
        tint = if (active) GlassTheme.tokens.accent.copy(alpha = 0.16f) else GlassTheme.tokens.glassFill,
        strokeColor = if (active) GlassTheme.tokens.accent.copy(alpha = 0.45f) else GlassTheme.tokens.glassStroke,
        shadowElevation = 0.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(Res.drawable.ic_filter),
                contentDescription = null,
                tint = if (active) GlassTheme.tokens.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Esqueleto de tres filas mientras carga un listado. Estaba escrito tres
 * veces —Foro, OposiTester, Informes—, byte a byte idéntico.
 */
@Composable
fun LoadingList() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LoadingStateCard(rows = 3)
        LoadingStateCard(rows = 3)
        LoadingStateCard(rows = 3)
    }
}
