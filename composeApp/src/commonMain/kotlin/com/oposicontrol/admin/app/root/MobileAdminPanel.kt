package com.oposicontrol.admin.app.root

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.AccountStatus
import com.oposicontrol.admin.domain.model.ForumReport
import com.oposicontrol.admin.core.ui.components.EmptyStateCard
import com.oposicontrol.admin.core.ui.components.GlassChip
import com.oposicontrol.admin.core.ui.components.GlassChipGroup
import com.oposicontrol.admin.core.ui.components.GlassSearchBar
import com.oposicontrol.admin.core.ui.components.GlassSurface
import com.oposicontrol.admin.core.ui.components.GlassTextField
import com.oposicontrol.admin.core.ui.components.LoadingStateCard
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.components.StatusTone
import com.oposicontrol.admin.core.ui.layout.UiSpacing
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.features.examaccess.presentation.ExamAccessIntent
import com.oposicontrol.admin.features.examaccess.presentation.ExamAccessViewModel
import com.oposicontrol.admin.features.moderation.presentation.ForumModerationIntent
import com.oposicontrol.admin.features.moderation.presentation.ForumModerationState
import com.oposicontrol.admin.features.moderation.presentation.ForumModerationViewModel
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
import com.oposicontrol.admin.features.users.presentation.USER_ACCOUNT_FIELD_LABEL
import com.oposicontrol.admin.features.users.presentation.USER_EMAIL_UNAVAILABLE_NOTE
import com.oposicontrol.admin.features.users.presentation.UsersIntent
import com.oposicontrol.admin.features.users.presentation.UsersViewModel
import com.oposicontrol.admin.features.users.presentation.userAccountKind
import com.oposicontrol.admin.features.users.presentation.userAccountKindLabel
import com.oposicontrol.admin.features.users.presentation.userAccountName
import com.oposicontrol.admin.features.users.presentation.userAccountStatusLabel
import com.oposicontrol.admin.features.users.presentation.userAccountStatusNeedsAttention
import com.oposicontrol.admin.features.users.presentation.userRoleSummary
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class AdminSection(val label: String, val icon: MobileIcon, val description: String) {
    Moderacion("Moderación", MobileIcon.Eye, "Reportes del foro OposiRed"),
    Usuarios("Usuarios", MobileIcon.Users, "Usuarios y dar opositickets"),
    Tickets("Tickets", MobileIcon.Wallet, "Carteras y ajustes de saldo"),
    Soporte("Soporte", MobileIcon.Chat, "Conversaciones de soporte"),
    Accesos("Accesos", MobileIcon.Key, "Control de accesos a exámenes"),
    Ajustes("Ajustes", MobileIcon.Gear, "Configuración del sistema"),
}

private val AdminUserCardShape = RoundedCornerShape(12.dp)
private val AdminUserPanelShape = RoundedCornerShape(14.dp)
private val AdminUserControlShape = RoundedCornerShape(8.dp)
private val AdminUserActionShape = RoundedCornerShape(10.dp)
private val AdminHubCardShape = RoundedCornerShape(12.dp)
private const val AdminListPreviewLimit = 30
private const val AdminDetailPreviewLimit = 20

