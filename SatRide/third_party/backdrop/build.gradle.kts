/*
 * Vendored from https://github.com/Kashif-E/KMPLiquidGlass (Apache License 2.0 — see LICENSE
 * in this directory), which is itself based on Kyant0's AndroidLiquidGlass.
 *
 * Vendored rather than consumed as a binary because the published 0.0.1-alpha02 artifact is
 * compiled against Compose Multiplatform 1.9.3, whose Modifier.Node internals crash on detach
 * under the CMP 1.11 this project uses. Building the same source against our own Compose
 * version removes the skew. Local changes are kept to the minimum needed to compile here.
 */
plugins {
    id("satride.cmp.feature")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xcontext-parameters",
            "-Xexpect-actual-classes",
        )
    }

    sourceSets {
        // The library shares one Skia-backed implementation between desktop and iOS. Wired as
        // an extra source directory on both rather than a custom intermediate source set: the
        // default hierarchy template owns jvmMain/iosMain, and srcDir composes with it cleanly.
        jvmMain.get().kotlin.srcDir("src/skiaMain/kotlin")
        iosMain.get().kotlin.srcDir("src/skiaMain/kotlin")
    }
}
