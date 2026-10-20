import java.util.Base64
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
// RC-11: espejo de AppConfigResolver.isSecretSupabaseKey (prefijo sb_secret_ o JWT con role=service_role, sin importar espacios).
// Los dos marcadores viven en UNA linea: la lista de permitidos de verify-no-direct-supabase.sh fija 1 linea SECRET aqui.
val secretSupabaseKeyMarkers = "sb_secret_" to "\"role\":\"service_role\""
fun isSecretSupabaseKey(key: String): Boolean {
    if (key.startsWith(secretSupabaseKeyMarkers.first, ignoreCase = true)) return true
    val parts = key.split('.')
    if (parts.size != 3) return false
    val payload = runCatching { String(Base64.getUrlDecoder().decode(parts[1].trim())) }.getOrNull() ?: return false
    return payload.filterNot { it.isWhitespace() }.contains(secretSupabaseKeyMarkers.second)
}

val rawSupabasePublicKey = localEnvValue("SUPABASE_ANON_KEY")
    .ifBlank { localEnvValue("SUPABASE_PUBLISHABLE_KEY") }
// Nunca se hornea un secreto en el artefacto (defensa en profundidad); ademas el build FALLA en voz alta (verifySupabasePublicKey).
val localSupabasePublicKey = rawSupabasePublicKey.takeUnless { isSecretSupabaseKey(it) }.orEmpty()
val secretSupabaseKeyProblem: String? =
    if (isSecretSupabaseKey(rawSupabasePublicKey)) "SUPABASE_ANON_KEY no puede ser una clave secreta (clave secreta o JWT con rol de servicio): usa la clave publica (anon/publishable)." else null

val verifySupabasePublicKey = tasks.register("verifySupabasePublicKey") {
    group = "verification"
    description = "Falla el build (todos los flavors y escritorio) si SUPABASE_ANON_KEY es un secreto (clave secreta o JWT con rol de servicio)."
    val problem = secretSupabaseKeyProblem
    doLast { if (problem != null) throw GradleException(problem) }
}

// Todas las variantes Android (dev/staging/prod, debug/release) y las tareas de escritorio que hornean la clave en jvmArgs.
tasks.configureEach {
    if (Regex("^pre(Dev|Staging|Prod)(Debug|Release)Build$").matches(name) ||
        Regex("^(run|runRelease.*|runDistributable|createDistributable|createReleaseDistributable|package(Release)?(Distribution.*|Dmg|Msi|Deb|UberJarForCurrentOS))$").matches(name)
    ) {
        dependsOn(verifySupabasePublicKey)
    }
}

// INV-NETWORK-COMPLETE: AppConfigResolver lanza fuera de DEV sin SUPABASE_URL https. Los flavors Android staging/prod
// toman SUPABASE_URL del mismo origen que dev/escritorio (variable de entorno o .env) y el BUILD falla en voz alta si falta
// o no es https/no-localhost (antes se horneaba "" y la app lanzaba al arrancar). Espejo de requireSecureUrl del resolver.
val nonDevSupabaseUrlProblem: String? = localSupabaseUrl.trim().trimEnd('/').let { url ->
    when {
        url.isEmpty() -> "SUPABASE_URL es obligatorio para los flavors staging/prod y la distribucion de escritorio de produccion (variable de entorno o .env)."
        !url.startsWith("https://", ignoreCase = true) -> "SUPABASE_URL debe usar HTTPS en los flavors staging/prod."
        url.contains("localhost", ignoreCase = true) -> "SUPABASE_URL no puede apuntar a localhost en los flavors staging/prod."
        else -> null
    }
}

val verifyNonDevSupabaseConfig = tasks.register("verifyNonDevSupabaseConfig") {
    group = "verification"
    description = "Falla el build de staging/prod si SUPABASE_URL falta o no es https (nunca deny-all silencioso ni crash en runtime)."
    val problem = nonDevSupabaseUrlProblem
    doLast { if (problem != null) throw GradleException(problem) }
}

// Los flavors staging/prod de Android no se construyen en CI (ver DEPLOYMENT.md); este guard corre antes de cualquier
// variante staging/prod (debug o release) y no afecta a dev ni a escritorio.
tasks.configureEach {
    if (Regex("^pre(Staging|Prod)(Debug|Release)Build$").matches(name)) dependsOn(verifyNonDevSupabaseConfig)
}

// Distribucion de escritorio de PRODUCCION (-PoposiProduction=true => APP_ENV=prod): hornea -Doposi.SUPABASE_URL en sus jvmArgs, asi
// que sin URL https arrancaria y fallaria (AppConfigResolver). Mismo guard, mismo mensaje: el build falla en voz alta en vez de
// empaquetar una distribucion con SUPABASE_URL vacia. El escritorio de desarrollo (sin el flag) no se ve afectado.
if (providers.gradleProperty("oposiProduction").orNull?.toBooleanStrictOrNull() == true) {
    tasks.configureEach {
        if (Regex("^(create(Release)?Distributable|package(Release)?(Distribution.*|Dmg|Msi|Deb))$").matches(name)) {
            dependsOn(verifyNonDevSupabaseConfig)
        }
    }
}

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

// Versión de la distribución de escritorio. macOS exige MAJOR.MINOR.PATCH con MAJOR > 0;
// sin -PoposiVersion se mantiene la 1.0.0 de siempre (MSI/DEB no cambian).
val desktopPackageVersion =
    providers.gradleProperty("oposiVersion").orNull?.trim()?.takeIf { it.isNotEmpty() } ?: "1.0.0"

