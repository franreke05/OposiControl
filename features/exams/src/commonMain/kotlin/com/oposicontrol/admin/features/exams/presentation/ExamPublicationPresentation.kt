package com.oposicontrol.admin.features.exams.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.humanMessage
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamStatus

/**
 * Lo que la pestaña Publicación DECIDE, sin pintar nada.
 *
 * Cuatro zonas que no se mezclan: **estado actual** (qué es hoy el examen para el
 * catálogo), **avisos** (orientación de la consola antes de actuar), **acciones
 * inmediatas** (se aplican al momento, sin «Guardar cambios») y **resultado** (qué
 * ha pasado con la última).
 *
 * Lo que es real y está verificado contra el backend (`AdminV1Routes`):
 *
 *  - Publicar: `publicado_en = ahora` y `activo = true`. Por eso publicar un examen
 *    archivado lo REACTIVA publicado: es la única forma de recuperarlo.
 *  - Despublicar: `publicado_en = null`. Vuelve a borrador.
 *  - Archivar: `activo = false` y `publicado_en = null`. No borra preguntas.
 *  - Publicar exige AL MENOS UNA pregunta activa vinculada (DEC-EX-01 = A,
 *    K-DEC01): sin ella el servidor responde 409 `EXAM_PUBLISH_REQUIRES_QUESTIONS`
 *    y no toca nada. Es lo único que exige; el resto de avisos son orientación.
 *
 * Lo que NO existe y aquí no aparece: desarchivar a borrador (no hay ruta),
 * publicación programada (`publishFrom` no está en el escritorio), estado «En
 * revisión» y vista previa del catálogo para un borrador.
 */

// ---------------------------------------------------------------------------
// Acciones inmediatas
// ---------------------------------------------------------------------------

enum class ExamPublicationAction {
    PUBLISH,
    UNPUBLISH,

    /** Reversible: retira del catálogo (`POST .../archivar`). */
    ARCHIVE,

    /** **Irreversible**: borra la fila (`DELETE .../examenes/{id}`). */
    DELETE,
}

/**
 * Las acciones que existen para cada estado, en el orden en que se ofrecen.
 *
 * Un archivado no ofrece «Archivar» (ya lo está) ni «Despublicar» (no está
 * publicado): «Publicar ahora» es lo único que lo recupera.
 *
 * **Borrar se ofrece en los tres**, y en el archivado especialmente: un examen
 * archivado es precisamente el que alguien ya quiso quitar de en medio, y hasta
 * ahora se quedaba en la base para siempre porque la única acción que existía
 * —la que decía «Eliminar»— era la que lo había archivado.
 *
 * Va SIEMPRE en último lugar. Quien paint esto lo separa además del resto (ver
 * `ExamPublicationSection`): el orden es la primera defensa contra pulsar lo
 * irreversible por inercia.
 */
fun examPublicationActions(status: ExamStatus?): List<ExamPublicationAction> = when (status) {
    null -> emptyList()
    ExamStatus.DRAFT -> listOf(
        ExamPublicationAction.PUBLISH,
        ExamPublicationAction.ARCHIVE,
        ExamPublicationAction.DELETE,
    )
    ExamStatus.PUBLISHED -> listOf(
        ExamPublicationAction.UNPUBLISH,
        ExamPublicationAction.ARCHIVE,
        ExamPublicationAction.DELETE,
    )
    ExamStatus.ARCHIVED -> listOf(ExamPublicationAction.PUBLISH, ExamPublicationAction.DELETE)
}

/**
 * La etiqueta del botón irreversible. «Eliminar» a secas es lo que ponía el botón
 * que archivaba, así que el adverbio no es adorno: distingue esta acción de la
 * que había antes con ese nombre.
 */
const val EXAM_DELETE_ACTION_LABEL: String = "Eliminar definitivamente"

/** Etiqueta del botón y lo que hace, dicho al lado del botón. */
data class ExamPublicationActionCopy(val label: String, val explanation: String)

/** Por qué «Publicar ahora» está apagado: la regla del servidor, dicha al lado del botón. */
const val EXAM_PUBLISH_REQUIRES_QUESTIONS_EXPLANATION: String =
    "No se puede publicar un examen sin preguntas activas. Vincula al menos una desde el banco de Preguntas."

