package com.oposicontrol.admin.app.root

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.EmptyStateCard
import com.oposicontrol.admin.core.ui.components.GlassSearchBar
import com.oposicontrol.admin.core.ui.components.GlassSurface
import com.oposicontrol.admin.core.ui.components.GlassTextField
import com.oposicontrol.admin.core.ui.components.LoadingStateCard
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.layout.UiSpacing
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.features.examaccess.presentation.ExamAccessIntent
import com.oposicontrol.admin.features.examaccess.presentation.ExamAccessViewModel
import com.oposicontrol.admin.features.exams.presentation.IconBadge
import com.oposicontrol.admin.features.exams.presentation.LineIcon
import com.oposicontrol.admin.features.exams.presentation.MobileActionPill
import com.oposicontrol.admin.features.exams.presentation.MobileActionSpec
import com.oposicontrol.admin.features.exams.presentation.MobileIcon
import com.oposicontrol.admin.features.exams.presentation.ResponsiveActionRow
import com.oposicontrol.admin.features.settings.presentation.SettingsIntent
import com.oposicontrol.admin.features.settings.presentation.SettingsViewModel
import com.oposicontrol.admin.features.support.presentation.SupportIntent
import com.oposicontrol.admin.features.support.presentation.SupportViewModel
import com.oposicontrol.admin.features.tickets.presentation.TicketsIntent
import com.oposicontrol.admin.features.tickets.presentation.TicketsViewModel
import com.oposicontrol.admin.features.users.presentation.UsersIntent
import com.oposicontrol.admin.features.users.presentation.UsersViewModel

private enum class AdminSection(val label: String, val icon: MobileIcon, val description: String) {
    Usuarios("Usuarios", MobileIcon.Users, "Gestiona cuentas y roles"),
    Accesos("Accesos", MobileIcon.Key, "Control de accesos a examenes"),
    Tickets("Tickets", MobileIcon.Wallet, "Carteras y ajustes de saldo"),
    Soporte("Soporte", MobileIcon.Chat, "Conversaciones de soporte"),
    Ajustes("Ajustes", MobileIcon.Gear, "Configuracion del sistema"),
}

@Composable
fun MobileAdminPanel(
    usersViewModel: UsersViewModel,
    accessViewModel: ExamAccessViewModel,
    ticketsViewModel: TicketsViewModel,
    supportViewModel: SupportViewModel,
    settingsViewModel: SettingsViewModel,
) {
    val usersState by usersViewModel.state.collectAsState()
    val accessState by accessViewModel.state.collectAsState()
    val ticketsState by ticketsViewModel.state.collectAsState()
    val supportState by supportViewModel.state.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()

    var activeSection by rememberSaveable { mutableStateOf<AdminSection?>(null) }

    Crossfade(
        targetState = activeSection,
        animationSpec = tween(durationMillis = 200),
    ) { section ->
        if (section == null) {
            AdminHub(onSection = { activeSection = it })
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(UiSpacing.normal),
            ) {
                AdminBackHeader(section = section, onBack = { activeSection = null })
                when (section) {
                    AdminSection.Usuarios -> AdminUsersSection(
                        usersState = usersState,
                        onIntent = usersViewModel::dispatch,
                    )
                    AdminSection.Accesos -> AdminAccessSection(
                        accessState = accessState,
                        onIntent = accessViewModel::dispatch,
                    )
                    AdminSection.Tickets -> AdminTicketsSection(
                        ticketsState = ticketsState,
                        onIntent = ticketsViewModel::dispatch,
                    )
                    AdminSection.Soporte -> AdminSupportSection(
                        supportState = supportState,
                        onIntent = supportViewModel::dispatch,
                    )
                    AdminSection.Ajustes -> AdminSettingsSection(
                        settingsState = settingsState,
                        onIntent = settingsViewModel::dispatch,
                    )
                }
            }
        }
    }
}

@Composable
private fun AdminHub(onSection: (AdminSection) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AdminSection.entries.forEach { section ->
            AdminEntryCard(section = section, onClick = { onSection(section) })
        }
    }
}

