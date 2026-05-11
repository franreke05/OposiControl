package com.oposicontrol.admin.features.news.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.NewsArticle
import com.oposicontrol.admin.domain.model.NewsAssetUpload
import com.oposicontrol.admin.domain.repository.NewsRepository
import com.oposicontrol.admin.domain.usecase.news.ArchiveNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.DeleteNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.ObserveNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.PublishNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.RefreshNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.ScheduleNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.UploadNewsAssetUseCase
import com.oposicontrol.admin.domain.usecase.news.UpsertNewsUseCase
import com.oposicontrol.admin.domain.usecase.news.ValidateNewsDraftUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NewsViewModelTest {
    @Test
    fun `filtering by specialty keeps only matching news`() = runTest {
        val viewModel = createViewModel(StandardTestDispatcher(testScheduler))

        viewModel.dispatch(NewsIntent.UpdateSpecialtyFilter("tcae"))
        advanceUntilIdle()

        val state = viewModel.state.value
        val items = (state.news as UiAsync.Success).value
        assertTrue(items.isNotEmpty())
        assertTrue(items.all { it.specialty == "TCAE" })
        assertEquals("tcae", state.specialtyFilter)
    }

    @Test
    fun `publishing is blocked when required canvas rules fail`() = runTest {
        val viewModel = createViewModel(StandardTestDispatcher(testScheduler))

        viewModel.dispatch(NewsIntent.CreateNew)
        advanceUntilIdle()
        viewModel.state.value.editorLayers.forEach { layer ->
            viewModel.dispatch(NewsIntent.ToggleLayerVisible(layer.id))
        }
        viewModel.dispatch(NewsIntent.Publish)
        advanceUntilIdle()

        val report = viewModel.state.value.validationReport
        assertNotNull(report)
        assertFalse(report.canPublish)
        assertTrue(report.errors.isNotEmpty())
    }

    @Test
    fun `new draft uses mobile news canvas size`() = runTest {
        val viewModel = createViewModel(StandardTestDispatcher(testScheduler))

        viewModel.dispatch(NewsIntent.CreateNew)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(800, state.editorCanvasWidth)
        assertEquals(500, state.editorCanvasHeight)
        assertEquals("SKY", state.editorBackgroundType)
    }

    @Test
    fun `fullscreen mode toggles without changing selected layer`() = runTest {
        val viewModel = createViewModel(StandardTestDispatcher(testScheduler))

        viewModel.dispatch(NewsIntent.CreateNew)
        advanceUntilIdle()
        viewModel.dispatch(NewsIntent.AddLayer)
        advanceUntilIdle()
        val selectedLayerId = viewModel.state.value.selectedLayerId

        viewModel.dispatch(NewsIntent.ToggleCanvasFullscreen)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.canvasFullscreen)
        assertEquals(selectedLayerId, viewModel.state.value.selectedLayerId)
    }

    @Test
    fun `locked layer ignores frame updates`() = runTest {
        val viewModel = createViewModel(StandardTestDispatcher(testScheduler))

        viewModel.dispatch(NewsIntent.CreateNew)
        advanceUntilIdle()
        viewModel.dispatch(NewsIntent.AddLayer)
        advanceUntilIdle()
        val layer = viewModel.state.value.editorLayers.first { it.id == viewModel.state.value.selectedLayerId }

        viewModel.dispatch(NewsIntent.ToggleLayerLocked(layer.id))
        viewModel.dispatch(NewsIntent.UpdateLayerFrame(layer.id, layer.x + 40, layer.y + 40, layer.width + 20, layer.height + 20))
        advanceUntilIdle()

        val lockedLayer = viewModel.state.value.editorLayers.first { it.id == layer.id }
        assertTrue(lockedLayer.locked)
        assertEquals(layer.x, lockedLayer.x)
        assertEquals(layer.y, lockedLayer.y)
        assertEquals(layer.width, lockedLayer.width)
        assertEquals(layer.height, lockedLayer.height)
    }

    @Test
    fun `layer frame update changes position and size`() = runTest {
        val viewModel = createViewModel(StandardTestDispatcher(testScheduler))

        viewModel.dispatch(NewsIntent.CreateNew)
        advanceUntilIdle()
        viewModel.dispatch(NewsIntent.AddLayer)
        advanceUntilIdle()
        val layer = viewModel.state.value.editorLayers.first { it.id == viewModel.state.value.selectedLayerId }

        viewModel.dispatch(NewsIntent.UpdateLayerFrame(layer.id, 160, 180, 310, 84))
        advanceUntilIdle()

        val movedLayer = viewModel.state.value.editorLayers.first { it.id == layer.id }
        assertEquals(160, movedLayer.x)
        assertEquals(180, movedLayer.y)
        assertEquals(310, movedLayer.width)
        assertEquals(84, movedLayer.height)
    }

    @Test
    fun `layer frame update is clamped inside mobile canvas`() = runTest {
        val viewModel = createViewModel(StandardTestDispatcher(testScheduler))

        viewModel.dispatch(NewsIntent.CreateNew)
        advanceUntilIdle()
        viewModel.dispatch(NewsIntent.AddLayer)
        advanceUntilIdle()
        val layer = viewModel.state.value.editorLayers.first { it.id == viewModel.state.value.selectedLayerId }

        viewModel.dispatch(NewsIntent.UpdateLayerFrame(layer.id, -30, -40, 900, 700))
        advanceUntilIdle()

        val movedLayer = viewModel.state.value.editorLayers.first { it.id == layer.id }
        assertEquals(0, movedLayer.x)
        assertEquals(0, movedLayer.y)
        assertEquals(800, movedLayer.width)
        assertEquals(500, movedLayer.height)
    }

    @Test
    fun `resizing near canvas edge preserves origin and limits size`() = runTest {
        val viewModel = createViewModel(StandardTestDispatcher(testScheduler))

        viewModel.dispatch(NewsIntent.CreateNew)
        advanceUntilIdle()
        viewModel.dispatch(NewsIntent.AddLayer)
        advanceUntilIdle()
        val layer = viewModel.state.value.editorLayers.first { it.id == viewModel.state.value.selectedLayerId }

        viewModel.dispatch(NewsIntent.UpdateLayerFrame(layer.id, 700, 420, 240, 160))
        advanceUntilIdle()

        val resizedLayer = viewModel.state.value.editorLayers.first { it.id == layer.id }
        assertEquals(700, resizedLayer.x)
        assertEquals(420, resizedLayer.y)
        assertEquals(100, resizedLayer.width)
        assertEquals(80, resizedLayer.height)
    }

    @Test
    fun `layer ordering commands keep selected layer and tolerate edges`() = runTest {
        val viewModel = createViewModel(StandardTestDispatcher(testScheduler))

        viewModel.dispatch(NewsIntent.CreateNew)
        advanceUntilIdle()
        viewModel.dispatch(NewsIntent.AddLayer)
        viewModel.dispatch(NewsIntent.AddLayer)
        advanceUntilIdle()
        val selectedId = viewModel.state.value.selectedLayerId.orEmpty()

        viewModel.dispatch(NewsIntent.BringLayerToFront(selectedId))
        viewModel.dispatch(NewsIntent.MoveLayerUp(selectedId))
        viewModel.dispatch(NewsIntent.SendLayerToBack(selectedId))
        viewModel.dispatch(NewsIntent.MoveLayerDown(selectedId))
        viewModel.dispatch(NewsIntent.MoveLayerUp("missing-layer"))
        advanceUntilIdle()

        assertEquals(selectedId, viewModel.state.value.selectedLayerId)
        assertEquals(
            viewModel.state.value.editorLayers.indices.toList(),
            viewModel.state.value.editorLayers.sortedBy { it.sortOrder }.map { it.sortOrder },
        )
    }

    @Test
    fun `center layer moves selected layer to canvas center`() = runTest {
        val viewModel = createViewModel(StandardTestDispatcher(testScheduler))

        viewModel.dispatch(NewsIntent.CreateNew)
        advanceUntilIdle()
        viewModel.dispatch(NewsIntent.AddLayer)
        advanceUntilIdle()
        val layer = viewModel.state.value.editorLayers.first { it.id == viewModel.state.value.selectedLayerId }

        viewModel.dispatch(NewsIntent.CenterLayer(layer.id))
        advanceUntilIdle()

        val centeredLayer = viewModel.state.value.editorLayers.first { it.id == layer.id }
        assertEquals((800 - layer.width) / 2, centeredLayer.x)
        assertEquals((500 - layer.height) / 2, centeredLayer.y)
    }

    @Test
    fun `image layer rotation is normalized and preserved on save`() = runTest {
        val viewModel = createViewModel(StandardTestDispatcher(testScheduler))

        viewModel.dispatch(NewsIntent.CreateNew)
        advanceUntilIdle()
        viewModel.dispatch(NewsIntent.AddLayerWithImage("news/local/image.png"))
        advanceUntilIdle()
        val layer = viewModel.state.value.editorLayers.first { it.id == viewModel.state.value.selectedLayerId }

        viewModel.dispatch(NewsIntent.UpdateLayerRotation(layer.id, -90))
        viewModel.dispatch(NewsIntent.UpdateTitle("Convocatoria TCAE"))
        viewModel.dispatch(NewsIntent.SaveDraft)
        advanceUntilIdle()

        val rotatedLayer = viewModel.state.value.editorLayers.first { it.id == layer.id }
        assertEquals(270, rotatedLayer.rotationDegrees)
    }

    @Test
    fun `layer visibility toggle keeps layer available for editing`() = runTest {
        val viewModel = createViewModel(StandardTestDispatcher(testScheduler))

        viewModel.dispatch(NewsIntent.CreateNew)
        advanceUntilIdle()
        viewModel.dispatch(NewsIntent.AddLayer)
        advanceUntilIdle()
        val layer = viewModel.state.value.editorLayers.first { it.id == viewModel.state.value.selectedLayerId }

        viewModel.dispatch(NewsIntent.ToggleLayerVisible(layer.id))
        advanceUntilIdle()

        val hiddenLayer = viewModel.state.value.editorLayers.first { it.id == layer.id }
        assertFalse(hiddenLayer.visible)
        assertEquals(layer.id, viewModel.state.value.selectedLayerId)
    }

    @Test
    fun `save keeps editable layers`() = runTest {
        val viewModel = createViewModel(StandardTestDispatcher(testScheduler))

        viewModel.dispatch(NewsIntent.CreateNew)
        advanceUntilIdle()
        viewModel.dispatch(NewsIntent.UpdateTitle("Convocatoria TCAE"))
        viewModel.dispatch(NewsIntent.AddLayer)
        advanceUntilIdle()
        val layerCount = viewModel.state.value.editorLayers.size

        viewModel.dispatch(NewsIntent.SaveDraft)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.selectedId != null)
        assertEquals(layerCount, viewModel.state.value.editorLayers.size)
    }

    @Test
    fun `local draft keeps resized layers when remote refresh is incomplete`() = runTest {
        val repository = FakeNewsRepositoryForTest()
        val viewModel = createViewModel(StandardTestDispatcher(testScheduler), repository)

        viewModel.dispatch(NewsIntent.CreateNew)
        advanceUntilIdle()
        viewModel.dispatch(NewsIntent.UpdateTitle("Convocatoria TCAE"))
        viewModel.dispatch(NewsIntent.AddLayer)
        advanceUntilIdle()
        val layer = viewModel.state.value.editorLayers.first { it.id == viewModel.state.value.selectedLayerId }

        viewModel.dispatch(NewsIntent.UpdateLayerFrame(layer.id, 240, 210, 320, 90))
        viewModel.dispatch(NewsIntent.SaveDraft)
        advanceUntilIdle()
        val savedId = viewModel.state.value.selectedId.orEmpty()

        repository.replaceRemote(
            listOf(
                NewsArticle(
                    id = savedId,
                    title = "Convocatoria TCAE",
                    specialty = "TCAE",
                    type = "Convocatoria",
                    backgroundType = "SKY",
                    backgroundPath = null,
                    status = "draft",
                    publishFrom = "",
                    text = "",
                    imagePath = null,
                    layers = emptyList(),
                ),
            ),
        )
        advanceUntilIdle()
        viewModel.dispatch(NewsIntent.Select(savedId))
        advanceUntilIdle()

        val preservedLayer = viewModel.state.value.editorLayers.first { it.id == layer.id }
        assertEquals(240, preservedLayer.x)
        assertEquals(210, preservedLayer.y)
        assertEquals(320, preservedLayer.width)
        assertEquals(90, preservedLayer.height)
    }

    @Test
    fun `publishing works with template background`() = runTest {
        val viewModel = createViewModel(StandardTestDispatcher(testScheduler))

        viewModel.dispatch(NewsIntent.CreateNew)
        advanceUntilIdle()
        viewModel.dispatch(NewsIntent.UpdateTitle("Convocatoria TCAE"))
        viewModel.dispatch(NewsIntent.UpdateBackgroundType("SKY"))
        viewModel.dispatch(NewsIntent.Publish)
        advanceUntilIdle()

        val report = viewModel.state.value.validationReport
        assertNotNull(report)
        assertTrue(report.canPublish)
        assertEquals("published", viewModel.state.value.editorStatus)
    }

    @Test
    fun `create new appears in local news section`() = runTest {
        val viewModel = createViewModel(StandardTestDispatcher(testScheduler))

        viewModel.dispatch(NewsIntent.CreateNew)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.selectedId.orEmpty().startsWith("local-"))
        val localItems = (state.localNews as UiAsync.Success).value
        assertTrue(localItems.any { it.id == state.selectedId && it.isLocalDraft })
    }

    @Test
    fun `save draft does not call backend`() = runTest {
        val repository = FakeNewsRepositoryForTest()
        val viewModel = createViewModel(StandardTestDispatcher(testScheduler), repository)

        viewModel.dispatch(NewsIntent.CreateNew)
        viewModel.dispatch(NewsIntent.UpdateTitle("Borrador local"))
        viewModel.dispatch(NewsIntent.SaveDraft)
        advanceUntilIdle()

        assertEquals(0, repository.upsertCount)
        assertTrue(viewModel.state.value.selectedId.orEmpty().startsWith("local-"))
    }

    @Test
    fun `publishing local draft creates remote published news`() = runTest {
        val repository = FakeNewsRepositoryForTest()
        val viewModel = createViewModel(StandardTestDispatcher(testScheduler), repository)

        viewModel.dispatch(NewsIntent.CreateNew)
        viewModel.dispatch(NewsIntent.UpdateTitle("Convocatoria publicada"))
        viewModel.dispatch(NewsIntent.Publish)
        advanceUntilIdle()

        assertEquals(1, repository.upsertCount)
        assertEquals("published", viewModel.state.value.editorStatus)
        assertFalse(viewModel.state.value.selectedId.orEmpty().startsWith("local-"))
        assertTrue(repository.lastUpsertedId.isBlank())
    }

    @Test
    fun `selecting background keeps exactly one title layer`() = runTest {
        val viewModel = createViewModel(StandardTestDispatcher(testScheduler))

        viewModel.dispatch(NewsIntent.CreateNew)
        viewModel.dispatch(NewsIntent.AddTitleLayer)
        viewModel.dispatch(NewsIntent.SelectBackgroundFile("content://media/background.png", "background.png", "image/png"))
        advanceUntilIdle()

        val titleLayers = viewModel.state.value.editorLayers.filter { it.kind == "titulo" }
        assertEquals(1, titleLayers.size)
        assertEquals("SKY", viewModel.state.value.editorBackgroundType)
    }

    @Test
    fun `local background is uploaded before publishing`() = runTest {
        val repository = FakeNewsRepositoryForTest()
        val viewModel = createViewModel(StandardTestDispatcher(testScheduler), repository)

        viewModel.dispatch(NewsIntent.CreateNew)
        viewModel.dispatch(NewsIntent.UpdateTitle("Con fondo local"))
        viewModel.dispatch(NewsIntent.SelectBackgroundFile("content://media/background.png", "background.png", "image/png"))
        viewModel.dispatch(NewsIntent.Publish)
        advanceUntilIdle()

        assertEquals(1, repository.uploadCount)
        assertEquals("uploaded", viewModel.state.value.editorBackgroundAssetStatus)
        assertTrue(viewModel.state.value.editorBackgroundPath.startsWith("https://cdn.oposicontrol.local/"))
    }

    private fun createViewModel(
        dispatcher: TestDispatcher,
        repository: FakeNewsRepositoryForTest = FakeNewsRepositoryForTest(),
    ): NewsViewModel {
        val validate = ValidateNewsDraftUseCase()
        return NewsViewModel(
            dispatcherProvider = TestDispatcherProvider(dispatcher),
            observeNewsUseCase = ObserveNewsUseCase(repository),
            refreshNewsUseCase = RefreshNewsUseCase(repository),
            upsertNewsUseCase = UpsertNewsUseCase(repository),
            deleteNewsUseCase = DeleteNewsUseCase(repository),
            validateNewsDraftUseCase = validate,
            publishNewsUseCase = PublishNewsUseCase(repository, validate),
            scheduleNewsUseCase = ScheduleNewsUseCase(repository, validate),
            uploadNewsAssetUseCase = UploadNewsAssetUseCase(repository),
            archiveNewsUseCase = ArchiveNewsUseCase(repository),
            analyticsTracker = NoOpAnalyticsTracker,
        )
    }
}

