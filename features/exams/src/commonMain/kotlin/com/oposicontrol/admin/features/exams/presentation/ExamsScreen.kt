package com.oposicontrol.admin.features.exams.presentation

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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.oposicontrol.admin.core.ui.components.AdminTable
import com.oposicontrol.admin.core.ui.components.AdminTableColumn
import com.oposicontrol.admin.core.ui.components.AdminTableRow
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.ConfirmDialog
import com.oposicontrol.admin.core.ui.components.CrudSplitLayout
import com.oposicontrol.admin.core.ui.components.DesktopIconButton
import com.oposicontrol.admin.core.ui.components.DesktopTab
import com.oposicontrol.admin.core.ui.components.DesktopTabs
import com.oposicontrol.admin.core.ui.components.FormFeedback
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GhostButton
import com.oposicontrol.admin.core.ui.components.GlassActionButton
import com.oposicontrol.admin.core.ui.components.GlassChip
import com.oposicontrol.admin.core.ui.components.GlassSearchBar
import com.oposicontrol.admin.core.ui.components.GlassSurface
import com.oposicontrol.admin.core.ui.components.GlassTextField
import com.oposicontrol.admin.core.ui.components.GlassToggleRow
import com.oposicontrol.admin.core.ui.components.Help
import com.oposicontrol.admin.core.ui.components.HelpTopic
import com.oposicontrol.admin.core.ui.components.InfoHelpButton
import com.oposicontrol.admin.core.ui.components.humanMessage
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.components.StatusTone
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.Community
import com.oposicontrol.admin.domain.model.ContentExamType
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.model.SpecialtyCapabilities

/**
 * Exámenes.
 *
 * Segunda pasada: sólo UI/UX. La lógica (auditoría de contrato, endpoints
 * reales de publicar/despublicar/archivar, tipo limitado por capacidades,
 * nº de preguntas derivado) se dio por aceptada y no se toca aquí — ver los
 * comentarios en `ExamsViewModel.kt` para esas reglas.
 *
 * Lo que cambia en esta pasada es puramente de acabado: la cabecera del
 * detalle, los filtros del listado, los selectores de especialidad/CCAA y la
 * barra de acciones del formulario. El detalle de qué se movió y por qué está
 * en cada composable de abajo.
 */
@Composable
fun ExamsScreen(
    viewModel: ExamsViewModel,
) {
    val state by viewModel.state.collectAsState()
    ExamsContent(state = state, onIntent = viewModel::dispatch)
}

@Composable
fun ExamsContent(
    state: ExamsState,
    onIntent: (ExamsIntent) -> Unit,
) {
    CrudSplitLayout(
        modifier = Modifier.fillMaxSize(),
        listPane = { ExamsListPane(state = state, onIntent = onIntent) },
        detailPane = {
            ExamDetailPane(
                state = state,
                onIntent = onIntent,
                // El parámetro se llama `onRequestArchive` y ahora manda de
                // verdad la intención de archivar: antes mandaba la de «borrar»,
                // que archivaba.
                onRequestArchive = { onIntent(ExamsIntent.RequestArchiveSelectedExam) },
            )
        },
    )

    // El diálogo de confirmación es el mismo mecanismo que usan Rafaga/Pregunta/
    // Especialidad (pendingDeleteTarget + ConfirmPendingDelete en el ViewModel)
    // — no uno nuevo sólo para Exámenes, para no duplicar el mensaje y el estado
    // de "hay un borrado pendiente" en dos sitios. Esta pantalla puede producir
    // dos de esos targets: archivar el examen y quitar su portada.
    when (state.pendingDeleteTarget) {
        PendingDeleteTarget.ArchiveExam -> ConfirmDialog(
            title = "Archivar examen",
            message = state.pendingDeleteMessage,
            confirmLabel = "Archivar",
            dismissLabel = "No archivar",
            onConfirm = { onIntent(ExamsIntent.ConfirmPendingDelete) },
            onDismiss = { onIntent(ExamsIntent.DismissPendingDelete) },
        )
        // Mismo par de diálogos que el workspace, y con el mismo texto: los dos
        // salen del ViewModel, así que las dos pantallas no pueden divergir.
        PendingDeleteTarget.DeleteExamForever -> ConfirmDialog(
            title = "Eliminar el examen para siempre",
            message = state.pendingDeleteMessage,
            confirmLabel = "Sí, eliminar",
            dismissLabel = "No eliminar",
            onConfirm = { onIntent(ExamsIntent.ConfirmPendingDelete) },
            onDismiss = { onIntent(ExamsIntent.DismissPendingDelete) },
        )
        PendingDeleteTarget.ExamCover -> ConfirmDialog(
            title = "Quitar portada",
            message = state.pendingDeleteMessage,
            confirmLabel = "Quitar",
            onConfirm = { onIntent(ExamsIntent.ConfirmPendingDelete) },
            onDismiss = { onIntent(ExamsIntent.DismissPendingDelete) },
        )
        else -> Unit
    }
}

// ---------------------------------------------------------------------------
// Listado
// ---------------------------------------------------------------------------

private enum class ExamStatusFilter(val label: String, val wireValue: String) {
    TODOS("Todos", "all"),
    BORRADOR("Borrador", "draft"),
    PUBLICADO("Publicado", "published"),
    ARCHIVADO("Archivado", "archived"),
}

