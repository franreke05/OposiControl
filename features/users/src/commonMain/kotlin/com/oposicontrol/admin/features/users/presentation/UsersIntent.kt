package com.oposicontrol.admin.features.users.presentation

/**
 * Intenciones de Usuarios.
 *
 * Han desaparecido `UpdateEmail`, `ToggleActive`, `QuickToggleBlock` y
 * `DeleteSelected`. No es una simplificación de diseño: ninguna de las cuatro
 * llegaba a persistir nada. El `PATCH` de usuario sólo escribe `nombre_mostrar`,
 * así que cambiar el email o el estado devolvía éxito sin haber cambiado nada; y
 * eliminar estaba cortado en el cliente con un `error(...)`. Ver
 * [userCapabilities].
 *
 * [CreateNew] sí sobrevive, porque la cabecera de la aplicación la dispara como
 * acción primaria, pero ahora explica que el alta no se hace desde el panel en
 * lugar de abrir un formulario que no puede guardar.
 */
sealed interface UsersIntent {
    /**
     * Vuelve a leer la lista. Es también el reintento cuando la primera lectura falla: la lista se lee una vez al crear
     * el ViewModel, que vive toda la sesión, así que sin esto un fallo pasajero dejaba Usuarios sin datos hasta reiniciar.
     */
    data object Refresh : UsersIntent

    /** El alta está impedida: muestra la explicación, no abre un editor falso. */
    data object CreateNew : UsersIntent
    data object DismissCreateNotice : UsersIntent

    /**
     * Selección pedida desde la lista.
     *
     * Puede quedar en espera si hay cambios sin guardar: en ese caso el estado
     * guarda [UsersState.pendingSelectionUserId] y pregunta antes de descartar.
     */
    data class SelectUser(val userId: String) : UsersIntent
    data object ConfirmDiscardChanges : UsersIntent
    data object CancelPendingSelection : UsersIntent

    data class UpdateName(val value: String) : UsersIntent
    data class ToggleRole(val roleId: String) : UsersIntent
    data object Save : UsersIntent
    data object DiscardChanges : UsersIntent

    // Conceder OposiTickets ya no se hace desde Usuarios: el dialogo era inalcanzable en escritorio y los botones del movil
    // fallaban siempre sin clave de idempotencia. Los ajustes de saldo viven en OposiTickets (con motivo e historico).

    data object OpenWarningDialog : UsersIntent
    data object DismissWarningDialog : UsersIntent
    data class UpdateWarningInput(val value: String) : UsersIntent
    data object ConfirmWarning : UsersIntent

    /** Dar de baja o reactivar la cuenta abierta: pide el motivo en un diálogo y sólo escribe al confirmar. */
    data object OpenAccountStatusDialog : UsersIntent
    data object DismissAccountStatusDialog : UsersIntent
    data class UpdateAccountStatusReason(val value: String) : UsersIntent
    data object ConfirmAccountStatus : UsersIntent

    data class UpdateSearch(val value: String) : UsersIntent
}
