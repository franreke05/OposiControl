package com.oposicontrol.admin.app.navigation

/**
 * Typed application routes. Feature screens may still be legacy adapters, but
 * route ownership stays here so desktop and mobile cannot drift apart.
 */
sealed interface AppRoute {
    data object Auth : AppRoute
    data object Home : AppRoute

    sealed interface Content : AppRoute {
        data object Exams : Content
        data class ExamDetail(val examId: String) : Content
        data object Questions : Content
        data object Resources : Content
        data object Imports : Content
        data object Taxonomy : Content

        /** Comunidades autónomas (`/api/v1/admin/comunidades-autonomas`). */
        data object Communities : Content

        /** Etiquetas de clasificación (`/api/v1/admin/etiquetas`). */
        data object Tags : Content
    }

    sealed interface Publication : AppRoute {
        data object News : Publication
        data object NewsTypes : Publication
    }

    /** Clasificacion, ligas y posicion por opositor. Solo consulta. */
    data object Rankings : AppRoute

    sealed interface Users : AppRoute {
        data object List : Users
        data class Detail(val userId: String) : Users
    }

    sealed interface Commercial : AppRoute {
        data object Catalog : Commercial
        data object Plans : Commercial
        data object Tickets : Commercial
        data object Subscriptions : Commercial

        /** OposiMarket: packs de OT, servicios, promociones y reglas de pop-up. */
        data object Market : Commercial

        // Las tres siguientes abren la MISMA pantalla de Market, cada una en su
        // pestaña. Son entradas de menú, no pantallas nuevas: el backend, el
        // repositorio y la tabla ya existen dentro de Market, y duplicarlos daría
        // dos sitios donde mantener lo mismo. Lo que aportan es encontrarlas —
        // quien busca "Promociones" en el menú no tiene por qué saber que viven
        // dentro de Market.
        data object Consumables : Commercial
        data object Promotions : Commercial
        data object PopupRules : Commercial
    }

    sealed interface Community : AppRoute {
        data object OposiRed : Community
        data class Thread(val threadId: String) : Community
        data class ModerationDetail(val reportId: String) : Community
    }

    sealed interface Reports : AppRoute {
        data object List : Reports
        data class Detail(val reportId: String) : Reports
    }

    sealed interface OposiTester : AppRoute {
        data object List : OposiTester
        data class Detail(val testerId: String) : OposiTester
    }

    sealed interface Support : AppRoute {
        data object Inbox : Support
    }

    sealed interface Moderation : AppRoute {
        data object Queue : Moderation
    }

    sealed interface Analytics : AppRoute {
        data object Home : Analytics
        data object Performance : Analytics
    }

    sealed interface System : AppRoute {
        data object Roles : System
        data object Audit : System
        data object Settings : System
        data object Status : System
        data object Diagnostics : System
    }
}