/**
 * Si publicar está vetado porque el examen guardado no tiene preguntas (K-DEC01).
 *
 * Sólo con la lista CARGADA y vacía: mientras se cuenta, o si no se ha podido
 * contar, no se sabe, y el botón sigue disponible — el servidor es la guarda final
 * y, si la lista estaba vacía, responde con su 409 legible. Apagar el botón sin
 * saberlo sería inventarse una regla.
 */
fun examPublishBlockedByQuestions(state: ExamsState): Boolean =
    state.selectedExamId != null && when (val questions = state.questions) {
        UiAsync.Empty -> true
        is UiAsync.Success -> questions.value.isEmpty()
        else -> false
    }

/**
 * [questionsMissing] cambia la explicación de «Publicar ahora» por la regla que lo
 * impide ([EXAM_PUBLISH_REQUIRES_QUESTIONS_EXPLANATION]); la etiqueta no cambia,
 * para que el botón apagado siga diciendo qué acción es.
 */
fun examPublicationActionCopy(
    action: ExamPublicationAction,
    status: ExamStatus?,
    questionsMissing: Boolean = false,
): ExamPublicationActionCopy =
    when (action) {
        ExamPublicationAction.PUBLISH -> if (questionsMissing) {
            ExamPublicationActionCopy(label = "Publicar ahora", explanation = EXAM_PUBLISH_REQUIRES_QUESTIONS_EXPLANATION)
        } else if (status == ExamStatus.ARCHIVED) {
            ExamPublicationActionCopy(
                label = "Publicar ahora",
                explanation = "Lo reactiva y lo publica a la vez: un examen archivado no puede volver a borrador.",
            )
        } else {
            ExamPublicationActionCopy(
                label = "Publicar ahora",
                explanation = "Aparece al momento en el catálogo de la app, con la versión guardada.",
            )
        }
        ExamPublicationAction.UNPUBLISH -> ExamPublicationActionCopy(
            label = "Despublicar",
            explanation = "Deja de aparecer en el catálogo y vuelve a borrador. Sus preguntas no cambian.",
        )
        ExamPublicationAction.ARCHIVE -> ExamPublicationActionCopy(
            label = "Archivar",
            explanation = "Lo retira del catálogo sin borrar sus preguntas. Después sólo se recupera publicándolo.",
        )
        // Las dos mitades de la verdad, las dos al lado del botón: que no hay
        // vuelta atrás, y que el servidor puede negarse. Lo segundo importa
        // tanto como lo primero — un examen que la gente ya ha hecho no se
        // borra, y enterarse al pulsar es peor que saberlo antes.
        ExamPublicationAction.DELETE -> ExamPublicationActionCopy(
            label = EXAM_DELETE_ACTION_LABEL,
            explanation = "Lo borra del servidor: no se puede deshacer. " +
                "Si los usuarios ya lo han usado, no se borrará y se te dirá por qué.",
        )
    }

// ---------------------------------------------------------------------------
// Los dos avisos de confirmación
//
// Viven aquí, en la capa pura, y no como literales dentro del ViewModel: son
// TEXTO DE PRODUCTO sobre lo que cada acción hace de verdad, y el test los
// compara contra lo mismo que lee la pantalla. La versión anterior del de
// archivar ya se había escrito dos veces con dos promesas distintas.
// ---------------------------------------------------------------------------

/** Archivar: reversible, y no toca las preguntas. */
const val EXAM_ARCHIVE_WARNING: String =
    "Este examen dejará de estar disponible para los usuarios. Las preguntas asociadas no se eliminan, " +
        "y puedes recuperarlo publicándolo de nuevo."

/**
 * Borrar: irreversible, y dice QUÉ se lleva y qué NO.
 *
 * Lo que no se lleva es el banco de preguntas, y hay que decirlo porque el aviso
 * antiguo de la ráfaga prometía «eliminar esta ráfaga y sus preguntas»:
 * `preguntas_examen` es una M:N y lo que se borra es la asociación, así que una
 * pregunta compartida sigue existiendo en los demás exámenes y en el banco.
 */
const val EXAM_DELETE_FOREVER_WARNING: String =
    "El examen se borra del servidor y no se puede deshacer: desaparece del catálogo, de las listas y de " +
        "los informes. Sus preguntas se quedan en el banco, sólo dejan de estar asociadas a este examen. " +
        "Si algún usuario lo ha intentado, comprado o valorado, el servidor no lo borrará."

