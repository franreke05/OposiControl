package com.oposicontrol.admin.features.questions.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.ExamType
import com.oposicontrol.admin.domain.model.QuestionResource
import com.oposicontrol.admin.domain.model.QuestionResourceType
import com.oposicontrol.admin.domain.model.QuestionType
import com.oposicontrol.admin.domain.model.Specialty
import com.oposicontrol.admin.domain.repository.QuestionActiveFilter

/**
 * Lo que la pantalla Preguntas DECIDE, sin pintar nada.
 *
 * Aquí vive todo lo que se puede comprobar sin componer: qué dice cada etiqueta,
 * qué secciones aplican a cada tipo, qué se puede hacer de verdad y qué está
 * bloqueado. Las vistas sólo colocan lo que estas funciones deciden.
 *
 * **Lo que el contrato NO tiene** —y por tanto esta pantalla no finge—:
 *
 *  - **Tema.** `QuestionDetail` no lo trae y `QuestionsRepository` no lo expone.
 *    El desplegable «Tema» del banco anterior era un `FlatDropdown` con una sola
 *    opción y `onSelect = {}`: ocupaba sitio y no filtraba nada. Se retira.
 *  - **Etiquetas.** No existen en el modelo de pregunta. Por eso el buscador del
 *    banco no las nombra: prometer «busca por etiqueta» y no encontrar ninguna
 *    enseña a desconfiar del buscador que sí funciona.
 *  - **Fechas.** El contrato V1 de preguntas no expone creación/actualización
 *    (`createdAt`/`updatedAt` son siempre `null`). No se pinta una sección para
 *    decir «No disponible» dos veces.
 */

// ---------------------------------------------------------------------------
// Tipos canónicos
// ---------------------------------------------------------------------------

/** Un tipo que llegó y no es ninguno de los tres canónicos. Nunca se disfraza de Ráfaga. */
const val QUESTION_UNKNOWN_TYPE: String = "Tipo sin identificar"

fun questionTypeLabel(type: QuestionType?): String = when (type) {
    QuestionType.RAFAGA -> "Ráfaga"
    QuestionType.RAZONADA -> "Razonada"
    QuestionType.INMERSIVA -> "Inmersiva"
    null -> QUESTION_UNKNOWN_TYPE
}

/** Qué es cada tipo, en una frase. El alias legacy `guiada` lo resuelve `QuestionType.fromExternal`. */
fun questionTypeDescription(type: QuestionType): String = when (type) {
    QuestionType.RAFAGA -> "Test encadenado. Sin explicación tras responder."
    QuestionType.RAZONADA -> "Lleva un razonamiento que explica la respuesta correcta."
    QuestionType.INMERSIVA -> "Puede apoyarse en recursos: imagen, audio, vídeo o documento."
}

/**
 * Una tarjeta del selector de tipo.
 *
 * Los tres están SIEMPRE disponibles: a diferencia del tipo de examen, ninguna
 * capacidad de especialidad puede prohibir uno, así que no hay «no permitido» ni
 * nada que comprobar contra el servidor.
 */
data class QuestionTypeChoice(
    val type: QuestionType,
    val label: String,
    val description: String,
    val selected: Boolean,
    /** KQ3-3: con vínculos o recursos el tipo está atado; la tarjeta no elegida deja de ser clicable. */
    val locked: Boolean = false,
) {
    val selectable: Boolean get() = !selected && !locked
}

fun questionTypeChoices(selected: QuestionType, locked: Boolean = false): List<QuestionTypeChoice> =
    QuestionType.entries.map { type ->
        QuestionTypeChoice(
            type = type,
            label = questionTypeLabel(type),
            description = questionTypeDescription(type),
            selected = type == selected,
            locked = locked,
        )
    }

/** Lo que lee un lector de pantalla: el estado va en texto, no sólo en color. */
fun questionTypeChoiceDescription(choice: QuestionTypeChoice): String =
    "${choice.label}. ${choice.description} ${if (choice.selected) "Elegido." else "No elegido."}"

// ---------------------------------------------------------------------------
// Identidad: código humano, nunca UUID
// ---------------------------------------------------------------------------

