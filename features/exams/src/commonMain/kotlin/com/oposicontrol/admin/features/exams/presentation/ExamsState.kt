package com.oposicontrol.admin.features.exams.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.Community
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamResourceAudit
import com.oposicontrol.admin.domain.model.ExamQuestion
import com.oposicontrol.admin.domain.model.Rafaga
import com.oposicontrol.admin.domain.model.RafagaQuestion
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.model.SpecialtyCapabilities

data class ExamsState(
    val specialties: UiAsync<List<Specialty>> = UiAsync.Loading,
    val exams: UiAsync<List<Exam>> = UiAsync.Loading,
    val rafagas: UiAsync<List<Rafaga>> = UiAsync.Loading,
    val questions: UiAsync<List<ExamQuestion>> = UiAsync.Empty,
    val rafagaQuestions: UiAsync<List<RafagaQuestion>> = UiAsync.Empty,
    val resourceAudit: UiAsync<ExamResourceAudit> = UiAsync.Idle,
    val searchQuery: String = "",
    val statusFilter: String = "all",
    val questionResourceFilter: String = "all",
    val specialtySearchQuery: String = "",
    val selectedContentSpecialtyId: String? = null,
    val contentPendingMigrationMessage: String = "",
    val specialtyEditorName: String = "",
    val specialtyEditorDescription: String = "",
    val specialtyEditorSortOrder: String = "0",
    val specialtyEditorIsActive: Boolean = true,
    val specialtyActionState: UiAsync<Unit> = UiAsync.Idle,
    val editorMode: ExamEditorMode = ExamEditorMode.Solucionario,
    val selectedExamId: String? = null,
    val selectedRafagaId: String? = null,
    val editorTitle: String = "",
    val editorDescription: String = "",
    val editorCode: String = "",
    val editorPriceTickets: String = "0",
    val editorSpecialtyId: String = "",
    val editorCommunityId: String? = null,
    /** Vocabulario canónico: rafaga | razonado | inmersivo. Restringido por [specialtyCapabilities]. */
    val editorTypeExam: String = "rafaga",
    val editorStatus: String = "draft",
    val editorPublishFrom: String = "",
    val editorTypeAccess: String = "gratuito",
    val editorYear: String = "",
    val editorIsOfficial: Boolean = false,
    val editorDurationMinutes: String = "",
    /**
     * Modos permitidos por la especialidad elegida en el editor. Se recarga cada
     * vez que cambia [editorSpecialtyId]. `Idle` mientras no hay especialidad
     * seleccionada todavía.
     */
    val specialtyCapabilities: UiAsync<SpecialtyCapabilities> = UiAsync.Idle,
    /**
     * Catálogo de comunidades autónomas para el selector de CCAA. Puede quedar en
     * `Error` sin bloquear el resto del formulario: la tabla que lo respalda es
     * una migración reciente que podría no estar aplicada en todos los entornos.
     */
    val communities: UiAsync<List<Community>> = UiAsync.Idle,
    /** Acción de publicar/despublicar en curso — distinta de [actionState], que es la del guardado general. */
    val lifecycleActionState: UiAsync<Unit> = UiAsync.Idle,
    val editorCoverUrl: String = "",
    val editorCoverStoragePath: String = "",
    val editorCoverMimeType: String = "",
    val editorCoverLocalUri: String = "",
    val editorCoverFileName: String = "",
    val coverUploadState: UiAsync<String> = UiAsync.Idle,
    val selectedQuestionId: String? = null,
    val selectedRafagaQuestionId: String? = null,
    val editorQuestionId: String? = null,
    val editorQuestionTitle: String = "",
    val editorQuestionStatement: String = "",
    val editorOptions: List<QuestionOptionDraft> = emptyList(),
    val editorResources: List<QuestionResourceDraft> = emptyList(),
    val uploadState: UiAsync<String> = UiAsync.Idle,
    val resourceUploadStates: Map<String, UiAsync<String>> = emptyMap(),
    val importState: UiAsync<Int> = UiAsync.Idle,
    val importSummary: String = "",
    val pendingDeleteTarget: PendingDeleteTarget? = null,
    val pendingDeleteMessage: String = "",
    val actionState: UiAsync<Unit> = UiAsync.Idle,
)
