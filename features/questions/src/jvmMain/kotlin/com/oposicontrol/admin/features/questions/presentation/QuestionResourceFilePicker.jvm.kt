package com.oposicontrol.admin.features.questions.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.oposicontrol.admin.domain.repository.QuestionResourceFile
import com.oposicontrol.admin.domain.repository.QuestionResourceKind
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * El selector real: `JFileChooser`, el mismo que usan Importación y el editor de
 * portada. Los bytes se leen aquí porque aquí es donde hay disco.
 *
 * Si el fichero no se puede leer NO se llama a [onPicked]: dejar el formulario con
 * un archivo de cero bytes elegido sería peor que no elegir nada, porque el error
 * aparecería al final, después de la subida, y hablando de un MIME.
 */
@Composable
actual fun rememberQuestionResourceFilePicker(
    kind: QuestionResourceKind,
    onPicked: (QuestionResourceFile) -> Unit,
): QuestionResourceFilePicker = remember(kind, onPicked) {
    QuestionResourceFilePicker(available = true) {
        val chooser = JFileChooser().apply {
            fileFilter = filterFor(kind)
            isAcceptAllFileFilterUsed = false
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            val file: File = chooser.selectedFile
            runCatching { file.readBytes() }.getOrNull()?.let { bytes ->
                onPicked(
                    QuestionResourceFile(
                        fileName = file.name,
                        mimeType = mimeTypeFor(file.name),
                        bytes = bytes,
                    ),
                )
            }
        }
    }
}

/**
 * Las extensiones que el servidor acepta para cada tipo, y ninguna más.
 *
 * Salen de su mapa `QUESTION_RESOURCE_MIME`. Si un día divergen, el diálogo dejará
 * de ofrecer algo que sí se podría subir —molesto pero visible—, en vez de dejar
 * subir algo que va a rechazar después de cruzar la red.
 */
private fun filterFor(kind: QuestionResourceKind): FileNameExtensionFilter = when (kind) {
    QuestionResourceKind.IMAGEN -> FileNameExtensionFilter("Imágenes (PNG, JPG, WEBP, GIF)", "png", "jpg", "jpeg", "webp", "gif")
    QuestionResourceKind.AUDIO -> FileNameExtensionFilter("Audio (MP3, M4A, WAV)", "mp3", "m4a", "wav")
    QuestionResourceKind.VIDEO -> FileNameExtensionFilter("Vídeo (MP4)", "mp4")
}

/**
 * El MIME que declara la parte multipart, derivado de la EXTENSIÓN.
 *
 * No se usa `Files.probeContentType`: depende del registro de tipos del sistema, y
 * en Windows devuelve `null` para `.webp` y `.m4a` con una instalación limpia. Un
 * mapa explícito con los mismos valores que valida el servidor es determinista y se
 * lee de un vistazo. Lo que no está en él viaja como octeto genérico y el servidor
 * lo rechaza con su propio mensaje — que es lo correcto: inventar aquí un
 * `image/png` para un fichero que no lo es sería acertar por casualidad.
 */
private fun mimeTypeFor(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "mp3" -> "audio/mpeg"
    "m4a" -> "audio/mp4"
    "wav" -> "audio/wav"
    "mp4" -> "video/mp4"

    else -> "application/octet-stream"
}