/**
 * El código que se enseña de una pregunta.
 *
 * Si el backend no mandó `codigo_pregunta`, se dice. **No se recorta el UUID**:
 * el banco anterior pintaba `id.take(8)` —un trozo de identificador interno— en
 * la fila, en la cabecera del editor y en la tabla de exámenes.
 */
fun questionCodeLabel(code: String): String = code.trim().ifBlank { "Sin código" }

/** Un examen, por su código y título humanos. Nunca por su id. */
fun examOptionLabel(exam: Exam): String {
    val code = exam.code.trim()
    return if (code.isBlank()) exam.title else "$code · ${exam.title}"
}

// ---------------------------------------------------------------------------
// Banco: búsqueda, filtros y recuento
// ---------------------------------------------------------------------------

/**
 * Lo que de verdad busca el servidor (`codigo_pregunta` y `titulo`, KQ0-3): código y
 * enunciado, en TODO el banco y no sobre lo cargado. No nombra etiquetas porque no
 * existen en el modelo.
 */
const val QUESTION_SEARCH_PLACEHOLDER: String = "Buscar por código o enunciado…"

/** Una opción de un filtro del banco. */
data class QuestionFilterOption<T>(val value: T, val label: String)

fun questionTypeFilterOptions(): List<QuestionFilterOption<QuestionType?>> =
    listOf(QuestionFilterOption<QuestionType?>(null, "Todos los tipos")) +
        QuestionType.entries.map { QuestionFilterOption<QuestionType?>(it, questionTypeLabel(it)) }

/** Los tres estados que se piden de verdad al servidor (`activa=true|false|todas`). */
fun questionStatusFilterOptions(): List<QuestionFilterOption<QuestionActiveFilter>> = listOf(
    QuestionFilterOption(QuestionActiveFilter.ALL, "Todos los estados"),
    QuestionFilterOption(QuestionActiveFilter.ACTIVE, "Activas"),
    QuestionFilterOption(QuestionActiveFilter.INACTIVE, "Inactivas"),
)

/** El filtro del banco por examen: TODOS, ráfagas incluidas (KQ0-5). */
fun questionExamFilterOptions(exams: UiAsync<List<Exam>>): List<QuestionFilterOption<String?>> =
    listOf(QuestionFilterOption<String?>(null, "Todos los exámenes")) +
        (exams as? UiAsync.Success)?.value.orEmpty().map { QuestionFilterOption<String?>(it.id, examOptionLabel(it)) }

/** El filtro del banco por especialidad (KQ0-4). */
fun questionSpecialtyFilterOptions(specialties: UiAsync<List<Specialty>>): List<QuestionFilterOption<String?>> =
    listOf(QuestionFilterOption<String?>(null, "Todas las especialidades")) +
        (specialties as? UiAsync.Success)?.value.orEmpty().map { QuestionFilterOption<String?>(it.id, it.name) }

/**
 * Si un examen admite preguntas de [type]: el mismo emparejamiento que impone
 * `validar_pregunta_examen` en la base (Ráfaga admite ráfaga, Razonado razonada e
 * Inmersivo inmersiva). Un examen de tipo desconocido no admite nada.
 */
fun examAcceptsQuestionType(exam: Exam, type: QuestionType): Boolean = when (ExamType.fromExternal(exam.typeExam)) {
    ExamType.RAFAGA -> type == QuestionType.RAFAGA
    ExamType.RAZONADO -> type == QuestionType.RAZONADA
    ExamType.INMERSIVO -> type == QuestionType.INMERSIVA
    null -> false
}

/**
 * Los exámenes a los que se puede vincular una pregunta (KQ0-5): del tipo que la
 * admite, de SU especialidad cuando se conoce y no archivados, cada uno con su
 * estado a la vista. Ofrecer los demás era ofrecer un rechazo seguro de la base
 * (`QUESTION_EXAM_TYPE_MISMATCH`, `QUESTION_EXAM_SPECIALTY_MISMATCH`).
 *
 * En un alta la especialidad todavía no existe —la aporta el examen elegido—, así
 * que [specialtyId] es `null` y sólo se filtra por tipo.
 */