/** Lo que dice la zona de acciones de un examen que todavía no existe. */
const val EXAM_PUBLICATION_NEEDS_SAVE: String = "Guarda el examen para poder publicarlo."

/** La publicación programada no existe: la fecha la fija el servidor al publicar. */
const val EXAM_NO_SCHEDULED_PUBLICATION: String =
    "La fecha de publicación la pone el servidor al publicar. No hay publicación programada."

/** Si hay una acción en curso —publicar/despublicar o un guardado—: no se lanza otra encima. */
fun examPublicationBusy(state: ExamsState): Boolean =
    state.lifecycleActionState is UiAsync.Loading || state.actionState is UiAsync.Loading

/** El diálogo de confirmación de una acción inmediata. */
data class ExamPublicationConfirmation(
    val title: String,
    val message: String,
    val confirmLabel: String,
)

/**
 * La confirmación de publicar o despublicar. `null` para las dos destructivas
 * —archivar y borrar—: ésas las ponen `RequestArchiveSelectedExam` y
 * `RequestDeleteSelectedExam` con el diálogo del ViewModel, cuyos textos son
 * [EXAM_ARCHIVE_WARNING] y [EXAM_DELETE_FOREVER_WARNING].
 *
 * El botón de confirmar NO repite la etiqueta del botón que abrió el diálogo:
 * «Publicar ahora» y «Publicar» son dos pasos distintos y así se distinguen.
 *
 * Si hay cambios sin guardar, se dice que se publica la versión guardada; si hay
 * avisos en «Antes de publicar», que son orientación y que lo único que el
 * servidor exige es tener preguntas (K-DEC01). Con la lista cargada y vacía la
 * pantalla no llega a abrir este diálogo ([examPublishBlockedByQuestions]).
 */
fun examPublicationConfirmation(
    action: ExamPublicationAction,
    state: ExamsState,
    exam: Exam?,
    questionCount: Int?,
): ExamPublicationConfirmation? {
    val savedVersion = if (hasUnsavedExamChanges(state, exam)) {
        " Se publica la versión guardada: los cambios sin guardar no se incluyen."
    } else {
        ""
    }
    val pending = if (examPublicationWarnings(state, exam, questionCount).checks.isNotEmpty()) {
        " Hay avisos en «$EXAM_PENDING_TITLE»: $EXAM_SERVER_PUBLISH_RULE"
    } else {
        ""
    }
    return when (action) {
        ExamPublicationAction.PUBLISH -> if (exam?.status == ExamStatus.ARCHIVED) {
            ExamPublicationConfirmation(
                title = "Reactivar y publicar",
                message = "El examen deja de estar archivado y aparece ahora en el catálogo de la app.$savedVersion$pending",
                confirmLabel = "Reactivar",
            )
        } else {
            ExamPublicationConfirmation(
                title = "Publicar examen",
                message = "El examen aparece ahora en el catálogo de la app.$savedVersion$pending",
                confirmLabel = "Publicar",
            )
        }
        ExamPublicationAction.UNPUBLISH -> ExamPublicationConfirmation(
            title = "Despublicar examen",
            message = "El examen deja de aparecer en el catálogo de la app y vuelve a borrador. " +
                "Puedes volver a publicarlo cuando quieras.",
            confirmLabel = "Despublicar",
        )
        ExamPublicationAction.ARCHIVE, ExamPublicationAction.DELETE -> null
    }
}

// ---------------------------------------------------------------------------
// Estado actual
// ---------------------------------------------------------------------------

/** Las tres fechas del examen, con su procedencia: «Sin publicar» no es una fecha. */
data class ExamPublicationFacts(
    val publishedAt: ExamFieldLabel,
    val createdAt: ExamFieldLabel,
    val updatedAt: ExamFieldLabel,
)

fun examPublicationFacts(exam: Exam): ExamPublicationFacts = ExamPublicationFacts(
    publishedAt = instantLabel(exam.publishedAt)
        ?.let { ExamFieldLabel(it, ExamFieldOrigin.VALUE) }
        ?: ExamFieldLabel("Sin publicar", ExamFieldOrigin.ABSENT),
    createdAt = ExamFieldLabel(instantLabel(exam.createdAt).orEmpty(), ExamFieldOrigin.VALUE),
    updatedAt = ExamFieldLabel(instantLabel(exam.updatedAt).orEmpty(), ExamFieldOrigin.VALUE),
)

