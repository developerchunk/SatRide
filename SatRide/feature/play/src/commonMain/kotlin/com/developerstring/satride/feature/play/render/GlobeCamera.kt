package com.developerstring.satride.feature.play.render

import kotlin.math.abs
import kotlin.math.exp

/**
 * The touch-driven camera for the globe, in both of its modes.
 *
 * In **orbit mode** yaw/pitch are the camera's position on a sphere around the Earth's centre
 * and [distanceKm] is how far out it sits. In **ride mode** the same yaw/pitch become an offset
 * around the followed satellite at [rideDistanceKm], so the one gesture vocabulary drives both.
 *
 * The fling model — exponential decay integrated exactly over the frame step — is the same one
 * `VirtualCamera` uses in the AR view, for the same reason: it is framerate-independent and it
 * feels like a physical object, where a linear stop reads as wrong even to people who could not
 * say why.
 */
class GlobeCamera {

    var yawDeg: Double = -30.0
        private set

    var pitchDeg: Double = 18.0
        private set

    // The landing distance: far enough back that the whole Earth sits comfortably inside the frame
    // with breathing room (at a 45° vertical FOV, ~5 Earth radii from the centre frames the disc
    // with margin), rather than cropping the limbs. Pinch still closes in to MIN_ORBIT_KM.
    var distanceKm: Double = EARTH_RADIUS_KM * 5.0
        private set

    var rideDistanceKm: Double = RIDE_REST_DISTANCE_KM
        private set

    /** Vertical field of view. Fixed: zoom is moving the camera, not narrowing the lens. */
    val fovDeg: Double = 45.0

    private var yawVelocity = 0.0
    private var pitchVelocity = 0.0

    /** The orbit pitch parked while a ride is running, and restored when it ends. */
    private var orbitPitchDeg: Double? = null

    // Pinch writes targets; settle() eases the real distances toward them, so zoom glides
    // instead of tracking the fingers step-for-step.
    private var distanceTargetKm: Double = distanceKm
    private var rideDistanceTargetKm: Double = rideDistanceKm

    /**
     * Drags the view so the globe follows the finger.
     *
     * Rotation per pixel shrinks as the camera closes in — up close the same drag should sweep
     * a city, not a hemisphere. The proximity term does exactly that, and bottoms out so the
     * camera never becomes unresponsive at minimum altitude.
     */
    fun drag(dxPx: Float, dyPx: Float, widthPx: Float, riding: Boolean) {
        if (widthPx <= 0f) return
        val k = degPerPixel(widthPx, riding)
        yawDeg = normalize(yawDeg - dxPx * k)
        pitchDeg = (pitchDeg + dyPx * k).coerceIn(MIN_PITCH, MAX_PITCH)
    }

    fun fling(velocityXPxS: Float, velocityYPxS: Float, widthPx: Float, riding: Boolean) {
        if (widthPx <= 0f) return
        val k = degPerPixel(widthPx, riding)
        yawVelocity = -velocityXPxS * k
        pitchVelocity = velocityYPxS * k
    }

    /** Advances fling inertia and zoom easing by [deltaSeconds]; returns true while moving. */
    fun settle(deltaSeconds: Float): Boolean {
        // Critically-damped-feeling zoom glide, framerate-independent.
        val zoomEase = 1.0 - exp((-ZOOM_EASE_PER_SECOND * deltaSeconds).toDouble())
        distanceKm += (distanceTargetKm - distanceKm) * zoomEase
        rideDistanceKm += (rideDistanceTargetKm - rideDistanceKm) * zoomEase

        if (!isMoving) return false
        val decay = exp((-DECAY_PER_SECOND * deltaSeconds).toDouble())
        // Exact integral of exponentially decaying velocity — framerate-independent travel.
        val travel = (1.0 - decay) / DECAY_PER_SECOND
        yawDeg = normalize(yawDeg + yawVelocity * travel)
        pitchDeg = (pitchDeg + pitchVelocity * travel).coerceIn(MIN_PITCH, MAX_PITCH)
        yawVelocity *= decay
        pitchVelocity *= decay
        if (pitchDeg <= MIN_PITCH || pitchDeg >= MAX_PITCH) pitchVelocity = 0.0
        if (!isMoving) {
            yawVelocity = 0.0
            pitchVelocity = 0.0
            return false
        }
        return true
    }

    val isMoving: Boolean
        get() = abs(yawVelocity) > STOP_THRESHOLD_DEG_S || abs(pitchVelocity) > STOP_THRESHOLD_DEG_S

    /** Pinch: above 1 zooms in, which moves the camera closer rather than narrowing the lens. */
    fun zoom(scaleFactor: Float, riding: Boolean) {
        if (scaleFactor <= 0f) return
        if (riding) {
            rideDistanceTargetKm = (rideDistanceTargetKm / scaleFactor).coerceIn(MIN_RIDE_KM, MAX_RIDE_KM)
        } else {
            distanceTargetKm = (distanceTargetKm / scaleFactor).coerceIn(MIN_ORBIT_KM, MAX_ORBIT_KM)
        }
    }

