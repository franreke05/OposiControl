package com.oposicontrol.admin.app.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.oposicontrol.admin.app.navigation.handleSystemBack
import com.oposicontrol.admin.app.navigation.canNavigateBack
import com.oposicontrol.admin.app.platform.PlatformBackHandler
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
import com.oposicontrol.admin.features.auth.presentation.AccessRequestViewModel
import com.oposicontrol.admin.features.auth.presentation.AuthScreen
import com.oposicontrol.admin.features.auth.presentation.AuthViewModel
import com.oposicontrol.admin.features.dashboard.presentation.DashboardIntent
import com.oposicontrol.admin.features.dashboard.presentation.DashboardQuickAction
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.features.dashboard.presentation.DashboardV2Destination
import com.oposicontrol.admin.features.dashboard.presentation.DashboardV2Intent
import com.oposicontrol.admin.features.dashboard.presentation.DashboardV2Screen
import com.oposicontrol.admin.features.dashboard.presentation.DashboardV2ViewModel
import com.oposicontrol.admin.features.dashboard.presentation.DashboardViewModel
import com.oposicontrol.admin.features.dashboard.presentation.MobileDashboardScreen
import com.oposicontrol.admin.features.exams.presentation.ExamGradingRulesViewModel
import com.oposicontrol.admin.features.exams.presentation.LocalGradingRulesViewModel
import com.oposicontrol.admin.features.examaccess.presentation.ExamAccessIntent
import com.oposicontrol.admin.features.examaccess.presentation.ExamAccessScreen
import com.oposicontrol.admin.features.examaccess.presentation.ExamAccessViewModel
import com.oposicontrol.admin.features.exams.presentation.ExamsIntent
import com.oposicontrol.admin.features.exams.presentation.ExamWorkspaceScreen
import com.oposicontrol.admin.features.exams.presentation.ExamsViewModel
import com.oposicontrol.admin.features.exams.presentation.ContentUploadScreen
import com.oposicontrol.admin.features.exams.presentation.MobileContentView
import com.oposicontrol.admin.features.questions.presentation.QuestionsScreen
import com.oposicontrol.admin.features.questions.presentation.QuestionsViewModel
import com.oposicontrol.admin.features.imports.presentation.ImportViewModel
import com.oposicontrol.admin.features.forum.presentation.ForumIntent
import com.oposicontrol.admin.features.forum.presentation.ForumScreen
import com.oposicontrol.admin.features.forum.presentation.ForumViewModel
import com.oposicontrol.admin.features.forum.presentation.ThreadDetailScreen
import com.oposicontrol.admin.features.opositester.presentation.TesterDetailScreen
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
import com.oposicontrol.admin.features.settings.presentation.SettingsScreen
import com.oposicontrol.admin.features.settings.presentation.SystemStatusScreen
import com.oposicontrol.admin.features.settings.presentation.SystemStatusViewModel
import com.oposicontrol.admin.features.specialties.presentation.SpecialtiesIntent
import com.oposicontrol.admin.features.specialties.presentation.SpecialtiesScreen
import com.oposicontrol.admin.features.specialties.presentation.SpecialtiesViewModel
import com.oposicontrol.admin.features.communities.presentation.CommunitiesIntent
import com.oposicontrol.admin.features.communities.presentation.CommunitiesScreen
import com.oposicontrol.admin.features.communities.presentation.CommunitiesViewModel
import com.oposicontrol.admin.features.tags.presentation.TagsIntent
import com.oposicontrol.admin.features.tags.presentation.TagsScreen
import com.oposicontrol.admin.features.tags.presentation.TagsViewModel
import com.oposicontrol.admin.features.analytics.presentation.ProductAnalyticsScreen
import com.oposicontrol.admin.domain.model.AnalyticsSection
import com.oposicontrol.admin.features.store.presentation.MarketScreen
import com.oposicontrol.admin.features.store.presentation.MarketSection
import com.oposicontrol.admin.features.store.presentation.MarketViewModel
import com.oposicontrol.admin.features.support.presentation.SupportScreen
import com.oposicontrol.admin.features.support.presentation.SupportViewModel
import com.oposicontrol.admin.features.tickets.presentation.TicketsIntent
import com.oposicontrol.admin.features.tickets.presentation.TicketsScreen
import com.oposicontrol.admin.features.tickets.presentation.TicketsViewModel
import com.oposicontrol.admin.features.users.presentation.UsersIntent
import com.oposicontrol.admin.features.users.presentation.UsersScreen
import com.oposicontrol.admin.features.users.presentation.UsersViewModel
import org.koin.compose.getKoin
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import androidx.compose.runtime.rememberCoroutineScope
import com.oposicontrol.admin.domain.repository.AuthRepository
import kotlinx.coroutines.launch

private const val TEMPORARILY_DISABLE_LOGIN = false

internal enum class AppContentTarget {
    TAB_PAGER,

    /**
     * Panel de escritorio (Dashboard V2).
     *
     * Sólo fuera de compacto. En móvil, Inicio sigue exactamente igual —
     * `MobileDashboardScreen` sobre el `DashboardViewModel` de siempre — que es
     * el mismo patrón con el que ya entraron Preguntas e Importación: pantalla
     * nueva en escritorio, camino compacto intacto.
     */
    DESKTOP_DASHBOARD,
    CONTENT_UPLOAD,
    SYSTEM_STATUS,
    FORUM,
    THREAD_DETAIL,
    REPORTS_LIST,
    REPORTS_DETAIL,
    OPOSITESTER_LIST,
    OPOSITESTER_DETAIL,
    MARKET,
    PRODUCT_ANALYTICS,
    MOBILE_CONTENT_ROOT,
    LEGACY_SECTION,
    LEGACY_PLACEHOLDER,
    // Hubs delgados de la consolidación de navegación (2026-09-04): varias
    // rutas antiguas de sidebar convergen en una sola pantalla con pestañas,
    // igual que ya hacía MARKET con Consumibles/Promociones/Pop-ups.
    SYSTEM_HUB,
    USERS_HUB,
    CATALOG_HUB,
    NEWS_HUB,
    MODERATION_HUB,

