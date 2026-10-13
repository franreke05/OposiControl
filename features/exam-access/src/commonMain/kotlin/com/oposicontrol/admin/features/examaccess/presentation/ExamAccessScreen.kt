package com.oposicontrol.admin.features.examaccess.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.ConfirmDialog
import com.oposicontrol.admin.core.ui.components.DangerButton
import com.oposicontrol.admin.core.ui.components.DesktopIconButton
import com.oposicontrol.admin.core.ui.components.DesktopVerticalScrollbar
import com.oposicontrol.admin.core.ui.components.DropdownField
import com.oposicontrol.admin.core.ui.components.EmptyStateCard
import com.oposicontrol.admin.core.ui.components.FormFeedback
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GhostButton
import com.oposicontrol.admin.core.ui.components.GlassSearchBar
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.WorkspaceCatalogRow
import com.oposicontrol.admin.core.ui.components.WorkspaceEditor
import com.oposicontrol.admin.core.ui.components.WorkspaceEditorHeader
import com.oposicontrol.admin.core.ui.components.WorkspaceFact
import com.oposicontrol.admin.core.ui.components.WorkspaceFacts
import com.oposicontrol.admin.core.ui.components.WorkspaceFilterPill
import com.oposicontrol.admin.core.ui.components.WorkspaceInfoLines
import com.oposicontrol.admin.core.ui.components.WorkspaceLayout
import com.oposicontrol.admin.core.ui.components.WorkspaceNotice
import com.oposicontrol.admin.core.ui.components.WorkspaceNoticeTone
import com.oposicontrol.admin.core.ui.components.WorkspacePanel
import com.oposicontrol.admin.core.ui.components.WorkspacePill
import com.oposicontrol.admin.core.ui.components.WorkspaceRail
import com.oposicontrol.admin.core.ui.components.WorkspaceSaveBar
import com.oposicontrol.admin.core.ui.components.WorkspaceSaveTone
import com.oposicontrol.admin.core.ui.components.WorkspaceSection
import com.oposicontrol.admin.core.ui.components.WorkspaceSizing
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing

internal const val ACCESS_CATALOG_TAG: String = "access-catalog"
internal const val ACCESS_EDITOR_TAG: String = "access-editor"
internal const val ACCESS_RAIL_TAG: String = "access-rail"

/**
 * Accesos, en escritorio.
 *
 * El mismo workspace de tres columnas que OposiTickets —catálogo, ficha y resumen—: aquí la fila es «esta persona puede
 * abrir este examen». La ficha de un acceso existente es de solo lectura con una única acción, retirarlo; conceder es un
 * formulario aparte en el que la persona y el examen se ELIGEN, no se teclean.
 */
@Composable
fun ExamAccessScreen(viewModel: ExamAccessViewModel) {
    val state by viewModel.state.collectAsState()
    ExamAccessContent(state = state, onIntent = viewModel::dispatch)
}

