# JVM/Desktop Foundation Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the JVM/Desktop foundation of OposiControl Admin technically correct (config resolution, SQLite location, routing/legacy convergence, Store↔Ktor boundary) without touching visual design, domain/DI architecture, or deleting legacy screens — Phase 0 before the Desktop visual redesign.

**Architecture:** Every fix is additive/surgical on top of the existing single `AppRoute`/`AppNavigator`, single `AppShell`, single `AppConfigResolver`/`RuntimeConfigSource`, and single Ktor backend boundary — no parallel router, no new client-side Supabase access, no schema change. Desktop reachability for Reports/Forum/OposiTester/Content Upload is unlocked by fixing how `AppRoot` picks content for a route, not by building new screens.

**Tech Stack:** Kotlin Multiplatform (JVM/Android/iOS), Compose Multiplatform Desktop, Koin, SqlDelight (JVM JDBC/SQLite driver), Ktor client (data module) + Ktor server (backend/ktor), kotlinx.serialization, kotlin.test + kotlinx-coroutines-test + ktor-client-mock.

**Spec:** The task brief pasted by the user (branch `integration/mobile-finish`, audited HEAD `916b2fa`) — reproduced in full in the conversation that produced this plan. No separate spec file exists; this plan's Global Constraints section is the extraction of that brief's binding rules.

## Global Constraints

- Branch base: `integration/mobile-finish`. No MAIN writes. No migrations. No force push. No new git worktrees.
- Do not redesign the visual layer: no layout/sidebar aesthetic/dashboard visual/table/branding/glass-component/color/typography changes.
- Do not rebuild shared domain, repositories, or DI wiring beyond the one repository swap explicitly required in Task 7.
- Do not delete legacy screens (`ModerationScreen`, `QuestionsResourcesScreen`, etc.) or `AdminSection`/`AppDestination`. Only routing/wiring changes.
- `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `BASE_URL`, `APP_ENV` remain the config surface. Service-role key never reaches a client. Never print/log secret values.
- No hardcoded secrets anywhere touched by this plan.
- One canonical `AppRoute`/`AppNavigator`/`AppShell` — no parallel Desktop router.
- Every task ends with its own real (non-cosmetic) automated test passing, plus the module compiles.
- Existing local desktop sessions/data must not be silently destroyed by the SQLite path change (best-effort migration required).

---

## Task 1: Config-cache-safe `.env` reading in `composeApp/build.gradle.kts`

**Files:**
- Modify: `composeApp/build.gradle.kts:12-21` (the `localEnvValue` function)

**Interfaces:**
- Consumes: nothing new.
- Produces: `localEnvValue(key: String): String`, same signature/behavior as today (env var wins, then `.env`), consumed unchanged by `localSupabaseUrl`/`localSupabasePublicKey` and the Android `buildConfigField`/desktop `jvmArgs`/`jvmRun` `systemProperty` calls later in the same file.

This is a Gradle build-script change; it has no `kotlin.test` unit test (a Gradle `Provider` evaluated at configuration time isn't something `kotlin.test` reaches). Its test is the manual configuration-cache check in Step 3.

- [ ] **Step 1: Replace the unsafe file read**

Current code (`composeApp/build.gradle.kts:12-21`):

```kotlin
fun localEnvValue(key: String): String {
    providers.environmentVariable(key).orNull?.let { return it }
    val envFile = rootProject.file(".env")
    if (!envFile.isFile) return ""
    return envFile.readLines()
        .firstOrNull { line -> line.trimStart().startsWith("$key=") }
        ?.substringAfter("=")
        ?.trim()
        .orEmpty()
}
```

Replace with:

```kotlin
fun localEnvValue(key: String): String {
    providers.environmentVariable(key).orNull?.let { return it }
    // providers.fileContents (not rootProject.file(...).readLines()) registers
    // .env as a tracked configuration-cache input: editing .env and re-running
    // with --configuration-cache now picks up the new value instead of silently
    // reusing whatever was cached from the first run.
    val envFileText = providers.fileContents(rootProject.layout.projectDirectory.file(".env")).asText.orNull
        ?: return ""
    return envFileText.lineSequence()
        .map { it.trim() }
        .firstOrNull { line -> line.isNotEmpty() && !line.startsWith("#") && line.startsWith("$key=") }
        ?.substringAfter("=")
        ?.trim()
        .orEmpty()
}
```

- [ ] **Step 2: Compile-check the build script**

Run: `.\gradlew.bat :composeApp:help --console=plain`
Expected: succeeds (proves the Kotlin DSL script still parses/evaluates; `help` is enough to force configuration).

- [ ] **Step 3: Verify configuration-cache picks up a `.env` edit (manual, not a unit test)**

```powershell
.\gradlew.bat :composeApp:jvmRun --configuration-cache --console=plain -q --dry-run
# then edit .env (e.g. touch SUPABASE_URL value), re-run:
.\gradlew.bat :composeApp:jvmRun --configuration-cache --console=plain -q --dry-run
```

Expected: second run either reports "Calculating task graph" (cache miss because the tracked file changed) rather than "Reusing configuration cache" silently applying stale values. Record the observed Gradle console line in the final report (Task 9's evidence), do not claim this passed without it.

- [ ] **Step 4: Commit**

```bash
git add composeApp/build.gradle.kts
git commit -m "fix(desktop): config-cache-safe .env reading in composeApp build script"
```

---

## Task 2: Layered, testable JVM `RuntimeConfigSource` (P0)

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/oposicontrol/admin/app/PlatformRuntimeConfigSource.jvm.kt`
- Create: `composeApp/src/jvmTest/kotlin/com/oposicontrol/admin/app/PlatformRuntimeConfigSourceJvmTest.kt`

**Interfaces:**
- Consumes: `com.oposicontrol.admin.core.common.config.RuntimeConfigSource` (existing interface, `fun read(key: String): String?`).
- Produces: `actual fun platformRuntimeConfigSource(): RuntimeConfigSource` (unchanged signature — `App.kt` calls it as-is), plus new testable internals: `open class JvmRuntimeConfigSource(...)`, `internal fun parseDotEnv(text: String): Map<String, String>`, `internal fun findDotEnvFile(startDir: File, maxDepth: Int): File?`, `internal fun loadDotEnv(startDir: File, maxDepth: Int = 8): Map<String, String>`.

