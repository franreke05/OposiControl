package com.oposicontrol.admin.features.users.presentation

import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.ui.components.StatusTone
import com.oposicontrol.admin.domain.model.AccountStatus
import com.oposicontrol.admin.domain.model.Role
import com.oposicontrol.admin.domain.model.User

// ---------------------------------------------------------------------------------------------------------------------
// Quién puede entrar
// ---------------------------------------------------------------------------------------------------------------------

/** El rol administrativo MÁS ALTO de una cuenta. El orden del enum es el de la lista: superadministradores primero. */
enum class AdminLevel(val code: String, val label: String) {
    SUPERADMIN("superadmin", "Superadministrador"),
    ADMIN("admin", "Administrador"),
}

/** Una cuenta con rol `admin` o `superadmin`, tal como la enseña «Quién puede entrar». */
data class AdminAccount(
    val userId: String,
    val name: String,
    /** `perfiles_usuario.username`: la lista de usuarios NO trae el correo (ver [userAccountName]). */
    val accountName: String,
    val level: AdminLevel,
    val accountStatus: AccountStatus,
)

/**
 * Las cuentas que tienen rol `admin` o `superadmin`, una fila por PERSONA: quien tiene los dos roles sale una vez, con el
 * más alto. Moderadores y usuarios no salen: esos roles no abren el panel.
 *
 * Se resuelve por CÓDIGO de rol (el que compara el servidor), no por el nombre, que es una etiqueta que alguien puede
 * reescribir. Superadministradores primero y, dentro de cada grupo, por nombre.
 */
fun panelAdminAccounts(users: List<User>, roles: List<Role>): List<AdminAccount> {
    val levelByRoleId = roles.mapNotNull { role ->
        val code = role.code.trim().lowercase()
        AdminLevel.entries.firstOrNull { it.code == code }?.let { role.id to it }
    }.toMap()
    return users.mapNotNull { user ->
        val level = user.roleIds.mapNotNull(levelByRoleId::get).minOrNull() ?: return@mapNotNull null
        AdminAccount(
            userId = user.id,
            name = userDisplayName(user),
            accountName = userAccountName(user),
            level = level,
            accountStatus = user.accountStatus,
        )
    }.sortedWith(compareBy<AdminAccount> { it.level }.thenBy { it.name.lowercase() })
}

/**
 * Une las lecturas por rol en una sola lista. Si falla UNA, el resultado es ese fallo: media lista pasaría por la lista
 * completa. Quien tiene los dos roles llega dos veces y se queda una.
 */
fun mergeRoleHolders(readings: List<AppResult<List<User>>>): AppResult<List<User>> {
    readings.firstNotNullOfOrNull { it as? AppResult.Failure }?.let { return it }
    return AppResult.Success(readings.flatMap { (it as AppResult.Success).value }.distinctBy { it.id })
}

internal const val ADMIN_ROLES_MISSING_MESSAGE: String =
    "El catálogo de roles no incluye los roles de administración, así que no se puede saber quién entra en el panel."

/**
 * De las dos lecturas (usuarios y catálogo de roles) a la lista, SIN convertir nunca un fallo en una lista vacía:
 *
 *  - si falla cualquiera de las dos, el resultado es ese fallo;
 *  - si el catálogo llega pero no trae ni `admin` ni `superadmin`, tampoco se puede afirmar que no haya administradores
 *    (quien mira esta pantalla es uno): es un error, no un «No hay administradores».
 */
fun resolveAdminAccounts(users: AppResult<List<User>>, roles: AppResult<List<Role>>): AppResult<List<AdminAccount>> = when {
    roles is AppResult.Failure -> AppResult.Failure(roles.error)
    users is AppResult.Failure -> AppResult.Failure(users.error)
    roles is AppResult.Success && users is AppResult.Success ->
        if (adminRoleIds(roles.value).isEmpty()) {
            AppResult.Failure(AppError.Validation(ADMIN_ROLES_MISSING_MESSAGE))
        } else {
            AppResult.Success(panelAdminAccounts(users.value, roles.value))
        }
    else -> AppResult.Failure(AppError.Validation(ADMIN_ROLES_MISSING_MESSAGE))
}

/**
 * Estado de la cuenta con las palabras de la acción que lo cambia («Dar de baja» / «Reactivar»). Una cuenta sin estado
 * conocido NO se da por activa.
 */
fun adminAccountStatusLabel(status: AccountStatus): String = when (status) {
    AccountStatus.ACTIVE -> "Activa"
    AccountStatus.BLOCKED -> "Dada de baja"
    AccountStatus.DELETION_PENDING -> "Eliminación pendiente"
    AccountStatus.UNKNOWN -> USER_NO_DATA
}

