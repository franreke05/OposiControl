package com.oposicontrol.admin.app.navigation

enum class NavigationGroupId {
    HOME,
    REPORTS,
    CONTENT,
    PUBLICATION,
    USERS,
    COMMERCIAL,
    COMMUNITY_SUPPORT,
    ANALYTICS,
    OPOSITESTER,
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
        "Inicio",
        listOf(item(AppRoute.Home, "home", "Inicio", "Resumen operativo del panel.", NavigationGroupId.HOME, "home")),
    ),
    AppRouteMenuGroup(
        NavigationGroupId.REPORTS,
        "Informes",
        listOf(
            item(
                AppRoute.Reports.List,
                "reports",
                "Informes",
                "Informes operativos y de moderacion.",
                NavigationGroupId.REPORTS,
                "reports",
                visibility = NavigationVisibility.MOBILE_ONLY,
            ),
        ),
    ),
    AppRouteMenuGroup(
        NavigationGroupId.CONTENT,
        "Contenido",
        listOf(
            item(AppRoute.Content.Exams, "content.exams", "Examenes", "Catalogo, preguntas y publicacion de examenes.", NavigationGroupId.CONTENT, "▣"),
            item(AppRoute.Content.Questions, "content.questions", "Preguntas", "Revision y edicion de preguntas.", NavigationGroupId.CONTENT, "?"),
            item(AppRoute.Content.Resources, "content.resources", "Recursos", "Recursos asociados al contenido.", NavigationGroupId.CONTENT, "◇"),
            item(AppRoute.Content.Imports, "content.imports", "Importaciones", "Entradas y revisiones de importacion.", NavigationGroupId.CONTENT, "⇧"),
            item(AppRoute.Content.Taxonomy, "content.taxonomy", "Taxonomia", "Especialidades, comunidades y etiquetas.", NavigationGroupId.CONTENT, "⌘"),
        ),
    ),
    AppRouteMenuGroup(
        NavigationGroupId.PUBLICATION,
        "Publicacion",
        listOf(
            item(AppRoute.Publication.News, "publication.news", "Noticias", "Comunicacion y contenidos editoriales.", NavigationGroupId.PUBLICATION, "✦"),
            item(AppRoute.Publication.NewsTypes, "publication.news-types", "Tipos de noticia", "Catalogo editorial.", NavigationGroupId.PUBLICATION, "≡"),
            item(AppRoute.Publication.Assets, "publication.assets", "Assets", "Recursos visuales de publicacion.", NavigationGroupId.PUBLICATION, "▧"),
        ),
    ),
    AppRouteMenuGroup(
        NavigationGroupId.USERS,
        "Usuarios",
        listOf(item(AppRoute.Users.List, "users", "Usuarios", "Identidad, acceso y soporte de usuarios.", NavigationGroupId.USERS, "◎")),
    ),
    AppRouteMenuGroup(
        NavigationGroupId.COMMERCIAL,
        "Comercial",
        listOf(
            item(AppRoute.Commercial.Catalog, "commercial.catalog", "Catalogo", "Productos y ofertas.", NavigationGroupId.COMMERCIAL, "◇"),
            item(AppRoute.Commercial.Plans, "commercial.plans", "Planes", "Planes y beneficios.", NavigationGroupId.COMMERCIAL, "◆"),
            item(AppRoute.Commercial.Tickets, "commercial.tickets", "Tickets", "Carteras y movimientos.", NavigationGroupId.COMMERCIAL, "◈"),
            item(AppRoute.Commercial.Subscriptions, "commercial.subscriptions", "Suscripciones", "Accesos y suscripciones.", NavigationGroupId.COMMERCIAL, "◌"),
        ),
    ),
    AppRouteMenuGroup(
        NavigationGroupId.COMMUNITY_SUPPORT,
        "Comunidad y soporte",
        listOf(
            item(AppRoute.Community.OposiRed, "community.oposired", "Foro", "Actividad de OposiRed", NavigationGroupId.COMMUNITY_SUPPORT, "forum"),
            item(AppRoute.Support.Inbox, "support", "Soporte", "Conversaciones y seguimiento.", NavigationGroupId.COMMUNITY_SUPPORT, "◫"),
            item(AppRoute.Moderation.Queue, "moderation", "Moderacion", "Revision de acciones y reportes.", NavigationGroupId.COMMUNITY_SUPPORT, "⚑"),
        ),
    ),
    AppRouteMenuGroup(
        NavigationGroupId.ANALYTICS,
        "Analitica",
        listOf(
            item(AppRoute.Analytics.Home, "analytics", "Analitica", "Metricas operativas y de producto.", NavigationGroupId.ANALYTICS, "◒"),
            item(AppRoute.Analytics.Events, "analytics.events", "Eventos", "Eventos y calidad de datos.", NavigationGroupId.ANALYTICS, "·"),
            item(AppRoute.Analytics.Performance, "analytics.performance", "Rendimiento", "Rendimiento y conversion.", NavigationGroupId.ANALYTICS, "↗"),
        ),
    ),
    AppRouteMenuGroup(
        NavigationGroupId.OPOSITESTER,
        "OposiTester",
        listOf(
            item(
                AppRoute.OposiTester.List,
                "opositester",
                "OposiTester",
                "Incidencias de los usuarios",
                NavigationGroupId.OPOSITESTER,
                "tester",
                visibility = NavigationVisibility.MOBILE_ONLY,
            ),
        ),
    ),
    AppRouteMenuGroup(
        NavigationGroupId.SYSTEM,
        "Sistema",
        listOf(
            item(AppRoute.System.Roles, "system.roles", "Roles", "Roles y acceso administrativo.", NavigationGroupId.SYSTEM, "⚿"),
            item(AppRoute.System.Audit, "system.audit", "Auditoria", "Trazabilidad de operaciones.", NavigationGroupId.SYSTEM, "☷"),
            item(AppRoute.System.Settings, "system.settings", "Configuración", "Configuración local del panel.", NavigationGroupId.SYSTEM, "settings"),
            item(AppRoute.System.Diagnostics, "system.diagnostics", "Diagnostico", "Estado de conectividad y backend.", NavigationGroupId.SYSTEM, "⌁"),
        ),
    ),
)

