package com.oposicontrol.admin.features.examaccess.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.logging.LogEvent
import com.oposicontrol.admin.core.common.logging.LogLayer
import com.oposicontrol.admin.core.common.logging.LogLevel
import com.oposicontrol.admin.core.common.logging.Logger
import com.oposicontrol.admin.core.common.logging.LogTag
import com.oposicontrol.admin.core.common.logging.NoOpLogger
import com.oposicontrol.admin.core.common.logging.OperationContext
import com.oposicontrol.admin.core.common.logging.SafeLogField
import com.oposicontrol.admin.core.common.logging.withOperationContext
import com.oposicontrol.admin.core.common.presentation.MviEffectViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.ExamAccess
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.User
import com.oposicontrol.admin.domain.repository.AdminOperationsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

class ExamAccessViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: AdminOperationsRepository,
    private val analyticsTracker: AnalyticsTracker,
    private val logger: Logger = NoOpLogger,
    private val users: Flow<List<User>> = emptyFlow(),
    private val exams: Flow<List<Exam>> = emptyFlow(),
    private val refreshExamCatalog: suspend () -> AppResult<List<Exam>> = {
        AppResult.Success(emptyList())
    },
) : MviEffectViewModel<ExamAccessState, ExamAccessIntent, ExamAccessEffect>(
    initialState = ExamAccessState(),
    dispatcherProvider = dispatcherProvider,
) {
    private var allAccesses: List<ExamAccessItem> = emptyList()
    private var refreshJob: Job? = null
    private var refreshGeneration = 0L
    private var lastSavedDraft: ExamAccessDraft? = null

    init {
        analyticsTracker.trackScreen("exam_access")
        observeCatalogs()
        dispatch(ExamAccessIntent.Refresh)
    }

    private fun observeCatalogs() {
        scope.launch {
            users.collectLatest { catalog ->
                val options = catalog
                    .filter(User::isActive)
                    .sortedBy { it.fullName.lowercase() }
                    .map { ExamAccessUserOption(UserId(it.id), it.fullName) }
                updateState {
                    it.copy(
                        userCatalog = if (options.isEmpty()) UiAsync.Empty else UiAsync.Success(options),
                    )
                }
            }
        }
        scope.launch {
            exams.collectLatest { catalog ->
                val options = catalog
                    .filter { it.status != ExamStatus.ARCHIVED && it.deletedAt == null }
                    .sortedBy { it.title.lowercase() }
                    .map { ExamAccessExamOption(ExamId(it.id), it.title) }
                updateState {
                    it.copy(
                        examCatalog = if (options.isEmpty()) UiAsync.Empty else UiAsync.Success(options),
                    )
                }
            }
        }
        scope.launch {
            when (val result = refreshExamCatalog()) {
                is AppResult.Failure -> updateState {
                    it.copy(examCatalog = UiAsync.Error(result.error))
                }

                is AppResult.Success -> Unit
            }
        }
    }

    override suspend fun handleIntent(intent: ExamAccessIntent) {
        when (intent) {
            ExamAccessIntent.CreateNew -> createNew()
            ExamAccessIntent.Refresh -> refresh()
            ExamAccessIntent.Save -> save()
            is ExamAccessIntent.Select -> select(intent.id)
            is ExamAccessIntent.SelectExam -> editorChanged {
                it.copy(
                    editorExamId = intent.id,
                    editorExamTitle = intent.displayTitle,
                )
            }

            is ExamAccessIntent.SelectUser -> editorChanged {
                it.copy(
                    editorUserId = intent.id,
                    editorUserName = intent.displayName,
                )
            }

            is ExamAccessIntent.ToggleActive -> editorChanged {
                it.copy(editorIsActive = intent.value)
            }

            is ExamAccessIntent.UpdateAccessType -> editorChanged {
                it.copy(editorAccessType = intent.value)
            }

            is ExamAccessIntent.UpdateExamTitle -> editorChanged {
                it.copy(editorExamTitle = intent.value)
            }

            is ExamAccessIntent.UpdateSearch -> {
                updateState { it.copy(searchQuery = intent.value) }
                publish()
            }

            is ExamAccessIntent.UpdateUserName -> editorChanged {
                it.copy(editorUserName = intent.value)
            }
        }
    }

    private fun createNew() {
        lastSavedDraft = null
        updateState {
            it.copy(
                selectedId = null,
                isCreating = true,
                editorUserId = null,
                editorExamId = null,
                editorUserName = "",
                editorExamTitle = "",
                editorAccessType = "manual",
                editorIsActive = true,
                editorVersion = 1,
                actionState = UiAsync.Idle,
            )
        }
    }

    private fun editorChanged(transform: (ExamAccessState) -> ExamAccessState) {
        lastSavedDraft = null
        updateState { transform(it).copy(actionState = UiAsync.Idle) }
    }

    private fun select(id: ExamAccessId) {
        val item = allAccesses.firstOrNull { it.id == id } ?: return
        lastSavedDraft = null
        updateState {
            it.copy(
                selectedId = item.id,
                isCreating = false,
                editorUserId = item.userId,
                editorExamId = item.examId,
                editorUserName = item.userName,
                editorExamTitle = item.examTitle,
                editorAccessType = item.accessType,
                editorIsActive = item.status == "active",
                editorVersion = item.version,
                actionState = UiAsync.Idle,
            )
        }
    }

    private fun publish() {
        val query = currentState().searchQuery.trim().lowercase()
        val filtered = allAccesses.filter { item ->
            query.isBlank() || item.userName.lowercase().contains(query) || item.examTitle.lowercase().contains(query)
        }
        updateState { it.copy(accesses = if (filtered.isEmpty()) UiAsync.Empty else UiAsync.Success(filtered)) }
    }

    private fun refresh() {
        val generation = ++refreshGeneration
        refreshJob?.cancel()
        updateState {
            it.copy(
                accesses = if (it.accesses is UiAsync.Success || it.accesses is UiAsync.Empty) {
                    it.accesses
                } else {
                    UiAsync.Loading
                },
                isRefreshing = true,
                refreshError = null,
            )
        }
        refreshJob = scope.launch {
            val result = traceOperation(
                operation = "exam_access.refresh",
                successCount = { it.size },
            ) {
                repository.getExamAccesses()
            }
            // WHY: a cancelled transport may still return; stale refreshes must not replace newer data.
            if (generation != refreshGeneration) return@launch
            when (result) {
                is AppResult.Failure -> updateState {
                    it.copy(
                        accesses = if (it.accesses is UiAsync.Success || it.accesses is UiAsync.Empty) {
                            it.accesses
                        } else {
                            UiAsync.Error(result.error)
                        },
                        isRefreshing = false,
                        refreshError = result.error,
                    )
                }

                is AppResult.Success -> {
                    allAccesses = result.value.map { it.toItem() }
                    publish()
                    val state = currentState()
                    if (!state.isCreating && state.selectedId == null) {
                        allAccesses.firstOrNull()?.let { select(it.id) }
                    } else if (
                        state.selectedId != null &&
                        allAccesses.none { it.id == state.selectedId }
                    ) {
                        allAccesses.firstOrNull()?.let { select(it.id) } ?: createNew()
                    }
                    updateState { it.copy(isRefreshing = false, refreshError = null) }
                }
            }
        }
    }

    private suspend fun save() {
        val state = currentState()
        val userId = state.editorUserId
        val examId = state.editorExamId
        if (userId == null || examId == null) {
            updateState {
                it.copy(
                    actionState = UiAsync.Error(
                        AppError.Validation("Selecciona usuario y examen mediante sus IDs antes de guardar."),
                    ),
                )
            }
            return
        }
        if (state.editorAccessType.isBlank()) {
            updateState {
                it.copy(actionState = UiAsync.Error(AppError.Validation("Selecciona un tipo de acceso.")))
            }
            return
        }
        val draft = ExamAccessDraft(
            userId = userId,
            examId = examId,
            accessType = state.editorAccessType.trim(),
            isActive = state.editorIsActive,
        )
        if (draft == lastSavedDraft && state.actionState is UiAsync.Success) return
        refreshJob?.cancel()
        refreshGeneration += 1
        updateState { it.copy(actionState = UiAsync.Loading) }
        val item = ExamAccess(
            id = state.selectedId?.value.orEmpty(),
            userName = state.editorUserName,
            examTitle = state.editorExamTitle,
            accessType = draft.accessType,
            isActive = draft.isActive,
            userId = draft.userId.value,
            examId = draft.examId.value,
            version = state.editorVersion,
        )
        when (
            val result = traceOperation(operation = "exam_access.save") {
                repository.upsertExamAccess(item)
            }
        ) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                val saved = result.value.toItem()
                allAccesses = allAccesses.filterNot { it.id == saved.id } + saved
                lastSavedDraft = draft
                analyticsTracker.trackAction("exam_access_save", mapOf("access_id" to saved.id.value))
                updateState {
                    it.copy(
                        selectedId = saved.id,
                        isCreating = false,
                        editorUserId = saved.userId,
                        editorExamId = saved.examId,
                        editorUserName = saved.userName,
                        editorExamTitle = saved.examTitle,
                        editorAccessType = saved.accessType,
                        editorIsActive = saved.status == "active",
                        editorVersion = saved.version,
                        actionState = UiAsync.Success(Unit),
                    )
                }
                publish()
                emitEffect(ExamAccessEffect.Saved(saved.id))
            }
        }
    }

    private fun ExamAccess.toItem() = ExamAccessItem(
        id = ExamAccessId(id),
        userId = userId.takeIf { it.isNotBlank() }?.let(::UserId),
        examId = examId.takeIf { it.isNotBlank() }?.let(::ExamId),
        userName = userName,
        examTitle = examTitle,
        accessType = accessType,
        status = if (isActive) "active" else "inactive",
        version = version,
    )

    private data class ExamAccessDraft(
        val userId: UserId,
        val examId: ExamId,
        val accessType: String,
        val isActive: Boolean,
    )

    private suspend fun <T> traceOperation(
        operation: String,
        successCount: (T) -> Int? = { null },
        block: suspend () -> AppResult<T>,
    ): AppResult<T> {
        val context = OperationContext()
        val startedAt = TimeSource.Monotonic.markNow()
        logger.log(vmEvent(LogLevel.INFO, "$operation.start", operation, context))
        return try {
            val result = withOperationContext(context) { block() }
            when (result) {
                is AppResult.Failure -> logger.log(
                    vmEvent(
                        level = LogLevel.ERROR,
                        event = "$operation.failure",
                        operation = operation,
                        context = context,
                        durationMs = startedAt.elapsedNow().inWholeMilliseconds,
                        fields = mapOf(
                            SafeLogField.RESULT to "failure",
                            SafeLogField.ERROR_TYPE to result.error.safeType(),
                            SafeLogField.RETRYABLE to result.error.isRetryable().toString(),
                        ),
                    ),
                )

                is AppResult.Success -> {
                    val fields = mutableMapOf(SafeLogField.RESULT to "success")
                    successCount(result.value)?.let { fields[SafeLogField.COUNT] = it.toString() }
                    logger.log(
                        vmEvent(
                            level = LogLevel.INFO,
                            event = "$operation.success",
                            operation = operation,
                            context = context,
                            durationMs = startedAt.elapsedNow().inWholeMilliseconds,
                            fields = fields,
                        ),
                    )
                }
            }
            result
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            logger.log(
                vmEvent(
                    level = LogLevel.ERROR,
                    event = "$operation.failure",
                    operation = operation,
                    context = context,
                    durationMs = startedAt.elapsedNow().inWholeMilliseconds,
                    fields = mapOf(
                        SafeLogField.RESULT to "failure",
                        SafeLogField.ERROR_TYPE to (throwable::class.simpleName ?: "Throwable"),
                        SafeLogField.RETRYABLE to "false",
                    ),
                ),
            )
            throw throwable
        }
    }

    private fun vmEvent(
        level: LogLevel,
        event: String,
        operation: String,
        context: OperationContext,
        durationMs: Long? = null,
        fields: Map<SafeLogField, String> = emptyMap(),
    ) = LogEvent(
        level = level,
        tag = LogTag.VIEW_MODEL,
        event = event,
        screen = "exam_access",
        layer = LogLayer.VIEW_MODEL,
        operation = operation,
        correlationId = context.correlationId,
        durationMs = durationMs,
        fields = fields,
    )

    private fun AppError.safeType(): String = when (this) {
        is AppError.Network -> "network"
        is AppError.Authentication -> "authentication"
        is AppError.Authorization -> "authorization"
        is AppError.Validation -> "validation"
        is AppError.NotFound -> "not_found"
        is AppError.Conflict -> "conflict"
        is AppError.RateLimited -> "rate_limited"
        is AppError.Unknown -> "unknown"
    }

    private fun AppError.isRetryable(): Boolean =
        this is AppError.Network || this is AppError.RateLimited
}