fun questionLinkableExamOptions(
    exams: UiAsync<List<Exam>>,
    type: QuestionType?,
    specialtyId: String?,
): List<QuestionFilterOption<String>> {
    if (type == null) return emptyList()
    return (exams as? UiAsync.Success)?.value.orEmpty()
        .filter { exam -> exam.status != ExamStatus.ARCHIVED }
        .filter { exam -> examAcceptsQuestionType(exam, type) }
        .filter { exam -> specialtyId.isNullOrBlank() || exam.specialtyId == specialtyId }
        .map { exam -> QuestionFilterOption(exam.id, "${examOptionLabel(exam)} · ${examStatusLabel(exam.status)}") }
}

/**
 * Qué enseñar en el diálogo «Vincular a examen» — bug real corregido: los tres
 * casos (catálogo cargando, catálogo caído, catálogo real sin candidatos) caían
 * en el mismo selector vacío, indistinguible entre sí y de un fallo silencioso.
 */
sealed interface QuestionLinkableExamsView {
    data object Loading : QuestionLinkableExamsView
    data object Unavailable : QuestionLinkableExamsView
    data object NoCandidates : QuestionLinkableExamsView
    data class Options(val options: List<QuestionFilterOption<String>>) : QuestionLinkableExamsView
}

fun questionLinkableExamsView(
    exams: UiAsync<List<Exam>>,
    type: QuestionType?,
    specialtyId: String?,
): QuestionLinkableExamsView = when (exams) {
    is UiAsync.Loading, UiAsync.Idle -> QuestionLinkableExamsView.Loading
    is UiAsync.Error -> QuestionLinkableExamsView.Unavailable
    is UiAsync.Success, UiAsync.Empty -> {
        val options = questionLinkableExamOptions(exams, type, specialtyId)
        if (options.isEmpty()) QuestionLinkableExamsView.NoCandidates else QuestionLinkableExamsView.Options(options)
    }
}

/** Publicado o borrador, dicho junto al examen que se elige. */
fun examStatusLabel(status: ExamStatus): String = when (status) {
    ExamStatus.PUBLISHED -> "Publicado"
    ExamStatus.DRAFT -> "Borrador"
    ExamStatus.ARCHIVED -> "Archivado"
}

fun questionSortLabel(sort: QuestionsSort): String = when (sort) {
    QuestionsSort.MOST_RECENT -> "Más recientes"
    QuestionsSort.BY_CODE -> "Por código"
}

/** El recuento del TOTAL de la consulta, no de la página. Singular y plural, sin «(s)». */
fun questionCountLabel(count: Int): String =
    if (count == 1) "1 pregunta" else "$count preguntas"

/**
 * Si el filtro por examen está activo, la lista NO es el banco entero.
 *
 * Importa decirlo: con un examen elegido la consulta es la de ese examen, en su
 * orden, así que un «0 preguntas» ahí no significa que el banco esté vacío.
 */
fun questionScopeNote(state: QuestionsState, exams: UiAsync<List<Exam>>): String? {
    val examId = state.examFilter ?: return null
    val exam = (exams as? UiAsync.Success)?.value?.firstOrNull { it.id == examId }
    val name = exam?.let { examOptionLabel(it) } ?: "el examen elegido"
    return "Viendo sólo las preguntas de $name, no el banco completo."
}

fun questionStatusLabel(active: Boolean): String = if (active) "Activa" else "Inactiva"

/**
 * El aviso antes de archivar (KQ0-7, QN-13).
 *
 * El anterior decía «Puede seguir apareciendo en los exámenes donde esté vinculada»,
 * y es al revés: una pregunta inactiva sale de la lista de cada examen y de su
 * `total_preguntas` (M1). Lo que sí sigue es la pregunta en el banco, reactivable.
 */
const val QUESTION_ARCHIVE_WARNING: String =
    "La pregunta deja de servirse: sale de los exámenes donde está vinculada y de su recuento. " +
        "Seguirá en el banco como inactiva y podrás reactivarla desde Clasificación."

// ---------------------------------------------------------------------------
// Estados del banco
// ---------------------------------------------------------------------------