    /**
     * Exámenes en escritorio: workspace de catálogo + examen + resumen.
     *
     * Mismo patrón que `DESKTOP_DASHBOARD`: la pantalla nueva se enruta sólo
     * cuando NO es un layout compacto, y el camino móvil sigue cayendo en
     * `MOBILE_CONTENT_ROOT` sin enterarse.
     */
    EXAM_WORKSPACE,
}

/**
 * Single source of truth for "what renders in the AppShell content slot",
 * kept as a pure function (route/layout in, target out) so Desktop
 * reachability for Reports/Forum/OposiTester/Content Upload is unit-tested
 * without a Compose UI test harness — see AppRootContentTargetTest. The
 * composable below must switch on exactly this function's result, not
 * re-derive the same conditions inline.
 */
internal fun resolveContentTarget(
    route: AppRoute,
    compactLayout: Boolean,
    showTabPager: Boolean,
    hasLegacyRoute: Boolean,
): AppContentTarget = when {
    showTabPager -> AppContentTarget.TAB_PAGER
    // Inicio en escritorio abre el panel nuevo. En compacto NO cambia nada: cae
    // por `hasLegacyRoute` al adaptador de AdminSection.DASHBOARD, que es quien
    // pinta MobileDashboardScreen con el DashboardViewModel actual.
    route == AppRoute.Home && !compactLayout -> AppContentTarget.DESKTOP_DASHBOARD
    route == AppRoute.Content.Imports -> AppContentTarget.CONTENT_UPLOAD
    // Exámenes en escritorio abre el workspace nuevo. `ExamDetail` entra por la
    // misma puerta porque es el mismo examen abierto en la misma pantalla: en el
    // workspace el detalle no es un destino aparte, es la columna central.
    // En compacto NO cambia nada: cae en `MOBILE_CONTENT_ROOT` unas líneas más
    // abajo, igual que hasta ahora.
    (route == AppRoute.Content.Exams || route is AppRoute.Content.ExamDetail) &&
        !compactLayout -> AppContentTarget.EXAM_WORKSPACE
    // «Estado del sistema» como pantalla suelta existe SOLO en la vista estrecha, a la que se llega desde Ajustes. En
    // escritorio la misma ruta abre el hub de Sistema en su pestaña Estado (más abajo): un único camino, no dos pantallas
    // de estado distintas según por dónde se entre.
    route == AppRoute.System.Status && compactLayout -> AppContentTarget.SYSTEM_STATUS
    route == AppRoute.Community.OposiRed -> AppContentTarget.FORUM
    route is AppRoute.Community.Thread -> AppContentTarget.THREAD_DETAIL
    // Moderación: hub con Reportes y OposiTester como pestañas (§11) — con
    // cuál lo decide `moderationTabFor`. Sólo en escritorio: en móvil, Reportes
    // y OposiTester siguen siendo destinos propios de la barra inferior (nunca
    // llegan aquí de todos modos — `showTabPager` ya los intercepta antes, ver
    // arriba — pero el guard explícito documenta la intención sin depender de
    // ese orden).
    (
        route == AppRoute.Moderation.Queue ||
            route is AppRoute.Reports.List ||
            route is AppRoute.OposiTester.List
        ) && !compactLayout -> AppContentTarget.MODERATION_HUB
    route is AppRoute.Reports.List -> AppContentTarget.REPORTS_LIST
    route is AppRoute.Reports.Detail -> AppContentTarget.REPORTS_DETAIL
    route is AppRoute.OposiTester.List -> AppContentTarget.OPOSITESTER_LIST
    route is AppRoute.OposiTester.Detail -> AppContentTarget.OPOSITESTER_DETAIL
    // Catálogo comercial: administración de escritorio (tablas y formularios
    // densos), no una pantalla de móvil. En compacto se deja caer al adaptador
    // legacy en vez de servir media pantalla.
    //
    // Catalog y Plans abren el mismo hub (Productos/Planes); con qué pestaña lo
    // decide `catalogTabFor`. Antes sólo Catalog llegaba aquí (COMMERCIAL_CATALOG)
    // y Plans caía al adaptador legacy — dos pantallas para lo mismo.
    //
    // Consumibles, Promociones y Pop-ups también abren Catálogo desde 2026-10-14: ahí es donde se editan. OposiMarket
    // se queda sólo como escaparate.
    (
        route == AppRoute.Commercial.Catalog ||
            route == AppRoute.Commercial.Plans ||
            route == AppRoute.Commercial.Consumables ||
            route == AppRoute.Commercial.Promotions ||
            route == AppRoute.Commercial.PopupRules
        ) && !compactLayout -> AppContentTarget.CATALOG_HUB
    // OposiMarket es administración densa de escritorio (cuatro tablas), no una
    // pantalla de móvil.
    //
    // Las cuatro rutas abren la misma pantalla; con qué pestaña lo decide
    // `marketSectionFor`. Son entradas de menú distintas sobre una sola
    // pantalla, no cuatro pantallas.
    route == AppRoute.Commercial.Market && !compactLayout -> AppContentTarget.MARKET
    // Analitica de producto: secciones con tablas densas, escritorio. Estudio
    // (antes caía al adaptador legacy de Dashboard, que no tenía nada que ver)
    // abre esta misma pantalla en su sección real — ver `analyticsSectionFor`.
    (route == AppRoute.Analytics.Home || route == AppRoute.Analytics.Performance) &&
        !compactLayout -> AppContentTarget.PRODUCT_ANALYTICS
    // Usuarios: hub con Competición (antes Rankings, pantalla propia) y Accesos
    // (antes Commercial.Subscriptions) añadidos como pestañas — con cuál lo
    // decide `usersTabFor`.
    //
    // OposiTickets es una subpantalla de Usuarios (decisión del propietario, 2026-10-11): una cartera es de una persona,
    // así que vive como pestaña del hub y no como entrada propia del menú.
    (
        route == AppRoute.Users.List ||
            route == AppRoute.Commercial.Tickets ||
            route == AppRoute.Rankings ||
            route == AppRoute.Commercial.Subscriptions
        ) && !compactLayout -> AppContentTarget.USERS_HUB
    // Sistema: hub con Estado/Administradores/Auditoría como pestañas — con
    // cuál lo decide `systemTabFor`. Las cinco rutas de Sistema convergen aquí
    // en escritorio, también `Status` (ver arriba).
    (
        route == AppRoute.System.Settings ||
            route == AppRoute.System.Roles ||
            route == AppRoute.System.Diagnostics ||
            route == AppRoute.System.Status ||
            route == AppRoute.System.Audit
        ) && !compactLayout -> AppContentTarget.SYSTEM_HUB
    // Noticias: hub con Tipos añadido como pestaña — con cuál lo decide
    // `newsTabFor`.
    (route == AppRoute.Publication.News || route == AppRoute.Publication.NewsTypes) &&
        !compactLayout -> AppContentTarget.NEWS_HUB
    // Comunidades y Etiquetas no tienen vista dentro del contenido móvil: caen a su
    // propia pantalla CRUD (adaptador legacy), igual que en escritorio.
    compactLayout && route is AppRoute.Content &&
        route != AppRoute.Content.Communities &&
        route != AppRoute.Content.Tags -> AppContentTarget.MOBILE_CONTENT_ROOT
    hasLegacyRoute -> AppContentTarget.LEGACY_SECTION
    else -> AppContentTarget.LEGACY_PLACEHOLDER
}

