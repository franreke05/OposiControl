plugins {
    id("kmp-library-convention")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.common)
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
