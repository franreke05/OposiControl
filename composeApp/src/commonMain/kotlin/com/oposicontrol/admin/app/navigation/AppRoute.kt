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
    }

    sealed interface Publication : AppRoute {
        data object News : Publication
        data object NewsTypes : Publication
        data object Assets : Publication
    }

    sealed interface Users : AppRoute {
        data object List : Users
        data class Detail(val userId: String) : Users
    }

    sealed interface Commercial : AppRoute {
        data object Catalog : Commercial
        data object Plans : Commercial
        data object Tickets : Commercial
        data object Subscriptions : Commercial
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
        data object Events : Analytics
        data object Performance : Analytics
    }

    sealed interface System : AppRoute {
        data object Roles : System
        data object Audit : System
        data object Settings : System
        data object Diagnostics : System
    }
}
