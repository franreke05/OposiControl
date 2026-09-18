package com.oposicontrol.admin.features.specialties.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.model.SpecialtyCapabilities
import com.oposicontrol.admin.domain.usecase.content.DeleteSpecialtyUseCase
import com.oposicontrol.admin.domain.usecase.content.GetSpecialtyCapabilitiesUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveAllExamsUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveSpecialtiesUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshAllExamsUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshSpecialtiesUseCase
import com.oposicontrol.admin.domain.usecase.content.SaveSpecialtyCapabilitiesUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertSpecialtyUseCase
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * El workspace de Especialidades.
 *
 * Tres cosas que este ViewModel hace y que conviene no deshacer:
 *
 *  - **Dos lecturas independientes.** Las especialidades y la lista completa de
 *    exámenes se piden por separado, cada una con su propio `UiAsync`. La
 *    pantalla anterior dependía de un único `safeCall` que leía las dos cosas,
 *    así que un fallo de `GET /examenes` dejaba el catálogo de especialidades
 *    vacío. Aquí un fallo de exámenes sólo deja el desglose por tipo sin
 *    disponible.
 *  - **Guard de semilla.** El flujo de especialidades emite `emptyList` en su
 *    primera emisión, antes de que nadie haya hablado con el servidor. Sin
 *    guard, la pantalla escribía «No hay especialidades» durante la carga. Sólo
 *    el refresco —que es quien sí ha hablado— puede declarar el vacío.
 *  - **Un solo guardado, dos escrituras.** `Save` lanza únicamente lo que está
 *    sucio: `POST`/`PATCH` en `especialidades` y `PUT` en
 *    `capacidades_especialidad`. Son dos endpoints y la interfaz lo dice; lo que
 *    no hace es fingir que es una transacción.
 */
