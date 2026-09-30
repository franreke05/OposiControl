package com.oposicontrol.admin.features.users.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.DesktopVerticalScrollbar
import com.oposicontrol.admin.core.ui.components.DropdownField
import com.oposicontrol.admin.core.ui.components.EmptyStateCard
import com.oposicontrol.admin.core.ui.components.GlassSearchBar
import com.oposicontrol.admin.core.ui.components.GlassSurface
import com.oposicontrol.admin.core.ui.components.SemanticIconContainer
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.User

/**
 * Filtro por población.
 *
 * Sustituye al antiguo «Todos / Activos / Bloqueados». Aquel filtraba sobre
 * `isRegistered`, que el backend calcula (`isActive` en el cable) como `!es_invitado`: pedir «Bloqueados»
 * devolvía en realidad la lista de invitados. Ver [UserAccountKind].
 */
private enum class PopulationFilter(val label: String) {
    TODAS("Todas"),
    OPOSITORES("Opositores"),
    ADMINISTRACION("Administración"),
}

/** Filtro por tipo de cuenta. Esta sí es la distinción que el dato soporta. */
private enum class AccountFilter(val label: String) {
    TODAS("Todas"),
    REGISTRADOS("Registrados"),
    INVITADOS("Invitados"),
}

/**
 * Catálogo de usuarios.
 *
 * Dos decisiones que vienen de la auditoría del contrato, no del diseño:
 *
 * - Cada fila dice la **población** por el color del rol (un rol con acceso al panel
 *   se lee distinto de uno de opositor): esta lista mezcla a los usuarios de la app
 *   con las cuentas que tienen acceso a este mismo panel, porque ambas son filas de
 *   `perfiles_usuario` y sólo las separa el rol.
 * - El tipo de cuenta dice **Registrado/Invitado** y no Activo/Bloqueado. No existe
 *   bloqueo editable desde el panel: el estado de cuenta se muestra en su pastilla. Ver [USER_ACCOUNT_STATUS_READ_ONLY_NOTE].
 *
 * El cliente lee todas las páginas del backend (`UsersApi.getUsers`), así que la lista
 * es el censo completo y no lleva aviso de truncamiento.
 *
 * Los filtros son los que YA existían —población, cuenta y rol—, con el mismo estado
 * local y la misma lógica; sólo cambia cómo se presentan (selectores en línea en vez
 * de tres filas de chips).
 */
@Composable
internal fun UsersListPane(
    state: UsersState,
    onSearch: (String) -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens
    var populationFilter by rememberSaveable { mutableStateOf(PopulationFilter.TODAS) }
    var accountFilter by rememberSaveable { mutableStateOf(AccountFilter.TODAS) }
    var roleFilter by rememberSaveable { mutableStateOf<String?>(null) }

    val rolesById = state.rolesById
    val adminRoleIds = state.adminRoleIds
    val loaded = (state.users as? UiAsync.Success)?.value

    GlassSurface(
        modifier = modifier.fillMaxSize(),
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                SemanticIconContainer(icon = AppIcon.USERS, role = SemanticRole.INFO, size = 40.dp, iconSize = 20.dp)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Usuarios",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = tokens.textPrimary,
                    )
                    Text(
                        text = loaded?.let { users ->
                            val shown = filteredUsers(users, state, populationFilter, accountFilter, roleFilter, adminRoleIds).size
                            userCountLabel(shown = shown, loaded = users.size)
                        } ?: "Catálogo de usuarios",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary,
                    )
                }
            }

            GlassSearchBar(
                value = state.searchQuery,
                onValueChange = onSearch,
                placeholder = "Buscar por nombre o nombre de usuario",
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.Top,
            ) {
                DropdownField(
                    label = "Población",
                    selectedLabel = populationFilter.label,
                    options = PopulationFilter.entries.map { it.name to it.label },
                    onSelect = { id ->
                        populationFilter = PopulationFilter.entries.firstOrNull { it.name == id } ?: PopulationFilter.TODAS
                    },
                    modifier = Modifier.weight(1f),
                )
                DropdownField(
                    label = "Cuenta",
                    selectedLabel = accountFilter.label,
                    options = AccountFilter.entries.map { it.name to it.label },
                    onSelect = { id ->
                        accountFilter = AccountFilter.entries.firstOrNull { it.name == id } ?: AccountFilter.TODAS
                    },
                    modifier = Modifier.weight(1f),
                )
                if (state.availableRoles.isNotEmpty()) {
                    DropdownField(
                        label = "Rol",
                        selectedLabel = state.availableRoles.firstOrNull { it.id == roleFilter }?.name ?: "Cualquiera",
                        options = listOf<Pair<String?, String>>(null to "Cualquiera") +
                            state.availableRoles.map { it.id to it.name },
                        onSelect = { id -> roleFilter = id },
                        modifier = Modifier.weight(1.4f),
                    )
                }
            }

            AsyncStatePane(
                state = state.users,
                modifier = Modifier.weight(1f),
                emptyTitle = "Sin usuarios",
                // Sin acción de crear: el alta no se hace desde el panel, y ofrecerla
                // aquí era prometer algo que terminaba en un error del cliente.
                emptyMessage = "El sistema no ha devuelto ningún usuario.",
                emptyIcon = AppIcon.USERS,
            ) { users ->
                val filtered = filteredUsers(users, state, populationFilter, accountFilter, roleFilter, adminRoleIds)
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    if (filtered.isEmpty()) {
                        EmptyStateCard(
                            title = "Sin resultados",
                            message = "Ningún usuario coincide con la búsqueda y los filtros actuales.",
                            icon = AppIcon.SEARCH,
                        )
                    } else {
                        val listState = rememberLazyListState()
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                            ) {
                                items(filtered, key = { it.id }) { user ->
                                    UserRowCard(
                                        user = user,
                                        selected = user.id == state.selectedUserId,
                                        rolesById = rolesById,
                                        adminRoleIds = adminRoleIds,
                                        onClick = { onSelect(user.id) },
                                        modifier = Modifier.padding(end = 6.dp),
                                    )
                                }
                            }
                            DesktopVerticalScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                        }
                    }
                }
            }
        }
    }
}

private fun filteredUsers(
    users: List<User>,
    state: UsersState,
    populationFilter: PopulationFilter,
    accountFilter: AccountFilter,
    roleFilter: String?,
    adminRoleIds: Set<String>,
): List<User> = users.filter { user ->
    matchesSearch(user, state.searchQuery) &&
        matchesPopulation(user, populationFilter, adminRoleIds) &&
        matchesAccount(user, accountFilter) &&
        (roleFilter == null || roleFilter in user.roleIds)
}

private fun matchesSearch(user: User, query: String): Boolean {
    if (query.isBlank()) return true
    return user.fullName.contains(query, ignoreCase = true) ||
        user.email.contains(query, ignoreCase = true)
}

private fun matchesPopulation(
    user: User,
    filter: PopulationFilter,
    adminRoleIds: Set<String>,
): Boolean = when (filter) {
    PopulationFilter.TODAS -> true
    PopulationFilter.OPOSITORES -> userPopulation(user, adminRoleIds) == UserPopulation.OPOSITOR
    PopulationFilter.ADMINISTRACION -> userPopulation(user, adminRoleIds) == UserPopulation.ADMINISTRACION
}

private fun matchesAccount(user: User, filter: AccountFilter): Boolean = when (filter) {
    AccountFilter.TODAS -> true
    AccountFilter.REGISTRADOS -> userAccountKind(user) == UserAccountKind.REGISTRADO
    AccountFilter.INVITADOS -> userAccountKind(user) == UserAccountKind.INVITADO
}
