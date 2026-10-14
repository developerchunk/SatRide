plugins {
    id("satride.kmp.library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(projects.core.common)

            // GitLive's KMP wrapper. `api` because AuthState and UserProfile are this module's
            // surface and callers need the coroutines/Flow types they expose.
            api(libs.firebase.auth)
            api(libs.firebase.firestore)
            implementation(libs.firebase.common)
            implementation(libs.kermit)
            implementation(libs.koin.core)
            implementation(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies {
            // `api(platform(...))`, not `implementation`.
            //
            // GitLive's Android artifacts declare `com.google.firebase:firebase-auth` and friends
            // with *no version* — they expect a BOM to supply it. Declared as `implementation` the
            // constraint stops at this module's boundary, and every consumer fails to resolve with
            // "Could not find com.google.firebase:firebase-auth:" — a version string that is
            // literally empty. `api` publishes the constraint to whoever depends on core:auth.
            // `project.dependencies.platform`: a KMP source-set block has no `platform()` of its own.
            api(project.dependencies.platform(libs.firebase.bom))

            // Credential Manager is the only Android-specific part: it is what produces the Google
            // ID token that Firebase Auth then exchanges for a session.
            implementation(libs.androidx.credentials)
            implementation(libs.androidx.credentials.playServices)
            implementation(libs.googleid)
        }
        jvmMain.dependencies {
            // Desktop initialises Firebase by hand — there is no google-services plugin for a JVM
            // process — and session persistence goes through the Java SDK's `FirebasePlatform`.
            implementation(libs.firebase.javaSdk)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
    }
}