class SpecialtiesViewModel(
    dispatcherProvider: DispatcherProvider,
    observeSpecialtiesUseCase: ObserveSpecialtiesUseCase,
    observeAllExamsUseCase: ObserveAllExamsUseCase,
    private val refreshSpecialtiesUseCase: RefreshSpecialtiesUseCase,
    private val refreshAllExamsUseCase: RefreshAllExamsUseCase,
    private val upsertSpecialtyUseCase: UpsertSpecialtyUseCase,
    private val deleteSpecialtyUseCase: DeleteSpecialtyUseCase,
    private val getSpecialtyCapabilitiesUseCase: GetSpecialtyCapabilitiesUseCase,
    private val saveSpecialtyCapabilitiesUseCase: SaveSpecialtyCapabilitiesUseCase,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<SpecialtiesState, SpecialtiesIntent>(
    initialState = SpecialtiesState(),
    dispatcherProvider = dispatcherProvider,
) {
    private var allItems: List<Specialty> = emptyList()
    private var allExams: List<Exam> = emptyList()

    init {
        scope.launch {
            observeSpecialtiesUseCase().collectLatest { specialties ->
                allItems = specialties
                publish()
                if (currentState().selectedId == null && !currentState().creatingNew && specialties.isNotEmpty()) {
                    select(currentState().items.firstOrNullId() ?: specialties.first().id)
                }
            }
        }
        scope.launch {
            observeAllExamsUseCase().collectLatest { exams ->
                allExams = exams
                publishExams()
            }
        }
        dispatch(SpecialtiesIntent.Refresh)
    }

    override suspend fun handleIntent(intent: SpecialtiesIntent) {
        when (intent) {
            SpecialtiesIntent.CreateNew -> createNew()
            SpecialtiesIntent.Refresh -> refresh()
            SpecialtiesIntent.Save -> save()
            SpecialtiesIntent.DiscardChanges -> discardChanges()
            is SpecialtiesIntent.SwitchSpecialty -> switchSpecialty(intent.id)
            is SpecialtiesIntent.Select -> select(intent.id)
            is SpecialtiesIntent.ToggleActive -> updateState { it.copy(editorIsActive = intent.value) }
            is SpecialtiesIntent.UpdateDescription -> updateState { it.copy(editorDescription = intent.value) }
            is SpecialtiesIntent.UpdateName -> updateState { it.copy(editorName = intent.value) }
            is SpecialtiesIntent.UpdateSearch -> {
                updateState { it.copy(searchQuery = intent.value) }
                publish()
            }

            is SpecialtiesIntent.UpdateStatusFilter -> {
                updateState { it.copy(statusFilter = intent.value) }
                publish()
            }

            is SpecialtiesIntent.UpdateSortOrder -> updateState { it.copy(editorSortOrder = intent.value) }

            is SpecialtiesIntent.ToggleCapability -> updateCapabilityDraft { draft ->
                when (intent.capability) {
                    SpecialtyCapabilityToggle.RAFAGAS -> draft.copy(permiteRafagas = intent.value)
                    SpecialtyCapabilityToggle.RAZONADOS -> draft.copy(permiteRazonados = intent.value)
                    SpecialtyCapabilityToggle.INMERSIVOS -> draft.copy(permiteInmersivos = intent.value)
                    SpecialtyCapabilityToggle.DEMOS -> draft.copy(permiteDemos = intent.value)
                    SpecialtyCapabilityToggle.OPOSIMARKET -> draft.copy(permiteOposimarket = intent.value)
                }
            }

            is SpecialtiesIntent.UpdateLimitationMessage -> updateCapabilityDraft { draft ->
                draft.copy(limitationMessage = intent.value)
            }

            SpecialtiesIntent.RequestArchive -> requestArchive()
            SpecialtiesIntent.ConfirmPendingAction -> confirmPendingAction()
            SpecialtiesIntent.DismissPendingAction -> updateState {
                it.copy(pendingAction = null, pendingMessage = "")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Publicación del catálogo
    // -----------------------------------------------------------------------

    /**
     * Recalcula catálogo y lista filtrada.
     *
     * @param confirmedEmpty `true` sólo cuando quien llama ha hablado con el
     *   servidor y sabe que de verdad no hay especialidades. Es lo que autoriza
     *   a escribir el vacío; el flujo por sí solo no puede, porque su primera
     *   emisión es la semilla.
     */
    private fun publish(confirmedEmpty: Boolean = false) {
        val catalog = allItems.sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase() }))
        val state = currentState()
        val filtered = applySpecialtyFilters(allItems, state.searchQuery, state.statusFilter)
        updateState { current ->
            current.copy(
                catalog = catalog,
                items = when {
                    filtered.isNotEmpty() -> UiAsync.Success(filtered)
                    // Recortado a cero teniendo datos detrás: es un vacío real,
                    // el de «no hay ninguna así».
                    allItems.isNotEmpty() -> UiAsync.Empty
                    confirmedEmpty -> UiAsync.Empty
                    current.items is UiAsync.Loading || current.items is UiAsync.Idle -> current.items
                    else -> UiAsync.Empty
                },
            )
        }
    }

    private fun publishExams(confirmedEmpty: Boolean = false) {
        updateState { current ->
            current.copy(
                exams = when {
                    allExams.isNotEmpty() -> UiAsync.Success(allExams)
                    confirmedEmpty -> UiAsync.Success(emptyList())
                    current.exams is UiAsync.Loading || current.exams is UiAsync.Idle -> current.exams
                    else -> current.exams
                },
            )
        }
    }

    private fun UiAsync<List<Specialty>>.firstOrNullId(): String? =
        (this as? UiAsync.Success)?.value?.firstOrNull()?.id

    // -----------------------------------------------------------------------
    // Lecturas
    // -----------------------------------------------------------------------

    private suspend fun refresh() {
        analyticsTracker.trackScreen("specialties")
        when (val result = refreshSpecialtiesUseCase()) {
            is AppResult.Failure -> updateState { it.copy(items = UiAsync.Error(result.error)) }
            is AppResult.Success -> publish(confirmedEmpty = true)
        }
        refreshExams()
    }

    /**
     * La lista completa de exámenes, para el desglose por tipo.
     *
     * `refreshSpecialties` ya pide `GET /examenes` para calcular `linkedExams`,
     * pero no guarda la lista en ningún flujo observable: sólo el recuento. Para
     * abrir ese recuento por tipo hay que pedirla otra vez por el camino que sí
     * la cachea, que es `refreshAllExams`. Es una llamada más, y se hace en su
     * propio resultado para que un fallo no toque al catálogo.
     */
    private suspend fun refreshExams() {
        updateState { if (it.exams is UiAsync.Success) it else it.copy(exams = UiAsync.Loading) }
        when (val result = refreshAllExamsUseCase()) {
            is AppResult.Failure -> updateState { it.copy(exams = UiAsync.Error(result.error)) }
            is AppResult.Success -> publishExams(confirmedEmpty = true)
        }
    }

    private suspend fun loadCapabilities(specialtyId: String) {
        updateState {
            it.copy(
                capabilities = UiAsync.Loading,
                capabilitiesDraft = null,
                capabilitiesActionState = UiAsync.Idle,
            )
        }
        when (val result = getSpecialtyCapabilitiesUseCase(specialtyId)) {
            is AppResult.Failure -> updateState { it.copy(capabilities = UiAsync.Error(result.error)) }
            is AppResult.Success -> updateState {
                it.copy(capabilities = UiAsync.Success(result.value), capabilitiesDraft = result.value)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Selección
    // -----------------------------------------------------------------------

    private fun select(id: String, resetActionState: Boolean = true) {
        val item = allItems.firstOrNull { it.id == id } ?: return
        val alreadySelected = currentState().selectedId == item.id
        adoptEditor(item, resetActionState)
        // Al cambiar de especialidad se recargan sus tipos. No se recarga cuando
        // es la misma (por ejemplo tras guardar la identidad) para no descartar
        // un borrador de capacidades a medio editar.
        if (!alreadySelected) {
            scope.launch { loadCapabilities(item.id) }
        }
    }

    private fun adoptEditor(item: Specialty, resetActionState: Boolean = true) {
        updateState {
            it.copy(
                selectedId = item.id,
                creatingNew = false,
                editorName = item.name,
                editorDescription = item.description,
                editorSortOrder = item.sortOrder.toString(),
                editorIsActive = item.isActive,
                actionState = if (resetActionState) UiAsync.Idle else it.actionState,
                pendingAction = null,
                pendingMessage = "",
            )
        }
    }

    private fun createNew() {
        updateState {
            it.copy(
                selectedId = null,
                creatingNew = true,
                editorName = "",
                editorDescription = "",
                editorSortOrder = allItems.size.toString(),
                editorIsActive = true,
                actionState = UiAsync.Idle,
                // Una especialidad que no existe no tiene fila de capacidades:
                // `PUT .../{id}/capacidades` necesita un id. Se cargan en cuanto
                // el alta devuelve uno.
                capabilities = UiAsync.Idle,
                capabilitiesDraft = null,
                capabilitiesActionState = UiAsync.Idle,
                pendingAction = null,
                pendingMessage = "",
            )
        }
    }

    /**
     * Abrir otra especialidad desde el catálogo.
     *
     * Si el editor está limpio, abre directo: preguntar en cada clic enseñaría
     * que confirmar no significa nada. Si hay cambios, los dos endpoints
     * pendientes se perderían juntos, y eso sí se pregunta.
     */
    private fun switchSpecialty(id: String?) {
        val state = currentState()
        if (!state.anyDirty) {
            if (id == null) createNew() else select(id)
            return
        }
        val writes = specialtyPendingWrites(state)
        val detail = specialtyWritesLabel(writes)?.let { " $it" }.orEmpty()
        updateState {
            it.copy(
                pendingAction = PendingSpecialtyAction.Switch(id),
                pendingMessage = "Hay cambios sin guardar en «${state.editorName.ifBlank { "esta especialidad" }}»." +
                    detail,
            )
        }
    }

    private fun discardChanges() {
        val state = currentState()
        val loaded = state.selectedSpecialty()
        val loadedCapabilities = (state.capabilities as? UiAsync.Success)?.value
        if (loaded == null) {
            // Descartar una especialidad que todavía no existe no deja nada
            // abierto: no hay versión guardada a la que volver.
            updateState {
                it.copy(
                    selectedId = null,
                    creatingNew = false,
                    editorName = "",
                    editorDescription = "",
                    editorSortOrder = "0",
                    editorIsActive = true,
                    actionState = UiAsync.Idle,
                    capabilities = UiAsync.Idle,
                    capabilitiesDraft = null,
                    capabilitiesActionState = UiAsync.Idle,
                )
            }
            return
        }
        adoptEditor(loaded)
        updateState {
            it.copy(capabilitiesDraft = loadedCapabilities, capabilitiesActionState = UiAsync.Idle)
        }
    }

    // -----------------------------------------------------------------------
    // Escrituras
    // -----------------------------------------------------------------------

    private fun updateCapabilityDraft(transform: (SpecialtyCapabilities) -> SpecialtyCapabilities) {
        val draft = currentState().capabilitiesDraft ?: return
        updateState { it.copy(capabilitiesDraft = transform(draft), capabilitiesActionState = UiAsync.Idle) }
    }

    /**
     * Guarda lo que esté sucio, en el orden en que la barra lo ha anunciado.
     *
     * La identidad va primero porque un alta tiene que existir antes de que sus
     * capacidades tengan a dónde ir. Si la identidad falla, las capacidades no
     * se intentan: escribir la mitad de un cambio y además la mitad menos
     * visible es peor que no escribir nada.
     */
    private suspend fun save() {
        val state = currentState()
        if (state.selectedId == null && !state.creatingNew) {
            updateState {
                it.copy(
                    actionState = UiAsync.Error(
                        AppError.Validation("Abre una especialidad del catálogo o crea una nueva."),
                    ),
                )
            }
            return
        }
        val blockers = specialtySaveBlockers(state)
        if (blockers.isNotEmpty()) {
            // El error se enseña junto a los campos que lo provocan: con dos
            // endpoints y dos bloques de formulario, un aviso en el sitio
            // equivocado manda a buscar el problema donde no está.
            val blocker = blockers.first()
            val error = UiAsync.Error(AppError.Validation(blocker.message))
            updateState {
                when (blocker.target) {
                    SpecialtyBlockerTarget.IDENTITY -> it.copy(actionState = error)
                    SpecialtyBlockerTarget.CAPABILITIES -> it.copy(capabilitiesActionState = error)
                }
            }
            return
        }
        val writes = specialtyPendingWrites(state)
        if (writes.isEmpty()) return
        val identityOk = if (SpecialtyWrite.CREATE in writes || SpecialtyWrite.IDENTITY in writes) {
            saveIdentity()
        } else {
            true
        }
        if (identityOk && SpecialtyWrite.CAPABILITIES in writes) {
            saveCapabilities()
        }
    }

    private suspend fun saveIdentity(): Boolean {
        val state = currentState()
        val existing = state.selectedSpecialty()
        val sortOrder = state.editorSortOrder.trim().toIntOrNull() ?: return false
        updateState { it.copy(actionState = UiAsync.Loading) }
        val specialty = Specialty(
            id = existing?.id.orEmpty(),
            name = state.editorName.trim(),
            description = state.editorDescription.trim(),
            isActive = state.editorIsActive,
            sortOrder = sortOrder,
            linkedExams = existing?.linkedExams ?: 0,
            createdAt = existing?.createdAt ?: Clock.System.now(),
            updatedAt = Clock.System.now(),
        )
        return when (val result = upsertSpecialtyUseCase(specialty)) {
            is AppResult.Failure -> {
                updateState { it.copy(actionState = UiAsync.Error(result.error)) }
                false
            }
            is AppResult.Success -> {
                val created = existing == null
                analyticsTracker.trackAction(
                    "specialty_save",
                    mapOf("specialty_id" to result.value.id, "created" to created.toString()),
                )
                // Se adopta lo devuelto por el servidor sin esperar a que el
                // flujo vuelva a emitir: si no, justo después de guardar el
                // editor seguiría comparándose contra la versión anterior y la
                // barra seguiría diciendo «cambios sin guardar».
                allItems = allItems.filterNot { it.id == result.value.id } + result.value
                publish()
                adoptEditor(result.value, resetActionState = false)
                updateState { it.copy(actionState = UiAsync.Success(Unit)) }
                if (created) {
                    loadCapabilities(result.value.id)
                }
                true
            }
        }
    }

    private suspend fun saveCapabilities() {
        val draft = currentState().capabilitiesDraft ?: return
        updateState { it.copy(capabilitiesActionState = UiAsync.Loading) }
        when (val result = saveSpecialtyCapabilitiesUseCase(draft)) {
            is AppResult.Failure -> updateState { it.copy(capabilitiesActionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction(
                    "specialty_capabilities_save",
                    mapOf(
                        "specialty_id" to draft.specialtyId,
                        // Qué tipos quedan abiertos importa más que el evento en
                        // sí: es lo que cambia para el usuario final de OposiBot.
                        "enabled_modes" to result.value.enabledExamModes.joinToString("|"),
                    ),
                )
                updateState {
                    it.copy(
                        capabilities = UiAsync.Success(result.value),
                        capabilitiesDraft = result.value,
                        capabilitiesActionState = UiAsync.Success(Unit),
                    )
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Archivar
    // -----------------------------------------------------------------------

    private fun requestArchive() {
        val state = currentState()
        val selected = state.selectedSpecialty()
        if (selected == null) {
            updateState {
                it.copy(
                    actionState = UiAsync.Error(
                        AppError.Validation("Abre una especialidad para poder archivarla."),
                    ),
                )
            }
            return
        }
        updateState {
            it.copy(
                pendingAction = PendingSpecialtyAction.Archive,
                pendingMessage = "«${selected.name}» pasará a archivada: desaparecerá del catálogo público y " +
                    "seguirá en esta lista. No se borra nada, y sus exámenes publicados se siguen sirviendo.",
            )
        }
    }

    private suspend fun confirmPendingAction() {
        when (val pending = currentState().pendingAction) {
            PendingSpecialtyAction.Archive -> archiveSelected()
            is PendingSpecialtyAction.Switch -> {
                updateState { it.copy(pendingAction = null, pendingMessage = "") }
                val id = pending.id
                if (id == null) createNew() else select(id)
            }
            null -> Unit
        }
    }

    private suspend fun archiveSelected() {
        val selectedId = currentState().selectedId ?: return
        updateState { it.copy(actionState = UiAsync.Loading, pendingAction = null, pendingMessage = "") }
        when (val result = deleteSpecialtyUseCase(selectedId)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("specialty_archive", mapOf("specialty_id" to selectedId))
                // El servidor ha escrito `activa = false`. La fila sigue ahí, así
                // que la especialidad se queda abierta y marcada como archivada:
                // dejar el editor en blanco daría a entender que se ha borrado.
                allItems = allItems.map { if (it.id == selectedId) it.copy(isActive = false) else it }
                publish()
                updateState { it.copy(editorIsActive = false, actionState = UiAsync.Success(Unit)) }
            }
        }
    }
}