/**
 * A qué ruta canónica lleva cada atajo del panel de escritorio.
 *
 * Está aquí y no en el módulo de dashboard porque `AppRoute` vive en `composeApp`
 * y un feature no puede depender de la app. Es además la única lista de destinos
 * del panel, y por eso se prueba: `AppRootContentTargetTest` la recorre entera.
 *
 * Todo pasa por `AppRoute`. El panel anterior navegaba con `AdminSection`, el
 * enum heredado, y por eso algunos atajos aterrizaban en pantallas que ya no
 * eran las canónicas — Estado, por ejemplo, tiene dos rutas y sólo una de ellas
 * existe en escritorio.
 */
internal fun DashboardV2Destination.toAppRoute(): AppRoute = when (this) {
    // System.Status está marcada MOBILE_ONLY en el catálogo de navegación; en
    // escritorio el estado del sistema vive en la pestaña Estado del hub de
    // Sistema, a la que se llega por Diagnostics.
    DashboardV2Destination.SYSTEM_DIAGNOSTICS -> AppRoute.System.Diagnostics
    DashboardV2Destination.MODERATION_QUEUE -> AppRoute.Moderation.Queue
    DashboardV2Destination.OPOSITESTER_LIST -> AppRoute.OposiTester.List
    // Revisar borradores es la pantalla de Exámenes: los borradores son exámenes
    // sin publicar, no una lista aparte.
    DashboardV2Destination.EXAMS -> AppRoute.Content.Exams
    // Misma ruta; lo que cambia es el gesto que se hace al llegar (ver
    // [openDashboardDestination]).
    DashboardV2Destination.NEW_EXAM -> AppRoute.Content.Exams
    DashboardV2Destination.QUESTIONS -> AppRoute.Content.Questions
    DashboardV2Destination.IMPORTS -> AppRoute.Content.Imports
    DashboardV2Destination.USERS -> AppRoute.Users.List
    DashboardV2Destination.ANALYTICS -> AppRoute.Analytics.Home
    DashboardV2Destination.SUBSCRIPTIONS -> AppRoute.Commercial.Subscriptions
    DashboardV2Destination.AUDIT -> AppRoute.System.Audit
}

/**
 * Etiqueta de la acción primaria de la cabecera.
 *
 * El panel de escritorio es la excepción a la regla anterior: no tiene adaptador
 * legacy del que tomarla prestada —y por eso hasta ahora se quedaba sin ninguna—
 * y sin embargo sí tiene una acción primaria evidente. Recargar existía sólo por
 * franja y escondido en el estado de error de cada una, así que con las seis
 * franjas correctas no había forma de pedir datos nuevos.
 *
 * [legacyLabel] es la del adaptador y se pasa ya resuelta a null cuando ese
 * adaptador no tiene acción, para que esta función no tenga que conocerlo.
 */
internal fun primaryActionLabelFor(target: AppContentTarget, legacyLabel: String?): String? =
    when (target) {
        AppContentTarget.DESKTOP_DASHBOARD -> DASHBOARD_REFRESH_LABEL
        // El workspace de Exámenes tampoco tiene adaptador legacy del que tomarla
        // prestada. Su acción primaria abre un borrador EN BLANCO —no crea nada—,
        // y por eso se llama «Nuevo examen» y no «Crear examen»: «Crear examen» es
        // el botón de la barra de guardado de ese borrador, el que sí lo persiste.
        // Dos botones coral con la misma etiqueta y efectos distintos en la misma
        // pantalla garantizan que se pulse el que no era.
        AppContentTarget.EXAM_WORKSPACE -> EXAM_NEW_LABEL
        AppContentTarget.LEGACY_SECTION -> legacyLabel
        else -> null
    }

/**
 * Si este destino del panel abre, además, un borrador de examen EN BLANCO (PC-15).
 */
internal fun DashboardV2Destination.opensBlankExamDraft(): Boolean =
    this == DashboardV2Destination.NEW_EXAM

/**
 * Lo que hace el panel de escritorio al pulsar un atajo (PC-15).
 *
 * El atajo de crear examen navegaba a Exámenes y ahí se quedaba: el administrador
 * aterrizaba en el catálogo, con el último examen abierto, y tenía que buscar el
 * botón de crear. Ahora el destino [DashboardV2Destination.NEW_EXAM] pide además el
 * borrador en blanco, con el MISMO intent que el botón «Nuevo examen» del propio
 * workspace (`SwitchExam(null)`): si hay cambios sin guardar pregunta antes de
 * tirarlos, y no escribe nada en la base — el examen sólo existe al guardarse.
 *
 * Está fuera del `@Composable` para poder probar que el borrador se pide y que la
 * navegación ocurre igual, sin montar la aplicación.
 */
