package com.oposicontrol.admin.features.questions.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.config.MediaUrlPolicy
import java.awt.Desktop
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform

private const val MAX_PREVIEW_BYTES = 10 * 1024 * 1024

private fun storageAllows(url: String): Boolean =
    MediaUrlPolicy.isOpenableStorageObject(url, KoinPlatform.getKoinOrNull()?.getOrNull<AppConfig>())

@Composable
internal actual fun QuestionResourceImagePreview(url: String, modifier: Modifier) {
    // null = cargando; Result = terminado (imagen o fallo).
    val result by produceState<Result<ImageBitmap>?>(null, url) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                require(storageAllows(url)) { "URL fuera de nuestro Storage" }
                val connection = URI(url).toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 5_000
                connection.readTimeout = 10_000
                connection.instanceFollowRedirects = false
                val bytes = connection.inputStream.use { it.readNBytes(MAX_PREVIEW_BYTES + 1) }
                require(bytes.size <= MAX_PREVIEW_BYTES) { "imagen demasiado grande para la vista previa" }
                org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
            }
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val image = result?.getOrNull()
        if (image != null) {
            Image(image, "Vista previa del recurso", Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        } else {
            Text(
                text = if (result == null) "Cargando…" else "Sin vista previa",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

internal actual fun openQuestionResourceExternally(url: String): String? {
    if (!storageAllows(url)) return "Este enlace no es de nuestro Storage: no se abre."
    val viaDesktop = runCatching {
        check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
        Desktop.getDesktop().browse(URI(url))
    }
    if (viaDesktop.isSuccess) return null
    // Alternativa en Windows cuando Java no puede abrir el navegador por defecto.
    val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    if (windows) {
        val viaShell = runCatching { ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start() }
        if (viaShell.isSuccess) return null
    }
    return "No se ha podido abrir el navegador: ${viaDesktop.exceptionOrNull()?.message ?: "error desconocido"}"
}
