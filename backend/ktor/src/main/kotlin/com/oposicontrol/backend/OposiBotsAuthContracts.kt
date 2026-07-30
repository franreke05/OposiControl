package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Transport contracts for the OposiBots V2 consumer application.
 *
 * These DTOs intentionally do not reuse OposiControl admin or domain models.
 * The live OposiBots repository was not available when this contract snapshot
 * was integrated, so this surface remains isolated under `/v1/auth`.
 */
@Serializable
data class OposiBotsLoginRequest(
    val identifier: String,
    val password: String,
    val keepSignedIn: Boolean,
)

@Serializable
data class OposiBotsLoginResponse(
    val sessionToken: String,
)

@Serializable
data class OposiBotsRegisterRequest(
    val identifier: String,
    val password: String,
)

@Serializable
data class OposiBotsRegisterResponse(
    val sessionToken: String,
)

@Serializable
data class OposiBotsRegisterAboutYouRequest(
    val firstName: String,
    val lastName: String,
    val username: String? = null,
    val specialty: String,
)

@Serializable
data class OposiBotsRecoverAccessRequest(
    val identifier: String,
)

@Serializable
data class OposiBotsResetPasswordRequest(
    val resetToken: String,
    val newPassword: String,
)

@Serializable
data class OposiBotsGoogleSignInRequest(
    val idToken: String,
)

@Serializable
data class OposiBotsAppleSignInRequest(
    val identityToken: String,
    val authorizationCode: String,
    val email: String? = null,
    val fullName: String? = null,
)

@Serializable
data class OposiBotsOAuthSignInResponse(
    val sessionToken: String,
    val isNewAccount: Boolean,
)

/**
 * Exact validation vocabulary currently understood by the OposiBots client.
 * Do not repurpose an entry for a semantically different field.
 */
@Serializable
enum class OposiBotsValidationIssue {
    BLANK_IDENTIFIER,
    BLANK_PASSWORD,
    PASSWORD_CONFIRMATION_MISMATCH,
    BLANK_FIRST_NAME,
    BLANK_LAST_NAME,
    SPECIALTY_NOT_SELECTED,
    INVALID_CURRENCY_CODE,
    NEGATIVE_TICKET_BALANCE,
    NON_POSITIVE_TICKET_AMOUNT,
    SCORE_OUT_OF_RANGE,
    PERCENTAGE_OUT_OF_RANGE,
}

@Serializable
enum class OposiBotsDomainErrorCode {
    @SerialName("Validation")
    VALIDATION,

    @SerialName("AuthenticationRequired")
    AUTHENTICATION_REQUIRED,

    @SerialName("AccessDenied")
    ACCESS_DENIED,

    @SerialName("NotFound")
    NOT_FOUND,

    @SerialName("Conflict")
    CONFLICT,

    @SerialName("TemporarilyUnavailable")
    TEMPORARILY_UNAVAILABLE,
}

/**
 * Stable wire representation of the client's `DomainError`.
 *
 * No human-readable upstream message is returned because authentication
 * responses must not reveal whether an account exists.
 */
@Serializable
data class OposiBotsDomainErrorResponse(
    val error: OposiBotsDomainErrorCode,
    val issues: Set<OposiBotsValidationIssue> = emptySet(),
)

internal class OposiBotsAuthProblem(
    val status: HttpStatusCode,
    val domainError: OposiBotsDomainErrorCode,
    val validationIssues: Set<OposiBotsValidationIssue> = emptySet(),
    cause: Throwable? = null,
) : RuntimeException(domainError.name, cause) {
    fun response(): OposiBotsDomainErrorResponse =
        OposiBotsDomainErrorResponse(
            error = domainError,
            issues = validationIssues,
        )

    companion object {
        fun validation(issues: Set<OposiBotsValidationIssue>) = OposiBotsAuthProblem(
            status = HttpStatusCode.BadRequest,
            domainError = OposiBotsDomainErrorCode.VALIDATION,
            validationIssues = issues,
        )

        fun authenticationRequired() = OposiBotsAuthProblem(
            status = HttpStatusCode.Unauthorized,
            domainError = OposiBotsDomainErrorCode.AUTHENTICATION_REQUIRED,
        )

        fun accessDenied() = OposiBotsAuthProblem(
            status = HttpStatusCode.Forbidden,
            domainError = OposiBotsDomainErrorCode.ACCESS_DENIED,
        )

        fun notFound() = OposiBotsAuthProblem(
            status = HttpStatusCode.NotFound,
            domainError = OposiBotsDomainErrorCode.NOT_FOUND,
        )

        fun conflict() = OposiBotsAuthProblem(
            status = HttpStatusCode.Conflict,
            domainError = OposiBotsDomainErrorCode.CONFLICT,
        )

        fun temporarilyUnavailable(
            rateLimited: Boolean = false,
            cause: Throwable? = null,
        ) = OposiBotsAuthProblem(
            status = if (rateLimited) HttpStatusCode.TooManyRequests else HttpStatusCode.ServiceUnavailable,
            domainError = OposiBotsDomainErrorCode.TEMPORARILY_UNAVAILABLE,
            cause = cause,
        )
    }
}