@Composable
private fun AdminEntryCard(section: AdminSection, onClick: () -> Unit) {
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(icon = section.icon, selected = false)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = section.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = section.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LineIcon(icon = MobileIcon.Back, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun AdminBackHeader(section: AdminSection, onBack: () -> Unit) {
    GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MobileActionPill(
                icon = MobileIcon.Back,
                label = "Volver",
                modifier = Modifier.width(104.dp),
                onClick = onBack,
            )
            IconBadge(icon = section.icon, selected = true)
            Text(
                text = section.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ─── USUARIOS ──────────────────────────────────────────────────────────────────

@Composable
private fun AdminUsersSection(
    usersState: com.oposicontrol.admin.features.users.presentation.UsersState,
    onIntent: (UsersIntent) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (val users = usersState.users) {
            is UiAsync.Loading -> LoadingStateCard()
            is UiAsync.Empty -> EmptyStateCard(
                title = "Sin usuarios",
                message = "No hay usuarios registrados.",
                action = {
                    MobileActionPill(
                        icon = MobileIcon.Plus,
                        label = "Nuevo",
                        onClick = { onIntent(UsersIntent.CreateNew) },
                    )
                },
            )
            is UiAsync.Error -> EmptyStateCard(
                title = "Error al cargar",
                message = users.error.message,
            )
            is UiAsync.Success -> {
                ResponsiveActionRow(
                    actions = listOf(
                        MobileActionSpec(MobileIcon.Plus, "Nuevo", onClick = { onIntent(UsersIntent.CreateNew) }),
                    ),
                    maxItemsInEachRow = 1,
                )
                users.value.forEach { user ->
                    val selected = usersState.selectedUserId == user.id
                    GlassSurface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onIntent(UsersIntent.SelectUser(user.id)) },
                        shape = RoundedCornerShape(18.dp),
                        tint = if (selected) GlassTheme.tokens.accent.copy(alpha = 0.10f) else GlassTheme.tokens.glassFill,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = user.fullName.ifBlank { "Sin nombre" },
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = user.email,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (user.roleIds.isNotEmpty()) {
                                    Text(
                                        text = user.roleIds.joinToString(", "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            StatusChip(status = if (user.isActive) "active" else "inactive")
                        }
                    }
                }
            }
            is UiAsync.Idle -> Unit
        }

        if (usersState.selectedUserId != null || usersState.editorName.isNotBlank() || usersState.editorEmail.isNotBlank()) {
            UsersEditorCard(state = usersState, onIntent = onIntent)
        }
    }
}

@Composable
private fun UsersEditorCard(
    state: com.oposicontrol.admin.features.users.presentation.UsersState,
    onIntent: (UsersIntent) -> Unit,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(UiSpacing.panel),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (state.selectedUserId != null) "Editar usuario" else "Nuevo usuario",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            GlassTextField(
                value = state.editorName,
                onValueChange = { onIntent(UsersIntent.UpdateName(it)) },
                placeholder = "Nombre completo",
                modifier = Modifier.fillMaxWidth(),
            )
            GlassTextField(
                value = state.editorEmail,
                onValueChange = { onIntent(UsersIntent.UpdateEmail(it)) },
                placeholder = "Email",
                modifier = Modifier.fillMaxWidth(),
            )
            GlassTextField(
                value = state.editorRoleIds,
                onValueChange = { onIntent(UsersIntent.UpdateRoleIds(it)) },
                placeholder = "Roles (separados por coma)",
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Activo", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = state.editorIsActive,
                    onCheckedChange = { onIntent(UsersIntent.ToggleActive(it)) },
                    colors = SwitchDefaults.colors(checkedThumbColor = GlassTheme.tokens.accent),
                )
            }
            AdminActionRow(
                actionState = state.actionState,
                onSave = { onIntent(UsersIntent.Save) },
                onDelete = if (state.selectedUserId != null) {
                    { onIntent(UsersIntent.DeleteSelected) }
                } else null,
            )
        }
    }
}

// ─── ACCESOS ───────────────────────────────────────────────────────────────────

