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
    SessionState.UNAUTHENTICATED -> AppRoute.Auth
    SessionState.AUTHENTICATED -> if (requested is AppRoute.Auth) AppRoute.Home else requested
}

/**
 * Ruta padre de una pantalla interna, o null si es una pantalla raíz.
 *
 * Existe como red de seguridad del botón Atrás del sistema, no como mecanismo
 * principal: lo normal es que la pila de navegación ya sepa de dónde se vino, y
 * la pila es más fiable que una tabla estática porque refleja el camino real —
 * a Importación se puede llegar desde Ajustes o desde una acción rápida del
 * Dashboard, y el padre correcto es distinto en cada caso.
 *
 * Esta tabla sólo entra en juego cuando la pila tiene un único elemento y aun
 * así estamos en una pantalla de detalle, que es lo que ocurre tras un
 * `replace()` o un `reset()`. Sin ella, en ese estado el botón Atrás cerraría la
 * aplicación desde una pantalla de detalle.
 *
 * No debe tener ciclos: `NavigationBackTest` lo comprueba.
 */
fun AppRoute.parentRoute(): AppRoute? = when (this) {
    is AppRoute.Reports.Detail -> AppRoute.Reports.List
    is AppRoute.Community.Thread -> AppRoute.Community.OposiRed
    is AppRoute.Community.ModerationDetail -> AppRoute.Community.OposiRed
    is AppRoute.OposiTester.Detail -> AppRoute.OposiTester.List
    is AppRoute.Users.Detail -> AppRoute.Users.List
    is AppRoute.Content.ExamDetail -> AppRoute.Content.Exams
    AppRoute.System.Status -> AppRoute.System.Settings
    // Importación no tiene un único origen legítimo (Ajustes en móvil, acción
    // rápida del Dashboard). Home es el destino seguro cuando la pila no puede
    // decirnos de dónde vino.
    AppRoute.Content.Imports -> AppRoute.Home
    else -> null
}

/**
 * Atiende el botón/gesto Atrás del sistema.
 *
 * Devuelve true si la navegación se ha consumido y false si no hay a dónde
 * volver — en ese caso quien llama debe dejar que el sistema haga lo suyo, que
 * en Android es enviar la aplicación al fondo. Ésa es la diferencia respecto al
 * comportamiento anterior: sin nadie atendiendo el gesto, Atrás **cerraba la
 * aplicación desde cualquier pantalla de detalle**.
 *
 * El orden importa: primero la pila (camino real recorrido), y sólo si está
 * agotada se recurre a [parentRoute]. Nunca se salta a Home «por si acaso».
 */
fun AppNavigator.handleSystemBack(): Boolean {
    if (pop()) return true
    val parent = currentRoute.value.parentRoute() ?: return false
    replace(parent)
    return true
}

/**
 * Si [handleSystemBack] va a consumir el gesto.
 *
 * Se usa para habilitar o deshabilitar el `BackHandler`: deshabilitado en una
 * pantalla raíz, el gesto llega al sistema y Android se comporta como en
 * cualquier otra aplicación.
 */
fun AppNavigator.canNavigateBack(): Boolean =
    backStack.value.size > 1 || currentRoute.value.parentRoute() != null
