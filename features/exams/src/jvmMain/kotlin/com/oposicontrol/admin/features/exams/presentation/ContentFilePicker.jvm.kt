package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.File
import java.nio.file.Files
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * El selector de fichero de escritorio.
 *
 * **Los dos lanzadores eran `ContentFilePickerLauncher {}`**: un cuerpo vacío. El
 * botón «Subir imagen» del editor de portada no abría nada, no fallaba y no
 * dejaba rastro — que es la peor de las tres cosas, porque no hay forma de
 * distinguirlo de un fallo del servidor.
 *
 * Todo lo que va DEBAJO ya funcionaba: `PlatformFileReader.jvm.kt` lee los bytes
 * de una ruta absoluta, `uploadExamCover` arma el multipart, y el backend valida
 * tamaño y tipo, sube a Storage y hace el PATCH. Faltaba exactamente la primera
 * pieza de la cadena.
 *
 * Se copia el patrón que el módulo de Importación ya tenía resuelto
 * (`ImportFilePicker.jvm.kt`) en vez de inventar otro: mismo `JFileChooser`,
 * mismo `remember` por callback y misma forma de devolver la selección. Dos
 * selectores de fichero distintos en la misma aplicación es exactamente el tipo
 * de divergencia que luego nadie sabe cuál mantener.
 */
@Composable
actual fun rememberContentFilePickerLauncher(
    mimeType: String,
    onPicked: (PickedFileReference) -> Unit,
): ContentFilePickerLauncher {
    return remember(mimeType, onPicked) {
        ContentFilePickerLauncher {
            val chooser = JFileChooser().apply {
                filterFor(mimeType)?.let {
                    fileFilter = it
                    isAcceptAllFileFilterUsed = false
                }
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                val file: File = chooser.selectedFile
                onPicked(
                    PickedFileReference(
                        // Ruta absoluta, que es lo que `PlatformFileReader` espera
                        // en esta plataforma. En Android el mismo campo lleva un
                        // `content://`; el nombre del campo es `uri` porque es el
                        // contrato común, no porque aquí lo sea.
                        uri = file.absolutePath,
                        name = file.name,
                        // `probeContentType` puede devolver null —depende del
                        // registro de tipos del sistema— y eso está bien: el
                        // multipart tiene su propio valor por defecto y el backend
                        // valida por su cuenta. Inventar aquí un `image/jpeg` para
                        // un fichero que no lo es sería peor que no decir nada.
                        mimeType = runCatching { Files.probeContentType(file.toPath()) }.getOrNull(),
                    ),
                )
            }
        }
    }
}

/**
 * Selector de fichero de TEXTO. Devuelve el contenido, no la ruta.
 *
 * Estaba igual de vacío que el anterior. Es el que usa la subida de recursos de
 * texto de una pregunta, y aunque ese camino tiene sus propios problemas —que no
 * se arreglan aquí—, un selector que no abre nada no es uno de ellos.
 */
@Composable
actual fun rememberTextFilePickerLauncher(
    onPicked: (PickedTextFile) -> Unit,
): ContentFilePickerLauncher {
    return remember(onPicked) {
        ContentFilePickerLauncher {
            val chooser = JFileChooser().apply {
                fileFilter = FileNameExtensionFilter("Ficheros de texto", "txt", "md", "csv", "json")
                isAcceptAllFileFilterUsed = true
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                val file: File = chooser.selectedFile
                // Si el fichero no se puede leer, no se llama a `onPicked`: es
                // preferible que no pase nada visible a rellenar el editor con una
                // cadena vacía que el usuario tomaría por el contenido del fichero.
                runCatching { file.readText() }.getOrNull()?.let { content ->
                    onPicked(PickedTextFile(name = file.name, content = content))
                }
            }
        }
    }
}

/**
 * El filtro del diálogo, derivado del `mimeType` que pide quien llama.
 *
 * Sólo se traducen los tipos que el producto usa de verdad. El comodín de
 * «cualquier fichero» —o cualquier tipo desconocido— devuelve `null` y el
 * diálogo se abre sin filtro, que es lo correcto: filtrar por una extensión
 * inventada escondería ficheros que el backend sí aceptaría.
 *
 * Las tres extensiones de imagen son EXACTAMENTE las que el editor de portada
 * anuncia y las que el backend valida. Si un día divergen, diverge aquí también
 * y a propósito, no por olvido.
 */
private fun filterFor(mimeType: String): FileNameExtensionFilter? = when {
    mimeType.startsWith("image/") ->
        FileNameExtensionFilter("Imágenes (JPG, PNG, WEBP)", "jpg", "jpeg", "png", "webp")
    mimeType == "application/pdf" -> FileNameExtensionFilter("Documentos PDF", "pdf")
    mimeType.startsWith("audio/") -> FileNameExtensionFilter("Audio", "mp3", "m4a", "ogg", "wav")
    mimeType.startsWith("video/") -> FileNameExtensionFilter("Vídeo", "mp4", "webm", "mov")
    else -> null
}
