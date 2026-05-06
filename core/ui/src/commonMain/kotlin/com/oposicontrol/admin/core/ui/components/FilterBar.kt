package com.oposicontrol.admin.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
