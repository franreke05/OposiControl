package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.ui.graphics.Color
import com.oposicontrol.admin.core.ui.components.StatusTone
import com.oposicontrol.admin.core.ui.theme.GlassTokens
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.ExamStatus

/**
 * Lo que la pestaña Acceso DECIDE, sin pintar nada.
 *
 * **VISIBILIDAD ≠ ACCESO.** Que un examen esté publicado sólo lo hace aparecer en
 * el catálogo de la app; no lo hace gratuito. Lo primero se decide en Publicación
 * (acciones inmediatas); lo segundo, aquí (viaja con «Guardar cambios»).
 *
 * Lo que el contrato sostiene y esta pestaña dice:
 *
 *  - `tipo_acceso` (gratuito / premium) y `precio_tickets` son datos reales del
 *    examen y se guardan.
 *  - Sólo un examen Ráfaga gratuito (y activo) se sirve sin sesión:
 *    `SupabaseV1Gateway.getPreguntasExamenRafaga` responde `Forbidden` a todo lo
 *    demás.
 *  - Ningún camino de compra consulta `precio_tickets` de un examen.
 *
 * Lo que NO dice, porque el contrato no lo sostiene: que una suscripción o una
 * compra con OposiTickets desbloqueen un examen premium, ni si un usuario
 * concreto puede abrirlo. Esa evaluación (`canUserAccessExam`) es trabajo
 * pendiente del backend; aquí no se presenta como calculada ni se diseña un
 * sistema de derechos de acceso.
 */

// ---------------------------------------------------------------------------
// Tipo de acceso
// ---------------------------------------------------------------------------

/** Gratuito, premium, o un valor que no es ninguno de los dos. */
enum class ExamAccessKind {
    FREE,
    PREMIUM,

    /**
     * Un valor que no es `gratuito` ni `premium`. Se dice, no se pinta como
     * gratuito: el editor anterior trataba todo lo que no fuera «premium» como
     * gratuito y marcaba ese chip sobre un dato que no lo era.
     */
    UNKNOWN,
}

fun examAccessKind(typeAccess: String): ExamAccessKind = when (typeAccess.trim().lowercase()) {
    "gratuito" -> ExamAccessKind.FREE
    "premium" -> ExamAccessKind.PREMIUM
    else -> ExamAccessKind.UNKNOWN
}

/** La frase que impide leer el tipo de acceso como una evaluación de usuarios. */
const val EXAM_ACCESS_NOT_CALCULATED: String =
    "Es un dato del examen: esta consola no calcula qué usuario puede abrirlo."

/**
 * Lo que implica el tipo de acceso elegido, sólo con lo verificado.
 *
 * La relación tipo ↔ acceso que sí existe —sólo un Ráfaga gratuito se sirve sin
 * sesión— se dice aquí porque es donde se elige el acceso; no se convierte en una
 * regla que impida combinaciones (las restricciones de base que lo harían tienen
 * estado desconocido, EXAM-006).
 */
fun examAccessNotes(state: ExamsState): List<String> {
    val rafaga = ExamTypeOption.fromWire(state.editorTypeExam) == ExamTypeOption.RAFAGA
    return when (examAccessKind(state.editorTypeAccess)) {
        ExamAccessKind.FREE -> listOf(
            EXAM_ACCESS_NOT_CALCULATED,
            if (rafaga) {
                // «Una vez publicado» (K0-8, EX-18): la ruta pública sólo sirve
                // exámenes con `publicado_en`; un borrador no sale sin sesión.
                "Al ser Ráfaga y gratuito, una vez publicado la app puede servir sus preguntas sin iniciar sesión."
            } else {
                "No es Ráfaga, así que no se sirve sin iniciar sesión: eso sólo ocurre con un Ráfaga gratuito."
            },
        )
        ExamAccessKind.PREMIUM -> listOf(
            EXAM_ACCESS_NOT_CALCULATED,
            "Un examen premium no se sirve sin iniciar sesión.",
        )
        ExamAccessKind.UNKNOWN -> listOf("Tipo de acceso sin identificar. Elige Gratuito o Premium.")
    }
}

// ---------------------------------------------------------------------------
// Las dos formas de acceso, como decisión
// ---------------------------------------------------------------------------

/**
 * Una de las dos formas de acceso, tal y como se ofrece en la pestaña.
 *
 * Las dos están SIEMPRE disponibles: a diferencia del tipo de examen, no hay
 * ninguna capacidad de especialidad que pueda prohibir una de ellas, así que no
 * existe aquí un tercer estado «no permitido» ni nada que comprobar contra el
 * servidor. Lo único que distingue a una tarjeta de la otra es cuál está elegida.
 */
data class ExamAccessChoice(
    val kind: ExamAccessKind,
    /** El valor que viaja al guardar. */
    val wireValue: String,
    val label: String,
    /** Qué implica, en una frase corta y sin prometer un desbloqueo. */
    val summary: String,
    val selected: Boolean,
) {
    /** Sólo se pulsa la que no está ya elegida. */
    val selectable: Boolean get() = !selected
}

