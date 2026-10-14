package com.developerstring.satride.feature.onboarding

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.developerstring.satride.core.designsystem.art.SpaceBackground
import com.developerstring.satride.core.designsystem.component.GlassPanel
import com.developerstring.satride.core.designsystem.component.SatRideWideButton
import com.developerstring.satride.core.designsystem.component.readableWidth
import com.developerstring.satride.core.designsystem.haptics.LocalSatRideHaptics
import com.developerstring.satride.core.designsystem.theme.MotionSpec
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.core.designsystem.theme.Spacing
import com.developerstring.satride.core.permissions.PermissionStatus
import com.developerstring.satride.core.permissions.SkyPermission
import com.developerstring.satride.core.permissions.rememberAppSettingsOpener
import com.developerstring.satride.core.permissions.rememberSkyPermissions
import org.koin.compose.viewmodel.koinViewModel

/**
 * The permission screen: three things, said once, before the app needs any of them.
 *
 * This exists because the alternative was worse in a specific way. Every permission used to be
 * asked for at the exact moment it was first needed, which is textbook advice and which here meant
 * the system dialog landed *on top of* the feature the user had just opened — the camera prompt
 * over a live AR view, the location prompt over an empty pass list that could not explain why it
 * was empty. Each one arrived with no context and cost a refusal that the app then had no way to
 * recover from, because a platform will not show its dialog twice.
 *
 * So the asking is pulled forward to one screen that can afford to explain itself, right after
 * sign-in and before the app proper. It is skippable, and skipping it is not a dead end: every
 * feature still degrades honestly without its permission, and the profile tab can grant any of
 * them later.
 *
 * The copy is deliberately three short lines. A permission screen that argues at length reads as a
 * screen that knows it is asking for too much.
 */
@Composable
fun PermissionsScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PermissionsViewModel = koinViewModel(),
) {
    val haptics = LocalSatRideHaptics.current
    val permissions = rememberSkyPermissions()
    val openSettings = rememberAppSettingsOpener()

    // The user can leave for system settings and come back with a different answer; without this
    // the rows would still show whatever was true when the screen was first composed.
    LifecycleResumeEffect(permissions) {
        permissions.refreshAll()
        onPauseOrDispose { }
    }

    // Whether the ask has happened yet — which is what turns the primary button from "Allow" into
    // "Continue". Tracked separately from the statuses because a user who declined everything has
    // still been asked, and showing them "Allow" a second time would just be a button that opens
    // nothing.
    var asked by remember { mutableStateOf(false) }
    // `asking` matters as well as `allSettled`: the three dialogs are shown one after another, so
    // partway through the run the later permissions are legitimately still unanswered and the
    // button must not yet offer to move on.
    val finished = asked && !permissions.asking && permissions.allSettled

    SpaceBackground(modifier = modifier.fillMaxSize(), tint = SatRideTheme.accents.tracking) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .readableWidth()
                .padding(horizontal = Spacing.xl),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { viewModel.complete(onDone) }) {
                    Text("Skip", style = MaterialTheme.typography.labelLarge)
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text(
                    text = "Three quick things",
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.padding(top = Spacing.md),
                )
                Text(
                    text = "So SatRide can actually find satellites for you.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Spacing.sm),
                )

                permissions.entries.forEach { (permission, controller) ->
                    PermissionRow(permission = permission, status = controller.status)
                }

                Text(
                    text = "Nothing leaves your phone. No photo or video is ever taken.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                )
            }

            Column(
                modifier = Modifier.padding(vertical = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                SatRideWideButton(
                    text = if (finished) "Continue" else "Allow access",
                    onClick = {
                        // Ignored mid-run: restarting the chain from the top while a dialog is
                        // open would ask for something twice.
                        if (permissions.asking) return@SatRideWideButton
                        haptics.press()
                        if (finished) {
                            viewModel.complete(onDone)
                        } else {
                            asked = true
                            permissions.requestAll()
                        }
                    },
                )

                // Only offered once the asking is over and something was refused: before that it
                // is a distraction, and after a clean run there is nothing in settings to fix.
                if (finished && openSettings != null &&
                    permissions.entries.any { (_, c) -> c.status == PermissionStatus.DENIED }
                ) {
                    TextButton(onClick = openSettings, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Change these in Settings",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** One permission: its illustration, what it is for, and where it currently stands. */
@Composable
private fun PermissionRow(permission: SkyPermission, status: PermissionStatus) {
    GlassPanel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PermissionScene(permission, Modifier.size(64.dp))
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(permission.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = permission.why,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusMark(status)
        }
    }
}

/**
 * The verdict, as a mark rather than a word.
 *
 * A tick, a dash, or nothing at all while the question is still open — a row that said
 * "Not requested" would be reporting on the app's internal state rather than on anything the
 * user has done.
 */
@Composable
private fun StatusMark(status: PermissionStatus) {
    val accents = SatRideTheme.accents
    val (mark, tint) = when (status) {
        PermissionStatus.GRANTED -> "✓" to accents.success
        PermissionStatus.DENIED -> "—" to accents.warning
        PermissionStatus.UNSUPPORTED -> "—" to MaterialTheme.colorScheme.onSurfaceVariant
        PermissionStatus.NOT_REQUESTED, PermissionStatus.PENDING -> null to Color.Unspecified
    }
    val settled by animateFloatAsState(
        targetValue = if (mark == null) 0f else 1f,
        animationSpec = MotionSpec.standard(),
        label = "permissionMark",
    )
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        if (mark != null) {
            Text(
                text = mark,
                style = MaterialTheme.typography.titleMedium,
                color = tint,
                modifier = Modifier.graphicsLayer {
                    alpha = settled
                    scaleX = 0.7f + 0.3f * settled
                    scaleY = 0.7f + 0.3f * settled
                },
            )
        }
    }
}
