plugins {
    id("satride.cmp.feature")
}

// Runtime permissions, owned here rather than by whichever feature happened to need one first.
//
// The camera and location controllers used to live in `feature:arsky` and the notification one in
// `feature:passes`, which was fine while each was asked for in exactly one place. It stopped being
// fine the moment the priming screen and the profile screen needed all three: features must not
// depend on features, so there was no legal way for either to reach them.
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kermit)
        }
        androidMain.dependencies {
            // `rememberLauncherForActivityResult`, and the permission-check helpers.
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
        }
    }
}
