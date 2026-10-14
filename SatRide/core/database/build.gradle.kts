plugins {
    id("satride.kmp.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    // Room's KSP processor generates the `actual object` for SatRideDatabaseConstructor, and
    // expect/actual *classes* are still flagged as beta. The warning is about generated code we
    // do not own and cannot annotate, so it is suppressed at the module level rather than
    // tolerated as permanent build noise.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            // `api`: the Koin module below and AppDispatchers are both part of this module's
            // surface, so consumers need them on their own classpath.
            api(projects.core.common)

            implementation(libs.room.runtime)
            implementation(libs.sqlite.bundled)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
    }
}

room {
    // Exported schemas are checked in. They are what makes a migration reviewable — a diff of
    // the generated JSON shows exactly what changed, and Room can only verify a migration
    // against a schema it has on disk.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // Room's processor runs per target: the generated code is platform-specific (the Android
    // implementation differs from the Native one), so one `ksp` line would only cover one.
    add("kspAndroid", libs.room.compiler)
    add("kspIosArm64", libs.room.compiler)
    add("kspIosSimulatorArm64", libs.room.compiler)
    add("kspJvm", libs.room.compiler)
}