internal fun openDashboardDestination(
    destination: DashboardV2Destination,
    navigate: (AppRoute) -> Unit,
    openBlankExamDraft: () -> Unit,
) {
    // Primero el borrador y después la navegación: `ExamsViewModel` es un `single`,
    // así que el intent lo procesa la misma instancia que va a pintar la pantalla y
    // ésta aparece ya con el editor vacío, sin un parpadeo del examen anterior.
    if (destination.opensBlankExamDraft()) openBlankExamDraft()
    navigate(destination.toAppRoute())
}

internal const val DASHBOARD_REFRESH_LABEL = "Actualizar"

internal const val EXAM_NEW_LABEL = "Nuevo examen"

@Composable
fun AppRoot(
    appConfig: AppConfig,
) {
    val navigator = remember { AppNavigator() }
    val authViewModel: AuthViewModel = koinInject()
    // Formulario «Solicitar acceso» de la pantalla de acceso (publico: no necesita sesion).
    val accessRequestViewModel: AccessRequestViewModel = koinInject()

    val authState by authViewModel.state.collectAsState()
    val route by navigator.currentRoute.collectAsState()
    val backStack by navigator.backStack.collectAsState()
    val environmentLabel = buildString {
        append("Entorno ")
        append(appConfig.environment.name.lowercase())
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
            accessRequestViewModel = accessRequestViewModel,
        )

        !TEMPORARILY_DISABLE_LOGIN && !authState.isAuthenticated -> AuthScreen(
            viewModel = authViewModel,
            onAuthenticated = { navigator.replace(AppRoute.Home) },
            accessRequestViewModel = accessRequestViewModel,
        )

        else -> {
            // Único punto de enganche del botón/gesto Atrás del sistema, y va
            // aquí porque es el único sitio que ve la pila completa. Antes no
            // había ninguno: Atrás cerraba la aplicación desde cualquier
            // pantalla de detalle.
            //
            // `enabled` se recalcula con la pila, así que en una pantalla raíz el
            // handler se desactiva y el gesto llega al sistema — que es el
            // comportamiento normal de Android, no un caso especial.
            PlatformBackHandler(
                enabled = navigator.canNavigateBack(),
                onBack = { navigator.handleSystemBack() },
            )
            BoxWithConstraints(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
            val breakpoint = appBreakpointFor(maxWidth)
            val compactLayout = breakpoint != AppBreakpoint.EXPANDED
            // Sólo en la vista estrecha (DEBT-016/E5, 2026-10-15): las pestañas que precarga son las de la barra
            // inferior móvil. En escritorio Inicio es Dashboard V2 (1 petición) y la precarga lanzaba además, sin que
            // nada lo pintara, dos veces el agregado heredado de Home (≈10 + nº de exámenes peticiones cada vez).
            if (compactLayout) WarmUpPrimaryTabs(authState.isAuthenticated)
            val metadata = route.metadata()
            val legacySection = route.toLegacySection()
            // Swipe-between-tabs only applies while sitting at the root of one of
            // the 5 primary destinations (not inside a pushed detail/content route,
            // where a horizontal drag has no well-defined target).
            val primaryTabRoutes = remember { mobilePrimaryNavigation.map { it.route } }
            val currentPrimaryTabIndex = primaryTabRoutes.indexOf(route)
            val showTabPager = compactLayout && currentPrimaryTabIndex >= 0
            val hasLegacyScreen = legacySection?.hasLegacyScreen() == true
            // `contentTarget` se calcula aquí y no dentro del slot de contenido porque la cabecera (acción primaria)
            // también lo necesita: una ruta puede tener `legacySection` (por su versión móvil) y aun así resolver a un
            // hub propio en escritorio, donde la acción del adaptador legacy dispararía sobre una pantalla que no se ve.
            //
            // El adaptador legacy sólo se compone si de verdad se va a pintar (su sección o la pestaña móvil actual):
            // componerlo inyecta su ViewModel, y varios cargan datos en `init`. Antes Inicio de escritorio creaba así
            // el `DashboardViewModel` heredado —y su agregado de ~10 + N peticiones— para no enseñarlo nunca.
            val contentTarget = resolveContentTarget(route, compactLayout, showTabPager, hasLegacyScreen)
            val legacyRoute = legacySection
                ?.takeIf { contentTarget == AppContentTarget.LEGACY_SECTION || contentTarget == AppContentTarget.TAB_PAGER }
                ?.let {
                    adminSectionRoute(
                        section = it,
                        environmentLabel = environmentLabel,
                        openSection = navigator::openSection,
                        onOpenContentUpload = { navigator.push(AppRoute.Content.Imports) },
                    )
                }
            // Se resuelve aquí y no dentro del slot de contenido porque la
            // cabecera también lo necesita: el panel de escritorio es la única
            // pantalla cuya acción primaria no viene del adaptador legacy.
            // `koinInject` sólo se llama en la rama del panel, así que ninguna
            // otra ruta paga por instanciarlo.
            val dashboardV2ViewModel: DashboardV2ViewModel? =
                if (contentTarget == AppContentTarget.DESKTOP_DASHBOARD) koinInject() else null
            // Mismo motivo que arriba: la cabecera necesita el ViewModel para su
            // acción primaria («Nuevo examen», que abre un borrador en blanco), así
            // que se resuelve aquí y no dentro del slot de contenido. Ninguna otra
            // ruta paga por él.
            val examsViewModel: ExamsViewModel? =
                if (contentTarget == AppContentTarget.EXAM_WORKSPACE) koinInject() else null
            val authRepository: AuthRepository = koinInject()
            val logoutScope = rememberCoroutineScope()
            AppShell(
                // El mismo cierre de sesion que Ajustes en la vista estrecha; la app vuelve al acceso al quedarse sin sesion.
                onLogout = { logoutScope.launch { authRepository.logout() } },
                currentRouteId = sidebarRouteIdFor(route, compactLayout),
                groups = appNavigationGroups.toShellGroups(compactLayout = compactLayout),
                mobileDestinations = mobilePrimaryNavigation.map { it.toShellItem() },
                onRouteSelected = { id ->
                    appNavigationGroups
                        .asSequence()
                        .flatMap { it.items.asSequence() }
                        .firstOrNull { it.metadata.id == id }
                        ?.route
                        ?.let { selectedRoute ->
                            if (compactLayout && selectedRoute in primaryTabRoutes) {
                                navigator.selectPrimaryTab(selectedRoute)
                            } else if (compactLayout) {
                                navigator.push(selectedRoute)
                            } else {
                                navigator.reset(selectedRoute)
                            }
                        }
                },
                environmentLabel = environmentLabel,
                title = route.headerTitle(),
                description = metadata.description,
                breadcrumbs = route.breadcrumbs(),
                showBreadcrumbs = !compactLayout,
                showScreenHeader = !(compactLayout && route == AppRoute.Home),
                // Detalle interno: gana espacio y evita cambiar de pestaña sin darse
                // cuenta mientras se lee/modera un hilo (Screen 05 §5), una incidencia
                // de OposiTester (Screen 07) o el estado del sistema (Screen 10). El
                // resto del panel mantiene la bottom nav igual que siempre.
                showBottomBar = !(
                    compactLayout &&
                        (route is AppRoute.Community.Thread || route is AppRoute.OposiTester.Detail || route == AppRoute.System.Status)
                    ),
                // Sólo si el cuerpo realmente va a pintar el adaptador legacy:
                // si `contentTarget` es uno de los hubs nuevos, esta ruta tiene
                // `legacySection` no nulo únicamente por su versión móvil, y
                // mostrar aquí su acción primaria dispararía sobre un
                // ViewModel/pantalla que no es la que se ve.
                //
                // El panel de escritorio es la excepción: no tiene adaptador
                // legacy que le preste una acción, y aun así SÍ tiene una acción
                // primaria evidente —recargar las seis franjas de golpe—, que
                // hasta ahora sólo existía por franja y escondida en el estado de
                // error de cada una.
                primaryActionLabel = primaryActionLabelFor(
                    target = contentTarget,
                    legacyLabel = legacySection?.primaryActionLabel
                        .takeIf { legacyRoute?.onPrimaryAction != null },
                ),
                onPrimaryAction = when (contentTarget) {
                    AppContentTarget.DESKTOP_DASHBOARD -> dashboardV2ViewModel?.let { viewModel ->
                        { viewModel.dispatch(DashboardV2Intent.RefreshAll) }
                    }
                    AppContentTarget.EXAM_WORKSPACE -> examsViewModel?.let { viewModel ->
                        // SwitchExam y no CreateNew: en el workspace este botón
                        // tira el examen que se esté editando, así que pasa por
                        // la misma confirmación que el catálogo.
                        { viewModel.dispatch(ExamsIntent.SwitchExam(null)) }
                    }
                    AppContentTarget.LEGACY_SECTION -> legacyRoute?.onPrimaryAction
                    else -> null
                },
                primaryActionIcon = AppIcon.REFRESH.takeIf {
                    contentTarget == AppContentTarget.DESKTOP_DASHBOARD
                },
                canGoBack = backStack.size > 1,
                onBack = { navigator.pop() },
                // Sin `globalSearch` (2026-10-15): la búsqueda global por etiquetas no tiene ruta admin canónica
                // —los endpoints legacy `/search` leen `search_index`, ausente en MAIN— y el componente sólo decía
                // «no disponible». Cada pantalla conserva su propio buscador (Preguntas, Exámenes, Usuarios…).
            ) {
                when (contentTarget) {
                    AppContentTarget.DESKTOP_DASHBOARD -> {
                        // PC-15: el `ExamsViewModel` se resuelve SÓLO al pulsar
                        // «Nuevo examen», no al pintar el panel: inyectarlo aquí
                        // cargaría el catálogo de exámenes cada vez que se abre
                        // Inicio.
                        val koin = getKoin()
                        // El mismo ViewModel que alimenta el botón «Actualizar»
                        // de la cabecera; en esta rama nunca es null.
                        DashboardV2Screen(
                            viewModel = requireNotNull(dashboardV2ViewModel),
                            // `reset` y no `push`: en escritorio el panel es la
                            // raíz, igual que hace la selección de sidebar. Con
                            // `push` cada atajo dejaría un nivel de vuelta atrás
                            // que en escritorio no se ve.
                            onOpen = { destination ->
                                openDashboardDestination(
                                    destination = destination,
                                    navigate = { targetRoute -> navigator.reset(targetRoute) },
                                    openBlankExamDraft = {
                                        koin.get<ExamsViewModel>().dispatch(ExamsIntent.SwitchExam(null))
                                    },
                                )
                            },
                        )
                    }
                    AppContentTarget.TAB_PAGER -> MobilePrimaryTabPager(
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
                                onOpenReport = { reportId -> navigator.push(AppRoute.OposiTester.Detail(reportId)) },
                            )
                            // Ajustes de la vista estrecha: perfil de la sesión, «Estado del sistema» y «Cerrar sesión».
                            // Va aquí, como las otras pestañas con pantalla propia, porque `System.Settings` no tiene
                            // sección legacy (en escritorio es el hub de Sistema) y sin esta rama la pestaña caía en el
                            // marcador «ruta preparada para la migración».
                            pageRoute == AppRoute.System.Settings -> SettingsScreen(
                                viewModel = koinInject(),
                                onSystemStatusClick = { navigator.push(AppRoute.System.Status) },
                            )
                            pageRoute == route -> if (legacyRoute != null) legacyRoute.content() else LegacyRouteAdapter(metadata.title, metadata.description)
                            else -> MobilePrimaryTabContent(
                                route = pageRoute,
                                environmentLabel = environmentLabel,
                                openSection = navigator::openSection,
                            )
                        }
                    }
                    AppContentTarget.CONTENT_UPLOAD -> {
                        // Desktop tiene su propia pantalla (features/imports);
                        // compacto/mobile sigue exactamente igual con
                        // ContentUploadScreen (features/exams, sin tocar).
                        if (compactLayout) {
                            ContentUploadScreen(viewModel = koinInject())
                        } else {
                            val importViewModel: ImportViewModel = koinInject()
                            ImportHubScreen(importViewModel)
                        }
                    }
                    AppContentTarget.SYSTEM_STATUS -> {
                        val viewModel: SystemStatusViewModel = koinInject()
                        SystemStatusScreen(viewModel = viewModel)
                    }
                    AppContentTarget.FORUM -> ForumScreen(
                        viewModel = koinInject(),
                        onOpenThread = { threadId -> navigator.push(AppRoute.Community.Thread(threadId)) },
                        onOpenPendingReports = { navigator.selectPrimaryTab(AppRoute.Reports.List) },
                    )
                    AppContentTarget.THREAD_DETAIL -> {
                        val threadId = (route as AppRoute.Community.Thread).threadId
                        ThreadDetailScreen(
                            viewModel = koinInject(parameters = { parametersOf(threadId) }),
                            onOpenAssociatedReports = { navigator.selectPrimaryTab(AppRoute.Reports.List) },
                            onBack = { navigator.pop() },
                        )
                    }
                    AppContentTarget.REPORTS_LIST -> ReportsScreen(
                        viewModel = koinInject(),
                        onOpenReport = { reportId -> navigator.push(AppRoute.Reports.Detail(reportId)) },
                    )
                    AppContentTarget.REPORTS_DETAIL -> {
                        val reportId = (route as AppRoute.Reports.Detail).reportId
                        ReportDetailScreen(viewModel = koinInject(parameters = { parametersOf(reportId) }))
                    }
                    AppContentTarget.OPOSITESTER_LIST -> TesterListScreen(
                        viewModel = koinInject(),
                        onOpenReport = { reportId -> navigator.push(AppRoute.OposiTester.Detail(reportId)) },
                    )
                    AppContentTarget.OPOSITESTER_DETAIL -> {
                        val testerId = (route as AppRoute.OposiTester.Detail).testerId
                        TesterDetailScreen(viewModel = koinInject(parameters = { parametersOf(testerId) }))
                    }
                    AppContentTarget.EXAM_WORKSPACE -> CompositionLocalProvider(
                        // Reglas de puntuación del examen (Control V1): el editor las pinta solo si alguien las provee.
                        LocalGradingRulesViewModel provides koinInject<ExamGradingRulesViewModel>(),
                    ) {
                        ExamWorkspaceScreen(
                        // El mismo ViewModel que alimenta «Nuevo examen» en la
                        // cabecera; en esta rama nunca es null.
                        viewModel = requireNotNull(examsViewModel),
                        // Vincular una pregunta a un examen es hoy un efecto
                        // lateral de editar la pregunta, y eso se hace en la
                        // pantalla de Preguntas. El enlace es real, no un atajo
                        // decorativo: lleva justo donde se hace.
                        onOpenQuestionBank = { navigator.push(AppRoute.Content.Questions) },
                        )
                    }
                    AppContentTarget.MOBILE_CONTENT_ROOT -> MobileContentRoot(
                        appConfig = appConfig,
                        initialView = route.toMobileContentView(),
                        showNavigationChrome = false,
                    )
                    AppContentTarget.MARKET -> {
                        MarketScreen(
                            viewModel = koinInject(),
                            // El escaparate no edita: «Editar en Catálogo» lleva a la pestaña donde sí se puede.
                            onEditInCatalog = { section -> navigator.push(catalogRouteForMarketSection(section)) },
                        )
                    }
                    AppContentTarget.PRODUCT_ANALYTICS -> ProductAnalyticsScreen(
                        viewModel = koinInject(),
                        initialSection = analyticsSectionFor(route),
                    )
                    // El paso entre la ficha de una persona y su cartera lo resuelve el propio hub: son dos pestañas de
                    // la misma pantalla, no dos secciones.
                    AppContentTarget.USERS_HUB -> UsersHubScreen(initialTab = usersTabFor(route))
                    AppContentTarget.SYSTEM_HUB -> {
                        val koin = getKoin()
                        SystemHubScreen(
                            initialTab = systemTabFor(route),
                            // «Administradores» solo lista: los roles y la baja de una cuenta se cambian en su ficha, que
                            // vive en Usuarios. El ViewModel de Usuarios se pide al pulsar, igual que desde Catálogo.
                            onOpenUser = { userId ->
                                koin.get<UsersViewModel>().dispatch(UsersIntent.SelectUser(userId))
                                navigator.openSection(AdminSection.USERS)
                            },
                        )
                    }
                    AppContentTarget.CATALOG_HUB -> {
                        val koin = getKoin()
                        CatalogHubScreen(
                            initialTab = catalogTabFor(route),
                            // Quien usó una promoción es una persona: su ficha vive en Usuarios y no se duplica aquí.
                            // El ViewModel de Usuarios se pide al pulsar: pedirlo al abrir Catálogo descargaría la lista
                            // entera de usuarios cada vez que alguien mira un precio.
                            onOpenUser = { userId ->
                                koin.get<UsersViewModel>().dispatch(UsersIntent.SelectUser(userId))
                                navigator.openSection(AdminSection.USERS)
                            },
                        )
                    }
                    AppContentTarget.NEWS_HUB -> NewsHubScreen(initialTab = newsTabFor(route))
                    AppContentTarget.MODERATION_HUB -> ModerationHubScreen(
                        initialTab = moderationTabFor(route),
                        onOpenReport = { reportId -> navigator.push(AppRoute.Reports.Detail(reportId)) },
                        onOpenTester = { testerId -> navigator.push(AppRoute.OposiTester.Detail(testerId)) },
                    )
                    AppContentTarget.LEGACY_SECTION -> legacyRoute?.content()
                    AppContentTarget.LEGACY_PLACEHOLDER -> LegacyRouteAdapter(metadata.title, metadata.description)
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
    val legacyRoute = route.toLegacySection()?.let { section ->
        adminSectionRoute(
            section = section,
            environmentLabel = environmentLabel,
            openSection = openSection,
        )
    }
    if (legacyRoute != null) {
        legacyRoute.content()
    } else {
        val metadata = route.metadata()
        LegacyRouteAdapter(metadata.title, metadata.description)
    }
}

/**
 * Lo que se ve en la vista estrecha al llegar a una sección que sólo existe en escritorio (Catálogo, OposiMarket,
 * Analytics, Noticias, Competición…). Ninguna tiene entrada de menú en móvil —la barra inferior sólo ofrece los cinco
 * destinos de [mobilePrimaryNavigation]—, así que sólo se llega aquí estrechando la ventana de escritorio con la sección
 * abierta. Antes decía «preparada para la migración», que prometía una pantalla que nadie está construyendo.
 */
@Composable
private fun LegacyRouteAdapter(title: String, description: String) {
    com.oposicontrol.admin.core.ui.components.EmptyStateCard(
        title = title,
        message = desktopOnlyMessage(description),
    )
}

internal fun desktopOnlyMessage(description: String): String =
    "$description Esta sección solo está disponible en la vista de escritorio: amplía la ventana para abrirla."

/**
 * Grupos del menú lateral. La vista estrecha no pinta menú lateral (sólo la barra inferior), pero el filtro se respeta
 * igual: una entrada DESKTOP_ONLY no se ofrece en compacto y una MOBILE_ONLY no se ofrece en escritorio.
 */
internal fun List<AppRouteMenuGroup>.toShellGroups(compactLayout: Boolean): List<ShellNavigationGroup> = mapNotNull { group ->
    val hidden = if (compactLayout) {
        com.oposicontrol.admin.app.navigation.NavigationVisibility.DESKTOP_ONLY
    } else {
        com.oposicontrol.admin.app.navigation.NavigationVisibility.MOBILE_ONLY
    }
    val items = group.sidebarItems.filter { it.metadata.visibility != hidden }
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
    is AppRoute.Content.ExamDetail -> listOf("Contenido", "Exámenes", examId)
    is AppRoute.Reports.Detail -> listOf("Informes", reportId)
    is AppRoute.Community.Thread -> listOf("Foro", threadId)
    is AppRoute.Community.ModerationDetail -> listOf("Foro", reportId)
    is AppRoute.OposiTester.Detail -> listOf("OposiTester", testerId)
    // Grupo y pantalla, como en Contenido («Contenido > Exámenes»). Si el grupo se llama igual que la pantalla
    // («Usuarios > Usuarios», «Sistema > Sistema») se queda en un solo nivel.
    else -> metadata().let { meta ->
        val group = appNavigationGroups.firstOrNull { it.id == meta.group }?.label
        listOfNotNull(group?.takeIf { it != meta.title }, meta.title)
    }
}

private fun AppRoute.headerTitle(): String = when (this) {
    is AppRoute.Content.ExamDetail -> "Editor de examen"
    is AppRoute.Reports.Detail -> "Informe"
    is AppRoute.Community.Thread -> "Hilo del foro"
    is AppRoute.Community.ModerationDetail -> "Detalle de moderación"
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

/** Si [adminSectionRoute] tiene pantalla para esta sección. Tiene que coincidir con sus ramas `null`. */
internal fun AdminSection.hasLegacyScreen(): Boolean = this !in SECTIONS_WITHOUT_SCREEN

private val SECTIONS_WITHOUT_SCREEN = setOf(AdminSection.ROLES, AdminSection.SETTINGS, AdminSection.STORE, AdminSection.EXAMS)

/**
 * Adaptador de una sección legacy, o `null` si la sección no tiene pantalla propia.
 *
 * `null` (2026-10-15) para las cuatro que sólo sobreviven como DESTINO de navegación y nunca se pintan aquí:
 * `ROLES` y `SETTINGS` (pintaban un cuerpo vacío; abren el hub de Sistema o el `SettingsScreen` del paginador),
 * `STORE` (abre Catálogo; la tienda heredada se retiró) y `EXAMS` (Exámenes abre el workspace en escritorio y el
 * contenido móvil en compacto; la pantalla antigua no la alcanzaba ninguna ruta).
 */
@Composable
private fun adminSectionRoute(
    section: AdminSection,
    environmentLabel: String,
    openSection: (AdminSection) -> Unit,
    onOpenContentUpload: () -> Unit = {},
): AdminSectionRoute? {
    return when (section) {
        // Sólo la vista estrecha llega aquí: Inicio en escritorio es `DESKTOP_DASHBOARD` (Dashboard V2).
        AdminSection.DASHBOARD -> {
            val viewModel: DashboardViewModel = koinInject()
            AdminSectionRoute(
                onPrimaryAction = { viewModel.dispatch(DashboardIntent.Refresh) },
                content = {
                    MobileDashboardScreen(
                        viewModel = viewModel,
                        environmentLabel = environmentLabel,
                        onQuickAction = { action ->
                            if (action == DashboardQuickAction.IMPORT_QUESTIONS) onOpenContentUpload()
                            else handleDashboardAction(action, openSection)
                        },
                    )
                },
            )
        }

        AdminSection.USERS -> {
            val viewModel: UsersViewModel = koinInject()
            val koin = getKoin()
            AdminSectionRoute(
                onPrimaryAction = { viewModel.dispatch(UsersIntent.CreateNew) },
                content = {
                    UsersScreen(
                        viewModel = viewModel,
                        onOpenTickets = { userId ->
                            koin.get<TicketsViewModel>().dispatch(TicketsIntent.OpenWalletOf(userId))
                            openSection(AdminSection.TICKETS)
                        },
                    )
                },
            )
        }

        AdminSection.ROLES,
        AdminSection.SETTINGS,
        AdminSection.STORE,
        AdminSection.EXAMS,
        -> null

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

        AdminSection.QUESTIONS_RESOURCES -> {
            // Preguntas (banco global) tiene su propio módulo/ViewModel desde
            // aquí en adelante — ver diseño aprobado. ExamsViewModel y
            // QuestionsResourcesScreen (features/exams) quedan sin usar en
            // esta rama a propósito: Exámenes permanece intacto, esta pantalla
            // ya no depende de él.
            val viewModel: QuestionsViewModel = koinInject()
            AdminSectionRoute(
                // «Nueva pregunta» estaba cableado a `{}`: el botón de la
                // cabecera existía en `AppDestination` y no hacía absolutamente
                // nada al pulsarlo. Abre el editor en alta, que es lo que promete.
                onPrimaryAction = {
                    viewModel.dispatch(
                        com.oposicontrol.admin.features.questions.presentation.QuestionsIntent.StartCreate,
                    )
                },
                content = { QuestionsScreen(viewModel) },
            )
        }

        AdminSection.EXAM_ACCESS -> {
            val viewModel: ExamAccessViewModel = koinInject()
            AdminSectionRoute(
                onPrimaryAction = { viewModel.dispatch(ExamAccessIntent.CreateNew) },
                content = { ExamAccessScreen(viewModel) },
            )
        }

        AdminSection.TICKETS -> {
            val viewModel: TicketsViewModel = koinInject()
            val koin = getKoin()
            AdminSectionRoute(
                onPrimaryAction = { viewModel.dispatch(TicketsIntent.PrepareAdjustment) },
                content = {
                    TicketsScreen(
                        viewModel = viewModel,
                        // Una cartera es de una persona: su ficha vive en Usuarios y no se duplica aqui. El ViewModel
                        // de Usuarios se pide al pulsar: pedirlo al abrir OposiTickets descargaria la lista entera.
                        onOpenUser = { userId ->
                            koin.get<UsersViewModel>().dispatch(UsersIntent.SelectUser(userId))
                            openSection(AdminSection.USERS)
                        },
                    )
                },
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
        DashboardQuickAction.REVIEW_FORUM_REPORTS -> AdminSection.MODERATION
        DashboardQuickAction.IMPORT_QUESTIONS -> AdminSection.QUESTIONS_RESOURCES
        DashboardQuickAction.CREATE_EXAM -> AdminSection.EXAMS
        DashboardQuickAction.OPEN_USERS -> AdminSection.USERS
        DashboardQuickAction.OPEN_SUPPORT -> AdminSection.SUPPORT
    }
    openSection(target)
}

/**
 * Pestaña de Market con la que abrir según la entrada de menú usada.
 *
 * `null` para la entrada "OposiMarket": ahí se respeta la pestaña en la que
 * estuviera el usuario, que es lo natural al volver a una pantalla que ya
 * estabas usando.
 */
internal fun marketSectionFor(route: AppRoute): MarketSection? = null

/** La ruta de Catálogo donde se edita lo que enseña una sección del escaparate. */
internal fun catalogRouteForMarketSection(section: MarketSection): AppRoute = when (section) {
    MarketSection.SERVICES -> AppRoute.Commercial.Consumables
    MarketSection.PROMOTIONS -> AppRoute.Commercial.Promotions
    MarketSection.POPUPS -> AppRoute.Commercial.PopupRules
    MarketSection.EXAMS, MarketSection.PRODUCTS -> AppRoute.Commercial.Catalog
}

/** Sección de Analytics con la que abrir según la entrada de menú usada. */
internal fun analyticsSectionFor(route: AppRoute): AnalyticsSection? = when (route) {
    AppRoute.Analytics.Performance -> AnalyticsSection.ESTUDIO
    else -> null
}

/**
 * Entrada del menú lateral que se marca para una ruta.
 *
 * Una ruta que no tiene entrada propia —una pestaña de un hub: OposiTickets, Competición, Planes…— marca la del hub que
 * la contiene. Antes no se marcaba ninguna y el menú se quedaba sin selección al entrar por un atajo.
 */
internal fun sidebarRouteIdFor(route: AppRoute, compactLayout: Boolean): String {
    val own = route.metadata()
    if (own.showInSidebar) return own.id
    fun targetOf(candidate: AppRoute) =
        resolveContentTarget(candidate, compactLayout, showTabPager = false, hasLegacyRoute = candidate.toLegacySection()?.hasLegacyScreen() == true)
    val target = targetOf(route)
    return appNavigationGroups.asSequence()
        .filter { it.id == own.group }
        .flatMap { it.sidebarItems.asSequence() }
        .firstOrNull { targetOf(it.route) == target }
        ?.metadata?.id
        ?: own.id
}

/** Pestaña del hub de Usuarios con la que abrir según la entrada de menú usada. */
internal fun usersTabFor(route: AppRoute): UsersHubTab? = when (route) {
    AppRoute.Commercial.Tickets -> UsersHubTab.TICKETS
    AppRoute.Rankings -> UsersHubTab.COMPETITION
    AppRoute.Commercial.Subscriptions -> UsersHubTab.ACCESS
    else -> null
}

/**
 * Pestaña del hub de Sistema con la que abrir según la ruta.
 *
 * Todas las rutas de Sistema fijan pestaña (ninguna devuelve `null` salvo las que no son de Sistema): la entrada del menú
 * —`Settings`— abre Estado, que es la pestaña por defecto ahora que «Preferencias» no existe; `Roles` abre
 * Administradores, que es donde quedó lo que tenía de real «Seguridad»; y `Status` —la pantalla suelta de la vista
 * estrecha— en escritorio es la misma pestaña Estado, no una segunda pantalla.
 */
internal fun systemTabFor(route: AppRoute): SystemHubTab? = when (route) {
    AppRoute.System.Settings,
    AppRoute.System.Diagnostics,
    AppRoute.System.Status,
    -> SystemHubTab.STATUS
    AppRoute.System.Roles -> SystemHubTab.ADMINS
    AppRoute.System.Audit -> SystemHubTab.AUDIT
    else -> null
}

/** Pestaña del hub de Catálogo con la que abrir según la entrada de menú usada. */
internal fun catalogTabFor(route: AppRoute): CatalogHubTab? = when (route) {
    AppRoute.Commercial.Plans -> CatalogHubTab.PLANS
    AppRoute.Commercial.Consumables -> CatalogHubTab.SERVICES
    AppRoute.Commercial.Promotions -> CatalogHubTab.PROMOTIONS
    AppRoute.Commercial.PopupRules -> CatalogHubTab.POPUPS
    else -> null
}

/** Pestaña del hub de Noticias con la que abrir según la entrada de menú usada. */
internal fun newsTabFor(route: AppRoute): NewsHubTab? = when (route) {
    AppRoute.Publication.NewsTypes -> NewsHubTab.TYPES
    else -> null
}

/** Pestaña del hub de Moderación con la que abrir según la entrada de menú usada. */
internal fun moderationTabFor(route: AppRoute): ModerationHubTab? = when (route) {
    is AppRoute.Reports.List -> ModerationHubTab.REPORTS
    is AppRoute.OposiTester.List -> ModerationHubTab.OPOSITESTER
    else -> null
}
