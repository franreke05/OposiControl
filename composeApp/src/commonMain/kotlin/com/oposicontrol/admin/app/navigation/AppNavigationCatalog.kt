package com.oposicontrol.admin.app.navigation

import com.oposicontrol.admin.core.ui.components.AppIcon

/**
 * Agrupación del menú.
 *
 * La estructura sigue cómo se administra el producto, no cómo está organizado el
 * código: quien ajusta precios entra en Economía y encuentra planes, tickets,
 * packs y Market juntos, en vez de repartidos entre "Comercial" y "Tienda".
 *
 * `REPORTS` y `OPOSITESTER` ya no son grupos propios de escritorio: son trabajo
 * de comunidad y viven dentro de `COMMUNITY`. Siguen siendo destinos de primer
 * nivel en móvil, donde sí son la tarea principal — ver [mobilePrimaryNavigation],
 * que los selecciona por ruta y no por grupo justamente para que reorganizar el
 * escritorio no toque la barra inferior del móvil.
 */
enum class NavigationGroupId {
    HOME,
    CONTENT,
    USERS,
    ECONOMY,
    COMMUNITY,
    ANALYTICS,
    OPERATIONS,
    SYSTEM,
}

enum class NavigationVisibility { DESKTOP_AND_MOBILE, DESKTOP_ONLY, MOBILE_ONLY }

data class AppRouteMetadata(
    val id: String,
    val title: String,
    val description: String,
    val group: NavigationGroupId,
    val requiresAuth: Boolean = true,
    /** Null means role policy is intentionally deferred to Phase 1/security. */
    val allowedRoles: Set<String>? = null,
    val visibility: NavigationVisibility = NavigationVisibility.DESKTOP_AND_MOBILE,
)

data class AppRouteMenuItem(
    val route: AppRoute,
    val metadata: AppRouteMetadata,
    val icon: String,
)

data class AppRouteMenuGroup(
    val id: NavigationGroupId,
    val label: String,
    val items: List<AppRouteMenuItem>,
)

private fun item(
    route: AppRoute,
    id: String,
    title: String,
    description: String,
    group: NavigationGroupId,
    icon: String,
    visibility: NavigationVisibility = NavigationVisibility.DESKTOP_AND_MOBILE,
) = AppRouteMenuItem(
    route = route,
    metadata = AppRouteMetadata(id, title, description, group, visibility = visibility),
    icon = icon,
)

