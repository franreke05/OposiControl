package com.oposicontrol.admin.app.bootstrap

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.di.coreModule
import com.oposicontrol.admin.core.common.platform.AppPlatformContext
import com.oposicontrol.admin.data.di.dataModule
import com.oposicontrol.admin.features.audit.di.auditFeatureModule
import com.oposicontrol.admin.features.auth.di.authFeatureModule
import com.oposicontrol.admin.features.communities.di.communitiesFeatureModule
import com.oposicontrol.admin.features.dashboard.di.dashboardFeatureModule
import com.oposicontrol.admin.features.examaccess.di.examAccessFeatureModule
import com.oposicontrol.admin.features.exams.di.examsFeatureModule
import com.oposicontrol.admin.features.forum.di.forumFeatureModule
import com.oposicontrol.admin.features.imports.di.importFeatureModule
import com.oposicontrol.admin.features.moderation.di.moderationFeatureModule
import com.oposicontrol.admin.features.opositester.di.opositesterFeatureModule
import com.oposicontrol.admin.features.news.di.newsFeatureModule
import com.oposicontrol.admin.features.questions.di.questionsFeatureModule
import com.oposicontrol.admin.features.newstypes.di.newsTypesFeatureModule
import com.oposicontrol.admin.features.reports.di.reportsFeatureModule
import com.oposicontrol.admin.features.roles.di.rolesFeatureModule
import com.oposicontrol.admin.features.settings.di.settingsFeatureModule
import com.oposicontrol.admin.features.settings.di.systemStatusFeatureModule
import com.oposicontrol.admin.features.specialties.di.specialtiesFeatureModule
import com.oposicontrol.admin.features.analytics.di.analyticsFeatureModule
import com.oposicontrol.admin.features.rankings.di.rankingsFeatureModule
import com.oposicontrol.admin.features.store.di.storeFeatureModule
import com.oposicontrol.admin.features.tags.di.tagsFeatureModule
import com.oposicontrol.admin.features.support.di.supportFeatureModule
import com.oposicontrol.admin.features.tickets.di.ticketsFeatureModule
import com.oposicontrol.admin.features.users.di.usersFeatureModule
import org.koin.core.module.Module

fun appModules(
    appConfig: AppConfig,
    platformContext: AppPlatformContext,
): List<Module> {
    return listOf(
        coreModule(appConfig, platformContext),
        dataModule,
        authFeatureModule,
        dashboardFeatureModule,
        usersFeatureModule,
        rolesFeatureModule,
        specialtiesFeatureModule,
        communitiesFeatureModule,
        tagsFeatureModule,
        newsTypesFeatureModule,
        newsFeatureModule,
        examsFeatureModule,
        examAccessFeatureModule,
        questionsFeatureModule,
        importFeatureModule,
        analyticsFeatureModule,
        rankingsFeatureModule,
        storeFeatureModule,
        ticketsFeatureModule,
        supportFeatureModule,
        moderationFeatureModule,
        settingsFeatureModule,
        systemStatusFeatureModule,
        auditFeatureModule,
        reportsFeatureModule,
        forumFeatureModule,
        opositesterFeatureModule,
    )
}
