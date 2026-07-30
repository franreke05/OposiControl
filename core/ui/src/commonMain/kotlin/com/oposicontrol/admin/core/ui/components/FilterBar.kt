package com.oposicontrol.admin.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.layout.AppLayout
import com.oposicontrol.admin.core.ui.layout.AppWindowClass
import com.oposicontrol.admin.core.ui.layout.UiSpacing

@Composable
fun FilterBar(
    searchValue: String,
    onSearchChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    filters: @Composable () -> Unit = {},
) {
    val stackFilters = AppLayout.metrics.windowClass == AppWindowClass.Mobile ||
        AppLayout.metrics.windowClass == AppWindowClass.Tablet
    GlassSurface(modifier = modifier.fillMaxWidth()) {
        if (stackFilters) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = UiSpacing.normal, vertical = UiSpacing.compact),
                verticalArrangement = Arrangement.spacedBy(UiSpacing.compact),
            ) {
                GlassSearchBar(
                    value = searchValue,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = placeholder,
                    onValueChange = onSearchChange,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(UiSpacing.compact),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    filters()
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = UiSpacing.normal, vertical = UiSpacing.compact),
                horizontalArrangement = Arrangement.spacedBy(UiSpacing.normal),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassSearchBar(
                    value = searchValue,
                    modifier = Modifier.width(420.dp),
                    placeholder = placeholder,
                    onValueChange = onSearchChange,
                )
                Row(
                    modifier = Modifier.wrapContentWidth(),
                    horizontalArrangement = Arrangement.spacedBy(UiSpacing.compact),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    filters()
                }
            }
        }
    }
}
