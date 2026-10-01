package com.oposicontrol.admin.features.users.presentation

import com.oposicontrol.admin.domain.model.AccountStatus
import com.oposicontrol.admin.domain.model.Role
import com.oposicontrol.admin.domain.model.User
import com.oposicontrol.admin.domain.model.UserDetail

/**
 * Capa de decisión de Usuarios. Sin Compose, sin colores, sin repositorios.
 *
 * Existe por el mismo motivo que sus equivalentes en Preguntas e Importaciones:
 * la pantalla se pinta **desde** esta matriz, no al revés. Todo lo que aquí se
 * declara está verificado contra el contrato real —`AdminV1Routes.kt`,
 * `UsersApi.kt`, `perfiles_usuario`—, no contra lo que la UI anterior aparentaba
 * saber hacer.
 *
 * Tres hallazgos de la auditoría gobiernan casi todo lo que sigue:
 *
 * 1. Esta pantalla mezcla **dos poblaciones**: los opositores de OposiBot y los
 *    administradores de OposiControl. Un administrador no es otra cosa que una
 *    fila de `perfiles_usuario` con un rol `admin`/`superadmin` enlazado. Ver
 *    [UserPopulation].
 * 2. `User.isRegistered` (`!es_invitado` en el backend) **no significa activo ni
 *    bloqueado**: distingue invitado de registrado. El estado de acceso es
 *    `User.accountStatus` (activa / bloqueada / eliminación pendiente). Ver
 *    [UserAccountKind] y [userAccountStatusLabel].
 * 3. `User.email` **no es el nombre de cuenta**: en la lista es el `username` legacy y
 *    en `GET /usuarios/{id}` el correo real de GoTrue. La pantalla pinta
 *    `User.username` ([userAccountName]) y no muestra el correo. Ver [USER_EMAIL_UNAVAILABLE_NOTE].
 */

// ---------------------------------------------------------------------------
// Población: ¿a quién estoy mirando?
// ---------------------------------------------------------------------------

/**
 * Qué es esta fila dentro del producto.
 *
 * La pregunta parece obvia y no lo es: esta pantalla lista `perfiles_usuario`
 * entera, y ahí conviven quien se ha bajado la app para opositar y quien
 * administra el panel desde el que se la estás mirando. La única señal que los
 * separa es el rol enlazado en `roles_usuario`.
 *
 * [INDETERMINADA] es un estado honesto y frecuente, no un fallo: si el catálogo
 * de roles todavía no ha cargado, o si el usuario no tiene ningún rol asignado,
 * no se puede afirmar de qué población es. Pintarlo como «opositor» por defecto
 * sería inventar: exactamente lo que haría creer que no hay administradores en
 * la lista cuando sí los hay.
 */
enum class UserPopulation {
    /** Usuario final de OposiBot. El caso mayoritario. */
    OPOSITOR,

    /** Tiene rol `admin` o `superadmin`: puede entrar en este panel. */
    ADMINISTRACION,

    /** No se puede afirmar todavía. Ni «opositor» ni «administrador». */
    INDETERMINADA,
}

/**
 * Códigos de rol que conceden acceso administrativo.
 *
 * **Espejo deliberado** de `CanonicalAuthorization.adminRoleCodes` en
 * `backend/ktor`. Se copia en vez de compartirse porque ese objeto vive en el
 * módulo de backend y el cliente KMP no depende de él; si allí cambia la lista,
 * hay que cambiarla aquí, y por eso está en un solo sitio y con este comentario.
 *
 * La comparación se hace en minúsculas y sin espacios, igual que el backend.
 */
val ADMIN_ROLE_CODES: Set<String> = setOf("superadmin", "admin")

/** Código de rol normalizado como lo compara el backend. */
private fun String.normalizedRoleCode(): String = trim().lowercase()

/**
 * Ids de rol del catálogo que conceden acceso administrativo.
 *
 * Se resuelve por **código**, no por nombre: el nombre es una etiqueta humana
 * («Administrador», «Admin», «Administración») que alguien puede reescribir sin
 * cambiar el permiso, mientras que el código es el que el backend compara de
 * verdad en `CanonicalAuthorization.hasAdmin`.
 */
fun adminRoleIds(roles: List<Role>): Set<String> =
    roles.filter { it.code.normalizedRoleCode() in ADMIN_ROLE_CODES }
        .map { it.id }
        .toSet()

/**
 * A qué población pertenece [user].
 *
 * [adminRoleIds] vacío significa «todavía no lo sé» —catálogo sin cargar—, y en
 * ese caso NO se afirma que nadie sea opositor.
 */
