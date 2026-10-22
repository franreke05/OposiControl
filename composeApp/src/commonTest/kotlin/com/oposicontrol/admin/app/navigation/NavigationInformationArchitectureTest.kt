package com.oposicontrol.admin.app.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La agrupación del menú se reorganizó para seguir cómo se administra el producto
 * (Economía junta planes, tickets, packs y Market) en vez de cómo estaba
 * organizado el código.
 *
 * Reagrupar es exactamente el tipo de cambio que pierde una pantalla sin que
 * nadie lo note: la entrada desaparece del sidebar y la ruta sigue existiendo,
 * así que nada falla al compilar. Estas pruebas fijan que no se pierda ninguna.
 */
class NavigationInformationArchitectureTest {

    private val allItems = appNavigationGroups.flatMap { it.items }

    @Test
    fun everyNavigableRouteStillHasAMenuEntry() {
        // Lista explícita: si alguien añade una ruta y no la coloca en un grupo,
        // este test no la detecta, pero si alguien BORRA una entrada al
        // reorganizar, salta aquí.
        val expected = listOf(
            AppRoute.Home,
            AppRoute.Content.Taxonomy,
            AppRoute.Content.Tags,
            AppRoute.Content.Exams,
            AppRoute.Content.Questions,
            AppRoute.Content.Resources,
            AppRoute.Content.Imports,
            AppRoute.Users.List,
            AppRoute.Rankings,
            AppRoute.Commercial.Plans,
            AppRoute.Commercial.Catalog,
            AppRoute.Commercial.Tickets,
            AppRoute.Commercial.Market,
            AppRoute.Commercial.Subscriptions,
            AppRoute.Community.OposiRed,
            AppRoute.Moderation.Queue,
            AppRoute.Reports.List,
            AppRoute.OposiTester.List,
            AppRoute.Analytics.Home,
            AppRoute.Analytics.Performance,
            AppRoute.Publication.News,
            AppRoute.Publication.NewsTypes,
            AppRoute.Support.Inbox,
            AppRoute.System.Audit,
            AppRoute.System.Roles,
            AppRoute.System.Settings,
            AppRoute.System.Diagnostics,
        )

        val missing = expected.filterNot { route -> allItems.any { it.route == route } }
        assertTrue(missing.isEmpty(), "Rutas sin entrada de menú tras reorganizar: $missing")
    }

    @Test
    fun theMobileBottomBarIsUnchangedByTheDesktopRegrouping() {
        // El móvil responde a otra tarea (supervisión rápida). Reorganizar el
        // escritorio no debe moverle la barra inferior, y por eso se selecciona
        // por ruta y no por grupo.
        assertEquals(
            listOf(
                AppRoute.Home,
                AppRoute.Reports.List,
                AppRoute.Community.OposiRed,
                AppRoute.OposiTester.List,
                AppRoute.System.Settings,
            ),
            mobilePrimaryNavigation.map { it.route },
        )
    }

    @Test
    fun reportsAndOpositesterAreNoLongerTopLevelDesktopGroupsButRemainReachable() {
        // Eran grupos propios con un solo elemento cada uno; ahora son trabajo de
        // comunidad. Lo que importa es que sigan alcanzables.
        val communityRoutes = appNavigationGroups
            .single { it.id == NavigationGroupId.COMMUNITY }
            .items.map { it.route }

        assertTrue(AppRoute.Reports.List in communityRoutes)
        assertTrue(AppRoute.OposiTester.List in communityRoutes)
    }

    @Test
    fun economyGroupsEverythingSomeoneAdjustingPricesNeeds() {
        val economyRoutes = appNavigationGroups
            .single { it.id == NavigationGroupId.ECONOMY }
            .items.map { it.route }

        assertTrue(AppRoute.Commercial.Plans in economyRoutes)
        assertTrue(AppRoute.Commercial.Catalog in economyRoutes)
        assertTrue(AppRoute.Commercial.Market in economyRoutes)
    }

    @Test
    fun ticketWalletsAreASubscreenOfUsers() {
        // Decisión del propietario (2026-10-11): OposiTickets es una SUBPANTALLA de Usuarios, no una entrada del menú.
        // La ruta sigue siendo la comercial —el dominio y el backend no se mueven— y abre el hub de Usuarios en su pestaña.
        val users = appNavigationGroups.single { it.id == NavigationGroupId.USERS }
        val economy = appNavigationGroups.single { it.id == NavigationGroupId.ECONOMY }

        assertEquals(
            listOf(AppRoute.Users.List),
            users.sidebarItems.map { it.route },
            "El grupo Usuarios enseña una sola entrada: OposiTickets vive dentro de ella.",
        )
        assertTrue(AppRoute.Commercial.Tickets in users.items.map { it.route }, "La ruta sigue registrada, sin entrada de menú.")
        assertEquals(
            listOf(AppRoute.Commercial.Catalog, AppRoute.Commercial.Market),
            economy.sidebarItems.map { it.route },
            "Monetización enseña Catálogo y OposiMarket.",
        )
        assertTrue(AppRoute.Commercial.Tickets !in economy.items.map { it.route })
        assertEquals(NavigationGroupId.USERS, AppRoute.Commercial.Tickets.metadata().group)
    }

