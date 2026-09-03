package com.oposicontrol.admin.features.users.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.DesktopIconButton
import com.oposicontrol.admin.core.ui.components.FilterBar
import com.oposicontrol.admin.core.ui.components.GlassChip
import com.oposicontrol.admin.core.ui.components.Help
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.layout.DesktopWindow
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.User

/** Filtro por estado de acceso. Los tres valores cubren la lista entera. */
private enum class UserStatusFilter(val label: String) {
    TODOS("Todos"),
    ACTIVOS("Activos"),
    BLOQUEADOS("Bloqueados"),
}

private const val PAGE_SIZE = 25

/**
 * Lista de usuarios.
 *
 * Se muestran los campos que el modelo de lista tiene de verdad: nombre, email,
 * rol, alta, saldo y estado. Especialidad y plan **no** están en `User` —viven en
 * `UserDetail`, que se carga por usuario— así que ponerlos aquí exigiría una
 * consulta por fila. Se dejan para la ficha en lugar de inventar columnas.
 *
 * La paginación es de cliente sobre la lista ya cargada. No es una decisión de
 * diseño sino la consecuencia de que el repositorio devuelve la lista completa:
 * al menos evita pintar cientos de filas de golpe, y el contador dice cuántas hay
 * en total para que nadie crea que sólo existen 25.
 */
@Composable
internal fun UsersListPane(
    state: UsersState,
    roleName: Map<String, String>,
    onSearch: (String) -> Unit,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
) {
    val tokens = GlassTheme.tokens
    val breakpoint = DesktopWindow.breakpoint
    var statusFilter by rememberSaveable { mutableStateOf(UserStatusFilter.TODOS) }
    var roleFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var page by rememberSaveable { mutableStateOf(0) }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        FilterBar(
            searchValue = state.searchQuery,
            onSearchChange = {
                onSearch(it)
                page = 0
            },
            placeholder = "Buscar por nombre o email",
        ) {
            UserStatusFilter.entries.forEach { option ->
                GlassChip(
                    label = option.label,
                    selected = statusFilter == option,
                    onClick = {
                        statusFilter = option
                        page = 0
                    },
                )
            }
        }

        if (state.availableRoles.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Rol",
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.textSecondary,
                )
                GlassChip(
                    label = "Cualquiera",
                    selected = roleFilter == null,
                    onClick = {
                        roleFilter = null
                        page = 0
                    },
                )
                state.availableRoles.forEach { role ->
                    GlassChip(
                        label = role.name,
                        selected = roleFilter == role.id,
                        onClick = {
                            roleFilter = if (roleFilter == role.id) null else role.id
                            page = 0
                        },
                    )
                }
            }
        }

        AsyncStatePane(
            state = state.users,
            emptyTitle = "Sin usuarios",
            emptyMessage = "Todavía no hay ningún usuario registrado.",
            emptyIcon = AppIcon.USERS,
            emptyAction = { PrimaryButton("Crear usuario", onCreate, size = ButtonSize.COMPACT) },
        ) { users ->
            val filtered = users.filter { user ->
                val matchesSearch = state.searchQuery.isBlank() ||
                    user.fullName.contains(state.searchQuery, ignoreCase = true) ||
                    user.email.contains(state.searchQuery, ignoreCase = true)
                val matchesStatus = when (statusFilter) {
                    UserStatusFilter.TODOS -> true
                    UserStatusFilter.ACTIVOS -> user.isActive
                    UserStatusFilter.BLOQUEADOS -> !user.isActive
                }
                val matchesRole = roleFilter == null || roleFilter in user.roleIds
                matchesSearch && matchesStatus && matchesRole
            }
            val pageCount = ((filtered.size - 1) / PAGE_SIZE + 1).coerceAtLeast(1)
            // La página se recorta en vez de dejarse fuera de rango: filtrar
            // puede reducir la lista por debajo de la página en la que estabas,
            // y quedarse mirando una tabla vacía con datos detrás sería un error
            // difícil de entender.
            val safePage = page.coerceIn(0, pageCount - 1)
            val visible = filtered.drop(safePage * PAGE_SIZE).take(PAGE_SIZE)

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                AdminTable(
                    columns = buildList {
                        add(AdminTableColumn("Usuario", 1.4f, minWidth = 150.dp))
                        add(AdminTableColumn("Rol", 0.9f))
                        // En ventana estrecha se ocultan las columnas secundarias
                        // antes que dejar que la tabla se desborde en horizontal.
                        if (breakpoint.isAtLeastStandard) {
                            add(AdminTableColumn("Alta", 0.7f))
                        }
                        add(AdminTableColumn("OT", 0.5f, numeric = true, help = Help.oposiTickets))
                        add(AdminTableColumn("Estado", 0.7f))
                    },
                    rows = visible.map { user ->
                        AdminTableRow(
                            key = user.id,
                            cells = buildList {
                                add(user.fullName)
                                add(user.roleIds.joinToString { roleName[it] ?: it }.ifBlank { "Sin roles" })
                                if (breakpoint.isAtLeastStandard) add(user.createdAt.toString().take(10))
                                add(user.ticketsBalance.toString())
                                add(if (user.isActive) "Activo" else "Bloqueado")
                            },
                            selected = user.id == state.selectedUserId,
                            supporting = user.email,
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

                UsersTableFooter(
                    total = filtered.size,
                    shown = visible.size,
                    page = safePage,
                    pageCount = pageCount,
                    onPrev = { page = (safePage - 1).coerceAtLeast(0) },
                    onNext = { page = (safePage + 1).coerceAtMost(pageCount - 1) },
                    onCreate = onCreate,
                )
            }
        }
    }
}

@Composable
private fun UsersTableFooter(
    total: Int,
    shown: Int,
    page: Int,
    pageCount: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onCreate: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            // Se dice el total, no sólo lo que se ve: si no, veinticinco filas
            // parecen ser todos los usuarios que existen.
            text = if (total == 0) "Sin usuarios" else "$shown de $total usuarios",
            style = MaterialTheme.typography.bodySmall,
            color = GlassTheme.tokens.textSecondary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (pageCount > 1) {
            DesktopIconButton(label = "Página anterior", onClick = onPrev, enabled = page > 0, size = 28.dp) {
                AppIconImage(AppIcon.CHEVRON_LEFT, size = 14.dp)
            }
            Text(
                text = "${page + 1}/$pageCount",
                style = MaterialTheme.typography.labelMedium,
                color = GlassTheme.tokens.textSecondary,
            )
            DesktopIconButton(
                label = "Página siguiente",
                onClick = onNext,
                enabled = page < pageCount - 1,
                size = 28.dp,
            ) {
                AppIconImage(AppIcon.CHEVRON_RIGHT, size = 14.dp)
            }
        }
        SecondaryButton(
            text = "Nuevo usuario",
            onClick = onCreate,
            size = ButtonSize.COMPACT,
            icon = { AppIconImage(AppIcon.ADD, size = 14.dp) },
        )
    }
}

