package com.oposicontrol.admin.app.root

import com.oposicontrol.admin.app.navigation.AppRoute
import com.oposicontrol.admin.features.dashboard.presentation.DashboardV2Destination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.oposicontrol.admin.features.store.presentation.MarketSection

class AppRootContentTargetTest {

    // --- Inicio: panel nuevo en escritorio, camino móvil intacto -------------

    @Test
    fun desktopHomeOpensTheNewDashboard() {
        assertEquals(
            AppContentTarget.DESKTOP_DASHBOARD,
            resolveContentTarget(AppRoute.Home, compactLayout = false, showTabPager = false, hasLegacyRoute = true),
        )
    }

    @Test
    fun compactHomeKeepsTheLegacyMobileDashboard() {
        // La rama que NO puede cambiar: en compacto, Inicio sigue cayendo al
        // adaptador de AdminSection.DASHBOARD, que pinta MobileDashboardScreen
        // sobre el DashboardViewModel actual. El panel de escritorio es una
        // pantalla densa de cinco franjas y no cabe en un móvil.
        assertEquals(
            AppContentTarget.LEGACY_SECTION,
            resolveContentTarget(AppRoute.Home, compactLayout = true, showTabPager = false, hasLegacyRoute = true),
        )
    }

    @Test
    fun theMobileTabPagerStillWinsOverTheDesktopDashboard() {
        // La navegación principal de móvil no puede quedar secuestrada por una
        // pantalla de escritorio.
        assertEquals(
            AppContentTarget.TAB_PAGER,
            resolveContentTarget(AppRoute.Home, compactLayout = true, showTabPager = true, hasLegacyRoute = true),
        )
    }

    // --- Destinos del panel: todos AppRoute, ninguno inventado ---------------

    @Test
    fun everyDashboardShortcutResolvesToItsVerifiedRoute() {
        assertEquals(AppRoute.System.Diagnostics, DashboardV2Destination.SYSTEM_DIAGNOSTICS.toAppRoute())
        assertEquals(AppRoute.Moderation.Queue, DashboardV2Destination.MODERATION_QUEUE.toAppRoute())
        assertEquals(AppRoute.OposiTester.List, DashboardV2Destination.OPOSITESTER_LIST.toAppRoute())
        assertEquals(AppRoute.Content.Exams, DashboardV2Destination.EXAMS.toAppRoute())
        assertEquals(AppRoute.Content.Questions, DashboardV2Destination.QUESTIONS.toAppRoute())
        assertEquals(AppRoute.Content.Imports, DashboardV2Destination.IMPORTS.toAppRoute())
        assertEquals(AppRoute.Users.List, DashboardV2Destination.USERS.toAppRoute())
        assertEquals(AppRoute.Analytics.Home, DashboardV2Destination.ANALYTICS.toAppRoute())
        assertEquals(AppRoute.Commercial.Subscriptions, DashboardV2Destination.SUBSCRIPTIONS.toAppRoute())
        assertEquals(AppRoute.System.Audit, DashboardV2Destination.AUDIT.toAppRoute())
        assertEquals(AppRoute.Content.Exams, DashboardV2Destination.NEW_EXAM.toAppRoute())
    }

    /**
     * PC-15: el atajo de examen nuevo navega a Exámenes Y pide el borrador en blanco,
     * con el mismo intent que el botón «Nuevo examen» del workspace. Navegar sin
     * pedirlo dejaría al administrador en el catálogo con el último examen abierto.
     */
    @Test
    fun theNewExamShortcutOpensABlankDraftAndOnlyThatShortcutDoes() {
        DashboardV2Destination.entries.forEach { destination ->
            val navigated = mutableListOf<AppRoute>()
            var blankDrafts = 0

            openDashboardDestination(
                destination = destination,
                navigate = { navigated += it },
                openBlankExamDraft = { blankDrafts++ },
            )

            assertEquals(listOf(destination.toAppRoute()), navigated, "$destination tiene que navegar una vez")
            assertEquals(
                if (destination == DashboardV2Destination.NEW_EXAM) 1 else 0,
                blankDrafts,
                "$destination y el borrador en blanco",
            )
        }
    }

