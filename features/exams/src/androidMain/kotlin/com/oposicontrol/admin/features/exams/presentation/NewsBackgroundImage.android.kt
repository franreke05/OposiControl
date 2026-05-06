package com.oposicontrol.admin.features.exams.presentation

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun NewsBackgroundImage(
    backgroundPath: String,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val bitmap = remember(backgroundPath) {
        runCatching {
            context.contentResolver.openInputStream(Uri.parse(backgroundPath))?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    }
}
