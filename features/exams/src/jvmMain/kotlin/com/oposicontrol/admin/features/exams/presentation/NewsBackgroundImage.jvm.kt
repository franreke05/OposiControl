package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import org.jetbrains.skia.Image as SkiaImage

/**
 * Una imagen LOCAL, decodificada en escritorio.
 *
 * Era `= Unit`, y por eso el bloque de portada no podía enseñar ni la imagen que
 * se acababa de elegir. Ahora decodifica con Skia —el mismo motor que dibuja la
 * interfaz— y con dos límites deliberados:
 *
 *  - **Sólo ficheros locales.** Una ruta `http(s)` se ignora y no pinta nada: la
 *    vista previa no descarga desde un Composable. Previsualizar la portada ya
 *    guardada pediría red y un caché de imágenes que no existe (D3); en su sitio
 *    va un marcador honesto.
 *  - **Fuera del hilo de interfaz.** La lectura y el decodificado van en
 *    `Dispatchers.IO` dentro de `produceState`, ligado a la ruta: recomponer no
 *    vuelve a leer el fichero, sólo cambiar de fichero.
 *
 * Si el fichero no existe o no es una imagen, no pinta nada y el marcador que
 * hay debajo sigue a la vista: un fallo de vista previa no es un fallo de la
 * portada, y no puede parecerlo.
 */
@Composable
actual fun NewsBackgroundImage(
    backgroundPath: String,
    modifier: Modifier,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, backgroundPath) {
        value = if (!backgroundPath.isLocalPath()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(backgroundPath.trim().removePrefix("file://").removePrefix("file:"))
                    if (file.isFile) SkiaImage.makeFromEncoded(file.readBytes()).toComposeImageBitmap() else null
                }.getOrNull()
            }
        }
    }
    bitmap?.let { image ->
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    }
}
