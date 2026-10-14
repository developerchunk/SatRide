plugins {
    id("satride.kmp.library")
}

kotlin {
    // Sensor frame conventions are the kind of thing that has to be stated once and referred to
    // everywhere, so the public surface of this module is part of the contract.
    explicitApi()

    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(projects.core.common)
            // For Quaternion/Vector3 and the WMM declination model. core:orbit stays pure; the
            // dependency runs this way only.
            api(projects.core.orbit)
        }
        jvmMain.dependencies {
            // The desktop location source reads one small JSON document — see IpGeolocation.kt.
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
    }
}
