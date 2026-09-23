package com.oposicontrol.admin.features.exams.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.humanMessage
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamQuestion
import com.oposicontrol.admin.domain.model.QuestionType

/**
 * Lo que la pestaña Preguntas DECIDE, sin pintar nada.
 *
 * Todo lo de aquí es una afirmación sobre el dominio —qué tipo tiene una
 * pregunta, qué se sabe de ella, qué se puede hacer con ella y qué no— y una
 * afirmación sobre el dominio se prueba con un test de unidad, no mirando una
 * captura.
 *
 * Las tres reglas que atraviesan el fichero:
 *
 *  1. **Nada que el contrato no dé.** Si `ExamQuestion` no lo lleva, aquí no se
 *     fabrica. No hay estado de pregunta —la base guarda `activa`, que ni
 *     siquiera llega al cliente—, así que no hay «revisada», «pendiente» ni
 *     «incompleta».
 *  2. **Un tipo que no se reconoce se dice.** Nunca se da por Ráfaga.
 *  3. **El recuento sale de las funciones que ya existen**
 *     ([linkedQuestionsLabel] sobre [provisionalQuestionCount]), para que la
 *     cabecera, la insignia de la pestaña y esta pantalla no puedan discrepar.
 */

// ---------------------------------------------------------------------------
// Tipo de pregunta
// ---------------------------------------------------------------------------

/**
 * Lo que se enseña cuando el tipo no resuelve.
 *
 * No es un eufemismo de «Ráfaga»: es que el valor que llegó no es ninguno de los
 * tres. Etiquetarlo como Ráfaga sería inventarse el dato — y el administrador no
 * tendría forma de ver que ahí hay algo que revisar.
 */
const val QUESTION_TYPE_UNKNOWN_LABEL: String = "Tipo sin identificar"

/**
 * El nombre de producto de cada tipo de pregunta.
 *
 * En femenino porque son PREGUNTAS —ráfaga, razonada, inmersiva—, que es el
 * vocabulario del propio `QuestionType`; el masculino (Razonado, Inmersivo) es el
 * de `ExamTypeOption`, que nombra tipos de EXAMEN. Son dos cosas distintas y se
 * leen juntas en esta misma pantalla, así que no comparten etiqueta.
 *
 * `guiada` no aparece: es un alias legacy que el mapper canoniza al leer y que
 * nunca se escribe ni se muestra.
 */
fun questionTypeLabel(type: QuestionType?): String = when (type) {
    QuestionType.RAFAGA -> "Ráfaga"
    QuestionType.RAZONADA -> "Razonada"
    QuestionType.INMERSIVA -> "Inmersiva"
    null -> QUESTION_TYPE_UNKNOWN_LABEL
}

/**
 * Por qué el tipo puede no ser de fiar, dicho una vez y no en cada fila.
 *
 * Es la limitación del contrato, verificada: el DTO del servidor declara
 * `tipo_pregunta` con valor por defecto «rafaga» y su JSON no fija
 * `encodeDefaults`, así que una pregunta que no traiga el campo llega
 * exactamente igual que una Ráfaga de verdad. La consola no puede distinguirlas
 * y no va a fingir que sí.
 */
const val EXAM_QUESTION_TYPE_CAVEAT: String =
    "El tipo lo trae cada pregunta. Una pregunta que no lo traiga llega como «Ráfaga» y no se " +
        "puede distinguir de una Ráfaga real: los tipos de esta lista se leen con esa reserva."

// ---------------------------------------------------------------------------
// La fila
// ---------------------------------------------------------------------------

/**
 * Una pregunta del examen, tal y como se enseña.
 *
 * [facts] son los datos que la pregunta TIENE: cuál es su respuesta correcta y
 * cuántos recursos la acompañan. No lleva estado porque no existe ninguno.
 */
data class ExamQuestionRow(
    val id: String,
    val position: Int,
    val title: String,
    val typeLabel: String,
    /** `false` sólo cuando el tipo no resuelve: la UI lo pinta como lo que es. */
    val typeKnown: Boolean,
    val facts: String,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    /** La relación examen↔pregunta va anulada por el tribunal (M10). La posición se mantiene. */
    val annulled: Boolean = false,
    /** Texto secundario de la anulación: el motivo, o `null` si no consta. */
    val annulmentDetail: String? = null,
)

