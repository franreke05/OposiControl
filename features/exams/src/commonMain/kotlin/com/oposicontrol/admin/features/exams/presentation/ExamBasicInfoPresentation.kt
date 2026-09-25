package com.oposicontrol.admin.features.exams.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.ui.components.humanMessage
import com.oposicontrol.admin.domain.model.Community
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamStatus
import com.oposicontrol.admin.domain.model.Specialty

/**
 * Lo que «Información básica», la cabecera, el resumen y la barra de guardado
 * DECIDEN, sin pintar nada.
 *
 * Vive aparte de `ExamWorkspacePresentation.kt` por tamaño, no por concepto: las
 * dos son la capa pura del workspace y se prueban en el mismo test. Separarlas
 * evita que aquélla se convierta en un monolito de mil quinientas líneas (§62).
 *
 * Cada función de este fichero traslada una decisión de dominio vinculante (D2,
 * D4-D6, D8-D12) y existe como función para que la decisión se pueda comprobar
 * con un test —y, donde importa, con una mutación que lo ponga en rojo— en lugar
 * de mirando una captura.
 */

// ---------------------------------------------------------------------------
// Estado
// ---------------------------------------------------------------------------

/** El estado de un examen que todavía no existe. Es un estado real, no una ausencia. */
const val EXAM_UNSAVED_STATUS: String = "Sin guardar"

// ---------------------------------------------------------------------------
// Código (D2)
//
// Editable y opcional. Si llega vacío al CREAR, el servidor genera
// `EX-xxxxxxxx`; si llega vacío al EDITAR, se conserva el que había. Dos
// comportamientos del mismo hueco vacío, y cada sitio de la pantalla dice el
// suyo. Y en ninguno se enseña el identificador interno.
// ---------------------------------------------------------------------------

/** Lo que dice un código que todavía no existe. */
const val EXAM_CODE_GENERATED: String = "Se genera solo"

/** Placeholder del campo Código. */
fun examCodePlaceholder(state: ExamsState, exam: Exam?): String = when {
    state.selectedExamId == null -> EXAM_CODE_GENERATED
    else -> exam?.code?.trim()?.takeIf { it.isNotEmpty() }?.let { "Se conserva $it" } ?: "Sin código"
}

/**
 * Línea de apoyo del campo Código: dice CUÁNDO pasa lo que promete el placeholder,
 * y que el código tiene que ser único.
 *
 * Con [conflict] dice con qué examen choca, en el sitio donde se está escribiendo:
 * en una ventana sin columna de resumen, el aviso de «Antes de publicar» está
 * plegado en la franja y el botón de guardar deshabilitado no explica por qué.
 */
fun examCodeSupport(state: ExamsState, conflict: Exam? = null): String = when {
    conflict != null -> examCodeConflictMessage(conflict)
    state.selectedExamId == null -> "Opcional. Debe ser único. Si lo dejas en blanco, se crea uno al guardar."
    else -> "Debe ser único. Si lo dejas en blanco, se conserva el código actual."
}

/**
 * El OTRO examen que ya usa el código escrito en el editor, o `null` (P1-3).
 *
 * `codigo_examen` es `not null unique` (fixture SQL, `001_live_shape.sql:51`) y la
 * base no traduce el 23505: guardar un código repetido acababa en un 500 y en «El
 * servidor no está respondiendo bien». Ahora que el código se escribe a mano,
 * repetir uno es un flujo normal, y se detecta aquí, antes de enviar nada.
 *
 * - **Comparación**: el código recortado y sin distinguir mayúsculas. Una
 *   restricción `unique` sobre texto sí las distingue, pero «ex-2026-0001» y
 *   «EX-2026-0001» son el mismo código para quien los lee, y dejar pasar el
 *   segundo sería fabricar dos exámenes indistinguibles.
 * - **El propio examen no cuenta**: su código guardado no choca consigo mismo.
 * - **Vacío no choca**: en uno nuevo el servidor genera uno; en uno existente se
 *   conserva el suyo.
 * - **Fuente**: `catalogExams`, la lista SIN filtrar. Una búsqueda o un filtro de
 *   estado no pueden esconder el choque. Incluye los **archivados**: la lista
 *   sale de `GET /rest/v1/examenes?select=*` sin filtro de `activo`
 *   (`AdminV1Routes.kt:1982`), el repositorio los mapea a `ARCHIVED` y la
 *   restricción `unique` también los cubre.
 *
 * Firma fijada: el ViewModel la llama antes de guardar.
 */
internal fun examCodeConflict(state: ExamsState, exam: Exam?): Exam? {
    val code = state.editorCode.trim()
    if (code.isEmpty()) return null
    val ownId = exam?.id ?: state.selectedExamId
    return state.catalogExams.firstOrNull { other ->
        other.id != ownId && other.code.trim().equals(code, ignoreCase = true)
    }
}

/** Lo que dicen la comprobación y el campo cuando el código ya lo tiene [other]. */
internal fun examCodeConflictMessage(other: Exam): String =
    "Ya hay otro examen con el código ${other.code.trim()}."

/**
 * El código en la ficha del resumen.
 *
 * Lo escrito en el editor manda —es lo que se va a guardar—; si no hay nada
 * escrito, un examen nuevo dice «Se genera solo» (ausencia legítima, no un
 * valor) y uno existente dice su código guardado.
 */
