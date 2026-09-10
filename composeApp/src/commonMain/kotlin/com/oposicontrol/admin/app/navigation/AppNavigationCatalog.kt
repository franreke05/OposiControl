package com.oposicontrol.admin.app.navigation

import com.oposicontrol.admin.core.ui.components.AppIcon

/**
 * Agrupación del menú.
 *
 * La estructura sigue cómo se administra el producto, no cómo está organizado el
 * código: quien ajusta precios entra en Monetización y encuentra planes, tickets,
 * catálogo y Market juntos, en vez de repartidos entre "Comercial" y "Tienda".
 *
 * `REPORTS` y `OPOSITESTER` ya no son grupos propios de escritorio: son trabajo
 * de comunidad y viven dentro de `COMMUNITY`. Siguen siendo destinos de primer
 * nivel en móvil, donde sí son la tarea principal — ver [mobilePrimaryNavigation],
 * que los selecciona por ruta y no por grupo justamente para que reorganizar el
 * escritorio no toque la barra inferior del móvil.
 *
 * Consolidación de navegación (2026-09-04): de 31 entradas de sidebar a 14. Las
 * 17 que salieron del menú (Especialidades, Recursos, Rankings, Accesos, Planes,
 * Consumibles, Promociones, Pop-ups, Reportes, OposiTester, Estudio, Eventos,
 * Tipos de noticia, Assets, Roles, Configuración, Estado) NO se borraron: su
 * `AppRoute` sigue existiendo, sigue registrada aquí (con `showInSidebar =
 * false`) para que `AppRoute.metadata()` no reviente, y sigue resolviendo a
 * contenido real — bien porque ya vivía en la pantalla que la absorbe (Recursos
 * en Preguntas, Consumibles/Promociones/Pop-ups en Market), bien porque
 * [AppRoot] la enruta ahora a una pestaña de un hub nuevo y delgado
 * (Sistema, y las pestañas añadidas a Usuarios/Catálogo/Noticias) que compone
 * la pantalla existente sin tocar su interior. Exámenes es la única vertical
 * que se dejó explícitamente intacta, arquitectura incluida.
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
    /**
     * Si aparece en el sidebar principal. `false` no es "ruta muerta": la ruta
     * sigue registrada aquí (así que [AppRoute.metadata] la resuelve, tiene
     * título/descripción para breadcrumbs, y [AppRoot] la sigue enrutando a
     * contenido real), simplemente no ocupa una fila propia en el menú.
     */
    val showInSidebar: Boolean = true,
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
) {
    /** Sólo lo que se pinta en el sidebar — [items] conserva la lista completa. */
    val sidebarItems: List<AppRouteMenuItem> get() = items.filter { it.metadata.showInSidebar }
}

private fun item(
    route: AppRoute,
    id: String,
    title: String,
    description: String,
    group: NavigationGroupId,
    icon: String,
    visibility: NavigationVisibility = NavigationVisibility.DESKTOP_AND_MOBILE,
    showInSidebar: Boolean = true,
) = AppRouteMenuItem(
    route = route,
    metadata = AppRouteMetadata(id, title, description, group, visibility = visibility, showInSidebar = showInSidebar),
    icon = icon,
)

