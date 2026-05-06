package com.oposicontrol.admin.core.database

import com.oposicontrol.admin.core.common.platform.AppPlatformContext
import app.cash.sqldelight.db.SqlDriver

expect class DatabaseDriverFactory(
    platformContext: AppPlatformContext,
) {
    fun createDriver(): SqlDriver
}
