package com.developerstring.satride.core.designsystem.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import com.developerstring.satride.core.designsystem.haptics.LocalSatRideHaptics
import com.developerstring.satride.core.designsystem.theme.MotionSpec
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.core.designsystem.theme.Spacing

/** Visual weight of an action. */
enum class SatRideButtonStyle { Primary, Secondary }

/**
 * The app's action button, restyled as a chunky physical control (Duolingo-style 3D press).
 *
 * The face sits on a darker "edge" of the same hue; pressing compresses the face onto the edge
 * and fires a haptic click. Depth from palette steps, not shadows — see
 * docs/design/kurzgesagt-style.md.
 *
 * [accent] defaults to the tracking accent; radio actions pass the radio accent so the
 * domain colour coding survives into controls.
 */
@Composable
fun SatRideButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: SatRideButtonStyle = SatRideButtonStyle.Primary,
    accent: Color = SatRideTheme.accents.tracking,
    enabled: Boolean = true,
) {
    val haptics = LocalSatRideHaptics.current
    val shape = RoundedCornerShape(16.dp)
    val edgeDepth = 5.dp

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val faceOffset by animateDpAsState(
        targetValue = if (pressed && enabled) edgeDepth - 1.dp else 0.dp,
        animationSpec = MotionSpec.fast(),
        label = "buttonPress",
    )

    LaunchedEffect(pressed) {
        if (pressed && enabled) haptics.press()
    }

    val shadow = SatRideTheme.accents.shadow
    val faceColor: Color
    val edgeColor: Color
    val contentColor: Color
    val borderColor: Color?
    when (style) {
        SatRideButtonStyle.Primary -> {
            faceColor = if (enabled) accent else MaterialTheme.colorScheme.surfaceVariant
            edgeColor = if (enabled) shadow.copy(alpha = 0.55f).compositeOver(accent) else shadow
            contentColor = if (enabled) shadow else MaterialTheme.colorScheme.onSurfaceVariant
            borderColor = null
        }

        SatRideButtonStyle.Secondary -> {
            faceColor = MaterialTheme.colorScheme.surfaceVariant
            edgeColor = shadow
            contentColor = if (enabled) accent else MaterialTheme.colorScheme.onSurfaceVariant
            borderColor = if (enabled) accent.copy(alpha = 0.45f) else SatRideTheme.accents.hairline
        }
    }

    // `width(IntrinsicSize.Max)` makes the face and edge agree on width in both sizing modes:
    // an un-widthed button wraps its label, and a `fillMaxWidth` caller fixes the constraints so
    // the intrinsic pass cannot shrink them. Without it one of the two layers ends up sized to
    // the text while the other fills the row.
    Box(modifier = modifier.height(Spacing.touchTarget + edgeDepth).width(IntrinsicSize.Max)) {
        // The edge: same silhouette, darker hue, sitting a step below the face.
        Box(
            Modifier
                .matchParentSize()
                .padding(top = edgeDepth)
                .background(edgeColor, shape),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = edgeDepth)
                .offset(y = faceOffset)
                .background(faceColor, shape)
                .then(
                    if (borderColor != null) Modifier.border(2.dp, borderColor, shape) else Modifier,
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                )
                .padding(horizontal = Spacing.xl),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge, color = contentColor, maxLines = 1)
        }
    }
}

/** Convenience for full-width call-to-action placements (onboarding, empty states). */
@Composable
fun SatRideWideButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: SatRideButtonStyle = SatRideButtonStyle.Primary,
    accent: Color = SatRideTheme.accents.sunshine,
    enabled: Boolean = true,
) {
    SatRideButton(
        text = text,
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        style = style,
        accent = accent,
        enabled = enabled,
    )
}
