package com.oposicontrol.admin.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

data class AdminTableColumn(
    val label: String,
    val weight: Float = 1f,
)

data class AdminTableRow(
    val key: String,
    val cells: List<String>,
    val selected: Boolean = false,
    val supporting: String? = null,
)

@Composable
fun AdminTable(
    columns: List<AdminTableColumn>,
    rows: List<AdminTableRow>,
    modifier: Modifier = Modifier,
    onRowClick: ((String) -> Unit)? = null,
) {
    GlassSurface(modifier = modifier.fillMaxWidth()) {
        val scrollState = rememberScrollState()
        val columnWidths = columns.map { ((it.weight * 172f).dp).coerceAtLeast(118.dp) }
        val tableWidth = columnWidths.fold(0.dp) { total, width -> total + width } + ((columns.size - 1).coerceAtLeast(0) * 14).dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState),
        ) {
            Row(
                modifier = Modifier
                    .width(tableWidth)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                columns.forEachIndexed { index, column ->
                    Text(
                        text = column.label,
                        modifier = Modifier.width(columnWidths[index]),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HorizontalDivider()
            LazyColumn {
                items(rows, key = { it.key }) { row ->
                    Column(
                        modifier = Modifier
                            .width(tableWidth)
                            .heightIn(min = 58.dp)
                            .background(
                                color = if (row.selected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                            )
                            .then(
                                if (onRowClick != null) {
                                    Modifier.clickable { onRowClick(row.key) }
                                } else {
                                    Modifier
                                },
                            )
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            row.cells.forEachIndexed { index, cell ->
                                Text(
                                    text = cell,
                                    modifier = Modifier.width(columnWidths.getOrElse(index) { 118.dp }),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (row.selected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (row.supporting != null) {
                            Text(
                                text = row.supporting,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