// ---------------------------------------------------------------------------
// Avisos
// ---------------------------------------------------------------------------

/** Publicar no se lleva lo que hay en el editor. */
const val EXAM_PUBLISH_SAVED_VERSION_NOTE: String =
    "Publicar publica la versión guardada. Los cambios sin guardar no se incluyen: guárdalos antes si quieres que salgan."

/** En un examen ya publicado, guardar SÍ cambia lo que está publicado. */
const val EXAM_PUBLISHED_SAVE_NOTE: String =
    "El examen está publicado: al guardar, los cambios se aplican a la versión publicada."

/**
 * Lo único que el servidor exige para publicar (DEC-EX-01 = A, K-DEC01). Antes
 * no exigía nada y las notas lo decían; tras K-DEC01 esa frase ya no es verdad.
 */
const val EXAM_SERVER_PUBLISH_RULE: String =
    "para publicar, el servidor sólo exige que haya al menos una pregunta activa."

/** «Antes de publicar» es orientación, no validación (D9), salvo la regla de [EXAM_SERVER_PUBLISH_RULE]. */
const val EXAM_PUBLISH_ORIENTATION: String =
    "Es una orientación de la consola: $EXAM_SERVER_PUBLISH_RULE"

const val EXAM_PUBLISHED_ORIENTATION: String =
    "Es una orientación de la consola: no cambia lo que ya está publicado."

data class ExamPublicationWarnings(
    val title: String,
    val savedVersionNote: String?,
    val checks: List<ExamCheck>,
    val orientationNote: String?,
) {
    val isEmpty: Boolean get() = savedVersionNote == null && checks.isEmpty()
}

/**
 * Lo que conviene mirar antes de actuar.
 *
 * Las filas son las MISMAS que la tarjeta «Antes de publicar» del resumen
 * ([examPublishPendingChecks]), así que la pestaña y el rail no pueden discrepar.
 * «Cambios sin guardar» se sustituye por una nota que dice qué significa aquí:
 * publicar publica lo guardado; en un publicado, guardar cambia lo publicado.
 */
fun examPublicationWarnings(state: ExamsState, exam: Exam?, questionCount: Int?): ExamPublicationWarnings {
    val checks = examPublishPendingChecks(examChecks(state, exam, questionCount))
        .filter { it.id != ExamCheckId.UNSAVED_CHANGES }
    val dirty = exam != null && hasUnsavedExamChanges(state, exam)
    val published = exam?.status == ExamStatus.PUBLISHED
    return ExamPublicationWarnings(
        title = if (published) "Avisos" else EXAM_PENDING_TITLE,
        savedVersionNote = when {
            !dirty -> null
            published -> EXAM_PUBLISHED_SAVE_NOTE
            else -> EXAM_PUBLISH_SAVED_VERSION_NOTE
        },
        checks = checks,
        orientationNote = when {
            checks.isEmpty() -> null
            published -> EXAM_PUBLISHED_ORIENTATION
            else -> EXAM_PUBLISH_ORIENTATION
        },
    )
}

/**
 * Si «Antes de publicar» se pinta DENTRO de la pestaña.
 *
 * Sólo cuando el rail no está. Con la columna de resumen visible, la lista de
 * [examPublicationWarnings] y la tarjeta «Antes de publicar» del resumen son la
 * MISMA —las dos salen de [examPublishPendingChecks]— y quedaban una al lado de
 * la otra en la misma pantalla. En WIDE manda el rail, que es donde vive el
 * resumen del examen; la pestaña se queda con lo suyo: estado, acciones y
 * resultado.
 *
 * **No se pierde nada al ocultarla.** El rail enseña las mismas filas y además
 * «Cambios sin guardar», que aquí era una nota aparte; y la confirmación de
 * publicar ([examPublicationConfirmation]) sigue diciendo que se publica la
 * versión guardada y qué es lo único que el servidor exige ([EXAM_SERVER_PUBLISH_RULE]).
 */
fun examPublicationShowsChecklist(railVisible: Boolean): Boolean = !railVisible

// ---------------------------------------------------------------------------
// Resultado
// ---------------------------------------------------------------------------

enum class ExamPublicationResultKind { WORKING, SUCCESS, ERROR }

data class ExamPublicationResult(
    val kind: ExamPublicationResultKind,
    val title: String,
    val detail: String?,
)

