plugins {
    id("satride.cmp.feature")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    // The iOS framework is produced here: composeApp is the app shell that every platform
    // entry point (androidApp, iosApp, desktopApp) hosts.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.designsystem)
            implementation(projects.core.web)
            implementation(projects.core.model)
            implementation(projects.core.common)
            implementation(projects.core.data)
            // The shell adapts the hardware haptics peripheral into the design system's
            // LocalSatRideHaptics, and reads onboarding state to route the first launch.
            implementation(projects.core.sensors)
            implementation(projects.core.datastore)

            implementation(projects.core.auth)
            // The reporting seam; the Firebase backends behind it are Android-only.
            implementation(projects.core.analytics)

            implementation(projects.feature.onboarding)
            implementation(projects.feature.auth)
            implementation(projects.feature.profile)
            implementation(projects.feature.startup)
            implementation(projects.feature.arsky)
            implementation(projects.feature.satellites)
            implementation(projects.feature.passes)
            implementation(projects.feature.play)
            implementation(projects.feature.radio)
            implementation(projects.feature.decode)
            implementation(projects.feature.settings)

            implementation(libs.androidx.navigation.compose)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)

            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.composeViewModel)

            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kermit)
            // The vendored KMPLiquidGlass backdrop library: the tab bar's selection capsule is a
            // real lens over the bar, not a painted highlight. Same reasoning as feature:auth —
            // vendored because the published binary targets CMP 1.9.3.
            implementation(projects.thirdParty.backdrop)
        }
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
            implementation(libs.koin.android)
        }
        commonTest.dependencies {
            implementation(libs.koin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            // Skiko's native runtime, so the offscreen globe render test can rasterise.
            implementation(compose.desktop.currentOs)
            // The vendored liquid-glass library, so the preview render test can prove the
            // lens actually refracts (see UiPreviewRenderTest's glass-lab frame).
            implementation(projects.thirdParty.backdrop)
            // `Module.verify()` is reflective and JVM-only.
            implementation(libs.koin.test)
            // Only so the graph verification can *name* the Ktor types it exempts; the app shell
            // itself never touches Ktor directly.
            implementation(libs.ktor.client.core)
            // The resolution test builds the real view models, so their modules must be visible.
            implementation(projects.feature.passes)
            implementation(projects.feature.satellites)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}
