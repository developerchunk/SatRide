import org.gradle.accessors.dm.LibrariesForLibs

/**
 * Convention for modules that render UI: the base KMP setup plus Compose Multiplatform.
 *
 * Compose artifacts come from the version catalog rather than the `compose.*` plugin accessors
 * (deprecated in Compose Multiplatform 1.11) so versions stay declared in one place.
 */

plugins {
    id("satride.kmp.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val libs = the<LibrariesForLibs>()

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
        }
    }
}
