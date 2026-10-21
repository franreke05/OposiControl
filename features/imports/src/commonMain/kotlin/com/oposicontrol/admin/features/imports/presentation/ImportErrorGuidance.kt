package com.oposicontrol.admin.features.imports.presentation

import com.oposicontrol.admin.core.common.result.AppError

/**
 * Mensajes de operador para los rechazos de la publicación de un lote oficial (FA37, `E1-V1-CONTROL-SPEC-IMPORT.md` §4).
 *
 * El servidor ya contesta con un texto en español, pero el panel no depende de él: cada código tiene aquí su mensaje, con
 * el siguiente paso concreto. Un código que no está en la tabla conserva el mensaje del servidor tal cual. Ningún mensaje
 * incluye texto del fichero ni identificadores: son constantes.
 */
internal val IMPORT_ERROR_GUIDANCE: Map<String, String> = mapOf(
    "IMPORT_EXAM_CODE_MISMATCH" to
        "El fichero es de otro examen: el código de la cabecera no coincide con el del examen elegido. " +
        "Elige el examen correcto o corrige la cabecera y vuelve a subirlo.",
    "IMPORT_LOT_DUPLICATE" to
        "Este contenido ya se importó en este examen. No se ha publicado nada: abre el lote anterior en el historial " +
        "o cambia el contenido antes de publicar.",
    "IMPORT_RESERVE_REQUIRES_OFFICIAL_EXAM" to
        "Hay filas de reserva y el examen no es oficial. Marca el examen como oficial o pasa esas filas a principales.",
    "IMPORT_MAIN_AFTER_RESERVE" to
        "El examen ya tiene reservas y este lote añade principales, que quedarían detrás de ellas. " +
        "Importa primero las principales.",
    "IMPORT_BLOCK_CONFLICT" to
        "Una pregunta que ya está en el examen figura aquí con el otro bloque (principal o reserva). " +
        "Corrige el bloque de esa fila u omítela.",
    "IMPORT_DEMO_CONFLICT" to
        "Una pregunta de demostración no puede ser reserva ni estar anulada. Omite esa fila.",
    "IMPORT_OFFICIAL_RULES_INVALID" to
        "Con este lote el examen quedaría inválido (por ejemplo, más preguntas anuladas que reservas). " +
        "No se ha importado nada; revisa las reglas de puntuación del examen.",
)

/** Sustituye el mensaje de un rechazo conocido por su guía de operador; el resto de errores pasan sin tocar. */
internal fun AppError.withImportGuidance(): AppError {
    val validation = this as? AppError.Validation ?: return this
    val text = validation.code?.uppercase()?.let(IMPORT_ERROR_GUIDANCE::get) ?: return this
    return validation.copy(message = text)
}
