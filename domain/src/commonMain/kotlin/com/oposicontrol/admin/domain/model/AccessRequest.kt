package com.oposicontrol.admin.domain.model

/**
 * Solicitudes de acceso al panel.
 *
 * Solo administradores y superadministradores entran en OposiControl. Quien no lo es lo PIDE desde la pantalla de acceso
 * ([AccessRequestSubmission]); un superadministrador revisa la solicitud ([AccessRequest]) y la aprueba —concede el rol
 * `admin`, nunca `superadmin`— o la rechaza. La cuenta que crea la solicitud nace SIN ACTIVAR: no puede iniciar sesion en
 * ningun sitio hasta que se aprueba. Todo lo decide el servidor: aqui solo viaja lo que la persona escribe.
 */

/** Limites del formulario. Los MISMOS que valida el servidor: aqui solo evitan un viaje que ya se sabe rechazado. */
object AccessRequestRules {
    const val NAME_MAX = 120
    const val EMAIL_MAX = 320
    const val REASON_MAX = 1_000
    const val PASSWORD_MIN = 12
    const val PASSWORD_MAX = 128
    const val RESOLUTION_REASON_MAX = 500

    private val EMAIL = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")

    fun isValidEmail(value: String): Boolean = value.length in 3..EMAIL_MAX && EMAIL.matches(value)
}

/** Lo que la persona envia. `toString` no imprime nada: lleva una contrasena. */
class AccessRequestSubmission(
    val name: String,
    val email: String,
    val password: String,
    val reason: String,
) {
    override fun toString(): String = "AccessRequestSubmission(<redactado>)"
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
)
