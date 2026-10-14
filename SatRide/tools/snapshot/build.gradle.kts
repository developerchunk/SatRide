import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Builds the catalog snapshot that ships inside the app.
//
// A real module rather than a standalone script so it uses the app's own network layer, parsers
// and mappers: whatever this writes is by construction what a live refresh would produce, and
// there is no second implementation to drift.
//
// Not a dependency of anything the app builds — run it by hand when refreshing the bundle.
plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("com.developerstring.satride.tools.snapshot.MainKt")
}

tasks.named<JavaExec>("run") {
    // Gradle would otherwise run this from tools/snapshot, and the default output path is
    // written relative to the repository root where the resource actually lives.
    workingDir = rootProject.projectDir
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.network)
    implementation(projects.core.orbit)
    implementation(projects.core.data)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.java)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
