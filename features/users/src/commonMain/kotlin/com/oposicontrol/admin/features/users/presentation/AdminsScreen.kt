package com.oposicontrol.admin.features.users.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.DesktopIconButton
import com.oposicontrol.admin.core.ui.components.DesktopTab
import com.oposicontrol.admin.core.ui.components.DesktopTabs
import com.oposicontrol.admin.core.ui.components.DesktopTabsVariant
import com.oposicontrol.admin.core.ui.components.ErrorStateCard
import com.oposicontrol.admin.core.ui.components.LoadingStateCard
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.components.StatusTone
import com.oposicontrol.admin.core.ui.components.WorkspaceCatalogRow
import com.oposicontrol.admin.core.ui.components.WorkspaceEqualRow
import com.oposicontrol.admin.core.ui.components.WorkspaceFact
import com.oposicontrol.admin.core.ui.components.WorkspaceMetaLine
import com.oposicontrol.admin.core.ui.components.WorkspaceInfoLines
import com.oposicontrol.admin.core.ui.components.WorkspaceLayout
import com.oposicontrol.admin.core.ui.components.WorkspaceNotice
import com.oposicontrol.admin.core.ui.components.WorkspaceNoticeTone
import com.oposicontrol.admin.core.ui.components.WorkspacePanel
import com.oposicontrol.admin.core.ui.components.WorkspacePill
import com.oposicontrol.admin.core.ui.components.WorkspaceRail
import com.oposicontrol.admin.core.ui.components.WorkspaceSection
import com.oposicontrol.admin.core.ui.components.humanMessage
import com.oposicontrol.admin.core.ui.components.isRetryable
import com.oposicontrol.admin.core.ui.layout.DesktopWindow
import com.oposicontrol.admin.core.ui.layout.supportsMasterDetail
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.AccountStatus

internal const val ADMINS_ACCOUNTS_TAG: String = "admins-accounts"
internal const val ADMINS_ROLE_TABLE_TAG: String = "admins-role-table"
internal const val ADMINS_REVIEW_DENIED_TAG: String = "admins-review-denied"

/**
 * Los tres bloques de «Administradores», cada uno respondiendo a una pregunta: quién puede entrar hoy, quién lo ha pedido
 * y qué permite cada rol.
 *
 * Van como SUB-PESTAÑAS y no como secciones apiladas porque «Solicitudes de acceso» ya es un workspace entero (catálogo a
 * la izquierda, ficha a la derecha) que necesita todo el ancho y todo el alto: metido como una sección más quedaría una
 * cola de decisiones con scroll dentro de otra página con scroll. Mismo criterio que la ficha de usuario
 * (`DesktopTabs`, variante de workspace): cada vista, una pregunta.
 */
enum class AdminsSection(val id: String, val label: String, val icon: AppIcon) {
    WHO("who", "Quién puede entrar", AppIcon.USERS),
    REQUESTS("requests", "Solicitudes de acceso", AppIcon.ROLES),
    ROLES("roles", "Qué permite cada rol", AppIcon.ACCESS),
}

/** Las sub-pestañas con su aviso: las solicitudes pendientes se ven sin tener que abrir esa pestaña. */
internal fun adminsSectionTabs(pendingRequests: Int): List<DesktopTab> = AdminsSection.entries.map { section ->
    DesktopTab(
        id = section.id,
        label = section.label,
        icon = section.icon,
        badge = pendingRequests.takeIf { section == AdminsSection.REQUESTS && it > 0 }?.toString(),
    )
}

/**
 * Sistema > Administradores.
 *
 * [accessRequestsViewModel] es la MISMA instancia que usa el hub para el aviso de pendientes: quien decide si esta sesión
 * puede revisar solicitudes es el servidor, a través de ella. Esta pantalla no edita nada: [onOpenUser] lleva a la ficha
 * de la persona en Usuarios, que es donde se cambian roles y se da de baja.
 */
