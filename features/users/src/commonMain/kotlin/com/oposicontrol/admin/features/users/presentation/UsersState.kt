package com.oposicontrol.admin.features.users.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.Role
import com.oposicontrol.admin.domain.model.User
import com.oposicontrol.admin.domain.model.UserDetail

/**
 * Estado de la pantalla de Usuarios.
 *
 * Respecto a la versión anterior faltan tres campos a propósito:
 *
 * - `editorEmail`: el campo que el backend llama `email` es en realidad
 *   `username`, y el guardado lo descarta. Mantenerlo editable hacía creer que se
 *   estaba corrigiendo el correo de alguien. Ahora se enseña como dato de sólo
 *   lectura. Ver [USER_ACCOUNT_FIELD_LABEL].
 * - `editorIsActive`: `User.isRegistered` es `!es_invitado`, no un estado de acceso,
 *   y el `PATCH` ni siquiera lo escribe. Ver [UserAccountKind].
 * - el par de acciones de bloqueo, por lo mismo.
 *
 * Y sobra uno nuevo, [pendingSelectionUserId], que es el que impide perder
 * cambios al saltar de usuario.
 */
/** Concesion de tickets con resultado desconocido: usuario, importe y la clave de idempotencia con la que se envio. */
data class PendingGrant(val userId: String, val amount: Int, val key: String)

data class UsersState(
    val users: UiAsync<List<User>> = UiAsync.Loading,
    val availableRoles: List<Role> = emptyList(),
    val selectedUserId: String? = null,
    val searchQuery: String = "",
    val editorName: String = "",
    val editorRoleIds: Set<String> = emptySet(),
    val actionState: UiAsync<Unit> = UiAsync.Idle,
    /**
     * Ficha de solo lectura del usuario seleccionado. Estado propio: se carga
     * aparte del formulario y su fallo no debe impedir editar nombre o roles.
     */
    val detail: UiAsync<UserDetail> = UiAsync.Idle,
    /**
     * Usuario al que se quiere saltar teniendo cambios sin guardar.
     *
     * Mientras no sea null la pantalla pide confirmación. El editor anterior
     * sobrescribía el formulario en silencio al pulsar otra fila, así que un
     * nombre a medio corregir se perdía sin ningún aviso.
     */
    val pendingSelectionUserId: String? = null,
    /**
     * Usuario que se pidió abrir desde otra pantalla (OposiTickets, un uso de promoción) cuando la lista todavía no
     * estaba cargada. Se abre en cuanto llega; sin esto la petición se perdía en silencio y se aterrizaba en otra ficha.
     */
    val pendingOpenUserId: String? = null,
    /** Se pidió abrir un usuario que no está en la lista cargada: se dice, en vez de enseñar a otra persona. */
    val openUserMissing: Boolean = false,
    /** Se está releyendo una lista que ya estaba en pantalla: se sigue viendo mientras tanto. */
    val refreshing: Boolean = false,
    /** La última relectura falló: se conserva lo que ya se había leído, y se dice. */
    val refreshFailed: Boolean = false,
    /** El alta está impedida; se explica en vez de abrir un editor que no guarda. */
    val createNoticeVisible: Boolean = false,
    // Quick-action dialogs
    val ticketsDialogOpen: Boolean = false,
    val ticketsInput: String = "",
    /**
     * Clave de idempotencia de la concesión en curso. Se fija al abrir el
     * diálogo para que reintentos y dobles clics no concedan dos veces.
     */
    val ticketsIdempotencyKey: String? = null,
    /**
     * Concesion cuyo resultado no se conoce (fallo de red, timeout, respuesta perdida): el servidor pudo haberla
     * aplicado. Conserva su clave para que reabrir el dialogo y confirmar el MISMO importe la reenvie con la misma
     * clave (el backend devuelve el movimiento original) en vez de conceder dos veces (MASTER5-IDEM-001). Se borra al
     * tener exito (solo la suya: MASTER6-IDEM-002, el exito de otra concesion no la borra); un importe distinto acuña una
     * clave nueva y NO descarta las pendientes anteriores (una por usuario e importe).
     */
    val pendingGrants: List<PendingGrant> = emptyList(),
    val warningDialogOpen: Boolean = false,
    val warningInput: String = "",
) {
    /**
     * La concesion pendiente MAS RECIENTE del usuario seleccionado (la que reabre el dialogo). MASTER6-IDEM-002: las
     * pendientes son una por (usuario, importe), no una por usuario; ver [pendingGrants].
     */
    val pendingGrant: PendingGrant? get() = pendingGrants.lastOrNull { it.userId == selectedUserId }

    val loadedUsers: List<User> get() = (users as? UiAsync.Success)?.value.orEmpty()

    val selectedUser: User? get() = loadedUsers.firstOrNull { it.id == selectedUserId }

    val rolesById: Map<String, Role> get() = availableRoles.associateBy { it.id }

    /** Ids del catálogo que conceden acceso al panel. Vacío = catálogo sin cargar. */
    val adminRoleIds: Set<String> get() = adminRoleIds(availableRoles)

    val isDirty: Boolean get() = userEditorIsDirty(selectedUser, editorName, editorRoleIds)

    val saveBlockers: List<String>
        get() = userSaveBlockers(
            editorName = editorName,
            currentName = selectedUser?.fullName,
            editorRoleIds = editorRoleIds,
            currentRoleIds = selectedUser?.roleIds?.toSet(),
        )

    /**
     * Se puede guardar si hay usuario, hay algo que guardar y nada lo impide.
     *
     * El editor anterior exigía además un email válido — un campo que el backend
     * descarta —, así que bloqueaba el guardado del nombre por un dato inútil.
     */
    val canSave: Boolean
        get() = selectedUser != null && isDirty && saveBlockers.isEmpty() && actionState !is UiAsync.Loading

    /** ¿El guardado pendiente concede o retira acceso administrativo? */
    val touchesAdminAccess: Boolean
        get() = userAdminAccessChanges(
            currentRoleIds = selectedUser?.roleIds?.toSet().orEmpty(),
            editorRoleIds = editorRoleIds,
            adminRoleIds = adminRoleIds,
        )
}
