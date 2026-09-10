package com.oposicontrol.admin.features.imports.presentation

import androidx.compose.runtime.Composable

// Ver ImportFilePicker.android.kt: mismo motivo, esta pantalla no se pinta en iOS.
@Composable
actual fun rememberPdfPickerLauncher(onPicked: (PickedPdf) -> Unit): () -> Unit = {}