/**
 * Toolbar local + listado.
 *
 * A propósito NO es un `FilterBar` (ese componente envuelve todo en su propia
 * `GlassSurface`, una tarjeta grande) — con la búsqueda global ya pintando su
 * propio panel justo encima de este contenido, dos tarjetas grandes apiladas
 * se leen como "dos buscadores". Aquí el buscador es una única línea y los
 * filtros van sueltos debajo, sin envoltorio, para que se note que son los
 * filtros de esta lista y no otro buscador.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExamsListPane(
    state: ExamsState,
    onIntent: (ExamsIntent) -> Unit,
) {
    var typeFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var specialtyFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var yearFilter by rememberSaveable { mutableStateOf<Int?>(null) }

    val statusFilter = ExamStatusFilter.entries.firstOrNull { it.wireValue == state.statusFilter }
        ?: ExamStatusFilter.TODOS
    val specialties = (state.specialties as? UiAsync.Success)?.value.orEmpty()
    val availableYears = (state.exams as? UiAsync.Success)?.value.orEmpty()
        .mapNotNull { it.year }
        .distinct()
        .sortedDescending()
    val hasActiveFilters = typeFilter != null || specialtyFilter != null || yearFilter != null ||
        statusFilter != ExamStatusFilter.TODOS || state.searchQuery.isNotBlank()

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxSize()) {
        GlassSearchBar(
            value = state.searchQuery,
            onValueChange = { onIntent(ExamsIntent.UpdateSearch(it)) },
            placeholder = "Buscar examen...",
            modifier = Modifier.fillMaxWidth(),
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            FilterDropdown(
                label = "Especialidad",
                selectedLabel = specialties.firstOrNull { it.id == specialtyFilter }?.name,
                options = listOf(null to "Cualquier especialidad") + specialties.map { it.id to it.name },
                onSelect = { specialtyFilter = it },
            )
            // Tipo y Estado eran chips: al lado de Especialidad/Año (desplegables)
            // leían como un mismo grupo de opciones. Todo el mismo control ahora.
            FilterDropdown(
                label = "Tipo",
                selectedLabel = ContentExamType.entries.firstOrNull { it.apiValue == typeFilter }?.label,
                options = listOf(null to "Todos") + ContentExamType.entries.map { it.apiValue to it.label },
                onSelect = { typeFilter = it },
            )
            FilterDropdown(
                label = "Estado",
                selectedLabel = statusFilter.label.takeIf { statusFilter != ExamStatusFilter.TODOS },
                options = ExamStatusFilter.entries.map { it.wireValue to it.label },
                onSelect = { value -> onIntent(ExamsIntent.UpdateStatusFilter(value ?: ExamStatusFilter.TODOS.wireValue)) },
            )
            FilterDropdown(
                label = "Año",
                selectedLabel = yearFilter?.toString(),
                options = listOf(null to "Cualquier año") + availableYears.map { it.toString() to it.toString() },
                onSelect = { yearFilter = it?.toIntOrNull() },
            )
            if (hasActiveFilters) {
                GhostButton(
                    text = "Limpiar filtros",
                    size = ButtonSize.COMPACT,
                    onClick = {
                        typeFilter = null
                        specialtyFilter = null
                        yearFilter = null
                        onIntent(ExamsIntent.UpdateStatusFilter(ExamStatusFilter.TODOS.wireValue))
                        onIntent(ExamsIntent.UpdateSearch(""))
                    },
                )
            }
        }

        AsyncStatePane(
            state = state.exams,
            emptyTitle = "Sin exámenes",
            emptyMessage = "Todavía no se ha creado ningún examen.",
            emptyIcon = AppIcon.EXAMS,
            emptyAction = { PrimaryButton("Crear examen", { onIntent(ExamsIntent.CreateNew) }, size = ButtonSize.COMPACT) },
        ) { exams ->
            val filtered = exams.filter { exam ->
                (typeFilter == null || exam.typeExam == typeFilter) &&
                    (specialtyFilter == null || exam.specialtyId == specialtyFilter) &&
                    (yearFilter == null || exam.year == yearFilter)
            }
            if (filtered.isEmpty()) {
                com.oposicontrol.admin.core.ui.components.EmptyStateCard(
                    title = "Sin resultados",
                    message = "Ningún examen coincide con la búsqueda y los filtros actuales.",
                    icon = AppIcon.SEARCH,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    filtered.forEach { exam ->
                        ExamCard(
                            exam = exam,
                            selected = exam.id == state.selectedExamId,
                            onClick = { onIntent(ExamsIntent.SelectExam(exam.id)) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Desplegable compacto para los filtros del listado — misma altura y forma que
 * un [GlassChip] para que no desentone al lado de los chips de Tipo/Estado,
 * pero resuelve listas que pueden crecer (especialidad, año) sin desbordarse
 * como haría una fila de chips con 20 especialidades reales.
 */
@Composable
private fun FilterDropdown(
    label: String,
    selectedLabel: String?,
    options: List<Pair<String?, String>>,
    onSelect: (String?) -> Unit,
) {
    val tokens = GlassTheme.tokens
    var expanded by remember { mutableStateOf(false) }
    val active = selectedLabel != null
    Box {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = 36.dp)
                .clip(Radius.pill)
                .background(if (active) tokens.accent.copy(alpha = 0.18f) else tokens.glassFill)
                .border(1.dp, if (active) tokens.accent.copy(alpha = 0.55f) else tokens.glassStroke, Radius.pill)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable { expanded = true }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = selectedLabel ?: label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (active) tokens.accent else tokens.textSecondary,
            )
            AppIconImage(AppIcon.CHEVRON_DOWN, size = 14.dp, tint = if (active) tokens.accent else tokens.textSecondary)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, optionLabel) ->
                DropdownMenuItem(text = { Text(optionLabel) }, onClick = { onSelect(value); expanded = false })
            }
        }
    }
}

/**
 * Tarjeta de examen en el listado.
 *
 * Sin UUID a la vista (§17): lo que orienta es el título, la especialidad, el
 * año, el tipo y el estado — nunca un identificador que no le dice nada a quien
 * administra. Seleccionado se marca con una barra coral a la izquierda y un
 * fondo muy tenue, no con un borde naranja alrededor de toda la tarjeta.
 */
@Composable
private fun ExamCard(
    exam: Exam,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = GlassTheme.tokens
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val background by animateColorAsState(
        targetValue = when {
            selected -> tokens.surfaceSelected
            hovered -> tokens.surfaceHover
            else -> tokens.surfaceSolid
        },
        animationSpec = tween(140),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radius.md)
            .background(background)
            .border(1.dp, tokens.border, Radius.md)
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(if (selected) tokens.accent else Color.Transparent),
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = exam.title.ifBlank { "Sin título" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = tokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(exam.specialtyName.ifBlank { null }, exam.year?.toString())
                    .joinToString(" · ")
                    .ifBlank { "Sin especialidad" },
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusChip(status = ContentExamType.fromApiValue(exam.typeExam).label, tone = StatusTone.NEUTRAL)
                StatusChip(status = exam.status.toDisplayLabel(), tone = exam.status.toTone())
            }
            Text(
                text = exam.questionCount.pluralize("pregunta", "preguntas"),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )
        }
    }
}

