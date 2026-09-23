package com.oposicontrol.admin.features.news.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.oposicontrol.admin.core.ui.theme.GlassTheme

/**
 * Propiedades de la capa seleccionada, en columna propia.
 *
 * Antes vivían debajo de la lista de capas, dentro de la pestaña «Capas
 * visuales»: para mover un título había que bajar por el panel, perder de vista
 * el lienzo y volver a subir para comprobar el resultado. Son dos trabajos
 * simultáneos —ver y ajustar— y ahora están uno al lado del otro.
 *
 * El contenido lo pinta [NewsLayerProperties], que vive en `NewsLayersEditor`:
 * una sola implementación para los dos sitios donde puede aparecer —columna
 * propia en ventana ancha, dentro del editor cuando no cabe—, para que no se
 * separen con el tiempo.
 */
@Composable
internal fun NewsLayerInspectorPane(state: NewsState, onIntent: (NewsIntent) -> Unit) {
    NewsPanel("Propiedades de capa") {
        Text(
            "Edita las propiedades del elemento seleccionado.",
            style = MaterialTheme.typography.labelMedium,
            color = GlassTheme.tokens.textSecondary,
        )

        val layer = state.editorLayers.firstOrNull { it.id == state.selectedLayerId }
        if (layer == null) {
            Text("Ninguna capa seleccionada", style = MaterialTheme.typography.titleSmall)
            Text(
                "Selecciona una capa en el lienzo o en la lista para editar sus propiedades.",
                style = MaterialTheme.typography.bodySmall,
                color = GlassTheme.tokens.textSecondary,
            )
            return@NewsPanel
        }

        NewsLayerProperties(layer, state.editorLayers.lastIndex, onIntent)
    }
}