@Composable
fun AdminsScreen(
    viewModel: AdminsViewModel,
    accessRequestsViewModel: AccessRequestsViewModel,
    onOpenUser: (String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val accessState by accessRequestsViewModel.state.collectAsState()
    var section by rememberSaveable { mutableStateOf(AdminsSection.WHO) }
    AdminsContent(
        state = state,
        accessState = accessState,
        section = section,
        onSectionChange = {
            // Al volver de Solicitudes puede haber un administrador nuevo (o uno menos): la lista se relee.
            if (it == AdminsSection.WHO && section != AdminsSection.WHO) viewModel.dispatch(AdminsIntent.Refresh)
            section = it
        },
        onIntent = viewModel::dispatch,
        onAccessIntent = accessRequestsViewModel::dispatch,
        onOpenUser = onOpenUser,
    )
}

/** La pantalla, por ESTADO: se puede renderizar contra estados fabricados, sin Koin ni red. */
@Composable
internal fun AdminsContent(
    state: AdminsState,
    accessState: AccessRequestsState,
    section: AdminsSection,
    onSectionChange: (AdminsSection) -> Unit,
    onIntent: (AdminsIntent) -> Unit,
    onAccessIntent: (AccessRequestsIntent) -> Unit,
    onOpenUser: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        DesktopTabs(
            tabs = adminsSectionTabs(accessState.pendingCount),
            selectedId = section.id,
            onSelect = { id -> AdminsSection.entries.firstOrNull { it.id == id }?.let(onSectionChange) },
            variant = DesktopTabsVariant.WORKSPACE,
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (section) {
                AdminsSection.WHO -> WhoCanEnterPane(state, onIntent, onOpenUser)
                AdminsSection.REQUESTS -> AccessReviewPane(accessState, onAccessIntent)
                AdminsSection.ROLES -> RolePermissionsPane()
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Quién puede entrar
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun WhoCanEnterPane(
    state: AdminsState,
    onIntent: (AdminsIntent) -> Unit,
    onOpenUser: (String) -> Unit,
) {
    // Apilado (ventana estrecha) desplaza la página entera: las columnas no llevan su propio scroll.
    val stacked = !DesktopWindow.breakpoint.supportsMasterDetail
    WorkspaceLayout(
        catalog = { paneModifier ->
            WorkspaceRail(modifier = paneModifier, scrolls = !stacked) { AdminsSummarySection(state) }
        },
        editor = { paneModifier, _, fillsHeight ->
            WorkspaceRail(modifier = paneModifier.testTag(ADMINS_ACCOUNTS_TAG), scrolls = fillsHeight) {
                AdminAccountsSection(state, onIntent, onOpenUser)
            }
        },
    )
}

@Composable
private fun AdminsSummarySection(state: AdminsState) {
    WorkspaceSection(icon = AppIcon.INFO, role = SemanticRole.INFO, title = "Resumen") {
        when (state.accounts) {
            // Una línea por recuento y no la rejilla de dos columnas: en la columna del resumen «Superadministradores»
            // no cabe en media anchura y se partía por la mitad de la palabra.
            is UiAsync.Success -> WorkspacePanel(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                adminSummaryFacts(state.loadedAccounts).forEach { fact -> WorkspaceMetaLine(label = fact.label, value = fact.value) }
            }
            UiAsync.Empty -> WorkspaceInfoLines(listOf("Ninguna cuenta tiene hoy un rol que abra el panel."))
            is UiAsync.Error -> WorkspaceInfoLines(listOf("Los recuentos se verán cuando se pueda leer la lista."))
            UiAsync.Loading, UiAsync.Idle -> WorkspaceInfoLines(listOf("Leyendo la lista…"))
        }
        WorkspaceInfoLines(ADMINS_GUIDANCE)
    }
}

internal val ADMINS_GUIDANCE: List<String> = listOf(
    "Entra en el panel quien tiene el rol de administrador o de superadministrador y la cuenta activa.",
    "Esta lista solo se consulta. Para cambiar un rol o dar de baja una cuenta, abre su ficha en Usuarios.",
    "Quien pide entrar aparece en «Solicitudes de acceso».",
)

/** Los cuatro recuentos del resumen. Una cuenta sin estado conocido cuenta como «sin la cuenta activa», nunca como activa. */
internal fun adminSummaryFacts(accounts: List<AdminAccount>): List<WorkspaceFact> {
    val active = accounts.count(::adminAccountCanEnter)
    return listOf(
        WorkspaceFact("Superadministradores", accounts.count { it.level == AdminLevel.SUPERADMIN }.toString()),
        WorkspaceFact("Administradores", accounts.count { it.level == AdminLevel.ADMIN }.toString()),
        WorkspaceFact("Con la cuenta activa", active.toString()),
        WorkspaceFact("Sin la cuenta activa", (accounts.size - active).toString()),
    )
}

@Composable
private fun AdminAccountsSection(
    state: AdminsState,
    onIntent: (AdminsIntent) -> Unit,
    onOpenUser: (String) -> Unit,
) {
    WorkspaceSection(
        icon = AppIcon.USERS,
        role = SemanticRole.INFO,
        title = "Quién puede entrar",
        subtitle = "Cuentas con rol de administrador o de superadministrador. Pulsa una para abrir su ficha en Usuarios.",
        action = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs), verticalAlignment = Alignment.CenterVertically) {
                if (state.loadedAccounts.isNotEmpty()) WorkspacePill(text = adminAccountsCountLabel(state.loadedAccounts.size))
                DesktopIconButton(
                    label = "Actualizar la lista de administradores",
                    size = Sizing.iconButtonSmall,
                    enabled = !state.refreshing,
                    onClick = { onIntent(AdminsIntent.Refresh) },
                ) { AppIconImage(AppIcon.REFRESH, size = 14.dp) }
            }
        },
    ) {
        if (state.refreshFailed) {
            WorkspaceNotice(
                tone = WorkspaceNoticeTone.WARNING,
                title = "No se ha podido actualizar la lista",
                detail = "Se muestra la última lectura. Puede que falte un cambio reciente.",
            )
        }
        AsyncStatePane(
            state = state.accounts,
            emptyTitle = "Sin administradores",
            emptyMessage = "Ninguna cuenta tiene el rol de administrador ni el de superadministrador.",
            emptyIcon = AppIcon.ROLES,
            loadingRows = 5,
            onRetry = { onIntent(AdminsIntent.Refresh) },
            // La lista sigue en pantalla al releerla: no parpadea ni vuelve arriba.
            stableContent = true,
        ) { accounts ->
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                accounts.forEach { account ->
                    key(account.userId) { AdminAccountRow(account, onOpenUser) }
                }
            }
        }
    }
}

