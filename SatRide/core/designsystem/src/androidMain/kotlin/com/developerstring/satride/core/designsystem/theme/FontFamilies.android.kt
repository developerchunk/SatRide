package com.developerstring.satride.core.designsystem.theme

import android.content.res.AssetManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * Android reads the TTFs from APK assets rather than Compose Resources, which does not package
 * `commonMain` resources for the Android target under the AGP KMP plugin. The single source of
 * truth stays `commonMain/composeResources/font/`; androidApp's `copyCatalogSnapshot` task
 * copies them into `assets/fonts/` at build time.
 */
@Composable
internal actual fun satRideFontFamilies(): SatRideFontFamilies {
    val assets: AssetManager = LocalContext.current.assets
    return remember(assets) {
        SatRideFontFamilies(
            heading = FontFamily(
                Font("fonts/baloo2_semibold.ttf", assets, FontWeight.SemiBold),
                Font("fonts/baloo2_bold.ttf", assets, FontWeight.Bold),
                Font("fonts/baloo2_extrabold.ttf", assets, FontWeight.ExtraBold),
            ),
            body = FontFamily(
                Font("fonts/nunito_regular.ttf", assets, FontWeight.Normal),
                Font("fonts/nunito_medium.ttf", assets, FontWeight.Medium),
                Font("fonts/nunito_semibold.ttf", assets, FontWeight.SemiBold),
                Font("fonts/nunito_bold.ttf", assets, FontWeight.Bold),
                Font("fonts/nunito_extrabold.ttf", assets, FontWeight.ExtraBold),
            ),
        )
    }
}
