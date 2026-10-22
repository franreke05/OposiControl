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
import com.oposicontrol.admin.domain.model.QuestionResource
import com.oposicontrol.admin.domain.model.QuestionResourceType
import com.oposicontrol.admin.domain.model.QuestionTag
import com.oposicontrol.admin.domain.model.Tag
import com.oposicontrol.admin.domain.model.QuestionType
import com.oposicontrol.admin.domain.repository.QuestionActiveFilter
import com.oposicontrol.admin.domain.repository.QuestionResourceDraft
import com.oposicontrol.admin.domain.repository.QuestionResourceFile
import com.oposicontrol.admin.domain.repository.QuestionResourceKind
import com.oposicontrol.admin.domain.repository.QuestionsRepository
import com.oposicontrol.admin.domain.repository.TagsRepository
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
        tags: FakeTagsRepository = FakeTagsRepository(),
    ): QuestionsViewModel {
        return QuestionsViewModel(
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            questionsRepository = repository,
            contentRepository = content,
            tagsRepository = tags,
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
        viewModel.dispatch(QuestionsIntent.SetActiveFilter(QuestionActiveFilter.ALL))
        advanceUntilIdle()
        assertEquals(0, repository.lastQuery.offset)
        assertEquals(QuestionActiveFilter.ALL, repository.lastQuery.active)

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
        // M8: la pregunta no tiene especialidad, así que esta pantalla ya no pide ese catálogo.
        assertEquals(0, content.refreshSpecialtiesCalls)
        val exams = assertIs<UiAsync.Success<List<Exam>>>(viewModel.state.value.exams)
        assertTrue(exams.value.any { it.id == "ex-raf" }, "Falta la ráfaga: ${exams.value.map { it.id }}")
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

    /** M8 · TEST 1/10: un alta no necesita especialidad (no existe) ni examen inicial. */
    @Test
    fun createNeedsNeitherASpecialtyNorAnExam() = runTest {
        val repository = FakeQuestionsRepository(listResult = AppResult.Success(emptyList()))
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()

        viewModel.dispatch(QuestionsIntent.StartCreate)
        viewModel.dispatch(QuestionsIntent.UpdateStatement("Enunciado nuevo"))
        listOf("A" to "a", "B" to "b", "C" to "c", "D" to "d").forEach { (label, text) ->
            viewModel.dispatch(QuestionsIntent.UpdateOptionContent(label, text))
        }
        advanceUntilIdle()
        assertTrue(viewModel.state.value.isDraftValid(), "sin especialidad ni examen, el alta es válida")
        assertEquals("", viewModel.state.value.draftCreateExamId)

        viewModel.dispatch(QuestionsIntent.UpdateCreateExamId("ex-1"))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.isDraftValid(), "con examen inicial, también")
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
        assertEquals(listOf<String?>(null, null), repository.createdExamIds, "sin examen inicial no viaja ninguno")

        // Y un alta NUEVA es otro id.
        viewModel.dispatch(QuestionsIntent.StartCreate)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.draftQuestionId != generated)
    }

    /**
     * M8 · TEST 9: el examen inicial viaja con el alta, en la misma llamada, sea de la
     * especialidad que sea (el ViewModel ni la conoce) y esté en borrador o publicado.
     */
    @Test
    fun createWithAnInitialExamSendsItInTheSameCallAndNoSpecialty() = runTest {
        val draftExamOtherSpecialty = rafaga.copy(id = "ex-raf-enf", specialtyId = "sp-2", specialtyName = "Enfermería", status = ExamStatus.DRAFT)
        val repository = FakeQuestionsRepository(
            listResult = AppResult.Success(emptyList()),
            createResult = AppResult.Success(detail("q-nueva")),
        )
        val viewModel = buildViewModel(repository, content = FakeContentRepository(listOf(exam, rafaga, draftExamOtherSpecialty)))
        advanceUntilIdle()

        viewModel.dispatch(QuestionsIntent.StartCreate)
        viewModel.dispatch(QuestionsIntent.UpdateType(QuestionType.RAFAGA))
        viewModel.dispatch(QuestionsIntent.UpdateStatement("Enunciado nuevo"))
        listOf("A" to "a", "B" to "b", "C" to "c", "D" to "d").forEach { (label, text) ->
            viewModel.dispatch(QuestionsIntent.UpdateOptionContent(label, text))
        }
        viewModel.dispatch(QuestionsIntent.UpdateCreateExamId("ex-raf-enf"))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.isDraftValid())

        viewModel.dispatch(QuestionsIntent.Save)
        advanceUntilIdle()

        assertEquals(listOf<String?>("ex-raf-enf"), repository.createdExamIds)
        assertIs<UiAsync.Success<Unit>>(viewModel.state.value.saveState)
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

    /**
     * M8 · TEST 4: si el servidor rechaza un vínculo (tipo incompatible), el rechazo se dice como
     * error, el diálogo sigue abierto y los vínculos conocidos no cambian.
     */
    @Test
    fun aRejectedLinkIsShownAsAnErrorAndLeavesTheKnownLinksAlone() = runTest {
        val existing = QuestionExamLink(examId = "ex-1", examCode = "EX-0012", examTitle = "Examen", position = 1)
        val repository = FakeQuestionsRepository(
            listResult = AppResult.Success(listOf(listItem())),
            detailResult = AppResult.Success(detail()),
            linksResult = AppResult.Success(listOf(existing)),
            linkResult = AppResult.Failure(AppError.Validation("El tipo de la pregunta no corresponde al del examen")),
        )
        val viewModel = buildViewModel(repository, exams = listOf(exam, rafaga))
        advanceUntilIdle()
        viewModel.dispatch(QuestionsIntent.SelectQuestion("q-1"))
        advanceUntilIdle()

        viewModel.dispatch(QuestionsIntent.RequestAssociateExam)
        viewModel.dispatch(QuestionsIntent.UpdateAssociateExamId("ex-raf"))
        viewModel.dispatch(QuestionsIntent.ConfirmAssociateExam)
        advanceUntilIdle()

        assertIs<UiAsync.Error>(viewModel.state.value.saveState)
        assertTrue(viewModel.state.value.showAssociateDialog, "el diálogo sigue abierto para corregir")
        assertEquals(listOf(existing), (viewModel.state.value.examLinks as UiAsync.Success).value)
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

    // -----------------------------------------------------------------
    // Recursos de una pregunta inmersiva (KQ4) — P0-4
    // -----------------------------------------------------------------

    private fun immersiveDetail(resources: List<QuestionResource> = emptyList()) =
        detail().copy(type = QuestionType.INMERSIVA, resources = resources)

    private fun resource(id: String = "r-1", title: String = "Audio del caso") = QuestionResource(
        id = id,
        questionId = "q-1",
        type = QuestionResourceType.AUDIO,
        title = title,
        textBody = null,
        storagePath = "preguntas/q-1/$id.mp3",
        publicUrl = "https://storage.example/$id?firma=abc",
        mimeType = "audio/mpeg",
        sortOrder = 0,
    )

    private fun TestScope.openImmersiveQuestion(repository: FakeQuestionsRepository): QuestionsViewModel {
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()
        viewModel.dispatch(QuestionsIntent.SelectQuestion("q-1"))
        advanceUntilIdle()
        return viewModel
    }

    /** Abre el diálogo y deja listo un audio con título: lo mínimo que admite el alta. */
    private fun QuestionsViewModel.fillAudioResource() {
        dispatch(QuestionsIntent.RequestAddResource)
        dispatch(QuestionsIntent.UpdateResourceKind(QuestionResourceKind.AUDIO))
        dispatch(QuestionsIntent.UpdateResourceTitle("Audio del caso"))
        dispatch(
            QuestionsIntent.PickResourceFile(
                QuestionResourceFile(fileName = "caso.mp3", mimeType = "audio/mpeg", bytes = byteArrayOf(1, 2, 3)),
            ),
        )
    }

    /** Un tipo de archivo va por la ruta multipart, con el id de idempotencia. */
    @Test
    fun addingAFileResourceUploadsItWithAStableId() = runTest {
        val repository = FakeQuestionsRepository(
            listResult = AppResult.Success(listOf(listItem())),
            detailResult = AppResult.Success(immersiveDetail()),
            uploadResourceResult = AppResult.Failure(AppError.Network("timeout")),
        )
        val viewModel = openImmersiveQuestion(repository)

        viewModel.dispatch(QuestionsIntent.RequestAddResource)
        viewModel.dispatch(QuestionsIntent.UpdateResourceKind(QuestionResourceKind.AUDIO))
        viewModel.dispatch(QuestionsIntent.UpdateResourceTitle("Audio del caso"))
        viewModel.dispatch(
            QuestionsIntent.PickResourceFile(
                QuestionResourceFile(fileName = "caso.mp3", mimeType = "audio/mpeg", bytes = byteArrayOf(1, 2, 3)),
            ),
        )
        advanceUntilIdle()
        viewModel.dispatch(QuestionsIntent.ConfirmAddResource)
        advanceUntilIdle()
        // Reintento tras el fallo: el diálogo sigue abierto y el id NO se regenera.
        viewModel.dispatch(QuestionsIntent.ConfirmAddResource)
        advanceUntilIdle()

        assertEquals(2, repository.uploadedResources.size)
        assertEquals(1, repository.uploadedResourceIds.toSet().size, "el reintento repite el id del primer intento")
        val (questionId, draft, file) = repository.uploadedResources.first()
        assertEquals("q-1", questionId)
        assertEquals(QuestionResourceKind.AUDIO, draft.kind)
        assertEquals("caso.mp3", file.fileName)
        assertEquals("audio/mpeg", file.mimeType)
    }

    /**
     * Un rechazo del backend —el 422 «sólo las inmersivas admiten recursos», por
     * ejemplo— llega a la pantalla como error, con SU mensaje. Nunca como un éxito
     * silencioso.
     */
    @Test
    fun aBackendRejectionSurfacesAsAnErrorWithItsOwnMessage() = runTest {
        val backendMessage = "Sólo las preguntas inmersivas admiten recursos. Cambia el tipo de la pregunta."
        val repository = FakeQuestionsRepository(
            listResult = AppResult.Success(listOf(listItem())),
            detailResult = AppResult.Success(immersiveDetail()),
            uploadResourceResult = AppResult.Failure(
                AppError.Validation(backendMessage, code = "QUESTION_TYPE_REJECTS_RESOURCES"),
            ),
        )
        val viewModel = openImmersiveQuestion(repository)

        viewModel.fillAudioResource()
        advanceUntilIdle()
        viewModel.dispatch(QuestionsIntent.ConfirmAddResource)
        advanceUntilIdle()

        val failure = assertIs<UiAsync.Error>(viewModel.state.value.resourceSaveState)
        assertEquals(backendMessage, failure.error.message)
        // El diálogo NO se cierra: cerrarlo sobre un fallo se lee como que se guardó.
        assertTrue(viewModel.state.value.showResourceDialog)
        // Y no se relee nada: no hubo escritura que refrescar.
        assertTrue(repository.listedResourcesFor.isEmpty())
    }

    /** Borrar pide confirmación y sólo después llama a la ruta de archivado. */
    @Test
    fun deletingAResourceConfirmsFirstAndThenCallsTheRepository() = runTest {
        val repository = FakeQuestionsRepository(
            listResult = AppResult.Success(listOf(listItem())),
            detailResult = AppResult.Success(immersiveDetail(listOf(resource()))),
            deleteResourceResult = AppResult.Success(resource()),
            resourcesResult = AppResult.Success(emptyList()),
        )
        val viewModel = openImmersiveQuestion(repository)

        viewModel.dispatch(QuestionsIntent.RequestDeleteResource("r-1"))
        advanceUntilIdle()
        assertEquals("r-1", viewModel.state.value.deleteResourceId)
        assertTrue(repository.deletedResources.isEmpty(), "pedir no es borrar")

        viewModel.dispatch(QuestionsIntent.ConfirmDeleteResource)
        advanceUntilIdle()

        assertEquals(listOf("q-1" to "r-1"), repository.deletedResources)
        assertEquals(null, viewModel.state.value.deleteResourceId)
        val detail = assertIs<UiAsync.Success<QuestionDetail>>(viewModel.state.value.detail)
        assertTrue(detail.value.resources.isEmpty(), "la lista se relee del servidor")
    }

    /** Un fallo al archivar se dice, y el recurso sigue en la lista. */
    @Test
    fun aFailedDeleteKeepsTheResourceAndReportsIt() = runTest {
        val repository = FakeQuestionsRepository(
            listResult = AppResult.Success(listOf(listItem())),
            detailResult = AppResult.Success(immersiveDetail(listOf(resource()))),
            deleteResourceResult = AppResult.Failure(AppError.Validation("El recurso no existe.")),
        )
        val viewModel = openImmersiveQuestion(repository)

        viewModel.dispatch(QuestionsIntent.RequestDeleteResource("r-1"))
        viewModel.dispatch(QuestionsIntent.ConfirmDeleteResource)
        advanceUntilIdle()

        assertIs<UiAsync.Error>(viewModel.state.value.resourceDeleteState)
        val detail = assertIs<UiAsync.Success<QuestionDetail>>(viewModel.state.value.detail)
        assertEquals(1, detail.value.resources.size)
    }

    /**
     * Cambiar de tipo SUELTA el archivo elegido: un `.mp3` no vale como imagen, y
     * dejarlo elegido sólo llevaría a un rechazo del servidor al final de la subida.
     */
    @Test
    fun switchingTheResourceKindDropsTheChosenFile() = runTest {
        val repository = FakeQuestionsRepository(
            listResult = AppResult.Success(listOf(listItem())),
            detailResult = AppResult.Success(immersiveDetail()),
        )
        val viewModel = openImmersiveQuestion(repository)

        viewModel.fillAudioResource()
        advanceUntilIdle()
        assertEquals("caso.mp3", viewModel.state.value.resourceForm.file?.fileName)

        viewModel.dispatch(QuestionsIntent.UpdateResourceKind(QuestionResourceKind.IMAGEN))
        advanceUntilIdle()
        assertEquals(null, viewModel.state.value.resourceForm.file, "el audio no vale como imagen")
        assertEquals("Audio del caso", viewModel.state.value.resourceForm.title, "el título sobrevive")
    }

    /** Una pregunta sin guardar no tiene id al que colgar nada: no se llama al servidor. */
    @Test
    fun aQuestionThatDoesNotExistYetCannotTakeResources() = runTest {
        val repository = FakeQuestionsRepository(listResult = AppResult.Success(emptyList()))
        val viewModel = buildViewModel(repository)
        advanceUntilIdle()
        viewModel.dispatch(QuestionsIntent.StartCreate)
        viewModel.dispatch(QuestionsIntent.UpdateType(QuestionType.INMERSIVA))
        advanceUntilIdle()

        assertFalse(viewModel.state.value.canAttachResources())

        viewModel.fillAudioResource()
        advanceUntilIdle()
        viewModel.dispatch(QuestionsIntent.ConfirmAddResource)
        advanceUntilIdle()

        assertTrue(repository.uploadedResources.isEmpty())
        assertIs<UiAsync.Error>(viewModel.state.value.resourceSaveState)
    }

    /**
     * La escritura fue bien y la relectura no: se dice exactamente eso. Decir «no se
     * ha podido añadir» sería mentir sobre lo que ya está en la base.
     */
    @Test
    fun aFailedRefreshAfterASuccessfulWriteSaysSoWithoutDenyingTheWrite() = runTest {
        val repository = FakeQuestionsRepository(
            listResult = AppResult.Success(listOf(listItem())),
            detailResult = AppResult.Success(immersiveDetail()),
            uploadResourceResult = AppResult.Success(resource()),
            resourcesResult = AppResult.Failure(AppError.Network("sin conexión")),
        )
        val viewModel = openImmersiveQuestion(repository)

        viewModel.fillAudioResource()
        advanceUntilIdle()
        viewModel.dispatch(QuestionsIntent.ConfirmAddResource)
        advanceUntilIdle()

        assertEquals(1, repository.uploadedResources.size)
        val failure = assertIs<UiAsync.Error>(viewModel.state.value.resourceSaveState)
        assertEquals(QUESTION_RESOURCES_STALE_NOTE, failure.error.message)
    }

    // -----------------------------------------------------------------------
    // Etiquetas (temas)
    // -----------------------------------------------------------------------

    private val tagConst = Tag(id = "t-const", name = "Constitución", slug = "constitucion", origin = "manual")
    private val tagAdmin = Tag(id = "t-admin", name = "Derecho administrativo", slug = "derecho-administrativo", origin = "manual")
    private val tagOld = Tag(id = "t-old", name = "Antigua", slug = "antigua", origin = "manual", active = false)

    private fun TestScope.selectedWithTags(
        repository: FakeQuestionsRepository,
        tags: FakeTagsRepository = FakeTagsRepository(refreshResult = AppResult.Success(listOf(tagConst, tagAdmin, tagOld))),
    ): QuestionsViewModel {
        val viewModel = buildViewModel(repository, tags = tags)
        advanceUntilIdle()
        viewModel.dispatch(QuestionsIntent.SelectQuestion("q-1"))
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun selectingAQuestionLoadsItsTagsAndOnlyActiveCatalogTagsAreOffered() = runTest {
        val repository = FakeQuestionsRepository(
            detailResult = AppResult.Success(detail()),
            questionTagsResult = AppResult.Success(listOf(QuestionTag(id = "t-const", name = "Constitución"))),
        )
        val viewModel = selectedWithTags(repository)

        val state = viewModel.state.value
        assertEquals(listOf("q-1"), repository.listedTagsFor)
        assertEquals(listOf("t-const"), state.draftTagIds)
        assertTrue(state.canEditTags())
        assertFalse(state.tagsDirty())
        // La desactivada no se ofrece; la ya elegida tampoco.
        assertEquals(listOf("t-admin"), state.addableTags().map { it.id })
    }

    @Test
    fun addingAndRemovingTagsOnlyEditsTheSelectionUntilSaved() = runTest {
        val repository = FakeQuestionsRepository(
            detailResult = AppResult.Success(detail()),
            questionTagsResult = AppResult.Success(listOf(QuestionTag(id = "t-const", name = "Constitución"))),
            setTagsResult = { ids -> AppResult.Success(ids.map { QuestionTag(id = it, name = it) }) },
        )
        val viewModel = selectedWithTags(repository)

        viewModel.dispatch(QuestionsIntent.AddQuestionTag("t-admin"))
        viewModel.dispatch(QuestionsIntent.RemoveQuestionTag("t-const"))
        advanceUntilIdle()
        assertEquals(listOf("t-admin"), viewModel.state.value.draftTagIds)
        assertTrue(viewModel.state.value.tagsDirty())
        assertTrue(repository.savedTagSets.isEmpty(), "nada viaja hasta pulsar Guardar etiquetas")
        // Las etiquetas no ensucian el borrador de la pregunta: tienen su propio guardado.
        assertFalse(viewModel.state.value.dirty)

        viewModel.dispatch(QuestionsIntent.SaveQuestionTags)
        advanceUntilIdle()

        assertEquals(listOf("q-1" to listOf("t-admin")), repository.savedTagSets)
        val state = viewModel.state.value
        assertIs<UiAsync.Success<Unit>>(state.tagsSaveState)
        assertEquals(listOf("t-admin"), (state.questionTags as UiAsync.Success).value.map { it.id })
        assertFalse(state.tagsDirty())
    }

    @Test
    fun aFailedTagSaveKeepsTheSelectionAndSaysWhy() = runTest {
        val repository = FakeQuestionsRepository(
            detailResult = AppResult.Success(detail()),
            setTagsResult = { AppResult.Failure(AppError.Validation("Estas etiquetas no existen o están desactivadas: x.")) },
        )
        val viewModel = selectedWithTags(repository)

        viewModel.dispatch(QuestionsIntent.AddQuestionTag("t-const"))
        viewModel.dispatch(QuestionsIntent.SaveQuestionTags)
        advanceUntilIdle()

        val state = viewModel.state.value
        val error = assertIs<UiAsync.Error>(state.tagsSaveState)
        assertTrue(error.error.message.contains("desactivadas"), error.error.message)
        assertEquals(listOf("t-const"), state.draftTagIds)
        assertTrue(state.tagsDirty())
        assertEquals(Pair(error.error.message, true), questionTagsSaveMessage(state))
    }

    @Test
    fun theSelectionStopsAtTheServerMaximum() = runTest {
        val catalog = (1..21).map { Tag(id = "t-$it", name = "Tema $it", slug = "tema-$it", origin = "manual") }
        val viewModel = selectedWithTags(
            FakeQuestionsRepository(detailResult = AppResult.Success(detail())),
            tags = FakeTagsRepository(refreshResult = AppResult.Success(catalog)),
        )

        catalog.forEach { viewModel.dispatch(QuestionsIntent.AddQuestionTag(it.id)) }
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(QUESTION_TAGS_MAX, state.draftTagIds.size)
        assertIs<UiAsync.Error>(state.tagsSaveState)
    }

    @Test
    fun creatingATagAddsItToTheCatalogAndToTheSelectionWithoutSavingTheQuestionTags() = runTest {
        val created = Tag(id = "t-new", name = "Igualdad", slug = "igualdad", origin = "manual")
        val tags = FakeTagsRepository(
            refreshResult = AppResult.Success(listOf(tagConst)),
            upsertResult = { AppResult.Success(created) },
        )
        val repository = FakeQuestionsRepository(detailResult = AppResult.Success(detail()))
        val viewModel = selectedWithTags(repository, tags)

        viewModel.dispatch(QuestionsIntent.RequestCreateTag)
        viewModel.dispatch(QuestionsIntent.UpdateNewTagName("  Igualdad "))
        viewModel.dispatch(QuestionsIntent.ConfirmCreateTag)
        advanceUntilIdle()

        // Sólo el nombre: el slug lo deriva el servidor.
        assertEquals(listOf(Tag(id = "", name = "Igualdad", slug = "", origin = "manual")), tags.upserted)
        val state = viewModel.state.value
        assertFalse(state.showCreateTagDialog)
        assertEquals(listOf("t-new"), state.draftTagIds)
        assertEquals(listOf(QuestionTagChip("t-new", "Igualdad", active = true)), state.selectedTagChips())
        assertTrue(state.tagsDirty())
        assertTrue(repository.savedTagSets.isEmpty())
    }

    @Test
    fun creatingATagWithoutNameOrWithAnExistingNameDoesNotCallTheServer() = runTest {
        val tags = FakeTagsRepository(refreshResult = AppResult.Success(listOf(tagConst)))
        val viewModel = selectedWithTags(FakeQuestionsRepository(detailResult = AppResult.Success(detail())), tags)

        viewModel.dispatch(QuestionsIntent.RequestCreateTag)
        viewModel.dispatch(QuestionsIntent.UpdateNewTagName("   "))
        viewModel.dispatch(QuestionsIntent.ConfirmCreateTag)
        advanceUntilIdle()
        assertIs<UiAsync.Error>(viewModel.state.value.createTagState)
        assertTrue(viewModel.state.value.showCreateTagDialog)

        viewModel.dispatch(QuestionsIntent.UpdateNewTagName("constitución"))
        viewModel.dispatch(QuestionsIntent.ConfirmCreateTag)
        advanceUntilIdle()

        assertTrue(tags.upserted.isEmpty(), "una etiqueta con ese nombre ya existe: se reutiliza")
        assertEquals(listOf("t-const"), viewModel.state.value.draftTagIds)
        assertFalse(viewModel.state.value.showCreateTagDialog)
    }

    @Test
    fun aNewQuestionCannotBeTaggedAndTagsFailingToLoadIsAnError() = runTest {
        val repository = FakeQuestionsRepository(
            detailResult = AppResult.Success(detail()),
            questionTagsResult = AppResult.Failure(AppError.Validation("boom")),
        )
        val viewModel = selectedWithTags(repository)
        assertIs<UiAsync.Error>(viewModel.state.value.questionTags)
        assertFalse(viewModel.state.value.canEditTags())

        viewModel.dispatch(QuestionsIntent.StartCreate)
        advanceUntilIdle()
        assertEquals(UiAsync.Idle, viewModel.state.value.questionTags)
        assertFalse(viewModel.state.value.canEditTags())

        viewModel.dispatch(QuestionsIntent.SaveQuestionTags)
        advanceUntilIdle()
        assertTrue(repository.savedTagSets.isEmpty())
        assertIs<UiAsync.Error>(viewModel.state.value.tagsSaveState)
    }
}

/**
 * El formulario, sin ViewModel: las reglas son puras y se comprueban como tales.
 * Los tres tipos (imagen, audio, vídeo) llevan siempre título y archivo.
 */
class QuestionResourceFormTest {

    private fun resource(type: QuestionResourceType) = QuestionResource(
        id = "r", questionId = "q-1", type = type, title = "t", textBody = null,
        storagePath = "p", publicUrl = null, mimeType = null, sortOrder = 0,
    )

    @Test
    fun anImmersiveIsUsableOnlyWithAtLeastOneAudio() {
        val base = QuestionDetail(
            id = "q-1", code = "P", type = QuestionType.INMERSIVA, statement = "s", options = emptyList(),
            explanation = "", source = "", active = true, difficulty = "media", resources = emptyList(),
        )
        assertTrue(base.lacksRequiredAudio(), "sin recursos")
        assertTrue(base.copy(resources = listOf(resource(QuestionResourceType.IMAGE))).lacksRequiredAudio(), "sólo imagen")
        assertFalse(base.copy(resources = listOf(resource(QuestionResourceType.AUDIO))).lacksRequiredAudio(), "con audio")
        assertFalse(base.copy(type = QuestionType.RAFAGA).lacksRequiredAudio(), "una ráfaga no pide audio")
    }

    private fun file() = QuestionResourceFile(fileName = "a.png", mimeType = "image/png", bytes = byteArrayOf(1))

    @Test
    fun onlyImageAudioAndVideoExist() {
        assertEquals(
            listOf("imagen", "audio", "video"),
            QuestionResourceKind.entries.map { it.wireValue },
        )
    }

    @Test
    fun everyKindNeedsATitle() {
        QuestionResourceKind.entries.forEach { kind ->
            assertEquals(
                "El recurso necesita un título.",
                QuestionResourceForm(kind = kind, title = "   ", file = file()).validationError(),
                "$kind sin título",
            )
        }
    }

    @Test
    fun everyKindWithoutAFileCannotBeSaved() {
        QuestionResourceKind.entries.forEach { kind ->
            assertEquals(
                "Elige el archivo que quieres subir.",
                QuestionResourceForm(kind = kind, title = "Material").validationError(),
                "$kind sin archivo",
            )
            assertEquals(null, QuestionResourceForm(kind = kind, title = "Material", file = file()).validationError())
        }
    }

    @Test
    fun theDraftCarriesTitleAndDescriptionTrimmed() {
        val draft = QuestionResourceForm(
            kind = QuestionResourceKind.VIDEO,
            title = "  Caso  ",
            description = " Plano general ",
            file = file(),
        ).toDraft()
        assertEquals(QuestionResourceKind.VIDEO, draft.kind)
        assertEquals("Caso", draft.title)
        assertEquals("Plano general", draft.description)
        assertEquals(null, draft.position)
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
    private val uploadResourceResult: AppResult<QuestionResource>? = null,
    private val deleteResourceResult: AppResult<QuestionResource>? = null,
    private val resourcesResult: AppResult<List<QuestionResource>> = AppResult.Success(emptyList()),
    private val questionTagsResult: AppResult<List<QuestionTag>> = AppResult.Success(emptyList()),
    private val setTagsResult: ((List<String>) -> AppResult<List<QuestionTag>>)? = null,
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
        draft: QuestionDraft,
        examId: String?,
    ): AppResult<QuestionDetail> {
        createdIds += questionId
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

    // --- Etiquetas ---

    val listedTagsFor = mutableListOf<String>()
    val savedTagSets = mutableListOf<Pair<String, List<String>>>()

    override suspend fun getQuestionTags(questionId: String): AppResult<List<QuestionTag>> {
        listedTagsFor += questionId
        return questionTagsResult
    }

    override suspend fun setQuestionTags(questionId: String, tagIds: List<String>): AppResult<List<QuestionTag>> {
        savedTagSets += questionId to tagIds
        return setTagsResult?.invoke(tagIds) ?: AppResult.Failure(AppError.Validation("no set tags configured"))
    }

    // --- Recursos (KQ4) ---

    /** Lo que llegó a cada ruta de escritura: es lo que prueba que la pantalla escribe de verdad. */
    val uploadedResources = mutableListOf<Triple<String, QuestionResourceDraft, QuestionResourceFile>>()
    /** Los ids con los que se pidió cada subida: el reintento tiene que repetir el primero. */
    val uploadedResourceIds = mutableListOf<String>()
    val deletedResources = mutableListOf<Pair<String, String>>()
    val listedResourcesFor = mutableListOf<String>()

    override suspend fun listQuestionResources(questionId: String): AppResult<List<QuestionResource>> {
        listedResourcesFor += questionId
        return resourcesResult
    }

    override suspend fun uploadQuestionResourceFile(
        questionId: String,
        resourceId: String,
        draft: QuestionResourceDraft,
        file: QuestionResourceFile,
    ): AppResult<QuestionResource> {
        uploadedResourceIds += resourceId
        uploadedResources += Triple(questionId, draft, file)
        return uploadResourceResult ?: AppResult.Failure(AppError.Validation("no upload resource configured"))
    }

    override suspend fun updateQuestionResource(
        questionId: String,
        resourceId: String,
        draft: QuestionResourceDraft,
    ): AppResult<QuestionResource> =
        AppResult.Failure(AppError.Validation("no update resource configured"))

    override suspend fun deleteQuestionResource(questionId: String, resourceId: String): AppResult<QuestionResource> {
        deletedResources += questionId to resourceId
        return deleteResourceResult ?: AppResult.Failure(AppError.Validation("no delete resource configured"))
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

private class FakeTagsRepository(
    private val refreshResult: AppResult<List<Tag>> = AppResult.Success(emptyList()),
    private val upsertResult: ((Tag) -> AppResult<Tag>)? = null,
) : TagsRepository {
    val upserted = mutableListOf<Tag>()

    override fun observeTags(): Flow<List<Tag>> = MutableStateFlow(emptyList())
    override suspend fun refreshTags(): AppResult<List<Tag>> = refreshResult
    override suspend fun upsertTag(tag: Tag): AppResult<Tag> {
        upserted += tag
        return upsertResult?.invoke(tag) ?: AppResult.Failure(AppError.Validation("no upsert configured"))
    }
    override suspend fun deleteTag(tagId: String): AppResult<Unit> = AppResult.Success(Unit)
}
