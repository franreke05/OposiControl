package com.oposicontrol.admin.features.imports.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

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

/**
 * Solo para pruebas de UI de la app entera: el diálogo nativo no se puede pulsar desde un test. En producción vale
 * `null` y se abre el diálogo del sistema; un test lo sustituye con un PDF concreto (UI E2E 2026-10-20).
 */
val LocalPdfPickerOverride = staticCompositionLocalOf<(() -> PickedPdf?)?> { null }
