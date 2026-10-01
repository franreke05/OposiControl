package com.oposicontrol.admin.core.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.oposicontrol.admin.core.common.platform.AppPlatformContext
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties

private const val DATABASE_FILE_NAME = "oposicontrol-desktop.db"
private const val APP_DATA_DIR_NAME = "OposiControl"

actual class DatabaseDriverFactory actual constructor(
    platformContext: AppPlatformContext,
) {
    actual fun createDriver(): SqlDriver {
        val databasePath = resolveDesktopDatabaseFile()
        migrateLegacyDatabaseIfNeeded(databasePath)
        return openHardenedDesktopDriver(databasePath)
    }
}

/**
 * MASTER6-AUTH-001: `secure_delete=ON` on every connection so a DELETE/UPDATE zero-fills the freed bytes (a scrubbed
 * legacy refresh token does not linger in free pages), and owner-only permissions on the DB directory/file where
 * the platform has POSIX permissions. Remaining limits are documented in docs/engineering/SECURITY.md.
 */
internal fun openHardenedDesktopDriver(databasePath: File): SqlDriver {
    val properties = Properties().apply { setProperty("secure_delete", "on") }
    val isNew = !databasePath.exists()
    val driver = JdbcSqliteDriver("jdbc:sqlite:${databasePath.absolutePath}", properties)
    if (isNew) {
        AdminDatabase.Schema.create(driver)
    }
    restrictToOwner(databasePath)
    return driver
}

/** 0700 on the directory, 0600 on the DB file, on POSIX file systems only (Windows inherits per-user ACLs). */
internal fun restrictToOwner(databasePath: File) {
    runCatching {
        val dir = databasePath.parentFile?.toPath()
        if (dir != null && Files.getFileStore(dir).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
        }
        val file = databasePath.toPath()
        if (Files.exists(file) && Files.getFileStore(file).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        }
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
    // Always ensure the target's parent directory exists, even when there's
    // nothing to migrate -- createDriver() opens a JDBC connection at this
    // exact path right after calling this function, and the SQLite JDBC
    // driver does not create missing parent directories itself (it only
    // creates the database file, given a directory that already exists).
    // Without this, the very first run on a machine with no prior
    // OposiControl app-data folder throws SQLException("... does not
    // exist") before Login can even be constructed -- confirmed via a real
    // crash log during Task 8's runtime verification.
    runCatching { target.parentFile?.mkdirs() }
    if (target.exists() || !legacyFile.isFile) return
    runCatching { legacyFile.copyTo(target) }
}