internal fun adminAccountStatusTone(status: AccountStatus): StatusTone = when (status) {
    AccountStatus.ACTIVE -> StatusTone.POSITIVE
    AccountStatus.BLOCKED, AccountStatus.DELETION_PENDING -> StatusTone.NEGATIVE
    AccountStatus.UNKNOWN -> StatusTone.UNKNOWN
}

/** El servidor solo deja entrar con el rol Y la cuenta activa: una cuenta dada de baja conserva el rol pero no entra. */
fun adminAccountCanEnter(account: AdminAccount): Boolean = account.accountStatus == AccountStatus.ACTIVE

internal fun adminAccountsCountLabel(count: Int): String = if (count == 1) "1 cuenta" else "$count cuentas"

// ---------------------------------------------------------------------------------------------------------------------
// Qué permite cada rol
// ---------------------------------------------------------------------------------------------------------------------

/** Una fila de la tabla de referencia: qué puede y qué no puede hacer un rol EN EL PANEL. */
data class RolePermissions(
    val code: String,
    val name: String,
    val entersPanel: Boolean,
    val can: List<String>,
    val cannot: List<String> = emptyList(),
)

/**
 * Tabla FIJA: describe lo que el servidor aplica hoy, comprobado en `backend/ktor` (no sale del catálogo de roles, cuyos
 * «permisos» son etiquetas que el servidor no consulta). Si allí cambia una regla, hay que cambiarla aquí.
 *
 *  - Solo `admin` y `superadmin` abren el panel: `CanonicalAuthorization.adminRoleCodes` y `AdminAuthorizationPlugin`
 *    (que además exige la cuenta activa: `AppV1Gateway.hasActiveAdminRole`).
 *  - Tres cosas exigen superadministrador (`SUPERADMIN_REQUIRED`): cambiar los roles `admin`/`superadmin`
 *    (`AdminV1Gateway.syncRoles`), revisar solicitudes de acceso (`requireSuperadminActor`) y dar de baja o reactivar una
 *    cuenta (`AdminV1Gateway.setAccountStatus`, que además rechaza la cuenta propia y la de otro superadministrador).
 *  - Ninguna otra ruta del panel mira el rol más allá de «es administrador»: por eso un administrador puede hoy ajustar
 *    OposiTickets, borrar del catálogo o conceder accesos.
 *  - `moderador` y `usuario` existen en el catálogo, pero ninguna ruta del panel los acepta.
 */
val PANEL_ROLE_PERMISSIONS: List<RolePermissions> = listOf(
    RolePermissions(
        code = "superadmin",
        name = "Superadministrador",
        entersPanel = true,
        can = listOf(
            "Todo lo que puede hacer un administrador.",
            "Conceder y retirar los roles de administrador y de superadministrador.",
            "Aprobar y rechazar las solicitudes de acceso al panel.",
            "Dar de baja y reactivar cuentas.",
        ),
        cannot = listOf(
            "Dar de baja su propia cuenta ni la de otro superadministrador.",
            "Quitar el rol al último superadministrador que quede.",
        ),
    ),
    RolePermissions(
        code = "admin",
        name = "Administrador",
        entersPanel = true,
        can = listOf(
            "Usar el resto del panel: por ejemplo, ajustar OposiTickets, borrar productos y planes del catálogo y conceder o retirar accesos a exámenes.",
            "Conceder y retirar los roles de moderador y de usuario.",
        ),
        cannot = listOf(
            "Conceder ni retirar los roles de administrador y de superadministrador.",
            "Ver ni decidir las solicitudes de acceso al panel.",
            "Dar de baja ni reactivar cuentas.",
        ),
    ),
    RolePermissions(
        code = "moderador",
        name = "Moderador",
        entersPanel = false,
        can = emptyList(),
        cannot = listOf(
            "Entrar en el panel: el rol existe, pero hoy el servidor solo deja pasar a administradores y superadministradores.",
        ),
    ),
    RolePermissions(
        code = "usuario",
        name = "Usuario",
        entersPanel = false,
        can = emptyList(),
        cannot = listOf("Entrar en el panel."),
    ),
)

internal const val ROLE_TABLE_ACTIVE_ACCOUNT_NOTE: String =
    "Además del rol hace falta la cuenta activa: con la cuenta dada de baja no se entra en el panel, sea cual sea el rol."

internal const val ACCESS_REVIEW_DENIED_MESSAGE: String = "Solo un superadministrador puede revisar solicitudes de acceso."