    @Test
    fun theSystemStatusShortcutAvoidsTheMobileOnlyRoute() {
        // AppRoute.System.Status está marcada MOBILE_ONLY en el catálogo: usarla
        // desde el panel de escritorio abriría una pantalla que allí no existe
        // como destino de menú.
        assertNotEquals(AppRoute.System.Status, DashboardV2Destination.SYSTEM_DIAGNOSTICS.toAppRoute())
    }

    @Test
    fun everyDashboardShortcutLandsOnAReachableDesktopTarget() {
        // Un atajo que aterrizara en LEGACY_PLACEHOLDER sería un enlace a una
        // pantalla en blanco. `hasLegacyRoute = true` porque varias de estas
        // rutas conservan mapeo legacy para su versión móvil.
        DashboardV2Destination.entries.forEach { destination ->
            val target = resolveContentTarget(
                destination.toAppRoute(),
                compactLayout = false,
                showTabPager = false,
                hasLegacyRoute = true,
            )
            assertNotEquals(
                AppContentTarget.LEGACY_PLACEHOLDER,
                target,
                "El atajo $destination no llega a ninguna pantalla real.",
            )
        }
    }
    @Test
    fun desktopOpensReportsListInsideTheModerationHub() {
        // Antes REPORTS_LIST (pantalla propia); ahora Reportes es una pestaña
        // del hub de Moderación en escritorio (§11) — sigue siendo destino
        // propio de la barra inferior en móvil, ver mobileReportsListStaysItsOwnTarget.
        assertEquals(
            AppContentTarget.MODERATION_HUB,
            resolveContentTarget(AppRoute.Reports.List, compactLayout = false, showTabPager = false, hasLegacyRoute = false),
        )
    }

    @Test
    fun mobileReportsListStaysItsOwnTarget() {
        // En compacto, fuera del pager (showTabPager=false: p.ej. alcanzado por
        // push en vez de por selectPrimaryTab), Reportes sigue resolviendo a su
        // propia pantalla, no al hub de escritorio.
        assertEquals(
            AppContentTarget.REPORTS_LIST,
            resolveContentTarget(AppRoute.Reports.List, compactLayout = true, showTabPager = false, hasLegacyRoute = false),
        )
    }

    @Test
    fun desktopReachesReportsDetailWithoutCompactLayout() {
        assertEquals(
            AppContentTarget.REPORTS_DETAIL,
            resolveContentTarget(AppRoute.Reports.Detail("R-1"), compactLayout = false, showTabPager = false, hasLegacyRoute = false),
        )
    }

    @Test
    fun desktopReachesForumWithoutCompactLayout() {
        assertEquals(
            AppContentTarget.FORUM,
            resolveContentTarget(AppRoute.Community.OposiRed, compactLayout = false, showTabPager = false, hasLegacyRoute = false),
        )
    }

    @Test
    fun desktopReachesThreadDetailWithoutCompactLayout() {
        assertEquals(
            AppContentTarget.THREAD_DETAIL,
            resolveContentTarget(AppRoute.Community.Thread("T-1"), compactLayout = false, showTabPager = false, hasLegacyRoute = false),
        )
    }

    @Test
    fun desktopOpensOpositesterListInsideTheModerationHub() {
        assertEquals(
            AppContentTarget.MODERATION_HUB,
            resolveContentTarget(AppRoute.OposiTester.List, compactLayout = false, showTabPager = false, hasLegacyRoute = false),
        )
    }

    @Test
    fun mobileOpositesterListStaysItsOwnTarget() {
        assertEquals(
            AppContentTarget.OPOSITESTER_LIST,
            resolveContentTarget(AppRoute.OposiTester.List, compactLayout = true, showTabPager = false, hasLegacyRoute = false),
        )
    }