val appNavigationGroups: List<AppRouteMenuGroup> = listOf(
    AppRouteMenuGroup(
        NavigationGroupId.HOME,
        "Dashboard",
        listOf(item(AppRoute.Home, AppIcon.HOME.key, "Dashboard", "Resumen operativo del panel.", NavigationGroupId.HOME, "home")),
    ),
    AppRouteMenuGroup(
        NavigationGroupId.CONTENT,
        "Contenido",
        listOf(
            item(AppRoute.Content.Taxonomy, "content.taxonomy", "Especialidades", "Especialidades, comunidades, etiquetas y modos disponibles.", NavigationGroupId.CONTENT, AppIcon.LAYERS.key),
            item(AppRoute.Content.Exams, "content.exams", "Exámenes", "Catálogo, preguntas y publicación de exámenes.", NavigationGroupId.CONTENT, AppIcon.EXAMS.key),
            item(AppRoute.Content.Questions, "content.questions", "Preguntas", "Revisión y edición de preguntas.", NavigationGroupId.CONTENT, AppIcon.QUESTIONS.key),
            item(AppRoute.Content.Resources, "content.resources", "Recursos", "Recursos asociados al contenido inmersivo.", NavigationGroupId.CONTENT, AppIcon.RESOURCES.key),
            item(AppRoute.Content.Imports, "content.imports", "Importación", "Subida de PDF, revisión y publicación.", NavigationGroupId.CONTENT, AppIcon.IMPORT.key),
        ),
    ),
    AppRouteMenuGroup(
        NavigationGroupId.USERS,
        "Usuarios",
        listOf(
            item(AppRoute.Users.List, "users", "Usuarios", "Identidad, perfil, rendimiento y economía por usuario.", NavigationGroupId.USERS, AppIcon.USERS.key),
            item(AppRoute.Rankings, "rankings", "Rankings y ligas", "Clasificación, ligas y posición por opositor.", NavigationGroupId.USERS, AppIcon.RANKINGS.key, NavigationVisibility.DESKTOP_ONLY),
        ),
    ),
    AppRouteMenuGroup(
        NavigationGroupId.ECONOMY,
        "Economía",
        listOf(
            item(AppRoute.Commercial.Plans, "commercial.plans", "Planes", "Planes de suscripción y beneficios.", NavigationGroupId.ECONOMY, AppIcon.PLANS.key),
            item(AppRoute.Commercial.Catalog, "commercial.catalog", "Catálogo comercial", "Productos, packs y tarifas.", NavigationGroupId.ECONOMY, AppIcon.CATALOG.key),
            item(AppRoute.Commercial.Tickets, "commercial.tickets", "OposiTickets", "Carteras, movimientos y concesiones.", NavigationGroupId.ECONOMY, AppIcon.TICKETS.key),
            item(AppRoute.Commercial.Market, "commercial.market", "OposiMarket", "Packs, servicios, promociones y pop-ups.", NavigationGroupId.ECONOMY, AppIcon.MARKET.key, NavigationVisibility.DESKTOP_ONLY),
            item(AppRoute.Commercial.Consumables, "commercial.consumables", "Consumibles", "Potenciadores y protectores que se compran con OT.", NavigationGroupId.ECONOMY, AppIcon.CATALOG.key, NavigationVisibility.DESKTOP_ONLY),
            item(AppRoute.Commercial.Promotions, "commercial.promotions", "Promociones", "Cupones y descuentos con ventana de validez.", NavigationGroupId.ECONOMY, AppIcon.PLANS.key, NavigationVisibility.DESKTOP_ONLY),
            item(AppRoute.Commercial.PopupRules, "commercial.popups", "Pop-ups", "Cuándo y a quién se muestra cada oferta.", NavigationGroupId.ECONOMY, AppIcon.INFO.key, NavigationVisibility.DESKTOP_ONLY),
            // Etiquetado por lo que realmente abre: esta ruta mapea a la pantalla
            // de accesos a examen, no a la gestion de suscripciones.
            item(AppRoute.Commercial.Subscriptions, "commercial.subscriptions", "Accesos", "Accesos concedidos a exámenes.", NavigationGroupId.ECONOMY, AppIcon.ACCESS.key),
        ),
    ),
    AppRouteMenuGroup(
        NavigationGroupId.COMMUNITY,
        "Comunidad",
        listOf(
            item(AppRoute.Community.OposiRed, "community.oposired", "OposiRed", "Hilos y comentarios de la comunidad.", NavigationGroupId.COMMUNITY, AppIcon.FORUM.key),
            item(AppRoute.Moderation.Queue, "moderation", "Moderación", "Acciones de moderación y su auditoría.", NavigationGroupId.COMMUNITY, AppIcon.MODERATION.key),
            item(AppRoute.Reports.List, AppIcon.REPORTS.key, "Reportes", "Reportes de contenido y su resolución.", NavigationGroupId.COMMUNITY, "reports"),
            item(AppRoute.OposiTester.List, "opositester", "OposiTester", "Incidencias enviadas por los usuarios.", NavigationGroupId.COMMUNITY, AppIcon.TESTER.key),
        ),
    ),
    AppRouteMenuGroup(
        NavigationGroupId.ANALYTICS,
        "Analítica",
        listOf(
            item(AppRoute.Analytics.Home, "analytics", "Producto", "Uso, sesiones y actividad.", NavigationGroupId.ANALYTICS, AppIcon.ANALYTICS.key),
            item(AppRoute.Analytics.Performance, "analytics.performance", "Estudio", "Rendimiento, dificultad y debilidades.", NavigationGroupId.ANALYTICS, AppIcon.PERFORMANCE.key),
            item(AppRoute.Analytics.Events, "analytics.events", "Eventos", "Eventos y calidad de datos.", NavigationGroupId.ANALYTICS, AppIcon.EVENTS.key),
        ),
    ),
    AppRouteMenuGroup(
        NavigationGroupId.OPERATIONS,
        "Operaciones",
        listOf(
            item(AppRoute.Publication.News, "publication.news", "Noticias", "Comunicación y contenidos editoriales.", NavigationGroupId.OPERATIONS, AppIcon.NEWS.key),
            item(AppRoute.Publication.NewsTypes, "publication.news-types", "Tipos de noticia", "Catálogo editorial.", NavigationGroupId.OPERATIONS, AppIcon.NEWS_TYPES.key),
            item(AppRoute.Publication.Assets, "publication.assets", "Assets", "Recursos visuales de publicación.", NavigationGroupId.OPERATIONS, AppIcon.ASSETS.key),
            item(AppRoute.Support.Inbox, "support", "Soporte", "Conversaciones y seguimiento.", NavigationGroupId.OPERATIONS, AppIcon.SUPPORT.key),
            item(AppRoute.System.Audit, "system.audit", "Auditoría", "Trazabilidad de operaciones sensibles.", NavigationGroupId.OPERATIONS, AppIcon.AUDIT.key),
        ),
    ),
    AppRouteMenuGroup(
        NavigationGroupId.SYSTEM,
        "Sistema",
        listOf(
            item(AppRoute.System.Roles, "system.roles", "Roles", "Roles y acceso administrativo.", NavigationGroupId.SYSTEM, AppIcon.ROLES.key),
            item(AppRoute.System.Settings, "system.settings", "Configuración", "Configuración local del panel.", NavigationGroupId.SYSTEM, AppIcon.SETTINGS.key),
            item(AppRoute.System.Diagnostics, "system.diagnostics", "Estado", "Conectividad y estado del backend.", NavigationGroupId.SYSTEM, AppIcon.STATUS.key),
        ),
    ),
)

