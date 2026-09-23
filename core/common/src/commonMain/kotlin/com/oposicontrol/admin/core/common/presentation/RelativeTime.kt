package com.oposicontrol.admin.core.common.presentation

import kotlinx.datetime.Instant

/**
 * "hace 5 min", "hace 3 h", "el 12/05/2026". Estaba escrita tres veces, byte a
 * byte, en Informes (`formatRelativeTime`), Foro (`formatForumRelativeTime`) y
 * OposiTester (`formatTesterRelativeTime`) — cada una en su propio módulo de
 * feature, sin depender entre sí, así que la copia vivía en el único sitio que
 * los tres ya comparten: `core/common`.
 *
 * `iso` en blanco o ilegible da cadena vacía: no hay fecha que "hace ..." pueda
 * inventar. Pasado el umbral semanal se cae a fecha corta en vez de seguir
 * contando días.
 */
fun formatRelativeTime(iso: String, now: Instant): String {
    if (iso.isBlank()) return ""
    val instant = runCatching { Instant.parse(iso) }.getOrNull() ?: return ""
    val minutes = (now - instant).inWholeMinutes
    return when {
        minutes < 1 -> "ahora mismo"
        minutes < 60 -> "hace $minutes min"
        minutes < 60 * 24 -> "hace ${minutes / 60} h"
        minutes < 60 * 24 * 7 -> "hace ${minutes / (60 * 24)} d"
        iso.length >= 10 -> "el ${iso.substring(8, 10)}/${iso.substring(5, 7)}/${iso.substring(0, 4)}"
        else -> ""
    }
}
