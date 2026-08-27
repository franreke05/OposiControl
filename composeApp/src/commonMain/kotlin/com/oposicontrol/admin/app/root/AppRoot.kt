package com.oposicontrol.admin.app.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.Modifier
import com.oposicontrol.admin.app.navigation.AppRoute
import com.oposicontrol.admin.app.navigation.AppRouteMenuGroup
import com.oposicontrol.admin.app.navigation.AppRouteMenuItem
import com.oposicontrol.admin.app.navigation.AdminSection
import com.oposicontrol.admin.app.navigation.AppNavigator
import com.oposicontrol.admin.app.navigation.appNavigationGroups
import com.oposicontrol.admin.app.navigation.metadata
import com.oposicontrol.admin.app.navigation.mobilePrimaryNavigation
import com.oposicontrol.admin.app.navigation.navigationId
import com.oposicontrol.admin.app.navigation.resolveGuardRoute
import com.oposicontrol.admin.app.navigation.SessionState
import com.oposicontrol.admin.app.navigation.toLegacySection
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.ui.components.AppShell
import com.oposicontrol.admin.core.ui.components.ShellNavigationGroup
import com.oposicontrol.admin.core.ui.components.ShellNavigationItem
import com.oposicontrol.admin.core.ui.layout.AppBreakpoint
import com.oposicontrol.admin.core.ui.layout.appBreakpointFor
import com.oposicontrol.admin.features.audit.presentation.AuditIntent
import com.oposicontrol.admin.features.audit.presentation.AuditScreen
import com.oposicontrol.admin.features.audit.presentation.AuditViewModel
import com.oposicontrol.admin.features.auth.presentation.AuthScreen
import com.oposicontrol.admin.features.auth.presentation.AuthViewModel
import com.oposicontrol.admin.features.dashboard.presentation.DashboardIntent
import com.oposicontrol.admin.features.dashboard.presentation.DashboardQuickAction
import com.oposicontrol.admin.features.dashboard.presentation.DashboardScreen
import com.oposicontrol.admin.features.dashboard.presentation.DashboardViewModel
import com.oposicontrol.admin.features.dashboard.presentation.MobileDashboardScreen
import com.oposicontrol.admin.features.examaccess.presentation.ExamAccessIntent
import com.oposicontrol.admin.features.examaccess.presentation.ExamAccessScreen
import com.oposicontrol.admin.features.examaccess.presentation.ExamAccessViewModel
import com.oposicontrol.admin.features.exams.presentation.ExamsIntent
import com.oposicontrol.admin.features.exams.presentation.ExamsScreen
import com.oposicontrol.admin.features.exams.presentation.ExamsViewModel
import com.oposicontrol.admin.features.exams.presentation.QuestionsResourcesScreen
import com.oposicontrol.admin.features.exams.presentation.MobileContentView
import com.oposicontrol.admin.features.forum.presentation.ForumIntent
import com.oposicontrol.admin.features.forum.presentation.ForumScreen
import com.oposicontrol.admin.features.forum.presentation.ForumViewModel
import com.oposicontrol.admin.features.forum.presentation.ThreadDetailScreen
import com.oposicontrol.admin.features.opositester.presentation.TesterListScreen
import com.oposicontrol.admin.features.moderation.presentation.ModerationIntent
import com.oposicontrol.admin.features.moderation.presentation.ModerationScreen
import com.oposicontrol.admin.features.moderation.presentation.ModerationViewModel
import com.oposicontrol.admin.features.news.presentation.NewsIntent
import com.oposicontrol.admin.features.news.presentation.NewsScreen
import com.oposicontrol.admin.features.news.presentation.NewsViewModel
import com.oposicontrol.admin.features.newstypes.presentation.NewsTypesIntent
import com.oposicontrol.admin.features.reports.presentation.ReportDetailScreen
import com.oposicontrol.admin.features.reports.presentation.ReportsIntent
import com.oposicontrol.admin.features.reports.presentation.ReportsScreen
import com.oposicontrol.admin.features.reports.presentation.ReportsViewModel
import com.oposicontrol.admin.features.newstypes.presentation.NewsTypesScreen
import com.oposicontrol.admin.features.newstypes.presentation.NewsTypesViewModel
import com.oposicontrol.admin.features.roles.presentation.RolesIntent
import com.oposicontrol.admin.features.roles.presentation.RolesScreen
import com.oposicontrol.admin.features.roles.presentation.RolesViewModel
import com.oposicontrol.admin.features.settings.presentation.SettingsIntent
import com.oposicontrol.admin.features.settings.presentation.SettingsScreen
import com.oposicontrol.admin.features.settings.presentation.SettingsViewModel
import com.oposicontrol.admin.features.specialties.presentation.SpecialtiesIntent
import com.oposicontrol.admin.features.specialties.presentation.SpecialtiesScreen
import com.oposicontrol.admin.features.specialties.presentation.SpecialtiesViewModel
import com.oposicontrol.admin.features.communities.presentation.CommunitiesIntent
import com.oposicontrol.admin.features.communities.presentation.CommunitiesScreen
import com.oposicontrol.admin.features.communities.presentation.CommunitiesViewModel
import com.oposicontrol.admin.features.tags.presentation.TagsIntent
import com.oposicontrol.admin.features.tags.presentation.TagsScreen
import com.oposicontrol.admin.features.tags.presentation.TagsViewModel
import com.oposicontrol.admin.features.store.presentation.StoreIntent
import com.oposicontrol.admin.features.store.presentation.StoreScreen
import com.oposicontrol.admin.features.store.presentation.StoreViewModel
import com.oposicontrol.admin.features.support.presentation.SupportScreen
import com.oposicontrol.admin.features.support.presentation.SupportViewModel
import com.oposicontrol.admin.features.tickets.presentation.TicketsIntent
import com.oposicontrol.admin.features.tickets.presentation.TicketsScreen
import com.oposicontrol.admin.features.tickets.presentation.TicketsViewModel
import com.oposicontrol.admin.features.users.presentation.UsersIntent
import com.oposicontrol.admin.features.users.presentation.UsersScreen
import com.oposicontrol.admin.features.users.presentation.UsersViewModel
import com.oposicontrol.admin.domain.repository.TagSearchRepository
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