    @Test
    fun desktopOpensModerationQueueInsideItsOwnHub() {
        assertEquals(
            AppContentTarget.MODERATION_HUB,
            resolveContentTarget(AppRoute.Moderation.Queue, compactLayout = false, showTabPager = false, hasLegacyRoute = true),
        )
    }

    @Test
    fun reportsListOpensTheReportsTabOfTheModerationHub() {
        assertEquals(ModerationHubTab.REPORTS, moderationTabFor(AppRoute.Reports.List))
    }

    @Test
    fun opositesterListOpensTheOpositesterTabOfTheModerationHub() {
        assertEquals(ModerationHubTab.OPOSITESTER, moderationTabFor(AppRoute.OposiTester.List))
    }

    @Test
    fun moderationQueueLeavesTheHubOnItsDefaultTab() {
        assertEquals(null, moderationTabFor(AppRoute.Moderation.Queue))
    }

    @Test
    fun desktopReachesOpositesterDetailWithoutCompactLayout() {
        assertEquals(
            AppContentTarget.OPOSITESTER_DETAIL,
            resolveContentTarget(AppRoute.OposiTester.Detail("OT-1"), compactLayout = false, showTabPager = false, hasLegacyRoute = false),
        )
    }

    @Test
    fun desktopReachesContentUploadWithoutCompactLayout() {
        assertEquals(
            AppContentTarget.CONTENT_UPLOAD,
            resolveContentTarget(AppRoute.Content.Imports, compactLayout = false, showTabPager = false, hasLegacyRoute = false),
        )
    }

    @Test
    fun desktopReachesSystemStatusWithoutCompactLayout() {
        assertEquals(
            AppContentTarget.SYSTEM_STATUS,
            resolveContentTarget(AppRoute.System.Status, compactLayout = false, showTabPager = false, hasLegacyRoute = false),
        )
    }

    @Test
    fun mobileTabPagerTakesPriorityWhenActiveEvenForAPrimaryTabRoute() {
        assertEquals(
            AppContentTarget.TAB_PAGER,
            resolveContentTarget(AppRoute.Reports.List, compactLayout = true, showTabPager = true, hasLegacyRoute = false),
        )
    }

    @Test
    fun mobileCompactContentRouteWithoutTabPagerUsesMobileContentRoot() {
        assertEquals(
            AppContentTarget.MOBILE_CONTENT_ROOT,
            resolveContentTarget(AppRoute.Content.Exams, compactLayout = true, showTabPager = false, hasLegacyRoute = true),
        )
    }

    @Test
    fun desktopReachesTheUsersHub() {
        // Antes caía al adaptador legacy — ahora Usuarios es un hub propio con
        // Competición y Accesos como pestañas.
        assertEquals(
            AppContentTarget.USERS_HUB,
            resolveContentTarget(AppRoute.Users.List, compactLayout = false, showTabPager = false, hasLegacyRoute = true),
        )
    }

    @Test
    fun ticketsOpenTheUsersHubOnTheirOwnTab() {
        // OposiTickets es una subpantalla de Usuarios: su ruta abre el hub, en la pestaña de carteras.
        assertEquals(
            AppContentTarget.USERS_HUB,
            resolveContentTarget(AppRoute.Commercial.Tickets, compactLayout = false, showTabPager = false, hasLegacyRoute = true),
        )
        assertEquals(UsersHubTab.TICKETS, usersTabFor(AppRoute.Commercial.Tickets))
        assertEquals(null, usersTabFor(AppRoute.Users.List))
        // Las pestañas van en el orden en que se piensan: la persona y, justo después, su cartera.
        assertEquals(listOf(UsersHubTab.USERS, UsersHubTab.TICKETS), UsersHubTab.entries.take(2))
        // En compacto no hay hub: sigue su adaptador de siempre.
        assertEquals(
            AppContentTarget.LEGACY_SECTION,
            resolveContentTarget(AppRoute.Commercial.Tickets, compactLayout = true, showTabPager = false, hasLegacyRoute = true),
        )
    }

