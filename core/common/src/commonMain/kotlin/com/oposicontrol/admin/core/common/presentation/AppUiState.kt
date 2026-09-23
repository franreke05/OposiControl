package com.oposicontrol.admin.core.common.presentation

enum class AppUiPhase {
    INITIAL,
    LOADING,
    CONTENT,
    EMPTY,
    ERROR,
    SAVING,
    SAVE_SUCCESS,
    SAVE_ERROR,
    DELETING,
    DELETE_SUCCESS,
    DELETE_ERROR,
    UNSAVED_CHANGES,
    OFFLINE,
    BACKEND_UNAVAILABLE,
    FORBIDDEN,
    NOT_FOUND,
    RETRYABLE_ERROR,
}

enum class UiErrorCategory {
    VALIDATION,
    AUTHENTICATION,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    NETWORK,
    BACKEND_UNAVAILABLE,
    RETRYABLE,
    UNKNOWN,
}

data class UiError(
    val category: UiErrorCategory,
    val message: String,
    val canRetry: Boolean = false,
)
