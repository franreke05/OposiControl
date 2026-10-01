package com.oposicontrol.admin.core.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.oposicontrol.admin.core.common.platform.AppPlatformContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.sql.DriverManager
import java.util.Properties
import java.util.logging.Level
import java.util.logging.Logger

private const val DATABASE_FILE_NAME = "oposicontrol-desktop.db"
private const val APP_DATA_DIR_NAME = "OposiControl"

private val hardeningLog: Logger = Logger.getLogger("com.oposicontrol.admin.core.database")

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

/**
 * 0700 on the directory, 0600 on the DB file, on POSIX file systems only (Windows inherits per-user ACLs).
 * MASTER7-SEC-001: a failure is not swallowed: it is logged (WARN, file name and exception class only, no values).
 */
internal fun restrictToOwner(
    databasePath: File,
    isPosix: (Path) -> Boolean = { Files.getFileStore(it).supportsFileAttributeView("posix") },
    setPermissions: (Path, Set<PosixFilePermission>) -> Unit = { path, perms -> Files.setPosixFilePermissions(path, perms) },
) {
    runCatching {
        val dir = databasePath.parentFile?.toPath()
        if (dir != null && isPosix(dir)) {
            setPermissions(dir, PosixFilePermissions.fromString("rwx------"))
        }
        val file = databasePath.toPath()
        if (Files.exists(file) && isPosix(file)) {
            setPermissions(file, PosixFilePermissions.fromString("rw-------"))
        }
    }.onFailure { error ->
        hardeningLog.log(
            Level.WARNING,
            "Owner-only hardening failed for ${databasePath.name}: ${error.javaClass.simpleName}",
        )
    }
}

/** Creates [dir] and, on POSIX stores, makes it 0700 before any DB or legacy copy is placed in it. */
internal fun ensureOwnerOnlyDir(
    dir: File,
    isPosix: (Path) -> Boolean = { Files.getFileStore(it).supportsFileAttributeView("posix") },
    setPermissions: (Path, Set<PosixFilePermission>) -> Unit = { path, perms -> Files.setPosixFilePermissions(path, perms) },
) {
    runCatching {
        dir.mkdirs()
        if (isPosix(dir.toPath())) {
            setPermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------"))
        }
    }.onFailure { error ->
        hardeningLog.log(Level.WARNING, "Owner-only directory setup failed: ${error.javaClass.simpleName}")
    }
}

/**
 * MASTER7-AUTH-002: a legacy DB left in the working directory may hold a plaintext refresh token. Blank only that
 * column (rows, access token and everything else stay), with `secure_delete` so the old bytes are zero-filled.
 * Idempotent: a second run finds nothing to blank. If the file cannot be scrubbed it is deleted with its sidecars,
 * because the migrated copy already holds the data. Never throws; returns true when the legacy file is clean or gone.
 */
internal fun scrubLegacyDatabase(legacyFile: File): Boolean {
    if (!legacyFile.isFile) return true
    val scrubbed = runCatching {
        val properties = Properties().apply { setProperty("secure_delete", "on") }
        DriverManager.getConnection("jdbc:sqlite:${legacyFile.absolutePath}", properties).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("UPDATE session_cache SET refresh_token = '' WHERE refresh_token <> ''")
            }
        }
    }.recoverCatching { error ->
        // A legacy DB without the table has no token to scrub.
        if (error.message?.contains("no such table") == true) 0 else throw error
    }
    if (scrubbed.isSuccess) return true
    hardeningLog.log(
        Level.WARNING,
        "Legacy database scrub failed (${scrubbed.exceptionOrNull()?.javaClass?.simpleName}); deleting ${legacyFile.name}",
    )
    val parent = legacyFile.absoluteFile.parentFile
    val deleted = listOf("", "-journal", "-wal", "-shm").map { File(parent, legacyFile.name + it) }
        .filter { it.exists() }
        .map { it.delete() }
        .all { it }
    if (!deleted) hardeningLog.log(Level.WARNING, "Legacy database ${legacyFile.name} could not be deleted")
    return deleted
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
    target.parentFile?.let { ensureOwnerOnlyDir(it) }
    if (!legacyFile.isFile) return
    if (!target.exists()) {
        val copied = runCatching { legacyFile.copyTo(target) }
            .onFailure { hardeningLog.log(Level.WARNING, "Legacy database copy failed: ${it.javaClass.simpleName}") }
            .isSuccess
        if (!copied) return
        restrictToOwner(target)
    }
    // Also when the target already exists: earlier builds left the plaintext original behind.
    if (legacyFile.absoluteFile.canonicalFile != target.absoluteFile.canonicalFile) scrubLegacyDatabase(legacyFile)
}
