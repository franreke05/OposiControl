package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.EmptyStateCard
import com.oposicontrol.admin.core.ui.components.GlassActionButton
import com.oposicontrol.admin.core.ui.components.GlassSurface
import com.oposicontrol.admin.core.ui.components.GlassTextField
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.layout.UiSpacing
import com.oposicontrol.admin.core.ui.theme.AdminTheme
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamQuestion
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.features.news.presentation.NewsIntent
import com.oposicontrol.admin.features.news.presentation.NewsItem
import com.oposicontrol.admin.features.news.presentation.NewsState
import com.oposicontrol.admin.features.news.presentation.NewsViewModel
import kotlinx.datetime.Instant

@Composable
fun MobileContentScreen(
    examsViewModel: ExamsViewModel,
    newsViewModel: NewsViewModel,
    initialView: MobileContentView = MobileContentView.Solucionarios,
    connectionDiagnostics: @Composable () -> Unit = {},
) {
    val examsState by examsViewModel.state.collectAsState()
    val newsState by newsViewModel.state.collectAsState()
    MobileContentContent(
        state = examsState,
        newsState = newsState,
        onIntent = examsViewModel::dispatch,
        onNewsIntent = newsViewModel::dispatch,
        initialView = initialView,
        connectionDiagnostics = connectionDiagnostics,
    )
}

@Preview
@Composable
fun MobileContentScreenPreview() {
    val mockExamsState = ExamsState(
        exams = UiAsync.Success(
            listOf(
                Exam(
                    id = "1",
                    specialtyId = "s1",
                    specialtyName = "TCAE",
                    title = "Examen de prueba",
                    description = "Descripcion",
                    status = ExamStatus.PUBLISHED,
                    publishFrom = null,
                    sortOrder = 0,
                    questionCount = 10,
                    createdAt = Instant.parse("2024-01-01T00:00:00Z"),
                    updatedAt = Instant.parse("2024-01-01T00:00:00Z"),
                ),
            ),
        ),
        specialties = UiAsync.Success(
            listOf(
                Specialty(
                    id = "s1",
                    name = "TCAE",
                    description = "",
                    isActive = true,
                    sortOrder = 0,
                    linkedExams = 1,
                    createdAt = Instant.parse("2024-01-01T00:00:00Z"),
                    updatedAt = Instant.parse("2024-01-01T00:00:00Z"),
                ),
            ),
        ),
    )

    val mockNewsState = NewsState(
        news = UiAsync.Success(
            listOf(
                NewsItem(
                    id = "n1",
                    title = "Noticia de prueba",
                    specialty = "TCAE",
                    type = "Convocatoria",
                    backgroundType = "A",
                    status = "published",
                    publishFrom = "2024-01-01",
                    text = "Cuerpo de la noticia",
                ),
            ),
        ),
    )

    AdminTheme {
        MobileContentContent(
            state = mockExamsState,
            newsState = mockNewsState,
            onIntent = {},
            onNewsIntent = {},
            initialView = MobileContentView.Noticias,
        )
    }
}

