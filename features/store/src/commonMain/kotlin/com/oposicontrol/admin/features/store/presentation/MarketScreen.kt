package com.oposicontrol.admin.features.store.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.DesktopTab
import com.oposicontrol.admin.core.ui.components.DesktopTabs
import com.oposicontrol.admin.core.ui.components.DesktopTabsVariant
import com.oposicontrol.admin.core.ui.components.DropdownField
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GlassToggleRow
import com.oposicontrol.admin.core.ui.components.Help
import com.oposicontrol.admin.core.ui.components.LabeledField
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.WorkspaceFact
import com.oposicontrol.admin.core.ui.components.WorkspaceFacts
import com.oposicontrol.admin.core.ui.components.WorkspaceNotice
import com.oposicontrol.admin.core.ui.components.WorkspaceNoticeTone
import com.oposicontrol.admin.core.ui.components.WorkspaceSizing
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.MarketPopupRule
import com.oposicontrol.admin.domain.model.MarketPromotion
import com.oposicontrol.admin.domain.model.MarketPromotionUsesPage
import com.oposicontrol.admin.domain.model.MarketService
import com.oposicontrol.admin.domain.model.MarketTicketPack

/**
 * OposiMarket: escaparate de exámenes publicados, packs de OposiTickets, servicios, promociones y reglas de pop-up.
 *
 * Cinco vistas en una pantalla con secciones, no cinco entradas de menú: quien ajusta precios suele mirarlas juntas. El
 * escaparate es consulta; las otras cuatro son catálogos editables sobre el mismo workspace que Catálogo y Contenido, y
 * comparten un único motor ([MarketEditor]) para que las cuatro se comporten igual.
 */
@Composable
fun MarketScreen(
    viewModel: MarketViewModel,
    /** Pestaña con la que abrir; `null` respeta la que hubiera. */
    initialSection: MarketSection? = null,
    /**
     * Lleva a Catálogo, a la pestaña donde se edita lo que enseña [MarketSection]. `null` = no se ofrece el enlace. La
     * tienda no edita: quien quiera cambiar algo lo hace allí.
     */
    onEditInCatalog: ((MarketSection) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(initialSection) {
        if (initialSection != null && initialSection != state.section) {
            viewModel.dispatch(MarketIntent.SelectSection(initialSection))
        }
    }

    MarketContent(state = state, onIntent = viewModel::dispatch, onEditInCatalog = onEditInCatalog)
}

/** Las pestañas de la tienda, en el orden en que se enseñan. Packs no está: un pack es un producto (ver [MarketSection]). */
internal val SHOWCASE_SECTIONS: List<MarketSection> =
    listOf(MarketSection.EXAMS, MarketSection.PRODUCTS, MarketSection.SERVICES, MarketSection.PROMOTIONS, MarketSection.POPUPS)

/**
 * OposiMarket: la tienda tal como queda publicada. SOLO CONSULTA (decisión del propietario, 2026-10-14): aquí se mira qué
 * hay en vigor; crear, editar y desactivar es cosa de Catálogo. Por eso esta pantalla no despacha ninguna intención de
 * edición: sólo cambia de pestaña, filtra los exámenes y refresca.
 */
@Composable
internal fun MarketContent(
    state: MarketState,
    onIntent: (MarketIntent) -> Unit,
    modifier: Modifier = Modifier,
    onEditInCatalog: ((MarketSection) -> Unit)? = null,
    today: String = todayIsoDate(),
) {
    // Una pestaña que ya no se ofrece (Packs) se enseña como la primera, en vez de dejar la pantalla sin contenido.
    val section = state.section.takeIf { it in SHOWCASE_SECTIONS } ?: MarketSection.EXAMS
    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            DesktopTabs(
                modifier = Modifier.weight(1f),
                tabs = SHOWCASE_SECTIONS.map { DesktopTab(it.name, it.label(), it.icon()) },
                selectedId = section.name,
                onSelect = { id ->
                    SHOWCASE_SECTIONS.firstOrNull { it.name == id }?.let { onIntent(MarketIntent.SelectSection(it)) }
                },
            )
            if (section != MarketSection.EXAMS && onEditInCatalog != null) {
                SecondaryButton(
                    text = "Editar en Catálogo",
                    onClick = { onEditInCatalog(section) },
                    size = ButtonSize.COMPACT,
                    icon = { AppIconImage(AppIcon.EDIT, size = 14.dp) },
                )
            }
            SecondaryButton(
                text = "Actualizar",
                onClick = { onIntent(MarketIntent.Refresh) },
                size = ButtonSize.COMPACT,
                icon = { AppIconImage(AppIcon.REFRESH, size = 14.dp) },
            )
        }

        val sectionModifier = Modifier.weight(1f)
        when (section) {
            MarketSection.EXAMS -> PublishedExamsSection(state = state, onIntent = onIntent, modifier = sectionModifier)
            else -> MarketShowcaseSection(showcaseOf(section, state, today), onRetry = { onIntent(MarketIntent.Refresh) }, modifier = sectionModifier)
        }
    }
}

