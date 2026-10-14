plugins {
    id("satride.cmp.feature")
}

// An in-app browser, so the legal pages open inside SatRide rather than throwing the user out to
// Chrome or Safari. See InAppWebView for why a Custom Tab / SFSafariViewController is not enough.
kotlin {
    sourceSets {
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
    }
}