This closes the actual P0 gap: today `platformRuntimeConfigSource()` only checks `-Doposi.<KEY>` then `System.getenv(KEY)`. Both are populated by Gradle (`:composeApp:run`, `:composeApp:jvmRun`, the packaged native distribution's baked-in `jvmArgs`) but **not** when a JVM `main()` is launched directly (e.g. Android Studio's gutter "Run" on `fun main()`), which spawns `java -cp ... MainKt` with none of Gradle's injected properties. Adding a `.env`-file fallback, discovered by walking up from the process's working directory, makes that path work too, without needing any IDE-specific run configuration.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.oposicontrol.admin.app

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlatformRuntimeConfigSourceJvmTest {
    private lateinit var tempRoot: File

    @BeforeTest
    fun setUp() {
        tempRoot = Files.createTempDirectory("oposi-config-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    @Test
    fun systemPropertyWinsOverEnvVarAndDotEnv() {
        val source = JvmRuntimeConfigSource(
            systemProperty = { key -> if (key == "oposi.BASE_URL") "https://from-system-property" else null },
            systemEnv = { key -> if (key == "BASE_URL") "https://from-env-var" else null },
            startDir = tempRoot,
        )
        assertEquals("https://from-system-property", source.read("BASE_URL"))
    }

    @Test
    fun envVarWinsOverDotEnvWhenNoSystemProperty() {
        val source = JvmRuntimeConfigSource(
            systemProperty = { null },
            systemEnv = { key -> if (key == "BASE_URL") "https://from-env-var" else null },
            startDir = tempRoot,
        )
        assertEquals("https://from-env-var", source.read("BASE_URL"))
    }

    @Test
    fun dotEnvIsTheLastResortFallback() {
        File(tempRoot, ".env").writeText("BASE_URL=https://from-dot-env\n")
        val source = JvmRuntimeConfigSource(
            systemProperty = { null },
            systemEnv = { null },
            startDir = tempRoot,
        )
        assertEquals("https://from-dot-env", source.read("BASE_URL"))
    }

    @Test
    fun missingKeyReturnsNullFromEveryLayer() {
        val source = JvmRuntimeConfigSource(
            systemProperty = { null },
            systemEnv = { null },
            startDir = tempRoot,
        )
        assertNull(source.read("SUPABASE_URL"))
    }

    @Test
    fun parseDotEnvSkipsBlankAndCommentLines() {
        val values = parseDotEnv(
            """
            # a comment
            
            APP_ENV=dev
            # another comment
            BASE_URL=http://localhost:8080
            """.trimIndent(),
        )
        assertEquals(mapOf("APP_ENV" to "dev", "BASE_URL" to "http://localhost:8080"), values)
    }

    @Test
    fun parseDotEnvStripsSurroundingQuotes() {
        val values = parseDotEnv("SUPABASE_ANON_KEY=\"quoted-value\"")
        assertEquals("quoted-value", values["SUPABASE_ANON_KEY"])
    }

    @Test
    fun parseDotEnvKeepsEqualsSignsInsideTheValue() {
        val values = parseDotEnv("BACKEND_TOKEN=abc=def==")
        assertEquals("abc=def==", values["BACKEND_TOKEN"])
    }

    @Test
    fun findDotEnvFileWalksUpParentDirectories() {
        val nested = File(tempRoot, "a/b/c").apply { mkdirs() }
        File(tempRoot, ".env").writeText("APP_ENV=dev\n")
        val found = findDotEnvFile(nested, maxDepth = 8)
        assertEquals(File(tempRoot, ".env").absolutePath, found?.absolutePath)
    }

    @Test
    fun findDotEnvFileReturnsNullWhenNoneExistsWithinMaxDepth() {
        val nested = File(tempRoot, "a/b/c").apply { mkdirs() }
        assertNull(findDotEnvFile(nested, maxDepth = 8))
    }
}
```

- [ ] **Step 2: Run to verify it fails (compile error — `JvmRuntimeConfigSource`/`parseDotEnv`/`findDotEnvFile` don't exist yet)**

Run: `.\gradlew.bat :composeApp:jvmTest --console=plain --tests "com.oposicontrol.admin.app.PlatformRuntimeConfigSourceJvmTest"`
Expected: compilation failure referencing the missing symbols.

- [ ] **Step 3: Implement**

Replace `composeApp/src/jvmMain/kotlin/com/oposicontrol/admin/app/PlatformRuntimeConfigSource.jvm.kt` with:

```kotlin
package com.oposicontrol.admin.app.bootstrap

import com.oposicontrol.admin.core.common.config.RuntimeConfigSource
import java.io.File

actual fun platformRuntimeConfigSource(): RuntimeConfigSource = JvmRuntimeConfigSource()

/**
 * JVM config resolution, checked in this order:
 * 1. `-Doposi.<KEY>` system property — what Gradle's `:composeApp:run`,
 *    `:composeApp:jvmRun` and the packaged native distribution's baked-in
 *    `jvmArgs` set (see composeApp/build.gradle.kts).
 * 2. `<KEY>` OS environment variable — a shell/CI that exported it directly.
 * 3. `.env` at the repo root, located by walking up from the JVM process's
 *    working directory. Covers running `MainKt` directly (e.g. Android
 *    Studio's gutter "Run" on `fun main()`), which bypasses Gradle entirely
 *    and therefore never receives 1 or 2.
 *
 * Never logs a resolved value (some of these are secrets). Exposed as an
 * open class with injectable lookups (not a private object calling
 * System.* directly) so every layer is unit-testable without touching real
 * process state — see PlatformRuntimeConfigSourceJvmTest.
 */
open class JvmRuntimeConfigSource(
    private val systemProperty: (String) -> String? = System::getProperty,
    private val systemEnv: (String) -> String? = System::getenv,
    private val startDir: File = File(System.getProperty("user.dir")),
) : RuntimeConfigSource {
    private val dotEnv: Map<String, String> by lazy { loadDotEnv(startDir) }

    override fun read(key: String): String? {
        systemProperty("oposi.$key")?.let { return it }
        systemEnv(key)?.let { return it }
        return dotEnv[key]
    }
}

internal fun loadDotEnv(startDir: File, maxDepth: Int = 8): Map<String, String> {
    val file = findDotEnvFile(startDir, maxDepth) ?: return emptyMap()
    return parseDotEnv(file.readText())
}

internal fun findDotEnvFile(startDir: File, maxDepth: Int): File? {
    var dir: File? = startDir.absoluteFile
    var depth = 0
    while (dir != null && depth <= maxDepth) {
        val candidate = File(dir, ".env")
        if (candidate.isFile) return candidate
        dir = dir.parentFile
        depth++
    }
    return null
}

internal fun parseDotEnv(text: String): Map<String, String> {
    val values = mutableMapOf<String, String>()
    for (rawLine in text.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val separator = line.indexOf('=')
        if (separator <= 0) continue
        val key = line.substring(0, separator).trim()
        var value = line.substring(separator + 1).trim()
        if (value.length >= 2 &&
            ((value.first() == '"' && value.last() == '"') || (value.first() == '\'' && value.last() == '\''))
        ) {
            value = value.substring(1, value.length - 1)
        }
        if (key.isNotEmpty()) values[key] = value
    }
    return values
}
```

Note the package stays `com.oposicontrol.admin.app.bootstrap` (matching the existing `expect fun platformRuntimeConfigSource()` in `composeApp/src/commonMain/kotlin/com/oposicontrol/admin/app/bootstrap/PlatformRuntimeConfigSource.kt` — the current file's `package com.oposicontrol.admin.app.bootstrap` line already matches despite its path under `.../app/PlatformRuntimeConfigSource.jvm.kt`; this plan keeps the file where it already is).

- [ ] **Step 4: Run to verify it passes**

Run: `.\gradlew.bat :composeApp:jvmTest --console=plain --tests "com.oposicontrol.admin.app.PlatformRuntimeConfigSourceJvmTest"`
Expected: all 9 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/jvmMain/kotlin/com/oposicontrol/admin/app/PlatformRuntimeConfigSource.jvm.kt composeApp/src/jvmTest/kotlin/com/oposicontrol/admin/app/PlatformRuntimeConfigSourceJvmTest.kt
git commit -m "fix(desktop): JVM config falls back to .env so running MainKt directly works"
```

---

## Task 3: Desktop SQLite database moved to a per-OS app-data directory

**Files:**
- Modify: `core/database/src/jvmMain/kotlin/com/oposicontrol/admin/core/database/DatabaseDriverFactory.jvm.kt`
- Create: `core/database/src/jvmTest/kotlin/com/oposicontrol/admin/core/database/DatabaseDriverFactoryJvmTest.kt`

**Interfaces:**
- Consumes: nothing new (`AppPlatformContext` stays the constructor param, unused on JVM as today).
- Produces: `actual class DatabaseDriverFactory` unchanged public shape (`actual fun createDriver(): SqlDriver`); new testable internals `internal fun resolveDesktopDatabaseFile(osName: String, env: (String) -> String?, userHome: String): File` and `internal fun migrateLegacyDatabaseIfNeeded(target: File, legacyFile: File)`.

Today the DB file is `File("oposicontrol-desktop.db")` — relative to whatever the process's working directory happens to be (Gradle module dir, IDE module dir, or a packaged app's install dir all differ), so the same install can silently "lose" its data by being launched a different way. This task pins it to the OS-conventional per-user data directory and preserves any DB already sitting in the current working directory with a one-time best-effort copy.

- [ ] **Step 1: Write the failing tests**

```kotlin
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
```

- [ ] **Step 2: Run to verify it fails**

Run: `.\gradlew.bat :core:database:jvmTest --console=plain --tests "com.oposicontrol.admin.core.database.DatabaseDriverFactoryJvmTest"`
Expected: compilation failure (`resolveDesktopDatabaseFile`/`migrateLegacyDatabaseIfNeeded` don't exist).

- [ ] **Step 3: Implement**

Replace `core/database/src/jvmMain/kotlin/com/oposicontrol/admin/core/database/DatabaseDriverFactory.jvm.kt` with:

```kotlin
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
```

- [ ] **Step 4: Run to verify it passes**

Run: `.\gradlew.bat :core:database:jvmTest --console=plain --tests "com.oposicontrol.admin.core.database.DatabaseDriverFactoryJvmTest"`
Expected: all 8 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add core/database/src/jvmMain/kotlin/com/oposicontrol/admin/core/database/DatabaseDriverFactory.jvm.kt core/database/src/jvmTest/kotlin/com/oposicontrol/admin/core/database/DatabaseDriverFactoryJvmTest.kt
git commit -m "fix(desktop): move SQLite DB to a per-OS app-data directory with legacy migration"
```

---

## Task 4: Navigation catalog — Desktop visibility + drop the wrong legacy fallback

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/oposicontrol/admin/app/navigation/AppNavigationCatalog.kt`
- Create: `composeApp/src/commonTest/kotlin/com/oposicontrol/admin/app/navigation/AppNavigationCatalogTest.kt`

**Interfaces:**
- Consumes: `AppRoute`, `NavigationVisibility` (existing).
- Produces: same `appNavigationGroups`/`toLegacySection()` public shape, different data.

Two independent fixes, both are data changes, not new mechanisms:

1. `Reports` and `OposiTester` menu items are hardcoded `NavigationVisibility.MOBILE_ONLY`, so `AppRoot`'s `toShellGroups(includeMobileOnly = compactLayout)` filters them out of the Desktop sidebar entirely — there's no button anywhere that can navigate there on Desktop. Making them `DESKTOP_AND_MOBILE` (the default) is a pure data change; `AppShell`'s sidebar/mobile-nav rendering is already visibility-driven.
2. `toLegacySection()` currently maps `AppRoute.Community.OposiRed -> AdminSection.MODERATION` and `AppRoute.Content.Imports -> AdminSection.QUESTIONS_RESOURCES`. Both are wrong fallbacks left over from before the newer screens existed: on Desktop today they make `AppRoot`'s `legacyRoute != null` branch win, rendering `ModerationScreen`/`QuestionsResourcesScreen` instead of the intended `ForumScreen`/`ContentUploadScreen` (Task 5 fixes the render branch; this task removes the wrong fallback and, as a side effect, also removes an existing latent bug where the AppShell header showed an unrelated "Nueva acción"/"Nueva pregunta" primary-action button while looking at the Forum tab or Content Upload on **mobile** too).

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.oposicontrol.admin.app.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppNavigationCatalogTest {
    @Test
    fun reportsIsVisibleOnDesktopNotJustMobile() {
        val item = appNavigationGroups.first { it.id == NavigationGroupId.REPORTS }.items.single()
        assertEquals(NavigationVisibility.DESKTOP_AND_MOBILE, item.metadata.visibility)
    }

    @Test
    fun opositesterIsVisibleOnDesktopNotJustMobile() {
        val item = appNavigationGroups.first { it.id == NavigationGroupId.OPOSITESTER }.items.single()
        assertEquals(NavigationVisibility.DESKTOP_AND_MOBILE, item.metadata.visibility)
    }

    @Test
    fun forumRouteHasNoLegacySectionFallback() {
        assertNull(AppRoute.Community.OposiRed.toLegacySection())
    }

    @Test
    fun contentImportsRouteHasNoLegacySectionFallback() {
        assertNull(AppRoute.Content.Imports.toLegacySection())
    }

    @Test
    fun contentQuestionsAndResourcesStillMapToTheLegacyEditor() {
        assertEquals(AdminSection.QUESTIONS_RESOURCES, AppRoute.Content.Questions.toLegacySection())
        assertEquals(AdminSection.QUESTIONS_RESOURCES, AppRoute.Content.Resources.toLegacySection())
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `.\gradlew.bat :composeApp:jvmTest --console=plain --tests "com.oposicontrol.admin.app.navigation.AppNavigationCatalogTest"`
Expected: FAIL (visibility still `MOBILE_ONLY`; `toLegacySection()` still non-null for those two routes).

- [ ] **Step 3: Implement**

In `AppNavigationCatalog.kt`, the Reports item (inside `NavigationGroupId.REPORTS` group) currently reads:

```kotlin
            item(
                AppRoute.Reports.List,
                "reports",
                "Informes",
                "Informes operativos y de moderacion.",
                NavigationGroupId.REPORTS,
                "reports",
                visibility = NavigationVisibility.MOBILE_ONLY,
            ),
```

Drop the `visibility` override (default is `DESKTOP_AND_MOBILE`):

```kotlin
            item(
                AppRoute.Reports.List,
                "reports",
                "Informes",
                "Informes operativos y de moderacion.",
                NavigationGroupId.REPORTS,
                "reports",
            ),
```

Same for the OposiTester item:

```kotlin
            item(
                AppRoute.OposiTester.List,
                "opositester",
                "OposiTester",
                "Incidencias de los usuarios",
                NavigationGroupId.OPOSITESTER,
                "tester",
            ),
```

In `toLegacySection()`, change:

```kotlin
    AppRoute.Community.OposiRed -> AdminSection.MODERATION
    is AppRoute.Community.Thread, is AppRoute.Community.ModerationDetail -> null
```

to:

```kotlin
    AppRoute.Community.OposiRed, is AppRoute.Community.Thread, is AppRoute.Community.ModerationDetail -> null
```

and change:

```kotlin
    AppRoute.Content.Questions, AppRoute.Content.Resources, AppRoute.Content.Imports -> AdminSection.QUESTIONS_RESOURCES
```

to:

```kotlin
    AppRoute.Content.Questions, AppRoute.Content.Resources -> AdminSection.QUESTIONS_RESOURCES
    AppRoute.Content.Imports -> null
```

`AppRoute.Moderation.Queue -> AdminSection.MODERATION` (a few lines below, unchanged) stays exactly as-is — it is the real, still-current canonical route for the legacy moderation-action-log screen (see Task 5's evidence note on why `ModerationScreen` is preserved, not replaced).

- [ ] **Step 4: Run to verify it passes**

Run: `.\gradlew.bat :composeApp:jvmTest --console=plain --tests "com.oposicontrol.admin.app.navigation.AppNavigationCatalogTest"`
Expected: all 5 tests PASS.

- [ ] **Step 5: Run the pre-existing navigator/catalog tests to confirm no regression**

Run: `.\gradlew.bat :composeApp:jvmTest --console=plain --tests "com.oposicontrol.admin.app.navigation.*"`
Expected: `AppNavigatorTest` and `AppNavigationCatalogTest` all PASS.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/oposicontrol/admin/app/navigation/AppNavigationCatalog.kt composeApp/src/commonTest/kotlin/com/oposicontrol/admin/app/navigation/AppNavigationCatalogTest.kt
git commit -m "fix(desktop): expose Reports/OposiTester in the desktop sidebar, drop wrong legacy fallbacks"
```

---

## Task 5: `AppRoot` content routing — unlock Desktop reachability (testable resolver)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/oposicontrol/admin/app/root/AppRoot.kt`
- Create: `composeApp/src/commonTest/kotlin/com/oposicontrol/admin/app/root/AppRootContentTargetTest.kt`

**Interfaces:**
- Consumes: `AppRoute` (existing).
- Produces: `internal enum class AppContentTarget { TAB_PAGER, CONTENT_UPLOAD, SYSTEM_STATUS, FORUM, THREAD_DETAIL, REPORTS_LIST, REPORTS_DETAIL, OPOSITESTER_LIST, OPOSITESTER_DETAIL, MOBILE_CONTENT_ROOT, LEGACY_SECTION, LEGACY_PLACEHOLDER }` and `internal fun resolveContentTarget(route: AppRoute, compactLayout: Boolean, showTabPager: Boolean, hasLegacyRoute: Boolean): AppContentTarget`. `AppRoot`'s content lambda must switch on this exact function — it is the single source of truth the tests exercise; do not let the composable re-derive the same conditions inline elsewhere.

This is the actual fix for section 4/5 of the brief: today `AppRoot`'s content `if/else if` chain gates `Content.Imports`, `Reports.Detail`, `Community.Thread` and `OposiTester.Detail` behind `compactLayout &&`, and never has a branch at all for `Reports.List`/`Community.OposiRed`/`OposiTester.List` outside the mobile tab pager — so on Desktop they either render the wrong legacy screen (Forum → `ModerationScreen`, Content Upload → `QuestionsResourcesScreen`, both via the `legacyRoute != null` fallback Task 4 just neutralized) or a bare `LegacyRouteAdapter` placeholder (Reports, OposiTester). `System.Status` is the one route that already works on Desktop today (its branch has no `compactLayout &&` guard) — this task makes the other six routes match that same unconditional pattern.

The mobile tab-pager behavior (`showTabPager`) is untouched: on mobile, `Reports.List`/`Community.OposiRed`/`OposiTester.List` are always inside `mobilePrimaryNavigation`, so `showTabPager` is `true` for them and wins before the new branches are ever reached — the new branches are therefore only reachable on Desktop (or, for the four routes that already existed under `compactLayout &&`, unchanged on mobile too, since removing an `&& compactLayout` that mobile already satisfied changes nothing there).

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.oposicontrol.admin.app.root

import com.oposicontrol.admin.app.navigation.AppRoute
import kotlin.test.Test
import kotlin.test.assertEquals

class AppRootContentTargetTest {
    @Test
    fun desktopReachesReportsListWithoutCompactLayout() {
        assertEquals(
            AppContentTarget.REPORTS_LIST,
            resolveContentTarget(AppRoute.Reports.List, compactLayout = false, showTabPager = false, hasLegacyRoute = false),
        )
    }

    @Test
    fun desktopReachesReportsDetailWithoutCompactLayout() {
        assertEquals(
            AppContentTarget.REPORTS_DETAIL,
            resolveContentTarget(AppRoute.Reports.Detail("R-1"), compactLayout = false, showTabPager = false, hasLegacyRoute = false),
        )
    }

    @Test
    fun desktopReachesForumWithoutCompactLayout() {
        assertEquals(
            AppContentTarget.FORUM,
            resolveContentTarget(AppRoute.Community.OposiRed, compactLayout = false, showTabPager = false, hasLegacyRoute = false),
        )
    }

    @Test
    fun desktopReachesThreadDetailWithoutCompactLayout() {
        assertEquals(
            AppContentTarget.THREAD_DETAIL,
            resolveContentTarget(AppRoute.Community.Thread("T-1"), compactLayout = false, showTabPager = false, hasLegacyRoute = false),
        )
    }

    @Test
    fun desktopReachesOpositesterListWithoutCompactLayout() {
        assertEquals(
            AppContentTarget.OPOSITESTER_LIST,
            resolveContentTarget(AppRoute.OposiTester.List, compactLayout = false, showTabPager = false, hasLegacyRoute = false),
        )
    }

    @Test
    fun desktopReachesOpositesterDetailWithoutCompactLayout() {
        assertEquals(
            AppContentTarget.OPOSITESTER_DETAIL,
            resolveContentTarget(AppRoute.OposiTester.Detail("OT-1"), compactLayout = false, showTabPager = false, hasLegacyRoute = false),
        )
    }

    @Test
    fun desktopReachesContentUploadWithoutCompactLayout() {
        assertEquals(
            AppContentTarget.CONTENT_UPLOAD,
            resolveContentTarget(AppRoute.Content.Imports, compactLayout = false, showTabPager = false, hasLegacyRoute = false),
        )
    }

    @Test
    fun desktopReachesSystemStatusWithoutCompactLayout() {
        assertEquals(
            AppContentTarget.SYSTEM_STATUS,
            resolveContentTarget(AppRoute.System.Status, compactLayout = false, showTabPager = false, hasLegacyRoute = false),
        )
    }

    @Test
    fun mobileTabPagerTakesPriorityWhenActiveEvenForAPrimaryTabRoute() {
        assertEquals(
            AppContentTarget.TAB_PAGER,
            resolveContentTarget(AppRoute.Reports.List, compactLayout = true, showTabPager = true, hasLegacyRoute = false),
        )
    }

    @Test
    fun mobileCompactContentRouteWithoutTabPagerUsesMobileContentRoot() {
        assertEquals(
            AppContentTarget.MOBILE_CONTENT_ROOT,
            resolveContentTarget(AppRoute.Content.Exams, compactLayout = true, showTabPager = false, hasLegacyRoute = true),
        )
    }

    @Test
    fun desktopFallsBackToTheLegacySectionWhenOneIsMapped() {
        assertEquals(
            AppContentTarget.LEGACY_SECTION,
            resolveContentTarget(AppRoute.Users.List, compactLayout = false, showTabPager = false, hasLegacyRoute = true),
        )
    }

    @Test
    fun unmappedRouteWithoutALegacySectionShowsThePlaceholder() {
        assertEquals(
            AppContentTarget.LEGACY_PLACEHOLDER,
            resolveContentTarget(AppRoute.System.Diagnostics, compactLayout = false, showTabPager = false, hasLegacyRoute = false),
        )
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `.\gradlew.bat :composeApp:jvmTest --console=plain --tests "com.oposicontrol.admin.app.root.AppRootContentTargetTest"`
Expected: compilation failure (`AppContentTarget`/`resolveContentTarget` don't exist).

- [ ] **Step 3: Implement — add the resolver, then rewire `AppRoot`'s content lambda to use it**

Add near the top-level of `AppRoot.kt` (below the imports, e.g. right after the `TEMPORARILY_DISABLE_LOGIN` constant):

```kotlin
internal enum class AppContentTarget {
    TAB_PAGER,
    CONTENT_UPLOAD,
    SYSTEM_STATUS,
    FORUM,
    THREAD_DETAIL,
    REPORTS_LIST,
    REPORTS_DETAIL,
    OPOSITESTER_LIST,
    OPOSITESTER_DETAIL,
    MOBILE_CONTENT_ROOT,
    LEGACY_SECTION,
    LEGACY_PLACEHOLDER,
}

/**
 * Single source of truth for "what renders in the AppShell content slot",
 * kept as a pure function (route/layout in, target out) so Desktop
 * reachability for Reports/Forum/OposiTester/Content Upload is unit-tested
 * without a Compose UI test harness — see AppRootContentTargetTest. The
 * composable below must switch on exactly this function's result, not
 * re-derive the same conditions inline.
 */
internal fun resolveContentTarget(
    route: AppRoute,
    compactLayout: Boolean,
    showTabPager: Boolean,
    hasLegacyRoute: Boolean,
): AppContentTarget = when {
    showTabPager -> AppContentTarget.TAB_PAGER
    route == AppRoute.Content.Imports -> AppContentTarget.CONTENT_UPLOAD
    route == AppRoute.System.Status -> AppContentTarget.SYSTEM_STATUS
    route == AppRoute.Community.OposiRed -> AppContentTarget.FORUM
    route is AppRoute.Community.Thread -> AppContentTarget.THREAD_DETAIL
    route is AppRoute.Reports.List -> AppContentTarget.REPORTS_LIST
    route is AppRoute.Reports.Detail -> AppContentTarget.REPORTS_DETAIL
    route is AppRoute.OposiTester.List -> AppContentTarget.OPOSITESTER_LIST
    route is AppRoute.OposiTester.Detail -> AppContentTarget.OPOSITESTER_DETAIL
    compactLayout && route is AppRoute.Content -> AppContentTarget.MOBILE_CONTENT_ROOT
    hasLegacyRoute -> AppContentTarget.LEGACY_SECTION
    else -> AppContentTarget.LEGACY_PLACEHOLDER
}
```

Then replace the content lambda body (the `if (showTabPager) { ... } else if (...) { ... } else { ... }` chain, currently `AppRoot.kt` roughly lines 218-274, i.e. everything between `) {` after the `AppShell(...)` parameter list and its matching closing `}`) with:

```kotlin
            ) {
                when (resolveContentTarget(route, compactLayout, showTabPager, legacyRoute != null)) {
                    AppContentTarget.TAB_PAGER -> MobilePrimaryTabPager(
                        routes = primaryTabRoutes,
                        currentIndex = currentPrimaryTabIndex,
                        onSettledIndexChange = { index -> navigator.selectPrimaryTab(primaryTabRoutes[index]) },
                    ) { pageRoute ->
                        when {
                            pageRoute is AppRoute.Reports.List -> ReportsScreen(
                                viewModel = koinInject(),
                                onOpenReport = { reportId -> navigator.push(AppRoute.Reports.Detail(reportId)) },
                            )
                            pageRoute is AppRoute.Community.OposiRed -> ForumScreen(
                                viewModel = koinInject(),
                                onOpenThread = { threadId -> navigator.push(AppRoute.Community.Thread(threadId)) },
                                onOpenPendingReports = { navigator.selectPrimaryTab(AppRoute.Reports.List) },
                            )
                            pageRoute is AppRoute.OposiTester.List -> TesterListScreen(
                                viewModel = koinInject(),
                                onOpenReport = { reportId -> navigator.push(AppRoute.OposiTester.Detail(reportId)) },
                            )
                            pageRoute == route -> if (legacyRoute != null) legacyRoute.content() else LegacyRouteAdapter(metadata.title, metadata.description)
                            else -> MobilePrimaryTabContent(
                                route = pageRoute,
                                environmentLabel = environmentLabel,
                                openSection = navigator::openSection,
                            )
                        }
                    }
                    AppContentTarget.CONTENT_UPLOAD -> ContentUploadScreen(viewModel = koinInject())
                    AppContentTarget.SYSTEM_STATUS -> {
                        val viewModel: SystemStatusViewModel = koinInject()
                        SystemStatusScreen(viewModel = viewModel)
                    }
                    AppContentTarget.FORUM -> ForumScreen(
                        viewModel = koinInject(),
                        onOpenThread = { threadId -> navigator.push(AppRoute.Community.Thread(threadId)) },
                        onOpenPendingReports = { navigator.selectPrimaryTab(AppRoute.Reports.List) },
                    )
                    AppContentTarget.THREAD_DETAIL -> {
                        val threadId = (route as AppRoute.Community.Thread).threadId
                        ThreadDetailScreen(
                            viewModel = koinInject(parameters = { parametersOf(threadId) }),
                            onOpenAssociatedReports = { navigator.selectPrimaryTab(AppRoute.Reports.List) },
                            onBack = { navigator.pop() },
                        )
                    }
                    AppContentTarget.REPORTS_LIST -> ReportsScreen(
                        viewModel = koinInject(),
                        onOpenReport = { reportId -> navigator.push(AppRoute.Reports.Detail(reportId)) },
                    )
                    AppContentTarget.REPORTS_DETAIL -> {
                        val reportId = (route as AppRoute.Reports.Detail).reportId
                        ReportDetailScreen(viewModel = koinInject(parameters = { parametersOf(reportId) }))
                    }
                    AppContentTarget.OPOSITESTER_LIST -> TesterListScreen(
                        viewModel = koinInject(),
                        onOpenReport = { reportId -> navigator.push(AppRoute.OposiTester.Detail(reportId)) },
                    )
                    AppContentTarget.OPOSITESTER_DETAIL -> {
                        val testerId = (route as AppRoute.OposiTester.Detail).testerId
                        TesterDetailScreen(viewModel = koinInject(parameters = { parametersOf(testerId) }))
                    }
                    AppContentTarget.MOBILE_CONTENT_ROOT -> MobileContentRoot(
                        appConfig = appConfig,
                        initialView = route.toMobileContentView(),
                        showNavigationChrome = false,
                    )
                    AppContentTarget.LEGACY_SECTION -> legacyRoute?.content()
                    AppContentTarget.LEGACY_PLACEHOLDER -> LegacyRouteAdapter(metadata.title, metadata.description)
                }
            }
```

Everything above this replaced block (breakpoint/`compactLayout`/`legacySection`/`legacyRoute`/`primaryTabRoutes`/`currentPrimaryTabIndex`/`showTabPager` computation, and the `AppShell(...)` call's parameters) is unchanged.

- [ ] **Step 4: Run to verify it passes**

Run: `.\gradlew.bat :composeApp:jvmTest --console=plain --tests "com.oposicontrol.admin.app.root.AppRootContentTargetTest"`
Expected: all 12 tests PASS.

- [ ] **Step 5: Compile the whole module (the composable itself has no unit test — this is the closest thing to one)**

Run: `.\gradlew.bat :composeApp:compileKotlinJvm --console=plain`
Expected: BUILD SUCCESSFUL — this exercises the `when` exhaustiveness (an enum `when` without `else` fails to compile if a case is missing) and every `koinInject`/composable call site's types.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/oposicontrol/admin/app/root/AppRoot.kt composeApp/src/commonTest/kotlin/com/oposicontrol/admin/app/root/AppRootContentTargetTest.kt
git commit -m "fix(desktop): Reports/Forum/Thread/OposiTester/Content Upload render on Desktop"
```

---

## Note on brief section 6 ("Desktop Shell") — no task needed

Audited `core/ui/src/commonMain/kotlin/com/oposicontrol/admin/core/ui/components/AppShell.kt`: the sidebar (`groups: List<ShellNavigationGroup>`), header/breadcrumbs, and back navigation (`canGoBack`/`onBack` → `BackHeader`) are already fully data-driven and rendered unconditionally for both the Desktop (`AppBreakpoint.EXPANDED`) and compact layouts — `BackHeader` in particular has no `compactLayout` guard on whether it *shows*, only on whether it also repeats the title inline. Task 4's sidebar-visibility fix and Task 5's routing fix are therefore sufficient to make the new routes render correctly with working back navigation on Desktop; `AppShell.kt` itself needs no change, which also means no visual redesign risk here. No task added for this section; it is closed by evidence, not by a diff.

---

## Task 6: Ktor canonical Store (`tienda`) admin boundary

**Files:**
- Modify: `backend/ktor/src/main/kotlin/com/oposicontrol/backend/AdminV1Routes.kt`
- Modify: `docs/integration/ENDPOINT_CATALOG.md`

**Interfaces:**
- Consumes: `AdminV1Gateway.request(method, path, body, prefer)` (existing private helper, already visible to new methods added to the same class), `sbJson` (existing `Json { encodeDefaults = true; explicitNulls = false }` instance already a property of `AdminV1Gateway`).
- Produces: `AdminV1Gateway.listArticulosTienda()/upsertArticuloTienda(...)`, `.listOfertasTienda()/upsertOfertaTienda(...)`, `.listRecursosTemporalesTienda()/upsertRecursoTemporalTienda(...)`, `.listCampanasRafaga()/upsertCampanaRafaga(...)`; new routes `GET/POST /api/v1/admin/tienda/articulos`, `/ofertas`, `/recursos-temporales`, `/rafagas` inside `fun Route.adminV1Routes(gateway: AdminV1Gateway)`. These automatically inherit the existing `authenticate("gateway-auth")` + admin-role gate already wrapping the whole `adminV1Routes(...)` call in `Application.kt:221-236` — no new auth wiring needed.

Evidence for why this task exists: `SupabaseCommerceRepositoryImpl` (data module) calls `${appConfig.supabaseUrl}/rest/v1/articulo_tienda` etc. directly from Desktop, using the anon key + user JWT — the only repository in `dataModule` that does not go through Ktor. `docs/integration/ENDPOINT_CATALOG.md` confirms no admin route exists yet for these four tables (only unrelated read-only `/api/v1/app/commercial/*` consumer routes exist). Row shapes below are taken verbatim from `SupabaseCommerceRepositoryImpl`'s existing DTOs — same table/column names, same `Int?` ids (these four tables use legacy serial integer ids, unlike `especialidades`/`examenes`'s UUIDs — this plan does not change the schema, only where the HTTP call to it originates).

- [ ] **Step 1: Add the DTOs and gateway methods**

In `AdminV1Routes.kt`, near the other response-wrapper DTOs (next to `AdminEspecialidadesResponse`/`AdminEspecialidadResponse`), add:

```kotlin
@Serializable
data class AdminArticulosTiendaResponse(val data: List<AdminArticuloTiendaDto>)
@Serializable
data class AdminArticuloTiendaResponse(val data: AdminArticuloTiendaDto)
@Serializable
data class AdminOfertasTiendaResponse(val data: List<AdminOfertaTiendaDto>)
@Serializable
data class AdminOfertaTiendaResponse(val data: AdminOfertaTiendaDto)
@Serializable
data class AdminRecursosTemporalesTiendaResponse(val data: List<AdminRecursoTemporalTiendaDto>)
@Serializable
data class AdminRecursoTemporalTiendaResponse(val data: AdminRecursoTemporalTiendaDto)
@Serializable
data class AdminCampanasRafagaResponse(val data: List<AdminCampanaRafagaDto>)
@Serializable
data class AdminCampanaRafagaResponse(val data: AdminCampanaRafagaDto)

@Serializable
data class AdminArticuloTiendaDto(
    val id: Int? = null,
    val nombre: String = "",
    @SerialName("tipo_articulo") val tipoArticulo: String = "",
    @SerialName("precio_tickets") val precioTickets: Int = 0,
    val activo: Boolean = true,
)

@Serializable
data class AdminOfertaTiendaDto(
    val id: Int? = null,
    @SerialName("articulo_tienda_id") val articuloTiendaId: Int = 0,
    val titulo: String = "",
    @SerialName("discount_percent") val discountPercent: Int? = null,
    @SerialName("special_price_tickets") val specialPriceTickets: Int? = null,
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null,
    val status: String = "draft",
)

@Serializable
data class AdminRecursoTemporalTiendaDto(
    val id: Int? = null,
    @SerialName("articulo_tienda_id") val articuloTiendaId: Int = 0,
    @SerialName("recurso_id") val recursoId: Int = 0,
    val titulo: String = "",
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null,
    @SerialName("access_duration_hours") val accessDurationHours: Int = 0,
    val status: String = "draft",
)

@Serializable
data class AdminCampanaRafagaDto(
    val id: Int? = null,
    val titulo: String = "",
    val descripcion: String = "",
    val tipo: String = "daily_streak",
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null,
    val multiplier: Double = 1.0,
    @SerialName("reward_tickets") val rewardTickets: Int = 0,
    @SerialName("linked_exam_id") val linkedExamId: Int? = null,
    @SerialName("linked_store_item_id") val linkedStoreItemId: Int? = null,
    val status: String = "draft",
)
```

Inside the `AdminV1Gateway` class body, near the other resource sections (e.g. after the AUDITORIA section, before the private helpers), add:

```kotlin
    // TIENDA (Store) -- proxies what the Desktop client used to call directly
    // on Supabase with the anon key + user JWT. Same tables/columns/ids
    // (legacy serial Int, not UUID) as before; only the origin of the HTTP
    // call moves behind the Ktor boundary. See KtorCommerceRepositoryImpl.
    fun listArticulosTienda(): List<AdminArticuloTiendaDto> =
        request("GET", "/rest/v1/articulo_tienda?select=*&order=nombre.asc", null).decodeList()

    fun upsertArticuloTienda(input: AdminArticuloTiendaDto): AdminArticuloTiendaDto =
        request(
            "POST",
            "/rest/v1/articulo_tienda?on_conflict=id",
            sbJson.encodeToString(listOf(input)),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<AdminArticuloTiendaDto>().first()

    fun listOfertasTienda(): List<AdminOfertaTiendaDto> =
        request("GET", "/rest/v1/oferta_tienda?select=*&order=starts_at.desc", null).decodeList()

    fun upsertOfertaTienda(input: AdminOfertaTiendaDto): AdminOfertaTiendaDto =
        request(
            "POST",
            "/rest/v1/oferta_tienda?on_conflict=id",
            sbJson.encodeToString(listOf(input)),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<AdminOfertaTiendaDto>().first()

    fun listRecursosTemporalesTienda(): List<AdminRecursoTemporalTiendaDto> =
        request("GET", "/rest/v1/recurso_temporal_tienda?select=*&order=starts_at.desc", null).decodeList()

    fun upsertRecursoTemporalTienda(input: AdminRecursoTemporalTiendaDto): AdminRecursoTemporalTiendaDto =
        request(
            "POST",
            "/rest/v1/recurso_temporal_tienda?on_conflict=id",
            sbJson.encodeToString(listOf(input)),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<AdminRecursoTemporalTiendaDto>().first()

    fun listCampanasRafaga(): List<AdminCampanaRafagaDto> =
        request("GET", "/rest/v1/campana_rafaga?select=*&order=starts_at.desc", null).decodeList()

    fun upsertCampanaRafaga(input: AdminCampanaRafagaDto): AdminCampanaRafagaDto =
        request(
            "POST",
            "/rest/v1/campana_rafaga?on_conflict=id",
            sbJson.encodeToString(listOf(input)),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<AdminCampanaRafagaDto>().first()
```

- [ ] **Step 2: Add the routes**

Inside `fun Route.adminV1Routes(gateway: AdminV1Gateway) { route("/api/v1/admin") { ... } }`, alongside the other `route("/especialidades") { ... }` etc. siblings, add:

```kotlin
        route("/tienda") {
            route("/articulos") {
                get { call.respond(AdminArticulosTiendaResponse(gateway.listArticulosTienda())) }
                post { call.respond(AdminArticuloTiendaResponse(gateway.upsertArticuloTienda(call.receive()))) }
            }
            route("/ofertas") {
                get { call.respond(AdminOfertasTiendaResponse(gateway.listOfertasTienda())) }
                post { call.respond(AdminOfertaTiendaResponse(gateway.upsertOfertaTienda(call.receive()))) }
            }
            route("/recursos-temporales") {
                get { call.respond(AdminRecursosTemporalesTiendaResponse(gateway.listRecursosTemporalesTienda())) }
                post { call.respond(AdminRecursoTemporalTiendaResponse(gateway.upsertRecursoTemporalTienda(call.receive()))) }
            }
            route("/rafagas") {
                get { call.respond(AdminCampanasRafagaResponse(gateway.listCampanasRafaga())) }
                post { call.respond(AdminCampanaRafagaResponse(gateway.upsertCampanaRafaga(call.receive()))) }
            }
        }
```

- [ ] **Step 3: Compile the backend**

Run: `.\gradlew.bat :backend:ktor:compileKotlin --console=plain`
Expected: BUILD SUCCESSFUL.

Note on test coverage: every existing CRUD resource in `AdminV1Gateway` (`especialidades`, `examenes`, `preguntas`, ...) has **no** unit test of its own — `AdminV1Gateway`'s HTTP calls go through `java.net.http.HttpClient` (not Ktor's client), it has a `private constructor`, and this codebase's backend tests (`AdminV1ForoTest`, `AdminV1FeedbackTest`, `AdminV1ReportesTest`) only cover small pure helper functions, never gateway HTTP calls. This task follows that same established convention rather than inventing new backend HTTP-mocking infrastructure inconsistent with the rest of the file. The real regression test for "does Desktop still bypass Supabase" is the client-side test in Task 7, which is the side that actually changes behavior.

- [ ] **Step 4: Update the endpoint catalog**

In `docs/integration/ENDPOINT_CATALOG.md`, in the `## \`/api/v1/admin/*\` — admin V1` table, add after `ADMIN-034`:

```
| `ADMIN-035` | GET/POST | `/api/v1/admin/tienda/articulos` | `SOURCE_VERIFIED` — replaces Desktop's direct Supabase call from `SupabaseCommerceRepositoryImpl` |
| `ADMIN-036` | GET/POST | `/api/v1/admin/tienda/ofertas` | `SOURCE_VERIFIED` |
| `ADMIN-037` | GET/POST | `/api/v1/admin/tienda/recursos-temporales` | `SOURCE_VERIFIED` |
| `ADMIN-038` | GET/POST | `/api/v1/admin/tienda/rafagas` | `SOURCE_VERIFIED` |
```

- [ ] **Step 5: Commit**

```bash
git add backend/ktor/src/main/kotlin/com/oposicontrol/backend/AdminV1Routes.kt docs/integration/ENDPOINT_CATALOG.md
git commit -m "feat(backend): canonical Ktor admin boundary for Store (tienda)"
```

---

## Task 7: Desktop Store repository moves behind the Ktor boundary

**Files:**
- Create: `data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/KtorCommerceRepositoryImpl.kt`
- Modify: `data/src/commonMain/kotlin/com/oposicontrol/admin/data/remote/api/BackendRoutes.kt`
- Modify: `data/src/commonMain/kotlin/com/oposicontrol/admin/data/di/DataModule.kt`
- Modify: `data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/SupabaseCommerceRepositoryImpl.kt` (mark `@Deprecated`, keep file — legacy is not deleted in this phase)
- Modify: `gradle/libs.versions.toml` (add `ktor-client-mock`)
- Modify: `data/build.gradle.kts` (test dependencies)
- Create: `data/src/jvmTest/kotlin/com/oposicontrol/admin/data/repository/KtorCommerceRepositoryImplTest.kt`

**Interfaces:**
- Consumes: `BackendRoutes.ADMIN_V1_TIENDA_ARTICULOS/OFERTAS/RECURSOS_TEMPORALES/RAFAGAS` (new constants), `BackendRoutes.url(baseUrl, path)` (existing), `NetworkResultAdapter.safeCall` (existing), `SessionLocalDataSource.currentSession()` (existing).
- Produces: `KtorCommerceRepositoryImpl : CommerceRepository` — same public contract as `SupabaseCommerceRepositoryImpl`, wired into `dataModule` in its place.

- [ ] **Step 1: Add the new backend route constants**

In `data/src/commonMain/kotlin/com/oposicontrol/admin/data/remote/api/BackendRoutes.kt`, alongside the other `ADMIN_V1_*` constants, add:

```kotlin
    const val ADMIN_V1_TIENDA_ARTICULOS = "/api/v1/admin/tienda/articulos"
    const val ADMIN_V1_TIENDA_OFERTAS = "/api/v1/admin/tienda/ofertas"
    const val ADMIN_V1_TIENDA_RECURSOS_TEMPORALES = "/api/v1/admin/tienda/recursos-temporales"
    const val ADMIN_V1_TIENDA_RAFAGAS = "/api/v1/admin/tienda/rafagas"
```

- [ ] **Step 2: Add the `ktor-client-mock` dependency**

In `gradle/libs.versions.toml`, alongside the other `ktor-client-*` aliases, add:

```toml
ktor-client-mock = { module = "io.ktor:ktor-client-mock", version.ref = "ktor" }
```

In `data/build.gradle.kts`, add a `jvmTest` block (the SqlDelight JDBC driver and the Ktor mock engine are both needed only for the JVM test, not `commonTest`, because the JDBC driver artifact is JVM-only):

```kotlin
        jvmTest.dependencies {
            implementation(libs.ktor.client.mock)
            implementation(libs.sqldelight.sqlite.driver)
        }
```

- [ ] **Step 3: Write the failing test**

```kotlin
package com.oposicontrol.admin.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.core.common.config.FeatureFlags
import com.oposicontrol.admin.core.common.logging.NoOpLogger
import com.oposicontrol.admin.core.database.AdminDatabase
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KtorCommerceRepositoryImplTest {
    private fun appConfig() = AppConfig(
        environment = Environment.DEV,
        baseUrl = "http://localhost:8080",
        backendToken = "test-token",
        supabaseUrl = "https://example.supabase.co",
        supabaseAnonKey = "anon-key",
        supabaseBucketExamResources = "exam-resources",
        featureFlags = FeatureFlags(enableLogs = false, enableAnalytics = false),
        appName = "OposiControl Admin",
        appVersion = "test",
    )

    private fun sessionLocalDataSource(): SessionLocalDataSource {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AdminDatabase.Schema.create(driver)
        return SessionLocalDataSource(AdminDatabase(driver))
    }

    @Test
    fun refreshCommerceOnlyCallsTheKtorBackendNeverSupabaseDirectly() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { request ->
            requests += request
            respond(content = ByteReadChannel("[]"), status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val config = appConfig()
        val repository = KtorCommerceRepositoryImpl(client, config, sessionLocalDataSource(), NetworkResultAdapter(NoOpLogger))

        repository.refreshCommerce()

        assertEquals(4, requests.size)
        requests.forEach { request ->
            assertTrue(
                request.url.toString().startsWith(config.baseUrl),
                "Store request must go through the Ktor backend (${config.baseUrl}), not Supabase directly: ${request.url}",
            )
            assertTrue(
                !request.url.host.contains("supabase", ignoreCase = true),
                "Store must never call Supabase directly from Desktop: ${request.url}",
            )
        }
        assertTrue(requests.any { it.url.encodedPath == "/api/v1/admin/tienda/articulos" })
        assertTrue(requests.any { it.url.encodedPath == "/api/v1/admin/tienda/ofertas" })
        assertTrue(requests.any { it.url.encodedPath == "/api/v1/admin/tienda/recursos-temporales" })
        assertTrue(requests.any { it.url.encodedPath == "/api/v1/admin/tienda/rafagas" })
    }

    @Test
    fun refreshCommerceSendsABearerTokenNeverASupabaseApiKey() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { request ->
            requests += request
            respond(content = ByteReadChannel("[]"), status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val repository = KtorCommerceRepositoryImpl(client, appConfig(), sessionLocalDataSource(), NetworkResultAdapter(NoOpLogger))

        repository.refreshCommerce()

        requests.forEach { request ->
            assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])
            assertNull(request.headers["apikey"])
        }
    }
}
```

- [ ] **Step 4: Run to verify it fails**

Run: `.\gradlew.bat :data:jvmTest --console=plain --tests "com.oposicontrol.admin.data.repository.KtorCommerceRepositoryImplTest"`
Expected: compilation failure (`KtorCommerceRepositoryImpl` doesn't exist yet).

- [ ] **Step 5: Implement `KtorCommerceRepositoryImpl`**

```kotlin
package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.api.BackendRoutes
import com.oposicontrol.admin.domain.model.BurstCampaign
import com.oposicontrol.admin.domain.model.BurstCampaignType
import com.oposicontrol.admin.domain.model.CommerceStatus
import com.oposicontrol.admin.domain.model.LimitedResourceWindow
import com.oposicontrol.admin.domain.model.StoreItem
import com.oposicontrol.admin.domain.model.StoreItemType
import com.oposicontrol.admin.domain.model.StoreOffer
import com.oposicontrol.admin.domain.repository.CommerceRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Store admin operations proxied through the Ktor boundary. Replaces
 * [SupabaseCommerceRepositoryImpl], which called Supabase PostgREST directly
 * from Desktop with the anon key + user JWT -- the only repository in
 * [com.oposicontrol.admin.data.di.dataModule] bypassing Ktor. Backend side:
 * AdminV1Routes.kt `/api/v1/admin/tienda/*`.
 */
class KtorCommerceRepositoryImpl(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
    private val adapter: NetworkResultAdapter,
) : CommerceRepository {
    private val items = MutableStateFlow<List<StoreItem>>(emptyList())
    private val offers = MutableStateFlow<List<StoreOffer>>(emptyList())
    private val limited = MutableStateFlow<List<LimitedResourceWindow>>(emptyList())
    private val bursts = MutableStateFlow<List<BurstCampaign>>(emptyList())

    override fun observeStoreItems(): Flow<List<StoreItem>> = items.asStateFlow()
    override fun observeStoreOffers(): Flow<List<StoreOffer>> = offers.asStateFlow()
    override fun observeLimitedResources(): Flow<List<LimitedResourceWindow>> = limited.asStateFlow()
    override fun observeBurstCampaigns(): Flow<List<BurstCampaign>> = bursts.asStateFlow()

    override suspend fun refreshCommerce(): AppResult<Unit> = adapter.safeCall {
        val itemsResponse = client.get(url(BackendRoutes.ADMIN_V1_TIENDA_ARTICULOS)) { backendHeaders() }
        itemsResponse.requireSuccess(BackendRoutes.ADMIN_V1_TIENDA_ARTICULOS)
        items.value = itemsResponse.body<List<StoreItemRowDto>>().map { it.toDomain() }

        offers.value = runCatching {
            val r = client.get(url(BackendRoutes.ADMIN_V1_TIENDA_OFERTAS)) { backendHeaders() }
            r.requireSuccess(BackendRoutes.ADMIN_V1_TIENDA_OFERTAS)
            r.body<List<StoreOfferRowDto>>().map { it.toDomain() }
        }.getOrElse { emptyList() }

        limited.value = runCatching {
            val r = client.get(url(BackendRoutes.ADMIN_V1_TIENDA_RECURSOS_TEMPORALES)) { backendHeaders() }
            r.requireSuccess(BackendRoutes.ADMIN_V1_TIENDA_RECURSOS_TEMPORALES)
            r.body<List<LimitedResourceRowDto>>().map { it.toDomain() }
        }.getOrElse { emptyList() }

        bursts.value = runCatching {
            val r = client.get(url(BackendRoutes.ADMIN_V1_TIENDA_RAFAGAS)) { backendHeaders() }
            r.requireSuccess(BackendRoutes.ADMIN_V1_TIENDA_RAFAGAS)
            r.body<List<BurstCampaignRowDto>>().map { it.toDomain() }
        }.getOrElse { emptyList() }
        Unit
    }

    override suspend fun upsertStoreItem(item: StoreItem): AppResult<StoreItem> {
        val result = adapter.safeCall {
            val r = client.post(url(BackendRoutes.ADMIN_V1_TIENDA_ARTICULOS)) {
                backendHeaders(); contentType(ContentType.Application.Json)
                setBody(item.toRowDto())
            }
            r.requireSuccess(BackendRoutes.ADMIN_V1_TIENDA_ARTICULOS)
            r.body<StoreItemRowDto>().toDomain()
        }
        if (result is AppResult.Success) refreshCommerce()
        return result
    }

    override suspend fun upsertStoreOffer(offer: StoreOffer): AppResult<StoreOffer> {
        val result = adapter.safeCall {
            val r = client.post(url(BackendRoutes.ADMIN_V1_TIENDA_OFERTAS)) {
                backendHeaders(); contentType(ContentType.Application.Json)
                setBody(offer.toRowDto())
            }
            r.requireSuccess(BackendRoutes.ADMIN_V1_TIENDA_OFERTAS)
            r.body<StoreOfferRowDto>().toDomain()
        }
        if (result is AppResult.Success) refreshCommerce()
        return result
    }

    override suspend fun upsertLimitedResource(window: LimitedResourceWindow): AppResult<LimitedResourceWindow> {
        val result = adapter.safeCall {
            val r = client.post(url(BackendRoutes.ADMIN_V1_TIENDA_RECURSOS_TEMPORALES)) {
                backendHeaders(); contentType(ContentType.Application.Json)
                setBody(window.toRowDto())
            }
            r.requireSuccess(BackendRoutes.ADMIN_V1_TIENDA_RECURSOS_TEMPORALES)
            r.body<LimitedResourceRowDto>().toDomain()
        }
        if (result is AppResult.Success) refreshCommerce()
        return result
    }

    override suspend fun upsertBurstCampaign(campaign: BurstCampaign): AppResult<BurstCampaign> {
        val result = adapter.safeCall {
            val r = client.post(url(BackendRoutes.ADMIN_V1_TIENDA_RAFAGAS)) {
                backendHeaders(); contentType(ContentType.Application.Json)
                setBody(campaign.toRowDto())
            }
            r.requireSuccess(BackendRoutes.ADMIN_V1_TIENDA_RAFAGAS)
            r.body<BurstCampaignRowDto>().toDomain()
        }
        if (result is AppResult.Success) refreshCommerce()
        return result
    }

    private fun url(path: String) = BackendRoutes.url(appConfig.baseUrl, path)

    private suspend fun HttpResponse.requireSuccess(endpoint: String) {
        if (!status.isSuccess()) error("backend ${status.value} $endpoint: ${bodyAsText().take(240)}")
    }

    private fun io.ktor.client.request.HttpRequestBuilder.backendHeaders() {
        val token = sessionLocalDataSource.currentSession()?.accessToken?.takeIf { it.isNotBlank() }
            ?: appConfig.backendToken.takeIf { it.isNotBlank() } ?: return
        header(HttpHeaders.Authorization, "Bearer $token")
    }
}

@Serializable
private data class StoreItemRowDto(
    val id: Int? = null,
    val nombre: String,
    @SerialName("tipo_articulo") val type: String,
    @SerialName("precio_tickets") val priceTickets: Int,
    val activo: Boolean,
)

@Serializable
private data class StoreOfferRowDto(
    val id: Int? = null,
    @SerialName("articulo_tienda_id") val itemId: Int,
    val titulo: String,
    @SerialName("discount_percent") val discountPercent: Int? = null,
    @SerialName("special_price_tickets") val specialPriceTickets: Int? = null,
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null,
    val status: String,
)

@Serializable
private data class LimitedResourceRowDto(
    val id: Int? = null,
    @SerialName("articulo_tienda_id") val itemId: Int,
    @SerialName("recurso_id") val resourceId: Int,
    val titulo: String,
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null,
    @SerialName("access_duration_hours") val accessDurationHours: Int,
    val status: String,
)

@Serializable
private data class BurstCampaignRowDto(
    val id: Int? = null,
    val titulo: String,
    val descripcion: String = "",
    val tipo: String,
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null,
    val multiplier: Double,
    @SerialName("reward_tickets") val rewardTickets: Int,
    @SerialName("linked_exam_id") val linkedExamId: Int? = null,
    @SerialName("linked_store_item_id") val linkedStoreItemId: Int? = null,
    val status: String,
)

private fun StoreItemRowDto.toDomain() = StoreItem(
    id = id?.toString().orEmpty(),
    name = nombre,
    description = "",
    type = type.toStoreItemType(),
    priceTickets = priceTickets,
    status = if (activo) CommerceStatus.ACTIVE else CommerceStatus.DRAFT,
    sortOrder = id ?: 0,
    createdAt = Instant.fromEpochMilliseconds(0),
    updatedAt = Instant.fromEpochMilliseconds(0),
)

private fun StoreItem.toRowDto() = StoreItemRowDto(
    id = id.toIntOrNull(),
    nombre = name,
    type = type.toDatabaseType(),
    priceTickets = priceTickets,
    activo = status == CommerceStatus.ACTIVE,
)

private fun StoreOfferRowDto.toDomain() = StoreOffer(
    id = id?.toString().orEmpty(),
    itemId = itemId.toString(),
    title = titulo,
    discountPercent = discountPercent,
    specialPriceTickets = specialPriceTickets,
    startsAt = startsAt?.let(Instant::parse),
    endsAt = endsAt?.let(Instant::parse),
    status = status.toCommerceStatus(),
)

private fun StoreOffer.toRowDto() = StoreOfferRowDto(
    id = id.toIntOrNull(),
    itemId = itemId.toIntOrNull() ?: 0,
    titulo = title,
    discountPercent = discountPercent,
    specialPriceTickets = specialPriceTickets,
    startsAt = startsAt?.toString(),
    endsAt = endsAt?.toString(),
    status = status.name.lowercase(),
)

private fun LimitedResourceRowDto.toDomain() = LimitedResourceWindow(
    id = id?.toString().orEmpty(),
    itemId = itemId.toString(),
    resourceId = resourceId.toString(),
    title = titulo,
    startsAt = startsAt?.let(Instant::parse),
    endsAt = endsAt?.let(Instant::parse),
    accessDurationHours = accessDurationHours,
    status = status.toCommerceStatus(),
)

private fun LimitedResourceWindow.toRowDto() = LimitedResourceRowDto(
    id = id.toIntOrNull(),
    itemId = itemId.toIntOrNull() ?: 0,
    resourceId = resourceId.toIntOrNull() ?: 0,
    titulo = title,
    startsAt = startsAt?.toString(),
    endsAt = endsAt?.toString(),
    accessDurationHours = accessDurationHours,
    status = status.name.lowercase(),
)

private fun BurstCampaignRowDto.toDomain() = BurstCampaign(
    id = id?.toString().orEmpty(),
    title = titulo,
    description = descripcion,
    type = tipo.toBurstCampaignType(),
    startsAt = startsAt?.let(Instant::parse),
    endsAt = endsAt?.let(Instant::parse),
    multiplier = multiplier,
    rewardTickets = rewardTickets,
    linkedExamId = linkedExamId?.toString(),
    linkedStoreItemId = linkedStoreItemId?.toString(),
    status = status.toCommerceStatus(),
)

private fun BurstCampaign.toRowDto() = BurstCampaignRowDto(
    id = id.toIntOrNull(),
    titulo = title,
    descripcion = description,
    tipo = type.name.lowercase(),
    startsAt = startsAt?.toString(),
    endsAt = endsAt?.toString(),
    multiplier = multiplier,
    rewardTickets = rewardTickets,
    linkedExamId = linkedExamId?.toIntOrNull(),
    linkedStoreItemId = linkedStoreItemId?.toIntOrNull(),
    status = status.name.lowercase(),
)

private fun String.toStoreItemType() = when (lowercase()) {
    "protector" -> StoreItemType.PROTECTOR
    "streak", "racha" -> StoreItemType.STREAK
    "potenciador", "potentiator" -> StoreItemType.POTENTIATOR
    else -> StoreItemType.CONSUMABLE
}

private fun StoreItemType.toDatabaseType() = when (this) {
    StoreItemType.PROTECTOR -> "protector"
    StoreItemType.STREAK -> "racha"
    StoreItemType.POTENTIATOR -> "potenciador"
    StoreItemType.CONSUMABLE,
    StoreItemType.EXAM_ACCESS,
    StoreItemType.RESOURCE,
    -> "consumible"
}

private fun String.toBurstCampaignType() = when (lowercase()) {
    "exam_burst" -> BurstCampaignType.EXAM_BURST
    "ticket_multiplier" -> BurstCampaignType.TICKET_MULTIPLIER
    "resource_unlock" -> BurstCampaignType.RESOURCE_UNLOCK
    else -> BurstCampaignType.DAILY_STREAK
}

private fun String.toCommerceStatus() = when (lowercase()) {
    "active" -> CommerceStatus.ACTIVE
    "scheduled" -> CommerceStatus.SCHEDULED
    "expired" -> CommerceStatus.EXPIRED
    "archived" -> CommerceStatus.ARCHIVED
    else -> CommerceStatus.DRAFT
}
```

(Row shapes/mapping are copied verbatim from `SupabaseCommerceRepositoryImpl` — same tables, same semantics; only the HTTP destination and headers change.)

- [ ] **Step 6: Rewire the DI module**

In `DataModule.kt`, change the import from `com.oposicontrol.admin.data.repository.SupabaseCommerceRepositoryImpl` to `com.oposicontrol.admin.data.repository.KtorCommerceRepositoryImpl`, and change:

```kotlin
    single<CommerceRepository> {
        val appConfig: AppConfig = get()
        SupabaseCommerceRepositoryImpl(
            client = get(),
            appConfig = appConfig,
            sessionLocalDataSource = get(),
            adapter = get(),
        )
    }
```

to:

```kotlin
    single<CommerceRepository> {
        KtorCommerceRepositoryImpl(
            client = get(),
            appConfig = get(),
            sessionLocalDataSource = get(),
            adapter = get(),
        )
    }
```

- [ ] **Step 7: Mark the old implementation deprecated (kept, not deleted)**

In `SupabaseCommerceRepositoryImpl.kt`, add above the class declaration:

```kotlin
@Deprecated(
    "Superseded by KtorCommerceRepositoryImpl -- Desktop must not call Supabase directly (anon key + user JWT bypassing the Ktor trust boundary). No longer wired in dataModule. Kept, not deleted, per Phase 0 policy.",
)
class SupabaseCommerceRepositoryImpl(
```

- [ ] **Step 8: Run to verify the new test passes**

Run: `.\gradlew.bat :data:jvmTest --console=plain --tests "com.oposicontrol.admin.data.repository.KtorCommerceRepositoryImplTest"`
Expected: both tests PASS.

- [ ] **Step 9: Compile composeApp (consumes `dataModule`/`StoreViewModel` unchanged) and data module fully**

Run: `.\gradlew.bat :data:compileKotlinJvm :composeApp:compileKotlinJvm --console=plain`
Expected: BUILD SUCCESSFUL (the `CommerceRepository` interface didn't change, so `StoreViewModel`/`StoreScreen` need no changes).

- [ ] **Step 10: Commit**

```bash
git add data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/KtorCommerceRepositoryImpl.kt data/src/commonMain/kotlin/com/oposicontrol/admin/data/remote/api/BackendRoutes.kt data/src/commonMain/kotlin/com/oposicontrol/admin/data/di/DataModule.kt data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/SupabaseCommerceRepositoryImpl.kt gradle/libs.versions.toml data/build.gradle.kts data/src/jvmTest/kotlin/com/oposicontrol/admin/data/repository/KtorCommerceRepositoryImplTest.kt
git commit -m "fix(desktop): Store goes through Ktor, not direct Supabase calls (P1)"
```

---

## Task 8: Full verification suite

**Files:** none (verification only).

- [ ] **Step 1: Run every requested test/compile target**

```powershell
.\gradlew.bat :composeApp:jvmTest --console=plain
.\gradlew.bat :composeApp:compileKotlinJvm --console=plain
.\gradlew.bat :core:database:jvmTest --console=plain
.\gradlew.bat :data:jvmTest --console=plain
.\gradlew.bat :data:compileKotlinJvm --console=plain
.\gradlew.bat :backend:ktor:test --console=plain
.\gradlew.bat :backend:ktor:compileKotlin --console=plain
git diff --check
```

Record the exact pass/fail outcome of each command — do not summarize as "all green" without having run every one of them in this session.

- [ ] **Step 2: Launch the real Desktop app**

```powershell
.\gradlew.bat :composeApp:run --console=plain
```

Run in the background with a bounded timeout (a GUI window has no natural exit); if a `.env`/env vars with real `SUPABASE_URL`/`SUPABASE_ANON_KEY`/`BASE_URL` are available in this environment, attempt to walk Login → Dashboard → Reports → Forum (OposiRed) → OposiTester → Settings → Content Upload → System Status, using no invented credentials. If a display/window session or valid credentials are unavailable in this sandbox, or login cannot be completed, stop and report the honest blocker (e.g. "no display available", "no valid admin JWT available locally" — matching how `docs/brain/CURRENT_STATE.md` already tracks this as `NOT_VERIFIED`) rather than claiming a walkthrough that didn't happen. Do not fabricate screenshots or click sequences.

- [ ] **Step 3: Record results**

Whatever actually happened in Steps 1-2 (pass/fail per command, and what the runtime walkthrough could/couldn't reach) feeds directly into the final `OUTPUT` report in Task 10 — this step has no separate commit, it's the evidence-gathering pass for that report.

---

## Task 9: Second Brain update (canonical registries only, no new docs)

**Files:**
- Modify: `docs/product/SCREEN_REGISTRY.md`
- Modify: `docs/brain/CURRENT_STATE.md`
- Modify: `docs/backend/ROUTE_MAP.md` (only if Task 6/7 evidence warrants a line — likely not, since it documents families not individual endpoints; skip if the existing `/api/v1/admin/*` family row already covers it)

**Interfaces:** none (documentation only).

- [ ] **Step 1: Fix the stale Desktop entrypoint reference**

In `docs/product/SCREEN_REGISTRY.md`, the "Navigation and scope notes" section currently states:

```
- Desktop entry: `DesktopModerationRoot → AppRoot`; source exposes the administrative `AdminSection` set.
```

`DesktopModerationRoot` (`composeApp/.../app/root/DesktopModerationRoot.kt`) is a 1-line pass-through wrapper (`AppRoot(appConfig)`) that is never called anywhere in the source tree — the real Desktop entry is `composeApp/src/jvmMain/kotlin/com/example/oposicontrol/main.kt`'s `main()`, which opens a `Window` and calls `OposiControlAdminApp(AppPlatformContext())` (`App.kt`), which calls `AppRoot(appConfig)` directly. Replace the line with:

```
- Desktop entry: `main()` (`composeApp/src/jvmMain/.../main.kt`) → `OposiControlAdminApp` (`App.kt`) → `AppRoot`; source exposes the administrative `AdminSection` set. `DesktopModerationRoot` is an unused pass-through wrapper (dead code, not deleted in this phase) — it is not the real entrypoint.
```

- [ ] **Step 2: Add a dashboard row to `CURRENT_STATE.md`**

In the "Operational dashboard" table, add a row documenting this pass (place it after the existing `UI runtime` row):

```
| Desktop/JVM foundation | FIXED | JVM config now falls back to `.env` (fixes running `MainKt` directly outside Gradle); desktop SQLite DB moved to the per-OS app-data directory with best-effort legacy-file migration; Reports/Forum/Thread Detail/OposiTester/Content Upload now render on Desktop (`compactLayout=false`) instead of a legacy screen or placeholder; Store (`tienda`) admin operations moved from a direct Supabase call (anon key + user JWT) to a new Ktor boundary (`/api/v1/admin/tienda/*`). No visual redesign, no schema change, no legacy screen deleted. | ../architecture/adr, `AppRoot.kt`, `AdminV1Routes.kt` |
```

Adjust the exact wording/verdict (`FIXED` vs `PASS_WITH_GAPS`) to match what Task 8 actually verified — do not claim `FIXED` for anything that didn't pass its test or couldn't be runtime-verified; downgrade to the honest state (e.g. note "Store backend routes compile but have no automated HTTP test, consistent with every other `AdminV1Gateway` resource" or "Desktop runtime walkthrough NOT_VERIFIED in this sandbox").

- [ ] **Step 3: Commit**

```bash
git add docs/product/SCREEN_REGISTRY.md docs/brain/CURRENT_STATE.md
git commit -m "docs: Second Brain -- JVM/Desktop foundation remediation state, fix stale entrypoint reference"
```

---

## Task 10: Final structured report

**Files:** none.

- [ ] **Step 1: Produce the exact `OUTPUT` block the brief requires**

Using the real results from Task 8 (test pass/fail, runtime walkthrough outcome) and Task 9 (docs updated), fill in the template below and present it to the user verbatim — every field must reflect what was actually run/observed in this session, not an assumption:

```
# OPOSICONTROL — JVM FOUNDATION REMEDIATION

VERDICT: <PASS | PASS_WITH_GAPS | FAILED>
BRANCH: integration/mobile-finish
COMMIT: <HEAD after Task 9's commit>
PUSH: <NO — not requested>
CONFIG_JVM: <PASS | PARTIAL>
ANDROID_STUDIO_RUN_CONFIG: <PASS | PARTIAL>
SQLITE_LOCATION: <per-OS app-data dir path pattern + migration behavior>
DESKTOP_ROUTING: <PASS | PARTIAL>
REPORTS_DESKTOP: <REACHABLE | NOT_REACHABLE>
FORUM_DESKTOP: <REACHABLE | NOT_REACHABLE>
OPOSITESTER_DESKTOP: <REACHABLE | NOT_REACHABLE>
CONTENT_UPLOAD_DESKTOP: <REACHABLE | NOT_REACHABLE>
SYSTEM_STATUS_DESKTOP: <REACHABLE | NOT_REACHABLE>
LEGACY_CONVERGENCE: <ForumScreen/ThreadDetailScreen canonical for Community.OposiRed/Thread; ModerationScreen preserved as the independent action-log admin section via AppRoute.Moderation.Queue (unique CRUD functionality, not a Forum duplicate); QuestionsResourcesScreen and ContentUploadScreen are NOT duplicates -- manual single-question editor vs bulk PDF import/staging, both now independently reachable on Desktop>
STORE_KTOR_BOUNDARY: <PASS | PARTIAL>
JVM_TESTS: <list of test files added + pass/fail>
RUNTIME: <what Task 8 Step 2 actually observed>
BUILD: <per-command pass/fail from Task 8 Step 1>
SECOND_BRAIN_UPDATED: YES
P0: <status of the JVM config fix>
P1: <status of the Store/Ktor boundary fix>
P2: <anything explicitly deferred, e.g. backend HTTP-level tests for tienda, matching existing AdminV1Gateway convention>
READY_FOR_DESKTOP_VISUAL_REDESIGN: <YES | NO, with the one-line reason if NO>
```

Do not start the visual redesign after this report — that is explicitly out of scope for this plan.
