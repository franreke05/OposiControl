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
        assertEquals(File("${tempHome.path}\\AppData\\Roaming\\OposiControl\\oposicontrol-desktop.db").path, file.path)
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
}