    @Test
    fun noGroupIsEmpty() {
        // Un grupo vacío deja un encabezado suelto en el sidebar.
        val empty = appNavigationGroups.filter { it.items.isEmpty() }
        assertTrue(empty.isEmpty(), "Grupos sin elementos: ${empty.map { it.id }}")
    }

    @Test
    fun everyMenuItemHasAUniqueId() {
        // Los ids identifican la ruta activa en el shell; uno duplicado marcaría
        // dos entradas como seleccionadas a la vez.
        val ids = allItems.map { it.metadata.id }
        assertEquals(ids.size, ids.distinct().size, "Ids duplicados: ${ids.groupingBy { it }.eachCount().filter { it.value > 1 }}")
    }

    @Test
    fun everyMenuItemDeclaresTheGroupItIsFiledUnder() {
        // Metadata y grupo contenedor deben coincidir; si divergen, el sidebar
        // resalta la sección equivocada al navegar.
        val mismatched = appNavigationGroups.flatMap { group ->
            group.items.filter { it.metadata.group != group.id }.map { group.id to it.metadata.id }
        }
        assertTrue(mismatched.isEmpty(), "Elementos con grupo incoherente: $mismatched")
    }

    @Test
    fun theDesktopOnlyMarketIsNotOfferedOnMobile() {
        val market = allItems.single { it.route == AppRoute.Commercial.Market }

        assertEquals(NavigationVisibility.DESKTOP_ONLY, market.metadata.visibility)
    }

    @Test
    fun metadataResolvesForEveryMenuRoute() {
        // `metadata()` lanza si una ruta no está en ningún grupo, así que esto
        // detecta un desajuste entre el catálogo y el resolvedor.
        allItems.forEach { item ->
            val metadata = item.route.metadata()
            assertEquals(item.metadata.id, metadata.id)
        }
    }

    // --- Consolidación de navegación (2026-09-04): de 31 a 14 -----------------
    // El sidebar pasó de 31 entradas a 14, y volvió a 15 cuando Especialidades
    // recuperó su fila. Vuelve a 14 al pasar OposiTickets a subpantalla de Usuarios
    // (ver theSidebarHasExactlyTheFourteenTargetEntries). Estas
    // pruebas fijan el número exacto y que cada ruta que salió del menú (a) sigue
    // registrada, (b) sigue resolviendo metadata sin reventar, y (c) sigue
    // accesible por ruta directa — que es justo lo que "reorganizar sin borrar
    // funcionalidad" significa.

    @Test
    fun theSidebarHasExactlyTheFifteenTargetEntries() {
        // 14 + Etiquetas (2026-10-15). Comunidades salió del menú el 2026-10-22: se elige en cada examen.
        val sidebarTitles = appNavigationGroups.flatMap { it.sidebarItems }.map { it.metadata.title }

        assertEquals(15, sidebarTitles.size, "El sidebar tiene ${sidebarTitles.size} entradas, se esperaban 15: $sidebarTitles")
        assertEquals(
            listOf(
                "Dashboard",
                // Especialidades va justo debajo de Exámenes: es su configuración
                // previa —qué modos admite la oposición— y el editor de exámenes
                // no puede ofrecer un tipo que la especialidad no tenga abierto.
                "Exámenes", "Especialidades", "Etiquetas", "Preguntas", "Importación",
                "Usuarios",
                "Catálogo", "OposiMarket",
                "OposiRed", "Moderación", "Soporte",
                "Analytics",
                "Noticias",
                "Sistema",
            ),
            sidebarTitles,
        )
    }

    @Test
    fun specialtiesIsReachableFromTheSidebarRightBelowExams() {
        // El defecto que cierra: la consolidación dejó `Content.Taxonomy` con
        // `showInSidebar = false` pero NO la absorbió en ninguna pestaña de hub
        // —a diferencia de Recursos, que se fue a Preguntas—. Ni los atajos del
        // dashboard ni la búsqueda global llegaban a ella, así que la única
        // pantalla que da de alta especialidades y edita sus capacidades se
        // quedó sin ninguna puerta.
        val content = appNavigationGroups.single { it.id == NavigationGroupId.CONTENT }
        val titles = content.sidebarItems.map { it.metadata.title }

        assertEquals(listOf("Exámenes", "Especialidades", "Etiquetas", "Preguntas", "Importación"), titles)
    }

