package com.oposicontrol.admin.features.news.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.config.MediaUrlPolicy
import org.koin.mp.KoinPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
internal actual fun rememberNewsImagePicker(onPicked: (String) -> Unit): () -> Unit {
    val callback by rememberUpdatedState(onPicked)
    return remember { {
        SwingUtilities.invokeLater {
            val chooser = JFileChooser().apply { fileFilter = FileNameExtensionFilter("Imágenes PNG, JPG, WEBP", "png", "jpg", "jpeg", "webp") }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) callback("file://" + chooser.selectedFile.absolutePath.replace('\\', '/'))
        }
    } }
}

@Composable
internal actual fun NewsAssetImage(path: String, modifier: Modifier) {
    val result by produceState<Pair<Boolean, ImageBitmap?>>(true to null, path) {
        value = true to null
        value = false to withContext(Dispatchers.IO) { runCatching {
            val bytes = if (path.startsWith("https://")) {
                // INV-NETWORK-COMPLETE: public Storage of the configured host only; no redirects.
                require(MediaUrlPolicy.isAllowedRemoteMedia(path, KoinPlatform.getKoin().getOrNull<AppConfig>()?.supabaseUrl.orEmpty()))
                val connection = URI(path).toURL().openConnection().apply {
                    connectTimeout = 5000; readTimeout = 10000
                    (this as? java.net.HttpURLConnection)?.instanceFollowRedirects = false
                }
                connection.getInputStream().use { it.readNBytes(10 * 1024 * 1024 + 1) }
            } else {
                val file = File(path.removePrefix("file://"))
                require(file.length() <= 10 * 1024 * 1024)
                file.readBytes()
            }
            require(bytes.size <= 10 * 1024 * 1024)
            org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
        }.getOrNull() }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        result.second?.let { Image(it, "Imagen de noticia", Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
            ?: Text(if (result.first) "Cargando imagen…" else "Imagen no disponible")
    }
}
