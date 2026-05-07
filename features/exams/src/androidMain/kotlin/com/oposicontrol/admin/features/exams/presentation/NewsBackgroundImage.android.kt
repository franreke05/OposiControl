package com.oposicontrol.admin.features.exams.presentation

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

@Composable
actual fun NewsBackgroundImage(
    backgroundPath: String,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val bitmapState = produceState<android.graphics.Bitmap?>(initialValue = null, backgroundPath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (backgroundPath.startsWith("http://") || backgroundPath.startsWith("https://")) {
                    URL(backgroundPath).openStream().use { input -> BitmapFactory.decodeStream(input) }
                } else {
                    context.contentResolver.openInputStream(Uri.parse(backgroundPath))?.use { input ->
                        BitmapFactory.decodeStream(input)
                    }
                }
            }
                .getOrNull()
        }
    }
    val bitmap = bitmapState.value
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    }
}
