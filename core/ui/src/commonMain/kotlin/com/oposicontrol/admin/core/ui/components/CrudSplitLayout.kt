package com.oposicontrol.admin.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.layout.AppLayout
import com.oposicontrol.admin.core.ui.layout.AppWindowClass
import com.oposicontrol.admin.core.ui.layout.UiSpacing

@Composable
fun CrudSplitLayout(
    modifier: Modifier = Modifier,
    listPane: @Composable () -> Unit,
    detailPane: @Composable () -> Unit,
) {
    val stacked = AppLayout.metrics.windowClass != AppWindowClass.DesktopWide
    if (stacked) {
        Column(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(UiSpacing.block),
        ) {
            listPane()
            detailPane()
        }
    } else {
        Row(
            modifier = modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(UiSpacing.blockLarge),
        ) {
            Row(modifier = Modifier.width(460.dp)) { listPane() }
            Row(modifier = Modifier.fillMaxWidth()) { detailPane() }
        }
    }
}
