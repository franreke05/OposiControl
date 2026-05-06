package com.oposicontrol.admin.core.common.presentation

import com.oposicontrol.admin.core.common.result.AppError

sealed interface UiAsync<out T> {
    data object Idle : UiAsync<Nothing>
    data object Loading : UiAsync<Nothing>
    data object Empty : UiAsync<Nothing>
    data class Success<T>(
        val value: T,
    ) : UiAsync<T>

    data class Error(
        val error: AppError,
    ) : UiAsync<Nothing>
}