fun examSummaryCode(state: ExamsState, exam: Exam?): ExamFieldLabel {
    val typed = state.editorCode.trim()
    val saved = exam?.code?.trim().orEmpty()
    return when {
        typed.isNotEmpty() -> ExamFieldLabel(typed, ExamFieldOrigin.VALUE)
        state.selectedExamId == null -> ExamFieldLabel(EXAM_CODE_GENERATED, ExamFieldOrigin.ABSENT)
        saved.isNotEmpty() -> ExamFieldLabel(saved, ExamFieldOrigin.VALUE)
        else -> ExamFieldLabel("Sin código", ExamFieldOrigin.ABSENT)
    }
}

/**
 * El código de la cabecera: sólo uno REAL, o nada.
 *
 * Un examen nuevo sin código escrito no pinta ninguno: el que genere el servidor
 * no existe todavía, y predecirlo sería inventarlo. Y nunca el identificador
 * interno del examen: lo que orienta a quien administra es `codigo_examen`.
 */
fun examHeaderCode(state: ExamsState, exam: Exam?): String? =
    state.editorCode.trim().ifBlank { exam?.code?.trim().orEmpty() }.takeIf { it.isNotEmpty() }

// ---------------------------------------------------------------------------
// Año (D6) y duración (D12)
// ---------------------------------------------------------------------------

/**
 * El año vacío, dicho igual en el placeholder del campo y en la ficha.
 *
 * El placeholder era «2026» en gris mientras la ficha decía «Sin año»: la misma
 * pantalla contradiciéndose. Un placeholder con forma de valor se lee como el
 * valor.
 */
const val EXAM_NO_YEAR: String = "Sin año"

fun examYearLabel(state: ExamsState): ExamFieldLabel =
    state.editorYear.trim().takeIf { it.isNotEmpty() }
        ?.let { ExamFieldLabel(it, ExamFieldOrigin.VALUE) }
        ?: ExamFieldLabel(EXAM_NO_YEAR, ExamFieldOrigin.ABSENT)

/**
 * La duración vacía.
 *
 * «Sin duración» y no «Sin límite de tiempo»: ninguna ruta aplica
 * `duracion_minutos` como límite y la app no la recibe, así que hablar de límite
 * sería afirmar un comportamiento que nadie ha verificado (D12).
 */
const val EXAM_NO_DURATION: String = "Sin duración"

fun examDurationLabel(state: ExamsState): ExamFieldLabel =
    durationLabel(state.editorDurationMinutes.trim().toIntOrNull())
        ?.let { ExamFieldLabel(it, ExamFieldOrigin.VALUE) }
        ?: ExamFieldLabel(EXAM_NO_DURATION, ExamFieldOrigin.ABSENT)

// ---------------------------------------------------------------------------
// Cabecera (D12)
// ---------------------------------------------------------------------------

/**
 * Las píldoras de metadatos de la cabecera, en orden: tipo, preguntas, duración
 * y, sólo si los hay, año y «Oficial». El estado va aparte porque se pinta como
 * `StatusChip`.
 *
 * **La comunidad no está aquí**: sale una sola vez, en el par etiqueta/valor de
 * la cabecera. Salía dos veces.
 *
 * En un examen nuevo sin tocar dan exactamente «Ráfaga · Sin preguntas
 * vinculadas · Sin duración»: los valores con los que el editor está de verdad.
 */
fun examHeaderChips(state: ExamsState, exam: Exam?): List<ExamFieldLabel> = buildList {
    add(ExamFieldLabel(ExamTypeOption.labelOf(state.editorTypeExam), ExamFieldOrigin.VALUE))
    add(linkedQuestionsLabel(state.questions, provisionalQuestionCount(state, exam)))
    add(examDurationLabel(state))
    examYearLabel(state).takeIf { it.isValue }?.let { year -> add(year.copy(text = "Año ${year.text}")) }
    if (state.editorIsOfficial) add(ExamFieldLabel("Oficial", ExamFieldOrigin.VALUE))
}

// ---------------------------------------------------------------------------
// Selectores de catálogo (D4, D5)
// ---------------------------------------------------------------------------

/** Qué clase de aviso lleva una celda de selector sin desplegable. */
enum class ExamPickerNoticeKind {
    /** Cargando: el campo inerte ya lo dice, no hace falta línea. */
    LOADING,

    /** Nada va mal; se explica por qué no hay nada que elegir. */
    INFO,

    /** Algo impide elegir. Icono de aviso. */
    PROBLEM,
}

/** El aviso compacto bajo el campo inerte de un selector que no puede desplegarse. */
data class ExamPickerNoticeText(
    val message: String,
    val kind: ExamPickerNoticeKind,
    /** Si «Reintentar» —`RefreshAll`— puede cambiar algo. */
    val canRetry: Boolean,
)

/**
 * El aviso del selector de especialidad cuando no hay catálogo que desplegar (D4).
 *
 * Existente: el examen conserva su especialidad —`especialidad_id` es `NOT
 * NULL`— aunque no se pueda enseñar la lista. Nuevo: sin catálogo no hay forma de
 * elegir una, y sin una no se puede crear; se dice así en vez de callarlo.
 */
