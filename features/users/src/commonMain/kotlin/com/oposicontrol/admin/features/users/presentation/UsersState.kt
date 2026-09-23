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
 * - `editorIsActive`: `User.isActive` es `!es_invitado`, no un estado de acceso,
 *   y el `PATCH` ni siquiera lo escribe. Ver [UserAccountKind].
 * - el par de acciones de bloqueo, por lo mismo.
 *
 * Y sobra uno nuevo, [pendingSelectionUserId], que es el que impide perder
 * cambios al saltar de usuario.
 */
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
    val warningDialogOpen: Boolean = false,
    val warningInput: String = "",
) {
    val loadedUsers: List<User> get() = (users as? UiAsync.Success)?.value.orEmpty()

    val selectedUser: User? get() = loadedUsers.firstOrNull { it.id == selectedUserId }

    val rolesById: Map<String, Role> get() = availableRoles.associateBy { it.id }

    /** Ids del catálogo que conceden acceso al panel. Vacío = catálogo sin cargar. */
    val adminRoleIds: Set<String> get() = adminRoleIds(availableRoles)

    val isDirty: Boolean get() = userEditorIsDirty(selectedUser, editorName, editorRoleIds)

    val saveBlockers: List<String> get() = userSaveBlockers(editorName)

    /**
     * Se puede guardar si hay usuario, hay algo que guardar y nada lo impide.
     *
     * El editor anterior exigía además un email válido — un campo que el backend
     * descarta —, así que bloqueaba el guardado del nombre por un dato inútil.
     */
    val canSave: Boolean get() = selectedUser != null && isDirty && saveBlockers.isEmpty()

    /** ¿El guardado pendiente concede o retira acceso administrativo? */
    val touchesAdminAccess: Boolean
        get() = userAdminAccessChanges(
            currentRoleIds = selectedUser?.roleIds?.toSet().orEmpty(),
            editorRoleIds = editorRoleIds,
            adminRoleIds = adminRoleIds,
        )
}
