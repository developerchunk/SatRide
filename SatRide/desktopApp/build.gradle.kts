import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    // The desktop shell owns the NavHostController so Escape can drive it — see Main.kt.
    implementation(libs.androidx.navigation.compose)
    implementation(project(":composeApp"))
    // For the --force-sync and --track development flags; see Main.kt.
    implementation(project(":core:data"))
    implementation(project(":core:datastore"))
    implementation(project(":core:model"))
    // Firebase is started by hand on the JVM, before Koin — see Main.kt.
    implementation(project(":core:auth"))
    // Pass-alert timers are rebuilt from the store on every launch — see Main.kt.
    implementation(project(":core:notifications"))

    implementation(libs.kermit)

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)

    implementation(libs.compose.uiToolingPreview)
}

/*
 * Firebase configuration for the desktop process.
 *
 * The same `google-services.json` the Android build consumes is bundled as a classpath resource,
 * so signing in on the desktop joins the same project as the phones without a second config file
 * to keep in step. Both candidate locations are git-ignored; a checkout without either simply runs
 * signed out, which every screen already handles.
 */
val googleServicesJson: File? = listOf(
    rootProject.file("androidApp/google-services.json"),
    rootProject.file("google-services/google-services.json"),
).firstOrNull { it.isFile }

tasks.processResources {
    googleServicesJson?.let { from(it) }
}

compose.desktop {
    application {
        mainClass = "com.developerstring.satride.MainKt"

        jvmArgs += listOf(
            // The macOS menu bar shows this rather than the main class name when run from Gradle.
            "-Dapple.awt.application.name=SatRide",
            "-Dapple.awt.application.appearance=system",
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)

            // The user-visible name. Identifiers stay `satride`; see bundleID below.
            packageName = "SatRide"
            // Kept level with `versionName` in androidApp/build.gradle.kts and APP_VERSION in
            // composeApp's AppModule.kt.
            packageVersion = "1.0.2"
            description = "Satellite tracking: a live planetarium, pass predictions and radio details."
            vendor = "DeveloperString"
            copyright = "© 2026 DeveloperString"

            // From `./gradlew :desktopApp:suggestRuntimeModules`, plus `jdk.httpserver` for the
            // Google sign-in loopback listener, which the suggestion tool cannot see because the
            // class is only touched when someone presses the button.
            modules(
                "java.compiler",
                "java.instrument",
                "java.management",
                "java.naming",
                "java.net.http",
                "java.sql",
                "jdk.unsupported",
                "jdk.httpserver",
            )

            // Icons are generated — see tools/icon/README.md. Edit the script, never these files.
            macOS {
                bundleID = "com.developerstring.satride"
                dockName = "SatRide"
                iconFile.set(project.file("src/main/icons/satride.icns"))
            }
            windows {
                iconFile.set(project.file("src/main/icons/satride.ico"))
                menu = true
                shortcut = true
                // Stable across versions so an MSI upgrade replaces the previous install.
                upgradeUuid = "6f5a0b1e-3c2d-4e7a-9b8f-2d1c0a9e8b7f"
            }
            linux {
                iconFile.set(project.file("src/main/icons/satride.png"))
            }
        }

        buildTypes.release.proguard {
            // Off, deliberately. Room, Ktor, kotlinx.serialization (every type-safe navigation
            // route is a serializer) and Firebase all need keep rules that have not been written
            // for the JVM build, and a stripped release that fails on the first parameterized route
            // is worse than an unstripped one that works. Revisit when download size matters.
            isEnabled.set(false)
        }
    }
}
