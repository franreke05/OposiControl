import org.gradle.api.tasks.JavaExec
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

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

fun String.toBuildConfigString(): String {
    return "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

val localSupabaseUrl = localEnvValue("SUPABASE_URL")
val localSupabasePublicKey = localEnvValue("SUPABASE_ANON_KEY")
    .ifBlank { localEnvValue("SUPABASE_PUBLISHABLE_KEY") }
    .takeUnless { it.startsWith("sb_secret_", ignoreCase = true) }
    .orEmpty()

val productionDesktopBuild =
    providers.gradleProperty("oposiProduction").orNull?.toBooleanStrictOrNull() ?: false

val desktopBaseUrl =
    if (productionDesktopBuild) {
        "https://api.oposibots.es"
    } else {
        localEnvValue("BASE_URL").ifBlank { "http://localhost:8080" }
    }

val desktopAppEnv =
    if (productionDesktopBuild) "prod" else "dev"

val desktopSentryEnvironment =
    if (productionDesktopBuild) "production" else "development"

val desktopSentryDsn =
    localEnvValue("SENTRY_DESKTOP_DSN")

val desktopSentryRelease =
    providers.gradleProperty("oposiRelease").orNull
        ?.trim()
        .orEmpty()

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.common)
            implementation(projects.core.ui)
            implementation(projects.data)
            implementation(projects.domain)
            implementation(projects.features.audit)
            implementation(projects.features.auth)
            implementation(projects.features.communities)
            implementation(projects.features.dashboard)
            implementation(projects.features.examAccess)
            implementation(projects.features.exams)
            implementation(projects.features.moderation)
            implementation(projects.features.news)
            implementation(projects.features.newsTypes)
            implementation(projects.features.reports)
            implementation(projects.features.forum)
            implementation(projects.features.imports)
            implementation(projects.features.opositester)
            implementation(projects.features.questions)
            implementation(projects.features.roles)
            implementation(projects.features.settings)
            implementation(projects.features.specialties)
            implementation(projects.features.analytics)
            implementation(projects.features.rankings)
            implementation(projects.features.store)
            implementation(projects.features.support)
            implementation(projects.features.tags)
            implementation(projects.features.tickets)
            implementation(projects.features.users)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.koin.compose)
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation("io.sentry:sentry:8.57.0")
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

android {
    namespace = "com.oposicontrol.admin.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.oposicontrol.admin"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"
    }

    flavorDimensions += "environment"

    productFlavors {
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            buildConfigField("String", "APP_ENV", "\"dev\"")
            buildConfigField(
                "String", "BASE_URL",
                localEnvValue("BASE_URL").ifBlank { "http://10.0.2.2:8080" }.toBuildConfigString(),
            )
            // The legacy static token must never be embedded in a client artifact.
            buildConfigField("String", "BACKEND_TOKEN", "\"\"")
            buildConfigField("String", "SUPABASE_URL", localSupabaseUrl.toBuildConfigString())
            buildConfigField("String", "SUPABASE_ANON_KEY", localSupabasePublicKey.toBuildConfigString())
            buildConfigField("String", "SUPABASE_BUCKET_EXAM_RESOURCES", "\"exam-resources\"")
            buildConfigField("boolean", "ENABLE_LOGS", "true")
            buildConfigField("boolean", "ENABLE_ANALYTICS", "false")
        }
        create("staging") {
            dimension = "environment"
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            buildConfigField("String", "APP_ENV", "\"staging\"")
            buildConfigField("String", "BASE_URL", "\"https://staging.api.oposicontrol.local\"")
            buildConfigField("String", "BACKEND_TOKEN", "\"\"")
            buildConfigField("String", "SUPABASE_URL", "\"\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"\"")
            buildConfigField("String", "SUPABASE_BUCKET_EXAM_RESOURCES", "\"exam-resources\"")
            buildConfigField("boolean", "ENABLE_LOGS", "true")
            buildConfigField("boolean", "ENABLE_ANALYTICS", "true")
        }
        create("prod") {
            dimension = "environment"
            buildConfigField("String", "APP_ENV", "\"prod\"")
            buildConfigField("String", "BASE_URL", "\"https://api.oposicontrol.local\"")
            buildConfigField("String", "BACKEND_TOKEN", "\"\"")
            buildConfigField("String", "SUPABASE_URL", "\"\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"\"")
            buildConfigField("String", "SUPABASE_BUCKET_EXAM_RESOURCES", "\"exam-resources\"")
            buildConfigField("boolean", "ENABLE_LOGS", "false")
            buildConfigField("boolean", "ENABLE_ANALYTICS", "true")
        }
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("boolean", "ENABLE_VERBOSE_NETWORK_LOGS", "true")
        }
        getByName("release") {
            isMinifyEnabled = true
            buildConfigField("boolean", "ENABLE_VERBOSE_NETWORK_LOGS", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    debugImplementation(libs.compose.uiTooling)
}

compose.desktop {
    application {
        mainClass = "com.oposicontrol.admin.app.MainKt"

        jvmArgs(
            "-Doposi.BACKEND_TOKEN=",
            "-Doposi.BASE_URL=$desktopBaseUrl",
            "-Doposi.APP_ENV=$desktopAppEnv",
            "-Doposi.SUPABASE_URL=$localSupabaseUrl",
            "-Doposi.SUPABASE_ANON_KEY=$localSupabasePublicKey",
            "-Doposi.SENTRY_DESKTOP_DSN=$desktopSentryDsn",
            "-Doposi.SENTRY_DESKTOP_ENVIRONMENT=$desktopSentryEnvironment",
            "-Doposi.SENTRY_DESKTOP_TRACES_SAMPLE_RATE=0.10",
            "-Doposi.SENTRY_DESKTOP_RELEASE=$desktopSentryRelease",
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "OposiControlAdmin"
            packageVersion = "1.0.0"
            // CI-OC-RELEASE: runtime embebido (jlink) con los modulos que el cliente usa de verdad (lista validada en la rama
            // macOS): java.sql (SQLite JDBC / DriverManager; sin el, la MSI fallaba al arrancar), java.management y
            // java.instrument (Sentry), jdk.unsupported (Skiko/coroutines) y jdk.crypto.ec (TLS ECDHE en JDK 21). Lo comprueba
            // el job `desktop-release-linux` con scripts/verify-desktop-runtime-modules.sh.
            modules("java.instrument", "java.management", "java.sql", "jdk.unsupported", "jdk.crypto.ec")
        }
    }
}

tasks.matching { it.name == "jvmRun" }.configureEach {
    if (this is JavaExec) {
        mainClass.set("com.oposicontrol.admin.app.MainKt")
        systemProperty("oposi.BACKEND_TOKEN", "")
        systemProperty("oposi.BASE_URL", desktopBaseUrl)
        systemProperty("oposi.APP_ENV", desktopAppEnv)
        systemProperty("oposi.SUPABASE_URL", localSupabaseUrl)
        systemProperty("oposi.SUPABASE_ANON_KEY", localSupabasePublicKey)
        systemProperty("oposi.SENTRY_DESKTOP_DSN", desktopSentryDsn)
        systemProperty("oposi.SENTRY_DESKTOP_ENVIRONMENT", desktopSentryEnvironment)
        systemProperty("oposi.SENTRY_DESKTOP_TRACES_SAMPLE_RATE", "0.10")
        systemProperty("oposi.SENTRY_DESKTOP_RELEASE", desktopSentryRelease)
    }
}