@Composable
private fun AdminAccessSection(
    accessState: com.oposicontrol.admin.features.examaccess.presentation.ExamAccessState,
    onIntent: (ExamAccessIntent) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassSearchBar(
                value = accessState.searchQuery,
                onValueChange = { onIntent(ExamAccessIntent.UpdateSearch(it)) },
                modifier = Modifier.weight(1f),
            )
            MobileActionPill(
                icon = MobileIcon.Plus,
                label = "Nuevo",
                modifier = Modifier.width(104.dp),
                onClick = { onIntent(ExamAccessIntent.CreateNew) },
            )
        }

        when (val accesses = accessState.accesses) {
            is UiAsync.Loading -> LoadingStateCard()
            is UiAsync.Empty -> EmptyStateCard(title = "Sin accesos", message = "No hay accesos registrados.")
            is UiAsync.Error -> EmptyStateCard(title = "Error al cargar", message = accesses.error.message)
            is UiAsync.Success -> accesses.value.forEach { item ->
                val selected = accessState.selectedId == item.id
                GlassSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onIntent(ExamAccessIntent.Select(item.id)) },
                    shape = RoundedCornerShape(18.dp),
                    tint = if (selected) GlassTheme.tokens.accent.copy(alpha = 0.10f) else GlassTheme.tokens.glassFill,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = item.userName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            StatusChip(status = item.status)
                        }
                        Text(
                            text = item.examTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = item.accessType,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            is UiAsync.Idle -> Unit
        }

        if (accessState.selectedId != null || accessState.editorUserName.isNotBlank()) {
            AccessEditorCard(state = accessState, onIntent = onIntent)
        }
    }
}

@Composable
private fun AccessEditorCard(
    state: com.oposicontrol.admin.features.examaccess.presentation.ExamAccessState,
    onIntent: (ExamAccessIntent) -> Unit,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(UiSpacing.panel),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (state.selectedId != null) "Editar acceso" else "Nuevo acceso",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            GlassTextField(
                value = state.editorUserName,
                onValueChange = { onIntent(ExamAccessIntent.UpdateUserName(it)) },
                placeholder = "Usuario",
                modifier = Modifier.fillMaxWidth(),
            )
            GlassTextField(
                value = state.editorExamTitle,
                onValueChange = { onIntent(ExamAccessIntent.UpdateExamTitle(it)) },
                placeholder = "Examen",
                modifier = Modifier.fillMaxWidth(),
            )
            GlassTextField(
                value = state.editorAccessType,
                onValueChange = { onIntent(ExamAccessIntent.UpdateAccessType(it)) },
                placeholder = "Tipo (manual, promo...)",
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Activo", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = state.editorIsActive,
                    onCheckedChange = { onIntent(ExamAccessIntent.ToggleActive(it)) },
                    colors = SwitchDefaults.colors(checkedThumbColor = GlassTheme.tokens.accent),
                )
            }
            AdminActionRow(
                actionState = state.actionState,
                onSave = { onIntent(ExamAccessIntent.Save) },
                onDelete = null,
            )
        }
    }
}

// ─── TICKETS ───────────────────────────────────────────────────────────────────

@Composable
private fun AdminTicketsSection(
    ticketsState: com.oposicontrol.admin.features.tickets.presentation.TicketsState,
    onIntent: (TicketsIntent) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GlassSearchBar(
            value = ticketsState.searchQuery,
            onValueChange = { onIntent(TicketsIntent.UpdateSearch(it)) },
            modifier = Modifier.fillMaxWidth(),
        )

        when (val wallets = ticketsState.wallets) {
            is UiAsync.Loading -> LoadingStateCard()
            is UiAsync.Empty -> EmptyStateCard(title = "Sin carteras", message = "No hay carteras disponibles.")
            is UiAsync.Error -> EmptyStateCard(title = "Error al cargar", message = wallets.error.message)
            is UiAsync.Success -> wallets.value.forEach { wallet ->
                val selected = ticketsState.selectedUserId == wallet.userId
                GlassSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onIntent(TicketsIntent.SelectWallet(wallet.userId)) },
                    shape = RoundedCornerShape(18.dp),
                    tint = if (selected) GlassTheme.tokens.accent.copy(alpha = 0.10f) else GlassTheme.tokens.glassFill,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = wallet.userName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = wallet.specialty,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "%.1f tickets".format(wallet.balance),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = GlassTheme.tokens.accent,
                            )
                            StatusChip(status = wallet.status)
                        }
                    }
                }
            }
            is UiAsync.Idle -> Unit
        }

        if (ticketsState.selectedUserId != null) {
            TicketsAdjustmentCard(state = ticketsState, onIntent = onIntent)
            TicketsHistorySection(state = ticketsState)
        }
    }
}

