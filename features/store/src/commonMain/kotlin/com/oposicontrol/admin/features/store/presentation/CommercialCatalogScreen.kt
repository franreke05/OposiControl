package com.oposicontrol.admin.features.store.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.DesktopTab
import com.oposicontrol.admin.core.ui.components.DesktopTabs
import com.oposicontrol.admin.core.ui.components.DesktopTabsVariant
import com.oposicontrol.admin.core.ui.components.DropdownField
import com.oposicontrol.admin.core.ui.components.EmptyStateCard
import com.oposicontrol.admin.core.ui.components.FormFeedback
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GlassToggleRow
import com.oposicontrol.admin.core.ui.components.LabeledField
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.StatusDotChip
import com.oposicontrol.admin.core.ui.components.WorkspaceEditor
import com.oposicontrol.admin.core.ui.components.WorkspaceEditorHeader
import com.oposicontrol.admin.core.ui.components.WorkspaceFacts
import com.oposicontrol.admin.core.ui.components.WorkspaceInfoLines
import com.oposicontrol.admin.core.ui.components.WorkspaceLayout
import com.oposicontrol.admin.core.ui.components.WorkspaceNotice
import com.oposicontrol.admin.core.ui.components.WorkspaceNoticeTone
import com.oposicontrol.admin.core.ui.components.WorkspacePill
import com.oposicontrol.admin.core.ui.components.WorkspaceRail
import com.oposicontrol.admin.core.ui.components.WorkspaceSection
import com.oposicontrol.admin.core.ui.components.WorkspaceSizing
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.CommercialProduct
import com.oposicontrol.admin.domain.model.formatPriceCents
import com.oposicontrol.admin.core.ui.components.ConfirmDialog
import com.oposicontrol.admin.core.ui.components.DangerOutlineButton
import com.oposicontrol.admin.domain.model.CommercialTariff

internal const val CATALOG_PRODUCTS_PANE_TAG: String = "catalog-products-pane"
internal const val CATALOG_PRODUCT_EDITOR_TAG: String = "catalog-product-editor"

/**
 * Catálogo comercial: productos y sus tarifas.
 *
 * El mismo workspace de tres columnas que Contenido. Lo que se edita aquí es lo que ve el usuario final en OposiBot, así
 * que la ficha enseña el precio ya formateado y, en los packs, el coste por ticket: sin esa cifra no se sabe de un vistazo
 * si un pack grande compensa frente a uno pequeño, que es la decisión real al fijar precios.
 */
@Composable
fun CommercialCatalogScreen(viewModel: CommercialCatalogViewModel) {
    val state by viewModel.state.collectAsState()
    CommercialCatalogContent(state = state, onIntent = viewModel::dispatch)
}

