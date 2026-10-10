package com.oposicontrol.admin.features.news.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.DesktopIconButton
import com.oposicontrol.admin.core.ui.components.DesktopVerticalScrollbar
import com.oposicontrol.admin.core.ui.components.DropdownField
import com.oposicontrol.admin.core.ui.components.GhostButton
import com.oposicontrol.admin.core.ui.components.GlassSearchBar
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.components.WorkspaceCatalogRow
import com.oposicontrol.admin.core.ui.components.WorkspaceEqualRow
import com.oposicontrol.admin.core.ui.components.WorkspaceFilterPill
import com.oposicontrol.admin.core.ui.components.WorkspaceNotice
import com.oposicontrol.admin.core.ui.components.WorkspaceNoticeTone
import com.oposicontrol.admin.core.ui.components.WorkspacePill
import com.oposicontrol.admin.core.ui.components.WorkspaceSection
import com.oposicontrol.admin.core.ui.components.WorkspaceSizing
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing

/** Miniatura de la fila: la imagen de la tarjeta si la hay; si no, el icono de noticias sobre superficie hundida. */
private val THUMBNAIL_SIZE = 36.dp

private val STATUS_FILTERS = listOf("all" to "Todas", "draft" to "Borrador", "scheduled" to "Programada", "published" to "Publicada", "archived" to "Archivada")

