plugins {
    id("satride.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.model)
            // `api`, not `implementation`: AppDispatchers exposes CoroutineDispatcher and the
            // repositories expose Flow, so every consumer needs coroutines on its own classpath.
            api(libs.kotlinx.coroutines.core)
            api(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
