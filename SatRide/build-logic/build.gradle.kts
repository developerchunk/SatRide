plugins {
    `kotlin-dsl`
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Plugin markers rather than implementation artifacts: they resolve the same coordinates the
// `plugins {}` block would, so the convention plugins below can apply them by id.
dependencies {
    // Makes the generated `LibrariesForLibs` type visible inside precompiled script plugins,
    // so convention plugins can read gradle/libs.versions.toml the same way modules do.
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))

    implementation(
        "org.jetbrains.kotlin.multiplatform:org.jetbrains.kotlin.multiplatform.gradle.plugin:${libs.versions.kotlin.get()}",
    )
    implementation(
        "com.android.kotlin.multiplatform.library:com.android.kotlin.multiplatform.library.gradle.plugin:${libs.versions.agp.get()}",
    )
    implementation(
        "org.jetbrains.compose:org.jetbrains.compose.gradle.plugin:${libs.versions.composeMultiplatform.get()}",
    )
    implementation(
        "org.jetbrains.kotlin.plugin.compose:org.jetbrains.kotlin.plugin.compose.gradle.plugin:${libs.versions.kotlin.get()}",
    )
}