fun userPopulation(user: User, adminRoleIds: Set<String>): UserPopulation = when {
    adminRoleIds.isEmpty() -> UserPopulation.INDETERMINADA
    user.roleIds.any { it in adminRoleIds } -> UserPopulation.ADMINISTRACION
    user.roleIds.isEmpty() -> UserPopulation.INDETERMINADA
    else -> UserPopulation.OPOSITOR
}

fun userPopulationLabel(population: UserPopulation): String = when (population) {
    UserPopulation.OPOSITOR -> "Opositor"
    UserPopulation.ADMINISTRACION -> "Administración"
    UserPopulation.INDETERMINADA -> "Sin determinar"
}

/**
 * Por qué esta pantalla enseña las dos poblaciones a la vez.
 *
 * Se dice en la propia pantalla en lugar de dejarlo en un documento: quien
 * administra tiene que saber que la lista que está filtrando incluye a sus
 * propios compañeros de panel, porque las acciones de esta pantalla —roles, sobre
 * todo— se aplican igual a unos y a otros.
 */
const val USER_POPULATION_NOTE: String =
    "Esta lista incluye tanto a los usuarios de la app como a las cuentas con acceso " +
        "administrativo: ambas viven en la misma tabla y se distinguen solo por su rol."

// ---------------------------------------------------------------------------
// Identidad: qué se puede enseñar y qué no
// ---------------------------------------------------------------------------

/**
 * Nombre con el que referirse al usuario.
 *
 * Nunca un identificador. Si no hay nombre se dice que falta, en lugar de caer
 * al UUID —que es lo que hacía la ficha anterior con `Field("ID", userId)`— o de
 * dejar la fila en blanco, que se confunde con un nombre vacío legítimo.
 */
fun userDisplayName(user: User): String =
    user.fullName.trim().ifBlank { USER_NAME_MISSING }

const val USER_NAME_MISSING: String = "Sin nombre"

/**
 * Etiqueta del campo que el backend llama `email`.
 *
 * No es un email: `perfiles_usuario` no tiene esa columna y el backend rellena
 * el campo con `username`. Llamarlo «Email» en pantalla es lo que hacía que un
 * administrador creyera estar viendo —y editando— el correo de alguien.
 */
const val USER_ACCOUNT_FIELD_LABEL: String = "Nombre de usuario"

/**
 * Valor del campo anterior, o la marca de ausencia.
 *
 * MASTER5-OC-001: sale de `User.username`, que la lista y la ficha informan igual. `User.email` lleva dos
 * semanticas (username en la lista, correo real de GoTrue en `GET /usuarios/{id}`), asi que leerlo hacia que la fila
 * pasara a enseñar el correo tras guardar bajo la etiqueta «Nombre de usuario». `email` solo es el respaldo de un
 * backend anterior que no envia `username`.
 */
fun userAccountName(user: User): String =
    (user.username?.takeIf { it.isNotBlank() } ?: user.email).trim().ifBlank { USER_NO_DATA }

/** El correo real lo devuelve el detalle, pero esta pantalla no lo muestra: el campo de cuenta es el nombre de usuario. */
const val USER_EMAIL_UNAVAILABLE_NOTE: String =
    "El correo electrónico no se muestra en esta pantalla: el campo de arriba es el nombre " +
        "de usuario, no el correo de la cuenta."

/** Ausencia de dato. Nunca un cero, nunca un hueco en blanco. */
const val USER_NO_DATA: String = "Sin dato"

/** Fecha de alta en formato corto. `createdAt` llega como instante ISO. */
fun userSignupDate(user: User): String =
    user.createdAt?.toString()?.take(10) ?: USER_NO_DATA

// ---------------------------------------------------------------------------
// Estado de la cuenta: invitado vs registrado (NO activo vs bloqueado)
// ---------------------------------------------------------------------------

/**
 * Qué tipo de cuenta es.
 *
 * El backend calcula `isActive = !es_invitado` (dominio: `isRegistered`) tanto en la lista como en la
 * ficha. Es decir: el booleano distingue una cuenta de invitado de una
 * registrada, y **no dice nada sobre acceso**. La pantalla anterior lo pintaba
 * como «Activo/Bloqueado» en la tabla y como «invitado/registrado» en la ficha —
 * dos significados contradictorios para el mismo bit, en la misma pantalla.
 */
enum class UserAccountKind {
    /** Cuenta completa. `es_invitado = false`. */
    REGISTRADO,

