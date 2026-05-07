package com.oposicontrol.admin.features.exams.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamQuestion
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.QuestionMoveDirection
import com.oposicontrol.admin.domain.model.QuestionOption
import com.oposicontrol.admin.domain.model.QuestionResource
import com.oposicontrol.admin.domain.model.QuestionResourceType
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.usecase.content.DeleteExamUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteSpecialtyUseCase
import com.oposicontrol.admin.domain.usecase.content.GetExamResourceAuditUseCase
import com.oposicontrol.admin.domain.usecase.content.GetExamQuestionsUseCase
import com.oposicontrol.admin.domain.usecase.content.ImportQuestionsUseCase
import com.oposicontrol.admin.domain.usecase.content.MoveQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveExamsUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveSpecialtiesUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshExamsUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshSpecialtiesUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertExamUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertSpecialtyUseCase
import com.oposicontrol.admin.domain.usecase.content.UploadExamCoverUseCase
import com.oposicontrol.admin.domain.usecase.content.UploadQuestionResourceUseCase
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.time.Clock

class ExamsViewModel(
    dispatcherProvider: DispatcherProvider,
    observeSpecialtiesUseCase: ObserveSpecialtiesUseCase,
    observeExamsUseCase: ObserveExamsUseCase,
    private val refreshSpecialtiesUseCase: RefreshSpecialtiesUseCase,
    private val upsertSpecialtyUseCase: UpsertSpecialtyUseCase,
    private val deleteSpecialtyUseCase: DeleteSpecialtyUseCase,
    private val refreshExamsUseCase: RefreshExamsUseCase,
    private val getExamQuestionsUseCase: GetExamQuestionsUseCase,
    private val getExamResourceAuditUseCase: GetExamResourceAuditUseCase,
    private val upsertExamUseCase: UpsertExamUseCase,
    private val deleteExamUseCase: DeleteExamUseCase,
    private val upsertQuestionUseCase: UpsertQuestionUseCase,
    private val importQuestionsUseCase: ImportQuestionsUseCase,
    private val deleteQuestionUseCase: DeleteQuestionUseCase,
    private val moveQuestionUseCase: MoveQuestionUseCase,
    private val uploadExamCoverUseCase: UploadExamCoverUseCase,
    private val uploadQuestionResourceUseCase: UploadQuestionResourceUseCase,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<ExamsState, ExamsIntent>(
    initialState = ExamsState(),
    dispatcherProvider = dispatcherProvider,
) {
    private var allSpecialties: List<Specialty> = emptyList()
    private var allExams: List<Exam> = emptyList()
    private var currentQuestions: List<ExamQuestion> = emptyList()

    init {
        scope.launch {
            observeSpecialtiesUseCase().collectLatest { specialties ->
                allSpecialties = specialties
                updateState {
                    it.copy(
                        specialties = if (specialties.isEmpty()) UiAsync.Empty else UiAsync.Success(specialties),
                        editorSpecialtyId = it.editorSpecialtyId.ifBlank { specialties.firstOrNull()?.id.orEmpty() },
                        selectedContentSpecialtyId = it.selectedContentSpecialtyId ?: specialties.firstOrNull()?.id,
                    )
                }
                if (currentState().specialtyEditorName.isBlank()) {
                    specialties.firstOrNull { it.id == currentState().selectedContentSpecialtyId }
                        ?.let { selectContentSpecialty(it.id) }
                }
                publishExams()
            }
        }
        scope.launch {
            observeExamsUseCase().collectLatest { exams ->
                allExams = exams
                publishExams()
                if (currentState().selectedExamId != null && exams.none { it.id == currentState().selectedExamId }) {
                    resetExamEditor()
                }
            }
        }
        dispatch(ExamsIntent.RefreshAll)
    }

    override suspend fun handleIntent(intent: ExamsIntent) {
        when (intent) {
            ExamsIntent.AddResource -> addResource()
            ExamsIntent.CreateNew -> resetExamEditor()
            ExamsIntent.CreateQuestion -> createQuestion()
            ExamsIntent.CreateSpecialty -> createSpecialty()
            ExamsIntent.DeleteSelectedExam -> deleteSelectedExam()
            ExamsIntent.DeleteSelectedQuestion -> deleteSelectedQuestion()
            ExamsIntent.DeleteSelectedSpecialty -> deleteSelectedSpecialty()
            ExamsIntent.RefreshAll -> refreshAll()
            ExamsIntent.RefreshResourceAudit -> refreshResourceAudit()
            ExamsIntent.SaveExam -> saveExam()
            ExamsIntent.SaveQuestion -> saveQuestion()
            ExamsIntent.SaveSpecialty -> saveSpecialty()
            ExamsIntent.UploadExamCover -> uploadExamCover()
            ExamsIntent.ClearExamCover -> clearExamCover()
            is ExamsIntent.MoveQuestionDown -> moveQuestion(intent.id, QuestionMoveDirection.DOWN)
            is ExamsIntent.MoveQuestionUp -> moveQuestion(intent.id, QuestionMoveDirection.UP)
            is ExamsIntent.RemoveResource -> removeResource(intent.resourceId)
            is ExamsIntent.SelectCorrectOption -> selectCorrectOption(intent.optionId)
            is ExamsIntent.SelectContentSpecialty -> selectContentSpecialty(intent.id)
            is ExamsIntent.SelectExam -> selectExam(intent.id)
            is ExamsIntent.SelectQuestion -> selectQuestion(intent.id)
            is ExamsIntent.ToggleSpecialtyActive -> updateState { it.copy(specialtyEditorIsActive = intent.value) }
            is ExamsIntent.UpdateDescription -> updateState { it.copy(editorDescription = intent.value) }
            is ExamsIntent.UpdateExamStatus -> updateState { it.copy(editorStatus = intent.value) }
            is ExamsIntent.UpdatePriceTickets -> updateState { it.copy(editorPriceTickets = intent.value.filter { char -> char.isDigit() }) }
            is ExamsIntent.UpdateOptionContent -> updateOption(intent.optionId, intent.value)
            is ExamsIntent.UpdatePublishFrom -> updateState { it.copy(editorPublishFrom = intent.value) }
            is ExamsIntent.SelectExamCoverFile -> selectExamCoverFile(intent.uri, intent.suggestedName, intent.mimeType)
            is ExamsIntent.UpdateQuestionResourceFilter -> updateState { it.copy(questionResourceFilter = intent.value) }
            is ExamsIntent.UpdateQuestionStatement -> updateState { it.copy(editorQuestionStatement = intent.value) }
            is ExamsIntent.UpdateQuestionTitle -> updateState { it.copy(editorQuestionTitle = intent.value) }
            is ExamsIntent.UpdateSpecialtyDescription -> updateState { it.copy(specialtyEditorDescription = intent.value) }
            is ExamsIntent.UpdateSpecialtyName -> updateState { it.copy(specialtyEditorName = intent.value) }
            is ExamsIntent.UpdateSpecialtySearch -> updateState { it.copy(specialtySearchQuery = intent.value) }
            is ExamsIntent.UpdateSpecialtySortOrder -> updateState { it.copy(specialtyEditorSortOrder = intent.value.filter { char -> char.isDigit() }) }
            is ExamsIntent.UpdateResourceTitle -> updateResource(intent.resourceId) { copy(title = intent.value) }
            is ExamsIntent.UpdateResourceType -> updateResource(intent.resourceId) {
                copy(
                    type = intent.value,
                    inputValue = "",
                    publicUrl = null,
                    storagePath = null,
                    mimeType = null,
                    localFileName = "",
                )
            }

            is ExamsIntent.UpdateResourceValue -> updateResource(intent.resourceId) { copy(inputValue = intent.value) }
            is ExamsIntent.SelectResourceFile -> selectResourceFile(
                resourceId = intent.resourceId,
                uri = intent.uri,
                suggestedName = intent.suggestedName,
                mimeType = intent.mimeType,
            )
            is ExamsIntent.ImportQuestionsFromText -> importQuestionsFromText(intent.fileName, intent.content)
            is ExamsIntent.UpdateSearch -> {
                updateState { it.copy(searchQuery = intent.value) }
                publishExams()
            }

            is ExamsIntent.UpdateSpecialtyId -> updateState { it.copy(editorSpecialtyId = intent.value) }
            is ExamsIntent.UpdateStatusFilter -> {
                updateState { it.copy(statusFilter = intent.value) }
                publishExams()
            }

            is ExamsIntent.UpdateTitle -> updateState { it.copy(editorTitle = intent.value) }
            is ExamsIntent.UploadResource -> uploadResource(intent.resourceId)
        }
    }

    private suspend fun refreshAll() {
        when (val specialties = refreshSpecialtiesUseCase()) {
            is AppResult.Failure -> updateState { it.copy(specialties = UiAsync.Error(specialties.error)) }
            is AppResult.Success -> Unit
        }
        when (val exams = refreshExamsUseCase()) {
            is AppResult.Failure -> updateState { it.copy(exams = UiAsync.Error(exams.error)) }
            is AppResult.Success -> publishExams()
        }
        analyticsTracker.trackScreen("exams")
    }

    private fun createSpecialty() {
        updateState {
            it.copy(
                selectedContentSpecialtyId = null,
                specialtyEditorName = "",
                specialtyEditorDescription = "",
                specialtyEditorSortOrder = allSpecialties.size.toString(),
                specialtyEditorIsActive = true,
                specialtyActionState = UiAsync.Idle,
            )
        }
    }

    private fun selectContentSpecialty(id: String) {
        val specialty = allSpecialties.firstOrNull { it.id == id } ?: return
        updateState {
            it.copy(
                selectedContentSpecialtyId = specialty.id,
                specialtyEditorName = specialty.name,
                specialtyEditorDescription = specialty.description,
                specialtyEditorSortOrder = specialty.sortOrder.toString(),
                specialtyEditorIsActive = specialty.isActive,
                specialtyActionState = UiAsync.Idle,
            )
        }
    }

    private suspend fun saveSpecialty() {
        val existing = allSpecialties.firstOrNull { it.id == currentState().selectedContentSpecialtyId }
        val sortOrder = currentState().specialtyEditorSortOrder.toIntOrNull()
            ?: return updateState {
                it.copy(specialtyActionState = UiAsync.Error(AppError.Validation("El orden debe ser numerico.")))
            }
        val now = Clock.System.now()
        val specialty = Specialty(
            id = existing?.id.orEmpty(),
            name = currentState().specialtyEditorName.trim(),
            description = currentState().specialtyEditorDescription.trim(),
            isActive = currentState().specialtyEditorIsActive,
            sortOrder = sortOrder,
            linkedExams = existing?.linkedExams ?: 0,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        updateState { it.copy(specialtyActionState = UiAsync.Loading) }
        when (val result = upsertSpecialtyUseCase(specialty)) {
            is AppResult.Failure -> updateState { it.copy(specialtyActionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("specialty_save", mapOf("specialty_id" to result.value.id))
                updateState {
                    it.copy(
                        selectedContentSpecialtyId = result.value.id,
                        specialtyEditorName = result.value.name,
                        specialtyEditorDescription = result.value.description,
                        specialtyEditorSortOrder = result.value.sortOrder.toString(),
                        specialtyEditorIsActive = result.value.isActive,
                        specialtyActionState = UiAsync.Success(Unit),
                    )
                }
                refreshSpecialtiesUseCase()
            }
        }
    }

    private suspend fun deleteSelectedSpecialty() {
        val selectedId = currentState().selectedContentSpecialtyId
            ?: return updateState {
                it.copy(specialtyActionState = UiAsync.Error(AppError.Validation("Selecciona una especialidad para borrarla.")))
            }
        updateState { it.copy(specialtyActionState = UiAsync.Loading) }
        when (val result = deleteSpecialtyUseCase(selectedId)) {
            is AppResult.Failure -> updateState { it.copy(specialtyActionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("specialty_delete", mapOf("specialty_id" to selectedId))
                createSpecialty()
                refreshSpecialtiesUseCase()
                updateState { it.copy(specialtyActionState = UiAsync.Success(Unit)) }
            }
        }
    }

    private fun publishExams() {
        val query = currentState().searchQuery.trim().lowercase()
        val statusFilter = currentState().statusFilter.trim().lowercase()
        val filtered = allExams.filter { exam ->
            (query.isBlank() ||
                exam.title.lowercase().contains(query) ||
                exam.specialtyName.lowercase().contains(query)) &&
                (statusFilter == "all" || exam.status.name.lowercase() == statusFilter)
        }
        updateState { it.copy(exams = if (filtered.isEmpty()) UiAsync.Empty else UiAsync.Success(filtered)) }
    }

    private suspend fun selectExam(id: String) {
        val exam = allExams.firstOrNull { it.id == id } ?: return
        updateState {
            it.copy(
                selectedExamId = exam.id,
                editorTitle = exam.title,
                editorDescription = exam.description,
                editorPriceTickets = exam.priceTickets.toString(),
                editorSpecialtyId = exam.specialtyId,
                editorStatus = exam.status.name.lowercase(),
                editorPublishFrom = exam.publishFrom?.toString().orEmpty(),
                editorCoverUrl = exam.coverUrl.orEmpty(),
                editorCoverStoragePath = exam.coverStoragePath.orEmpty(),
                editorCoverMimeType = exam.coverMimeType.orEmpty(),
                editorCoverLocalUri = "",
                editorCoverFileName = "",
                coverUploadState = UiAsync.Idle,
                selectedQuestionId = null,
                editorQuestionId = null,
                editorQuestionTitle = "",
                editorQuestionStatement = "",
                editorOptions = emptyList(),
                editorResources = emptyList(),
                resourceUploadStates = emptyMap(),
                resourceAudit = UiAsync.Idle,
                actionState = UiAsync.Idle,
            )
        }
        loadQuestions(exam.id)
    }

    private suspend fun loadQuestions(examId: String) {
        updateState { it.copy(questions = UiAsync.Loading) }
        when (val result = getExamQuestionsUseCase(examId)) {
            is AppResult.Failure -> updateState { it.copy(questions = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                currentQuestions = result.value.sortedBy { it.sortOrder }
                updateState {
                    it.copy(
                        questions = if (currentQuestions.isEmpty()) UiAsync.Empty else UiAsync.Success(currentQuestions),
                    )
                }
                refreshResourceAudit()
            }
        }
    }

    private suspend fun refreshResourceAudit() {
        val examId = currentState().selectedExamId ?: return
        updateState { it.copy(resourceAudit = UiAsync.Loading) }
        when (val result = getExamResourceAuditUseCase(examId)) {
            is AppResult.Failure -> updateState { it.copy(resourceAudit = UiAsync.Error(result.error)) }
            is AppResult.Success -> updateState { it.copy(resourceAudit = UiAsync.Success(result.value)) }
        }
    }

    private fun resetExamEditor() {
        currentQuestions = emptyList()
        updateState {
            it.copy(
                selectedExamId = null,
                editorTitle = "",
                editorDescription = "",
                editorPriceTickets = "0",
                editorSpecialtyId = allSpecialties.firstOrNull()?.id.orEmpty(),
                editorStatus = "draft",
                editorPublishFrom = "",
                editorCoverUrl = "",
                editorCoverStoragePath = "",
                editorCoverMimeType = "",
                editorCoverLocalUri = "",
                editorCoverFileName = "",
                coverUploadState = UiAsync.Idle,
                questions = UiAsync.Empty,
                resourceAudit = UiAsync.Idle,
                selectedQuestionId = null,
                editorQuestionId = null,
                editorQuestionTitle = "",
                editorQuestionStatement = "",
                editorOptions = emptyList(),
                editorResources = emptyList(),
                uploadState = UiAsync.Idle,
                resourceUploadStates = emptyMap(),
                importState = UiAsync.Idle,
                importSummary = "",
                actionState = UiAsync.Idle,
            )
        }
    }

    private suspend fun saveExam() {
        val now = Clock.System.now()
        val existing = allExams.firstOrNull { it.id == currentState().selectedExamId }
        val specialty = allSpecialties.firstOrNull { it.id == currentState().editorSpecialtyId }
            ?: return updateState {
                it.copy(actionState = UiAsync.Error(AppError.Validation("Selecciona una especialidad valida.")))
            }
        val priceTickets = currentState().editorPriceTickets.toIntOrNull()
            ?: return updateState {
                it.copy(actionState = UiAsync.Error(AppError.Validation("El precio en tickets debe ser un numero valido.")))
            }
        if (priceTickets < 0) {
            return updateState {
                it.copy(actionState = UiAsync.Error(AppError.Validation("El precio en tickets no puede ser negativo.")))
            }
        }
        val publishFrom = currentState().editorPublishFrom.takeIf { it.isNotBlank() }?.let {
            runCatching { kotlinx.datetime.Instant.parse(it) }.getOrNull()
                ?: return updateState {
                    it.copy(actionState = UiAsync.Error(AppError.Validation("publicar_desde debe tener formato ISO, por ejemplo 2026-04-15T09:00:00Z.")))
                }
        }
        updateState { it.copy(actionState = UiAsync.Loading) }
        val status = currentState().editorStatus.toExamStatus()
        val exam = Exam(
            id = existing?.id.orEmpty(),
            specialtyId = specialty.id,
            specialtyName = specialty.name,
            title = currentState().editorTitle.trim(),
            description = currentState().editorDescription.trim(),
            priceTickets = priceTickets,
            status = status,
            publishFrom = when (status) {
                ExamStatus.PUBLISHED -> existing?.publishFrom
                    ?: publishFrom
                    ?: now
                else -> publishFrom ?: existing?.publishFrom
            },
            publishedAt = when (status) {
                ExamStatus.PUBLISHED -> existing?.publishedAt ?: now
                else -> existing?.publishedAt
            },
            sortOrder = existing?.sortOrder ?: allExams.size,
            questionCount = currentQuestions.size,
            coverUrl = currentState().editorCoverUrl.ifBlank { existing?.coverUrl },
            coverStoragePath = currentState().editorCoverStoragePath.ifBlank { existing?.coverStoragePath },
            coverMimeType = currentState().editorCoverMimeType.ifBlank { existing?.coverMimeType },
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            deletedAt = existing?.deletedAt,
        )
        when (val result = upsertExamUseCase(exam)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("exam_save", mapOf("exam_id" to result.value.id))
                updateState { it.copy(selectedExamId = result.value.id, actionState = UiAsync.Success(Unit)) }
                selectExam(result.value.id)
            }
        }
    }

    private suspend fun deleteSelectedExam() {
        val selectedExamId = currentState().selectedExamId
            ?: return updateState {
                it.copy(actionState = UiAsync.Error(AppError.Validation("Selecciona un examen para borrarlo.")))
            }
        updateState { it.copy(actionState = UiAsync.Loading) }
        when (val result = deleteExamUseCase(selectedExamId)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("exam_delete", mapOf("exam_id" to selectedExamId))
                resetExamEditor()
                updateState { it.copy(actionState = UiAsync.Success(Unit)) }
            }
        }
    }

    private fun createQuestion() {
        val examId = currentState().selectedExamId
            ?: return updateState {
                it.copy(actionState = UiAsync.Error(AppError.Validation("Primero guarda o selecciona un examen.")))
            }
        val questionId = blankQuestionId()
        updateState {
            it.copy(
                selectedQuestionId = null,
                editorQuestionId = questionId,
                editorQuestionTitle = "",
                editorQuestionStatement = "",
                editorOptions = blankOptionDrafts(questionId),
                editorResources = listOf(blankResourceDraft()),
                uploadState = UiAsync.Idle,
                resourceUploadStates = emptyMap(),
                importState = UiAsync.Idle,
                actionState = UiAsync.Idle,
            )
        }
    }

    private fun selectQuestion(id: String) {
        val question = currentQuestions.firstOrNull { it.id == id } ?: return
        updateState {
            it.copy(
                selectedQuestionId = question.id,
                editorQuestionId = question.id,
                editorQuestionTitle = question.title,
                editorQuestionStatement = question.statement,
                editorOptions = question.options.sortedBy { option -> option.sortOrder }.map { option ->
                    QuestionOptionDraft(
                        id = option.id,
                        label = option.label,
                        content = option.content,
                        isCorrect = option.isCorrect,
                        sortOrder = option.sortOrder,
                    )
                },
                editorResources = question.resources.sortedBy { resource -> resource.sortOrder }.map { resource ->
                    QuestionResourceDraft(
                        id = resource.id,
                        type = resource.type.toResourceInputValue(),
                        title = resource.title,
                        inputValue = when (resource.type) {
                            QuestionResourceType.TEXT -> resource.textBody.orEmpty()
                            else -> resource.publicUrl ?: resource.storagePath.orEmpty()
                        },
                        publicUrl = resource.publicUrl,
                        storagePath = resource.storagePath,
                        mimeType = resource.mimeType,
                        localFileName = "",
                        sortOrder = resource.sortOrder,
                    )
                },
                uploadState = UiAsync.Idle,
                resourceUploadStates = emptyMap(),
                importState = UiAsync.Idle,
                actionState = UiAsync.Idle,
            )
        }
    }

    private suspend fun saveQuestion() {
        val examId = currentState().selectedExamId
            ?: return updateState {
                it.copy(actionState = UiAsync.Error(AppError.Validation("Selecciona un examen antes de guardar preguntas.")))
            }
        val questionId = currentState().editorQuestionId
            ?: return updateState {
                it.copy(actionState = UiAsync.Error(AppError.Validation("Crea o selecciona una pregunta.")))
            }
        val existing = currentQuestions.firstOrNull { it.id == questionId }
        val now = Clock.System.now()
        updateState { it.copy(actionState = UiAsync.Loading) }
        val question = ExamQuestion(
            id = questionId,
            examId = examId,
            title = currentState().editorQuestionTitle.trim(),
            statement = currentState().editorQuestionStatement.trim(),
            sortOrder = existing?.sortOrder ?: currentQuestions.size,
            options = currentState().editorOptions.sortedBy { it.sortOrder }.map { option ->
                QuestionOption(
                    id = option.id,
                    questionId = questionId,
                    label = option.label,
                    content = option.content.trim(),
                    isCorrect = option.isCorrect,
                    sortOrder = option.sortOrder,
                )
            },
            resources = currentState().editorResources.sortedBy { it.sortOrder }.mapIndexed { index, resource ->
                val type = resource.type.toResourceType()
                QuestionResource(
                    id = resource.id,
                    questionId = questionId,
                    type = type,
                    title = resource.title.trim(),
                    textBody = if (type == QuestionResourceType.TEXT) resource.inputValue.trim() else null,
                    storagePath = if (type == QuestionResourceType.TEXT) null else resource.storagePath,
                    publicUrl = if (type == QuestionResourceType.TEXT) null else resource.publicUrl
                        ?: resource.inputValue.takeIf { it.startsWith("http://") || it.startsWith("https://") },
                    mimeType = resource.mimeType ?: if (type == QuestionResourceType.TEXT) "text/plain" else null,
                    sortOrder = index,
                )
            },
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        when (val result = upsertQuestionUseCase(question)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("question_save", mapOf("exam_id" to examId, "question_id" to result.value.id))
                loadQuestions(examId)
                updateState { it.copy(selectedQuestionId = result.value.id, editorQuestionId = result.value.id, actionState = UiAsync.Success(Unit)) }
                selectQuestion(result.value.id)
            }
        }
    }

    private suspend fun deleteSelectedQuestion() {
        val examId = currentState().selectedExamId
            ?: return updateState {
                it.copy(actionState = UiAsync.Error(AppError.Validation("Selecciona un examen.")))
            }
        val questionId = currentState().selectedQuestionId
            ?: return updateState {
                it.copy(actionState = UiAsync.Error(AppError.Validation("Selecciona una pregunta guardada para borrarla.")))
            }
        updateState { it.copy(actionState = UiAsync.Loading) }
        when (val result = deleteQuestionUseCase(examId, questionId)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("question_delete", mapOf("question_id" to questionId))
                loadQuestions(examId)
                createQuestion()
                updateState { it.copy(actionState = UiAsync.Success(Unit)) }
            }
        }
    }

    private suspend fun moveQuestion(questionId: String, direction: QuestionMoveDirection) {
        val examId = currentState().selectedExamId ?: return
        when (val result = moveQuestionUseCase(examId, questionId, direction)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                currentQuestions = result.value
                updateState {
                    it.copy(
                        questions = if (result.value.isEmpty()) UiAsync.Empty else UiAsync.Success(result.value),
                        actionState = UiAsync.Success(Unit),
                    )
                }
            }
        }
    }

    private fun updateOption(optionId: String, value: String) {
        updateState {
            it.copy(
                editorOptions = it.editorOptions.map { option ->
                    if (option.id == optionId) option.copy(content = value) else option
                },
            )
        }
    }

    private fun selectCorrectOption(optionId: String) {
        updateState {
            it.copy(
                editorOptions = it.editorOptions.map { option ->
                    option.copy(isCorrect = option.id == optionId)
                },
            )
        }
    }

    private fun addResource() {
        updateState {
            val nextOrder = it.editorResources.size
            if (it.editorQuestionId == null) {
                return@updateState it.copy(
                    uploadState = UiAsync.Error(AppError.Validation("Crea o selecciona una pregunta antes de anadir recursos.")),
                )
            }
            it.copy(
                editorResources = it.editorResources + blankResourceDraft().copy(
                    title = "Recurso ${nextOrder + 1}",
                    sortOrder = nextOrder,
                ),
            )
        }
    }

    private fun removeResource(resourceId: String) {
        updateState {
            it.copy(
                editorResources = it.editorResources
                    .filterNot { resource -> resource.id == resourceId }
                    .mapIndexed { index, resource -> resource.copy(sortOrder = index) },
                resourceUploadStates = it.resourceUploadStates - resourceId,
            )
        }
    }

    private fun updateResource(resourceId: String, transform: QuestionResourceDraft.() -> QuestionResourceDraft) {
        updateState {
            it.copy(
                editorResources = it.editorResources.map { resource ->
                    if (resource.id == resourceId) transform(resource) else resource
                },
            )
        }
    }

    private fun selectResourceFile(
        resourceId: String,
        uri: String,
        suggestedName: String,
        mimeType: String?,
    ) {
        updateResource(resourceId) {
            copy(
                inputValue = uri,
                title = title.takeUnless { it.startsWith("Recurso ") }.orEmpty()
                    .ifBlank { suggestedName.substringBeforeLast('.', suggestedName) },
                type = mimeType.toResourceInputValue(type),
                mimeType = mimeType,
                localFileName = suggestedName,
                publicUrl = null,
                storagePath = null,
            )
        }
        setResourceUploadState(resourceId, UiAsync.Success("Archivo seleccionado"))
    }

    private fun selectExamCoverFile(uri: String, suggestedName: String, mimeType: String?) {
        if (mimeType?.startsWith("image/") != true) {
            updateState {
                it.copy(coverUploadState = UiAsync.Error(AppError.Validation("Selecciona una imagen para la portada.")))
            }
            return
        }
        updateState {
            it.copy(
                editorCoverLocalUri = uri,
                editorCoverFileName = suggestedName.ifBlank { "portada" },
                editorCoverMimeType = mimeType,
                coverUploadState = UiAsync.Success("Archivo seleccionado"),
            )
        }
    }

    private suspend fun uploadExamCover() {
        val examId = currentState().selectedExamId
            ?: return updateState {
                it.copy(coverUploadState = UiAsync.Error(AppError.Validation("Guarda el examen antes de subir portada.")))
            }
        val localUri = currentState().editorCoverLocalUri
        if (localUri.isBlank()) {
            updateState {
                it.copy(coverUploadState = UiAsync.Error(AppError.Validation("Selecciona una portada antes de subir.")))
            }
            return
        }
        updateState { it.copy(coverUploadState = UiAsync.Loading) }
        when (val result = uploadExamCoverUseCase(examId, localUri)) {
            is AppResult.Failure -> updateState { it.copy(coverUploadState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("exam_cover_upload", mapOf("exam_id" to examId))
                updateState {
                    it.copy(
                        editorCoverUrl = result.value.coverUrl.orEmpty(),
                        editorCoverStoragePath = result.value.coverStoragePath.orEmpty(),
                        editorCoverMimeType = result.value.coverMimeType.orEmpty(),
                        editorCoverLocalUri = "",
                        editorCoverFileName = "",
                        coverUploadState = UiAsync.Success("Portada subida"),
                    )
                }
            }
        }
    }

    private fun clearExamCover() {
        updateState {
            it.copy(
                editorCoverUrl = "",
                editorCoverStoragePath = "",
                editorCoverMimeType = "",
                editorCoverLocalUri = "",
                editorCoverFileName = "",
                coverUploadState = UiAsync.Idle,
            )
        }
    }

    private suspend fun importQuestionsFromText(fileName: String, content: String) {
        val examId = currentState().selectedExamId
            ?: return updateState {
                it.copy(importState = UiAsync.Error(AppError.Validation("Selecciona un solucionario antes de importar.")))
            }
        val report = QuestionImportParser.parseReport(content)
        if (report.hasErrors) {
            updateState {
                it.copy(
                    importState = UiAsync.Error(
                        AppError.Validation(report.issues.joinToString("\n") { issue -> issue.message }),
                    ),
                    importSummary = "Importacion cancelada: ${report.issues.size} errores detectados.",
                )
            }
            return
        }
        if (report.questions.isEmpty()) {
            updateState {
                it.copy(
                    importState = UiAsync.Error(AppError.Validation("No se encontraron preguntas en $fileName.")),
                    importSummary = "",
                )
            }
            return
        }
        updateState { it.copy(importState = UiAsync.Loading) }
        val now = Clock.System.now()
        val labels = listOf("A", "B", "C", "D")
        val startOrder = currentQuestions.size
        val questions = report.questions.mapIndexed { index, draft ->
            val questionId = blankQuestionId()
            ExamQuestion(
                id = questionId,
                examId = examId,
                title = draft.title.ifBlank { "Pregunta ${startOrder + index + 1}" },
                statement = draft.statement,
                sortOrder = startOrder + index,
                options = draft.options.mapIndexed { optionIndex, content ->
                    val label = labels[optionIndex]
                    QuestionOption(
                        id = "${questionId}-option-$label",
                        questionId = questionId,
                        label = label,
                        content = content,
                        isCorrect = label == draft.correctLabel,
                        sortOrder = optionIndex,
                    )
                },
                resources = emptyList(),
                createdAt = now,
                updatedAt = now,
            )
        }
        when (val result = importQuestionsUseCase(examId, questions)) {
            is AppResult.Failure -> updateState { it.copy(importState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                loadQuestions(examId)
                analyticsTracker.trackAction("questions_import", mapOf("exam_id" to examId, "count" to result.value.size.toString()))
                val summary = buildString {
                    append("${result.value.size} preguntas importadas")
                    if (report.duplicateCount > 0) append(" | ${report.duplicateCount} duplicadas omitidas")
                    if (report.skippedOverLimit > 0) append(" | ${report.skippedOverLimit} por encima del limite de 50")
                }
                updateState { it.copy(importState = UiAsync.Success(result.value.size), importSummary = summary) }
            }
        }
    }

    private suspend fun uploadResource(resourceId: String) {
        val questionId = currentState().editorQuestionId
            ?: return updateStateWithResourceError(resourceId, "Crea la pregunta antes de subir recursos.")
        val resource = currentState().editorResources.firstOrNull { it.id == resourceId }
            ?: return
        val resourceType = resource.type.toResourceType()
        if (resourceType == QuestionResourceType.TEXT) {
            return updateStateWithResourceError(resourceId, "Los recursos de texto no necesitan subida.")
        }
        if (resource.inputValue.isBlank()) {
            return updateStateWithResourceError(resourceId, "Indica un archivo o URL antes de subir.")
        }
        updateState {
            it.copy(
                uploadState = UiAsync.Loading,
                resourceUploadStates = it.resourceUploadStates + (resourceId to UiAsync.Loading),
            )
        }
        when (
            val result = uploadQuestionResourceUseCase(
                questionId = questionId,
                resourceId = resource.id,
                type = resourceType,
                localPathOrUrl = resource.inputValue.trim(),
                title = resource.title.trim(),
            )
        ) {
            is AppResult.Failure -> updateState {
                it.copy(
                    uploadState = UiAsync.Error(result.error),
                    resourceUploadStates = it.resourceUploadStates + (resourceId to UiAsync.Error(result.error)),
                )
            }
            is AppResult.Success -> {
                updateResource(resource.id) {
                    copy(
                        inputValue = result.value.publicUrl ?: inputValue,
                        publicUrl = result.value.publicUrl,
                        storagePath = result.value.storagePath,
                        mimeType = result.value.mimeType,
                        title = result.value.title,
                        localFileName = "",
                    )
                }
                updateState {
                    it.copy(
                        uploadState = UiAsync.Success(result.value.publicUrl.orEmpty()),
                        resourceUploadStates = it.resourceUploadStates + (resourceId to UiAsync.Success("Recurso subido")),
                    )
                }
            }
        }
    }

    private fun updateStateWithResourceError(resourceId: String, message: String) {
        val error = AppError.Validation(message)
        updateState {
            it.copy(
                uploadState = UiAsync.Error(error),
                resourceUploadStates = it.resourceUploadStates + (resourceId to UiAsync.Error(error)),
            )
        }
    }

    private fun setResourceUploadState(resourceId: String, state: UiAsync<String>) {
        updateState {
            it.copy(resourceUploadStates = it.resourceUploadStates + (resourceId to state))
        }
    }

    private fun String?.toResourceInputValue(fallback: String): String {
        return when {
            this == null -> fallback
            startsWith("image/") -> "image"
            startsWith("audio/") -> "audio"
            startsWith("video/") -> "video"
            else -> "document"
        }
    }
}
