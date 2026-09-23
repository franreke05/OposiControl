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
/**
 * Anchos de las columnas del taller.
 *
 * [inspector] separa las propiedades de la capa del carril de publicación: antes
 * compartían el mismo panel derecho, así que editar una capa obligaba a bajar
 * por debajo de la vista previa. Son trabajos distintos y ahora son columnas
 * distintas — el editor manda, y la publicación es contexto, no formulario.
 *
 * Cero cuando la ventana no da: la columna desaparece y su contenido cae dentro
 * del editor, en vez de estrujarse hasta ser ilegible.
 */
internal data class NewsPaneSizes(
    val library: Int,
    val editor: Int,
    val inspector: Int,
    val rail: Int,
)

/** Hueco entre columnas, el mismo que aplica el `Row` del taller. */
private const val NEWS_PANE_GAP = 16

internal fun newsPaneSizes(width: Int): NewsPaneSizes = when {
    // WIDE: biblioteca + editor + inspector + publicación → 3 huecos.
    width >= 1720 -> NewsPaneSizes(
        library = 320,
        editor = width - 320 - 320 - 300 - 3 * NEWS_PANE_GAP,
        inspector = 320,
        rail = 300,
    )
    // STANDARD: la publicación se pliega dentro del editor; el inspector aguanta
    // porque es donde se trabaja. 3 columnas → 2 huecos.
    width >= 1380 -> NewsPaneSizes(
        library = 304,
        editor = width - 304 - 320 - 2 * NEWS_PANE_GAP,
        inspector = 320,
        rail = 0,
    )
    // COMPACT: solo biblioteca y editor → 1 hueco.
    width >= 940 -> NewsPaneSizes(
        library = 288,
        editor = width - 288 - NEWS_PANE_GAP,
        inspector = 0,
        rail = 0,
    )
    else -> NewsPaneSizes(library = 0, editor = width, inspector = 0, rail = 0)
}
internal fun NewsState.saveLabel(): String = when {
    actionState is UiAsync.Loading -> "Guardando…"
    actionState is UiAsync.Error -> "No se pudo guardar"
    isDirty -> "Cambios sin guardar"
    actionState is UiAsync.Success -> actionMessage.ifBlank { "Operación completada" }
    else -> "Sin cambios"
}
