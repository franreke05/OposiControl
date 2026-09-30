package com.oposicontrol.admin.domain.model

import kotlinx.datetime.Instant

/**
 * Estado de cuenta canonico (`AccountStatus` del contrato). [UNKNOWN] cubre tanto un backend que aun no
 * lo emite como un valor no reconocido: nunca se presenta como activa (deny-by-default).
 */
enum class AccountStatus(val wire: String?) {
    ACTIVE("activa"),
    BLOCKED("bloqueada"),
    DELETION_PENDING("eliminacion_pendiente"),
    UNKNOWN(null);

    companion object {
        fun fromWire(value: String?): AccountStatus =
            entries.firstOrNull { it.wire != null && it.wire == value } ?: UNKNOWN
    }
}

data class User(
    val id: String,
    val fullName: String,
    val email: String,
    val roleIds: List<String>,
    /**
     * Cuenta registrada (`!es_invitado`); NO es el estado de cuenta ni de acceso, ese es [accountStatus]. En el cable
     * legacy sigue llamandose `isActive` (`UserDto.isActive`) hasta que el backend lo renombre. Null = el backend no lo informo.
     */
    val isRegistered: Boolean?,
    /** Null si el backend no lo informo o no es parseable: no se fabrica una fecha. */
    val createdAt: Instant?,
    val ticketsBalance: Int = 0,
    val accountStatus: AccountStatus = AccountStatus.UNKNOWN,
    val username: String? = null,
    val authProviders: List<String> = emptyList(),
    val onboardingCompleted: Boolean? = null,
)