val appNavigationGroups: List<AppRouteMenuGroup> = listOf(
    AppRouteMenuGroup(
        NavigationGroupId.HOME,
        "Inicio",
        listOf(item(AppRoute.Home, AppIcon.HOME.key, "Dashboard", "Resumen operativo del panel.", NavigationGroupId.HOME, "home")),
    ),
    AppRouteMenuGroup(
        NavigationGroupId.CONTENT,
        "Contenido",
        listOf(
            item(AppRoute.Content.Exams, "content.exams", "Exámenes", "Catálogo, preguntas y publicación de exámenes.", NavigationGroupId.CONTENT, AppIcon.EXAMS.key),
            item(AppRoute.Content.Questions, "content.questions", "Preguntas", "Revisión y edición de preguntas y sus recursos.", NavigationGroupId.CONTENT, AppIcon.QUESTIONS.key),
            item(AppRoute.Content.Imports, "content.imports", "Importación", "Subida de PDF, revisión y publicación.", NavigationGroupId.CONTENT, AppIcon.IMPORT.key),
            // Recursos ya vivía dentro de la misma pantalla que Preguntas
            // (QuestionsResourcesScreen) — toLegacySection() ya mapeaba ambas
            // rutas al mismo AdminSection.QUESTIONS_RESOURCES antes de esta
            // consolidación. No hacía falta tocar nada para que "la ruta antigua
            // de Recursos siga abriendo Preguntas": ya lo hacía.
            item(AppRoute.Content.Resources, "content.resources", "Recursos", "Recursos asociados al contenido inmersivo.", NavigationGroupId.CONTENT, AppIcon.RESOURCES.key, showInSidebar = false),
            item(AppRoute.Content.Taxonomy, "content.taxonomy", "Especialidades", "Especialidades, comunidades, etiquetas y modos disponibles.", NavigationGroupId.CONTENT, AppIcon.LAYERS.key, showInSidebar = false),
        ),
    ),
    AppRouteMenuGroup(
        NavigationGroupId.USERS,
        "Usuarios",
        listOf(
            item(AppRoute.Users.List, "users", "Usuarios", "Identidad, perfil, rendimiento y competición por usuario.", NavigationGroupId.USERS, AppIcon.USERS.key),
            item(AppRoute.Rankings, "rankings", "Rankings y ligas", "Clasificación, ligas y posición por opositor.", NavigationGroupId.USERS, AppIcon.RANKINGS.key, NavigationVisibility.DESKTOP_ONLY, showInSidebar = false),
            // Movida de Monetización a Usuarios (§7): es acceso a examen por
            // usuario, no una palanca de precio.
            item(AppRoute.Commercial.Subscriptions, "commercial.subscriptions", "Accesos", "Accesos concedidos a exámenes.", NavigationGroupId.USERS, AppIcon.ACCESS.key, showInSidebar = false),
        ),
    ),
    AppRouteMenuGroup(
        NavigationGroupId.ECONOMY,
        "Monetización",
        listOf(
            item(AppRoute.Commercial.Catalog, "commercial.catalog", "Catálogo", "Productos, planes y tarifas.", NavigationGroupId.ECONOMY, AppIcon.CATALOG.key),
            item(AppRoute.Commercial.Market, "commercial.market", "OposiMarket", "Packs, servicios, promociones y pop-ups.", NavigationGroupId.ECONOMY, AppIcon.MARKET.key, NavigationVisibility.DESKTOP_ONLY),
            item(AppRoute.Commercial.Tickets, "commercial.tickets", "OposiTickets", "Carteras, movimientos y concesiones.", NavigationGroupId.ECONOMY, AppIcon.TICKETS.key),
            item(AppRoute.Commercial.Plans, "commercial.plans", "Planes", "Planes de suscripción y beneficios.", NavigationGroupId.ECONOMY, AppIcon.PLANS.key, showInSidebar = false),
            item(AppRoute.Commercial.Consumables, "commercial.consumables", "Consumibles", "Potenciadores y protectores que se compran con OT.", NavigationGroupId.ECONOMY, AppIcon.CATALOG.key, NavigationVisibility.DESKTOP_ONLY, showInSidebar = false),
            item(AppRoute.Commercial.Promotions, "commercial.promotions", "Promociones", "Cupones y descuentos con ventana de validez.", NavigationGroupId.ECONOMY, AppIcon.PLANS.key, NavigationVisibility.DESKTOP_ONLY, showInSidebar = false),
            item(AppRoute.Commercial.PopupRules, "commercial.popups", "Pop-ups", "Cuándo y a quién se muestra cada oferta.", NavigationGroupId.ECONOMY, AppIcon.INFO.key, NavigationVisibility.DESKTOP_ONLY, showInSidebar = false),
        ),
    ),
    AppRouteMenuGroup(
        NavigationGroupId.COMMUNITY,
        "Comunidad",
        listOf(
            item(AppRoute.Community.OposiRed, "community.oposired", "OposiRed", "Hilos y comentarios de la comunidad.", NavigationGroupId.COMMUNITY, AppIcon.FORUM.key),
            item(AppRoute.Moderation.Queue, "moderation", "Moderación", "Reportes, OposiTester y acciones de moderación.", NavigationGroupId.COMMUNITY, AppIcon.MODERATION.key),
            // Movida de Operaciones a Comunidad (§11): soporte es trabajo de
            // comunidad, no publicación editorial.
            item(AppRoute.Support.Inbox, "support", "Soporte", "Conversaciones y seguimiento.", NavigationGroupId.COMMUNITY, AppIcon.SUPPORT.key),
            // Bug preexistente arreglado de paso: los argumentos posicionales de
            // id/icon estaban intercambiados (pasaba AppIcon.REPORTS.key como id
            // y el literal "reports" como icon), así que esta fila pintaba con la
            // clave de icono equivocada.
            item(AppRoute.Reports.List, "reports", "Reportes", "Reportes de contenido y su resolución.", NavigationGroupId.COMMUNITY, AppIcon.REPORTS.key, showInSidebar = false),
            item(AppRoute.OposiTester.List, "opositester", "OposiTester", "Incidencias enviadas por los usuarios.", NavigationGroupId.COMMUNITY, AppIcon.TESTER.key, showInSidebar = false),
        ),
    ),
    AppRouteMenuGroup(
        NavigationGroupId.ANALYTICS,
        "Analítica",
        listOf(
            item(AppRoute.Analytics.Home, "analytics", "Analytics", "Producto, estudio, retención, comunidad, economía y marketing.", NavigationGroupId.ANALYTICS, AppIcon.ANALYTICS.key),
            item(AppRoute.Analytics.Performance, "analytics.performance", "Estudio", "Rendimiento, dificultad y debilidades.", NavigationGroupId.ANALYTICS, AppIcon.PERFORMANCE.key, showInSidebar = false),
            item(AppRoute.Analytics.Events, "analytics.events", "Eventos", "Eventos y calidad de datos.", NavigationGroupId.ANALYTICS, AppIcon.EVENTS.key, showInSidebar = false),
        ),
    ),
    // El enum sigue llamándose OPERATIONS (es el id interno del grupo, no texto
    // visible) para no remover el identificador que ya usa el resto del código;
    // sólo cambia la etiqueta visible y qué vive dentro.
    AppRouteMenuGroup(
        NavigationGroupId.OPERATIONS,
        "Publicación",
        listOf(
            item(AppRoute.Publication.News, "publication.news", "Noticias", "Comunicación, tipos editoriales y assets.", NavigationGroupId.OPERATIONS, AppIcon.NEWS.key),
            item(AppRoute.Publication.NewsTypes, "publication.news-types", "Tipos de noticia", "Catálogo editorial.", NavigationGroupId.OPERATIONS, AppIcon.NEWS_TYPES.key, showInSidebar = false),
            item(AppRoute.Publication.Assets, "publication.assets", "Assets", "Recursos visuales de publicación.", NavigationGroupId.OPERATIONS, AppIcon.ASSETS.key, showInSidebar = false),
        ),
    ),
    AppRouteMenuGroup(
        NavigationGroupId.SYSTEM,
        "Sistema",
        listOf(
            // Settings es la entrada visible: abre el hub de Sistema en su
            // pestaña "Preferencias" — ver systemTabFor() en AppRoot.kt.
            item(AppRoute.System.Settings, "system.settings", "Sistema", "Preferencias, seguridad, estado y auditoría del panel.", NavigationGroupId.SYSTEM, AppIcon.SETTINGS.key),
            item(AppRoute.System.Roles, "system.roles", "Roles", "Roles y acceso administrativo.", NavigationGroupId.SYSTEM, AppIcon.ROLES.key, showInSidebar = false),
            item(AppRoute.System.Diagnostics, "system.diagnostics", "Estado", "Conectividad y estado del backend.", NavigationGroupId.SYSTEM, AppIcon.STATUS.key, showInSidebar = false),
            // Movida de Operaciones a Sistema (§14).
            item(AppRoute.System.Audit, "system.audit", "Auditoría", "Trazabilidad de operaciones sensibles.", NavigationGroupId.SYSTEM, AppIcon.AUDIT.key, showInSidebar = false),
        ),
    ),
)

