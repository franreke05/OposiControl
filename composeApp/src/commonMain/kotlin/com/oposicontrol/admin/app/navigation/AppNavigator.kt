package com.oposicontrol.admin.app.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SessionState { AUTHENTICATED, UNAUTHENTICATED, SESSION_RESTORING }

/** Single navigation state owner for all platforms and responsive layouts. */
class AppNavigator(
    initialRoute: AppRoute = AppRoute.Home,
) {
    private val mutableBackStack = MutableStateFlow(listOf(initialRoute))
    private val mutableRoute = MutableStateFlow(initialRoute)
    private val tabStacks = mutableMapOf<AppRoute, List<AppRoute>>()

    val backStack: StateFlow<List<AppRoute>> = mutableBackStack.asStateFlow()
    val currentRoute: StateFlow<AppRoute> = mutableRoute.asStateFlow()

    fun push(route: AppRoute) {
        if (route == mutableRoute.value) return
        updateStack(mutableBackStack.value + route)
    }

    fun replace(route: AppRoute) {
        val current = mutableBackStack.value
        updateStack(if (current.isEmpty()) listOf(route) else current.dropLast(1) + route)
    }

    fun pop(): Boolean {
        val current = mutableBackStack.value
        if (current.size <= 1) return false
        updateStack(current.dropLast(1))
        return true
    }

    fun popTo(route: AppRoute): Boolean {
        val index = mutableBackStack.value.indexOfLast { it == route }
        if (index < 0) return false
        updateStack(mutableBackStack.value.take(index + 1))
        return true
    }

    fun reset(route: AppRoute) {
        tabStacks.clear()
        updateStack(listOf(route))
    }

    /**
     * Selects a mobile primary destination while keeping one stack per
     * destination inside this navigator. It is not a second router: all
     * routes still flow through the single canonical back stack.
     */
    fun selectPrimaryTab(route: AppRoute) {
        val targetRoot = route.primaryTabRoot() ?: return
        val currentRoot = mutableRoute.value.primaryTabRoot()
        if (targetRoot == currentRoot) return

        currentRoot?.let { tabStacks[it] = mutableBackStack.value }
        updateStack(tabStacks[targetRoot] ?: listOf(targetRoot))
    }

    fun openAuth() = reset(AppRoute.Auth)

    /** Compatibility bridge for legacy feature callbacks during migration. */
    fun openSection(section: AdminSection) = replace(section.toAppRoute())

    private fun updateStack(stack: List<AppRoute>) {
        val safeStack = stack.ifEmpty { listOf(AppRoute.Home) }
        mutableBackStack.value = safeStack
        mutableRoute.value = safeStack.last()
    }
}

private fun AppRoute.primaryTabRoot(): AppRoute? = when (this) {
    AppRoute.Home -> AppRoute.Home
    AppRoute.Reports.List, is AppRoute.Reports.Detail -> AppRoute.Reports.List
    AppRoute.Community.OposiRed,
    is AppRoute.Community.Thread,
    is AppRoute.Community.ModerationDetail,
    -> AppRoute.Community.OposiRed
    AppRoute.OposiTester.List, is AppRoute.OposiTester.Detail -> AppRoute.OposiTester.List
    AppRoute.System.Settings -> AppRoute.System.Settings
    else -> null
}

fun AdminSection.toAppRoute(): AppRoute = when (this) {
    AdminSection.DASHBOARD -> AppRoute.Home
    AdminSection.USERS -> AppRoute.Users.List
    AdminSection.ROLES -> AppRoute.System.Roles
    AdminSection.SPECIALTIES,
    AdminSection.COMMUNITIES,
    AdminSection.TAGS,
    -> AppRoute.Content.Taxonomy
    AdminSection.NEWS_TYPES -> AppRoute.Publication.NewsTypes
    AdminSection.NEWS -> AppRoute.Publication.News
    AdminSection.EXAMS -> AppRoute.Content.Exams
    AdminSection.QUESTIONS_RESOURCES -> AppRoute.Content.Questions
    AdminSection.EXAM_ACCESS -> AppRoute.Commercial.Subscriptions
    AdminSection.STORE -> AppRoute.Commercial.Catalog
    AdminSection.TICKETS -> AppRoute.Commercial.Tickets
    AdminSection.SUPPORT -> AppRoute.Support.Inbox
    AdminSection.MODERATION -> AppRoute.Moderation.Queue
    AdminSection.SETTINGS -> AppRoute.System.Settings
    AdminSection.AUDIT -> AppRoute.System.Audit
}

fun resolveGuardRoute(session: SessionState, requested: AppRoute): AppRoute? = when (session) {
    SessionState.SESSION_RESTORING -> null
    SessionState.UNAUTHENTICATED -> if (requested is AppRoute.Auth) AppRoute.Auth else AppRoute.Auth
    SessionState.AUTHENTICATED -> if (requested is AppRoute.Auth) AppRoute.Home else requested
}