    @Test
    fun aHubTabMarksItsHubInTheSidebar() {
        // Una ruta sin entrada propia marca la del hub que la contiene; antes el menú se quedaba sin selección.
        assertEquals("users", sidebarRouteIdFor(AppRoute.Users.List, compactLayout = false))
        assertEquals("users", sidebarRouteIdFor(AppRoute.Commercial.Tickets, compactLayout = false))
        assertEquals("users", sidebarRouteIdFor(AppRoute.Rankings, compactLayout = false))
        assertEquals("commercial.catalog", sidebarRouteIdFor(AppRoute.Commercial.Plans, compactLayout = false))
        // Promociones se EDITA en Catálogo desde 2026-10-14: OposiMarket es sólo el escaparate.
        assertEquals("commercial.catalog", sidebarRouteIdFor(AppRoute.Commercial.Promotions, compactLayout = false))
        assertEquals("commercial.market", sidebarRouteIdFor(AppRoute.Commercial.Market, compactLayout = false))
        // Una ruta con entrada propia se marca a sí misma.
        assertEquals("commercial.catalog", sidebarRouteIdFor(AppRoute.Commercial.Catalog, compactLayout = false))
    }

    @Test
    fun compactLayoutKeepsTheLegacyUsersSectionInsteadOfTheHub() {
        assertEquals(
            AppContentTarget.LEGACY_SECTION,
            resolveContentTarget(AppRoute.Users.List, compactLayout = true, showTabPager = false, hasLegacyRoute = true),
        )
    }

    @Test
    fun rankingsAndSubscriptionsOpenTheUsersHubTooNotTheirOwnScreen() {
        // Antes Rankings tenía su propio AppContentTarget.RANKINGS; ahora abre
        // el mismo hub que Usuarios, en la pestaña Competición.
        assertEquals(
            AppContentTarget.USERS_HUB,
            resolveContentTarget(AppRoute.Rankings, compactLayout = false, showTabPager = false, hasLegacyRoute = false),
        )
        assertEquals(
            AppContentTarget.USERS_HUB,
            resolveContentTarget(AppRoute.Commercial.Subscriptions, compactLayout = false, showTabPager = false, hasLegacyRoute = true),
        )
    }

    @Test
    fun desktopReachesTheSystemHub() {
        // Estado (System.Diagnostics) no tenía ningún destino de contenido antes
        // de la consolidación y caía al marcador de posición. Ahora abre el hub
        // de Sistema en su pestaña real.
        assertEquals(
            AppContentTarget.SYSTEM_HUB,
            resolveContentTarget(AppRoute.System.Diagnostics, compactLayout = false, showTabPager = false, hasLegacyRoute = false),
        )
        assertEquals(
            AppContentTarget.SYSTEM_HUB,
            resolveContentTarget(AppRoute.System.Settings, compactLayout = false, showTabPager = false, hasLegacyRoute = true),
        )
        assertEquals(
            AppContentTarget.SYSTEM_HUB,
            resolveContentTarget(AppRoute.System.Roles, compactLayout = false, showTabPager = false, hasLegacyRoute = true),
        )
        assertEquals(
            AppContentTarget.SYSTEM_HUB,
            resolveContentTarget(AppRoute.System.Audit, compactLayout = false, showTabPager = false, hasLegacyRoute = true),
        )
    }

    @Test
    fun desktopReachesTheCatalogHub() {
        assertEquals(
            AppContentTarget.CATALOG_HUB,
            resolveContentTarget(
                AppRoute.Commercial.Catalog,
                compactLayout = false,
                showTabPager = false,
                // La ruta tiene mapeo legacy (AdminSection.STORE); el catálogo
                // canónico debe ganarle, o el escritorio seguiría abriendo la
                // pantalla de tienda antigua.
                hasLegacyRoute = true,
            ),
        )
        // Planes abre el mismo hub, en su pestaña — antes caía al adaptador
        // legacy y era una pantalla distinta a Catálogo.
        assertEquals(
            AppContentTarget.CATALOG_HUB,
            resolveContentTarget(AppRoute.Commercial.Plans, compactLayout = false, showTabPager = false, hasLegacyRoute = true),
        )
    }

