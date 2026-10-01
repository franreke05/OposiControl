package com.oposicontrol.admin.core.database

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseDriverFactoryJvmTest {
    private lateinit var tempHome: File

    @BeforeTest
    fun setUp() {
        tempHome = Files.createTempDirectory("oposi-db-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempHome.deleteRecursively()
    }

    @Test
    fun windowsUsesAppDataRoaming() {
        val file = resolveDesktopDatabaseFile(
            osName = "Windows 11",
            env = { key -> if (key == "APPDATA") "${tempHome.path}\\AppData\\Roaming" else null },
            userHome = tempHome.path,
        )
        // SEC-001: host-independent (the Linux CI job runs :core:database:jvmTest); java.io.File joins with the HOST separator.
        assertEquals(
            "${tempHome.path}\\AppData\\Roaming\\OposiControl\\oposicontrol-desktop.db".replace('\\', '/'),
            file.path.replace('\\', '/'),
        )
    }

    @Test
    fun windowsFallsBackToUserHomeAppDataRoamingWhenAppDataIsUnset() {
        val file = resolveDesktopDatabaseFile(osName = "Windows 11", env = { null }, userHome = tempHome.path)
        assertTrue(file.path.replace('\\', '/').endsWith("AppData/Roaming/OposiControl/oposicontrol-desktop.db"))
    }

    @Test
    fun macUsesApplicationSupport() {
        val file = resolveDesktopDatabaseFile(osName = "Mac OS X", env = { null }, userHome = tempHome.path)
        assertEquals(File(tempHome, "Library/Application Support/OposiControl/oposicontrol-desktop.db").path, file.path)
    }

    @Test
    fun linuxUsesXdgDataHomeWhenSet() {
        val xdg = File(tempHome, "xdg-data")
        val file = resolveDesktopDatabaseFile(osName = "Linux", env = { key -> if (key == "XDG_DATA_HOME") xdg.path else null }, userHome = tempHome.path)
        assertEquals(File(xdg, "OposiControl/oposicontrol-desktop.db").path, file.path)
    }

    @Test
    fun linuxFallsBackToDotLocalShareWhenXdgDataHomeIsUnset() {
        val file = resolveDesktopDatabaseFile(osName = "Linux", env = { null }, userHome = tempHome.path)
        assertEquals(File(tempHome, ".local/share/OposiControl/oposicontrol-desktop.db").path, file.path)
    }

    @Test
    fun migrationCopiesALegacyDatabaseWhenTargetIsMissing() {
        val legacy = File(tempHome, "legacy.db").apply { writeText("legacy-bytes") }
        val target = File(tempHome, "new/oposicontrol-desktop.db")

        migrateLegacyDatabaseIfNeeded(target, legacy)

        assertTrue(target.exists())
        assertEquals("legacy-bytes", target.readText())
    }

    @Test
    fun migrationDoesNothingWhenTargetAlreadyExists() {
        val legacy = File(tempHome, "legacy.db").apply { writeText("legacy-bytes") }
        val target = File(tempHome, "existing.db").apply { writeText("current-bytes") }

        migrateLegacyDatabaseIfNeeded(target, legacy)

        assertEquals("current-bytes", target.readText())
    }

    @Test
    fun migrationDoesNothingWhenNoLegacyFileExists() {
        val target = File(tempHome, "new/oposicontrol-desktop.db")

        migrateLegacyDatabaseIfNeeded(target, File(tempHome, "does-not-exist.db"))

        assertFalse(target.exists())
    }

    @Test
    fun migrationCreatesTheParentDirectoryEvenWhenThereIsNoLegacyFileToMigrate() {
        val target = File(tempHome, "fresh-install/nested/oposicontrol-desktop.db")

        migrateLegacyDatabaseIfNeeded(target, File(tempHome, "does-not-exist.db"))

        assertTrue(target.parentFile.isDirectory)
        assertFalse(target.exists())
    }

    private fun legacyDbWithSession(file: File, refreshToken: String) {
        java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use { s ->
                s.execute(
                    "CREATE TABLE session_cache (user_id TEXT NOT NULL PRIMARY KEY, display_name TEXT NOT NULL, " +
                        "access_token TEXT NOT NULL, refresh_token TEXT NOT NULL, expires_at TEXT NOT NULL, role_ids_csv TEXT NOT NULL)",
                )
                s.execute("INSERT INTO session_cache VALUES ('u1','Admin','access-1','$refreshToken','2099-01-01T00:00:00Z','admin')")
            }
        }
    }

    private fun readSessionRow(file: File): List<String> =
        java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT user_id, access_token, refresh_token FROM session_cache").use { rs ->
                    buildList { while (rs.next()) add("${rs.getString(1)}|${rs.getString(2)}|${rs.getString(3)}") }
                }
            }
        }

    @Test
    fun migrationScrubsTheLegacyRefreshTokenAndKeepsTheRowIdempotently() {
        val legacy = File(tempHome, "legacy.db")
        legacyDbWithSession(legacy, "plain-refresh")
        val target = File(tempHome, "new/oposicontrol-desktop.db")

        migrateLegacyDatabaseIfNeeded(target, legacy)
        assertEquals(listOf("u1|access-1|"), readSessionRow(legacy))
        // The migrated copy was taken before the scrub, so it keeps the data it was given.
        assertTrue(target.exists())

        migrateLegacyDatabaseIfNeeded(target, legacy)
        assertEquals(listOf("u1|access-1|"), readSessionRow(legacy))
    }

    @Test
    fun scrubNeverDeletesAValidLegacySession() {
        val legacy = File(tempHome, "legacy.db")
        legacyDbWithSession(legacy, "")

        assertTrue(scrubLegacyDatabase(legacy))
        assertTrue(legacy.exists())
        assertEquals(listOf("u1|access-1|"), readSessionRow(legacy))
    }

    @Test
    fun scrubOfAnUnreadableLegacyFileDoesNotThrowAndRemovesIt() {
        val legacy = File(tempHome, "legacy.db").apply { writeText("not-a-database") }

        assertTrue(scrubLegacyDatabase(legacy))
        assertFalse(legacy.exists())
    }

    @Test
    fun migrationLeavesTheDirectoryAndCopyOwnerOnlyOnPosix() {
        val target = File(tempHome, "new/oposicontrol-desktop.db")
        org.junit.Assume.assumeTrue(Files.getFileStore(tempHome.toPath()).supportsFileAttributeView("posix"))
        val legacy = File(tempHome, "legacy.db")
        legacyDbWithSession(legacy, "x")

        migrateLegacyDatabaseIfNeeded(target, legacy)

        assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(target.parentFile.toPath())))
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(target.toPath())))
    }

    @Test
    fun restrictToOwnerLogsAWarningInsteadOfFailingSilently() {
        val logger = java.util.logging.Logger.getLogger("com.oposicontrol.admin.core.database")
        val records = mutableListOf<java.util.logging.LogRecord>()
        val handler = object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) { records += record }
            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.addHandler(handler)
        try {
            restrictToOwner(File(tempHome, "missing-dir/oposicontrol-desktop.db"))
        } finally {
            logger.removeHandler(handler)
        }
        assertTrue(records.any { it.level == java.util.logging.Level.WARNING })
    }
}