/**
 * Qué ha pasado con la última acción de publicar o despublicar de ESTE examen.
 *
 * La acción se deduce del estado del examen, que es de lo que depende: mientras
 * carga, el estado es el de antes (un publicado se está despublicando); tras el
 * éxito, el nuevo; tras un fallo, el de antes. El error lleva el mensaje humano
 * existente ([humanMessage]). El ViewModel devuelve el resultado a `Idle` al
 * abrir otro examen, así que no se arrastra.
 */
fun examPublicationResult(state: ExamsState, exam: Exam?): ExamPublicationResult? {
    val status = exam?.status ?: return null
    return when (val lifecycle = state.lifecycleActionState) {
        UiAsync.Idle, UiAsync.Empty -> null
        UiAsync.Loading -> ExamPublicationResult(
            kind = ExamPublicationResultKind.WORKING,
            title = if (status == ExamStatus.PUBLISHED) "Despublicando…" else "Publicando…",
            detail = null,
        )
        is UiAsync.Success -> when (status) {
            ExamStatus.PUBLISHED -> ExamPublicationResult(
                kind = ExamPublicationResultKind.SUCCESS,
                title = "Examen publicado.",
                detail = "Ya aparece en el catálogo de la app.",
            )
            ExamStatus.DRAFT -> ExamPublicationResult(
                kind = ExamPublicationResultKind.SUCCESS,
                title = "Examen despublicado.",
                detail = "Ya no aparece en el catálogo de la app.",
            )
            // Publicar o despublicar no dejan un examen archivado.
            ExamStatus.ARCHIVED -> null
        }
        is UiAsync.Error -> ExamPublicationResult(
            kind = ExamPublicationResultKind.ERROR,
            title = if (status == ExamStatus.PUBLISHED) "No se ha podido despublicar." else "No se ha podido publicar.",
            detail = lifecycle.error.humanMessage(),
        )
    }
}

/** El color de la fila del resultado: el de las comprobaciones, ya medido en `ExamCheckContrastTest`. */
internal fun examPublicationResultStatus(kind: ExamPublicationResultKind): ExamCheckStatus = when (kind) {
    ExamPublicationResultKind.WORKING -> ExamCheckStatus.UNKNOWN
    ExamPublicationResultKind.SUCCESS -> ExamCheckStatus.OK
    ExamPublicationResultKind.ERROR -> ExamCheckStatus.BLOCKING
}

internal fun examPublicationResultIcon(kind: ExamPublicationResultKind): Pair<AppIcon, SemanticRole> = when (kind) {
    ExamPublicationResultKind.WORKING -> AppIcon.CLOCK to SemanticRole.NEUTRAL
    ExamPublicationResultKind.SUCCESS -> AppIcon.CHECK to SemanticRole.SUCCESS
    ExamPublicationResultKind.ERROR -> AppIcon.WARNING to SemanticRole.DANGER
}

// ---------------------------------------------------------------------------
// El aviso del BORRADO
//
// ### Por qué un aviso de la ACCIÓN y no un error de campo
//
// El backend responde al borrado bloqueado con `EXAM_HAS_USER_DATA`, y la
// tubería que trae ese código hasta la pantalla ya existía: `AppError.Validation`
// lo lleva en `code` y `examFieldError` (ExamBasicInfoPresentation) traduce un
// código a UN CAMPO del formulario para marcarlo en rojo y abrir su pestaña.
//
// Aquí no sirve, y no por falta de tubería: **no hay campo culpable**. El examen
// está perfectamente guardado y ningún valor del formulario está mal; lo que no se
// puede es borrarlo, y eso no se corrige escribiendo. Señalar un campo mandaría al
// administrador a arreglar algo que no está roto, y `examFieldError` devuelve
// `null` para este código a propósito — ver su KDoc y el caso
// `el rechazo por datos de usuario no senala ningun campo del formulario`.
//
// Tampoco vale la barra de guardado (`actionState`): habla de guardar, y aquí
// nadie ha guardado. Por eso el borrado tiene su propio estado
// ([ExamsState.examDeleteState]) y su aviso se pinta en la pestaña Publicación,
// junto al botón que lo provoca, que es donde se lee como su consecuencia.
// ---------------------------------------------------------------------------