@Composable
fun MobileContentContent(
    state: ExamsState,
    newsState: NewsState,
    onIntent: (ExamsIntent) -> Unit,
    onNewsIntent: (NewsIntent) -> Unit,
    initialView: MobileContentView = MobileContentView.Noticias,
    connectionDiagnostics: @Composable () -> Unit = {},
) {
    var activeView by rememberSaveable { mutableStateOf(initialView) }
    var isNewsFullscreen by rememberSaveable { mutableStateOf(false) }

    if (isNewsFullscreen) {
        MobileLayeredNewsEditor(
            state = newsState,
            onIntent = onNewsIntent,
            isFullscreen = true,
            onToggleFullscreen = { isNewsFullscreen = false }
        )
    } else {
        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = UiSpacing.screenMobile,
                        top = UiSpacing.mobileTopBar,
                        end = UiSpacing.screenMobile,
                        bottom = UiSpacing.mobileBottomBarClearance + navBarBottom,
                    ),
                verticalArrangement = Arrangement.spacedBy(UiSpacing.normal),
            ) {
                MobileTopBar(
                    activeView = activeView,
                    subtitle = if (activeView == MobileContentView.Noticias) {
                        newsState.editorTitle.ifBlank { "Sin seleccion" }
                    } else {
                        state.editorTitle.ifBlank { "Sin seleccion" }
                    },
                    status = if (activeView == MobileContentView.Noticias) {
                        newsState.editorStatus.ifBlank { "draft" }
                    } else {
                        state.editorStatus.ifBlank { "draft" }
                    },
                )
                connectionDiagnostics()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(UiSpacing.block),
                ) {
                    when (activeView) {
                        MobileContentView.Solucionarios -> SolucionarioList(
                            state = state,
                            onIntent = onIntent,
                            onCreate = {
                                onIntent(ExamsIntent.CreateNew)
                                activeView = MobileContentView.Editor
                            },
                        )

                        MobileContentView.Editor -> SolucionarioEditor(state = state, onIntent = onIntent)
                        MobileContentView.Preguntas -> QuestionsPanel(state = state, onIntent = onIntent)
                        MobileContentView.Recursos -> ResourceTray(state = state, onIntent = onIntent)
                        MobileContentView.Noticias -> MobileNewsPanel(
                            state = newsState,
                            onIntent = onNewsIntent,
                            isFullscreen = false,
                            onToggleFullscreen = { isNewsFullscreen = true }
                        )
                    }
                }
            }

            MobileBottomTabBar(
                activeView = activeView,
                onSelected = { activeView = it },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

enum class MobileContentView(val label: String, val title: String, val icon: MobileIcon) {
    Solucionarios("Contenido", "Contenido", MobileIcon.Stack),
    Editor("Editor", "Solucionario", MobileIcon.Edit),
    Preguntas("Preguntas", "Preguntas", MobileIcon.Question),
    Recursos("Recursos", "Recursos", MobileIcon.Media),
    Noticias("Noticias", "Noticias", MobileIcon.News),
}

enum class MobileIcon {
    Stack,
    Edit,
    Question,
    Media,
    News,
    Plus,
    Import,
    Up,
    Down,
    Save,
    Remove,
}

@Composable
private fun MobileTopBar(
    activeView: MobileContentView,
    subtitle: String,
    status: String,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBadge(activeView.icon, selected = true)
                Column(verticalArrangement = Arrangement.spacedBy(1.dp), modifier = Modifier.width(176.dp)) {
                    Text(
                        text = activeView.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            StatusChip(status = status)
        }
    }
}

@Composable
private fun MobileBottomTabBar(
    activeView: MobileContentView,
    onSelected: (MobileContentView) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        tint = GlassTheme.tokens.glassFillStrong,
    ) {
        // Column inside BoxScope: tabs row + nav bar spacer (glass extends under system nav)
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(UiSpacing.mobileBottomBar)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MobileContentView.entries.forEach { view ->
                    MobileTabItem(
                        view = view,
                        selected = view == activeView,
                        onSelected = onSelected,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            // Transparent glass fill below tab row, over system navigation bar
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsBottomHeight(WindowInsets.navigationBars),
            )
        }
    }
}

