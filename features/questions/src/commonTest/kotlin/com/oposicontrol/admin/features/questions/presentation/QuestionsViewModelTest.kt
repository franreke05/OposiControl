package com.oposicontrol.admin.features.questions.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.testing.TestDispatcherProvider
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.QuestionDetail
import com.oposicontrol.admin.domain.model.QuestionDraft
import com.oposicontrol.admin.domain.model.QuestionExamLink
import com.oposicontrol.admin.domain.model.QuestionListItem
import com.oposicontrol.admin.domain.model.QuestionListQuery
import com.oposicontrol.admin.domain.model.QuestionListSort
import com.oposicontrol.admin.domain.model.QuestionOption
import com.oposicontrol.admin.domain.model.QuestionPage
import com.oposicontrol.admin.domain.model.QuestionType
import com.oposicontrol.admin.domain.repository.QuestionActiveFilter
import com.oposicontrol.admin.domain.repository.QuestionsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class QuestionsViewModelTest {

    private val exam = Exam(
        id = "ex-1",
        code = "EX-0012",
        specialtyId = "sp-1",
        specialtyName = "Administrativo",
        title = "Administrativo del Estado 2024",
        description = "",
        priceTickets = 0,
        status = ExamStatus.PUBLISHED,
        publishFrom = null,
        publishedAt = null,
        sortOrder = 0,
        questionCount = 1,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private fun listItem(id: String = "q-1", active: Boolean = true) = QuestionListItem(
        id = id,
        code = "PREG-000123",
        type = QuestionType.RAZONADA,
        statement = "¿Cuál es la capital de Francia?",
        active = active,
    )

    private fun detail(id: String = "q-1") = QuestionDetail(
        id = id,
        code = "PREG-000123",
        type = QuestionType.RAZONADA,
        statement = "¿Cuál es la capital de Francia?",
        options = listOf("A", "B", "C", "D").mapIndexed { i, l ->
            QuestionOption(id = "o-$l", questionId = id, label = l, content = "Opción $l", isCorrect = i == 1, sortOrder = i)
        },
        explanation = "Porque sí.",
        source = "Fuente X",
        active = true,
        difficulty = "media",
        resources = emptyList(),
    )

    /** Un examen Ráfaga de la misma especialidad: `observeExams()` lo deja fuera y `observeAllExams()` no. */
    private val rafaga = exam.copy(id = "ex-raf", code = "RAF-0001", title = "Ráfaga de repaso", typeExam = "rafaga")

    private fun TestScope.buildViewModel(
        repository: FakeQuestionsRepository,
        exams: List<Exam> = listOf(exam),
        content: FakeContentRepository = FakeContentRepository(exams),
    ): QuestionsViewModel {
        return QuestionsViewModel(
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            questionsRepository = repository,
            contentRepository = content,
        )
    }

    @Test
    fun loadsTheGlobalBankOnStartup() = runTest {
        val repository = FakeQuestionsRepository(listResult = AppResult.Success(listOf(listItem())))
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        val success = assertIs<UiAsync.Success<QuestionPage>>(viewModel.state.value.questions)
        assertEquals(listOf("PREG-000123"), success.value.items.map { it.code })
        // La primera página, activas, lo más reciente primero: la consulta por defecto, explícita.
        assertEquals(QuestionListQuery(active = QuestionActiveFilter.ACTIVE, sort = QuestionListSort.MOST_RECENT, offset = 0, limit = 25), repository.lastQuery)
    }

    @Test
    fun backendFailureSurfacesHonestlyNotAsEmpty() = runTest {
        val repository = FakeQuestionsRepository(listResult = AppResult.Failure(AppError.Validation("boom")))
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        assertIs<UiAsync.Error>(viewModel.state.value.questions)
    }

    // -----------------------------------------------------------------------
    // Listado en el servidor (KQ0-4)
    // -----------------------------------------------------------------------

    /**
     * La búsqueda espera 300 ms tras la última tecla y viaja al servidor. Antes se
     * filtraba lo cargado. Mutaciones: quitar la espera (una consulta por tecla) o
     * no mandar `search` ponen este test en rojo.
     */
    @Test
    fun theSearchWaitsForTheTypingToStopAndTravelsToTheServer() = runTest {
        val repository = FakeQuestionsRepository(listResult = AppResult.Success(listOf(listItem())))
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()
        assertEquals(1, repository.queries.size)

        viewModel.dispatch(QuestionsIntent.Search("Fran"))
        advanceTimeBy(100)
        runCurrent()
        viewModel.dispatch(QuestionsIntent.Search("Francia"))
        runCurrent()
        advanceTimeBy(299)
        runCurrent()
        assertEquals(1, repository.queries.size, "Ha consultado antes de dejar de escribir")

        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, repository.queries.size, "Una sola consulta para toda la ráfaga de teclas")
        assertEquals("Francia", repository.lastQuery.search)
        assertEquals(0, repository.lastQuery.offset)
    }

    /** «Inactivas» llega al servidor: antes se filtraba un conjunto del que ya se habían ido. */
    @Test
    fun theStatusFilterReachesTheRepository() = runTest {
        val repository = FakeQuestionsRepository(listResult = AppResult.Success(listOf(listItem(active = false))))
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(QuestionsIntent.SetActiveFilter(QuestionActiveFilter.INACTIVE))
        advanceUntilIdle()
        assertEquals(QuestionActiveFilter.INACTIVE, repository.lastQuery.active)

        viewModel.dispatch(QuestionsIntent.SetActiveFilter(QuestionActiveFilter.ALL))
        advanceUntilIdle()
        assertEquals(QuestionActiveFilter.ALL, repository.lastQuery.active)
    }

    /** Todo cambio de filtro, orden o tamaño vuelve a la primera página; cambiar de página pide ESA página. */
    @Test
    fun changingAFilterGoesBackToTheFirstPage() = runTest {
        val repository = FakeQuestionsRepository(listResult = AppResult.Success(listOf(listItem())), total = 120)
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(QuestionsIntent.SetPageIndex(2))
        advanceUntilIdle()
        assertEquals(50, repository.lastQuery.offset)
        assertEquals(25, repository.lastQuery.limit)

        viewModel.dispatch(QuestionsIntent.SetTypeFilter(QuestionType.RAFAGA))
        advanceUntilIdle()
        assertEquals(0, viewModel.state.value.pageIndex)
        assertEquals(0, repository.lastQuery.offset)
        assertEquals(QuestionType.RAFAGA, repository.lastQuery.type)

        viewModel.dispatch(QuestionsIntent.SetPageIndex(1))
        viewModel.dispatch(QuestionsIntent.SetSpecialtyFilter("sp-1"))
        advanceUntilIdle()
        assertEquals(0, repository.lastQuery.offset)
        assertEquals("sp-1", repository.lastQuery.specialtyId)

        viewModel.dispatch(QuestionsIntent.SetPageIndex(1))
        viewModel.dispatch(QuestionsIntent.SetSort(QuestionsSort.BY_CODE))
        advanceUntilIdle()
        assertEquals(0, repository.lastQuery.offset)
        assertEquals(QuestionListSort.BY_CODE, repository.lastQuery.sort)

        viewModel.dispatch(QuestionsIntent.SetPageSize(50))
        advanceUntilIdle()
        assertEquals(50, repository.lastQuery.limit)
        assertEquals(0, repository.lastQuery.offset)
    }

    /** El total del servidor gobierna la paginación, no las filas que trae la página. */
    @Test
    fun theServerTotalDrivesThePagination() = runTest {
        val repository = FakeQuestionsRepository(listResult = AppResult.Success(listOf(listItem())), total = 60)
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        assertEquals(60, viewModel.state.value.totalQuestions())
        assertEquals(3, viewModel.state.value.totalPages())
        assertEquals(1, viewModel.state.value.visibleQuestions().size)
    }

    /** Elegir un examen pide la página de ESE examen. */
    @Test
    fun selectingAnExamFilterAsksForThatExamsPage() = runTest {
        val repository = FakeQuestionsRepository(
            listResult = AppResult.Success(listOf(listItem())),
            forExamResult = AppResult.Success(listOf(listItem().copy(examId = "ex-1", position = 3))),
        )
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(QuestionsIntent.SetExamFilter("ex-1"))
        advanceUntilIdle()

        assertEquals("ex-1", repository.lastQuery.examId)
        assertEquals(3, viewModel.state.value.visibleQuestions().single().position)
    }

    /** Sin filtros, cero es un banco vacío; con filtros, cero es «nada coincide». */
    @Test
    fun zeroResultsIsAnEmptyBankOnlyWithoutFilters() = runTest {
        val repository = FakeQuestionsRepository(listResult = AppResult.Success(emptyList()))
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()
        assertEquals(UiAsync.Empty, viewModel.state.value.questions)

        viewModel.dispatch(QuestionsIntent.SetTypeFilter(QuestionType.INMERSIVA))
        advanceUntilIdle()
        val filtered = assertIs<UiAsync.Success<QuestionPage>>(viewModel.state.value.questions)
        assertTrue(filtered.value.items.isEmpty())
    }

    /** Una página más allá del final (se archivó lo último de la última) cae a la última que existe. */
    @Test
    fun aPageBeyondTheEndFallsBackToTheLastPage() = runTest {
        val repository = FakeQuestionsRepository(
            responder = { query ->
                val items = if (query.offset < 30) listOf(listItem()) else emptyList()
                AppResult.Success(QuestionPage(items, total = 30, offset = query.offset, limit = query.limit))
            },
        )
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(QuestionsIntent.SetPageIndex(4))
        advanceUntilIdle()

        assertEquals(1, viewModel.state.value.pageIndex)
        assertEquals(25, repository.lastQuery.offset)
        assertEquals(1, viewModel.state.value.visibleQuestions().size)
    }

    // -----------------------------------------------------------------------
    // Catálogos (KQ0-5)
    // -----------------------------------------------------------------------

    /**
     * La pantalla pide sus catálogos y ve TODOS los exámenes, ráfagas incluidas.
     * Antes observaba `observeExams()` —sin ráfagas— y no refrescaba nada: sin
     * abrir Exámenes antes, el selector salía vacío. Mutación: volver a
     * `observeExams()` o quitar el refresco pone este test en rojo.
     */
    @Test
    fun catalogsAreRefreshedOnStartupAndIncludeRafagas() = runTest {
        val content = FakeContentRepository(listOf(exam, rafaga))
        val viewModel = buildViewModel(FakeQuestionsRepository(), content = content)
        advanceUntilIdle()

        assertEquals(1, content.refreshAllExamsCalls)
        assertEquals(1, content.refreshSpecialtiesCalls)
        val exams = assertIs<UiAsync.Success<List<Exam>>>(viewModel.state.value.exams)
        assertTrue(exams.value.any { it.id == "ex-raf" }, "Falta la ráfaga: ${exams.value.map { it.id }}")
        assertEquals(UiAsync.Empty, viewModel.state.value.specialties)
    }

    /** Un catálogo que no se ha podido pedir es un error, nunca una lista vacía en silencio. */
    @Test
    fun aCatalogFailureIsAnErrorNotAnEmptyList() = runTest {
        val content = FakeContentRepository(listOf(exam), examsShouldFail = true)
        val viewModel = buildViewModel(FakeQuestionsRepository(), content = content)
        advanceUntilIdle()

        assertIs<UiAsync.Error>(viewModel.state.value.exams)
    }

    /** Cambiar el tipo de un alta suelta el examen inicial que ya no lo admite. */
    @Test
    fun changingTheTypeOfANewQuestionDropsAnIncompatibleInitialExam() = runTest {
        val razonado = exam.copy(id = "ex-raz", typeExam = "razonado")
        val viewModel = buildViewModel(FakeQuestionsRepository(), content = FakeContentRepository(listOf(razonado, rafaga)))
        advanceUntilIdle()

        viewModel.dispatch(QuestionsIntent.StartCreate)
        viewModel.dispatch(QuestionsIntent.UpdateCreateExamId("ex-raz"))
        viewModel.dispatch(QuestionsIntent.UpdateType(QuestionType.RAZONADA))
        advanceUntilIdle()
        assertEquals("ex-raz", viewModel.state.value.draftCreateExamId)

        viewModel.dispatch(QuestionsIntent.UpdateType(QuestionType.RAFAGA))
        advanceUntilIdle()
        assertEquals("", viewModel.state.value.draftCreateExamId)
        assertEquals("", viewModel.state.value.draftDifficulty, "Un alta no inventa dificultad (KQ0-2)")
    }

    @Test
    fun selectingAQuestionLoadsDetailAndPopulatesTheDraft() = runTest {
        val repository = FakeQuestionsRepository(
            listResult = AppResult.Success(listOf(listItem())),
            detailResult = AppResult.Success(detail()),
        )
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(QuestionsIntent.SelectQuestion("q-1"))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("¿Cuál es la capital de Francia?", state.draftStatement)
        assertEquals("B", state.draftOptions.first { it.isCorrect }.label)
        assertFalse(state.dirty)
    }

    @Test
    fun editingAnOptionMarksDirtyAndOptionsDirty() = runTest {
        val repository = FakeQuestionsRepository(
            listResult = AppResult.Success(listOf(listItem())),
            detailResult = AppResult.Success(detail()),
        )
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()
        viewModel.dispatch(QuestionsIntent.SelectQuestion("q-1"))
        advanceUntilIdle()

        viewModel.dispatch(QuestionsIntent.UpdateOptionContent("A", "Nuevo contenido"))
        advanceUntilIdle()

        assertTrue(viewModel.state.value.dirty)
        assertTrue(viewModel.state.value.optionsDirty)
    }

    @Test
    fun savingWithoutTouchingOptionsSendsOptionsChangedFalse() = runTest {
        val repository = FakeQuestionsRepository(
            listResult = AppResult.Success(listOf(listItem())),
            detailResult = AppResult.Success(detail()),
            updateResult = AppResult.Success(detail()),
        )
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()
        viewModel.dispatch(QuestionsIntent.SelectQuestion("q-1"))
        advanceUntilIdle()

        viewModel.dispatch(QuestionsIntent.UpdateStatement("Enunciado editado"))
        advanceUntilIdle()
        viewModel.dispatch(QuestionsIntent.Save)
        advanceUntilIdle()

        assertEquals(false, repository.lastOptionsChanged)
        assertIs<UiAsync.Success<Unit>>(viewModel.state.value.saveState)
    }

    @Test
    fun backendRejectionOnSaveIsShownHonestlyNotAsSuccess() = runTest {
        val repository = FakeQuestionsRepository(
            listResult = AppResult.Success(listOf(listItem())),
            detailResult = AppResult.Success(detail()),
            updateResult = AppResult.Failure(AppError.Validation("admin_replace_question_options no disponible")),
        )
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()
        viewModel.dispatch(QuestionsIntent.SelectQuestion("q-1"))
        advanceUntilIdle()
        viewModel.dispatch(QuestionsIntent.UpdateOptionContent("A", "x"))
        advanceUntilIdle()

        viewModel.dispatch(QuestionsIntent.Save)
        advanceUntilIdle()

        assertIs<UiAsync.Error>(viewModel.state.value.saveState)
    }

    @Test
    fun blankStatementBlocksSave() = runTest {
        val repository = FakeQuestionsRepository(listResult = AppResult.Success(listOf(listItem())), detailResult = AppResult.Success(detail()))
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()
        viewModel.dispatch(QuestionsIntent.SelectQuestion("q-1"))
        advanceUntilIdle()

        viewModel.dispatch(QuestionsIntent.UpdateStatement(""))
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isDraftValid())
    }

    /** KQ2-4: la especialidad se elige; el examen inicial es opcional. */
    @Test
    fun createRequiresASpecialtyButNotAnExam() = runTest {
        val repository = FakeQuestionsRepository(listResult = AppResult.Success(emptyList()))
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(QuestionsIntent.StartCreate)
        viewModel.dispatch(QuestionsIntent.UpdateStatement("Enunciado nuevo"))
        listOf("A" to "a", "B" to "b", "C" to "c", "D" to "d").forEach { (label, text) ->
            viewModel.dispatch(QuestionsIntent.UpdateOptionContent(label, text))
        }
        advanceUntilIdle()
        assertFalse(viewModel.state.value.isDraftValid(), "sin especialidad no hay alta")

        viewModel.dispatch(QuestionsIntent.UpdateCreateSpecialtyId("sp-1"))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.isDraftValid(), "con especialidad y sin examen, sí")
        assertEquals("", viewModel.state.value.draftCreateExamId)
    }

    /**
     * KQ2-4, P1 de C: el id del alta se genera UNA vez y se conserva en el
     * reintento. Un id nuevo por intento crearía dos preguntas cuando el primer
     * intento llegó pero su respuesta se perdió.
     */
    @Test
    fun retryingACreateReusesTheSameQuestionId() = runTest {
        val repository = FakeQuestionsRepository(
            listResult = AppResult.Success(emptyList()),
            createResult = AppResult.Failure(AppError.Network("timeout")),
        )
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(QuestionsIntent.StartCreate)
        viewModel.dispatch(QuestionsIntent.UpdateCreateSpecialtyId("sp-1"))
        viewModel.dispatch(QuestionsIntent.UpdateStatement("Enunciado nuevo"))
        listOf("A" to "a", "B" to "b", "C" to "c", "D" to "d").forEach { (label, text) ->
            viewModel.dispatch(QuestionsIntent.UpdateOptionContent(label, text))
        }
        advanceUntilIdle()
        val generated = viewModel.state.value.draftQuestionId
        assertTrue(generated.isNotBlank(), "el alta nace con su id")

        viewModel.dispatch(QuestionsIntent.Save)
        advanceUntilIdle()
        assertIs<UiAsync.Error>(viewModel.state.value.saveState)
        viewModel.dispatch(QuestionsIntent.Save)
        advanceUntilIdle()

        assertEquals(listOf(generated, generated), repository.createdIds, "el reintento tiene que llevar el MISMO id")
        assertEquals(listOf("sp-1", "sp-1"), repository.createdSpecialtyIds)
        assertEquals(listOf<String?>(null, null), repository.createdExamIds, "sin examen inicial no viaja ninguno")

        // Y un alta NUEVA es otro id.
        viewModel.dispatch(QuestionsIntent.StartCreate)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.draftQuestionId != generated)
    }

    /** KQ3-2: los vínculos vienen del servidor, no de haber llegado filtrando por un examen. */
    @Test
    fun linksComeFromTheServerNotFromTheSession() = runTest {
        val link = QuestionExamLink(examId = "ex-1", examCode = "EX-0012", examTitle = "Examen", position = 5, examType = "razonado", status = "publicado")
        val repository = FakeQuestionsRepository(
            listResult = AppResult.Success(listOf(listItem())),
            detailResult = AppResult.Success(detail()),
            linksResult = AppResult.Success(listOf(link)),
        )
        val viewModel = buildViewModel(repository, exams = listOf(exam))
        advanceUntilIdle()

        viewModel.dispatch(QuestionsIntent.SelectQuestion("q-1"))
        advanceUntilIdle()

        val links = assertIs<UiAsync.Success<List<QuestionExamLink>>>(viewModel.state.value.examLinks)
        assertEquals(listOf(link), links.value)
        assertEquals(listOf("q-1"), repository.listedLinksFor)
    }

    /** Un fallo al consultar los vínculos es un fallo, no una lista vacía que parezca «en ninguno». */
    @Test
    fun aLinksFailureIsAnErrorNotAnEmptyList() = runTest {
        val repository = FakeQuestionsRepository(
            listResult = AppResult.Success(listOf(listItem())),
            detailResult = AppResult.Success(detail()),
            linksResult = AppResult.Failure(AppError.Network("sin conexión")),
        )
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(QuestionsIntent.SelectQuestion("q-1"))
        advanceUntilIdle()

        assertIs<UiAsync.Error>(viewModel.state.value.examLinks)
        assertIs<UiAsync.Success<*>>(viewModel.state.value.detail, "el detalle no depende de los vínculos")
    }

    @Test
    fun associatingToAnAlreadyLinkedExamIsRejectedClientSideWithoutACall() = runTest {
        val link = QuestionExamLink(examId = "ex-1", examCode = "EX-0012", examTitle = "Examen", position = 5)
        val repository = FakeQuestionsRepository(
            listResult = AppResult.Success(listOf(listItem())),
            detailResult = AppResult.Success(detail()),
            linksResult = AppResult.Success(listOf(link)),
        )
        val viewModel = buildViewModel(repository, exams = listOf(exam))
        advanceUntilIdle()
        viewModel.dispatch(QuestionsIntent.SelectQuestion("q-1"))
        advanceUntilIdle()

        viewModel.dispatch(QuestionsIntent.RequestAssociateExam)
        viewModel.dispatch(QuestionsIntent.UpdateAssociateExamId("ex-1"))
        viewModel.dispatch(QuestionsIntent.ConfirmAssociateExam)
        advanceUntilIdle()

        assertEquals(0, repository.linkCallCount)
        assertEquals(0, repository.updateCallCount, "vincular ya no reenvía el borrador por PATCH (D-03)")
        assertIs<UiAsync.Error>(viewModel.state.value.saveState)
    }

    /** Vincular es su propia llamada, sin posición = al final, y la lista que vuelve sustituye a la conocida. */
    @Test
    fun linkingCallsItsOwnRouteAndReplacesTheLinksWithTheResponse() = runTest {
        val returned = QuestionExamLink(examId = "ex-1", examCode = "EX-0012", examTitle = "Examen", position = 7)
        val repository = FakeQuestionsRepository(
            listResult = AppResult.Success(listOf(listItem())),
            detailResult = AppResult.Success(detail()),
            linksResult = AppResult.Success(emptyList()),
            linkResult = AppResult.Success(listOf(returned)),
        )
        val viewModel = buildViewModel(repository, exams = listOf(exam))
        advanceUntilIdle()
        viewModel.dispatch(QuestionsIntent.SelectQuestion("q-1"))
        advanceUntilIdle()

        viewModel.dispatch(QuestionsIntent.RequestAssociateExam)
        viewModel.dispatch(QuestionsIntent.UpdateAssociateExamId("ex-1"))
        viewModel.dispatch(QuestionsIntent.ConfirmAssociateExam)
        advanceUntilIdle()

        assertEquals(1, repository.linkCallCount)
        assertEquals(null, repository.lastLinkPosition, "posición vacía = al final: no se inventa un 1")
        assertEquals(0, repository.updateCallCount)
        assertEquals(listOf(returned), (viewModel.state.value.examLinks as UiAsync.Success).value)
        assertFalse(viewModel.state.value.showAssociateDialog)
    }

    @Test
    fun unlinkRefreshesLinksFromTheResponse() = runTest {
        val link = QuestionExamLink(examId = "ex-1", examCode = "EX-0012", examTitle = "Examen", position = 5)
        val repository = FakeQuestionsRepository(
            listResult = AppResult.Success(listOf(listItem())),
            detailResult = AppResult.Success(detail()),
            linksResult = AppResult.Success(listOf(link)),
            unlinkResult = AppResult.Success(emptyList()),
        )
        val viewModel = buildViewModel(repository, exams = listOf(exam))
        advanceUntilIdle()
        viewModel.dispatch(QuestionsIntent.SelectQuestion("q-1"))
        advanceUntilIdle()

        viewModel.dispatch(QuestionsIntent.RequestUnlinkExam("ex-1"))
        advanceUntilIdle()
        assertEquals("ex-1", viewModel.state.value.unlinkExamId, "quitar pide confirmación")
        assertEquals(0, repository.unlinkCallCount)

        viewModel.dispatch(QuestionsIntent.ConfirmUnlinkExam)
        advanceUntilIdle()

        assertEquals(listOf("q-1" to "ex-1"), repository.unlinked)
        assertEquals(emptyList(), (viewModel.state.value.examLinks as UiAsync.Success).value)
        assertEquals(null, viewModel.state.value.unlinkExamId)
    }

    /** KQ3-3: con vínculos el tipo está bloqueado y el intento de cambiarlo se ignora. */
    @Test
    fun theTypeCannotBeChangedWhileTheQuestionIsLinked() = runTest {
        val link = QuestionExamLink(examId = "ex-1", examCode = "EX-0012", examTitle = "Examen", position = 5)
        val repository = FakeQuestionsRepository(
            listResult = AppResult.Success(listOf(listItem())),
            detailResult = AppResult.Success(detail()),
            linksResult = AppResult.Success(listOf(link)),
        )
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()
        viewModel.dispatch(QuestionsIntent.SelectQuestion("q-1"))
        advanceUntilIdle()

        viewModel.dispatch(QuestionsIntent.UpdateType(QuestionType.RAFAGA))
        advanceUntilIdle()

        assertEquals(QuestionType.RAZONADA, viewModel.state.value.draftType)
        assertFalse(viewModel.state.value.dirty)
    }

    @Test
    fun deletingArchivesAndClearsSelection() = runTest {
        val repository = FakeQuestionsRepository(
            listResult = AppResult.Success(listOf(listItem())),
            detailResult = AppResult.Success(detail()),
            archiveResult = AppResult.Success(Unit),
        )
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()
        viewModel.dispatch(QuestionsIntent.SelectQuestion("q-1"))
        advanceUntilIdle()

        viewModel.dispatch(QuestionsIntent.RequestDelete)
        viewModel.dispatch(QuestionsIntent.ConfirmDelete)
        advanceUntilIdle()

        assertEquals(null, viewModel.state.value.selectedQuestionId)
        assertEquals(1, repository.archiveCallCount)
    }

    @Test
    fun cancelWithDirtyChangesAsksForConfirmationFirst() = runTest {
        val repository = FakeQuestionsRepository(listResult = AppResult.Success(listOf(listItem())), detailResult = AppResult.Success(detail()))
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()
        viewModel.dispatch(QuestionsIntent.SelectQuestion("q-1"))
        advanceUntilIdle()
        viewModel.dispatch(QuestionsIntent.UpdateStatement("cambiado"))
        advanceUntilIdle()

        viewModel.dispatch(QuestionsIntent.RequestCancel)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.showCancelConfirm)
        assertEquals("cambiado", viewModel.state.value.draftStatement)

        viewModel.dispatch(QuestionsIntent.ConfirmCancel)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.showCancelConfirm)
        assertEquals("¿Cuál es la capital de Francia?", viewModel.state.value.draftStatement)
    }
}

