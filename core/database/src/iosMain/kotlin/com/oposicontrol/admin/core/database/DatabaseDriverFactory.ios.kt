package com.oposicontrol.admin.core.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.oposicontrol.admin.core.common.platform.AppPlatformContext

actual class DatabaseDriverFactory actual constructor(
    platformContext: AppPlatformContext,
) {
    actual fun createDriver(): SqlDriver {
        return NativeSqliteDriver(
            schema = AdminDatabase.Schema,
            name = "oposicontrol.db",
        )
    }
}
