plugins {
    id("satride.cmp.feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.designsystem)
            implementation(projects.core.model)
            // Brings core:sensors and core:orbit with it — the tracker, the attitude stream and
            // the frame conventions all arrive through here.
            implementation(projects.core.data)
            // `api`, not `implementation`: PermissionStatus is a field of SkyUiState, so anything
            // reading this feature's state needs the type on its own compile classpath.
            api(projects.core.permissions)

            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.composeViewModel)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            // LocalLifecycleOwner, which CameraX binds its preview to.
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kermit)
        }
        androidMain.dependencies {
            implementation(libs.camerax.core)
            implementation(libs.camerax.camera2)
            implementation(libs.camerax.lifecycle)
            implementation(libs.camerax.view)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
    }
}
