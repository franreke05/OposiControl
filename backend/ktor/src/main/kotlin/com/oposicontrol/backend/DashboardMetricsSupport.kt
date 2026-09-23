package com.oposicontrol.backend

/**
 * Soporte para métricas honestas del dashboard admin.
 *
 * Antes, cada métrica se calculaba con `catch { 0 }` / `getOrDefault(0)`, de modo
 * que un fallo de lectura era indistinguible de un cero real: el panel enseñaba
 * "0 exámenes" cuando en realidad no había podido preguntar. Además los conteos
 * se hacían trayendo todas las filas (`select=id` + `.size`), lo que PostgREST
 * recorta silenciosamente al llegar a su `max-rows`, así que el número mostrado
 * podía ser simplemente falso.
 *
 * Aquí viven las dos piezas puras de esa corrección: el identificador estable de
 * cada métrica (para poder decir *cuál* no está disponible) y el parser de
 * `Content-Range`, que es como PostgREST devuelve un conteo exacto sin
 * transferir las filas.
 */
object DashboardMetricKey {
    const val TOTAL_SPECIALTIES = "totalSpecialties"
    const val TOTAL_EXAMS = "totalExams"
    const val PUBLISHED_EXAMS = "publishedExams"
    const val DRAFT_EXAMS = "draftExams"
    const val TOTAL_QUESTIONS = "totalQuestions"
    const val TOTAL_ATTEMPTS = "totalAttempts"
    const val ACTIVE_USERS_30D = "activeUsers30d"
    const val AVG_SCORE = "avgScore"
    const val GLOBAL_ERROR_RATE = "globalErrorRate"
    const val ATTEMPTS_PER_DAY = "attemptsPerDay"
}

/**
 * Extrae el total exacto de una cabecera `Content-Range` de PostgREST.
 *
 * PostgREST responde `Content-Range: <primera>-<ultima>/<total>` cuando se pide
 * `Prefer: count=exact`. El total es `*` si no se pidió el conteo, y el rango es
 * `*` cuando el resultado está vacío (`*​/0`).
 *
 * Devuelve `null` cuando el total no se conoce, para que quien llame distinga
 * "no disponible" de "cero" en lugar de inventar un 0.
 */
internal fun parseContentRangeTotal(header: String?): Int? {
    val total = header?.substringAfter('/', missingDelimiterValue = "")?.trim().orEmpty()
    if (total.isEmpty() || total == "*") return null
    return total.toIntOrNull()?.takeIf { it >= 0 }
}