const val QUESTION_BANK_EMPTY_TITLE: String = "No hay preguntas"
const val QUESTION_BANK_EMPTY_MESSAGE: String =
    "El banco no ha devuelto ninguna pregunta. Puedes crear una nueva o importar contenido desde Importación."

/** Vacío por filtros ≠ banco vacío: son dos cosas distintas y se dicen distinto. */
const val QUESTION_BANK_NO_MATCHES: String = "No hay preguntas que coincidan con estos filtros."

// ---------------------------------------------------------------------------
// Editor: modo y cabecera
// ---------------------------------------------------------------------------

enum class QuestionEditorMode {
    /** Nada elegido: el panel explica qué es esto y ofrece crear. */
    EMPTY,

    /** Alta: la pregunta todavía no existe. */
    NEW,

    /** Una pregunta del banco, abierta. */
    EXISTING,
}

fun questionEditorMode(state: QuestionsState): QuestionEditorMode = when {
    state.isCreating -> QuestionEditorMode.NEW
    state.selectedQuestionId != null -> QuestionEditorMode.EXISTING
    else -> QuestionEditorMode.EMPTY
}

const val QUESTION_EDITOR_EMPTY_TITLE: String = "Preguntas"
const val QUESTION_EDITOR_EMPTY_MESSAGE: String =
    "Selecciona una pregunta del banco para revisar su contenido, o crea una nueva."

/** El título de la cabecera del editor: código humano o «Nueva pregunta». */
fun questionEditorTitle(state: QuestionsState): String = when (questionEditorMode(state)) {
    QuestionEditorMode.NEW -> "Nueva pregunta"
    QuestionEditorMode.EXISTING -> when (val detail = state.detail) {
        is UiAsync.Success -> questionCodeLabel(detail.value.code)
        is UiAsync.Error -> "No se ha podido abrir"
        else -> "Cargando…"
    }
    QuestionEditorMode.EMPTY -> QUESTION_EDITOR_EMPTY_TITLE
}

/** En un alta el código todavía no existe: lo genera el servidor al guardar. */
const val QUESTION_CODE_ON_CREATE: String = "El código se genera al guardar."

// ---------------------------------------------------------------------------
// Guardado
// ---------------------------------------------------------------------------

enum class QuestionSaveTone { CLEAN, DIRTY, SAVING, SAVED, ERROR }

/**
 * La barra de guardado, con el mismo vocabulario que Exámenes.
 *
 * `primaryEnabled` sale de la validación cliente que ya existía
 * ([QuestionsState.isDraftValid]); el backend sigue siendo la autoridad final y
 * un rechazo se enseña como error, nunca como éxito.
 */
data class QuestionSaveBar(
    val tone: QuestionSaveTone,
    val message: String,
    val primaryLabel: String,
    val primaryEnabled: Boolean,
    val showDiscard: Boolean,
)

fun questionSaveBar(state: QuestionsState): QuestionSaveBar {
    val creating = state.isCreating
    val primaryLabel = if (creating) "Crear pregunta" else "Guardar cambios"
    val valid = state.isDraftValid()
    val tone = when {
        state.saveState is UiAsync.Loading -> QuestionSaveTone.SAVING
        state.saveState is UiAsync.Error -> QuestionSaveTone.ERROR
        state.dirty || creating -> QuestionSaveTone.DIRTY
        state.saveState is UiAsync.Success -> QuestionSaveTone.SAVED
        else -> QuestionSaveTone.CLEAN
    }
    val message = when (tone) {
        QuestionSaveTone.SAVING -> if (creating) "Creando la pregunta…" else "Guardando los cambios…"
        QuestionSaveTone.ERROR -> (state.saveState as UiAsync.Error).error.message
        QuestionSaveTone.DIRTY -> when {
            creating && state.draftSpecialtyId.isBlank() ->
                "Elige la especialidad de la pregunta. El examen inicial es opcional."
            !valid -> "Revisa el enunciado, las cuatro opciones y cuál es la correcta."
            creating -> "Esta pregunta todavía no existe. Se creará al guardar."
            else -> "Hay cambios sin guardar en esta pregunta."
        }
        QuestionSaveTone.SAVED -> "Guardado."
        QuestionSaveTone.CLEAN -> "Sin cambios pendientes."
    }
    return QuestionSaveBar(
        tone = tone,
        message = message,
        primaryLabel = primaryLabel,
        primaryEnabled = (state.dirty || creating) && valid && state.saveState !is UiAsync.Loading,
        showDiscard = state.dirty,
    )
}

