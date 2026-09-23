package com.oposicontrol.admin.features.specialties.presentation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.DesktopVerticalScrollbar
import com.oposicontrol.admin.core.ui.components.GhostButton
import com.oposicontrol.admin.core.ui.components.GlassSearchBar
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SemanticIconContainer
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.Specialty

/** Etiqueta de prueba del catálogo. Sólo semántica: no se pinta. */
internal const val SPECIALTIES_CATALOG_TAG: String = "specialties-catalog"

/**
 * El catálogo: buscar una especialidad y abrirla.
 *
 * Todo lo que hace esta columna ocurre en el cliente, sobre la lista que ya está
 * descargada, y la interfaz no finge lo contrario: `GET /especialidades` no
 * acepta ni búsqueda ni filtro de estado, así que aquí no hay consultas, hay un
 * recorte de lo que hay en memoria. Por eso tampoco hay paginación: la lista
 * viene entera.
 *
 * El filtro de estado son tres píldoras y no un desplegable porque son TRES
 * opciones y se alternan mucho: «Archivadas» es la única forma de encontrar una
 * especialidad que se archivó, ya que archivar no la saca de esta lista.
 */
@Composable
internal fun SpecialtiesCatalogPane(
    state: SpecialtiesState,
    onIntent: (SpecialtiesIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasFilters = specialtyCatalogHasFilters(state.searchQuery, state.statusFilter)

    SpecialtiesSection(
        icon = AppIcon.LAYERS,
        role = SemanticRole.INFO,
        title = "Especialidades",
        modifier = modifier,
        // El total va en la cabecera de la columna: es el primer dato que se
        // busca al abrir la pantalla y tenerlo al pie obligaba a recorrer la
        // lista para encontrarlo.
        action = {
            if (state.catalog.isNotEmpty()) {
                SpecialtyGlassPill(text = state.catalog.size.toString())
            }
        },
    ) {
        GlassSearchBar(
            value = state.searchQuery,
            onValueChange = { onIntent(SpecialtiesIntent.UpdateSearch(it)) },
            placeholder = "Buscar especialidad…",
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SpecialtyStatusFilter.entries.forEach { filter ->
                SpecialtyStatusFilterPill(
                    filter = filter,
                    selected = filter == state.statusFilter,
                    onClick = { onIntent(SpecialtiesIntent.UpdateStatusFilter(filter)) },
                )
            }
            if (hasFilters) {
                Box(modifier = Modifier.weight(1f))
                GhostButton(
                    text = "Limpiar",
                    size = ButtonSize.COMPACT,
                    onClick = {
                        onIntent(SpecialtiesIntent.UpdateSearch(""))
                        onIntent(SpecialtiesIntent.UpdateStatusFilter(SpecialtyStatusFilter.ALL))
                    },
                )
            }
        }

        AsyncStatePane(
            state = state.items,
            // La lista vacía significa dos cosas distintas y no se pueden contar
            // igual: sin filtros es «no hay especialidades» e invita a crear una;
            // con la búsqueda o el estado puestos es «no hay ninguna ASÍ», y
            // ofrecer ahí «Nueva especialidad» respondería a una pregunta que
            // nadie hizo.
            emptyTitle = if (hasFilters) "Sin resultados" else "Sin especialidades",
            emptyMessage = if (hasFilters) {
                "Ninguna especialidad coincide con la búsqueda y el filtro de estado."
            } else {
                "Todavía no se ha creado ninguna especialidad."
            },
            emptyIcon = if (hasFilters) AppIcon.SEARCH else AppIcon.LAYERS,
            loadingRows = 5,
            // Faltaba: sin `onRetry`, un corte de red dejaba la columna con un
            // mensaje y sin ninguna salida.
            onRetry = { onIntent(SpecialtiesIntent.Refresh) },
            emptyAction = {
                if (!hasFilters) {
                    PrimaryButton(
                        text = "Nueva especialidad",
                        onClick = { onIntent(SpecialtiesIntent.SwitchSpecialty(null)) },
                        size = ButtonSize.COMPACT,
                    )
                }
            },
        ) { items ->
            val scroll = rememberScrollState()
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scroll)
                        .padding(end = SpecialtiesSizing.scrollbarGutter),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    items.forEach { specialty ->
                        SpecialtyCatalogRow(
                            specialty = specialty,
                            selected = specialty.id == state.selectedId,
                            onClick = { onIntent(SpecialtiesIntent.SwitchSpecialty(specialty.id)) },
                        )
                    }
                }
                DesktopVerticalScrollbar(
                    scrollState = scroll,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }
    }
}