/**
 * La biblioteca: busqueda y filtros que resuelve el SERVIDOR (D4-01), filas resumen y «Cargar más» cuando hay
 * otra pagina. Un refresco con filas en pantalla no las borra: la cabecera dice «Actualizando…» y, si falla, el
 * aviso se pinta encima de lo ultimo leido.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NewsCatalogPane(state: NewsState, onIntent: (NewsIntent) -> Unit, modifier: Modifier = Modifier) {
    val tokens = GlassTheme.tokens
    val rows = (state.news as? UiAsync.Success)?.value.orEmpty()
    val hasFilters = state.hasLibraryFilters()
    val loaded = state.libraryState is UiAsync.Success
    val shown: UiAsync<List<NewsItem>> = when {
        state.libraryState is UiAsync.Error && rows.isEmpty() -> UiAsync.Error((state.libraryState as UiAsync.Error).error)
        !loaded && rows.isEmpty() -> UiAsync.Loading
        rows.isEmpty() -> UiAsync.Empty
        else -> UiAsync.Success(rows)
    }
    val busy = state.actionState is UiAsync.Loading

    WorkspaceSection(
        icon = AppIcon.NEWS,
        role = SemanticRole.INFO,
        title = "Noticias",
        modifier = modifier,
        action = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs), verticalAlignment = Alignment.CenterVertically) {
                when {
                    state.libraryRefreshing -> WorkspacePill(text = "Actualizando…")
                    rows.isNotEmpty() -> WorkspacePill(text = rows.size.toString())
                }
                DesktopIconButton(
                    label = "Actualizar noticias",
                    size = Sizing.iconButtonSmall,
                    enabled = !state.libraryRefreshing,
                    onClick = { onIntent(NewsIntent.Refresh) },
                ) { AppIconImage(AppIcon.REFRESH, size = 14.dp) }
            }
        },
    ) {
        if (state.libraryRefreshFailed) {
            WorkspaceNotice(
                tone = WorkspaceNoticeTone.WARNING,
                title = "No se pudo actualizar",
                detail = "Se enseña lo último que se leyó. Vuelve a intentarlo con el botón de actualizar.",
            )
        }
        SecondaryButton(
            text = "Nueva noticia",
            onClick = { onIntent(NewsIntent.CreateNew) },
            modifier = Modifier.fillMaxWidth(),
            size = ButtonSize.COMPACT,
            enabled = !busy,
            icon = { AppIconImage(AppIcon.ADD, size = 14.dp) },
        )
        GlassSearchBar(
            value = state.searchQuery,
            onValueChange = { onIntent(NewsIntent.UpdateSearch(it)) },
            placeholder = "Buscar por título…",
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            STATUS_FILTERS.forEach { (key, label) ->
                WorkspaceFilterPill(label = label, selected = state.statusFilter == key, onClick = { onIntent(NewsIntent.UpdateStatusFilter(key)) })
            }
            if (hasFilters) {
                GhostButton(text = "Limpiar", size = ButtonSize.COMPACT, onClick = { onIntent(NewsIntent.ClearFilters) })
            }
        }
        WorkspaceEqualRow(columns = 2, gap = Spacing.xs) {
            DropdownField(
                label = "Tipo",
                selectedLabel = if (state.typeFilter == "all") "Todos" else catalogName(state.typeFilter, state.newsTypes),
                options = listOf<Pair<String?, String>>("all" to "Todos") + (state.newsTypes as? UiAsync.Success)?.value.orEmpty().map { it.id to it.name },
                onSelect = { onIntent(NewsIntent.UpdateTypeFilter(it ?: "all")) },
                enabled = state.newsTypes is UiAsync.Success,
            )
            DropdownField(
                label = "Especialidad",
                selectedLabel = if (state.specialtyFilter == "all") "Todas" else catalogName(state.specialtyFilter, state.specialties),
                options = listOf<Pair<String?, String>>("all" to "Todas") + (state.specialties as? UiAsync.Success)?.value.orEmpty().map { it.id to it.name },
                onSelect = { onIntent(NewsIntent.UpdateSpecialtyFilter(it ?: "all")) },
                enabled = state.specialties is UiAsync.Success,
            )
        }
        AsyncStatePane(
            state = shown,
            emptyTitle = if (hasFilters) "Sin resultados" else "Sin noticias",
            emptyMessage = if (hasFilters) "Ninguna noticia coincide con la búsqueda y los filtros." else "Todavía no hay ninguna noticia. Crea la primera.",
            emptyIcon = if (hasFilters) AppIcon.SEARCH else AppIcon.NEWS,
            loadingRows = 6,
            onRetry = { onIntent(NewsIntent.Refresh) },
            emptyAction = {
                if (!hasFilters) PrimaryButton(text = "Nueva noticia", size = ButtonSize.COMPACT, onClick = { onIntent(NewsIntent.CreateNew) })
            },
        ) { items ->
            val scroll = rememberScrollState()
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(scroll).padding(end = WorkspaceSizing.scrollbarGutter),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    items.forEach { item ->
                        WorkspaceCatalogRow(
                            title = item.title.ifBlank { if (item.isLocalDraft) "Nueva noticia" else "Sin título" },
                            selected = item.id == state.selectedId,
                            onClick = { onIntent(NewsIntent.Select(item.id)) },
                            icon = AppIcon.NEWS,
                            eyebrow = if (item.isLocalDraft) "Sin guardar" else null,
                            meta = if (item.isLocalDraft) emptyList() else newsLibraryMeta(item, state),
                            leading = { NewsThumbnail(item.imageUrl) },
                            trailing = { StatusChip(status = if (item.isLocalDraft) "Nueva" else newsStatusLabel(item.status), tone = if (item.isLocalDraft) com.oposicontrol.admin.core.ui.components.StatusTone.ATTENTION else newsStatusTone(item.status)) },
                        )
                    }
                    Text(
                        text = newsCountLabel(items.size) + if (state.libraryNextCursor != null) " cargadas · hay más" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.textSecondary,
                    )
                    if (state.libraryNextCursor != null) {
                        SecondaryButton(
                            text = "Cargar más",
                            onClick = { onIntent(NewsIntent.LoadMore) },
                            modifier = Modifier.fillMaxWidth(),
                            size = ButtonSize.COMPACT,
                            loading = state.libraryLoadingMore,
                        )
                    }
                }
                DesktopVerticalScrollbar(scrollState = scroll, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun NewsThumbnail(imageUrl: String?) {
    val tokens = GlassTheme.tokens
    Box(
        modifier = Modifier.size(THUMBNAIL_SIZE).clip(Radius.md).background(tokens.surfaceMuted),
        contentAlignment = Alignment.Center,
    ) {
        if (!imageUrl.isNullOrBlank()) {
            NewsAssetImage(imageUrl, Modifier.size(THUMBNAIL_SIZE))
        } else {
            AppIconImage(AppIcon.NEWS, size = Sizing.icon, tint = tokens.textSecondary)
        }
    }
}
