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
import com.oposicontrol.admin.domain.model.CommercialPlan

internal const val CATALOG_PLANS_PANE_TAG: String = "catalog-plans-pane"
internal const val CATALOG_PLAN_EDITOR_TAG: String = "catalog-plan-editor"

/**
 * Planes de suscripción y sus beneficios, sobre el mismo workspace de tres columnas que Productos.
 *
 * Un plan no tiene precio: lo fijan las tarifas del producto de suscripción que apunta a él. Aquí se decide qué planes
 * existen, en qué orden y qué da cada uno.
 */
@Composable
fun CommercialPlansScreen(viewModel: CommercialPlansViewModel) {
    val state by viewModel.state.collectAsState()
    CommercialPlansContent(state = state, onIntent = viewModel::dispatch)
}

@Composable
internal fun CommercialPlansContent(
    state: CommercialPlansState,
    onIntent: (CommercialPlansIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    WorkspaceLayout(
        modifier = modifier,
        catalog = { paneModifier ->
            StoreCatalogPane(
                icon = AppIcon.PLANS,
                title = "Planes",
                noun = "planes",
                loaded = state.plans,
                items = state.visiblePlans,
                totalDownloaded = state.allPlans.size,
                pageIndex = state.pageIndex,
                onPageChange = { onIntent(CommercialPlansIntent.SetPage(it)) },
                searchQuery = state.searchQuery,
                onSearchChange = { onIntent(CommercialPlansIntent.UpdateSearch(it)) },
                searchPlaceholder = "Buscar por nombre o código…",
                filters = PLAN_STATUS_FILTERS,
                selectedFilter = state.statusFilter,
                onFilterChange = { onIntent(CommercialPlansIntent.UpdateFilter(it)) },
                selectedKey = state.selectedId.takeUnless { state.creatingNew },
                onSelect = { onIntent(CommercialPlansIntent.Open(it)) },
                onRefresh = { onIntent(CommercialPlansIntent.Refresh) },
                row = ::planRowSpec,
                emptyTitle = "Sin planes",
                emptyMessage = "Todavía no hay ningún plan de suscripción configurado.",
                modifier = paneModifier.testTag(CATALOG_PLANS_PANE_TAG),
                onCreate = { onIntent(CommercialPlansIntent.Open(null)) },
                createLabel = "Nuevo plan",
                refreshing = state.refreshing,
                refreshFailed = state.refreshFailed,
            )
        },
        editor = { paneModifier, contentWidth, fillsHeight ->
            PlanEditorPane(state, onIntent, contentWidth, fillsHeight, paneModifier.testTag(CATALOG_PLAN_EDITOR_TAG))
        },
        rail = { paneModifier, scrolls ->
            WorkspaceRail(modifier = paneModifier, scrolls = scrolls) {
                val facts = planFacts(state.selected)
                if (facts.isNotEmpty()) {
                    WorkspaceSection(icon = AppIcon.ANALYTICS, role = SemanticRole.INSIGHT, title = "Vista rápida") {
                        WorkspaceFacts(facts)
                    }
                }
                WorkspaceSection(icon = AppIcon.INFO, role = SemanticRole.INFO, title = "Información") {
                    WorkspaceInfoLines(planInformationLines)
                }
            }
        },
    )

    UnsavedChangesDialog(
        visible = state.pendingOpen != null,
        onConfirm = { onIntent(CommercialPlansIntent.ConfirmOpen) },
        onDismiss = { onIntent(CommercialPlansIntent.DismissOpen) },
    )
}

@Composable
private fun PlanEditorPane(
    state: CommercialPlansState,
    onIntent: (CommercialPlansIntent) -> Unit,
    contentWidth: Dp,
    fillsHeight: Boolean,
    modifier: Modifier = Modifier,
) {
    val plan = state.selected
    val open = plan != null || state.creatingNew
    val saving = state.actionState is UiAsync.Loading
    val status = editorSaveStatus(
        isNew = state.creatingNew,
        dirty = state.dirty,
        problem = state.problem,
        saving = saving,
        createLabel = "Crear plan",
    )

    WorkspaceEditor(
        modifier = modifier,
        fillsHeight = fillsHeight,
        header = {
            WorkspaceEditorHeader(
                icon = AppIcon.PLANS,
                role = if (state.creatingNew) SemanticRole.BRAND else SemanticRole.INFO,
                title = when {
                    state.creatingNew -> "Nuevo plan"
                    plan == null -> "Planes"
                    else -> state.draft.name.ifBlank { "Plan sin nombre" }
                },
                subtitle = when {
                    state.creatingNew -> "Todavía no existe en el servidor: se crea al guardar."
                    plan == null -> "Ningún plan abierto."
                    else -> plan.code
                },
            ) {
                if (open) {
                    val live = commerceStatus(state.draft.active, state.draft.visible)
                    StatusDotChip(label = live.label, tone = live.tone)
                    WorkspacePill(text = planLevelLabel(state.draft.level))
                }
            }
        },
        tabs = if (plan == null) {
            // Un plan que aún no existe no tiene beneficios que enseñar: sólo su ficha.
            null
        } else {
            {
                val compactTabs = contentWidth < WorkspaceSizing.tabIconsMinWidth
                DesktopTabs(
                    tabs = listOf(
                        DesktopTab(PlanTab.PLAN.id, PlanTab.PLAN.label, AppIcon.PLANS.takeUnless { compactTabs }),
                        DesktopTab(
                            id = PlanTab.BENEFITS.id,
                            label = PlanTab.BENEFITS.label,
                            icon = AppIcon.CHECK.takeUnless { compactTabs },
                            badge = plan.benefits.size.takeIf { it > 0 }?.toString(),
                        ),
                    ),
                    selectedId = state.tab.id,
                    onSelect = { onIntent(CommercialPlansIntent.SelectTab(PlanTab.fromId(it))) },
                    variant = DesktopTabsVariant.WORKSPACE,
                )
            }
        },
        footer = if (open && (plan == null || state.tab == PlanTab.PLAN)) {
            {
                StoreSaveBar(
                    status = status,
                    saving = saving,
                    onSave = { onIntent(CommercialPlansIntent.Save) },
                    onDiscard = { onIntent(CommercialPlansIntent.Discard) },
                )
            }
        } else {
            null
        },
    ) {
        when {
            !open -> EmptyStateCard(
                title = "Sin plan abierto",
                message = "Elige un plan del catálogo para editar su ficha y sus beneficios, o crea uno nuevo.",
                icon = AppIcon.PLANS,
                action = { PrimaryButton(text = "Nuevo plan", onClick = { onIntent(CommercialPlansIntent.Open(null)) }) },
            )
            plan != null && state.tab == PlanTab.BENEFITS -> BenefitsSection(plan, state, onIntent)
            else -> PlanFormSections(state, onIntent)
        }
    }
}

@Composable
private fun PlanFormSections(state: CommercialPlansState, onIntent: (CommercialPlansIntent) -> Unit) {
    val draft = state.draft
    val update: (PlanDraft) -> Unit = { onIntent(CommercialPlansIntent.UpdateDraft(it)) }
    FormSection(
        title = "Identificación",
        description = "Cómo se llama el plan y con qué código lo reconoce la app.",
        icon = AppIcon.PLANS,
        iconRole = SemanticRole.INFO,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.field)) {
            LabeledField(
                label = "Nombre",
                value = draft.name,
                onValueChange = { update(draft.copy(name = it)) },
                placeholder = "Ej. Razonado",
                required = true,
            )
            LabeledField(
                label = "Código",
                value = draft.code,
                onValueChange = { update(draft.copy(code = it)) },
                placeholder = "Ej. razonado",
                required = true,
                readOnly = !state.creatingNew,
                supportingText = if (state.creatingNew) {
                    "Se guarda en minúsculas. Letras, números, punto, guion y guion bajo. No se puede cambiar después."
                } else {
                    "Identifica el plan en la app. No se puede cambiar."
                },
            )
            LabeledField(
                label = "Descripción",
                value = draft.description,
                onValueChange = { update(draft.copy(description = it)) },
                placeholder = "Qué ofrece este plan",
                singleLine = false,
                minLines = 3,
                maxWidth = Sizing.inputDescriptionMaxWidth,
            )
        }
    }

    FormSection(
        title = "Orden",
        description = "Qué plan incluye a cuál y en qué orden se enseñan.",
        icon = AppIcon.ANALYTICS,
        iconRole = SemanticRole.INSIGHT,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.field)) {
            DropdownField(
                label = "Nivel",
                selectedLabel = planLevelLabel(draft.level),
                options = PLAN_LEVELS.map { it.toString() to planLevelLabel(it) },
                onSelect = { value -> value?.toIntOrNull()?.let { update(draft.copy(level = it)) } },
                required = true,
                supportingText = "Un nivel mayor incluye lo que ofrece el menor.",
            )
            LabeledField(
                label = "Posición",
                value = draft.position,
                onValueChange = { update(draft.copy(position = it)) },
                placeholder = "1",
                supportingText = "Entero no negativo. Ordena los planes de menor a mayor.",
            )
        }
    }

    FormSection(
        title = "Visibilidad",
        description = "Si se puede contratar y si aparece en la app.",
        icon = AppIcon.VIEW,
        iconRole = SemanticRole.INFO,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            GlassToggleRow(
                title = "Activo",
                description = "Un plan inactivo no se puede contratar.",
                checked = draft.active,
                onCheckedChange = { update(draft.copy(active = it)) },
            )
            GlassToggleRow(
                title = "Visible en la app",
                description = "Controla si el plan se enseña a los usuarios. Requiere que esté activo.",
                checked = draft.visible,
                onCheckedChange = { update(draft.copy(visible = it)) },
            )
            if (draft.visible && !draft.active) {
                WorkspaceNotice(
                    tone = WorkspaceNoticeTone.WARNING,
                    title = "Visible pero inactivo",
                    detail = "Se enseñaría y no se podría contratar: el servidor rechaza este guardado. Ocúltalo o actívalo.",
                )
            }
        }
    }

    StoreSaveFeedback(state.actionState, state.notice, savingText = "Guardando el plan…", savedText = "Plan guardado.")
}