/**
 * Las dos tarjetas de acceso, con la elegida marcada.
 *
 * Un `tipo_acceso` que no es ninguno de los dos no marca ninguna tarjeta —no se
 * disfraza de gratuito— y el aviso de [examAccessNotes] lo dice.
 */
fun examAccessChoices(state: ExamsState): List<ExamAccessChoice> {
    val current = examAccessKind(state.editorTypeAccess)
    return listOf(
        ExamAccessChoice(
            kind = ExamAccessKind.FREE,
            wireValue = "gratuito",
            label = "Gratuito",
            summary = "Acceso sin coste.",
            selected = current == ExamAccessKind.FREE,
        ),
        ExamAccessChoice(
            kind = ExamAccessKind.PREMIUM,
            wireValue = "premium",
            label = "Premium",
            summary = "Acceso restringido.",
            selected = current == ExamAccessKind.PREMIUM,
        ),
    )
}

/** Lo que lee un lector de pantalla en una tarjeta de acceso: texto, no sólo color. */
fun examAccessCardDescription(choice: ExamAccessChoice): String =
    "${choice.label}. ${choice.summary} ${if (choice.selected) "Elegido." else "No elegido."}"

// ---------------------------------------------------------------------------
// Visibilidad (se decide en Publicación)
// ---------------------------------------------------------------------------

/** La idea que esta pestaña existe para no perder. */
const val EXAM_VISIBILITY_HEADLINE: String =
    "El tipo de acceso no decide si el examen aparece en el catálogo."

/** El destino real: la pestaña Publicación de este mismo workspace. */
const val EXAM_VISIBILITY_LINK: String = "Ir a Publicación"

/** El estado de visibilidad del examen, tal y como se recuerda desde Acceso. */
data class ExamVisibilitySummary(
    val statusLabel: String,
    val tone: StatusTone,
    val text: String,
)

/**
 * Qué significa hoy el estado del examen para el catálogo de la app.
 *
 * El catálogo exige `activo` y `publicado_en` (`AppV1Routes`), así que borrador y
 * archivado no aparecen. El texto del publicado repite a propósito que verse no es
 * abrirse: es el malentendido que esta pestaña existe para evitar.
 */
fun examVisibilitySummary(exam: Exam?): ExamVisibilitySummary = when (val status = exam?.status) {
    null -> ExamVisibilitySummary(
        statusLabel = "Sin guardar",
        tone = StatusTone.NEUTRAL,
        text = "Todavía no existe, así que no aparece en ningún catálogo.",
    )
    ExamStatus.DRAFT -> ExamVisibilitySummary(
        statusLabel = examStatusLabel(status),
        tone = examStatusTone(status),
        text = "No aparece en el catálogo de la app.",
    )
    // Sólo el hecho. La distinción «verse no es abrirse» la lleva
    // [EXAM_VISIBILITY_HEADLINE], que la banda enseña en TODOS los estados: dicha
    // también aquí, un examen publicado leía la misma idea dos frases seguidas.
    ExamStatus.PUBLISHED -> ExamVisibilitySummary(
        statusLabel = examStatusLabel(status),
        tone = examStatusTone(status),
        text = "Aparece en el catálogo de la app.",
    )
    ExamStatus.ARCHIVED -> ExamVisibilitySummary(
        statusLabel = examStatusLabel(status),
        tone = examStatusTone(status),
        text = "Retirado: no aparece en el catálogo de la app.",
    )
}

// ---------------------------------------------------------------------------
// Precio en OposiTickets
// ---------------------------------------------------------------------------

/** La unidad, escrita una sola vez. No hay símbolo monetario: no son euros. */
const val EXAM_TICKETS_UNIT: String = "OposiTickets"

/**
 * Una cantidad de OposiTickets con separador de millar: `1577` → «1.577».
 *
 * Es SÓLO presentación: lo que se escribe en el campo y lo que viaja al guardar
 * siguen siendo los dígitos sin puntuar. No usa ningún formateador de plataforma
 * porque esto es `commonMain` y `java.text` no existe en iOS; agrupar de tres en
 * tres desde la derecha es todo lo que hace falta y se comporta igual en los
 * cuatro destinos.
 */
fun formatTickets(amount: Int): String {
    val negative = amount < 0
    // A `Long` antes de cambiar el signo: `-Int.MIN_VALUE` no cabe en un `Int`.
    val digits = (if (negative) -amount.toLong() else amount.toLong()).toString()
    val grouped = digits.reversed().chunked(3).joinToString(".").reversed()
    return if (negative) "-$grouped" else grouped
}

/**
 * El precio escrito, formateado y con su unidad, o `null` si todavía no hay cifra
 * que leer (campo vacío, algo que no es un número, o cero).
 */
fun examPriceReading(editorPriceTickets: String): String? =
    editorPriceTickets.trim().toIntOrNull()
        ?.takeIf { it > 0 }
        ?.let { "${formatTickets(it)} $EXAM_TICKETS_UNIT" }

/** Si el precio se enseña, y cómo. */
enum class ExamPriceMode {
    /** Gratuito sin precio, o acceso sin identificar: no hay nada que decir de un precio. */
    HIDDEN,

