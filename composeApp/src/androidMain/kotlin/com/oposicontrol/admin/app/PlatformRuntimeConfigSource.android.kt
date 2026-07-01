package com.oposicontrol.admin.app.bootstrap

import com.oposicontrol.admin.app.BuildConfig
import com.oposicontrol.admin.core.common.config.AppConfigResolver
import com.oposicontrol.admin.core.common.config.MapRuntimeConfigSource
import com.oposicontrol.admin.core.common.config.RuntimeConfigSource

actual fun platformRuntimeConfigSource(): RuntimeConfigSource {
    return MapRuntimeConfigSource(
        mapOf(
            AppConfigResolver.KEY_APP_ENV to BuildConfig.APP_ENV,
            AppConfigResolver.KEY_BASE_URL to BuildConfig.BASE_URL,
            AppConfigResolver.KEY_BACKEND_TOKEN to BuildConfig.BACKEND_TOKEN,
            AppConfigResolver.KEY_SUPABASE_URL to BuildConfig.SUPABASE_URL,
            AppConfigResolver.KEY_SUPABASE_ANON_KEY to BuildConfig.SUPABASE_ANON_KEY,
            AppConfigResolver.KEY_SUPABASE_BUCKET_EXAM_RESOURCES to BuildConfig.SUPABASE_BUCKET_EXAM_RESOURCES,
            AppConfigResolver.KEY_ENABLE_LOGS to BuildConfig.ENABLE_LOGS.toString(),
            AppConfigResolver.KEY_ENABLE_ANALYTICS to BuildConfig.ENABLE_ANALYTICS.toString(),
            AppConfigResolver.KEY_APP_NAME to "OposiControl Admin",
            AppConfigResolver.KEY_APP_VERSION to "1.0.0",
        ),
    )
}
