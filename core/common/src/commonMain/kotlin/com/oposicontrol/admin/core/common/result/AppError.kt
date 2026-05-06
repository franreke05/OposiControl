package com.oposicontrol.admin.core.common.result

sealed interface AppError {
    val message: String

    data class Network(
        override val message: String,
        val code: Int? = null,
    ) : AppError

    data class Authentication(
        override val message: String,
    ) : AppError

    data class Authorization(
        override val message: String,
    ) : AppError

    data class Validation(
        override val message: String,
    ) : AppError

    data class NotFound(
        override val message: String,
    ) : AppError

    data class Unknown(
        override val message: String,
        val cause: Throwable? = null,
    ) : AppError
}
