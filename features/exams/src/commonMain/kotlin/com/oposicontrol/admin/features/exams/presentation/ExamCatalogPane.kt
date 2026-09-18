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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.DesktopIconButton
import com.oposicontrol.admin.core.ui.components.EmptyStateCard
import com.oposicontrol.admin.core.ui.components.GhostButton
import com.oposicontrol.admin.core.ui.components.GlassSearchBar
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SemanticIconContainer
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.components.StatusTone
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamStatus

/**
 * El catálogo: buscar un examen y abrirlo.
 *
 * Todo lo que hace esta columna ocurre en el cliente, sobre la lista que ya está
 * descargada, y la interfaz no finge lo contrario:
 *
 *  - **Los filtros no son consultas.** `GET /examenes` no acepta un solo
 *    parámetro; lo que hay es una lista completa en memoria que se recorta aquí.
 *  - **La paginación tampoco.** Por eso la barra de páginas dice siempre cuántos
 *    exámenes hay en total: un «1 2 3 … 12» a secas se leería como páginas que
 *    se piden al servidor, y no las hay.
 *
 * Búsqueda y Estado se resuelven en el ViewModel (que también filtra en cliente)
 * porque son estado que sobrevive a recomponer la columna; Especialidad, Tipo y
 * Año se resuelven aquí porque no hacen falta en ningún otro sitio.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExamCatalogPane(
    state: ExamsState,
    onIntent: (ExamsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var specialtyFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var typeFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var yearFilter by rememberSaveable { mutableStateOf<Int?>(null) }
    var pageIndex by rememberSaveable { mutableStateOf(0) }

    val specialties = (state.specialties as? UiAsync.Success)?.value.orEmpty()
    val loadedExams = (state.exams as? UiAsync.Success)?.value.orEmpty()
    val availableYears = loadedExams.mapNotNull { it.year }.distinct().sortedDescending()
    val statusFilter = state.statusFilter.trim().lowercase()
    val filter = ExamCatalogFilter(
        specialtyId = specialtyFilter,
        typeWire = typeFilter,
        year = yearFilter,
    )
    val hasActiveFilters = filter.isActive ||
        (statusFilter.isNotBlank() && statusFilter != "all") ||
        state.searchQuery.isNotBlank()

    // Volver a la primera página cuando cambia lo que se está mirando: quedarse
    // en la página 4 de una lista que ahora tiene una página es la forma más
    // rápida de que el catálogo parezca vacío sin estarlo.
    LaunchedEffect(specialtyFilter, typeFilter, yearFilter, statusFilter, state.searchQuery) {
        pageIndex = 0
    }

    ExamWorkspaceSection(
        icon = AppIcon.EXAMS,
        role = SemanticRole.INFO,
        title = "Exámenes",
        modifier = modifier,
        // El total va en la cabecera de la columna, no sólo en la barra de
        // páginas: es el primer dato que se busca al abrir la pantalla —«¿cuántos
        // hay?»— y tenerlo abajo obligaba a recorrer la lista para encontrarlo.
        action = {
            (state.exams as? UiAsync.Success)?.value?.size?.let { total ->
                ExamCountBadge(total)
            }
        },
    ) {
        GlassSearchBar(
            value = state.searchQuery,
            onValueChange = { onIntent(ExamsIntent.UpdateSearch(it)) },
            placeholder = "Buscar examen…",
            modifier = Modifier.fillMaxWidth(),
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            ExamFilterPill(
                label = "Especialidad",
                selectedLabel = specialties.firstOrNull { it.id == specialtyFilter }?.name,
                options = listOf<Pair<String?, String>>(null to "Cualquier especialidad") +
                    specialties.map { it.id to it.name },
                onSelect = { specialtyFilter = it },
            )
            ExamFilterPill(
                label = "Tipo",
                selectedLabel = typeFilter?.let { ExamTypeOption.labelOf(it) },
                options = listOf<Pair<String?, String>>(null to "Cualquier tipo") +
                    ExamTypeOption.entries.map { it.wireValue to it.label },
                onSelect = { typeFilter = it },
            )
            ExamFilterPill(
                label = "Estado",
                selectedLabel = ExamStatus.entries
                    .firstOrNull { it.name.lowercase() == statusFilter }
                    ?.let(::examStatusLabel),
                options = listOf<Pair<String?, String>>(null to "Cualquier estado") +
                    ExamStatus.entries.map { it.name.lowercase() to examStatusLabel(it) },
                onSelect = { value -> onIntent(ExamsIntent.UpdateStatusFilter(value ?: "all")) },
            )
            ExamFilterPill(
                label = "Año",
                selectedLabel = yearFilter?.toString(),
                options = listOf<Pair<String?, String>>(null to "Cualquier año") +
                    availableYears.map { it.toString() to it.toString() },
                onSelect = { yearFilter = it?.toIntOrNull() },
            )
            if (hasActiveFilters) {
                GhostButton(
                    text = "Limpiar",
                    size = ButtonSize.COMPACT,
                    onClick = {
                        specialtyFilter = null
                        typeFilter = null
                        yearFilter = null
                        onIntent(ExamsIntent.UpdateStatusFilter("all"))
                        onIntent(ExamsIntent.UpdateSearch(""))
                    },
                )
            }
        }

        AsyncStatePane(
            state = state.exams,
            // La lista vacía significa dos cosas distintas y no se pueden contar
            // igual: sin filtros es «no hay exámenes» e invita a crear uno; con
            // la búsqueda o el estado puestos es «no hay ninguno ASÍ», y ofrecer
            // ahí «Crear examen» sería responder a una pregunta que nadie hizo.
            emptyTitle = if (hasActiveFilters) "Sin resultados" else "Sin exámenes",
            emptyMessage = if (hasActiveFilters) {
                "Ningún examen coincide con la búsqueda y los filtros actuales."
            } else {
                "Todavía no se ha creado ningún examen."
            },
            emptyIcon = if (hasActiveFilters) AppIcon.SEARCH else AppIcon.EXAMS,
            loadingRows = 6,
            onRetry = { onIntent(ExamsIntent.RefreshAll) },
            emptyAction = {
                if (!hasActiveFilters) {
                    // La misma etiqueta y el mismo efecto que la acción de la
                    // página: abre un borrador en blanco, no crea nada. «Crear
                    // examen» es el botón que lo persiste, en la barra de guardado.
                    PrimaryButton(
                        text = "Nuevo examen",
                        onClick = { onIntent(ExamsIntent.SwitchExam(null)) },
                        size = ButtonSize.COMPACT,
                    )
                }
            },
        ) { exams ->
            val filtered = applyCatalogFilters(exams, filter)
            if (filtered.isEmpty()) {
                EmptyStateCard(
                    title = "Sin resultados",
                    message = "Ningún examen coincide con la búsqueda y los filtros actuales.",
                    icon = AppIcon.SEARCH,
                )
            } else {
                val page = examCatalogPage(filtered, pageIndex)
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    // La barra de páginas va ARRIBA, no debajo de la lista. No es
                    // una preferencia: la lista desplaza y ocupa todo el alto que
                    // le queda a la columna, así que un pie por debajo de ella se
                    // quedaría sin sitio que ocupar. Arriba mide primero y además
                    // hace de recuento de resultados, que es lo que uno quiere
                    // leer justo después de filtrar.
                    ExamCatalogPager(
                        page = page,
                        onPageChange = { pageIndex = it },
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        page.items.forEach { exam ->
                            ExamCatalogCard(
                                exam = exam,
                                selected = exam.id == state.selectedExamId,
                                // El abierto lee su recuento de la misma fuente
                                // que la cabecera; uno cerrado, el de esta sesión
                                // si lo hay y, si no, el servido con su marca de
                                // no confirmado (P2-8). Nunca el servido a secas
                                // como si fuera un recuento.
                                questionsLabel = examCatalogQuestionsLabel(state, exam),
                                onClick = { onIntent(ExamsIntent.SwitchExam(exam.id)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tarjeta de examen.
 *
 * Sin identificadores internos: lo que orienta es el código humano, el título,
 * la especialidad, el año y los dos chips. Seleccionada se marca con una barra
 * coral a la izquierda y un fondo muy tenue — no con un borde de color alrededor
 * de toda la tarjeta, que en una lista de doce compite con el contenido.
 */