/**
 * Una fila del catálogo.
 *
 * Sin identificadores internos: lo que orienta es el nombre, la descripción y
 * los tres metadatos —estado, orden y exámenes—. Seleccionada se marca con una
 * barra coral a la izquierda y un fondo muy tenue, no con un borde de color
 * alrededor de toda la fila, que en una lista de doce compite con el contenido.
 *
 * El recuento de exámenes lleva su salvedad en el título accesible y en la
 * pestaña Impacto, no en la fila: «4 ex.» a secas en 330dp es lo único que cabe,
 * y la fila no es el sitio donde se decide nada sobre ese número.
 */
@Composable
private fun SpecialtyCatalogRow(
    specialty: Specialty,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = GlassTheme.tokens
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val colors = specialtyCatalogRowColors(selected, hovered, tokens)
    val background by animateColorAsState(targetValue = colors.background, animationSpec = tween(140))
    val description = specialtyRowDescription(specialty)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radius.card)
            .background(background)
            .border(1.dp, colors.border, Radius.card)
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(specialtyCatalogRowBar(selected, tokens)),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            // Contenedor de icono también en la fila: la lista deja de ser una
            // columna de bloques de texto y gana un punto de anclaje por fila. En
            // la seleccionada el rol pasa a BRAND, y el coral que ya marca borde y
            // fondo se remata dentro.
            SemanticIconContainer(
                icon = AppIcon.LAYERS,
                role = if (selected) SemanticRole.BRAND else SemanticRole.INFO,
                size = Sizing.iconButton,
                iconSize = Sizing.icon,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = specialty.name.ifBlank { "Especialidad sin nombre" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip(
                        status = specialtyStatusLabel(specialty.isActive),
                        tone = specialtyStatusTone(specialty.isActive),
                    )
                }
                Text(
                    // Una descripción ausente se enseña como ausencia, no como un
                    // hueco: en esta tabla el backend rellena la descripción vacía
                    // con el slug, y un «tcae-2026» ahí se leería como texto
                    // escrito por alguien.
                    text = description ?: "Sin descripción",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.description,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Orden ${specialty.sortOrder}",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.description,
                    )
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.description,
                    )
                    Text(
                        text = specialtyExamCountLabel(specialty.linkedExams),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.description,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Una píldora del filtro de estado.
 *
 * Opaca, no de cristal: es un control de formulario y vive dentro de un
 * contenedor translúcido. Activa, el coral se queda en el borde y en la marca;
 * el texto se lee en `textPrimary`, porque `accentInk` sobre el tinte de una
 * píldora activa no llega al 4,5:1 del tema claro.
 */
@Composable
private fun SpecialtyStatusFilterPill(
    filter: SpecialtyStatusFilter,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = specialtyStatusFilterColors(selected, GlassTheme.tokens)
    Row(
        modifier = Modifier
            .defaultMinSize(minHeight = Sizing.buttonCompact)
            .clip(Radius.pill)
            .background(colors.background)
            .border(1.dp, colors.border, Radius.pill)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        if (selected) {
            AppIconImage(icon = AppIcon.CHECK, size = 12.dp, tint = colors.icon)
        }
        Text(
            text = filter.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.content,
            maxLines = 1,
        )
    }
}