/**
 * Destinos de la barra inferior de movil.
 *
 * Se seleccionan por RUTA, no por grupo: reorganizar la agrupacion de escritorio
 * no debe mover la navegacion principal del movil, que responde a otra tarea
 * (supervision rapida de comunidad, reportes y OposiTester).
 */
val mobilePrimaryNavigation: List<AppRouteMenuItem> = listOf(
    AppRoute.Home,
    AppRoute.Reports.List,
    AppRoute.Community.OposiRed,
    AppRoute.OposiTester.List,
    AppRoute.System.Settings,
).map { route -> appNavigationGroups.itemFor(route) }

private fun List<AppRouteMenuGroup>.itemFor(route: AppRoute): AppRouteMenuItem =
    asSequence().flatMap { it.items.asSequence() }.first { it.route == route }

fun AppRoute.metadata(): AppRouteMetadata = when (this) {
    AppRoute.Auth -> AppRouteMetadata("auth", "Acceso", "Inicia sesión para continuar.", NavigationGroupId.SYSTEM, requiresAuth = false)
    AppRoute.Home -> appNavigationGroups.metadataFor(this)
    is AppRoute.Content -> appNavigationGroups.metadataFor(if (this is AppRoute.Content.ExamDetail) AppRoute.Content.Exams else this)
    is AppRoute.Publication -> appNavigationGroups.metadataFor(this)
    is AppRoute.Users -> appNavigationGroups.metadataFor(if (this is AppRoute.Users.Detail) AppRoute.Users.List else this)
    AppRoute.Rankings -> appNavigationGroups.metadataFor(this)
    is AppRoute.Commercial -> appNavigationGroups.metadataFor(this)
    is AppRoute.Community -> appNavigationGroups.metadataFor(AppRoute.Community.OposiRed)
    is AppRoute.Reports -> appNavigationGroups.metadataFor(AppRoute.Reports.List)
    is AppRoute.OposiTester -> appNavigationGroups.metadataFor(AppRoute.OposiTester.List)
    is AppRoute.Support -> appNavigationGroups.metadataFor(this)
    is AppRoute.Moderation -> appNavigationGroups.metadataFor(this)
    is AppRoute.Analytics -> appNavigationGroups.metadataFor(this)
    AppRoute.System.Status -> AppRouteMetadata(
        id = "system.status",
        title = "Estado del sistema",
        description = "Estado operativo del panel y sus servicios.",
        group = NavigationGroupId.SYSTEM,
        // visibility explicito (no el default DESKTOP_AND_MOBILE): Screen 10
        // es una pantalla mobile, igual que en la version de Screen 10 real
        // (feature/mobile-settings) -- se preserva desde la ruta ya preparada.
        visibility = NavigationVisibility.MOBILE_ONLY,
    )
    is AppRoute.System -> appNavigationGroups.metadataFor(this)
}