private class FakeQuestionsRepository(
    private val listResult: AppResult<List<QuestionListItem>> = AppResult.Success(emptyList()),
    private val forExamResult: AppResult<List<QuestionListItem>> = AppResult.Success(emptyList()),
    /** El total que «cuenta» el servidor; por defecto, las filas que trae la página. */
    private val total: Int? = null,
    /** Si no es `null`, contesta él a cada consulta. */
    private val responder: ((QuestionListQuery) -> AppResult<QuestionPage>)? = null,
    private val detailResult: AppResult<QuestionDetail>? = null,
    private val updateResult: AppResult<QuestionDetail>? = null,
    private val createResult: AppResult<QuestionDetail>? = null,
    private val archiveResult: AppResult<Unit> = AppResult.Success(Unit),
    private val linksResult: AppResult<List<QuestionExamLink>> = AppResult.Success(emptyList()),
    private val linkResult: AppResult<List<QuestionExamLink>>? = null,
    private val unlinkResult: AppResult<List<QuestionExamLink>>? = null,
) : QuestionsRepository {
    /** Cada consulta que ha llegado, en orden: lo que el ViewModel pidió de verdad. */
    val queries = mutableListOf<QuestionListQuery>()
    val lastQuery: QuestionListQuery get() = queries.last()
    var updateCallCount = 0
        private set
    var lastOptionsChanged: Boolean? = null
        private set
    var archiveCallCount = 0
        private set
    /** Los ids con los que se pidió cada alta: el reintento tiene que repetir el primero. */
    val createdIds = mutableListOf<String>()
    val createdSpecialtyIds = mutableListOf<String>()
    val createdExamIds = mutableListOf<String?>()
    val listedLinksFor = mutableListOf<String>()
    var linkCallCount = 0
        private set
    var lastLinkPosition: Int? = -1
        private set
    var unlinkCallCount = 0
        private set
    val unlinked = mutableListOf<Pair<String, String>>()

    override suspend fun listQuestions(query: QuestionListQuery): AppResult<QuestionPage> {
        queries += query
        responder?.let { return it(query) }
        return when (val source = if (query.examId == null) listResult else forExamResult) {
            is AppResult.Failure -> source
            is AppResult.Success -> AppResult.Success(
                QuestionPage(items = source.value, total = total ?: source.value.size, offset = query.offset, limit = query.limit),
            )
        }
    }

    override suspend fun getQuestionDetail(questionId: String): AppResult<QuestionDetail> =
        detailResult ?: AppResult.Failure(AppError.Validation("no detail configured"))

    override suspend fun createQuestion(
        questionId: String,
        specialtyId: String,
        draft: QuestionDraft,
        examId: String?,
    ): AppResult<QuestionDetail> {
        createdIds += questionId
        createdSpecialtyIds += specialtyId
        createdExamIds += examId
        return createResult ?: AppResult.Failure(AppError.Validation("admin_create_question no disponible"))
    }

    override suspend fun updateQuestion(
        questionId: String,
        draft: QuestionDraft,
        optionsChanged: Boolean,
    ): AppResult<QuestionDetail> {
        updateCallCount += 1
        lastOptionsChanged = optionsChanged
        return updateResult ?: AppResult.Failure(AppError.Validation("no update configured"))
    }

    override suspend fun archiveQuestion(questionId: String): AppResult<Unit> {
        archiveCallCount += 1
        return archiveResult
    }

    override suspend fun listQuestionExams(questionId: String): AppResult<List<QuestionExamLink>> {
        listedLinksFor += questionId
        return linksResult
    }

    override suspend fun linkQuestionToExam(questionId: String, examId: String, position: Int?): AppResult<List<QuestionExamLink>> {
        linkCallCount += 1
        lastLinkPosition = position
        return linkResult ?: AppResult.Failure(AppError.Validation("no link configured"))
    }

    override suspend fun unlinkQuestionFromExam(questionId: String, examId: String): AppResult<List<QuestionExamLink>> {
        unlinkCallCount += 1
        unlinked += questionId to examId
        return unlinkResult ?: AppResult.Failure(AppError.Validation("no unlink configured"))
    }
}

