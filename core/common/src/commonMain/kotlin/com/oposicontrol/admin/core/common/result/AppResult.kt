package com.oposicontrol.admin.core.common.result

sealed interface AppResult<out T> {
    data class Success<T>(
        val value: T,
    ) : AppResult<T>

    data class Failure(
        val error: AppError,
    ) : AppResult<Nothing>
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> {
    return when (this) {
        is AppResult.Failure -> this
        is AppResult.Success -> AppResult.Success(transform(value))
    }
}

inline fun <T> AppResult<T>.onSuccess(block: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) block(value)
    return this
}

fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Success<T>)?.value
