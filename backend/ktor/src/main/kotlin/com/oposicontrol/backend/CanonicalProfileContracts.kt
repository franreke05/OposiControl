package com.oposicontrol.backend

import kotlinx.serialization.Serializable

// =====================================================================
// CONTRATOS CANONICOS DE PERFIL (SLICE 1, `openapi/profiles/profiles.yaml`, FROZEN)
//
// D1-11 GET /api/v1/app/me, D1-12 POST /me/onboarding, D1-13 PATCH /me/profile,
// D1-16 GET /legal/documents, D1-17 POST /me/legal-acceptances,
// D1-19 DELETE /me. La identidad es SIEMPRE el `sub` del JWT: ningun cuerpo
// lleva id de usuario. Los DTO de comando llevan SOLO campos USER_INPUT y se
// decodifican estrictos (`receiveCanonicalCommand`).
// =====================================================================

/** `Username` del contrato: `^[A-Za-z0-9._-]{3,30}$`. */
internal val USERNAME_PATTERN = Regex("^[A-Za-z0-9._-]{3,30}$")

@Serializable
data class AttributionHints(
    val fuente: String? = null,
    val canal: String? = null,
    val campania: String? = null,
    val plataforma: String? = null,
    val installReferrer: String? = null,
)

@CanonicalCommand
@Serializable
data class OnboardingRequest(
    val username: String,
    val nombre: String,
    val apellidos: String,
    val especialidadId: String? = null,
    val acceptedLegalDocumentIds: List<String>,
    val attribution: AttributionHints? = null,
    val referralCode: String? = null,
)

@Serializable
data class ProfileDetailPatch(
    val edad: Int? = null,
    val ciudad: String? = null,
    val estadoLaboral: String? = null,
    val centroTrabajo: String? = null,
    val tiempoEstudioMin: Int? = null,
    val franja: String? = null,
    val fechaExamenObjetivo: String? = null,
)

/**
 * PATCH parcial con semantica de conjunto. `especialidadId` REEMPLAZA la unica
 * especialidad activa (DEC-002): escalar, nunca lista, no se puede vaciar.
 */
@CanonicalCommand
@Serializable
data class ProfilePatchRequest(
    val username: String? = null,
    val nombre: String? = null,
    val apellidos: String? = null,
    val especialidadId: String? = null,
    val comunidadPrincipalId: String? = null,
    val detail: ProfileDetailPatch? = null,
    val targetCommunityIds: List<String>? = null,
    val introSeen: Boolean? = null,
)

@CanonicalCommand
@Serializable
data class LegalAcceptanceRequest(
    val documentIds: List<String>,
    val plataforma: String,
)

@CanonicalCommand
@Serializable
data class AccountDeletionRequest(
    val confirmation: String,
    val currentPassword: String? = null,
)

// ---------- respuestas (derivadas: solo salida) ----------

@Serializable
data class MeProfile(
    val username: String? = null,
    val nombreMostrar: String? = null,
    val nombre: String? = null,
    val apellidos: String? = null,
    val especialidadPrincipalId: String? = null,
    val comunidadPrincipalId: String? = null,
    /** D1-14 (FA42): URL FIRMADA y corta del objeto propio `avatars/{uid}/{sha256}.jpg`; `null` sin avatar o si lo guardado no es del servidor. */
    val avatarUrl: String? = null,
    /** FA53: avatar predefinido elegido (1..10) o `null` (sin predefinido; puede haber foto antigua en [avatarUrl]). Nunca ambos. */
    val avatarId: Int? = null,
    val onboardingCompleted: Boolean,
    val accountStatus: String,
    val introSeenAt: String? = null,
)

@Serializable
data class Me(
    val id: String,
    val email: String? = null,
    val authProviders: List<String>,
    val registeredAt: String,
    val profile: MeProfile,
)

@Serializable
data class LegalDocument(
    val id: String,
    val tipo: String,
    val version: String,
    val titulo: String,
    val contenido: String,
    val publicadoEn: String,
)

@Serializable
data class LegalDocumentList(val items: List<LegalDocument>)

@Serializable
data class PendingLegalDocument(val id: String, val tipo: String, val version: String)

@Serializable
data class LegalAcceptanceResponse(val pendingLegalDocuments: List<PendingLegalDocument>)

/**
 * D1-14 200. FA53: predefinido -> solo [avatarId] (1..10); foto (FA42, solo con AVATAR_PHOTO_UPLOADS_ENABLED) -> URL firmada del avatar recien
 * guardado y su caducidad (instante ISO). Los `null` no viajan (`explicitNulls = false`): ausente = null.
 */
@Serializable
data class AvatarResponse(
    val avatarId: Int? = null,
    val avatarUrl: String? = null,
    val expiresAt: String? = null,
)

/** D1-14 con cuerpo JSON (FA53): el avatar predefinido elegido, 1..10 (fuera de rango 422 AVATAR_ID_INVALID). */
@CanonicalCommand
@Serializable
data class AvatarPresetRequest(val avatarId: Int)

@Serializable
data class AccountDeletionAccepted(
    val accountStatus: String,
    val requestedAt: String,
)
