package com.developerstring.satride.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Spacing scale. Every gap in the app should come from here, not a literal. */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp

    /** Minimum comfortable touch target; enforced on all interactive elements. */
    val touchTarget = 48.dp

    /**
     * How far the last thing on a tab screen must stay clear of the bottom of the display.
     *
     * The tab bar **floats over** the content rather than reserving space for it — that is what
     * lets the sky and the globe run to the edge of the screen — so nothing is automatically kept
     * out from under it. Anything a user has to read or tap has to make that room itself, and a
     * screen that forgets ends up with its most important control (the AR view's [Turn on] prompt
     * was the reported case) sitting underneath the pill.
     *
     * Measured against the bar itself: the pill is the icon plus its label inside `Spacing.lg` of
     * vertical padding, and this leaves a small margin on top of that. It is deliberately *not*
     * the system navigation-bar inset, which the bar already adds for itself.
     */
    val navBarClearance = 88.dp

    /**
     * The widest a column of text or cards is allowed to get.
     *
     * On a tablet or a rotated phone the window is far wider than anything in this app wants to
     * be. A settings row stretched across 1200 px puts its label and its switch a hand's width
     * apart, and a paragraph at that measure is genuinely harder to read — the eye loses the line
     * on the way back. Content is capped here and centred; the background still fills the window,
     * so the screen reads as a wide surface with a column on it rather than as a phone layout
     * that has been pulled out of shape.
     */
    val contentMaxWidth = 640.dp

    /** The floating tab bar's ceiling. A pill the width of a tablet is a stripe, not a control. */
    val navBarMaxWidth = 560.dp
}

// Generous radii: nothing in the illustrated style has a sharp corner.
internal val SatRideShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