    /** Cuenta de invitado, creada sin registro. `es_invitado = true`. */
    INVITADO,

    /** El backend no informo `es_invitado`: no se afirma ni registrado ni invitado. */
    SIN_DATO,
}

fun userAccountKind(user: User): UserAccountKind = when (user.isRegistered) {
    true -> UserAccountKind.REGISTRADO
    false -> UserAccountKind.INVITADO
    null -> UserAccountKind.SIN_DATO
}

/**
 * Estado de la cuenta (`AccountStatus` canonico): lo que el panel dice del acceso. Un estado ausente o no
 * reconocido (`UNKNOWN`) se muestra como sin dato, nunca como activo (deny-by-default).
 */
fun userAccountStatusLabel(status: AccountStatus): String = when (status) {
    AccountStatus.ACTIVE -> "Activa"
    AccountStatus.BLOCKED -> "Bloqueada"
    AccountStatus.DELETION_PENDING -> "Eliminación pendiente"
    AccountStatus.UNKNOWN -> USER_NO_DATA
}

/** Bloqueada o con eliminación pendiente: pide atención (misma regla que la pastilla de escritorio). */
fun userAccountStatusNeedsAttention(status: AccountStatus): Boolean = userAccountStatusTone(status) == UserPillTone.ATTENTION

fun userAccountKindLabel(kind: UserAccountKind): String = when (kind) {
    UserAccountKind.REGISTRADO -> "Registrado"
    UserAccountKind.INVITADO -> "Invitado"
    UserAccountKind.SIN_DATO -> USER_NO_DATA
}

/**
 * Por qué no hay ninguna acción de bloqueo en esta pantalla.
 *
 * El estado de cuenta (activa / bloqueada / eliminación pendiente) SÍ existe y se enseña, pero es de sólo lectura:
 * el `PATCH` de usuario sólo escribe `nombre_mostrar` y ninguna operación del panel lo cambia. El botón «Bloquear»
 * anterior llamaba a ese `PATCH` con un booleano, el backend lo descartaba y la UI pintaba «Operación completada».
 */
const val USER_ACCOUNT_STATUS_READ_ONLY_NOTE: String =
    "El estado de la cuenta se muestra tal como lo informa el sistema, pero no se puede cambiar desde el panel."

// ---------------------------------------------------------------------------
// Matriz de honestidad: qué se puede hacer de verdad
// ---------------------------------------------------------------------------

/**
 * Hasta dónde llega una operación administrativa.
 *
 * El orden importa: la UI ordena por gravedad para que lo que no funciona se lea
 * antes que lo que sí.
 */
enum class UserCapabilityLevel {
    /** Llega a Supabase y persiste. */
    REAL,

    /** Hace algo, pero menos de lo que su nombre promete. */
    PARTIAL,

    /** Existe el control pero está impedido a propósito. */
    BLOCKED,

    /** No hay ni endpoint ni columna. La funcionalidad no existe. */
    NOT_FOUND,
}

/**
 * Una operación y hasta dónde llega de verdad.
 *
 * [note] no es un adorno: es lo que se enseña cuando alguien busca el botón que
 * espera y no lo encuentra.
 */
data class UserCapability(
    val name: String,
    val level: UserCapabilityLevel,
    val note: String,
)

/**
 * Todo lo que esta pantalla puede y no puede hacer, verificado en el contrato.
 *
 * Cada línea está comprobada leyendo el código, no inferida de la UI:
 * `AdminV1Routes.updateUsuario` (sólo `nombre_mostrar`), `AdminV1Routes.syncRoles`
 * (RPC `admin_sync_roles`), `AdminV1Routes.grantTickets` (ledger idempotente),
 * `AdminV1Routes.warnUser` (sólo auditoría) y `UsersApi` (crear y eliminar
 * cortados en el cliente).
 */