// ---------------------------------------------------------------------------
// Respuestas
// ---------------------------------------------------------------------------

/** Una opción, como se lee: letra, texto y si es la correcta. */
data class QuestionAnswerRow(
    val label: String,
    val content: String,
    val correct: Boolean,
) {
    /** Sin contenido no se pinta un hueco mudo: se dice que falta. */
    val empty: Boolean get() = content.isBlank()
}

fun questionAnswerRows(state: QuestionsState): List<QuestionAnswerRow> =
    state.draftOptions.sortedBy { it.sortOrder }.map {
        QuestionAnswerRow(label = it.label, content = it.content, correct = it.isCorrect)
    }

/** La letra de la correcta, o `null` si no hay exactamente una marcada. */
fun questionCorrectAnswerLabel(state: QuestionsState): String? =
    state.draftOptions.filter { it.isCorrect }.singleOrNull()?.label

/** La frase que acompaña a la marca: el estado nunca va sólo en color. */
fun questionCorrectAnswerNote(state: QuestionsState): String =
    questionCorrectAnswerLabel(state)
        ?.let { "La respuesta correcta es la opción $it." }
        ?: "Marca cuál de las cuatro es la respuesta correcta."

/**
 * Editar opciones depende de una RPC que hoy no está (P0-5).
 *
 * La UI NO se bloquea por eso: `updateQuestion(optionsChanged = false)` sí guarda
 * enunciado, tipo, explicación, fuente y estado. Lo que se dice es la verdad
 * concreta — si tocas las opciones, ese guardado puede volver rechazado — y el
 * rechazo se enseña como error, no como éxito.
 */
const val QUESTION_OPTIONS_RISK_NOTE: String =
    "Cambiar las opciones o la respuesta correcta depende de una operación del servidor que hoy " +
        "puede rechazar el guardado. El resto de campos sí se guardan."

// ---------------------------------------------------------------------------
// Explicación — por tipo
// ---------------------------------------------------------------------------

/**
 * Una Ráfaga no enseña razonamiento al responder, así que no se le pinta una
 * tarjeta de explicación vacía. Razonada e Inmersiva sí.
 */
fun questionShowsExplanation(type: QuestionType): Boolean = type != QuestionType.RAFAGA

// ---------------------------------------------------------------------------
// Clasificación — sólo lo que el contrato tiene
// ---------------------------------------------------------------------------

/**
 * La dificultad tal y como se lee.
 *
 * Es del contrato y se reenvía intacta en cada guardado, pero esta pantalla NO la
 * edita: no hay control para ella en el diseño aprobado, así que se enseña como
 * lectura en vez de fingir un selector que no escribe nada.
 *
 * Es lo único de sólo lectura de esta sección: «Estado» tiene su interruptor y
 * «Fuente» su campo, y pintarlos además como texto sería el mismo dato dos veces.
 */
fun questionDifficultyLabel(state: QuestionsState): String =
    state.draftDifficulty.trim().ifBlank { "Sin definir" }

/** Por qué no hay tema ni especialidad que enseñar. */
const val QUESTION_NO_TOPIC_NOTE: String =
    "El contrato de preguntas no incluye tema ni especialidad propios: la especialidad la aporta el " +
        "examen al que se vincula."

// ---------------------------------------------------------------------------
// Relación con exámenes
// ---------------------------------------------------------------------------

/**
 * Los vínculos son REALES desde KQ3-2 (`GET /preguntas/{id}/examenes`): una lista
 * vacía significa exactamente eso, y se dice así.
 */
const val QUESTION_LINKS_EMPTY_NOTE: String = "Esta pregunta no está en ningún examen."