@Composable
private fun AdminAccountRow(account: AdminAccount, onOpenUser: (String) -> Unit) {
    WorkspaceCatalogRow(
        title = account.name,
        // Nunca «seleccionada»: la fila no abre nada aquí, lleva a la ficha de la persona en Usuarios.
        selected = false,
        onClick = { onOpenUser(account.userId) },
        icon = AppIcon.USERS,
        description = "$USER_ACCOUNT_FIELD_LABEL: ${account.accountName}",
        meta = listOfNotNull(
            "Conserva el rol, pero no entra mientras la cuenta no esté activa.".takeIf {
                account.accountStatus == AccountStatus.BLOCKED || account.accountStatus == AccountStatus.DELETION_PENDING
            },
        ),
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                WorkspacePill(text = account.level.label, icon = AppIcon.ROLES)
                StatusChip(
                    status = adminAccountStatusLabel(account.accountStatus),
                    tone = adminAccountStatusTone(account.accountStatus),
                )
            }
        },
        changeKey = account.level to account.accountStatus,
    )
}

// ---------------------------------------------------------------------------------------------------------------------
// Solicitudes de acceso
// ---------------------------------------------------------------------------------------------------------------------

/**
 * La pantalla de solicitudes TAL CUAL ([AccessRequestsContent]) cuando el servidor ha dicho que esta sesión puede
 * revisarlas. Si ha dicho que no, se explica con una frase en vez de dejar la pestaña vacía; y mientras no haya dicho
 * nada no se enseña ni lo uno ni lo otro (un fallo de red no es un «no tienes permiso»).
 */