    @Test
    fun examsKeepsItsOwnSidebarEntryUnmergedWithAnyHub() {
        // El requisito explícito de esta fase: Exámenes no se fusiona con
        // Preguntas, Especialidades ni ningún hub.
        val exams = allItems.single { it.route == AppRoute.Content.Exams }
        assertTrue(exams.metadata.showInSidebar, "Exámenes debe seguir en el sidebar")
        assertEquals("Exámenes", exams.metadata.title)
        assertEquals(NavigationGroupId.CONTENT, exams.metadata.group)
    }

    @Test
    fun theSixteenRemovedRoutesAreHiddenNotDeleted() {
        // Las cuatro rutas de Sistema con fila en el catálogo (Roles/Settings/Diagnostics/Audit) suman
        // sólo tres aquí: Settings es la que se dejó visible como "Sistema" —
        // ver systemSettingsIsTheOneVisibleSystemEntryRelabeledSistema.
        //
        // Y son dieciséis, no diecisiete: `Content.Taxonomy` (Especialidades)
        // recuperó su fila en el sidebar — ver
        // specialtiesIsReachableFromTheSidebarRightBelowExams.
        val hiddenRoutes = listOf(
            AppRoute.Content.Resources,
            AppRoute.Rankings,
            AppRoute.Commercial.Subscriptions,
            AppRoute.Commercial.Plans,
            AppRoute.Commercial.Consumables,
            AppRoute.Commercial.Promotions,
            AppRoute.Commercial.PopupRules,
            AppRoute.Reports.List,
            AppRoute.OposiTester.List,
            AppRoute.Analytics.Performance,
            AppRoute.Publication.NewsTypes,
            AppRoute.System.Roles,
            AppRoute.System.Diagnostics,
            AppRoute.System.Audit,
        )

        hiddenRoutes.forEach { route ->
            val item = allItems.singleOrNull { it.route == route }
            assertTrue(item != null, "$route ya no está registrada en el catálogo — no debería haberse borrado")
            assertTrue(!item.metadata.showInSidebar, "$route debería estar oculta del sidebar, no lo está")
            // No revienta: metadata() sigue resolviendo para breadcrumbs/título.
            route.metadata()
        }
    }

    @Test
    fun systemSettingsIsTheOneVisibleSystemEntryRelabeledSistema() {
        // De las cuatro rutas de Sistema, Settings es la que se dejó visible
        // (abre el hub en su pestaña por defecto, «Estado») — las otras tres
        // (Roles/Diagnostics/Audit) están ocultas, cubiertas por
        // theSeventeenRemovedRoutesAreHiddenNotDeleted.
        val settings = allItems.single { it.route == AppRoute.System.Settings }
        assertTrue(settings.metadata.showInSidebar)
        assertEquals("Sistema", settings.metadata.title)
        // El texto del menú y de la cabecera dice lo que hay hoy detrás: ni «Preferencias» ni «Seguridad».
        assertEquals("Estado, administradores y auditoría del panel.", settings.metadata.description)
    }

    @Test
    fun theOldSecurityRouteIsNowCalledAdministradores() {
        // `System.Roles` sigue existiendo (la usan atajos y la búsqueda), pero abre Administradores y se llama así en la
        // cabecera y en las migas.
        val roles = allItems.single { it.route == AppRoute.System.Roles }
        assertEquals("Administradores", roles.metadata.title)
        assertTrue(!roles.metadata.showInSidebar)
    }

    @Test
    fun theStandaloneStatusRouteHasNoSidebarRowOfItsOwn() {
        // `System.Status` no está en ningún grupo del menú; al declararse sin fila propia, en escritorio marca «Sistema».
        assertTrue(allItems.none { it.route == AppRoute.System.Status })
        assertTrue(!AppRoute.System.Status.metadata().showInSidebar)
    }

    @Test
    fun supportMovedFromOperationsToCommunity() {
        val support = allItems.single { it.route == AppRoute.Support.Inbox }
        assertEquals(NavigationGroupId.COMMUNITY, support.metadata.group)
        assertTrue(support.metadata.showInSidebar)
    }

    @Test
    fun subscriptionsMovedFromEconomyToUsers() {
        val subscriptions = allItems.single { it.route == AppRoute.Commercial.Subscriptions }
        assertEquals(NavigationGroupId.USERS, subscriptions.metadata.group)
    }

    @Test
    fun auditMovedFromOperationsToSystem() {
        val audit = allItems.single { it.route == AppRoute.System.Audit }
        assertEquals(NavigationGroupId.SYSTEM, audit.metadata.group)
    }

    @Test
    fun reportsIconIsTheRealRegistryKeyNotASwappedArgument() {
        // Bug preexistente: id/icon estaban intercambiados posicionalmente para
        // esta única fila. Ver el comentario en AppNavigationCatalog.kt.
        val reports = allItems.single { it.route == AppRoute.Reports.List }
        assertEquals("reports", reports.metadata.id)
        assertEquals(com.oposicontrol.admin.core.ui.components.AppIcon.REPORTS.key, reports.icon)
    }
}
