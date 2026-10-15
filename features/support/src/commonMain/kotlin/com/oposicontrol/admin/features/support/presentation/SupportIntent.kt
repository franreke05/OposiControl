package com.oposicontrol.admin.features.support.presentation

/**
 * Sin «Asignado a» ni filtro de estado: el backend ignora el responsable (`assignedTo` siempre vacío) y no publica un
 * conjunto cerrado de estados de conversación (solo el defecto `abierta`), así que ninguno de los dos hacía nada real.
 */
sealed interface SupportIntent {
    data object Refresh : SupportIntent
    data object SendReply : SupportIntent
    data class SelectConversation(val id: String) : SupportIntent
    data class UpdateSearch(val value: String) : SupportIntent
    data class UpdateReplyText(val value: String) : SupportIntent
}
