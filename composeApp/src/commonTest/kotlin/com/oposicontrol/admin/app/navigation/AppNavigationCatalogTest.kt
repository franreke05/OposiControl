package com.oposicontrol.admin.app.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppNavigationCatalogTest {
    @Test
    fun reportsIsVisibleOnDesktopNotJustMobile() {
        val item = appNavigationGroups.first { it.id == NavigationGroupId.REPORTS }.items.single()
        assertEquals(NavigationVisibility.DESKTOP_AND_MOBILE, item.metadata.visibility)
    }

    @Test
    fun opositesterIsVisibleOnDesktopNotJustMobile() {
        val item = appNavigationGroups.first { it.id == NavigationGroupId.OPOSITESTER }.items.single()
        assertEquals(NavigationVisibility.DESKTOP_AND_MOBILE, item.metadata.visibility)
    }

    @Test
    fun forumRouteHasNoLegacySectionFallback() {
        assertNull(AppRoute.Community.OposiRed.toLegacySection())
    }

    @Test
    fun contentImportsRouteHasNoLegacySectionFallback() {
        assertNull(AppRoute.Content.Imports.toLegacySection())
    }

    @Test
    fun contentQuestionsAndResourcesStillMapToTheLegacyEditor() {
        assertEquals(AdminSection.QUESTIONS_RESOURCES, AppRoute.Content.Questions.toLegacySection())
        assertEquals(AdminSection.QUESTIONS_RESOURCES, AppRoute.Content.Resources.toLegacySection())
    }
}