@Composable
private fun MobileTabItem(
    view: MobileContentView,
    selected: Boolean,
    onSelected: (MobileContentView) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable { onSelected(view) }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        Box(
            modifier = Modifier
                .width(if (selected) 24.dp else 0.dp)
                .height(3.dp)
                .background(
                    color = if (selected) GlassTheme.tokens.accent else Color.Transparent,
                    shape = RoundedCornerShape(2.dp),
                )
        )
        LineIcon(
            icon = view.icon,
            modifier = Modifier.size(22.dp),
            color = if (selected) GlassTheme.tokens.accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = view.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) GlassTheme.tokens.accent else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MobileActionPill(
    icon: MobileIcon,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tint = if (enabled) GlassTheme.tokens.accent else GlassTheme.tokens.glassFillStrong
    val foreground = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    GlassSurface(
        modifier = modifier
            .defaultMinSize(minWidth = 88.dp, minHeight = 48.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        tint = tint,
        strokeColor = tint.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier
                .height(48.dp)
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LineIcon(icon = icon, modifier = Modifier.size(18.dp), color = foreground)
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun IconBadge(
    icon: MobileIcon,
    selected: Boolean,
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(42.dp)
            .background(background, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        LineIcon(icon = icon, modifier = Modifier.size(22.dp), color = foreground)
    }
}

@Composable
private fun LineIcon(
    icon: MobileIcon,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.095f, cap = StrokeCap.Round)
        when (icon) {
            MobileIcon.Stack -> {
                drawRoundRect(color, Offset(w * 0.16f, h * 0.2f), Size(w * 0.68f, h * 0.18f), CornerRadius(w * 0.08f), style = stroke)
                drawRoundRect(color, Offset(w * 0.16f, h * 0.42f), Size(w * 0.68f, h * 0.18f), CornerRadius(w * 0.08f), style = stroke)
                drawRoundRect(color, Offset(w * 0.16f, h * 0.64f), Size(w * 0.68f, h * 0.18f), CornerRadius(w * 0.08f), style = stroke)
            }
            MobileIcon.Edit -> {
                drawLine(color, Offset(w * 0.22f, h * 0.78f), Offset(w * 0.78f, h * 0.22f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.66f, h * 0.18f), Offset(w * 0.82f, h * 0.34f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.18f, h * 0.82f), Offset(w * 0.38f, h * 0.78f), stroke.width, StrokeCap.Round)
            }
            MobileIcon.Question -> {
                drawCircle(color, radius = w * 0.28f, center = Offset(w * 0.5f, h * 0.36f), style = stroke)
                drawLine(color, Offset(w * 0.5f, h * 0.62f), Offset(w * 0.5f, h * 0.74f), stroke.width, StrokeCap.Round)
                drawCircle(color, radius = w * 0.035f, center = Offset(w * 0.5f, h * 0.86f))
            }
            MobileIcon.Media -> {
                drawRoundRect(color, Offset(w * 0.15f, h * 0.2f), Size(w * 0.7f, h * 0.6f), CornerRadius(w * 0.08f), style = stroke)
                drawCircle(color, radius = w * 0.08f, center = Offset(w * 0.35f, h * 0.42f), style = stroke)
                drawLine(color, Offset(w * 0.24f, h * 0.7f), Offset(w * 0.48f, h * 0.54f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.48f, h * 0.54f), Offset(w * 0.76f, h * 0.72f), stroke.width, StrokeCap.Round)
            }
            MobileIcon.News -> {
                drawRoundRect(color, Offset(w * 0.16f, h * 0.18f), Size(w * 0.68f, h * 0.64f), CornerRadius(w * 0.08f), style = stroke)
                drawLine(color, Offset(w * 0.28f, h * 0.36f), Offset(w * 0.72f, h * 0.36f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.28f, h * 0.52f), Offset(w * 0.72f, h * 0.52f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.28f, h * 0.68f), Offset(w * 0.58f, h * 0.68f), stroke.width, StrokeCap.Round)
            }
            MobileIcon.Plus -> {
                drawLine(color, Offset(w * 0.5f, h * 0.2f), Offset(w * 0.5f, h * 0.8f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.2f, h * 0.5f), Offset(w * 0.8f, h * 0.5f), stroke.width, StrokeCap.Round)
            }
            MobileIcon.Import -> {
                drawLine(color, Offset(w * 0.5f, h * 0.18f), Offset(w * 0.5f, h * 0.62f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.32f, h * 0.44f), Offset(w * 0.5f, h * 0.62f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.68f, h * 0.44f), Offset(w * 0.5f, h * 0.62f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.24f, h * 0.8f), Offset(w * 0.76f, h * 0.8f), stroke.width, StrokeCap.Round)
            }
            MobileIcon.Up -> {
                drawLine(color, Offset(w * 0.5f, h * 0.2f), Offset(w * 0.5f, h * 0.82f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.28f, h * 0.42f), Offset(w * 0.5f, h * 0.2f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.72f, h * 0.42f), Offset(w * 0.5f, h * 0.2f), stroke.width, StrokeCap.Round)
            }
            MobileIcon.Down -> {
                drawLine(color, Offset(w * 0.5f, h * 0.18f), Offset(w * 0.5f, h * 0.8f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.28f, h * 0.58f), Offset(w * 0.5f, h * 0.8f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.72f, h * 0.58f), Offset(w * 0.5f, h * 0.8f), stroke.width, StrokeCap.Round)
            }
            MobileIcon.Save -> {
                drawRoundRect(color, Offset(w * 0.18f, h * 0.18f), Size(w * 0.64f, h * 0.64f), CornerRadius(w * 0.08f), style = stroke)
                drawLine(color, Offset(w * 0.32f, h * 0.18f), Offset(w * 0.32f, h * 0.42f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.34f, h * 0.68f), Offset(w * 0.66f, h * 0.68f), stroke.width, StrokeCap.Round)
            }
            MobileIcon.Remove -> {
                drawLine(color, Offset(w * 0.24f, h * 0.5f), Offset(w * 0.76f, h * 0.5f), stroke.width, StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun SolucionarioEditor(
    state: ExamsState,
    onIntent: (ExamsIntent) -> Unit,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(UiSpacing.panel),
            verticalArrangement = Arrangement.spacedBy(UiSpacing.normal),
        ) {
            Text(
                text = if (state.selectedExamId == null) "Crear solucionario" else "Editar solucionario",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            GlassTextField(
                value = state.editorTitle,
                onValueChange = { onIntent(ExamsIntent.UpdateTitle(it)) },
                placeholder = "Nombre del solucionario",
            )
            GlassTextField(
                value = state.editorDescription,
                onValueChange = { onIntent(ExamsIntent.UpdateDescription(it)) },
                placeholder = "Nota interna",
                singleLine = false,
            )
            MobileSpecialtyPicker(
                specialtiesState = state.specialties,
                selectedId = state.editorSpecialtyId,
                onSelected = { onIntent(ExamsIntent.UpdateSpecialtyId(it)) },
            )
            GlassTextField(
                value = state.editorStatus,
                onValueChange = { onIntent(ExamsIntent.UpdateExamStatus(it)) },
                placeholder = "draft | published | archived",
            )
            GlassTextField(
                value = state.editorPublishFrom,
                onValueChange = { onIntent(ExamsIntent.UpdatePublishFrom(it)) },
                placeholder = "Publicar desde",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(UiSpacing.compact)) {
                StatusChip(status = state.editorStatus.ifBlank { "draft" })
                StatusChip(status = "sin IDs")
            }
            GlassActionButton(
                text = "Guardar",
                modifier = Modifier.fillMaxWidth(),
                onClick = { onIntent(ExamsIntent.SaveExam) },
            )
        }
    }
}

@Composable
private fun MobileSpecialtyPicker(
    specialtiesState: UiAsync<List<Specialty>>,
    selectedId: String,
    onSelected: (String) -> Unit,
) {
    when (specialtiesState) {
        is UiAsync.Success -> {
            val selected = specialtiesState.value.firstOrNull { it.id == selectedId }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Especialidad",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                GlassTextField(
                    value = selected?.name.orEmpty(),
                    onValueChange = { typedValue ->
                        specialtiesState.value.firstOrNull { it.name.equals(typedValue, ignoreCase = true) }?.let {
                            onSelected(it.id)
                        }
                    },
                    placeholder = "Escribe el nombre exacto",
                )
                Text(
                    text = specialtiesState.value.joinToString(prefix = "Disponibles: ") { it.name },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        is UiAsync.Error -> Text(specialtiesState.error.message, color = MaterialTheme.colorScheme.error)
        UiAsync.Loading -> Text("Cargando especialidades...")
        else -> Text("Sin especialidades")
    }
}

@Composable
private fun SolucionarioList(
    state: ExamsState,
    onIntent: (ExamsIntent) -> Unit,
    onCreate: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(UiSpacing.compact)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Solucionarios",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            GlassActionButton(
                text = "Nuevo",
                modifier = Modifier
                    .width(112.dp)
                    .height(48.dp),
                onClick = onCreate,
            )
        }
        when (val exams = state.exams) {
            is UiAsync.Success -> exams.value.forEach { exam ->
                SolucionarioCard(
                    solucionario = exam.toSolucionarioUi(selected = exam.id == state.selectedExamId),
                    onClick = { onIntent(ExamsIntent.SelectExam(exam.id)) },
                )
            }

            UiAsync.Empty -> EmptyStateCard("Sin solucionarios", "Crea el primer solucionario para empezar.")
            is UiAsync.Error -> EmptyStateCard("No se pudo cargar", exams.error.message)
            UiAsync.Loading -> EmptyStateCard("Cargando", "Preparando solucionarios.")
            UiAsync.Idle -> EmptyStateCard("Listo", "Sin carga activa.")
        }
    }
}

@Composable
private fun SolucionarioCard(
    solucionario: SolucionarioUi,
    onClick: () -> Unit,
) {
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        tint = if (solucionario.hasSelection) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        } else {
            GlassTheme.tokens.glassFill
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(UiSpacing.panel),
            verticalArrangement = Arrangement.spacedBy(UiSpacing.compact),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.width(214.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = solucionario.title.ifBlank { "Solucionario sin nombre" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = solucionario.specialty.ifBlank { "Sin especialidad asignada" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusChip(solucionario.status)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(UiSpacing.compact)) {
                StatusChip("${solucionario.questionCount} preguntas")
                StatusChip(if (solucionario.hasSelection) "seleccionado" else "editable")
            }
        }
    }
}

@Composable
private fun QuestionsPanel(
    state: ExamsState,
    onIntent: (ExamsIntent) -> Unit,
) {
    val importPicker = rememberTextFilePickerLauncher { file ->
        onIntent(ExamsIntent.ImportQuestionsFromText(file.name, file.content))
    }
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(UiSpacing.panel),
            verticalArrangement = Arrangement.spacedBy(UiSpacing.normal),
        ) {
            Text(
                text = "Preguntas",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(UiSpacing.compact)) {
                MobileActionPill(
                    icon = MobileIcon.Import,
                    label = "Importar",
                    modifier = Modifier
                        .width(140.dp)
                        .height(48.dp),
                    enabled = state.selectedExamId != null,
                    onClick = { importPicker.open() },
                )
                MobileActionPill(
                    icon = MobileIcon.Plus,
                    label = "Nueva",
                    modifier = Modifier
                        .width(140.dp)
                        .height(48.dp),
                    enabled = state.selectedExamId != null,
                    onClick = { onIntent(ExamsIntent.CreateQuestion) },
                )
            }
            when (val importState = state.importState) {
                is UiAsync.Error -> Text(importState.error.message, color = MaterialTheme.colorScheme.error)
                UiAsync.Loading -> Text("Importando preguntas...")
                is UiAsync.Success -> Text("${importState.value} preguntas importadas.")
                else -> Unit
            }
            when (val questions = state.questions) {
                is UiAsync.Success -> questions.value.forEach { question ->
                    QuestionCard(
                        question = question.toQuestionUi(selected = question.id == state.selectedQuestionId),
                        onSelect = { onIntent(ExamsIntent.SelectQuestion(question.id)) },
                        onMoveUp = { onIntent(ExamsIntent.MoveQuestionUp(question.id)) },
                        onMoveDown = { onIntent(ExamsIntent.MoveQuestionDown(question.id)) },
                    )
                }

                UiAsync.Empty -> DropZone(
                    text = "Sin preguntas. Crea o importa la primera.",
                    state = DropTargetState.Idle,
                )

                is UiAsync.Error -> EmptyStateCard("No se pudieron cargar preguntas", questions.error.message)
                UiAsync.Loading -> EmptyStateCard("Cargando", "Preparando preguntas.")
                UiAsync.Idle -> EmptyStateCard("Selecciona un solucionario", "Las preguntas apareceran aqui.")
            }
            QuestionEditor(state = state, onIntent = onIntent)
        }
    }
}

@Composable
private fun QuestionCard(
    question: QuestionUi,
    onSelect: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = UiSpacing.questionMinHeight)
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(18.dp),
        tint = if (question.selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else GlassTheme.tokens.glassFill,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(UiSpacing.normal),
            verticalArrangement = Arrangement.spacedBy(UiSpacing.compact),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(UiSpacing.compact)) {
                StatusChip(if (question.hasResources) "${question.resourceCount} recursos" else "sin recursos")
                StatusChip("ok ${question.correctOptionLabel}")
            }
            Text(
                text = question.title.ifBlank { "Pregunta sin titulo" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = question.statement.ifBlank { "Sin enunciado" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            DropZone(
                text = if (question.hasResources) {
                    "Recursos enlazados"
                } else {
                    "Enlaza recursos"
                },
                state = if (question.hasResources) DropTargetState.Linked else DropTargetState.Ready,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(UiSpacing.compact)) {
                MobileActionPill(icon = MobileIcon.Up, label = "Subir", modifier = Modifier.width(132.dp), onClick = onMoveUp)
                MobileActionPill(icon = MobileIcon.Down, label = "Bajar", modifier = Modifier.width(132.dp), onClick = onMoveDown)
            }
        }
    }
}

@Composable
private fun DropZone(
    text: String,
    state: DropTargetState,
) {
    val color = when (state) {
        DropTargetState.Idle -> MaterialTheme.colorScheme.surfaceVariant
        DropTargetState.Ready -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        DropTargetState.Linked -> GlassTheme.tokens.accent.copy(alpha = 0.14f)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 54.dp)
            .background(color = color, shape = RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun QuestionEditor(
    state: ExamsState,
    onIntent: (ExamsIntent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(UiSpacing.compact)) {
        Text(
            text = "Editor de pregunta",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        GlassTextField(
            value = state.editorQuestionTitle,
            onValueChange = { onIntent(ExamsIntent.UpdateQuestionTitle(it)) },
            placeholder = "Titulo",
        )
        GlassTextField(
            value = state.editorQuestionStatement,
            onValueChange = { onIntent(ExamsIntent.UpdateQuestionStatement(it)) },
            placeholder = "Enunciado de la pregunta",
            singleLine = false,
        )
        state.editorOptions.sortedBy { it.sortOrder }.forEach { option ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(UiSpacing.compact)) {
                    StatusChip(if (option.isCorrect) "correcta" else "opcion ${option.label}")
                    MobileActionPill(
                        icon = MobileIcon.Save,
                        label = option.label,
                        modifier = Modifier.width(142.dp),
                        onClick = { onIntent(ExamsIntent.SelectCorrectOption(option.id)) },
                    )
                }
                GlassTextField(
                    value = option.content,
                    onValueChange = { onIntent(ExamsIntent.UpdateOptionContent(option.id, it)) },
                    placeholder = "Respuesta ${option.label}",
                )
            }
        }
        GlassActionButton(
            text = "Guardar pregunta",
            modifier = Modifier.fillMaxWidth(),
            onClick = { onIntent(ExamsIntent.SaveQuestion) },
        )
    }
}

@Composable
private fun ResourceTray(
    state: ExamsState,
    onIntent: (ExamsIntent) -> Unit,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(UiSpacing.panel),
            verticalArrangement = Arrangement.spacedBy(UiSpacing.normal),
        ) {
            Text(
                text = "Recursos",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            MobileActionPill(
                icon = MobileIcon.Plus,
                label = "Anadir recurso",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                onClick = { onIntent(ExamsIntent.AddResource) },
            )
            state.editorResources.sortedBy { it.sortOrder }.forEach { resource ->
                ResourceTile(
                    resource = resource.toResourceTileUi(),
                    onFilePicked = { picked ->
                        onIntent(
                            ExamsIntent.SelectResourceFile(
                                resourceId = resource.id,
                                uri = picked.uri,
                                suggestedName = picked.name,
                                mimeType = picked.mimeType,
                            ),
                        )
                    },
                    onUpload = { onIntent(ExamsIntent.UploadResource(resource.id)) },
                    onRemove = { onIntent(ExamsIntent.RemoveResource(resource.id)) },
                    onTypeChange = { onIntent(ExamsIntent.UpdateResourceType(resource.id, it)) },
                    onTitleChange = { onIntent(ExamsIntent.UpdateResourceTitle(resource.id, it)) },
                    onValueChange = { onIntent(ExamsIntent.UpdateResourceValue(resource.id, it)) },
                    type = resource.type,
                    title = resource.title,
                    value = resource.inputValue,
                )
            }
            if (state.editorResources.isEmpty()) {
                DropZone("No hay recursos en la bandeja.", DropTargetState.Idle)
            }
        }
    }
}

@Composable
private fun ResourceTile(
    resource: ResourceTileUi,
    type: String,
    title: String,
    value: String,
    onTypeChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onFilePicked: (PickedFileReference) -> Unit,
    onUpload: () -> Unit,
    onRemove: () -> Unit,
) {
    val filePicker = rememberContentFilePickerLauncher(onPicked = onFilePicked)
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = UiSpacing.resourceMinHeight),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(UiSpacing.normal),
            verticalArrangement = Arrangement.spacedBy(UiSpacing.compact),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(UiSpacing.compact)) {
                StatusChip(resource.type)
                StatusChip(if (resource.linked) "enlazado" else "pendiente")
            }
            GlassTextField(
                value = type,
                onValueChange = onTypeChange,
                placeholder = "imagen | audio | video | documento",
            )
            GlassTextField(
                value = title,
                onValueChange = onTitleChange,
                placeholder = "Nombre del recurso",
            )
            GlassTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = "URL o ruta local del recurso",
                singleLine = false,
            )
            // SOLO MOSTRAR PREVIEW SI NO ES IMAGEN O SI ES NECESARIO, PERO OCULTAR DIRECCIONES LARGAS EN CAPAS
            Text(
                text = resource.valuePreview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(UiSpacing.compact)) {
                MobileActionPill(icon = MobileIcon.Import, label = "Archivo", modifier = Modifier.width(96.dp), onClick = { filePicker.open() })
                MobileActionPill(icon = MobileIcon.Up, label = "Subir", modifier = Modifier.width(96.dp), onClick = onUpload)
                MobileActionPill(icon = MobileIcon.Remove, label = "Quitar", modifier = Modifier.width(96.dp), onClick = onRemove)
            }
        }
    }
}

