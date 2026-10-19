plugins {
    id("kmp-library-convention")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.common)
            implementation(projects.core.ui)
            implementation(projects.domain)
            // El mismo pintor del marcado (arbol tipado a AnnotatedString) que usa Control: una sola vista previa para todo.
            implementation(projects.features.controlV1)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.koin.core)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(projects.core.testing)
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        // Test de UI SÓLO en jvmTest, no en commonTest: `compose.uiTest` en común
        // arrastraría `ui-test-junit4` a la compilación de tests unitarios de
        // Android, que necesita instrumentación o Robolectric. Aquí basta el
        // runtime de escritorio, y el alcance se queda contenido.
        //
        // Misma configuración que `features/exams`, `features/dashboard` y
        // `features/questions`, y por el mismo motivo: el banco de pruebas visual
        // vive ahí.
        jvmTest.dependencies {
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
            implementation(compose.desktop.currentOs)
        }
    }
}
