package com.developerstring.satride.core.designsystem.component

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.developerstring.satride.core.designsystem.theme.NumericTextStyles

/** Size variants for live numeric readouts. */
enum class NumericSize { Small, Medium, Large }

/**
 * Renders a changing number without horizontal jitter.
 *
 * Use this for anything that ticks — frequencies, azimuth/elevation, range, countdowns.
 * Proportional digits reflow as values change, which reads as the whole layout twitching once
 * per second.
 */
@Composable
fun NumericText(
    text: String,
    modifier: Modifier = Modifier,
    size: NumericSize = NumericSize.Medium,
    color: Color = LocalContentColor.current,
) {
    val style: TextStyle = when (size) {
        NumericSize.Small -> NumericTextStyles.small
        NumericSize.Medium -> NumericTextStyles.medium
        NumericSize.Large -> NumericTextStyles.large
    }
    Text(text = text, modifier = modifier, style = style, color = color, maxLines = 1)
}
