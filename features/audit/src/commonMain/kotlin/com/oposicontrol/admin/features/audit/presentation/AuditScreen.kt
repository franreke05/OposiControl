package com.oposicontrol.admin.features.audit.presentation

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
import com.oposicontrol.admin.core.ui.components.DesktopVerticalScrollbar
import com.oposicontrol.admin.core.ui.components.DropdownField
import com.oposicontrol.admin.core.ui.components.EmptyStateCard
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GhostButton
import com.oposicontrol.admin.core.ui.components.GlassSearchBar
import com.oposicontrol.admin.core.ui.components.LabeledField
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.WorkspaceCatalogRow
import com.oposicontrol.admin.core.ui.components.WorkspaceEditor
import com.oposicontrol.admin.core.ui.components.WorkspaceEditorHeader
import com.oposicontrol.admin.core.ui.components.WorkspaceFact
import com.oposicontrol.admin.core.ui.components.WorkspaceFacts
import com.oposicontrol.admin.core.ui.components.WorkspaceInfoLines
import com.oposicontrol.admin.core.ui.components.WorkspaceLayout
import com.oposicontrol.admin.core.ui.components.WorkspaceNotice
import com.oposicontrol.admin.core.ui.components.WorkspaceNoticeTone
import com.oposicontrol.admin.core.ui.components.WorkspacePanel
import com.oposicontrol.admin.core.ui.components.WorkspacePill
import com.oposicontrol.admin.core.ui.components.WorkspaceRail
import com.oposicontrol.admin.core.ui.components.WorkspaceSection
import com.oposicontrol.admin.core.ui.components.WorkspaceSizing
import com.oposicontrol.admin.core.ui.components.humanMessage
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.AuditDetail
import com.oposicontrol.admin.domain.model.AuditMovement
import kotlinx.datetime.TimeZone

internal const val AUDIT_CATALOG_TAG: String = "audit-catalog"
internal const val AUDIT_EDITOR_TAG: String = "audit-editor"
internal const val AUDIT_RAIL_TAG: String = "audit-rail"

/**
 * Auditoría, en escritorio.
 *
 * El mismo workspace de tres columnas que Accesos y OposiTickets —catálogo, ficha y resumen—: aquí la fila es «alguien hizo
 * algo». Todo es de solo lectura: el registro se consulta, no se edita.
 */
@Composable
fun AuditScreen(viewModel: AuditViewModel) {
    val state by viewModel.state.collectAsState()
    AuditContent(state = state, onIntent = viewModel::dispatch)
}

/**
 * El workspace, por ESTADO: el banco de pruebas visual lo renderiza contra estados fabricados, sin Ktor ni Koin.
 *
 * [zone] es la zona en la que se leen las horas: la del equipo. El banco de pruebas fija una para que sus capturas no
 * dependan de dónde se ejecuten.
 */
@Composable
internal fun AuditContent(
    state: AuditState,
    onIntent: (AuditIntent) -> Unit,
    modifier: Modifier = Modifier,
    zone: TimeZone = TimeZone.currentSystemDefault(),
) {
    WorkspaceLayout(
        modifier = modifier,
        catalog = { paneModifier -> AuditCatalogPane(state, onIntent, zone, paneModifier.testTag(AUDIT_CATALOG_TAG)) },
        editor = { paneModifier, _, fillsHeight ->
            AuditEditorPane(state, onIntent, zone, fillsHeight, paneModifier.testTag(AUDIT_EDITOR_TAG))
        },
        rail = { paneModifier, scrolls -> AuditRail(state, paneModifier.testTag(AUDIT_RAIL_TAG), scrolls) },
    )
}

// ---------------------------------------------------------------------------------------------------------------------
// Catálogo
// ---------------------------------------------------------------------------------------------------------------------

private const val ALL_ACTIONS: String = "Todas las acciones"
private const val ALL_PEOPLE: String = "Todas las personas"

