package com.oposicontrol.admin.features.imports.presentation

import androidx.compose.runtime.Composable

data class PickedPdf(
    val path: String,
    val name: String,
    val sizeBytes: Long,
)

/**
 * Selector de PDF propio de Importación — deliberadamente no reutiliza
 * `features/exams/.../ContentFilePicker` (cuya implementación JVM real es un
 * no-op hoy) para no depender de `features/exams`. La implementación JVM de
 * este selector sí abre un diálogo real (`JFileChooser`); Android/iOS quedan
 * como no-op honesto porque esta pantalla solo se pinta en desktop
 * (ver `AppRoot.kt`: `AppContentTarget.CONTENT_UPLOAD` solo la usa cuando
 * `!compactLayout`).
 */
@Composable
expect fun rememberPdfPickerLauncher(onPicked: (PickedPdf) -> Unit): () -> Unit