/** Cuando la consulta de vínculos falla, no se enseña un vacío que parezca un dato. */
const val QUESTION_LINKS_UNAVAILABLE_NOTE: String =
    "No se han podido consultar los exámenes de esta pregunta. Vuelve a intentarlo."

/**
 * Diálogo «Vincular a examen»: cero candidatos es un hecho real —esta
 * especialidad/tipo no tiene examen todavía—, no un fallo. Se dice así en vez
 * de enseñar un selector vacío que parece roto.
 */
const val QUESTION_LINKABLE_EXAMS_EMPTY_NOTE: String = "No hay exámenes compatibles disponibles."

/** Igual que [QUESTION_LINKS_UNAVAILABLE_NOTE], para el catálogo de exámenes del selector. */
const val QUESTION_LINKABLE_EXAMS_UNAVAILABLE_NOTE: String =
    "No se han podido consultar los exámenes disponibles. Vuelve a intentarlo."

/** Lo que pierde el examen si se quita la pregunta, dicho antes de confirmar. */
const val QUESTION_UNLINK_WARNING: String =
    "La pregunta seguirá en el banco; sólo desaparece de este examen y las demás se recolocan."

/** Y si el examen está publicado, el cambio se ve al instante y no se puede dejar vacío (Q4). */
const val QUESTION_UNLINK_PUBLISHED_WARNING: String =
    "Este examen está publicado: los opositores dejarán de ver la pregunta al instante. Si fuera la " +
        "última pregunta activa, la base lo rechazará: despublica antes."

fun questionExamLinkPositionLabel(position: Int): String = "Posición $position"

/** `borrador` · `publicado` · `archivado`, tal y como los deriva el servidor (K3-4). */
fun questionExamLinkStatusLabel(status: String): String = when (status.lowercase()) {
    "publicado" -> "Publicado"
    "archivado" -> "Archivado"
    else -> "Borrador"
}

/**
 * Por qué NO se puede cambiar el tipo de esta pregunta, o `null` si se puede (KQ3-3).
 *
 * Con exámenes vinculados, el tipo de la pregunta está atado al del examen; con
 * recursos, a `inmersiva`. La base lo rechaza (`QUESTION_TYPE_LOCKED_BY_EXAMS`,
 * `QUESTION_TYPE_LOCKED_BY_RESOURCES`), y decirlo antes evita un borrador que sólo
 * puede fallar al guardar. Los recursos ARCHIVADOS no se ven desde aquí: ese caso
 * lo dice la base y se pinta al pie.
 */
fun questionTypeLockReason(state: QuestionsState): String? {
    if (state.isCreating) return null
    if (state.knownExamLinks().isNotEmpty()) return "Con exámenes vinculados no se puede cambiar el tipo. Quítala antes de esos exámenes."
    val detail = (state.detail as? UiAsync.Success)?.value ?: return null
    if (detail.type == QuestionType.INMERSIVA && detail.resources.isNotEmpty()) {
        return "Una pregunta inmersiva con recursos no puede cambiar de tipo. Quita antes sus recursos."
    }
    return null
}

// ---------------------------------------------------------------------------
// Recursos — sólo Inmersiva
// ---------------------------------------------------------------------------

/** Los recursos son de la pregunta, y sólo una Inmersiva puede apoyarse en ellos. */
fun questionShowsResources(type: QuestionType): Boolean = type == QuestionType.INMERSIVA

fun questionResourceTypeLabel(type: QuestionResourceType): String = when (type) {
    QuestionResourceType.IMAGE -> "Imagen"
    QuestionResourceType.AUDIO -> "Audio"
    QuestionResourceType.VIDEO -> "Vídeo"
    QuestionResourceType.TEXT -> "Texto"
    QuestionResourceType.DOCUMENT -> "Documento"
}

/**
 * Un recurso, como se puede enseñar.
 *
 * **Nunca sale `storagePath`.** Es una ruta interna del almacenamiento y no dice
 * nada a quien administra; lo que importa es si el recurso se puede abrir. Un
 * recurso de texto se sostiene con su propio cuerpo y no necesita URL.
 */
data class QuestionResourceRow(
    val title: String,
    val typeLabel: String,
    val accessible: Boolean,
    val accessLabel: String,
)

