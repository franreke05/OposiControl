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
import com.oposicontrol.admin.features.controlv1.di.FORUM_CONFIG
import com.oposicontrol.admin.features.controlv1.di.REFERRAL_CONFIG
import com.oposicontrol.admin.features.controlv1.presentation.AlertsScreen
import com.oposicontrol.admin.features.controlv1.presentation.ExamEconomyScreen
import com.oposicontrol.admin.features.controlv1.presentation.ForumOpsScreen
import com.oposicontrol.admin.features.controlv1.presentation.MarkupEditorScreen
import com.oposicontrol.admin.features.controlv1.presentation.OrdersScreen
import com.oposicontrol.admin.features.controlv1.presentation.ReferralScreen
import com.oposicontrol.admin.features.examaccess.presentation.ExamAccessScreen
import com.oposicontrol.admin.features.moderation.presentation.ModerationScreen
import com.oposicontrol.admin.features.newstypes.presentation.NewsTypesScreen
import com.oposicontrol.admin.features.news.presentation.NewsScreen
import com.oposicontrol.admin.features.opositester.presentation.TesterListScreen
import com.oposicontrol.admin.features.rankings.presentation.RankingsScreen
import com.oposicontrol.admin.features.reports.presentation.ReportsScreen
import com.oposicontrol.admin.features.settings.presentation.SystemStatusScreen
import com.oposicontrol.admin.features.store.presentation.CommercialCatalogIntent
import com.oposicontrol.admin.features.store.presentation.CommercialCatalogScreen
import com.oposicontrol.admin.features.store.presentation.CommercialCatalogViewModel
import com.oposicontrol.admin.features.store.presentation.CommercialPlansScreen
import com.oposicontrol.admin.features.store.presentation.CommercialPlansViewModel
import com.oposicontrol.admin.features.tickets.presentation.TicketsIntent
import com.oposicontrol.admin.features.tickets.presentation.TicketsScreen
import com.oposicontrol.admin.features.tickets.presentation.TicketsViewModel
import com.oposicontrol.admin.features.users.presentation.AccessRequestsIntent
import com.oposicontrol.admin.features.users.presentation.AccessRequestsViewModel
import com.oposicontrol.admin.features.users.presentation.AdminsScreen
import com.oposicontrol.admin.features.users.presentation.UsersIntent
import com.oposicontrol.admin.features.users.presentation.UsersScreen
import com.oposicontrol.admin.features.users.presentation.UsersViewModel
import org.koin.compose.getKoin
import org.koin.compose.koinInject
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import com.oposicontrol.admin.core.ui.components.motionEntrance
import com.oposicontrol.admin.features.store.presentation.MarketCatalogEditor
import com.oposicontrol.admin.features.store.presentation.MarketSection
import com.oposicontrol.admin.features.store.presentation.MarketViewModel

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
 * Estado+Administradores+Auditoría sin inventar una capa nueva, y eso es
 * justo lo que esta fase de navegación no pide.
 *
 * El botón de acción primaria de la cabecera se deja fuera a propósito (mismo
 * trato que Market): con cuatro pantallas independientes detrás de una fila de
 * pestañas, una única acción en la cabecera acertaría para una y sería ruido —
 * o algo peor, se dispararía sobre la pantalla equivocada — para las otras
 * tres. Cada pantalla conserva sus propias acciones dentro de su cuerpo.
 */

/**
 * El cuerpo de una pestaña de hub. Ocupa el mismo hueco que ocupaba la pantalla suelta y entra con el movimiento de
 * contenido cuando cambia [tabId]: las pestañas y la cabecera de la página se quedan quietas, que es lo que hace que ir
 * de Usuarios a OposiTickets se lea como «otra cara de lo mismo» y no como otra página.
 */
@Composable
private fun ColumnScope.HubTabBody(tabId: String, content: @Composable () -> Unit) {
    Box(modifier = Modifier.weight(1f).fillMaxWidth().motionEntrance(key = tabId)) { content() }
}

