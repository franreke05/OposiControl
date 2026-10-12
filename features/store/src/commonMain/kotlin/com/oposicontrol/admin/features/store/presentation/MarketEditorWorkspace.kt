package com.oposicontrol.admin.features.store.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.EmptyStateCard
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.StatusDotChip
import com.oposicontrol.admin.core.ui.components.WorkspaceEditor
import com.oposicontrol.admin.core.ui.components.WorkspaceEditorHeader
import com.oposicontrol.admin.core.ui.components.WorkspaceFact
import com.oposicontrol.admin.core.ui.components.WorkspaceFacts
import com.oposicontrol.admin.core.ui.components.WorkspaceInfoLines
import com.oposicontrol.admin.core.ui.components.WorkspaceLayout
import com.oposicontrol.admin.core.ui.components.WorkspaceNotice
import com.oposicontrol.admin.core.ui.components.WorkspaceNoticeTone
import com.oposicontrol.admin.core.ui.components.WorkspacePill
import com.oposicontrol.admin.core.ui.components.WorkspaceRail
import com.oposicontrol.admin.core.ui.components.WorkspaceSection

/**
 * Lo que el workspace genérico no puede saber de una entidad: sus textos y cómo se lee. Cada sección de OposiMarket
 * construye el suyo; el marco —catálogo, ficha, barra de guardado, columna de información— es uno solo.
 */
internal class MarketEditorUi<T, D>(
    val icon: AppIcon,
    val title: String,
    /** Nombre en plural y minúscula para el pie de la lista: «packs», «servicios»… */
    val noun: String,
    val searchPlaceholder: String,
    val filters: List<CatalogFilterOption>,
    val row: (T) -> CatalogRowSpec,
    val emptyTitle: String,
    val emptyMessage: String,
    val newLabel: String,
    val createLabel: String,
    /** Título de la ficha cuando el borrador todavía no tiene nombre. */
    val namelessTitle: String,
    val closedMessage: String,
    val draftTitle: (D) -> String,
    val subtitle: (T) -> String,
    val draftStatus: (D) -> CommerceStatus,
    val draftPill: (D) -> String?,
    val facts: (T?) -> List<WorkspaceFact>,
    val information: List<String>,
    val savedText: String,
    val tag: String,
)

/**
 * Un catálogo editable de OposiMarket sobre el workspace de tres columnas.
 *
 * [tabs] y [alternateContent] son para las fichas con más de una cara (los usos de una promoción): cuando hay contenido
 * alternativo se enseña en lugar del formulario y la barra de guardado desaparece, porque en esa cara no hay nada que
 * guardar.
 */