/**
 * Qué clase de aviso deja el último borrado.
 *
 * [BLOCKED] y [FAILED] se distinguen por el CÓDIGO del rechazo y no por su texto,
 * y la diferencia es accionable: el primero no va a cambiar por reintentarlo —hay
 * que archivar—, el segundo sí.
 */
enum class ExamDeletionNoticeKind {
    /** Borrando. */
    WORKING,

    /** El servidor se ha negado: el examen tiene datos de usuarios. Definitivo. */
    BLOCKED,

    /** No se ha podido borrar: la comprobación falló, o el servidor no respondió. */
    FAILED,
}

/**
 * El título del bloqueo. Constante con nombre porque lo usan la pantalla y el
 * banco visual —que se desplaza hasta él para capturarlo—, y un literal repetido
 * en los dos sitios deja de coincidir a la primera reescritura.
 */
const val EXAM_DELETE_BLOCKED_TITLE: String = "No se puede borrar este examen."

/** El aviso que se pinta bajo las acciones. [detail] lo redacta el backend. */
data class ExamDeletionNotice(
    val kind: ExamDeletionNoticeKind,
    val title: String,
    val detail: String?,
)

/**
 * Códigos de dominio del borrado, copia literal de los de
 * `backend/.../BackendErrors.kt`.
 *
 * Se comparan NORMALIZADOS (ver [examDeletionNotice]): el servidor los publica en
 * minúsculas (`ErrorDto(error = cause.errorCode.lowercase())`).
 *
 * Hermanos de `EXAM_ERROR_CODE_DUPLICATE` y `EXAM_ERROR_QUESTION_COUNT_INVALID`,
 * que viven en `ExamBasicInfoPresentation` porque aquéllos SÍ se atribuyen a un
 * campo. Éstos no tienen campo, así que viven donde se usan.
 */
internal const val EXAM_ERROR_HAS_USER_DATA: String = "EXAM_HAS_USER_DATA"

/** Ver [EXAM_ERROR_HAS_USER_DATA]. La guarda no pudo comprobarse; no se borró nada. */
internal const val EXAM_ERROR_DELETE_GUARD_UNAVAILABLE: String = "EXAM_DELETE_GUARD_UNAVAILABLE"

/**
 * Qué ha pasado con el último borrado de ESTE examen, o `null` si no hay nada que
 * decir.
 *
 * El éxito devuelve `null` deliberadamente: un borrado que sale bien deja el
 * editor vacío —el ViewModel llama a `resetExamEditor`— y «Examen borrado.» al
 * lado de un formulario en blanco describiría algo que ya no está en pantalla. La
 * confirmación de que desapareció es que desapareció.
 */
fun examDeletionNotice(state: ExamsState): ExamDeletionNotice? = when (val deletion = state.examDeleteState) {
    UiAsync.Idle, UiAsync.Empty, is UiAsync.Success -> null
    UiAsync.Loading -> ExamDeletionNotice(
        kind = ExamDeletionNoticeKind.WORKING,
        title = "Borrando el examen…",
        detail = null,
    )
    is UiAsync.Error -> {
        val code = (deletion.error as? AppError.Validation)?.code?.uppercase()
        if (code == EXAM_ERROR_HAS_USER_DATA) {
            ExamDeletionNotice(
                kind = ExamDeletionNoticeKind.BLOCKED,
                title = EXAM_DELETE_BLOCKED_TITLE,
                // El mensaje del backend enumera QUÉ hay colgando. No se
                // reescribe aquí: quien conoce lo que bloquea es quien lo contó.
                detail = deletion.error.humanMessage(),
            )
        } else {
            ExamDeletionNotice(
                kind = ExamDeletionNoticeKind.FAILED,
                title = "No se ha podido borrar.",
                detail = deletion.error.humanMessage(),
            )
        }
    }
}

/** El color del aviso: el de las comprobaciones, ya medido en `ExamCheckContrastTest`. */
internal fun examDeletionNoticeStatus(kind: ExamDeletionNoticeKind): ExamCheckStatus = when (kind) {
    ExamDeletionNoticeKind.WORKING -> ExamCheckStatus.UNKNOWN
    // Los dos en rojo, y es lo correcto: en los dos casos el examen NO se ha
    // borrado. Un ámbar diría «ha ido a medias», y a medias no va nunca — o
    // borra, o no toca nada.
    ExamDeletionNoticeKind.BLOCKED, ExamDeletionNoticeKind.FAILED -> ExamCheckStatus.BLOCKING
}
