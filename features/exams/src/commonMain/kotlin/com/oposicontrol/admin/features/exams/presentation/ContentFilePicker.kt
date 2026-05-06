package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.runtime.Composable

data class PickedFileReference(
    val uri: String,
    val name: String,
    val mimeType: String?,
)

data class PickedTextFile(
    val name: String,
    val content: String,
)

class ContentFilePickerLauncher(
    private val openPicker: () -> Unit,
) {
    fun open() = openPicker()
}

@Composable
expect fun rememberContentFilePickerLauncher(
    mimeType: String = "*/*",
    onPicked: (PickedFileReference) -> Unit,
): ContentFilePickerLauncher

@Composable
expect fun rememberTextFilePickerLauncher(
    onPicked: (PickedTextFile) -> Unit,
): ContentFilePickerLauncher
