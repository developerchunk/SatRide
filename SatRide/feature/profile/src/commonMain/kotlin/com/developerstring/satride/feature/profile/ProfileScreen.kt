package com.developerstring.satride.feature.profile

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.developerstring.satride.core.designsystem.component.GlassPanel
import com.developerstring.satride.core.designsystem.component.NumericSize
import com.developerstring.satride.core.designsystem.component.NumericText
import com.developerstring.satride.core.designsystem.component.SatRideButton
import com.developerstring.satride.core.designsystem.component.SatRideButtonStyle
import com.developerstring.satride.core.designsystem.component.SatRideIcon
import com.developerstring.satride.core.designsystem.component.SatRideIconGraphic
import com.developerstring.satride.core.designsystem.component.ScreenReveal
import com.developerstring.satride.core.designsystem.component.SectionHeader
import com.developerstring.satride.core.designsystem.component.ShimmerBox
import com.developerstring.satride.core.designsystem.component.readableWidth
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.core.designsystem.theme.Spacing
import com.developerstring.satride.core.permissions.PermissionStatus
import com.developerstring.satride.core.permissions.SkyPermission
import com.developerstring.satride.core.permissions.rememberAppSettingsOpener
import com.developerstring.satride.core.permissions.rememberSkyPermissions
import com.developerstring.satride.core.web.LegalPage
import org.koin.compose.viewmodel.koinViewModel

/**
 * The profile tab: an offer when signed out, and the account when signed in.
 */
@Composable
fun ProfileScreen(
    onOpenSignIn: () -> Unit,
    onOpenEdit: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = koinViewModel(),
    onMessage: (String) -> Unit = {},
    /** Opens a legal page in the in-app web view. */
    onOpenPage: (LegalPage) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                ProfileEffect.OpenSignIn -> onOpenSignIn()
                ProfileEffect.OpenEdit -> onOpenEdit()
                is ProfileEffect.ShowMessage -> onMessage(effect.text)
            }
        }
    }

    // The shell arrives on the navigation frame; the panels compose on the next one. The skeleton
    // is the same [LoadingProfile] the pending-session state uses, so an arrival that is *also*
    // waiting on Firebase reads as one continuous wait rather than two stacked ones.
    ScreenReveal(
        modifier = modifier.fillMaxSize(),
        skeleton = {
            Column(
                modifier = Modifier.fillMaxSize().padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) { LoadingProfile() }
        },
    ) {
        // The tab bar floats over this screen rather than reserving space below it, so the last
        // rows — the legal links, and [Delete account] above them — sat underneath the pill. The
        // clearance is `navBarClearance` (the pill's own height) *plus the system navigation-bar
        // inset the pill floats above*: the fixed constant alone was right only on a device with
        // no gesture bar, and left the last row clipped on everything else. It is padding on the
        // scrolling content, so it is scrollable space rather than a dead gap.
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .readableWidth()
                .padding(
                    start = Spacing.lg,
                    end = Spacing.lg,
                    top = Spacing.lg,
                    bottom = Spacing.navBarClearance + bottomInset,
                ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            when {
                state.loading -> LoadingProfile()
                !state.isSignedIn -> SignedOut(onSignIn = { viewModel.onEvent(ProfileEvent.SignInRequested) })
                else -> SignedIn(state = state, onEvent = viewModel::onEvent)
            }

            // Outside the sign-in branch on purpose. Permissions are a property of the app on this
            // phone, not of an account — the sky view and the pass reminders work identically
            // signed out — and burying the only way to grant them behind a sign-in wall left a
            // user who declined during the first run with no way back at all.
            PermissionsPanel()

            LegalPanel(onOpenPage = onOpenPage)
        }
    }

    if (state.confirmingDelete) {
        AlertDialog(
            onDismissRequest = { viewModel.onEvent(ProfileEvent.DeleteDismissed) },
            title = { Text("Delete your account?") },
            text = {
                Text(
                    "Your profile is removed from SatRide's servers. Satellites, passes and reminders " +
                        "on this phone are untouched — none of that was ever tied to an account.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.onEvent(ProfileEvent.DeleteConfirmed) }) {
                    Text("Delete", color = SatRideTheme.accents.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.onEvent(ProfileEvent.DeleteDismissed) }) { Text("Cancel") }
            },
        )
    }
}

