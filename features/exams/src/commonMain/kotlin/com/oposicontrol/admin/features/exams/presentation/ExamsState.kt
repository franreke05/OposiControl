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
    /** La lista del catálogo, YA filtrada por búsqueda y estado. Es lo que se pinta. */
    val exams: UiAsync<List<Exam>> = UiAsync.Loading,
    /**
     * Todos los exámenes, sin filtrar. No se pinta: sirve para RESOLVER identidad.
     *
     * Existe porque buscar en el catálogo no puede cambiar quién es el examen
     * abierto. Mientras el examen abierto se resolvía sobre [exams], escribir algo
     * que no encontrara nada lo dejaba en `null` y la pantalla perdía el chip de
     * estado de la cabecera, las acciones de Publicación —que pasaban a decir
     * «Guarda el examen para poder publicarlo» a un examen ya publicado— y el
     * aviso de cambios sin guardar. Filtrar una lista es una operación de vista;
     * no puede tener consecuencias sobre el objeto que se está editando.
     *
     * Es la misma fuente que ya usaba `selectExam` para abrirlo, y por eso
     * «Descartar» sí funcionaba con la búsqueda puesta.
     */
    val catalogExams: List<Exam> = emptyList(),
    val rafagas: UiAsync<List<Rafaga>> = UiAsync.Loading,
    val questions: UiAsync<List<ExamQuestion>> = UiAsync.Empty,
    /**
     * Último recuento de preguntas CARGADO en esta sesión, por examen.
     *
     * Lo escribe sólo una carga con éxito de las preguntas —lista con n → n, lista
     * vacía → 0—; un fallo no lo toca. Es la cifra que se enseña mientras
     * [questions] está cargando, antes que `Exam.questionCount`, sólo para que la
     * cabecera no parpadee cada vez que se guarda o se publica. Las dos son un
     * dato (K2-2): desde M1 el `total_preguntas` servido lo mantiene la base, con
     * la misma definición que la lista (preguntas activas vinculadas).
     */
    val countedQuestions: Map<String, Int> = emptyMap(),
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
    val editorPriceTickets: String = NewExamDefaults.PRICE_TICKETS,
    val editorSpecialtyId: String = "",
    val editorCommunityId: String? = null,
    /** Vocabulario canónico: rafaga | razonado | inmersivo. Restringido por [specialtyCapabilities]. */
    val editorTypeExam: String = NewExamDefaults.TYPE_EXAM,
    val editorStatus: String = "draft",
    val editorPublishFrom: String = "",
    val editorTypeAccess: String = NewExamDefaults.TYPE_ACCESS,
    val editorYear: String = "",
    val editorIsOfficial: Boolean = NewExamDefaults.IS_OFFICIAL,
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
    /**
     * Resultado de la última acción general: guardar el examen, guardar o quitar
     * una pregunta, archivar.
     *
     * Tras guardar un examen se queda en `Success` también después de reabrirlo
     * —es lo que hace visible «Cambios guardados.»—. La siguiente edición del
     * formulario lo devuelve a `Idle`, y lo mismo hace con el `Error` de un
     * guardado rechazado, que describía un formulario que ya no existe. El `Error`
     * de otra acción (quitar o mover una pregunta, archivar) no lo retira una
     * edición: se queda hasta la siguiente acción o hasta abrir otro examen.
     */
    val actionState: UiAsync<Unit> = UiAsync.Idle,
    /**
     * Resultado del BORRADO DEFINITIVO del examen abierto, y de nada más.
     *
     * Tiene estado propio por la misma razón que [lifecycleActionState] lo tiene
     * frente a [actionState]: quien lee [actionState] es la barra de guardado, y
     * el borrado no es un guardado. Metido ahí, un rechazo del servidor
     * —«no se puede borrar: los usuarios ya lo han usado»— aparecía al pie del
     * formulario como si hubiera fallado un guardado que nadie pidió, y encima
     * `examFieldError` tenía que decidir sobre un código sin campo culpable.
     *
     * Lo pinta [examDeletionNotice] en la pestaña Publicación, al lado del botón
     * que lo provoca, que es donde el aviso se lee como consecuencia de la acción.
     *
     * **No lo comparte con archivar**: archivar es reversible, es una acción de
     * ciclo de vida y su resultado va por [lifecycleActionState] junto a
     * publicar/despublicar.
     */
    val examDeleteState: UiAsync<Unit> = UiAsync.Idle,
)

/**
 * Con qué valores arranca un examen NUEVO en los campos que no empiezan vacíos.
 *
 * Una sola fuente para los tres sitios que los necesitan: los valores por
 * defecto de [ExamsState], `resetEditor` —que los reescribe al empezar un
 * examen— y el guardián de borrador nuevo, que los compara para saber si alguien
 * ha tocado algo. Cuando eran literales repetidos, el guardián se quedó sin
 * estos cuatro: cambiar SÓLO el tipo, el acceso, el precio o «Oficial» de un
 * examen nuevo y pinchar otro del catálogo tiraba el cambio sin preguntar.
 */
internal object NewExamDefaults {
    const val TYPE_EXAM: String = "rafaga"
    const val TYPE_ACCESS: String = "gratuito"
    const val PRICE_TICKETS: String = "0"
    const val IS_OFFICIAL: Boolean = false
}
