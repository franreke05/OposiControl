package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.ui.graphics.Color
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.theme.GlassTokens

/**
 * Lo que la pestaña Configuración DECIDE, sin pintar nada.
 *
 * Tres cosas y ninguna más, porque son las únicas que el examen guarda y el
 * editor sabe escribir en esta pestaña: el tipo (con lo que su especialidad
 * permite), la duración y si es oficial. El número de preguntas se enseña, no se
 * escribe (D1). El precio y los tickets NO viven aquí: son de Acceso.
 *
 * Lo que tampoco está, y por qué:
 *
 *  - **«Sin regreso» en Ráfaga.** No hay evidencia de esa semántica: el backend
 *    registra respuestas (`AppV1Routes.responder`) sin imponer orden ni bloquear el
 *    retroceso, y el dominio de exámenes no la describe. Por eso ni se afirma ni
 *    hay controles «incompatibles» que atar entre sí.
 *  - **Tipo ⇒ acceso** (ráfaga siempre gratuita, inmersivo siempre premium). Sólo
 *    aparece en restricciones de la base cuyo estado en vivo es desconocido
 *    (EXAM-006). Lo que sí está en el contrato —que sólo una ráfaga gratuita se
 *    sirve sin sesión— lo dice la descripción del tipo; no se convierte en regla.
 */

// ---------------------------------------------------------------------------
// Tipo de examen: tres respuestas, no dos
// ---------------------------------------------------------------------------

/**
 * Qué se sabe de si la especialidad permite un tipo.
 *
 * **Tres respuestas y no dos.** [examTypeAvailability] devuelve un booleano y
 * pinta igual «la especialidad lo prohíbe» que «todavía no lo sé»: con las
 * capacidades cargando o caídas, las tres tarjetas decían «no permitido». Eso es
 * afirmar una decisión que nadie ha tomado.
 */
enum class ExamTypePermission {
    /** La especialidad lo permite: la tarjeta se puede elegir. */
    ALLOWED,

    /** La especialidad lo tiene desactivado. */
    NOT_ALLOWED,

    /** No se sabe: sin especialidad, comprobando o la consulta falló. No es un «no». */
    UNCHECKED,
}

/** Una tarjeta de tipo: qué tipo, qué permite su especialidad y si es el elegido. */
data class ExamTypeChoice(
    val option: ExamTypeOption,
    val permission: ExamTypePermission,
    val selected: Boolean,
) {
    /**
     * Se puede pulsar todo lo que la especialidad no haya PROHIBIDO.
     *
     * Antes exigía [ExamTypePermission.ALLOWED], y eso dejaba el selector muerto
     * justo cuando más hace falta: un examen nuevo no tiene especialidad, así que
     * las capacidades están en `Idle`, los tres tipos salen [UNCHECKED] y ninguna
     * tarjeta se podía pulsar. Con el borrador arrancando en «rafaga» por defecto,
     * el resultado visible era que sólo existía Ráfaga.
     *
     * «Todavía no lo sé» no puede impedir elegir. Lo que sí impide es GUARDAR:
     * `saveExam` bloquea un tipo que las capacidades prohíben y también uno que no
     * ha podido comprobar, y lo dice. Elegir es del administrador; comprobar, del
     * servidor.
     */
    val selectable: Boolean get() = permission != ExamTypePermission.NOT_ALLOWED && !selected
}

/**
 * Las tres tarjetas, en el orden canónico Ráfaga · Razonado · Inmersivo.
 *
 * El elegido se resuelve con [ExamTypeOption.fromWire] y no comparando el texto:
 * un examen antiguo guardado como `guiado` ES un Razonado y su tarjeta tiene que
 * salir marcada. Nunca se escribe `guiado`: la tarjeta sólo emite `wireValue`.
 *
 * Unas capacidades que siguen en memoria después de vaciar la especialidad no
 * cuentan: sin especialidad no hay nada que comprobar.
 */
