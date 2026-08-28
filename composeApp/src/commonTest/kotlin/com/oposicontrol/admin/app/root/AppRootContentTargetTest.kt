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
}