@Composable
private fun AuditCatalogPane(state: AuditState, onIntent: (AuditIntent) -> Unit, zone: TimeZone, modifier: Modifier = Modifier) {
    WorkspaceSection(
        icon = AppIcon.AUDIT,
        role = SemanticRole.INFO,
        title = "Movimientos",
        modifier = modifier,
        // Sin el recuento al lado: con él, a 1366 de ancho el título se recortaba. El recuento va al pie de la lista.
        action = {
            SecondaryButton(
                text = "Actualizar",
                size = ButtonSize.COMPACT,
                onClick = { onIntent(AuditIntent.Refresh) },
                icon = { AppIconImage(AppIcon.REFRESH, size = 14.dp) },
            )
        },
    ) {
        // Filtros y lista comparten UN desplazamiento: los filtros ocupan media columna y, fijos, dejarían la lista en
        // tres filas en una pantalla de 768 de alto.
        val scroll = rememberScrollState()
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(scroll).padding(end = WorkspaceSizing.scrollbarGutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                AuditFilters(state, onIntent)
                AsyncStatePane(
                    state = state.movements,
                    // Vacío significa dos cosas: sin filtros, «el registro está vacío»; con ellos, «ninguno ASÍ».
                    emptyTitle = if (state.hasFilters) "Ningún movimiento coincide" else "Todavía no hay movimientos",
                    emptyMessage = when {
                        // El buscador solo mira lo cargado: si quedan páginas, puede estar más abajo.
                        state.searchQuery.isNotBlank() && state.nextCursor != null ->
                            "Ninguno de los movimientos cargados coincide con la búsqueda. Puede haber más: carga otra página o usa los filtros."
                        state.hasFilters -> "No hay ningún movimiento con esos filtros. Prueba a ampliar las fechas o a quitar alguno."
                        else -> "Cuando alguien haga un cambio desde el panel, aparecerá aquí con quién lo hizo y cuándo."
                    },
                    emptyIcon = if (state.hasFilters) AppIcon.SEARCH else AppIcon.AUDIT,
                    loadingRows = 6,
                    onRetry = { onIntent(AuditIntent.Refresh) },
                    stableContent = true,
                ) { movements ->
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        movements.forEach { movement ->
                            key(movement.id) {
                                WorkspaceCatalogRow(
                                    title = auditActionLabel(movement.action),
                                    selected = movement.id == state.selectedId,
                                    onClick = { onIntent(AuditIntent.Select(movement.id)) },
                                    icon = AppIcon.AUDIT,
                                    // Sobre qué, en su propia línea: junto a la fecha, en una columna de 310, se recortaba.
                                    eyebrow = auditTargetLabel(movement),
                                    description = auditActorLabel(movement),
                                    meta = listOf(formatAuditMoment(movement.occurredAt, zone, withSeconds = false)),
                                )
                            }
                        }
                    }
                }
                LoadMore(state, onIntent)
            }
            DesktopVerticalScrollbar(scrollState = scroll, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable
private fun AuditFilters(state: AuditState, onIntent: (AuditIntent) -> Unit) {
    val reversed = auditDateRangeIsReversed(state.fromDate, state.untilDate)
    GlassSearchBar(
        value = state.searchQuery,
        onValueChange = { onIntent(AuditIntent.UpdateSearch(it)) },
        placeholder = "Buscar en lo cargado…",
        modifier = Modifier.fillMaxWidth(),
    )
    DropdownField(
        label = "Acción",
        selectedLabel = state.actionFilter?.let(::auditActionLabel) ?: ALL_ACTIONS,
        options = listOf<Pair<String?, String>>(null to ALL_ACTIONS) + auditActionLabels.map { it.key to it.value },
        onSelect = { onIntent(AuditIntent.SelectAction(it)) },
    )
    DropdownField(
        label = "Persona",
        selectedLabel = state.actorFilter?.let { id -> state.actorOptions.firstOrNull { it.id == id }?.label ?: id } ?: ALL_PEOPLE,
        options = listOf<Pair<String?, String>>(null to ALL_PEOPLE) + state.actorOptions.map { it.id to it.label },
        onSelect = { onIntent(AuditIntent.SelectActor(it)) },
        // Se puede elegir entre quienes ya han aparecido: el servidor no ofrece una lista de «todos los que han hecho algo».
        supportingText = if (state.actorOptions.isEmpty()) "Aparecerán aquí las personas de los movimientos cargados." else null,
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        LabeledField(
            label = "Desde",
            value = state.fromDate,
            onValueChange = { onIntent(AuditIntent.UpdateFromDate(it)) },
            placeholder = "AAAA-MM-DD",
            modifier = Modifier.weight(1f),
            supportingText = auditDateHint(state.fromDate),
            isError = auditDateInput(state.fromDate) == AuditDateInput.INVALID,
        )
        LabeledField(
            label = "Hasta",
            value = state.untilDate,
            onValueChange = { onIntent(AuditIntent.UpdateUntilDate(it)) },
            placeholder = "AAAA-MM-DD",
            modifier = Modifier.weight(1f),
            supportingText = if (reversed) "Es anterior a «Desde»." else auditDateHint(state.untilDate),
            isError = reversed || auditDateInput(state.untilDate) == AuditDateInput.INVALID,
        )
    }
    if (state.hasFilters) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            GhostButton(text = "Quitar filtros", size = ButtonSize.COMPACT, onClick = { onIntent(AuditIntent.ClearFilters) })
        }
    }
}