@Composable
private fun TicketsAdjustmentCard(
    state: com.oposicontrol.admin.features.tickets.presentation.TicketsState,
    onIntent: (TicketsIntent) -> Unit,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(UiSpacing.panel),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Ajuste de saldo", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            GlassTextField(
                value = state.adjustmentAmount,
                onValueChange = { onIntent(TicketsIntent.UpdateAdjustmentAmount(it)) },
                placeholder = "Importe (ej: 5.0 o -2.0)",
                modifier = Modifier.fillMaxWidth(),
            )
            GlassTextField(
                value = state.adjustmentReason,
                onValueChange = { onIntent(TicketsIntent.UpdateAdjustmentReason(it)) },
                placeholder = "Razon del ajuste",
                modifier = Modifier.fillMaxWidth(),
            )
            AdminActionRow(
                actionState = state.actionState,
                saveLabel = "Aplicar",
                saveIcon = MobileIcon.Check,
                onSave = { onIntent(TicketsIntent.ApplyAdjustment) },
                onDelete = null,
            )
        }
    }
}

@Composable
private fun TicketsHistorySection(
    state: com.oposicontrol.admin.features.tickets.presentation.TicketsState,
) {
    when (val transactions = state.transactions) {
        is UiAsync.Loading -> LoadingStateCard()
        is UiAsync.Empty -> EmptyStateCard(title = "Sin movimientos", message = "Esta cartera no tiene movimientos.")
        is UiAsync.Error -> EmptyStateCard(title = "Error", message = transactions.error.message)
        is UiAsync.Success -> {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Historial",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                transactions.value.forEach { movement ->
                    GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = movement.reason.ifBlank { movement.type },
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = movement.occurredAt,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            val amountColor = if (movement.amount >= 0) GlassTheme.tokens.accent
                            else MaterialTheme.colorScheme.error
                            Text(
                                text = "%+.1f".format(movement.amount),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = amountColor,
                            )
                        }
                    }
                }
            }
        }
        is UiAsync.Idle -> Unit
    }
}

// ─── SOPORTE ───────────────────────────────────────────────────────────────────

@Composable
private fun AdminSupportSection(
    supportState: com.oposicontrol.admin.features.support.presentation.SupportState,
    onIntent: (SupportIntent) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassSearchBar(
                value = supportState.searchQuery,
                onValueChange = { onIntent(SupportIntent.UpdateSearch(it)) },
                modifier = Modifier.weight(1f),
            )
        }
        SupportStatusFilter(
            current = supportState.statusFilter,
            onSelected = { onIntent(SupportIntent.UpdateStatusFilter(it)) },
        )

        when (val conversations = supportState.conversations) {
            is UiAsync.Loading -> LoadingStateCard()
            is UiAsync.Empty -> EmptyStateCard(title = "Sin conversaciones", message = "No hay conversaciones activas.")
            is UiAsync.Error -> EmptyStateCard(title = "Error al cargar", message = conversations.error.message)
            is UiAsync.Success -> conversations.value.forEach { conv ->
                val selected = supportState.selectedConversationId == conv.id
                GlassSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onIntent(SupportIntent.SelectConversation(conv.id)) },
                    shape = RoundedCornerShape(18.dp),
                    tint = if (selected) GlassTheme.tokens.accent.copy(alpha = 0.10f) else GlassTheme.tokens.glassFill,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = conv.userName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            StatusChip(status = conv.status)
                        }
                        Text(
                            text = conv.subject,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (conv.lastMessage.isNotBlank()) {
                            Text(
                                text = conv.lastMessage,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            is UiAsync.Idle -> Unit
        }

        if (supportState.selectedConversationId != null) {
            SupportMessagesSection(state = supportState)
            SupportReplyCard(state = supportState, onIntent = onIntent)
        }
    }
}

@Composable
private fun SupportStatusFilter(current: String, onSelected: (String) -> Unit) {
    val options = listOf("all" to "Todos", "open" to "Abiertos", "resolved" to "Resueltos", "closed" to "Cerrados")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (value, label) ->
            val selected = current == value
            val bg = if (selected) GlassTheme.tokens.accent.copy(alpha = 0.16f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            val fg = if (selected) GlassTheme.tokens.accent else MaterialTheme.colorScheme.onSurfaceVariant
            Text(
                text = label,
                modifier = Modifier
                    .background(bg, RoundedCornerShape(12.dp))
                    .clickable { onSelected(value) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = fg,
            )
        }
    }
}