private fun Int.pluralize(singular: String, plural: String): String =
    "$this " + if (this == 1) singular else plural

private fun ExamStatus.toDisplayLabel(): String = when (this) {
    ExamStatus.DRAFT -> "Borrador"
    ExamStatus.PUBLISHED -> "Publicado"
    ExamStatus.ARCHIVED -> "Archivado"
}

private fun ExamStatus.toTone(): StatusTone = when (this) {
    ExamStatus.DRAFT -> StatusTone.ATTENTION
    ExamStatus.PUBLISHED -> StatusTone.POSITIVE
    ExamStatus.ARCHIVED -> StatusTone.NEGATIVE
}

// ---------------------------------------------------------------------------
// Detalle — formulario
// ---------------------------------------------------------------------------

private enum class ExamDetailTab(val id: String, val label: String, val icon: AppIcon) {
    BASICO("basico", "Información básica", AppIcon.EXAMS),
    CONFIGURACION("config", "Configuración", AppIcon.SETTINGS),
    ACCESO("acceso", "Acceso y monetización", AppIcon.TICKETS),
    PUBLICACION("publicacion", "Publicación", AppIcon.CLOCK),
}

@Composable
private fun ExamDetailPane(
    state: ExamsState,
    onIntent: (ExamsIntent) -> Unit,
    onRequestArchive: () -> Unit,
) {
    var selectedTab by rememberSaveable(state.selectedExamId) { mutableStateOf(ExamDetailTab.BASICO.id) }
    val isNew = state.selectedExamId == null
    val capabilities = (state.specialtyCapabilities as? UiAsync.Success)?.value
    val selectedExam = state.exams.selectedExam(state.selectedExamId)

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        ExamDetailHeader(
            isNew = isNew,
            title = state.editorTitle,
            exam = selectedExam,
            busy = state.lifecycleActionState is UiAsync.Loading,
            onPublish = { onIntent(ExamsIntent.PublishExam) },
            onUnpublish = { onIntent(ExamsIntent.UnpublishExam) },
            onArchive = onRequestArchive,
        )

        DesktopTabs(
            tabs = ExamDetailTab.entries.map { DesktopTab(it.id, it.label, it.icon) },
            selectedId = selectedTab,
            onSelect = { selectedTab = it },
        )

        // weight(1f) + scroll aquí, no en todo el panel: así Guardar/Cancelar
        // quedan fijos al pie mientras sólo el contenido de la pestaña
        // desplaza — un formulario largo no debería esconder sus propios
        // botones al hacer scroll.
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            when (selectedTab) {
                ExamDetailTab.BASICO.id -> ExamBasicInfoTab(state, onIntent)
                ExamDetailTab.CONFIGURACION.id -> ExamConfigurationTab(state, onIntent, capabilities)
                ExamDetailTab.ACCESO.id -> ExamAccessTab(state, onIntent, capabilities)
                ExamDetailTab.PUBLICACION.id -> ExamPublicationTab(state, onIntent, selectedExam)
            }
        }

        // Guardar/Cancelar viven fuera de las pestañas a propósito: son la
        // acción del formulario completo, no de una pestaña concreta.
        ExamFormActions(state = state, onIntent = onIntent)
    }
}

/**
 * Cabecera del examen seleccionado.
 *
 * Archivar ya no es un botón rojo al lado del título — ésa era la acción
 * visualmente más potente del editor entero, por delante incluso de Guardar.
 * Ahora vive en el menú «Más acciones», junto a Publicar/Despublicar, que son
 * las otras acciones de ciclo de vida del examen. El código, que antes salía
 * suelto aquí debajo del título, ya está en Información básica — mostrarlo
 * dos veces no aportaba nada.
 */
@Composable
private fun ExamDetailHeader(
    isNew: Boolean,
    title: String,
    exam: Exam?,
    busy: Boolean,
    onPublish: () -> Unit,
    onUnpublish: () -> Unit,
    onArchive: () -> Unit,
) {
    val tokens = GlassTheme.tokens
    var menuExpanded by remember { mutableStateOf(false) }
    val status = exam?.status

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    text = if (isNew) "Nuevo examen" else title.ifBlank { "Examen sin título" },
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (status != null) {
                    StatusChip(status = status.toDisplayLabel(), tone = status.toTone())
                }
            }
            if (!isNew && exam != null) {
                Text(
                    text = listOfNotNull(
                        exam.specialtyName.ifBlank { null },
                        ContentExamType.fromApiValue(exam.typeExam).label,
                        exam.questionCount.pluralize("pregunta", "preguntas"),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                )
            } else if (isNew) {
                Text(
                    text = "Guarda el examen para poder publicarlo o archivarlo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                )
            }
        }
        if (!isNew) {
            Box {
                DesktopIconButton(
                    label = "Más acciones",
                    onClick = { menuExpanded = true },
                ) {
                    AppIconImage(AppIcon.MORE, size = 18.dp, tint = tokens.textSecondary)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    if (status != ExamStatus.PUBLISHED) {
                        DropdownMenuItem(
                            text = { Text(if (busy) "Publicando…" else "Publicar") },
                            enabled = !busy,
                            onClick = { menuExpanded = false; onPublish() },
                        )
                    }
                    if (status == ExamStatus.PUBLISHED) {
                        DropdownMenuItem(
                            text = { Text(if (busy) "Despublicando…" else "Despublicar") },
                            enabled = !busy,
                            onClick = { menuExpanded = false; onUnpublish() },
                        )
                    }
                    Box(
                        modifier = Modifier
                            .padding(vertical = 4.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(tokens.border),
                    )
                    DropdownMenuItem(
                        text = { Text("Archivar", color = tokens.danger) },
                        onClick = { menuExpanded = false; onArchive() },
                    )
                }
            }
        }
    }
}