fun questionResourceRows(resources: List<QuestionResource>): List<QuestionResourceRow> =
    resources.sortedBy { it.sortOrder }.map { resource ->
        val inlineText = resource.type == QuestionResourceType.TEXT && !resource.textBody.isNullOrBlank()
        val accessible = inlineText || !resource.publicUrl.isNullOrBlank()
        QuestionResourceRow(
            title = resource.title.trim().ifBlank { "Recurso sin título" },
            typeLabel = questionResourceTypeLabel(resource.type),
            accessible = accessible,
            accessLabel = when {
                inlineText -> "Texto incluido"
                accessible -> "Accesible"
                else -> "Sin dirección pública"
            },
        )
    }

const val QUESTION_RESOURCES_EMPTY: String = "Esta pregunta no tiene recursos vinculados."

/** P0-4: subir recursos no funciona, así que no se ofrece un botón que aparente que sí. */
const val QUESTION_RESOURCE_UPLOAD_BLOCKED_NOTE: String =
    "Subir o vincular recursos todavía no está disponible desde esta consola."

// ---------------------------------------------------------------------------
// Matriz de honestidad — la UI se pinta desde aquí
// ---------------------------------------------------------------------------

enum class QuestionCapabilityLevel { REAL, PARTIAL, BLOCKED }

data class QuestionCapability(
    val name: String,
    val level: QuestionCapabilityLevel,
    val note: String,
)

/**
 * Qué se puede hacer de verdad en esta pantalla.
 *
 * No es decorativo: las secciones consultan esto para decidir si un control se
 * ofrece, se ofrece con aviso, o se deja desactivado con su motivo. Si mañana
 * aparece una capacidad, cambia aquí y la UI la sigue.
 */
fun questionCapabilities(): List<QuestionCapability> = listOf(
    // KQ0-4: todo el banco se consulta en el servidor, paginado. Antes era
    // cliente sobre lo cargado, y «Buscar» no veía más allá de esa descarga.
    QuestionCapability("Buscar", QuestionCapabilityLevel.REAL, "En el servidor, paginado: por código y enunciado."),
    QuestionCapability("Filtrar por tipo", QuestionCapabilityLevel.REAL, "En el servidor, paginado."),
    QuestionCapability("Filtrar por estado", QuestionCapabilityLevel.REAL, "En el servidor, paginado: activas, inactivas o todas."),
    QuestionCapability("Filtrar por examen", QuestionCapabilityLevel.REAL, "En el servidor, paginado, en el orden del examen."),
    QuestionCapability("Filtrar por especialidad", QuestionCapabilityLevel.REAL, "En el servidor, paginado."),
    QuestionCapability("Filtrar por tema", QuestionCapabilityLevel.BLOCKED, "El contrato no tiene tema."),
    QuestionCapability("Crear", QuestionCapabilityLevel.PARTIAL, "Existe el endpoint; exige examen inicial y puede fallar (P0-5)."),
    QuestionCapability("Editar contenido", QuestionCapabilityLevel.REAL, "Enunciado, tipo, explicación, fuente y estado."),
    QuestionCapability("Editar respuestas", QuestionCapabilityLevel.PARTIAL, "Depende de una RPC que hoy puede rechazar (P0-5)."),
    // KQ0-7: archivar no sólo «marca inactiva»: la saca de sus exámenes y del recuento.
    QuestionCapability("Archivar", QuestionCapabilityLevel.REAL, "La quita de sus exámenes y del recuento."),
    QuestionCapability("Vincular a examen", QuestionCapabilityLevel.REAL, "Viaja en el mismo PATCH con posición."),
    QuestionCapability("Desvincular", QuestionCapabilityLevel.BLOCKED, "No existe en el contrato."),
    QuestionCapability("Ver recursos", QuestionCapabilityLevel.REAL, "Llegan en el detalle de la pregunta."),
    QuestionCapability("Subir recursos", QuestionCapabilityLevel.BLOCKED, "P0-4: la subida no funciona."),
)

fun questionCapability(name: String): QuestionCapability =
    questionCapabilities().firstOrNull { it.name == name }
        ?: error("Capacidad no declarada en la matriz: $name")
