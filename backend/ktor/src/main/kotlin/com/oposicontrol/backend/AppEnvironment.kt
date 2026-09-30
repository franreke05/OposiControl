package com.oposicontrol.backend

/**
 * MASTER-SEC-006: UN solo resolvedor de `APP_ENV` (antes tres sitios discrepaban: el auth consumer trataba el valor
 * vacio como `dev`, `module()` no, y el resto lo dejaba pasar). Falla CERRADO: solo un valor explicito de desarrollo
 * relaja captcha, HTTPS, el limitador en memoria y la clave publicable; vacio/ausente/desconocido es no-desarrollo.
 */
internal object AppEnvironment {
    private val DEVELOPMENT = setOf("dev", "development", "local", "test")

    /** Valor que se usa cuando `APP_ENV` esta vacio o ausente: nunca desarrollo. */
    const val FAIL_CLOSED = "production"

    /** Forma canonica (recortada, en minusculas); vacio/ausente -> [FAIL_CLOSED]. */
    fun resolve(raw: String?): String = raw?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: FAIL_CLOSED

    /** `true` solo para un valor EXPLICITO de desarrollo. */
    fun isDevelopment(raw: String?): Boolean = resolve(raw) in DEVELOPMENT

    fun fromSystemEnvironment(): String = resolve(System.getenv("APP_ENV"))
}
