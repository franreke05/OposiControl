package com.oposicontrol.admin.features.news.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.id.randomUuid
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.data.local.NewsEditorDraftLocalDataSource
import com.oposicontrol.admin.data.local.NewsEditorDraftSnapshot
import com.oposicontrol.admin.domain.model.NewsArticle
import com.oposicontrol.admin.domain.model.NewsArticleDraft
import com.oposicontrol.admin.domain.model.NewsArticleType
import com.oposicontrol.admin.domain.model.NewsAssetDraft
import com.oposicontrol.admin.domain.model.NewsAssetStatus
import com.oposicontrol.admin.domain.model.NewsCanvasDraft
import com.oposicontrol.admin.domain.model.NewsCanvasLayer
import com.oposicontrol.admin.domain.model.NewsDesignDraft
import com.oposicontrol.admin.domain.model.NewsEditorialStatus
import com.oposicontrol.admin.domain.model.NewsLayer
import com.oposicontrol.admin.domain.model.NewsLayerKind
import com.oposicontrol.admin.domain.model.NewsListFilter
import com.oposicontrol.admin.domain.model.NewsSummary
import com.oposicontrol.admin.domain.repository.AdminOperationsRepository
import com.oposicontrol.admin.domain.repository.ContentRepository
import com.oposicontrol.admin.domain.usecase.news.ArchiveNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.DeleteNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.LoadMoreNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.LoadNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.ObserveNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.PublishNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.RefreshNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.SaveNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.ScheduleNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.UploadNewsAssetUseCase
import com.oposicontrol.admin.domain.usecase.news.ValidateNewsDraftUseCase
import com.oposicontrol.admin.features.news.editor.NEWS_CANVAS_DEFAULT_HEIGHT
import com.oposicontrol.admin.features.news.editor.NEWS_CANVAS_DEFAULT_WIDTH
import com.oposicontrol.admin.features.news.editor.NEWS_LAYER_MIN_SIDE
import com.oposicontrol.admin.features.news.editor.NewsAlignment
import com.oposicontrol.admin.features.news.editor.NewsDocument
import com.oposicontrol.admin.features.news.editor.NewsEditGroup
import com.oposicontrol.admin.features.news.editor.NewsFrame
import com.oposicontrol.admin.features.news.editor.NewsHistory
import com.oposicontrol.admin.features.news.editor.alignFrame
import com.oposicontrol.admin.features.news.editor.clampFrame
import com.oposicontrol.admin.features.news.editor.nudgeFrame
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Rango editable del tamano de fuente. Empieza en 8 y no en 0 a proposito: `0`
 * es el centinela de «derivado del tipo de capa» (ver [NewsLayerDraft.fontSizeSp])
 * y no debe poder escribirse como si fuera un tamano.
 */
private const val NewsLayerMinFontSizeSp = 8
private const val NewsLayerMaxFontSizeSp = 200

/** Los unicos pesos que el lienzo sabe pintar, mas la cadena vacia («derivado del tipo de capa»). */
private val NewsLayerFontWeights = setOf("", "normal", "medium", "semibold", "bold")

/** Prefijo del identificador de una noticia que todavia no existe en el servidor. */
internal const val NEWS_LOCAL_ID_PREFIX = "local-"

internal fun String?.isLocalNewsId(): Boolean = this?.startsWith(NEWS_LOCAL_ID_PREFIX) == true

private val draftJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

@Serializable
private data class NewsLayerDraftJson(
    val id: String,
    val name: String,
    val kind: String,
    val content: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val opacity: Int,
    val fontFamily: String = "sans",
    val textColor: String = "",
    val visible: Boolean,
    val locked: Boolean = false,
    val sortOrder: Int,
    val rotationDegrees: Int = 0,
    val fontSizeSp: Int = 0,
    val fontWeight: String = "",
)

private fun NewsLayerDraft.toJson() = NewsLayerDraftJson(
    id = id, name = name, kind = kind, content = content,
    x = x, y = y, width = width, height = height, opacity = opacity,
    fontFamily = fontFamily, textColor = textColor, visible = visible,
    locked = locked, sortOrder = sortOrder, rotationDegrees = rotationDegrees,
    fontSizeSp = fontSizeSp, fontWeight = fontWeight,
)

private fun NewsLayerDraftJson.toDraft() = NewsLayerDraft(
    id = id, name = name, kind = kind, content = content,
    x = x, y = y, width = width, height = height, opacity = opacity,
    fontFamily = fontFamily, textColor = textColor, visible = visible,
    locked = locked, sortOrder = sortOrder, rotationDegrees = rotationDegrees,
    fontSizeSp = fontSizeSp, fontWeight = fontWeight,
)

private fun List<NewsLayerDraft>.encodeToJsonString(): String =
    runCatching { draftJson.encodeToString(map { it.toJson() }) }.getOrElse { "[]" }

private fun String.decodeLayersFromJson(): List<NewsLayerDraft> =
    runCatching { draftJson.decodeFromString<List<NewsLayerDraftJson>>(this).map { it.toDraft() } }.getOrElse { emptyList() }

/**
 * Noticias: biblioteca (listado canonico, filtros en el servidor) y ficha (documento con historial de deshacer).
 *
 * Reglas que gobiernan este ViewModel:
 *  - Guardar es guardar en el SERVIDOR. El borrador local de SQLDelight es solo una red de seguridad: se escribe con
 *    cada edicion sin guardar y se restaura al abrir la pantalla, nunca sustituye al guardado.
 *  - El estado editorial lo pone el servidor: publicar, programar y archivar son comandos sobre una noticia ya
 *    guardada; si hay cambios pendientes, se guardan primero y solo despues se manda el comando.
 *  - Una noticia publicada o archivada no se edita (D4-04): sus intents de edicion se ignoran y la pantalla lo dice.
 *  - Toda mutacion del documento pasa por [commit], que alimenta el historial, recalcula la validacion y marca sucio.
 */
