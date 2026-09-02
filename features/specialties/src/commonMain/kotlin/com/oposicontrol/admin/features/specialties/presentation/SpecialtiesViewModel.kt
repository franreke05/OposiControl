package com.oposicontrol.admin.features.specialties.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.model.SpecialtyCapabilities
import com.oposicontrol.admin.domain.usecase.content.DeleteSpecialtyUseCase
import com.oposicontrol.admin.domain.usecase.content.GetSpecialtyCapabilitiesUseCase
import com.oposicontrol.admin.domain.usecase.content.SaveSpecialtyCapabilitiesUseCase
import com.oposicontrol.admin.domain.usecase.content.ObserveSpecialtiesUseCase
import com.oposicontrol.admin.domain.usecase.content.RefreshSpecialtiesUseCase
import com.oposicontrol.admin.domain.usecase.content.UpsertSpecialtyUseCase
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.time.Clock

class SpecialtiesViewModel(
    dispatcherProvider: DispatcherProvider,
    observeSpecialtiesUseCase: ObserveSpecialtiesUseCase,
    private val refreshSpecialtiesUseCase: RefreshSpecialtiesUseCase,
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

    init {
        scope.launch {
            observeSpecialtiesUseCase().collectLatest { specialties ->
                allItems = specialties
                publish()
                if (currentState().selectedId == null && specialties.isNotEmpty()) {
                    select(specialties.first().id)
                }
            }
        }
        dispatch(SpecialtiesIntent.Refresh)
    }

    override suspend fun handleIntent(intent: SpecialtiesIntent) {
        when (intent) {
            SpecialtiesIntent.CreateNew -> updateState {
                it.copy(
                    selectedId = null,
                    editorName = "",
                    editorDescription = "",
                    editorSortOrder = (allItems.size).toString(),
                    editorIsActive = true,
                    actionState = UiAsync.Idle,
                )
            }

            SpecialtiesIntent.DeleteSelected -> deleteSelected()
            SpecialtiesIntent.Refresh -> refresh()
            SpecialtiesIntent.Save -> save()
            is SpecialtiesIntent.Select -> select(intent.id)
            is SpecialtiesIntent.ToggleActive -> updateState { it.copy(editorIsActive = intent.value) }
            is SpecialtiesIntent.UpdateDescription -> updateState { it.copy(editorDescription = intent.value) }
            is SpecialtiesIntent.UpdateName -> updateState { it.copy(editorName = intent.value) }
            is SpecialtiesIntent.UpdateSearch -> {
                updateState { it.copy(searchQuery = intent.value) }
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

            SpecialtiesIntent.SaveCapabilities -> saveCapabilities()
        }
    }

    private fun updateCapabilityDraft(transform: (SpecialtyCapabilities) -> SpecialtyCapabilities) {
        val draft = currentState().capabilitiesDraft ?: return
        updateState { it.copy(capabilitiesDraft = transform(draft), capabilitiesActionState = UiAsync.Idle) }
    }

    private suspend fun loadCapabilities(specialtyId: String) {
        updateState { it.copy(capabilities = UiAsync.Loading, capabilitiesDraft = null, capabilitiesActionState = UiAsync.Idle) }
        when (val result = getSpecialtyCapabilitiesUseCase(specialtyId)) {
            is AppResult.Failure -> updateState { it.copy(capabilities = UiAsync.Error(result.error)) }
            is AppResult.Success -> updateState {
                it.copy(capabilities = UiAsync.Success(result.value), capabilitiesDraft = result.value)
            }
        }
    }

    private suspend fun saveCapabilities() {
        val draft = currentState().capabilitiesDraft
            ?: return updateState {
                it.copy(
                    capabilitiesActionState = UiAsync.Error(
                        AppError.Validation("Selecciona una especialidad para editar sus modos."),
                    ),
                )
            }
        updateState { it.copy(capabilitiesActionState = UiAsync.Loading) }
        when (val result = saveSpecialtyCapabilitiesUseCase(draft)) {
            is AppResult.Failure -> updateState { it.copy(capabilitiesActionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction(
                    "specialty_capabilities_save",
                    mapOf(
                        "specialty_id" to draft.specialtyId,
                        // Qué modos quedan activos importa más que el evento en sí:
                        // es lo que cambia para el usuario final de OposiBot.
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

    private suspend fun refresh() {
        analyticsTracker.trackScreen("specialties")
        when (val result = refreshSpecialtiesUseCase()) {
            is AppResult.Failure -> updateState { it.copy(items = UiAsync.Error(result.error)) }
            is AppResult.Success -> publish()
        }
    }

    private fun select(id: String, resetActionState: Boolean = true) {
        val item = allItems.firstOrNull { it.id == id } ?: return
        val alreadySelected = currentState().selectedId == item.id
        updateState {
            it.copy(
                selectedId = item.id,
                editorName = item.name,
                editorDescription = item.description,
                editorSortOrder = item.sortOrder.toString(),
                editorIsActive = item.isActive,
                actionState = if (resetActionState) UiAsync.Idle else it.actionState,
            )
        }
        // Al cambiar de especialidad se recargan sus modos. Se evita recargar
        // cuando es la misma (p. ej. tras guardar el formulario principal) para no
        // descartar un borrador de capacidades a medio editar.
        if (!alreadySelected) {
            scope.launch { loadCapabilities(item.id) }
        }
    }

    private fun publish() {
        val query = currentState().searchQuery.trim().lowercase()
        val filtered = allItems.filter { item ->
            query.isBlank() ||
                item.name.lowercase().contains(query) ||
                item.description.lowercase().contains(query)
        }
        updateState {
            it.copy(items = if (filtered.isEmpty()) UiAsync.Empty else UiAsync.Success(filtered))
        }
    }

    private suspend fun save() {
        val existing = allItems.firstOrNull { it.id == currentState().selectedId }
        val sortOrder = currentState().editorSortOrder.toIntOrNull()
            ?: return updateState {
                it.copy(actionState = UiAsync.Error(AppError.Validation("El orden debe ser numerico.")))
            }

        updateState { it.copy(actionState = UiAsync.Loading) }
        val specialty = Specialty(
            id = existing?.id.orEmpty(),
            name = currentState().editorName.trim(),
            description = currentState().editorDescription.trim(),
            isActive = currentState().editorIsActive,
            sortOrder = sortOrder,
            linkedExams = existing?.linkedExams ?: 0,
            createdAt = existing?.createdAt ?: Clock.System.now(),
            updatedAt = Clock.System.now(),
        )
        when (val result = upsertSpecialtyUseCase(specialty)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("specialty_save", mapOf("specialty_id" to result.value.id))
                updateState { it.copy(selectedId = result.value.id, actionState = UiAsync.Success(Unit)) }
                select(result.value.id, resetActionState = false)
            }
        }
    }

    private suspend fun deleteSelected() {
        val selectedId = currentState().selectedId
            ?: return updateState {
                it.copy(actionState = UiAsync.Error(AppError.Validation("Selecciona una especialidad para borrarla.")))
            }
        updateState { it.copy(actionState = UiAsync.Loading) }
        when (val result = deleteSpecialtyUseCase(selectedId)) {
            is AppResult.Failure -> updateState { it.copy(actionState = UiAsync.Error(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("specialty_delete", mapOf("specialty_id" to selectedId))
                updateState {
                    it.copy(
                        selectedId = null,
                        editorName = "",
                        editorDescription = "",
                        editorSortOrder = "0",
                        editorIsActive = true,
                        actionState = UiAsync.Success(Unit),
                    )
                }
            }
        }
    }
}
