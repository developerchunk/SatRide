package com.developerstring.satride.feature.decode

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.developerstring.satride.core.designsystem.component.PhasePlaceholder

@Composable
fun DecodeScreen(modifier: Modifier = Modifier) {
    PhasePlaceholder(
        title = "Decode",
        phase = "P10",
        description = "Live SSTV, telemetry and audio decoding surfaces.",
        modifier = modifier,
    )
}
