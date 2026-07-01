package com.oposicontrol.admin.features.exams.presentation

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberContentFilePickerLauncher(
    mimeType: String,
    onPicked: (PickedFileReference) -> Unit,
): ContentFilePickerLauncher {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            onPicked(
                PickedFileReference(
                    uri = uri.toString(),
                    name = context.displayName(uri),
                    mimeType = context.contentResolver.getType(uri),
                ),
            )
        }
    }
    return ContentFilePickerLauncher { launcher.launch(mimeType) }
}

@Composable
actual fun rememberTextFilePickerLauncher(
    onPicked: (PickedTextFile) -> Unit,
): ContentFilePickerLauncher {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val name = context.displayName(uri)
            val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (content != null) onPicked(PickedTextFile(name = name, content = content))
        }
    }
    return ContentFilePickerLauncher { launcher.launch("text/*") }
}

private fun android.content.Context.displayName(uri: Uri): String {
    return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    } ?: uri.lastPathSegment ?: "archivo"
}
