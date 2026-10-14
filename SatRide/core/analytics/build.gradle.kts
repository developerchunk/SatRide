plugins {
    id("satride.kmp.library")
}

// Product analytics and crash reporting, behind an interface.
//
// The interface is the point. Firebase is an Android-only dependency here — the KMP wrapper this
// project uses covers auth and Firestore but not Analytics or Crashlytics — so common code that
// called it directly could not compile for iOS or desktop. Reporting through a seam means every
// screen logs the same way on every platform, and the platforms that have no backend get a no-op
// instead of a compile error.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.common)

            implementation(libs.kermit)
            implementation(libs.koin.core)
        }
        androidMain.dependencies {
            // `api(platform(...))` for the same reason core:auth does it: the Firebase artifacts
            // below are declared without versions and need the BOM's constraint to reach consumers.
            api(project.dependencies.platform(libs.firebase.bom))
            implementation(libs.firebase.analytics)
            implementation(libs.firebase.crashlytics)
        }
    }
}
