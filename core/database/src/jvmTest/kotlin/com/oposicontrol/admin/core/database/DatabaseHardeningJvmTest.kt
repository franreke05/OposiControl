package com.oposicontrol.admin.core.database

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** MASTER6-AUTH-001: secure_delete on every desktop connection and owner-only permissions where POSIX exists. */
class DatabaseHardeningJvmTest {
    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("oposi-harden-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun containsBytes(file: File, marker: String): Boolean {
        val haystack = String(file.readBytes(), Charsets.ISO_8859_1)
        return haystack.contains(marker)
    }

    @Test
    fun deletedLegacyRefreshTokenIsZeroFilledOnDisk() {
        val marker = "LEGACY-REFRESH-TOKEN-MARKER-0123456789"
        val dbFile = File(dir, "OposiControl/oposicontrol-desktop.db").also { it.parentFile.mkdirs() }
        val driver = openHardenedDesktopDriver(dbFile)
        val db = AdminDatabase(driver)
        db.sessionQueries.upsertSession("u1", "Admin", "access", marker, "2026-10-01T01:00:00Z", "admin")
        db.sessionQueries.clearSession()
        driver.close()

        assertFalse(containsBytes(dbFile, marker), "secure_delete debe sobrescribir el token borrado")
        File(dbFile.path + "-journal").takeIf { it.exists() }?.let { assertFalse(containsBytes(it, marker)) }
    }

    @Test
    fun secureDeletePragmaIsOnForTheConnection() {
        val dbFile = File(dir, "x.db")
        val driver = openHardenedDesktopDriver(dbFile)
        val value = driver.executeQuery(
            null, "PRAGMA secure_delete", { c: app.cash.sqldelight.db.SqlCursor ->
                app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getLong(0) else null)
            }, 0,
        ).value
        driver.close()
        assertEquals(1L, value)
    }

    /** POSIX-only: Assume-skipped on Windows (inherited ACL); it executes in CI (ubuntu-latest, `:core:database:jvmTest`). */
    @Test
    fun databaseAndDirectoryAreOwnerOnlyOnPosix() {
        val dbFile = File(dir, "OposiControl/oposicontrol-desktop.db").also { it.parentFile.mkdirs() }
        org.junit.Assume.assumeTrue(Files.getFileStore(dir.toPath()).supportsFileAttributeView("posix"))
        // A pre-existing world-readable directory must be tightened, not just left as created.
        Files.setPosixFilePermissions(dbFile.parentFile.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        openHardenedDesktopDriver(dbFile).close()
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(dbFile.toPath()))
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(dbFile.parentFile.toPath()))
    }

    /** Portable part: on a non-POSIX store (Windows) hardening is a silent no-op (no WARN) and the DB still opens. */
    @Test
    fun nonPosixStoreOpensTheDatabaseWithoutHardeningWarnings() {
        org.junit.Assume.assumeFalse(Files.getFileStore(dir.toPath()).supportsFileAttributeView("posix"))
        val logger = java.util.logging.Logger.getLogger("com.oposicontrol.admin.core.database")
        val records = mutableListOf<java.util.logging.LogRecord>()
        val handler = object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) { records += record }
            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.addHandler(handler)
        val dbFile = File(dir, "OposiControl/oposicontrol-desktop.db")
        try {
            ensureOwnerOnlyDir(dbFile.parentFile)
            openHardenedDesktopDriver(dbFile).close()
        } finally {
            logger.removeHandler(handler)
        }
        assertTrue(dbFile.isFile)
        assertTrue(records.none { it.level == java.util.logging.Level.WARNING }, "no hardening WARN on a non-POSIX store")
    }
}
