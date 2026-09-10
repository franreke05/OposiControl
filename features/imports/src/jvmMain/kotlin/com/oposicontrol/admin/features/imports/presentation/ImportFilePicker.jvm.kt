package com.oposicontrol.admin.features.imports.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
actual fun rememberPdfPickerLauncher(onPicked: (PickedPdf) -> Unit): () -> Unit {
    return remember(onPicked) {
        {
            val chooser = JFileChooser().apply {
                fileFilter = FileNameExtensionFilter("Documentos PDF", "pdf")
                isAcceptAllFileFilterUsed = false
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                val file: File = chooser.selectedFile
                onPicked(PickedPdf(path = file.absolutePath, name = file.name, sizeBytes = file.length()))
            }
        }
    }
}