@Composable
internal fun <T, D> MarketEditorWorkspace(
    editor: MarketEditor<T, D>,
    spec: MarketEditorSpec<T, D>,
    ui: MarketEditorUi<T, D>,
    onAction: (EditorAction<D>) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    tabs: (@Composable (contentWidth: Dp) -> Unit)? = null,
    alternateContent: (@Composable () -> Unit)? = null,
    form: @Composable (draft: D, isNew: Boolean, base: T?, update: (D) -> Unit) -> Unit,
) {
    val selected = editor.selected(spec)
    val open = selected != null || editor.creatingNew
    val saving = editor.actionState is UiAsync.Loading
    val dirty = editor.dirty(spec)

    WorkspaceLayout(
        modifier = modifier,
        catalog = { paneModifier ->
            StoreCatalogPane(
                icon = ui.icon,
                title = ui.title,
                noun = ui.noun,
                loaded = editor.items,
                items = editor.visible(spec),
                totalDownloaded = editor.all.size,
                pageIndex = editor.pageIndex,
                onPageChange = { onAction(EditorAction.SetPage(it)) },
                searchQuery = editor.searchQuery,
                onSearchChange = { onAction(EditorAction.UpdateSearch(it)) },
                searchPlaceholder = ui.searchPlaceholder,
                filters = ui.filters,
                selectedFilter = editor.filter,
                onFilterChange = { onAction(EditorAction.UpdateFilter(it)) },
                selectedKey = editor.selectedId.takeUnless { editor.creatingNew },
                onSelect = { onAction(EditorAction.Open(it)) },
                onRefresh = onRefresh,
                row = ui.row,
                emptyTitle = ui.emptyTitle,
                emptyMessage = ui.emptyMessage,
                modifier = paneModifier.testTag("${ui.tag}-pane"),
                onCreate = { onAction(EditorAction.Open(null)) },
                createLabel = ui.newLabel,
                refreshing = editor.refreshing,
                refreshFailed = editor.refreshFailed,
            )
        },
        editor = { paneModifier, contentWidth, fillsHeight ->
            WorkspaceEditor(
                modifier = paneModifier.testTag("${ui.tag}-editor"),
                fillsHeight = fillsHeight,
                header = {
                    WorkspaceEditorHeader(
                        icon = ui.icon,
                        role = if (editor.creatingNew) SemanticRole.BRAND else SemanticRole.INFO,
                        title = when {
                            editor.creatingNew -> ui.newLabel
                            selected == null -> ui.title
                            else -> ui.draftTitle(editor.draft).ifBlank { ui.namelessTitle }
                        },
                        subtitle = when {
                            editor.creatingNew -> "Todavía no existe en el servidor: se crea al guardar."
                            selected == null -> "Nada abierto."
                            else -> ui.subtitle(selected)
                        },
                    ) {
                        if (open) {
                            val live = ui.draftStatus(editor.draft)
                            StatusDotChip(label = live.label, tone = live.tone)
                            ui.draftPill(editor.draft)?.let { WorkspacePill(text = it) }
                        }
                    }
                },
                tabs = if (tabs != null && selected != null) {
                    { tabs(contentWidth) }
                } else {
                    null
                },
                footer = if (open && alternateContent == null) {
                    {
                        StoreSaveBar(
                            status = editorSaveStatus(
                                isNew = editor.creatingNew,
                                dirty = dirty,
                                problem = editor.problem(spec),
                                saving = saving,
                                createLabel = ui.createLabel,
                                failure = saveFailureText(editor.actionState),
                                savedMessage = savedText(editor.actionState, ui.savedText),
                                notice = editor.notice,
                            ),
                            saving = saving,
                            onSave = { onAction(EditorAction.Save) },
                            onDiscard = { onAction(EditorAction.Discard) },
                        )
                    }
                } else {
                    null
                },
                // Packs, servicios, promociones y avisos comparten esta ficha y, con ella, el mismo movimiento: el cuerpo
                // entra al abrir otro registro; guardar no lo mueve.
                contentKey = editor.openSeq to (alternateContent != null),
            ) {
                // Lo que cambia el sentido de «guardar» en estas cuatro secciones va en la ficha, no sólo en la columna
                // de información: esa columna no se enseña por debajo de 1500 dp de ancho, y el aviso no puede perderse.
                if (open && alternateContent == null) {
                    WorkspaceNotice(tone = WorkspaceNoticeTone.INFO, title = "La app todavía no usa estos datos", detail = MARKET_NOT_CONSUMED_LINE)
                }
                when {
                    !open && (editor.items is UiAsync.Loading || editor.items is UiAsync.Idle) -> EmptyStateCard(
                        title = "Cargando…",
                        message = "La ficha se abre en cuanto llegue la lista.",
                        icon = ui.icon,
                    )
                    !open -> EmptyStateCard(
                        title = "Nada abierto",
                        message = ui.closedMessage,
                        icon = ui.icon,
                        action = { PrimaryButton(text = ui.newLabel, onClick = { onAction(EditorAction.Open(null)) }) },
                    )
                    alternateContent != null -> alternateContent()
                    else -> form(editor.draft, editor.creatingNew, selected) { onAction(EditorAction.UpdateDraft(it)) }
                }
            }
        },
        rail = { paneModifier, scrolls ->
            WorkspaceRail(modifier = paneModifier, scrolls = scrolls) {
                val facts = ui.facts(spec.toEntity(editor.draft, selected) ?: selected)
                if (facts.isNotEmpty()) {
                    WorkspaceSection(icon = AppIcon.ANALYTICS, role = SemanticRole.INSIGHT, title = "Vista rápida") {
                        WorkspaceFacts(facts)
                    }
                }
                WorkspaceSection(icon = AppIcon.INFO, role = SemanticRole.INFO, title = "Información") {
                    WorkspaceInfoLines(ui.information)
                }
            }
        },
    )

    UnsavedChangesDialog(
        visible = editor.pendingOpen != null,
        onConfirm = { onAction(EditorAction.ConfirmOpen) },
        onDismiss = { onAction(EditorAction.DismissOpen) },
    )
}