@Composable
internal fun CommercialCatalogContent(
    state: CommercialCatalogState,
    onIntent: (CommercialCatalogIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    WorkspaceLayout(
        modifier = modifier,
        catalog = { paneModifier ->
            StoreCatalogPane(
                icon = AppIcon.CATALOG,
                title = "Productos",
                noun = "productos",
                loaded = state.products,
                items = state.visibleProducts,
                totalDownloaded = state.allProducts.size,
                pageIndex = state.pageIndex,
                onPageChange = { onIntent(CommercialCatalogIntent.SetPage(it)) },
                searchQuery = state.searchQuery,
                onSearchChange = { onIntent(CommercialCatalogIntent.UpdateSearch(it)) },
                searchPlaceholder = "Buscar por nombre o código…",
                filters = PRODUCT_TYPE_FILTERS,
                selectedFilter = state.typeFilter,
                onFilterChange = { onIntent(CommercialCatalogIntent.UpdateTypeFilter(it)) },
                selectedKey = state.selectedId.takeUnless { state.creatingNew },
                onSelect = { onIntent(CommercialCatalogIntent.Open(it)) },
                onRefresh = { onIntent(CommercialCatalogIntent.Refresh) },
                row = ::productRowSpec,
                emptyTitle = "Sin productos",
                emptyMessage = "Todavía no hay ningún producto comercial configurado.",
                modifier = paneModifier.testTag(CATALOG_PRODUCTS_PANE_TAG),
                onCreate = { onIntent(CommercialCatalogIntent.Open(null)) },
                createLabel = "Nuevo producto",
                refreshing = state.refreshing,
                refreshFailed = state.refreshFailed,
            )
        },
        editor = { paneModifier, contentWidth, fillsHeight ->
            ProductEditorPane(state, onIntent, contentWidth, fillsHeight, paneModifier.testTag(CATALOG_PRODUCT_EDITOR_TAG))
        },
        rail = { paneModifier, scrolls ->
            WorkspaceRail(modifier = paneModifier, scrolls = scrolls) {
                val facts = productFacts(state.draft.toProduct(state.selected) ?: state.selected, state.plans)
                if (facts.isNotEmpty()) {
                    WorkspaceSection(icon = AppIcon.ANALYTICS, role = SemanticRole.INSIGHT, title = "Vista rápida") {
                        WorkspaceFacts(facts)
                    }
                }
                WorkspaceSection(icon = AppIcon.INFO, role = SemanticRole.INFO, title = "Información") {
                    WorkspaceInfoLines(productInformationLines)
                }
            }
        },
    )

    UnsavedChangesDialog(
        visible = state.pendingOpen != null,
        onConfirm = { onIntent(CommercialCatalogIntent.ConfirmOpen) },
        onDismiss = { onIntent(CommercialCatalogIntent.DismissOpen) },
    )

    state.pendingDelete?.let { pending ->
        val product = state.allProducts.firstOrNull { it.id == pending.productId } ?: return@let
        val tariff = pending.tariffId?.let { id -> product.tariffs.firstOrNull { it.id == id } }
        ConfirmDialog(
            title = if (tariff != null) "Eliminar tarifa" else "Eliminar producto",
            message = catalogDeleteMessage(product, tariff),
            confirmLabel = "Eliminar",
            destructive = true,
            onConfirm = { onIntent(CommercialCatalogIntent.ConfirmDelete) },
            onDismiss = { onIntent(CommercialCatalogIntent.DismissDelete) },
        )
    }
}

/** Texto de la confirmación de borrado: qué se borra, qué arrastra y qué alternativa hay. Función pura. */
internal fun catalogDeleteMessage(product: CommercialProduct, tariff: CommercialTariff?): String = buildString {
    if (tariff != null) {
        append("Producto: ${product.name}\n")
        append("Tarifa: ${tariffPeriodLabel(tariff.billingPeriod)} · ${tariffPhaseLabel(tariff.pricePhase)}, ")
        append("${formatPriceCents(tariff.priceCents, tariff.currency)}\n\n")
        append("La app dejará de ofrecer este precio. No se puede deshacer; para retirarla sin borrarla, desactívala.")
    } else {
        append("Producto: ${product.name} (${product.code})\n")
        if (product.tariffs.isNotEmpty()) append("Se borran también sus ${product.tariffs.size} tarifas.\n")
        append("\nDesaparece del catálogo que ve la app y no se puede deshacer. ")
        append("Si alguna suscripción o compra lo usa, el servidor no lo borrará: en ese caso, desactívalo.")
    }
}

@Composable
private fun ProductEditorPane(
    state: CommercialCatalogState,
    onIntent: (CommercialCatalogIntent) -> Unit,
    contentWidth: Dp,
    fillsHeight: Boolean,
    modifier: Modifier = Modifier,
) {
    val product = state.selected
    val open = product != null || state.creatingNew
    val saving = state.actionState is UiAsync.Loading
    val status = editorSaveStatus(
        isNew = state.creatingNew,
        dirty = state.dirty,
        problem = state.problem,
        saving = saving,
        createLabel = "Crear producto",
        failure = saveFailureText(state.actionState),
        savedMessage = savedText(state.actionState, "Producto guardado."),
        notice = state.notice,
    )

    WorkspaceEditor(
        modifier = modifier,
        fillsHeight = fillsHeight,
        header = {
            WorkspaceEditorHeader(
                icon = AppIcon.CATALOG,
                role = if (state.creatingNew) SemanticRole.BRAND else SemanticRole.INFO,
                title = when {
                    state.creatingNew -> "Nuevo producto"
                    product == null -> "Catálogo"
                    else -> state.draft.name.ifBlank { "Producto sin nombre" }
                },
                subtitle = when {
                    state.creatingNew -> "Todavía no existe en el servidor: se crea al guardar."
                    product == null -> "Ningún producto abierto."
                    else -> product.code
                },
            ) {
                if (open) {
                    val live = commerceStatus(state.draft.active, state.draft.visible, state.draft.highlighted)
                    StatusDotChip(label = live.label, tone = live.tone)
                    if (state.draft.type.isNotBlank()) WorkspacePill(text = productTypeLabel(state.draft.type))
                }
                // Sólo un producto que ya existe en el servidor se puede borrar; uno nuevo se descarta.
                if (product != null && !state.creatingNew) {
                    DangerOutlineButton(
                        text = "Eliminar",
                        size = ButtonSize.COMPACT,
                        enabled = !state.deleting && !saving,
                        onClick = { onIntent(CommercialCatalogIntent.AskDeleteProduct) },
                    )
                }
            }
        },
        tabs = if (product == null) {
            // Un producto que aún no existe no tiene tarifas que enseñar: sólo su ficha.
            null
        } else {
            {
                val compactTabs = contentWidth < WorkspaceSizing.tabIconsMinWidth
                DesktopTabs(
                    tabs = listOf(
                        DesktopTab(ProductTab.PRODUCT.id, ProductTab.PRODUCT.label, AppIcon.CATALOG.takeUnless { compactTabs }),
                        DesktopTab(
                            id = ProductTab.TARIFFS.id,
                            label = ProductTab.TARIFFS.label,
                            icon = AppIcon.PLANS.takeUnless { compactTabs },
                            badge = product.tariffs.size.takeIf { it > 0 }?.toString(),
                        ),
                    ),
                    selectedId = state.tab.id,
                    onSelect = { onIntent(CommercialCatalogIntent.SelectTab(ProductTab.fromId(it))) },
                    variant = DesktopTabsVariant.WORKSPACE,
                )
            }
        },
        footer = if (open && (product == null || state.tab == ProductTab.PRODUCT)) {
            {
                StoreSaveBar(
                    status = status,
                    saving = saving,
                    onSave = { onIntent(CommercialCatalogIntent.Save) },
                    onDiscard = { onIntent(CommercialCatalogIntent.Discard) },
                )
            }
        } else {
            null
        },
        // El cuerpo entra al abrir otro producto o cambiar de pestaña; guardar no lo mueve.
        contentKey = state.openSeq to state.tab,
    ) {
        when {
            !open && (state.products is UiAsync.Loading || state.products is UiAsync.Idle) -> EmptyStateCard(
                title = "Cargando el catálogo…",
                message = "La ficha se abre en cuanto llegue la lista de productos.",
                icon = AppIcon.CATALOG,
            )
            !open -> EmptyStateCard(
                title = "Sin producto abierto",
                message = "Elige un producto del catálogo para editar su ficha y sus tarifas, o crea uno nuevo.",
                icon = AppIcon.CATALOG,
                action = { PrimaryButton(text = "Nuevo producto", onClick = { onIntent(CommercialCatalogIntent.Open(null)) }) },
            )
            product != null && state.tab == ProductTab.TARIFFS -> TariffsSection(product, state, onIntent)
            else -> ProductFormSections(state, onIntent)
        }
    }
}

@Composable
private fun ProductFormSections(state: CommercialCatalogState, onIntent: (CommercialCatalogIntent) -> Unit) {
    val draft = state.draft
    val product = state.selected
    FormSection(
        title = "Identificación",
        description = "Cómo se llama el producto y con qué código lo pide la app.",
        icon = AppIcon.CATALOG,
        iconRole = SemanticRole.INFO,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.field)) {
            LabeledField(
                label = "Nombre",
                value = draft.name,
                onValueChange = { onIntent(CommercialCatalogIntent.UpdateName(it)) },
                placeholder = "Ej. 2.600 OposiTickets",
                required = true,
            )
            LabeledField(
                label = "Código",
                value = draft.code,
                onValueChange = { onIntent(CommercialCatalogIntent.UpdateCode(it)) },
                placeholder = "Ej. pack_2600",
                required = true,
                readOnly = !state.creatingNew,
                supportingText = if (state.creatingNew) {
                    "Letras, números, punto, guion y guion bajo. No se puede cambiar después."
                } else {
                    "Identifica el producto en la app. No se puede cambiar."
                },
            )
            DropdownField(
                label = "Tipo de producto",
                selectedLabel = draft.type.takeIf { it.isNotBlank() }?.let(::productTypeLabel).orEmpty(),
                options = PRODUCT_TYPES.map { it to productTypeLabel(it) },
                onSelect = { type -> type?.let { onIntent(CommercialCatalogIntent.UpdateType(it)) } },
                required = true,
                placeholder = "Selecciona un tipo",
            )
            LabeledField(
                label = "Descripción",
                value = draft.description,
                onValueChange = { onIntent(CommercialCatalogIntent.UpdateDescription(it)) },
                placeholder = "Qué incluye este producto",
                singleLine = false,
                minLines = 3,
                maxWidth = Sizing.inputDescriptionMaxWidth,
            )
        }
    }

    FormSection(
        title = "Precio y contenido",
        description = "Lo que cuesta y lo que entrega.",
        icon = AppIcon.TICKETS,
        iconRole = SemanticRole.COMMERCE,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.field)) {
            LabeledField(
                label = "Precio (${product?.currency ?: "EUR"})",
                value = draft.price,
                onValueChange = { onIntent(CommercialCatalogIntent.UpdatePrice(it)) },
                placeholder = "Ej. 12,99",
                supportingText = if (draft.type == "suscripcion") {
                    "En una suscripción el precio real lo fijan las tarifas. Puede quedar en blanco."
                } else {
                    "Con coma o punto y como mucho dos decimales. En blanco si el precio lo fijan las tarifas."
                },
            )
            if (draft.type == "pack_tickets" || draft.ticketAmount.isNotBlank()) {
                LabeledField(
                    label = "OposiTickets que entrega",
                    value = draft.ticketAmount,
                    onValueChange = { onIntent(CommercialCatalogIntent.UpdateTicketAmount(it)) },
                    placeholder = "Ej. 2600",
                    required = draft.type == "pack_tickets",
                    supportingText = product?.centsPerTicket?.let {
                        "Coste guardado por OposiTicket: ${formatCentsPerTicket(it)} ${product.currency}."
                    },
                )
            }
            if (draft.type == "suscripcion") {
                DropdownField(
                    label = "Plan",
                    selectedLabel = state.plans.firstOrNull { it.id == draft.planId }?.name
                        ?: draft.planId?.let { "Plan fuera del catálogo" }.orEmpty(),
                    options = state.plans.map { it.id to it.name },
                    onSelect = { onIntent(CommercialCatalogIntent.UpdatePlan(it)) },
                    required = true,
                    enabled = state.plans.isNotEmpty(),
                    placeholder = "Selecciona un plan",
                    supportingText = "El plan decide qué beneficios da esta suscripción.",
                )
                if (state.plansUnavailable) {
                    WorkspaceNotice(
                        tone = WorkspaceNoticeTone.WARNING,
                        title = "No se pudieron leer los planes",
                        detail = "Sin ellos no se puede elegir el plan de una suscripción. Usa «Actualizar» en el catálogo.",
                    )
                }
            }
        }
    }

    FormSection(
        title = "Visibilidad",
        description = "Si se puede comprar y si aparece en el catálogo de la app.",
        icon = AppIcon.VIEW,
        iconRole = SemanticRole.INFO,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            GlassToggleRow(
                title = "Activo",
                description = "Un producto inactivo no se puede comprar.",
                checked = draft.active,
                onCheckedChange = { onIntent(CommercialCatalogIntent.ToggleActive(it)) },
            )
            GlassToggleRow(
                title = "Visible en el catálogo",
                description = "Controla si aparece en OposiMarket dentro de la app.",
                checked = draft.visible,
                onCheckedChange = { onIntent(CommercialCatalogIntent.ToggleVisible(it)) },
            )
            GlassToggleRow(
                title = "Destacado",
                description = "Muestra la etiqueta promocional. Requiere que sea visible.",
                checked = draft.highlighted,
                onCheckedChange = { onIntent(CommercialCatalogIntent.ToggleHighlighted(it)) },
            )
            if (draft.highlighted && !draft.visible) {
                WorkspaceNotice(
                    tone = WorkspaceNoticeTone.WARNING,
                    title = "Destacado pero no visible",
                    detail = "Una etiqueta sobre un producto oculto no la ve nadie: el servidor rechaza este guardado.",
                )
            }
            LabeledField(
                label = "Etiqueta promocional",
                value = draft.promotionalLabel,
                onValueChange = { onIntent(CommercialCatalogIntent.UpdatePromotionalLabel(it)) },
                placeholder = "Ej. Más elegido",
            )
            LabeledField(
                label = "Posición",
                value = draft.position,
                onValueChange = { onIntent(CommercialCatalogIntent.UpdatePosition(it)) },
                placeholder = "Ej. 1",
                supportingText = "Entero no negativo. Ordena el catálogo de menor a mayor.",
            )
        }
    }

}