@Composable
private fun ExamBasicInfoTab(
    state: ExamsState,
    onIntent: (ExamsIntent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.section)) {
        FormSection(title = "Identificación") {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.field)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    LabeledField(
                        label = "Título",
                        required = true,
                        value = state.editorTitle,
                        onValueChange = { onIntent(ExamsIntent.UpdateTitle(it)) },
                        placeholder = "Ej. Simulacro Andalucía 2026",
                        modifier = Modifier.weight(2f),
                    )
                    LabeledField(
                        label = "Código del examen",
                        help = Help.codigoExamen,
                        value = state.editorCode,
                        onValueChange = { onIntent(ExamsIntent.UpdateCode(it)) },
                        placeholder = "Automático",
                        modifier = Modifier.weight(1f),
                    )
                }
                LabeledField(
                    label = "Descripción",
                    value = state.editorDescription,
                    onValueChange = { onIntent(ExamsIntent.UpdateDescription(it)) },
                    placeholder = "Explicación breve del examen",
                    singleLine = false,
                    maxWidth = Sizing.inputDescriptionMaxWidth,
                )
            }
        }
        FormSection(title = "Ubicación") {
            FieldRow(
                { SpecialtyPicker(state.specialties, state.editorSpecialtyId, { onIntent(ExamsIntent.UpdateSpecialtyId(it)) }, it) },
                { CommunityPicker(state.communities, state.editorCommunityId, { onIntent(ExamsIntent.UpdateCommunityId(it)) }, it) },
                {
                    LabeledField(
                        label = "Año",
                        value = state.editorYear,
                        onValueChange = { value -> onIntent(ExamsIntent.UpdateYear(value.filter { c -> c.isDigit() }.take(4))) },
                        placeholder = "2026",
                        modifier = it,
                    )
                },
            )
        }
    }
}

@Composable
private fun ExamConfigurationTab(
    state: ExamsState,
    onIntent: (ExamsIntent) -> Unit,
    capabilities: SpecialtyCapabilities?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.section)) {
        FormSection(title = "Tipo de examen", help = Help.tipoExamen) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    ContentExamType.entries.forEach { type ->
                        // Mientras no sepamos con certeza qué permite la especialidad
                        // (Loading/Error), NINGÚN tipo se puede elegir — antes
                        // "capabilities?.allows(...) ?: true" dejaba seleccionar
                        // cualquier cosa justo cuando no podíamos comprobar nada.
                        // El valor ya elegido se sigue viendo (selected no depende
                        // de enabled), sólo no se puede cambiar a otro sin saber.
                        val allowed = capabilities?.allows(type.apiValue) ?: false
                        GlassChip(
                            label = type.label,
                            selected = state.editorTypeExam == type.apiValue,
                            enabled = allowed,
                            onClick = { onIntent(ExamsIntent.UpdateTypeExam(type.apiValue)) },
                        )
                    }
                }
                when (val capabilitiesState = state.specialtyCapabilities) {
                    UiAsync.Loading -> Text(
                        text = "Comprobando los modos disponibles…",
                        style = MaterialTheme.typography.bodySmall,
                        color = GlassTheme.tokens.textSecondary,
                    )
                    is UiAsync.Error -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        Text(
                            text = "No se pudieron comprobar los modos disponibles.",
                            style = MaterialTheme.typography.bodySmall,
                            color = GlassTheme.tokens.danger,
                        )
                        GhostButton(
                            text = "Reintentar",
                            size = ButtonSize.COMPACT,
                            onClick = { onIntent(ExamsIntent.UpdateSpecialtyId(state.editorSpecialtyId)) },
                        )
                    }
                    is UiAsync.Success -> if (!capabilitiesState.value.configured) {
                        Text(
                            text = "Esta especialidad todavía no tiene sus modos configurados: se muestran los valores por defecto.",
                            style = MaterialTheme.typography.bodySmall,
                            color = GlassTheme.tokens.textSecondary,
                        )
                    }
                    UiAsync.Idle, UiAsync.Empty -> Unit
                }
            }
        }
        FormSection(title = "Datos del examen") {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.field)) {
                FieldRow(
                    {
                        LabeledField(
                            label = "Número de preguntas",
                            help = Help.numeroPreguntas,
                            value = (state.questions as? UiAsync.Success)?.value?.size?.toString() ?: "—",
                            onValueChange = {},
                            placeholder = "—",
                            readOnly = true,
                            modifier = it,
                        )
                    },
                    {
                        LabeledField(
                            label = "Duración (minutos)",
                            help = Help.duracionExamen,
                            value = state.editorDurationMinutes,
                            onValueChange = { value -> onIntent(ExamsIntent.UpdateDurationMinutes(value.filter { c -> c.isDigit() })) },
                            placeholder = "Sin límite",
                            modifier = it,
                        )
                    },
                )
                GlassToggleRow(
                    title = "Examen oficial",
                    description = "Corresponde a una convocatoria real ya celebrada.",
                    checked = state.editorIsOfficial,
                    onCheckedChange = { onIntent(ExamsIntent.ToggleIsOfficial(it)) },
                )
            }
        }
        // Portada RETIRADA (decisión de producto, 2026-09-23): se compone con
        // los componentes de la app, no se sube un archivo. Ver el comentario
        // equivalente en `ExamBasicInfoSection`.
    }
}