fun specialtyPickerNotice(specialties: UiAsync<List<Specialty>>, isNew: Boolean): ExamPickerNoticeText? =
    when (specialties) {
        is UiAsync.Success -> null
        is UiAsync.Error -> ExamPickerNoticeText(
            message = if (isNew) {
                "No se ha podido cargar el catálogo. Sin especialidad no se puede crear el examen."
            } else {
                "No se ha podido cargar el catálogo. El examen conserva su especialidad."
            },
            kind = ExamPickerNoticeKind.PROBLEM,
            canRetry = true,
        )
        UiAsync.Loading, UiAsync.Idle ->
            ExamPickerNoticeText("Cargando especialidades…", ExamPickerNoticeKind.LOADING, canRetry = false)
        UiAsync.Empty -> ExamPickerNoticeText(
            message = if (isNew) {
                "Todavía no hay ninguna especialidad creada. Sin especialidad no se puede crear el examen."
            } else {
                "Todavía no hay ninguna especialidad creada."
            },
            kind = if (isNew) ExamPickerNoticeKind.PROBLEM else ExamPickerNoticeKind.INFO,
            canRetry = true,
        )
    }

/**
 * Lo que enseña el campo inerte del selector de especialidad.
 *
 * Lo mismo que [specialtyLabel] —el nombre guardado si el examen lo trae—, salvo
 * en un borrador sin elegir: ahí «Sin especialidad» sería verdad, pero pintada en
 * el hueco del campo cuando el catálogo ha fallado se leería como la
 * CONSECUENCIA del fallo. El campo dice el estado del catálogo y la línea de
 * debajo, qué implica.
 */
fun specialtyPickerValue(state: ExamsState, exam: Exam?): ExamFieldLabel {
    val label = specialtyLabel(state.editorSpecialtyId, state.specialties, exam)
    if (label.origin != ExamFieldOrigin.ABSENT) return label
    return when (state.specialties) {
        is UiAsync.Error -> ExamFieldLabel("No disponible", ExamFieldOrigin.UNAVAILABLE)
        UiAsync.Loading, UiAsync.Idle -> ExamFieldLabel("Cargando…", ExamFieldOrigin.PENDING)
        UiAsync.Empty -> ExamFieldLabel("No hay especialidades", ExamFieldOrigin.ABSENT)
        is UiAsync.Success -> label
    }
}

/**
 * El aviso del selector de comunidad cuando no hay catálogo que desplegar (D5).
 *
 * **«Catálogo caído» no es «Nacional».** Con una comunidad guardada el aviso dice
 * que se conserva —y el campo no se puede desplegar, para que un fallo de lectura
 * no se convierta en una escritura—; sólo SIN comunidad guardada dice que el
 * examen es nacional, porque ahí sí lo es: `comunidad_id` nulo es nacional, lo
 * sepa o no el catálogo. Antes el texto de ese caso atribuía el fallo a una
 * migración no aplicada, una causa que nadie había verificado.
 */
fun communityPickerNotice(
    selectedId: String?,
    communities: UiAsync<List<Community>>,
    isNew: Boolean,
): ExamPickerNoticeText? = when (communities) {
    is UiAsync.Success -> null
    is UiAsync.Error -> ExamPickerNoticeText(
        message = when {
            selectedId != null ->
                "No se ha podido cargar el catálogo. El examen conserva la comunidad que tenga guardada."
            isNew -> "No se ha podido cargar el catálogo de comunidades. El examen se creará como nacional."
            else -> "No se ha podido cargar el catálogo de comunidades. Este examen es nacional."
        },
        kind = ExamPickerNoticeKind.PROBLEM,
        canRetry = true,
    )
    UiAsync.Loading, UiAsync.Idle ->
        ExamPickerNoticeText("Cargando comunidades…", ExamPickerNoticeKind.LOADING, canRetry = false)
    // `Empty` con un id guardado es un vínculo roto, no un examen nacional.
    UiAsync.Empty -> if (selectedId != null) {
        ExamPickerNoticeText(
            message = communityLabel(selectedId, communities).detail
                ?: "Este examen apunta a una comunidad que ya no está en el catálogo.",
            kind = ExamPickerNoticeKind.PROBLEM,
            canRetry = true,
        )
    } else {
        ExamPickerNoticeText(
            message = if (isNew) {
                "No hay comunidades en el catálogo. El examen se creará como nacional."
            } else {
                "No hay comunidades en el catálogo. Este examen es nacional."
            },
            kind = ExamPickerNoticeKind.INFO,
            canRetry = false,
        )
    }
}

// ---------------------------------------------------------------------------
// Compatibilidad del tipo (D10)
// ---------------------------------------------------------------------------

/** Las seis situaciones reales de la compatibilidad del tipo con la especialidad. */
enum class ExamTypeCompatibilityCase {
    NO_SPECIALTY,
    CHECKING,
    ALLOWED,
    NOT_ALLOWED,
    UNAVAILABLE,
    UNKNOWN_TYPE,
}

data class ExamTypeCompatibility(
    val case: ExamTypeCompatibilityCase,
    val label: String,
    val detail: String?,
    val status: ExamCheckStatus,
)

/**
 * Si el tipo del editor está permitido por su especialidad, y por qué no cuando no.
 *
 * Una fila de la tabla D10 por caso. Lo que corrige: la etiqueta de «no
 * disponible» —«No se han podido consultar…»— salía también MIENTRAS se
 * consultaba, y sin especialidad elegida hablaba de una especialidad que no hay.
 *
 * El tipo desconocido va primero porque bloquea sea cual sea la especialidad:
 * `saveExam` no guarda un tipo que no reconoce.
 */