    fun stopFling() {
        yawVelocity = 0.0
        pitchVelocity = 0.0
    }

    /**
     * Switches the shared pitch between the two modes' resting angles.
     *
     * Yaw and pitch are deliberately one gesture vocabulary across orbit and ride — see the class
     * comment — but the two modes want to *open* pointed in very different directions. From orbit
     * you are looking at a planet from outside, and a near-level gaze frames it. Riding, a level
     * gaze puts the horizon across the middle and fills most of the screen with empty space, when
     * the entire point of the ride is the ground going past underneath.
     *
     * So the angle is swapped on entry and restored on exit, rather than biased permanently. A
     * bias would quietly eat the drag range at one end: the renderer clamps the gaze to 84° down,
     * so every degree of downward bias is a degree the user can no longer look *up* to find the
     * horizon again. Swapping keeps the full range available in both modes.
     *
     * The stored orbit pitch is the one the user last chose, not a constant, so exiting a ride
     * returns the globe exactly as they left it.
     */
    fun setRiding(riding: Boolean) {
        if (riding) {
            if (orbitPitchDeg == null) {
                orbitPitchDeg = pitchDeg
                pitchDeg = RIDE_REST_PITCH
                stopFling()
            }
        } else {
            orbitPitchDeg?.let { restored ->
                pitchDeg = restored
                orbitPitchDeg = null
                stopFling()
            }
        }
    }

    /** Points the orbit camera somewhere specific — used to open facing the sunlit side. */
    fun orientTo(yawDegrees: Double, pitchDegrees: Double) {
        stopFling()
        yawDeg = normalize(yawDegrees)
        pitchDeg = pitchDegrees.coerceIn(MIN_PITCH, MAX_PITCH)
    }

    private fun degPerPixel(widthPx: Float, riding: Boolean): Double {
        if (riding) return fovDeg / widthPx * 2.4
        val proximity = ((distanceKm - EARTH_RADIUS_KM) / (2.0 * EARTH_RADIUS_KM)).coerceIn(0.10, 1.2)
        return fovDeg / widthPx * 3.0 * proximity
    }

    private fun normalize(value: Double): Double = (value % 360.0 + 360.0) % 360.0

    companion object {
        /**
         * Where the gaze rests when a ride starts: down at the ground, with the horizon just in
         * frame at the top.
         *
         * The number is geometry, not taste. From altitude `h` the horizon sits
         * `90° - asin(R / (R + h))` below local horizontal — about 20° from 400 km, 24° from
         * 600 km, 28° from 830 km. The vertical field of view is 45°, so the gaze reaches 22.5°
         * above its own centre. Resting at 45° therefore puts the horizon within a few degrees of
         * the top edge across the whole of low Earth orbit, and everything below it is ground.
         *
         * Straight down was tried and is worse: with no horizon at all the motion stops reading as
         * flight and becomes a texture scrolling past, because there is nothing in frame to travel
         * *towards*. The sliver of limb and atmosphere is what sells it.
         */
        const val RIDE_REST_PITCH: Double = 45.0

        /** Just short of the poles, where the up-vector degenerates and the view would spin. */
        const val MAX_PITCH: Double = 85.0
        const val MIN_PITCH: Double = -85.0

        const val MIN_ORBIT_KM: Double = EARTH_RADIUS_KM * 1.15

        /**
         * Far enough to hold the whole constellation, not just the low stuff.
         *
         * Was 9 Earth radii — about 57,000 km — which is *less* than the diameter of the
         * geostationary ring. Anything above low orbit therefore could not be framed at all: GPS
         * sits at 26,560 km from the centre and the GEO belt at 42,164 km, so both spilled off
         * screen with no way to pull back.
         *
         * With a 45° vertical field of view, framing a radius `r` needs a camera distance of
         * `r / tan(22.5°)` ≈ `2.4 r`. For the GEO ring that is about 102,000 km, so 30 Earth radii
         * (191,000 km) frames it with room to spare and leaves headroom for the high Molniya
         * apogees — while still being a small fraction of the way to the Moon, which is the point
         * at which the Earth stops being worth looking at.
         */
        const val MAX_ORBIT_KM: Double = EARTH_RADIUS_KM * 30.0

        /**
         * How far behind the satellite the ride camera rests.
         *
         * Sized so the orbit track is legible, not so the craft is. Close in, the dashed path runs
         * out of frame within a few pixels of the model and the ride is just a satellite over
         * scenery; from here the line sweeps away toward the horizon and the view finally says
         * where this thing is *going*. Pinch still closes all the way to [MIN_RIDE_KM] for anyone
         * who wants to look at the craft itself.
         */
        const val RIDE_REST_DISTANCE_KM: Double = 600.0

        /** Pinch bounds for the ride camera: nose-to-the-glass, out to most of a hemisphere. */
        const val MIN_RIDE_KM: Double = 15.0
        const val MAX_RIDE_KM: Double = 4000.0

        private const val DECAY_PER_SECOND = 4.0f
        private const val ZOOM_EASE_PER_SECOND = 9.0
        private const val STOP_THRESHOLD_DEG_S = 0.5
    }
}
