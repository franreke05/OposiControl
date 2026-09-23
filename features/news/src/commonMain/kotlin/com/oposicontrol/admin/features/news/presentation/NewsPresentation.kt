package com.oposicontrol.admin.features.news.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync

data class NewsCatalogOption(val id: String, val name: String, val active: Boolean = true)

internal fun newsStatusLabel(value: String): String = when (value.lowercase()) {
    "draft" -> "Borrador"
    "published" -> "Publicada"
    "scheduled" -> "Programada"
    "archived" -> "Archivada"
    else -> "Sin identificar"
}
internal fun catalogName(value: String, catalog: UiAsync<List<NewsCatalogOption>>): String = when (catalog) {
    UiAsync.Loading, UiAsync.Idle -> "Cargando…"
    is UiAsync.Error -> "No disponible"
    else -> (catalog as? UiAsync.Success)?.value?.firstOrNull { it.id == value }?.name
        ?: if (value.isBlank()) "Sin seleccionar" else "Sin identificar"
}
internal fun newsAssetName(path: String): String = path.substringBefore('?').replace('\\', '/')
    .substringAfterLast('/').takeIf { it.contains('.') && !it.matches(Regex("[a-fA-F0-9-]{30,}.*")) }
    ?.take(48) ?: "Imagen seleccionada"
internal fun newsDateLabel(value: String): String {
    val match = Regex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}).*").matchEntire(value) ?: return "Sin fecha confirmada"
    val (year, month, day, hour, minute) = match.destructured
    return "$day/$month/$year · $hour:$minute UTC"
}
internal data class NewsPaneSizes(val library: Int, val editor: Int, val rail: Int)
internal fun newsPaneSizes(width: Int): NewsPaneSizes = when {
    width >= 1380 -> NewsPaneSizes(360, width - 852, 460)
    width >= 940 -> NewsPaneSizes(288, width - 304, 0)
    else -> NewsPaneSizes(0, width, 0)
}
internal fun NewsState.saveLabel(): String = when {
    actionState is UiAsync.Loading -> "Guardando…"
    actionState is UiAsync.Error -> "No se pudo guardar"
    isDirty -> "Cambios sin guardar"
    actionState is UiAsync.Success -> actionMessage.ifBlank { "Operación completada" }
    else -> "Sin cambios"
}
