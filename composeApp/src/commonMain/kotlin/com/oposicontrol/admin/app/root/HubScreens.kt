package com.oposicontrol.admin.app.root

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.DesktopTab
import com.oposicontrol.admin.core.ui.components.DesktopTabs
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.features.audit.presentation.AuditScreen
import com.oposicontrol.admin.features.examaccess.presentation.ExamAccessScreen
import com.oposicontrol.admin.features.moderation.presentation.ModerationScreen
import com.oposicontrol.admin.features.newstypes.presentation.NewsTypesScreen
import com.oposicontrol.admin.features.news.presentation.NewsScreen
import com.oposicontrol.admin.features.opositester.presentation.TesterListScreen
import com.oposicontrol.admin.features.rankings.presentation.RankingsScreen
import com.oposicontrol.admin.features.reports.presentation.ReportsScreen
import com.oposicontrol.admin.features.roles.presentation.RolesScreen
import com.oposicontrol.admin.features.settings.presentation.SettingsScreen
import com.oposicontrol.admin.features.settings.presentation.SystemStatusScreen
import com.oposicontrol.admin.features.store.presentation.CommercialCatalogIntent
import com.oposicontrol.admin.features.store.presentation.CommercialCatalogScreen
import com.oposicontrol.admin.features.store.presentation.CommercialCatalogViewModel
import com.oposicontrol.admin.features.store.presentation.CommercialPlansScreen
import com.oposicontrol.admin.features.store.presentation.CommercialPlansViewModel
import com.oposicontrol.admin.features.users.presentation.UsersScreen
import org.koin.compose.koinInject

/**
 * Hubs delgados de la consolidación de navegación (2026-09-04).
 *
 * Cada uno es un selector de pestañas sobre pantallas que YA EXISTÍAN y NO se
 * tocan por dentro — cada rama del `when` compone el screen tal cual estaba,
 * con su propio ViewModel inyectado como siempre. Lo único nuevo es la fila de
 * pestañas: mismo patrón que ya usa `MarketScreen`/`ProductAnalyticsScreen`
 * (una pantalla, varias entradas de menú antiguas apuntando a su sección), sólo
 * que aquí cada pestaña es una pantalla entera en vez de una sección de un
 * único ViewModel — por eso el estado de pestaña es local (`rememberSaveable`),
 * no una intención de un ViewModel compartido: no hay un ViewModel que unifique
 * Roles+Configuración+Estado+Auditoría sin inventar una capa nueva, y eso es
 * justo lo que esta fase de navegación no pide.
 *
 * El botón de acción primaria de la cabecera se deja fuera a propósito (mismo
 * trato que Market): con cuatro pantallas independientes detrás de una fila de
 * pestañas, una única acción en la cabecera acertaría para una y sería ruido —
 * o algo peor, se dispararía sobre la pantalla equivocada — para las otras
 * tres. Cada pantalla conserva sus propias acciones dentro de su cuerpo.
 */

internal enum class SystemHubTab(val id: String, val label: String, val icon: AppIcon) {
    PREFERENCES("preferences", "Preferencias", AppIcon.SETTINGS),
    SECURITY("security", "Seguridad", AppIcon.ROLES),
    STATUS("status", "Estado", AppIcon.STATUS),
    AUDIT("audit", "Auditoría", AppIcon.AUDIT),
}

@Composable
internal fun SystemHubScreen(
    initialTab: SystemHubTab?,
    onSystemStatusClick: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf((initialTab ?: SystemHubTab.PREFERENCES).id) }
    LaunchedEffect(initialTab) { if (initialTab != null) selectedTab = initialTab.id }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        DesktopTabs(
            tabs = SystemHubTab.entries.map { DesktopTab(it.id, it.label, it.icon) },
            selectedId = selectedTab,
            onSelect = { selectedTab = it },
        )
        when (selectedTab) {
            SystemHubTab.SECURITY.id -> RolesScreen(viewModel = koinInject())
            SystemHubTab.STATUS.id -> SystemStatusScreen(viewModel = koinInject())
            SystemHubTab.AUDIT.id -> AuditScreen(viewModel = koinInject())
            else -> SettingsScreen(viewModel = koinInject(), onSystemStatusClick = onSystemStatusClick)
        }
    }
}

internal enum class UsersHubTab(val id: String, val label: String, val icon: AppIcon) {
    USERS("users", "Usuarios", AppIcon.USERS),
    COMPETITION("competition", "Competición", AppIcon.RANKINGS),
    ACCESS("access", "Accesos", AppIcon.ACCESS),
}

@Composable
internal fun UsersHubScreen(
    initialTab: UsersHubTab?,
    /** Abre la cartera de un usuario en OposiTickets. `null` = la ficha no ofrece el enlace. */
    onOpenTickets: ((String) -> Unit)? = null,
) {
    var selectedTab by rememberSaveable { mutableStateOf((initialTab ?: UsersHubTab.USERS).id) }
    LaunchedEffect(initialTab) { if (initialTab != null) selectedTab = initialTab.id }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        DesktopTabs(
            tabs = UsersHubTab.entries.map { DesktopTab(it.id, it.label, it.icon) },
            selectedId = selectedTab,
            onSelect = { selectedTab = it },
        )
        when (selectedTab) {
            UsersHubTab.COMPETITION.id -> RankingsScreen(viewModel = koinInject())
            UsersHubTab.ACCESS.id -> ExamAccessScreen(viewModel = koinInject())
            else -> UsersScreen(viewModel = koinInject(), onOpenTickets = onOpenTickets)
        }
    }
}

