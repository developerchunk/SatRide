plugins {
    id("satride.cmp.feature")
    // Multi-Play's room and player documents are `@Serializable` for Firestore.
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.designsystem)
            implementation(projects.core.model)
            implementation(projects.core.common)
            // Brings core:orbit with it — SGP4, the frame conversions and the vector math the
            // globe is rendered from all arrive through here.
            implementation(projects.core.data)
            // Multi-Play: rooms live in Firestore (through core:auth's wrapper), the tilt camera
            // reads the attitude sensor, and the craft/controls preferences are settings.
            implementation(projects.core.auth)
            implementation(projects.core.sensors)
            implementation(projects.core.datastore)

            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.composeViewModel)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kermit)
        }
        androidMain.dependencies {
            // The arena locks the activity to landscape and hides the system bars while it runs.
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
    }
}