class NewsViewModel(
    dispatcherProvider: DispatcherProvider,
    observeNewsUseCase: ObserveNewsUseCase,
    private val refreshNewsUseCase: RefreshNewsUseCase,
    private val loadMoreNewsUseCase: LoadMoreNewsUseCase,
    private val loadNewsUseCase: LoadNewsUseCase,
    private val saveNewsUseCase: SaveNewsUseCase,
    private val deleteNewsUseCase: DeleteNewsUseCase,
    private val validateNewsDraftUseCase: ValidateNewsDraftUseCase,
    private val publishNewsUseCase: PublishNewsUseCase,
    private val scheduleNewsUseCase: ScheduleNewsUseCase,
    private val uploadNewsAssetUseCase: UploadNewsAssetUseCase,
    private val archiveNewsUseCase: ArchiveNewsUseCase,
    private val analyticsTracker: AnalyticsTracker,
    private val draftLocalDataSource: NewsEditorDraftLocalDataSource,
    private val contentRepository: ContentRepository? = null,
    private val operationsRepository: AdminOperationsRepository? = null,
    private val searchDebounceMillis: Long = SEARCH_DEBOUNCE_MILLIS,
) : MviViewModel<NewsState, NewsIntent>(
    initialState = NewsState(),
    dispatcherProvider = dispatcherProvider,
) {
    private var summaries: List<NewsSummary> = emptyList()
    private var history = NewsHistory()

    /** Documento tal como esta en el servidor (o `null` para una noticia que aun no existe): la referencia de «sucio». */
    private var baseline: NewsDocument? = null
    private var createKey: String = randomUuid()
    private var searchJob: Job? = null
    private var pendingSnapshot: NewsEditorDraftSnapshot? = null
    private var autoSelectDone = false

    /**
     * URL publica de cada ruta de asset que esta sesion conoce (la da el servidor al leer la ficha o al subir). El
     * documento solo guarda la RUTA: sin este registro, deshacer o descartar hacia una imagen anterior la dejaba
     * enlazada pero sin poder pintarla.
     */
    private val assetUrls = mutableMapOf<String, String>()
    private var assetBaseUrl: String? = null

    init {
        restoreDraftIfPresent()
        scope.launch {
            observeNewsUseCase().collectLatest { rows ->
                summaries = rows
                publishLibrary()
                autoSelectFirstIfIdle()
            }
        }
        scope.launch { refreshLibrary() }
        scope.launch { refreshCatalogs() }
        analyticsTracker.trackScreen("news")
    }

    // =================================================================================================================
    // Intents
    // =================================================================================================================

    override suspend fun handleIntent(intent: NewsIntent) {
        val busy = currentState().actionState is UiAsync.Loading
        if (busy && intent !is NewsIntent.Refresh && intent !is NewsIntent.SelectTab) return
        when (intent) {
            // ---- biblioteca
            NewsIntent.Refresh -> { refreshLibrary(); refreshCatalogs() }
            NewsIntent.LoadMore -> loadMore()
            is NewsIntent.UpdateSearch -> {
                updateState { it.copy(searchQuery = intent.value) }
                searchJob?.cancel()
                searchJob = scope.launch {
                    delay(searchDebounceMillis)
                    refreshLibrary()
                }
            }
            is NewsIntent.UpdateStatusFilter -> { updateState { it.copy(statusFilter = intent.value) }; refreshLibrary() }
            is NewsIntent.UpdateSpecialtyFilter -> { updateState { it.copy(specialtyFilter = intent.value) }; refreshLibrary() }
            is NewsIntent.UpdateTypeFilter -> { updateState { it.copy(typeFilter = intent.value) }; refreshLibrary() }
            NewsIntent.ClearFilters -> {
                updateState { it.copy(searchQuery = "", statusFilter = "all", specialtyFilter = "all", typeFilter = "all") }
                refreshLibrary()
            }

            // ---- ficha
            NewsIntent.CreateNew -> guarded(intent) { createNew() }
            is NewsIntent.Select -> if (intent.id != currentState().selectedId) guarded(intent) { open(intent.id) }
            NewsIntent.RetryRecord -> currentState().selectedId?.takeUnless { it.isLocalNewsId() }?.let { open(it) }
            is NewsIntent.SelectTab -> updateState { it.copy(editorTab = intent.tab) }
            NewsIntent.SaveDraft -> save()
            is NewsIntent.SaveDraftFor -> { if (switchTo(intent.id)) save() }
            NewsIntent.Publish -> publish()
            is NewsIntent.PublishFor -> { if (switchTo(intent.id)) publish() }
            NewsIntent.Schedule -> schedule(currentState().editorPublishFrom)
            is NewsIntent.ScheduleAt -> schedule(intent.isoUtc)
            NewsIntent.Archive -> archive()
            is NewsIntent.ArchiveFor -> { if (switchTo(intent.id)) archive() }
            NewsIntent.ValidateDraft -> validateCurrent()
            NewsIntent.DeleteSelected -> delete()
            NewsIntent.Discard -> discard()
            NewsIntent.Undo -> undo()
            NewsIntent.Redo -> redo()
            NewsIntent.ConfirmPending -> {
                val pending = currentState().pendingIntent ?: return
                updateState { it.copy(pendingIntent = null) }
                when (pending) {
                    NewsIntent.CreateNew -> createNew()
                    is NewsIntent.Select -> open(pending.id)
                    else -> handleIntent(pending)
                }
            }
            NewsIntent.DismissPending -> updateState { it.copy(pendingIntent = null) }
            is NewsIntent.RequestConfirm -> updateState { it.copy(confirmIntent = intent.intent) }
            NewsIntent.DismissConfirm -> updateState { it.copy(confirmIntent = null) }
            NewsIntent.DismissRecoveredDraft -> updateState { it.copy(recoveredDraft = false) }

            // ---- contenido
            is NewsIntent.UpdateTitle -> commit(NewsEditGroup("title")) { state ->
                state.copy(editorTitle = intent.value, editorLayers = state.editorLayers.withTitleContent(state.editorTitle, intent.value))
            }
            is NewsIntent.UpdateSpecialty -> commit(NewsEditGroup("specialty")) { it.copy(editorSpecialty = intent.value) }
            is NewsIntent.UpdateType -> commit(NewsEditGroup("type")) { it.copy(editorType = intent.value) }
            is NewsIntent.UpdateText -> commit(NewsEditGroup("text")) { it.copy(editorText = intent.value) }
            // El estado editorial es del servidor: el intent se conserva por el editor movil y no cambia nada aqui.
            is NewsIntent.UpdateStatus -> Unit
            is NewsIntent.UpdateBackgroundType -> commit(NewsEditGroup("backgroundType")) {
                it.copy(editorBackgroundType = intent.value.toNewsBackgroundStyle())
            }
            is NewsIntent.UpdateBackgroundPath -> commit(NewsEditGroup("backgroundPath")) {
                it.copy(
                    editorBackgroundPath = intent.value.trim(),
                    editorBackgroundUrl = assetUrl(intent.value.trim()),
                    editorBackgroundAssetStatus = "linked",
                )
            }
            is NewsIntent.UpdateImagePath -> commit(NewsEditGroup("imagePath")) {
                it.copy(
                    editorImagePath = intent.value.trim(),
                    editorImageUrl = assetUrl(intent.value.trim()),
                    editorImageAssetStatus = "linked",
                )
            }
            is NewsIntent.SelectBackgroundFile -> startUpload(NEWS_UPLOAD_BACKGROUND, intent.uri)
            is NewsIntent.UploadBackground -> startUpload(NEWS_UPLOAD_BACKGROUND, intent.uri)
            is NewsIntent.UploadImage -> startUpload(NEWS_UPLOAD_IMAGE, intent.uri)
            is NewsIntent.UploadLayerImage -> startUpload(newsLayerUploadTarget(intent.id), intent.uri)
            is NewsIntent.RetryUpload -> currentState().uploads[intent.target]?.let { startUpload(it.target, it.uri) }
            is NewsIntent.DismissUpload -> updateState { it.copy(uploads = it.uploads - intent.target) }

            // ---- lienzo y capas
            is NewsIntent.UpdateCanvasWidth -> commit(NewsEditGroup("canvas")) {
                it.copy(editorCanvasWidth = intent.value.toPositiveInt(it.editorCanvasWidth))
            }
            is NewsIntent.UpdateCanvasHeight -> commit(NewsEditGroup("canvas")) {
                it.copy(editorCanvasHeight = intent.value.toPositiveInt(it.editorCanvasHeight))
            }
            NewsIntent.ToggleCanvasFullscreen -> updateState { it.copy(canvasFullscreen = !it.canvasFullscreen) }
            NewsIntent.SelectBackground -> updateState { it.copy(selectedTarget = NewsEditorSelection.BACKGROUND, selectedLayerId = null) }
            NewsIntent.AddTitleLayer -> addTitleLayer()
            NewsIntent.AddLayer -> addTextLayer()
            is NewsIntent.AddLayerWithImage -> addImageLayer(intent.uri)
            is NewsIntent.SelectLayer -> selectLayer(intent.id)
            NewsIntent.DeselectLayer -> updateState { it.copy(selectedTarget = NewsEditorSelection.ARTICLE, selectedLayerId = null) }
            is NewsIntent.SelectAdjacentLayer -> selectAdjacentLayer(intent.backwards)
            is NewsIntent.RemoveLayer -> removeLayer(intent.id)
            is NewsIntent.MoveLayerDown -> moveLayer(intent.id, -1)
            is NewsIntent.MoveLayerUp -> moveLayer(intent.id, 1)
            is NewsIntent.BringLayerToFront -> moveLayerToEdge(intent.id, toFront = true)
            is NewsIntent.SendLayerToBack -> moveLayerToEdge(intent.id, toFront = false)
            is NewsIntent.CenterLayer -> updateLayer(intent.id, NewsEditGroup("align", intent.id)) { layer, canvasWidth, canvasHeight ->
                layer.withFrame(alignFrame(alignFrame(layer.frame(), NewsAlignment.CENTER_H, canvasWidth, canvasHeight), NewsAlignment.CENTER_V, canvasWidth, canvasHeight))
            }
            is NewsIntent.AlignLayer -> updateLayer(intent.id, NewsEditGroup("align", intent.id)) { layer, canvasWidth, canvasHeight ->
                layer.withFrame(alignFrame(layer.frame(), intent.alignment, canvasWidth, canvasHeight))
            }
            is NewsIntent.NudgeLayer -> updateLayer(intent.id, NewsEditGroup("nudge", intent.id)) { layer, canvasWidth, canvasHeight ->
                layer.withFrame(nudgeFrame(layer.frame(), intent.dx, intent.dy, canvasWidth, canvasHeight))
            }
            is NewsIntent.DuplicateLayer -> duplicateLayer(intent.id)
            is NewsIntent.ToggleLayerVisible -> updateLayer(intent.id, NewsEditGroup("visible", intent.id)) { layer, _, _ -> layer.copy(visible = !layer.visible) }
            is NewsIntent.ToggleLayerLocked -> updateLayer(intent.id, NewsEditGroup("locked", intent.id), allowLocked = true) { layer, _, _ -> layer.copy(locked = !layer.locked) }
            is NewsIntent.UpdateLayerName -> updateLayer(intent.id, NewsEditGroup("name", intent.id)) { layer, _, _ -> layer.copy(name = intent.value) }
            is NewsIntent.UpdateLayerKind -> updateLayer(intent.id, NewsEditGroup("kind", intent.id)) { layer, _, _ ->
                // Entre texto e imagen el contenido no se traduce: un parrafo no es una URL, ni al reves.
                val crossesKind = (layer.kind.toLayerKind() == NewsLayerKind.IMAGE) != (intent.value.toLayerKind() == NewsLayerKind.IMAGE)
                layer.copy(kind = intent.value, content = if (crossesKind) "" else layer.content)
            }
            is NewsIntent.UpdateLayerContent -> updateLayer(intent.id, NewsEditGroup("content", intent.id)) { layer, _, _ -> layer.copy(content = intent.value) }
            is NewsIntent.UpdateLayerX -> updateLayer(intent.id, NewsEditGroup("frame", intent.id)) { layer, _, _ -> layer.copy(x = intent.value.toIntOrNull() ?: layer.x) }
            is NewsIntent.UpdateLayerY -> updateLayer(intent.id, NewsEditGroup("frame", intent.id)) { layer, _, _ -> layer.copy(y = intent.value.toIntOrNull() ?: layer.y) }
            is NewsIntent.UpdateLayerWidth -> updateLayer(intent.id, NewsEditGroup("frame", intent.id)) { layer, _, _ -> layer.copy(width = intent.value.toPositiveInt(layer.width)) }
            is NewsIntent.UpdateLayerHeight -> updateLayer(intent.id, NewsEditGroup("frame", intent.id)) { layer, _, _ -> layer.copy(height = intent.value.toPositiveInt(layer.height)) }
            is NewsIntent.UpdateLayerFrame -> updateLayer(intent.id, NewsEditGroup(if (intent.gesture) "gesture" else "frame", intent.id)) { layer, _, _ ->
                layer.copy(x = intent.x, y = intent.y, width = intent.width, height = intent.height)
            }
            is NewsIntent.UpdateLayerOpacity -> updateLayer(intent.id, NewsEditGroup("opacity", intent.id)) { layer, _, _ ->
                layer.copy(opacity = intent.value.toBoundedInt(layer.opacity, 0, 100))
            }
            is NewsIntent.UpdateLayerRotation -> updateLayer(intent.id, NewsEditGroup("rotation", intent.id)) { layer, _, _ ->
                layer.copy(rotationDegrees = intent.degrees.normalizedDegrees())
            }
            is NewsIntent.UpdateLayerFontFamily -> updateLayer(intent.id, NewsEditGroup("font", intent.id)) { layer, _, _ -> layer.copy(fontFamily = intent.value) }
            is NewsIntent.UpdateLayerFontSize -> updateLayer(intent.id, NewsEditGroup("fontSize", intent.id)) { layer, _, _ ->
                // Un `0` explicito devuelve la capa a «derivado del tipo»; sin este caso fijar el tamano seria irreversible.
                val size = if (intent.value.trim() == "0") 0 else intent.value.toBoundedInt(layer.fontSizeSp, NewsLayerMinFontSizeSp, NewsLayerMaxFontSizeSp)
                layer.copy(fontSizeSp = size)
            }
            is NewsIntent.UpdateLayerFontWeight -> updateLayer(intent.id, NewsEditGroup("fontWeight", intent.id)) { layer, _, _ ->
                layer.copy(fontWeight = intent.value.toKnownFontWeight(layer.fontWeight))
            }
            is NewsIntent.UpdateLayerTextColor -> updateLayer(intent.id, NewsEditGroup("color", intent.id)) { layer, _, _ -> layer.copy(textColor = intent.value) }
        }
    }

    /** Navegacion que tiraria cambios sin guardar: se retiene y la pantalla pregunta. */
    private suspend fun guarded(intent: NewsIntent, action: suspend () -> Unit) {
        if (currentState().isDirty) updateState { it.copy(pendingIntent = intent) } else action()
    }

    /** Variantes `*For` del editor movil: abren la fila si no es la abierta. `false` si no pudo abrirse. */
    private suspend fun switchTo(id: String): Boolean {
        if (currentState().selectedId == id) return true
        open(id)
        return currentState().selectedId == id && currentState().recordState is UiAsync.Success
    }

    // =================================================================================================================
    // Biblioteca
    // =================================================================================================================

    private suspend fun refreshLibrary() {
        val hadRows = summaries.isNotEmpty()
        updateState { it.copy(libraryRefreshing = hadRows, libraryState = if (hadRows) it.libraryState else UiAsync.Loading) }
        when (val result = refreshNewsUseCase(currentState().listFilter())) {
            is AppResult.Failure -> updateState {
                it.copy(
                    libraryRefreshing = false,
                    libraryRefreshFailed = hadRows,
                    libraryState = if (hadRows) it.libraryState else UiAsync.Error(result.error),
                )
            }
            is AppResult.Success -> {
                updateState { it.copy(libraryRefreshing = false, libraryRefreshFailed = false, libraryState = UiAsync.Success(Unit), libraryNextCursor = result.value.nextCursor) }
                // La ficha abierta se relee solo si no hay cambios en ella: refrescar nunca pisa lo que se esta escribiendo.
                val id = currentState().selectedId
                if (id != null && !id.isLocalNewsId() && !currentState().isDirty && currentState().recordState is UiAsync.Success) reloadQuietly(id)
            }
        }
    }

    private suspend fun loadMore() {
        val cursor = currentState().libraryNextCursor ?: return
        if (currentState().libraryLoadingMore) return
        updateState { it.copy(libraryLoadingMore = true) }
        when (val result = loadMoreNewsUseCase(currentState().listFilter(), cursor)) {
            is AppResult.Failure -> updateState { it.copy(libraryLoadingMore = false, libraryRefreshFailed = true) }
            is AppResult.Success -> updateState { it.copy(libraryLoadingMore = false, libraryNextCursor = result.value.nextCursor) }
        }
    }

    private suspend fun refreshCatalogs() {
        val unavailable = UiAsync.Error(AppError.Validation("Catálogo no disponible."))
        updateState { it.copy(specialties = UiAsync.Loading, newsTypes = UiAsync.Loading) }
        val specialties = when (val result = contentRepository?.refreshSpecialties()) {
            is AppResult.Success -> UiAsync.Success(result.value.map { NewsCatalogOption(it.id, it.name, it.isActive) })
            is AppResult.Failure -> UiAsync.Error(result.error)
            null -> unavailable
        }
        updateState { it.copy(specialties = specialties) }
        val types = when (val result = operationsRepository?.getNewsTypes()) {
            is AppResult.Success -> UiAsync.Success(result.value.map { NewsCatalogOption(it.id, it.name, it.isActive) })
            is AppResult.Failure -> UiAsync.Error(result.error)
            null -> unavailable
        }
        updateState { it.copy(newsTypes = types) }
    }

    private fun publishLibrary() {
        val state = currentState()
        val remote = summaries.map { it.toItem() }
        val local = state.selectedId?.takeIf { it.isLocalNewsId() }?.let { listOf(state.toItem()) }.orEmpty()
        val all = local + remote
        updateState {
            it.copy(
                news = if (all.isEmpty()) UiAsync.Empty else UiAsync.Success(all),
                publishedNews = remote.filter { row -> row.status.equals("published", ignoreCase = true) }.let { rows -> if (rows.isEmpty()) UiAsync.Empty else UiAsync.Success(rows) },
                localNews = if (local.isEmpty()) UiAsync.Empty else UiAsync.Success(local),
            )
        }
    }

    /** Con la biblioteca recien leida y nada abierto, se abre la primera fila. En su propia corrutina: el colector no espera a la red. */
    private fun autoSelectFirstIfIdle() {
        if (autoSelectDone || summaries.isEmpty()) return
        autoSelectDone = true
        if (currentState().selectedId != null || pendingSnapshot != null) return
        val first = summaries.first().id
        scope.launch { open(first) }
    }

    // =================================================================================================================
    // Ficha: abrir, crear, descartar, deshacer
    // =================================================================================================================

    private suspend fun open(id: String) {
        updateState { it.copy(selectedId = id, recordState = UiAsync.Loading, pendingIntent = null, confirmIntent = null, actionState = UiAsync.Idle, actionKind = null, actionConflict = false, recoveredDraft = false, uploads = emptyMap()) }
        when (val result = loadNewsUseCase(id)) {
            is AppResult.Failure -> updateState { it.copy(recordState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                applyArticle(result.value, resetSelection = true)
                val snapshot = pendingSnapshot?.takeIf { it.newsId == id }
                if (snapshot != null) {
                    pendingSnapshot = null
                    applyRecovered(snapshot)
                } else {
                    clearSnapshot()
                }
            }
        }
        publishLibrary()
    }

    /** Relectura tras un refresco: solo si la ficha no tiene cambios. Un fallo aqui no quita nada de la pantalla. */
    private suspend fun reloadQuietly(id: String) {
        when (val result = loadNewsUseCase(id)) {
            is AppResult.Failure -> Unit
            is AppResult.Success -> if (currentState().selectedId == id && !currentState().isDirty) applyArticle(result.value, resetSelection = false)
        }
    }

    private fun applyArticle(article: NewsArticle, resetSelection: Boolean) {
        rememberAsset(article.backgroundPath, article.backgroundUrl)
        rememberAsset(article.imagePath, article.imageUrl)
        val layers = article.layers.map { it.toUi() }
            .ifEmpty { defaultLayers(article.title) }
            .map { it.coerceInsideCanvas(NEWS_CANVAS_DEFAULT_WIDTH, NEWS_CANVAS_DEFAULT_HEIGHT) }
            .normalizedLayerOrder()
        updateState { state ->
            state.copy(
                selectedId = article.id,
                recordState = UiAsync.Success(Unit),
                editorTitle = article.title,
                editorBackgroundType = article.backgroundType.toNewsBackgroundStyle(),
                editorBackgroundPath = article.backgroundPath.orEmpty(),
                editorBackgroundUrl = article.backgroundUrl.orEmpty(),
                editorSpecialty = article.specialty,
                editorType = article.type,
                editorText = article.text,
                editorImagePath = article.imagePath.orEmpty(),
                editorImageUrl = article.imageUrl.orEmpty(),
                editorStatus = article.status,
                editorPublishFrom = article.publishFrom,
                editorPublishedAt = article.publishedAt.orEmpty(),
                editorVersion = article.version,
                editorCanvasWidth = NEWS_CANVAS_DEFAULT_WIDTH,
                editorCanvasHeight = NEWS_CANVAS_DEFAULT_HEIGHT,
                editorBackgroundAssetStatus = if (article.backgroundPath.isNullOrBlank()) "linked" else "uploaded",
                editorImageAssetStatus = if (article.imagePath.isNullOrBlank()) "linked" else "uploaded",
                editorLayers = layers,
                selectedTarget = if (resetSelection) NewsEditorSelection.ARTICLE else state.selectedTarget,
                selectedLayerId = if (resetSelection) null else state.selectedLayerId?.takeIf { id -> layers.any { it.id == id } },
                isDirty = false,
                canUndo = false,
                canRedo = false,
                actionConflict = false,
            )
        }
        baseline = currentState().document()
        history = NewsHistory()
        validateCurrent()
    }

    private fun createNew() {
        val localId = NEWS_LOCAL_ID_PREFIX + randomUuid()
        createKey = randomUuid()
        updateState { state ->
            state.copy(
                selectedId = localId,
                recordState = UiAsync.Success(Unit),
                editorTitle = "",
                editorBackgroundType = "SKY",
                editorBackgroundPath = "",
                editorBackgroundUrl = "",
                editorSpecialty = "",
                editorType = "",
                editorText = "",
                editorImagePath = "",
                editorImageUrl = "",
                editorStatus = "draft",
                editorPublishFrom = "",
                editorPublishedAt = "",
                editorVersion = "",
                editorCanvasWidth = NEWS_CANVAS_DEFAULT_WIDTH,
                editorCanvasHeight = NEWS_CANVAS_DEFAULT_HEIGHT,
                editorBackgroundAssetStatus = "linked",
                editorImageAssetStatus = "linked",
                editorLayers = defaultLayers(title = ""),
                editorTab = NewsEditorTab.CONTENT,
                selectedTarget = NewsEditorSelection.ARTICLE,
                selectedLayerId = null,
                actionState = UiAsync.Idle,
                actionKind = null,
                actionConflict = false,
                isDirty = false,
                canUndo = false,
                canRedo = false,
                uploads = emptyMap(),
                pendingIntent = null,
                confirmIntent = null,
                recoveredDraft = false,
            )
        }
        baseline = null
        history = NewsHistory()
        clearSnapshot()
        validateCurrent()
        publishLibrary()
    }

    private fun discard() {
        val base = baseline
        if (base == null) {
            // Noticia nueva: descartar es volver a la ficha en blanco.
            createNew()
            return
        }
        applyDocument(base)
        history = NewsHistory()
        updateState { it.copy(isDirty = false, canUndo = false, canRedo = false, actionState = UiAsync.Idle, actionKind = null, actionConflict = false, recoveredDraft = false, uploads = emptyMap()) }
        clearSnapshot()
        validateCurrent()
        publishLibrary()
    }

    private fun undo() {
        val (next, document) = history.undo(currentState().document()) ?: return
        history = next
        applyDocument(document)
        afterHistoryMove()
    }

    private fun redo() {
        val (next, document) = history.redo(currentState().document()) ?: return
        history = next
        applyDocument(document)
        afterHistoryMove()
    }

    private fun afterHistoryMove() {
        val report = validateNewsDraftUseCase(currentState().toDesignDraft())
        updateState {
            it.copy(
                validationReport = report,
                isDirty = it.document() != baseline,
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                actionState = UiAsync.Idle,
                actionKind = null,
            )
        }
        persistSnapshot()
        publishLibrary()
    }

    /**
     * La unica puerta de mutacion del documento. Ignora la edicion si la ficha no es editable (publicada o
     * archivada, o sin ficha leida), registra el «antes» en el historial y recalcula validacion y suciedad.
     */
    private fun commit(group: NewsEditGroup, transform: (NewsState) -> NewsState) {
        val before = currentState()
        if (!before.isEditable()) return
        val next = transform(before).withClampedLayers()
        if (next.document() == before.document()) {
            updateState { next }
            return
        }
        // Una accion de un clic es su propio paso de deshacer aunque se repita; escribir o arrastrar se funden en uno.
        history = history.record(before.document(), group).let { if (group.kind in DISCRETE_EDIT_KINDS) it.closeGroup() else it }
        val report = validateNewsDraftUseCase(next.toDesignDraft())
        // Las subidas se leen del estado MAS RECIENTE: su progreso llega desde el hilo HTTP y no debe perderse.
        updateState { latest ->
            next.copy(
                uploads = latest.uploads,
                validationReport = report,
                isDirty = next.document() != baseline,
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                actionState = UiAsync.Idle,
                actionKind = null,
            )
        }
        persistSnapshot()
        publishLibrary()
    }

    private fun validateCurrent() {
        val report = validateNewsDraftUseCase(currentState().toDesignDraft())
        updateState { it.copy(validationReport = report) }
    }

    // =================================================================================================================
    // Guardar y comandos de estado
    // =================================================================================================================

    private suspend fun save(): Boolean {
        val state = currentState()
        if (!state.isEditable()) {
            fail(NewsAction.SAVE, AppError.Validation("Una noticia publicada o archivada no se edita."))
            return false
        }
        state.uploads.values.firstOrNull { !it.failed }?.let {
            fail(NewsAction.SAVE, AppError.Validation("Espera a que termine la subida de «${it.fileName}»."))
            return false
        }
        state.uploads.values.firstOrNull { it.failed }?.let {
            fail(NewsAction.SAVE, AppError.Validation("La subida de «${it.fileName}» falló. Reintenta o quítala antes de guardar."))
            return false
        }
        begin(NewsAction.SAVE)
        val article = state.toArticle()
        return when (val result = saveNewsUseCase(article, createKey)) {
            is AppResult.Failure -> {
                fail(NewsAction.SAVE, result.error)
                // El alta anterior llego al servidor aunque su respuesta se perdiera: releer la biblioteca la ensena.
                if (result.error.isNewsAlreadyCreated()) refreshLibrary()
                false
            }
            is AppResult.Success -> {
                analyticsTracker.trackAction("news_save", mapOf("news_id" to result.value.id, "layers" to result.value.layers.size.toString()))
                if (article.id.isBlank()) createKey = randomUuid()
                applySaved(result.value, NewsAction.SAVE, "Guardado en el servidor.")
                true
            }
        }
    }

    private suspend fun publish() {
        if (!ensureSaved()) return
        begin(NewsAction.PUBLISH)
        when (val result = publishNewsUseCase(currentState().selectedId.orEmpty(), currentState().toDesignDraft())) {
            is AppResult.Failure -> fail(NewsAction.PUBLISH, result.error)
            is AppResult.Success -> {
                analyticsTracker.trackAction("news_publish", mapOf("news_id" to result.value.id))
                applySaved(result.value, NewsAction.PUBLISH, "Publicada.")
            }
        }
    }

    private suspend fun schedule(isoUtc: String) {
        val at = isoUtc.trim()
        if (at.isBlank()) {
            fail(NewsAction.SCHEDULE, AppError.Validation("Añade una fecha de publicación antes de programar."))
            return
        }
        if (!at.matches(ISO_UTC)) {
            fail(NewsAction.SCHEDULE, AppError.Validation("Fecha inválida. Usa el formato 2026-05-11T09:00:00Z."))
            return
        }
        if (!ensureSaved()) return
        begin(NewsAction.SCHEDULE)
        when (val result = scheduleNewsUseCase(currentState().selectedId.orEmpty(), at, currentState().toDesignDraft())) {
            is AppResult.Failure -> fail(NewsAction.SCHEDULE, result.error)
            is AppResult.Success -> {
                analyticsTracker.trackAction("news_schedule", mapOf("news_id" to result.value.id))
                applySaved(result.value, NewsAction.SCHEDULE, "Programada.")
            }
        }
    }

    private suspend fun archive() {
        val id = currentState().selectedId
        if (id == null || id.isLocalNewsId()) {
            fail(NewsAction.ARCHIVE, AppError.Validation("Guarda la noticia antes de archivarla."))
            return
        }
        if (currentState().isDirty) {
            fail(NewsAction.ARCHIVE, AppError.Validation("Guarda o descarta los cambios antes de archivar."))
            return
        }
        begin(NewsAction.ARCHIVE)
        when (val result = archiveNewsUseCase(id)) {
            is AppResult.Failure -> fail(NewsAction.ARCHIVE, result.error)
            is AppResult.Success -> {
                analyticsTracker.trackAction("news_archive", mapOf("news_id" to result.value.id))
                applySaved(result.value, NewsAction.ARCHIVE, "Archivada.")
            }
        }
    }

    private suspend fun delete() {
        val id = currentState().selectedId ?: return
        if (id.isLocalNewsId()) {
            createNew()
            return
        }
        begin(NewsAction.DELETE)
        when (val result = deleteNewsUseCase(id)) {
            is AppResult.Failure -> fail(NewsAction.DELETE, result.error)
            is AppResult.Success -> {
                analyticsTracker.trackAction("news_delete", mapOf("news_id" to id))
                clearSnapshot()
                val next = summaries.firstOrNull { it.id != id }
                if (next != null) open(next.id) else createNew()
                updateState { it.copy(actionState = UiAsync.Success(Unit), actionKind = NewsAction.DELETE, actionMessage = "Noticia eliminada.") }
            }
        }
    }

    /** Un comando de estado sobre una ficha con cambios (o nueva) guarda primero. `false` si el guardado fallo. */
    private suspend fun ensureSaved(): Boolean {
        val state = currentState()
        return if (state.selectedId.isLocalNewsId() || state.isDirty) save() else true
    }

    private fun begin(action: NewsAction) {
        updateState { it.copy(actionState = UiAsync.Loading, actionKind = action, actionConflict = false, confirmIntent = null) }
    }

    private fun fail(action: NewsAction, error: AppError) {
        updateState { it.copy(actionState = UiAsync.Error(error), actionKind = action, actionConflict = error.isNewsConflict()) }
    }

    private fun applySaved(article: NewsArticle, action: NewsAction, message: String) {
        val keepSelection = currentState().selectedLayerId
        val tab = currentState().editorTab
        applyArticle(article, resetSelection = false)
        clearSnapshot()
        updateState {
            it.copy(
                selectedLayerId = keepSelection?.takeIf { id -> it.editorLayers.any { layer -> layer.id == id } },
                editorTab = tab,
                actionState = UiAsync.Success(Unit),
                actionKind = action,
                actionMessage = message,
                recoveredDraft = false,
            )
        }
        publishLibrary()
    }

    // =================================================================================================================
    // Subidas
    // =================================================================================================================

    private fun startUpload(target: String, uri: String) {
        if (!currentState().isEditable()) return
        val owner = currentState().selectedId
        val fileName = newsAssetName(uri)
        updateState { it.copy(uploads = it.uploads + (target to NewsUploadState(target, uri, fileName))) }
        // La ficha puede cambiar mientras sube: progreso, fallo y resultado son solo de la noticia y del fichero que lo pidieron.
        fun NewsState.ownUpload(): NewsUploadState? = uploads[target]?.takeIf { selectedId == owner && it.uri == uri }
        scope.launch {
            val key = randomUuid()
            val result = uploadNewsAssetUseCase(uri, key) { fraction ->
                updateState { state ->
                    val current = state.ownUpload() ?: return@updateState state
                    state.copy(uploads = state.uploads + (target to current.copy(progress = fraction)))
                }
            }
            when (result) {
                is AppResult.Failure -> updateState { state ->
                    val current = state.ownUpload() ?: return@updateState state
                    state.copy(uploads = state.uploads + (target to current.copy(error = result.error.newsUploadMessage())))
                }
                is AppResult.Success -> {
                    val upload = result.value
                    rememberAsset(upload.path, upload.publicUrl)
                    if (currentState().ownUpload() == null) return@launch
                    updateState { it.copy(uploads = it.uploads - target) }
                    when {
                        target == NEWS_UPLOAD_BACKGROUND -> commit(NewsEditGroup("background")) {
                            it.copy(
                                editorBackgroundPath = upload.path,
                                editorBackgroundUrl = upload.publicUrl,
                                editorBackgroundAssetStatus = "uploaded",
                            )
                        }
                        target == NEWS_UPLOAD_IMAGE -> commit(NewsEditGroup("image")) {
                            it.copy(editorImagePath = upload.path, editorImageUrl = upload.publicUrl, editorImageAssetStatus = "uploaded")
                        }
                        target.startsWith("layer:") -> {
                            val layerId = target.removePrefix("layer:")
                            updateLayer(layerId, NewsEditGroup("layerImage", layerId)) { layer, _, _ -> layer.copy(content = upload.publicUrl.ifBlank { upload.path }) }
                        }
                    }
                }
            }
        }
    }

    private fun rememberAsset(path: String?, url: String?) {
        if (path.isNullOrBlank() || url.isNullOrBlank()) return
        assetUrls[path] = url
        // El servidor compone la URL como <base publica del bucket> + ruta: con un par conocido se resuelve cualquier otra.
        if (url.endsWith(path)) assetBaseUrl = url.removeSuffix(path)
    }

    /** URL para pintar una ruta de asset, o vacio si esta sesion no puede saberla (la ficha lo dice: «sin vista previa»). */
    private fun assetUrl(path: String): String = when {
        path.isBlank() -> ""
        path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true) -> path
        else -> assetUrls[path] ?: assetBaseUrl?.let { it + path }.orEmpty()
    }

    // =================================================================================================================
    // Capas
    // =================================================================================================================

    private fun selectLayer(id: String) {
        if (currentState().editorLayers.none { it.id == id }) return
        history = history.closeGroup()
        updateState { it.copy(selectedTarget = NewsEditorSelection.LAYER, selectedLayerId = id) }
    }

    private fun selectAdjacentLayer(backwards: Boolean) {
        val layers = currentState().editorLayers.sortedByDescending { it.sortOrder }
        if (layers.isEmpty()) return
        val index = layers.indexOfFirst { it.id == currentState().selectedLayerId }
        val next = when {
            index < 0 -> 0
            backwards -> (index - 1 + layers.size) % layers.size
            else -> (index + 1) % layers.size
        }
        selectLayer(layers[next].id)
    }

    private fun addTextLayer() {
        val layers = currentState().editorLayers
        // Cada capa nueva nace un poco mas abajo que la anterior para no taparla, sin salirse del lienzo.
        val layer = NewsLayerDraft(
            randomUuid(), "Texto ${layers.size + 1}", "texto", "",
            x = 64, y = (240 + layers.size * 24).coerceAtMost(NEWS_CANVAS_DEFAULT_HEIGHT - 112), width = 640, height = 112,
            opacity = 100, visible = true, sortOrder = layers.size,
        )
        commit(NewsEditGroup("add", layer.id)) {
            it.copy(editorLayers = (layers + layer).normalizedLayerOrder(), selectedTarget = NewsEditorSelection.LAYER, selectedLayerId = layer.id)
        }
    }

    private fun addTitleLayer() {
        commit(NewsEditGroup("addTitle")) { state ->
            val layers = state.editorLayers.withSingleTitleLayer(state.editorTitle, state.editorCanvasWidth, state.editorCanvasHeight)
            state.copy(
                editorLayers = layers,
                selectedTarget = NewsEditorSelection.LAYER,
                selectedLayerId = layers.firstOrNull { it.kind.toLayerKind() == NewsLayerKind.TITLE }?.id,
            )
        }
    }

    /** Capa de imagen: nace al fondo con el contenido vacio y recibe la URL publica cuando termina la subida. */
    private fun addImageLayer(uri: String) {
        if (!currentState().isEditable()) return
        val layers = currentState().editorLayers
        val content = if (uri.isLocalAssetPath()) "" else uri
        val layer = NewsLayerDraft(
            randomUuid(), "Imagen ${layers.count { it.kind.toLayerKind() == NewsLayerKind.IMAGE } + 1}", "imagen", content,
            0, 0, NEWS_CANVAS_DEFAULT_WIDTH, NEWS_CANVAS_DEFAULT_HEIGHT, 86, visible = true, sortOrder = 0,
        )
        commit(NewsEditGroup("add", layer.id)) {
            it.copy(
                editorLayers = (listOf(layer) + layers.map { existing -> existing.copy(sortOrder = existing.sortOrder + 1) }).normalizedLayerOrder(),
                selectedTarget = NewsEditorSelection.LAYER,
                selectedLayerId = layer.id,
            )
        }
        if (uri.isLocalAssetPath()) startUpload(newsLayerUploadTarget(layer.id), uri)
    }

    private fun removeLayer(id: String) {
        if (currentState().editorLayers.firstOrNull { it.id == id }?.locked == true) return
        commit(NewsEditGroup("remove", id)) { state ->
            val layers = state.editorLayers.filterNot { it.id == id }.normalizedLayerOrder()
            state.copy(
                editorLayers = layers,
                selectedLayerId = if (state.selectedLayerId == id) null else state.selectedLayerId,
                selectedTarget = if (state.selectedLayerId == id) NewsEditorSelection.ARTICLE else state.selectedTarget,
                uploads = state.uploads - newsLayerUploadTarget(id),
            )
        }
    }

    private fun moveLayer(id: String, direction: Int) {
        val layers = currentState().editorLayers.sortedBy { it.sortOrder }.toMutableList()
        val index = layers.indexOfFirst { it.id == id }
        if (index < 0 || layers.size < 2 || layers[index].locked) return
        val target = (index + direction).coerceIn(0, layers.lastIndex)
        if (target == index) return
        val item = layers.removeAt(index)
        layers.add(target, item)
        commit(NewsEditGroup("order", id)) {
            it.copy(editorLayers = layers.reindexedByPosition(), selectedTarget = NewsEditorSelection.LAYER, selectedLayerId = id)
        }
    }

    private fun moveLayerToEdge(id: String, toFront: Boolean) {
        val layers = currentState().editorLayers.sortedBy { it.sortOrder }.toMutableList()
        val index = layers.indexOfFirst { it.id == id }
        if (index < 0 || layers.size < 2 || layers[index].locked) return
        val item = layers.removeAt(index)
        if (toFront) layers.add(item) else layers.add(0, item)
        commit(NewsEditGroup("order", id)) {
            it.copy(editorLayers = layers.reindexedByPosition(), selectedTarget = NewsEditorSelection.LAYER, selectedLayerId = id)
        }
    }

    private fun duplicateLayer(id: String) {
        val layers = currentState().editorLayers.sortedBy { it.sortOrder }
        val source = layers.firstOrNull { it.id == id } ?: return
        val copy = source.copy(id = randomUuid(), name = "${source.name} copia", x = source.x + 12, y = source.y + 12, locked = false, sortOrder = layers.size)
        commit(NewsEditGroup("add", copy.id)) {
            it.copy(editorLayers = (layers + copy).normalizedLayerOrder(), selectedTarget = NewsEditorSelection.LAYER, selectedLayerId = copy.id)
        }
    }

    private fun updateLayer(
        id: String,
        group: NewsEditGroup,
        allowLocked: Boolean = false,
        transform: (layer: NewsLayerDraft, canvasWidth: Int, canvasHeight: Int) -> NewsLayerDraft,
    ) {
        val state = currentState()
        val layer = state.editorLayers.firstOrNull { it.id == id } ?: return
        if (layer.locked && !allowLocked) return
        commit(group) { current ->
            current.copy(
                editorLayers = current.editorLayers.map { candidate ->
                    if (candidate.id == id) transform(candidate, current.editorCanvasWidth, current.editorCanvasHeight).coerceInsideCanvas(current.editorCanvasWidth, current.editorCanvasHeight) else candidate
                }.normalizedLayerOrder(),
                selectedTarget = NewsEditorSelection.LAYER,
                selectedLayerId = id,
            )
        }
    }

    // =================================================================================================================
    // Borrador local (red de seguridad)
    // =================================================================================================================

    private fun restoreDraftIfPresent() {
        val snapshot = runCatching { draftLocalDataSource.readDraft() }.getOrNull() ?: return
        if (snapshot.newsId.isBlank() || snapshot.newsId.isLocalNewsId()) {
            createNew()
            updateState { it.copy(selectedId = snapshot.newsId.ifBlank { NEWS_LOCAL_ID_PREFIX + randomUuid() }) }
            applyRecovered(snapshot)
        } else {
            // La ficha hay que leerla del servidor (version, URL) y poner encima lo recuperado.
            pendingSnapshot = snapshot
            scope.launch { open(snapshot.newsId) }
        }
    }

    private fun applyRecovered(snapshot: NewsEditorDraftSnapshot) {
        val document = NewsDocument(
            title = snapshot.title,
            text = snapshot.textContent,
            specialty = snapshot.specialty,
            type = snapshot.type,
            backgroundType = snapshot.backgroundType.toNewsBackgroundStyle(),
            backgroundPath = snapshot.backgroundPath,
            imagePath = snapshot.imagePath,
            canvasWidth = snapshot.canvasWidth.takeIf { it > 0 } ?: NEWS_CANVAS_DEFAULT_WIDTH,
            canvasHeight = snapshot.canvasHeight.takeIf { it > 0 } ?: NEWS_CANVAS_DEFAULT_HEIGHT,
            layers = snapshot.layersJson.decodeLayersFromJson(),
        )
        if (document == currentState().document()) return
        val before = currentState().document()
        history = history.record(before, NewsEditGroup("recovered"))
        applyDocument(document)
        val report = validateNewsDraftUseCase(currentState().toDesignDraft())
        updateState { it.copy(validationReport = report, isDirty = it.document() != baseline, canUndo = history.canUndo, canRedo = false, recoveredDraft = true) }
        publishLibrary()
    }

    private fun persistSnapshot() {
        val state = currentState()
        if (!state.isDirty) {
            clearSnapshot()
            return
        }
        runCatching {
            draftLocalDataSource.saveDraft(
                NewsEditorDraftSnapshot(
                    newsId = state.selectedId.orEmpty(),
                    title = state.editorTitle,
                    textContent = state.editorText,
                    specialty = state.editorSpecialty,
                    type = state.editorType,
                    backgroundType = state.editorBackgroundType,
                    backgroundPath = state.editorBackgroundPath,
                    imagePath = state.editorImagePath,
                    status = state.editorStatus,
                    layersJson = state.editorLayers.encodeToJsonString(),
                    canvasWidth = state.editorCanvasWidth,
                    canvasHeight = state.editorCanvasHeight,
                ),
            )
        }
    }

    private fun clearSnapshot() {
        runCatching { draftLocalDataSource.clearDraft() }
    }

    private fun applyDocument(document: NewsDocument) {
        updateState { state ->
            val layers = document.layers.map { it.coerceInsideCanvas(document.canvasWidth, document.canvasHeight) }.normalizedLayerOrder()
            state.copy(
                editorTitle = document.title,
                editorText = document.text,
                editorSpecialty = document.specialty,
                editorType = document.type,
                editorBackgroundType = document.backgroundType,
                editorBackgroundPath = document.backgroundPath,
                editorBackgroundUrl = if (document.backgroundPath == state.editorBackgroundPath) state.editorBackgroundUrl else assetUrl(document.backgroundPath),
                editorImagePath = document.imagePath,
                editorImageUrl = if (document.imagePath == state.editorImagePath) state.editorImageUrl else assetUrl(document.imagePath),
                editorCanvasWidth = document.canvasWidth,
                editorCanvasHeight = document.canvasHeight,
                editorLayers = layers,
                selectedLayerId = state.selectedLayerId?.takeIf { id -> layers.any { it.id == id } },
                selectedTarget = if (state.selectedLayerId != null && layers.none { it.id == state.selectedLayerId }) NewsEditorSelection.ARTICLE else state.selectedTarget,
            )
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 300L

        /** Grupos de edicion que son UNA accion (un clic, una eleccion, una subida) y no una racha de pulsaciones. */
        val DISCRETE_EDIT_KINDS = setOf(
            "specialty", "type", "backgroundType", "backgroundPath", "imagePath", "background", "image", "layerImage",
            "align", "order", "visible", "locked", "kind", "font", "fontWeight", "add", "addTitle", "remove",
        )
        val ISO_UTC = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""")
    }
}

// =====================================================================================================================
// Documento <-> estado
// =====================================================================================================================

internal fun NewsState.document(): NewsDocument = NewsDocument(
    title = editorTitle,
    text = editorText,
    specialty = editorSpecialty,
    type = editorType,
    backgroundType = editorBackgroundType,
    backgroundPath = editorBackgroundPath,
    imagePath = editorImagePath,
    canvasWidth = editorCanvasWidth,
    canvasHeight = editorCanvasHeight,
    layers = editorLayers.normalizedLayerOrder(),
)

/** Editable: hay ficha leida (o nueva) y su estado admite cambios de contenido (D4-04: borrador o programada). */
internal fun NewsState.isEditable(): Boolean =
    selectedId != null && recordState is UiAsync.Success && editorStatus.lowercase() !in setOf("published", "archived")

internal fun NewsState.listFilter(): NewsListFilter = NewsListFilter(
    status = statusFilter.takeUnless { it == "all" || it.isBlank() },
    specialtyId = specialtyFilter.takeUnless { it == "all" || it.isBlank() },
    typeId = typeFilter.takeUnless { it == "all" || it.isBlank() },
    query = searchQuery.trim().takeIf { it.isNotBlank() },
)

internal fun NewsState.toArticle(): NewsArticle = NewsArticle(
    id = selectedId?.takeUnless { it.isLocalNewsId() }.orEmpty(),
    title = editorTitle,
    specialty = editorSpecialty,
    type = editorType,
    backgroundType = editorBackgroundType,
    backgroundPath = editorBackgroundPath.ifBlank { null },
    status = editorStatus,
    publishFrom = editorPublishFrom,
    text = editorText,
    imagePath = editorImagePath.ifBlank { null },
    layers = editorLayers.normalizedLayerOrder().map { it.toDomain() },
    version = editorVersion,
    publishedAt = editorPublishedAt.ifBlank { null },
    backgroundUrl = editorBackgroundUrl.ifBlank { null },
    imageUrl = editorImageUrl.ifBlank { null },
)

internal fun NewsState.toItem(): NewsItem = NewsItem(
    id = selectedId.orEmpty(),
    title = editorTitle,
    specialty = editorSpecialty,
    type = editorType,
    backgroundType = editorBackgroundType,
    backgroundPath = editorBackgroundPath.ifBlank { null },
    status = editorStatus,
    publishFrom = editorPublishFrom,
    text = editorText,
    imagePath = editorImagePath.ifBlank { null },
    layers = editorLayers.normalizedLayerOrder(),
    isLocalDraft = selectedId.isLocalNewsId(),
    imageUrl = editorImageUrl.ifBlank { null },
    publishedAt = editorPublishedAt.ifBlank { null },
    updatedAt = editorVersion,
)

internal fun NewsSummary.toItem(): NewsItem = NewsItem(
    id = id,
    title = title,
    specialty = specialtyId,
    type = typeId,
    backgroundType = "",
    status = status,
    publishFrom = publishFrom.orEmpty(),
    text = "",
    imageUrl = imageUrl,
    publishedAt = publishedAt,
    updatedAt = updatedAt,
)

internal fun NewsState.toDesignDraft() = NewsDesignDraft(
    article = NewsArticleDraft(
        id = selectedId.orEmpty(),
        title = editorTitle,
        specialty = editorSpecialty,
        type = editorType.toArticleType(),
        status = editorStatus.toEditorialStatus(),
        publishFrom = editorPublishFrom,
        text = editorText,
    ),
    canvas = NewsCanvasDraft(
        width = editorCanvasWidth,
        height = editorCanvasHeight,
        backgroundStyle = editorBackgroundType.toNewsBackgroundStyle(),
        background = editorBackgroundPath.ifBlank { null }?.let { NewsAssetDraft(path = it, status = editorBackgroundAssetStatus.toAssetStatus()) },
        imageAsset = editorImagePath.ifBlank { null }?.let { NewsAssetDraft(path = it, status = editorImageAssetStatus.toAssetStatus()) },
        layers = editorLayers.normalizedLayerOrder().map { it.toCanvasLayer() },
    ),
)

internal fun NewsState.withClampedLayers(): NewsState =
    copy(editorLayers = editorLayers.map { it.coerceInsideCanvas(editorCanvasWidth, editorCanvasHeight) }.normalizedLayerOrder())

/**
 * Composicion de partida, en px del lienzo (4 por dp de movil): margen de 16 dp y alto para dos lineas de titulo
 * (2 x 72 x 1,2 + margenes), una de subtitulo y tres de texto, todo dentro de 592.
 *
 * El contenido nace VACIO. El lienzo pinta el nombre de la capa como guia mientras se edita, pero un texto de relleno
 * guardado en `capas` («Especialidad - Tipo») acabaria publicado tal cual en el banner de OposiBot.
 */
private fun defaultLayers(title: String): List<NewsLayerDraft> = listOf(
    NewsLayerDraft(randomUuid(), "Título", "titulo", title, 64, 48, 900, 216, 100, visible = true, sortOrder = 0),
    NewsLayerDraft(randomUuid(), "Subtítulo", "texto", "", 64, 276, 800, 92, 92, visible = true, sortOrder = 1),
    NewsLayerDraft(randomUuid(), "Texto", "texto", "", 64, 380, 900, 180, 88, visible = true, sortOrder = 2),
)

internal fun List<NewsLayerDraft>.normalizedLayerOrder(): List<NewsLayerDraft> =
    sortedBy { it.sortOrder }.mapIndexed { index, layer -> layer.copy(sortOrder = index) }

/**
 * El orden de la LISTA manda. Reordenar y despues [normalizedLayerOrder] volvia a ordenar por el `sortOrder` viejo
 * y deshacia el movimiento: «Adelante» y «Al fondo» no cambiaban nada (defecto heredado del panel anterior).
 */
private fun List<NewsLayerDraft>.reindexedByPosition(): List<NewsLayerDraft> =
    mapIndexed { index, layer -> layer.copy(sortOrder = index) }

/**
 * El titulo de la ficha alimenta la capa de titulo MIENTRAS digan lo mismo. Si la capa se reescribio en Diseño (un
 * titular mas corto para el banner), escribir en la ficha ya no la pisa.
 */
private fun List<NewsLayerDraft>.withTitleContent(previous: String, title: String): List<NewsLayerDraft> =
    map { if (it.kind.toLayerKind() == NewsLayerKind.TITLE && it.content == previous) it.copy(content = title) else it }

private fun List<NewsLayerDraft>.withSingleTitleLayer(title: String, canvasWidth: Int, canvasHeight: Int): List<NewsLayerDraft> {
    val titleLayer = firstOrNull { it.kind.toLayerKind() == NewsLayerKind.TITLE }
    val nonTitle = filterNot { it.kind.toLayerKind() == NewsLayerKind.TITLE }
    val normalizedTitle = (
        titleLayer ?: NewsLayerDraft(
            id = randomUuid(), name = "Título", kind = "titulo", content = title,
            x = 64, y = 48, width = 900, height = 216, opacity = 100, fontFamily = "sans", textColor = "#FFFFFF",
            visible = true, sortOrder = nonTitle.size,
        )
        ).copy(
        name = titleLayer?.name?.ifBlank { "Título" } ?: "Título",
        kind = "titulo",
        // Una capa de titulo que ya dice algo conserva su texto: «Titulo» la recoloca, no la reescribe.
        content = titleLayer?.content?.takeIf { it.isNotBlank() } ?: title,
        visible = true,
        sortOrder = nonTitle.size,
    )
    return (nonTitle + normalizedTitle).map { it.coerceInsideCanvas(canvasWidth, canvasHeight) }.normalizedLayerOrder()
}

internal fun NewsLayerDraft.frame(): NewsFrame = NewsFrame(x, y, width, height)

internal fun NewsLayerDraft.withFrame(frame: NewsFrame): NewsLayerDraft = copy(x = frame.x, y = frame.y, width = frame.width, height = frame.height)

internal fun NewsLayerDraft.coerceInsideCanvas(canvasWidth: Int, canvasHeight: Int): NewsLayerDraft =
    withFrame(clampFrame(frame(), canvasWidth, canvasHeight, NEWS_LAYER_MIN_SIDE))

private fun NewsLayerDraft.toCanvasLayer() = NewsCanvasLayer(
    id = id, name = name, kind = kind.toLayerKind(), content = content, x = x, y = y, width = width, height = height,
    opacity = opacity, fontFamily = fontFamily, textColor = textColor, visible = visible, locked = locked,
    sortOrder = sortOrder, rotationDegrees = rotationDegrees.normalizedDegrees(),
)

private fun String.toEditorialStatus(): NewsEditorialStatus = when (trim().lowercase()) {
    "scheduled", "programada", "programado" -> NewsEditorialStatus.SCHEDULED
    "published", "publicada", "publicado" -> NewsEditorialStatus.PUBLISHED
    "archived", "archivada", "archivado" -> NewsEditorialStatus.ARCHIVED
    else -> NewsEditorialStatus.DRAFT
}

private fun String.toArticleType(): NewsArticleType = when (trim().lowercase()) {
    "aviso" -> NewsArticleType.AVISO
    "oferta" -> NewsArticleType.OFERTA
    "recurso" -> NewsArticleType.RECURSO
    else -> NewsArticleType.CONVOCATORIA
}

internal fun String.toNewsBackgroundStyle(): String = when (trim().uppercase()) {
    "B", "MINT" -> "MINT"
    "C", "GOLD" -> "GOLD"
    else -> "SKY"
}

internal fun String.toLayerKind(): NewsLayerKind = when (trim().lowercase()) {
    "titulo", "title" -> NewsLayerKind.TITLE
    "imagen", "image" -> NewsLayerKind.IMAGE
    "badge", "etiqueta" -> NewsLayerKind.BADGE
    "cta", "boton", "button" -> NewsLayerKind.CTA
    "background", "fondo" -> NewsLayerKind.BACKGROUND
    else -> NewsLayerKind.TEXT
}

private fun String.toAssetStatus(): NewsAssetStatus = when (trim().lowercase()) {
    "uploading", "subiendo" -> NewsAssetStatus.UPLOADING
    "uploaded", "subido" -> NewsAssetStatus.UPLOADED
    "linked", "enlazado" -> NewsAssetStatus.LINKED
    "error" -> NewsAssetStatus.ERROR
    else -> NewsAssetStatus.LOCAL
}

private fun NewsLayer.toUi() = NewsLayerDraft(
    id.ifBlank { randomUuid() }, name, kind, content, x, y, width, height, opacity, fontFamily, textColor, visible, locked, sortOrder,
    rotationDegrees.normalizedDegrees(),
    fontSizeSp = fontSizeSp,
    fontWeight = fontWeight.toKnownFontWeight(""),
)

private fun NewsLayerDraft.toDomain() = NewsLayer(
    id, name, kind, content, x, y, width, height, opacity, fontFamily, textColor, visible, sortOrder, locked,
    rotationDegrees.normalizedDegrees(),
    fontSizeSp = fontSizeSp,
    fontWeight = fontWeight,
)

private fun String.toPositiveInt(fallback: Int): Int = toIntOrNull()?.coerceAtLeast(1) ?: fallback

private fun String.toBoundedInt(fallback: Int, min: Int, max: Int): Int = toIntOrNull()?.coerceIn(min, max) ?: fallback

private fun String.toKnownFontWeight(fallback: String): String {
    val normalized = trim().lowercase()
    return if (normalized in NewsLayerFontWeights) normalized else fallback
}

private fun Int.normalizedDegrees(): Int {
    val mod = this % 360
    return if (mod < 0) mod + 360 else mod
}

internal fun String.isLocalAssetPath(): Boolean =
    startsWith("content://", ignoreCase = true) || startsWith("file://", ignoreCase = true)

/**
 * Un rechazo que exige RELEER la ficha: la version cambio, otro editor la publico o archivo, o el servidor la
 * borro. Se reconoce por el codigo de dominio que viaja en `AppError.Validation.code` o por un 404 al escribir.
 */
/** El servidor ya tiene una noticia creada con esta clave de alta y el cuerpo ha cambiado desde entonces. */
internal fun AppError.isNewsAlreadyCreated(): Boolean =
    this is AppError.Validation && code?.lowercase() == "idempotency_key_reused"

internal fun AppError.isNewsConflict(): Boolean = when (this) {
    is AppError.Validation -> code?.lowercase() in setOf("precondition_failed", "news_not_editable", "invalid_transition", "conflict", "version_conflict")
    is AppError.NotFound -> true
    else -> false
}