/**
 * Beneficios del plan. Cada uno es un código y EXACTAMENTE un valor; el tipo se elige al crearlo y después sólo cambia el
 * valor, que es lo único que el servidor permite sin dejar el beneficio con dos valores.
 */
@Composable
private fun BenefitsSection(plan: CommercialPlan, state: CommercialPlansState, onIntent: (CommercialPlansIntent) -> Unit) {
    val draft = state.benefitDraft
    FormSection(
        title = "Beneficios",
        description = "Lo que da este plan. Cada beneficio lleva un único valor.",
        icon = AppIcon.CHECK,
        iconRole = SemanticRole.SUCCESS,
        action = {
            if (draft == null) {
                SecondaryButton(
                    text = "Nuevo beneficio",
                    size = ButtonSize.COMPACT,
                    onClick = { onIntent(CommercialPlansIntent.EditBenefit(null)) },
                    icon = { AppIconImage(AppIcon.ADD, size = 14.dp) },
                )
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            if (plan.benefits.isEmpty() && draft == null) {
                EmptyStateCard(
                    title = "Sin beneficios",
                    message = "Este plan todavía no tiene ningún beneficio configurado.",
                    icon = AppIcon.CHECK,
                )
            }
            plan.benefits.forEach { benefit ->
                StoreChildRow(
                    title = benefit.code,
                    subtitle = benefit.kind()?.label ?: "Revisa este beneficio: no lleva un único valor.",
                    value = benefitValueLabel(benefit),
                    editing = draft?.id == benefit.id,
                    onEdit = { onIntent(CommercialPlansIntent.EditBenefit(benefit.id)) },
                )
            }
        }
    }

    if (draft != null) {
        val update: (BenefitDraft) -> Unit = { onIntent(CommercialPlansIntent.UpdateBenefit(it)) }
        FormSection(
            title = if (draft.isNew) "Nuevo beneficio" else "Editar beneficio",
            description = if (draft.isNew) {
                "El código y el tipo de valor identifican el beneficio y no se pueden cambiar después."
            } else {
                "${draft.code} · ${draft.kind.label}"
            },
            icon = AppIcon.EDIT,
            iconRole = SemanticRole.INFO,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.field)) {
                if (draft.isNew) {
                    LabeledField(
                        label = "Código del beneficio",
                        value = draft.code,
                        onValueChange = { update(draft.copy(code = it)) },
                        placeholder = "Ej. examenes_mensuales",
                        required = true,
                        supportingText = "Es el nombre con el que la app pregunta por este beneficio.",
                    )
                }
                if (!draft.kindLocked) {
                    DropdownField(
                        label = "Tipo de valor",
                        selectedLabel = draft.kind.label,
                        options = BenefitKind.entries.map { it.id to it.label },
                        onSelect = { value -> value?.let { update(draft.copy(kind = BenefitKind.fromId(it), text = "")) } },
                        required = true,
                    )
                }
                when (draft.kind) {
                    BenefitKind.BOOLEAN -> GlassToggleRow(
                        title = "Incluido en el plan",
                        description = if (draft.booleanValue) "El plan da este beneficio." else "El plan no da este beneficio.",
                        checked = draft.booleanValue,
                        onCheckedChange = { update(draft.copy(booleanValue = it)) },
                    )
                    BenefitKind.INTEGER -> LabeledField(
                        label = "Valor",
                        value = draft.text,
                        onValueChange = { update(draft.copy(text = it)) },
                        placeholder = "Ej. 10",
                        required = true,
                        supportingText = "Número entero.",
                    )
                    BenefitKind.NUMERIC -> LabeledField(
                        label = "Valor",
                        value = draft.text,
                        onValueChange = { update(draft.copy(text = it)) },
                        placeholder = "Ej. 0,5",
                        required = true,
                        supportingText = "Número con coma o punto decimal.",
                    )
                    BenefitKind.TEXT -> LabeledField(
                        label = "Valor",
                        value = draft.text,
                        onValueChange = { update(draft.copy(text = it)) },
                        placeholder = "Texto del beneficio",
                        required = true,
                    )
                }
                FormFeedback(state = state.benefitActionState, loadingText = "Guardando el beneficio…", successText = "Beneficio guardado.")
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton(
                        text = if (draft.isNew) "Crear beneficio" else "Guardar beneficio",
                        onClick = { onIntent(CommercialPlansIntent.SaveBenefit) },
                        loading = state.benefitActionState is UiAsync.Loading,
                        enabled = state.benefitActionState !is UiAsync.Loading,
                    )
                    SecondaryButton(text = "Cancelar", onClick = { onIntent(CommercialPlansIntent.CancelBenefit) })
                }
            }
        }
    } else {
        FormFeedback(state = state.benefitActionState, loadingText = "Guardando el beneficio…", successText = "Beneficio guardado.")
    }
}
