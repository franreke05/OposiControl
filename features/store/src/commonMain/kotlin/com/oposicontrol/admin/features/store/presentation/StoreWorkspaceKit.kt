package com.oposicontrol.admin.features.store.presentation

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.ConfirmDialog
import com.oposicontrol.admin.core.ui.components.DesktopIconButton
import com.oposicontrol.admin.core.ui.components.DesktopVerticalScrollbar
import com.oposicontrol.admin.core.ui.components.humanMessage
import com.oposicontrol.admin.core.ui.components.GhostButton
import com.oposicontrol.admin.core.ui.components.GlassSearchBar
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.components.StatusTone
import com.oposicontrol.admin.core.ui.components.WorkspaceCatalogRow
import com.oposicontrol.admin.core.ui.components.WorkspaceFilterPill
import com.oposicontrol.admin.core.ui.components.WorkspaceNotice
import com.oposicontrol.admin.core.ui.components.WorkspaceNoticeTone
import com.oposicontrol.admin.core.ui.components.WorkspacePager
import com.oposicontrol.admin.core.ui.components.WorkspacePill
import com.oposicontrol.admin.core.ui.components.WorkspaceSaveBar
import com.oposicontrol.admin.core.ui.components.WorkspaceSaveTone
import com.oposicontrol.admin.core.ui.components.WorkspaceSection
import com.oposicontrol.admin.core.ui.components.WorkspaceSizing
import com.oposicontrol.admin.core.ui.components.workspaceRangeText
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.core.ui.theme.OposiMotion
import com.oposicontrol.admin.core.ui.theme.motionTween
import androidx.compose.runtime.key

/**
 * Lo que comparten TODOS los catálogos comerciales —productos, planes, packs, servicios, promociones y pop-ups—: una
 * columna de catálogo con búsqueda, filtros y páginas, una barra de guardado y la confirmación de cambios sin guardar.
 *
 * Cada pantalla pone su ficha y sus reglas; el marco es uno solo, para que las seis se lean como la misma pantalla con
 * otro contenido y no como seis parientes.
 */

/** Filas por página del catálogo. El servidor ya dio la lista entera: esto es una ventana sobre ella, no una consulta. */
internal const val STORE_CATALOG_PAGE_SIZE: Int = 12

/** Ventana visible de una lista ya descargada. */
internal data class CatalogWindow<T>(
    val items: List<T>,
    val pageIndex: Int,
    val pageCount: Int,
    val offset: Int,
    val total: Int,
)

/** Recorta [all] a su página. Un índice fuera de rango (la lista encogió al filtrar) cae en la última página válida. */
internal fun <T> catalogWindow(all: List<T>, pageIndex: Int, pageSize: Int = STORE_CATALOG_PAGE_SIZE): CatalogWindow<T> {
    val pageCount = if (all.isEmpty()) 1 else (all.size + pageSize - 1) / pageSize
    val index = pageIndex.coerceIn(0, pageCount - 1)
    val offset = index * pageSize
    return CatalogWindow(all.drop(offset).take(pageSize), index, pageCount, offset, all.size)
}

/** Una opción de filtro: su clave (`null` = todas) y su etiqueta. */
internal data class CatalogFilterOption(val key: String?, val label: String)

/** Lo que una fila de catálogo enseña de su entidad. */
internal data class CatalogRowSpec(
    val key: String,
    val title: String,
    val eyebrow: String? = null,
    val description: String? = null,
    val meta: List<String> = emptyList(),
    val status: String,
    val statusTone: StatusTone,
)