@Composable
private fun SupportMessagesSection(
    state: com.oposicontrol.admin.features.support.presentation.SupportState,
) {
    when (val messages = state.messages) {
        is UiAsync.Loading -> LoadingStateCard()
        is UiAsync.Empty -> EmptyStateCard(title = "Sin mensajes", message = "No hay mensajes en esta conversacion.")
        is UiAsync.Error -> EmptyStateCard(title = "Error", message = messages.error.message)
        is UiAsync.Success -> {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Mensajes",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                messages.value.forEach { msg ->
                    val noteBg = if (msg.internal) MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
                    else GlassTheme.tokens.glassFill
                    GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), tint = noteBg) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(msg.authorName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                Text(msg.timestamp, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(msg.text, style = MaterialTheme.typography.bodySmall)
                            if (msg.internal) {
                                Text("Nota interna", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
        is UiAsync.Idle -> Unit
    }
}

@Composable
private fun SupportReplyCard(
    state: com.oposicontrol.admin.features.support.presentation.SupportState,
    onIntent: (SupportIntent) -> Unit,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(UiSpacing.panel),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Responder", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            GlassTextField(
                value = state.assignee,
                onValueChange = { onIntent(SupportIntent.UpdateAssignee(it)) },
                placeholder = "Asignado a",
                modifier = Modifier.fillMaxWidth(),
            )
            GlassTextField(
                value = state.replyText,
                onValueChange = { onIntent(SupportIntent.UpdateReplyText(it)) },
                placeholder = "Escribe tu respuesta...",
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Nota interna", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = state.sendAsInternalNote,
                    onCheckedChange = { onIntent(SupportIntent.ToggleInternalNote(it)) },
                    colors = SwitchDefaults.colors(checkedThumbColor = GlassTheme.tokens.accent),
                )
            }
            AdminActionRow(
                actionState = state.actionState,
                saveLabel = "Enviar",
                saveIcon = MobileIcon.Up,
                onSave = { onIntent(SupportIntent.SendReply) },
                onDelete = null,
            )
        }
    }
}

// ─── AJUSTES ───────────────────────────────────────────────────────────────────

@Composable
private fun AdminSettingsSection(
    settingsState: com.oposicontrol.admin.features.settings.presentation.SettingsState,
    onIntent: (SettingsIntent) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Entorno", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    text = settingsState.environmentLabel.ifBlank { "—" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when (val loadState = settingsState.settings) {
            is UiAsync.Loading -> LoadingStateCard()
            is UiAsync.Error -> EmptyStateCard(
                title = "Error al cargar",
                message = loadState.error.message,
            )
            else -> Unit
        }

        GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(UiSpacing.panel),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Perfil admin", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                GlassTextField(
                    value = settingsState.displayName,
                    onValueChange = { onIntent(SettingsIntent.UpdateDisplayName(it)) },
                    placeholder = "Nombre visible",
                    modifier = Modifier.fillMaxWidth(),
                )
                GlassTextField(
                    value = settingsState.email,
                    onValueChange = { onIntent(SettingsIntent.UpdateEmail(it)) },
                    placeholder = "Email",
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Release notes", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = settingsState.receiveReleaseNotes,
                        onCheckedChange = { onIntent(SettingsIntent.ToggleReleaseNotes(it)) },
                        colors = SwitchDefaults.colors(checkedThumbColor = GlassTheme.tokens.accent),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Logs verbosos", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = settingsState.enableVerboseLogs,
                        onCheckedChange = { onIntent(SettingsIntent.ToggleVerboseLogs(it)) },
                        colors = SwitchDefaults.colors(checkedThumbColor = GlassTheme.tokens.accent),
                    )
                }
                AdminActionRow(
                    actionState = settingsState.actionState,
                    saveLabel = "Guardar",
                    onSave = { onIntent(SettingsIntent.Save) },
                    onDelete = null,
                )
            }
        }
    }
}

// ─── SHARED ────────────────────────────────────────────────────────────────────

@Composable
private fun AdminActionRow(
    actionState: UiAsync<Unit>,
    saveLabel: String = "Guardar",
    saveIcon: MobileIcon = MobileIcon.Save,
    onSave: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (actionState) {
            is UiAsync.Error -> Text(
                text = actionState.error.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            is UiAsync.Success -> Text(
                text = "Guardado correctamente",
                style = MaterialTheme.typography.bodySmall,
                color = GlassTheme.tokens.accent,
            )
            else -> Unit
        }
        val saving = actionState is UiAsync.Loading
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MobileActionPill(
                icon = saveIcon,
                label = if (saving) "Guardando..." else saveLabel,
                modifier = Modifier.weight(1f),
                enabled = !saving,
                onClick = onSave,
            )
            if (onDelete != null) {
                MobileActionPill(
                    icon = MobileIcon.Remove,
                    label = "Eliminar",
                    modifier = Modifier.width(120.dp),
                    enabled = !saving,
                    onClick = onDelete,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
