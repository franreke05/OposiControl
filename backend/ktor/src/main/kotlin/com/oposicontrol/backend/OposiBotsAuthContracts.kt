package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/*
 * SLICE 1: los cuerpos aceptan el cable CANONICO (`email`, sesion con `expiresAt`, usuario con
 * `onboardingCompleted` y `accountStatus`, Apple con `nonce` y `firstAuth*`) Y el LEGACY que sigue enviando
 * OB (`identifier`, `keepSignedIn`, `email` y `fullName` de Apple, `expiresAtEpochSeconds`, `profileComplete`):
 * superconjunto aditivo sobre las mismas rutas de /v1/auth. Los campos desconocidos se toleran durante la
 * ventana de compatibilidad (desviacion registrada en SLICE_1_IMPLEMENTATION respecto de
 * additionalProperties:false; se retira cuando el trafico legacy llegue a cero).
 */

@Serializable
data class OposiBotsRegisterRequest(
    /** LEGACY (deprecated): alias de [email]. */
    val identifier: String? = null,
    val password: String,
    val captchaToken: String? = null,
    val email: String? = null,
) {
    fun resolvedIdentifier(): String = email?.takeIf(String::isNotBlank) ?: identifier.orEmpty()
}

@Serializable
data class OposiBotsRegisterResponse(val verificationRequired: Boolean = true)

@Serializable
data class OposiBotsEmailResendRequest(
    val email: String,
    val captchaToken: String? = null,
)

@Serializable
data class OposiBotsEmailExchangeRequest(val code: String)

@Serializable
data class OposiBotsLoginRequest(
    /** LEGACY (deprecated): alias de [email]. */
    val identifier: String? = null,
    val password: String,
    /** LEGACY: se tolera y se ignora (la sesion la decide GoTrue). */
    val keepSignedIn: Boolean = true,
    val email: String? = null,
) {
    fun resolvedIdentifier(): String = email?.takeIf(String::isNotBlank) ?: identifier.orEmpty()
}

@Serializable
data class OposiBotsRefreshRequest(val refreshToken: String)

@Serializable
data class OposiBotsRecoverRequest(
    /** LEGACY (deprecated): alias de [email]. */
    val identifier: String? = null,
    val captchaToken: String? = null,
    val email: String? = null,
) {
    fun resolvedIdentifier(): String = email?.takeIf(String::isNotBlank) ?: identifier.orEmpty()
}

@Serializable
data class OposiBotsResetPasswordRequest(
    val resetToken: String,
    val newPassword: String,
)

@Serializable
data class OposiBotsGoogleRequest(
    val idToken: String,
    val nonce: String? = null,
)

@Serializable
data class OposiBotsAppleRequest(
    val identityToken: String,
    val authorizationCode: String,
    val nonce: String? = null,
    val firstAuthGivenName: String? = null,
    val firstAuthFamilyName: String? = null,
    val firstAuthEmail: String? = null,
    /** LEGACY (OB los envia; el servidor los ignoraba): alias de [firstAuthEmail] y de nombre y apellidos. */
    val email: String? = null,
    val fullName: String? = null,
) {
    /** (nombre, apellidos) de la primera autenticacion; el alias legacy `fullName` se parte por el primer espacio. */
    fun firstAuthNames(): Pair<String?, String?> {
        val given = firstAuthGivenName?.trim()?.takeIf(String::isNotEmpty)
        val family = firstAuthFamilyName?.trim()?.takeIf(String::isNotEmpty)
        if (given != null || family != null) return given to family
        val parts = fullName?.trim().orEmpty().split(Regex("\\s+"), limit = 2).filter(String::isNotEmpty)
        return parts.getOrNull(0) to parts.getOrNull(1)
    }
}