@Composable
private fun MobileNewsPanel(
    state: NewsState,
    onIntent: (NewsIntent) -> Unit,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(UiSpacing.normal)) {
        MobileLayeredNewsEditor(
            state = state,
            onIntent = onIntent,
            isFullscreen = isFullscreen,
            onToggleFullscreen = onToggleFullscreen
        )

        if (!isFullscreen) {
            GlassSurface(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(UiSpacing.panel),
                    verticalArrangement = Arrangement.spacedBy(UiSpacing.normal),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Noticias",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        MobileActionPill(
                            icon = MobileIcon.Plus,
                            label = "Nueva",
                            modifier = Modifier.width(112.dp),
                            onClick = { onIntent(NewsIntent.CreateNew) },
                        )
                    }
                    GlassTextField(
                        value = state.searchQuery,
                        onValueChange = { onIntent(NewsIntent.UpdateSearch(it)) },
                        placeholder = "Buscar noticia",
                    )
                    when (val news = state.news) {
                        is UiAsync.Success -> news.value.forEach { item ->
                            MobileNewsCard(
                                item = item,
                                selected = item.id == state.selectedId,
                                onClick = { onIntent(NewsIntent.Select(item.id)) },
                                onToggleActive = {
                                    onIntent(NewsIntent.Select(item.id))
                                    onIntent(NewsIntent.UpdateStatus(if (item.status == "published") "draft" else "published"))
                                    onIntent(NewsIntent.Save)
                                }
                            )
                        }

                        UiAsync.Empty -> DropZone("Sin noticias para este filtro.", DropTargetState.Idle)
                        is UiAsync.Error -> EmptyStateCard("No se pudieron cargar noticias", news.error.message)
                        UiAsync.Loading -> EmptyStateCard("Cargando", "Preparando noticias.")
                        UiAsync.Idle -> EmptyStateCard("Noticias", "Sin carga activa.")
                    }
                }
            }
        }
    }
}