fun examTypeChoices(state: ExamsState): List<ExamTypeChoice> {
    val selected = ExamTypeOption.fromWire(state.editorTypeExam)
    val capabilities = (state.specialtyCapabilities as? UiAsync.Success)?.value
        ?.takeIf { state.editorSpecialtyId.isNotBlank() }
    return ExamTypeOption.entries.map { option ->
        ExamTypeChoice(
            option = option,
            permission = when {
                capabilities == null -> ExamTypePermission.UNCHECKED
                capabilities.allows(option.wireValue) -> ExamTypePermission.ALLOWED
                else -> ExamTypePermission.NOT_ALLOWED
            },
            selected = option == selected,
        )
    }
}

/** La palabra que acompaña a la marca: el color nunca va solo. */
fun examTypePermissionLabel(permission: ExamTypePermission): String = when (permission) {
    ExamTypePermission.ALLOWED -> "Permitido"
    ExamTypePermission.NOT_ALLOWED -> "No permitido"
    ExamTypePermission.UNCHECKED -> "Sin comprobar"
}

internal fun examTypePermissionIcon(permission: ExamTypePermission): AppIcon = when (permission) {
    ExamTypePermission.ALLOWED -> AppIcon.CHECK
    ExamTypePermission.NOT_ALLOWED -> AppIcon.BLOCKED
    ExamTypePermission.UNCHECKED -> AppIcon.CLOCK
}

/** Lo que un lector de pantalla oye de una tarjeta: tipo, qué hace, permiso y si es la elegida. */
fun examTypeCardDescription(choice: ExamTypeChoice): String = buildString {
    append(choice.option.label)
    append(". ")
    append(choice.option.description)
    append(" ")
    append(examTypePermissionLabel(choice.permission))
    append(" en esta especialidad.")
    if (choice.selected) append(" Tipo elegido.")
}

// ---------------------------------------------------------------------------
// Compatibilidad con la especialidad (D10), tal y como se lee DENTRO de la pestaña
// ---------------------------------------------------------------------------

/**
 * El bloque de compatibilidad de Configuración.
 *
 * Etiqueta, estado y caso son los de [examTypeCompatibility] —la misma función que
 * pinta la tarjeta del resumen y la comprobación—, así que la pestaña y el rail no
 * pueden decir cosas distintas. Sólo cambia el DETALLE en dos casos, porque el de
 * D10 está escrito para leerse desde fuera de la pestaña: «Reintenta en
 * Configuración» dicho dentro de Configuración es una instrucción que manda al
 * sitio donde ya se está.
 *
 * [note] es el `mensaje_limitacion` de la especialidad cuando explica por qué
 * OTRAS tarjetas están bloqueadas. Se dice una vez: si ya es el detalle (el tipo
 * elegido es el bloqueado), no se repite.
 */
data class ExamTypeCompatibilityPanel(
    val case: ExamTypeCompatibilityCase,
    val label: String,
    val detail: String?,
    val status: ExamCheckStatus,
    val note: String?,
    val canRetry: Boolean,
    val asksForSpecialty: Boolean,
)

/** Detalle de «no disponibles» dentro de la pestaña: dice la consecuencia, no adónde ir. */
const val EXAM_TYPE_UNAVAILABLE_IN_TAB: String =
    "Hasta poder comprobarlos, el tipo no se puede cambiar y el examen no se puede guardar."

/** Detalle de «no permitido» sin `mensaje_limitacion`: las dos salidas reales. */
const val EXAM_TYPE_NOT_ALLOWED_IN_TAB: String =
    "Elige otro tipo o cambia la especialidad en Información básica."