    @Test
    fun compactLayoutKeepsTheLegacyStoreInsteadOfTheDesktopCatalog() {
        // El catálogo es una vista densa de escritorio (tabla + formulario). En
        // compacto se prefiere lo que ya existía a servir media pantalla.
        assertEquals(
            AppContentTarget.LEGACY_SECTION,
            resolveContentTarget(
                AppRoute.Commercial.Catalog,
                compactLayout = true,
                showTabPager = false,
                hasLegacyRoute = true,
            ),
        )
    }

    @Test
    fun theTabPagerStillWinsOverTheCatalogHub() {
        // La navegación principal de móvil no puede quedar secuestrada por una
        // pantalla de escritorio.
        assertEquals(
            AppContentTarget.TAB_PAGER,
            resolveContentTarget(
                AppRoute.Commercial.Catalog,
                compactLayout = false,
                showTabPager = true,
                hasLegacyRoute = true,
            ),
        )
    }

    @Test
    fun desktopReachesTheNewsHub() {
        assertEquals(
            AppContentTarget.NEWS_HUB,
            resolveContentTarget(AppRoute.Publication.News, compactLayout = false, showTabPager = false, hasLegacyRoute = false),
        )
        // Tipos de noticia y Assets abren el mismo hub — antes eran pantallas
        // sueltas sin destino de contenido propio.
        assertEquals(
            AppContentTarget.NEWS_HUB,
            resolveContentTarget(AppRoute.Publication.NewsTypes, compactLayout = false, showTabPager = false, hasLegacyRoute = false),
        )
        assertEquals(
            AppContentTarget.NEWS_HUB,
            resolveContentTarget(AppRoute.Publication.Assets, compactLayout = false, showTabPager = false, hasLegacyRoute = false),
        )
    }

    @Test
    fun analyticsPerformanceAndEventsOpenProductAnalyticsInsteadOfFallingToDashboard() {
        // Antes Estudio y Eventos caían al adaptador legacy de
        // AdminSection.DASHBOARD, que no tenía nada que ver con analítica.
        assertEquals(
            AppContentTarget.PRODUCT_ANALYTICS,
            resolveContentTarget(AppRoute.Analytics.Performance, compactLayout = false, showTabPager = false, hasLegacyRoute = true),
        )
        assertEquals(
            AppContentTarget.PRODUCT_ANALYTICS,
            resolveContentTarget(AppRoute.Analytics.Events, compactLayout = false, showTabPager = false, hasLegacyRoute = true),
        )
    }

    @Test
    fun desktopReachesTheMarket() {
        assertEquals(
            AppContentTarget.MARKET,
            resolveContentTarget(
                AppRoute.Commercial.Market,
                compactLayout = false,
                showTabPager = false,
                hasLegacyRoute = false,
            ),
        )
    }

    @Test
    fun compactLayoutDoesNotOpenTheMarket() {
        // Cuatro tablas densas no caben en una pantalla de móvil; se prefiere el
        // marcador de posición a servir media pantalla.
        assertEquals(
            AppContentTarget.LEGACY_PLACEHOLDER,
            resolveContentTarget(
                AppRoute.Commercial.Market,
                compactLayout = true,
                showTabPager = false,
                hasLegacyRoute = false,
            ),
        )
    }

    // --- Qué pestaña exacta abre cada ruta legacy dentro de su hub nuevo -----
    // resolveContentTarget sólo dice "abre el hub de Usuarios"; estas prueban
    // la parte que de verdad importa para el §21: CON QUÉ PESTAÑA.

    @Test
    fun rankingsOpensTheCompetitionTabOfTheUsersHub() {
        assertEquals(UsersHubTab.COMPETITION, usersTabFor(AppRoute.Rankings))
    }