@Composable
private fun ExamAccessTab(
    state: ExamsState,
    onIntent: (ExamsIntent) -> Unit,
    capabilities: SpecialtyCapabilities?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.section)) {
        FormSection(title = "Tipo de acceso", help = Help.tipoAcceso) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                GlassChip(
                    label = "Gratuito",
                    selected = state.editorTypeAccess == "gratuito",
                    onClick = {
                        onIntent(ExamsIntent.UpdateTypeAccess("gratuito"))
                        // Un examen gratuito ya está abierto para cualquiera (ver
                        // SupabaseV1Gateway.kt): un precio en OT no desbloquearía
                        // nada. Se limpia para no guardar un precio inerte oculto.
                        onIntent(ExamsIntent.UpdatePriceTickets("0"))
                    },
                )
                GlassChip(
                    label = "Premium",
                    selected = state.editorTypeAccess == "premium",
                    onClick = { onIntent(ExamsIntent.UpdateTypeAccess("premium")) },
                )
            }
        }
        // Compra individual con OT sólo tiene efecto real en un examen premium:
        // uno gratuito ya es accesible para cualquiera sin comprar nada (ver el
        // comentario de allowsOposiTicketsPurchase() en ExamsModels.kt — no hay
        // ningún endpoint de compra en este backend que dependa de este precio,
        // así que en un examen gratuito el precio no hace nada).
        if (state.editorTypeAccess.allowsOposiTicketsPurchase()) {
            FormSection(
                title = "Compra individual",
                description = "Precio para desbloquear este examen suelto, sin pasar por una suscripción.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.field)) {
                    val purchaseEnabled = (state.editorPriceTickets.toIntOrNull() ?: 0) > 0
                    GlassToggleRow(
                        title = "Permitir compra con OposiTickets",
                        checked = purchaseEnabled,
                        onCheckedChange = { enabled ->
                            onIntent(ExamsIntent.UpdatePriceTickets(if (enabled) "1" else "0"))
                        },
                    )
                    if (purchaseEnabled) {
                        LabeledField(
                            label = "Precio en OposiTickets",
                            help = Help.precioExamen,
                            value = state.editorPriceTickets,
                            onValueChange = { onIntent(ExamsIntent.UpdatePriceTickets(it.filter { c -> c.isDigit() }.take(9))) },
                            placeholder = "1300",
                        )
                    }
                }
            }
        }
        if (capabilities != null) {
            FormSection(title = "Demo", help = Help.demoEspecialidad) {
                StatusChip(
                    status = if (capabilities.permiteDemos) "Habilitada para esta especialidad" else "No habilitada para esta especialidad",
                    tone = if (capabilities.permiteDemos) StatusTone.POSITIVE else StatusTone.NEUTRAL,
                )
            }
        }
    }
}

@Composable
private fun ExamPublicationTab(
    state: ExamsState,
    onIntent: (ExamsIntent) -> Unit,
    selectedExam: Exam?,
) {
    val tokens = GlassTheme.tokens
    val isNew = state.selectedExamId == null
    val status = state.editorStatus.toExamStatus()
    val busy = state.lifecycleActionState is UiAsync.Loading

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.section)) {
        FormSection(title = "Estado", help = Help.estadoExamen) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                StatusChip(status = status.toDisplayLabel(), tone = status.toTone())
                if (isNew) {
                    Text(
                        text = "Guarda el examen para poder publicarlo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        if (status != ExamStatus.PUBLISHED) {
                            PrimaryButton(
                                text = "Publicar",
                                onClick = { onIntent(ExamsIntent.PublishExam) },
                                loading = busy,
                                size = ButtonSize.COMPACT,
                            )
                        }
                        if (status == ExamStatus.PUBLISHED) {
                            SecondaryButton(
                                text = "Despublicar",
                                onClick = { onIntent(ExamsIntent.UnpublishExam) },
                                loading = busy,
                                size = ButtonSize.COMPACT,
                            )
                        }
                    }
                    (state.lifecycleActionState as? UiAsync.Error)?.let { error ->
                        Text(
                            text = error.error.humanMessage(),
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.danger,
                        )
                    }
                }
            }
        }
        FormSection(title = "Información del sistema") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SystemMetaLine("Creado", selectedExam?.createdAt?.toString())
                SystemMetaLine("Última actualización", selectedExam?.updatedAt?.toString())
                SystemMetaLine("Publicado", selectedExam?.publishedAt?.toString() ?: "Sin publicar")
            }
        }
    }
}

@Composable
private fun SystemMetaLine(label: String, value: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(text = "$label:", style = MaterialTheme.typography.bodySmall, color = GlassTheme.tokens.textSecondary)
        Text(text = value ?: "—", style = MaterialTheme.typography.bodySmall, color = GlassTheme.tokens.textPrimary)
    }
}

private fun UiAsync<List<Exam>>.selectedExam(id: String?): Exam? =
    (this as? UiAsync.Success)?.value?.firstOrNull { it.id == id }

// ---------------------------------------------------------------------------
// Guardar / cancelar
// ---------------------------------------------------------------------------

/**
 * Cancelar y Guardar, abajo a la derecha — el orden y la posición que se
 * espera de un formulario de escritorio, no dos botones sueltos a la
 * izquierda. Guardar es el CTA coral; Cancelar es la acción secundaria.
 */
