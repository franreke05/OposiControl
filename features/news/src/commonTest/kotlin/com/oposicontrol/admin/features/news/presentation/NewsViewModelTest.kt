package com.oposicontrol.admin.features.news.presentation

import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.data.local.NewsEditorDraftSnapshot
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NewsViewModelTest {

    // ------------------------------------------------------------------------------------------------ biblioteca

    @Test
    fun `the library loads the summaries and opens the first article with its version`() = runTest {
        val repository = FakeNewsRepository()
        val viewModel = createViewModel(repository)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertIs<UiAsync.Success<Unit>>(state.libraryState)
        assertEquals(listOf("news-1", "news-2"), (state.news as UiAsync.Success).value.map { it.id })
        assertEquals("news-1", state.selectedId)
        assertIs<UiAsync.Success<Unit>>(state.recordState)
        assertEquals("Convocatoria TCAE", state.editorTitle)
        assertEquals("2026-10-08T12:00:00Z", state.editorVersion)
        assertFalse(state.isDirty)
        assertTrue(repository.calls.contains("get:news-1"))
    }

    @Test
    fun `filters go to the server and the search is debounced`() = runTest {
        val repository = FakeNewsRepository()
        val viewModel = createViewModel(repository)
        advanceUntilIdle()
        val before = repository.refreshFilters.size

        viewModel.dispatch(NewsIntent.UpdateStatusFilter("published"))
        advanceUntilIdle()
        assertEquals("published", repository.refreshFilters.last().status)

        viewModel.dispatch(NewsIntent.UpdateSearch("con"))
        viewModel.dispatch(NewsIntent.UpdateSearch("conv"))
        viewModel.dispatch(NewsIntent.UpdateSearch("convo"))
        advanceTimeBy(100)
        assertEquals(before + 1, repository.refreshFilters.size, "tres pulsaciones no son tres peticiones")
        advanceUntilIdle()
        assertEquals(before + 2, repository.refreshFilters.size)
        assertEquals("convo", repository.refreshFilters.last().query)
        assertEquals("published", repository.refreshFilters.last().status)

        viewModel.dispatch(NewsIntent.ClearFilters)
        advanceUntilIdle()
        assertNull(repository.refreshFilters.last().status)
        assertNull(repository.refreshFilters.last().query)
    }

    @Test
    fun `a failed refresh keeps the rows on screen and says so`() = runTest {
        val repository = FakeNewsRepository()
        val viewModel = createViewModel(repository)
        advanceUntilIdle()

        repository.failRefresh = AppError.Network("sin red")
        viewModel.dispatch(NewsIntent.Refresh)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.libraryRefreshFailed)
        assertIs<UiAsync.Success<Unit>>(state.libraryState)
        assertEquals(2, (state.news as UiAsync.Success).value.size)
        assertEquals("Convocatoria TCAE", state.editorTitle, "la ficha abierta no se toca")
    }

    @Test
    fun `an empty library is EMPTY and a failed first read is ERROR`() = runTest {
        val empty = createViewModel(FakeNewsRepository(initial = emptyList()))
        advanceUntilIdle()
        assertEquals(UiAsync.Empty, empty.state.value.news)
        assertNull(empty.state.value.selectedId)

        val broken = FakeNewsRepository().apply { failRefresh = AppError.Network("sin red") }
        val failing = createViewModel(broken)
        advanceUntilIdle()
        assertIs<UiAsync.Error>(failing.state.value.libraryState)
    }

    // ------------------------------------------------------------------------------------------------ crear y guardar

    @Test
    fun `a new article is created on the server with an idempotency key and becomes the open record`() = runTest {
        val repository = FakeNewsRepository()
        val viewModel = createViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(NewsIntent.CreateNew)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.selectedId.orEmpty().startsWith("local-"))
        assertEquals(3, (viewModel.state.value.news as UiAsync.Success).value.size, "la nueva aparece en la biblioteca como sin guardar")

        viewModel.dispatch(NewsIntent.UpdateTitle("Nueva convocatoria"))
        viewModel.dispatch(NewsIntent.UpdateSpecialty(NEWS_ESP))
        viewModel.dispatch(NewsIntent.UpdateType(NEWS_TIPO))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.isDirty)
        assertNull(viewModel.state.value.saveProblem())

        viewModel.dispatch(NewsIntent.SaveDraft)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("news-3", state.selectedId)
        assertFalse(state.isDirty)
        assertIs<UiAsync.Success<Unit>>(state.actionState)
        assertEquals(NewsAction.SAVE, state.actionKind)
        assertEquals("Guardado en el servidor.", state.actionMessage)
        assertEquals("draft", state.editorStatus)
        assertTrue(state.editorVersion.isNotBlank())
        assertTrue(repository.lastIdempotencyKey.matches(Regex("[0-9a-f-]{36}")))
        assertEquals("Nueva convocatoria", repository.lastSaved?.title)
        assertEquals("Nueva convocatoria", repository.lastSaved?.layers?.first { it.kind == "titulo" }?.content, "la capa de titulo sigue al titulo")
    }

    @Test
    fun `saving without the fields the server requires is refused before sending`() = runTest {
        val repository = FakeNewsRepository()
        val viewModel = createViewModel(repository)
        advanceUntilIdle()
        viewModel.dispatch(NewsIntent.CreateNew)
        viewModel.dispatch(NewsIntent.UpdateTitle("Sin clasificar"))
        viewModel.dispatch(NewsIntent.SaveDraft)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertIs<UiAsync.Error>(state.actionState)
        assertEquals("La noticia necesita especialidad.", (state.actionState as UiAsync.Error).error.message)
        assertFalse(repository.calls.contains("create"))
    }

    @Test
    fun `editing an existing article is sent with its version and the new version is kept`() = runTest {
        val repository = FakeNewsRepository()
        val viewModel = createViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(NewsIntent.UpdateText("Texto nuevo"))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.isDirty)
        viewModel.dispatch(NewsIntent.SaveDraft)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(repository.calls.contains("update:news-1"))
        assertFalse(state.isDirty)
        assertEquals(repository.articles.getValue("news-1").version, state.editorVersion)
        assertEquals("Texto nuevo", repository.articles.getValue("news-1").text)
    }

    @Test
    fun `a stale version is a CONFLICT that keeps the local edits and does not retry`() = runTest {
        val repository = FakeNewsRepository()
        val viewModel = createViewModel(repository)
        advanceUntilIdle()
        repository.changeOnServer("news-1", "Cambiada por otro")
        viewModel.dispatch(NewsIntent.UpdateText("Mi texto"))
        viewModel.dispatch(NewsIntent.SaveDraft)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertIs<UiAsync.Error>(state.actionState)
        assertTrue(state.actionConflict)
        assertTrue(state.isDirty)
        assertEquals("Mi texto", state.editorText, "lo escrito no se pierde hasta recargar")
        assertTrue(state.saveStatus().conflict)
        assertFalse(state.saveStatus().canSave)

        viewModel.dispatch(NewsIntent.RetryRecord)
        advanceUntilIdle()
        assertEquals("Cambiada por otro", viewModel.state.value.editorTitle)
        assertFalse(viewModel.state.value.isDirty)
        assertFalse(viewModel.state.value.actionConflict)
    }

    @Test
    fun `a forbidden write is FORBIDDEN not a conflict`() = runTest {
        val repository = FakeNewsRepository().apply { failSave = AppError.Authorization("No tienes permisos.") }
        val viewModel = createViewModel(repository)
        advanceUntilIdle()
        viewModel.dispatch(NewsIntent.UpdateText("x"))
        viewModel.dispatch(NewsIntent.SaveDraft)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertIs<AppError.Authorization>((state.actionState as UiAsync.Error).error)
        assertFalse(state.actionConflict)
        assertTrue(state.isDirty)
    }

    // ------------------------------------------------------------------------------------------------ navegacion

    @Test
    fun `leaving with unsaved changes asks first and keeps the record if dismissed`() = runTest {
        val repository = FakeNewsRepository()
        val viewModel = createViewModel(repository)
        advanceUntilIdle()
        viewModel.dispatch(NewsIntent.UpdateTitle("Editada"))
        viewModel.dispatch(NewsIntent.Select("news-2"))
        advanceUntilIdle()

        assertEquals(NewsIntent.Select("news-2"), viewModel.state.value.pendingIntent)
        assertEquals("news-1", viewModel.state.value.selectedId)

        viewModel.dispatch(NewsIntent.DismissPending)
        advanceUntilIdle()
        assertNull(viewModel.state.value.pendingIntent)
        assertEquals("Editada", viewModel.state.value.editorTitle)

        viewModel.dispatch(NewsIntent.Select("news-2"))
        viewModel.dispatch(NewsIntent.ConfirmPending)
        advanceUntilIdle()
        assertEquals("news-2", viewModel.state.value.selectedId)
        assertEquals("Aviso SAS", viewModel.state.value.editorTitle)
        assertFalse(viewModel.state.value.isDirty)
    }

    @Test
    fun `discard restores the server copy and clears the local snapshot`() = runTest {
        val local = InMemoryNewsEditorDraftLocalDataSource()
        val viewModel = createViewModel(FakeNewsRepository(), local = local)
        advanceUntilIdle()
        viewModel.dispatch(NewsIntent.UpdateTitle("Borrador a medias"))
        advanceUntilIdle()
        assertNotNull(local.snapshot, "cada edicion deja copia local")

        viewModel.dispatch(NewsIntent.Discard)
        advanceUntilIdle()
        assertEquals("Convocatoria TCAE", viewModel.state.value.editorTitle)
        assertFalse(viewModel.state.value.isDirty)
        assertNull(local.snapshot)
    }

    @Test
    fun `a failed record read is an ERROR with retry`() = runTest {
        val repository = FakeNewsRepository().apply { failGet = AppError.Network("sin red") }
        val viewModel = createViewModel(repository)
        advanceUntilIdle()
        assertIs<UiAsync.Error>(viewModel.state.value.recordState)

        repository.failGet = null
        viewModel.dispatch(NewsIntent.RetryRecord)
        advanceUntilIdle()
        assertIs<UiAsync.Success<Unit>>(viewModel.state.value.recordState)
        assertEquals("Convocatoria TCAE", viewModel.state.value.editorTitle)
    }

    // ------------------------------------------------------------------------------------------------ comandos de estado

    @Test
    fun `publishing a dirty draft saves first and then publishes`() = runTest {
        val repository = FakeNewsRepository()
        val viewModel = createViewModel(repository)
        advanceUntilIdle()
        viewModel.dispatch(NewsIntent.UpdateText("Cuerpo final"))
        viewModel.dispatch(NewsIntent.Publish)
        advanceUntilIdle()

        val state = viewModel.state.value
        val calls = repository.calls.filter { it.startsWith("update:") || it.startsWith("publish:") }
        assertEquals(listOf("update:news-1", "publish:news-1"), calls)
        assertEquals("published", state.editorStatus)
        assertEquals("2026-10-08T13:00:00Z", state.editorPublishedAt)
        assertFalse(state.isDirty)
        assertEquals("Publicada.", state.actionMessage)

        // Publicada = solo lectura: una edicion no cambia nada ni ensucia.
        viewModel.dispatch(NewsIntent.UpdateTitle("Intento"))
        advanceUntilIdle()
        assertEquals("Convocatoria TCAE", viewModel.state.value.editorTitle)
        assertFalse(viewModel.state.value.isDirty)
        assertFalse(viewModel.state.value.isEditable())
    }

    @Test
    fun `publishing is blocked by the editor rules before calling the server`() = runTest {
        val repository = FakeNewsRepository()
        val viewModel = createViewModel(repository)
        advanceUntilIdle()
        viewModel.state.value.editorLayers.forEach { viewModel.dispatch(NewsIntent.ToggleLayerVisible(it.id)) }
        viewModel.dispatch(NewsIntent.Publish)
        advanceUntilIdle()

        assertFalse(repository.calls.any { it.startsWith("publish:") })
        assertIs<UiAsync.Error>(viewModel.state.value.actionState)
        assertEquals(NewsAction.PUBLISH, viewModel.state.value.actionKind)
        assertEquals("draft", repository.articles.getValue("news-1").status)
    }

    @Test
    fun `scheduling validates the instant and sends it to the server`() = runTest {
        val repository = FakeNewsRepository()
        val viewModel = createViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(NewsIntent.ScheduleAt("mañana"))
        advanceUntilIdle()
        assertIs<UiAsync.Error>(viewModel.state.value.actionState)
        assertFalse(repository.calls.any { it.startsWith("schedule:") })

        viewModel.dispatch(NewsIntent.ScheduleAt("2026-12-01T09:00:00Z"))
        advanceUntilIdle()
        assertTrue(repository.calls.contains("schedule:news-1:2026-12-01T09:00:00Z"))
        assertEquals("scheduled", viewModel.state.value.editorStatus)
        assertEquals("2026-12-01T09:00:00Z", viewModel.state.value.editorPublishFrom)
        assertTrue(viewModel.state.value.isEditable(), "programada sigue siendo editable")
    }

    @Test
    fun `archive refuses with pending changes and works on the saved copy`() = runTest {
        val repository = FakeNewsRepository()
        val viewModel = createViewModel(repository)
        advanceUntilIdle()
        viewModel.dispatch(NewsIntent.UpdateTitle("Solo local"))
        viewModel.dispatch(NewsIntent.Archive)
        advanceUntilIdle()
        assertFalse(repository.calls.any { it.startsWith("archive:") })
        assertIs<UiAsync.Error>(viewModel.state.value.actionState)

        viewModel.dispatch(NewsIntent.Discard)
        viewModel.dispatch(NewsIntent.Archive)
        advanceUntilIdle()
        assertTrue(repository.calls.contains("archive:news-1"))
        assertEquals("archived", viewModel.state.value.editorStatus)
        assertEquals("Convocatoria TCAE", repository.articles.getValue("news-1").title, "se archiva la version del servidor")
    }

    @Test
    fun `deleting opens the next article and a new draft is simply reset`() = runTest {
        val repository = FakeNewsRepository()
        val viewModel = createViewModel(repository)
        advanceUntilIdle()
        viewModel.dispatch(NewsIntent.DeleteSelected)
        advanceUntilIdle()
        assertTrue(repository.calls.contains("delete:news-1"))
        assertEquals("news-2", viewModel.state.value.selectedId)
        assertEquals("Noticia eliminada.", viewModel.state.value.actionMessage)

        viewModel.dispatch(NewsIntent.CreateNew)
        viewModel.dispatch(NewsIntent.UpdateTitle("Nunca guardada"))
        viewModel.dispatch(NewsIntent.DeleteSelected)
        advanceUntilIdle()
        assertEquals(1, repository.calls.count { it.startsWith("delete:") })
        assertEquals("", viewModel.state.value.editorTitle)
        assertTrue(viewModel.state.value.selectedId.orEmpty().startsWith("local-"))
    }

    @Test
    fun `the mobile SaveDraftFor opens the row and saves it`() = runTest {
        val repository = FakeNewsRepository()
        val viewModel = createViewModel(repository)
        advanceUntilIdle()
        viewModel.dispatch(NewsIntent.SaveDraftFor("news-2"))
        advanceUntilIdle()
        assertEquals("news-2", viewModel.state.value.selectedId)
        assertTrue(repository.calls.contains("update:news-2"))
    }

    // ------------------------------------------------------------------------------------------------ deshacer / rehacer

    @Test
    fun `typing coalesces into one undo step and undo returns to the server copy`() = runTest {
        val viewModel = createViewModel(FakeNewsRepository())
        advanceUntilIdle()
        listOf("C", "Co", "Con", "Conv").forEach { viewModel.dispatch(NewsIntent.UpdateTitle(it)) }
        advanceUntilIdle()
        assertTrue(viewModel.state.value.canUndo)
        assertTrue(viewModel.state.value.isDirty)

        viewModel.dispatch(NewsIntent.Undo)
        advanceUntilIdle()
        assertEquals("Convocatoria TCAE", viewModel.state.value.editorTitle)
        assertFalse(viewModel.state.value.isDirty, "deshacer hasta la copia del servidor deja la ficha limpia")
        assertFalse(viewModel.state.value.canUndo)
        assertTrue(viewModel.state.value.canRedo)

        viewModel.dispatch(NewsIntent.Redo)
        advanceUntilIdle()
        assertEquals("Conv", viewModel.state.value.editorTitle)
        assertTrue(viewModel.state.value.isDirty)
    }

    @Test
    fun `a drag is one undo step per gesture while typed values are separate steps`() = runTest {
        val viewModel = createViewModel(FakeNewsRepository())
        advanceUntilIdle()
        val layer = viewModel.state.value.editorLayers.first()
        repeat(30) { step -> viewModel.dispatch(NewsIntent.UpdateLayerFrame(layer.id, 48 + step, 42, layer.width, layer.height, gesture = true)) }
        advanceUntilIdle()
        viewModel.dispatch(NewsIntent.Undo)
        advanceUntilIdle()
        val restored = viewModel.state.value.editorLayers.first { it.id == layer.id }
        assertEquals(48, restored.x, "treinta frames son un solo paso")
        assertFalse(viewModel.state.value.canUndo)

        viewModel.dispatch(NewsIntent.UpdateLayerX(layer.id, "100"))
        viewModel.dispatch(NewsIntent.UpdateLayerOpacity(layer.id, "50"))
        advanceUntilIdle()
        viewModel.dispatch(NewsIntent.Undo)
        advanceUntilIdle()
        val afterOne = viewModel.state.value.editorLayers.first { it.id == layer.id }
        assertEquals(100, afterOne.x)
        assertEquals(100, afterOne.opacity)
    }

    // ------------------------------------------------------------------------------------------------ capas

    @Test
    fun `layer geometry is exact clamped and respects locks`() = runTest {
        val viewModel = createViewModel(FakeNewsRepository())
        advanceUntilIdle()
        viewModel.dispatch(NewsIntent.AddLayer)
        advanceUntilIdle()
        val id = viewModel.state.value.selectedLayerId!!

        viewModel.dispatch(NewsIntent.UpdateLayerFrame(id, -30, -40, 5000, 5000))
        advanceUntilIdle()
        val clamped = viewModel.state.value.editorLayers.first { it.id == id }
        // El lienzo es el banner de Inicio de OposiBot a 4 px por dp: 1432 x 592.
        assertEquals(listOf(0, 0, 1432, 592), listOf(clamped.x, clamped.y, clamped.width, clamped.height))

        viewModel.dispatch(NewsIntent.UpdateLayerFrame(id, 160, 180, 310, 84))
        viewModel.dispatch(NewsIntent.NudgeLayer(id, 10, -5))
        advanceUntilIdle()
        val nudged = viewModel.state.value.editorLayers.first { it.id == id }
        assertEquals(170 to 175, nudged.x to nudged.y)

        viewModel.dispatch(NewsIntent.AlignLayer(id, com.oposicontrol.admin.features.news.editor.NewsAlignment.RIGHT))
        advanceUntilIdle()
        assertEquals(1432 - 310, viewModel.state.value.editorLayers.first { it.id == id }.x)

        viewModel.dispatch(NewsIntent.ToggleLayerLocked(id))
        viewModel.dispatch(NewsIntent.UpdateLayerFrame(id, 10, 10, 50, 50))
        viewModel.dispatch(NewsIntent.RemoveLayer(id))
        advanceUntilIdle()
        val locked = viewModel.state.value.editorLayers.first { it.id == id }
        assertTrue(locked.locked)
        assertEquals(1432 - 310, locked.x, "una capa bloqueada no se mueve ni se borra")

        viewModel.dispatch(NewsIntent.ToggleLayerLocked(id))
        viewModel.dispatch(NewsIntent.RemoveLayer(id))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.editorLayers.none { it.id == id })
        assertNull(viewModel.state.value.selectedLayerId)
    }

    @Test
    fun `ordering commands keep the selection and renumber from zero`() = runTest {
        val viewModel = createViewModel(FakeNewsRepository())
        advanceUntilIdle()
        viewModel.dispatch(NewsIntent.AddLayer)
        viewModel.dispatch(NewsIntent.AddLayer)
        advanceUntilIdle()
        val selected = viewModel.state.value.selectedLayerId!!
        viewModel.dispatch(NewsIntent.SendLayerToBack(selected))
        viewModel.dispatch(NewsIntent.MoveLayerUp(selected))
        viewModel.dispatch(NewsIntent.BringLayerToFront(selected))
        viewModel.dispatch(NewsIntent.MoveLayerDown(selected))
        viewModel.dispatch(NewsIntent.MoveLayerUp("missing"))
        advanceUntilIdle()
        assertEquals(selected, viewModel.state.value.selectedLayerId)
        assertEquals(listOf(0, 1, 2), viewModel.state.value.editorLayers.sortedBy { it.sortOrder }.map { it.sortOrder })
        assertEquals(1, viewModel.state.value.editorLayers.first { it.id == selected }.sortOrder)
    }

    @Test
    fun `font size zero returns to derived and unknown weights are rejected`() = runTest {
        val viewModel = createViewModel(FakeNewsRepository())
        advanceUntilIdle()
        val id = viewModel.state.value.editorLayers.first().id
        viewModel.dispatch(NewsIntent.UpdateLayerFontSize(id, "3"))
        viewModel.dispatch(NewsIntent.UpdateLayerFontWeight(id, "bold"))
        viewModel.dispatch(NewsIntent.UpdateLayerFontWeight(id, "heavy"))
        advanceUntilIdle()
        var layer = viewModel.state.value.editorLayers.first { it.id == id }
        assertEquals(8, layer.fontSizeSp)
        assertEquals("bold", layer.fontWeight)
        viewModel.dispatch(NewsIntent.UpdateLayerFontSize(id, "0"))
        advanceUntilIdle()
        layer = viewModel.state.value.editorLayers.first { it.id == id }
        assertEquals(0, layer.fontSizeSp)
    }

    // ------------------------------------------------------------------------------------------------ subidas

    @Test
    fun `a background upload reports progress and lands as the server path and url`() = runTest {
        val repository = FakeNewsRepository()
        val viewModel = createViewModel(repository)
        advanceUntilIdle()
        viewModel.dispatch(NewsIntent.UploadBackground("file:///tmp/fondo.png"))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.uploads.isEmpty())
        assertEquals("news/8f14e45f-ceea-467a-9a3e-1b2c3d4e5f60/fondo.png", state.editorBackgroundPath)
        assertEquals("https://cdn.oposicontrol.local/news/8f14e45f-ceea-467a-9a3e-1b2c3d4e5f60/fondo.png", state.editorBackgroundUrl)
        assertTrue(state.isDirty)
        assertTrue(repository.lastIdempotencyKey.matches(Regex("[0-9a-f-]{36}")))

        viewModel.dispatch(NewsIntent.SaveDraft)
        advanceUntilIdle()
        assertEquals("news/8f14e45f-ceea-467a-9a3e-1b2c3d4e5f60/fondo.png", repository.lastSaved?.backgroundPath, "al servidor viaja la RUTA, nunca la URL")
    }

    @Test
    fun `a failed upload stays visible with retry and blocks saving until resolved`() = runTest {
        val repository = FakeNewsRepository().apply { failUpload = AppError.Network("sin red") }
        val viewModel = createViewModel(repository)
        advanceUntilIdle()
        viewModel.dispatch(NewsIntent.UploadImage("file:///tmp/tarjeta.png"))
        advanceUntilIdle()

        val failed = viewModel.state.value.uploads.getValue(NEWS_UPLOAD_IMAGE)
        assertTrue(failed.failed)
        assertEquals("tarjeta.png", failed.fileName)
        assertEquals("Hay una subida fallida: reintenta o quítala.", viewModel.state.value.saveProblem())
        assertEquals("", viewModel.state.value.editorImagePath)

        repository.failUpload = null
        viewModel.dispatch(NewsIntent.RetryUpload(NEWS_UPLOAD_IMAGE))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.uploads.isEmpty())
        assertEquals("news/8f14e45f-ceea-467a-9a3e-1b2c3d4e5f60/tarjeta.png", viewModel.state.value.editorImagePath)
    }

    @Test
    fun `an image layer waits for its upload and then carries the public url`() = runTest {
        val repository = FakeNewsRepository()
        val viewModel = createViewModel(repository)
        advanceUntilIdle()
        viewModel.dispatch(NewsIntent.AddLayerWithImage("file:///tmp/capa.png"))
        advanceUntilIdle()
        val layer = viewModel.state.value.editorLayers.first { it.kind == "imagen" }
        assertEquals("https://cdn.oposicontrol.local/news/8f14e45f-ceea-467a-9a3e-1b2c3d4e5f60/capa.png", layer.content)
        assertEquals(0, layer.sortOrder, "la imagen nace al fondo")
        assertEquals(layer.id, viewModel.state.value.selectedLayerId)
    }

    // ------------------------------------------------------------------------------------------------ borrador local

    @Test
    fun `a local snapshot of a server article is recovered on top of the fresh read`() = runTest {
        val repository = FakeNewsRepository()
        val local = InMemoryNewsEditorDraftLocalDataSource(
            NewsEditorDraftSnapshot(
                newsId = "news-2", title = "Aviso SAS corregido", textContent = "Texto local", specialty = NEWS_ESP, type = NEWS_TIPO,
                backgroundType = "MINT", backgroundPath = "", imagePath = "", status = "draft", layersJson = "[]", canvasWidth = 800, canvasHeight = 500,
            ),
        )
        val viewModel = createViewModel(repository, local = local)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("news-2", state.selectedId, "se abre la noticia del borrador, no la primera")
        assertEquals("Aviso SAS corregido", state.editorTitle)
        assertEquals("MINT", state.editorBackgroundType)
        assertEquals(repository.articles.getValue("news-2").version, state.editorVersion, "la version es la del servidor")
        assertTrue(state.isDirty)
        assertTrue(state.recoveredDraft)
        assertTrue(state.canUndo, "deshacer devuelve a la copia del servidor")
    }

    @Test
    fun `a local snapshot of an unsaved article reopens as a new draft`() = runTest {
        val local = InMemoryNewsEditorDraftLocalDataSource(
            NewsEditorDraftSnapshot(
                newsId = "local-abc", title = "Nunca guardada", textContent = "", specialty = "", type = "",
                backgroundType = "SKY", backgroundPath = "", imagePath = "", status = "draft", layersJson = "[]", canvasWidth = 800, canvasHeight = 500,
            ),
        )
        val viewModel = createViewModel(FakeNewsRepository(), local = local)
        advanceUntilIdle()
        val state = viewModel.state.value
        assertEquals("local-abc", state.selectedId)
        assertEquals("Nunca guardada", state.editorTitle)
        assertTrue(state.recoveredDraft)
        assertTrue(state.isDirty)
    }

    @Test
    fun `a failed local save never pretends the server has the changes`() = runTest {
        val local = object : com.oposicontrol.admin.data.local.NewsEditorDraftLocalDataSource {
            override fun readDraft(): NewsEditorDraftSnapshot? = null
            override fun saveDraft(snapshot: NewsEditorDraftSnapshot) = error("disk unavailable")
            override fun clearDraft() = Unit
        }
        val viewModel = createViewModel(FakeNewsRepository(), local = local)
        advanceUntilIdle()
        viewModel.dispatch(NewsIntent.UpdateTitle("Editada"))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.isDirty)
        assertEquals("Cambios sin guardar.", viewModel.state.value.saveStatus().message)
    }

    // ------------------------------------------------------------------------------------------------ apoyo

    private fun TestScope.createViewModel(
        repository: FakeNewsRepository,
        local: com.oposicontrol.admin.data.local.NewsEditorDraftLocalDataSource = InMemoryNewsEditorDraftLocalDataSource(),
    ): NewsViewModel {
        val validate = ValidateNewsDraftUseCase()
        return NewsViewModel(
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            observeNewsUseCase = ObserveNewsUseCase(repository),
            refreshNewsUseCase = RefreshNewsUseCase(repository),
            loadMoreNewsUseCase = LoadMoreNewsUseCase(repository),
            loadNewsUseCase = LoadNewsUseCase(repository),
            saveNewsUseCase = SaveNewsUseCase(repository),
            deleteNewsUseCase = DeleteNewsUseCase(repository),
            validateNewsDraftUseCase = validate,
            publishNewsUseCase = PublishNewsUseCase(repository, validate),
            scheduleNewsUseCase = ScheduleNewsUseCase(repository, validate),
            uploadNewsAssetUseCase = UploadNewsAssetUseCase(repository),
            archiveNewsUseCase = ArchiveNewsUseCase(repository),
            analyticsTracker = NoOpAnalyticsTracker,
            draftLocalDataSource = local,
            searchDebounceMillis = 300,
        )
    }
}