private const val TEMPORARILY_DISABLE_LOGIN = false

@Composable
fun AppRoot(
    appConfig: AppConfig,
) {
    val navigator = remember { AppNavigator() }
    val authViewModel: AuthViewModel = koinInject()
    val tagSearchRepository: TagSearchRepository = koinInject()

    val authState by authViewModel.state.collectAsState()
    val route by navigator.currentRoute.collectAsState()
    val backStack by navigator.backStack.collectAsState()
    val environmentLabel = buildString {
        append("Entorno ")
        append(appConfig.environment.name.lowercase())
        append(" | vista frontend")
        if (appConfig.supabaseUrl.isBlank()) append(" | Supabase sin configurar")
    }

    LaunchedEffect(authState.isAuthenticated, authState.isRestoring) {
        val session = when {
            authState.isRestoring -> SessionState.SESSION_RESTORING
            authState.isAuthenticated -> SessionState.AUTHENTICATED
            else -> SessionState.UNAUTHENTICATED
        }
        resolveGuardRoute(session, route)?.let { guardedRoute ->
            if (guardedRoute != route) {
                if (guardedRoute is AppRoute.Auth) navigator.openAuth() else navigator.replace(guardedRoute)
            }
        }
    }

    when {
        !TEMPORARILY_DISABLE_LOGIN && authState.isRestoring -> AuthScreen(
            viewModel = authViewModel,
            onAuthenticated = { navigator.replace(AppRoute.Home) },
        )

        !TEMPORARILY_DISABLE_LOGIN && !authState.isAuthenticated -> AuthScreen(
            viewModel = authViewModel,
            onAuthenticated = { navigator.replace(AppRoute.Home) },
        )

        else -> {
            WarmUpPrimaryTabs(authState.isAuthenticated)
            BoxWithConstraints(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
            val breakpoint = appBreakpointFor(maxWidth)
            val compactLayout = breakpoint != AppBreakpoint.EXPANDED
            val metadata = route.metadata()
            val legacySection = route.toLegacySection()
            val legacyRoute = legacySection?.let {
                adminSectionRoute(
                    section = it,
                    environmentLabel = environmentLabel,
                    openSection = navigator::openSection,
                    mobileExperience = compactLayout,
                    onSystemStatusClick = { navigator.push(AppRoute.System.Status) },
                )
            }
            // Swipe-between-tabs only applies while sitting at the root of one of
            // the 5 primary destinations (not inside a pushed detail/content route,
            // where a horizontal drag has no well-defined target).
            val primaryTabRoutes = remember { mobilePrimaryNavigation.map { it.route } }
            val currentPrimaryTabIndex = primaryTabRoutes.indexOf(route)
            val showTabPager = compactLayout && currentPrimaryTabIndex >= 0
            AppShell(
                currentRouteId = route.navigationId(),
                groups = appNavigationGroups.toShellGroups(includeMobileOnly = compactLayout),
                mobileDestinations = mobilePrimaryNavigation.map { it.toShellItem() },
                onRouteSelected = { id ->
                    appNavigationGroups
                        .asSequence()
                        .flatMap { it.items.asSequence() }
                        .firstOrNull { it.metadata.id == id }
                        ?.route
                        ?.let { selectedRoute ->
                            if (compactLayout) navigator.selectPrimaryTab(selectedRoute) else navigator.reset(selectedRoute)
                        }
                },
                environmentLabel = environmentLabel,
                title = route.headerTitle(),
                description = metadata.description,
                breadcrumbs = route.breadcrumbs(),
                showBreadcrumbs = !compactLayout,
                showScreenHeader = !(compactLayout && route == AppRoute.Home),
                // Detalle interno: gana espacio y evita cambiar de pestaña sin darse
                // cuenta mientras se lee/modera un hilo (Screen 05 §5). Solo esta
                // ruta; el resto del panel mantiene la bottom nav igual que siempre.
                showBottomBar = !(compactLayout && route is AppRoute.Community.Thread),
                primaryActionLabel = legacySection?.primaryActionLabel.takeIf { legacyRoute?.onPrimaryAction != null },
                onPrimaryAction = legacyRoute?.onPrimaryAction,
                canGoBack = backStack.size > 1,
                onBack = { navigator.pop() },
                globalSearch = {
                    if (!compactLayout && legacySection != null) {
                        GlobalTagSearch(
                            currentSection = legacySection,
                            repository = tagSearchRepository,
                            onOpenSection = navigator::openSection,
                        )
                    }
                },
            ) {
                if (showTabPager) {
                    MobilePrimaryTabPager(
                        routes = primaryTabRoutes,
                        currentIndex = currentPrimaryTabIndex,
                        onSettledIndexChange = { index -> navigator.selectPrimaryTab(primaryTabRoutes[index]) },
                    ) { pageRoute ->
                        when {
                            pageRoute is AppRoute.Reports.List -> ReportsScreen(
                                viewModel = koinInject(),
                                onOpenReport = { reportId -> navigator.push(AppRoute.Reports.Detail(reportId)) },
                            )
                            pageRoute is AppRoute.Community.OposiRed -> ForumScreen(
                                viewModel = koinInject(),
                                onOpenThread = { threadId -> navigator.push(AppRoute.Community.Thread(threadId)) },
                                onOpenPendingReports = { navigator.selectPrimaryTab(AppRoute.Reports.List) },
                            )
                            pageRoute is AppRoute.OposiTester.List -> TesterListScreen(
                                viewModel = koinInject(),
                                // Screen 07 (detalle) todavía no existe: la ruta tipada ya
                                // está lista, cae en el adaptador genérico "no implementado
                                // todavía" hasta que se construya, mismo patrón que Foro→Hilo.
                                onOpenReport = { reportId -> navigator.push(AppRoute.OposiTester.Detail(reportId)) },
                            )
                            pageRoute == route -> if (legacyRoute != null) legacyRoute.content() else LegacyRouteAdapter(metadata.title, metadata.description)
                            else -> MobilePrimaryTabContent(
                                route = pageRoute,
                                environmentLabel = environmentLabel,
                                openSection = navigator::openSection,
                            )
                        }
                    }
                } else if (compactLayout && route is AppRoute.Content) {
                    MobileContentRoot(
                        appConfig = appConfig,
                        initialView = route.toMobileContentView(),
                        showNavigationChrome = false,
                    )
                } else if (compactLayout && route is AppRoute.Reports.Detail) {
                    val reportId = (route as AppRoute.Reports.Detail).reportId
                    ReportDetailScreen(viewModel = koinInject(parameters = { parametersOf(reportId) }))
                } else if (compactLayout && route is AppRoute.Community.Thread) {
                    val threadId = (route as AppRoute.Community.Thread).threadId
                    ThreadDetailScreen(
                        viewModel = koinInject(parameters = { parametersOf(threadId) }),
                        onOpenAssociatedReports = { navigator.selectPrimaryTab(AppRoute.Reports.List) },
                        onBack = { navigator.pop() },
                    )
                } else if (legacyRoute != null) {
                    legacyRoute.content()
                } else {
                    LegacyRouteAdapter(metadata.title, metadata.description)
                }
            }
            }
        }
    }
}

