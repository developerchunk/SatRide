import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    // Reads `androidApp/google-services.json` and generates the string resources Firebase's
    // auto-init ContentProvider reads at launch. Applied here and nowhere else: the shared modules
    // talk to Firebase through the KMP wrapper and never touch the config.
    alias(libs.plugins.googleServices)
    // Uploads the R8 mapping file with each release build, which is what makes an obfuscated
    // stack trace readable in the Crashlytics console.
    alias(libs.plugins.crashlytics)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}
dependencies {
    implementation(project(":composeApp"))

    // Pins the native Firebase versions the KMP wrapper resolves against. Without the BOM a
    // transitive bump in one artifact can silently desynchronise it from the others, which shows
    // up as a `NoSuchMethodError` at runtime rather than a resolution failure at build time.
    implementation(platform(libs.firebase.bom))

    // Google sign-in lives in `core:auth`, but Credential Manager's Play-services backend has to
    // be on the *application* classpath to be discovered.
    implementation(libs.androidx.credentials.playServices)
    // The activity reads `satride://` links and puts them on the guide bus, both of which live in
    // core:common. composeApp depends on it with `implementation`, so it is not exposed here.
    implementation(project(":core:common"))

    // The alarm and boot receivers are declared in this module's manifest, so they have to be on
    // this module's *compile* classpath — not merely on the runtime one, where they already arrive
    // transitively. Without it lint reports MissingClass, which is the right complaint: a manifest
    // naming a class the module cannot see is one refactor away from being a crash at boot.
    implementation(project(":core:notifications"))

    // The Application installs the context the analytics backend needs.
    implementation(project(":core:analytics"))

    implementation(libs.androidx.activity.compose)

    // Koin is needed here (not just in composeApp) because the Application class starts the
    // graph and contributes the Android context to it.
    implementation(libs.koin.core)
    implementation(libs.koin.android)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

/**
 * Puts the bundled catalog where Android can read it.
 *
 * The file lives once, in composeApp's resources; this copies it into the APK's assets rather than
 * keeping a second copy in version control. Compose Resources would normally carry it, but it does
 * not package `commonMain` files for the Android target under the AGP KMP plugin — see
 * `composeApp/src/commonMain/.../data/CatalogSnapshot.kt`.
 */
val bundledAssetsDir = layout.buildDirectory.dir("generated/bundledAssets").get().asFile

val copyCatalogSnapshot by tasks.registering(Copy::class) {
    from(rootProject.layout.projectDirectory.dir("composeApp/src/commonMain/composeResources/files"))
    // The design system's bundled fonts ride the same workaround: desktop and iOS load them
    // through Compose Resources, Android reads `assets/fonts/` (see designsystem's
    // FontFamilies.android.kt).
    from(rootProject.layout.projectDirectory.dir("core/designsystem/src/commonMain/composeResources/font")) {
        into("fonts")
    }
    into(bundledAssetsDir)
}

/**
 * Release signing credentials, from `keystore.properties` or the environment.
 *
 * Two sources because there are two callers. A developer keeps a gitignored `keystore.properties`
 * pointing at a keystore that lives *outside* the repository; CI has no such file and supplies the
 * same four values as secrets. Neither the keystore nor the passwords are ever in version control.
 *
 * Returns null when nothing is configured, and the release build then falls back to debug signing
 * rather than failing. That matters because `assembleRelease` is also how release *performance* is
 * measured, and a contributor with no signing credentials still has to be able to build it — they
 * simply cannot produce something uploadable, which is the correct outcome.
 */
val releaseSigning: Map<String, String>? = run {
    val properties = rootProject.file("keystore.properties")
    val values = if (properties.exists()) {
        Properties()
            .apply { properties.inputStream().use { stream -> load(stream) } }
            .entries
            .associate { entry -> entry.key.toString() to entry.value.toString() }
    } else {
        mapOf(
            "storeFile" to System.getenv("SATRIDE_STORE_FILE").orEmpty(),
            "storePassword" to System.getenv("SATRIDE_STORE_PASSWORD").orEmpty(),
            "keyAlias" to System.getenv("SATRIDE_KEY_ALIAS").orEmpty(),
            "keyPassword" to System.getenv("SATRIDE_KEY_PASSWORD").orEmpty(),
        )
    }
    values.takeIf { candidate ->
        candidate.keys.containsAll(listOf("storeFile", "storePassword", "keyAlias", "keyPassword")) &&
            candidate.values.none { it.isBlank() } &&
            file(candidate.getValue("storeFile")).exists()
    }
}

android {
    namespace = "com.developerstring.satride"
    compileSdk = providers.gradleProperty("satride.android.compileSdk").get().toInt()

    defaultConfig {
        applicationId = "com.developerstring.satride"
        minSdk = providers.gradleProperty("satride.android.minSdk").get().toInt()
        targetSdk = providers.gradleProperty("satride.android.targetSdk").get().toInt()
        versionCode = 5
        versionName = "1.0.2"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    signingConfigs {
        // Created only when credentials are actually available; `release` below falls back to the
        // debug key otherwise, so a clone with no keystore still builds.
        if (releaseSigning != null) {
            create("upload") {
                storeFile = file(releaseSigning.getValue("storeFile"))
                storePassword = releaseSigning.getValue("storePassword")
                keyAlias = releaseSigning.getValue("keyAlias")
                keyPassword = releaseSigning.getValue("keyPassword")
                // v1 as well as v2/v3: minSdk is 26, so v2 alone would be enough for Play, but the
                // JAR signature costs nothing and keeps the artifact verifiable by older tooling.
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }
    buildTypes {
        release {
            // The upload key when it is configured, the debug key when it is not.
            //
            // The fallback is not a loophole — a debug-signed bundle is rejected at upload, so it
            // cannot be shipped by accident. It exists so that `assembleRelease` still works for
            // anyone measuring release performance, which is the only performance that matters and
            // which is not measurable on a debug build.
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")

            // R8 in full mode: shrink, obfuscate and strip unused resources. See
            // `proguard-rules.pro` for the handful of reflective lookups that need protecting —
            // notably the serializers behind every type-safe navigation route, which fail on the
            // release build only.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )

            /*
             * Native debug symbols, uploaded with the bundle so Play can symbolicate native
             * crashes and ANRs. Without this the Console warns on every upload, and any native
             * crash arrives as bare hex addresses that cannot be read.
             *
             * SatRide has no NDK code of its own — all twenty `.so` files come from AndroidX
             * (`sqlite-bundled`, `datastore`, `graphics-path`, CameraX). Of those, only
             * `libsqliteJni.so` and `libdatastore_shared_counter.so` still carry symbols, and
             * what they carry is a `.symtab` — no DWARF `.debug_*` sections at all.
             *
             * `SYMBOL_TABLE` rather than `FULL` because that is what these libraries actually
             * hold: the two levels run different tasks — `FULL` runs
             * `extractReleaseNativeDebugMetadata` (DWARF) and `SYMBOL_TABLE` runs
             * `extractReleaseNativeSymbolTables` (`.symtab`) — and `FULL` does not fall back.
             *
             * **This setting cannot silence the Play Console warning for this app, and that is not
             * a misconfiguration.** AGP does inspect dependency `.so` files — it is not limited to
             * `externalNativeBuild` output — but it cannot recover information the publisher never
             * shipped. Running the task with `--info` says so for every one of our libraries:
             *
             *     Unable to extract native debug metadata from …/libsqliteJni.so
             *     because the native debug metadata has already been stripped.
             *
             * AndroidX publishes these prebuilt and stripped. Nothing in this build script changes
             * that; the fix would have to come from the library, and the warning is advisory —
             * bundles upload and ship normally with it. See `docs/plan/play-store-listing.md`.
             *
             * The setting stays because it is correct for what it covers, and becomes load-bearing
             * the moment SatRide gains native code of its own.
             */
            ndk.debugSymbolLevel = "FULL"
        }
        debug {
            // Kept off in debug so the build stays fast and stack traces stay readable; the release
            // build is the one that has to survive shrinking, and it is verified separately.
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }

    sourceSets.named("main") {
        assets.srcDir(bundledAssetsDir)
    }
}

tasks.named("preBuild") { dependsOn(copyCatalogSnapshot) }

/*
 * Says out loud which key a release artifact will be signed with.
 *
 * The failure this prevents is quiet and expensive: a debug-signed bundle looks completely normal
 * locally, builds without complaint, and is only rejected at the end of an upload — after the
 * listing has been filled in. One line in the build log makes it impossible to miss.
 *
 * Emitted at configuration time rather than from a `doLast`. A task action that reads
 * `releaseSigning` captures the build script itself, which the configuration cache cannot
 * serialise — it fails the build with "cannot serialize Gradle script object references". Logging
 * here reads the value while the script is still executing, so nothing is captured.
 *
 * The trade-off is that it only prints when the configuration cache misses, so a repeat build is
 * silent. Treat it as a reminder, not a guarantee. The check that always works, on the artifact
 * itself rather than on the build:
 *
 *     keytool -printcert -jarfile androidApp/build/outputs/bundle/release/androidApp-release.aab
 *
 * The upload key is `CN=Aditya Shinde`; the debug key is `CN=Android Debug`.
 */
if (gradle.startParameter.taskNames.any { it.contains("elease", ignoreCase = false) }) {
    if (releaseSigning != null) {
        logger.lifecycle("SatRide: release will be signed with the upload key (${releaseSigning["keyAlias"]}).")
    } else {
        logger.warn(
            "SatRide: release will be signed with the DEBUG key — Play rejects those. " +
                "Fill in keystore.properties (see keystore.properties.example).",
        )
    }
}