/**
 * The session is being restored.
 *
 * A shimmer rather than a spinner, and rather than the signed-out prompt: this lasts a fraction of
 * a second, and flashing "sign in" at somebody who is signed in reads as the app having lost their
 * account.
 */
@Composable
private fun LoadingProfile() {
    GlassPanel(Modifier.fillMaxWidth()) {
        ShimmerBox(Modifier.size(width = 160.dp, height = 24.dp))
        Box(Modifier.padding(top = Spacing.sm)) {
            ShimmerBox(Modifier.size(width = 220.dp, height = 16.dp))
        }
    }
}

@Composable
private fun SignedOut(onSignIn: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = Spacing.xxl), Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text("Not signed in", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "SatRide works fine without an account. Sign in to keep a profile and credit " +
                    "whoever got you into this.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            SatRideButton(text = "Sign in with Google", onClick = onSignIn)
        }
    }
}

@Composable
private fun SignedIn(state: ProfileUiState, onEvent: (ProfileEvent) -> Unit) {
    GlassPanel(Modifier.fillMaxWidth()) {
        Text(
            text = state.displayName ?: "Astronomer",
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        state.user?.email?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (state.profile == null) {
        // Signed in with no profile document — they skipped the form, and can still fill it in.
        GlassPanel(Modifier.fillMaxWidth()) {
            Text("No profile yet", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "Add a name so the rest of the app knows what to call you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(Modifier.padding(top = Spacing.sm)) {
                SatRideButton(text = "Create profile", onClick = { onEvent(ProfileEvent.EditRequested) })
            }
        }
    } else {
        SectionHeader(title = "Details")
        GlassPanel(Modifier.fillMaxWidth()) {
            // Country only. "Referred by" is recorded at sign-up and kept, but showing it back is
            // noise — it is information *about* the user that the user already knows, and "Nobody"
            // is a slightly bleak thing to read on your own profile.
            DetailRow("Country", state.country?.let { "${it.flag}  ${it.name}" } ?: "Not set")
        }
        SatRideButton(
            text = "Edit profile",
            onClick = { onEvent(ProfileEvent.EditRequested) },
            style = SatRideButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    LocationPanel(state = state, onEvent = onEvent)

    SectionHeader(title = "Account")
    SatRideButton(
        text = "Sign out",
        onClick = { onEvent(ProfileEvent.SignOutRequested) },
        style = SatRideButtonStyle.Secondary,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
    )
    SatRideButton(
        text = "Delete account",
        onClick = { onEvent(ProfileEvent.DeleteRequested) },
        style = SatRideButtonStyle.Secondary,
        accent = SatRideTheme.accents.danger,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Where the app thinks you are.
 *
 * Worth a place on this screen because it is the single number that silently decides whether the
 * Passes tab is right: every AOS, every elevation and every reminder is computed from it. A user
 * who suspects the passes are wrong currently has no way to check, and the first version of this
 * app shipped computing them from a **four-day-old** cached fix without saying so anywhere.
 *
 * Collapsed by default — it is a diagnostic, not something to read daily — and opening it asks for
 * a fresh fix rather than showing whatever was cached.
 */
@Composable
private fun LocationPanel(state: ProfileUiState, onEvent: (ProfileEvent) -> Unit) {
    SectionHeader(title = "Your location")

    GlassPanel(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onEvent(ProfileEvent.CoordinatesToggled) },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SatRideIconGraphic(
                icon = SatRideIcon.Location,
                size = 20.dp,
                tint = SatRideTheme.accents.tracking,
                modifier = Modifier.padding(end = Spacing.md),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (state.showCoordinates) "Coordinates" else "Show coordinates",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = state.locationSource,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Rotated rather than swapped for an "up" variant, so the two states are visibly the
            // same control in two positions instead of two different marks.
            val chevronTurn by animateFloatAsState(
                targetValue = if (state.showCoordinates) 180f else 0f,
                label = "coordinatesChevron",
            )
            SatRideIconGraphic(
                icon = SatRideIcon.Chevron,
                size = 20.dp,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(chevronTurn),
            )
        }

        if (state.showCoordinates) {
            Box(Modifier.padding(top = Spacing.md)) {
                val coordinates = state.coordinates
                if (coordinates == null) {
                    Text(
                        text = "No fix yet. Grant location, or step outside for a moment — " +
                            "passes are computed from wherever the app thinks you are.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // Monospaced, because coordinates are read digit by digit and compared against
                    // something else — a map, a second device, a memory of where you live.
                    NumericText(text = coordinates, size = NumericSize.Medium)
                }
            }
        }
    }
}

/**
 * The three permissions, always on this screen and always actionable.
 *
 * Each row says what the permission is for and offers the one move that is available from where
 * it currently stands: ask, if the platform will still show its dialog; open system settings, if
 * it will not; nothing at all if it is granted or the device cannot offer it. That last
 * distinction matters — a "Grant" button that the platform silently ignores, which is what a
 * second request for a denied permission is, is worse than no button.
 */
@Composable
private fun PermissionsPanel() {
    val permissions = rememberSkyPermissions()
    val openSettings = rememberAppSettingsOpener()

    // Permissions change while the app is in the background — that is the entire point of the
    // [Open settings] button below. Without this the row the user just fixed would still read
    // as denied when they came back.
    LifecycleResumeEffect(permissions) {
        permissions.refreshAll()
        onPauseOrDispose { }
    }

    SectionHeader(title = "Permissions")

    GlassPanel(Modifier.fillMaxWidth()) {
        permissions.entries.forEachIndexed { index, (permission, controller) ->
            if (index > 0) {
                Box(Modifier.fillMaxWidth().height(1.dp).padding(vertical = Spacing.xs))
            }
            PermissionRow(
                permission = permission,
                status = controller.status,
                onRequest = controller::request,
                onOpenSettings = openSettings,
            )
        }
    }
}

@Composable
private fun PermissionRow(
    permission: SkyPermission,
    status: PermissionStatus,
    onRequest: () -> Unit,
    onOpenSettings: (() -> Unit)?,
) {
    val accents = SatRideTheme.accents
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(permission.label, style = MaterialTheme.typography.titleSmall)
            Text(
                text = permission.why,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when {
            status.isGranted -> Text(
                text = "Allowed",
                style = MaterialTheme.typography.labelSmall,
                color = accents.success,
            )

            status == PermissionStatus.UNSUPPORTED -> Text(
                text = "Unavailable",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            status.canRequest -> TextButton(onClick = onRequest) { Text("Allow") }

            // Denied, or waiting on a dialog that is already open. Settings is the only route
            // left; where the platform has no such page there is nothing honest to offer.
            onOpenSettings != null -> TextButton(
                onClick = onOpenSettings,
                enabled = status != PermissionStatus.PENDING,
            ) { Text("Settings") }

            else -> Text(
                text = "Blocked",
                style = MaterialTheme.typography.labelSmall,
                color = accents.warning,
            )
        }
    }
}

/**
 * The legal documents, linked below permissions.
 *
 * Play requires the privacy policy to be reachable from inside the app, not only from the store
 * listing, and the profile tab is where a user goes looking for "the settings-ish stuff". All four
 * open in the in-app web view rather than a browser — see `InAppWebView`.
 */
@Composable
private fun LegalPanel(onOpenPage: (LegalPage) -> Unit) {
    SectionHeader(title = "Legal")
    GlassPanel(Modifier.fillMaxWidth()) {
        LegalPage.entries.forEachIndexed { index, page ->
            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).padding(vertical = Spacing.xxs))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenPage(page) }
                    .padding(vertical = Spacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(page.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                SatRideIconGraphic(
                    icon = SatRideIcon.Chevron,
                    size = 18.dp,
                    tint = SatRideTheme.accents.tracking,
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
