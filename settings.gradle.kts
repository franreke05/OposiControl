rootProject.name = "OposiControl"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

includeBuild("build-logic")

include(":composeApp")
include(":core:common")
include(":core:network")
include(":core:database")
include(":core:ui")
include(":core:testing")
include(":domain")
include(":data")
include(":features:auth")
include(":features:dashboard")
include(":features:users")
include(":features:roles")
include(":features:settings")
include(":features:analytics")
include(":features:audit")
include(":features:specialties")
include(":features:communities")
include(":features:tags")
include(":features:news-types")
include(":features:news")
include(":features:exams")
include(":features:questions")
include(":features:imports")
include(":features:exam-access")
include(":features:rankings")
include(":features:store")
include(":features:tickets")
include(":features:support")
include(":features:moderation")
include(":features:reports")
include(":features:forum")
include(":features:opositester")
include(":backend:ktor")