// Número de build de macOS (CFBundleVersion). En CI, el número de ejecución del workflow.
val desktopBuildNumber =
    providers.gradleProperty("oposiBuildNumber").orNull?.trim()?.takeIf { it.isNotEmpty() } ?: desktopPackageVersion

// Firma macOS (Developer ID). Desactivada por defecto: la build interna sin firma no
// necesita nada. Identidad y keychain llegan por variables de entorno que crea el
// workflow en un keychain temporal; nunca hay credenciales en Gradle ni en el repo.
val macSigningEnabled =
    providers.gradleProperty("oposiMacSign").orNull?.toBooleanStrictOrNull() ?: false
val macSigningIdentity = providers.environmentVariable("OPOSI_MACOS_SIGNING_IDENTITY").orNull.orEmpty()
val macSigningKeychain = providers.environmentVariable("OPOSI_MACOS_KEYCHAIN").orNull.orEmpty()

// jpackage sólo empaqueta para el sistema en el que corre.
val isMacHost = System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

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
            implementation(projects.features.controlV1)
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
        // E2E de interfaz contra el stack aislado oposi-control-e2e (ControlPanelUiE2ETest): la app entera con su Koin real.
        jvmTest.dependencies {
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
            implementation(libs.sqldelight.sqlite.driver)
            implementation(projects.core.database)
            implementation(libs.kotlinx.serialization.json)
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
            buildConfigField("String", "SUPABASE_URL", localSupabaseUrl.toBuildConfigString())
            buildConfigField("String", "SUPABASE_ANON_KEY", localSupabasePublicKey.toBuildConfigString())
            buildConfigField("String", "SUPABASE_BUCKET_EXAM_RESOURCES", "\"exam-resources\"")
            buildConfigField("boolean", "ENABLE_LOGS", "true")
            buildConfigField("boolean", "ENABLE_ANALYTICS", "true")
        }
        create("prod") {
            dimension = "environment"
            buildConfigField("String", "APP_ENV", "\"prod\"")
            buildConfigField("String", "BASE_URL", "\"https://api.oposicontrol.local\"")
            buildConfigField("String", "BACKEND_TOKEN", "\"\"")
            buildConfigField("String", "SUPABASE_URL", localSupabaseUrl.toBuildConfigString())
            buildConfigField("String", "SUPABASE_ANON_KEY", localSupabasePublicKey.toBuildConfigString())
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
            // CI-OC-RELEASE: el job `desktop-release-linux` comprueba esta lista de modulos con
            // scripts/verify-desktop-runtime-modules.sh.
            // El nombre de la carpeta `.app` sale de ESTE packageName, no del de `macOS {}`:
            // en macOS la app es `OposiControl.app`; Windows/Linux siguen como OposiControlAdmin.
            packageName = if (isMacHost) "OposiControl" else "OposiControlAdmin"
            packageVersion = desktopPackageVersion

            // Runtime embebido (jlink): lo que sugiere `suggestRuntimeModules`
            // (java.sql para SQLite, java.management/java.instrument para Sentry,
            // jdk.unsupported para Skiko/coroutines) más jdk.crypto.ec, que en JDK 21
            // sigue siendo un módulo aparte y sin él fallan los handshakes TLS ECDHE.
            modules("java.instrument", "java.management", "java.sql", "jdk.unsupported", "jdk.crypto.ec")

            windows {
                iconFile.set(project.file("packaging/windows/OposiControl.ico"))
            }
            linux {
                iconFile.set(project.file("packaging/linux/OposiControl.png"))
            }

            macOS {
                // Sólo macOS: `OposiControl.app`. MSI y DEB conservan su nombre.
                packageName = "OposiControl"
                dockName = "OposiControl"
                bundleID = "com.oposicontrol.admin"
                appCategory = "public.app-category.productivity"
                packageBuildVersion = desktopBuildNumber
                iconFile.set(project.file("packaging/macos/OposiControl.icns"))
                entitlementsFile.set(project.file("packaging/macos/entitlements.plist"))
                runtimeEntitlementsFile.set(project.file("packaging/macos/entitlements.plist"))
                signing {
                    sign.set(macSigningEnabled)
                    if (macSigningIdentity.isNotEmpty()) identity.set(macSigningIdentity)
                    if (macSigningKeychain.isNotEmpty()) keychain.set(macSigningKeychain)
                }
            }
        }
    }
}

/**
 * Falla si la distribución NO apunta a producción. La ejecuta el workflow de macOS
 * antes de empaquetar: una build para Arturo sin `-PoposiProduction=true` apuntaría a
 * `localhost:8080`, y sin las claves públicas de Supabase el login no funcionaría.
 * No imprime ningún valor.
 */
val verifyProductionDesktopConfig by tasks.registering {
    val production = productionDesktopBuild
    val baseUrl = desktopBaseUrl
    val appEnv = desktopAppEnv
    val supabaseUrl = localSupabaseUrl
    val supabaseKey = localSupabasePublicKey
    doLast {
        check(production) { "Falta -PoposiProduction=true." }
        check(baseUrl == "https://api.oposibots.es") { "BASE_URL de producción inesperada." }
        check(appEnv == "prod") { "APP_ENV no es prod." }
        check(supabaseUrl.startsWith("https://") && supabaseUrl.endsWith(".supabase.co")) {
            "SUPABASE_URL ausente o no es un proyecto Supabase (defínelo en el entorno)."
        }
        check(supabaseKey.isNotBlank()) {
            "Falta la clave PÚBLICA de Supabase (SUPABASE_ANON_KEY o SUPABASE_PUBLISHABLE_KEY; nunca la clave secreta)."
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