@Composable
private fun ExamFormActions(
    state: ExamsState,
    onIntent: (ExamsIntent) -> Unit,
) {
    val saving = state.actionState is UiAsync.Loading
    val canSave = state.editorTitle.isNotBlank() && state.editorSpecialtyId.isNotBlank()
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        (state.actionState as? UiAsync.Error)?.let { error ->
            Text(
                text = error.error.humanMessage(),
                style = MaterialTheme.typography.bodySmall,
                color = GlassTheme.tokens.danger,
            )
        }
        if (state.actionState is UiAsync.Success) {
            Text(
                text = "Examen guardado.",
                style = MaterialTheme.typography.bodySmall,
                color = GlassTheme.tokens.success,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
        ) {
            if (state.selectedExamId != null) {
                GhostButton(text = "Cancelar", onClick = { onIntent(ExamsIntent.CreateNew) })
            }
            PrimaryButton(
                text = "Guardar cambios",
                onClick = { onIntent(ExamsIntent.SaveExam) },
                enabled = canSave,
                loading = saving,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Portada
// ---------------------------------------------------------------------------

@Composable
private fun CoverEditor(
    state: ExamsState,
    onIntent: (ExamsIntent) -> Unit,
) {
    val tokens = GlassTheme.tokens
    val picker = rememberContentFilePickerLauncher(mimeType = "image/*") { picked ->
        onIntent(ExamsIntent.SelectExamCoverFile(picked.uri, picked.name, picked.mimeType))
    }
    val hasCover = state.editorCoverUrl.isNotBlank() || state.editorCoverLocalUri.isNotBlank()
    val uploading = state.coverUploadState is UiAsync.Loading

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Box(modifier = Modifier.width(220.dp)) {
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = Radius.md,
                tint = tokens.surfaceMuted,
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                    contentAlignment = Alignment.Center,
                ) {
                    if (hasCover) {
                        Text(
                            text = state.editorCoverFileName.ifBlank { "Portada guardada" },
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            AppIconImage(AppIcon.EXAMS, size = 22.dp, tint = tokens.textSecondary)
                            Text(
                                text = "Sin portada",
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.textSecondary,
                            )
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            SecondaryButton(
                text = if (hasCover) "Sustituir" else "Subir imagen",
                size = ButtonSize.COMPACT,
                onClick = { picker.open() },
            )
            if (state.editorCoverLocalUri.isNotBlank()) {
                PrimaryButton(
                    text = "Guardar portada",
                    size = ButtonSize.COMPACT,
                    loading = uploading,
                    onClick = { onIntent(ExamsIntent.UploadExamCover) },
                )
            }
            if (state.editorCoverUrl.isNotBlank()) {
                GhostButton(
                    text = "Quitar",
                    size = ButtonSize.COMPACT,
                    onClick = { onIntent(ExamsIntent.RequestClearExamCover) },
                )
            }
        }
        Text(
            text = "JPG, PNG o WEBP. Tamaño máximo 10 MB.",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary,
        )
        (state.coverUploadState as? UiAsync.Error)?.let { error ->
            Text(error.error.humanMessage(), style = MaterialTheme.typography.bodySmall, color = tokens.danger)
        }
    }
}

// ---------------------------------------------------------------------------
// Selectores
// ---------------------------------------------------------------------------

/**
 * Desplegable de campo de formulario: misma altura/forma que un
 * [GlassTextField] para que no se note qué campo es un input libre y cuál un
 * selector cerrado — antes Especialidad se veía como una fila de chips y no
 * quedaba claro que fuera un control de selección único.
 */
@Composable
private fun DropdownField(
    label: String,
    selectedLabel: String,
    options: List<Pair<String?, String>>,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    help: HelpTopic? = null,
    required: Boolean = false,
    placeholder: String = "Selecciona",
) {
    val tokens = GlassTheme.tokens
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = if (required) "$label *" else label,
                style = MaterialTheme.typography.labelMedium,
                color = tokens.textSecondary,
            )
            help?.let { InfoHelpButton(it) }
        }
        Box(modifier = Modifier.widthIn(max = Sizing.inputTextMaxWidth)) {
            GlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = Sizing.inputHeight)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable { expanded = true },
                shape = Radius.md,
                tint = tokens.glassFill,
                strokeColor = if (expanded) tokens.accent.copy(alpha = 0.48f) else tokens.glassStroke,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = selectedLabel.ifBlank { placeholder },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (selectedLabel.isBlank()) tokens.textSecondary else tokens.textPrimary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    AppIconImage(AppIcon.CHEVRON_DOWN, size = 16.dp, tint = tokens.textSecondary)
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, optionLabel) ->
                    DropdownMenuItem(text = { Text(optionLabel) }, onClick = { onSelect(value); expanded = false })
                }
            }
        }
    }
}

@Composable
private fun PickerStatusMessage(
    label: String,
    message: String,
    modifier: Modifier = Modifier,
    help: HelpTopic? = null,
    isError: Boolean = false,
) {
    val tokens = GlassTheme.tokens
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = tokens.textSecondary)
            help?.let { InfoHelpButton(it) }
        }
        Text(message, style = MaterialTheme.typography.bodySmall, color = if (isError) tokens.danger else tokens.textSecondary)
    }
}

@Composable
private fun SpecialtyPicker(
    specialtiesState: UiAsync<List<Specialty>>,
    selectedId: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (specialtiesState) {
        is UiAsync.Success -> DropdownField(
            label = "Especialidad",
            required = true,
            selectedLabel = specialtiesState.value.firstOrNull { it.id == selectedId }?.name.orEmpty(),
            placeholder = "Selecciona una especialidad",
            options = specialtiesState.value.map { it.id to it.name },
            onSelect = { value -> value?.let(onSelected) },
            modifier = modifier,
        )
        is UiAsync.Error -> PickerStatusMessage("Especialidad *", specialtiesState.error.humanMessage(), modifier, isError = true)
        UiAsync.Loading -> PickerStatusMessage("Especialidad *", "Cargando especialidades…", modifier)
        else -> PickerStatusMessage("Especialidad *", "Sin especialidades todavía.", modifier)
    }
}

/**
 * Selector de CCAA.
 *
 * Tolerante a fallo por diseño: `comunidad_autonoma` es una tabla reciente y, si
 * su migración no está aplicada en este entorno, el backend responde con error
 * al consultarla. Eso no debe impedir seguir editando el resto del examen — el
 * examen simplemente queda sin comunidad asignada (Nacional).
 */
@Composable
private fun CommunityPicker(
    communitiesState: UiAsync<List<Community>>,
    selectedId: String?,
    onSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (communitiesState) {
        is UiAsync.Success -> DropdownField(
            label = "Comunidad autónoma / ámbito",
            help = Help.comunidadAutonoma,
            selectedLabel = if (selectedId == null) {
                "Nacional"
            } else {
                communitiesState.value.firstOrNull { it.id == selectedId }?.name ?: "Nacional"
            },
            options = listOf<Pair<String?, String>>(null to "Nacional") + communitiesState.value.map { it.id to it.name },
            onSelect = onSelected,
            modifier = modifier,
        )
        is UiAsync.Error -> PickerStatusMessage(
            "Comunidad autónoma / ámbito",
            "No disponible en este entorno todavía.",
            modifier,
            help = Help.comunidadAutonoma,
        )
        UiAsync.Loading -> PickerStatusMessage("Comunidad autónoma / ámbito", "Cargando…", modifier, help = Help.comunidadAutonoma)
        UiAsync.Idle, UiAsync.Empty -> PickerStatusMessage("Comunidad autónoma / ámbito", "Nacional", modifier, help = Help.comunidadAutonoma)
    }
}