@Serializable
data class OposiBotsProfileRequest(
    val firstName: String,
    val lastName: String,
    val username: String? = null,
    val specialty: String,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class OposiBotsAuthUserDto(
    val id: String,
    val email: String? = null,
    /** LEGACY (deprecated): igual que [onboardingCompleted]. */
    val profileComplete: Boolean,
    @EncodeDefault val onboardingCompleted: Boolean = profileComplete,
    /** Siempre `activa` en una sesion: la puerta de estado deniega el resto antes de devolverla. */
    @EncodeDefault val accountStatus: String = "activa",
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class OposiBotsAuthSessionDto(
    val accessToken: String,
    val refreshToken: String,
    /** LEGACY (deprecated): igual que [expiresAt]. */
    val expiresAtEpochSeconds: Long,
    val user: OposiBotsAuthUserDto,
    @EncodeDefault val expiresAt: Long = expiresAtEpochSeconds,
)

@Serializable
data class OposiBotsOAuthResponse(
    val session: OposiBotsAuthSessionDto,
    val isNewAccount: Boolean,
)

@Serializable
data class OposiBotsMeResponse(val user: OposiBotsAuthUserDto)

@Serializable
enum class OposiBotsValidationIssue {
    BLANK_IDENTIFIER,
    INVALID_IDENTIFIER,
    BLANK_PASSWORD,
    INVALID_PASSWORD,
    BLANK_FIRST_NAME,
    BLANK_LAST_NAME,
    INVALID_USERNAME,
    SPECIALTY_NOT_SELECTED,
    INVALID_CREDENTIAL,
    INVALID_CAPTCHA,
}

@Serializable
enum class OposiBotsErrorCode {
    Validation,
    AuthenticationRequired,
    AccessDenied,
    Conflict,
    TemporarilyUnavailable,
}

/**
 * Campo del DTO de peticion al que se refiere cada problema de validacion
 * (`details.issues[].field` del sobre canonico).
 */
internal fun OposiBotsValidationIssue.field(): String = when (this) {
    OposiBotsValidationIssue.BLANK_IDENTIFIER, OposiBotsValidationIssue.INVALID_IDENTIFIER -> "identifier"
    OposiBotsValidationIssue.BLANK_PASSWORD, OposiBotsValidationIssue.INVALID_PASSWORD -> "password"
    OposiBotsValidationIssue.BLANK_FIRST_NAME -> "firstName"
    OposiBotsValidationIssue.BLANK_LAST_NAME -> "lastName"
    OposiBotsValidationIssue.INVALID_USERNAME -> "username"
    OposiBotsValidationIssue.SPECIALTY_NOT_SELECTED -> "specialty"
    OposiBotsValidationIssue.INVALID_CREDENTIAL -> "credential"
    OposiBotsValidationIssue.INVALID_CAPTCHA -> "captchaToken"
}

/** Nombre del enum legacy `error` que OB (`AuthErrorWire`) espera para un estado HTTP. */
internal fun legacyAuthErrorFor(status: HttpStatusCode): OposiBotsErrorCode = when {
    status == HttpStatusCode.Unauthorized -> OposiBotsErrorCode.AuthenticationRequired
    status == HttpStatusCode.Forbidden -> OposiBotsErrorCode.AccessDenied
    status == HttpStatusCode.Conflict -> OposiBotsErrorCode.Conflict
    status.value in 400..499 -> OposiBotsErrorCode.Validation
    else -> OposiBotsErrorCode.TemporarilyUnavailable
}

internal class OposiBotsAuthProblem(
    val status: HttpStatusCode,
    val code: OposiBotsErrorCode,
    val issues: Set<OposiBotsValidationIssue> = emptySet(),
    val retryAfterSeconds: Long? = null,
) : RuntimeException(code.name) {
    /**
     * Codigo canonico (SLICE 0): Validation->VALIDATION,
     * AuthenticationRequired->UNAUTHENTICATED, AccessDenied->FORBIDDEN,
     * Conflict->CONFLICT, TemporarilyUnavailable->UPSTREAM_UNAVAILABLE. El
     * limite de peticiones reutiliza TemporarilyUnavailable con 429: ahi el
     * codigo canonico es RATE_LIMITED, que es el del estado.
     */
    fun canonicalCode(): CanonicalErrorCode = when (code) {
        OposiBotsErrorCode.Validation -> CanonicalErrorCode.VALIDATION
        OposiBotsErrorCode.AuthenticationRequired -> CanonicalErrorCode.UNAUTHENTICATED
        OposiBotsErrorCode.AccessDenied -> CanonicalErrorCode.FORBIDDEN
        OposiBotsErrorCode.Conflict -> CanonicalErrorCode.CONFLICT
        OposiBotsErrorCode.TemporarilyUnavailable ->
            if (status == HttpStatusCode.TooManyRequests) CanonicalErrorCode.RATE_LIMITED else CanonicalErrorCode.UPSTREAM_UNAVAILABLE
    }

    /**
     * El sobre canonico con los campos LEGACY de OB (`error` = nombre del enum,
     * `issues`) hasta que ambos clientes lean `code`.
     */
    fun response(requestId: String): ErrorDto {
        val canonical = canonicalCode()
        return ErrorDto(
            error = code.name,
            message = canonical.defaultMessage,
            code = canonical.name,
            requestId = requestId,
            details = issues.takeIf { it.isNotEmpty() }
                ?.let { set -> ErrorDetails(issues = set.map { ErrorIssue(it.field(), it.name) }) },
            issues = issues.map { it.name },
        )
    }

    companion object {
        fun validation(vararg issues: OposiBotsValidationIssue) =
            OposiBotsAuthProblem(HttpStatusCode.BadRequest, OposiBotsErrorCode.Validation, issues.toSet())

        fun authentication() =
            OposiBotsAuthProblem(HttpStatusCode.Unauthorized, OposiBotsErrorCode.AuthenticationRequired)

        fun accessDenied() =
            OposiBotsAuthProblem(HttpStatusCode.Forbidden, OposiBotsErrorCode.AccessDenied)

        fun conflict() =
            OposiBotsAuthProblem(HttpStatusCode.Conflict, OposiBotsErrorCode.Conflict)

        fun unavailable(retryAfterSeconds: Long? = null) =
            OposiBotsAuthProblem(
                status = if (retryAfterSeconds == null) HttpStatusCode.ServiceUnavailable else HttpStatusCode.TooManyRequests,
                code = OposiBotsErrorCode.TemporarilyUnavailable,
                retryAfterSeconds = retryAfterSeconds,
            )
    }
}