@Composable
private fun AccessReviewPane(accessState: AccessRequestsState, onAccessIntent: (AccessRequestsIntent) -> Unit) {
    when (accessState.availability) {
        AccessReviewAvailability.ALLOWED -> AccessRequestsContent(state = accessState, onIntent = onAccessIntent)
        AccessReviewAvailability.DENIED -> AccessReviewSection {
            WorkspaceNotice(
                tone = WorkspaceNoticeTone.INFO,
                title = ACCESS_REVIEW_DENIED_MESSAGE,
                detail = "Aprobar o rechazar a quien pide entrar en el panel exige ese rol. Lo comprueba el servidor en cada consulta.",
                modifier = Modifier.testTag(ADMINS_REVIEW_DENIED_TAG),
            )
        }
        AccessReviewAvailability.UNKNOWN -> AccessReviewSection {
            when (val requests = accessState.requests) {
                is UiAsync.Error -> ErrorStateCard(
                    title = "No se ha podido comprobar si puedes revisar solicitudes",
                    message = requests.error.humanMessage(),
                    technicalDetail = requests.error.message.takeIf { it.isNotBlank() && it != requests.error.humanMessage() },
                    onRetry = { onAccessIntent(AccessRequestsIntent.Refresh) }.takeIf { requests.error.isRetryable() },
                )
                // `Idle` aquí no es un reposo: solo dura hasta que arranca la primera consulta (la lanza el hub al abrirse).
                UiAsync.Idle, UiAsync.Loading, UiAsync.Empty, is UiAsync.Success -> LoadingStateCard(rows = 5)
            }
        }
    }
}

@Composable
private fun AccessReviewSection(content: @Composable () -> Unit) {
    WorkspaceRail(modifier = Modifier.fillMaxSize()) {
        WorkspaceSection(icon = AppIcon.ROLES, role = SemanticRole.WARNING, title = "Solicitudes de acceso") { content() }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Qué permite cada rol
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun RolePermissionsPane() {
    // Dos tarjetas por fila mientras quepan; en ventana estrecha, una debajo de otra.
    val columns = if (DesktopWindow.breakpoint.supportsMasterDetail) 2 else 1
    WorkspaceRail(modifier = Modifier.fillMaxSize().testTag(ADMINS_ROLE_TABLE_TAG)) {
        WorkspaceSection(
            icon = AppIcon.ACCESS,
            role = SemanticRole.INFO,
            title = "Qué permite cada rol",
            subtitle = "Lo que el servidor aplica hoy. Es una tabla de consulta: los roles no se crean ni se editan desde el panel.",
        ) {
            PANEL_ROLE_PERMISSIONS.chunked(columns).forEach { rowRoles ->
                WorkspaceEqualRow(columns = columns, gap = Spacing.sm) {
                    rowRoles.forEach { RolePermissionsCard(it) }
                }
            }
            WorkspaceNotice(tone = WorkspaceNoticeTone.INFO, title = ROLE_TABLE_ACTIVE_ACCOUNT_NOTE)
        }
    }
}

@Composable
private fun RolePermissionsCard(role: RolePermissions) {
    val tokens = GlassTheme.tokens
    WorkspacePanel(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = role.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = tokens.textPrimary,
                modifier = Modifier.weight(1f),
            )
            StatusChip(
                status = if (role.entersPanel) "Entra en el panel" else "No entra en el panel",
                tone = if (role.entersPanel) StatusTone.POSITIVE else StatusTone.NEUTRAL,
            )
        }
        RolePermissionLines(label = "Puede", lines = role.can)
        RolePermissionLines(label = "No puede", lines = role.cannot)
    }
}

@Composable
private fun RolePermissionLines(label: String, lines: List<String>) {
    if (lines.isEmpty()) return
    val tokens = GlassTheme.tokens
    Text(text = label, style = MaterialTheme.typography.labelMedium, color = tokens.textSecondary)
    lines.forEach { line ->
        Text(text = "•  $line", style = MaterialTheme.typography.bodySmall, color = tokens.textPrimary)
    }
}
