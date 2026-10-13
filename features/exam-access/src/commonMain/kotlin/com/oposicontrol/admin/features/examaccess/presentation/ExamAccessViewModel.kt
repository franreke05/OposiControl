package com.oposicontrol.admin.features.examaccess.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.ExamAccess
import com.oposicontrol.admin.domain.repository.AdminOperationsRepository

/**
 * Accesos a exámenes: quién tiene abierto qué examen de pago, concederlo a mano y retirarlo.
 *
 * Dos reglas que gobiernan la clase:
 *
 * 1. **Se concede a identificadores, no a texto.** La persona sale de una búsqueda en el servidor y el examen del
 *    catálogo; el formulario no tiene ningún campo libre que el servidor deba adivinar.
 * 2. **Conceder y retirar son operaciones distintas.** Antes había un único «guardar» con un interruptor que el servidor
 *    ignoraba, y editar una fila podía traspasar el acceso a otra persona. Aquí una concesión es siempre nueva y una fila
 *    existente sólo se puede retirar.
 */
class ExamAccessViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: AdminOperationsRepository,
    private val analyticsTracker: AnalyticsTracker,
    /** Búsqueda de personas por nombre o username, resuelta en el servidor. */
    private val searchPeople: suspend (query: String) -> AppResult<List<AccessUserOption>>,
    /** Catálogo de exámenes entre los que elegir. */
    private val loadExamCatalog: suspend () -> AppResult<List<AccessExamOption>>,
) : MviViewModel<ExamAccessState, ExamAccessIntent>(
    initialState = ExamAccessState(),
    dispatcherProvider = dispatcherProvider,
) {
    private var writeInFlight = false

    init {
        analyticsTracker.trackScreen("exam_access")
        dispatch(ExamAccessIntent.Refresh)
    }

    override suspend fun handleIntent(intent: ExamAccessIntent) {
        when (intent) {
            ExamAccessIntent.Refresh -> refresh()
            ExamAccessIntent.CreateNew -> {
                updateState {
                    it.copy(
                        creating = true,
                        selectedId = null,
                        userQuery = "",
                        userResults = UiAsync.Idle,
                        pickedUser = null,
                        examQuery = "",
                        pickedExam = null,
                        reason = ExamAccessTypes.DEFAULT,
                        confirmingRevoke = false,
                        actionState = UiAsync.Idle,
                    )
                }
                if (currentState().exams !is UiAsync.Success) loadExams()
            }
            ExamAccessIntent.DiscardDraft -> updateState {
                it.copy(creating = false, selectedId = it.allAccesses.firstOrNull()?.id, actionState = UiAsync.Idle)
            }
            is ExamAccessIntent.Select -> updateState {
                if (it.allAccesses.none { item -> item.id == intent.id }) {
                    it
                } else {
                    it.copy(selectedId = intent.id, creating = false, confirmingRevoke = false, actionState = UiAsync.Idle)
                }
            }
            is ExamAccessIntent.UpdateSearch -> {
                updateState { it.copy(searchQuery = intent.value) }
                publish()
            }
            is ExamAccessIntent.UpdateFilter -> {
                updateState { it.copy(filter = intent.filter) }
                publish()
            }
            is ExamAccessIntent.UpdateUserQuery -> updateState { it.copy(userQuery = intent.value) }
            ExamAccessIntent.SearchUsers -> searchUsers()
            is ExamAccessIntent.PickUser -> updateState { state ->
                val option = (state.userResults as? UiAsync.Success)?.value?.firstOrNull { it.id == intent.userId }
                if (option == null) state else state.copy(pickedUser = option, userResults = UiAsync.Idle, userQuery = "", actionState = UiAsync.Idle)
            }
            ExamAccessIntent.ClearUser -> updateState { it.copy(pickedUser = null, actionState = UiAsync.Idle) }
            is ExamAccessIntent.UpdateExamQuery -> updateState { it.copy(examQuery = intent.value) }
            is ExamAccessIntent.PickExam -> updateState { state ->
                val option = (state.exams as? UiAsync.Success)?.value?.firstOrNull { it.id == intent.examId }
                if (option == null) state else state.copy(pickedExam = option, examQuery = "", actionState = UiAsync.Idle)
            }
            ExamAccessIntent.ClearExam -> updateState { it.copy(pickedExam = null, actionState = UiAsync.Idle) }
            ExamAccessIntent.RetryExams -> loadExams()
            is ExamAccessIntent.UpdateReason -> updateState {
                // Sólo los motivos que el servidor acepta de una concesión manual; cualquier otro se ignora.
                if (ExamAccessTypes.grantable.any { option -> option.first == intent.code }) it.copy(reason = intent.code) else it
            }
            ExamAccessIntent.Grant -> grant()
            ExamAccessIntent.RequestRevoke -> updateState {
                val item = it.selected
                if (item != null && item.isActive && !item.isPurchase) it.copy(confirmingRevoke = true) else it
            }
            ExamAccessIntent.DismissRevoke -> updateState { it.copy(confirmingRevoke = false) }
            ExamAccessIntent.ConfirmRevoke -> revoke()
        }
    }

    private fun publish() {
        val state = currentState()
        // Antes de la primera respuesta no hay nada que filtrar: se respeta el estado de carga o de error.
        if (state.accesses is UiAsync.Loading || (state.accesses is UiAsync.Error && state.allAccesses.isEmpty())) return
        val filtered = state.allAccesses.filter { accessMatches(it, state.searchQuery, state.filter) }
        updateState { it.copy(accesses = if (filtered.isEmpty()) UiAsync.Empty else UiAsync.Success(filtered)) }
    }

    private suspend fun refresh() {
        // Releer con datos en pantalla no los quita: sólo la primera lectura enseña el esqueleto.
        val hadRows = currentState().allAccesses.isNotEmpty()
        if (!hadRows) updateState { it.copy(accesses = UiAsync.Loading) }
        when (val result = repository.getExamAccesses()) {
            is AppResult.Failure -> updateState { it.copy(accesses = UiAsync.Error(result.error), allAccesses = emptyList()) }
            is AppResult.Success -> {
                val rows = result.value.map { it.toItem() }
                updateState { state ->
                    val keepsSelection = state.selectedId != null && rows.any { it.id == state.selectedId }
                    state.copy(
                        allAccesses = rows,
                        accesses = UiAsync.Success(rows),
                        selectedId = when {
                            state.creating -> null
                            keepsSelection -> state.selectedId
                            else -> rows.firstOrNull()?.id
                        },
                    )
                }
                publish()
            }
        }
    }

    private suspend fun loadExams() {
        updateState { it.copy(exams = UiAsync.Loading) }
        val loaded = when (val result = loadExamCatalog()) {
            is AppResult.Failure -> UiAsync.Error(result.error)
            is AppResult.Success -> if (result.value.isEmpty()) UiAsync.Empty else UiAsync.Success(result.value)
        }
        updateState { it.copy(exams = loaded) }
    }

    private suspend fun searchUsers() {
        val query = currentState().userQuery.trim()
        if (query.isEmpty()) {
            updateState { it.copy(userResults = UiAsync.Idle) }
            return
        }
        updateState { it.copy(userResults = UiAsync.Loading) }
        val found = when (val result = searchPeople(query)) {
            is AppResult.Failure -> UiAsync.Error(result.error)
            is AppResult.Success -> if (result.value.isEmpty()) UiAsync.Empty else UiAsync.Success(result.value)
        }
        // Si entre tanto se cambió lo tecleado, estos resultados responden a otra pregunta: se descartan.
        updateState { if (it.userQuery.trim() == query) it.copy(userResults = found) else it }
    }

    private suspend fun grant() {
        if (writeInFlight) return
        val state = currentState()
        // Los intents se atienden en orden: un segundo clic llega cuando el primero ya terminó y cerró el formulario.
        if (!state.creating) return
        val user = state.pickedUser
        val exam = state.pickedExam
        if (user == null || exam == null) {
            val missing = if (user == null) "Elige a la persona" else "Elige el examen"
            updateState { it.copy(actionState = UiAsync.Error(AppError.Validation("$missing antes de conceder el acceso."))) }
            return
        }
        writeInFlight = true
        try {
            updateState { it.copy(actionState = UiAsync.Loading) }
            val request = ExamAccess(
                // Sin id: el servidor crea siempre una fila nueva (o devuelve la vigente si ya tenía acceso).
                id = "",
                userName = user.label,
                examTitle = exam.title,
                accessType = state.reason,
                isActive = true,
                userId = user.id,
                examId = exam.id,
            )
            when (val result = repository.upsertExamAccess(request)) {
                is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
                is AppResult.Success -> {
                    val saved = result.value.toItem()
                    analyticsTracker.trackAction("exam_access_grant", mapOf("access_id" to saved.id))
                    updateState {
                        it.copy(
                            allAccesses = listOf(saved) + it.allAccesses.filterNot { item -> item.id == saved.id },
                            creating = false,
                            pickedUser = null,
                            pickedExam = null,
                            selectedId = saved.id,
                            // Se limpian búsqueda y filtro: lo recién concedido tiene que verse en el catálogo.
                            searchQuery = "",
                            filter = AccessFilter.ALL,
                            accesses = UiAsync.Success(emptyList()),
                            actionState = UiAsync.Success(Unit),
                        )
                    }
                    publish()
                }
            }
        } finally {
            writeInFlight = false
        }
    }

    private suspend fun revoke() {
        if (writeInFlight) return
        val item = currentState().selected ?: return
        if (!item.isActive || item.isPurchase) return
        writeInFlight = true
        try {
            updateState { it.copy(confirmingRevoke = false, actionState = UiAsync.Loading) }
            when (val result = repository.revokeExamAccess(item.id)) {
                is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
                is AppResult.Success -> {
                    analyticsTracker.trackAction("exam_access_revoke", mapOf("access_id" to item.id))
                    updateState { it.copy(actionState = UiAsync.Success(Unit)) }
                    // Se relee la lista entera: retirar cierra todas las concesiones manuales de esa persona sobre ese
                    // examen, no sólo la fila abierta.
                    refresh()
                }
            }
        } finally {
            writeInFlight = false
        }
    }

    private fun ExamAccess.toItem() = ExamAccessItem(
        id = id,
        userName = userName,
        examTitle = examTitle,
        accessType = accessType,
        status = if (isActive) ExamAccessItem.STATUS_ACTIVE else ExamAccessItem.STATUS_INACTIVE,
        userId = userId,
        examId = examId,
        grantedAt = grantedAt,
        validUntil = validUntil,
    )
}