/**
 * Tarifas del producto: las que fijan de verdad el precio de un plan (alta frente a renovación, mensual frente a anual).
 * Periodo y fase son la clave natural de la tarifa: se eligen al crearla y después sólo cambia el precio y su estado.
 */
@Composable
private fun TariffsSection(product: CommercialProduct, state: CommercialCatalogState, onIntent: (CommercialCatalogIntent) -> Unit) {
    val draft = state.tariffDraft
    FormSection(
        title = "Tarifas",
        description = "Precio por periodo y fase. La fase separa el alta de la renovación.",
        icon = AppIcon.PLANS,
        iconRole = SemanticRole.COMMERCE,
        action = {
            if (draft == null) {
                SecondaryButton(
                    text = "Nueva tarifa",
                    size = ButtonSize.COMPACT,
                    onClick = { onIntent(CommercialCatalogIntent.EditTariff(null)) },
                    icon = { AppIconImage(AppIcon.ADD, size = 14.dp) },
                )
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            if (product.tariffs.isEmpty() && draft == null) {
                EmptyStateCard(
                    title = "Sin tarifas",
                    message = "Este producto no tiene tarifas: su precio es el de la ficha.",
                    icon = AppIcon.PLANS,
                )
            }
            product.tariffs.forEach { tariff ->
                StoreChildRow(
                    title = "${tariffPeriodLabel(tariff.billingPeriod)} · ${tariffPhaseLabel(tariff.pricePhase)}",
                    subtitle = "Posición ${tariff.position}",
                    value = formatPriceCents(tariff.priceCents, tariff.currency),
                    status = commerceStatus(tariff.active, tariff.visible),
                    editing = draft?.id == tariff.id,
                    onEdit = { onIntent(CommercialCatalogIntent.EditTariff(tariff.id)) },
                    onDelete = { onIntent(CommercialCatalogIntent.AskDeleteTariff(tariff.id)) },
                    deleteEnabled = !state.deleting,
                )
            }
        }
    }

    if (draft != null) {
        FormSection(
            title = if (draft.isNew) "Nueva tarifa" else "Editar tarifa",
            description = if (draft.isNew) {
                "Periodo y fase identifican la tarifa y no se pueden cambiar después."
            } else {
                "${tariffPeriodLabel(draft.period)} · ${tariffPhaseLabel(draft.phase)}"
            },
            icon = AppIcon.EDIT,
            iconRole = SemanticRole.INFO,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.field)) {
                if (draft.isNew) {
                    DropdownField(
                        label = "Periodo de facturación",
                        selectedLabel = draft.period.takeIf { it.isNotBlank() }?.let(::tariffPeriodLabel).orEmpty(),
                        options = TARIFF_PERIODS.map { it to tariffPeriodLabel(it) },
                        onSelect = { value -> value?.let { onIntent(CommercialCatalogIntent.UpdateTariff(draft.copy(period = it))) } },
                        required = true,
                    )
                    DropdownField(
                        label = "Fase de precio",
                        selectedLabel = draft.phase.takeIf { it.isNotBlank() }?.let(::tariffPhaseLabel).orEmpty(),
                        options = TARIFF_PHASES.map { it to tariffPhaseLabel(it) },
                        onSelect = { value -> value?.let { onIntent(CommercialCatalogIntent.UpdateTariff(draft.copy(phase = it))) } },
                        required = true,
                    )
                }
                LabeledField(
                    label = "Precio (${product.currency})",
                    value = draft.price,
                    onValueChange = { onIntent(CommercialCatalogIntent.UpdateTariff(draft.copy(price = it))) },
                    placeholder = "Ej. 12,99",
                    required = true,
                )
                LabeledField(
                    label = "Posición",
                    value = draft.position,
                    onValueChange = { onIntent(CommercialCatalogIntent.UpdateTariff(draft.copy(position = it))) },
                    placeholder = "Ej. 1",
                )
                GlassToggleRow(
                    title = "Activa",
                    description = "Una tarifa inactiva no se puede contratar.",
                    checked = draft.active,
                    onCheckedChange = { onIntent(CommercialCatalogIntent.UpdateTariff(draft.copy(active = it))) },
                )
                GlassToggleRow(
                    title = "Visible",
                    description = "Controla si se enseña en la app.",
                    checked = draft.visible,
                    onCheckedChange = { onIntent(CommercialCatalogIntent.UpdateTariff(draft.copy(visible = it))) },
                )
                FormFeedback(state = state.tariffActionState, loadingText = "Guardando la tarifa…", successText = "Tarifa guardada.")
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton(
                        text = if (draft.isNew) "Crear tarifa" else "Guardar tarifa",
                        onClick = { onIntent(CommercialCatalogIntent.SaveTariff) },
                        loading = state.tariffActionState is UiAsync.Loading,
                        enabled = state.tariffActionState !is UiAsync.Loading,
                    )
                    SecondaryButton(text = "Cancelar", onClick = { onIntent(CommercialCatalogIntent.CancelTariff) })
                }
            }
        }
    } else {
        FormFeedback(state = state.tariffActionState, loadingText = "Guardando la tarifa…", successText = "Tarifa guardada.")
    }
}