@Composable
private fun MobileNewsCard(
    item: NewsItem,
    selected: Boolean,
    onClick: () -> Unit,
    onToggleActive: () -> Unit,
) {
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        tint = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else GlassTheme.tokens.glassFill,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NewsActiveToggle(
                    isActive = item.status == "published",
                    onToggle = onToggleActive
                )
                Column(
                    modifier = Modifier.width(218.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = item.title.ifBlank { "Noticia sin titulo" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${item.specialty} - ${item.type}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(UiSpacing.compact)) {
                StatusChip(item.status)
            }
        }
    }
}

@Composable
private fun NewsActiveToggle(
    isActive: Boolean,
    onToggle: () -> Unit,
) {
    val background = if (isActive) GlassTheme.tokens.accent else GlassTheme.tokens.glassFillStrong
    val color = if (isActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(42.dp)
            .background(background, RoundedCornerShape(14.dp))
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (isActive) {
            Canvas(modifier = Modifier.size(18.dp)) {
                val stroke = 3.dp.toPx()
                drawLine(color, Offset(size.width * 0.2f, size.height * 0.5f), Offset(size.width * 0.45f, size.height * 0.75f), stroke, StrokeCap.Round)
                drawLine(color, Offset(size.width * 0.45f, size.height * 0.75f), Offset(size.width * 0.85f, size.height * 0.25f), stroke, StrokeCap.Round)
            }
        } else {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(color.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            )
        }
    }
}

private fun Exam.toSolucionarioUi(selected: Boolean): SolucionarioUi {
    return SolucionarioUi(
        title = title,
        specialty = specialtyName,
        status = status.name.lowercase(),
        questionCount = questionCount,
        hasSelection = selected,
    )
}


private fun ExamQuestion.toQuestionUi(selected: Boolean): QuestionUi {
    return QuestionUi(
        title = title,
        statement = statement,
        correctOptionLabel = options.firstOrNull { it.isCorrect }?.label ?: "-",
        resourceCount = resources.size,
        hasResources = resources.isNotEmpty(),
        selected = selected,
    )
}


private fun QuestionResourceDraft.toResourceTileUi(): ResourceTileUi {
    val linkedValue = publicUrl ?: storagePath
    return ResourceTileUi(
        type = type.ifBlank { "documento" },
        title = title.ifBlank { "Recurso sin titulo" },
        valuePreview = linkedValue ?: inputValue.ifBlank { "Pendiente de contenido o archivo" },
        linked = linkedValue != null || inputValue.isNotBlank(),
    )
}
