package com.developerstring.satride.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.developerstring.satride.core.designsystem.theme.Spacing

/**
 * Caps this composable's width and centres it in whatever space it was given.
 *
 * The one thing almost every screen needs on a large screen. Applied to a scrolling column or a
 * list's own modifier, the surrounding background still fills the window while the content stays
 * a readable measure — which is the difference between an app that supports tablets and one that
 * merely runs on them.
 */
fun Modifier.readableWidth(max: Dp = Spacing.contentMaxWidth): Modifier =
    // The order is the whole trick, and the obvious spelling does not work.
    //
    // `fillMaxWidth().widthIn(max)` leaves the element full width: `fillMaxWidth` pins *min* width
    // to the parent's, and `widthIn` can only narrow the maximum, so the minimum wins and nothing
    // changes. `wrapContentWidth` in between is what releases that minimum — and it centres the
    // result inside the space that was given up, which is the other half of what this is for.
    this.fillMaxWidth().wrapContentWidth().widthIn(max = max)

/**
 * Fills the window, then lays [content] out as a centred column of at most [max] width.
 *
 * Use where the content is not itself scrollable — a centred prompt, an empty state. Scrolling
 * screens want [readableWidth] on the scroll container instead, so the scrollbar and the touch
 * target still span the whole window.
 */
@Composable
fun ReadableColumn(
    modifier: Modifier = Modifier,
    max: Dp = Spacing.contentMaxWidth,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.readableWidth(max), contentAlignment = Alignment.Center, content = content)
    }
}