/**
 * Columna de catálogo genérica.
 *
 * [items] es la lista YA filtrada; [loaded] distingue cargando / error / vacío de «hay datos». La búsqueda y los filtros
 * actúan en el cliente sobre lo descargado, y por eso el pie dice el rango sobre el total y no «página del servidor».
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> StoreCatalogPane(
    icon: AppIcon,
    title: String,
    noun: String,
    loaded: UiAsync<*>,
    items: List<T>,
    totalDownloaded: Int,
    pageIndex: Int,
    onPageChange: (Int) -> Unit,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    searchPlaceholder: String,
    filters: List<CatalogFilterOption>,
    selectedFilter: String?,
    onFilterChange: (String?) -> Unit,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    row: (T) -> CatalogRowSpec,
    emptyTitle: String,
    emptyMessage: String,
    modifier: Modifier = Modifier,
    rowIcon: AppIcon = icon,
    onCreate: (() -> Unit)? = null,
    createLabel: String = "Nuevo",
    refreshing: Boolean = false,
    refreshFailed: Boolean = false,
) {
    val hasFilters = searchQuery.isNotBlank() || selectedFilter != null
    val window = catalogWindow(items, pageIndex)
    // Lo que se pinta: el estado de la lectura mientras no haya datos, y después la ventana filtrada.
    val shown: UiAsync<List<T>> = when (loaded) {
        is UiAsync.Loading, UiAsync.Idle -> UiAsync.Loading
        is UiAsync.Error -> UiAsync.Error(loaded.error)
        else -> if (window.items.isEmpty()) UiAsync.Empty else UiAsync.Success(window.items)
    }

    WorkspaceSection(
        icon = icon,
        role = SemanticRole.INFO,
        title = title,
        modifier = modifier,
        action = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs), verticalAlignment = Alignment.CenterVertically) {
                when {
                    refreshing -> WorkspacePill(text = "Actualizando…")
                    totalDownloaded > 0 -> WorkspacePill(text = totalDownloaded.toString())
                }
                DesktopIconButton(
                    label = "Actualizar $noun",
                    size = Sizing.iconButtonSmall,
                    enabled = !refreshing,
                    onClick = onRefresh,
                ) { AppIconImage(AppIcon.REFRESH, size = 14.dp) }
            }
        },
    ) {
        if (refreshFailed) {
            WorkspaceNotice(
                tone = WorkspaceNoticeTone.WARNING,
                title = "No se pudo actualizar",
                detail = "Se enseña lo último que se leyó. Vuelve a intentarlo con el botón de actualizar.",
            )
        }
        // Crear está SIEMPRE a la vista, no sólo cuando la lista está vacía: con la lista poblada se abre sola la primera
        // ficha, y un «nuevo» que sólo existe en el estado vacío deja sin forma de crear el segundo.
        if (onCreate != null && (loaded is UiAsync.Success || loaded is UiAsync.Empty)) {
            SecondaryButton(
                text = createLabel,
                onClick = onCreate,
                modifier = Modifier.fillMaxWidth(),
                size = ButtonSize.COMPACT,
                icon = { AppIconImage(AppIcon.ADD, size = 14.dp) },
            )
        }
        GlassSearchBar(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = searchPlaceholder,
            modifier = Modifier.fillMaxWidth(),
        )
        if (filters.isNotEmpty()) {
            // Las píldoras bajan de línea: con seis tipos de producto no caben en una columna de 330 dp, y una fila que
            // se corta esconde justo los filtros que no se ven.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                filters.forEach { option ->
                    WorkspaceFilterPill(
                        label = option.label,
                        selected = option.key == selectedFilter,
                        onClick = { onFilterChange(option.key) },
                    )
                }
                if (hasFilters) {
                    GhostButton(
                        text = "Limpiar",
                        size = ButtonSize.COMPACT,
                        onClick = {
                            onSearchChange("")
                            onFilterChange(null)
                        },
                    )
                }
            }
        }
        if (loaded is UiAsync.Success || loaded is UiAsync.Empty) {
            WorkspacePager(
                rangeText = workspaceRangeText(window.offset, window.items.size, window.total, noun),
                pageIndex = window.pageIndex,
                pageCount = window.pageCount,
                onPageChange = onPageChange,
            )
        }
        AsyncStatePane(
            state = shown,
            emptyTitle = if (hasFilters) "Sin resultados" else emptyTitle,
            emptyMessage = if (hasFilters) "Nada coincide con la búsqueda y el filtro." else emptyMessage,
            emptyIcon = if (hasFilters) AppIcon.SEARCH else icon,
            loadingRows = 5,
            onRetry = onRefresh,
            emptyAction = {
                if (!hasFilters && onCreate != null) {
                    PrimaryButton(text = createLabel, onClick = onCreate, size = ButtonSize.COMPACT)
                }
            },
            // La lista sigue en pantalla cuando llegan datos nuevos: guardar no la devuelve arriba ni la funde consigo misma.
            stableContent = true,
        ) { rows ->
            val scroll = rememberScrollState()
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(scroll).padding(end = WorkspaceSizing.scrollbarGutter),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    rows.forEach { item ->
                        val spec = row(item)
                        // La identidad es el registro, no la posicion: filtrar no «cambia» la fila que queda arriba, y la que
                        // se acaba de guardar (lo que dice la fila es otro) se resalta un instante.
                        key(spec.key) {
                            WorkspaceCatalogRow(
                                title = spec.title,
                                selected = spec.key == selectedKey,
                                onClick = { onSelect(spec.key) },
                                icon = rowIcon,
                                eyebrow = spec.eyebrow,
                                description = spec.description,
                                meta = spec.meta,
                                trailing = { StatusChip(status = spec.status, tone = spec.statusTone) },
                                changeKey = spec,
                            )
                        }
                    }
                }
                DesktopVerticalScrollbar(scrollState = scroll, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
}

/** Qué dice la barra de guardado de una ficha. Función pura: se prueba sin Compose. */
internal data class EditorSaveStatus(
    val message: String,
    val tone: WorkspaceSaveTone,
    val detail: String?,
    val prominent: Boolean,
    val canSave: Boolean,
    val canDiscard: Boolean,
    val primaryLabel: String,
)

