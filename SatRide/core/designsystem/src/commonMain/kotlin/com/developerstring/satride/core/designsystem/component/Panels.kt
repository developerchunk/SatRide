package com.developerstring.satride.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.core.designsystem.theme.Spacing

/**
 * The app's card: a solid rounded "toy panel" in the illustrated style — surface fill sitting
 * on a sliver of the shadow colour, with a chunky hairline border. Over the camera/sky layer it
 * stays readable because the fill is near-opaque; overlays should still never cover more than
 * about a third of the AR view.
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    contentPadding: PaddingValues = PaddingValues(Spacing.lg),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            // The bottom "edge" step that makes the panel read as a physical tile.
            .background(SatRideTheme.accents.shadow.copy(alpha = 0.9f), shape)
            .padding(bottom = 3.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f), shape)
            .border(1.5.dp, SatRideTheme.accents.hairline, shape)
            .padding(contentPadding),
        content = content,
    )
}

/** A small labelled status chip — sensor accuracy, pass quality, live session state. */
@Composable
fun AccentChip(
    label: String,
    modifier: Modifier = Modifier,
    accent: androidx.compose.ui.graphics.Color = SatRideTheme.accents.tracking,
    leading: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .background(accent.copy(alpha = 0.20f), shape)
            .border(1.5.dp, accent.copy(alpha = 0.55f), shape)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs + Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        leading?.invoke()
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Section heading with an optional trailing action, used across list and detail screens. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        trailing?.invoke()
    }
}
