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

    /**
     * Host-independent: the POSIX decision and the permission call are injected, so these run on Windows and Linux.
     * A non-POSIX store must never receive a permission call (and no WARN); a POSIX store gets 0700 dir / 0600 file.
     */
    private fun captureWarnings(block: () -> Unit): List<java.util.logging.LogRecord> {
        val logger = java.util.logging.Logger.getLogger("com.oposicontrol.admin.core.database")
        val records = mutableListOf<java.util.logging.LogRecord>()
        val handler = object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) { records += record }
            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.addHandler(handler)
        try { block() } finally { logger.removeHandler(handler) }
        return records.filter { it.level == java.util.logging.Level.WARNING }
    }

    @Test
    fun nonPosixStoreGetsNoPermissionCallsAndNoWarnings() {
        val dbFile = File(dir, "OposiControl/oposicontrol-desktop.db")
        val calls = mutableListOf<java.nio.file.Path>()
        val warnings = captureWarnings {
            ensureOwnerOnlyDir(dbFile.parentFile, isPosix = { false }, setPermissions = { p, _ -> calls.add(p) })
            openHardenedDesktopDriver(dbFile).close()
            restrictToOwner(dbFile, isPosix = { false }, setPermissions = { p, _ -> calls.add(p) })
        }
        assertTrue(dbFile.isFile)
        assertTrue(calls.isEmpty(), "no permission call on a non-POSIX store")
        assertTrue(warnings.isEmpty(), "no hardening WARN on a non-POSIX store")
    }

    @Test
    fun posixStoreGetsOwnerOnlyDirAndFile() {
        val dbFile = File(dir, "OposiControl/oposicontrol-desktop.db")
        val applied = mutableMapOf<java.nio.file.Path, Set<java.nio.file.attribute.PosixFilePermission>>()
        ensureOwnerOnlyDir(dbFile.parentFile, isPosix = { true }, setPermissions = { p, perms -> applied[p] = perms })
        openHardenedDesktopDriver(dbFile).close()
        restrictToOwner(dbFile, isPosix = { true }, setPermissions = { p, perms -> applied[p] = perms })
        assertEquals(PosixFilePermissions.fromString("rwx------"), applied[dbFile.parentFile.toPath()])
        assertEquals(PosixFilePermissions.fromString("rw-------"), applied[dbFile.toPath()])
    }

    @Test
    fun hardeningFailureIsLoggedWithoutTheValues() {
        val dbFile = File(dir, "OposiControl/oposicontrol-desktop.db").also { it.parentFile.mkdirs() }
        dbFile.writeText("x")
        val warnings = captureWarnings {
            restrictToOwner(dbFile, isPosix = { true }, setPermissions = { _, _ -> throw java.io.IOException("secret-detail") })
        }
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().message.orEmpty().contains("IOException"))
        assertFalse(warnings.single().message.orEmpty().contains("secret-detail"))
    }
}
