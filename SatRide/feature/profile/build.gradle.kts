plugins {
    id("satride.cmp.feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.designsystem)
            implementation(projects.core.model)
            implementation(projects.core.auth)
            implementation(projects.core.data)
            // The permission rows, which this screen shows whether or not anyone is signed in.
            implementation(projects.core.permissions)
            implementation(projects.core.web)

            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.composeViewModel)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            // LifecycleResumeEffect, for re-reading permissions after a trip to system settings.
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
    }
}
