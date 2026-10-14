package com.developerstring.satride.core.sensors

import com.developerstring.satride.core.common.runCatchingCancellable
import com.developerstring.satride.core.model.GeoPos
import com.developerstring.satride.core.model.ObserverPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A fix as the platform reported it.
 *
 * [ageMillis] is how old the platform said the fix was when it handed it over — *not* how long ago
 * SatRide received it. It exists because a cached fix can be days stale: a Pixel 6a used indoors
 * reported a network fix from **four days earlier** as its most recent known location, and without
 * an age there is no way to tell that from a fix taken this second, no way to prefer the fresher of
 * two providers, and no way to tell the user their passes are computed for where they were on
 * Tuesday.
 */
public data class GeoFix(val position: GeoPos, val accuracyM: Double? = null, val ageMillis: Long = 0L) {
    /**
     * Old enough to be worth doubting.
     *
     * An hour is generous on purpose — for pass prediction a fix from an hour ago is genuinely as
     * good as one from this second, because the observer has not moved far enough to matter. Days
     * are a different question: that is a flight, not a walk.
     */
    public val isStale: Boolean get() = ageMillis > STALE_AFTER_MILLIS

    public companion object {
        public const val STALE_AFTER_MILLIS: Long = 60L * 60L * 1000L
    }
}

/**
 * Whether [candidate] is a better answer than [current].
 *
 * Standard "is this a better location" reasoning, and needed here because the app now listens to
 * GPS and the network provider at the same time: the two interleave, and without this a coarse
 * network fix arriving a second after a good GPS one would replace it.
 *
 * Fresher wins when the gap is large, because a stale precise fix is worse than a current coarse
 * one — the user may simply not be there any more. Otherwise accuracy decides.
 */
internal fun isBetterFix(candidate: GeoFix, current: GeoFix?): Boolean {
    if (current == null) return true

    val muchFresher = current.ageMillis - candidate.ageMillis > SIGNIFICANTLY_FRESHER_MILLIS
    if (muchFresher) return true

    val muchStaler = candidate.ageMillis - current.ageMillis > SIGNIFICANTLY_FRESHER_MILLIS
    if (muchStaler) return false

    val candidateAccuracy = candidate.accuracyM ?: Double.MAX_VALUE
    val currentAccuracy = current.accuracyM ?: Double.MAX_VALUE
    return candidateAccuracy <= currentAccuracy
}

/** Two minutes: long enough that ordinary provider jitter does not count as "fresher". */
private const val SIGNIFICANTLY_FRESHER_MILLIS = 2L * 60L * 1000L

/** Fast enough that granting permission feels immediate. */
private val INITIAL_RETRY: Duration = 1.seconds

/** Slow enough that a permanent refusal costs nothing measurable. */
private val MAX_RETRY: Duration = 30.seconds

/**
 * Platform access to the device's location.
 *
 * SatRide asks for **coarse** accuracy everywhere. A kilometre of observer error moves a satellite
 * at 800 km range by under a tenth of a degree — far below the compass error the AR view already
 * carries — so fine location would cost battery and user trust to buy precision that cannot be
 * seen. It also means the permission request is the less alarming one.
 */
public interface PlatformLocationSource {
    /** Fixes as they arrive. Cold: the platform starts listening on collection and stops after. */
    public fun fixes(): Flow<GeoFix>

    /** The most recent cached fix, if the platform has one. Usually instant, and usually enough. */
    public suspend fun lastKnown(): GeoFix?

    /** False when permission is missing or the platform has no location hardware at all. */
    public val isAvailable: Boolean
}

/**
 * Where the observer is.
 *
 * A manual position always wins. Someone who typed in a location meant it — they may be planning
 * tonight's session for a site they will drive to later, and quietly overriding that with the
 * device's own fix would make the setting look broken.
 */
public interface LocationProvider {
    public val position: StateFlow<ObserverPosition>

    /** Asks the platform for a fresh fix. Safe to call when unavailable; simply does nothing. */
    public suspend fun requestFix()

    /** Sets or clears the manual override. */
    public fun setManual(position: GeoPos?)
}

/**
 * Combines a manual override with the platform's fixes.
 *
 * The last device fix is remembered rather than dropped when the manual override is cleared, so
 * turning "use my location" back on does not leave the user staring at an empty sky waiting for
 * the GPS to reacquire.
 */
public class DefaultLocationProvider(private val source: PlatformLocationSource, scope: CoroutineScope) :
    LocationProvider {

    private val manual = MutableStateFlow<GeoPos?>(null)
    private val automatic = MutableStateFlow<GeoFix?>(null)

    override val position: StateFlow<ObserverPosition> =
        combine(manual, automatic) { manualPosition, fix ->
            when {
                manualPosition != null -> ObserverPosition.Manual(manualPosition)
                fix != null -> ObserverPosition.Automatic(fix.position, fix.accuracyM)
                else -> ObserverPosition.Unknown
            }
        }.stateIn(scope, SharingStarted.Eagerly, ObserverPosition.Unknown)

    init {
        scope.launch {
            // Retried, not checked once.
            //
            // `isAvailable` is false until location permission is granted, and the permission is
            // granted *after* launch — the sky view asks for it on arrival. Subscribing only if the
            // source happened to be available at construction meant a fresh install sat on "waiting
            // for a location fix" until the app was killed and reopened, which is the worst first
            // impression this app can make. The same loop covers permission being revoked and
            // re-granted from Settings mid-session.
            var backoff = INITIAL_RETRY
            while (true) {
                if (source.isAvailable) {
                    backoff = INITIAL_RETRY

                    // `runCatchingCancellable`, not `runCatching`: the plain one swallows
                    // `CancellationException` too, so cancelling the scope would leave this loop
                    // spinning instead of stopping.
                    runCatchingCancellable {
                        // The cached fix is a *seed*, not an answer: it can be days old (see
                        // [GeoFix]), so it fills the screen at once and is replaced by anything
                        // better.
                        source.lastKnown()?.let { if (isBetterFix(it, automatic.value)) automatic.value = it }
                        source.fixes().collect { fix ->
                            // Guarded, because the platform source listens to GPS *and* the network
                            // provider at once and they interleave. Taking whatever arrived last
                            // would let a coarse network fix overwrite a good GPS one a second
                            // after it landed.
                            if (isBetterFix(fix, automatic.value)) automatic.value = fix
                        }
                    }
                }

                // Backs off rather than polling at a fixed rate forever. Someone who declined
                // location permanently is the common case, and waking once a second for the life of
                // the process — to ask a question whose answer cannot change without a trip to
                // Settings — is a battery cost for nothing.
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_RETRY)
            }
        }
    }

    override suspend fun requestFix() {
        if (!source.isAvailable) return
        source.lastKnown()?.let { if (isBetterFix(it, automatic.value)) automatic.value = it }
    }

    override fun setManual(position: GeoPos?) {
        manual.value = position
    }
}
