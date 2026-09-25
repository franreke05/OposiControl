package com.oposicontrol.admin.features.exams.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.Community
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamQuestion
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.ExamType
import com.oposicontrol.admin.domain.model.QuestionMoveDirection
import com.oposicontrol.admin.domain.model.QuestionOption
import com.oposicontrol.admin.domain.model.QuestionResource
import com.oposicontrol.admin.domain.model.QuestionResourceType
import com.oposicontrol.admin.domain.model.Rafaga
import com.oposicontrol.admin.domain.model.RafagaQuestion
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.model.SpecialtyCapabilities
import com.oposicontrol.admin.domain.model.toAudit
import com.oposicontrol.admin.domain.usecase.communities.ObserveCommunitiesUseCase
import com.oposicontrol.admin.domain.usecase.communities.RefreshCommunitiesUseCase
import com.oposicontrol.admin.domain.usecase.content.ArchiveExamUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteExamUseCase
import com.oposicontrol.admin.domain.usecase.content.ArchiveQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteQuestionResourceUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteRafagaQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteRafagaUseCase
import com.oposicontrol.admin.domain.usecase.content.DeleteSpecialtyUseCase
import com.oposicontrol.admin.domain.usecase.content.GetExamResourceAuditUseCase
import com.oposicontrol.admin.domain.usecase.content.GetExamQuestionsUseCase
import com.oposicontrol.admin.domain.usecase.content.GetRafagaQuestionsUseCase
import com.oposicontrol.admin.domain.usecase.content.GetSpecialtyCapabilitiesUseCase
import com.oposicontrol.admin.domain.usecase.content.PublishExamUseCase
import com.oposicontrol.admin.domain.usecase.content.UnpublishExamUseCase
import com.oposicontrol.admin.domain.usecase.content.ImportQuestionsUseCase
import com.oposicontrol.admin.domain.usecase.content.MoveQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.MoveRafagaQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveAllExamsUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveRafagasUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveSpecialtiesUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshAllExamsUseCase
import com.oposicontrol.admin.domain.usecase.content.UnlinkQuestionFromExamUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshRafagasUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshSpecialtiesUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertExamUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertRafagaQuestionUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertRafagaUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertSpecialtyUseCase
import com.oposicontrol.admin.domain.usecase.content.UploadQuestionResourceUseCase
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.time.Clock

