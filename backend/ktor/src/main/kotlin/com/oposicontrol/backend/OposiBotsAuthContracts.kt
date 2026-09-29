package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

@Serializable
data class OposiBotsRegisterRequest(
    val identifier: String,
    val password: String,
    val captchaToken: String? = null,
)

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
    val identifier: String,
    val password: String,
    val keepSignedIn: Boolean = true,
)

@Serializable
data class OposiBotsRefreshRequest(val refreshToken: String)

@Serializable
data class OposiBotsRecoverRequest(
    val identifier: String,
    val captchaToken: String? = null,
)

@Serializable
data class OposiBotsResetPasswordRequest(
    val resetToken: String,
    val newPassword: String,
)

@Serializable
data class OposiBotsGoogleRequest(val idToken: String)

@Serializable
data class OposiBotsAppleRequest(
    val identityToken: String,
    val authorizationCode: String,
)

@Serializable
data class OposiBotsProfileRequest(
    val firstName: String,
    val lastName: String,
    val username: String? = null,
    val specialty: String,
)

@Serializable
data class OposiBotsAuthUserDto(
    val id: String,
    val email: String? = null,
    val profileComplete: Boolean,
)

@Serializable
data class OposiBotsAuthSessionDto(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long,
    val user: OposiBotsAuthUserDto,
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
