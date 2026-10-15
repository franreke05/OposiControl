package com.oposicontrol.admin.features.moderation.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync

data class ModerationState(
    val actions: UiAsync<List<ModerationActionRow>> = UiAsync.Loading,
    val searchQuery: String = "",
    val selectedId: String? = null,
    // La unica medida que el backend aplica (MODERATION_ACTION_TYPES): no hay selector.
    val editorActionType: String = MODERATION_ACTION_WARNING,
    val editorTargetUser: String = "",
    // Solo informativo: el backend firma con el `sub` del JWT. Vacio = la sesion no trae nombre (nunca uno inventado).
    val editorExecutor: String = "",
    val editorReason: String = "",
    val actionState: UiAsync<Unit> = UiAsync.Idle,
)

/** Valor de `actionType` que acepta `POST /api/v1/admin/moderacion/actions`. El resto es 422 (no se aplica en V1). */
const val MODERATION_ACTION_WARNING = "advertencia"
