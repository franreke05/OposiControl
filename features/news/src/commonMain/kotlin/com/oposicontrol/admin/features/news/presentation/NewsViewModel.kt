package com.oposicontrol.admin.features.news.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.id.randomUuid
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.data.local.NewsEditorDraftLocalDataSource
import com.oposicontrol.admin.data.local.NewsEditorDraftSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
import com.oposicontrol.admin.domain.usecase.news.ArchiveNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.DeleteNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.ObserveNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.PublishNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.RefreshNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.ScheduleNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.UploadNewsAssetUseCase
import com.oposicontrol.admin.domain.usecase.news.UpsertNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.ValidateNewsDraftUseCase
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val NewsCanvasDefaultWidth = 800
private const val NewsCanvasDefaultHeight = 500
private const val NewsLayerMinWidth = 24
private const val NewsLayerMinHeight = 24

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
)

private fun NewsLayerDraft.toJson() = NewsLayerDraftJson(
    id = id, name = name, kind = kind, content = content,
    x = x, y = y, width = width, height = height, opacity = opacity,
    fontFamily = fontFamily, textColor = textColor, visible = visible,
    locked = locked, sortOrder = sortOrder, rotationDegrees = rotationDegrees,
)

private fun NewsLayerDraftJson.toDraft() = NewsLayerDraft(
    id = id, name = name, kind = kind, content = content,
    x = x, y = y, width = width, height = height, opacity = opacity,
    fontFamily = fontFamily, textColor = textColor, visible = visible,
    locked = locked, sortOrder = sortOrder, rotationDegrees = rotationDegrees,
)

private fun List<NewsLayerDraft>.encodeToJsonString(): String =
    runCatching { draftJson.encodeToString(map { it.toJson() }) }.getOrElse { "[]" }

private fun String.decodeLayersFromJson(): List<NewsLayerDraft> =
    runCatching { draftJson.decodeFromString<List<NewsLayerDraftJson>>(this).map { it.toDraft() } }.getOrElse { emptyList() }

