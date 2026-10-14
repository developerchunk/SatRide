plugins {
    id("satride.kmp.library")
    // Test-only: the reference fixture is JSON. The plugin adds no runtime dependency to
    // commonMain, which stays free of everything but the standard library and datetime.
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    // This module is extracted as the `orbitk` library in P13, so its public surface is part
    // of the deliverable rather than an implementation detail.
    explicitApi()

    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.datetime)
            implementation(libs.okio)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

// Test fixtures are real data files (see src/commonTest/resources/FIXTURES.md), not embedded
// strings: a 140 KB golden-output literal would exceed the JVM's constant-pool limit, and
// keeping them as files means they stay reviewable and regenerable. The Gradle wiring that makes
// them readable from commonTest lives in the satride.kmp.library convention plugin.
