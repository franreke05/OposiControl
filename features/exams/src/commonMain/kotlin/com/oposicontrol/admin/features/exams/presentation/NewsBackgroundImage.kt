package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun NewsBackgroundImage(
    backgroundPath: String,
    modifier: Modifier = Modifier,
)
