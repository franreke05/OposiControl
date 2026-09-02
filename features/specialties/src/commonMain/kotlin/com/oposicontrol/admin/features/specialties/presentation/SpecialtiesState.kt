package com.oposicontrol.admin.features.specialties.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.model.SpecialtyCapabilities

data class SpecialtiesState(
    val items: UiAsync<List<Specialty>> = UiAsync.Loading,
    val searchQuery: String = "",
    val selectedId: String? = null,
    val editorName: String = "",
    val editorDescription: String = "",
    val editorSortOrder: String = "0",
    val editorIsActive: Boolean = true,
    val actionState: UiAsync<Unit> = UiAsync.Idle,
    /**
     * Capacidades de la especialidad seleccionada. Estado propio porque tiene su
     * propia carga y su propio error: si la tabla no esta aprovisionada, esta
     * seccion falla sola sin tumbar el resto de la pantalla.
     */
    val capabilities: UiAsync<SpecialtyCapabilities> = UiAsync.Idle,
    val capabilitiesDraft: SpecialtyCapabilities? = null,
    val capabilitiesActionState: UiAsync<Unit> = UiAsync.Idle,
) {
    /** `true` si el borrador difiere de lo cargado, para habilitar Guardar. */
    val capabilitiesDirty: Boolean
        get() {
            val loaded = (capabilities as? UiAsync.Success)?.value ?: return false
            val draft = capabilitiesDraft ?: return false
            return draft != loaded
        }
}