private val USER_CAPABILITIES: List<UserCapability> = listOf(
    UserCapability(
        name = "Editar nombre",
        level = UserCapabilityLevel.REAL,
        note = "Es el único campo del perfil que el guardado escribe.",
    ),
    UserCapability(
        name = "Asignar roles",
        level = UserCapabilityLevel.REAL,
        note = "Sustituye la lista completa de roles del usuario.",
    ),
    UserCapability(
        name = "Conceder OposiTickets",
        level = UserCapabilityLevel.REAL,
        note = "Queda registrado en el histórico y no se duplica si se reintenta.",
    ),
    UserCapability(
        name = "Advertir",
        level = UserCapabilityLevel.PARTIAL,
        note = "Deja constancia en el registro de auditoría, pero no envía ningún aviso al usuario.",
    ),
    UserCapability(
        name = "Cambiar el nombre de usuario",
        level = UserCapabilityLevel.BLOCKED,
        note = "El guardado descarta este campo: sólo se escribe el nombre.",
    ),
    UserCapability(
        name = "Crear usuario",
        level = UserCapabilityLevel.BLOCKED,
        note = "Las altas se hacen desde el sistema de autenticación, no desde el panel.",
    ),
    UserCapability(
        name = "Eliminar usuario",
        level = UserCapabilityLevel.BLOCKED,
        note = "Las bajas se hacen desde el sistema de autenticación, no desde el panel.",
    ),
    UserCapability(
        name = "Bloquear o desbloquear",
        level = UserCapabilityLevel.NOT_FOUND,
        note = USER_ACCOUNT_STATUS_READ_ONLY_NOTE,
    ),
    UserCapability(
        name = "Invitar por correo",
        level = UserCapabilityLevel.NOT_FOUND,
        note = "No hay ningún flujo de invitación en el sistema.",
    ),
    UserCapability(
        name = "Ver el último acceso",
        level = UserCapabilityLevel.NOT_FOUND,
        note = "No se guarda en ninguna tabla que el panel pueda consultar.",
    ),
)

/** Busca una capacidad por nombre. Devuelve null si no está declarada. */
fun userCapability(name: String): UserCapability? =
    USER_CAPABILITIES.firstOrNull { it.name == name }

/** Las que no funcionan del todo, primero las peores. Para el bloque de avisos. */
fun userUnavailableCapabilities(): List<UserCapability> =
    USER_CAPABILITIES
        .filter { it.level != UserCapabilityLevel.REAL }
        .sortedBy { it.level.ordinal }

// ---------------------------------------------------------------------------
// Recuento: cuántos usuarios se están viendo de verdad
// ---------------------------------------------------------------------------

/**
 * Recuento honesto de lo que hay debajo de los filtros.
 *
 * Habla de «de los N cargados»: N son los usuarios que el cliente ha leído (todas
 * las páginas del backend), y los filtros los reducen a un subconjunto de ellos.
 */
fun userCountLabel(shown: Int, loaded: Int): String = when {
    loaded == 0 -> "Sin usuarios"
    shown == loaded && loaded == 1 -> "1 usuario"
    shown == loaded -> "$loaded usuarios"
    shown == 1 -> "1 de $loaded cargados"
    else -> "$shown de $loaded cargados"
}

// ---------------------------------------------------------------------------
// Editor: qué se guarda y qué se pierde al cambiar de usuario
// ---------------------------------------------------------------------------

/**
 * ¿Hay cambios sin guardar en el editor respecto a [user]?
 *
 * Sólo se comparan los campos que el guardado escribe de verdad. Comparar
 * también el nombre de usuario marcaría como «sucio» un campo que el backend va
 * a descartar, y obligaría a confirmar la pérdida de algo que nunca se iba a
 * guardar.
 */
fun userEditorIsDirty(
    user: User?,
    editorName: String,
    editorRoleIds: Set<String>,
): Boolean {
    if (user == null) return editorName.isNotBlank() || editorRoleIds.isNotEmpty()
    return editorName.trim() != user.fullName.trim() || editorRoleIds != user.roleIds.toSet()
}

/**
 * Motivos por los que no se puede guardar. Lista vacía = se puede.
 *
 * El nombre es obligatorio salvo que el usuario ya no tuviera (`currentName` en
 * blanco: edición «sólo roles»); `UpsertUserUseCase` aplica la misma regla. El
 * correo **ya no** se exige: la versión anterior lo pedía para poder guardar y
 * después el backend lo tiraba, así que bloqueaba el guardado de un usuario sin
 * username por un campo que no servía para nada.
 */