fun examTypeCompatibility(state: ExamsState): ExamTypeCompatibility {
    val option = ExamTypeOption.fromWire(state.editorTypeExam)
        ?: return ExamTypeCompatibility(
            case = ExamTypeCompatibilityCase.UNKNOWN_TYPE,
            label = ExamTypeOption.labelOf(state.editorTypeExam),
            detail = "Elige Ráfaga, Razonado o Inmersivo para poder guardar.",
            status = ExamCheckStatus.BLOCKING,
        )
    if (state.editorSpecialtyId.isBlank()) {
        return ExamTypeCompatibility(
            case = ExamTypeCompatibilityCase.NO_SPECIALTY,
            label = "Selecciona una especialidad para comprobar qué tipos admite.",
            detail = null,
            status = ExamCheckStatus.UNKNOWN,
        )
    }
    return when (val capabilities = state.specialtyCapabilities) {
        UiAsync.Idle, UiAsync.Loading -> ExamTypeCompatibility(
            case = ExamTypeCompatibilityCase.CHECKING,
            label = "Comprobando los tipos que permite esta especialidad…",
            detail = null,
            status = ExamCheckStatus.UNKNOWN,
        )
        is UiAsync.Error, UiAsync.Empty -> ExamTypeCompatibility(
            case = ExamTypeCompatibilityCase.UNAVAILABLE,
            label = "No se han podido comprobar los tipos de esta especialidad.",
            detail = "Reintenta en Configuración. Hasta entonces no se puede guardar.",
            status = ExamCheckStatus.UNKNOWN,
        )
        is UiAsync.Success -> if (capabilities.value.allows(option.wireValue)) {
            ExamTypeCompatibility(
                case = ExamTypeCompatibilityCase.ALLOWED,
                label = "${option.label} está permitido en esta especialidad.",
                detail = "Esta especialidad usa la configuración por defecto: nadie ha decidido todavía sus tipos."
                    .takeIf { !capabilities.value.configured },
                status = ExamCheckStatus.OK,
            )
        } else {
            ExamTypeCompatibility(
                case = ExamTypeCompatibilityCase.NOT_ALLOWED,
                label = "${option.label} no está permitido en esta especialidad.",
                detail = capabilities.value.limitationMessage?.takeIf { it.isNotBlank() }
                    ?: "Cambia el tipo en Configuración o elige otra especialidad.",
                status = ExamCheckStatus.BLOCKING,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Preguntas vinculadas en las comprobaciones (D1)
// ---------------------------------------------------------------------------

/** Un borrador o archivado sin preguntas: publicar lo rechaza el servidor (K-DEC01). */
internal const val EXAM_EMPTY_QUESTIONS_DETAIL: String =
    "Sin preguntas activas no se puede publicar. Vincula al menos una desde el banco de Preguntas."

/** Un publicado que se ha quedado sin preguntas: no hay nada que servir. */
internal const val EXAM_PUBLISHED_EMPTY_QUESTIONS_DETAIL: String =
    "Está publicado sin preguntas activas: la app no tiene nada que servir. Vincula preguntas o despublícalo."

/**
 * La fila «Preguntas vinculadas» de las comprobaciones.
 *
 * **Bloqueo para publicar** en un examen guardado con la lista cargada y vacía
 * (DEC-EX-01 = A, K-DEC01): el servidor rechaza publicar un examen sin preguntas
 * activas con 409 `EXAM_PUBLISH_REQUIRES_QUESTIONS`, así que decirlo antes es
 * decir una regla real. Guardar sigue sin exigirlo —las preguntas se vinculan
 * DESPUÉS de crear el examen—, y por eso en un examen nuevo es sólo un aviso.
 *
 * **No usa la cifra provisional**: un «sin preguntas» se lee como un hecho, así
 * que mientras se cuenta la fila dice que se está contando.
 */
internal fun linkedQuestionsCheck(state: ExamsState, questionCount: Int?): ExamCheck {
    // Contando: se resuelve solo en un momento. No es un problema del examen y
    // no puede hacer aparecer «Antes de publicar» por sí solo (P2-6).
    val counting = state.selectedExamId != null &&
        (state.questions is UiAsync.Idle || state.questions is UiAsync.Loading)
    val emptyDetail = if (state.selectedExam()?.status == ExamStatus.PUBLISHED) {
        EXAM_PUBLISHED_EMPTY_QUESTIONS_DETAIL
    } else {
        EXAM_EMPTY_QUESTIONS_DETAIL
    }
    val (status, detail) = if (state.selectedExamId == null) {
        ExamCheckStatus.WARNING to "Todavía sin preguntas: se vinculan después de crear el examen."
    } else {
        when (val questions = state.questions) {
            UiAsync.Idle, UiAsync.Loading -> ExamCheckStatus.UNKNOWN to "Contando las preguntas de este examen…"
            is UiAsync.Error -> ExamCheckStatus.UNKNOWN to "No se ha podido contar las preguntas de este examen."
            UiAsync.Empty -> ExamCheckStatus.BLOCKING to emptyDetail
            is UiAsync.Success -> {
                val count = questionCount ?: questions.value.size
                if (count == 0) {
                    ExamCheckStatus.BLOCKING to emptyDetail
                } else {
                    ExamCheckStatus.OK to linkedQuestionsText(count)
                }
            }
        }
    }
    return ExamCheck(
        id = ExamCheckId.LINKED_QUESTIONS,
        label = "Preguntas vinculadas",
        status = status,
        detail = detail,
        transient = counting,
    )
}

// ---------------------------------------------------------------------------
// Barra de guardado (D8)
// ---------------------------------------------------------------------------

/** En qué situación está el formulario respecto al servidor. */
enum class ExamSaveBarMode {
    /** El examen todavía no existe. */
    NEW,

    /** Existe y lo que hay en el editor no es lo que está guardado. */
    DIRTY,

    /** Existe y el editor está exactamente como está guardado. */
    CLEAN,
}

/** Qué tono porta el icono o el punto de la barra. */
enum class ExamSaveBarTone { INFO, WARNING, DANGER, SUCCESS }

data class ExamSaveBarMessage(val text: String, val tone: ExamSaveBarTone)

data class ExamSaveBarStatus(
    val mode: ExamSaveBarMode,
    /** `null` es que la barra no dice nada. En [ExamSaveBarMode.CLEAN] sin resultado, siempre. */
    val message: ExamSaveBarMessage?,
    val primaryLabel: String,
    val canSave: Boolean,
    val canDiscard: Boolean,
    val saving: Boolean,
) {
    /** La forma destacada —relleno, icono, frase con peso— es sólo para cambios sin guardar. */
    val prominent: Boolean get() = mode == ExamSaveBarMode.DIRTY
}

const val EXAM_UNSAVED_CHANGES_MESSAGE: String = "Hay cambios sin guardar en este examen."

/**
 * Qué dice y qué ofrece la barra de guardado.
 *
 * La regla que no se puede romper: **nunca «Hay cambios sin guardar» si no los
 * hay**. Y su recíproca, que el código anterior sí rompía: tras un guardado con
 * éxito, un cambio nuevo tiene que volver a avisar. El resultado de la última
 * acción se quedaba en `Success` y la barra seguía diciendo «Cambios guardados.»
 * encima de un editor que ya no lo estaba.
 *
 * Prioridad: un error manda (explica por qué no se ha guardado); después, los
 * cambios pendientes; después, la línea de un examen nuevo; y sólo con el editor
 * limpio, el «guardado» de la última acción.
 */
fun examSaveBarStatus(state: ExamsState, exam: Exam?): ExamSaveBarStatus {
    val isNew = state.selectedExamId == null
    val dirty = !isNew && hasUnsavedExamChanges(state, exam)
    val mode = when {
        isNew -> ExamSaveBarMode.NEW
        dirty -> ExamSaveBarMode.DIRTY
        else -> ExamSaveBarMode.CLEAN
    }
    val action = state.actionState
    val message = when {
        action is UiAsync.Error -> ExamSaveBarMessage(action.error.humanMessage(), ExamSaveBarTone.DANGER)
        mode == ExamSaveBarMode.DIRTY -> ExamSaveBarMessage(EXAM_UNSAVED_CHANGES_MESSAGE, ExamSaveBarTone.WARNING)
        mode == ExamSaveBarMode.NEW ->
            ExamSaveBarMessage("Este examen todavía no existe. Se creará al guardar.", ExamSaveBarTone.INFO)
        action is UiAsync.Success -> ExamSaveBarMessage("Cambios guardados.", ExamSaveBarTone.SUCCESS)
        else -> null
    }
    // Las dos condiciones que `saveExam` exige. El resto (tipo permitido,
    // números) las resuelve el guardado con su mensaje humano.
    val requiredPresent = state.editorTitle.isNotBlank() && state.editorSpecialtyId.isNotBlank()
    // Un código que ya usa otro examen no se puede guardar (P1-3): el servidor lo
    // rechazaría con un error genérico. El porqué lo dicen el campo y la
    // comprobación «Código».
    val codeFree = examCodeConflict(state, exam) == null
    return ExamSaveBarStatus(
        mode = mode,
        message = message,
        primaryLabel = if (isNew) "Crear examen" else "Guardar cambios",
        canSave = (isNew || dirty) && requiredPresent && codeFree,
        canDiscard = dirty,
        saving = action is UiAsync.Loading,
    )
}

// ---------------------------------------------------------------------------
// El CAMPO culpable de un guardado rechazado
//
// El defecto que esto arregla: al crear un examen, el guardado fallaba y al pie
// del formulario aparecía una sola línea roja —«Alguno de los valores enviados no
// está permitido para este campo.»— que no decía QUÉ campo. Con cuatro pestañas y
// una quincena de campos, eso es un callejón sin salida: el administrador no
// puede saber dónde mirar.
//
// El backend ya devuelve un código de dominio (`ErrorDto.error`) junto al mensaje
// humano, y `NetworkResultAdapter` lo trae hasta `AppError.Validation.code`. Aquí
// se traduce ese código a UN campo concreto. La traducción vive en la capa pura y
// no en el composable para que se pueda probar con un test en vez de mirando una
// captura: es la misma regla que el resto de este fichero.
// ---------------------------------------------------------------------------

/**
 * Los códigos de dominio que esta pantalla sabe atribuir a un campo.
 *
 * Son copias literales de los identificadores que escribe
 * `backend/.../BackendErrors.kt`, y están como constantes con nombre para que el
 * `when` de [examFieldError] no lleve cadenas sueltas y el test compare contra lo
 * mismo que el código. No se muestran nunca: son identificadores de esquema.
 */
internal const val EXAM_ERROR_QUESTION_COUNT_INVALID: String = "EXAM_QUESTION_COUNT_INVALID"

/** Ver [EXAM_ERROR_QUESTION_COUNT_INVALID]. `codigo_examen` es `unique` en la base. */
internal const val EXAM_ERROR_CODE_DUPLICATE: String = "EXAM_CODE_DUPLICATE"

/**
 * Ver [EXAM_ERROR_QUESTION_COUNT_INVALID]. Un código vacío tras `trim` (K0-4): lo
 * rechaza el PATCH del servidor antes de escribir y, con M5, también el CHECK
 * `examenes_codigo_examen_no_vacio_chk` de la base, con el mismo código.
 */
internal const val EXAM_ERROR_CODE_REQUIRED: String = "EXAM_CODE_REQUIRED"

/**
 * El campo del editor al que un rechazo puede apuntar, con la pestaña en la que
 * vive.
 *
 * La pestaña es parte de la identidad del campo, no un dato aparte: un campo
 * señalado en una pestaña cerrada es un campo que no se ve, y es justo lo que
 * había que resolver.
 */
internal enum class ExamField(val tab: ExamWorkspaceTabId, val label: String) {
    /** «Código del examen», en Información básica. */
    CODE(ExamWorkspaceTabId.BASICA, "Código del examen"),

    /** «Número de preguntas», en Configuración. De sólo lectura: se deriva (D1). */
    QUESTION_COUNT(ExamWorkspaceTabId.CONFIGURACION, "Número de preguntas"),
}

/** De dónde viene la acusación. Cambia si hay que MOVER al usuario o sólo marcar. */
internal enum class ExamFieldErrorSource {
    /**
     * Lo ha detectado el propio editor sobre lo que hay escrito ahora mismo, sin
     * enviar nada: [examCodeConflict]. Marca el campo, pero no mueve a nadie —el
     * usuario puede estar en otra pestaña sin haber intentado guardar todavía, y
     * arrastrarlo a Información básica por algo que él mismo está escribiendo
     * sería quitarle el control de la pantalla.
     */
    EDITOR,

    /**
     * El guardado se ha intentado y se ha rechazado. Aquí sí se mueve: el error
     * es la respuesta a una acción que el usuario acaba de pedir.
     */
    SAVE_REJECTED,
}

/** El campo señalado, con el texto que va debajo de él. */
internal data class ExamFieldError(
    val field: ExamField,
    /** El mensaje que se pinta bajo el campo. Lo redacta el backend; aquí no se reescribe. */
    val message: String,
    val source: ExamFieldErrorSource,
)

/**
 * Qué campo del editor está mal, o `null`.
 *
 * Dos fuentes, en este orden:
 *
 *  1. **El rechazo del último guardado** (`actionState`), cuando trae un código de
 *     dominio que esta pantalla sabe atribuir. Manda sobre la otra porque es la
 *     razón por la que el examen NO está guardado, y su mensaje lo ha redactado
 *     quien conoce la restricción.
 *  2. **El conflicto de código que el editor ya detectaba** ([examCodeConflict]).
 *     No se duplica la detección: se reutiliza la función que ya bloquea el botón
 *     de guardar y que ya escribe el apoyo del campo. Lo único que se añade es que
 *     ahora ese campo también se pinta en rojo, en vez de decirlo en gris.
 *
 * Un código que no está en el mapa devuelve `null` a propósito: entonces el error
 * no se atribuye a ningún campo y la línea al pie sigue siendo la señal, que es
 * exactamente lo que era antes. Inventar un campo sería peor que no señalar
 * ninguno.
 *
 * **`EXAM_HAS_USER_DATA` es el caso que hace de esa regla algo más que una
 * cautela.** Es un rechazo de negocio con código, como los dos de arriba, y aun
 * así no tiene campo culpable: el examen está bien guardado y lo que no se puede
 * es BORRARLO. Se presenta como aviso de la acción y no como error de campo — ver
 * `examDeletionNotice` en `ExamPublicationPresentation.kt`, que además explica por
 * qué no vale ni este mecanismo ni la barra de guardado.
 *
 * El mensaje al pie no desaparece en ningún caso: [examSaveBarStatus] sigue
 * dándole prioridad al error. Esto añade una segunda señal, no la sustituye.
 */
internal fun examFieldError(state: ExamsState): ExamFieldError? {
    val rejection = (state.actionState as? UiAsync.Error)?.error as? AppError.Validation
    // El código se NORMALIZA antes de comparar, y no es higiene: el backend lo
    // publica en minúsculas —`ErrorDto(error = cause.errorCode.lowercase())` en
    // `Application.kt`— y estas constantes son copia literal de los
    // identificadores en MAYÚSCULAS de `BackendErrors.kt`. Sin normalizar, la
    // atribución de campo no se disparaba NUNCA contra el servidor real: sólo en
    // los tests, que construían el `AppError` a mano con la constante. El defecto
    // sobrevivió porque las dos mitades estaban bien y sólo la costura estaba mal.
    val rejectedField = when (rejection?.code?.uppercase()) {
        EXAM_ERROR_QUESTION_COUNT_INVALID -> ExamField.QUESTION_COUNT
        EXAM_ERROR_CODE_DUPLICATE, EXAM_ERROR_CODE_REQUIRED -> ExamField.CODE
        else -> null
    }
    if (rejection != null && rejectedField != null) {
        return ExamFieldError(rejectedField, rejection.humanMessage(), ExamFieldErrorSource.SAVE_REJECTED)
    }
    return examCodeConflict(state, state.selectedExam())?.let {
        ExamFieldError(ExamField.CODE, examCodeConflictMessage(it), ExamFieldErrorSource.EDITOR)
    }
}

/**
 * La pestaña que hay que ABRIR para que el campo señalado se vea, o `null`.
 *
 * **Por qué seleccionar la pestaña y no ponerle una insignia.** Se barajaron las
 * dos. Una insignia en la pestaña dice «algo va mal ahí dentro» y deja al
 * administrador buscando el campo entre los de esa pestaña; el objetivo era
 * justo lo contrario, que no tenga que buscar. Y la señal PERSISTENTE que una
 * insignia aportaría ya existe y no necesita a las pestañas: `ExamSaveBar` vive
 * fuera del área que desplaza y fuera del conmutador, así que el mensaje al pie
 * está a la vista en cualquier pestaña, siempre. Seleccionar la pestaña añade lo
 * que faltaba —poner el campo culpable delante— sin un componente nuevo en el
 * sistema de diseño.
 *
 * **Por qué no molesta.** Sólo devuelve una pestaña para
 * [ExamFieldErrorSource.SAVE_REJECTED]: el instante siguiente a pulsar «Guardar»,
 * cuando nadie está escribiendo y el usuario está esperando precisamente la
 * respuesta a eso. El conflicto de código que el editor detecta al teclear
 * ([ExamFieldErrorSource.EDITOR]) marca el campo pero NO mueve la pestaña — si lo
 * hiciera, escribir un código repetido y pasar a Configuración devolvería al
 * usuario a Información básica una y otra vez.
 *
 * Es una función pura para que el test diga a qué pestaña se va sin renderizar
 * nada; quien la consume la usa como clave de un `LaunchedEffect`, así que una
 * pestaña elegida a mano después no se pisa.
 */
internal fun examFieldErrorTab(state: ExamsState): ExamWorkspaceTabId? =
    examFieldError(state)
        ?.takeIf { it.source == ExamFieldErrorSource.SAVE_REJECTED }
        ?.field
        ?.tab

// ---------------------------------------------------------------------------
// Especialidad sin elegir (P2-15) y duración cero (P2-7)
// ---------------------------------------------------------------------------

/**
 * Qué le falta a un examen sin especialidad, dicho de forma que se pueda hacer.
 *
 * «Selecciona una especialidad» es una orden, y con el catálogo caído no se puede
 * cumplir: el selector no tiene nada que desplegar. Ahí se dice qué hace falta
 * para poder elegirla. Con el catálogo cargado pero vacío tampoco hay nada que
 * elegir, y se dice con las palabras del aviso del selector (D4).
 */
internal fun examSpecialtyMissingDetail(specialties: UiAsync<List<Specialty>>): String = when (specialties) {
    is UiAsync.Error -> "No se puede elegir especialidad hasta que cargue el catálogo."
    UiAsync.Empty -> EXAM_NO_SPECIALTIES_CREATED
    is UiAsync.Success -> if (specialties.value.isEmpty()) EXAM_NO_SPECIALTIES_CREATED else EXAM_SELECT_SPECIALTY
    UiAsync.Idle, UiAsync.Loading -> EXAM_SELECT_SPECIALTY
}

private const val EXAM_SELECT_SPECIALTY = "Selecciona una especialidad."
private const val EXAM_NO_SPECIALTIES_CREATED = "Todavía no hay ninguna especialidad creada."

/** El aviso de una duración de 0 minutos (P2-7). */
const val EXAM_ZERO_DURATION_WARNING: String =
    "Una duración de 0 minutos no se puede cumplir: déjala vacía para no fijar duración."

/** Si el editor va a guardar 0 minutos: «0», «00» y «0000» son el mismo cero. */
internal fun examDurationIsZero(state: ExamsState): Boolean =
    state.editorDurationMinutes.trim().toIntOrNull() == 0

// ---------------------------------------------------------------------------
// Pendientes: tarjeta «Antes de publicar» y franja plegada (P0-2, P1-1, P2-6)
// ---------------------------------------------------------------------------

/** El título de lo pendiente. El mismo en la tarjeta del resumen y en la franja (D9). */
const val EXAM_PENDING_TITLE: String = "Antes de publicar"

/**
 * Las comprobaciones que la tarjeta «Antes de publicar» enseña.
 *
 * Todo lo que no está bien, menos el tipo —tiene su propia tarjeta— y menos lo
 * TRANSITORIO ([ExamCheck.transient]): «contando preguntas» se resuelve solo en un
 * segundo, y cuando hacía aparecer la tarjeta por sí solo, el resumen saltaba
 * ~190dp en cada apertura y en cada guardado (P2-6). Si al terminar de contar
 * resulta que no hay preguntas, eso sí es un hallazgo y la fila aparece.
 */
internal fun examPublishPendingChecks(checks: List<ExamCheck>): List<ExamCheck> =
    checks.filter { it.id != ExamCheckId.TYPE_ALLOWED && it.status != ExamCheckStatus.OK && !it.transient }

/**
 * La franja de resumen plegada: UNA línea de texto, siempre del mismo alto.
 *
 * [status] es el que porta el punto de color —el peor de lo pendiente—, o `null`
 * mientras sólo se está comprobando. [details] es lo que abre «Ver detalle»; vacía,
 * la franja no ofrece detalle.
 */
internal data class ExamSummaryStripLine(
    val text: String,
    val status: ExamCheckStatus?,
    val details: List<ExamCheck>,
)

/**
 * Qué dice la franja de resumen cuando no hay columna de resumen (P0-2, P1-1).
 *
 * La franja se desplegaba con TODAS las comprobaciones encima del marco del
 * editor, y el marco se quedaba con lo que sobrara: en 1366×768 el formulario de
 * un examen nuevo medía 0 px, y en uno existente pasaba de 181 a 80 px con la
 * primera tecla, porque «Cambios sin guardar» añadía una fila. Ahora la franja es
 * una sola línea que nombra lo pendiente; el detalle se abre encima, sin empujar.
 *
 * Mismas reglas que la tarjeta del resumen, con una diferencia: aquí sí entra el
 * tipo, porque en este régimen no hay tarjeta de compatibilidad al lado.
 */
internal fun examSummaryStripLine(checks: List<ExamCheck>): ExamSummaryStripLine {
    val problems = checks.filter { it.status != ExamCheckStatus.OK }
    val firm = problems.filterNot { it.transient }
    return when {
        firm.isNotEmpty() -> ExamSummaryStripLine(
            text = "$EXAM_PENDING_TITLE: ${spanishList(firm.map { examCheckShortName(it.id) })}.",
            status = firm.minBy { examCheckSeverityRank(it.status) }.status,
            details = firm,
        )
        // Sólo se está contando o comprobando: ni «todo bien» —no se sabe— ni una
        // lista de problemas que dentro de un segundo no estará.
        problems.isNotEmpty() -> ExamSummaryStripLine("Comprobando el examen…", status = null, details = emptyList())
        else -> ExamSummaryStripLine(
            text = "Sin nada pendiente: el examen se puede guardar tal y como está.",
            status = ExamCheckStatus.OK,
            details = emptyList(),
        )
    }
}

/** El nombre corto de una comprobación, para la línea de la franja. */
internal fun examCheckShortName(id: ExamCheckId): String = when (id) {
    ExamCheckId.TITLE -> "título"
    ExamCheckId.CODE -> "código"
    ExamCheckId.SPECIALTY -> "especialidad"
    ExamCheckId.TYPE_ALLOWED -> "tipo"
    ExamCheckId.LINKED_QUESTIONS -> "preguntas"
    ExamCheckId.DURATION -> "duración"
    ExamCheckId.ACCESS_PRICE -> "precio"
    ExamCheckId.UNSAVED_CHANGES -> "cambios sin guardar"
}

/** Bloquear pesa más que avisar, y avisar más que no saber. */
private fun examCheckSeverityRank(status: ExamCheckStatus): Int = when (status) {
    ExamCheckStatus.BLOCKING -> 0
    ExamCheckStatus.WARNING -> 1
    ExamCheckStatus.UNKNOWN -> 2
    ExamCheckStatus.OK -> 3
}

/** «a», «a y b», «a, b y c». */
private fun spanishList(items: List<String>): String = when (items.size) {
    0 -> ""
    1 -> items.single()
    else -> items.dropLast(1).joinToString(", ") + " y " + items.last()
}

// ---------------------------------------------------------------------------
// Recuento en las tarjetas del catálogo (P2-8)
// ---------------------------------------------------------------------------

/**
 * El recuento de la línea de meta de una tarjeta del catálogo.
 *
 * - **El examen abierto**: la misma regla que la cabecera (D1), porque las dos
 *   están en pantalla a la vez.
 * - **Uno cerrado**: el recuento de esta sesión si lo hay (`countedQuestions`) y,
 *   si no, el `total_preguntas` servido. Los dos son un dato (K2-2): desde M1 la
 *   base mantiene la columna al vincular, quitar o archivar preguntas. Antes
 *   ningún escritor la mantenía y el servido llevaba la marca «≈» de no
 *   confirmado; la marca ya no dice nada cierto y se retira.
 */
internal fun examCatalogQuestionsLabel(state: ExamsState, exam: Exam): String = when {
    exam.id == state.selectedExamId ->
        linkedQuestionsShortLabel(state.questions, provisionalQuestionCount(state, exam))
    else -> linkedQuestionsShortText(state.countedQuestions[exam.id] ?: exam.questionCount)
}

// ---------------------------------------------------------------------------
// Tarjeta «Información» (D11)
// ---------------------------------------------------------------------------

/**
 * Las cuatro líneas de la tarjeta «Información» del resumen.
 *
 * Sin deícticos —«esta pestaña», «su pestaña»—: la tarjeta vive en el resumen, a
 * la derecha de cualquier pestaña, y «esta» no señala nada. Y prudentes con lo
 * que hoy no funciona: los recursos no se gestionan desde Recursos (sólo se
 * ven), y crear o editar preguntas todavía no funciona, así que no se promete.
 */
val examInformationLines: List<String> = listOf(
    "El tipo de examen lo limitan las capacidades de la especialidad.",
    "Los recursos pertenecen a cada pregunta, no al examen: se gestionan desde el banco de Preguntas.",
    "La portada se asigna sola según el tipo y la comunidad del examen: no se sube ni se guarda.",
    "Las preguntas de este examen se gestionan desde el banco de Preguntas. Crear o editar preguntas todavía no funciona.",
)

// ---------------------------------------------------------------------------
// Tipos sin especialidad (D15)
// ---------------------------------------------------------------------------

/**
 * La nota de los tipos mientras no hay capacidades que enseñar.
 *
 * `Idle` significa que no hay especialidad elegida —el ViewModel sólo pide
 * capacidades cuando hay una—, y ahí «Comprobando…» era falso: no se estaba
 * comprobando nada. Con el borrador nuevo sin especialidad preseleccionada (D15)
 * es el caso normal al abrir «Nuevo examen».
 */
internal fun pendingCapabilitiesNote(
    state: UiAsync<com.oposicontrol.admin.domain.model.SpecialtyCapabilities>,
): String = if (state is UiAsync.Idle) {
    "Selecciona una especialidad para comprobar qué tipos admite."
} else {
    "Comprobando qué modos permite esta especialidad…"
}