/**
 * Las preguntas en el orden en que el usuario las verá, numeradas desde 1.
 *
 * La posición es la de la LISTA, no `sortOrder`: el servidor recompacta
 * `posicion` al desvincular, y numerar con el campo crudo enseñaría huecos que en
 * la app no existen.
 */
fun examQuestionRows(questions: List<ExamQuestion>): List<ExamQuestionRow> {
    val ordered = questions.sortedBy { it.sortOrder }
    return ordered.mapIndexed { index, question ->
        ExamQuestionRow(
            id = question.id,
            position = index + 1,
            title = examQuestionTitle(question),
            typeLabel = questionTypeLabel(question.type),
            typeKnown = question.type != null,
            facts = examQuestionFacts(question),
            canMoveUp = index > 0,
            canMoveDown = index < ordered.lastIndex,
            annulled = question.annulled,
            annulmentDetail = examQuestionAnnulmentDetail(question),
        )
    }
}

/**
 * La etiqueta de una pregunta anulada en este examen. Es la MISMA etiqueta y el MISMO tono que el chip
 * de la revisión de importaciones (`IMPORT_ANNULLED_CHIP` / `IMPORT_ANNULLED_TONE` en features/imports).
 */
const val EXAM_QUESTION_ANNULLED_LABEL: String = "Anulada"

/** Tono del chip de anulada: atención. */
val EXAM_QUESTION_ANNULLED_TONE: com.oposicontrol.admin.core.ui.components.StatusTone =
    com.oposicontrol.admin.core.ui.components.StatusTone.ATTENTION

/**
 * El motivo de la anulación como texto secundario, o `null`. Sólo lo hay si la pregunta
 * va anulada: un motivo suelto sin anulación no se enseña.
 */
internal fun examQuestionAnnulmentDetail(question: ExamQuestion): String? =
    if (question.annulled) question.annulmentReason?.trim()?.takeIf { it.isNotEmpty() } else null

/** El título de la pregunta, o su enunciado, o que no tiene ninguno de los dos. */
internal fun examQuestionTitle(question: ExamQuestion): String =
    question.title.ifBlank { question.statement }.ifBlank { "Pregunta sin título" }

/**
 * Lo que se sabe de la pregunta, en una línea.
 *
 * «Sin opciones» y «Sin respuesta correcta marcada» son cosas distintas y por eso
 * se dicen distinto: una pregunta sin opciones no está a medio configurar, es que
 * no han llegado; una con opciones y sin correcta sí es un problema de contenido.
 * Decir «Sin respuesta marcada» en los dos casos convertía una ausencia de datos
 * en un defecto del examen.
 */
internal fun examQuestionFacts(question: ExamQuestion): String {
    val answer = when {
        question.options.isEmpty() -> "Sin opciones"
        else -> question.options.firstOrNull { it.isCorrect }
            ?.let { "Respuesta correcta: ${it.label}" }
            ?: "Sin respuesta correcta marcada"
    }
    val resources = question.resources.size
        .takeIf { it > 0 }
        // Concordancia: «1 recurso», no «1 recursos».
        ?.let { if (it == 1) "1 recurso" else "$it recursos" }
    return listOfNotNull(answer, resources).joinToString(" · ")
}

// ---------------------------------------------------------------------------
// Recuento: la misma fuente que la cabecera y la insignia
// ---------------------------------------------------------------------------

/**
 * El recuento tal y como lo enseña esta pestaña, con su procedencia.
 *
 * **No es una regla nueva**: es exactamente [linkedQuestionsLabel] con la cifra
 * provisional de [provisionalQuestionCount], que son las que ya usan la cabecera,
 * la ficha del resumen y la tarjeta del catálogo. Duplicar la regla aquí era la
 * forma garantizada de que la pestaña acabara diciendo un número distinto del de
 * la cabecera que tiene tres centímetros más arriba.
 *
 * Mientras carga, la cifra va marcada «sin confirmar»; si la carga falla, no hay
 * cifra: no saber no es saber cinco.
 */
fun examQuestionsCountLabel(state: ExamsState, exam: Exam?): ExamFieldLabel =
    linkedQuestionsLabel(state.questions, provisionalQuestionCount(state, exam))

// ---------------------------------------------------------------------------
// Qué se puede hacer aquí, y qué no
// ---------------------------------------------------------------------------