/**
 * Sistema responde a tres preguntas, en este orden: ¿funciona? (Estado, la pestaña por defecto), ¿quién puede entrar?
 * (Administradores) y ¿qué se ha hecho? (Auditoría).
 *
 * «Preferencias» y «Seguridad» ya no están: la primera era un formulario con un nombre y un correo que no eran los de la
 * sesión y dos interruptores sin efecto; la segunda, un editor de roles que el servidor nunca dejó guardar.
 */
internal enum class SystemHubTab(val id: String, val label: String, val icon: AppIcon) {
    STATUS("status", "Estado", AppIcon.STATUS),

    /** Quién puede entrar, quién lo ha pedido y qué permite cada rol. Absorbe «Solicitudes de acceso», antes en Usuarios. */
    ADMINS("admins", "Administradores", AppIcon.ROLES),
    AUDIT("audit", "Auditoría", AppIcon.AUDIT),

    /** Ventanas de ranking demasiado rápidas y desvíos de contadores del foro: solo lectura. */
    ALERTS("alerts", "Alertas", AppIcon.WARNING),
}

/**
 * Las pestañas del hub de Sistema. «Administradores» avisa de las solicitudes de acceso pendientes para que no haga falta
 * abrirla para saberlo; el recuento solo es mayor que cero cuando el SERVIDOR ha dejado leer la lista a esta sesión.
 */
internal fun systemHubTabs(pendingAccessRequests: Int): List<DesktopTab> = SystemHubTab.entries.map { tab ->
    DesktopTab(
        id = tab.id,
        label = tab.label,
        icon = tab.icon,
        badge = pendingAccessRequests.takeIf { tab == SystemHubTab.ADMINS && it > 0 }?.toString(),
    )
}

/**
 * [onOpenUser] abre la ficha de una persona en Usuarios desde «Administradores» (que solo lista: los roles y la baja se
 * cambian en la ficha).
 *
 * El ViewModel de solicitudes de acceso se pide al abrir el hub y no al pulsar la pestaña: su respuesta (una petición
 * ligera) es la que dice si hay algo pendiente y si esta sesión puede revisarlo.
 */
@Composable
internal fun SystemHubScreen(
    initialTab: SystemHubTab?,
    onOpenUser: (String) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf((initialTab ?: SystemHubTab.STATUS).id) }
    LaunchedEffect(initialTab) { if (initialTab != null) selectedTab = initialTab.id }

    val accessRequestsViewModel: AccessRequestsViewModel = koinInject()
    val accessRequestsState by accessRequestsViewModel.state.collectAsState()
    // Cada vez que se abre el hub se vuelve a preguntar: un permiso retirado deja de verse sin reiniciar la aplicación.
    LaunchedEffect(Unit) { accessRequestsViewModel.dispatch(AccessRequestsIntent.Refresh) }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        DesktopTabs(
            tabs = systemHubTabs(accessRequestsState.pendingCount),
            selectedId = selectedTab,
            onSelect = { selectedTab = it },
        )
        HubTabBody(selectedTab) {
            when (selectedTab) {
                SystemHubTab.ADMINS.id -> AdminsScreen(
                    viewModel = koinInject(),
                    accessRequestsViewModel = accessRequestsViewModel,
                    onOpenUser = onOpenUser,
                )
                SystemHubTab.AUDIT.id -> AuditScreen(viewModel = koinInject())
                SystemHubTab.ALERTS.id -> AlertsScreen(viewModel = koinInject())
                else -> SystemStatusScreen(viewModel = koinInject())
            }
        }
    }
}

internal enum class UsersHubTab(val id: String, val label: String, val icon: AppIcon) {
    USERS("users", "Usuarios", AppIcon.USERS),

    /** Las carteras son de las personas: van justo después de ellas, no en Monetización ni como entrada propia del menú. */
    TICKETS("tickets", "OposiTickets", AppIcon.TICKETS),
    COMPETITION("competition", "Competición", AppIcon.RANKINGS),
    ACCESS("access", "Accesos", AppIcon.ACCESS),

    /** Programa Invita y gana: configuración y lista de invitaciones. */
    REFERRALS("referrals", "Invitaciones", AppIcon.USERS),
}

