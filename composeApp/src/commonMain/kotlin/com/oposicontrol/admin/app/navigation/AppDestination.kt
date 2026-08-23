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
        description = "Resumen operativo del panel y acceso rapido a los modulos.",
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
        description = "Catalogo base que organiza el contenido y los examenes.",
        primaryActionLabel = "Nueva especialidad",
    ),
    COMMUNITIES(
        label = "Comunidades",
        description = "Catalogo de comunidades autonomas para acotar examenes por territorio.",
        primaryActionLabel = "Nueva comunidad",
    ),
    TAGS(
        label = "Etiquetas",
        description = "Taxonomia que alimenta la busqueda global y la clasificacion de contenido.",
        primaryActionLabel = "Nueva etiqueta",
    ),
    NEWS_TYPES(
        label = "Tipos de noticia",
        description = "Catalogo de tipos editoriales y estados para comunicacion interna.",
        primaryActionLabel = "Nuevo tipo",
    ),
    NEWS(
        label = "Noticias",
        description = "Comunicados, bloques editoriales y tarjetas programables.",
        primaryActionLabel = "Nueva noticia",
    ),
    EXAMS(
        label = "Examenes",
        description = "Metadatos, publicacion y agrupacion de preguntas por especialidad.",
        primaryActionLabel = "Crear examen",
    ),
    QUESTIONS_RESOURCES(
        label = "Preguntas y recursos",
        description = "Editor de preguntas con 4 opciones, respuesta correcta y recursos enlazados.",
        primaryActionLabel = "Nueva pregunta",
    ),
    EXAM_ACCESS(
        label = "Accesos",
        description = "Concesion y revision de accesos a examenes por usuario.",
        primaryActionLabel = "Nuevo acceso",
    ),
    STORE(
        label = "Tienda",
        description = "Catalogo basico de items y productos internos.",
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
        label = "Moderacion",
        description = "Acciones administrativas y registro de medidas sobre usuarios.",
        primaryActionLabel = "Nueva accion",
    ),
    SETTINGS(
        label = "Ajustes",
        description = "Preferencias locales y flags del panel administrativo.",
        primaryActionLabel = "Actualizar",
    ),
    AUDIT(
        label = "Auditoria",
        description = "Linea temporal de operaciones administrativas y cambios clave.",
        primaryActionLabel = "Actualizar",
    ),
}
