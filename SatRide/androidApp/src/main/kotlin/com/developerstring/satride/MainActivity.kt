package com.developerstring.satride

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.developerstring.satride.core.common.SkyDeepLink
import com.developerstring.satride.core.common.SkyGuideRequests
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val guideRequests: SkyGuideRequests by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge is not cosmetic here: the sky view (P5) renders camera behind the
        // system bars, so the window must be full-bleed from the first frame.
        //
        // Both bars are given an explicitly transparent scrim rather than the defaults. Bare
        // `enableEdgeToEdge()` uses `SystemBarStyle.auto`, which paints a translucent scrim behind
        // the bars on API levels that cannot render them fully transparent — so the app drew a
        // faint band at the bottom that no Compose code owned and none of the theming could reach.
        // `dark(TRANSPARENT)` also pins the icons light, which is correct on a permanently dark app.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        // Cold start: the app was launched *by* the link. Handled before `setContent`, so the
        // request is already waiting on the bus when the sky view first composes rather than
        // arriving a frame later and making the screen visibly jump.
        handleDeepLink(intent)

        setContent {
            App()
        }
    }

    /**
     * Warm start: the app was already running and the link was delivered to the existing task.
     *
     * `setIntent` matters — without it `getIntent()` keeps returning the launch intent, so a second
     * notification tapped in the same session would replay the first one's satellite.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val target = SkyDeepLink.parse(intent?.data?.toString()) ?: return
        guideRequests.request(noradId = target.noradId, guide = target.guide)
    }
}
