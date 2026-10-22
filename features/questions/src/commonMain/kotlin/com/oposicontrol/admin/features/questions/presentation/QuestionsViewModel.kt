package com.oposicontrol.admin.features.questions.presentation

import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.id.randomUuid
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.QuestionDraft
import com.oposicontrol.admin.domain.model.QuestionType
import com.oposicontrol.admin.domain.model.Tag
import com.oposicontrol.admin.domain.repository.ContentRepository
import com.oposicontrol.admin.domain.repository.QuestionsRepository
import com.oposicontrol.admin.domain.repository.TagsRepository
import com.oposicontrol.admin.domain.validation.EtiquetaValidator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * ViewModel de Preguntas (banco global, JVM/Desktop).
 *
 * Depende de [QuestionsRepository] (propio, ver diseño aprobado) para todo lo
 * específico de preguntas, y de [ContentRepository] sólo para los catálogos de
 * exámenes — capacidad que ya existía y que usan otras
 * pantallas; no añade nada a ese contrato.
 *
 * **Los catálogos los pide esta pantalla (KQ0-5, QN-10).** Antes sólo observaba
 * `observeExams()` —sin ráfagas— y nunca los refrescaba: si no se había abierto
 * Exámenes antes, el selector de examen salía vacío y una pregunta de ráfaga no se
 * podía vincular. Un fallo al pedirlos es un `Error`, nunca una lista vacía.
 */