class ExamsViewModel(
    dispatcherProvider: DispatcherProvider,
    observeSpecialtiesUseCase: ObserveSpecialtiesUseCase,
    /**
     * Fuente única del catálogo: TODOS los tipos.
     *
     * Antes eran `ObserveExamsUseCase` + `ObserveRafagasUseCase`, y el reparto
     * previo entre esos dos cubos era justamente donde se perdía un examen
     * `razonado`: no caía en ninguno. `observeRafagas` sigue aquí porque el
     * camino móvil tiene su propia lista de ráfagas, pero la lista de exámenes
     * de esta pantalla ya no se construye sumando cubos.
     */
    observeAllExamsUseCase: ObserveAllExamsUseCase,
    observeRafagasUseCase: ObserveRafagasUseCase,
    private val refreshSpecialtiesUseCase: RefreshSpecialtiesUseCase,
    private val upsertSpecialtyUseCase: UpsertSpecialtyUseCase,
    private val deleteSpecialtyUseCase: DeleteSpecialtyUseCase,
    private val refreshAllExamsUseCase: RefreshAllExamsUseCase,
    /**
     * Ya no se llama (K0-5): las ráfagas llegan con [refreshAllExamsUseCase], que
     * rellena la misma lista que observa [observeRafagasUseCase]. Se conserva el
     * parámetro para no cambiar el módulo de Koin en esta ronda.
     */
    @Suppress("UNUSED_PARAMETER", "unused")
    refreshRafagasUseCase: RefreshRafagasUseCase,
    private val getExamQuestionsUseCase: GetExamQuestionsUseCase,
    private val getRafagaQuestionsUseCase: GetRafagaQuestionsUseCase,
    private val getExamResourceAuditUseCase: GetExamResourceAuditUseCase,
    private val upsertExamUseCase: UpsertExamUseCase,
    /** BORRA el examen. Irreversible, y el servidor puede negarse. */
    private val deleteExamUseCase: DeleteExamUseCase,

    /** Lo ARCHIVA. Reversible, y por su propia ruta. */
    private val archiveExamUseCase: ArchiveExamUseCase,
    private val publishExamUseCase: PublishExamUseCase,
    private val unpublishExamUseCase: UnpublishExamUseCase,
    private val getSpecialtyCapabilitiesUseCase: GetSpecialtyCapabilitiesUseCase,
    observeCommunitiesUseCase: ObserveCommunitiesUseCase,
    private val refreshCommunitiesUseCase: RefreshCommunitiesUseCase,
    private val upsertRafagaUseCase: UpsertRafagaUseCase,
    private val deleteRafagaUseCase: DeleteRafagaUseCase,
    private val upsertQuestionUseCase: UpsertQuestionUseCase,
    private val importQuestionsUseCase: ImportQuestionsUseCase,
    /** Quita el vínculo pregunta↔examen. La pregunta sigue en el banco. */
    private val unlinkQuestionFromExamUseCase: UnlinkQuestionFromExamUseCase,
    /** Saca la pregunta del banco: desaparece de todos los exámenes en los que esté. */
    private val archiveQuestionUseCase: ArchiveQuestionUseCase,
    private val moveQuestionUseCase: MoveQuestionUseCase,
    private val upsertRafagaQuestionUseCase: UpsertRafagaQuestionUseCase,
    private val deleteRafagaQuestionUseCase: DeleteRafagaQuestionUseCase,
    private val moveRafagaQuestionUseCase: MoveRafagaQuestionUseCase,
    private val uploadQuestionResourceUseCase: UploadQuestionResourceUseCase,
    private val deleteQuestionResourceUseCase: DeleteQuestionResourceUseCase,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<ExamsState, ExamsIntent>(
    initialState = ExamsState(),
    dispatcherProvider = dispatcherProvider,
) {
    private var allSpecialties: List<Specialty> = emptyList()
    private var allExams: List<Exam> = emptyList()
    private var allRafagas: List<Rafaga> = emptyList()
    private var currentQuestions: List<ExamQuestion> = emptyList()
    private var currentRafagaQuestions: List<RafagaQuestion> = emptyList()

    /**
     * El `Error` del último guardado del examen: la misma instancia que se publicó
     * en `actionState`. Ese campo lo comparten otras acciones —quitar o mover una
     * pregunta, archivar—, y ésta es la forma de distinguir el error del guardado
     * de los suyos: la siguiente edición del formulario retira el primero y deja
     * los demás ([withoutSaveResult]).
     */
    private var lastExamSaveError: UiAsync.Error? = null

    init {
        scope.launch {
            observeSpecialtiesUseCase().collectLatest { specialties ->
                allSpecialties = specialties
                updateState {
                    it.copy(
                        specialties = seeded(specialties, it.specialties),
                        // Sin preselección de `editorSpecialtyId`: un examen nuevo
                        // no hereda la primera especialidad del catálogo. Esa
                        // «primera» la decide el orden del catálogo —inactivas
                        // incluidas—, no quien administra, y bastaba escribir un
                        // título para crear el examen en ella.
                        selectedContentSpecialtyId = it.selectedContentSpecialtyId ?: specialties.firstOrNull()?.id,
                    )
                }
                if (currentState().specialtyEditorName.isBlank()) {
                    specialties.firstOrNull { it.id == currentState().selectedContentSpecialtyId }
                        ?.let { selectContentSpecialty(it.id) }
                }
                // Primera carga: las especialidades llegan después de que el
                // editor arrancara vacío, así que sus capacidades aún no se han
                // pedido. `selectEditorSpecialty` es idempotente (mismo id), por
                // eso es seguro no comprobar aquí si ya se había cargado antes.
                val filledSpecialty = currentState().editorSpecialtyId
                if (filledSpecialty.isNotBlank() && currentState().specialtyCapabilities is UiAsync.Idle) {
                    selectEditorSpecialty(filledSpecialty)
                }
                publishExams()
            }
        }
        scope.launch {
            observeAllExamsUseCase().collectLatest { exams ->
                allExams = exams
                publishExams()
                if (currentState().selectedExamId != null && exams.none { it.id == currentState().selectedExamId }) {
                    resetExamEditor()
                }
            }
        }
        scope.launch {
            observeCommunitiesUseCase().collectLatest { communities ->
                updateState { it.copy(communities = seeded(communities, it.communities)) }
            }
        }
        // Las comunidades y las especialidades se piden UNA vez, en `refreshAll`
        // (el `RefreshAll` del final de este bloque), que es también su
        // «Reintentar». Las especialidades tenían aquí un segundo refresco propio
        // y cada arranque las pedía dos veces (EX-13, K0-5); `refreshAll` ya
        // resuelve el vacío confirmado con `loadedCatalog`, así que un catálogo
        // sin especialidades tampoco se queda en `Loading`.
        scope.launch {
            observeRafagasUseCase().collectLatest { rafagas ->
                allRafagas = rafagas
                updateState {
                    it.copy(
                        rafagas = if (rafagas.isEmpty()) UiAsync.Empty else UiAsync.Success(rafagas),
                    )
                }
                if (currentState().selectedRafagaId != null && rafagas.none { it.id == currentState().selectedRafagaId }) {
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
            ExamsIntent.CreateRafaga -> resetRafagaEditor()
            ExamsIntent.CreateQuestion -> createQuestion()
            ExamsIntent.CreateRafagaQuestion -> createRafagaQuestion()
            ExamsIntent.CreateSpecialty -> createSpecialty()
            ExamsIntent.DeleteSelectedExam -> deleteSelectedExam()
            ExamsIntent.ArchiveSelectedExam -> archiveSelectedExam()
            ExamsIntent.DeleteSelectedQuestion -> deleteSelectedQuestion()
            ExamsIntent.DeleteSelectedRafaga -> deleteSelectedRafaga()
            ExamsIntent.DeleteSelectedRafagaQuestion -> deleteSelectedRafagaQuestion()
            ExamsIntent.DeleteSelectedSpecialty -> deleteSelectedSpecialty()
            // Dos peticiones y dos avisos, porque son dos acciones. El texto de
            // cada uno vive en la capa pura (`ExamPublicationPresentation`), donde
            // el test lo compara contra lo mismo que lee la pantalla.
            ExamsIntent.RequestArchiveSelectedExam -> requestDelete(
                PendingDeleteTarget.ArchiveExam,
                EXAM_ARCHIVE_WARNING,
            )
            ExamsIntent.RequestDeleteSelectedExam -> requestDelete(
                PendingDeleteTarget.DeleteExamForever,
                EXAM_DELETE_FOREVER_WARNING,
            )
            // El mensaje dice lo que la operación HACE, no lo que su antiguo
            // nombre sugería: archivar una pregunta la saca del banco, así que
            // deja de estar en todos los exámenes en los que estuviera.
            ExamsIntent.RequestDeleteSelectedQuestion -> requestDelete(
                PendingDeleteTarget.Question,
                ARCHIVE_QUESTION_WARNING,
            )
            is ExamsIntent.RequestUnlinkQuestion -> requestDelete(
                PendingDeleteTarget.UnlinkQuestion(intent.questionId),
                "La pregunta sale de este examen y sigue en el banco, disponible para otros. " +
                    "Las siguientes se renumeran.",
            )
            is ExamsIntent.RequestArchiveQuestion -> requestDelete(
                PendingDeleteTarget.ArchiveQuestion(intent.questionId),
                ARCHIVE_QUESTION_WARNING,
            )
            // Una ráfaga es una fila de `examenes`, así que `deleteRafaga` llama
            // al MISMO `DELETE /examenes/{id}` que el borrado de examen y ha
            // heredado su semántica nueva: borra de verdad, y el servidor se
            // niega si tiene datos de usuarios. El aviso decía «y sus preguntas»,
            // que no era cierto ni antes ni ahora — lo que se borra es la
            // asociación, y las preguntas se quedan en el banco.
            ExamsIntent.RequestDeleteSelectedRafaga -> requestDelete(
                PendingDeleteTarget.Rafaga,
                "La ráfaga se borra del servidor y no se puede deshacer. Sus preguntas se quedan en el banco. " +
                    "Si algún usuario la ha usado, el servidor no la borrará.",
            )
            ExamsIntent.RequestDeleteSelectedRafagaQuestion -> requestDelete(PendingDeleteTarget.RafagaQuestion, "Eliminar esta pregunta de rafaga?")
            ExamsIntent.RequestDeleteSelectedSpecialty -> requestDelete(PendingDeleteTarget.Specialty, "Eliminar esta especialidad?")
            ExamsIntent.PublishExam -> publishExam()
            ExamsIntent.UnpublishExam -> unpublishExam()
            ExamsIntent.ConfirmPendingDelete -> confirmPendingDelete()
            ExamsIntent.DismissPendingDelete -> updateState { it.copy(pendingDeleteTarget = null, pendingDeleteMessage = "") }
            ExamsIntent.RefreshAll -> refreshAll()
            ExamsIntent.RefreshResourceAudit -> refreshResourceAudit()
            ExamsIntent.RetryLoadQuestions -> retryLoadQuestions()
            ExamsIntent.SaveExam -> saveExam()
            ExamsIntent.SaveQuestion -> saveQuestion()
            ExamsIntent.SaveRafaga -> saveRafaga()
            ExamsIntent.SaveRafagaQuestion -> saveRafagaQuestion()
            ExamsIntent.SaveSpecialty -> saveSpecialty()
            is ExamsIntent.MoveQuestionDown -> moveQuestion(intent.id, QuestionMoveDirection.DOWN)
            is ExamsIntent.MoveQuestionUp -> moveQuestion(intent.id, QuestionMoveDirection.UP)
            is ExamsIntent.MoveRafagaQuestionDown -> moveRafagaQuestion(intent.id, QuestionMoveDirection.DOWN)
            is ExamsIntent.MoveRafagaQuestionUp -> moveRafagaQuestion(intent.id, QuestionMoveDirection.UP)
            is ExamsIntent.RemoveResource -> removeResource(intent.resourceId)
            is ExamsIntent.RequestRemoveResource -> requestDelete(PendingDeleteTarget.Resource(intent.resourceId), "Eliminar este recurso y su archivo asociado?")
            is ExamsIntent.SelectCorrectOption -> selectCorrectOption(intent.optionId)
            is ExamsIntent.SelectContentSpecialty -> selectContentSpecialty(intent.id)
            is ExamsIntent.SelectExam -> selectExam(intent.id)
            is ExamsIntent.SwitchExam -> switchExam(intent.id)
            is ExamsIntent.SelectQuestion -> selectQuestion(intent.id)
            is ExamsIntent.SelectRafaga -> selectRafaga(intent.id)
            is ExamsIntent.SelectRafagaQuestion -> selectRafagaQuestion(intent.id)
            is ExamsIntent.ToggleSpecialtyActive -> updateState { it.copy(specialtyEditorIsActive = intent.value) }
            // Ediciones del formulario del examen: `editExamForm` retira además el
            // resultado del último guardado, confirmación o error.
            is ExamsIntent.UpdateDescription -> editExamForm { it.copy(editorDescription = intent.value) }
            is ExamsIntent.UpdateCode -> editExamForm { it.copy(editorCode = intent.value) }
            is ExamsIntent.UpdateCommunityId -> editExamForm { it.copy(editorCommunityId = intent.value) }
            is ExamsIntent.UpdateTypeExam -> editExamForm { it.copy(editorTypeExam = intent.value) }
            is ExamsIntent.UpdateExamStatus -> editExamForm { it.copy(editorStatus = intent.value) }
            is ExamsIntent.UpdateTypeAccess -> editExamForm { it.copy(editorTypeAccess = intent.value) }
            is ExamsIntent.UpdateYear -> editExamForm { it.copy(editorYear = intent.value.filter { c -> c.isDigit() }.take(4)) }
            is ExamsIntent.ToggleIsOfficial -> editExamForm { it.copy(editorIsOfficial = intent.value) }
            is ExamsIntent.UpdateDurationMinutes -> editExamForm { it.copy(editorDurationMinutes = intent.value.filter { c -> c.isDigit() }.take(4)) }
            is ExamsIntent.UpdatePriceTickets -> editExamForm { it.copy(editorPriceTickets = intent.value.filter { char -> char.isDigit() }.take(9)) }
            is ExamsIntent.UpdateOptionContent -> updateOption(intent.optionId, intent.value)
            is ExamsIntent.UpdatePublishFrom -> editExamForm { it.copy(editorPublishFrom = intent.value) }
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

            // Cambiar de especialidad NUNCA mueve el tipo elegido. Reintentar las
            // capacidades de la misma tampoco es un cambio: ni retira el resultado
            // del último guardado.
            is ExamsIntent.UpdateSpecialtyId -> {
                val changed = intent.value != currentState().editorSpecialtyId
                if (changed) updateState { it.withoutSaveResult() }
                selectEditorSpecialty(intent.value)
            }
            is ExamsIntent.UpdateStatusFilter -> {
                updateState { it.copy(statusFilter = intent.value) }
                publishExams()
            }

            is ExamsIntent.UpdateTitle -> editExamForm { it.copy(editorTitle = intent.value) }
            is ExamsIntent.UploadResource -> uploadResource(intent.resourceId)
        }
    }

    /**
     * Lo que hay que decir antes de archivar una pregunta.
     *
     * La segunda frase no es prudencia: es una limitación real del contrato. No
     * existe la consulta inversa pregunta → exámenes, así que el panel **no
     * puede** decir de cuántos exámenes va a desaparecer. Callarlo dejaría creer
     * que se está borrando de éste.
     */
    private val ARCHIVE_QUESTION_WARNING: String =
        "La pregunta se archiva en el banco: desaparece de TODOS los exámenes en los que esté, " +
            "no sólo de éste. No podemos decirte de cuántos: no existe una consulta que liste " +
            "los exámenes de una pregunta."

    /**
     * **La emisión SEMILLA de un repositorio no resuelve un estado.**
     *
     * `KtorCommunitiesRepositoryImpl` —y el de especialidades— siembran su
     * `MutableStateFlow` con `emptyList()`, así que lo primero que llega por el
     * flujo es una lista vacía que **no significa «no hay ninguna»**: significa
     * «todavía no se ha preguntado». Publicarla como `Success(emptyList())` tenía
     * una consecuencia concreta y grave: el estado nunca pasaba por `Loading`, y
     * desde el arranque un examen con comunidad real se etiquetaba «Nacional» con
     * el desplegable pintado como si tal cosa. No hacía falta que el backend
     * estuviera caído; bastaba con abrir la pantalla rápido.
     *
     * La regla, por tanto:
     *
     *  - Lista no vacía → `Success`. Es un dato de verdad, venga de donde venga.
     *  - Lista vacía con el estado todavía en `Loading` → **no se toca**. Es la
     *    semilla, y no puede decidir nada.
     *  - Lista vacía cuando ya había datos → `Empty` de verdad: alguien los borró.
     *
     * `Empty` como resultado de una carga con éxito y `Error` como resultado de un
     * fallo los sigue decidiendo el `refresh*UseCase`, que es el único que sabe si
     * ha hablado con el servidor.
     */
    private fun <T> seeded(values: List<T>, current: UiAsync<List<T>>): UiAsync<List<T>> = when {
        values.isNotEmpty() -> UiAsync.Success(values)
        current is UiAsync.Loading || current is UiAsync.Idle -> current
        else -> UiAsync.Empty
    }

    /**
     * Una lista que viene de hablar con el servidor: vacía es `Empty`, no `Loading`.
     *
     * Es la otra mitad de [seeded]: el vacío que SÍ se ha confirmado. Sin esto, el
     * guard de semilla dejaría un catálogo legítimamente vacío en `Loading` para
     * siempre. Desde K0-5 es la única puerta: el refresco de especialidades que el
     * `init` lanzaba aparte (con su propio `resolveEmpty`) duplicaba la petición.
     */
    private fun <T> loadedCatalog(values: List<T>): UiAsync<List<T>> =
        if (values.isEmpty()) UiAsync.Empty else UiAsync.Success(values)

    private fun requestDelete(target: PendingDeleteTarget, message: String) {
        updateState { it.copy(pendingDeleteTarget = target, pendingDeleteMessage = message) }
    }

    /**
     * Un cambio en el formulario del examen.
     *
     * Aplica el cambio y retira el resultado del último guardado
     * ([withoutSaveResult]): tanto «Cambios guardados.» como el error de un
     * guardado rechazado hablan de lo que había en el editor AL guardar, y tras
     * una edición ya no es verdad.
     */
    private fun editExamForm(change: (ExamsState) -> ExamsState) {
        updateState { change(it).withoutSaveResult() }
    }

    /**
     * El estado sin el resultado del último guardado, que una edición deja
     * obsoleto.
     *
     *  - La confirmación (`Success`). La prioridad de la barra la tapaba mientras
     *    el editor estuviera sucio, pero deshacer a mano la edición lo dejaba
     *    limpio y «Cambios guardados.» volvía a salir sobre algo que nadie había
     *    guardado.
     *  - El `Error` del guardado del examen ([lastExamSaveError]). Describía un
     *    intento sobre una versión del formulario que ya no existe: con el código
     *    ya corregido, la barra seguía diciendo «Ya hay otro examen con el
     *    código X».
     *
     * El `Error` de OTRA acción —quitar o mover una pregunta, archivar— se queda:
     * editar el formulario no lo resuelve. Y nada se retira sin una edición: el
     * error de un guardado sigue a la vista hasta que alguien toca el formulario.
     */
    private fun ExamsState.withoutSaveResult(): ExamsState = when {
        actionState is UiAsync.Success -> copy(actionState = UiAsync.Idle)
        actionState === lastExamSaveError -> copy(actionState = UiAsync.Idle)
        else -> this
    }

    /**
     * Un `Error` de guardar el examen, anotado en [lastExamSaveError] para que la
     * siguiente edición lo retire. Se puede crear dentro de un reductor: si
     * `update` lo repitiera, la última instancia anotada es la que queda publicada.
     */
    private fun examSaveError(error: AppError): UiAsync.Error =
        UiAsync.Error(error).also { lastExamSaveError = it }

    /**
     * Abrir otro examen —o empezar uno nuevo— sin tirar lo que se estaba
     * escribiendo.
     *
     * `SelectExam` y `CreateNew` sobrescribían los catorce campos del editor sin
     * mirar si había cambios: media hora de trabajo desaparecía al pinchar la
     * tarjeta de al lado, sin diálogo y sin forma de volver. Este camino reutiliza
     * el mecanismo de confirmación que la pantalla ya tenía montado
     * (`pendingDeleteTarget` + `ConfirmDialog`) en vez de añadir un segundo.
     *
     * **Si el editor está limpio no hay diálogo.** Confirmar algo que no tiene
     * consecuencias es la forma más rápida de enseñar que confirmar no significa
     * nada, y entonces tampoco protege cuando sí las tiene.
     */
    private suspend fun switchExam(targetId: String?) {
        val current = currentState()
        val open = allExams.firstOrNull { it.id == current.selectedExamId }
        val dirty = when {
            current.selectedExamId == null -> hasUnsavedNewExamDraft(current)
            else -> hasUnsavedExamChanges(current, open)
        }
        if (!dirty) {
            if (targetId == null) resetExamEditor() else selectExam(targetId)
            return
        }
        requestDelete(
            PendingDeleteTarget.SwitchExam(targetId),
            "Este examen tiene cambios sin guardar. Si abres otro, se pierden.",
        )
    }

    /**
     * Si un examen NUEVO tiene algo escrito que se perdería.
     *
     * No se puede usar `hasUnsavedExamChanges` porque ésa compara contra un examen
     * guardado y aquí no hay ninguno: se compara contra los valores con los que
     * `resetEditor` abre un examen nuevo, que salen de la misma fuente
     * ([NewExamDefaults]) para que los dos no puedan divergir.
     *
     * Todo campo cuenta, especialidad incluida, porque el ViewModel ya no rellena
     * ninguno por su cuenta en un borrador nuevo:
     *
     *  - La especialidad empieza vacía. Antes se preseleccionaba la primera del
     *    catálogo y por eso tenía que quedar fuera; ahora, si tiene valor, lo ha
     *    elegido alguien.
     *  - El tipo se compara contra el literal por defecto. La compensación que lo
     *    comparaba contra el tipo «ya ajustado por las capacidades» existía por la
     *    preselección —sus capacidades movían el tipo solo—; sin especialidad las
     *    capacidades están en `Idle`, y el ajuste automático sólo ocurre cuando el
     *    USUARIO cambia de especialidad, lo que ya es un cambio.
     */
    private fun hasUnsavedNewExamDraft(state: ExamsState): Boolean =
        state.editorTitle.isNotBlank() ||
            state.editorDescription.isNotBlank() ||
            state.editorCode.isNotBlank() ||
            state.editorSpecialtyId.isNotBlank() ||
            state.editorYear.isNotBlank() ||
            state.editorDurationMinutes.isNotBlank() ||
            state.editorCommunityId != null ||
            state.editorTypeExam != NewExamDefaults.TYPE_EXAM ||
            state.editorTypeAccess != NewExamDefaults.TYPE_ACCESS ||
            state.editorPriceTickets != NewExamDefaults.PRICE_TICKETS ||
            state.editorIsOfficial != NewExamDefaults.IS_OFFICIAL

    private suspend fun confirmPendingDelete() {
        val target = currentState().pendingDeleteTarget ?: return
        updateState { it.copy(pendingDeleteTarget = null, pendingDeleteMessage = "") }
        when (target) {
            PendingDeleteTarget.ArchiveExam -> archiveSelectedExam()
            PendingDeleteTarget.DeleteExamForever -> deleteSelectedExam()
            PendingDeleteTarget.Rafaga -> deleteSelectedRafaga()
            PendingDeleteTarget.Question -> deleteSelectedQuestion()
            PendingDeleteTarget.RafagaQuestion -> deleteSelectedRafagaQuestion()
            PendingDeleteTarget.Specialty -> deleteSelectedSpecialty()
            is PendingDeleteTarget.Resource -> deleteResource(target.resourceId)
            is PendingDeleteTarget.UnlinkQuestion -> unlinkQuestion(target.questionId)
            is PendingDeleteTarget.ArchiveQuestion -> archiveQuestion(target.questionId)
            // No borra nada: descarta el borrador del editor y abre lo que se
            // pidio. Comparte mecanismo con los demas porque comparte forma
            // —una accion con consecuencia irreversible que se confirma— y no
            // porque sea un borrado.
            is PendingDeleteTarget.SwitchExam ->
                if (target.examId == null) resetExamEditor() else selectExam(target.examId)
        }
    }

    /**
     * Recarga los catálogos de la pantalla sin tocar el examen abierto.
     *
     * Es también el «Reintentar» de los catálogos caídos, y por eso el estado de
     * especialidades y comunidades sale de la RESPUESTA, no del `Flow`: un
     * `StateFlow` no vuelve a emitir una lista igual a la que ya tenía, así que
     * tras un fallo una recarga correcta con los mismos datos dejaba el catálogo
     * en `Error` para siempre. Las comunidades, además, no se recargaban aquí: si
     * fallaban al arrancar, no había forma de reintentarlo sin reiniciar.
     *
     * Las comunidades siguen siendo tolerantes a fallo: `comunidad_autonoma` es
     * una tabla reciente que puede no existir en un entorno, y su error apaga
     * sólo el selector de CCAA, no el formulario.
     */
    private suspend fun refreshAll() {
        // Los exámenes van PRIMERO (K0-5): el repositorio cuenta los exámenes de
        // cada especialidad con el catálogo que ya tiene en caché, y si las
        // especialidades se pedían antes lo descargaba él por su cuenta — un
        // segundo `GET /examenes` completo en cada apertura (EX-13).
        when (val exams = refreshAllExamsUseCase()) {
            is AppResult.Failure -> updateState { it.copy(exams = UiAsync.Error(exams.error)) }
            is AppResult.Success -> publishExams()
        }
        when (val specialties = refreshSpecialtiesUseCase()) {
            is AppResult.Failure -> updateState { it.copy(specialties = UiAsync.Error(specialties.error)) }
            is AppResult.Success -> updateState { it.copy(specialties = loadedCatalog(specialties.value)) }
        }
        when (val communities = refreshCommunitiesUseCase()) {
            is AppResult.Failure -> updateState { it.copy(communities = UiAsync.Error(communities.error)) }
            is AppResult.Success -> updateState { it.copy(communities = loadedCatalog(communities.value)) }
        }
        // Sin `refreshRafagasUseCase()`: las ráfagas son filas del mismo
        // `GET /examenes` que acaba de refrescar `refreshAllExamsUseCase`, y el
        // repositorio ya rellena con él la lista que observa este ViewModel.
        // Pedirlas otra vez era otro catálogo completo por apertura (K0-5).
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
        publishExams()
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

    /**
     * Publica la lista de exámenes ya filtrada por lo que el usuario ha pedido.
     *
     * Filtra por búsqueda y por estado —los dos controles que existen en
     * pantalla— y por nada más. **Ya no filtra por `selectedContentSpecialtyId`**,
     * que es la especialidad seleccionada en el EDITOR de especialidades y
     * arranca valiendo la primera de la lista: mientras estuvo aquí, el catálogo
     * de escritorio mostraba en silencio los exámenes de una sola especialidad sin
     * que ningún control lo dijera, y el filtro visible de Especialidad se
     * aplicaba encima de ese recorte invisible.
     *
     * El camino móvil no cambia: `MobileContentScreen` vuelve a aplicar ese mismo
     * filtro por su cuenta sobre la lista publicada, que es donde sí corresponde
     * —allí la especialidad elegida es el contexto de toda la pantalla—.
     */
    private fun publishExams() {
        val query = currentState().searchQuery.trim().lowercase()
        val statusFilter = currentState().statusFilter.trim().lowercase()
        val filtered = allExams.filter { exam ->
            (query.isBlank() ||
                exam.title.lowercase().contains(query) ||
                exam.specialtyName.lowercase().contains(query)) &&
                (statusFilter.isBlank() || statusFilter.equals("all", ignoreCase = true) || exam.status.name.lowercase() == statusFilter.trim().lowercase())
        }
        updateState {
            it.copy(
                exams = if (filtered.isEmpty()) UiAsync.Empty else UiAsync.Success(filtered),
                // La lista sin filtrar viaja SIEMPRE junto a la filtrada. Es lo que
                // permite que el examen abierto conserve su identidad cuando la
                // búsqueda no lo incluye — ver `ExamsState.catalogExams`.
                catalogExams = allExams,
            )
        }
    }

    /**
     * Cambia la especialidad del editor y recarga sus capacidades.
     *
     * Es el único punto de entrada para tocar `editorSpecialtyId`: si se hiciera
     * con un `updateState` suelto (como antes), el selector de Tipo de examen
     * seguiría mostrando los modos de la especialidad anterior hasta la próxima
     * acción del usuario — permitiendo, por ejemplo, guardar un examen Inmersivo
     * en una especialidad que sólo tiene Ráfaga habilitada.
     *
     * [adjustTypeToCapabilities] decide si el TIPO se mueve para encajar en las
     * capacidades nuevas, y es `true` sólo cuando el usuario cambia a OTRA
     * especialidad: ahí el tipo elegido puede dejar de estar permitido y se cae
     * al primero que lo esté. Al abrir un examen, al arrancar o al reintentar las
     * capacidades de la misma especialidad, NO: el tipo es un dato guardado, y
     * moverlo en silencio disfrazaba un `razonado`, un `inmersivo` o un tipo
     * desconocido de «Ráfaga», dejaba el editor sucio sin que nadie hubiera tocado
     * nada y, al guardar, cambiaba el tipo del examen. Si el tipo guardado no está
     * permitido, se ve —la comprobación de tipo lo bloquea—, no se corrige a
     * escondidas.
     */
    private suspend fun selectEditorSpecialty(specialtyId: String) {
        updateState { it.copy(editorSpecialtyId = specialtyId, specialtyCapabilities = UiAsync.Loading) }
        if (specialtyId.isBlank()) {
            updateState { it.copy(specialtyCapabilities = UiAsync.Idle) }
            return
        }
        when (val result = getSpecialtyCapabilitiesUseCase(specialtyId)) {
            is AppResult.Failure -> updateState { it.copy(specialtyCapabilities = UiAsync.Error(result.error)) }
            // El tipo NO se toca. Ni al abrir, ni al reintentar, ni cuando el
            // usuario cambia de especialidad: cambiarlo por él es decidir en su
            // nombre y dejarle un examen distinto del que creía tener. Si la
            // especialidad nueva no admite el tipo elegido, la comprobación lo
            // bloquea y lo dice; cambiarlo es decisión suya.
            is AppResult.Success -> updateState { it.copy(specialtyCapabilities = UiAsync.Success(result.value)) }
        }
    }

    /**
     * Abre [id] en el editor, re-sembrando todos sus campos desde lo guardado.
     *
     * [keepSaveResult] conserva `actionState` en vez de devolverlo a `Idle`. Sólo
     * lo pide `saveExam`, que reabre el examen justo después de ponerlo en
     * `Success`: sin esto la confirmación «Cambios guardados.» se borraba antes de
     * pintarse. Abrir un examen por cualquier otro camino —catálogo, «Descartar»—
     * sí empieza sin resultado anterior.
     */
    private suspend fun selectExam(id: String, keepSaveResult: Boolean = false) {
        val exam = allExams.firstOrNull { it.id == id } ?: return
        updateState {
            it.copy(
                selectedExamId = exam.id,
                selectedRafagaId = null,
                editorMode = ExamEditorMode.Solucionario,
                editorTitle = exam.title,
                editorDescription = exam.description,
                editorCode = exam.code,
                editorPriceTickets = exam.priceTickets.toString(),
                editorCommunityId = exam.communityId,
                editorTypeExam = exam.typeExam,
                editorStatus = exam.status.name.lowercase(),
                editorPublishFrom = exam.publishFrom?.toString().orEmpty(),
                editorTypeAccess = exam.typeAccess,
                editorYear = exam.year?.toString().orEmpty(),
                editorIsOfficial = exam.isOfficial,
                editorDurationMinutes = exam.durationMinutes?.toString().orEmpty(),
                selectedQuestionId = null,
                selectedRafagaQuestionId = null,
                editorQuestionId = null,
                editorQuestionTitle = "",
                editorQuestionStatement = "",
                editorOptions = emptyList(),
                editorResources = emptyList(),
                resourceUploadStates = emptyMap(),
                resourceAudit = UiAsync.Idle,
                // Las preguntas del examen ANTERIOR no pueden seguir en pantalla
                // mientras se piden las capacidades de éste: la cabecera, la
                // insignia de la pestaña y la tarjeta del catálogo pintaban su
                // recuento —o un «0» falso si se venía de un examen nuevo— como si
                // fuera de éste.
                questions = UiAsync.Loading,
                contentPendingMigrationMessage = "",
                actionState = if (keepSaveResult) it.actionState else UiAsync.Idle,
                // El resultado de publicar o despublicar es de UN examen. Sin
                // esto se quedaba en `Success`/`Error` para siempre y la pestaña
                // Publicación del siguiente examen abierto decía «Examen
                // publicado» —o el error— de otro.
                lifecycleActionState = UiAsync.Idle,
                // Y lo mismo con el aviso del borrado, por el mismo motivo: «no
                // se puede borrar: los usuarios ya lo han usado» es de UN examen,
                // y colgado del siguiente sería una acusación falsa.
                examDeleteState = UiAsync.Idle,
            )
        }
        selectEditorSpecialty(exam.specialtyId)
        // Las preguntas se cargan para CUALQUIER examen, no sólo para los de
        // ráfaga. El endpoint real es `/examenes/{id}/preguntas` y no mira el
        // tipo: la puerta anterior —«Contenido premium/inmersivo pendiente de
        // migrar»— era una limitación del cliente que ya no existía en el
        // servidor, y dejaba las pestañas de Preguntas y Recursos de un examen
        // razonado o inmersivo enseñando un aviso de migración en lugar de sus
        // preguntas reales. Si el servidor falla, se ve el fallo (`UiAsync.Error`)
        // en vez de un mensaje que decide por él.
        loadQuestions(exam.id)
    }

    private suspend fun selectRafaga(id: String) {
        val rafaga = allRafagas.firstOrNull { it.id == id } ?: return
        updateState {
            it.copy(
                editorMode = ExamEditorMode.Rafaga,
                selectedExamId = null,
                selectedRafagaId = rafaga.id,
                editorTitle = rafaga.title,
                editorDescription = "",
                editorPriceTickets = rafaga.priceTickets.toString(),
                editorSpecialtyId = rafaga.specialtyId,
                editorStatus = rafaga.status.name.lowercase(),
                editorPublishFrom = rafaga.publishFrom?.toString().orEmpty(),
                editorTypeAccess = if (rafaga.priceTickets > 0) "premium" else "gratuito",
                editorYear = "",
                editorIsOfficial = false,
                editorDurationMinutes = "",
                questions = UiAsync.Empty,
                resourceAudit = UiAsync.Idle,
                selectedQuestionId = null,
                selectedRafagaQuestionId = null,
                editorQuestionId = null,
                editorQuestionTitle = "",
                editorQuestionStatement = "",
                editorOptions = emptyList(),
                editorResources = emptyList(),
                uploadState = UiAsync.Idle,
                resourceUploadStates = emptyMap(),
                contentPendingMigrationMessage = "",
                actionState = UiAsync.Idle,
            )
        }
        loadRafagaQuestions(rafaga.id)
    }

    private suspend fun loadQuestions(examId: String) {
        updateState { it.copy(questions = UiAsync.Loading, contentPendingMigrationMessage = "") }
        when (val result = getExamQuestionsUseCase(examId)) {
            is AppResult.Failure -> updateState { it.copy(questions = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                currentQuestions = result.value.sortedBy { it.sortOrder }
                updateState {
                    it.copy(
                        questions = if (currentQuestions.isEmpty()) UiAsync.Empty else UiAsync.Success(currentQuestions),
                        // Recuento de sesión: la cifra provisional la próxima vez
                        // que este examen esté cargando. Una lista vacía también
                        // cuenta (0); un fallo no llega aquí y no lo toca.
                        countedQuestions = it.countedQuestions + (examId to currentQuestions.size),
                    )
                }
                syncCatalogQuestionCount(examId, currentQuestions.size)
                // La auditoría de recursos sale de las preguntas que se acaban de
                // cargar (K0-5). Antes se pedía `GET /examenes/{id}/preguntas` OTRA
                // vez sólo para contar sus recursos: dos descargas de la misma
                // lista por cada examen abierto (EX-13). El «Actualizar» del
                // camino móvil (`RefreshResourceAudit`) sigue preguntando al servidor.
                updateState { it.copy(resourceAudit = UiAsync.Success(currentQuestions.toAudit())) }
            }
        }
    }

    /**
     * Alinea el `total_preguntas` de la fila del catálogo con las preguntas que
     * se acaban de contar.
     *
     * Sin esto, quitar una pregunta de un examen de 40 dejaba «39 preguntas
     * vinculadas» en la cabecera y «40» en la tarjeta del catálogo hasta que
     * alguien pulsara Actualizar: dos números para el mismo dato, en la misma
     * pantalla, y ninguno señalado como el bueno. Y no era un caso raro —
     * desvincular y archivar recargan las preguntas y nunca tocaban la lista de
     * exámenes, así que divergían **siempre** que se usaba cualquiera de las dos.
     *
     * Es el mismo remiendo local que ya hace `applyLifecycleResult` al publicar:
     * se corrige la fila en memoria y se vuelve a publicar la lista, sin esperar
     * a que el `Flow` del repositorio dé la vuelta.
     */
    private fun syncCatalogQuestionCount(examId: String, count: Int) {
        if (allExams.none { it.id == examId && it.questionCount != count }) return
        allExams = allExams.map { if (it.id == examId) it.copy(questionCount = count) else it }
        publishExams()
    }

    /**
     * Mete en el catálogo en memoria un examen que acaba de devolver el servidor,
     * sin esperar a que el `Flow` del repositorio dé la vuelta.
     *
     * Entra TAL CUAL, recuento incluido: desde M1 `total_preguntas` lo mantiene la
     * base al vincular, quitar o archivar preguntas, así que la cifra servida es un
     * dato (K2-2) y no hay que protegerla con la de memoria. Un examen que todavía
     * no estaba (recién creado) entra el primero, como lo ordena el servidor.
     */
    private fun mergeIntoCatalog(saved: Exam) {
        allExams = if (allExams.any { it.id == saved.id }) {
            allExams.map { if (it.id == saved.id) saved else it }
        } else {
            listOf(saved) + allExams
        }
        publishExams()
    }

    /**
     * «Reintentar» de Preguntas y Recursos: vuelve a pedir las preguntas del
     * examen abierto y NADA MÁS.
     *
     * Antes era `SelectExam`, que re-siembra los catorce campos del editor desde
     * el examen guardado: reintentar una carga fallida en otra pestaña tiraba sin
     * preguntar lo que hubiera escrito en Información básica.
     */
    private suspend fun retryLoadQuestions() {
        val examId = currentState().selectedExamId ?: return
        loadQuestions(examId)
    }

    private suspend fun loadRafagaQuestions(rafagaId: String) {
        updateState { it.copy(rafagaQuestions = UiAsync.Loading) }
        when (val result = getRafagaQuestionsUseCase(rafagaId)) {
            is AppResult.Failure -> updateState { it.copy(rafagaQuestions = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                currentRafagaQuestions = result.value.sortedBy { it.sortOrder }
                updateState {
                    it.copy(
                        rafagaQuestions = if (currentRafagaQuestions.isEmpty()) UiAsync.Empty else UiAsync.Success(currentRafagaQuestions),
                    )
                }
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

    private suspend fun resetExamEditor() = resetEditor(ExamEditorMode.Solucionario)

    private suspend fun resetRafagaEditor() = resetEditor(ExamEditorMode.Rafaga)

    private suspend fun resetEditor(mode: ExamEditorMode) {
        if (mode == ExamEditorMode.Solucionario) currentQuestions = emptyList()
        else currentRafagaQuestions = emptyList()
        updateState {
            it.copy(
                editorMode = mode,
                selectedExamId = null,
                selectedRafagaId = null,
                editorTitle = "",
                editorDescription = "",
                editorCode = "",
                editorPriceTickets = NewExamDefaults.PRICE_TICKETS,
                editorCommunityId = null,
                editorTypeExam = NewExamDefaults.TYPE_EXAM,
                editorStatus = "draft",
                editorPublishFrom = "",
                editorTypeAccess = NewExamDefaults.TYPE_ACCESS,
                editorYear = "",
                editorIsOfficial = NewExamDefaults.IS_OFFICIAL,
                editorDurationMinutes = "",
                questions = UiAsync.Empty,
                rafagaQuestions = UiAsync.Empty,
                resourceAudit = UiAsync.Idle,
                selectedQuestionId = null,
                selectedRafagaQuestionId = null,
                editorQuestionId = null,
                editorQuestionTitle = "",
                editorQuestionStatement = "",
                editorOptions = emptyList(),
                editorResources = emptyList(),
                uploadState = UiAsync.Idle,
                resourceUploadStates = emptyMap(),
                importState = UiAsync.Idle,
                importSummary = "",
                contentPendingMigrationMessage = "",
                actionState = UiAsync.Idle,
                // Un examen nuevo no hereda el resultado de publicar el anterior,
                // ni el aviso de un borrado que se rechazó sobre otro examen.
                lifecycleActionState = UiAsync.Idle,
                examDeleteState = UiAsync.Idle,
                // Sin especialidad y sin capacidades: la elige quien crea el
                // examen. La primera del catálogo no es una elección de nadie.
                editorSpecialtyId = "",
                specialtyCapabilities = UiAsync.Idle,
            )
        }
    }

    private suspend fun saveExam() {
        val now = Clock.System.now()
        // El examen en edición: el mismo registro que la pantalla resuelve con
        // `selectedExam()` (`catalogExams` es esta misma lista, ya publicada), y
        // `null` en un borrador nuevo.
        val existing = allExams.firstOrNull { it.id == currentState().selectedExamId }
        // Un código que ya usa otro examen no se envía (P1-3). La barra ya
        // deshabilita el botón, pero el ViewModel no puede fiarse de eso: el
        // servidor lo rechazaba con un 500 genérico. Misma regla y mismo mensaje
        // que el campo y la comprobación «Código»: sin espacios ni mayúsculas,
        // contra el catálogo sin filtrar —archivados incluidos— y sin contar el
        // propio examen.
        val codeConflict = examCodeConflict(currentState(), existing)
        if (codeConflict != null) {
            return updateState {
                it.copy(
                    actionState = examSaveError(
                        AppError.Validation(
                            message = examCodeConflictMessage(codeConflict),
                            // El mismo código que devolvería el servidor con el
                            // 23505 de `codigo_examen`: así este rechazo local
                            // señala el campo y abre su pestaña exactamente igual
                            // que el remoto, sin una segunda regla que mantener.
                            code = EXAM_ERROR_CODE_DUPLICATE,
                        ),
                    ),
                )
            }
        }
        val specialtyId = currentState().editorSpecialtyId
        val specialty = allSpecialties.firstOrNull { it.id == specialtyId }
            ?: return updateState {
                val message = when {
                    // Con el catálogo caído, «elige una especialidad» mandaría a
                    // hacer algo imposible: el selector no se puede desplegar.
                    it.specialties is UiAsync.Error ->
                        "No se ha podido cargar el catálogo de especialidades. Reintenta antes de guardar."
                    specialtyId.isBlank() -> "Selecciona una especialidad."
                    else -> "Selecciona una especialidad válida."
                }
                it.copy(actionState = examSaveError(AppError.Validation(message)))
            }
        val priceTickets = currentState().editorPriceTickets.toIntOrNull()
            ?: return updateState {
                it.copy(actionState = examSaveError(AppError.Validation("El precio en tickets debe ser un número válido.")))
            }
        if (priceTickets < 0) {
            return updateState {
                it.copy(actionState = examSaveError(AppError.Validation("El precio en tickets no puede ser negativo.")))
            }
        }
        val year = currentState().editorYear.takeIf { it.isNotBlank() }?.toIntOrNull()
            ?: currentState().editorYear.takeIf { it.isNotBlank() }?.let {
                return updateState {
                    it.copy(actionState = examSaveError(AppError.Validation("El año debe ser numérico.")))
                }
            }
        val durationMinutes = currentState().editorDurationMinutes.takeIf { it.isNotBlank() }?.toIntOrNull()
            ?: currentState().editorDurationMinutes.takeIf { it.isNotBlank() }?.let {
                return updateState {
                    it.copy(actionState = examSaveError(AppError.Validation("La duración debe ser numérica.")))
                }
            }
        // «0» pasa el filtro de entrada —sólo deja dígitos— pero no es una
        // duración que se pueda cumplir (P2-7). Vacía es «sin duración».
        if (durationMinutes != null && durationMinutes <= 0) {
            return updateState {
                it.copy(actionState = examSaveError(AppError.Validation("La duración debe ser mayor que 0 o quedar vacía.")))
            }
        }
        val publishFrom = currentState().editorPublishFrom.takeIf { it.isNotBlank() }?.let {
            runCatching { kotlinx.datetime.Instant.parse(it) }.getOrNull()
                ?: return updateState {
                    it.copy(actionState = examSaveError(AppError.Validation("publicar_desde debe tener formato ISO, por ejemplo 2026-04-15T09:00:00Z.")))
                }
        }
        val typeExam = ExamType.fromExternal(currentState().editorTypeExam)?.wireValue
            ?: return updateState {
                it.copy(actionState = examSaveError(AppError.Validation("El tipo de examen no es válido.")))
            }
        // Antes, si specialtyCapabilities no era Success (fallo de red, o
        // todavía Loading), el `capabilities != null &&` de abajo se saltaba
        // por completo la comprobación y dejaba guardar cualquier tipo sin
        // verificar — justo el hueco de seguridad que no queremos: no se
        // guarda un tipo cuya validez no podemos comprobar.
        when (val capabilitiesState = currentState().specialtyCapabilities) {
            is UiAsync.Success -> if (!capabilitiesState.value.allows(typeExam)) {
                return updateState {
                    it.copy(actionState = examSaveError(AppError.Validation("Esta especialidad no permite el tipo de examen elegido.")))
                }
            }
            else -> return updateState {
                it.copy(
                    actionState = examSaveError(
                        AppError.Validation("No se pudieron comprobar los modos permitidos por la especialidad. Reintenta antes de guardar."),
                    ),
                )
            }
        }
        updateState { it.copy(actionState = UiAsync.Loading) }
        val status = currentState().editorStatus.toExamStatus()
        val exam = Exam(
            id = existing?.id.orEmpty(),
            // El código se preserva si el editor lo deja en blanco: antes se
            // enviaba "" a la primera edición y borraba el código real que el
            // servidor había autogenerado al crear el examen.
            code = currentState().editorCode.trim().ifBlank { existing?.code.orEmpty() },
            specialtyId = specialty.id,
            specialtyName = specialty.name,
            communityId = currentState().editorCommunityId,
            title = currentState().editorTitle.trim(),
            description = currentState().editorDescription.trim(),
            typeExam = typeExam,
            typeAccess = currentState().editorTypeAccess.ifBlank { "gratuito" },
            priceTickets = priceTickets,
            status = status,
            publishFrom = when (status) {
                ExamStatus.PUBLISHED -> publishFrom ?: existing?.publishFrom ?: now
                else -> publishFrom ?: existing?.publishFrom
            },
            publishedAt = when (status) {
                ExamStatus.PUBLISHED -> existing?.publishedAt ?: now
                else -> existing?.publishedAt
            },
            sortOrder = existing?.sortOrder ?: allExams.size,
            questionCount = currentQuestions.size,
            durationMinutes = durationMinutes,
            year = year,
            isOfficial = currentState().editorIsOfficial,
            // La portada ya no se sube ni se elige: se deduce del tipo y de la comunidad
            // (`ExamCoverResolver`). Las columnas legacy de Storage se conservan tal cual.
            coverUrl = existing?.coverUrl,
            coverStoragePath = existing?.coverStoragePath,
            coverMimeType = existing?.coverMimeType,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            deletedAt = existing?.deletedAt,
        )
        when (val result = upsertExamUseCase(exam)) {
            is AppResult.Failure -> updateState { it.copy(actionState = examSaveError(result.error)) }
            is AppResult.Success -> {
                val saved = result.value
                analyticsTracker.trackAction("exam_save", mapOf("exam_id" to saved.id))
                // El examen guardado entra en el catálogo en memoria ANTES de
                // reabrirlo. `selectExam` lee de `allExams`, y el `Flow` del
                // repositorio que lo actualiza vive en otra corrutina —en
                // escritorio, en otro hilo: `Dispatchers.Default`—. Sin esto el
                // editor se re-sembraba con los valores de ANTES de guardar, la
                // barra anunciaba cambios pendientes recién guardados y un examen
                // recién creado ni siquiera se encontraba. Es el mismo remiendo que
                // ya hacía `applyLifecycleResult` al publicar.
                mergeIntoCatalog(saved)
                updateState { it.copy(selectedExamId = saved.id, actionState = UiAsync.Success(Unit)) }
                // Reabrir conserva el `Success`: es la confirmación que ve quien
                // guarda. La retira la siguiente edición (`editExamForm`).
                selectExam(saved.id, keepSaveResult = true)
            }
        }
    }

    /**
     * Publica el examen mediante el endpoint real (`POST .../publicar`), no
     * mediante el guardado genérico. El backend audita esta acción y fija
     * `publicado_en` con su propio reloj — no hay publicación programada: elegir
     * una fecha futura en el formulario no la retrasa.
     */
    private suspend fun publishExam() {
        val examId = currentState().selectedExamId
            ?: return updateState { it.copy(lifecycleActionState = UiAsync.Error(AppError.Validation("Guarda el examen antes de publicarlo."))) }
        updateState { it.copy(lifecycleActionState = UiAsync.Loading) }
        when (val result = publishExamUseCase(examId)) {
            is AppResult.Failure -> updateState { it.copy(lifecycleActionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("exam_publish", mapOf("exam_id" to examId))
                applyLifecycleResult(result.value)
                updateState { it.copy(lifecycleActionState = UiAsync.Success(Unit)) }
            }
        }
    }

    private suspend fun unpublishExam() {
        val examId = currentState().selectedExamId
            ?: return updateState { it.copy(lifecycleActionState = UiAsync.Error(AppError.Validation("Selecciona un examen para despublicarlo."))) }
        updateState { it.copy(lifecycleActionState = UiAsync.Loading) }
        when (val result = unpublishExamUseCase(examId)) {
            is AppResult.Failure -> updateState { it.copy(lifecycleActionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("exam_unpublish", mapOf("exam_id" to examId))
                applyLifecycleResult(result.value)
                updateState { it.copy(lifecycleActionState = UiAsync.Success(Unit)) }
            }
        }
    }

    /**
     * Refleja en memoria el examen que acaba de devolver publicar/despublicar,
     * sin esperar a que el `Flow` de `observeAllExamsUseCase` —que vive en su propia
     * corrutina— se propague.
     *
     * **No re-siembra el editor.** Antes llamaba a `selectExam`, que copia los
     * catorce campos del examen guardado sobre el editor: publicar con el título
     * cambiado y sin guardar tiraba el cambio en silencio. Publicar sólo cambia el
     * estado, así que sólo se toca el estado: la fila del catálogo y
     * `editorStatus`, que es lo que `saveExam` manda después —sin él, «Guardar
     * cambios» tras publicar despublicaba el examen—.
     *
     * **Tampoco vuelve a descargar el catálogo** (K0-5): el examen que devuelve el
     * servidor ya es la fila entera, y el repositorio la funde en su caché. El
     * `refreshAllExamsUseCase()` que había aquí era un `GET /examenes` completo
     * por cada publicación (EX-13).
     */
    private fun applyLifecycleResult(exam: Exam) {
        mergeIntoCatalog(exam)
        updateState { it.copy(editorStatus = exam.status.name.lowercase()) }
    }

    /**
     * **Borra el examen abierto.** Sólo llega aquí desde `ConfirmPendingDelete`
     * con [PendingDeleteTarget.DeleteExamForever]: no hay camino sin confirmar.
     *
     * El resultado va a `examDeleteState` y NO a `actionState`, que es el de la
     * barra de guardado: un rechazo del servidor —«los usuarios ya lo han
     * usado»— no es un guardado fallido, y puesto ahí se leía como si lo fuera.
     * Lo pinta `examDeletionNotice` junto al botón que lo provocó.
     *
     * El éxito RESETEA el editor: el examen ya no existe, así que dejarlo abierto
     * dejaría a la vista un formulario que guarda contra un id inexistente.
     */
    private suspend fun deleteSelectedExam() {
        val selectedExamId = currentState().selectedExamId
            ?: return updateState {
                it.copy(
                    examDeleteState = UiAsync.Error(AppError.Validation("Selecciona un examen para borrarlo.")),
                )
            }
        updateState { it.copy(examDeleteState = UiAsync.Loading) }
        when (val result = deleteExamUseCase(selectedExamId)) {
            is AppResult.Failure -> updateState { it.copy(examDeleteState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("exam_delete", mapOf("exam_id" to selectedExamId))
                resetExamEditor()
                updateState { it.copy(examDeleteState = UiAsync.Success(Unit)) }
            }
        }
    }

    /**
     * **Archiva el examen abierto**: reversible, y por su ruta
     * (`POST .../archivar`).
     *
     * Va por `lifecycleActionState` y usa [applyLifecycleResult] como publicar y
     * despublicar, porque es lo mismo que ellas: cambia la visibilidad y devuelve
     * el examen guardado. Antes archivar reseteaba el editor —cuando compartía
     * camino con «borrar»— y cerraba el examen que acababas de archivar sin
     * motivo: archivarlo no lo hace desaparecer.
     */
    private suspend fun archiveSelectedExam() {
        val selectedExamId = currentState().selectedExamId
            ?: return updateState {
                it.copy(actionState = UiAsync.Error(AppError.Validation("Selecciona un examen para archivarlo.")))
            }
        updateState { it.copy(lifecycleActionState = UiAsync.Loading) }
        when (val result = archiveExamUseCase(selectedExamId)) {
            is AppResult.Failure -> updateState { it.copy(lifecycleActionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("exam_archive", mapOf("exam_id" to selectedExamId))
                applyLifecycleResult(result.value)
                updateState { it.copy(lifecycleActionState = UiAsync.Success(Unit)) }
            }
        }
    }

    private suspend fun saveRafaga() {
        val now = Clock.System.now()
        val existing = allRafagas.firstOrNull { it.id == currentState().selectedRafagaId }
        val specialty = allSpecialties.firstOrNull { it.id == currentState().editorSpecialtyId }
            ?: return updateState {
                it.copy(actionState = UiAsync.Error(AppError.Validation("Selecciona una especialidad válida.")))
            }
        val priceTickets = currentState().editorPriceTickets.toIntOrNull()
            ?: return updateState {
                it.copy(actionState = UiAsync.Error(AppError.Validation("El precio en tickets debe ser un número válido.")))
            }
        val publishFrom = currentState().editorPublishFrom.takeIf { it.isNotBlank() }?.let {
            runCatching { kotlinx.datetime.Instant.parse(it) }.getOrNull()
                ?: return updateState {
                    it.copy(actionState = UiAsync.Error(AppError.Validation("publicar_desde debe tener formato ISO, por ejemplo 2026-04-15T09:00:00Z.")))
                }
        }
        updateState { it.copy(actionState = UiAsync.Loading) }
        val status = currentState().editorStatus.toExamStatus()
        val rafaga = Rafaga(
            id = existing?.id.orEmpty(),
            specialtyId = specialty.id,
            specialtyName = specialty.name,
            title = currentState().editorTitle.trim(),
            priceTickets = priceTickets,
            status = status,
            publishFrom = when (status) {
                ExamStatus.PUBLISHED -> existing?.publishFrom ?: publishFrom ?: now
                else -> publishFrom ?: existing?.publishFrom
            },
            publishedAt = when (status) {
                ExamStatus.PUBLISHED -> existing?.publishedAt ?: now
                else -> existing?.publishedAt
            },
            questionCount = currentRafagaQuestions.size,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            deletedAt = existing?.deletedAt,
        )
        when (val result = upsertRafagaUseCase(rafaga)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("rafaga_save", mapOf("rafaga_id" to result.value.id))
                updateState { it.copy(selectedRafagaId = result.value.id, actionState = UiAsync.Success(Unit)) }
                selectRafaga(result.value.id)
            }
        }
    }

    private suspend fun deleteSelectedRafaga() {
        val selectedRafagaId = currentState().selectedRafagaId
            ?: return updateState {
                it.copy(actionState = UiAsync.Error(AppError.Validation("Selecciona una rafaga para borrarla.")))
            }
        updateState { it.copy(actionState = UiAsync.Loading) }
        when (val result = deleteRafagaUseCase(selectedRafagaId)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("rafaga_delete", mapOf("rafaga_id" to selectedRafagaId))
                resetRafagaEditor()
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
                editorResources = emptyList(),
                uploadState = UiAsync.Idle,
                resourceUploadStates = emptyMap(),
                importState = UiAsync.Idle,
                actionState = UiAsync.Idle,
            )
        }
    }

    private fun createRafagaQuestion() {
        val rafagaId = currentState().selectedRafagaId
            ?: return updateState {
                it.copy(actionState = UiAsync.Error(AppError.Validation("Primero guarda o selecciona una rafaga.")))
            }
        updateState {
            it.copy(
                selectedRafagaQuestionId = null,
                editorQuestionId = blankQuestionId(),
                editorQuestionTitle = "",
                editorQuestionStatement = "",
                editorOptions = emptyList(),
                editorResources = emptyList(),
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

    private fun selectRafagaQuestion(id: String) {
        val question = currentRafagaQuestions.firstOrNull { it.id == id } ?: return
        updateState {
            it.copy(
                selectedRafagaQuestionId = question.id,
                editorQuestionId = question.id,
                editorQuestionTitle = "",
                editorQuestionStatement = question.statement,
                editorOptions = emptyList(),
                editorResources = emptyList(),
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
        if (currentState().editorOptions.none { it.isCorrect }) {
            return updateState { it.copy(actionState = UiAsync.Error(AppError.Validation("Marca una opción como correcta antes de guardar."))) }
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

    /**
     * Archiva la pregunta seleccionada en el editor.
     *
     * **El comportamiento no cambia y el nombre del caso de uso ahora lo dice.**
     * Antes esto llamaba a `deleteQuestionUseCase(examId, questionId)`, que
     * recibía el examen y lo IGNORABA: la pregunta se archivaba en todos los
     * exámenes en los que estuviera. No era un fallo de este método sino de la
     * firma, y por eso la corrección es cambiar de operación, no envolverla.
     *
     * Se conserva archivar —y no se convierte en desvincular— porque es lo que
     * hace hoy el botón del camino móvil que llega hasta aquí, y cambiarle el
     * efecto por debajo sería una regresión silenciosa en una pantalla que este
     * encargo no toca. Quien quiera quitar la pregunta de un solo examen tiene
     * [unlinkQuestion], que es lo que ofrece el workspace de escritorio.
     */
    private suspend fun deleteSelectedQuestion() {
        val examId = currentState().selectedExamId
            ?: return updateState {
                it.copy(actionState = UiAsync.Error(AppError.Validation("Selecciona un examen.")))
            }
        val questionId = currentState().selectedQuestionId
            ?: return updateState {
                it.copy(actionState = UiAsync.Error(AppError.Validation("Selecciona una pregunta guardada para archivarla.")))
            }
        archiveQuestion(questionId, reloadExamId = examId, resetEditor = true)
    }

    /**
     * Quita la pregunta de ESTE examen. Sigue en el banco y en los demás exámenes.
     *
     * El backend recompacta `posicion` al borrar la fila, así que se recargan las
     * preguntas en vez de quitar una de la lista en memoria: las posiciones que
     * se ven después de esto son las que el servidor acaba de escribir, no una
     * renumeración adivinada aquí.
     */
    private suspend fun unlinkQuestion(questionId: String) {
        val examId = currentState().selectedExamId
            ?: return updateState {
                it.copy(actionState = UiAsync.Error(AppError.Validation("Selecciona un examen.")))
            }
        updateState { it.copy(actionState = UiAsync.Loading) }
        when (val result = unlinkQuestionFromExamUseCase(examId, questionId)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction(
                    "question_unlink",
                    mapOf("exam_id" to examId, "question_id" to questionId),
                )
                loadQuestions(examId)
                updateState { it.copy(actionState = UiAsync.Success(Unit)) }
            }
        }
    }

    private suspend fun archiveQuestion(
        questionId: String,
        reloadExamId: String? = currentState().selectedExamId,
        resetEditor: Boolean = false,
    ) {
        updateState { it.copy(actionState = UiAsync.Loading) }
        when (val result = archiveQuestionUseCase(questionId)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("question_archive", mapOf("question_id" to questionId))
                reloadExamId?.let { loadQuestions(it) }
                if (resetEditor) createQuestion()
                updateState { it.copy(actionState = UiAsync.Success(Unit)) }
            }
        }
    }

    private suspend fun saveRafagaQuestion() {
        val rafagaId = currentState().selectedRafagaId
            ?: return updateState {
                it.copy(actionState = UiAsync.Error(AppError.Validation("Selecciona una rafaga antes de guardar preguntas.")))
            }
        val questionId = currentState().editorQuestionId
            ?: return updateState {
                it.copy(actionState = UiAsync.Error(AppError.Validation("Crea o selecciona una pregunta de rafaga.")))
            }
        val existing = currentRafagaQuestions.firstOrNull { it.id == questionId }
        val now = Clock.System.now()
        updateState { it.copy(actionState = UiAsync.Loading) }
        val question = RafagaQuestion(
            id = questionId,
            rafagaId = rafagaId,
            statement = currentState().editorQuestionStatement.trim(),
            sortOrder = existing?.sortOrder ?: currentRafagaQuestions.size,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            deletedAt = existing?.deletedAt,
        )
        when (val result = upsertRafagaQuestionUseCase(question)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("rafaga_question_save", mapOf("rafaga_id" to rafagaId, "question_id" to result.value.id))
                loadRafagaQuestions(rafagaId)
                updateState { it.copy(selectedRafagaQuestionId = result.value.id, editorQuestionId = result.value.id, actionState = UiAsync.Success(Unit)) }
                selectRafagaQuestion(result.value.id)
            }
        }
    }

    private suspend fun deleteSelectedRafagaQuestion() {
        val rafagaId = currentState().selectedRafagaId
            ?: return updateState {
                it.copy(actionState = UiAsync.Error(AppError.Validation("Selecciona una rafaga.")))
            }
        val questionId = currentState().selectedRafagaQuestionId
            ?: return updateState {
                it.copy(actionState = UiAsync.Error(AppError.Validation("Selecciona una pregunta guardada para borrarla.")))
            }
        updateState { it.copy(actionState = UiAsync.Loading) }
        when (val result = deleteRafagaQuestionUseCase(rafagaId, questionId)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("rafaga_question_delete", mapOf("question_id" to questionId))
                loadRafagaQuestions(rafagaId)
                createRafagaQuestion()
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

    private suspend fun moveRafagaQuestion(questionId: String, direction: QuestionMoveDirection) {
        val rafagaId = currentState().selectedRafagaId ?: return
        when (val result = moveRafagaQuestionUseCase(rafagaId, questionId, direction)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                currentRafagaQuestions = result.value
                updateState {
                    it.copy(
                        rafagaQuestions = if (result.value.isEmpty()) UiAsync.Empty else UiAsync.Success(result.value),
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

    private suspend fun deleteResource(resourceId: String) {
        val questionId = currentState().editorQuestionId
        val resource = currentState().editorResources.firstOrNull { it.id == resourceId }
        if (questionId.isNullOrBlank() || resource == null || currentQuestions.none { it.id == questionId }) {
            removeResource(resourceId)
            return
        }
        updateState {
            it.copy(
                uploadState = UiAsync.Loading,
                resourceUploadStates = it.resourceUploadStates + (resourceId to UiAsync.Loading),
            )
        }
        when (val result = deleteQuestionResourceUseCase(questionId, resourceId)) {
            is AppResult.Failure -> updateState {
                it.copy(
                    uploadState = UiAsync.Error(result.error),
                    resourceUploadStates = it.resourceUploadStates + (resourceId to UiAsync.Error(result.error)),
                )
            }
            is AppResult.Success -> {
                analyticsTracker.trackAction("question_resource_delete", mapOf("question_id" to questionId, "resource_id" to resourceId))
                removeResource(resourceId)
                loadQuestions(currentState().selectedExamId.orEmpty())
                updateState {
                    it.copy(
                        uploadState = UiAsync.Success("Recurso eliminado"),
                        resourceUploadStates = it.resourceUploadStates - resourceId,
                    )
                }
            }
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
                    importSummary = "Importación cancelada: ${report.issues.size} errores detectados.",
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
                    if (report.skippedOverLimit > 0) append(" | ${report.skippedOverLimit} por encima del límite de 50")
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
