@file:Suppress("DEPRECATION")

package com.oposicontrol.admin.app.navigation

/**
 * Source-compatible migration marker for old feature code. AppRoute and
 * AppNavigator are the active navigation contract.
 */
@Deprecated("Use AppRoute and AppNavigator")
sealed interface AppDestination {
    data object Auth : AppDestination
    data class Admin(val section: AdminSection) : AppDestination
}

enum class AdminSection(
    val label: String,
    val description: String,
    val primaryActionLabel: String? = null,
) {
    DASHBOARD(
        label = "Dashboard",
        description = "Resumen operativo del panel y acceso rápido a los modulos.",
        primaryActionLabel = "Actualizar",
    ),
    USERS(
        label = "Usuarios",
        description = "Gestion de usuarios administrativos y roles asignados.",
        primaryActionLabel = "Nuevo usuario",
    ),
    ROLES(
        label = "Roles",
        description = "Definicion de roles y permisos del panel.",
        primaryActionLabel = "Nuevo rol",
    ),
    SPECIALTIES(
        label = "Especialidades",
        description = "Catálogo base que organiza el contenido y los exámenes.",
        primaryActionLabel = "Nueva especialidad",
    ),
    COMMUNITIES(
        label = "Comunidades",
        description = "Catálogo de comunidades autonomas para acotar exámenes por territorio.",
        primaryActionLabel = "Nueva comunidad",
    ),
    TAGS(
        label = "Etiquetas",
        description = "Taxonomia que alimenta la busqueda global y la clasificación de contenido.",
        primaryActionLabel = "Nueva etiqueta",
    ),
    NEWS_TYPES(
        label = "Tipos de noticia",
        description = "Catálogo de tipos editoriales y estados para comunicación interna.",
        primaryActionLabel = "Nuevo tipo",
    ),
    NEWS(
        label = "Noticias",
        description = "Comunicados, bloques editoriales y tarjetas programables.",
        primaryActionLabel = "Nueva noticia",
    ),
    EXAMS(
        label = "Exámenes",
        description = "Metadatos, publicación y preguntas de cada examen.",
        primaryActionLabel = "Crear examen",
    ),
    QUESTIONS_RESOURCES(
        label = "Preguntas y recursos",
        description = "Editor de preguntas con 4 opciones, respuesta correcta y recursos enlazados.",
        primaryActionLabel = "Nueva pregunta",
    ),
    EXAM_ACCESS(
        label = "Accesos",
        description = "Concesion y revisión de accesos a exámenes por usuario.",
        primaryActionLabel = "Nuevo acceso",
    ),
    STORE(
        label = "Tienda",
        description = "Catálogo básico de items y productos internos.",
        primaryActionLabel = "Nuevo item",
    ),
    TICKETS(
        label = "Tickets",
        description = "Saldos, movimientos y ajustes manuales de carteras de tickets.",
        primaryActionLabel = "Nuevo ajuste",
    ),
    SUPPORT(
        label = "Soporte",
        description = "Conversaciones, notas internas y respuestas del equipo.",
        primaryActionLabel = null,
    ),
    MODERATION(
        label = "Moderación",
        description = "Acciones administrativas y registro de medidas sobre usuarios.",
        primaryActionLabel = "Nueva acción",
    ),
    SETTINGS(
        label = "Ajustes",
        description = "Preferencias locales y flags del panel administrativo.",
        primaryActionLabel = "Actualizar",
    ),
    AUDIT(
        label = "Auditoría",
        description = "Linea temporal de operaciones administrativas y cambios clave.",
        primaryActionLabel = "Actualizar",
    ),
}
