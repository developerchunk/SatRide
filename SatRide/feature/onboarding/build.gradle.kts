plugins {
    id("satride.cmp.feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.designsystem)
            implementation(projects.core.model)
            implementation(projects.core.datastore)
            // The permission screen asks for all three up front; see PermissionsScreen.
            implementation(projects.core.permissions)
            implementation(projects.core.analytics)

            implementation(libs.kotlinx.datetime)

            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.composeViewModel)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            // LifecycleResumeEffect, for re-reading permissions after a trip to system settings.
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
    }
}
