package com.oposicontrol.admin.domain.model

/**
 * Solicitudes de acceso al panel.
 *
 * Solo administradores y superadministradores entran en OposiControl. Quien no lo es lo PIDE desde la pantalla de acceso
 * ([AccessRequestSubmission]), recibe un codigo de 6 cifras en su correo y lo introduce, junto a la contrasena que elige,
 * para demostrar que el buzon es suyo; un superadministrador revisa la solicitud ([AccessRequest]) y la aprueba —concede el rol `admin`, nunca
 * `superadmin`, y solo si el correo esta verificado— o la rechaza. La cuenta que crea la solicitud nace SIN ACTIVAR: no
 * puede iniciar sesion en ningun sitio hasta que se aprueba. Todo lo decide el servidor: aqui solo viaja lo que la
 * persona escribe.
 */

/** Limites del formulario. Los MISMOS que valida el servidor: aqui solo evitan un viaje que ya se sabe rechazado. */
object AccessRequestRules {
    const val NAME_MAX = 120
    const val EMAIL_MAX = 320
    const val REASON_MAX = 1_000

    /** Contrasena NUEVA: de 12 a 72 caracteres ASCII imprimibles (`^[\x20-\x7E]{12,72}$`, el limite de bcrypt en GoTrue). */
    const val PASSWORD_MIN = 12
    const val PASSWORD_MAX = 72
    const val RESOLUTION_REASON_MAX = 500

    /** El codigo que llega por correo: exactamente 6 cifras ASCII. */
    const val VERIFICATION_CODE_LENGTH = 6

    /** Una solicitud sin verificar caduca a la hora de enviarse (lo decide el servidor; aqui solo se explica). */
    const val UNVERIFIED_EXPIRY_HOURS = 1

    private val EMAIL = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")
    private val NEW_PASSWORD = Regex("[\\x20-\\x7E]{$PASSWORD_MIN,$PASSWORD_MAX}")
    private val VERIFICATION_CODE = Regex("[0-9]{$VERIFICATION_CODE_LENGTH}")

    /**
     * Forma canonica del correo, la MISMA que aplica el servidor, o `null` si no tiene arreglo:
     *  1. fuera los espacios Unicode del principio y del final;
     *  2. si DENTRO queda un espacio o un caracter de control ISO, `null`;
     *  3. minusculas SOLO en A-Z (no `lowercase()`: convierte `İ` en dos puntos de codigo y no coincidiria con GoTrue).
     * Una cadena vacia tras el recorte devuelve `""`.
     */
    fun canonicalEmail(raw: String): String? {
        val stripped = raw.trim()
        if (stripped.any { it.isWhitespace() || it.isISOControl() }) return null
        val out = StringBuilder(stripped.length)
        for (c in stripped) out.append(if (c in 'A'..'Z') c + ('a' - 'A') else c)
        return out.toString()
    }

    /** Forma de correo sobre un valor YA canonico. */
    fun isValidEmail(value: String): Boolean = value.length in 3..EMAIL_MAX && EMAIL.matches(value)

    fun isValidNewPassword(value: String): Boolean = NEW_PASSWORD.matches(value)

    fun isValidVerificationCode(value: String): Boolean = VERIFICATION_CODE.matches(value)
}

/**
 * Lo que la persona envia al PEDIR el acceso. Ya no lleva contrasena (revision de seguridad 2026-10-14): la elige en el paso
 * de verificacion, junto al codigo, cuando ya ha demostrado que el buzon es suyo. `toString` no imprime nada.
 */
class AccessRequestSubmission(
    val name: String,
    val email: String,
    val reason: String,
) {
    override fun toString(): String = "AccessRequestSubmission(<redactado>)"
}

/**
 * Respuesta del servidor a verificar el codigo o pedir otro, cuando HAY respuesta HTTP que interpretar. Un fallo de red o
 * un 5xx no estan aqui: viajan como `AppResult.Failure` (y un 503 llega como `AppError.Network` con codigo 503).
 */
sealed interface AccessRequestCodeOutcome {
    /** Verificar: 204, correo verificado. Reenviar: 202, siempre el mismo (exista o no la solicitud). */
    data object Accepted : AccessRequestCodeOutcome

    /** 400 `INVALID_CREDENTIAL`: codigo mal, caducado o usado, correo sin solicitud, solicitud caducada... todo igual. */
    data object InvalidCode : AccessRequestCodeOutcome

    /** 400 sobre `identifier`: el correo no tiene forma de correo. */
    data object InvalidEmail : AccessRequestCodeOutcome

    /**
     * Verificar: 400 sobre `password`: la contrasena elegida no cumple la regla de contrasena nueva. No dice nada del codigo
     * ni de la solicitud (se decide antes de mirarlos).
     */
    data object InvalidPassword : AccessRequestCodeOutcome

    /** 429: demasiados intentos. [retryAfterSeconds] es la cabecera `Retry-After` (`null` si no vino o no se entiende). */
    data class RateLimited(val retryAfterSeconds: Long?) : AccessRequestCodeOutcome
}

enum class AccessRequestStatus(val wire: String) {
    PENDING("pendiente"),
    APPROVED("aprobada"),
    REJECTED("rechazada"),
    ;

    companion object {
        fun fromWire(value: String?): AccessRequestStatus? = entries.firstOrNull { it.wire == value?.trim()?.lowercase() }
    }
}

data class AccessRequest(
    val id: String,
    val userId: String,
    val name: String,
    val email: String,
    val reason: String,
    val status: AccessRequestStatus,
    /** Instante ISO-8601 tal cual lo da el servidor. */
    val createdAt: String,
    val resolvedAt: String? = null,
    val resolvedBy: String? = null,
    val resolutionReason: String? = null,
    /** El solicitante introdujo el codigo del correo. Lo calcula el servidor. */
    val emailVerified: Boolean = false,
    /** Pendiente, sin verificar y con mas de una hora: ya no se puede aprobar. Lo calcula el servidor. */
    val expired: Boolean = false,
    val emailVerifiedAt: String? = null,
) {
    /** Aprobar solo tiene sentido con el buzon demostrado y a tiempo; el servidor lo vuelve a comprobar (409 si no). */
    val canBeApproved: Boolean get() = status == AccessRequestStatus.PENDING && emailVerified && !expired
}