val mobilePrimaryNavigation: List<AppRouteMenuItem> = listOf(
    appNavigationGroups.first { it.id == NavigationGroupId.HOME }.items.single(),
    appNavigationGroups.first { it.id == NavigationGroupId.REPORTS }.items.single(),
    appNavigationGroups.first { it.id == NavigationGroupId.COMMUNITY_SUPPORT }.items.first(),
    appNavigationGroups.first { it.id == NavigationGroupId.OPOSITESTER }.items.single(),
    appNavigationGroups.first { it.id == NavigationGroupId.SYSTEM }.items.first { it.route == AppRoute.System.Settings },
)

fun AppRoute.metadata(): AppRouteMetadata = when (this) {
    AppRoute.Auth -> AppRouteMetadata("auth", "Acceso", "Inicia sesion para continuar.", NavigationGroupId.SYSTEM, requiresAuth = false)
    AppRoute.Home -> appNavigationGroups.metadataFor(this)
    is AppRoute.Content -> appNavigationGroups.metadataFor(if (this is AppRoute.Content.ExamDetail) AppRoute.Content.Exams else this)
    is AppRoute.Publication -> appNavigationGroups.metadataFor(this)
    is AppRoute.Users -> appNavigationGroups.metadataFor(if (this is AppRoute.Users.Detail) AppRoute.Users.List else this)
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
    AppRoute.Content.Questions, AppRoute.Content.Resources, AppRoute.Content.Imports -> AdminSection.QUESTIONS_RESOURCES
    AppRoute.Content.Taxonomy -> AdminSection.SPECIALTIES
    AppRoute.Publication.News -> AdminSection.NEWS
    AppRoute.Publication.NewsTypes -> AdminSection.NEWS_TYPES
    AppRoute.Publication.Assets -> AdminSection.NEWS
    AppRoute.Users.List, is AppRoute.Users.Detail -> AdminSection.USERS
    AppRoute.Commercial.Catalog, AppRoute.Commercial.Plans -> AdminSection.STORE
    AppRoute.Commercial.Tickets -> AdminSection.TICKETS
    AppRoute.Commercial.Subscriptions -> AdminSection.EXAM_ACCESS
    AppRoute.Community.OposiRed -> AdminSection.MODERATION
    is AppRoute.Community.Thread, is AppRoute.Community.ModerationDetail -> null
    is AppRoute.Reports, is AppRoute.OposiTester -> null
    AppRoute.Support.Inbox -> AdminSection.SUPPORT
    AppRoute.Moderation.Queue -> AdminSection.MODERATION
    AppRoute.Analytics.Home, AppRoute.Analytics.Events, AppRoute.Analytics.Performance -> AdminSection.DASHBOARD
    AppRoute.System.Roles -> AdminSection.ROLES
    AppRoute.System.Audit -> AdminSection.AUDIT
    AppRoute.System.Settings -> AdminSection.SETTINGS
    AppRoute.System.Status -> null
    AppRoute.System.Diagnostics -> null
}

private fun List<AppRouteMenuGroup>.metadataFor(route: AppRoute): AppRouteMetadata =
    asSequence().flatMap { it.items.asSequence() }.first { it.route == route }.metadata
