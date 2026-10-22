package com.oposicontrol.admin.app.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppNavigationCatalogTest {
    // Reports y OposiTester dejaron de ser grupos propios de escritorio y pasaron
    // a Comunidad. La intención que estas pruebas fijan no cambia —siguen
    // teniendo que verse en escritorio, no sólo en móvil—, así que se buscan por
    // ruta en vez de por grupo, que es como deberían haberse escrito.
    private fun itemFor(route: AppRoute) =
        appNavigationGroups.flatMap { it.items }.single { it.route == route }

    @Test
    fun reportsIsVisibleOnDesktopNotJustMobile() {
        assertEquals(NavigationVisibility.DESKTOP_AND_MOBILE, itemFor(AppRoute.Reports.List).metadata.visibility)
    }

    @Test
    fun opositesterIsVisibleOnDesktopNotJustMobile() {
        assertEquals(NavigationVisibility.DESKTOP_AND_MOBILE, itemFor(AppRoute.OposiTester.List).metadata.visibility)
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
    fun communitiesAndTagsHaveTheirOwnRouteAndSidebarEntry() {
        // Antes `toLegacySection` nunca devolvía estas secciones y `openSection` las mandaba a Especialidades.
        assertEquals(AdminSection.COMMUNITIES, AppRoute.Content.Communities.toLegacySection())
        assertEquals(AdminSection.TAGS, AppRoute.Content.Tags.toLegacySection())
        assertEquals(AppRoute.Content.Communities, AdminSection.COMMUNITIES.toAppRoute())
        assertEquals(AppRoute.Content.Tags, AdminSection.TAGS.toAppRoute())
        listOf(AppRoute.Content.Tags).forEach { route ->
            val item = itemFor(route)
            assertEquals(NavigationGroupId.CONTENT, item.metadata.group)
            assertEquals(true, item.metadata.showInSidebar)
            assertEquals(NavigationVisibility.DESKTOP_ONLY, item.metadata.visibility)
        }
    }

    @Test
    fun desktopOnlyScreensAreMarkedSoTheNarrowViewNeverOffersThem() {
        listOf(
            AppRoute.Commercial.Catalog,
            AppRoute.Commercial.Plans,
            AppRoute.Commercial.Market,
            AppRoute.Analytics.Home,
            AppRoute.Analytics.Performance,
            AppRoute.Publication.News,
            AppRoute.Publication.NewsTypes,
            AppRoute.Rankings,
        ).forEach { route ->
            assertEquals(NavigationVisibility.DESKTOP_ONLY, itemFor(route).metadata.visibility, "$route")
        }
    }

    @Test
    fun contentQuestionsAndResourcesStillMapToTheLegacyEditor() {
        assertEquals(AdminSection.QUESTIONS_RESOURCES, AppRoute.Content.Questions.toLegacySection())
        assertEquals(AdminSection.QUESTIONS_RESOURCES, AppRoute.Content.Resources.toLegacySection())
    }
}