/** «Cargar más»: solo cuando el servidor dijo que hay otra página y la lista no está cargando ni caída. */
@Composable
private fun LoadMore(state: AuditState, onIntent: (AuditIntent) -> Unit) {
    if (state.movements is UiAsync.Loading || state.movements is UiAsync.Error) return
    // Cuántos hay CARGADOS, no cuántos hay: el servidor pagina y no da un total.
    if (state.allMovements.isNotEmpty()) Hint(auditLoadedSummary(state.allMovements.size, hasMore = state.nextCursor != null))
    if (state.nextCursor == null) return
    SecondaryButton(
        text = if (state.loadingMore) "Cargando…" else "Cargar más",
        size = ButtonSize.COMPACT,
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.loadingMore,
        loading = state.loadingMore,
        onClick = { onIntent(AuditIntent.LoadMore) },
    )
    state.loadMoreError?.let { error ->
        WorkspaceNotice(
            tone = WorkspaceNoticeTone.WARNING,
            title = "No se han podido cargar más movimientos",
            detail = "${error.humanMessage()} Lo ya cargado sigue aquí: vuelve a pulsar «Cargar más».",
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Ficha
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun AuditEditorPane(
    state: AuditState,
    onIntent: (AuditIntent) -> Unit,
    zone: TimeZone,
    fillsHeight: Boolean,
    modifier: Modifier = Modifier,
) {
    val movement = state.selected
    WorkspaceEditor(
        modifier = modifier,
        fillsHeight = fillsHeight,
        header = {
            WorkspaceEditorHeader(
                icon = AppIcon.AUDIT,
                title = movement?.let { auditActionLabel(it.action) } ?: "Auditoría",
                subtitle = movement?.let { "${auditActorLabel(it)} · ${formatAuditMoment(it.occurredAt, zone)}" }
                    ?: "Ningún movimiento abierto.",
            ) {
                movement?.let { WorkspacePill(text = auditTargetLabel(it), icon = AppIcon.AUDIT) }
            }
        },
        // Otro movimiento: el cuerpo entra. Cargar su antes y su después, no.
        contentKey = state.selectedId,
    ) {
        if (movement != null) {
            MovementDetail(state, movement, onIntent, zone)
        } else {
            // «Elige uno del catálogo» solo es verdad si en el catálogo hay alguno: cada estado dice lo suyo.
            val (title, message) = when (state.movements) {
                UiAsync.Loading, UiAsync.Idle -> "Cargando movimientos" to "La ficha se abrirá con el más reciente."
                is UiAsync.Error -> "Sin movimientos que enseñar" to "No se ha podido leer el registro. Reinténtalo desde el catálogo."
                UiAsync.Empty -> "Nada que abrir" to if (state.hasFilters) {
                    "Ningún movimiento coincide con la búsqueda y los filtros."
                } else {
                    "El registro todavía no tiene movimientos."
                }
                is UiAsync.Success -> "Sin movimiento abierto" to "Elige un movimiento del catálogo para ver quién lo hizo, sobre qué y qué cambió."
            }
            EmptyStateCard(title = title, message = message, icon = AppIcon.AUDIT)
        }
    }
}

@Composable
private fun MovementDetail(state: AuditState, movement: AuditMovement, onIntent: (AuditIntent) -> Unit, zone: TimeZone) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        FormSection(
            title = "Detalle del movimiento",
            description = "Quién hizo qué, sobre qué y cuándo. Solo lectura: el registro no se edita ni se borra desde aquí.",
            icon = AppIcon.AUDIT,
            iconRole = SemanticRole.INFO,
        ) {
            WorkspaceFacts(movementFacts(movement, zone))
        }
        FormSection(
            title = "Qué cambió",
            description = "Lo que había antes y lo que quedó, tal como lo guardó el servidor al hacer la acción.",
            icon = AppIcon.INFO,
            iconRole = SemanticRole.INFO,
        ) {
            // En reposo no hay nada cargando: se dice qué hacer, no se enseña un esqueleto.
            if (state.detail is UiAsync.Idle) {
                EmptyStateCard(
                    title = "Cambios sin cargar",
                    message = "El antes y el después se piden al servidor solo cuando hacen falta.",
                    icon = AppIcon.INFO,
                    action = {
                        SecondaryButton(text = "Ver qué cambió", size = ButtonSize.COMPACT, onClick = { onIntent(AuditIntent.LoadDetail) })
                    },
                )
            } else {
                AsyncStatePane(
                    state = state.detail,
                    loadingRows = 3,
                    onRetry = { onIntent(AuditIntent.LoadDetail) },
                ) { detail -> Changes(detail) }
            }
        }
    }
}

/** Los cuatro datos de la ficha. El valor es lo que se lee; el detalle, lo que se copia o lo que explica una ausencia. */
private fun movementFacts(movement: AuditMovement, zone: TimeZone): List<WorkspaceFact> = listOf(
    WorkspaceFact(
        label = "Quién",
        value = auditActorLabel(movement),
        detail = when {
            movement.actorId == null -> "No lo hizo una persona con sesión en el panel."
            movement.actorName.isNullOrBlank() -> "No se ha encontrado su perfil: se muestra su identificador."
            else -> movement.actorId
        },
        emphasised = true,
    ),
    WorkspaceFact(
        label = "Qué",
        value = auditActionLabel(movement.action),
        detail = if (auditActionIsKnown(movement.action)) movement.action else "Código sin nombre en esta versión del panel.",
        emphasised = true,
    ),
    WorkspaceFact(
        label = "Sobre qué",
        value = auditTargetLabel(movement),
        detail = movement.recordId?.let { "Registro $it" } ?: "El movimiento no apunta a un registro concreto.",
    ),
    WorkspaceFact(
        label = "Cuándo",
        value = formatAuditMoment(movement.occurredAt, zone),
        detail = "Hora de este equipo (${zone.id}).",
    ),
)

@Composable
private fun Changes(detail: AuditDetail) {
    val rows = auditChangeRows(detail.before, detail.after)
    if (rows.isEmpty()) {
        WorkspaceNotice(
            tone = WorkspaceNoticeTone.INFO,
            title = "Este movimiento no guardó cambios",
            detail = "El servidor anotó la acción, quién la hizo y cuándo, pero no el estado anterior ni el resultante.",
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        WorkspacePanel(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            rows.forEach { row ->
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(text = row.label, style = MaterialTheme.typography.labelMedium, color = GlassTheme.tokens.textSecondary)
                    // Sin límite de líneas: un motivo largo se lee entero, que para eso se guardó.
                    Text(
                        text = row.text,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (row.before != null && row.after != null && row.before != row.after) FontWeight.SemiBold else FontWeight.Normal,
                        color = GlassTheme.tokens.textPrimary,
                    )
                    row.note?.let { Hint(it) }
                }
            }
        }
        // Un lado que no se guardó no es un lado vacío: se dice cuál falta.
        when {
            detail.before == null -> Hint("No se guardó el estado anterior: solo se ve lo que quedó.")
            detail.after == null -> Hint("No se guardó el estado resultante: solo se ve lo que había.")
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = GlassTheme.tokens.textSecondary)
}

// ---------------------------------------------------------------------------------------------------------------------
// Rail
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun AuditRail(state: AuditState, modifier: Modifier = Modifier, scrolls: Boolean = true) {
    WorkspaceRail(modifier = modifier, scrolls = scrolls) {
        WorkspaceSection(icon = AppIcon.ANALYTICS, role = SemanticRole.INSIGHT, title = "Lo cargado") {
            when (state.movements) {
                // Sin lista no hay recuentos: se dice, no se pintan ceros.
                UiAsync.Loading, UiAsync.Idle -> WorkspaceFacts(auditOverviewFacts(state).map { it.copy(value = "Cargando…") })
                is UiAsync.Error -> WorkspaceFacts(auditOverviewFacts(state).map { it.copy(value = "No disponible") })
                else -> WorkspaceFacts(auditOverviewFacts(state))
            }
        }
        WorkspaceSection(icon = AppIcon.INFO, role = SemanticRole.INFO, title = "Qué queda registrado") {
            WorkspaceInfoLines(auditRecordedLines)
        }
        WorkspaceSection(icon = AppIcon.INFO, role = SemanticRole.INFO, title = "Qué no queda registrado") {
            WorkspaceInfoLines(auditNotRecordedLines)
        }
    }
}