/** El workspace, por ESTADO: el banco de pruebas visual lo renderiza contra estados fabricados, sin Ktor ni Koin. */
@Composable
internal fun ExamAccessContent(state: ExamAccessState, onIntent: (ExamAccessIntent) -> Unit, modifier: Modifier = Modifier) {
    WorkspaceLayout(
        modifier = modifier,
        catalog = { paneModifier -> AccessCatalogPane(state, onIntent, paneModifier.testTag(ACCESS_CATALOG_TAG)) },
        editor = { paneModifier, _, fillsHeight ->
            AccessEditorPane(state, onIntent, fillsHeight, paneModifier.testTag(ACCESS_EDITOR_TAG))
        },
        rail = { paneModifier, scrolls -> AccessRail(state, paneModifier.testTag(ACCESS_RAIL_TAG), scrolls) },
    )

    val item = state.selected
    if (state.confirmingRevoke && item != null) {
        ConfirmDialog(
            title = "Retirar acceso",
            message = buildString {
                append("Persona: ${item.userName}\n")
                append("Examen: ${item.examTitle}\n\n")
                append("Dejará de poder abrir este examen. Queda registrado en auditoría; para devolvérselo hay que concederlo otra vez.")
            },
            confirmLabel = "Retirar",
            destructive = true,
            onConfirm = { onIntent(ExamAccessIntent.ConfirmRevoke) },
            onDismiss = { onIntent(ExamAccessIntent.DismissRevoke) },
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Catálogo
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun AccessCatalogPane(state: ExamAccessState, onIntent: (ExamAccessIntent) -> Unit, modifier: Modifier = Modifier) {
    val hasFilters = accessCatalogHasFilters(state.searchQuery, state.filter)
    WorkspaceSection(
        icon = AppIcon.ACCESS,
        role = SemanticRole.INFO,
        title = "Accesos",
        modifier = modifier,
        action = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs), verticalAlignment = Alignment.CenterVertically) {
                if (state.allAccesses.isNotEmpty()) WorkspacePill(text = state.allAccesses.size.toString())
                DesktopIconButton(
                    label = "Actualizar accesos",
                    size = Sizing.iconButtonSmall,
                    onClick = { onIntent(ExamAccessIntent.Refresh) },
                ) { AppIconImage(AppIcon.REFRESH, size = 14.dp) }
            }
        },
    ) {
        PrimaryButton(
            text = "Conceder acceso",
            size = ButtonSize.COMPACT,
            modifier = Modifier.fillMaxWidth(),
            onClick = { onIntent(ExamAccessIntent.CreateNew) },
            icon = { AppIconImage(AppIcon.ADD, size = 14.dp) },
        )
        GlassSearchBar(
            value = state.searchQuery,
            onValueChange = { onIntent(ExamAccessIntent.UpdateSearch(it)) },
            placeholder = "Buscar persona o examen…",
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccessFilter.entries.forEach { filter ->
                WorkspaceFilterPill(
                    label = filter.label,
                    selected = filter == state.filter,
                    onClick = { onIntent(ExamAccessIntent.UpdateFilter(filter)) },
                )
            }
            if (hasFilters) {
                Box(modifier = Modifier.weight(1f))
                GhostButton(
                    text = "Limpiar",
                    size = ButtonSize.COMPACT,
                    onClick = {
                        onIntent(ExamAccessIntent.UpdateSearch(""))
                        onIntent(ExamAccessIntent.UpdateFilter(AccessFilter.ALL))
                    },
                )
            }
        }
        AsyncStatePane(
            state = state.accesses,
            // Vacío significa dos cosas: sin filtros, «nadie tiene un acceso»; con ellos, «ninguno ASÍ».
            emptyTitle = if (hasFilters) "Sin resultados" else "Sin accesos",
            emptyMessage = if (hasFilters) {
                "Ningún acceso coincide con la búsqueda y el filtro."
            } else {
                "Todavía no se ha abierto ningún examen de pago a nadie. Usa «Conceder acceso» para el primero."
            },
            emptyIcon = if (hasFilters) AppIcon.SEARCH else AppIcon.ACCESS,
            loadingRows = 6,
            onRetry = { onIntent(ExamAccessIntent.Refresh) },
            stableContent = true,
        ) { accesses ->
            val scroll = rememberScrollState()
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(scroll).padding(end = WorkspaceSizing.scrollbarGutter),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    accesses.forEach { access ->
                        key(access.id) {
                            WorkspaceCatalogRow(
                                title = access.userName.ifBlank { "Usuario sin nombre" },
                                selected = !state.creating && access.id == state.selectedId,
                                onClick = { onIntent(ExamAccessIntent.Select(access.id)) },
                                icon = AppIcon.ACCESS,
                                description = access.examTitle,
                                meta = listOfNotNull(ExamAccessTypes.label(access.accessType), formatAccessDate(access.grantedAt)),
                                trailing = { WorkspacePill(text = accessStatusLabel(access)) },
                                // El acceso que se acaba de retirar se resalta un instante en el catálogo.
                                changeKey = access.status,
                            )
                        }
                    }
                }
                DesktopVerticalScrollbar(scrollState = scroll, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Ficha
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun AccessEditorPane(
    state: ExamAccessState,
    onIntent: (ExamAccessIntent) -> Unit,
    fillsHeight: Boolean,
    modifier: Modifier = Modifier,
) {
    val item = state.selected
    WorkspaceEditor(
        modifier = modifier,
        fillsHeight = fillsHeight,
        header = {
            WorkspaceEditorHeader(
                icon = AppIcon.ACCESS,
                title = when {
                    state.creating -> "Conceder acceso"
                    item != null -> item.userName.ifBlank { "Usuario sin nombre" }
                    else -> "Accesos"
                },
                subtitle = when {
                    state.creating -> "Abre un examen de pago a una persona concreta."
                    item != null -> item.examTitle
                    else -> "Ningún acceso abierto."
                },
            ) {
                if (!state.creating && item != null) WorkspacePill(text = accessStatusLabel(item), icon = AppIcon.ACCESS)
            }
        },
        footer = if (state.creating) {
            { GrantSaveBar(state, onIntent) }
        } else {
            null
        },
        // Otro acceso, o pasar de ver a conceder: el cuerpo entra. Elegir persona o examen dentro del formulario, no.
        contentKey = if (state.creating) "new" else state.selectedId,
    ) {
        when {
            state.creating -> GrantForm(state, onIntent)
            item != null -> AccessDetail(state, item, onIntent)
            else -> EmptyStateCard(
                title = "Sin acceso abierto",
                message = "Elige un acceso del catálogo para ver su detalle, o concede uno nuevo.",
                icon = AppIcon.ACCESS,
            )
        }
    }
}

@Composable
private fun AccessDetail(state: ExamAccessState, item: ExamAccessItem, onIntent: (ExamAccessIntent) -> Unit) {
    FormSection(
        title = "Detalle del acceso",
        description = "Quién puede abrir qué examen y desde cuándo. Solo lectura: un acceso no se edita, se retira.",
        icon = AppIcon.ACCESS,
        iconRole = SemanticRole.INFO,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.field)) {
            WorkspaceFacts(
                listOf(
                    WorkspaceFact("Persona", item.userName.ifBlank { "Usuario sin nombre" }, emphasised = true),
                    WorkspaceFact("Examen", item.examTitle, emphasised = true),
                    WorkspaceFact("Motivo", ExamAccessTypes.label(item.accessType)),
                    WorkspaceFact("Estado", accessStatusLabel(item)),
                    WorkspaceFact("Concedido el", formatAccessDate(item.grantedAt) ?: "Sin fecha registrada"),
                    WorkspaceFact(
                        if (item.isActive) "Vigente hasta" else "Retirado el",
                        formatAccessDate(item.validUntil) ?: if (item.isActive) "Sin caducidad" else "Sin fecha registrada",
                    ),
                ),
            )
            when {
                item.isPurchase -> WorkspaceNotice(
                    tone = WorkspaceNoticeTone.INFO,
                    title = "Acceso por compra",
                    detail = "Lo creó una compra. No se retira desde aquí.",
                )
                item.isActive -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    DangerButton(
                        text = "Retirar acceso",
                        size = ButtonSize.COMPACT,
                        enabled = state.actionState !is UiAsync.Loading,
                        onClick = { onIntent(ExamAccessIntent.RequestRevoke) },
                    )
                    Text(
                        text = "La persona dejará de poder abrir este examen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = GlassTheme.tokens.textSecondary,
                    )
                }
                else -> WorkspaceNotice(
                    tone = WorkspaceNoticeTone.INFO,
                    title = "Este acceso ya no está vigente",
                    detail = "Para devolvérselo a esta persona, concédelo de nuevo: quedará como una concesión nueva.",
                )
            }
            FormFeedback(
                state = state.actionState,
                loadingText = "Retirando el acceso…",
                successText = if (item.isActive) "Acceso concedido." else "Acceso retirado.",
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Conceder
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun GrantForm(state: ExamAccessState, onIntent: (ExamAccessIntent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        FormSection(
            title = "Persona",
            description = "Busca por nombre visible o nombre de usuario y elige a quién se le abre el examen.",
            icon = AppIcon.USERS,
            iconRole = SemanticRole.INFO,
        ) {
            val picked = state.pickedUser
            if (picked != null) {
                PickedRow(title = picked.label, detail = picked.detail, onChange = { onIntent(ExamAccessIntent.ClearUser) })
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                        GlassSearchBar(
                            value = state.userQuery,
                            onValueChange = { onIntent(ExamAccessIntent.UpdateUserQuery(it)) },
                            placeholder = "Nombre o nombre de usuario",
                            modifier = Modifier.weight(1f),
                        )
                        SecondaryButton(
                            text = "Buscar",
                            size = ButtonSize.COMPACT,
                            enabled = state.userQuery.isNotBlank() && state.userResults !is UiAsync.Loading,
                            onClick = { onIntent(ExamAccessIntent.SearchUsers) },
                        )
                    }
                    // En reposo no hay nada cargando: se dice qué hacer, no se enseña un esqueleto.
                    if (state.userResults is UiAsync.Idle) {
                        Hint("Escribe un nombre y pulsa «Buscar».")
                    } else {
                        AsyncStatePane(
                            state = state.userResults,
                            emptyTitle = "Nadie con ese nombre",
                            emptyMessage = "Ninguna persona coincide. Prueba con otra parte del nombre o con su nombre de usuario.",
                            emptyIcon = AppIcon.USERS,
                            loadingRows = 3,
                            onRetry = { onIntent(ExamAccessIntent.SearchUsers) },
                        ) { people ->
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                people.forEach { person ->
                                    key(person.id) {
                                        WorkspaceCatalogRow(
                                            title = person.label,
                                            selected = false,
                                            onClick = { onIntent(ExamAccessIntent.PickUser(person.id)) },
                                            icon = AppIcon.USERS,
                                            description = person.detail,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        FormSection(
            title = "Examen",
            description = "Elige el examen del catálogo. Un examen gratuito ya lo puede abrir todo el mundo.",
            icon = AppIcon.EXAMS,
            iconRole = SemanticRole.INFO,
        ) {
            val picked = state.pickedExam
            if (picked != null) {
                PickedRow(title = picked.title, detail = picked.detail, onChange = { onIntent(ExamAccessIntent.ClearExam) })
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    GlassSearchBar(
                        value = state.examQuery,
                        onValueChange = { onIntent(ExamAccessIntent.UpdateExamQuery(it)) },
                        placeholder = "Título o especialidad",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AsyncStatePane(
                        state = state.exams,
                        emptyTitle = "Sin exámenes",
                        emptyMessage = "Todavía no hay ningún examen en el catálogo.",
                        emptyIcon = AppIcon.EXAMS,
                        loadingRows = 3,
                        onRetry = { onIntent(ExamAccessIntent.RetryExams) },
                    ) { exams ->
                        val matches = examMatches(exams, state.examQuery)
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            if (matches.isEmpty()) Hint("Ningún examen coincide con «${state.examQuery.trim()}».")
                            matches.forEach { exam ->
                                key(exam.id) {
                                    WorkspaceCatalogRow(
                                        title = exam.title,
                                        selected = false,
                                        onClick = { onIntent(ExamAccessIntent.PickExam(exam.id)) },
                                        icon = AppIcon.EXAMS,
                                        description = exam.detail,
                                    )
                                }
                            }
                            val hidden = exams.count { exam ->
                                val q = state.examQuery.trim().lowercase()
                                q.isEmpty() || exam.title.lowercase().contains(q) || exam.detail.lowercase().contains(q)
                            } - matches.size
                            if (hidden > 0) Hint("Hay $hidden más. Escribe parte del título para acotar.")
                        }
                    }
                }
            }
        }
        FormSection(
            title = "Motivo",
            description = "Por qué se abre el examen. Queda guardado con el acceso y en auditoría.",
            icon = AppIcon.INFO,
            iconRole = SemanticRole.INFO,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.field)) {
                DropdownField(
                    label = "Motivo",
                    selectedLabel = ExamAccessTypes.label(state.reason),
                    options = ExamAccessTypes.grantable.map { it.first to it.second },
                    onSelect = { code -> code?.let { onIntent(ExamAccessIntent.UpdateReason(it)) } },
                    required = true,
                )
                FormFeedback(state = state.actionState, loadingText = "Concediendo el acceso…", successText = "Acceso concedido.")
            }
        }
    }
}

/** La elección ya hecha, con la salida para cambiarla. */
@Composable
private fun PickedRow(title: String, detail: String, onChange: () -> Unit) {
    WorkspacePanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = GlassTheme.tokens.textPrimary,
                )
                Text(text = detail, style = MaterialTheme.typography.bodySmall, color = GlassTheme.tokens.textSecondary)
            }
            GhostButton(text = "Cambiar", size = ButtonSize.COMPACT, onClick = onChange)
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = GlassTheme.tokens.textSecondary)
}

@Composable
private fun GrantSaveBar(state: ExamAccessState, onIntent: (ExamAccessIntent) -> Unit) {
    val saving = state.actionState is UiAsync.Loading
    val missing = when {
        state.pickedUser == null && state.pickedExam == null -> "Elige a la persona y el examen."
        state.pickedUser == null -> "Falta elegir a la persona."
        state.pickedExam == null -> "Falta elegir el examen."
        else -> null
    }
    WorkspaceSaveBar(
        message = when {
            saving -> "Concediendo el acceso…"
            missing != null -> missing
            else -> "${state.pickedUser?.label} podrá abrir «${state.pickedExam?.title}»."
        },
        tone = if (missing == null) WorkspaceSaveTone.SUCCESS else WorkspaceSaveTone.INFO,
        detail = if (missing == null) "Queda registrado en auditoría." else null,
        prominent = missing == null,
        primaryLabel = "Conceder acceso",
        canSave = missing == null && !saving,
        saving = saving,
        onSave = { onIntent(ExamAccessIntent.Grant) },
        onDiscard = if (!saving) ({ onIntent(ExamAccessIntent.DiscardDraft) }) else null,
    )
}

// ---------------------------------------------------------------------------------------------------------------------
// Rail
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun AccessRail(state: ExamAccessState, modifier: Modifier = Modifier, scrolls: Boolean = true) {
    WorkspaceRail(modifier = modifier, scrolls = scrolls) {
        WorkspaceSection(icon = AppIcon.ANALYTICS, role = SemanticRole.INSIGHT, title = "Todos los accesos") {
            when (state.accesses) {
                // Sin lista no hay recuentos: se dice, no se pintan ceros.
                UiAsync.Loading, UiAsync.Idle -> WorkspaceFacts(accessOverviewFacts(emptyList()).map { it.copy(value = "Cargando…") })
                is UiAsync.Error -> WorkspaceFacts(accessOverviewFacts(emptyList()).map { it.copy(value = "No disponible") })
                else -> WorkspaceFacts(accessOverviewFacts(state.allAccesses))
            }
        }
        WorkspaceSection(icon = AppIcon.INFO, role = SemanticRole.INFO, title = "Información") {
            WorkspaceInfoLines(accessInformationLines)
        }
    }
}