/**
 * [problem] es el primer motivo por el que no se puede guardar (o `null`). Sin cambios, la barra es discreta; con cambios
 * se hace prominente y ofrece descartar. Mientras guarda no se puede ni guardar ni descartar.
 */
internal fun editorSaveStatus(
    isNew: Boolean,
    dirty: Boolean,
    problem: String?,
    saving: Boolean,
    createLabel: String,
    saveLabel: String = "Guardar",
    /** El último guardado falló: su motivo, ya dicho para leerlo. Manda sobre todo lo demás salvo «guardando». */
    failure: String? = null,
    /** El último guardado salió bien y la ficha sigue como quedó: «Producto guardado.». */
    savedMessage: String? = null,
    /** El servidor conservó algún campo que se envió vacío (ver `keptByServerNotice`). */
    notice: String? = null,
): EditorSaveStatus {
    val label = if (isNew) createLabel else saveLabel
    return when {
        saving -> EditorSaveStatus("Guardando…", WorkspaceSaveTone.INFO, null, prominent = dirty, canSave = false, canDiscard = false, primaryLabel = label)
        // La barra es lo único que no se desplaza con el formulario: un fallo dicho al final de una ficha larga quedaba
        // fuera de la vista, y quien pulsaba Guardar no veía cambiar nada.
        failure != null -> EditorSaveStatus(
            message = "No se pudo guardar.",
            tone = WorkspaceSaveTone.DANGER,
            detail = failure,
            prominent = true,
            canSave = problem == null,
            canDiscard = dirty,
            primaryLabel = label,
        )
        !dirty && !isNew && notice != null -> EditorSaveStatus(
            message = "Guardado con una salvedad.",
            tone = WorkspaceSaveTone.WARNING,
            detail = notice,
            prominent = true,
            canSave = false,
            canDiscard = false,
            primaryLabel = label,
        )
        !dirty && !isNew && savedMessage != null ->
            EditorSaveStatus(savedMessage, WorkspaceSaveTone.SUCCESS, null, false, canSave = false, canDiscard = false, primaryLabel = label)
        !dirty && !isNew -> EditorSaveStatus("Sin cambios pendientes.", WorkspaceSaveTone.SUCCESS, null, false, canSave = false, canDiscard = false, primaryLabel = label)
        problem != null -> EditorSaveStatus(problem, WorkspaceSaveTone.WARNING, null, prominent = true, canSave = false, canDiscard = dirty, primaryLabel = label)
        isNew -> EditorSaveStatus("Listo para crear.", WorkspaceSaveTone.SUCCESS, "Todavía no existe en el servidor.", true, canSave = true, canDiscard = dirty, primaryLabel = label)
        else -> EditorSaveStatus("Cambios sin guardar.", WorkspaceSaveTone.WARNING, "Lo que se ve en la ficha todavía no está en el servidor.", true, canSave = true, canDiscard = true, primaryLabel = label)
    }
}

