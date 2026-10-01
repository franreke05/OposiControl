package com.oposicontrol.backend

/**
 * MASTER5-API-005 / MASTER6-SEC-002: un nombre visible no admite caracteres de control (p{Cc}), de formato invisibles
 * (p{Cf}: bidi U+202A..U+202E / U+2066..U+2069, ancho cero U+200B..U+200D, BOM U+FEFF...), separadores de linea o de
 * parrafo (p{Zl}/p{Zp}) ni sustitutos UTF-16 sueltos. Consecuencia aceptada: el ZWJ (U+200D) de los emojis compuestos
 * tambien se rechaza.
 *
 * MASTER7-ARCH-001: regla UNICA compartida por la escritura admin (D1-25 `nombreMostrar`) y las escrituras del propio
 * usuario (`nombre` / `apellidos` en onboarding y PATCH de perfil, y los nombres de primer acceso de Apple). Extiende
 * el rechazo del ZWJ a los nombres de usuario (registrado en ARCHITECTURE_DELTA_S1 para revision del owner); no hay
 * lista de emojis permitidos.
 */
internal fun isAcceptableDisplayName(name: String): Boolean {
    var i = 0
    while (i < name.length) {
        val cp = name.codePointAt(i)
        when (Character.getType(cp).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SURROGATE ->
                return false
        }
        i += Character.charCount(cp)
    }
    return true
}