fun userSaveBlockers(
    editorName: String,
    currentName: String? = null,
    editorRoleIds: Set<String>? = null,
    currentRoleIds: Set<String>? = null,
): List<String> = buildList {
    // MASTER4-API-001: un usuario SIN nombre puede guardar un cambio de roles dejando el nombre en blanco; vaciar un
    // nombre existente (o no conocer el actual) sigue bloqueado, igual que el 400 REQUIRED del servidor.
    if (editorName.isBlank() && currentName?.isBlank() != true) add("El nombre no puede estar vacío.")
    // MASTER5-OC-006: limites del contrato (D1-25 nombreMostrar maxLength 100; D1-26 roleIds no vacio). El cliente los
    // aplica antes de escribir: si no, el PATCH del nombre persiste y luego /roles falla con 422 (guardado parcial).
    if (userNameLength(editorName.trim()) > USER_NAME_MAX_LENGTH) add("El nombre no puede superar los $USER_NAME_MAX_LENGTH caracteres.")
    // Quitar TODOS los roles lo rechaza el servidor (422 ROLE_SYNC_EMPTY_ROLES). Un usuario que ya estaba sin roles y
    // solo cambia el nombre no envia /roles (conjunto sin cambios), por eso solo se exige si el conjunto cambia.
    if (editorRoleIds != null && currentRoleIds != null && editorRoleIds.isEmpty() && editorRoleIds != currentRoleIds) {
        add("El usuario debe conservar al menos un rol.")
    }
}

/** D1-25 `AdminUserPatchRequest.nombreMostrar`: maxLength 100. */
const val USER_NAME_MAX_LENGTH: Int = 100

/**
 * MASTER6-QUALITY-005: longitud en PUNTOS DE CODIGO Unicode, como la cuenta el servidor (`codePointCount`). `String.length`
 * cuenta unidades UTF-16 y un emoji (plano suplementario) ocupa dos: con ella el cliente rechazaba nombres que el servidor
 * acepta. Un par sustituto valido cuenta uno; un sustituto suelto (invalido) cuenta uno, igual que en `codePointCount`.
 */
fun userNameLength(name: String): Int {
    var count = 0
    var i = 0
    while (i < name.length) {
        i += if (name[i].isHighSurrogate() && i + 1 < name.length && name[i + 1].isLowSurrogate()) 2 else 1
        count++
    }
    return count
}

/**
 * MASTER6-QUALITY-010: estado de cuenta a pintar. La ficha recien leida manda sobre la fila de la lista (que puede estar
 * obsoleta: una cuenta bloqueada o con eliminacion pendiente se veia "Activa"); si la ficha no cargo o no trae un estado
 * reconocido, se mantiene el de la fila.
 */
fun userEffectiveAccountStatus(user: User, detail: UserDetail?): AccountStatus =
    detail?.profile?.accountStatus?.takeIf { it != AccountStatus.UNKNOWN } ?: user.accountStatus

/** Qué se va a escribir realmente al pulsar Guardar. Se dice antes, no después. */
const val USER_SAVE_SCOPE_NOTE: String =
    "Al guardar se actualizan el nombre y los roles. Ningún otro campo de esta ficha se escribe."

// ---------------------------------------------------------------------------
// Roles
// ---------------------------------------------------------------------------

/** Nombre legible de un rol; si el catálogo no lo tiene, se dice. */
fun roleLabel(roleId: String, rolesById: Map<String, Role>): String =
    rolesById[roleId]?.name?.trim()?.ifBlank { null } ?: "Rol sin nombre"

/** Resumen de roles para una fila de tabla. */
fun userRoleSummary(user: User, rolesById: Map<String, Role>): String =
    if (user.roleIds.isEmpty()) {
        "Sin roles"
    } else {
        user.roleIds.joinToString(", ") { roleLabel(it, rolesById) }
    }

/**
 * Aviso al conceder o retirar acceso administrativo.
 *
 * La guarda de `admin_sync_roles` (`20260921071916 role_sync_guard`) está
 * INSTALADA en MAIN: el servidor rechaza dejar la plataforma sin ningún
 * superadministrador (409 `ROLE_SYNC_LAST_SUPERADMIN`, traducido en
 * `BackendErrors.kt`). El aviso ya no es la única protección, pero sigue pidiendo
 * revisar el cambio antes de confirmar. No promete más que eso: sólo se veta la
 * transición de ≥1 a 0 superadministradores, no cualquier cambio de acceso.
 */
const val USER_ADMIN_ROLE_WARNING: String =
    "Este cambio concede o retira acceso al panel de administración. Revísalo antes de " +
        "confirmar: el servidor rechaza dejar la plataforma sin ningún superadministrador."

/**
 * ¿El cambio de roles toca el acceso administrativo?
 *
 * Se calcula comparando conjuntos y no contando: pasar de `admin` a `superadmin`
 * mantiene el acceso y no debería avisar como si lo estuviera concediendo.
 */
fun userAdminAccessChanges(
    currentRoleIds: Set<String>,
    editorRoleIds: Set<String>,
    adminRoleIds: Set<String>,
): Boolean {
    val had = currentRoleIds.any { it in adminRoleIds }
    val has = editorRoleIds.any { it in adminRoleIds }
    return had != has
}
