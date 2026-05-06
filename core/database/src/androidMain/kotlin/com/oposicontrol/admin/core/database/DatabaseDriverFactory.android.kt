package com.oposicontrol.admin.core.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.oposicontrol.admin.core.common.platform.AppPlatformContext

actual class DatabaseDriverFactory actual constructor(
    private val platformContext: AppPlatformContext,
) {
    actual fun createDriver(): SqlDriver {
        return AndroidSqliteDriver(
            schema = AdminDatabase.Schema,
            context = platformContext.applicationContext,
            name = "oposicontrol.db",
        )
    }
}