    @Test
    fun subscriptionsOpensTheAccessTabOfTheUsersHub() {
        assertEquals(UsersHubTab.ACCESS, usersTabFor(AppRoute.Commercial.Subscriptions))
    }

    @Test
    fun usersListLeavesTheHubOnItsDefaultTab() {
        assertEquals(null, usersTabFor(AppRoute.Users.List))
    }

    @Test
    fun rolesOpensTheSecurityTabOfTheSystemHub() {
        assertEquals(SystemHubTab.SECURITY, systemTabFor(AppRoute.System.Roles))
    }

    @Test
    fun diagnosticsOpensTheStatusTabOfTheSystemHub() {
        assertEquals(SystemHubTab.STATUS, systemTabFor(AppRoute.System.Diagnostics))
    }

    @Test
    fun auditOpensTheAuditTabOfTheSystemHub() {
        assertEquals(SystemHubTab.AUDIT, systemTabFor(AppRoute.System.Audit))
    }

    @Test
    fun plansOpensThePlansTabOfTheCatalogHub() {
        assertEquals(CatalogHubTab.PLANS, catalogTabFor(AppRoute.Commercial.Plans))
    }

    @Test
    fun whatTheStoreSellsIsEditedInTheCatalogHubAndTheStoreOnlyShowsIt() {
        // Las tres entradas que antes abrían OposiMarket abren ahora Catálogo, cada una en su pestaña.
        assertEquals(CatalogHubTab.SERVICES, catalogTabFor(AppRoute.Commercial.Consumables))
        assertEquals(CatalogHubTab.PROMOTIONS, catalogTabFor(AppRoute.Commercial.Promotions))
        assertEquals(CatalogHubTab.POPUPS, catalogTabFor(AppRoute.Commercial.PopupRules))
        listOf(AppRoute.Commercial.Consumables, AppRoute.Commercial.Promotions, AppRoute.Commercial.PopupRules).forEach { route ->
            assertEquals(null, marketSectionFor(route), "$route ya no abre una pestaña del escaparate")
        }
        // Y cada pestaña del escaparate sabe a dónde ir para editar; «Editar en Catálogo» vuelve a la misma pestaña.
        listOf(MarketSection.SERVICES, MarketSection.PROMOTIONS, MarketSection.POPUPS).forEach { section ->
            assertEquals(catalogTabForMarketSection(section), catalogTabFor(catalogRouteForMarketSection(section)), section.name)
        }
        assertEquals(CatalogHubTab.PRODUCTS, catalogTabForMarketSection(MarketSection.PRODUCTS))
        // Un pack es un producto: no tiene pestaña propia, ni en Catálogo ni en el escaparate.
        assertEquals(CatalogHubTab.PRODUCTS, catalogTabForMarketSection(MarketSection.PACKS))
        assertEquals(listOf("Productos", "Planes", "Servicios", "Promociones", "Avisos"), CatalogHubTab.entries.map { it.label })
    }

    @Test
    fun newsTypesOpensTheTypesTabOfTheNewsHub() {
        assertEquals(NewsHubTab.TYPES, newsTabFor(AppRoute.Publication.NewsTypes))
    }

    @Test
    fun assetsLeavesTheNewsHubOnItsDefaultNewsTab() {
        // No tiene pantalla propia: aterriza en Noticias, igual que antes.
        assertEquals(null, newsTabFor(AppRoute.Publication.Assets))
    }

    @Test
    fun performanceMapsToTheEstudioAnalyticsSection() {
        assertEquals(com.oposicontrol.admin.domain.model.AnalyticsSection.ESTUDIO, analyticsSectionFor(AppRoute.Analytics.Performance))
    }

    @Test
    fun eventsMapsToTheProductoAnalyticsSection() {
        assertEquals(com.oposicontrol.admin.domain.model.AnalyticsSection.PRODUCTO, analyticsSectionFor(AppRoute.Analytics.Events))
    }

    // --- Cabecera del panel de escritorio ------------------------------------

