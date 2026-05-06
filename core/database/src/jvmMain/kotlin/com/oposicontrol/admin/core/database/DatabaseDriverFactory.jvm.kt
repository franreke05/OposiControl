package com.oposicontrol.admin.core.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.oposicontrol.admin.core.common.platform.AppPlatformContext
import java.io.File

actual class DatabaseDriverFactory actual constructor(
    platformContext: AppPlatformContext,
) {
    actual fun createDriver(): SqlDriver {
        val databasePath = File("oposicontrol-desktop.db")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${databasePath.absolutePath}")
        if (!databasePath.exists()) {
            AdminDatabase.Schema.create(driver)
        }
        return driver
    }
}
