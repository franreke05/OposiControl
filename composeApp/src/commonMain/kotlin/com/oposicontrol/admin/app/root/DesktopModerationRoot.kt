package com.oposicontrol.admin.app.root

import androidx.compose.runtime.Composable
import com.oposicontrol.admin.app.navigation.AdminSection
import com.oposicontrol.admin.core.common.config.AppConfig

@Composable
fun DesktopModerationRoot(
    appConfig: AppConfig,
) {
    AppRoot(
        appConfig = appConfig,
        sections = listOf(
            AdminSection.DASHBOARD,
            AdminSection.SPECIALTIES,
            AdminSection.TAGS,
            AdminSection.EXAMS,
            AdminSection.QUESTIONS_RESOURCES,
            AdminSection.NEWS,
            AdminSection.USERS,
            AdminSection.ROLES,
            AdminSection.EXAM_ACCESS,
            AdminSection.TICKETS,
            AdminSection.SUPPORT,
            AdminSection.MODERATION,
            AdminSection.AUDIT,
            AdminSection.SETTINGS,
        ),
    )
}