    @Test
    fun elPanelDeEscritorioNoDibujaLaBusquedaGlobal() {
        // El defecto que cierra: `globalSearch` se pasaba a `AppShell` con la
        // única condición de que la ruta tuviera sección legacy, e Inicio la
        // tiene. Como el repositorio de búsqueda devuelve `emptyList()` de forma
        // incondicional, el panel arrancaba con un buscador que jamás encuentra
        // nada por encima de «Estado del sistema».
        assertFalse(
            showsGlobalSearch(
                target = AppContentTarget.DESKTOP_DASHBOARD,
                compactLayout = false,
                hasLegacySection = true,
            ),
        )
    }

    @Test
    fun elRestoDeSeccionesDeEscritorioLaConservan() {
        // La retirada es quirúrgica: si se hubiera borrado el componente o el
        // repositorio, esto también daría false y nadie se enteraría hasta que
        // alguien arreglase la búsqueda y no apareciera por ningún lado.
        assertTrue(
            showsGlobalSearch(
                target = AppContentTarget.LEGACY_SECTION,
                compactLayout = false,
                hasLegacySection = true,
            ),
        )
    }

    @Test
    fun usuariosNoDibujaLaBusquedaGlobalPorqueTieneLaSuya() {
        // Mismo criterio que el panel de escritorio y el workspace de Exámenes: el
        // catálogo de Usuarios busca de verdad por nombre y email, y encima
        // quedaba la global contestando siempre «Sin resultados».
        assertFalse(
            showsGlobalSearch(
                target = AppContentTarget.USERS_HUB,
                compactLayout = false,
                hasLegacySection = true,
            ),
        )
    }

    @Test
    fun preguntasNoDibujaLaBusquedaGlobalPorqueTieneLaSuya() {
        // Mismo criterio que el workspace de Exámenes, que ya estaba excluido: el
        // banco de Preguntas busca de verdad sobre código y enunciado, y encima
        // quedaba la global contestando siempre «Sin resultados». Dos cajas
        // equivalentes, y la de arriba enseñando a desconfiar de la que funciona.
        assertFalse(
            showsGlobalSearch(
                target = AppContentTarget.LEGACY_SECTION,
                compactLayout = false,
                hasLegacySection = true,
                sectionHasOwnSearch = true,
            ),
        )
    }

    @Test
    fun enCompactoNoHayBusquedaGlobalEnNingunaPantalla() {
        AppContentTarget.entries.forEach { target ->
            assertFalse(
                showsGlobalSearch(target = target, compactLayout = true, hasLegacySection = true),
                "«$target» dibujaría la búsqueda global en móvil.",
            )
        }
    }

    @Test
    fun elPanelDeEscritorioTieneSuPropiaAccionPrimaria() {
        // Antes sólo se rellenaba para LEGACY_SECTION, así que el panel nuevo se
        // quedaba sin acción primaria: recargar existía únicamente por franja y
        // escondido dentro del estado de error de cada una.
        assertEquals(
            "Actualizar",
            primaryActionLabelFor(AppContentTarget.DESKTOP_DASHBOARD, legacyLabel = null),
        )
    }

    @Test
    fun laAccionPrimariaDelPanelNoLaPuedePisarElAdaptadorLegacy() {
        // Inicio tiene `legacySection` no nulo por su versión móvil. Si esta
        // función mirase primero al adaptador, el botón del panel diría lo que
        // diga el dashboard heredado y dispararía sobre su ViewModel.
        assertEquals(
            "Actualizar",
            primaryActionLabelFor(AppContentTarget.DESKTOP_DASHBOARD, legacyLabel = "Refrescar"),
        )
    }

    @Test
    fun unHubSinAccionUnicaSigueSinBotonPrimario() {
        listOf(
            AppContentTarget.USERS_HUB,
            AppContentTarget.SYSTEM_HUB,
            AppContentTarget.MODERATION_HUB,
            AppContentTarget.CATALOG_HUB,
        ).forEach { target ->
            assertNull(
                primaryActionLabelFor(target, legacyLabel = "Crear"),
                "«$target» ofrecería la acción primaria de un adaptador que no pinta.",
            )
        }
    }