fun examTypeCompatibilityPanel(state: ExamsState): ExamTypeCompatibilityPanel {
    val compatibility = examTypeCompatibility(state)
    val capabilities = (state.specialtyCapabilities as? UiAsync.Success)?.value
        ?.takeIf { state.editorSpecialtyId.isNotBlank() }
    val limitation = capabilities?.limitationMessage?.takeIf { it.isNotBlank() }
    val detail = when (compatibility.case) {
        ExamTypeCompatibilityCase.UNAVAILABLE -> EXAM_TYPE_UNAVAILABLE_IN_TAB
        ExamTypeCompatibilityCase.NOT_ALLOWED -> limitation ?: EXAM_TYPE_NOT_ALLOWED_IN_TAB
        else -> compatibility.detail
    }
    val someTypeBlocked = capabilities != null &&
        ExamTypeOption.entries.any { !capabilities.allows(it.wireValue) }
    // Sólo con capacidades DECIDIDAS: en una especialidad sin configurar el
    // detalle ya dice que son los valores por defecto, y un mensaje de limitación
    // ahí no lo ha escrito nadie para este caso.
    val note = limitation?.takeIf { capabilities.configured && someTypeBlocked && it != detail }
    return ExamTypeCompatibilityPanel(
        case = compatibility.case,
        label = compatibility.label,
        detail = detail,
        status = compatibility.status,
        note = note,
        canRetry = compatibility.case == ExamTypeCompatibilityCase.UNAVAILABLE,
        asksForSpecialty = compatibility.case == ExamTypeCompatibilityCase.NO_SPECIALTY,
    )
}

// ---------------------------------------------------------------------------
// Parámetros
// ---------------------------------------------------------------------------

/**
 * Placeholder de la duración: «Sin duración» (DEBT-034).
 *
 * Decía «Sin límite», y eso es una afirmación de comportamiento que nada respalda:
 * ninguna ruta del repositorio aplica `duracion_minutos` como límite y la API de
 * la app no la envía. Es el mismo texto que la cabecera y la ficha del resumen.
 */
const val EXAM_DURATION_PLACEHOLDER: String = EXAM_NO_DURATION

/** Apoyo de la duración cuando no hay nada que avisar. Neutro: no promete un límite. */
const val EXAM_DURATION_OPTIONAL: String = "Opcional. Déjala vacía si no quieres fijar una duración."

/**
 * La línea de apoyo del campo de duración.
 *
 * Con el resumen plegado (1366) el aviso de la comprobación no está a la vista,
 * así que el campo dice lo mismo donde se escribe: cero no se puede cumplir, y un
 * texto que no es un número no se va a poder guardar («La duración debe ser
 * numérica.»).
 */
fun examDurationSupport(editorDurationMinutes: String): String {
    val value = editorDurationMinutes.trim()
    val minutes = value.toIntOrNull()
    return when {
        value.isEmpty() -> EXAM_DURATION_OPTIONAL
        minutes == null -> "Escribe los minutos con cifras."
        minutes == 0 -> EXAM_ZERO_DURATION_WARNING
        minutes < 0 -> "Escribe un número de minutos mayor que cero."
        else -> EXAM_DURATION_OPTIONAL
    }
}

/**
 * El número de preguntas, de sólo lectura, con la regla D1.
 *
 * La MISMA función que la ficha del resumen ([linkedQuestionCountLabel]) con la
 * misma cifra mientras carga: si una dice «5», la otra también. Esa cifra es un
 * dato desde M1 (K2-2): `total_preguntas` lo mantiene la base.
 */
fun examQuestionCountField(state: ExamsState): ExamFieldLabel =
    linkedQuestionCountLabel(state.questions, provisionalQuestionCount(state, state.selectedExam()))

/** Apoyo del número de preguntas: de dónde sale, o por qué no es un dato todavía. */
fun examQuestionCountSupport(state: ExamsState, field: ExamFieldLabel): String = when {
    state.selectedExamId == null -> "Las preguntas se vinculan después de crear el examen."
    !field.isValue && field.detail != null -> field.detail
    else -> "Sale de las preguntas vinculadas; no se escribe a mano."
}