/**
 * Usuarios y sus subpantallas. OposiTickets es una de ellas: ir de la ficha de una persona a su cartera, y volver, es
 * cambiar de pestaña con esa persona ya abierta, no saltar a otra sección.
 *
 * Los ViewModel de la otra pestaña se piden al pulsar (`getKoin`) y no al abrir el hub: pedirlos antes descargaría todas
 * las carteras cada vez que alguien mira un usuario, y toda la lista de usuarios cada vez que alguien mira una cartera.
 *
 * «Solicitudes de acceso» ya no vive aquí: quién entra en el panel es asunto de Sistema > Administradores.
 */
@Composable
internal fun UsersHubScreen(initialTab: UsersHubTab?) {
    var selectedTab by rememberSaveable { mutableStateOf((initialTab ?: UsersHubTab.USERS).id) }
    LaunchedEffect(initialTab) { if (initialTab != null) selectedTab = initialTab.id }
    val koin = getKoin()

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        DesktopTabs(
            tabs = UsersHubTab.entries.map { DesktopTab(it.id, it.label, it.icon) },
            selectedId = selectedTab,
            onSelect = { selectedTab = it },
        )
        HubTabBody(selectedTab) {
            when (selectedTab) {
                UsersHubTab.TICKETS.id -> TicketsScreen(
                    viewModel = koinInject(),
                    onOpenUser = { userId ->
                        koin.get<UsersViewModel>().dispatch(UsersIntent.SelectUser(userId))
                        selectedTab = UsersHubTab.USERS.id
                    },
                )
                UsersHubTab.COMPETITION.id -> RankingsScreen(viewModel = koinInject())
                UsersHubTab.ACCESS.id -> ExamAccessScreen(viewModel = koinInject())
                UsersHubTab.REFERRALS.id -> ReferralScreen(
                    configViewModel = koinInject(qualifier = REFERRAL_CONFIG),
                    viewModel = koinInject(),
                )
                else -> UsersScreen(
                    viewModel = koinInject(),
                    onOpenTickets = { userId ->
                        koin.get<TicketsViewModel>().dispatch(TicketsIntent.OpenWalletOf(userId))
                        selectedTab = UsersHubTab.TICKETS.id
                    },
                )
            }
        }
    }
}

/**
 * Catálogo es el ÚNICO sitio donde se crea, se edita y se desactiva lo que vende la tienda (decisión del propietario,
 * 2026-10-14). Servicios, promociones y avisos vivían en OposiMarket, que ahora es sólo el escaparate. Los packs de tickets
 * no tienen pestaña: son productos de tipo «Pack de tickets», en Productos.
 */
internal enum class CatalogHubTab(val id: String, val label: String, val icon: AppIcon) {
    PRODUCTS("products", "Productos", AppIcon.CATALOG),
    PLANS("plans", "Planes", AppIcon.PLANS),
    SERVICES("services", "Servicios", AppIcon.MARKET),
    PROMOTIONS("promotions", "Promociones", AppIcon.PLANS),
    POPUPS("popups", "Avisos", AppIcon.EVENTS),

    /** Comunidad obligatoria, precio en OT y días de acceso de cada examen. */
    ECONOMY("economy", "Comunidad y precios", AppIcon.TICKETS),

    /** Pedidos de pago: en revisión y casos de reembolso. */
    ORDERS("orders", "Pedidos", AppIcon.MARKET),
    ;

    /** El editor de OposiMarket que monta esta pestaña; `null` en las dos que tienen pantalla propia. */
    val marketSection: MarketSection?
        get() = when (this) {
            SERVICES -> MarketSection.SERVICES
            PROMOTIONS -> MarketSection.PROMOTIONS
            POPUPS -> MarketSection.POPUPS
            PRODUCTS, PLANS, ECONOMY, ORDERS -> null
        }
}

