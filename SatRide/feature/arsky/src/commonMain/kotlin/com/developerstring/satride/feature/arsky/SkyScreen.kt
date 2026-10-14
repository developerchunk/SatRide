package com.developerstring.satride.feature.arsky

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.core.designsystem.theme.Spacing
import com.developerstring.satride.core.model.Topocentric
import com.developerstring.satride.core.orbit.math.Quaternion
import com.developerstring.satride.core.permissions.rememberCameraPermission
import com.developerstring.satride.core.permissions.rememberLocationPermission
import com.developerstring.satride.core.sensors.AttitudeFrames
import com.developerstring.satride.feature.arsky.camera.CameraLayer
import com.developerstring.satride.feature.arsky.hud.CalibrationCard
import com.developerstring.satride.feature.arsky.hud.GuideBanner
import com.developerstring.satride.feature.arsky.hud.SkyHud
import com.developerstring.satride.feature.arsky.hud.SkyPrompt
import com.developerstring.satride.feature.arsky.hud.TimeTravelBar
import com.developerstring.satride.feature.arsky.planetarium.VirtualCamera
import com.developerstring.satride.feature.arsky.projection.CameraFov
import com.developerstring.satride.feature.arsky.render.SkyPalette
import com.developerstring.satride.feature.arsky.render.SkyRenderState
import com.developerstring.satride.feature.arsky.render.SkySurface
import org.koin.compose.viewmodel.koinViewModel

/**
 * The hero screen: satellites drawn where they really are.
 *
 * ## The layer cake
 *
 * Camera preview (a native view) → sky layer (one Compose `Canvas`) → HUD (ordinary composables).
 * Only the middle layer runs at frame rate, and it is the only one that knows about projection.
 * Planetarium mode omits the bottom layer and swaps where the attitude comes from, which is why
 * it is a genuine second mode rather than an error screen.
 *
 * @param satelliteDetail the P4 detail content, supplied by the app shell. Features may not depend
 *   on features — `checkModuleDependencyRules` enforces it — so the sheet arrives as a slot rather
 *   than as a direct call into `feature:satellites`.
 */
@Composable
fun SkyScreen(
    onOpenSearch: () -> Unit,
    satelliteDetail: @Composable (noradId: Int, onGuide: () -> Unit, onDismiss: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: SkyViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The view model outlives this composition — tab state is restored, which is what makes
    // returning to the sky instant — so its periodic trail rebuild has to be told when nobody is
    // looking. Otherwise it goes on propagating the catalog behind every other tab.
    DisposableEffect(viewModel) {
        viewModel.onVisibilityChanged(true)
        onDispose { viewModel.onVisibilityChanged(false) }
    }

    val cameraPermission = rememberCameraPermission()
    val locationPermission = rememberLocationPermission()

    LaunchedEffect(cameraPermission.status) { viewModel.onCameraPermission(cameraPermission.status) }
    LaunchedEffect(locationPermission.status) { viewModel.onLocationPermission(locationPermission.status) }

    // Location is asked for on arrival without preamble: the screen is blank and inexplicable
    // without it, so nothing shown first would make a better case than the screen itself. The
    // camera is *not* asked for here — see SkyPrompt, which offers it once there is a sky to
    // put behind.
    LaunchedEffect(Unit) { if (locationPermission.status.canRequest) locationPermission.request() }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                SkyEffect.OpenSearch -> onOpenSearch()

                // Previously discarded, which made the AR toggle a dead control: tapping it
                // without camera permission changed nothing and said nothing.
                is SkyEffect.ShowMessage -> snackbarHostState.showSnackbar(effect.text)

                // The camera toggle was switched on before the app had permission. The launcher
                // is a UI object, so the view model can only ask; this is where it happens.
                SkyEffect.RequestCameraPermission -> cameraPermission.request()
            }
        }
    }

    val renderState = remember { SkyRenderState() }
    val virtualCamera = remember { VirtualCamera() }

    // Read only inside the draw phase — see SkySurface. Held as state and passed by value, these
    // would recompose the whole screen at sensor rate.
    var sensorAttitude by remember { mutableStateOf(Quaternion.IDENTITY) }
    var cameraFov by remember { mutableStateOf(CameraFov.PLANETARIUM_DEFAULT) }
    var viewportWidth by remember { mutableStateOf(0f) }
    var viewportHeight by remember { mutableStateOf(0f) }

    LaunchedEffect(viewModel) { viewModel.attitude.collect { sensorAttitude = it } }

    val augmented = state.mode == SkyMode.AUGMENTED
    val palette = skyPalette()

    // 600 ms, per the phase file: long enough to read as the arc being drawn in, short enough not
    // to be in the way of a second tap.
    val selectionProgress by animateFloatAsState(
        targetValue = if (state.selectedId != null) 1f else 0f,
        animationSpec = tween(durationMillis = 600),
        label = "selectionArc",
    )

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // The feed, not the mode: the augmented sky is drawn over whatever is behind it, and with
        // the camera off that is simply the dark background. `state.cameraOn` already folds in the
        // mode, the user's switch and the permission.
        if (state.cameraOn) {
            CameraLayer(
                modifier = Modifier.fillMaxSize(),
                onFov = { cameraFov = it },
                onUnavailable = viewModel::onCameraUnavailable,
            )
        }

        SkySurface(
            satellites = state.satellites,
            positions = viewModel.positions,
            attitude = { if (augmented) sensorAttitude else virtualCamera.attitude() },
            fov = { if (augmented) cameraFov else virtualCamera.fov(viewportWidth, viewportHeight) },
            palette = palette,
            renderState = renderState,
            selectedId = state.selectedId,
            guideTargetId = state.guideTargetId,
            selectionProgress = selectionProgress,
            onTapSatellite = { viewModel.onEvent(SkyEvent.SatelliteTapped(it)) },
            onTapEmpty = { viewModel.onEvent(SkyEvent.SelectionDismissed) },
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged {
                    viewportWidth = it.width.toFloat()
                    viewportHeight = it.height.toFloat()
                }
                .then(
                    if (augmented) {
                        Modifier
                    } else {
                        Modifier.planetariumGestures(virtualCamera) { viewportWidth to viewportHeight }
                    },
                ),
        )

        SkyHud(
            state = state,
            onEvent = viewModel::onEvent,
            modifier = Modifier.fillMaxWidth().safeDrawingPadding().padding(Spacing.lg),
        )

        // `navBarClearance` on the bottom, because the tab bar floats over this screen rather than
        // reserving space below it: without it the camera prompt — the one control that unlocks
        // the AR view — is drawn underneath the pill and cannot be tapped.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(
                    start = Spacing.lg,
                    end = Spacing.lg,
                    top = Spacing.lg,
                    bottom = Spacing.navBarClearance,
                ),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            AnimatedVisibility(state.guideTarget != null, enter = fadeIn(), exit = fadeOut()) {
                state.guideTarget?.let { target ->
                    GuideBanner(target = target, onClear = { viewModel.onEvent(SkyEvent.GuideCleared) })
                }
            }
            SkyPrompt(state = state, onRequestCamera = cameraPermission::request)
            if (state.timeTravel.enabled) {
                TimeTravelBar(state = state.timeTravel, onEvent = viewModel::onEvent)
            }
        }

        if (state.calibrationCardOpen) {
            CalibrationCard(
                accuracy = state.accuracy,
                onDismiss = { viewModel.onEvent(SkyEvent.CalibrationDismissed) },
                modifier = Modifier.align(Alignment.Center).padding(Spacing.xl),
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(
                    start = Spacing.lg,
                    end = Spacing.lg,
                    top = Spacing.lg,
                    bottom = Spacing.navBarClearance,
                ),
        )

        state.selectedId?.let { selected ->
            satelliteDetail(
                selected,
                { viewModel.onEvent(SkyEvent.GuideRequested(selected)) },
                { viewModel.onEvent(SkyEvent.SelectionDismissed) },
            )
        }
    }

    GuideLoop(guideTargetId = state.guideTargetId, viewModel = viewModel) { sensorAttitude }
}

