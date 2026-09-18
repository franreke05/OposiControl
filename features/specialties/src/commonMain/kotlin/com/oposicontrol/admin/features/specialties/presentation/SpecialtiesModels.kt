package com.oposicontrol.admin.features.specialties.presentation

/**
 * Las acciones de esta pantalla que hay que confirmar antes de ejecutarlas.
 *
 * Vive en el ESTADO del ViewModel y no en un `remember` local a propósito: el
 * diálogo y el «hay algo pendiente» tienen que leerse del mismo sitio, y un
 * `remember` dentro del composable se pierde al recomponer la columna y no lo
 * puede fabricar ningún banco de pruebas visual.
 *
 * (Aquí vivía `SpecialtyItem`, un modelo de fila sin ningún uso de producción:
 * la pantalla siempre ha trabajado sobre `domain.model.Specialty`. Se retira en
 * vez de arrastrarlo, porque un modelo paralelo al de dominio invita a
 * rellenarlo con campos que la especialidad no tiene.)
 */
sealed interface PendingSpecialtyAction {
    /**
     * Archivar la especialidad abierta.
     *
     * No se llama «eliminar» porque no elimina: `DELETE /especialidades/{id}`
     * ejecuta internamente un `PATCH {activa: false}`, la fila permanece y
     * `listEspecialidades` no filtra por `activa`, así que la especialidad sigue
     * en este mismo catálogo marcada como archivada.
     */
    data object Archive : PendingSpecialtyAction

    /**
     * Abrir otra especialidad —o empezar una nueva, con [id] a `null`—
     * descartando lo que haya en el editor.
     *
     * No borra nada del servidor; lo que se pierde es lo escrito en pantalla. Se
     * confirma igualmente porque las dos escrituras de esta pantalla se pierden
     * juntas, y sólo aparece si de verdad hay cambios: un diálogo en cada clic
     * del catálogo enseñaría que confirmar no significa nada.
     */
    data class Switch(val id: String?) : PendingSpecialtyAction
}
