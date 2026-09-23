plugins {
    id("kmp-library-convention")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.common)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.compose.uiToolingPreview)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        // Test de UI SÓLO en jvmTest, no en commonTest: `compose.uiTest` en común
        // arrastraría `ui-test-junit4` a la compilación de tests unitarios de
        // Android, que necesita instrumentación o Robolectric. Es la misma
        // configuración que `features/exams` y `features/dashboard`.
        //
        // Existe para lo que sólo se comprueba COMPONIENDO el tema: qué color de
        // contenido hereda un `Text` que no pide ninguno.
        jvmTest.dependencies {
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
            implementation(compose.desktop.currentOs)
        }
    }
}

compose.resources {
    publicResClass = true
}