@Composable
private fun ExamCatalogCard(
    exam: Exam,
    selected: Boolean,
    questionsLabel: String,
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
            .clip(Radius.card)
            .background(background)
            .border(1.dp, if (selected) tokens.accent.copy(alpha = 0.45f) else tokens.border, Radius.card)
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            // Contenedor de icono también en la tarjeta: la lista deja de ser una
            // columna de bloques de texto y pasa a tener un punto de anclaje por
            // fila. En la seleccionada el rol se vuelve BRAND, así que el coral
            // que ya marca el borde y el fondo se remata dentro.
            SemanticIconContainer(
                icon = AppIcon.EXAMS,
                role = if (selected) SemanticRole.BRAND else SemanticRole.INFO,
                size = Sizing.iconButton,
                iconSize = Sizing.icon,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                // Código y estado comparten línea: los dos son «cómo se llama esto
                // y en qué punto está», y juntos dejan el título entero para el
                // renglón de abajo.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = exam.code.ifBlank { "Sin código" },
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip(status = examStatusLabel(exam.status), tone = examStatusTone(exam.status))
                }
                Text(
                    text = exam.title.ifBlank { "Examen sin título" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        exam.specialtyName.takeIf { it.isNotBlank() },
                        exam.year?.toString(),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Línea de meta: tipo · preguntas · duración. El tipo NO es un chip
                // de estado —lo fue, en tono neutro, y se leía con el mismo peso
                // que «Publicado» siendo otra clase de dato— sino la primera
                // palabra de esta línea.
                Text(
                    text = listOfNotNull(
                        ExamTypeOption.labelOf(exam.typeExam),
                        questionsLabel,
                        durationLabel(exam.durationMinutes),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Cuántos exámenes hay en total, en la cabecera de la columna. */
@Composable
private fun ExamCountBadge(total: Int) {
    val tokens = GlassTheme.tokens
    Text(
        text = total.toString(),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = examGlassText(tokens).primary,
        modifier = Modifier
            .clip(Radius.pill)
            .background(examGlassPillFill(tokens))
            .border(1.dp, tokens.sectionGlassStroke, Radius.pill)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/**
 * Barra de páginas.
 *
 * Dice el rango y el total, no sólo el número de página. Es la diferencia entre
 * «esto es un trozo de lo que hay aquí» y «esto es una página que el servidor me
 * ha dado», y sólo lo primero es verdad.
 */
@Composable
private fun ExamCatalogPager(
    page: ExamCatalogPage<*>,
    onPageChange: (Int) -> Unit,
) {
    val tokens = GlassTheme.tokens
    Row(
        modifier = Modifier.fillMaxWidth().height(Sizing.buttonCompact),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = examCatalogRangeText(page),
            style = MaterialTheme.typography.bodySmall,
            color = examGlassText(tokens).secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (page.isPaginated) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DesktopIconButton(
                    label = "Página anterior",
                    enabled = page.hasPrevious,
                    size = Sizing.iconButtonSmall,
                    onClick = { onPageChange(page.pageIndex - 1) },
                ) {
                    AppIconImage(AppIcon.CHEVRON_LEFT, size = 14.dp)
                }
                Text(
                    text = "${page.pageIndex + 1} / ${page.pageCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = examGlassText(tokens).primary,
                )
                DesktopIconButton(
                    label = "Página siguiente",
                    enabled = page.hasNext,
                    size = Sizing.iconButtonSmall,
                    onClick = { onPageChange(page.pageIndex + 1) },
                ) {
                    AppIconImage(AppIcon.CHEVRON_RIGHT, size = 14.dp)
                }
            }
        }
    }
}

/**
 * Píldora de filtro con desplegable.
 *
 * Opaca, no de cristal: es un control de formulario y vive dentro de un
 * contenedor translúcido. Un control translúcido sobre cristal toma un color
 * distinto en cada punto de la pantalla, que es justo lo que el nivel 2 existe
 * para evitar.
 */
@Composable
private fun ExamFilterPill(
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
                .defaultMinSize(minHeight = Sizing.buttonCompact)
                .clip(Radius.pill)
                .background(examFilterPillBackground(tokens, active))
                .border(
                    width = 1.dp,
                    color = if (active) tokens.accent.copy(alpha = 0.55f) else tokens.border,
                    shape = Radius.pill,
                )
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable { expanded = true }
                .padding(horizontal = Spacing.sm, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = selectedLabel ?: label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = examFilterPillLabelColor(tokens, active),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            AppIconImage(
                icon = AppIcon.CHEVRON_DOWN,
                size = 13.dp,
                tint = examFilterPillChevronTint(tokens, active),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, optionLabel) ->
                DropdownMenuItem(
                    text = { Text(optionLabel) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** El tono del chip de estado. Tres estados, tres tonos, sin cuarto por descarte. */
internal fun examStatusTone(status: ExamStatus): StatusTone = when (status) {
    ExamStatus.DRAFT -> StatusTone.ATTENTION
    ExamStatus.PUBLISHED -> StatusTone.POSITIVE
    ExamStatus.ARCHIVED -> StatusTone.NEGATIVE
}

// ---------------------------------------------------------------------------
// Colores de la píldora de filtro.
//
// Funciones para que el test de contraste mida exactamente lo que se pinta.
// ---------------------------------------------------------------------------

/**
 * El texto de una píldora de filtro.
 *
 * Activa, en `textPrimary` y no en `accentInk`: medido, `accentInk` sobre el tinte
 * `surfaceSelected` de la píldora activa da 4,21:1 en el tema claro, por debajo
 * del 4,5:1 de un texto de 13sp. Es el mismo defecto y el mismo remedio que la
 * etiqueta de la pestaña activa del workspace: el coral se queda en el borde y en
 * el chevron, que son elementos de interfaz (3:1), y el texto se lee.
 */
internal fun examFilterPillLabelColor(
    tokens: com.oposicontrol.admin.core.ui.theme.GlassTokens,
    active: Boolean,
): Color = if (active) tokens.textPrimary else tokens.textSecondary

/** El chevron de una píldora de filtro: coral si está activa, que es donde el coral sí llega. */
internal fun examFilterPillChevronTint(
    tokens: com.oposicontrol.admin.core.ui.theme.GlassTokens,
    active: Boolean,
): Color = if (active) tokens.accentInk else tokens.textSecondary

/** El fondo de una píldora de filtro, el mismo que mide el test. */
internal fun examFilterPillBackground(
    tokens: com.oposicontrol.admin.core.ui.theme.GlassTokens,
    active: Boolean,
): Color = if (active) tokens.surfaceSelected else tokens.surfaceSolid