/**
 * Destinos de la barra inferior de movil.
 *
 * Se seleccionan por RUTA, no por grupo: reorganizar la agrupacion de escritorio
 * no debe mover la navegacion principal del movil, que responde a otra tarea
 * (supervision rapida de comunidad, reportes y OposiTester). Sigue funcionando
 * igual tras la consolidación: busca por ruta en la lista completa de items,
 * `showInSidebar` no afecta esta búsqueda.
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

/**
 * Sección legacy a la que cae una ruta cuando [AppRoot] no tiene un destino de
 * contenido más específico para ella.
 *
 * `null` no significa "sin pantalla": significa que la ruta ya tiene un destino
 * propio en `resolveContentTarget` (Exámenes, Market, Analytics, los hubs
 * nuevos de Sistema/Usuarios/Catálogo/Noticias...) y no debe caer al adaptador
 * genérico — si cayera, el header mostraría la acción primaria de la sección
 * equivocada.
 */
fun AppRoute.toLegacySection(): AdminSection? = when (this) {
    AppRoute.Auth -> null
    AppRoute.Home -> AdminSection.DASHBOARD
    AppRoute.Content.Exams, is AppRoute.Content.ExamDetail -> AdminSection.EXAMS
    AppRoute.Content.Questions, AppRoute.Content.Resources -> AdminSection.QUESTIONS_RESOURCES
    AppRoute.Content.Imports -> null
    AppRoute.Content.Taxonomy -> AdminSection.SPECIALTIES
    AppRoute.Publication.News -> null
    AppRoute.Publication.NewsTypes -> null
    AppRoute.Publication.Assets -> null
    AppRoute.Users.List, is AppRoute.Users.Detail -> AdminSection.USERS
    // Pantalla propia de escritorio: no cae al adaptador legacy.
    AppRoute.Rankings -> null
    // Catálogo y Planes ahora abren el hub propio de Catálogo (ver
    // catalogTabFor en AppRoot.kt), no el adaptador legacy.
    AppRoute.Commercial.Catalog, AppRoute.Commercial.Plans -> null
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
    // Estudio y Eventos ahora abren ProductAnalyticsScreen en su sección real
    // (ver analyticsSectionFor en AppRoot.kt) en vez de caer al Dashboard.
    AppRoute.Analytics.Events, AppRoute.Analytics.Performance -> null
    // Sistema (Roles/Configuración/Estado/Auditoría) ahora abre el hub propio
    // de Sistema (ver systemTabFor en AppRoot.kt), no el adaptador legacy.
    AppRoute.System.Roles -> null
    AppRoute.System.Audit -> null
    AppRoute.System.Settings -> null
    AppRoute.System.Status -> null
    AppRoute.System.Diagnostics -> null
}

private fun List<AppRouteMenuGroup>.metadataFor(route: AppRoute): AppRouteMetadata =
    asSequence().flatMap { it.items.asSequence() }.first { it.route == route }.metadata