class NewsViewModel(
    dispatcherProvider: DispatcherProvider,
    observeNewsUseCase: ObserveNewsUseCase,
    private val refreshNewsUseCase: RefreshNewsUseCase,
    private val upsertNewsUseCase: UpsertNewsUseCase,
    private val deleteNewsUseCase: DeleteNewsUseCase,
    private val validateNewsDraftUseCase: ValidateNewsDraftUseCase,
    private val publishNewsUseCase: PublishNewsUseCase,
    private val scheduleNewsUseCase: ScheduleNewsUseCase,
    private val uploadNewsAssetUseCase: UploadNewsAssetUseCase,
    private val archiveNewsUseCase: ArchiveNewsUseCase,
    private val analyticsTracker: AnalyticsTracker,
    private val draftLocalDataSource: NewsEditorDraftLocalDataSource,
) : MviViewModel<NewsState, NewsIntent>(
    initialState = NewsState(),
    dispatcherProvider = dispatcherProvider,
) {
    private var allNews: List<NewsItem> = emptyList()
    private var remoteNews: List<NewsItem> = emptyList()
    private val localDrafts = linkedMapOf<String, NewsItem>()
    private var isEditingLocalDraft = false

    init {
        restoreDraftIfPresent()
        scope.launch {
            observeNewsUseCase().collectLatest { articles ->
                remoteNews = articles.map { it.toUi() }
                rebuildNewsFromLocalAndRemote()
                publish()
                if (currentState().selectedId == null && !isEditingLocalDraft) allNews.firstOrNull()?.let { select(it.id) }
            }
        }
        scope.launch { refreshNewsUseCase() }
        analyticsTracker.trackScreen("news")
    }

    private fun restoreDraftIfPresent() {
        val snapshot = runCatching { draftLocalDataSource.readDraft() }.getOrNull() ?: return
        val restoredId = snapshot.newsId.ifBlank { "local-${randomUuid()}" }
        val layers = snapshot.layersJson.decodeLayersFromJson()
        updateState {
            it.copy(
                selectedId = restoredId,
                editorTitle = snapshot.title,
                editorText = snapshot.textContent,
                editorSpecialty = snapshot.specialty,
                editorType = snapshot.type,
                editorBackgroundType = snapshot.backgroundType,
                editorBackgroundPath = snapshot.backgroundPath,
                editorImagePath = snapshot.imagePath,
                editorStatus = snapshot.status,
                editorLayers = layers,
                editorCanvasWidth = snapshot.canvasWidth,
                editorCanvasHeight = snapshot.canvasHeight,
            )
        }
        if (restoredId.startsWith("local-")) {
            val item = currentState().toNewsItem().copy(isLocalDraft = true)
            localDrafts[restoredId] = item
            isEditingLocalDraft = true
        }
    }

    override suspend fun handleIntent(intent: NewsIntent) {
        when (intent) {
            NewsIntent.Refresh -> refreshNewsUseCase()
            NewsIntent.CreateNew -> createNew()
            NewsIntent.SaveDraft -> saveDraft()
            is NewsIntent.SaveDraftFor -> {
                select(intent.id)
                saveDraft()
            }
            NewsIntent.Publish -> publishCurrent()
            is NewsIntent.PublishFor -> {
                select(intent.id)
                publishCurrent()
            }
            NewsIntent.Schedule -> scheduleCurrent()
            NewsIntent.Archive -> archiveCurrent()
            is NewsIntent.ArchiveFor -> {
                select(intent.id)
                archiveCurrent()
            }
            NewsIntent.ValidateDraft -> validateCurrentDraft()
            NewsIntent.DeleteSelected -> deleteSelected()
            NewsIntent.SelectBackground -> updateAndValidate { it.copy(selectedTarget = NewsEditorSelection.BACKGROUND, selectedLayerId = null) }
            NewsIntent.AddTitleLayer -> addTitleLayer()
            NewsIntent.AddLayer -> addLayer()
            is NewsIntent.AddLayerWithImage -> addLayerWithImage(intent.uri)
            is NewsIntent.Select -> select(intent.id)
            is NewsIntent.SelectBackgroundFile -> updateAndValidate {
                val titleLayers = it.editorLayers.withSingleTitleLayer(
                    title = it.editorTitle.ifBlank { "Titulo de noticia" },
                    canvasWidth = it.editorCanvasWidth,
                    canvasHeight = it.editorCanvasHeight,
                )
                it.copy(
                    editorBackgroundPath = intent.uri,
                    editorBackgroundAssetStatus = if (intent.uri.startsWith("http") || intent.uri.startsWith("news/")) "linked" else "local",
                    editorLayers = titleLayers,
                    selectedTarget = NewsEditorSelection.BACKGROUND,
                    selectedLayerId = titleLayers.firstOrNull { layer -> layer.kind.toLayerKind() == NewsLayerKind.TITLE }?.id,
                    actionState = UiAsync.Idle,
                )
            }
            is NewsIntent.SelectLayer -> updateState { it.copy(selectedTarget = NewsEditorSelection.LAYER, selectedLayerId = intent.id) }
            is NewsIntent.UpdateBackgroundType -> updateAndValidate { it.copy(editorBackgroundType = intent.value) }
            is NewsIntent.UpdateBackgroundPath -> updateAndValidate {
                val nextLayers = if (intent.value.isBlank()) {
                    it.editorLayers
                } else {
                    it.editorLayers.withSingleTitleLayer(
                        title = it.editorTitle.ifBlank { "Titulo de noticia" },
                        canvasWidth = it.editorCanvasWidth,
                        canvasHeight = it.editorCanvasHeight,
                    )
                }
                it.copy(
                    editorBackgroundPath = intent.value,
                    editorBackgroundAssetStatus = if (intent.value.isLocalAssetPath()) "local" else "linked",
                    editorLayers = nextLayers,
                )
            }
            is NewsIntent.UpdateImagePath -> updateAndValidate { it.copy(editorImagePath = intent.value) }
            is NewsIntent.RemoveLayer -> removeLayer(intent.id)
            is NewsIntent.MoveLayerDown -> moveLayer(intent.id, -1)
            is NewsIntent.MoveLayerUp -> moveLayer(intent.id, 1)
            is NewsIntent.BringLayerToFront -> moveLayerToEdge(intent.id, toFront = true)
            is NewsIntent.SendLayerToBack -> moveLayerToEdge(intent.id, toFront = false)
            is NewsIntent.CenterLayer -> centerLayer(intent.id)
            is NewsIntent.DuplicateLayer -> duplicateLayer(intent.id)
            is NewsIntent.ToggleLayerVisible -> updateLayer(intent.id) { layer -> layer.copy(visible = !layer.visible) }
            is NewsIntent.ToggleLayerLocked -> updateLayer(intent.id, allowLocked = true) { layer -> layer.copy(locked = !layer.locked) }
            is NewsIntent.UpdateLayerContent -> updateLayer(intent.id) { layer -> layer.copy(content = intent.value) }
            is NewsIntent.UpdateLayerHeight -> updateLayer(intent.id) { layer -> layer.copy(height = intent.value.toPositiveInt(layer.height)) }
            is NewsIntent.UpdateLayerFrame -> updateLayer(intent.id) { layer -> layer.copy(x = intent.x, y = intent.y, width = intent.width, height = intent.height) }
            is NewsIntent.UpdateLayerKind -> updateLayer(intent.id) { layer -> layer.copy(kind = intent.value) }
            is NewsIntent.UpdateLayerName -> updateLayer(intent.id) { layer -> layer.copy(name = intent.value) }
            is NewsIntent.UpdateLayerOpacity -> updateLayer(intent.id) { layer -> layer.copy(opacity = intent.value.toBoundedInt(layer.opacity, 0, 100)) }
            is NewsIntent.UpdateLayerRotation -> updateLayer(intent.id) { layer -> layer.copy(rotationDegrees = intent.degrees.normalizedDegrees()) }
            is NewsIntent.UpdateLayerWidth -> updateLayer(intent.id) { layer -> layer.copy(width = intent.value.toPositiveInt(layer.width)) }
            is NewsIntent.UpdateLayerFontFamily -> updateLayer(intent.id) { layer -> layer.copy(fontFamily = intent.value) }
            is NewsIntent.UpdateLayerTextColor -> updateLayer(intent.id) { layer -> layer.copy(textColor = intent.value) }
            is NewsIntent.UpdateLayerX -> updateLayer(intent.id) { layer -> layer.copy(x = intent.value.toIntOrNull() ?: layer.x) }
            is NewsIntent.UpdateLayerY -> updateLayer(intent.id) { layer -> layer.copy(y = intent.value.toIntOrNull() ?: layer.y) }
            is NewsIntent.UpdatePublishFrom -> updateAndValidate { it.copy(editorPublishFrom = intent.value) }
            is NewsIntent.UpdateCanvasWidth -> updateAndValidate {
                it.copy(editorCanvasWidth = intent.value.toPositiveInt(it.editorCanvasWidth)).withClampedLayers()
            }
            is NewsIntent.UpdateCanvasHeight -> updateAndValidate {
                it.copy(editorCanvasHeight = intent.value.toPositiveInt(it.editorCanvasHeight)).withClampedLayers()
            }
            is NewsIntent.UpdateBackgroundAssetStatus -> updateAndValidate { it.copy(editorBackgroundAssetStatus = intent.value) }
            is NewsIntent.UpdateImageAssetStatus -> updateAndValidate { it.copy(editorImageAssetStatus = intent.value) }
            NewsIntent.ToggleCanvasFullscreen -> updateState { it.copy(canvasFullscreen = !it.canvasFullscreen) }
            is NewsIntent.UpdateSearch -> {
                updateState { it.copy(searchQuery = intent.value) }
                publish()
            }
            is NewsIntent.UpdateSpecialty -> updateAndValidate { it.copy(editorSpecialty = intent.value) }
            is NewsIntent.UpdateSpecialtyFilter -> {
                updateState { it.copy(specialtyFilter = intent.value) }
                publish()
            }
            is NewsIntent.UpdateStatus -> updateAndValidate { it.copy(editorStatus = intent.value) }
            is NewsIntent.UpdateStatusFilter -> {
                updateState { it.copy(statusFilter = intent.value) }
                publish()
            }
            is NewsIntent.UpdateText -> updateAndValidate { it.copy(editorText = intent.value) }
            is NewsIntent.UpdateTitle -> updateAndValidate { state ->
                val hasTitleLayer = state.editorLayers.any { it.kind.toLayerKind() == NewsLayerKind.TITLE }
                val updatedLayers = when {
                    hasTitleLayer -> state.editorLayers.map { if (it.kind.toLayerKind() == NewsLayerKind.TITLE) it.copy(content = intent.value.ifBlank { it.content }) else it }
                    intent.value.isNotBlank() -> state.editorLayers.withSingleTitleLayer(intent.value, state.editorCanvasWidth, state.editorCanvasHeight)
                    else -> state.editorLayers
                }
                state.copy(editorTitle = intent.value, editorLayers = updatedLayers)
            }
            is NewsIntent.UpdateType -> updateAndValidate { it.copy(editorType = intent.value) }
            is NewsIntent.UpdateTypeFilter -> {
                updateState { it.copy(typeFilter = intent.value) }
                publish()
            }
        }
    }

    private fun createNew() {
        val localId = "local-${randomUuid()}"
        updateState { state ->
            state.copy(
                selectedId = localId,
                editorTitle = "",
                editorBackgroundType = "SKY",
                editorBackgroundPath = "",
                editorSpecialty = "1",
                editorType = "1",
                editorText = "",
                editorImagePath = "",
                editorStatus = "draft",
                editorPublishFrom = "",
                editorCanvasWidth = NewsCanvasDefaultWidth,
                editorCanvasHeight = NewsCanvasDefaultHeight,
                editorBackgroundAssetStatus = "linked",
                editorImageAssetStatus = "linked",
                editorLayers = defaultLayers("Titulo de noticia", "Especialidad - Tipo", ""),
                selectedTarget = NewsEditorSelection.ARTICLE,
                selectedLayerId = null,
                actionState = UiAsync.Idle,
            )
        }
        rememberCurrentDraft()
        validateCurrentDraft()
    }

    private fun select(id: String) {
        val item = allNews.firstOrNull { it.id == id } ?: return
        isEditingLocalDraft = false
        updateState {
            it.copy(
                selectedId = item.id,
                editorTitle = item.title,
                editorBackgroundType = item.backgroundType.toNewsBackgroundStyle(),
                editorBackgroundPath = item.backgroundPath.orEmpty(),
                editorSpecialty = item.specialty,
                editorType = item.type,
                editorText = item.text,
                editorImagePath = item.imagePath.orEmpty(),
                editorStatus = item.status,
                editorPublishFrom = item.publishFrom,
                editorCanvasWidth = NewsCanvasDefaultWidth,
                editorCanvasHeight = NewsCanvasDefaultHeight,
                editorBackgroundAssetStatus = if (item.backgroundPath.isNullOrBlank()) "local" else "linked",
                editorImageAssetStatus = if (item.imagePath.isNullOrBlank()) "linked" else "linked",
                editorLayers = item.layers
                    .ifEmpty { defaultLayers(item.title, "${item.specialty} - ${item.type}", item.text) }
                    .map { layer -> layer.coerceInsideCanvas(NewsCanvasDefaultWidth, NewsCanvasDefaultHeight) },
                selectedTarget = NewsEditorSelection.ARTICLE,
                selectedLayerId = null,
                actionState = UiAsync.Idle,
            )
        }
        validateCurrentDraft()
    }

    private fun publish() {
        val query = currentState().searchQuery.trim().lowercase()
        val statusFilter = currentState().statusFilter.trim().lowercase()
        val specialtyFilter = currentState().specialtyFilter.trim().lowercase()
        val typeFilter = currentState().typeFilter.trim().lowercase()
        val filtered = allNews.filter { item ->
            (query.isBlank() || item.title.lowercase().contains(query) || item.text.lowercase().contains(query)) &&
                (statusFilter == "all" || item.status.lowercase() == statusFilter) &&
                (specialtyFilter == "all" || item.specialty.lowercase() == specialtyFilter) &&
                (typeFilter == "all" || item.type.lowercase() == typeFilter)
        }
        val published = filtered.filter { it.isPublishedRemote() }
        val localOrDraft = filtered.filterNot { it.isPublishedRemote() }
        updateState {
            it.copy(
                news = if (filtered.isEmpty()) UiAsync.Empty else UiAsync.Success(filtered),
                publishedNews = if (published.isEmpty()) UiAsync.Empty else UiAsync.Success(published),
                localNews = if (localOrDraft.isEmpty()) UiAsync.Empty else UiAsync.Success(localOrDraft),
            )
        }
    }

    private suspend fun saveDraft() {
        ensureTitleLayerInState()
        val draft = currentState().toDesignDraft()
        val report = validateNewsDraftUseCase(draft)
        updateState { it.copy(validationReport = report) }
        if (!report.canSave) {
            updateState { it.copy(actionState = UiAsync.Error(com.oposicontrol.admin.core.common.result.AppError.Validation("Añade al menos un título para guardar."))) }
            return
        }
        rememberCurrentDraft()
        val draftId = currentState().selectedId.orEmpty()
        analyticsTracker.trackAction("news_save_draft", mapOf("news_id" to draftId, "layers" to currentState().editorLayers.size.toString()))
        updateState { it.copy(actionState = UiAsync.Success(Unit), isDirty = false) }
    }

    private suspend fun publishCurrent() {
        persistWithEditorialAction("news_publish") { article, draft -> publishNewsUseCase(article, draft) }
    }

    private suspend fun scheduleCurrent() {
        val pf = currentState().editorPublishFrom.trim()
        if (pf.isBlank()) {
            updateState { it.copy(actionState = UiAsync.Error(com.oposicontrol.admin.core.common.result.AppError.Validation("Añade una fecha de publicación antes de programar."))) }
            return
        }
        if (!pf.matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z"""))) {
            updateState { it.copy(actionState = UiAsync.Error(com.oposicontrol.admin.core.common.result.AppError.Validation("Fecha inválida. Usa formato ISO: 2026-05-11T09:00:00Z"))) }
            return
        }
        persistWithEditorialAction("news_schedule") { article, draft -> scheduleNewsUseCase(article, draft) }
    }

    private suspend fun archiveCurrent() {
        val previousId = currentState().selectedId
        if (previousId?.startsWith("local-") == true) {
            localDrafts.remove(previousId)
            rebuildNewsFromLocalAndRemote()
            createNew()
            updateState { it.copy(actionState = UiAsync.Success(Unit)) }
            return
        }
        val article = currentState().toNewsItem().toDomain()
        updateState { it.copy(actionState = UiAsync.Loading) }
        when (val result = archiveNewsUseCase(article)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("news_archive", mapOf("news_id" to result.value.id))
                previousId?.takeIf { it.startsWith("local-") }?.let { localDrafts.remove(it) }
                rememberSavedArticle(currentState().toNewsItem().copy(id = result.value.id, status = result.value.status))
                updateState { it.copy(selectedId = result.value.id, editorStatus = result.value.status, actionState = UiAsync.Success(Unit)) }
            }
        }
    }

    private suspend fun persistWithEditorialAction(
        actionName: String,
        persist: suspend (NewsArticle, NewsDesignDraft) -> AppResult<NewsArticle>,
    ) {
        ensureTitleLayerInState()
        updateState { it.copy(actionState = UiAsync.Loading) }
        val prepared = prepareAssetsForRemotePublish() ?: return
        val draft = prepared.toDesignDraft()
        val report = validateNewsDraftUseCase(draft)
        updateState { it.copy(validationReport = report) }
        val article = prepared.toNewsItem().copy(
            id = prepared.selectedId.orEmpty().takeUnless { it.startsWith("local-") }.orEmpty(),
            isLocalDraft = false,
        ).toDomain()
        val previousId = currentState().selectedId
        when (val result = persist(article, draft)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction(actionName, mapOf("news_id" to result.value.id, "layers" to result.value.layers.size.toString()))
                previousId?.takeIf { it.startsWith("local-") }?.let { localDrafts.remove(it) }
                rememberSavedArticle(currentState().toNewsItem().copy(id = result.value.id, status = result.value.status))
                updateState { it.copy(selectedId = result.value.id, editorStatus = result.value.status, actionState = UiAsync.Success(Unit), isDirty = false) }
            }
        }
    }

    private suspend fun deleteSelected() {
        val id = currentState().selectedId ?: return
        if (id.startsWith("local-")) {
            localDrafts.remove(id)
            rebuildNewsFromLocalAndRemote()
            createNew()
            updateState { it.copy(actionState = UiAsync.Success(Unit)) }
            return
        }
        updateState { it.copy(actionState = UiAsync.Loading) }
        when (val result = deleteNewsUseCase(id)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("news_delete", mapOf("news_id" to id))
                localDrafts.remove(id)
                rebuildNewsFromLocalAndRemote()
                createNew()
                updateState { it.copy(actionState = UiAsync.Success(Unit)) }
            }
        }
    }

    private fun addLayer() {
        val layers = currentState().editorLayers
        val layer = NewsLayerDraft(randomUuid(), "Texto ${layers.size + 1}", "texto", "", 48, 180 + (layers.size * 18), 280, 64, 100, visible = true, sortOrder = layers.size)
        updateAndValidate { it.copy(editorLayers = (layers + layer).normalizedLayerOrder(), selectedTarget = NewsEditorSelection.LAYER, selectedLayerId = layer.id) }
    }

    private fun addTitleLayer() {
        updateAndValidate { state ->
            val layers = state.editorLayers.withSingleTitleLayer(
                title = state.editorTitle.ifBlank { "Titulo de noticia" },
                canvasWidth = state.editorCanvasWidth,
                canvasHeight = state.editorCanvasHeight,
            )
            state.copy(
                editorLayers = layers,
                selectedTarget = NewsEditorSelection.LAYER,
                selectedLayerId = layers.firstOrNull { it.kind.toLayerKind() == NewsLayerKind.TITLE }?.id,
            )
        }
    }

    private fun addLayerWithImage(uri: String) {
        val layers = currentState().editorLayers
        val layer = NewsLayerDraft(randomUuid(), "Imagen fondo ${layers.count { it.kind.toLayerKind() == NewsLayerKind.IMAGE } + 1}", "imagen", uri, 0, 0, NewsCanvasDefaultWidth, NewsCanvasDefaultHeight, 86, visible = true, sortOrder = 0)
        updateAndValidate {
            it.copy(
                editorImagePath = uri,
                editorImageAssetStatus = if (uri.startsWith("http") || uri.startsWith("news/")) "linked" else "local",
                editorLayers = (listOf(layer) + layers.map { existing -> existing.copy(sortOrder = existing.sortOrder + 1) }).normalizedLayerOrder(),
                selectedTarget = NewsEditorSelection.LAYER,
                selectedLayerId = layer.id,
            )
        }
    }

    private fun removeLayer(id: String) {
        updateState { state ->
            val layers = state.editorLayers.filterNot { it.id == id }.normalizedLayerOrder()
            state.copy(editorLayers = layers, selectedLayerId = if (state.selectedLayerId == id) layers.firstOrNull()?.id else state.selectedLayerId)
        }
        rememberCurrentDraft()
        validateCurrentDraft()
    }

    private fun moveLayer(id: String, direction: Int) {
        val layers = currentState().editorLayers.sortedBy { it.sortOrder }.toMutableList()
        val index = layers.indexOfFirst { it.id == id }
        if (index < 0 || layers.size < 2) return
        val target = (index + direction).coerceIn(0, layers.lastIndex)
        if (target == index) return
        val item = layers.removeAt(index)
        layers.add(target, item)
        updateAndValidate {
            it.copy(
                editorLayers = layers.normalizedLayerOrder(),
                selectedTarget = NewsEditorSelection.LAYER,
                selectedLayerId = id,
            )
        }
    }

    private fun moveLayerToEdge(id: String, toFront: Boolean) {
        val layers = currentState().editorLayers.sortedBy { it.sortOrder }.toMutableList()
        val index = layers.indexOfFirst { it.id == id }
        if (index < 0 || layers.size < 2) return
        val item = layers.removeAt(index)
        if (toFront) {
            layers.add(item)
        } else {
            layers.add(0, item)
        }
        updateAndValidate {
            it.copy(
                editorLayers = layers.normalizedLayerOrder(),
                selectedTarget = NewsEditorSelection.LAYER,
                selectedLayerId = id,
            )
        }
    }

    private fun centerLayer(id: String) {
        updateLayer(id) { layer ->
            layer.copy(
                x = ((currentState().editorCanvasWidth - layer.width) / 2).coerceAtLeast(0),
                y = ((currentState().editorCanvasHeight - layer.height) / 2).coerceAtLeast(0),
            )
        }
    }

    private fun duplicateLayer(id: String) {
        val layers = currentState().editorLayers.sortedBy { it.sortOrder }
        val source = layers.firstOrNull { it.id == id } ?: return
        val copy = source.copy(
            id = randomUuid(),
            name = "${source.name} copia",
            x = source.x + 12,
            y = source.y + 12,
            locked = false,
            sortOrder = layers.size,
        )
        updateAndValidate { it.copy(editorLayers = (layers + copy).normalizedLayerOrder(), selectedTarget = NewsEditorSelection.LAYER, selectedLayerId = copy.id) }
    }

    private fun updateLayer(id: String, allowLocked: Boolean = false, transform: (NewsLayerDraft) -> NewsLayerDraft) {
        updateAndValidate { state ->
            state.copy(
                editorLayers = state.editorLayers.map { layer ->
                    if (layer.id == id && (!layer.locked || allowLocked)) {
                        transform(layer).coerceInsideCanvas(state.editorCanvasWidth, state.editorCanvasHeight)
                    } else {
                        layer
                    }
                }.normalizedLayerOrder(),
                selectedTarget = NewsEditorSelection.LAYER,
                selectedLayerId = id,
            )
        }
    }

    private fun updateAndValidate(transform: (NewsState) -> NewsState) {
        val next = transform(currentState()).withClampedLayers()
        val report = validateNewsDraftUseCase(next.toDesignDraft())
        updateState { next.copy(validationReport = report, isDirty = true) }
        rememberCurrentDraft(next.copy(validationReport = report))
    }

    private fun validateCurrentDraft() {
        val report = validateNewsDraftUseCase(currentState().toDesignDraft())
        updateState { it.copy(validationReport = report) }
    }

    private fun ensureTitleLayerInState() {
        val s = currentState()
        val titleLayer = s.editorLayers.firstOrNull { it.kind.toLayerKind() == NewsLayerKind.TITLE }
        when {
            s.editorTitle.isNotBlank() && titleLayer == null ->
                updateState { st -> st.copy(editorLayers = st.editorLayers.withSingleTitleLayer(st.editorTitle, st.editorCanvasWidth, st.editorCanvasHeight)) }
            s.editorTitle.isBlank() && titleLayer?.content?.isNotBlank() == true ->
                updateState { it.copy(editorTitle = titleLayer.content) }
        }
    }

    private fun rememberCurrentDraft(state: NewsState = currentState()) {
        val localId = state.selectedId?.ifBlank { null } ?: "local-${randomUuid()}"
        val item = state.copy(selectedId = localId).toNewsItem().copy(isLocalDraft = true)
        if (state.selectedId.isNullOrBlank()) updateState { it.copy(selectedId = localId) }
        localDrafts[item.id] = item
        isEditingLocalDraft = true
        rebuildNewsFromLocalAndRemote()
        publish()
        runCatching {
            draftLocalDataSource.saveDraft(
                NewsEditorDraftSnapshot(
                    newsId = localId,
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
                )
            )
        }
    }

    private fun rememberSavedArticle(item: NewsItem) {
        localDrafts.remove(item.id)
        remoteNews = listOf(item.copy(isLocalDraft = false)) + remoteNews.filterNot { it.id == item.id }
        isEditingLocalDraft = false
        runCatching { draftLocalDataSource.clearDraft() }
        rebuildNewsFromLocalAndRemote()
        publish()
    }

    private fun rebuildNewsFromLocalAndRemote() {
        val patched = remoteNews.map { remote -> localDrafts[remote.id] ?: remote.copy(isLocalDraft = false) }
        val localOnly = localDrafts.values.filter { draft -> patched.none { it.id == draft.id } }
        allNews = localOnly.map { it.copy(isLocalDraft = true) } + patched
    }

    private suspend fun prepareAssetsForRemotePublish(): NewsState? {
        var prepared = currentState()
        val replacements = linkedMapOf<String, String>()
        suspend fun uploadIfLocal(path: String): String? {
            if (!path.isLocalAssetPath()) return path
            replacements[path]?.let { return it }
            return when (val result = uploadNewsAssetUseCase(path)) {
                is AppResult.Failure -> {
                    updateState { it.copy(actionState = UiAsync.Error(result.error)) }
                    null
                }
                is AppResult.Success -> result.value.publicUrl.ifBlank { result.value.path }.also { replacements[path] = it }
            }
        }
        val backgroundPath = if (prepared.editorBackgroundPath.isBlank()) {
            ""
        } else {
            uploadIfLocal(prepared.editorBackgroundPath) ?: return null
        }
        val imagePath = if (prepared.editorImagePath.isBlank()) {
            ""
        } else {
            uploadIfLocal(prepared.editorImagePath) ?: return null
        }
        val layers = prepared.editorLayers.map { layer ->
            if (layer.kind.toLayerKind() == NewsLayerKind.IMAGE && layer.content.isLocalAssetPath()) {
                val uploaded = uploadIfLocal(layer.content) ?: return null
                layer.copy(content = uploaded)
            } else {
                layer
            }
        }
        prepared = prepared.copy(
            editorBackgroundPath = backgroundPath,
            editorImagePath = imagePath,
            editorBackgroundAssetStatus = when {
                backgroundPath.isBlank() -> "linked"
                backgroundPath.isLocalAssetPath() -> prepared.editorBackgroundAssetStatus
                else -> "uploaded"
            },
            editorImageAssetStatus = when {
                imagePath.isBlank() -> "linked"
                imagePath.isLocalAssetPath() -> prepared.editorImageAssetStatus
                else -> "uploaded"
            },
            editorLayers = layers,
        )
        updateState { prepared }
        rememberCurrentDraft(prepared)
        return prepared
    }
}

private fun defaultLayers(title: String, subtitle: String, body: String): List<NewsLayerDraft> {
    return listOf(
        NewsLayerDraft(randomUuid(), "Titulo", "titulo", title, 48, 42, 430, 82, 100, visible = true, sortOrder = 0),
        NewsLayerDraft(randomUuid(), "Subtitulo", "texto", subtitle, 48, 142, 360, 44, 92, visible = true, sortOrder = 1),
        NewsLayerDraft(randomUuid(), "Texto", "texto", body, 48, 220, 430, 112, 88, visible = body.isNotBlank(), sortOrder = 2),
    )
}

private fun List<NewsLayerDraft>.normalizedLayerOrder(): List<NewsLayerDraft> {
    return this.sortedBy { it.sortOrder }.mapIndexed { index, layer -> layer.copy(sortOrder = index) }
}

private fun List<NewsLayerDraft>.withSingleTitleLayer(
    title: String,
    canvasWidth: Int,
    canvasHeight: Int,
): List<NewsLayerDraft> {
    val titleLayer = firstOrNull { it.kind.toLayerKind() == NewsLayerKind.TITLE }
    val nonTitle = filterNot { it.kind.toLayerKind() == NewsLayerKind.TITLE }
    val normalizedTitle = (titleLayer ?: NewsLayerDraft(
        id = randomUuid(),
        name = "Titulo",
        kind = "titulo",
        content = title.ifBlank { "Titulo de noticia" },
        x = 48,
        y = 42,
        width = 460,
        height = 92,
        opacity = 100,
        fontFamily = "sans",
        textColor = "#FFFFFF",
        visible = true,
        sortOrder = nonTitle.size,
    )).copy(
        name = titleLayer?.name?.ifBlank { "Titulo" } ?: "Titulo",
        kind = "titulo",
        content = title.ifBlank { titleLayer?.content.orEmpty().ifBlank { "Titulo de noticia" } },
        visible = true,
        sortOrder = nonTitle.size,
    )
    return (nonTitle + normalizedTitle).map { it.coerceInsideCanvas(canvasWidth, canvasHeight) }.normalizedLayerOrder()
}

private fun NewsState.toNewsItem() = NewsItem(
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
    isLocalDraft = selectedId?.startsWith("local-") == true,
)

private fun NewsState.toDesignDraft() = NewsDesignDraft(
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
        background = editorBackgroundPath.ifBlank { null }?.let {
            NewsAssetDraft(
                path = it,
                status = editorBackgroundAssetStatus.toAssetStatus(),
            )
        },
        imageAsset = editorImagePath.ifBlank { null }?.let {
            NewsAssetDraft(
                path = it,
                status = editorImageAssetStatus.toAssetStatus(),
            )
        },
        layers = editorLayers.normalizedLayerOrder().map { it.toCanvasLayer() },
    ),
)

private fun NewsState.withClampedLayers(): NewsState {
    return copy(editorLayers = editorLayers.map { it.coerceInsideCanvas(editorCanvasWidth, editorCanvasHeight) }.normalizedLayerOrder())
}

private fun NewsLayerDraft.coerceInsideCanvas(
    canvasWidth: Int,
    canvasHeight: Int,
): NewsLayerDraft {
    val safeCanvasWidth = canvasWidth.coerceAtLeast(NewsLayerMinWidth)
    val safeCanvasHeight = canvasHeight.coerceAtLeast(NewsLayerMinHeight)
    val safeX = x.coerceIn(0, safeCanvasWidth - NewsLayerMinWidth)
    val safeY = y.coerceIn(0, safeCanvasHeight - NewsLayerMinHeight)
    val safeWidth = width.coerceIn(NewsLayerMinWidth, safeCanvasWidth - safeX)
    val safeHeight = height.coerceIn(NewsLayerMinHeight, safeCanvasHeight - safeY)
    return copy(
        x = safeX,
        y = safeY,
        width = safeWidth,
        height = safeHeight,
    )
}

private fun NewsLayerDraft.toCanvasLayer() = NewsCanvasLayer(
    id = id,
    name = name,
    kind = kind.toLayerKind(),
    content = content,
    x = x,
    y = y,
    width = width,
    height = height,
    opacity = opacity,
    fontFamily = fontFamily,
    textColor = textColor,
    visible = visible,
    locked = locked,
    sortOrder = sortOrder,
    rotationDegrees = rotationDegrees.normalizedDegrees(),
)

private fun String.toEditorialStatus(): NewsEditorialStatus {
    return when (trim().lowercase()) {
        "scheduled", "programada", "programado" -> NewsEditorialStatus.SCHEDULED
        "published", "publicada", "publicado" -> NewsEditorialStatus.PUBLISHED
        "archived", "archivada", "archivado" -> NewsEditorialStatus.ARCHIVED
        else -> NewsEditorialStatus.DRAFT
    }
}

private fun String.toArticleType(): NewsArticleType {
    return when (trim().lowercase()) {
        "aviso" -> NewsArticleType.AVISO
        "oferta" -> NewsArticleType.OFERTA
        "recurso" -> NewsArticleType.RECURSO
        else -> NewsArticleType.CONVOCATORIA
    }
}

private fun String.toNewsBackgroundStyle(): String {
    return when (trim().uppercase()) {
        "A", "SKY" -> "SKY"
        "B", "MINT" -> "MINT"
        "C", "GOLD" -> "GOLD"
        else -> "SKY"
    }
}

private fun String.toLayerKind(): NewsLayerKind {
    return when (trim().lowercase()) {
        "titulo", "title" -> NewsLayerKind.TITLE
        "imagen", "image" -> NewsLayerKind.IMAGE
        "badge", "etiqueta" -> NewsLayerKind.BADGE
        "cta", "boton", "button" -> NewsLayerKind.CTA
        "background", "fondo" -> NewsLayerKind.BACKGROUND
        else -> NewsLayerKind.TEXT
    }
}

private fun String.toAssetStatus(): NewsAssetStatus {
    return when (trim().lowercase()) {
        "uploading", "subiendo" -> NewsAssetStatus.UPLOADING
        "uploaded", "subido" -> NewsAssetStatus.UPLOADED
        "linked", "enlazado" -> NewsAssetStatus.LINKED
        "error" -> NewsAssetStatus.ERROR
        else -> NewsAssetStatus.LOCAL
    }
}

private fun NewsArticle.toUi() = NewsItem(
    id = id,
    title = title,
    specialty = specialty,
    type = type,
    backgroundType = backgroundType,
    backgroundPath = backgroundPath,
    status = status,
    publishFrom = publishFrom,
    text = text,
    imagePath = imagePath,
    layers = layers.map { it.toUi() },
    isLocalDraft = false,
)

private fun NewsItem.toDomain() = NewsArticle(
    id = id,
    title = title,
    specialty = specialty,
    type = type,
    backgroundType = backgroundType,
    backgroundPath = backgroundPath,
    status = status,
    publishFrom = publishFrom,
    text = text,
    imagePath = imagePath,
    layers = layers.map { it.toDomain() },
)

private fun NewsLayer.toUi() = NewsLayerDraft(id, name, kind, content, x, y, width, height, opacity, fontFamily, textColor, visible, locked, sortOrder, rotationDegrees.normalizedDegrees())

private fun NewsLayerDraft.toDomain() = NewsLayer(id, name, kind, content, x, y, width, height, opacity, fontFamily, textColor, visible, sortOrder, locked, rotationDegrees.normalizedDegrees())

private fun String.toPositiveInt(fallback: Int): Int = toIntOrNull()?.coerceAtLeast(1) ?: fallback

private fun String.toBoundedInt(fallback: Int, min: Int, max: Int): Int = toIntOrNull()?.coerceIn(min, max) ?: fallback

private fun Int.normalizedDegrees(): Int {
    val mod = this % 360
    return if (mod < 0) mod + 360 else mod
}

private fun String.isLocalAssetPath(): Boolean {
    return startsWith("content://", ignoreCase = true) || startsWith("file://", ignoreCase = true)
}

private fun NewsItem.isPublishedRemote(): Boolean {
    return status.equals("published", ignoreCase = true) && !isLocalDraft && !id.startsWith("local-")
}
