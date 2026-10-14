package com.developerstring.satride.core.sensors

import com.developerstring.satride.core.model.GeoPos
import com.developerstring.satride.core.orbit.math.Quaternion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlin.math.abs
import kotlin.time.Clock

/** A reading straight from the platform, before smoothing or any north correction. */
public data class RawAttitudeSample(
    public val rotation: Quaternion,
    public val elapsedNanos: Long,
    public val accuracy: CompassAccuracy,
)

/**
 * Platform orientation sensing.
 *
 * Implementations convert into the ENU device→world convention described in [AttitudeFrames] and
 * declare which north they measured against; everything else is shared.
 */
public interface PlatformAttitudeSource {
    /** Cold flow: the platform registers its listener on collection and unregisters after. */
    public fun samples(): Flow<RawAttitudeSample>

    public val northReference: NorthReference

    public val isAvailable: Boolean
}

/**
 * The attitude the rest of the app consumes: true north, ENU, smoothed.
 *
 * Both corrections live here rather than in the platform actuals so there is one implementation
 * of each to get right, and so the smoothing can be tested on every target without a device.
 */
public class DefaultAttitudeProvider(
    private val source: PlatformAttitudeSource,
    private val locationProvider: LocationProvider,
    private val clock: Clock,
    scope: CoroutineScope,
    private val smoothingConfig: AttitudeSmoothingConfig = AttitudeSmoothingConfig(),
) : AttitudeProvider {

    private val _accuracy = MutableStateFlow(CompassAccuracy.UNCALIBRATED)
    override val accuracy: StateFlow<CompassAccuracy> = _accuracy.asStateFlow()

    override val isAvailable: Boolean get() = source.isAvailable

    private var declination = DeclinationCache()

    /**
     * Shared, not cold-per-collector: the sky view, the HUD and the debug overlay all want the
     * same stream, and registering three sensor listeners for one physical sensor is a waste of
     * battery for identical numbers. `WhileSubscribed` still stops the sensor when the last
     * collector goes away.
     */
    private val corrected: Flow<AttitudeSample> = source.samples()
        .onEach { _accuracy.value = it.accuracy }
        .map { sample -> AttitudeSample(toTrueNorth(sample.rotation), sample.elapsedNanos) }
        .shareIn(scope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), replay = 1)

    override val rawAttitude: Flow<AttitudeSample> = corrected

    override val attitude: Flow<AttitudeSample> = flow {
        // One smoother per collector, created on collection: filter state is per-stream, and
        // sharing it would let a debug overlay opening and closing perturb the sky view.
        val smoother = AttitudeSmoother(smoothingConfig)
        corrected.collect { sample ->
            emit(AttitudeSample(smoother.update(sample), sample.elapsedNanos))
        }
    }

    private fun toTrueNorth(rotation: Quaternion): Quaternion = when (source.northReference) {
        // Already true north — CoreMotion does this itself. Applying declination here would
        // double the error rather than remove it.
        NorthReference.TRUE -> rotation

        NorthReference.MAGNETIC -> {
            val position = locationProvider.position.value.geoPos
            if (position == null) {
                // No position, so no declination is knowable. Magnetic north is a better
                // answer than refusing to draw anything: it is within a few degrees of true
                // north across most of the populated world, and the alternative is a blank
                // sky.
                rotation
            } else {
                AttitudeFrames.toTrueNorth(rotation, declination.forPosition(position, clock))
            }
        }
    }
}

/**
 * Caches the magnetic declination for the current location.
 *
 * The field model is a spherical-harmonic sum; running it per sensor sample at game rate is
 * pointless work for a value that changes by a degree over hundreds of kilometres. Recomputed
 * only when the observer has moved far enough to matter.
 */
private class DeclinationCache {
    private var cachedPosition: GeoPos? = null
    private var cachedValue: Double = 0.0

    fun forPosition(position: GeoPos, clock: Clock): Double {
        val previous = cachedPosition
        if (previous != null && isNear(previous, position)) return cachedValue

        val value = com.developerstring.satride.core.orbit.geomag.Wmm.declinationDeg(position, clock.now())
        cachedPosition = position
        cachedValue = value
        return value
    }

    /** Roughly 25 km, over which declination changes by well under a tenth of a degree. */
    private fun isNear(a: GeoPos, b: GeoPos): Boolean =
        abs(a.latDeg - b.latDeg) < 0.25 && abs(a.lonDeg - b.lonDeg) < 0.25
}