/**
 * Precarga las tres pestañas móviles ya cerradas (Inicio, Informes, Foro) en
 * cuanto hay una sesión autenticada, en vez de esperar a que el usuario las
 * abra por primera vez — así el `HorizontalPager` de [MobilePrimaryTabPager]
 * (que solo compone la página visible) no obliga a arrancar cada carga desde
 * cero la primera vez que se desliza hasta esa pestaña.
 *
 * Depende de que sus ViewModels estén registrados como `single` (no `factory`)
 * en Koin: `koinInject()` los crea una única vez — disparando el `init { }`
 * que ya dispatch-ea su carga inicial — y esa misma instancia persiste el
 * resto de la sesión, así que revisitar la pestaña no vuelve a mostrar el
 * skeleton de carga. [ReportDetailViewModel] no entra aquí: sigue siendo
 * `factory` porque necesita un `reportId` nuevo cada vez.
 *
 * En cada transición a autenticado (incluido un nuevo login tras cerrar
 * sesión) se refresca explícitamente: los `single` de Koin sobreviven a un
 * logout, así que sin esto un segundo inicio de sesión en el mismo proceso
 * podría enseñar por un instante datos cacheados de la sesión anterior.
 */
@Composable
private fun WarmUpPrimaryTabs(isAuthenticated: Boolean) {
    val dashboardViewModel: DashboardViewModel = koinInject()
    val reportsViewModel: ReportsViewModel = koinInject()
    val forumViewModel: ForumViewModel = koinInject()
    LaunchedEffect(isAuthenticated) {
        if (!isAuthenticated) return@LaunchedEffect
        dashboardViewModel.dispatch(DashboardIntent.Refresh)
        reportsViewModel.dispatch(ReportsIntent.Refresh)
        forumViewModel.dispatch(ForumIntent.Load)
    }
}