/**
 * Drives the haptic guide.
 *
 * Its own loop rather than part of the render pass, because it runs only while a target is set.
 * The common case is no target at all, and a screen that is not guiding anyone should not be
 * computing pointing errors thirty times a second.
 */
@Composable
private fun GuideLoop(guideTargetId: Int?, viewModel: SkyViewModel, attitude: () -> Quaternion) {
    if (guideTargetId == null) return
    val angles = remember { DoubleArray(2) }

    LaunchedEffect(guideTargetId) {
        while (true) {
            val nowNanos = withFrameNanos { it }
            viewModel.positions.beginFrame()
            if (!viewModel.positions.readInto(guideTargetId, angles)) continue

            val error = AttitudeFrames.angularSeparationDeg(
                attitude(),
                Topocentric(
                    azimuthDeg = angles[0],
                    elevationDeg = angles[1],
                    rangeKm = 0.0,
                    rangeRateKmS = 0.0,
                ),
            )
            viewModel.onGuideFrame(error, nowNanos)
        }
    }
}

/** Drag to look around, pinch to zoom — planetarium mode's replacement for moving the phone. */
private fun Modifier.planetariumGestures(camera: VirtualCamera, viewport: () -> Pair<Float, Float>): Modifier =
    pointerInput(camera) {
        detectTransformGestures { _, pan, zoom, _ ->
            val (width, height) = viewport()
            camera.stop()
            if (zoom != 1f) camera.zoom(zoom)
            camera.drag(pan.x, pan.y, width, height)
        }
    }

@Composable
private fun skyPalette(): SkyPalette {
    val accents = SatRideTheme.accents
    return SkyPalette(
        tracking = accents.tracking,
        radio = accents.radio,
        guide = accents.success,
        horizon = accents.tracking.copy(alpha = 0.35f),
        trail = accents.trackingDim,
        selectedTrail = accents.tracking,
        selection = accents.tracking.copy(alpha = 0.6f),
        // White rather than an accent: labels sit over a live camera image of unknown brightness,
        // and the accent colours were chosen against the app's dark surfaces, not against a
        // sunlit rooftop.
        labelStyle = MaterialTheme.typography.labelSmall.copy(
            color = Color.White,
            fontWeight = FontWeight.Medium,
        ),
    )
}
