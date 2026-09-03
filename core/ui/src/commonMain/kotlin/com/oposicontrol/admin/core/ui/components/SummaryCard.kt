package com.oposicontrol.admin.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Spacing

/**
 * Tarjeta de KPI.
 *
 * Se ha reducido a la mitad de alto (142dp mínimos → 84dp) y el número ha subido
 * de `titleLarge` a `headlineSmall`. La versión anterior tenía la proporción del
 * revés: una tarjeta enorme con la cifra pequeña dentro, así que una fila de
 * cuatro KPIs se comía la mitad de la pantalla para mostrar cuatro números.
 *
 * En un KPI la jerarquía es siempre la misma: el dato manda, la etiqueta lo
 * nombra y el apoyo lo matiza.
 *
 * `help` añade el icono `i` junto a la etiqueta, para métricas cuyo nombre no
 * basta para saber qué miden.
 */
@Composable
fun SummaryCard(
    title: String,
    value: String,
    supporting: String,
    modifier: Modifier = Modifier,
    help: HelpTopic? = null,
) {
    val tokens = GlassTheme.tokens
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 84.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.surfaceSolid)
            .border(1.dp, tokens.border, RoundedCornerShape(12.dp))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = tokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            help?.let { InfoHelpButton(it) }
        }
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            color = tokens.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (supporting.isNotBlank()) {
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
