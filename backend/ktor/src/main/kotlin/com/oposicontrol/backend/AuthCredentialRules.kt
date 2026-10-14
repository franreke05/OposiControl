package com.oposicontrol.backend

// =====================================================================
// Reglas UNICAS de correo y contrasena del plano de auth (plan del propietario, 2026-10-14, puntos 5 y 6).
//
// Correo canonico: [canonicalAuthEmail]. Es la MISMA funcion en register, login, resend, recover, la solicitud de acceso
// de administrador (alta, verificacion y reenvio del codigo), la guarda de correo, los seudonimos de log y lo que se envia
// a GoTrue. Antes cada sitio hacia `trim()` o `trim().lowercase()` por su cuenta, y resend/recover mandaban a GoTrue el
// correo solo recortado.
//
// Contrasena NUEVA: [isAcceptableNewPassword] (registro, reset, solicitud de acceso). El login NO la usa: comprueba una
// contrasena que ya existe y conserva su regla de siempre (12..128 caracteres) para no dejar fuera a nadie.
// =====================================================================

/**
 * Forma canonica de un correo del plano de auth, o `null` si no tiene arreglo:
 *
 *  1. Se quitan los espacios Unicode del principio y del final (`Char.isWhitespace`: ASCII, U+00A0, U+2003, tabuladores...).
 *  2. Si DENTRO queda cualquier espacio o caracter de control ISO, es `null` (el llamador responde 400 INVALID_IDENTIFIER).
 *  3. Se pasan a minusculas SOLO las letras ASCII A-Z. NO `String.lowercase()`: el minusculado Unicode de Kotlin convierte
 *     `İ` (U+0130) en `i̇` (dos puntos de codigo) y no coincide con lo que guarda GoTrue; cualquier otro caracter se deja tal cual.
 *
 * Una cadena vacia tras el recorte devuelve `""` (el llamador la trata como identificador en blanco). No valida la forma de
 * correo: eso es [isValidAuthEmailShape], sobre el resultado.
 */
internal fun canonicalAuthEmail(raw: String): String? {
    val stripped = raw.trim()
    if (stripped.any { it.isWhitespace() || it.isISOControl() }) return null
    val out = StringBuilder(stripped.length)
    for (c in stripped) out.append(if (c in 'A'..'Z') c + ('a' - 'A') else c)
    return out.toString()
}

/** Forma de correo sobre un valor YA canonico: 3..320 caracteres, una `@`, dominio con punto, sin espacios. */
internal fun isValidAuthEmailShape(canonical: String): Boolean =
    canonical.length in 3..320 && canonical.none(Char::isWhitespace) && AUTH_EMAIL_REGEX.matches(canonical)

/**
 * El correo canonico o el 400 de siempre: `BLANK_IDENTIFIER` si no queda nada, `INVALID_IDENTIFIER` si tiene espacios o
 * controles dentro o no tiene forma de correo. Depende solo de la FORMA del valor, nunca de que exista nada: no revela nada.
 */
internal fun requireCanonicalAuthEmail(raw: String): String {
    val canonical = canonicalAuthEmail(raw) ?: throw OposiBotsAuthProblem.validation(OposiBotsValidationIssue.INVALID_IDENTIFIER)
    if (canonical.isEmpty()) throw OposiBotsAuthProblem.validation(OposiBotsValidationIssue.BLANK_IDENTIFIER)
    if (!isValidAuthEmailShape(canonical)) throw OposiBotsAuthProblem.validation(OposiBotsValidationIssue.INVALID_IDENTIFIER)
    return canonical
}

/** El problema de identificador de un correo crudo, o `null` si es valido (para juntarlo con el de la contrasena). */
internal fun authEmailIssue(raw: String): OposiBotsValidationIssue? {
    val canonical = canonicalAuthEmail(raw) ?: return OposiBotsValidationIssue.INVALID_IDENTIFIER
    return when {
        canonical.isEmpty() -> OposiBotsValidationIssue.BLANK_IDENTIFIER
        !isValidAuthEmailShape(canonical) -> OposiBotsValidationIssue.INVALID_IDENTIFIER
        else -> null
    }
}

/** Longitud maxima de una contrasena nueva: 72, el limite real de bcrypt en GoTrue (en bytes; con ASCII, bytes = caracteres). */
internal const val AUTH_NEW_PASSWORD_MAX_LENGTH = 72
internal const val AUTH_NEW_PASSWORD_MIN_LENGTH = 12

/**
 * `^[\x20-\x7E]{12,72}$`: de 12 a 72 caracteres ASCII IMPRIMIBLES (del espacio a la tilde, ambos incluidos). Una sola
 * unidad: caracteres = bytes, asi que el tope de 72 bytes de bcrypt nunca se pasa y ningun caracter no ASCII (ñ, emoji,
 * espacios Unicode) entra en una contrasena nueva. Es la regla del contrato (`openapi/auth/auth.yaml`, esquema `Password`,
 * enmienda FA17).
 */
private val NEW_PASSWORD_REGEX = Regex("[\\x20-\\x7E]{$AUTH_NEW_PASSWORD_MIN_LENGTH,$AUTH_NEW_PASSWORD_MAX_LENGTH}")

internal fun String.isAcceptableNewPassword(): Boolean = NEW_PASSWORD_REGEX.matches(this)

private val AUTH_EMAIL_REGEX = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")
