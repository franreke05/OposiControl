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
    val envFile = rootProject.file(".env")
    if (!envFile.isFile) return ""
    return envFile.readLines()
        .firstOrNull { line -> line.trimStart().startsWith("$key=") }
        ?.substringAfter("=")
        ?.trim()
        .orEmpty()
}

fun String.toBuildConfigString(): String {
    return "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

val supabasePublishableKeyForBuild = localEnvValue("SUPABASE_PUBLISHABLE_KEY")
val supabaseUrlForBuild = localEnvValue("SUPABASE_URL")

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
            implementation(projects.features.dashboard)
            implementation(projects.features.examAccess)
            implementation(projects.features.exams)
            implementation(projects.features.moderation)
            implementation(projects.features.news)
            implementation(projects.features.newsTypes)
            implementation(projects.features.roles)
            implementation(projects.features.settings)
            implementation(projects.features.specialties)
            implementation(projects.features.store)
            implementation(projects.features.support)
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
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
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
            buildConfigField("String", "SUPABASE_URL", supabaseUrlForBuild.toBuildConfigString())
            buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", supabasePublishableKeyForBuild.toBuildConfigString())
            buildConfigField("String", "SUPABASE_BUCKET_EXAM_RESOURCES", "\"exam-resources\"")
            buildConfigField("boolean", "USE_FAKE_DATA", "false")
            buildConfigField("boolean", "ENABLE_LOGS", "true")
            buildConfigField("boolean", "ENABLE_ANALYTICS", "false")
        }
        create("staging") {
            dimension = "environment"
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            buildConfigField("String", "APP_ENV", "\"staging\"")
            buildConfigField("String", "BASE_URL", "\"https://staging.api.oposicontrol.local\"")
            buildConfigField("String", "SUPABASE_URL", supabaseUrlForBuild.toBuildConfigString())
            buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", supabasePublishableKeyForBuild.toBuildConfigString())
            buildConfigField("String", "SUPABASE_BUCKET_EXAM_RESOURCES", "\"exam-resources\"")
            buildConfigField("boolean", "USE_FAKE_DATA", "false")
            buildConfigField("boolean", "ENABLE_LOGS", "true")
            buildConfigField("boolean", "ENABLE_ANALYTICS", "true")
        }
        create("prod") {
            dimension = "environment"
            buildConfigField("String", "APP_ENV", "\"prod\"")
            buildConfigField("String", "BASE_URL", "\"https://api.oposicontrol.local\"")
            buildConfigField("String", "SUPABASE_URL", supabaseUrlForBuild.toBuildConfigString())
            buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", supabasePublishableKeyForBuild.toBuildConfigString())
            buildConfigField("String", "SUPABASE_BUCKET_EXAM_RESOURCES", "\"exam-resources\"")
            buildConfigField("boolean", "USE_FAKE_DATA", "false")
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

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "OposiControlAdmin"
            packageVersion = "1.0.0"
        }
    }
}

tasks.matching { it.name == "jvmRun" }.configureEach {
    if (this is JavaExec) {
        mainClass.set("com.oposicontrol.admin.app.MainKt")
    }
}