private class FakeNewsRepositoryForTest : NewsRepository {
    var upsertCount = 0
        private set
    var uploadCount = 0
        private set
    var lastUpsertedId = ""
        private set
    private val items = MutableStateFlow(
        listOf(
            NewsArticle(
                id = "news-1",
                title = "Convocatoria TCAE",
                specialty = "TCAE",
                type = "Convocatoria",
                backgroundType = "A",
                backgroundPath = "news/backgrounds/cover.png",
                status = "draft",
                publishFrom = "",
                text = "Texto",
                imagePath = null,
                layers = emptyList(),
            ),
            NewsArticle(
                id = "news-2",
                title = "Aviso SAS",
                specialty = "SAS",
                type = "Aviso",
                backgroundType = "A",
                backgroundPath = "news/backgrounds/cover.png",
                status = "draft",
                publishFrom = "",
                text = "Texto",
                imagePath = null,
                layers = emptyList(),
            ),
        ),
    )

    override fun observeNews(): Flow<List<NewsArticle>> = items

    override suspend fun refreshNews(): AppResult<List<NewsArticle>> = AppResult.Success(items.value)

    override suspend fun upsertNews(article: NewsArticle): AppResult<NewsArticle> {
        upsertCount += 1
        lastUpsertedId = article.id
        val saved = article.copy(id = article.id.ifBlank { "news-${items.value.size + 1}" })
        items.value = items.value.filterNot { it.id == saved.id } + saved
        return AppResult.Success(saved)
    }

    override suspend fun deleteNews(newsId: String): AppResult<Unit> {
        items.value = items.value.filterNot { it.id == newsId }
        return AppResult.Success(Unit)
    }

    override suspend fun uploadNewsAsset(localPathOrUri: String): AppResult<NewsAssetUpload> {
        uploadCount += 1
        val fileName = localPathOrUri.substringAfterLast('/').ifBlank { "asset.png" }
        val path = "news-assets/test/$fileName"
        return AppResult.Success(
            NewsAssetUpload(
                path = path,
                publicUrl = "https://cdn.oposicontrol.local/$path",
                mimeType = "image/png",
            ),
        )
    }

    fun replaceRemote(news: List<NewsArticle>) {
        items.value = news
    }
}