private class FakeContentRepository(
    private val exams: List<Exam>,
    private val examsShouldFail: Boolean = false,
) : com.oposicontrol.admin.domain.repository.ContentRepository {
    var refreshAllExamsCalls = 0
        private set
    var refreshSpecialtiesCalls = 0
        private set

    override fun observeSpecialties(): Flow<List<com.oposicontrol.admin.domain.model.Specialty>> = MutableStateFlow(emptyList())

    /** El cubo legacy SIN ráfagas: si el ViewModel lo observa, la ráfaga desaparece del selector. */
    override fun observeExams(): Flow<List<Exam>> = MutableStateFlow(exams.filterNot { it.typeExam == "rafaga" })

    /** La fuente única, con ráfagas (KQ0-5). */
    override fun observeAllExams(): Flow<List<Exam>> = MutableStateFlow(if (examsShouldFail) emptyList() else exams)
    override fun observeRafagas(): Flow<List<com.oposicontrol.admin.domain.model.Rafaga>> = MutableStateFlow(emptyList())
    override suspend fun refreshSpecialties(): AppResult<List<com.oposicontrol.admin.domain.model.Specialty>> {
        refreshSpecialtiesCalls += 1
        return AppResult.Success(emptyList())
    }
    override suspend fun refreshAllExams(): AppResult<List<Exam>> {
        refreshAllExamsCalls += 1
        return if (examsShouldFail) AppResult.Failure(AppError.Network("sin conexión")) else AppResult.Success(exams)
    }
    override suspend fun refreshExams() = AppResult.Success(exams.filterNot { it.typeExam == "rafaga" })
    override suspend fun refreshRafagas() = AppResult.Success(emptyList<com.oposicontrol.admin.domain.model.Rafaga>())
    override suspend fun getExamQuestions(examId: String) = AppResult.Success(emptyList<com.oposicontrol.admin.domain.model.ExamQuestion>())
    override suspend fun getRafagaQuestions(rafagaId: String) = AppResult.Success(emptyList<com.oposicontrol.admin.domain.model.RafagaQuestion>())
    override suspend fun getExamResourceAudit(examId: String) = AppResult.Failure(AppError.Validation("not used"))
    override suspend fun upsertSpecialty(specialty: com.oposicontrol.admin.domain.model.Specialty) = AppResult.Success(specialty)
    override suspend fun deleteSpecialty(specialtyId: String) = AppResult.Success(Unit)
    override suspend fun getSpecialtyCapabilities(specialtyId: String) = AppResult.Failure(AppError.Validation("not used"))
    override suspend fun saveSpecialtyCapabilities(capabilities: com.oposicontrol.admin.domain.model.SpecialtyCapabilities) = AppResult.Success(capabilities)
    override suspend fun upsertExam(exam: Exam) = AppResult.Success(exam)
    override suspend fun publishExam(examId: String) = AppResult.Failure(AppError.Validation("not used"))
    override suspend fun unpublishExam(examId: String) = AppResult.Failure(AppError.Validation("not used"))
    override suspend fun deleteExam(examId: String) = AppResult.Success(Unit)
    override suspend fun upsertRafaga(rafaga: com.oposicontrol.admin.domain.model.Rafaga) = AppResult.Success(rafaga)
    override suspend fun deleteRafaga(rafagaId: String) = AppResult.Success(Unit)
    override suspend fun upsertQuestion(question: com.oposicontrol.admin.domain.model.ExamQuestion) = AppResult.Success(question)
    override suspend fun importQuestions(examId: String, questions: List<com.oposicontrol.admin.domain.model.ExamQuestion>) = AppResult.Success(questions)
    override suspend fun moveQuestion(
        examId: String,
        questionId: String,
        direction: com.oposicontrol.admin.domain.model.QuestionMoveDirection,
    ) = AppResult.Success(emptyList<com.oposicontrol.admin.domain.model.ExamQuestion>())
    override suspend fun upsertRafagaQuestion(question: com.oposicontrol.admin.domain.model.RafagaQuestion) = AppResult.Success(question)
    override suspend fun deleteRafagaQuestion(rafagaId: String, questionId: String) = AppResult.Success(Unit)
    override suspend fun moveRafagaQuestion(
        rafagaId: String,
        questionId: String,
        direction: com.oposicontrol.admin.domain.model.QuestionMoveDirection,
    ) = AppResult.Success(emptyList<com.oposicontrol.admin.domain.model.RafagaQuestion>())
    override suspend fun uploadResource(
        questionId: String,
        resourceId: String,
        type: com.oposicontrol.admin.domain.model.QuestionResourceType,
        localPathOrUrl: String,
        title: String,
    ) = AppResult.Failure(AppError.Validation("not used"))
    override suspend fun deleteResource(questionId: String, resourceId: String) = AppResult.Success(Unit)
    override suspend fun uploadExamCover(examId: String, localPathOrUri: String) = AppResult.Failure(AppError.Validation("not used"))
    override suspend fun clearExamCover(examId: String) = AppResult.Failure(AppError.Validation("not used"))
}