@Composable
fun MobileAdminPanel(
    usersViewModel: UsersViewModel,
    moderationViewModel: ForumModerationViewModel,
    accessViewModel: ExamAccessViewModel,
    ticketsViewModel: TicketsViewModel,
    supportViewModel: SupportViewModel,
    settingsViewModel: SettingsViewModel,
) {
    val usersState by usersViewModel.state.collectAsState()
    val moderationState by moderationViewModel.state.collectAsState()
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
                    AdminSection.Moderacion -> AdminModerationSection(
                        state = moderationState,
                        onIntent = moderationViewModel::dispatch,
                    )
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
        verticalArrangement = Arrangement.spacedBy(8.dp),
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
            .height(72.dp)
            .clickable(onClick = onClick),
        shape = AdminHubCardShape,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AdminBackHeader(section: AdminSection, onBack: () -> Unit) {
    GlassSurface(modifier = Modifier.fillMaxWidth(), shape = AdminUserPanelShape) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AdminSectionBackButton(onClick = onBack)
            IconBadge(icon = section.icon, selected = true)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = section.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = section.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AdminSectionBackButton(onClick: () -> Unit) {
    GlassSurface(
        modifier = Modifier
            .width(48.dp)
            .height(48.dp)
            .clickable(onClick = onClick),
        shape = AdminUserActionShape,
        tint = GlassTheme.tokens.accent,
        strokeColor = GlassTheme.tokens.accent.copy(alpha = 0.44f),
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LineIcon(icon = MobileIcon.Back, modifier = Modifier.size(18.dp), color = Color.White)
        }
    }
}

@Composable
private fun AdminListMeta(total: Int, visible: Int, label: String) {
    val text = if (total > visible) {
        "Mostrando $visible de $total $label"
    } else {
        "$total $label"
    }
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = GlassTheme.tokens.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

// ─── MODERACIÓN FORO ─────────────────────────────────────────────────────────

@Composable
private fun AdminModerationSection(
    state: ForumModerationState,
    onIntent: (ForumModerationIntent) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ResponsiveActionRow(
            actions = listOf(
                MobileActionSpec(MobileIcon.Refresh, "Actualizar", onClick = { onIntent(ForumModerationIntent.Refresh) }),
            ),
            maxItemsInEachRow = 1,
        )
        when (val reports = state.reports) {
            is UiAsync.Loading -> LoadingStateCard()
            is UiAsync.Empty -> EmptyStateCard(
                title = "Sin reportes pendientes",
                message = "La cola de moderación del foro está limpia.",
            )
            is UiAsync.Error -> EmptyStateCard(title = "Error al cargar", message = reports.error.message)
            is UiAsync.Success -> {
                val visibleReports = reports.value.take(AdminListPreviewLimit)
                AdminListMeta(total = reports.value.size, visible = visibleReports.size, label = "reportes")
                visibleReports.forEach { report ->
                    ReportCard(
                        report = report,
                        busy = state.actionState is UiAsync.Loading,
                        onResolve = { action, tickets ->
                            onIntent(ForumModerationIntent.Resolve(report.id, action, null, tickets))
                        },
                    )
                }
            }
            is UiAsync.Idle -> Unit
        }
    }
}

private val MODERATION_ACTIONS = listOf("descartar" to "Descartar", "ocultar" to "Ocultar", "avisar" to "Avisar", "banear" to "Banear")
private val REWARD_PRESETS = listOf(0, 5, 10)

@Composable
private fun ReportCard(
    report: ForumReport,
    busy: Boolean,
    onResolve: (action: String, ticketsReporter: Int) -> Unit,
) {
    var action by rememberSaveable(report.id) { mutableStateOf("descartar") }
    var reward by rememberSaveable(report.id) { mutableStateOf(0) }
    GlassSurface(modifier = Modifier.fillMaxWidth(), shape = AdminUserPanelShape) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusChip(status = report.reason.ifBlank { "reporte" })
                Text(
                    text = report.entityType,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (report.description.isNotBlank()) {
                Text(report.description, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text = "Reportado por ${report.reporterName.ifBlank { report.reporterId.take(8) }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Acción", style = MaterialTheme.typography.labelMedium, color = GlassTheme.tokens.textSecondary)
            GlassChipGroup(
                options = MODERATION_ACTIONS,
                selected = setOf(action),
                onToggle = { action = it },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Recompensa al reporter", style = MaterialTheme.typography.labelMedium, color = GlassTheme.tokens.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                REWARD_PRESETS.forEach { preset ->
                    GlassChip(
                        label = if (preset == 0) "Sin tickets" else "+$preset",
                        selected = reward == preset,
                        onClick = { reward = preset },
                    )
                }
            }
            MobileActionPill(
                icon = MobileIcon.Check,
                label = if (busy) "Resolviendo..." else "Resolver",
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                onClick = { onResolve(action, reward) },
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
    var query by rememberSaveable { mutableStateOf("") }
    val showEditor = usersState.selectedUserId != null || usersState.editorName.isNotBlank()

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (val users = usersState.users) {
            is UiAsync.Loading -> LoadingStateCard()
            // Sin acción de crear: el alta se hace en el sistema de autenticación,
            // no desde el panel, y el cliente cortaba la llamada con un error.
            is UiAsync.Empty -> EmptyStateCard(
                title = "Sin usuarios",
                message = "El sistema no ha devuelto ningún usuario.",
            )
            is UiAsync.Error -> EmptyStateCard(
                title = "Error al cargar",
                message = users.error.message,
            )
            is UiAsync.Success -> {
                val normalizedQuery = query.trim()
                val filteredUsers = if (normalizedQuery.length >= 2) {
                    users.value.filter { user ->
                        user.fullName.contains(normalizedQuery, ignoreCase = true) ||
                            user.email.contains(normalizedQuery, ignoreCase = true) ||
                            user.roleIds.any { it.contains(normalizedQuery, ignoreCase = true) }
                    }
                } else {
                    users.value
                }
                val visibleUsers = filteredUsers.take(AdminListPreviewLimit)
                AdminUsersListToolbar(
                    total = users.value.size,
                    visible = visibleUsers.size,
                    filtered = filteredUsers.size,
                    query = query,
                    onQueryChange = { query = it },
                )
                if (showEditor) {
                    UsersEditorCard(state = usersState, onIntent = onIntent)
                }
                if (visibleUsers.isEmpty()) {
                    EmptyStateCard(
                        title = "Sin resultados",
                        message = "Prueba con otro nombre o nombre de usuario.",
                    )
                }
                visibleUsers.forEach { user ->
                    val selected = usersState.selectedUserId == user.id
                    GlassSurface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(88.dp)
                            .clickable { onIntent(UsersIntent.SelectUser(user.id)) },
                        shape = AdminUserCardShape,
                        tint = if (selected) GlassTheme.tokens.accent.copy(alpha = 0.08f) else GlassTheme.tokens.glassFill,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(88.dp)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
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
                                Text(
                                    // Tipo de cuenta (registrada/invitada) + nombres de rol, no sus identificadores:
                                    // aquí se pintaban los UUID del catálogo.
                                    text = userAccountKindLabel(userAccountKind(user)) + if (user.roleIds.isNotEmpty()) {
                                        " · " + userRoleSummary(user, usersState.rolesById)
                                    } else {
                                        ""
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(
                                modifier = Modifier.width(112.dp),
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = user.ticketsBalance?.let { "$it tks" } ?: "Sin dato",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = GlassTheme.tokens.accent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                // MASTER4-OC-004: el estado de la cuenta (AccountStatus canonico), no el tipo invitado/registrado
                                // (`isActive` = `!es_invitado`, que va en la linea de la izquierda). Bloqueada o con eliminacion
                                // pendiente piden atencion; un estado ausente/desconocido dice "Sin dato", nunca "Activa".
                                StatusChip(
                                    status = userAccountStatusLabel(user.accountStatus),
                                    tone = when {
                                        user.accountStatus == AccountStatus.ACTIVE -> StatusTone.POSITIVE
                                        userAccountStatusNeedsAttention(user.accountStatus) -> StatusTone.ATTENTION
                                        else -> StatusTone.NEUTRAL
                                    },
                                )
                            }
                        }
                    }
                }
            }
            is UiAsync.Idle -> Unit
        }

        if (showEditor && usersState.users !is UiAsync.Success) {
            UsersEditorCard(state = usersState, onIntent = onIntent)
        }
    }
}

@Composable
private fun AdminUsersListToolbar(
    total: Int,
    visible: Int,
    filtered: Int,
    query: String,
    onQueryChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GlassSearchBar(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = if (query.trim().length >= 2) "$filtered encontrados" else "$total usuarios",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = GlassTheme.tokens.textPrimary,
                )
                Text(
                    text = if (filtered > visible) "Mostrando $visible. Afina la búsqueda." else "Mostrando $visible",
                    style = MaterialTheme.typography.labelSmall,
                    color = GlassTheme.tokens.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun UsersEditorCard(
    state: com.oposicontrol.admin.features.users.presentation.UsersState,
    onIntent: (UsersIntent) -> Unit,
) {
    var manualTickets by rememberSaveable { mutableStateOf("") }

    GlassSurface(modifier = Modifier.fillMaxWidth(), shape = AdminUserPanelShape) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Editar usuario",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            GlassTextField(
                value = state.editorName,
                onValueChange = { onIntent(UsersIntent.UpdateName(it)) },
                placeholder = "Nombre",
                modifier = Modifier.fillMaxWidth(),
            )
            // El campo que el backend llama `email` es en realidad `username`, y
            // el guardado lo descarta. Editable hacía creer que se estaba
            // corrigiendo el correo de alguien, así que pasa a sólo lectura.
            Text(
                text = USER_ACCOUNT_FIELD_LABEL,
                style = MaterialTheme.typography.labelMedium,
                color = GlassTheme.tokens.textSecondary,
            )
            Text(
                text = state.selectedUser?.let { userAccountName(it) }.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = USER_EMAIL_UNAVAILABLE_NOTE,
                style = MaterialTheme.typography.labelSmall,
                color = GlassTheme.tokens.textSecondary,
            )
            if (state.availableRoles.isNotEmpty()) {
                AdminUserRoleSelector(
                    options = state.availableRoles.map { it.id to it.name },
                    selected = state.editorRoleIds,
                    onToggle = { onIntent(UsersIntent.ToggleRole(it)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // Aquí había un interruptor «Usuario activo». El `PATCH` de usuario
            // sólo escribe `nombre_mostrar`, así que moverlo devolvía éxito sin
            // cambiar nada. No existe bloqueo en el sistema.
            if (state.selectedUserId != null) {
                Text("Dar opositickets", style = MaterialTheme.typography.labelMedium, color = GlassTheme.tokens.textSecondary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(5, 10, 25).forEach { amount ->
                        AdminTicketPresetButton(
                            amount = amount,
                            enabled = state.actionState !is UiAsync.Loading,
                            onClick = { onIntent(UsersIntent.GrantTickets(amount)) },
                        )
                    }
                }
                val manualAmount = manualTickets.toIntOrNull()?.takeIf { it > 0 }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassTextField(
                        value = manualTickets,
                        onValueChange = { value -> manualTickets = value.filter(Char::isDigit).take(5) },
                        placeholder = "Cantidad",
                        modifier = Modifier.width(172.dp),
                    )
                    AdminUserActionButton(
                        icon = MobileIcon.Check,
                        label = "Enviar",
                        modifier = Modifier.width(104.dp),
                        enabled = manualAmount != null && state.actionState !is UiAsync.Loading,
                        tint = GlassTheme.tokens.accent,
                        strokeColor = GlassTheme.tokens.accent.copy(alpha = 0.44f),
                        contentColor = Color.White,
                        onClick = {
                            val amount = manualAmount ?: return@AdminUserActionButton
                            onIntent(UsersIntent.GrantTickets(amount))
                            manualTickets = ""
                        },
                    )
                }
            }
            AdminUsersActionRow(
                actionState = state.actionState,
                onSave = { onIntent(UsersIntent.Save) },
                // Eliminar estaba cortado en el cliente con un `error(...)`: el
                // diálogo prometía una baja que nunca llegaba a ejecutarse.
                onDelete = null,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdminUserRoleSelector(
    options: List<Pair<String, String>>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (id, label) ->
            AdminUserRoleChip(
                label = label,
                selected = id in selected,
                onClick = { onToggle(id) },
            )
        }
    }
}

@Composable
private fun AdminUserRoleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = GlassTheme.tokens
    val tint = if (selected) tokens.accent.copy(alpha = 0.14f) else tokens.glassFillStrong.copy(alpha = 0.60f)
    val stroke = if (selected) tokens.accent.copy(alpha = 0.44f) else tokens.glassStroke
    val foreground = if (selected) tokens.accent else tokens.textSecondary

    GlassSurface(
        modifier = Modifier
            .height(36.dp)
            .clickable(onClick = onClick),
        shape = AdminUserControlShape,
        tint = tint,
        strokeColor = stroke,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .height(36.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// Aquí vivía `AdminUserToggleRow`, el interruptor «Activo» de la ficha de usuario
// en móvil. Se retira con su único punto de llamada: el `PATCH` de usuario sólo
// escribe `nombre_mostrar`, así que moverlo devolvía éxito sin cambiar nada, y
// `isActive` no significa acceso sino invitado/registrado.

@Composable
private fun AdminTicketPresetButton(
    amount: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tokens = GlassTheme.tokens
    val foreground = if (enabled) tokens.accent else tokens.textSecondary
    GlassSurface(
        modifier = Modifier
            .width(if (amount == 5) 58.dp else 64.dp)
            .height(44.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = AdminUserControlShape,
        tint = tokens.glassFillStrong.copy(alpha = if (enabled) 0.70f else 0.45f),
        strokeColor = tokens.accent.copy(alpha = if (enabled) 0.28f else 0.12f),
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "+$amount",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = foreground,
            )
        }
    }
}

@Composable
private fun AdminUsersActionRow(
    actionState: UiAsync<Unit>,
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
            AdminUserActionButton(
                icon = MobileIcon.Save,
                label = if (saving) "Guardando..." else "Guardar",
                modifier = Modifier.weight(1f),
                enabled = !saving,
                tint = GlassTheme.tokens.accent,
                strokeColor = GlassTheme.tokens.accent.copy(alpha = 0.44f),
                contentColor = Color.White,
                onClick = onSave,
            )
            if (onDelete != null) {
                AdminUserActionButton(
                    icon = MobileIcon.Remove,
                    label = "Eliminar",
                    modifier = Modifier.width(108.dp),
                    enabled = !saving,
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
                    strokeColor = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
                    contentColor = MaterialTheme.colorScheme.error,
                    onClick = onDelete,
                )
            }
        }
    }
}

@Composable
private fun AdminUserActionButton(
    icon: MobileIcon,
    label: String,
    modifier: Modifier,
    enabled: Boolean,
    tint: Color,
    strokeColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
) {
    val disabledTint = GlassTheme.tokens.glassFillStrong.copy(alpha = 0.55f)
    val disabledContent = GlassTheme.tokens.textSecondary
    GlassSurface(
        modifier = modifier
            .height(48.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = AdminUserActionShape,
        tint = if (enabled) tint else disabledTint,
        strokeColor = if (enabled) strokeColor else GlassTheme.tokens.glassStroke,
        shadowElevation = if (enabled) 8.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LineIcon(icon = icon, modifier = Modifier.size(16.dp), color = if (enabled) contentColor else disabledContent)
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) contentColor else disabledContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
                modifier = Modifier.width(204.dp),
            )
            MobileActionPill(
                icon = MobileIcon.Plus,
                label = "Nuevo",
                modifier = Modifier.width(104.dp),
                onClick = { onIntent(ExamAccessIntent.CreateNew) },
            )
        }

        if (accessState.selectedId != null || accessState.editorUserName.isNotBlank()) {
            AccessEditorCard(state = accessState, onIntent = onIntent)
        }

        when (val accesses = accessState.accesses) {
            is UiAsync.Loading -> LoadingStateCard()
            is UiAsync.Empty -> EmptyStateCard(title = "Sin accesos", message = "No hay accesos registrados.")
            is UiAsync.Error -> EmptyStateCard(title = "Error al cargar", message = accesses.error.message)
            is UiAsync.Success -> {
                val visibleAccesses = accesses.value.take(AdminListPreviewLimit)
                AdminListMeta(total = accesses.value.size, visible = visibleAccesses.size, label = "accesos")
                visibleAccesses.forEach { item ->
                    val selected = accessState.selectedId == item.id
                    GlassSurface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(78.dp)
                            .clickable { onIntent(ExamAccessIntent.Select(item.id)) },
                        shape = AdminUserCardShape,
                        tint = if (selected) GlassTheme.tokens.accent.copy(alpha = 0.10f) else GlassTheme.tokens.glassFill,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(78.dp)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
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
                                    modifier = Modifier.width(200.dp),
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
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            is UiAsync.Idle -> Unit
        }
    }
}

@Composable
private fun AccessEditorCard(
    state: com.oposicontrol.admin.features.examaccess.presentation.ExamAccessState,
    onIntent: (ExamAccessIntent) -> Unit,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth(), shape = AdminUserPanelShape) {
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

        if (ticketsState.selectedUserId != null) {
            TicketsAdjustmentCard(state = ticketsState, onIntent = onIntent)
            TicketsHistorySection(state = ticketsState)
        }

        when (val wallets = ticketsState.wallets) {
            is UiAsync.Loading -> LoadingStateCard()
            is UiAsync.Empty -> EmptyStateCard(title = "Sin carteras", message = "No hay carteras disponibles.")
            is UiAsync.Error -> EmptyStateCard(title = "Error al cargar", message = wallets.error.message)
            is UiAsync.Success -> {
                val visibleWallets = wallets.value.take(AdminListPreviewLimit)
                AdminListMeta(total = wallets.value.size, visible = visibleWallets.size, label = "carteras")
                visibleWallets.forEach { wallet ->
                    val selected = ticketsState.selectedUserId == wallet.userId
                    GlassSurface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(78.dp)
                            .clickable { onIntent(TicketsIntent.SelectWallet(wallet.userId)) },
                        shape = AdminUserCardShape,
                        tint = if (selected) GlassTheme.tokens.accent.copy(alpha = 0.10f) else GlassTheme.tokens.glassFill,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(78.dp)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.width(188.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                            Column(
                                modifier = Modifier.width(96.dp),
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = "${formatOneDecimal(wallet.balance)} tks",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = GlassTheme.tokens.accent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                StatusChip(status = wallet.status)
                            }
                        }
                    }
                }
            }
            is UiAsync.Idle -> Unit
        }
    }
}

@Composable
private fun TicketsAdjustmentCard(
    state: com.oposicontrol.admin.features.tickets.presentation.TicketsState,
    onIntent: (TicketsIntent) -> Unit,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth(), shape = AdminUserPanelShape) {
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
            val visibleMovements = transactions.value.take(AdminDetailPreviewLimit)
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Historial reciente",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                AdminListMeta(total = transactions.value.size, visible = visibleMovements.size, label = "movimientos")
                visibleMovements.forEach { movement ->
                    GlassSurface(modifier = Modifier.fillMaxWidth(), shape = AdminUserCardShape) {
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
                                text = formatOneDecimal(movement.amount, includePlus = true),
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
        GlassSearchBar(
            value = supportState.searchQuery,
            onValueChange = { onIntent(SupportIntent.UpdateSearch(it)) },
            modifier = Modifier.fillMaxWidth(),
        )
        SupportStatusFilter(
            current = supportState.statusFilter,
            onSelected = { onIntent(SupportIntent.UpdateStatusFilter(it)) },
        )

        if (supportState.selectedConversationId != null) {
            SupportReplyCard(state = supportState, onIntent = onIntent)
            SupportMessagesSection(state = supportState)
        }

        when (val conversations = supportState.conversations) {
            is UiAsync.Loading -> LoadingStateCard()
            is UiAsync.Empty -> EmptyStateCard(title = "Sin conversaciones", message = "No hay conversaciones activas.")
            is UiAsync.Error -> EmptyStateCard(title = "Error al cargar", message = conversations.error.message)
            is UiAsync.Success -> {
                val visibleConversations = conversations.value.take(AdminListPreviewLimit)
                AdminListMeta(total = conversations.value.size, visible = visibleConversations.size, label = "conversaciones")
                visibleConversations.forEach { conv ->
                    val selected = supportState.selectedConversationId == conv.id
                    GlassSurface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(88.dp)
                            .clickable { onIntent(SupportIntent.SelectConversation(conv.id)) },
                        shape = AdminUserCardShape,
                        tint = if (selected) GlassTheme.tokens.accent.copy(alpha = 0.10f) else GlassTheme.tokens.glassFill,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(88.dp)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.width(204.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    text = conv.userName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
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
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Column(
                                modifier = Modifier.width(84.dp),
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                StatusChip(status = conv.status)
                                Text(
                                    text = conv.assignedTo.ifBlank { "Sin asignar" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            is UiAsync.Idle -> Unit
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SupportStatusFilter(current: String, onSelected: (String) -> Unit) {
    val options = listOf("all" to "Todos", "open" to "Abiertos", "resolved" to "Resueltos", "closed" to "Cerrados")
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (value, label) ->
            val selected = current == value
            val bg = if (selected) GlassTheme.tokens.accent.copy(alpha = 0.16f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            val fg = if (selected) GlassTheme.tokens.accent else MaterialTheme.colorScheme.onSurfaceVariant
            Text(
                text = label,
                modifier = Modifier
                    .height(36.dp)
                    .background(bg, AdminUserControlShape)
                    .clickable { onSelected(value) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
            val visibleMessages = messages.value.take(AdminDetailPreviewLimit)
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Mensajes recientes",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                AdminListMeta(total = messages.value.size, visible = visibleMessages.size, label = "mensajes")
                visibleMessages.forEach { msg ->
                    val noteBg = if (msg.internal) MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
                    else GlassTheme.tokens.glassFill
                    GlassSurface(modifier = Modifier.fillMaxWidth(), shape = AdminUserCardShape, tint = noteBg) {
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
    GlassSurface(modifier = Modifier.fillMaxWidth(), shape = AdminUserPanelShape) {
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
        GlassSurface(modifier = Modifier.fillMaxWidth(), shape = AdminUserPanelShape) {
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

        GlassSurface(modifier = Modifier.fillMaxWidth(), shape = AdminUserPanelShape) {
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

private fun formatOneDecimal(value: Double, includePlus: Boolean = false): String {
    val scaled = (abs(value) * 10).roundToInt()
    val sign = when {
        value < 0 -> "-"
        includePlus -> "+"
        else -> ""
    }
    return "$sign${scaled / 10}.${scaled % 10}"
}