    @Test
    fun unaSeccionLegacyConserva_laSuya() {
        assertEquals(
            "Crear usuario",
            primaryActionLabelFor(AppContentTarget.LEGACY_SECTION, legacyLabel = "Crear usuario"),
        )
        assertNull(primaryActionLabelFor(AppContentTarget.LEGACY_SECTION, legacyLabel = null))
    }

    // --- Exámenes: workspace en escritorio, camino móvil intacto -------------

    @Test
    fun examenesEnEscritorioAbreElWorkspaceNuevo() {
        assertEquals(
            AppContentTarget.EXAM_WORKSPACE,
            resolveContentTarget(
                AppRoute.Content.Exams,
                compactLayout = false,
                showTabPager = false,
                hasLegacyRoute = true,
            ),
        )
    }

    @Test
    fun elDetalleDeExamenEntraPorLaMismaPuertaQueElCatalogo() {
        // En el workspace, el detalle no es un destino aparte: es la columna
        // central de la misma pantalla.
        assertEquals(
            AppContentTarget.EXAM_WORKSPACE,
            resolveContentTarget(
                AppRoute.Content.ExamDetail("EX-123"),
                compactLayout = false,
                showTabPager = false,
                hasLegacyRoute = true,
            ),
        )
    }

    @Test
    fun enCompactoExamenesSigueCayendoEnElContenidoMovil() {
        // La rama que NO puede cambiar: el workspace es una pantalla de tres
        // columnas y no cabe en un móvil.
        listOf<AppRoute>(AppRoute.Content.Exams, AppRoute.Content.ExamDetail("EX-123")).forEach { route ->
            assertEquals(
                AppContentTarget.MOBILE_CONTENT_ROOT,
                resolveContentTarget(route, compactLayout = true, showTabPager = false, hasLegacyRoute = true),
                "«$route» en compacto debe seguir abriendo el contenido móvil.",
            )
        }
    }

    @Test
    fun importacionesNoQuedaAtrapadaPorElWorkspaceDeExamenes() {
        // `Content.Imports` es también una ruta de Contenido y se resuelve antes:
        // si el guard de Exámenes se colocara mal, se llevaría por delante la
        // pantalla de importación.
        assertEquals(
            AppContentTarget.CONTENT_UPLOAD,
            resolveContentTarget(
                AppRoute.Content.Imports,
                compactLayout = false,
                showTabPager = false,
                hasLegacyRoute = true,
            ),
        )
    }

    @Test
    fun elWorkspaceDeExamenesNoDibujaLaBusquedaGlobal() {
        // Tiene buscador local propio —y ése sí encuentra—. Dos campos de
        // búsqueda uno sobre otro, con el de arriba contestando siempre «Sin
        // resultados», enseñan a desconfiar del que funciona.
        assertFalse(
            showsGlobalSearch(
                target = AppContentTarget.EXAM_WORKSPACE,
                compactLayout = false,
                hasLegacySection = true,
            ),
        )
    }

    @Test
    fun laAccionDePaginaDelWorkspaceDeExamenesSeLlamaNuevoExamen() {
        // Abre un borrador en blanco y no crea nada, así que no puede llamarse
        // igual que el botón de la barra de guardado —«Crear examen»—, que sí lo
        // persiste: dos acciones coral con la misma etiqueta y efectos distintos
        // en la misma pantalla. Y no toma prestada la etiqueta del adaptador.
        assertEquals(
            "Nuevo examen",
            primaryActionLabelFor(AppContentTarget.EXAM_WORKSPACE, legacyLabel = null),
        )
        assertEquals(
            "Nuevo examen",
            primaryActionLabelFor(AppContentTarget.EXAM_WORKSPACE, legacyLabel = "Otra cosa"),
        )
    }
}
