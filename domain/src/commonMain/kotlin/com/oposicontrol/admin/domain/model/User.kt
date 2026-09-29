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
    /** OJO: el backend lo calcula como `!es_invitado`, no es el estado de cuenta; ese es [accountStatus]. */
    val isActive: Boolean,
    val createdAt: Instant,
    val ticketsBalance: Int = 0,
    val accountStatus: AccountStatus = AccountStatus.UNKNOWN,
    val username: String? = null,
    val authProviders: List<String> = emptyList(),
    val onboardingCompleted: Boolean? = null,
)