/**
 * UN editor de los que antes vivían en OposiMarket (servicios, promociones o avisos), para montarlo como pestaña de
 * Catálogo. Sin fila de pestañas propia: la pone el hub. Al entrar selecciona su sección, que es lo que dispara la lectura.
 */
@Composable
fun MarketCatalogEditor(
    viewModel: MarketViewModel,
    section: MarketSection,
    modifier: Modifier = Modifier,
    /** Abre la ficha de un usuario desde un uso de promoción. `null` = no se ofrece el enlace. */
    onOpenUser: ((String) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(section) { viewModel.dispatch(MarketIntent.SelectSection(section)) }
    MarketCatalogEditorContent(state = state, section = section, onIntent = viewModel::dispatch, modifier = modifier, onOpenUser = onOpenUser)
}

/** El editor, por ESTADO: el banco de pruebas visual lo renderiza contra estados fabricados, sin Ktor ni Koin. */
@Composable
internal fun MarketCatalogEditorContent(
    state: MarketState,
    section: MarketSection,
    onIntent: (MarketIntent) -> Unit,
    modifier: Modifier = Modifier,
    onOpenUser: ((String) -> Unit)? = null,
) {
    when (section) {
        MarketSection.SERVICES -> MarketEditorWorkspace(
            editor = state.services,
            spec = ServiceSpec,
            ui = serviceUi,
            onAction = { onIntent(MarketIntent.Services(it)) },
            onRefresh = { onIntent(MarketIntent.Refresh) },
            modifier = modifier,
        ) { draft, isNew, _, update -> ServiceForm(draft, isNew, update) }
        MarketSection.PROMOTIONS -> PromotionsWorkspace(state, onIntent, onOpenUser, modifier)
        MarketSection.POPUPS -> MarketEditorWorkspace(
            editor = state.popups,
            spec = PopupSpec,
            ui = popupUi,
            onAction = { onIntent(MarketIntent.Popups(it)) },
            onRefresh = { onIntent(MarketIntent.Refresh) },
            modifier = modifier,
        ) { draft, isNew, _, update -> PopupForm(draft, isNew, update) }
        // Exámenes y productos tienen su propia pantalla; packs ya no se edita desde el panel.
        MarketSection.EXAMS, MarketSection.PRODUCTS, MarketSection.PACKS -> Unit
    }
}

// =====================================================================================================================
// Servicios
// =====================================================================================================================

private val serviceUi = MarketEditorUi<MarketService, ServiceDraft>(
    icon = AppIcon.MARKET,
    title = "Servicios",
    noun = "servicios",
    searchPlaceholder = "Buscar por nombre o tipo…",
    filters = ACTIVE_FILTERS_MASCULINE,
    row = ::serviceRowSpec,
    emptyTitle = "Sin servicios",
    emptyMessage = "No hay potenciadores, protectores ni otros servicios configurados.",
    newLabel = "Nuevo servicio",
    createLabel = "Crear servicio",
    namelessTitle = "Servicio sin nombre",
    closedMessage = "Elige un servicio de la lista para editarlo, o crea uno nuevo.",
    draftTitle = { it.name },
    subtitle = { it.code },
    draftStatus = { commerceStatus(it.active) },
    draftPill = { draft -> draft.type.takeIf { it.isNotBlank() }?.let(::serviceTypeLabel) },
    facts = ::serviceFacts,
    information = serviceInformationLines,
    savedText = "Servicio guardado.",
    tag = "market-services",
)

@Composable
private fun ServiceForm(draft: ServiceDraft, isNew: Boolean, update: (ServiceDraft) -> Unit) {
    FormSection(
        title = "Identificación",
        description = "Qué servicio es y con qué código se reconoce.",
        icon = AppIcon.MARKET,
        iconRole = SemanticRole.INFO,
        help = Help.potenciadores,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.field)) {
            LabeledField(
                label = "Nombre",
                value = draft.name,
                onValueChange = { update(draft.copy(name = it)) },
                placeholder = "Ej. Protector de racha",
                required = true,
            )
            CodeField(draft.code, isNew, placeholder = "Ej. protector_racha_7d", entity = "el servicio") { update(draft.copy(code = it)) }
            DropdownField(
                label = "Tipo de servicio",
                selectedLabel = draft.type.takeIf { it.isNotBlank() }?.let(::serviceTypeLabel).orEmpty(),
                options = SERVICE_TYPES.map { it to serviceTypeLabel(it) },
                onSelect = { type -> type?.let { update(draft.copy(type = it)) } },
                required = true,
                placeholder = "Selecciona un tipo",
            )
            DescriptionField(draft.description, "Qué hace este servicio") { update(draft.copy(description = it)) }
        }
    }
    FormSection(
        title = "Precio",
        description = "En OposiTickets, en dinero o de las dos formas.",
        icon = AppIcon.TICKETS,
        iconRole = SemanticRole.COMMERCE,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.field)) {
            LabeledField(
                label = "Precio en OposiTickets",
                value = draft.priceTickets,
                onValueChange = { update(draft.copy(priceTickets = it)) },
                placeholder = "Ej. 30",
                supportingText = "Entero no negativo. En blanco si no se puede pagar con OposiTickets.",
            )
            LabeledField(
                label = "Precio (EUR)",
                value = draft.price,
                onValueChange = { update(draft.copy(price = it)) },
                placeholder = "Ej. 1,99",
                supportingText = "Con coma o punto y como mucho dos decimales. En blanco si no se vende por dinero.",
            )
            if (draft.active && draft.priceTickets.isBlank() && draft.price.isBlank()) {
                WorkspaceNotice(
                    tone = WorkspaceNoticeTone.WARNING,
                    title = "Activo y sin precio",
                    detail = "No se podría adquirir de ninguna forma: el servidor rechaza este guardado. Ponle un precio o desactívalo.",
                )
            }
        }
    }
    ActiveSection(
        description = "Si el servicio se puede adquirir.",
        toggleDescription = "Un servicio inactivo no se puede adquirir.",
        active = draft.active,
        onChange = { update(draft.copy(active = it)) },
    )
}

