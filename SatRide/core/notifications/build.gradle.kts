plugins {
    id("satride.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            // `api`: the Koin module and the alert types are this module's whole surface, and
            // both expose Flow and Clock from core:common.
            api(projects.core.common)

            implementation(libs.kermit)
        }
        androidMain.dependencies {
            // NotificationCompat and the notification-channel helpers.
            implementation(libs.androidx.core.ktx)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
    }
}
