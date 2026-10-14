plugins {
    id("satride.kmp.library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(projects.core.common)
            api(projects.core.orbit)
            api(projects.core.sensors)
            api(projects.core.network)
            implementation(projects.core.database)
            api(projects.core.datastore)
            // `api`: the alert types appear in this module's own surface (PassAlertCoordinator)
            // and the passes feature schedules reminders through them.
            api(projects.core.notifications)

            implementation(libs.kotlinx.serialization.json)
            api(libs.kotlinx.datetime)
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.client.core)
            implementation(libs.okio)
            // The repositories are tested against a real in-memory database and the real Ktor
            // stack rather than fakes, so these are needed to build one. core:database keeps Room
            // as an implementation detail, which is why they are repeated here.
            implementation(libs.room.runtime)
            implementation(libs.sqlite.bundled)
        }
    }
}