// =====================================================================================================================
// Promociones
// =====================================================================================================================

private val promotionUi = MarketEditorUi<MarketPromotion, PromotionDraft>(
    icon = AppIcon.PLANS,
    title = "Promociones",
    noun = "promociones",
    searchPlaceholder = "Buscar por nombre o código…",
    filters = ACTIVE_FILTERS_FEMININE,
    row = ::promotionRowSpec,
    emptyTitle = "Sin promociones",
    emptyMessage = "No se ha creado ninguna promoción todavía.",
    newLabel = "Nueva promoción",
    createLabel = "Crear promoción",
    namelessTitle = "Promoción sin nombre",
    closedMessage = "Elige una promoción de la lista para editarla, o crea una nueva.",
    draftTitle = { it.name },
    subtitle = { it.code },
    draftStatus = { feminineStatus(it.active) },
    draftPill = { draft ->
        parseBenefitNumeric(draft.value)?.takeIf { draft.discountType in DISCOUNT_TYPES }?.let { promotionValueLabel(draft.discountType, it) }
    },
    facts = ::promotionFacts,
    information = promotionInformationLines,
    savedText = "Promoción guardada.",
    tag = "market-promotions",
)

@Composable
private fun PromotionsWorkspace(
    state: MarketState,
    onIntent: (MarketIntent) -> Unit,
    onOpenUser: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val promotion = state.promotions.selected(PromotionSpec)
    val showsUses = promotion != null && state.promotionTab == PromotionTab.USES
    MarketEditorWorkspace(
        editor = state.promotions,
        spec = PromotionSpec,
        ui = promotionUi,
        onAction = { onIntent(MarketIntent.Promotions(it)) },
        onRefresh = { onIntent(MarketIntent.Refresh) },
        modifier = modifier,
        tabs = { contentWidth -> PromotionTabs(state, promotion, contentWidth, onIntent) },
        alternateContent = if (showsUses) {
            { PromotionUsesSection(state, onIntent, onOpenUser) }
        } else {
            null
        },
    ) { draft, isNew, _, update -> PromotionForm(draft, isNew, update) }
}

