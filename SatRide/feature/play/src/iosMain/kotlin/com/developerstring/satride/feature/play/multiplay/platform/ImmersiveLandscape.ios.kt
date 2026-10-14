package com.developerstring.satride.feature.play.multiplay.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import platform.UIKit.UIApplication
import platform.UIKit.UIInterfaceOrientationMask
import platform.UIKit.UIInterfaceOrientationMaskLandscape
import platform.UIKit.UIInterfaceOrientationMaskPortrait
import platform.UIKit.UIWindowScene
import platform.UIKit.UIWindowSceneGeometryPreferencesIOS

@Composable
actual fun ImmersiveLandscape() {
    DisposableEffect(Unit) {
        val app = UIApplication.sharedApplication
        app.idleTimerDisabled = true
        requestOrientation(UIInterfaceOrientationMaskLandscape)
        onDispose {
            app.idleTimerDisabled = false
            // Snap back to portrait; the app's plist lets the user rotate freely from there.
            requestOrientation(UIInterfaceOrientationMaskPortrait)
        }
    }
}

private fun requestOrientation(mask: UIInterfaceOrientationMask) {
    val scene = UIApplication.sharedApplication.connectedScenes.firstOrNull() as? UIWindowScene ?: return
    val preferences = UIWindowSceneGeometryPreferencesIOS(interfaceOrientations = mask)
    // iOS 16+. The root view controller already allows every orientation the plist lists, so
    // asking the scene for landscape is enough; no `setNeedsUpdateOfSupportedInterfaceOrientations`
    // (which the bundled Kotlin/Native SDK bindings do not expose anyway).
    scene.requestGeometryUpdateWithPreferences(preferences, errorHandler = null)
}

actual val motionControlsAvailable: Boolean = true
