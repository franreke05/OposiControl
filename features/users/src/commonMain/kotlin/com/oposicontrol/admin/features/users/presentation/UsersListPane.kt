package com.oposicontrol.admin.features.users.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.AdminTable
import com.oposicontrol.admin.core.ui.components.AdminTableColumn
import com.oposicontrol.admin.core.ui.components.AdminTableRow
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.DesktopIconButton
import com.oposicontrol.admin.core.ui.components.FilterBar
import com.oposicontrol.admin.core.ui.components.GlassChip
import com.oposicontrol.admin.core.ui.components.Help
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.User

/**
 * Filtro por población.
 *
 * Sustituye al antiguo «Todos / Activos / Bloqueados». Aquel filtraba sobre
 * `isActive`, que el backend calcula como `!es_invitado`: pedir «Bloqueados»
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
 * Anchos a los que cada columna secundaria empieza a caber.
 *
 * Son umbrales sobre el ancho **del panel**, no sobre el de la ventana, y esa es
 * justo la corrección: `CrudSplitLayout` da a la lista un `weight(0.32f)` con
 * tope de 560dp, así que en una ventana de 1920 —que es WIDE— este panel mide
 * unos 525dp. Decidir las columnas con el breakpoint de ventana metía seis
 * columnas donde caben tres, y «OT» y «Cuenta» se quedaban fuera del scroll
 * horizontal de la tabla: invisibles, no estrechas.
 *
 * Es el mismo error que ya costó un apilado indebido en Preguntas, al revés:
 * allí se aplicó el breakpoint al ancho del contenido, aquí el de la ventana a un
 * panel. La regla que queda: el breakpoint describe la VENTANA; para repartir
 * dentro de un panel se miden umbrales explícitos.
 */
private object UsersTableWidths {
    val tickets = 620.dp
    val role = 820.dp
    val signup = 1000.dp
}

/**
 * Catálogo de usuarios.
 *
 * Dos decisiones que vienen de la auditoría del contrato, no del diseño:
 *
 * - Hay una columna **Población**. Esta tabla mezcla a los usuarios de la app con
 *   las cuentas que tienen acceso a este mismo panel, porque ambas son filas de
 *   `perfiles_usuario` y sólo las separa el rol. Antes había que leer la columna
 *   de roles e interpretarla.
 * - La columna de estado dice **Registrado/Invitado** y no Activo/Bloqueado. No
 *   existe bloqueo en el sistema. Ver [USER_NO_BLOCK_STATE_NOTE].
 *
 * Ya no hay paginación de cliente. La había sobre una lista que el backend sirve
 * paginada de 50 en 50, así que partía en dos páginas de 25 lo que ya era una sola
 * página del servidor — y el pie decía «25 de 50 usuarios» como si 50 fuese el
 * total del sistema. Ahora se enseñan las filas cargadas y se dice que son las
 * primeras. Ver [userListTruncationNote].
 */
@OptIn(ExperimentalLayoutApi::class)
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

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        FilterBar(
            searchValue = state.searchQuery,
            onSearchChange = onSearch,
            placeholder = "Buscar por nombre o nombre de usuario",
        ) {
            PopulationFilter.entries.forEach { option ->
                GlassChip(
                    label = option.label,
                    selected = populationFilter == option,
                    onClick = { populationFilter = option },
                )
            }
        }

        // FlowRow y no Row: en el panel de lista —unos 525dp en 1920— los chips no
        // caben en una línea, y un `Row` no envuelve: comprime al último hasta
        // dejarlo en una columna de letras de veinte píxeles de ancho. «Moderador»
        // salía escrito en vertical. Se vio en la captura, no en la compilación.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                text = "Cuenta",
                style = MaterialTheme.typography.labelMedium,
                color = tokens.textSecondary,
            )
            AccountFilter.entries.forEach { option ->
                GlassChip(
                    label = option.label,
                    selected = accountFilter == option,
                    onClick = { accountFilter = option },
                )
            }
        }

        if (state.availableRoles.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    text = "Rol",
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.textSecondary,
                )
                GlassChip(
                    label = "Cualquiera",
                    selected = roleFilter == null,
                    onClick = { roleFilter = null },
                )
                state.availableRoles.forEach { role ->
                    GlassChip(
                        label = role.name,
                        selected = roleFilter == role.id,
                        onClick = { roleFilter = if (roleFilter == role.id) null else role.id },
                    )
                }
            }
        }

        AsyncStatePane(
            state = state.users,
            emptyTitle = "Sin usuarios",
            // Sin acción de crear: el alta no se hace desde el panel, y ofrecerla
            // aquí era prometer algo que terminaba en un error del cliente.
            emptyMessage = "El sistema no ha devuelto ningún usuario.",
            emptyIcon = AppIcon.USERS,
        ) { users ->
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val showTickets = maxWidth >= UsersTableWidths.tickets
                val showRole = maxWidth >= UsersTableWidths.role
                val showSignup = maxWidth >= UsersTableWidths.signup

                val filtered = users.filter { user ->
                    matchesSearch(user, state.searchQuery) &&
                        matchesPopulation(user, populationFilter, adminRoleIds) &&
                        matchesAccount(user, accountFilter) &&
                        (roleFilter == null || roleFilter in user.roleIds)
                }

                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    // El recuento y el aviso de truncamiento van ENCIMA de la
                    // tabla, no debajo. Debajo no se leían nunca: `AdminTable` se
                    // queda con el alto disponible y los empujaba fuera del
                    // viewport. Y el aviso de truncamiento es justamente la
                    // corrección de que la lista se quedaba en 50 usuarios sin
                    // decirlo: invisible, no corregía nada.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        Text(
                            text = userCountLabel(shown = filtered.size, loaded = users.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.textSecondary,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    userListTruncationNote(users.size)?.let { note ->
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.warningInk,
                        )
                    }

                    AdminTable(
                        columns = buildList {
                            add(AdminTableColumn("Usuario", 1.5f, minWidth = 150.dp))
                            add(AdminTableColumn("Población", 0.9f, minWidth = 110.dp))
                            if (showRole) add(AdminTableColumn("Rol", 0.9f))
                            if (showSignup) add(AdminTableColumn("Alta", 0.7f))
                            if (showTickets) {
                                add(AdminTableColumn("OT", 0.5f, minWidth = 64.dp, numeric = true, help = Help.oposiTickets))
                            }
                            add(AdminTableColumn("Cuenta", 0.7f, minWidth = 96.dp))
                        },
                        rows = filtered.map { user ->
                            AdminTableRow(
                                key = user.id,
                                cells = buildList {
                                    add(userDisplayName(user))
                                    add(userPopulationLabel(userPopulation(user, adminRoleIds)))
                                    if (showRole) add(userRoleSummary(user, rolesById))
                                    if (showSignup) add(userSignupDate(user))
                                    if (showTickets) add(user.ticketsBalance.toString())
                                    add(userAccountKindLabel(userAccountKind(user)))
                                },
                                selected = user.id == state.selectedUserId,
                                supporting = userAccountName(user),
                            )
                        },
                        onRowClick = onSelect,
                        emptyTitle = "Sin resultados",
                        emptyMessage = "Ningún usuario coincide con la búsqueda y los filtros actuales.",
                        rowActions = { row ->
                            DesktopIconButton(
                                label = "Ver ficha de ${row.cells.firstOrNull().orEmpty()}",
                                onClick = { onSelect(row.key) },
                                size = 28.dp,
                            ) {
                                AppIconImage(AppIcon.VIEW, size = 14.dp)
                            }
                        },
                    )

                }
            }
        }
    }
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
