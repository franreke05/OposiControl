package com.oposicontrol.admin.features.news.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.*
import com.oposicontrol.admin.core.ui.theme.GlassTheme

// Medidas fijas del panel. En ventanas medias el panel mide 288dp: 288 - 36 de
// padding dejan 252dp utiles. Tres filtros de 80dp separados por 4dp ocupan
// 248dp, asi que la fila de filtros entra entera y nunca vuelve a apilarse.
private val FilterChipWidth = 80.dp
private val FilterValueWidth = 48.dp
private val FilterChipHeight = 32.dp
private val ThumbnailSize = 56.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NewsLibraryPane(state: NewsState, onIntent: (NewsIntent) -> Unit, modifier: Modifier) {
    val tokens = GlassTheme.tokens
    val items = (state.news as? UiAsync.Success)?.value.orEmpty()
    NewsPanel("Biblioteca", modifier) {
        Text("Selecciona una noticia para editarla o crea una nueva.", style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
        GlassSearchBar(state.searchQuery, { onIntent(NewsIntent.UpdateSearch(it)) }, "Buscar noticias…")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 3) {
            NewsFilterChip(
                label = "Estado",
                value = if (state.statusFilter == "all") "Todos" else newsStatusLabel(state.statusFilter),
                active = state.statusFilter != "all",
                options = listOf("all" to "Todos", "draft" to "Borradores", "scheduled" to "Programadas", "published" to "Publicadas", "archived" to "Archivadas"),
            ) { onIntent(NewsIntent.UpdateStatusFilter(it)) }
            NewsFilterChip(
                label = "Tipo",
                value = if (state.typeFilter == "all") "Todos" else catalogName(state.typeFilter, state.newsTypes),
                active = state.typeFilter != "all",
                options = listOf("all" to "Todos") + ((state.newsTypes as? UiAsync.Success)?.value.orEmpty().map { it.id to it.name }),
            ) { onIntent(NewsIntent.UpdateTypeFilter(it)) }
            NewsFilterChip(
                label = "Especialidad",
                value = if (state.specialtyFilter == "all") "Todas" else catalogName(state.specialtyFilter, state.specialties),
                active = state.specialtyFilter != "all",
                options = listOf("all" to "Todas") + ((state.specialties as? UiAsync.Success)?.value.orEmpty().map { it.id to it.name }),
            ) { onIntent(NewsIntent.UpdateSpecialtyFilter(it)) }
        }
        HorizontalDivider(color = tokens.border)
        when (state.libraryState) {
            UiAsync.Loading, UiAsync.Idle -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is UiAsync.Error -> ErrorStateCard(
                message = "Los borradores locales siguen disponibles mientras tanto.",
                title = "No se pudo actualizar la biblioteca",
                onRetry = { onIntent(NewsIntent.Refresh) },
            )
            else -> Unit
        }
        if (state.news is UiAsync.Success) Text(newsCountLabel(items.size), style = MaterialTheme.typography.labelMedium, color = tokens.textSecondary)
        if (items.isEmpty() && state.libraryState !is UiAsync.Loading) EmptyStateCard(
            title = "Sin noticias en esta vista",
            message = "Crea una noticia o ajusta los filtros para encontrar contenido.",
            icon = AppIcon.NEWS,
        )
        LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { item ->
                NewsLibraryCard(item, state, state.selectedId == item.id) { onIntent(NewsIntent.Select(item.id)) }
            }
        }
    }
}

/** Tarjeta compacta: miniatura cuadrada a la izquierda; titulo, metadatos y estado a la derecha. */
@Composable
private fun NewsLibraryCard(item: NewsItem, state: NewsState, selected: Boolean, onSelect: () -> Unit) {
    val tokens = GlassTheme.tokens
    GlassSurface(
        Modifier.fillMaxWidth().clickable(onClick = onSelect),
        shape = RoundedCornerShape(12.dp),
        tint = if (selected) tokens.surfaceSelected else tokens.glassFill,
        strokeColor = if (selected) tokens.accentInk else tokens.border,
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NewsLibraryThumbnail(item.backgroundPath?.takeIf { it.isNotBlank() } ?: item.imagePath?.takeIf { it.isNotBlank() })
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.title.ifBlank { "Nueva noticia" }, style = MaterialTheme.typography.titleSmall, color = tokens.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(newsLibraryMeta(item, state), style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(newsStatusLabel(item.status), tone = newsStatusTone(item.status))
                    if (item.isLocalDraft) Text("Copia local", style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Miniatura de 56dp. Sin imagen no queda hueco: icono de noticias sobre superficie apagada. */
@Composable
private fun NewsLibraryThumbnail(image: String?) {
    val tokens = GlassTheme.tokens
    Box(
        Modifier.size(ThumbnailSize).clip(RoundedCornerShape(10.dp)).background(tokens.surfaceMuted),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) NewsAssetImage(image, Modifier.matchParentSize())
        else AppIconImage(AppIcon.NEWS, size = 20.dp, tint = tokens.textSecondary)
    }
}

/** Filtro compacto: etiqueta breve sobre un disparador de 80dp que abre el menu de siempre. */
@Composable
private fun NewsFilterChip(label: String, value: String, active: Boolean, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    val tokens = GlassTheme.tokens
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.width(FilterChipWidth), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box {
            Row(
                Modifier
                    .height(FilterChipHeight)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) tokens.surfaceSelected else tokens.glassFill)
                    .border(1.dp, if (active) tokens.accentInk else tokens.fieldBorder, RoundedCornerShape(10.dp))
                    .clickable(enabled = options.isNotEmpty()) { expanded = true }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (active) tokens.accentInk else tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(FilterValueWidth),
                )
                AppIconImage(AppIcon.CHEVRON_DOWN, size = 12.dp, tint = tokens.textSecondary)
            }
            DropdownMenu(expanded, { expanded = false }) {
                options.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onSelect(id) }) }
            }
        }
    }
}

private fun newsCountLabel(count: Int): String = if (count == 1) "1 noticia" else "$count noticias"

private fun newsLibraryMeta(item: NewsItem, state: NewsState): String {
    val label = newsDateLabel(item.publishFrom)
    val date = if (label.contains(" · ")) label.substringBefore(" · ") else "Sin fecha"
    return "$date · ${catalogName(item.type, state.newsTypes)}"
}

private fun newsStatusTone(status: String): StatusTone = when (status) {
    "published" -> StatusTone.POSITIVE
    "scheduled" -> StatusTone.NEUTRAL
    "draft", "archived" -> StatusTone.ATTENTION
    else -> StatusTone.UNKNOWN
}
