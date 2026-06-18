package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.EmptyStateCard
import com.oposicontrol.admin.core.ui.components.GlassSurface
import com.oposicontrol.admin.core.ui.components.GlassTextField
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.layout.UiSpacing
import com.oposicontrol.admin.core.ui.theme.AdminTheme
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamQuestion
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.isInmersivo
import com.oposicontrol.admin.domain.model.isRafaga
import com.oposicontrol.admin.domain.model.QuestionResourceType
import com.oposicontrol.admin.domain.model.Rafaga
import com.oposicontrol.admin.domain.model.RafagaQuestion
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.model.Tag
import com.oposicontrol.admin.domain.model.TagSearchQuery
import com.oposicontrol.admin.domain.model.TaggedSearchResult
import com.oposicontrol.admin.domain.repository.TagSearchRepository
import com.oposicontrol.admin.features.news.presentation.NewsIntent
import com.oposicontrol.admin.features.news.presentation.NewsItem
import com.oposicontrol.admin.features.news.presentation.NewsState
import com.oposicontrol.admin.features.news.presentation.NewsViewModel
import kotlinx.coroutines.delay
import kotlinx.datetime.Instant

@Composable
fun MobileContentScreen(
    examsViewModel: ExamsViewModel,
    newsViewModel: NewsViewModel,
    tagSearchRepository: TagSearchRepository? = null,
    initialView: MobileContentView = MobileContentView.Solucionarios,
    backendActionLabel: String = "Reinicio",
    backendActionInProgress: Boolean = false,
    onRestartBackend: () -> Unit = {},
    connectionDiagnostics: @Composable () -> Unit = {},
    adminContent: @Composable () -> Unit = {},
) {
    val examsState by examsViewModel.state.collectAsState()
    val newsState by newsViewModel.state.collectAsState()
    MobileContentContent(
        state = examsState,
        newsState = newsState,
        onIntent = examsViewModel::dispatch,
        onNewsIntent = newsViewModel::dispatch,
        tagSearchRepository = tagSearchRepository,
        initialView = initialView,
        backendActionLabel = backendActionLabel,
        backendActionInProgress = backendActionInProgress,
        onRestartBackend = onRestartBackend,
        connectionDiagnostics = connectionDiagnostics,
        adminContent = adminContent,
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
                    priceTickets = 0,
                    status = ExamStatus.PUBLISHED,
                    publishFrom = null,
                    publishedAt = Instant.parse("2024-01-01T00:00:00Z"),
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
            tagSearchRepository = null,
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
    tagSearchRepository: TagSearchRepository? = null,
    initialView: MobileContentView = MobileContentView.Noticias,
    backendActionLabel: String = "Reinicio",
    backendActionInProgress: Boolean = false,
    onRestartBackend: () -> Unit = {},
    connectionDiagnostics: @Composable () -> Unit = {},
    adminContent: @Composable () -> Unit = {},
) {
    var activeView by rememberSaveable { mutableStateOf(initialView) }
    var isNewsFullscreen by rememberSaveable { mutableStateOf(false) }
    LockScreenOrientation(
        if (isNewsFullscreen) {
            ScreenOrientation.UNSET
        } else {
            ScreenOrientation.PORTRAIT
        },
    )

    if (isNewsFullscreen) {
        MobileLayeredNewsEditor(
            state = newsState,
            onIntent = onNewsIntent,
            isFullscreen = true,
            onToggleFullscreen = { isNewsFullscreen = false }
        )
    } else {
        if (state.pendingDeleteTarget != null) {
            AlertDialog(
                onDismissRequest = { onIntent(ExamsIntent.DismissPendingDelete) },
                title = { Text("Confirmar eliminacion") },
                text = { Text(state.pendingDeleteMessage.ifBlank { "Esta accion no se puede deshacer." }) },
                confirmButton = {
                    TextButton(onClick = { onIntent(ExamsIntent.ConfirmPendingDelete) }) {
                        Text("Eliminar")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onIntent(ExamsIntent.DismissPendingDelete) }) {
                        Text("Cancelar")
                    }
                },
            )
        }
        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val swipeThresholdPx = with(LocalDensity.current) { 96.dp.toPx() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(
                        start = UiSpacing.screenMobile,
                        top = 12.dp,
                        end = UiSpacing.screenMobile,
                        bottom = UiSpacing.mobileBottomBarClearance + navBarBottom,
                    )
                    .mobileContentSwipe(
                        activeView = activeView,
                        thresholdPx = swipeThresholdPx,
                        onViewChange = { activeView = it },
                    ),
                verticalArrangement = Arrangement.spacedBy(UiSpacing.normal),
            ) {
                MobileTopBar(
                    activeView = activeView,
                    subtitle = when (activeView) {
                        MobileContentView.Noticias -> newsState.editorTitle.ifBlank { "Sin seleccion" }
                        MobileContentView.Admin -> "Panel de administracion"
                        else -> state.editorTitle.ifBlank { "Sin seleccion" }
                    },
                    status = when (activeView) {
                        MobileContentView.Noticias -> newsState.editorStatus.ifBlank { "draft" }
                        MobileContentView.Admin -> "active"
                        else -> state.editorStatus.ifBlank { "draft" }
                    },
                    backendActionLabel = backendActionLabel,
                    backendActionInProgress = backendActionInProgress,
                    showBackendAction = activeView == MobileContentView.Solucionarios,
                    onRestartBackend = onRestartBackend,
                )
                connectionDiagnostics()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(UiSpacing.block),
                ) {
                    Crossfade(
                        targetState = activeView,
                        animationSpec = tween(durationMillis = 220),
                    ) { view ->
                        when (view) {
                            MobileContentView.Solucionarios -> SolucionarioList(
                                state = state,
                                onIntent = onIntent,
                                onCreate = {
                                    onIntent(ExamsIntent.CreateNew)
                                    activeView = MobileContentView.Editor
                                },
                                onCreateBurst = {
                                    onIntent(ExamsIntent.CreateRafaga)
                                    activeView = MobileContentView.Editor
                                },
                            )

                            MobileContentView.Editor -> {
                                if (state.editorMode == ExamEditorMode.Rafaga) {
                                    RafagaEditor(state = state, onIntent = onIntent)
                                } else {
                                    SolucionarioEditor(state = state, onIntent = onIntent)
                                }
                            }
                            MobileContentView.Preguntas -> QuestionsPanel(state = state, onIntent = onIntent)
                            MobileContentView.Recursos -> ResourceTray(state = state, onIntent = onIntent)
                            MobileContentView.Especialidades -> MobileSpecialtiesPanel(state = state, onIntent = onIntent)
                            MobileContentView.Etiquetas -> MobileTagSearchPanel(repository = tagSearchRepository)
                            MobileContentView.Noticias -> MobileNewsPanel(
                                state = newsState,
                                onIntent = onNewsIntent,
                                isFullscreen = false,
                                onToggleFullscreen = { isNewsFullscreen = true }
                            )
                            MobileContentView.Admin -> adminContent()
                        }
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

enum class MobileContentView(val label: String, val title: String, val icon: MobileIcon, val mobileVisible: Boolean = true) {
    Solucionarios("Contenido", "Contenido", MobileIcon.Stack),
    Editor("Editor", "Solucionario", MobileIcon.Edit),
    Preguntas("Preguntas", "Preguntas", MobileIcon.Question),
    Recursos("Recursos", "Recursos", MobileIcon.Media),
    Especialidades("Especialidades", "Especialidades", MobileIcon.Specialty),
    Etiquetas("Etiquetas", "Etiquetas", MobileIcon.Search),
    Noticias("Noticias", "Noticias", MobileIcon.News, mobileVisible = false), // ponytail: desktop-only
    Admin("Admin", "Admin", MobileIcon.Admin),
    ;
    companion object { val mobileTabs = entries.filter { it.mobileVisible } }
}

private fun Modifier.mobileContentSwipe(
    activeView: MobileContentView,
    thresholdPx: Float,
    onViewChange: (MobileContentView) -> Unit,
): Modifier = pointerInput(activeView, thresholdPx) {
    var dragTotal = 0f
    detectHorizontalDragGestures(
        onDragStart = { dragTotal = 0f },
        onHorizontalDrag = { _, dragAmount -> dragTotal += dragAmount },
        onDragEnd = {
            if (dragTotal > thresholdPx) {
                activeView.previousView()?.let(onViewChange)
            } else if (dragTotal < -thresholdPx) {
                activeView.nextView()?.let(onViewChange)
            }
        },
        onDragCancel = { dragTotal = 0f },
    )
}

private fun MobileContentView.previousView(): MobileContentView? {
    val index = MobileContentView.mobileTabs.indexOf(this)
    return MobileContentView.mobileTabs.getOrNull(index - 1)
}

private fun MobileContentView.nextView(): MobileContentView? {
    val index = MobileContentView.mobileTabs.indexOf(this)
    return MobileContentView.mobileTabs.getOrNull(index + 1)
}

enum class MobileIcon {
    Stack,
    Edit,
    Question,
    Media,
    News,
    Specialty,
    Search,
    Plus,
    Import,
    Up,
    Down,
    Save,
    Remove,
    Refresh,
    Admin,
    Users,
    Key,
    Wallet,
    Chat,
    Gear,
    Back,
    Check,
    Upload,
    Clock,
    Photo,
    Trash,
    Eye,
    Lock,
    Unlock,
    Copy,
}

@Composable
private fun MobileTopBar(
    activeView: MobileContentView,
    subtitle: String,
    status: String,
    backendActionLabel: String,
    backendActionInProgress: Boolean,
    showBackendAction: Boolean,
    onRestartBackend: () -> Unit,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (showBackendAction) 112.dp else 64.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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
            if (showBackendAction) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Backend Ktor",
                        modifier = Modifier.width(132.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MobileActionPill(
                        icon = MobileIcon.Refresh,
                        label = backendActionLabel,
                        modifier = Modifier.width(184.dp),
                        enabled = !backendActionInProgress,
                        onClick = onRestartBackend,
                    )
                }
            }
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
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        tint = GlassTheme.tokens.glassFillStrong,
    ) {
        // Column inside BoxScope: tabs row + nav bar spacer (glass extends under system nav)
        Column(modifier = Modifier.fillMaxWidth()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(UiSpacing.mobileBottomBar)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(MobileContentView.mobileTabs, key = { it.name }) { view ->
                    MobileTabItem(
                        view = view,
                        selected = view == activeView,
                        onSelected = onSelected,
                        modifier = Modifier.width(78.dp),
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
    val indicatorWidth by animateDpAsState(
        targetValue = if (selected) 24.dp else 0.dp,
        animationSpec = tween(durationMillis = 160),
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) GlassTheme.tokens.accent else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 160),
    )
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
                .width(indicatorWidth)
                .height(3.dp)
                .background(
                    color = contentColor.copy(alpha = if (selected) 1f else 0f),
                    shape = RoundedCornerShape(2.dp),
                )
        )
        LineIcon(
            icon = view.icon,
            modifier = Modifier.size(22.dp),
            color = contentColor,
        )
        Text(
            text = view.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun MobileActionPill(
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
            .widthIn(min = 96.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        tint = tint,
        strokeColor = tint.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LineIcon(icon = icon, modifier = Modifier.size(18.dp), color = foreground)
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

data class MobileActionSpec(
    val icon: MobileIcon,
    val label: String,
    val minWidth: Int = 118,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResponsiveActionRow(
    actions: List<MobileActionSpec>,
    modifier: Modifier = Modifier,
    maxItemsInEachRow: Int = 2,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = maxItemsInEachRow,
    ) {
        actions.forEach { action ->
            MobileActionPill(
                icon = action.icon,
                label = action.label,
                modifier = Modifier.width(action.minWidth.dp),
                enabled = action.enabled,
                onClick = action.onClick,
            )
        }
    }
}

@Composable
fun IconBadge(
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
fun LineIcon(
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
            MobileIcon.Specialty -> {
                drawRoundRect(color, Offset(w * 0.18f, h * 0.2f), Size(w * 0.64f, h * 0.6f), CornerRadius(w * 0.1f), style = stroke)
                drawLine(color, Offset(w * 0.32f, h * 0.38f), Offset(w * 0.68f, h * 0.38f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.32f, h * 0.54f), Offset(w * 0.6f, h * 0.54f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.32f, h * 0.7f), Offset(w * 0.52f, h * 0.7f), stroke.width, StrokeCap.Round)
            }
            MobileIcon.Search -> {
                drawCircle(color, radius = w * 0.24f, center = Offset(w * 0.42f, h * 0.42f), style = stroke)
                drawLine(color, Offset(w * 0.6f, h * 0.6f), Offset(w * 0.82f, h * 0.82f), stroke.width, StrokeCap.Round)
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
            MobileIcon.Refresh -> {
                drawArc(color, startAngle = 36f, sweepAngle = 260f, useCenter = false, topLeft = Offset(w * 0.2f, h * 0.2f), size = Size(w * 0.6f, h * 0.6f), style = stroke)
                drawLine(color, Offset(w * 0.7f, h * 0.18f), Offset(w * 0.82f, h * 0.34f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.7f, h * 0.18f), Offset(w * 0.54f, h * 0.22f), stroke.width, StrokeCap.Round)
            }
            MobileIcon.Admin -> {
                drawRoundRect(color, Offset(w * 0.2f, h * 0.18f), Size(w * 0.6f, h * 0.64f), CornerRadius(w * 0.12f), style = stroke)
                drawLine(color, Offset(w * 0.34f, h * 0.38f), Offset(w * 0.66f, h * 0.38f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.34f, h * 0.52f), Offset(w * 0.66f, h * 0.52f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.34f, h * 0.66f), Offset(w * 0.54f, h * 0.66f), stroke.width, StrokeCap.Round)
            }
            MobileIcon.Users -> {
                drawCircle(color, radius = w * 0.16f, center = Offset(w * 0.38f, h * 0.34f), style = stroke)
                drawArc(color, startAngle = 180f, sweepAngle = 180f, useCenter = false, topLeft = Offset(w * 0.1f, h * 0.52f), size = Size(w * 0.56f, h * 0.3f), style = stroke)
                drawCircle(color, radius = w * 0.12f, center = Offset(w * 0.68f, h * 0.32f), style = stroke)
                drawArc(color, startAngle = 180f, sweepAngle = 180f, useCenter = false, topLeft = Offset(w * 0.46f, h * 0.5f), size = Size(w * 0.44f, h * 0.24f), style = stroke)
            }
            MobileIcon.Key -> {
                drawCircle(color, radius = w * 0.22f, center = Offset(w * 0.38f, h * 0.38f), style = stroke)
                drawLine(color, Offset(w * 0.56f, h * 0.56f), Offset(w * 0.82f, h * 0.82f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.72f, h * 0.68f), Offset(w * 0.72f, h * 0.8f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.82f, h * 0.72f), Offset(w * 0.82f, h * 0.84f), stroke.width, StrokeCap.Round)
            }
            MobileIcon.Wallet -> {
                drawRoundRect(color, Offset(w * 0.14f, h * 0.26f), Size(w * 0.72f, h * 0.52f), CornerRadius(w * 0.1f), style = stroke)
                drawRoundRect(color, Offset(w * 0.58f, h * 0.42f), Size(w * 0.28f, h * 0.22f), CornerRadius(w * 0.06f), style = stroke)
                drawCircle(color, radius = w * 0.04f, center = Offset(w * 0.72f, h * 0.53f))
            }
            MobileIcon.Chat -> {
                drawRoundRect(color, Offset(w * 0.14f, h * 0.18f), Size(w * 0.72f, h * 0.52f), CornerRadius(w * 0.1f), style = stroke)
                drawLine(color, Offset(w * 0.28f, h * 0.36f), Offset(w * 0.72f, h * 0.36f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.28f, h * 0.5f), Offset(w * 0.58f, h * 0.5f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.24f, h * 0.7f), Offset(w * 0.4f, h * 0.56f), stroke.width, StrokeCap.Round)
            }
            MobileIcon.Gear -> {
                drawCircle(color, radius = w * 0.2f, center = Offset(w * 0.5f, h * 0.5f), style = stroke)
                val cx = w * 0.5f; val cy = h * 0.5f; val r = w * 0.4f
                for (i in 0..5) {
                    val angle = kotlin.math.PI / 3.0 * i
                    val sx = cx + (r * 0.75f * kotlin.math.cos(angle).toFloat())
                    val sy = cy + (r * 0.75f * kotlin.math.sin(angle).toFloat())
                    val ex = cx + (r * kotlin.math.cos(angle).toFloat())
                    val ey = cy + (r * kotlin.math.sin(angle).toFloat())
                    drawLine(color, Offset(sx, sy), Offset(ex, ey), stroke.width, StrokeCap.Round)
                }
            }
            MobileIcon.Back -> {
                drawLine(color, Offset(w * 0.62f, h * 0.22f), Offset(w * 0.28f, h * 0.5f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.28f, h * 0.5f), Offset(w * 0.62f, h * 0.78f), stroke.width, StrokeCap.Round)
            }
            MobileIcon.Check -> {
                drawLine(color, Offset(w * 0.2f, h * 0.52f), Offset(w * 0.44f, h * 0.74f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.44f, h * 0.74f), Offset(w * 0.8f, h * 0.28f), stroke.width, StrokeCap.Round)
            }
            MobileIcon.Upload -> {
                drawLine(color, Offset(w * 0.5f, h * 0.74f), Offset(w * 0.5f, h * 0.22f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.28f, h * 0.45f), Offset(w * 0.5f, h * 0.22f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.72f, h * 0.45f), Offset(w * 0.5f, h * 0.22f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.18f, h * 0.82f), Offset(w * 0.82f, h * 0.82f), stroke.width, StrokeCap.Round)
            }
            MobileIcon.Clock -> {
                drawCircle(color, radius = w * 0.35f, center = Offset(w * 0.5f, h * 0.5f), style = stroke)
                drawLine(color, Offset(w * 0.5f, h * 0.28f), Offset(w * 0.5f, h * 0.5f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.5f, h * 0.5f), Offset(w * 0.67f, h * 0.62f), stroke.width, StrokeCap.Round)
            }
            MobileIcon.Photo -> {
                drawRoundRect(color, Offset(w * 0.12f, h * 0.2f), Size(w * 0.76f, h * 0.58f), CornerRadius(w * 0.08f), style = stroke)
                drawCircle(color, radius = w * 0.09f, center = Offset(w * 0.34f, h * 0.4f), style = stroke)
                drawLine(color, Offset(w * 0.18f, h * 0.68f), Offset(w * 0.42f, h * 0.52f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.42f, h * 0.52f), Offset(w * 0.62f, h * 0.62f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.62f, h * 0.62f), Offset(w * 0.82f, h * 0.5f), stroke.width, StrokeCap.Round)
            }
            MobileIcon.Trash -> {
                drawLine(color, Offset(w * 0.16f, h * 0.28f), Offset(w * 0.84f, h * 0.28f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.38f, h * 0.18f), Offset(w * 0.62f, h * 0.18f), stroke.width, StrokeCap.Round)
                drawRoundRect(color, Offset(w * 0.22f, h * 0.34f), Size(w * 0.56f, h * 0.48f), CornerRadius(w * 0.06f), style = stroke)
                drawLine(color, Offset(w * 0.38f, h * 0.44f), Offset(w * 0.38f, h * 0.72f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.62f, h * 0.44f), Offset(w * 0.62f, h * 0.72f), stroke.width, StrokeCap.Round)
            }
            MobileIcon.Eye -> {
                drawArc(color, startAngle = 180f, sweepAngle = -180f, useCenter = false, topLeft = Offset(w * 0.1f, h * 0.2f), size = Size(w * 0.8f, h * 0.6f), style = stroke)
                drawArc(color, startAngle = 0f, sweepAngle = 180f, useCenter = false, topLeft = Offset(w * 0.1f, h * 0.2f), size = Size(w * 0.8f, h * 0.6f), style = stroke)
                drawCircle(color, radius = w * 0.13f, center = Offset(w * 0.5f, h * 0.5f), style = stroke)
            }
            MobileIcon.Lock -> {
                drawArc(color, startAngle = 0f, sweepAngle = -180f, useCenter = false, topLeft = Offset(w * 0.3f, h * 0.1f), size = Size(w * 0.4f, h * 0.42f), style = stroke)
                drawLine(color, Offset(w * 0.3f, h * 0.31f), Offset(w * 0.3f, h * 0.46f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.7f, h * 0.31f), Offset(w * 0.7f, h * 0.46f), stroke.width, StrokeCap.Round)
                drawRoundRect(color, Offset(w * 0.18f, h * 0.46f), Size(w * 0.64f, h * 0.4f), CornerRadius(w * 0.08f), style = stroke)
                drawCircle(color, radius = w * 0.06f, center = Offset(w * 0.5f, h * 0.64f))
            }
            MobileIcon.Unlock -> {
                drawArc(color, startAngle = 0f, sweepAngle = -180f, useCenter = false, topLeft = Offset(w * 0.3f, h * 0.1f), size = Size(w * 0.4f, h * 0.42f), style = stroke)
                drawLine(color, Offset(w * 0.3f, h * 0.31f), Offset(w * 0.3f, h * 0.46f), stroke.width, StrokeCap.Round)
                drawRoundRect(color, Offset(w * 0.18f, h * 0.46f), Size(w * 0.64f, h * 0.4f), CornerRadius(w * 0.08f), style = stroke)
                drawCircle(color, radius = w * 0.06f, center = Offset(w * 0.5f, h * 0.64f))
            }
            MobileIcon.Copy -> {
                drawRoundRect(color, Offset(w * 0.3f, h * 0.12f), Size(w * 0.52f, h * 0.52f), CornerRadius(w * 0.07f), style = stroke)
                drawRoundRect(color, Offset(w * 0.18f, h * 0.32f), Size(w * 0.52f, h * 0.52f), CornerRadius(w * 0.07f), style = stroke)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExamStatusSelector(
    selected: String,
    onSelected: (String) -> Unit,
) {
    val options = listOf(
        "draft" to "Borrador",
        "published" to "Publicado",
        "archived" to "Archivado",
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Estado",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            maxItemsInEachRow = 3,
        ) {
            options.forEach { (value, label) ->
                TypePill(
                    label = label,
                    selected = selected == value,
                    modifier = Modifier.width(104.dp),
                ) {
                    onSelected(value)
                }
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
            MobileSectionLabel("Datos principales")
            GlassTextField(
                value = state.editorTitle,
                onValueChange = { onIntent(ExamsIntent.UpdateTitle(it)) },
                placeholder = "Nombre del solucionario",
            )
            GlassTextField(
                value = state.editorPriceTickets,
                onValueChange = { value -> onIntent(ExamsIntent.UpdatePriceTickets(value.filter { it.isDigit() }.take(9))) },
                placeholder = "Tickets",
            )
            MobileSpecialtyPicker(
                specialtiesState = state.specialties,
                selectedId = state.editorSpecialtyId,
                onSelected = { onIntent(ExamsIntent.UpdateSpecialtyId(it)) },
            )
            MobileSectionLabel("Estado")
            ExamStatusSelector(
                selected = state.editorStatus,
                onSelected = { onIntent(ExamsIntent.UpdateExamStatus(it)) },
            )
            MobileSectionLabel("Portada")
            ExamCoverEditor(state = state, onIntent = onIntent)
            Row(horizontalArrangement = Arrangement.spacedBy(UiSpacing.compact)) {
                StatusChip(status = state.editorStatus.ifBlank { "draft" }.toReadableStatus())
                StatusChip(status = if (state.editorCoverUrl.isNotBlank()) "con portada" else "sin portada")
            }
            MobileSectionLabel("Acciones")
            ResponsiveActionRow(
                actions = listOf(
                    MobileActionSpec(
                        icon = MobileIcon.Save,
                        label = "Guardar",
                        minWidth = 132,
                        onClick = { onIntent(ExamsIntent.SaveExam) },
                    ),
                ),
            )
            MobileSectionLabel("Eliminar contenido")
            ResponsiveActionRow(
                actions = listOf(
                    MobileActionSpec(
                        icon = MobileIcon.Remove,
                        label = "Eliminar",
                        minWidth = 132,
                        enabled = state.selectedExamId != null,
                        onClick = { onIntent(ExamsIntent.RequestDeleteSelectedExam) },
                    ),
                ),
            )
        }
    }
}

@Composable
private fun RafagaEditor(
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
                text = if (state.selectedRafagaId == null) "Crear rafaga" else "Editar rafaga",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            MobileSectionLabel("Datos principales")
            GlassTextField(
                value = state.editorTitle,
                onValueChange = { onIntent(ExamsIntent.UpdateTitle(it)) },
                placeholder = "titulo",
            )
            GlassTextField(
                value = state.editorPriceTickets,
                onValueChange = { value -> onIntent(ExamsIntent.UpdatePriceTickets(value.filter { it.isDigit() }.take(9))) },
                placeholder = "Tickets",
            )
            MobileSpecialtyPicker(
                specialtiesState = state.specialties,
                selectedId = state.editorSpecialtyId,
                onSelected = { onIntent(ExamsIntent.UpdateSpecialtyId(it)) },
            )
            MobileSectionLabel("Estado")
            ExamStatusSelector(
                selected = state.editorStatus,
                onSelected = { onIntent(ExamsIntent.UpdateExamStatus(it)) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(UiSpacing.compact)) {
                StatusChip(status = state.editorStatus.ifBlank { "draft" }.toReadableStatus())
                StatusChip(status = "${state.editorPriceTickets.ifBlank { "0" }} tickets")
            }
            MobileSectionLabel("Acciones")
            ResponsiveActionRow(
                actions = listOf(
                    MobileActionSpec(
                        icon = MobileIcon.Save,
                        label = "Guardar",
                        minWidth = 132,
                        onClick = { onIntent(ExamsIntent.SaveRafaga) },
                    ),
                ),
            )
            MobileSectionLabel("Eliminar contenido")
            ResponsiveActionRow(
                actions = listOf(
                    MobileActionSpec(
                        icon = MobileIcon.Remove,
                        label = "Eliminar",
                        minWidth = 132,
                        enabled = state.selectedRafagaId != null,
                        onClick = { onIntent(ExamsIntent.RequestDeleteSelectedRafaga) },
                    ),
                ),
            )
        }
    }
}

@Composable
private fun ExamCoverEditor(
    state: ExamsState,
    onIntent: (ExamsIntent) -> Unit,
) {
    val coverPicker = rememberContentFilePickerLauncher(mimeType = "image/*") { picked ->
        onIntent(ExamsIntent.SelectExamCoverFile(picked.uri, picked.name, picked.mimeType))
    }
    val hasRemoteCover = state.editorCoverUrl.isNotBlank() || state.editorCoverStoragePath.isNotBlank()
    val hasLocalCover = state.editorCoverLocalUri.isNotBlank()
    val isUploading = state.coverUploadState is UiAsync.Loading
    val coverPreviewPath = when {
        hasLocalCover -> state.editorCoverLocalUri
        state.editorCoverUrl.isNotBlank() -> state.editorCoverUrl
        else -> ""
    }
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 164.dp),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(UiSpacing.normal),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Portada",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                StatusChip(
                    status = when {
                        isUploading -> "subiendo"
                        hasRemoteCover -> "subida"
                        hasLocalCover -> "seleccionada"
                        else -> "pendiente"
                    },
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(118.dp)
                    .background(GlassTheme.tokens.glassFillStrong, RoundedCornerShape(14.dp))
                    .padding(10.dp),
                contentAlignment = Alignment.BottomStart,
            ) {
                if (coverPreviewPath.isNotBlank()) {
                    NewsBackgroundImage(
                        backgroundPath = coverPreviewPath,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                            .background(GlassTheme.tokens.glassFill, RoundedCornerShape(12.dp)),
                    )
                }
                if (isUploading) {
                    Box(modifier = Modifier.align(Alignment.Center)) {
                        StatusChip("subiendo")
                    }
                }
                Text(
                    text = when {
                        hasLocalCover -> state.editorCoverFileName
                        hasRemoteCover -> "Portada subida"
                        else -> "Sin archivo seleccionado"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (coverPreviewPath.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        Color.White
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = if (coverPreviewPath.isBlank()) 0f else 0.42f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            CoverUploadStatus(state.coverUploadState)
            ResponsiveActionRow(
                actions = listOf(
                    MobileActionSpec(
                        icon = MobileIcon.Import,
                        label = "Elegir imagen",
                        minWidth = 150,
                        onClick = { coverPicker.open() },
                    ),
                    MobileActionSpec(
                        icon = MobileIcon.Up,
                        label = "Guardar imagen",
                        minWidth = 162,
                        enabled = state.selectedExamId != null && hasLocalCover && !isUploading,
                        onClick = { onIntent(ExamsIntent.UploadExamCover) },
                    ),
                    MobileActionSpec(
                        icon = MobileIcon.Remove,
                        label = "Quitar",
                        minWidth = 114,
                        enabled = hasRemoteCover || hasLocalCover,
                        onClick = { onIntent(ExamsIntent.RequestClearExamCover) },
                    ),
                ),
            )
        }
    }
}

@Composable
private fun CoverUploadStatus(uploadState: UiAsync<String>) {
    val message = when (uploadState) {
        is UiAsync.Error -> uploadState.error.message
        is UiAsync.Success -> uploadState.value
        UiAsync.Loading -> "Subiendo portada..."
        UiAsync.Empty -> "Sin portada"
        UiAsync.Idle -> "Elige una imagen si quieres mostrar portada"
    }
    val color = if (uploadState is UiAsync.Error) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun String.toReadableStatus(): String {
    return when (trim().lowercase()) {
        "published" -> "Publicado"
        "draft" -> "Borrador"
        "archived" -> "Archivado"
        "scheduled" -> "Programado"
        "pending" -> "Pendiente"
        "active" -> "Activo"
        "inactive" -> "Inactivo"
        else -> ifBlank { "Pendiente" }
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
                    placeholder = "Busca o escribe la especialidad",
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
    onCreateBurst: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(UiSpacing.normal)) {
        ContentSpecialtyFilter(
            state = state,
            onIntent = onIntent,
        )
        when (val exams = state.exams) {
            is UiAsync.Success -> {
                val rafagas = exams.value
                    .filter { it.isRafaga }
                    .map { it.toContentExamUi(selected = it.id == state.selectedExamId) }
                val inmersivos = exams.value
                    .filter { it.isInmersivo }
                    .map { it.toContentExamUi(selected = it.id == state.selectedExamId) }
                ContentExamSection(
                    title = "Rafagas",
                    items = rafagas,
                    emptyText = "No hay examenes rafaga activos para esta especialidad.",
                    onClick = { onIntent(ExamsIntent.SelectExam(it)) },
                )
                ContentExamSection(
                    title = "Solucionarios / Inmersivos",
                    items = inmersivos,
                    emptyText = "No hay examenes inmersivos activos para esta especialidad.",
                    onClick = { onIntent(ExamsIntent.SelectExam(it)) },
                    pendingLabel = "endpoint premium pendiente",
                )
                ContentQuestionsPreview(state = state, onIntent = onIntent)
            }

            UiAsync.Empty -> EmptyStateCard("Sin examenes", "No hay examenes activos para esta especialidad.")
            is UiAsync.Error -> EmptyStateCard("No se pudo cargar contenido", exams.error.message)
            UiAsync.Loading -> EmptyStateCard("Cargando", "Preparando contenido /v1.")
            UiAsync.Idle -> EmptyStateCard("Listo", "Sin carga activa.")
        }
    }
}

@Composable
private fun ContentSpecialtyFilter(
    state: ExamsState,
    onIntent: (ExamsIntent) -> Unit,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(UiSpacing.panel),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Especialidades",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            when (val specialties = state.specialties) {
                is UiAsync.Success -> LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(items = specialties.value, key = { it.id }) { specialty ->
                        TypePill(
                            label = specialty.name.ifBlank { specialty.description.ifBlank { "Especialidad" } },
                            selected = specialty.id == state.selectedContentSpecialtyId,
                            modifier = Modifier.width(148.dp),
                            onClick = { onIntent(ExamsIntent.SelectContentSpecialty(specialty.id)) },
                        )
                    }
                }

                UiAsync.Empty -> DropZone("No hay especialidades activas.", DropTargetState.Idle)
                is UiAsync.Error -> EmptyStateCard("No se pudieron cargar especialidades", specialties.error.message)
                UiAsync.Loading -> Text("Cargando especialidades...")
                UiAsync.Idle -> Text("Especialidades pendientes de cargar.")
            }
        }
    }
}

@Composable
private fun ContentExamSection(
    title: String,
    items: List<ContentExamUi>,
    emptyText: String,
    onClick: (String) -> Unit,
    pendingLabel: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        if (items.isEmpty()) {
            DropZone(text = emptyText, state = DropTargetState.Idle)
            return
        }
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .height(214.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            items(items = items, key = { it.id }) { item ->
                ContentExamCard(
                    exam = item,
                    pendingLabel = pendingLabel,
                    onClick = { onClick(item.id) },
                )
            }
        }
    }
}

@Composable
private fun ContentExamCard(
    exam: ContentExamUi,
    pendingLabel: String?,
    onClick: () -> Unit,
) {
    GlassSurface(
        modifier = Modifier
            .width(276.dp)
            .height(206.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        tint = if (exam.selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else GlassTheme.tokens.glassFill,
        strokeColor = if (exam.selected) GlassTheme.tokens.accent.copy(alpha = 0.62f) else GlassTheme.tokens.glassStroke,
        shadowElevation = if (exam.selected) 14.dp else 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.width(156.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = exam.title.ifBlank { "Examen sin titulo" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = exam.specialty.ifBlank { exam.code.ifBlank { "Sin especialidad" } },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(
                    modifier = Modifier.width(96.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    StatusChip(exam.typeAccess.toAccessLabel(), modifier = Modifier.width(92.dp))
                    StatusChip(exam.typeExam.toExamTypeLabel(), modifier = Modifier.width(92.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ContentInfoPill(
                    label = "Anio",
                    value = exam.year?.toString() ?: "-",
                    modifier = Modifier.width(118.dp),
                )
                ContentInfoPill(
                    label = "Preguntas",
                    value = exam.questionCount.toString(),
                    modifier = Modifier.width(118.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ContentInfoPill(
                    label = "Min",
                    value = exam.durationMinutes?.toString() ?: "-",
                    modifier = Modifier.width(118.dp),
                )
                ContentInfoPill(
                    label = "Oficial",
                    value = if (exam.isOfficial) "Si" else "No",
                    modifier = Modifier.width(118.dp),
                )
            }
            if (pendingLabel != null) {
                Text(
                    text = pendingLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ContentQuestionsPreview(
    state: ExamsState,
    onIntent: (ExamsIntent) -> Unit,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(UiSpacing.panel),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Preguntas del examen seleccionado",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (state.contentPendingMigrationMessage.isNotBlank()) {
                DropZone(state.contentPendingMigrationMessage, DropTargetState.Ready)
                return@Column
            }
            when (val questions = state.questions) {
                is UiAsync.Success -> questions.value
                    .sortedBy { it.sortOrder }
                    .forEach { question ->
                        ContentQuestionCard(
                            question = question,
                            selected = question.id == state.selectedQuestionId,
                            onClick = { onIntent(ExamsIntent.SelectQuestion(question.id)) },
                        )
                    }

                UiAsync.Empty -> DropZone("Sin preguntas para este examen.", DropTargetState.Idle)
                is UiAsync.Error -> EmptyStateCard("No se pudieron cargar preguntas", questions.error.message)
                UiAsync.Loading -> EmptyStateCard("Cargando", "Preparando preguntas.")
                UiAsync.Idle -> DropZone("Selecciona una rafaga para ver sus preguntas.", DropTargetState.Idle)
            }
        }
    }
}

@Composable
private fun ContentQuestionCard(
    question: ExamQuestion,
    selected: Boolean,
    onClick: () -> Unit,
) {
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        tint = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else GlassTheme.tokens.glassFill,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.width(226.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = question.statement.ifBlank { "Pregunta sin titulo" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = question.title.ifBlank { "Pregunta ${question.sortOrder}" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusChip("#${question.sortOrder}", modifier = Modifier.width(72.dp))
            }
            question.options.sortedBy { it.sortOrder }.forEach { option ->
                Text(
                    text = "${option.label.ifBlank { "-" }}. ${option.content}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RafagaCarousel(
    state: ExamsState,
    onIntent: (ExamsIntent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Rafagas",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        when (val rafagas = state.rafagas) {
            is UiAsync.Success -> LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                items(items = rafagas.value.map { it.toRafagaUi(it.id == state.selectedRafagaId) }, key = { it.id }) { item ->
                    RafagaCard(
                        rafaga = item,
                        onClick = { onIntent(ExamsIntent.SelectRafaga(item.id)) },
                    )
                }
            }

            UiAsync.Empty -> DropZone("Sin rafagas. Crea una desde el boton Rafaga.", DropTargetState.Idle)
            is UiAsync.Error -> EmptyStateCard("No se pudieron cargar rafagas", rafagas.error.message)
            UiAsync.Loading -> EmptyStateCard("Cargando", "Preparando rafagas.")
            UiAsync.Idle -> EmptyStateCard("Rafagas", "Sin carga activa.")
        }
    }
}

@Composable
private fun SolucionarioCarousel(
    title: String,
    items: List<SolucionarioUi>,
    emptyText: String,
    onClick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        if (items.isEmpty()) {
            DropZone(text = emptyText, state = DropTargetState.Idle)
            return
        }
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            items(items = items, key = { it.id }) { item ->
                SolucionarioCard(
                    solucionario = item,
                    modifier = Modifier.width(276.dp),
                    onClick = { onClick(item.id) },
                )
            }
        }
    }
}

@Composable
private fun SolucionarioCard(
    solucionario: SolucionarioUi,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val coverPath = solucionario.coverUrl.ifBlank { solucionario.coverStoragePath }
    GlassSurface(
        modifier = modifier
            .height(208.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        tint = if (solucionario.hasSelection) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        } else {
            GlassTheme.tokens.glassFill
        },
        strokeColor = if (solucionario.hasSelection) {
            GlassTheme.tokens.accent.copy(alpha = 0.62f)
        } else {
            GlassTheme.tokens.glassStroke
        },
        shadowElevation = if (solucionario.hasSelection) 14.dp else 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(UiSpacing.compact),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(76.dp)
                    .background(GlassTheme.tokens.glassFillStrong, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.BottomStart,
            ) {
                if (coverPath.isNotBlank()) {
                    NewsBackgroundImage(
                        backgroundPath = coverPath,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Text(
                    text = if (coverPath.isBlank()) "Sin portada" else if (solucionario.isBurst) "Rafaga" else "Portada guardada",
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (coverPath.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.width(154.dp),
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusChip(solucionario.status.toReadableStatus(), modifier = Modifier.width(118.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ContentInfoPill(
                    label = "Precio",
                    value = solucionario.priceTickets.toTicketLabel(),
                    modifier = Modifier.width(118.dp),
                )
                ContentInfoPill(
                    label = "Preguntas",
                    value = solucionario.questionCount.toString(),
                    modifier = Modifier.width(118.dp),
                )
            }
        }
    }
}

@Composable
private fun ContentInfoPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(34.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
                shape = RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = "$label: $value",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun Int.toTicketLabel(): String {
    return if (this <= 0) "Gratis" else "$this tickets"
}

@Composable
private fun RafagaCard(
    rafaga: RafagaUi,
    onClick: () -> Unit,
) {
    GlassSurface(
        modifier = Modifier
            .width(276.dp)
            .height(208.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        tint = if (rafaga.hasSelection) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        } else {
            GlassTheme.tokens.glassFill
        },
        strokeColor = if (rafaga.hasSelection) {
            GlassTheme.tokens.accent.copy(alpha = 0.62f)
        } else {
            GlassTheme.tokens.glassStroke
        },
        shadowElevation = if (rafaga.hasSelection) 14.dp else 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(UiSpacing.compact),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(76.dp)
                    .background(GlassTheme.tokens.glassFillStrong, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = "rafaga",
                    modifier = Modifier.padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.width(156.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = rafaga.title.ifBlank { "Rafaga sin nombre" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = rafaga.specialty.ifBlank { "Sin especialidad" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusChip(rafaga.status.toReadableStatus(), modifier = Modifier.width(118.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ContentInfoPill(
                    label = "Precio",
                    value = rafaga.priceTickets.toTicketLabel(),
                    modifier = Modifier.width(118.dp),
                )
                ContentInfoPill(
                    label = "Preguntas",
                    value = rafaga.questionCount.toString(),
                    modifier = Modifier.width(118.dp),
                )
            }
        }
    }
}

@Composable
private fun QuestionsPanel(
    state: ExamsState,
    onIntent: (ExamsIntent) -> Unit,
) {
    if (state.editorMode == ExamEditorMode.Rafaga) {
        RafagaQuestionsPanel(state = state, onIntent = onIntent)
        return
    }
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
            ResponsiveActionRow(
                actions = listOf(
                    MobileActionSpec(
                        icon = MobileIcon.Import,
                        label = "Importar",
                        minWidth = 138,
                        enabled = state.selectedExamId != null,
                        onClick = { importPicker.open() },
                    ),
                    MobileActionSpec(
                        icon = MobileIcon.Plus,
                        label = "Nueva",
                        minWidth = 124,
                        enabled = state.selectedExamId != null,
                        onClick = { onIntent(ExamsIntent.CreateQuestion) },
                    ),
                ),
            )
            when (val importState = state.importState) {
                is UiAsync.Error -> Text(importState.error.message, color = MaterialTheme.colorScheme.error)
                UiAsync.Loading -> Text("Importando preguntas...")
                is UiAsync.Success -> Text("${importState.value} preguntas importadas.")
                else -> Unit
            }
            when (val questions = state.questions) {
                is UiAsync.Success -> QuestionSelectorRow(
                    questions = questions.value.map { it.toQuestionUi(selected = it.id == state.selectedQuestionId) },
                    onSelect = { onIntent(ExamsIntent.SelectQuestion(it)) },
                )

                UiAsync.Empty -> DropZone(
                    text = "Sin preguntas. Crea o importa la primera.",
                    state = DropTargetState.Idle,
                )

                is UiAsync.Error -> EmptyStateCard("No se pudieron cargar preguntas", questions.error.message)
                UiAsync.Loading -> EmptyStateCard("Cargando", "Preparando preguntas.")
                UiAsync.Idle -> EmptyStateCard("Selecciona un solucionario", "Las preguntas apareceran aqui.")
            }
            if (state.editorQuestionId != null) {
                QuestionEditor(state = state, onIntent = onIntent)
                QuestionResourcesSection(state = state, onIntent = onIntent)
            } else {
                DropZone("Selecciona una pregunta para desplegar su editor y recursos.", DropTargetState.Idle)
            }
        }
    }
}

@Composable
private fun RafagaQuestionsPanel(
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
                text = "Preguntas de rafaga",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            MobileActionPill(
                icon = MobileIcon.Plus,
                label = "Nueva",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                enabled = state.selectedRafagaId != null,
                onClick = { onIntent(ExamsIntent.CreateRafagaQuestion) },
            )
            when (val questions = state.rafagaQuestions) {
                is UiAsync.Success -> RafagaQuestionSelectorRow(
                    questions = questions.value.map { it.toRafagaQuestionUi(selected = it.id == state.selectedRafagaQuestionId) },
                    onSelect = { onIntent(ExamsIntent.SelectRafagaQuestion(it)) },
                )

                UiAsync.Empty -> DropZone("Sin preguntas. Crea la primera rafaga_pregunta.", DropTargetState.Idle)
                is UiAsync.Error -> EmptyStateCard("No se pudieron cargar preguntas", questions.error.message)
                UiAsync.Loading -> EmptyStateCard("Cargando", "Preparando preguntas.")
                UiAsync.Idle -> EmptyStateCard("Selecciona una rafaga", "Las preguntas apareceran aqui.")
            }
            if (state.editorQuestionId != null) {
                RafagaQuestionEditor(state = state, onIntent = onIntent)
            } else {
                DropZone("Selecciona una pregunta para editar su enunciado.", DropTargetState.Idle)
            }
        }
    }
}

@Composable
private fun RafagaQuestionSelectorRow(
    questions: List<RafagaQuestionUi>,
    onSelect: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(106.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        items(items = questions, key = { it.id }) { question ->
            GlassSurface(
                modifier = Modifier
                    .width(188.dp)
                    .height(98.dp)
                    .clickable { onSelect(question.id) },
                shape = RoundedCornerShape(18.dp),
                tint = if (question.selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else GlassTheme.tokens.glassFill,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StatusChip(if (question.selected) "seleccionada" else "rafaga_pregunta")
                    Text(
                        text = question.statement.ifBlank { "Sin enunciado" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun RafagaQuestionEditor(
    state: ExamsState,
    onIntent: (ExamsIntent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(UiSpacing.compact)) {
        Text(
            text = "Editor rafaga_pregunta",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        GlassTextField(
            value = state.editorQuestionStatement,
            onValueChange = { onIntent(ExamsIntent.UpdateQuestionStatement(it)) },
            placeholder = "enunciado",
            singleLine = false,
        )
        ResponsiveActionRow(
            actions = listOf(
                MobileActionSpec(MobileIcon.Save, "Guardar", 118, onClick = { onIntent(ExamsIntent.SaveRafagaQuestion) }),
                MobileActionSpec(
                    icon = MobileIcon.Remove,
                    label = "Eliminar",
                    minWidth = 118,
                    enabled = state.selectedRafagaQuestionId != null,
                    onClick = { onIntent(ExamsIntent.RequestDeleteSelectedRafagaQuestion) },
                ),
                MobileActionSpec(
                    icon = MobileIcon.Up,
                    label = "Subir",
                    minWidth = 106,
                    enabled = state.selectedRafagaQuestionId != null,
                    onClick = { state.selectedRafagaQuestionId?.let { onIntent(ExamsIntent.MoveRafagaQuestionUp(it)) } },
                ),
                MobileActionSpec(
                    icon = MobileIcon.Down,
                    label = "Bajar",
                    minWidth = 106,
                    enabled = state.selectedRafagaQuestionId != null,
                    onClick = { state.selectedRafagaQuestionId?.let { onIntent(ExamsIntent.MoveRafagaQuestionDown(it)) } },
                ),
            ),
        )
    }
}

@Composable
private fun QuestionSelectorRow(
    questions: List<QuestionUi>,
    onSelect: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(126.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        items(items = questions, key = { it.id }) { question ->
            QuestionSelectorCard(
                question = question,
                onSelect = { onSelect(question.id) },
            )
        }
    }
}

@Composable
private fun QuestionSelectorCard(
    question: QuestionUi,
    onSelect: () -> Unit,
) {
    GlassSurface(
        modifier = Modifier
            .width(188.dp)
            .height(118.dp)
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(18.dp),
        tint = if (question.selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else GlassTheme.tokens.glassFill,
        strokeColor = if (question.selected) GlassTheme.tokens.accent.copy(alpha = 0.62f) else GlassTheme.tokens.glassStroke,
        shadowElevation = if (question.selected) 14.dp else 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusChip("ok ${question.correctOptionLabel}")
                StatusChip(if (question.hasResources) "${question.resourceCount}" else "0")
            }
            Text(
                text = question.title.ifBlank { "Pregunta sin titulo" },
                style = MaterialTheme.typography.titleSmall,
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
            ResponsiveActionRow(
                actions = listOf(
                    MobileActionSpec(icon = MobileIcon.Up, label = "Subir", minWidth = 124, onClick = onMoveUp),
                    MobileActionSpec(icon = MobileIcon.Down, label = "Bajar", minWidth = 124, onClick = onMoveDown),
                ),
            )
        }
    }
}

@Composable
private fun DropZone(
    text: String,
    state: DropTargetState,
) {
    val targetColor = when (state) {
        DropTargetState.Idle -> MaterialTheme.colorScheme.surfaceVariant
        DropTargetState.Ready -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        DropTargetState.Linked -> GlassTheme.tokens.accent.copy(alpha = 0.14f)
    }
    val color by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 180),
    )
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
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(UiSpacing.compact)) {
                        StatusChip(if (option.isCorrect) "correcta" else "opcion ${option.label}")
                    }
                    ResponsiveActionRow(
                        actions = listOf(
                            MobileActionSpec(
                                icon = MobileIcon.Save,
                                label = if (option.isCorrect) "Correcta ${option.label}" else "Marcar ${option.label}",
                                minWidth = 142,
                                onClick = { onIntent(ExamsIntent.SelectCorrectOption(option.id)) },
                            ),
                        ),
                    )
                    GlassTextField(
                        value = option.content,
                        onValueChange = { onIntent(ExamsIntent.UpdateOptionContent(option.id, it)) },
                        placeholder = "Respuesta ${option.label}",
                    )
                }
            }
        }
        ResponsiveActionRow(
            actions = listOf(
                MobileActionSpec(
                    icon = MobileIcon.Save,
                    label = "Guardar",
                    minWidth = 118,
                    onClick = { onIntent(ExamsIntent.SaveQuestion) },
                ),
                MobileActionSpec(
                    icon = MobileIcon.Remove,
                    label = "Eliminar",
                    minWidth = 118,
                    enabled = state.selectedQuestionId != null,
                    onClick = { onIntent(ExamsIntent.RequestDeleteSelectedQuestion) },
                ),
                MobileActionSpec(
                    icon = MobileIcon.Up,
                    label = "Subir",
                    minWidth = 106,
                    enabled = state.selectedQuestionId != null,
                    onClick = { state.selectedQuestionId?.let { onIntent(ExamsIntent.MoveQuestionUp(it)) } },
                ),
                MobileActionSpec(
                    icon = MobileIcon.Down,
                    label = "Bajar",
                    minWidth = 106,
                    enabled = state.selectedQuestionId != null,
                    onClick = { state.selectedQuestionId?.let { onIntent(ExamsIntent.MoveQuestionDown(it)) } },
                ),
            ),
        )
    }
}

@Composable
private fun QuestionResourcesSection(
    state: ExamsState,
    onIntent: (ExamsIntent) -> Unit,
) {
    val selectedQuestion = (state.questions as? UiAsync.Success)
        ?.value
        ?.firstOrNull { it.id == state.selectedQuestionId }
    Column(verticalArrangement = Arrangement.spacedBy(UiSpacing.compact)) {
        Text(
            text = "Recursos de la pregunta",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val linkedTypes = selectedQuestion?.resources.orEmpty().map { it.type }.toSet()
            items(items = QuestionResourceType.entries, key = { it.name }) { type ->
                ResourceTypeLegendChip(
                    label = type.toLegendLabel(),
                    linked = type in linkedTypes,
                )
            }
        }
        LinkedResourcesRow(selectedQuestion = selectedQuestion)
        MobileActionPill(
            icon = MobileIcon.Plus,
            label = "Anadir recurso",
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            enabled = state.editorQuestionId != null,
            onClick = { onIntent(ExamsIntent.AddResource) },
        )
        state.editorResources.sortedBy { it.sortOrder }.forEach { resource ->
            ResourceTile(
                resource = resource.toResourceTileUi(),
                uploadState = state.resourceUploadStates[resource.id] ?: UiAsync.Idle,
                hasQuestion = state.editorQuestionId != null,
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
                onRemove = { onIntent(ExamsIntent.RequestRemoveResource(resource.id)) },
                onTypeChange = { onIntent(ExamsIntent.UpdateResourceType(resource.id, it)) },
                onTitleChange = { onIntent(ExamsIntent.UpdateResourceTitle(resource.id, it)) },
                onValueChange = { onIntent(ExamsIntent.UpdateResourceValue(resource.id, it)) },
                type = resource.type,
                title = resource.title,
                value = resource.inputValue,
            )
        }
        if (state.editorResources.isEmpty()) {
            DropZone("No hay recursos editables para esta pregunta.", DropTargetState.Idle)
        }
    }
}

@Composable
private fun ResourceTypeLegendChip(
    label: String,
    linked: Boolean,
) {
    Box(
        modifier = Modifier
            .width(112.dp)
            .height(38.dp)
            .background(
                color = if (linked) GlassTheme.tokens.accent else GlassTheme.tokens.glassFillStrong,
                shape = RoundedCornerShape(14.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (linked) "$label enlazado" else label,
            style = MaterialTheme.typography.labelMedium,
            color = if (linked) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LinkedResourcesRow(
    selectedQuestion: ExamQuestion?,
) {
    val resources = selectedQuestion?.resources.orEmpty().sortedBy { it.sortOrder }
    if (resources.isEmpty()) {
        DropZone("La pregunta no tiene recursos enlazados guardados.", DropTargetState.Ready)
        return
    }
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(92.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        items(items = resources, key = { it.id }) { resource ->
            GlassSurface(
                modifier = Modifier
                    .width(188.dp)
                    .height(84.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    StatusChip(resource.type.toLegendLabel())
                    Text(
                        text = resource.title.ifBlank { "Recurso sin titulo" },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = resource.publicUrl ?: resource.storagePath ?: resource.textBody.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun QuestionResourceType.toLegendLabel(): String {
    return when (this) {
        QuestionResourceType.IMAGE -> "Imagen"
        QuestionResourceType.AUDIO -> "Audio"
        QuestionResourceType.VIDEO -> "Video"
        QuestionResourceType.TEXT -> "Texto"
        QuestionResourceType.DOCUMENT -> "Documento"
    }
}

@Composable
private fun ResourceTray(
    state: ExamsState,
    onIntent: (ExamsIntent) -> Unit,
) {
    if (state.editorMode == ExamEditorMode.Rafaga) {
        EmptyStateCard("Recursos", "Las rafagas usan rafaga_pregunta: solo enunciado y orden.")
        return
    }
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
                enabled = state.editorQuestionId != null,
                onClick = { onIntent(ExamsIntent.AddResource) },
            )
            if (state.editorQuestionId == null) {
                DropZone(
                    "Selecciona una pregunta para gestionar sus recursos.",
                    DropTargetState.Idle,
                )
            }
            state.editorResources.sortedBy { it.sortOrder }.forEach { resource ->
                ResourceTile(
                    resource = resource.toResourceTileUi(),
                    uploadState = state.resourceUploadStates[resource.id] ?: UiAsync.Idle,
                    hasQuestion = state.editorQuestionId != null,
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
                    onRemove = { onIntent(ExamsIntent.RequestRemoveResource(resource.id)) },
                    onTypeChange = { onIntent(ExamsIntent.UpdateResourceType(resource.id, it)) },
                    onTitleChange = { onIntent(ExamsIntent.UpdateResourceTitle(resource.id, it)) },
                    onValueChange = { onIntent(ExamsIntent.UpdateResourceValue(resource.id, it)) },
                    type = resource.type,
                    title = resource.title,
                    value = resource.inputValue,
                )
            }
            if (state.editorResources.isEmpty() && state.editorQuestionId != null) {
                DropZone(
                    "Esta pregunta no tiene recursos. Usa Anadir recurso para crear el primero.",
                    DropTargetState.Ready,
                )
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
    uploadState: UiAsync<String>,
    hasQuestion: Boolean,
    onTypeChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onFilePicked: (PickedFileReference) -> Unit,
    onUpload: () -> Unit,
    onRemove: () -> Unit,
) {
    val filePicker = rememberContentFilePickerLauncher(onPicked = onFilePicked)
    val resourceType = type.toResourceType()
    val isText = resourceType == QuestionResourceType.TEXT
    val isUploading = uploadState is UiAsync.Loading
    val hasManualUrl = value.startsWith("http://") || value.startsWith("https://")
    val hasLocalFile = value.isNotBlank() && !hasManualUrl && !isText
    val canUpload = hasQuestion && !isText && (hasManualUrl || hasLocalFile) && !isUploading
    val displayValue = when {
        value.startsWith("content://") -> ""
        value.startsWith("file://") -> ""
        else -> value
    }
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
                StatusChip(resourceStatusLabel(resource, uploadState))
            }
            ResourceTypeSelector(selected = type, onSelected = onTypeChange)
            GlassTextField(
                value = title,
                onValueChange = onTitleChange,
                placeholder = "Nombre del recurso",
            )
            if (isText) {
                GlassTextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = "Contenido de texto",
                    singleLine = false,
                )
            } else {
                GlassTextField(
                    value = displayValue,
                    onValueChange = onValueChange,
                    placeholder = "URL publica opcional",
                    singleLine = false,
                )
            }
            ResourceFileSummary(resource = resource, uploadState = uploadState)
            Text(
                text = resource.valuePreview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            ResponsiveActionRow(
                actions = listOf(
                    MobileActionSpec(
                        icon = MobileIcon.Import,
                        label = "Archivo",
                        minWidth = 122,
                        enabled = !isText,
                        onClick = { filePicker.open() },
                    ),
                    MobileActionSpec(
                        icon = MobileIcon.Up,
                        label = "Subir",
                        minWidth = 110,
                        enabled = canUpload,
                        onClick = onUpload,
                    ),
                    MobileActionSpec(
                        icon = MobileIcon.Remove,
                        label = "Quitar",
                        minWidth = 116,
                        onClick = onRemove,
                    ),
                ),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResourceTypeSelector(
    selected: String,
    onSelected: (String) -> Unit,
) {
    val options = listOf(
        "image" to "Imagen",
        "audio" to "Audio",
        "video" to "Video",
        "document" to "Documento",
        "text" to "Texto",
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        maxItemsInEachRow = 3,
    ) {
        options.forEach { (value, label) ->
            TypePill(
                label = label,
                selected = selected == value,
                modifier = Modifier.widthIn(min = if (value == "document") 128.dp else 88.dp),
            ) {
                onSelected(value)
            }
        }
    }
}

@Composable
private fun TypePill(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val tint by animateColorAsState(
        targetValue = if (selected) GlassTheme.tokens.accent else GlassTheme.tokens.glassFillStrong,
        animationSpec = tween(durationMillis = 160),
    )
    val foreground by animateColorAsState(
        targetValue = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 160),
    )
    Box(
        modifier = modifier
            .height(38.dp)
            .background(tint, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MobileSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ResourceFileSummary(
    resource: ResourceTileUi,
    uploadState: UiAsync<String>,
) {
    val statusText = when (uploadState) {
        is UiAsync.Error -> uploadState.error.message
        is UiAsync.Success -> uploadState.value
        UiAsync.Loading -> "Subiendo..."
        UiAsync.Empty -> "Pendiente"
        UiAsync.Idle -> if (resource.fileName.isBlank()) "Sin archivo seleccionado" else "Archivo seleccionado"
    }
    val detail = listOf(resource.fileName, resource.mimeType)
        .filter { it.isNotBlank() }
        .joinToString(" | ")
        .ifBlank { statusText }
    Text(
        text = detail,
        style = MaterialTheme.typography.bodySmall,
        color = if (uploadState is UiAsync.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun resourceStatusLabel(resource: ResourceTileUi, uploadState: UiAsync<String>): String {
    return when (uploadState) {
        is UiAsync.Error -> "error"
        is UiAsync.Success -> if (resource.linked) "subido" else "seleccionado"
        UiAsync.Loading -> "subiendo"
        UiAsync.Empty -> "pendiente"
        UiAsync.Idle -> if (resource.linked) "subido" else "pendiente"
    }
}

@Composable
private fun MobileSpecialtiesPanel(
    state: ExamsState,
    onIntent: (ExamsIntent) -> Unit,
) {
    val specialties = (state.specialties as? UiAsync.Success)?.value.orEmpty()
    val query = state.specialtySearchQuery.trim().lowercase()
    val filtered = specialties.filter { specialty ->
        query.isBlank() ||
            specialty.name.lowercase().contains(query) ||
            specialty.description.lowercase().contains(query)
    }
    Column(verticalArrangement = Arrangement.spacedBy(UiSpacing.normal)) {
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
                        text = "Especialidades",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    MobileActionPill(
                        icon = MobileIcon.Plus,
                        label = "Nueva",
                        modifier = Modifier.width(112.dp),
                        onClick = { onIntent(ExamsIntent.CreateSpecialty) },
                    )
                }
                GlassTextField(
                    value = state.specialtySearchQuery,
                    onValueChange = { onIntent(ExamsIntent.UpdateSpecialtySearch(it)) },
                    placeholder = "Buscar especialidad",
                )
                when (val async = state.specialties) {
                    is UiAsync.Success -> {
                        if (filtered.isEmpty()) {
                            DropZone("Sin especialidades para este filtro.", DropTargetState.Idle)
                        } else {
                            filtered.forEach { specialty ->
                                SpecialtyMobileCard(
                                    specialty = specialty,
                                    selected = specialty.id == state.selectedContentSpecialtyId,
                                    onClick = { onIntent(ExamsIntent.SelectContentSpecialty(specialty.id)) },
                                )
                            }
                        }
                    }

                    UiAsync.Empty -> DropZone("No hay especialidades creadas.", DropTargetState.Idle)
                    is UiAsync.Error -> EmptyStateCard("No se pudieron cargar especialidades", async.error.message)
                    UiAsync.Loading -> EmptyStateCard("Cargando", "Preparando especialidades.")
                    UiAsync.Idle -> EmptyStateCard("Especialidades", "Sin carga activa.")
                }
            }
        }
        GlassSurface(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(UiSpacing.panel),
                verticalArrangement = Arrangement.spacedBy(UiSpacing.normal),
            ) {
                Text(
                    text = if (state.selectedContentSpecialtyId == null) "Crear especialidad" else "Editar especialidad",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                MobileSectionLabel("Informacion")
                GlassTextField(
                    value = state.specialtyEditorName,
                    onValueChange = { onIntent(ExamsIntent.UpdateSpecialtyName(it)) },
                    placeholder = "nombre",
                )
                GlassTextField(
                    value = state.specialtyEditorDescription,
                    onValueChange = { onIntent(ExamsIntent.UpdateSpecialtyDescription(it)) },
                    placeholder = "slug o descripcion",
                )
                GlassTextField(
                    value = state.specialtyEditorSortOrder,
                    onValueChange = { onIntent(ExamsIntent.UpdateSpecialtySortOrder(it)) },
                    placeholder = "orden",
                )
                MobileSectionLabel("Estado")
                StatusChip(if (state.specialtyEditorIsActive) "Activa" else "Inactiva")
                ResponsiveActionRow(
                    actions = listOf(
                        MobileActionSpec(
                            icon = MobileIcon.Specialty,
                            label = if (state.specialtyEditorIsActive) "Activa" else "Inactiva",
                            minWidth = 124,
                            onClick = { onIntent(ExamsIntent.ToggleSpecialtyActive(!state.specialtyEditorIsActive)) },
                        ),
                        MobileActionSpec(
                            icon = MobileIcon.Save,
                            label = "Guardar",
                            minWidth = 124,
                            onClick = { onIntent(ExamsIntent.SaveSpecialty) },
                        ),
                    ),
                )
                MobileSectionLabel("Eliminar especialidad")
                ResponsiveActionRow(
                    actions = listOf(
                        MobileActionSpec(
                            icon = MobileIcon.Remove,
                            label = "Eliminar",
                            minWidth = 124,
                            enabled = state.selectedContentSpecialtyId != null,
                            onClick = { onIntent(ExamsIntent.RequestDeleteSelectedSpecialty) },
                        ),
                    ),
                )
                when (val actionState = state.specialtyActionState) {
                    is UiAsync.Error -> Text(actionState.error.message, color = MaterialTheme.colorScheme.error)
                    UiAsync.Loading -> Text("Guardando especialidad...")
                    is UiAsync.Success -> Text("Especialidad sincronizada.")
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun SpecialtyMobileCard(
    specialty: Specialty,
    selected: Boolean,
    onClick: () -> Unit,
) {
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        tint = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else GlassTheme.tokens.glassFill,
        strokeColor = if (selected) GlassTheme.tokens.accent.copy(alpha = 0.62f) else GlassTheme.tokens.glassStroke,
        shadowElevation = if (selected) 14.dp else 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.width(210.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = specialty.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = specialty.description.ifBlank { "Sin descripcion" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusChip(if (specialty.isActive) "activa" else "inactiva")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(UiSpacing.compact)) {
                StatusChip("${specialty.linkedExams} examenes")
                StatusChip("orden ${specialty.sortOrder}")
            }
        }
    }
}

@Composable
private fun MobileTagSearchPanel(
    repository: TagSearchRepository?,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var tags by remember { mutableStateOf<List<Tag>>(emptyList()) }
    var selectedTags by remember { mutableStateOf<Set<String>>(emptySet()) }
    var results by remember { mutableStateOf<UiAsync<List<TaggedSearchResult>>>(UiAsync.Idle) }
    var reindexState by remember { mutableStateOf<UiAsync<Unit>>(UiAsync.Idle) }

    if (repository == null) {
        EmptyStateCard("Etiquetas", "El buscador de etiquetas no esta disponible en este entorno.")
        return
    }

    LaunchedEffect(query) {
        delay(180)
        when (val result = repository.findTags(query)) {
            is AppResult.Failure -> Unit
            is AppResult.Success -> tags = result.value
        }
    }

    LaunchedEffect(query, selectedTags) {
        delay(260)
        if (query.isBlank() && selectedTags.isEmpty()) {
            results = UiAsync.Idle
            return@LaunchedEffect
        }
        results = UiAsync.Loading
        results = when (
            val result = repository.search(
                TagSearchQuery(
                    text = query,
                    tagSlugs = selectedTags.toList(),
                    section = null,
                ),
            )
        ) {
            is AppResult.Failure -> UiAsync.Error(result.error)
            is AppResult.Success -> if (result.value.isEmpty()) UiAsync.Empty else UiAsync.Success(result.value)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(UiSpacing.normal)) {
        GlassSurface(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(UiSpacing.panel),
                verticalArrangement = Arrangement.spacedBy(UiSpacing.normal),
            ) {
                Text(
                    text = "Buscador por etiquetas",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                GlassTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Texto o etiqueta",
                )
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(items = tags.take(24), key = { tag: Tag -> tag.slug }) { tag ->
                        TypePill(
                            label = "#${tag.slug}",
                            selected = tag.slug in selectedTags,
                            modifier = Modifier.widthIn(min = 118.dp),
                        ) {
                            selectedTags = if (tag.slug in selectedTags) {
                                selectedTags - tag.slug
                            } else {
                                selectedTags + tag.slug
                            }
                        }
                    }
                }
                ResponsiveActionRow(
                    actions = listOf(
                        MobileActionSpec(
                            icon = MobileIcon.Remove,
                            label = "Limpiar",
                            minWidth = 118,
                            enabled = query.isNotBlank() || selectedTags.isNotEmpty(),
                            onClick = {
                                query = ""
                                selectedTags = emptySet<String>()
                                results = UiAsync.Idle
                            },
                        ),
                        MobileActionSpec(
                            icon = MobileIcon.Search,
                            label = "Reindexar",
                            minWidth = 132,
                            enabled = reindexState !is UiAsync.Loading,
                            onClick = {
                                reindexState = UiAsync.Loading
                            },
                        ),
                    ),
                )
                if (reindexState is UiAsync.Loading) {
                    LaunchedEffect(reindexState) {
                        reindexState = when (val result = repository.reindex()) {
                            is AppResult.Failure -> UiAsync.Error(result.error)
                            is AppResult.Success -> UiAsync.Success(Unit)
                        }
                    }
                }
                when (val state = reindexState) {
                    is UiAsync.Error -> Text(state.error.message, color = MaterialTheme.colorScheme.error)
                    is UiAsync.Success<*> -> Text("Indice actualizado.")
                    else -> Unit
                }
            }
        }
        SearchResultsMobilePane(results)
    }
}

@Composable
private fun SearchResultsMobilePane(
    state: UiAsync<List<TaggedSearchResult>>,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(UiSpacing.panel),
            verticalArrangement = Arrangement.spacedBy(UiSpacing.compact),
        ) {
            when (state) {
                UiAsync.Idle -> Text(
                    text = "Selecciona etiquetas o escribe texto para buscar contenido.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                UiAsync.Loading -> Text("Buscando...")
                UiAsync.Empty -> DropZone("Sin resultados para esta busqueda.", DropTargetState.Idle)
                is UiAsync.Error -> EmptyStateCard("No se pudo buscar", state.error.message)
                is UiAsync.Success -> state.value.take(20).forEach { result ->
                    SearchResultMobileCard(result)
                }
            }
        }
    }
}

@Composable
private fun SearchResultMobileCard(result: TaggedSearchResult) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = result.title,
                    modifier = Modifier.width(212.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusChip(result.section)
            }
            Text(
                text = result.summary.ifBlank { result.entityType },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (result.tags.isNotEmpty()) {
                Text(
                    text = result.tags.take(5).joinToString(" ") { "#${it.slug}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
                    MobileNewsSection(
                        title = "Publicadas",
                        state = state.publishedNews,
                        selectedId = state.selectedId,
                        emptyMessage = "No hay noticias publicadas.",
                        published = true,
                        onIntent = onIntent,
                    )
                    MobileNewsSection(
                        title = "Guardadas/locales",
                        state = state.localNews,
                        selectedId = state.selectedId,
                        emptyMessage = "No hay noticias guardadas.",
                        published = false,
                        onIntent = onIntent,
                    )
                }
            }
        }
    }
}

@Composable
private fun MobileNewsSection(
    title: String,
    state: UiAsync<List<NewsItem>>,
    selectedId: String?,
    emptyMessage: String,
    published: Boolean,
    onIntent: (NewsIntent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        when (state) {
            is UiAsync.Success -> state.value.forEach { item ->
                MobileNewsCard(
                    item = item,
                    selected = item.id == selectedId,
                    published = published,
                    onClick = { onIntent(NewsIntent.Select(item.id)) },
                    onSaveLocal = {
                        onIntent(NewsIntent.Select(item.id))
                        onIntent(NewsIntent.SaveDraft)
                    },
                    onPublish = {
                        onIntent(NewsIntent.Select(item.id))
                        onIntent(NewsIntent.Publish)
                    },
                    onArchive = {
                        onIntent(NewsIntent.Select(item.id))
                        onIntent(NewsIntent.Archive)
                    },
                )
            }

            UiAsync.Empty -> DropZone(emptyMessage, DropTargetState.Idle)
            is UiAsync.Error -> EmptyStateCard("No se pudieron cargar noticias", state.error.message)
            UiAsync.Loading -> EmptyStateCard("Cargando", "Preparando noticias.")
            UiAsync.Idle -> EmptyStateCard(title, "Sin carga activa.")
        }
    }
}

@Composable
private fun MobileNewsCard(
    item: NewsItem,
    selected: Boolean,
    published: Boolean,
    onClick: () -> Unit,
    onSaveLocal: () -> Unit,
    onPublish: () -> Unit,
    onArchive: () -> Unit,
) {
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        tint = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else GlassTheme.tokens.glassFill,
        strokeColor = if (selected) GlassTheme.tokens.accent.copy(alpha = 0.62f) else GlassTheme.tokens.glassStroke,
        shadowElevation = if (selected) 14.dp else 8.dp,
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
                NewsCardPreview(item = item)
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
                StatusChip(item.status.ifBlank { "draft" }.toReadableStatus())
                StatusChip("${item.layers.size} capas")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MobileActionPill(
                    icon = MobileIcon.Edit,
                    label = "Editar",
                    modifier = Modifier.width(92.dp),
                    onClick = onClick,
                )
                if (published) {
                    MobileActionPill(
                        icon = MobileIcon.Remove,
                        label = "Archivar",
                        modifier = Modifier.width(112.dp),
                        onClick = onArchive,
                    )
                } else {
                    MobileActionPill(
                        icon = MobileIcon.Save,
                        label = "Guardar",
                        modifier = Modifier.width(102.dp),
                        onClick = onSaveLocal,
                    )
                    MobileActionPill(
                        icon = MobileIcon.Up,
                        label = "Publicar",
                        modifier = Modifier.width(110.dp),
                        onClick = onPublish,
                    )
                }
            }
        }
    }
}

@Composable
private fun NewsCardPreview(item: NewsItem) {
    val color = if (item.backgroundPath.isNullOrBlank()) GlassTheme.tokens.accent else MaterialTheme.colorScheme.secondary
    Box(
        modifier = Modifier
            .width(52.dp)
            .height(42.dp)
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (item.backgroundPath.isNullOrBlank()) item.backgroundType.ifBlank { "SKY" }.take(3) else "IMG",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
        id = id,
        title = title,
        specialty = specialtyName,
        status = status.name.lowercase(),
        priceTickets = priceTickets,
        questionCount = questionCount,
        coverUrl = coverUrl.orEmpty(),
        coverStoragePath = coverStoragePath.orEmpty(),
        isBurst = false,
        hasSelection = selected,
    )
}

private fun Exam.toContentExamUi(selected: Boolean): ContentExamUi {
    return ContentExamUi(
        id = id,
        code = code,
        title = title,
        specialty = specialtyName,
        typeExam = typeExam,
        typeAccess = typeAccess,
        year = year,
        questionCount = questionCount,
        durationMinutes = durationMinutes,
        isOfficial = isOfficial,
        selected = selected,
    )
}

private fun String.toAccessLabel(): String {
    return when (lowercase()) {
        "gratuito" -> "Gratis"
        "premium" -> "Premium"
        else -> ifBlank { "Acceso" }
    }
}

private fun String.toExamTypeLabel(): String {
    return when (lowercase()) {
        "rafaga" -> "Rafaga"
        "inmersivo" -> "Inmersivo"
        else -> ifBlank { "Examen" }
    }
}

private fun Rafaga.toRafagaUi(selected: Boolean): RafagaUi {
    return RafagaUi(
        id = id,
        title = title,
        specialty = specialtyName,
        status = status.name.lowercase(),
        priceTickets = priceTickets,
        questionCount = questionCount,
        hasSelection = selected,
    )
}

private fun ExamQuestion.toQuestionUi(selected: Boolean): QuestionUi {
    return QuestionUi(
        id = id,
        title = title,
        statement = statement,
        correctOptionLabel = options.firstOrNull { it.isCorrect }?.label ?: "-",
        resourceCount = resources.size,
        hasResources = resources.isNotEmpty(),
        selected = selected,
    )
}

private fun RafagaQuestion.toRafagaQuestionUi(selected: Boolean): RafagaQuestionUi {
    return RafagaQuestionUi(
        id = id,
        statement = statement,
        selected = selected,
    )
}

private fun QuestionResourceDraft.toResourceTileUi(): ResourceTileUi {
    val linkedValue = publicUrl ?: storagePath
    val hasLocalUri = inputValue.startsWith("content://") || inputValue.startsWith("file://")
    return ResourceTileUi(
        type = type.ifBlank { "documento" },
        title = title.ifBlank { "Recurso sin titulo" },
        valuePreview = when {
            linkedValue != null -> linkedValue.substringAfterLast('/').ifBlank { "Recurso subido" }
            localFileName.isNotBlank() -> localFileName
            hasLocalUri -> "Archivo seleccionado"
            inputValue.isNotBlank() -> inputValue
            else -> "Pendiente de contenido o archivo"
        },
        fileName = localFileName,
        mimeType = mimeType.orEmpty(),
        linked = linkedValue != null,
    )
}