internal enum class CatalogHubTab(val id: String, val label: String, val icon: AppIcon) {
    PRODUCTS("products", "Productos", AppIcon.CATALOG),
    PLANS("plans", "Planes", AppIcon.PLANS),
}

/**
 * Productos y Planes comparten catálogo: una suscripción apunta a un plan. Los dos ViewModel viven en el hub —no en cada
 * pestaña— para que cambiar de pestaña no tire un borrador a medias, y los planes guardados llegan al selector de
 * Productos sin volver a pedirlos al servidor.
 *
 * La pestaña Planes era la tienda heredada (`tienda/...`), que escribe en tablas que producción no tiene; ahora es el
 * catálogo canónico de planes y beneficios.
 */
@Composable
internal fun CatalogHubScreen(initialTab: CatalogHubTab?) {
    var selectedTab by rememberSaveable { mutableStateOf((initialTab ?: CatalogHubTab.PRODUCTS).id) }
    LaunchedEffect(initialTab) { if (initialTab != null) selectedTab = initialTab.id }

    val productsViewModel: CommercialCatalogViewModel = koinInject()
    val plansViewModel: CommercialPlansViewModel = koinInject()
    val plansState by plansViewModel.state.collectAsState()
    val plans = plansState.plans
    LaunchedEffect(plans) {
        if (plans is UiAsync.Success) productsViewModel.dispatch(CommercialCatalogIntent.SyncPlans(plans.value))
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        DesktopTabs(
            tabs = CatalogHubTab.entries.map { DesktopTab(it.id, it.label, it.icon) },
            selectedId = selectedTab,
            onSelect = { selectedTab = it },
        )
        when (selectedTab) {
            CatalogHubTab.PLANS.id -> CommercialPlansScreen(viewModel = plansViewModel)
            else -> CommercialCatalogScreen(viewModel = productsViewModel)
        }
    }
}

internal enum class ModerationHubTab(val id: String, val label: String, val icon: AppIcon) {
    MODERATION("moderation", "Moderación", AppIcon.MODERATION),
    REPORTS("reports", "Reportes", AppIcon.REPORTS),
    OPOSITESTER("opositester", "OposiTester", AppIcon.TESTER),
}

/**
 * Hub de colas de Comunidad: Moderación absorbe Reportes y OposiTester como
 * pestañas (§11). `onOpenReport`/`onOpenTester` navegan de verdad (empujan
 * [AppRoute.Reports.Detail]/[AppRoute.OposiTester.Detail]) — el detalle sigue
 * siendo pantalla completa, no una pestaña más de este hub, igual que el
 * detalle de un usuario no vive dentro del hub de Usuarios.
 */
@Composable
internal fun ModerationHubScreen(
    initialTab: ModerationHubTab?,
    onOpenReport: (String) -> Unit,
    onOpenTester: (String) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf((initialTab ?: ModerationHubTab.MODERATION).id) }
    LaunchedEffect(initialTab) { if (initialTab != null) selectedTab = initialTab.id }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        DesktopTabs(
            tabs = ModerationHubTab.entries.map { DesktopTab(it.id, it.label, it.icon) },
            selectedId = selectedTab,
            onSelect = { selectedTab = it },
        )
        when (selectedTab) {
            ModerationHubTab.REPORTS.id -> ReportsScreen(viewModel = koinInject(), onOpenReport = onOpenReport)
            ModerationHubTab.OPOSITESTER.id -> TesterListScreen(viewModel = koinInject(), onOpenReport = onOpenTester)
            else -> ModerationScreen(viewModel = koinInject())
        }
    }
}

internal enum class NewsHubTab(val id: String, val label: String, val icon: AppIcon) {
    NEWS("news", "Noticias", AppIcon.NEWS),
    TYPES("types", "Tipos", AppIcon.NEWS_TYPES),
}

@Composable
internal fun NewsHubScreen(initialTab: NewsHubTab?) {
    var selectedTab by rememberSaveable { mutableStateOf((initialTab ?: NewsHubTab.NEWS).id) }
    LaunchedEffect(initialTab) { if (initialTab != null) selectedTab = initialTab.id }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        DesktopTabs(
            tabs = NewsHubTab.entries.map { DesktopTab(it.id, it.label, it.icon) },
            selectedId = selectedTab,
            onSelect = { selectedTab = it },
        )
        when (selectedTab) {
            NewsHubTab.TYPES.id -> NewsTypesScreen(viewModel = koinInject())
            // Assets no tiene pantalla propia todavía — ya abría Noticias antes
            // de esta consolidación (misma AdminSection.NEWS legacy), así que
            // aterrizar aquí en la pestaña Noticias no es un cambio de
            // comportamiento, es preservar el que ya había.
            else -> NewsScreen(viewModel = koinInject())
        }
    }
}