/** La pestaña de Catálogo donde se edita lo que enseña una sección del escaparate; `null` si no se edita en Catálogo. */
internal fun catalogTabForMarketSection(section: MarketSection): CatalogHubTab? = when (section) {
    MarketSection.PRODUCTS -> CatalogHubTab.PRODUCTS
    MarketSection.SERVICES -> CatalogHubTab.SERVICES
    MarketSection.PROMOTIONS -> CatalogHubTab.PROMOTIONS
    MarketSection.POPUPS -> CatalogHubTab.POPUPS
    MarketSection.EXAMS -> null
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
internal fun CatalogHubScreen(
    initialTab: CatalogHubTab?,
    /** Abre la ficha de un usuario desde un uso de promoción. `null` = no se ofrece el enlace. */
    onOpenUser: ((String) -> Unit)? = null,
) {
    var selectedTab by rememberSaveable { mutableStateOf((initialTab ?: CatalogHubTab.PRODUCTS).id) }
    LaunchedEffect(initialTab) { if (initialTab != null) selectedTab = initialTab.id }

    val productsViewModel: CommercialCatalogViewModel = koinInject()
    val plansViewModel: CommercialPlansViewModel = koinInject()
    // Un solo ViewModel para las tres pestañas que vienen de OposiMarket: cambiar entre ellas no tira un borrador a medias.
    val marketViewModel: MarketViewModel = koinInject()
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
        HubTabBody(selectedTab) {
            when (selectedTab) {
                CatalogHubTab.PLANS.id -> CommercialPlansScreen(viewModel = plansViewModel)
                CatalogHubTab.ECONOMY.id -> ExamEconomyScreen(viewModel = koinInject())
                CatalogHubTab.ORDERS.id -> OrdersScreen(viewModel = koinInject())
                CatalogHubTab.SERVICES.id, CatalogHubTab.PROMOTIONS.id, CatalogHubTab.POPUPS.id -> {
                    val section = CatalogHubTab.entries.first { it.id == selectedTab }.marketSection
                    if (section != null) MarketCatalogEditor(viewModel = marketViewModel, section = section, onOpenUser = onOpenUser)
                }
                else -> CommercialCatalogScreen(viewModel = productsViewModel)
            }
        }
    }
}

internal enum class ModerationHubTab(val id: String, val label: String, val icon: AppIcon) {
    MODERATION("moderation", "Moderación", AppIcon.MODERATION),
    REPORTS("reports", "Reportes", AppIcon.REPORTS),
    OPOSITESTER("opositester", "OposiTester", AppIcon.TESTER),

    /** OposiRed: cola de moderación, canales, bloqueos y límites. */
    FORUM("forum", "Foro", AppIcon.FORUM),
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
        HubTabBody(selectedTab) {
            when (selectedTab) {
                ModerationHubTab.REPORTS.id -> ReportsScreen(viewModel = koinInject(), onOpenReport = onOpenReport)
                ModerationHubTab.OPOSITESTER.id -> TesterListScreen(viewModel = koinInject(), onOpenReport = onOpenTester)
                ModerationHubTab.FORUM.id -> ForumOpsScreen(
                    queueViewModel = koinInject(),
                    channelsViewModel = koinInject(),
                    blocksViewModel = koinInject(),
                    configViewModel = koinInject(qualifier = FORUM_CONFIG),
                )
                else -> ModerationScreen(viewModel = koinInject())
            }
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
            else -> NewsScreen(viewModel = koinInject())
        }
    }
}

internal enum class ImportHubTab(val id: String, val label: String, val icon: AppIcon) {
    IMPORT("import", "Importación", AppIcon.IMPORT),

    /** Escribe o pega un razonado con marcado y ve exactamente cómo lo verá la app, con los problemas señalados. */
    MARKUP("markup", "Marcado de razonados", AppIcon.EDIT),
}

/**
 * Importación y su editor de marcado. La importación conserva su pantalla tal cual; el editor de marcado es la
 * herramienta para preparar los razonados antes de subirlos: valida la gramática y enseña la vista previa.
 */
@Composable
internal fun ImportHubScreen(importViewModel: com.oposicontrol.admin.features.imports.presentation.ImportViewModel) {
    var selectedTab by rememberSaveable { mutableStateOf(ImportHubTab.IMPORT.id) }
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        DesktopTabs(
            tabs = ImportHubTab.entries.map { DesktopTab(it.id, it.label, it.icon) },
            selectedId = selectedTab,
            onSelect = { selectedTab = it },
        )
        HubTabBody(selectedTab) {
            when (selectedTab) {
                ImportHubTab.MARKUP.id -> MarkupEditorScreen()
                else -> com.oposicontrol.admin.features.imports.presentation.ImportScreen(importViewModel)
            }
        }
    }
}
