package com.oposicontrol.admin.features.news.presentation

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

@Composable
internal actual fun rememberNewsImagePicker(onPicked: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let { onPicked(it.toString()) } }
    return { launcher.launch("image/*") }
}
@Composable
internal actual fun NewsAssetImage(path: String, modifier: Modifier) {
    val context = LocalContext.current
    val result by produceState<Pair<Boolean, ImageBitmap?>>(true to null, path) {
        value = true to null
        value = false to withContext(Dispatchers.IO) { runCatching {
            val stream = when {
                path.startsWith("content://") -> context.contentResolver.openInputStream(Uri.parse(path))!!
                path.startsWith("https://") -> URL(path).openConnection().apply { connectTimeout = 5000; readTimeout = 10000 }.getInputStream()
                else -> File(path.removePrefix("file://")).inputStream()
            }
            stream.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
        }.getOrNull() }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        result.second?.let { Image(it, "Imagen de noticia", Modifier.matchParentSize(), contentScale = ContentScale.Crop) } ?: Text(if (result.first) "Cargando imagen…" else "Imagen no disponible")
    }
}