/**
 * Lo que separa estas acciones del formulario.
 *
 * Ordenar, quitar y archivar viajan al servidor en cuanto se pulsan; el tipo, la
 * duración o el acceso esperan a «Guardar cambios». Que las dos clases de acción
 * convivan en el mismo editor sin decirlo es cómo alguien acaba pulsando
 * «Descartar» creyendo que deshace una desvinculación.
 */
const val EXAM_QUESTIONS_IMMEDIATE_NOTE: String =
    "Ordenar, quitar y archivar se aplican al momento, sin pasar por «Guardar cambios»."

/** El examen todavía no existe: no hay nada a lo que vincular preguntas. */
const val EXAM_QUESTIONS_NEW_EXAM_TITLE: String = "Todavía no hay examen que vincular"

const val EXAM_QUESTIONS_NEW_EXAM_MESSAGE: String =
    "Las preguntas se vinculan a un examen ya creado. Guarda éste y vuelve a esta pestaña."

/** Examen guardado y sin preguntas. Es un dato, no un fallo. */
const val EXAM_QUESTIONS_EMPTY_TITLE: String = "Este examen no tiene preguntas"

/**
 * Cómo se vinculan, que es la pregunta que se hace quien ve esta tarjeta.
 *
 * Vincular es hoy un efecto lateral de editar la pregunta —se le asigna examen y
 * posición— y eso se hace en la pantalla de Preguntas. Por eso aquí se explica el
 * camino real en vez de ofrecer un botón de «vincular una existente», que no
 * existe en ninguna ruta.
 */
const val EXAM_QUESTIONS_EMPTY_MESSAGE: String =
    "Las preguntas se asignan a un examen desde la pantalla de Preguntas: al editar una, se le " +
        "indica a qué examen pertenece y en qué posición."

const val EXAM_QUESTIONS_ERROR_TITLE: String = "No se han podido cargar las preguntas"

/** Mientras se cuentan. El recuento de arriba ya dice que es provisional. */
const val EXAM_QUESTIONS_LOADING_LABEL: String = "Cargando las preguntas de este examen…"

const val EXAM_QUESTIONS_LOADING_DETAIL: String =
    "La lista todavía no está aquí, así que el recuento de arriba no es definitivo."

/**
 * Crear preguntas y editar sus opciones: **bloqueado, y se dice** (P0-5).
 *
 * El servidor no admite hoy esas dos operaciones. La interfaz puede estar
 * preparada —el botón existe y está donde estará— pero no puede fingir que
 * funciona: va deshabilitado con el motivo al lado. Un botón activo aquí sólo
 * podría acabar en el mismo error, y con un clic de por medio que hace pensar que
 * el fallo es de quien lo pulsó.
 *
 * No nombra la causa técnica a propósito: ni funciones de base de datos, ni
 * códigos de error, ni tablas. Quien administra necesita saber qué no puede hacer
 * y qué sí.
 */
const val EXAM_QUESTIONS_CREATE_BLOCKED: String =
    "El servidor todavía no admite crear preguntas ni cambiar sus opciones desde la consola. " +
        "Lo que sí funciona es editar los campos de una pregunta que ya existe, en la pantalla de Preguntas."

/** Qué hace «Quitar del examen», dicho antes de pulsarlo. */
const val EXAM_QUESTION_UNLINK_NOTE: String =
    "Quitar una pregunta la saca de este examen y la deja en el banco. Archivarla la retira del " +
        "banco entero, y con él de todos los exámenes que la usen."

/**
 * Si hay una acción en curso sobre el examen abierto.
 *
 * Se usa para DESHABILITAR las acciones inmediatas mientras algo vuela, no para
 * anunciar qué está pasando: `actionState` lo comparten el guardado del examen y
 * las acciones sobre preguntas, así que esta pestaña no puede saber cuál de las
 * dos es. Atribuirle a las preguntas un guardado en curso —o su error— sería
 * inventarse la causa; el mensaje lo da la barra de guardado, que es quien lo
 * tiene.
 */
fun examQuestionsBusy(state: ExamsState): Boolean = state.actionState is UiAsync.Loading

/** El mensaje humano de una carga de preguntas fallida, o `null` si no falló. */
fun examQuestionsLoadError(questions: UiAsync<List<ExamQuestion>>): String? =
    (questions as? UiAsync.Error)?.error?.humanMessage()
