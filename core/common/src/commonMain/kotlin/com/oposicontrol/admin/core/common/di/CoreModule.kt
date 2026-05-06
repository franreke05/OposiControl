package com.oposicontrol.admin.core.common.di

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.analytics.ConsoleAnalyticsTracker
import com.oposicontrol.admin.core.common.analytics.NoOpAnalyticsTracker
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.coroutine.DefaultDispatcherProvider
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.logging.ConsoleLogger
import com.oposicontrol.admin.core.common.logging.Logger
import com.oposicontrol.admin.core.common.logging.NoOpLogger
import com.oposicontrol.admin.core.common.platform.AppPlatformContext
import org.koin.dsl.module

fun coreModule(
    appConfig: AppConfig,
    platformContext: AppPlatformContext,
) = module {
    single { appConfig }
    single { platformContext }
    single<DispatcherProvider> { DefaultDispatcherProvider }
    single<Logger> {
        if (appConfig.featureFlags.enableLogs) {
            ConsoleLogger()
        } else {
            NoOpLogger
        }
    }
    single<AnalyticsTracker> {
        if (appConfig.featureFlags.enableAnalytics) {
            ConsoleAnalyticsTracker(get())
        } else {
            NoOpAnalyticsTracker
        }
    }
}
