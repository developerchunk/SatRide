package com.developerstring.satride.core.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.developerstring.satride.core.designsystem.generated.resources.Res
import com.developerstring.satride.core.designsystem.generated.resources.baloo2_bold
import com.developerstring.satride.core.designsystem.generated.resources.baloo2_extrabold
import com.developerstring.satride.core.designsystem.generated.resources.baloo2_semibold
import com.developerstring.satride.core.designsystem.generated.resources.nunito_bold
import com.developerstring.satride.core.designsystem.generated.resources.nunito_extrabold
import com.developerstring.satride.core.designsystem.generated.resources.nunito_medium
import com.developerstring.satride.core.designsystem.generated.resources.nunito_regular
import com.developerstring.satride.core.designsystem.generated.resources.nunito_semibold
import org.jetbrains.compose.resources.Font

@Composable
internal actual fun satRideFontFamilies(): SatRideFontFamilies = SatRideFontFamilies(
    heading = FontFamily(
        Font(Res.font.baloo2_semibold, FontWeight.SemiBold),
        Font(Res.font.baloo2_bold, FontWeight.Bold),
        Font(Res.font.baloo2_extrabold, FontWeight.ExtraBold),
    ),
    body = FontFamily(
        Font(Res.font.nunito_regular, FontWeight.Normal),
        Font(Res.font.nunito_medium, FontWeight.Medium),
        Font(Res.font.nunito_semibold, FontWeight.SemiBold),
        Font(Res.font.nunito_bold, FontWeight.Bold),
        Font(Res.font.nunito_extrabold, FontWeight.ExtraBold),
    ),
)
