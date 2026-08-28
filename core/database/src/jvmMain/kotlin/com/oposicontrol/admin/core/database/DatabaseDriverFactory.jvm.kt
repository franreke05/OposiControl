package com.oposicontrol.admin.core.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.oposicontrol.admin.core.common.platform.AppPlatformContext
import java.io.File

private const val DATABASE_FILE_NAME = "oposicontrol-desktop.db"
private const val APP_DATA_DIR_NAME = "OposiControl"

actual class DatabaseDriverFactory actual constructor(
    platformContext: AppPlatformContext,
) {
    actual fun createDriver(): SqlDriver {
        val databasePath = resolveDesktopDatabaseFile()
        migrateLegacyDatabaseIfNeeded(databasePath)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${databasePath.absolutePath}")
        if (!databasePath.exists()) {
            AdminDatabase.Schema.create(driver)
        }
        return driver
    }
}

/**
 * Per-OS user-data directory for the desktop DB, so it survives regardless
 * of which working directory the JVM happened to be launched from (a Gradle
 * task dir, an IDE module dir and a packaged app's install dir are all
 * different directories for the exact same logical install).
 */
internal fun resolveDesktopDatabaseFile(
    osName: String = System.getProperty("os.name").orEmpty(),
    env: (String) -> String? = System::getenv,
    userHome: String = System.getProperty("user.home").orEmpty(),
): File {
    val appDataDir = when {
        osName.contains("win", ignoreCase = true) ->
            File(env("APPDATA") ?: "$userHome/AppData/Roaming", APP_DATA_DIR_NAME)
        osName.contains("mac", ignoreCase = true) ->
            File(userHome, "Library/Application Support/$APP_DATA_DIR_NAME")
        else ->
            File(env("XDG_DATA_HOME") ?: "$userHome/.local/share", APP_DATA_DIR_NAME)
    }
    return File(appDataDir, DATABASE_FILE_NAME)
}

/**
 * Best-effort one-time migration: earlier builds created the DB wherever the
 * JVM happened to be launched from. If the new per-OS location has no DB yet
 * but a legacy one sits in the current working directory, carry it over
 * instead of silently starting the user over with an empty database. Never
 * throws — a failed copy just falls back to creating a fresh DB.
 */
internal fun migrateLegacyDatabaseIfNeeded(
    target: File,
    legacyFile: File = File(DATABASE_FILE_NAME),
) {
    if (target.exists() || !legacyFile.isFile) return
    runCatching {
        target.parentFile?.mkdirs()
        legacyFile.copyTo(target)
    }
}
