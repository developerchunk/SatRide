plugins {
    id("satride.cmp.feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.designsystem)
            implementation(projects.core.model)
            implementation(projects.core.web)
            // `api`, not `implementation`: ObserverMode and UnitSystem are fields of
            // SettingsUiState, so anything reading this feature's state needs them on its own
            // compile classpath.
            api(projects.core.datastore)

            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.composeViewModel)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
        }
    }
}