    /** Premium: el precio se puede poner o quitar. */
    PREMIUM,

    /** Gratuito con un precio GUARDADO: no hace nada y se dice, con la forma de quitarlo. */
    FREE_WITH_PRICE,
}

fun examPriceMode(state: ExamsState): ExamPriceMode {
    val price = state.editorPriceTickets.trim().toIntOrNull() ?: 0
    return when (examAccessKind(state.editorTypeAccess)) {
        ExamAccessKind.PREMIUM -> ExamPriceMode.PREMIUM
        ExamAccessKind.FREE -> if (price > 0) ExamPriceMode.FREE_WITH_PRICE else ExamPriceMode.HIDDEN
        ExamAccessKind.UNKNOWN -> ExamPriceMode.HIDDEN
    }
}

/**
 * Por qué el bloque de monetización no pide un precio.
 *
 * El bloque NO desaparece: una sección que se esfuma al cambiar de acceso deja al
 * administrador preguntándose si el precio sigue guardado en alguna parte. Se
 * queda y dice por qué no aplica.
 */
fun examPriceEmptyNote(state: ExamsState): String = when (examAccessKind(state.editorTypeAccess)) {
    ExamAccessKind.FREE -> "El examen es gratuito: no tiene precio en $EXAM_TICKETS_UNIT."
    ExamAccessKind.PREMIUM -> "Enciende el precio para ponerle una cantidad."
    ExamAccessKind.UNKNOWN -> "Elige antes el tipo de acceso."
}

/**
 * Si el interruptor «Poner un precio» está encendido.
 *
 * Encendido es TODO lo que no sea cero —también el campo vacío mientras se
 * escribe—. El editor anterior lo ataba a `precio > 0` y el campo desaparecía al
 * borrar el número para escribir otro. «0» y «00» son el mismo cero.
 */
fun examPriceEnabled(editorPriceTickets: String): Boolean = editorPriceTickets.trim().toIntOrNull() != 0

/**
 * La línea de apoyo del precio: qué escribir, o por qué no se va a poder guardar
 * («El precio en tickets debe ser un número válido», «no puede ser negativo»).
 */
fun examPriceSupport(editorPriceTickets: String): String {
    val value = editorPriceTickets.trim()
    val price = value.toIntOrNull()
    return when {
        value.isEmpty() -> "Escribe cuántos $EXAM_TICKETS_UNIT cuesta, o apaga el precio."
        price == null -> "Escribe el precio con cifras."
        price < 0 -> "El precio no puede ser negativo."
        else -> "En $EXAM_TICKETS_UNIT, sin decimales."
    }
}

/** Lo que el precio hace hoy: nada. Mismo hecho que la ayuda `Help.precioExamen`. */
const val EXAM_PRICE_INERT_NOTICE: String =
    "El precio se guarda, pero hoy no desbloquea nada: no existe una compra de examen suelto que lo consulte."

/** El titular de un precio que está guardado y no hace nada. */
const val EXAM_PRICE_WITHOUT_EFFECT_TITLE: String = "Precio guardado · sin efecto"

/** Un precio guardado en un examen gratuito, dicho sin prometer ni amenazar un cobro. */
fun examPriceWithoutEffect(editorPriceTickets: String): String {
    val price = editorPriceTickets.trim().toIntOrNull() ?: 0
    return "El examen es gratuito: su precio guardado de ${formatTickets(price)} $EXAM_TICKETS_UNIT " +
        "no tiene ningún efecto."
}

// ---------------------------------------------------------------------------
// Colores de la tarjeta de acceso.
//
// En funciones para que `ExamCheckContrastTest` mida exactamente lo que se pinta,
// con la paleta viva de los dos temas.
// ---------------------------------------------------------------------------

internal data class ExamAccessCardColors(
    val background: Color,
    val border: Color,
    val title: Color,
    val summary: Color,
    /** El check coral de la elegida: elemento de interfaz, 3:1. El texto nunca va en coral. */
    val selectedMark: Color,
)

/**
 * El relleno, el borde y los textos de una tarjeta de acceso.
 *
 * Mismo reparto que la tarjeta de tipo de Configuración —para que las dos
 * decisiones del workspace se vean iguales— menos el estado «no permitido», que
 * aquí no existe: ninguna de las dos formas de acceso se puede prohibir.
 *
 * **El texto no va en coral (DEBT-024).** El chip anterior pintaba su texto en
 * `accentInk` sobre `surfaceSelected`: 4,21:1 en claro. El coral se queda en el
 * borde y en el check, que como elementos de interfaz piden 3:1.
 */
internal fun examAccessCardColors(
    selected: Boolean,
    hovered: Boolean,
    tokens: GlassTokens,
): ExamAccessCardColors = ExamAccessCardColors(
    background = when {
        selected -> tokens.surfaceSelected
        hovered -> tokens.surfaceHover
        else -> tokens.surfaceSolid
    },
    border = when {
        selected -> tokens.accent.copy(alpha = 0.55f)
        hovered -> tokens.borderStrong
        else -> tokens.border
    },
    title = tokens.textPrimary,
    summary = tokens.textSecondary,
    selectedMark = tokens.accentInk,
)