/**
 * Horizontal swipe between the 5 primary mobile tabs, kept in sync with
 * [AppNavigator] in both directions: tapping a bottom-nav icon animates the
 * pager to that page ([currentIndex] changing), and swiping to settle on a
 * different page reports it back via [onSettledIndexChange] so the navigator
 * (and therefore the selected tab, header, etc.) follows the gesture.
 */
@Composable
private fun MobilePrimaryTabPager(
    routes: List<AppRoute>,
    currentIndex: Int,
    onSettledIndexChange: (Int) -> Unit,
    pageContent: @Composable (AppRoute) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = currentIndex.coerceAtLeast(0)) { routes.size }
    // LaunchedEffect(pagerState) below never restarts (pagerState's identity never
    // changes), so it must read currentIndex/onSettledIndexChange through
    // rememberUpdatedState — otherwise its collector closure stays bound to
    // whatever those were at first composition, silently comparing against a
    // stale index forever (reproduced: only the very first tab switch worked,
    // every swipe after that compared against the initial page and no-opped).
    val latestIndex = rememberUpdatedState(currentIndex)
    val latestOnSettledIndexChange = rememberUpdatedState(onSettledIndexChange)

    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0 && pagerState.currentPage != currentIndex && !pagerState.isScrollInProgress) {
            pagerState.animateScrollToPage(currentIndex)
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { settled ->
            if (settled != latestIndex.value) latestOnSettledIndexChange.value(settled)
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        pageContent(routes[page])
    }
}