fun AppRoute.navigationId(): String = metadata().id

fun AppRoute.toLegacySection(): AdminSection? = when (this) {
    AppRoute.Auth -> null
    AppRoute.Home -> AdminSection.DASHBOARD
    AppRoute.Content.Exams, is AppRoute.Content.ExamDetail -> AdminSection.EXAMS
    AppRoute.Content.Questions, AppRoute.Content.Resources -> AdminSection.QUESTIONS_RESOURCES
    AppRoute.Content.Imports -> null
    AppRoute.Content.Taxonomy -> AdminSection.SPECIALTIES
    AppRoute.Publication.News -> AdminSection.NEWS
    AppRoute.Publication.NewsTypes -> AdminSection.NEWS_TYPES
    AppRoute.Publication.Assets -> AdminSection.NEWS
    AppRoute.Users.List, is AppRoute.Users.Detail -> AdminSection.USERS
    // Pantalla propia de escritorio: no cae al adaptador legacy.
    AppRoute.Rankings -> null
    AppRoute.Commercial.Catalog, AppRoute.Commercial.Plans -> AdminSection.STORE
    // Market tiene pantalla propia de escritorio; no cae al adaptador legacy.
    AppRoute.Commercial.Market -> null
    AppRoute.Commercial.Consumables,
    AppRoute.Commercial.Promotions,
    AppRoute.Commercial.PopupRules,
    -> null
    AppRoute.Commercial.Tickets -> AdminSection.TICKETS
    AppRoute.Commercial.Subscriptions -> AdminSection.EXAM_ACCESS
    AppRoute.Community.OposiRed, is AppRoute.Community.Thread, is AppRoute.Community.ModerationDetail -> null
    is AppRoute.Reports, is AppRoute.OposiTester -> null
    AppRoute.Support.Inbox -> AdminSection.SUPPORT
    AppRoute.Moderation.Queue -> AdminSection.MODERATION
    // Analitica de producto tiene pantalla propia de escritorio; no cae al legacy.
    AppRoute.Analytics.Home -> null
    AppRoute.Analytics.Events, AppRoute.Analytics.Performance -> AdminSection.DASHBOARD
    AppRoute.System.Roles -> AdminSection.ROLES
    AppRoute.System.Audit -> AdminSection.AUDIT
    AppRoute.System.Settings -> AdminSection.SETTINGS
    AppRoute.System.Status -> null
    AppRoute.System.Diagnostics -> null
}

private fun List<AppRouteMenuGroup>.metadataFor(route: AppRoute): AppRouteMetadata =
    asSequence().flatMap { it.items.asSequence() }.first { it.route == route }.metadata
