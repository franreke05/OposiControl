package com.oposicontrol.admin.features.examaccess.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync

data class ExamAccessState(
    /** Lo que se pinta en el catálogo: los accesos que pasan búsqueda y filtro. */
    val accesses: UiAsync<List<ExamAccessItem>> = UiAsync.Loading,
    /** Todos los descargados, sin filtrar: el acceso abierto se busca aquí aunque el filtro lo oculte. */
    val allAccesses: List<ExamAccessItem> = emptyList(),
    val searchQuery: String = "",
    val filter: AccessFilter = AccessFilter.ALL,
    val selectedId: String? = null,
    /** El formulario de concesión nueva está abierto. */
    val creating: Boolean = false,
    val userQuery: String = "",
    /** `Idle` = todavía no se ha buscado a nadie. */
    val userResults: UiAsync<List<AccessUserOption>> = UiAsync.Idle,
    val pickedUser: AccessUserOption? = null,
    val examQuery: String = "",
    /** Catálogo de exámenes; se pide la primera vez que se abre el formulario. */
    val exams: UiAsync<List<AccessExamOption>> = UiAsync.Idle,
    val pickedExam: AccessExamOption? = null,
    val reason: String = ExamAccessTypes.DEFAULT,
    val confirmingRevoke: Boolean = false,
    val actionState: UiAsync<Unit> = UiAsync.Idle,
) {
    val selected: ExamAccessItem? get() = allAccesses.firstOrNull { it.id == selectedId }

    val canGrant: Boolean get() = pickedUser != null && pickedExam != null
}
