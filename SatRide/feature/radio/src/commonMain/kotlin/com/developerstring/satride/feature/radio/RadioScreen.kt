package com.developerstring.satride.feature.radio

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.developerstring.satride.core.designsystem.component.PhasePlaceholder

@Composable
fun RadioScreen(modifier: Modifier = Modifier) {
    PhasePlaceholder(
        title = "Radio",
        phase = "P8",
        description = "Spectrum, waterfall and tuning for a connected RTL-SDR.",
        modifier = modifier,
    )
}
