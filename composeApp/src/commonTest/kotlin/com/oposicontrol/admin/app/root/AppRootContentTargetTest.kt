package com.oposicontrol.admin.app.root

import com.oposicontrol.admin.app.navigation.AppRoute
import kotlin.test.Test
import kotlin.test.assertEquals

class AppRootContentTargetTest {
    @Test
    fun desktopReachesReportsListWithoutCompactLayout() {
        assertEquals(
            AppContentTarget.REPORTS_LIST,
            resolveContentTarget(AppRoute.Reports.List, compactLayout = false, showTabPager = false, hasLegacyRoute = false),
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
    fun desktopReachesOpositesterListWithoutCompactLayout() {
        assertEquals(
            AppContentTarget.OPOSITESTER_LIST,
            resolveContentTarget(AppRoute.OposiTester.List, compactLayout = false, showTabPager = false, hasLegacyRoute = false),
        )
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
    fun desktopFallsBackToTheLegacySectionWhenOneIsMapped() {
        assertEquals(
            AppContentTarget.LEGACY_SECTION,
            resolveContentTarget(AppRoute.Users.List, compactLayout = false, showTabPager = false, hasLegacyRoute = true),
        )
    }

    @Test
    fun unmappedRouteWithoutALegacySectionShowsThePlaceholder() {
        assertEquals(
            AppContentTarget.LEGACY_PLACEHOLDER,
            resolveContentTarget(AppRoute.System.Diagnostics, compactLayout = false, showTabPager = false, hasLegacyRoute = false),
        )
    }

    @Test
    fun desktopReachesTheCommercialCatalog() {
        assertEquals(
            AppContentTarget.COMMERCIAL_CATALOG,
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
    fun theTabPagerStillWinsOverTheCommercialCatalog() {
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
}
