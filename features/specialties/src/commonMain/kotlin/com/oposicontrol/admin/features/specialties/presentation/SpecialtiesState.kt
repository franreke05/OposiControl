package com.oposicontrol.admin.features.specialties.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.model.SpecialtyCapabilities

/**
 * El estado completo del workspace de Especialidades.
 *
 * Dos decisiones que conviene no deshacer sin querer:
 *
 *  - **[catalog] es la lista SIN filtrar y [items] la filtrada.** La
 *    especialidad abierta se resuelve siempre sobre [catalog]
 *    ([selectedSpecialty]). Es el mismo defecto que Exámenes ya pagó: con la
 *    lista filtrada como única fuente, escribir en el buscador algo que no
 *    incluyera la especialidad abierta la hacía desaparecer del editor, y con
 *    ella su estado, su recuento y el aviso de cambios sin guardar.
 *  - **Dos borradores y dos estados de acción.** La identidad va a
 *    `especialidades` por PATCH y las capacidades a
 *    `capacidades_especialidad` por PUT: son dos escrituras a dos endpoints, y
 *    juntarlas en un solo `actionState` haría imposible decir cuál de las dos
 *    ha fallado.
 */
data class SpecialtiesState(
    /** Lo que se pinta en el catálogo: ya buscado, filtrado y ordenado. */
    val items: UiAsync<List<Specialty>> = UiAsync.Loading,
    /** Todas las especialidades descargadas, sin recortar. La fuente del editor. */
    val catalog: List<Specialty> = emptyList(),
    val searchQuery: String = "",
    val statusFilter: SpecialtyStatusFilter = SpecialtyStatusFilter.ALL,
    val selectedId: String? = null,
    /**
     * `true` mientras el editor contiene una especialidad que todavía no existe.
     *
     * Distingue «no hay nada abierto» (el editor enseña su estado vacío) de
     * «estoy creando una» (el editor enseña el formulario y la barra dice
     * «Crear especialidad»). Sin esta bandera las dos cosas son `selectedId ==
     * null` y no se pueden contar distinto.
     */
    val creatingNew: Boolean = false,
    val editorName: String = "",
    val editorDescription: String = "",
    val editorSortOrder: String = "0",
    val editorIsActive: Boolean = true,
    val actionState: UiAsync<Unit> = UiAsync.Idle,
    /**
     * Capacidades de la especialidad seleccionada. Estado propio porque tiene su
     * propia carga y su propio error: si la tabla no está aprovisionada, esta
     * sección falla sola sin tumbar el resto de la pantalla.
     */
    val capabilities: UiAsync<SpecialtyCapabilities> = UiAsync.Idle,
    val capabilitiesDraft: SpecialtyCapabilities? = null,
    val capabilitiesActionState: UiAsync<Unit> = UiAsync.Idle,
    /**
     * Todos los exámenes, para el desglose por tipo de la pestaña Impacto.
     *
     * Es un estado aparte y no un campo de la especialidad porque ningún
     * endpoint devuelve el desglose: se calcula en cliente agrupando la lista
     * completa, igual que `refreshSpecialties` calcula `linkedExams`. Con su
     * propio `UiAsync` un fallo de `GET /examenes` deja el desglose sin
     * disponible y no arrastra al catálogo —que es el defecto que la pantalla
     * anterior sí tenía, con las dos lecturas dentro del mismo `safeCall`.
     */
    val exams: UiAsync<List<Exam>> = UiAsync.Idle,
    val pendingAction: PendingSpecialtyAction? = null,
    val pendingMessage: String = "",
) {
    /** La especialidad abierta, resuelta sobre la lista SIN filtrar. */
    fun selectedSpecialty(): Specialty? = catalog.firstOrNull { it.id == selectedId }

    /** `true` si el borrador de capacidades difiere de lo cargado. */
    val capabilitiesDirty: Boolean
        get() {
            val loaded = (capabilities as? UiAsync.Success)?.value ?: return false
            val draft = capabilitiesDraft ?: return false
            return draft != loaded
        }

    /**
     * `true` si los campos de identidad difieren de la especialidad guardada.
     *
     * Con una especialidad nueva no hay nada guardado contra lo que comparar, así
     * que basta con que se haya escrito algo: es lo que se perdería al cambiar de
     * fila.
     */
    val identityDirty: Boolean
        get() {
            val loaded = selectedSpecialty()
                ?: return editorName.isNotBlank() || editorDescription.isNotBlank()
            return editorName.trim() != loaded.name ||
                editorDescription.trim() != loaded.description ||
                editorSortOrder.trim() != loaded.sortOrder.toString() ||
                editorIsActive != loaded.isActive
        }

    /** `true` si hay algo pendiente en cualquiera de las dos mitades del editor. */
    val anyDirty: Boolean get() = identityDirty || capabilitiesDirty
}