@Composable
private fun PromotionTabs(state: MarketState, promotion: MarketPromotion?, contentWidth: Dp, onIntent: (MarketIntent) -> Unit) {
    val compactTabs = contentWidth < WorkspaceSizing.tabIconsMinWidth
    DesktopTabs(
        tabs = listOf(
            DesktopTab(PromotionTab.PROMOTION.id, PromotionTab.PROMOTION.label, AppIcon.PLANS.takeUnless { compactTabs }),
            DesktopTab(
                id = PromotionTab.USES.id,
                label = PromotionTab.USES.label,
                icon = AppIcon.USERS.takeUnless { compactTabs },
                badge = promotion?.uses?.takeIf { it > 0 }?.let(::formatCount),
            ),
        ),
        selectedId = state.promotionTab.id,
        onSelect = { onIntent(MarketIntent.SelectPromotionTab(PromotionTab.fromId(it))) },
        variant = DesktopTabsVariant.WORKSPACE,
    )
}

@Composable
private fun PromotionForm(draft: PromotionDraft, isNew: Boolean, update: (PromotionDraft) -> Unit) {
    FormSection(
        title = "Identificación",
        description = "Cómo se llama la promoción y qué código la activa.",
        icon = AppIcon.PLANS,
        iconRole = SemanticRole.INFO,
        help = Help.promociones,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.field)) {
            LabeledField(
                label = "Nombre",
                value = draft.name,
                onValueChange = { update(draft.copy(name = it)) },
                placeholder = "Ej. Vuelta al estudio",
                required = true,
            )
            LabeledField(
                label = "Código",
                value = draft.code,
                onValueChange = { update(draft.copy(code = it)) },
                placeholder = "Ej. OTONO25",
                required = true,
                readOnly = !isNew,
                supportingText = if (isNew) {
                    "Se guarda en mayúsculas. Letras, números, punto, guion y guion bajo. No se puede cambiar después."
                } else {
                    "Identifica la promoción. No se puede cambiar."
                },
            )
        }
    }
    FormSection(
        title = "Descuento",
        description = "Qué da la promoción.",
        icon = AppIcon.TICKETS,
        iconRole = SemanticRole.COMMERCE,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.field)) {
            DropdownField(
                label = "Tipo de descuento",
                selectedLabel = draft.discountType.takeIf { it.isNotBlank() }?.let(::discountTypeLabel).orEmpty(),
                options = DISCOUNT_TYPES.map { it to discountTypeLabel(it) },
                onSelect = { type -> type?.let { update(draft.copy(discountType = it)) } },
                required = true,
                placeholder = "Selecciona un tipo",
            )
            LabeledField(
                label = "Valor",
                value = draft.value,
                onValueChange = { update(draft.copy(value = it)) },
                placeholder = "Ej. 15",
                required = true,
                supportingText = when (draft.discountType) {
                    "porcentaje" -> "Porcentaje que se descuenta. Por ejemplo, 15 es un 15 %."
                    "tickets_extra" -> "OposiTickets que se regalan con la compra. Número entero."
                    "cantidad" -> "Número no negativo. El servidor lo guarda tal cual: la unidad la fija quien aplique la promoción."
                    else -> "Número no negativo."
                },
            )
            val percent = parseBenefitNumeric(draft.value)
            if (draft.discountType == "porcentaje" && percent != null && percent > 100) {
                WorkspaceNotice(
                    tone = WorkspaceNoticeTone.WARNING,
                    title = "Más del 100 %",
                    detail = "Un porcentaje mayor que 100 descontaría más de lo que cuesta. El servidor lo admite: revisa que sea lo que quieres.",
                )
            }
        }
    }
    FormSection(
        title = "Validez",
        description = "Entre qué fechas se puede usar. Sin fechas, vale mientras esté activa.",
        icon = AppIcon.CLOCK,
        iconRole = SemanticRole.INFO,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.field)) {
            LabeledField(
                label = "Válida desde",
                value = draft.validFrom,
                onValueChange = { update(draft.copy(validFrom = it)) },
                placeholder = "AAAA-MM-DD",
                supportingText = "Día de inicio (00:00 UTC). En blanco, sin fecha de inicio.",
            )
            LabeledField(
                label = "Válida hasta",
                value = draft.validUntil,
                onValueChange = { update(draft.copy(validUntil = it)) },
                placeholder = "AAAA-MM-DD",
                supportingText = "Último día incluido (hasta las 23:59:59 UTC). En blanco, sin fecha de fin.",
            )
            GlassToggleRow(
                title = "Activa",
                description = "Una promoción inactiva no se puede usar aunque esté dentro de sus fechas.",
                checked = draft.active,
                onCheckedChange = { update(draft.copy(active = it)) },
            )
        }
    }
}

