package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.runtime.Composable

@Composable
actual fun rememberContentFilePickerLauncher(
    mimeType: String,
    onPicked: (PickedFileReference) -> Unit,
): ContentFilePickerLauncher = ContentFilePickerLauncher {}

@Composable
actual fun rememberTextFilePickerLauncher(
    onPicked: (PickedTextFile) -> Unit,
): ContentFilePickerLauncher = ContentFilePickerLauncher {}