/** Renders the content of an arbitrary primary tab, not just the current route —
 * needed so [MobilePrimaryTabPager] can compose neighboring pages while swiping. */
@Composable
private fun MobilePrimaryTabContent(
    route: AppRoute,
    environmentLabel: String,
    openSection: (AdminSection) -> Unit,
) {
    val legacySection = route.toLegacySection()
    if (legacySection != null) {
        adminSectionRoute(
            section = legacySection,
            environmentLabel = environmentLabel,
            openSection = openSection,
            mobileExperience = true,
        ).content()
    } else {
        val metadata = route.metadata()
        LegacyRouteAdapter(metadata.title, metadata.description)
    }
}

@Composable
private fun LegacyRouteAdapter(title: String, description: String) {
    com.oposicontrol.admin.core.ui.components.EmptyStateCard(
        title = title,
        message = "$description Esta ruta esta preparada para la migracion de su vertical.",
    )
}

private fun List<AppRouteMenuGroup>.toShellGroups(includeMobileOnly: Boolean): List<ShellNavigationGroup> = mapNotNull { group ->
    val items = group.items.filter { includeMobileOnly || it.metadata.visibility != com.oposicontrol.admin.app.navigation.NavigationVisibility.MOBILE_ONLY }
    items.takeIf { it.isNotEmpty() }?.let {
        ShellNavigationGroup(
            id = group.id.name,
            label = group.label,
            items = it.map { item -> item.toShellItem() },
        )
    }
}

private fun AppRouteMenuItem.toShellItem(): ShellNavigationItem = ShellNavigationItem(
    id = metadata.id,
    label = metadata.title,
    icon = icon,
)

private fun AppRoute.breadcrumbs(): List<String> = when (this) {
    AppRoute.Home -> emptyList()
    AppRoute.Auth -> listOf("Acceso")
    is AppRoute.Content.ExamDetail -> listOf("Contenido", "Examenes", examId)
    is AppRoute.Reports.Detail -> listOf("Informes", reportId)
    is AppRoute.Community.Thread -> listOf("Foro", threadId)
    is AppRoute.Community.ModerationDetail -> listOf("Foro", reportId)
    is AppRoute.OposiTester.Detail -> listOf("OposiTester", testerId)
    else -> listOf(metadata().title)
}

private fun AppRoute.headerTitle(): String = when (this) {
    is AppRoute.Content.ExamDetail -> "Editor de examen"
    is AppRoute.Reports.Detail -> "Informe"
    is AppRoute.Community.Thread -> "Hilo del foro"
    is AppRoute.Community.ModerationDetail -> "Detalle de moderacion"
    is AppRoute.OposiTester.Detail -> "Detalle de OposiTester"
    else -> metadata().title
}