// ---------------------------------------------------------------------------
// Campo con etiqueta + ayuda opcional
// ---------------------------------------------------------------------------

/**
 * Fila de N campos a ancho igual, capados por el max width propio de cada
 * campo. Sustituye a la vieja `TwoColumnRow` de ancho fijo: con `weight` cada
 * columna aprovecha el ancho real disponible en vez de dejar un hueco vacío
 * en pantallas anchas, sin llegar a estirarse de forma absurda.
 */
@Composable
private fun FieldRow(vararg fields: @Composable (Modifier) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        fields.forEach { field ->
            Box(modifier = Modifier.weight(1f)) { field(Modifier.fillMaxWidth()) }
        }
    }
}

/**
 * Campo del formulario con label propio y ayuda opcional.
 *
 * Distinto de `EditorField` (que sigue usando `QuestionsResourcesScreen`, sin
 * tocar) porque necesita `help` y `readOnly`, que ese componente no soporta.
 */
@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = label,
    help: HelpTopic? = null,
    required: Boolean = false,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    maxWidth: androidx.compose.ui.unit.Dp = Sizing.inputTextMaxWidth,
) {
    val tokens = GlassTheme.tokens
    Column(
        modifier = modifier.fillMaxWidth().widthIn(max = maxWidth),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = if (required) "$label *" else label,
                style = MaterialTheme.typography.labelMedium,
                color = tokens.textSecondary,
            )
            help?.let { InfoHelpButton(it) }
        }
        if (readOnly) {
            GlassSurface(modifier = Modifier.fillMaxWidth(), shape = Radius.md, tint = tokens.surfaceMuted) {
                Box(modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 10.dp)) {
                    Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge, color = tokens.textSecondary)
                }
            }
        } else {
            GlassTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = placeholder,
                singleLine = singleLine,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Preguntas y recursos — pantalla de la ruta Content.Questions.
//
// Fuera de alcance de este rediseño (§25: sólo Exámenes). Se conserva tal cual
// estaba: sigue siendo la pantalla real que usa esa ruta y comparte el mismo
// ExamsViewModel/ExamsState, así que tocarla sin necesidad sería justo el tipo
// de regresión que el encargo pidió evitar.
// ---------------------------------------------------------------------------

@Composable
fun QuestionsResourcesScreen(
    viewModel: ExamsViewModel,
) {
    val state by viewModel.state.collectAsState()
    var confirmDeleteQuestion by remember { mutableStateOf(false) }
    CrudSplitLayout(
        modifier = Modifier.fillMaxSize(),
        listPane = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FormSection(
                    title = "Examen activo",
                    description = "Selecciona el examen y gestiona preguntas con 4 opciones e IDs estables.",
                ) {
                    AsyncStatePane(state.exams, emptyMessage = "No hay exámenes cargados.") { exams ->
                        AdminTable(
                            columns = listOf(
                                AdminTableColumn("Título", 1.1f),
                                AdminTableColumn("Estado", 0.6f),
                                AdminTableColumn("Preguntas", 0.6f),
                            ),
                            rows = exams.map { exam ->
                                AdminTableRow(
                                    key = exam.id,
                                    cells = listOf(exam.title, exam.status.name.lowercase(), exam.questionCount.toString()),
                                    selected = exam.id == state.selectedExamId,
                                    supporting = exam.specialtyName,
                                )
                            },
                            onRowClick = { viewModel.dispatch(ExamsIntent.SelectExam(it)) },
                        )
                    }
                }
                FormSection(
                    title = "Preguntas",
                    description = "Orden, enunciado y recursos explicativos ligados a cada pregunta.",
                ) {
                    GlassTextField(
                        value = state.questionResourceFilter,
                        onValueChange = { viewModel.dispatch(ExamsIntent.UpdateQuestionResourceFilter(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "all | with_resource | without_resource | pending",
                    )
                    AsyncStatePane(state.questions, emptyMessage = "Este examen aun no tiene preguntas.") { questions ->
                        val filteredQuestions = questions.filter { question ->
                            when (state.questionResourceFilter.trim().lowercase()) {
                                "with_resource" -> question.resources.isNotEmpty()
                                "without_resource" -> question.resources.isEmpty()
                                "pending" -> question.resources.any { resource ->
                                    resource.textBody.isNullOrBlank() && resource.publicUrl.isNullOrBlank() && resource.storagePath.isNullOrBlank()
                                }
                                else -> true
                            }
                        }
                        AdminTable(
                            columns = listOf(
                                AdminTableColumn("Título", 1f),
                                AdminTableColumn("Estado", 0.6f),
                                AdminTableColumn("Recursos", 0.6f),
                            ),
                            rows = filteredQuestions.map { question ->
                                val pending = question.resources.any { resource ->
                                    resource.textBody.isNullOrBlank() && resource.publicUrl.isNullOrBlank() && resource.storagePath.isNullOrBlank()
                                }
                                AdminTableRow(
                                    key = question.id,
                                    cells = listOf(
                                        question.title,
                                        if (pending) "pendiente" else question.options.firstOrNull { it.isCorrect }?.label ?: "-",
                                        if (question.resources.isEmpty()) "sin recurso" else "${question.resources.size}",
                                    ),
                                    selected = question.id == state.selectedQuestionId,
                                    supporting = question.statement,
                                )
                            },
                            onRowClick = { viewModel.dispatch(ExamsIntent.SelectQuestion(it)) },
                        )
                    }
                }
                FormSection(
                    title = "Auditoría de recursos",
                    description = "Resumen de preguntas con recurso y recursos detectados sin enlace.",
                ) {
                    when (val audit = state.resourceAudit) {
                        is UiAsync.Success -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusChip("${audit.value.summary.totalQuestions} preguntas")
                                StatusChip("${audit.value.summary.questionsWithResources} con recurso")
                                StatusChip("${audit.value.summary.questionsWithoutResources} sin recurso")
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusChip("${audit.value.summary.linkedResources} enlazados")
                                StatusChip("${audit.value.summary.pendingResources} pendientes")
                                StatusChip("${audit.value.summary.unlinkedResources} sin enlazar")
                            }
                            if (audit.value.unlinkedResources.isNotEmpty()) {
                                AdminTable(
                                    columns = listOf(
                                        AdminTableColumn("Ruta", 1.4f),
                                        AdminTableColumn("Tipo", 0.6f),
                                        AdminTableColumn("Motivo", 1f),
                                    ),
                                    rows = audit.value.unlinkedResources.map { resource ->
                                        AdminTableRow(
                                            key = resource.id,
                                            cells = listOf(
                                                resource.path,
                                                resource.type.name.lowercase(),
                                                resource.reason.name.lowercase(),
                                            ),
                                            supporting = resource.suggestedQuestionId ?: "Sin sugerencia",
                                        )
                                    },
                                )
                            }
                        }
                        is UiAsync.Error -> Text(audit.error.message, color = MaterialTheme.colorScheme.error)
                        UiAsync.Loading -> Text("Auditando recursos...")
                        else -> Text("Selecciona un examen para auditar recursos.")
                    }
                }
            }
        },
        detailPane = {
            FormSection(
                title = if (state.selectedQuestionId == null) "Nueva pregunta" else "Editar pregunta",
                description = "Cada pregunta requiere 4 opciones, una correcta por ID y al menos un recurso.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassActionButton(
                        text = "Nueva pregunta",
                        onClick = { viewModel.dispatch(ExamsIntent.CreateQuestion) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    EditorField(
                        label = "Título de la pregunta",
                        value = state.editorQuestionTitle,
                        onValueChange = { viewModel.dispatch(ExamsIntent.UpdateQuestionTitle(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "Ej. Pregunta 1",
                    )
                    EditorField(
                        label = "Enunciado",
                        value = state.editorQuestionStatement,
                        onValueChange = { viewModel.dispatch(ExamsIntent.UpdateQuestionStatement(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "Introduce el enunciado completo",
                        singleLine = false,
                    )
                    state.editorOptions.sortedBy { it.sortOrder }.forEach { option ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatusChip(if (option.isCorrect) "correcta" else "opcion ${option.label.lowercase()}")
                            EditorField(
                                label = "Opcion ${option.label}",
                                value = option.content,
                                onValueChange = { viewModel.dispatch(ExamsIntent.UpdateOptionContent(option.id, it)) },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = "Contenido de la opcion ${option.label}",
                            )
                            GlassActionButton(
                                text = "Marcar ${option.label} como correcta",
                                onClick = { viewModel.dispatch(ExamsIntent.SelectCorrectOption(option.id)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    state.editorResources.sortedBy { it.sortOrder }.forEach { resource ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusChip(resource.type)
                            EditorField(
                                label = "Tipo recurso",
                                value = resource.type,
                                onValueChange = { viewModel.dispatch(ExamsIntent.UpdateResourceType(resource.id, it)) },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = "image | audio | video | text",
                            )
                            EditorField(
                                label = "Título recurso",
                                value = resource.title,
                                onValueChange = { viewModel.dispatch(ExamsIntent.UpdateResourceTitle(resource.id, it)) },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = "Nombre visible del recurso",
                            )
                            EditorField(
                                label = if (resource.type == "text") "Contenido texto" else "Ruta local o URL",
                                value = resource.inputValue,
                                onValueChange = { viewModel.dispatch(ExamsIntent.UpdateResourceValue(resource.id, it)) },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = if (resource.type == "text") {
                                    "Explicacion, notas o transcripcion"
                                } else {
                                    "C:\\ruta\\archivo.png o https://..."
                                },
                                singleLine = resource.type != "text",
                            )
                            if (resource.type != "text") {
                                GlassActionButton(
                                    text = "Subir recurso",
                                    onClick = { viewModel.dispatch(ExamsIntent.UploadResource(resource.id)) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            GlassActionButton(
                                text = "Quitar recurso",
                                onClick = { viewModel.dispatch(ExamsIntent.RemoveResource(resource.id)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    GlassActionButton(
                        text = "Anadir recurso",
                        onClick = { viewModel.dispatch(ExamsIntent.AddResource) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GlassActionButton(
                            text = "Guardar pregunta",
                            onClick = { viewModel.dispatch(ExamsIntent.SaveQuestion) },
                            modifier = Modifier.weight(1f),
                        )
                        GlassActionButton(
                            text = "↑ Arriba",
                            onClick = { state.selectedQuestionId?.let { viewModel.dispatch(ExamsIntent.MoveQuestionUp(it)) } },
                            modifier = Modifier.weight(1f),
                        )
                        GlassActionButton(
                            text = "↓ Abajo",
                            onClick = { state.selectedQuestionId?.let { viewModel.dispatch(ExamsIntent.MoveQuestionDown(it)) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (state.selectedQuestionId != null) {
                        GlassActionButton(
                            text = "Eliminar pregunta",
                            tint = MaterialTheme.colorScheme.error,
                            onClick = { confirmDeleteQuestion = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    when (val uploadState = state.uploadState) {
                        is UiAsync.Error -> Text(uploadState.error.message, color = MaterialTheme.colorScheme.error)
                        UiAsync.Loading -> Text("Subiendo recurso...")
                        is UiAsync.Success -> Text("Recurso listo: ${uploadState.value}")
                        else -> Unit
                    }
                    FormFeedback(state.actionState, successText = "Pregunta sincronizada.")
                }
            }
        },
    )

    if (confirmDeleteQuestion) {
        ConfirmDialog(
            title = "Eliminar pregunta",
            message = "Se eliminará la pregunta seleccionada y sus recursos. ¿Continuar?",
            confirmLabel = "Eliminar",
            onConfirm = {
                confirmDeleteQuestion = false
                viewModel.dispatch(ExamsIntent.DeleteSelectedQuestion)
            },
            onDismiss = { confirmDeleteQuestion = false },
        )
    }
}

@Composable
private fun EditorField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = label,
    singleLine: Boolean = true,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
        )
        GlassTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = placeholder,
            singleLine = singleLine,
        )
    }
}