/**
 * Usos de la promoción abierta: los tres contadores (conteos exactos del servidor sobre TODOS los usos) y la lista, más
 * reciente primero, que se va pidiendo por páginas. Sólo lectura: los usos los escribe la liquidación de una compra.
 */
@Composable
private fun PromotionUsesSection(state: MarketState, onIntent: (MarketIntent) -> Unit, onOpenUser: ((String) -> Unit)?) {
    FormSection(
        title = "Usos registrados",
        description = "Quién ha usado esta promoción y qué pasó después. Más recientes primero.",
        icon = AppIcon.USERS,
        iconRole = SemanticRole.INSIGHT,
    ) {
        AsyncStatePane(
            state = state.promotionUses,
            emptyTitle = "Sin usos",
            emptyMessage = "Nadie ha usado esta promoción todavía.",
            emptyIcon = AppIcon.USERS,
            loadingRows = 4,
            onRetry = { onIntent(MarketIntent.LoadPromotionUses) },
        ) { page ->
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                WorkspaceFacts(promotionUseFacts(page))
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    page.items.forEach { use ->
                        StoreChildRow(
                            title = promotionUseTitle(use),
                            subtitle = promotionUseOutcome(use),
                            value = promotionUseDiscount(use),
                            onEdit = onOpenUser?.let { open -> { open(use.userId) } },
                            editLabel = "Ver usuario",
                            editIcon = AppIcon.USERS,
                        )
                    }
                }
                if (state.moreUsesFailed) {
                    WorkspaceNotice(
                        tone = WorkspaceNoticeTone.WARNING,
                        title = "No se pudieron cargar más usos",
                        detail = "Los que ya se ven siguen siendo válidos. Vuelve a intentarlo.",
                    )
                }
                if (page.nextCursor != null) {
                    SecondaryButton(
                        text = if (state.loadingMoreUses) "Cargando…" else "Cargar más usos",
                        size = ButtonSize.COMPACT,
                        enabled = !state.loadingMoreUses,
                        loading = state.loadingMoreUses,
                        onClick = { onIntent(MarketIntent.LoadMorePromotionUses) },
                    )
                }
            }
        }
    }
}

/** Contadores de usos. Lo que se ve en la lista puede ser menos que el total: se dice cuántos hay cargados. */
internal fun promotionUseFacts(page: MarketPromotionUsesPage): List<WorkspaceFact> = listOf(
    WorkspaceFact("Usos", formatCount(page.uses)),
    WorkspaceFact("Generaron compra", formatCount(page.conversions)),
    WorkspaceFact("Renovaron después", formatCount(page.renewals)),
    WorkspaceFact("En esta lista", "${formatCount(page.items.size)} de ${formatCount(maxOf(page.uses, page.items.size))}"),
)

