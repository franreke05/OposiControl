package com.oposicontrol.admin.features.specialties.presentation

sealed interface SpecialtiesIntent {
    data object CreateNew : SpecialtiesIntent
    data object Refresh : SpecialtiesIntent

    /**
     * Guardar lo que haya cambiado, en sus DOS endpoints si hace falta.
     *
     * Sustituye a los dos botones que había —uno para el formulario y otro para
     * los modos— porque partir el guardado en dos botones no informaba de que
     * eran dos escrituras: sólo conseguía que se guardara la mitad. Lo que las
     * escrituras son se dice en la barra ([specialtyWritesLabel]); lo que se
     * lanza aquí es sólo lo que está sucio.
     */
    data object Save : SpecialtiesIntent

    /** Volver a lo guardado: recarga la identidad y devuelve el borrador de capacidades. */
    data object DiscardChanges : SpecialtiesIntent

    /**
     * Abrir una especialidad, preguntando primero si el editor está sucio.
     *
     * [Select] abre directo y se usa para descartar; éste es el que usa el
     * catálogo.
     */
    data class SwitchSpecialty(val id: String?) : SpecialtiesIntent
    data class Select(val id: String) : SpecialtiesIntent
    data class UpdateSearch(val value: String) : SpecialtiesIntent
    data class UpdateStatusFilter(val value: SpecialtyStatusFilter) : SpecialtiesIntent
    data class UpdateName(val value: String) : SpecialtiesIntent
    data class UpdateDescription(val value: String) : SpecialtiesIntent
    data class UpdateSortOrder(val value: String) : SpecialtiesIntent
    data class ToggleActive(val value: Boolean) : SpecialtiesIntent

    // Capacidades: qué tipos de examen existen realmente para esta especialidad.
    // Se editan en el mismo formulario que el resto, pero se escriben aparte
    // porque van a otra tabla y a otro endpoint.
    data class ToggleCapability(val capability: SpecialtyCapabilityToggle, val value: Boolean) : SpecialtiesIntent
    data class UpdateLimitationMessage(val value: String) : SpecialtiesIntent

    /** Pide archivar la especialidad abierta: abre el diálogo, no archiva. */
    data object RequestArchive : SpecialtiesIntent
    data object ConfirmPendingAction : SpecialtiesIntent
    data object DismissPendingAction : SpecialtiesIntent
}

/** Los cinco interruptores de `capacidades_especialidad`. */
enum class SpecialtyCapabilityToggle {
    RAFAGAS,
    RAZONADOS,
    INMERSIVOS,
    DEMOS,
    OPOSIMARKET,
}