// ---------------------------------------------------------------------------
// Colores de la tarjeta de tipo y de su píldora de permiso.
//
// Funciones para que `ExamCheckContrastTest` mida exactamente lo que se pinta,
// con la paleta viva de los dos temas.
// ---------------------------------------------------------------------------

internal data class ExamTypeCardColors(
    val background: Color,
    val border: Color,
    val title: Color,
    val description: Color,
    /** El check coral del tipo elegido: elemento de interfaz, 3:1. El texto nunca va en coral. */
    val selectedMark: Color,
)

internal fun examTypeCardColors(
    selected: Boolean,
    permission: ExamTypePermission,
    hovered: Boolean,
    tokens: GlassTokens,
): ExamTypeCardColors {
    val allowed = permission == ExamTypePermission.ALLOWED
    return ExamTypeCardColors(
        background = when {
            selected -> tokens.surfaceSelected
            !allowed -> tokens.surfaceMuted
            hovered -> tokens.surfaceHover
            else -> tokens.surfaceSolid
        },
        border = when {
            selected -> tokens.accent.copy(alpha = 0.55f)
            !allowed -> tokens.border
            hovered -> tokens.borderStrong
            else -> tokens.border
        },
        title = if (allowed || selected) tokens.textPrimary else tokens.textSecondary,
        description = tokens.textSecondary,
        selectedMark = tokens.accentInk,
    )
}

/**
 * Los colores del campo inerte de Configuración —«Número de preguntas»— según esté
 * señalado o no.
 *
 * Función pura, y por eso mismo: `ExamCheckContrastTest` mide exactamente lo que se
 * pinta, con la paleta viva de los dos temas. Si el estado de error se eligiera
 * dentro del composable, el test no lo vería.
 *
 * **El relleno NO cambia con el error.** `surfaceMuted` es lo que dice «esto no se
 * escribe», y teñirlo de rojo haría dos anuncios con una sola señal. Lo que cambia
 * es la frontera —el borde, que en reposo es el filete de cristal y con error es
 * `danger`— y el apoyo de debajo, que es el que dice qué pasa.
 */
internal data class ExamReadOnlyFieldColors(
    val fill: Color,
    val border: Color,
    val support: Color,
)

internal fun examReadOnlyFieldColors(
    isError: Boolean,
    tokens: GlassTokens,
): ExamReadOnlyFieldColors = ExamReadOnlyFieldColors(
    fill = tokens.surfaceMuted,
    // `glassStroke` en reposo es el trazo por defecto de `GlassSurface`, que en
    // escritorio resuelve a `border`: el campo se veía así y se sigue viendo así.
    border = if (isError) tokens.danger else tokens.glassStroke,
    support = if (isError) tokens.danger else tokens.textSecondary,
)

internal data class ExamTypePermissionPillColors(
    val background: Color,
    val border: Color,
    val content: Color,
    val icon: Color,
)

/**
 * La píldora «Permitido / No permitido / Sin comprobar» de cada tarjeta.
 *
 * Lleva su propio relleno para que su contraste no dependa de sobre qué tarjeta
 * cae (normal, elegida o apagada). El verde sólo en la marca: el texto de un
 * permiso es texto, y va en `textPrimary`.
 */
internal fun examTypePermissionPillColors(
    permission: ExamTypePermission,
    tokens: GlassTokens,
): ExamTypePermissionPillColors = when (permission) {
    ExamTypePermission.ALLOWED -> ExamTypePermissionPillColors(
        background = tokens.surfaceSolid,
        border = tokens.success.copy(alpha = 0.45f),
        content = tokens.textPrimary,
        icon = tokens.success,
    )
    ExamTypePermission.NOT_ALLOWED,
    ExamTypePermission.UNCHECKED,
    -> ExamTypePermissionPillColors(
        background = tokens.surfaceMuted,
        border = tokens.border,
        content = tokens.textSecondary,
        icon = tokens.textSecondary,
    )
}