@Composable
internal fun StoreSaveBar(status: EditorSaveStatus, saving: Boolean, onSave: () -> Unit, onDiscard: () -> Unit, modifier: Modifier = Modifier) {
    WorkspaceSaveBar(
        message = status.message,
        primaryLabel = status.primaryLabel,
        onSave = onSave,
        modifier = modifier,
        tone = status.tone,
        detail = status.detail,
        prominent = status.prominent,
        canSave = status.canSave,
        saving = saving,
        onDiscard = if (status.canDiscard) onDiscard else null,
    )
}

/** Confirmación al abrir otra ficha con cambios sin guardar. No borra nada del servidor: lo que se pierde es lo escrito. */
@Composable
internal fun UnsavedChangesDialog(visible: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    if (!visible) return
    ConfirmDialog(
        title = "Cambios sin guardar",
        message = "Hay cambios en esta ficha que todavía no se han guardado. Si continúas, se pierden.",
        confirmLabel = "Descartar y continuar",
        dismissLabel = "Seguir aquí",
        destructive = false,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * Lo que la barra de guardado tiene que decir del último guardado, sacado de su estado: el motivo de un fallo (ya dicho
 * para leerlo) o el «guardado» de un éxito. Va en la barra y no al pie del formulario porque la barra no se desplaza.
 */
internal fun saveFailureText(state: UiAsync<Unit>): String? = (state as? UiAsync.Error)?.error?.humanMessage()

internal fun savedText(state: UiAsync<Unit>, text: String): String? = text.takeIf { state is UiAsync.Success }

/** Estado de un elemento comercial dicho con palabra, icono (lo pone `StatusChip`) y tono. */
internal data class CommerceStatus(val label: String, val tone: StatusTone)

internal fun commerceStatus(active: Boolean, visible: Boolean = true, highlighted: Boolean = false): CommerceStatus = when {
    !active -> CommerceStatus("Inactivo", StatusTone.NEGATIVE)
    !visible -> CommerceStatus("Oculto", StatusTone.ATTENTION)
    highlighted -> CommerceStatus("Destacado", StatusTone.POSITIVE)
    else -> CommerceStatus("Activo", StatusTone.POSITIVE)
}

/** Entero no negativo de un campo de texto, o `null` si no lo es. */
internal fun parseNonNegativeInt(text: String): Int? {
    val t = text.trim()
    if (t.isEmpty() || t.length > 9 || !t.all { it.isDigit() }) return null
    return t.toInt()
}

/**
 * Fila de un elemento hijo de la ficha (una tarifa, un beneficio): qué es, cuánto vale, su estado y «Editar».
 *
 * El dato es opaco (nivel 2) sobre el cristal de la sección; la fila que se está editando se marca con el acento para que
 * el formulario de debajo no parezca huérfano.
 */
@Composable
internal fun StoreChildRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    editing: Boolean = false,
    /** `null` = fila de sólo lectura (un uso de promoción): no se enseña un botón que no haría nada. */
    onEdit: (() -> Unit)? = null,
    subtitle: String? = null,
    status: CommerceStatus? = null,
    editLabel: String = "Editar",
    editIcon: AppIcon = AppIcon.EDIT,
) {
    val tokens = GlassTheme.tokens
    val background by animateColorAsState(
        targetValue = if (editing) tokens.surfaceSelected else tokens.surfaceSolid,
        animationSpec = motionTween(OposiMotion.Fast),
    )
    val borderColor by animateColorAsState(
        targetValue = if (editing) tokens.accent.copy(alpha = 0.45f) else tokens.border,
        animationSpec = motionTween(OposiMotion.Fast),
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(Radius.card)
            .background(background)
            .border(1.dp, borderColor, Radius.card)
            .padding(Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = tokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = tokens.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (status != null) StatusChip(status = status.label, tone = status.tone)
        if (onEdit != null) {
            SecondaryButton(
                text = editLabel,
                size = ButtonSize.COMPACT,
                enabled = !editing,
                onClick = onEdit,
                icon = { AppIconImage(editIcon, size = 14.dp) },
            )
        }
    }
}
