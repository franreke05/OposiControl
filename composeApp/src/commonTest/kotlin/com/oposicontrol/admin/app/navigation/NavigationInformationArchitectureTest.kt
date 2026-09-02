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
            AppRoute.Analytics.Events,
            AppRoute.Publication.News,
            AppRoute.Publication.NewsTypes,
            AppRoute.Publication.Assets,
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
        assertTrue(AppRoute.Commercial.Tickets in economyRoutes)
        assertTrue(AppRoute.Commercial.Market in economyRoutes)
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
}
