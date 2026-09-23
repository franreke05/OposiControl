package com.oposicontrol.admin.features.imports.presentation

import androidx.compose.runtime.Composable

// Esta pantalla solo se pinta en desktop (ver AppRoot.kt): en compacto/mobile
// sigue rendiendo el ContentUploadScreen existente, sin cambios. No-op honesto,
// no alcanzable en runtime.
@Composable
actual fun rememberPdfPickerLauncher(onPicked: (PickedPdf) -> Unit): () -> Unit = {}