private fun AppRoute.toMobileContentView(): MobileContentView = when (this) {
    AppRoute.Content.Exams -> MobileContentView.Solucionarios
    AppRoute.Content.Questions -> MobileContentView.Preguntas
    AppRoute.Content.Resources -> MobileContentView.Recursos
    AppRoute.Content.Imports -> MobileContentView.Solucionarios
    AppRoute.Content.Taxonomy -> MobileContentView.Especialidades
    is AppRoute.Content.ExamDetail -> MobileContentView.Editor
    else -> MobileContentView.Solucionarios
}

private class AdminSectionRoute(
    val onPrimaryAction: (() -> Unit)?,
    val content: @Composable () -> Unit,
)

@Composable
private fun adminSectionRoute(
    section: AdminSection,
    environmentLabel: String,
    openSection: (AdminSection) -> Unit,
    mobileExperience: Boolean,
    onSystemStatusClick: () -> Unit = {},
): AdminSectionRoute {
    return when (section) {
        AdminSection.DASHBOARD -> {
            val viewModel: DashboardViewModel = koinInject()
            AdminSectionRoute(
                onPrimaryAction = { viewModel.dispatch(DashboardIntent.Refresh) },
                content = {
                    if (mobileExperience) {
                        MobileDashboardScreen(
                            viewModel = viewModel,
                            environmentLabel = environmentLabel,
                            onQuickAction = { action -> handleDashboardAction(action, openSection) },
                        )
                    } else {
                        DashboardScreen(
                            viewModel = viewModel,
                            environmentLabel = environmentLabel,
                            onQuickAction = { action -> handleDashboardAction(action, openSection) },
                        )
                    }
                },
            )
        }

        AdminSection.USERS -> {
            val viewModel: UsersViewModel = koinInject()
            AdminSectionRoute(
                onPrimaryAction = { viewModel.dispatch(UsersIntent.CreateNew) },
                content = { UsersScreen(viewModel) },
            )
        }

        AdminSection.ROLES -> {
            val viewModel: RolesViewModel = koinInject()
            AdminSectionRoute(
                onPrimaryAction = { viewModel.dispatch(RolesIntent.CreateNew) },
                content = { RolesScreen(viewModel) },
            )
        }

        AdminSection.SPECIALTIES -> {
            val viewModel: SpecialtiesViewModel = koinInject()
            AdminSectionRoute(
                onPrimaryAction = { viewModel.dispatch(SpecialtiesIntent.CreateNew) },
                content = { SpecialtiesScreen(viewModel) },
            )
        }

        AdminSection.COMMUNITIES -> {
            val viewModel: CommunitiesViewModel = koinInject()
            AdminSectionRoute(
                onPrimaryAction = { viewModel.dispatch(CommunitiesIntent.CreateNew) },
                content = { CommunitiesScreen(viewModel) },
            )
        }

        AdminSection.TAGS -> {
            val viewModel: TagsViewModel = koinInject()
            AdminSectionRoute(
                onPrimaryAction = { viewModel.dispatch(TagsIntent.CreateNew) },
                content = { TagsScreen(viewModel) },
            )
        }

        AdminSection.NEWS_TYPES -> {
            val viewModel: NewsTypesViewModel = koinInject()
            AdminSectionRoute(
                onPrimaryAction = { viewModel.dispatch(NewsTypesIntent.CreateNew) },
                content = { NewsTypesScreen(viewModel) },
            )
        }

        AdminSection.NEWS -> {
            val viewModel: NewsViewModel = koinInject()
            AdminSectionRoute(
                onPrimaryAction = { viewModel.dispatch(NewsIntent.CreateNew) },
                content = { NewsScreen(viewModel) },
            )
        }

        AdminSection.EXAMS -> {
            val viewModel: ExamsViewModel = koinInject()
            AdminSectionRoute(
                onPrimaryAction = { viewModel.dispatch(ExamsIntent.CreateNew) },
                content = { ExamsScreen(viewModel) },
            )
        }

        AdminSection.QUESTIONS_RESOURCES -> {
            val viewModel: ExamsViewModel = koinInject()
            AdminSectionRoute(
                onPrimaryAction = { viewModel.dispatch(ExamsIntent.CreateQuestion) },
                content = { QuestionsResourcesScreen(viewModel) },
            )
        }

        AdminSection.EXAM_ACCESS -> {
            val viewModel: ExamAccessViewModel = koinInject()
            AdminSectionRoute(
                onPrimaryAction = { viewModel.dispatch(ExamAccessIntent.CreateNew) },
                content = { ExamAccessScreen(viewModel) },
            )
        }

        AdminSection.STORE -> {
            val viewModel: StoreViewModel = koinInject()
            AdminSectionRoute(
                onPrimaryAction = { viewModel.dispatch(StoreIntent.CreateNew) },
                content = { StoreScreen(viewModel) },
            )
        }

        AdminSection.TICKETS -> {
            val viewModel: TicketsViewModel = koinInject()
            AdminSectionRoute(
                onPrimaryAction = { viewModel.dispatch(TicketsIntent.PrepareAdjustment) },
                content = { TicketsScreen(viewModel) },
            )
        }

        AdminSection.SUPPORT -> {
            val viewModel: SupportViewModel = koinInject()
            AdminSectionRoute(
                onPrimaryAction = null,
                content = { SupportScreen(viewModel) },
            )
        }

        AdminSection.MODERATION -> {
            val viewModel: ModerationViewModel = koinInject()
            AdminSectionRoute(
                onPrimaryAction = { viewModel.dispatch(ModerationIntent.CreateNew) },
                content = { ModerationScreen(viewModel) },
            )
        }

        AdminSection.SETTINGS -> {
            val viewModel: SettingsViewModel = koinInject()
            AdminSectionRoute(
                onPrimaryAction = { viewModel.dispatch(SettingsIntent.Refresh) },
                content = {
                    SettingsScreen(
                        viewModel = viewModel,
                        onSystemStatusClick = onSystemStatusClick,
                    )
                },
            )
        }

        AdminSection.AUDIT -> {
            val viewModel: AuditViewModel = koinInject()
            AdminSectionRoute(
                onPrimaryAction = { viewModel.dispatch(AuditIntent.Refresh) },
                content = { AuditScreen(viewModel) },
            )
        }
    }
}

private fun handleDashboardAction(
    action: DashboardQuickAction,
    openSection: (AdminSection) -> Unit,
) {
    val target = when (action) {
        DashboardQuickAction.REVIEW_PENDING -> AdminSection.QUESTIONS_RESOURCES
        DashboardQuickAction.REVIEW_FORUM_REPORTS -> AdminSection.MODERATION
        DashboardQuickAction.IMPORT_QUESTIONS -> AdminSection.QUESTIONS_RESOURCES
        DashboardQuickAction.AUDIT_RESOURCES -> AdminSection.QUESTIONS_RESOURCES
        DashboardQuickAction.CREATE_NEWS -> AdminSection.NEWS
        DashboardQuickAction.CREATE_EXAM -> AdminSection.EXAMS
        DashboardQuickAction.OPEN_USERS -> AdminSection.USERS
        DashboardQuickAction.OPEN_SUPPORT -> AdminSection.SUPPORT
        DashboardQuickAction.ACTIVATE_OFFER -> AdminSection.STORE
        DashboardQuickAction.REVIEW_TICKETS -> AdminSection.TICKETS
        DashboardQuickAction.OPEN_AUDIT -> AdminSection.AUDIT
    }
    openSection(target)
}
