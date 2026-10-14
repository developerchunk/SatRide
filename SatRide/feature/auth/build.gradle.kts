plugins {
    id("satride.cmp.feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.designsystem)
            implementation(projects.core.web)
            implementation(projects.core.model)
            implementation(projects.core.auth)
            implementation(projects.core.data)

            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.composeViewModel)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.kermit)
            // The KMPLiquidGlass backdrop library, vendored and built against this project's
            // Compose version — the published binary targets CMP 1.9.3 and crashes on node
            // detach under CMP 1.11. See third_party/backdrop/build.gradle.kts.
            implementation(projects.thirdParty.backdrop)
        }
        androidMain.dependencies {
            implementation(libs.androidx.credentials)
            implementation(libs.androidx.credentials.playServices)
            implementation(libs.googleid)
            implementation(libs.androidx.activity.compose)
        }
        jvmMain.dependencies {
            // Only to read Google's token response. The exchange itself goes through
            // `java.net.http`, which the JDK ships, so desktop sign-in adds no HTTP stack.
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
    }
}
