rootProject.name = "SatRide"

pluginManagement {
    includeBuild("build-logic")
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

// Lets modules write `projects.core.model` instead of `project(":core:model")`.
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// --- Platform entry points ---
include(":androidApp")
include(":desktopApp")
include(":composeApp")

// --- Core: shared, feature-agnostic building blocks ---
include(":core:model")
include(":core:common")
include(":core:designsystem")
include(":core:orbit")
include(":core:network")
include(":core:database")
include(":core:datastore")
include(":core:data")
include(":core:sensors")
include(":core:sdr")
include(":core:dsp")
include(":core:audio")
include(":core:notifications")
include(":core:auth")
include(":core:permissions")
include(":core:web")
include(":core:analytics")

// --- Tools: developer utilities, never shipped in the app ---
include(":tools:snapshot")

// --- Features: one per product area. Features never depend on other features. ---
include(":feature:onboarding")
include(":feature:auth")
include(":feature:profile")
include(":feature:startup")
include(":feature:arsky")
include(":feature:satellites")
include(":feature:passes")
include(":feature:play")
include(":feature:radio")
include(":feature:decode")
include(":feature:settings")

// Vendored third-party source — see third_party/backdrop/build.gradle.kts for why.
include(":third_party:backdrop")