// =====================================================================================================================
// Pop-ups
// =====================================================================================================================

private val popupUi = MarketEditorUi<MarketPopupRule, PopupDraft>(
    icon = AppIcon.EVENTS,
    title = "Reglas de pop-up",
    noun = "reglas",
    searchPlaceholder = "Buscar por título o código…",
    filters = ACTIVE_FILTERS_FEMININE,
    row = ::popupRowSpec,
    emptyTitle = "Sin reglas de pop-up",
    emptyMessage = "No hay ninguna regla: la app no tiene ningún pop-up comercial configurado.",
    newLabel = "Nueva regla",
    createLabel = "Crear regla",
    namelessTitle = "Regla sin título",
    closedMessage = "Elige una regla de la lista para editarla, o crea una nueva.",
    draftTitle = { it.title },
    subtitle = { it.code },
    draftStatus = { feminineStatus(it.active) },
    draftPill = { draft -> parseNonNegativeInt(draft.priority)?.let { "Prioridad $it" } },
    facts = ::popupFacts,
    information = popupInformationLines,
    savedText = "Regla guardada.",
    tag = "market-popups",
)

@Composable
private fun PopupForm(draft: PopupDraft, isNew: Boolean, update: (PopupDraft) -> Unit) {
    FormSection(
        title = "Mensaje",
        description = "Lo que el usuario lee en el pop-up.",
        icon = AppIcon.EVENTS,
        iconRole = SemanticRole.INFO,
        help = Help.reglasPopup,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.field)) {
            LabeledField(
                label = "Título",
                value = draft.title,
                onValueChange = { update(draft.copy(title = it)) },
                placeholder = "Ej. ¿Te quedas sin OposiTickets?",
                required = true,
            )
            CodeField(draft.code, isNew, placeholder = "Ej. sin_tickets_bloqueo", entity = "la regla") { update(draft.copy(code = it)) }
            LabeledField(
                label = "Mensaje",
                value = draft.message,
                onValueChange = { update(draft.copy(message = it)) },
                placeholder = "El texto del pop-up",
                required = true,
                singleLine = false,
                minLines = 3,
                maxWidth = Sizing.inputDescriptionMaxWidth,
            )
            LabeledField(
                label = "Botón principal",
                value = draft.primaryCta,
                onValueChange = { update(draft.copy(primaryCta = it)) },
                placeholder = "Ej. Ver packs",
                supportingText = "Texto del botón principal. Opcional.",
            )
            LabeledField(
                label = "Botón secundario",
                value = draft.secondaryCta,
                onValueChange = { update(draft.copy(secondaryCta = it)) },
                placeholder = "Ej. Ahora no",
                supportingText = "Texto del botón secundario. Opcional.",
            )
        }
    }
    FormSection(
        title = "Cuándo aparece",
        description = "El evento que lo dispara y dónde.",
        icon = AppIcon.CLOCK,
        iconRole = SemanticRole.INSIGHT,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.field)) {
            LabeledField(
                label = "Evento disparador",
                value = draft.trigger,
                onValueChange = { update(draft.copy(trigger = it)) },
                placeholder = "Ej. sin_tickets",
                required = true,
                supportingText = "El nombre del evento por el que la app preguntará por esta regla cuando la consuma.",
            )
            LabeledField(
                label = "Pantalla",
                value = draft.screen,
                onValueChange = { update(draft.copy(screen = it)) },
                placeholder = "Ej. market",
                supportingText = "Pantalla de la app donde puede aparecer. En blanco, cualquiera.",
            )
            DropdownField(
                label = "Momento",
                selectedLabel = popupMomentLabel(draft.moment),
                options = listOf<Pair<String?, String>>(null to popupMomentLabel(null)) + POPUP_MOMENTS.map { it to popupMomentLabel(it) },
                onSelect = { update(draft.copy(moment = it)) },
            )
        }
    }
    FormSection(
        title = "A quién y con qué frecuencia",
        description = "Para no enseñarle siempre lo mismo a la misma persona.",
        icon = AppIcon.USERS,
        iconRole = SemanticRole.INFO,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.field)) {
            DropdownField(
                label = "Tipo de examen",
                selectedLabel = popupExamTypeLabel(draft.targetExamType),
                options = listOf<Pair<String?, String>>(null to popupExamTypeLabel(null)) + POPUP_EXAM_TYPES.map { it to popupExamTypeLabel(it) },
                onSelect = { update(draft.copy(targetExamType = it)) },
            )
            LabeledField(
                label = "Plan mínimo",
                value = draft.minimumPlan,
                onValueChange = { update(draft.copy(minimumPlan = it)) },
                placeholder = "Ej. razonado",
                supportingText = "Código del plan a partir del cual se enseña. En blanco, a cualquiera.",
            )
            LabeledField(
                label = "Frecuencia mínima (horas)",
                value = draft.frequencyHours,
                onValueChange = { update(draft.copy(frequencyHours = it)) },
                placeholder = "Ej. $POPUP_DEFAULT_FREQUENCY_HOURS",
                required = draft.active,
                supportingText = "Horas que deben pasar antes de repetirlo al mismo usuario. Obligatoria si la regla está activa.",
            )
            LabeledField(
                label = "Prioridad",
                value = draft.priority,
                onValueChange = { update(draft.copy(priority = it)) },
                placeholder = "Ej. $POPUP_DEFAULT_PRIORITY",
                supportingText = "Entero no negativo. Con varias reglas para el mismo disparador, gana la mayor.",
            )
            GlassToggleRow(
                title = "Activa",
                description = "Una regla inactiva no enseña ningún pop-up.",
                checked = draft.active,
                onCheckedChange = { update(draft.copy(active = it)) },
            )
        }
    }
}

