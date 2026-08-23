package com.oposicontrol.admin.app.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppNavigatorTest {
    @Test
    fun initialRouteIsHome() {
        val navigator = AppNavigator()

        assertEquals(AppRoute.Home, navigator.currentRoute.value)
        assertEquals(listOf(AppRoute.Home), navigator.backStack.value)
    }

    @Test
    fun pushPopAndPopToPreserveTypedParameters() {
        val detail = AppRoute.Content.ExamDetail("EX-123")
        val navigator = AppNavigator()

        navigator.push(AppRoute.Content.Exams)
        navigator.push(detail)
        assertEquals(detail, navigator.currentRoute.value)
        assertTrue(navigator.pop())
        assertEquals(AppRoute.Content.Exams, navigator.currentRoute.value)
        navigator.push(AppRoute.Users.List)
        assertTrue(navigator.popTo(AppRoute.Content.Exams))
        assertEquals(AppRoute.Content.Exams, navigator.currentRoute.value)
        assertFalse(navigator.popTo(detail))
    }

    @Test
    fun replaceDoesNotGrowBackStack() {
        val navigator = AppNavigator()

        navigator.push(AppRoute.Content.Exams)
        navigator.replace(AppRoute.Content.Questions)

        assertEquals(listOf(AppRoute.Home, AppRoute.Content.Questions), navigator.backStack.value)
    }

    @Test
    fun primaryTabsRestoreTheirOwnStackWithoutSecondRouters() {
        val navigator = AppNavigator()
        val reportDetail = AppRoute.Reports.Detail("R-1")

        navigator.selectPrimaryTab(AppRoute.Reports.List)
        navigator.push(reportDetail)
        navigator.selectPrimaryTab(AppRoute.Community.OposiRed)
        assertEquals(listOf(AppRoute.Community.OposiRed), navigator.backStack.value)

        navigator.selectPrimaryTab(AppRoute.Reports.List)
        assertEquals(listOf(AppRoute.Reports.List, reportDetail), navigator.backStack.value)
        assertTrue(navigator.pop())
        assertEquals(AppRoute.Reports.List, navigator.currentRoute.value)
    }

    @Test
    fun selectingCurrentPrimaryTabDoesNotDuplicateOrResetItsRoute() {
        val navigator = AppNavigator()
        val detail = AppRoute.OposiTester.Detail("T-1")

        navigator.selectPrimaryTab(AppRoute.OposiTester.List)
        navigator.push(detail)
        navigator.selectPrimaryTab(AppRoute.OposiTester.List)

        assertEquals(listOf(AppRoute.OposiTester.List, detail), navigator.backStack.value)
    }

    @Test
    fun resetClearsProtectedTabStacks() {
        val navigator = AppNavigator()
        navigator.selectPrimaryTab(AppRoute.Reports.List)
        navigator.push(AppRoute.Reports.Detail("R-1"))
        navigator.reset(AppRoute.Auth)
        navigator.replace(AppRoute.Home)
        navigator.selectPrimaryTab(AppRoute.Reports.List)

        assertEquals(listOf(AppRoute.Reports.List), navigator.backStack.value)
    }

    @Test
    fun legacySectionMapsToCanonicalRoute() {
        assertEquals(AppRoute.Content.Exams, AdminSection.EXAMS.toAppRoute())
        assertEquals(AppRoute.System.Audit, AdminSection.AUDIT.toAppRoute())
        assertEquals(AppRoute.Commercial.Tickets, AdminSection.TICKETS.toAppRoute())
    }

    @Test
    fun mobilePrimaryNavigationUsesTheProductDestinations() {
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
    fun authGuardDoesNotLeakRouteWhileRestoring() {
        assertEquals(null, resolveGuardRoute(SessionState.SESSION_RESTORING, AppRoute.Home))
        assertEquals(AppRoute.Auth, resolveGuardRoute(SessionState.UNAUTHENTICATED, AppRoute.Home))
        assertEquals(AppRoute.Home, resolveGuardRoute(SessionState.AUTHENTICATED, AppRoute.Auth))
    }
}