class QuestionsViewModel(
    dispatcherProvider: DispatcherProvider,
    private val questionsRepository: QuestionsRepository,
    private val contentRepository: ContentRepository,
    /** Catálogo de etiquetas y alta de una nueva desde el editor; las de la pregunta van por [questionsRepository]. */
    private val tagsRepository: TagsRepository,
) : MviViewModel<QuestionsState, QuestionsIntent>(
    initialState = QuestionsState(),
    dispatcherProvider = dispatcherProvider,
) {
    /** La búsqueda que espera a que se deje de teclear ([QUESTION_SEARCH_DEBOUNCE_MILLIS]). */
    private var searchJob: Job? = null

    /**
     * Número de la última carga pedida. Una respuesta de una carga anterior —la
     * búsqueda de hace dos teclas— no puede pisar la de la consulta vigente.
     */
    private var loadGeneration = 0

    /**
     * Número de la última selección. Los vínculos se cargan en PARALELO al detalle
     * (KQ3-2), así que los de una pregunta que ya no está seleccionada no pueden
     * aterrizar sobre la siguiente.
     */
    private var selectionGeneration = 0

    init {
        scope.launch {
            contentRepository.observeAllExams().collectLatest { exams ->
                updateState { it.copy(exams = seededCatalog(exams, it.exams)) }
            }
        }
        scope.launch { refreshCatalogs() }
        scope.launch { refreshTagCatalog() }
        scope.launch { loadQuestions() }
    }

    override suspend fun handleIntent(intent: QuestionsIntent) {
        when (intent) {
            QuestionsIntent.Refresh -> {
                if (currentState().exams is UiAsync.Error) {
                    refreshCatalogs()
                }
                reloadQuestions()
            }

            // La búsqueda espera a que se deje de escribir: cada tecla sería una
            // consulta al servidor. El resto de filtros recarga al momento, y todos
            // vuelven a la primera página: la página 7 de otra consulta no existe.
            is QuestionsIntent.Search -> {
                updateState { it.copy(searchQuery = intent.query, pageIndex = 0) }
                searchJob?.cancel()
                searchJob = scope.launch {
                    delay(QUESTION_SEARCH_DEBOUNCE_MILLIS)
                    loadQuestions()
                }
            }
            is QuestionsIntent.SetTypeFilter -> {
                updateState { it.copy(typeFilter = intent.type, pageIndex = 0) }
                reloadQuestions()
            }
            is QuestionsIntent.SetActiveFilter -> {
                updateState { it.copy(activeFilter = intent.active, pageIndex = 0) }
                reloadQuestions()
            }
            is QuestionsIntent.SetSort -> {
                updateState { it.copy(sort = intent.sort, pageIndex = 0) }
                reloadQuestions()
            }
            is QuestionsIntent.SetPageIndex -> {
                updateState { it.copy(pageIndex = intent.index.coerceAtLeast(0)) }
                reloadQuestions()
            }
            is QuestionsIntent.SetPageSize -> {
                updateState { it.copy(pageSize = intent.size, pageIndex = 0) }
                reloadQuestions()
            }
            is QuestionsIntent.SetExamFilter -> {
                updateState { it.copy(examFilter = intent.examId, pageIndex = 0) }
                reloadQuestions()
            }

            is QuestionsIntent.SelectQuestion -> selectQuestion(intent.id)
            QuestionsIntent.StartCreate -> startCreate()
            is QuestionsIntent.UpdateCreateExamId -> updateState { it.copy(draftCreateExamId = intent.examId) }

            is QuestionsIntent.UpdateStatement -> updateState { it.copy(draftStatement = intent.value, dirty = true) }
            // Cambiar el tipo de un alta suelta el examen inicial si ya no lo admite:
            // la base lo rechazaría (`QUESTION_EXAM_TYPE_MISMATCH`), y el selector ya
            // no lo ofrece, así que quedaría elegido algo invisible.
            //
            // Y en una pregunta guardada con vínculos o con recursos el tipo está
            // BLOQUEADO (KQ3-3): la base lo rechaza (`QUESTION_TYPE_LOCKED_BY_*`) y
            // la tarjeta no es seleccionable; si el intento llega igual, se ignora
            // en vez de dejar un borrador que sólo puede fallar al guardar.
            is QuestionsIntent.UpdateType -> updateState { state ->
                if (!state.isCreating && questionTypeLockReason(state) != null && intent.type != state.draftType) return@updateState state
                val createExam = (state.exams as? UiAsync.Success)?.value?.firstOrNull { it.id == state.draftCreateExamId }
                state.copy(
                    draftType = intent.type,
                    dirty = true,
                    draftCreateExamId = if (createExam != null && !examAcceptsQuestionType(createExam, intent.type)) {
                        ""
                    } else {
                        state.draftCreateExamId
                    },
                )
            }
            is QuestionsIntent.UpdateOptionContent -> updateState {
                it.copy(
                    draftOptions = it.draftOptions.map { option ->
                        if (option.label == intent.label) option.copy(content = intent.value) else option
                    },
                    dirty = true,
                    optionsDirty = true,
                )
            }
            is QuestionsIntent.SetCorrectOption -> updateState {
                it.copy(
                    draftOptions = it.draftOptions.map { option -> option.copy(isCorrect = option.label == intent.label) },
                    dirty = true,
                    optionsDirty = true,
                )
            }
            is QuestionsIntent.UpdateExplanation -> updateState { it.copy(draftExplanation = intent.value, dirty = true) }
            is QuestionsIntent.UpdateSource -> updateState { it.copy(draftSource = intent.value, dirty = true) }
            is QuestionsIntent.SetActive -> updateState { it.copy(draftActive = intent.active, dirty = true) }

            QuestionsIntent.RequestAssociateExam -> updateState {
                it.copy(showAssociateDialog = true, associateExamId = "", associatePosition = "", saveState = UiAsync.Idle)
            }
            is QuestionsIntent.UpdateAssociateExamId -> updateState { it.copy(associateExamId = intent.examId) }
            is QuestionsIntent.UpdateAssociatePosition -> updateState { it.copy(associatePosition = intent.value) }
            QuestionsIntent.CancelAssociateDialog -> updateState { it.copy(showAssociateDialog = false) }
            QuestionsIntent.ConfirmAssociateExam -> associateExam()

            is QuestionsIntent.RequestUnlinkExam -> updateState { it.copy(unlinkExamId = intent.examId, saveState = UiAsync.Idle) }
            QuestionsIntent.DismissUnlinkConfirm -> updateState { it.copy(unlinkExamId = null) }
            QuestionsIntent.ConfirmUnlinkExam -> unlinkExam()

            QuestionsIntent.RequestAddResource -> updateState {
                it.copy(
                    showResourceDialog = true,
                    resourceForm = QuestionResourceForm(),
                    // UNA vez por alta: sobrevive a cada reintento hasta que el
                    // recurso exista o el diálogo se cierre.
                    resourceDraftId = randomUuid(),
                    resourceSaveState = UiAsync.Idle,
                )
            }
            // Cancelar borra también el error del intento anterior: si no, la
            // sección se quedaría con un «no hemos podido añadir el recurso» sobre
            // un alta que ya nadie está intentando.
            QuestionsIntent.CancelResourceDialog -> updateState {
                it.copy(
                    showResourceDialog = false,
                    resourceForm = QuestionResourceForm(),
                    resourceDraftId = "",
                    resourceSaveState = UiAsync.Idle,
                )
            }
            is QuestionsIntent.UpdateResourceKind -> updateState {
                it.copy(resourceForm = it.resourceForm.withKind(intent.kind), resourceSaveState = UiAsync.Idle)
            }
            is QuestionsIntent.UpdateResourceTitle -> updateState {
                it.copy(resourceForm = it.resourceForm.copy(title = intent.value))
            }
            is QuestionsIntent.UpdateResourceDescription -> updateState {
                it.copy(resourceForm = it.resourceForm.copy(description = intent.value))
            }
            is QuestionsIntent.PickResourceFile -> updateState {
                it.copy(resourceForm = it.resourceForm.copy(file = intent.file), resourceSaveState = UiAsync.Idle)
            }
            QuestionsIntent.ClearResourceFile -> updateState {
                it.copy(resourceForm = it.resourceForm.copy(file = null))
            }
            QuestionsIntent.ConfirmAddResource -> addResource()

            is QuestionsIntent.RequestDeleteResource -> updateState {
                it.copy(deleteResourceId = intent.resourceId, resourceDeleteState = UiAsync.Idle)
            }
            QuestionsIntent.DismissDeleteResourceConfirm -> updateState { it.copy(deleteResourceId = null) }
            QuestionsIntent.ConfirmDeleteResource -> deleteResource()

            is QuestionsIntent.AddQuestionTag -> addTagToSelection(intent.tagId)
            is QuestionsIntent.RemoveQuestionTag -> updateState {
                it.copy(draftTagIds = it.draftTagIds - intent.tagId, tagsSaveState = UiAsync.Idle)
            }
            is QuestionsIntent.UpdateTagSearch -> updateState { it.copy(tagSearch = intent.query) }
            QuestionsIntent.SaveQuestionTags -> saveQuestionTags()
            QuestionsIntent.ReloadQuestionTags -> {
                if (currentState().tagCatalog is UiAsync.Error) refreshTagCatalog()
                currentState().selectedQuestionId?.takeIf { !currentState().isCreating }?.let { id ->
                    updateState { it.copy(questionTags = UiAsync.Loading) }
                    loadQuestionTags(id, selectionGeneration)
                }
            }
            QuestionsIntent.RequestCreateTag -> updateState {
                it.copy(showCreateTagDialog = true, newTagName = it.tagSearch.trim(), createTagState = UiAsync.Idle)
            }
            is QuestionsIntent.UpdateNewTagName -> updateState { it.copy(newTagName = intent.value) }
            QuestionsIntent.CancelCreateTag -> updateState {
                it.copy(showCreateTagDialog = false, newTagName = "", createTagState = UiAsync.Idle)
            }
            QuestionsIntent.ConfirmCreateTag -> createTag()

            QuestionsIntent.RequestCancel -> requestCancel()
            QuestionsIntent.ConfirmCancel -> confirmCancel()
            QuestionsIntent.DismissCancelConfirm -> updateState { it.copy(showCancelConfirm = false) }

            QuestionsIntent.RequestDelete -> updateState { it.copy(showDeleteConfirm = true) }
            QuestionsIntent.ConfirmDelete -> confirmDelete()
            QuestionsIntent.DismissDeleteConfirm -> updateState { it.copy(showDeleteConfirm = false) }

            QuestionsIntent.Save -> save()
        }
    }

    /** Una recarga explícita manda sobre una búsqueda que todavía esperaba. */
    private suspend fun reloadQuestions() {
        searchJob?.cancel()
        loadQuestions()
    }

    /**
     * Pide al servidor la página que describen los filtros (KQ0-4).
     *
     * - Cero resultados SIN filtros es un banco vacío (`Empty`); con filtros es una
     *   página vacía (`Success`), que la pantalla dice como «nada coincide».
     * - Una página más allá del final —se archivó lo último de la última página— se
     *   corrige a la última que existe en vez de enseñar un vacío falso.
     */
    private suspend fun loadQuestions() {
        val generation = ++loadGeneration
        val state = currentState()
        val query = state.toListQuery()
        updateState { it.copy(questions = UiAsync.Loading) }
        val result = questionsRepository.listQuestions(query)
        if (generation != loadGeneration) return
        when (result) {
            is AppResult.Failure -> updateState { it.copy(questions = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                val page = result.value
                if (page.items.isEmpty() && page.total > 0 && query.offset > 0) {
                    val lastPage = (page.total - 1) / query.limit
                    updateState { it.copy(pageIndex = lastPage) }
                    return loadQuestions()
                }
                updateState {
                    it.copy(
                        questions = if (page.total == 0 && !state.hasBankFilters()) UiAsync.Empty else UiAsync.Success(page),
                    )
                }
            }
        }
    }

    /**
     * Pide el catálogo de exámenes que usan los selectores (KQ0-5). La especialidad de
     * cada examen ya viene resuelta en él: la pregunta no tiene especialidad (M8).
     */
    private suspend fun refreshCatalogs() {
        when (val exams = contentRepository.refreshAllExams()) {
            is AppResult.Failure -> updateState { it.copy(exams = UiAsync.Error(exams.error)) }
            is AppResult.Success -> updateState { it.copy(exams = loadedCatalog(exams.value)) }
        }
    }

    private fun startCreate() {
        selectionGeneration++
        updateState {
            it.copy(
                selectedQuestionId = null,
                isCreating = true,
                detail = UiAsync.Idle,
                draftStatement = "",
                draftType = QuestionType.RAZONADA,
                draftOptions = defaultDraftOptions(),
                draftExplanation = "",
                draftSource = "",
                draftActive = true,
                // Sin definir (NULL): la pantalla no edita la dificultad y no la inventa (KQ0-2).
                draftDifficulty = "",
                // UNA vez por alta (KQ2-4): sobrevive a cada reintento hasta que la
                // pregunta exista o el alta se cancele.
                draftQuestionId = randomUuid(),
                draftCreateExamId = "",
                optionsDirty = true, // alta siempre manda opciones
                dirty = false,
                examLinks = UiAsync.Idle,
                saveState = UiAsync.Idle,
                // Una pregunta que todavía no existe no tiene id al que colgar un
                // recurso: el servidor respondería 404. El control no se ofrece.
                showResourceDialog = false,
                resourceForm = QuestionResourceForm(),
                resourceDraftId = "",
                resourceSaveState = UiAsync.Idle,
                deleteResourceId = null,
                resourceDeleteState = UiAsync.Idle,
                // Igual que los recursos: sin id no hay a qué colgar etiquetas.
                questionTags = UiAsync.Idle,
                draftTagIds = emptyList(),
                tagSearch = "",
                tagsSaveState = UiAsync.Idle,
                showCreateTagDialog = false,
                newTagName = "",
                createTagState = UiAsync.Idle,
            )
        }
    }

    /**
     * Detalle y vínculos en PARALELO (KQ3-2): son dos consultas independientes y el
     * panel de relaciones no tiene por qué esperar al editor. [selectionGeneration]
     * descarta lo que llegue de una selección anterior.
     */
    private suspend fun selectQuestion(id: String) {
        val generation = ++selectionGeneration
        updateState {
            it.copy(
                selectedQuestionId = id,
                isCreating = false,
                detail = UiAsync.Loading,
                dirty = false,
                optionsDirty = false,
                saveState = UiAsync.Idle,
                examLinks = UiAsync.Loading,
                unlinkExamId = null,
                // Los recursos son de LA pregunta: un diálogo a medias o el error de
                // la anterior no pueden sobrevivir al cambio de selección.
                showResourceDialog = false,
                resourceForm = QuestionResourceForm(),
                resourceDraftId = "",
                resourceSaveState = UiAsync.Idle,
                deleteResourceId = null,
                resourceDeleteState = UiAsync.Idle,
                // Las etiquetas también son de LA pregunta.
                questionTags = UiAsync.Loading,
                draftTagIds = emptyList(),
                tagSearch = "",
                tagsSaveState = UiAsync.Idle,
                showCreateTagDialog = false,
                newTagName = "",
                createTagState = UiAsync.Idle,
            )
        }
        scope.launch { loadExamLinks(id, generation) }
        scope.launch { loadQuestionTags(id, generation) }
        val result = questionsRepository.getQuestionDetail(id)
        if (generation != selectionGeneration) return
        when (result) {
            is AppResult.Failure -> updateState { it.copy(detail = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                val detail = result.value
                updateState {
                    it.copy(
                        detail = UiAsync.Success(detail),
                        draftStatement = detail.statement,
                        draftType = detail.type ?: QuestionType.RAZONADA,
                        draftOptions = if (detail.options.size == 4) {
                            detail.options.sortedBy { option -> option.sortOrder }
                        } else {
                            defaultDraftOptions()
                        },
                        draftExplanation = detail.explanation,
                        draftSource = detail.source,
                        draftActive = detail.active,
                        draftDifficulty = detail.difficulty,
                        dirty = false,
                        optionsDirty = false,
                    )
                }
            }
        }
    }

    /** Los vínculos vienen del servidor, nunca de la sesión: vacío es «en ningún examen», y un fallo es un fallo. */
    private suspend fun loadExamLinks(questionId: String, generation: Int) {
        val result = questionsRepository.listQuestionExams(questionId)
        if (generation != selectionGeneration) return
        updateState {
            it.copy(
                examLinks = when (result) {
                    is AppResult.Failure -> UiAsync.Error(result.error)
                    is AppResult.Success -> UiAsync.Success(result.value)
                },
            )
        }
    }

    // -----------------------------------------------------------------
    // Etiquetas (temas)
    // -----------------------------------------------------------------

    /** Sólo las ACTIVAS se ofrecen; un fallo es un fallo, nunca una lista vacía. */
    private suspend fun refreshTagCatalog() {
        when (val result = tagsRepository.refreshTags()) {
            is AppResult.Failure -> updateState { it.copy(tagCatalog = UiAsync.Error(result.error)) }
            is AppResult.Success -> updateState { it.copy(tagCatalog = loadedCatalog(result.value.filter { tag -> tag.active })) }
        }
    }

    /** Las guardadas siembran la selección editable; las de una selección anterior no aterrizan aquí. */
    private suspend fun loadQuestionTags(questionId: String, generation: Int) {
        val result = questionsRepository.getQuestionTags(questionId)
        if (generation != selectionGeneration) return
        updateState {
            when (result) {
                is AppResult.Failure -> it.copy(questionTags = UiAsync.Error(result.error))
                is AppResult.Success -> it.copy(
                    questionTags = UiAsync.Success(result.value),
                    draftTagIds = result.value.map { tag -> tag.id },
                )
            }
        }
    }

    private fun addTagToSelection(tagId: String) = updateState { state ->
        when {
            tagId in state.draftTagIds -> state.copy(tagSearch = "")
            state.draftTagIds.size >= QUESTION_TAGS_MAX -> state.copy(
                tagsSaveState = UiAsync.Error(AppError.Validation("Una pregunta admite como máximo $QUESTION_TAGS_MAX etiquetas.")),
            )
            else -> state.copy(draftTagIds = state.draftTagIds + tagId, tagSearch = "", tagsSaveState = UiAsync.Idle)
        }
    }

    /**
     * Guarda el CONJUNTO de etiquetas por su ruta, sin reenviar el borrador de la
     * pregunta. Lo que vuelve del servidor sustituye a lo conocido: es la lista real.
     */
    private suspend fun saveQuestionTags() {
        val state = currentState()
        val questionId = state.selectedQuestionId
        if (!state.canEditTags() || questionId.isNullOrBlank()) {
            return updateState {
                it.copy(tagsSaveState = UiAsync.Error(AppError.Validation("Guarda la pregunta antes de etiquetarla.")))
            }
        }
        updateState { it.copy(tagsSaveState = UiAsync.Loading) }
        val result = questionsRepository.setQuestionTags(questionId, state.draftTagIds)
        updateState {
            if (it.selectedQuestionId != questionId) return@updateState it
            when (result) {
                is AppResult.Failure -> it.copy(tagsSaveState = UiAsync.Error(result.error))
                is AppResult.Success -> it.copy(
                    tagsSaveState = UiAsync.Success(Unit),
                    questionTags = UiAsync.Success(result.value),
                    draftTagIds = result.value.map { tag -> tag.id },
                )
            }
        }
    }

    /**
     * Crea una etiqueta del catálogo (`POST /etiquetas`, el slug lo deriva el servidor)
     * y la AÑADE a la selección, que se guarda después con «Guardar etiquetas». Si ya
     * hay una activa con ese nombre, se usa ésa en vez de duplicarla.
     */
    private suspend fun createTag() {
        val state = currentState()
        val name = state.newTagName.trim()
        val invalid = when {
            name.isBlank() -> "La etiqueta necesita un nombre."
            name.length > EtiquetaValidator.MAX_NAME -> "El nombre no puede superar ${EtiquetaValidator.MAX_NAME} caracteres."
            else -> null
        }
        if (invalid != null) {
            return updateState { it.copy(createTagState = UiAsync.Error(AppError.Validation(invalid))) }
        }
        val existing = (state.tagCatalog as? UiAsync.Success)?.value.orEmpty()
            .firstOrNull { it.active && it.name.trim().equals(name, ignoreCase = true) }
        if (existing != null) {
            updateState { it.copy(showCreateTagDialog = false, newTagName = "", createTagState = UiAsync.Idle) }
            return addTagToSelection(existing.id)
        }
        updateState { it.copy(createTagState = UiAsync.Loading) }
        when (val result = tagsRepository.upsertTag(Tag(id = "", name = name, slug = "", origin = "manual"))) {
            is AppResult.Failure -> updateState { it.copy(createTagState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                val created = result.value
                updateState {
                    val catalog = (it.tagCatalog as? UiAsync.Success)?.value.orEmpty()
                    it.copy(
                        tagCatalog = UiAsync.Success(catalog.filterNot { tag -> tag.id == created.id } + created),
                        showCreateTagDialog = false,
                        newTagName = "",
                        createTagState = UiAsync.Success(Unit),
                    )
                }
                addTagToSelection(created.id)
            }
        }
    }

    private fun requestCancel() {
        val state = currentState()
        if (state.dirty) {
            updateState { it.copy(showCancelConfirm = true) }
        } else {
            confirmCancel()
        }
    }

    private fun confirmCancel() {
        val state = currentState()
        val detail = (state.detail as? UiAsync.Success)?.value
        updateState {
            if (detail != null) {
                it.copy(
                    isCreating = false,
                    draftStatement = detail.statement,
                    draftType = detail.type ?: QuestionType.RAZONADA,
                    draftOptions = if (detail.options.size == 4) detail.options.sortedBy { o -> o.sortOrder } else defaultDraftOptions(),
                    draftExplanation = detail.explanation,
                    draftSource = detail.source,
                    draftActive = detail.active,
                    draftDifficulty = detail.difficulty,
                    dirty = false,
                    optionsDirty = false,
                    showCancelConfirm = false,
                    saveState = UiAsync.Idle,
                )
            } else {
                it.copy(
                    selectedQuestionId = null,
                    isCreating = false,
                    detail = UiAsync.Idle,
                    dirty = false,
                    optionsDirty = false,
                    showCancelConfirm = false,
                    saveState = UiAsync.Idle,
                )
            }
        }
    }

    private suspend fun confirmDelete() {
        val id = currentState().selectedQuestionId ?: return updateState { it.copy(showDeleteConfirm = false) }
        updateState { it.copy(archiveState = UiAsync.Loading) }
        when (val result = questionsRepository.archiveQuestion(id)) {
            is AppResult.Failure -> updateState { it.copy(archiveState = UiAsync.Error(result.error), showDeleteConfirm = false) }
            is AppResult.Success -> {
                updateState {
                    it.copy(
                        archiveState = UiAsync.Success(Unit),
                        showDeleteConfirm = false,
                        selectedQuestionId = null,
                        detail = UiAsync.Idle,
                    )
                }
                loadQuestions()
            }
        }
    }

    /**
     * Vincular es una llamada PROPIA (`POST /preguntas/{id}/examenes`, KQ3-2) y no
     * un PATCH con el borrador entero: antes «Vincular» guardaba en silencio las
     * ediciones sin confirmar, o fallaba por ellas. La lista que vuelve del servidor
     * SUSTITUYE a la conocida: es la real, con la posición que la base asignó.
     */
    private suspend fun associateExam() {
        val state = currentState()
        val questionId = state.selectedQuestionId ?: return
        val examId = state.associateExamId
        val positionText = state.associatePosition.trim()
        val position = positionText.toIntOrNull()
        if (examId.isBlank() || (positionText.isNotEmpty() && (position == null || position <= 0))) {
            return updateState { it.copy(saveState = UiAsync.Error(AppError.Validation("Elige un examen y, si quieres fijarla, una posición de 1 en adelante."))) }
        }
        if (state.knownExamLinks().any { it.examId == examId }) {
            return updateState { it.copy(saveState = UiAsync.Error(AppError.Validation("Esta pregunta ya está en ese examen."))) }
        }
        updateState { it.copy(saveState = UiAsync.Loading) }
        when (val result = questionsRepository.linkQuestionToExam(questionId, examId, position)) {
            is AppResult.Failure -> updateState { it.copy(saveState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                updateState {
                    it.copy(
                        saveState = UiAsync.Success(Unit),
                        showAssociateDialog = false,
                        examLinks = UiAsync.Success(result.value),
                    )
                }
                // La fila del banco puede llevar la posición del examen filtrado.
                if (state.examFilter != null) loadQuestions()
            }
        }
    }

    /** Quitar de un examen deja la pregunta intacta en el banco; la lista vuelve releída del servidor. */
    private suspend fun unlinkExam() {
        val state = currentState()
        val questionId = state.selectedQuestionId ?: return updateState { it.copy(unlinkExamId = null) }
        val examId = state.unlinkExamId ?: return
        updateState { it.copy(saveState = UiAsync.Loading) }
        when (val result = questionsRepository.unlinkQuestionFromExam(questionId, examId)) {
            is AppResult.Failure -> updateState { it.copy(saveState = UiAsync.Error(result.error), unlinkExamId = null) }
            is AppResult.Success -> {
                updateState {
                    it.copy(saveState = UiAsync.Success(Unit), unlinkExamId = null, examLinks = UiAsync.Success(result.value))
                }
                if (state.examFilter != null) loadQuestions()
            }
        }
    }

    // -----------------------------------------------------------------
    // Recursos de una pregunta inmersiva (KQ4)
    // -----------------------------------------------------------------

    /**
     * Alta de recurso: por referencia (`texto`, `enlace`) o subiendo el archivo.
     *
     * **La regla «sólo inmersiva» no se comprueba aquí.** La impone el servidor
     * contra el tipo GUARDADO de la pregunta —el único que no puede estar
     * desactualizado— y su 422 llega a la pantalla con el mensaje que él redacta.
     * Lo que sí se comprueba antes de gastar la red es lo que la BASE rechazaría por
     * forma: un enlace sin esquema, un texto vacío, un archivo que falta.
     *
     * El id del recurso NO se regenera cuando falla: un timeout a mitad de una
     * subida de 40 MB tiene que poder reintentarse con el mismo id, que es lo que
     * impide que el objeto acabe dos veces en Storage.
     */
    private suspend fun addResource() {
        val state = currentState()
        val questionId = state.selectedQuestionId
        if (questionId.isNullOrBlank()) {
            return updateState {
                it.copy(
                    resourceSaveState = UiAsync.Error(
                        AppError.Validation("Guarda la pregunta antes de añadirle recursos."),
                    ),
                )
            }
        }
        val form = state.resourceForm
        form.validationError()?.let { reason ->
            return updateState { it.copy(resourceSaveState = UiAsync.Error(AppError.Validation(reason))) }
        }
        updateState { it.copy(resourceSaveState = UiAsync.Loading) }
        // `validationError()` ya garantiza que hay archivo: los tres tipos lo exigen.
        val file = form.file ?: return updateState {
            it.copy(resourceSaveState = UiAsync.Error(AppError.Validation("Elige el archivo que quieres subir.")))
        }
        val result = questionsRepository.uploadQuestionResourceFile(
            questionId = questionId,
            resourceId = state.resourceDraftId,
            draft = form.toDraft(),
            file = file,
        )
        when (result) {
            is AppResult.Failure -> updateState { it.copy(resourceSaveState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                updateState {
                    it.copy(
                        resourceSaveState = UiAsync.Success(Unit),
                        showResourceDialog = false,
                        resourceForm = QuestionResourceForm(),
                        resourceDraftId = "",
                    )
                }
                refreshResources(questionId) { error -> copy(resourceSaveState = UiAsync.Error(error)) }
            }
        }
    }

    /** Archivar un recurso: la lista se relee del servidor, nunca se edita a mano. */
    private suspend fun deleteResource() {
        val state = currentState()
        val questionId = state.selectedQuestionId
        val resourceId = state.deleteResourceId ?: return
        if (questionId.isNullOrBlank()) {
            return updateState { it.copy(deleteResourceId = null) }
        }
        updateState { it.copy(resourceDeleteState = UiAsync.Loading) }
        when (val result = questionsRepository.deleteQuestionResource(questionId, resourceId)) {
            is AppResult.Failure -> updateState {
                it.copy(resourceDeleteState = UiAsync.Error(result.error), deleteResourceId = null)
            }
            is AppResult.Success -> {
                updateState { it.copy(resourceDeleteState = UiAsync.Success(Unit), deleteResourceId = null) }
                refreshResources(questionId) { error -> copy(resourceDeleteState = UiAsync.Error(error)) }
            }
        }
    }

    /**
     * Relee los recursos del servidor y los mete en el detalle abierto.
     *
     * La lista NO se compone a mano con lo que devolvió la escritura: sólo
     * `GET /recursos` trae la `urlAcceso` firmada de todos y la posición que la base
     * acabó asignando, así que lo que se pinta es siempre lo que el servidor dice.
     *
     * Si la relectura falla, [onFailure] lo cuenta en el estado de la operación que
     * la pidió. **La escritura ya había ido bien**, y decir lo contrario sería peor
     * que decir esto: el recurso está guardado y lo que falta es refrescar.
     */
    private suspend fun refreshResources(
        questionId: String,
        onFailure: QuestionsState.(AppError) -> QuestionsState,
    ) {
        when (val result = questionsRepository.listQuestionResources(questionId)) {
            is AppResult.Failure -> updateState { state ->
                state.onFailure(AppError.Validation(QUESTION_RESOURCES_STALE_NOTE))
            }
            is AppResult.Success -> updateState { state ->
                // Si mientras tanto se ha cambiado de pregunta, no se pisa el detalle
                // de la nueva con los recursos de la anterior.
                val detail = (state.detail as? UiAsync.Success)?.value
                if (state.selectedQuestionId != questionId || detail == null) {
                    state
                } else {
                    state.copy(detail = UiAsync.Success(detail.copy(resources = result.value)))
                }
            }
        }
    }

    /**
     * Guardar. En un alta el id viaja desde [QuestionsState.draftQuestionId] y NO se
     * regenera si falla: un timeout con la pregunta ya creada en el servidor tiene
     * que poder reintentarse con el MISMO id, que es lo que impide duplicarla.
     */
    private suspend fun save() {
        val state = currentState()
        if (!state.isDraftValid()) {
            return updateState { it.copy(saveState = UiAsync.Error(AppError.Validation("Revisa enunciado, opciones y respuesta correcta antes de guardar."))) }
        }
        updateState { it.copy(saveState = UiAsync.Loading) }
        val draft = state.toDraft()
        val result = if (state.isCreating) {
            questionsRepository.createQuestion(
                questionId = state.draftQuestionId,
                draft = draft,
                examId = state.draftCreateExamId.takeIf { it.isNotBlank() },
            )
        } else {
            val id = state.selectedQuestionId ?: return updateState { it.copy(saveState = UiAsync.Error(AppError.Validation("Selecciona una pregunta."))) }
            questionsRepository.updateQuestion(id, draft, optionsChanged = state.optionsDirty)
        }
        when (result) {
            is AppResult.Failure -> updateState { it.copy(saveState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                val detail = result.value
                val wasCreating = state.isCreating
                updateState {
                    it.copy(
                        saveState = UiAsync.Success(Unit),
                        isCreating = false,
                        selectedQuestionId = detail.id,
                        detail = UiAsync.Success(detail),
                        dirty = false,
                        optionsDirty = false,
                        draftQuestionId = "",
                    )
                }
                if (wasCreating) {
                    // La pregunta ya existe: sus vínculos (el examen inicial, si lo hubo) son del servidor.
                    val generation = ++selectionGeneration
                    updateState { it.copy(examLinks = UiAsync.Loading, questionTags = UiAsync.Loading) }
                    scope.launch { loadExamLinks(detail.id, generation) }
                    scope.launch { loadQuestionTags(detail.id, generation) }
                }
                loadQuestions()
            }
        }
    }
}

/**
 * La emisión SEMILLA de un repositorio no resuelve un catálogo: sus `StateFlow`
 * arrancan en `emptyList()`, que significa «todavía no se ha preguntado», no «no
 * hay ninguno». Una lista vacía sólo pasa a `Empty` si ya había datos; el vacío
 * confirmado lo decide el refresco ([loadedCatalog]).
 */
private fun <T> seededCatalog(values: List<T>, current: UiAsync<List<T>>): UiAsync<List<T>> = when {
    values.isNotEmpty() -> UiAsync.Success(values)
    current is UiAsync.Loading || current is UiAsync.Idle || current is UiAsync.Error -> current
    else -> UiAsync.Empty
}

/** Una lista que viene de hablar con el servidor: vacía es `Empty`, no `Loading`. */
private fun <T> loadedCatalog(values: List<T>): UiAsync<List<T>> =
    if (values.isEmpty()) UiAsync.Empty else UiAsync.Success(values)

private fun QuestionsState.toDraft() = QuestionDraft(
    statement = draftStatement.trim(),
    type = draftType,
    options = draftOptions,
    explanation = draftExplanation.trim(),
    source = draftSource.trim(),
    active = draftActive,
    difficulty = draftDifficulty,
)

/**
 * Validación cliente — el backend sigue siendo la autoridad final (P0 de
 * opciones/creación puede rechazarlo igual aunque esto pase).
 */
fun QuestionsState.isDraftValid(): Boolean {
    if (draftStatement.isBlank()) return false
    // KQ2-4: el examen inicial es opcional; la pregunta no tiene especialidad (M8).
    if (optionsDirty || isCreating) {
        if (draftOptions.size != 4) return false
        if (draftOptions.any { it.content.isBlank() }) return false
        if (draftOptions.count { it.isCorrect } != 1) return false
    }
    return true
}