// =====================================================================================================================
// Piezas compartidas por los formularios
// =====================================================================================================================

/** El código: se elige al crear y después es de sólo lectura, porque identifica a la entidad. */
@Composable
private fun CodeField(value: String, isNew: Boolean, placeholder: String, entity: String, onChange: (String) -> Unit) {
    LabeledField(
        label = "Código",
        value = value,
        onValueChange = onChange,
        placeholder = placeholder,
        required = true,
        readOnly = !isNew,
        supportingText = if (isNew) {
            "Letras, números, punto, guion y guion bajo. No se puede cambiar después."
        } else {
            "Identifica $entity. No se puede cambiar."
        },
    )
}

@Composable
private fun DescriptionField(value: String, placeholder: String, onChange: (String) -> Unit) {
    LabeledField(
        label = "Descripción",
        value = value,
        onValueChange = onChange,
        placeholder = placeholder,
        singleLine = false,
        minLines = 3,
        maxWidth = Sizing.inputDescriptionMaxWidth,
    )
}

@Composable
private fun ActiveSection(description: String, toggleDescription: String, active: Boolean, onChange: (Boolean) -> Unit) {
    FormSection(title = "Disponibilidad", description = description, icon = AppIcon.VIEW, iconRole = SemanticRole.INFO) {
        GlassToggleRow(title = "Activo", description = toggleDescription, checked = active, onCheckedChange = onChange)
    }
}

internal fun MarketSection.label(): String = when (this) {
    MarketSection.EXAMS -> "Exámenes"
    MarketSection.PRODUCTS -> "Productos y packs"
    MarketSection.PACKS -> "Packs OT"
    MarketSection.SERVICES -> "Servicios"
    MarketSection.PROMOTIONS -> "Promociones"
    MarketSection.POPUPS -> "Avisos"
}

private fun MarketSection.icon(): AppIcon = when (this) {
    MarketSection.EXAMS -> AppIcon.EXAMS
    MarketSection.PRODUCTS -> AppIcon.CATALOG
    MarketSection.PACKS -> AppIcon.TICKETS
    MarketSection.SERVICES -> AppIcon.MARKET
    MarketSection.PROMOTIONS -> AppIcon.PLANS
    MarketSection.POPUPS -> AppIcon.EVENTS
}
