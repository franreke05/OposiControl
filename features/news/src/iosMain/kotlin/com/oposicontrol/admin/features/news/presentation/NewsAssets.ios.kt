package com.oposicontrol.admin.features.news.presentation

import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

@Composable
internal actual fun rememberNewsImagePicker(onPicked: (String) -> Unit): () -> Unit {
    var unavailable by remember { mutableStateOf(false) }
    if (unavailable) AlertDialog(onDismissRequest = { unavailable = false }, title = { Text("Selección no disponible") }, text = { Text("Selecciona la imagen desde OposiControl de escritorio.") }, confirmButton = { TextButton(onClick = { unavailable = false }) { Text("Cerrar") } })
    return { unavailable = true }
}
@Composable
internal actual fun NewsAssetImage(path: String, modifier: Modifier) {
    Text("Vista de imagen disponible en escritorio", modifier)
}
