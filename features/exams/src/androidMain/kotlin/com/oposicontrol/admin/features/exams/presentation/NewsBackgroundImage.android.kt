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
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.config.MediaUrlPolicy
import org.koin.mp.KoinPlatform
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
                    // INV-NETWORK-COMPLETE: https public Storage of the configured host only; no redirects, with timeouts.
                    if (!MediaUrlPolicy.isAllowedRemoteMedia(backgroundPath, KoinPlatform.getKoin().getOrNull<AppConfig>()?.supabaseUrl.orEmpty())) return@runCatching null
                    val connection = (URL(backgroundPath).openConnection() as java.net.HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        connectTimeout = 5000
                        readTimeout = 10000
                    }
                    connection.inputStream.use { input -> BitmapFactory.decodeStream(input) }
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
