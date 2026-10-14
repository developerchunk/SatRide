package com.developerstring.satride.core.orbit.doppler

import com.developerstring.satride.core.orbit.Constants

/**
 * A tuned frequency and how fast it is moving.
 *
 * [driftHzPerS] exists so the radio can ramp its oscillator smoothly between the one-second
 * updates the tracking engine produces. Stepping the frequency once a second instead is
 * audible as a tick in the recovered audio and shows up as banding in a decoded image.
 */
public data class DopplerState(
    /** Frequency the receiver should tune to, hertz. */
    public val correctedHz: Double,
    /** Rate of change of [correctedHz], hertz per second. */
    public val driftHzPerS: Double,
    /** Shift away from the nominal transmit frequency, hertz. */
    public val shiftHz: Double,
)

/**
 * Doppler shift for a satellite downlink.
 *
 * The classical (non-relativistic) formula is used deliberately. At 7.7 km/s the relativistic
 * correction is about 3 parts in 10^10 — under a hertz at UHF — which is far below the
 * frequency error of the $30 receiver this app is built around.
 */
public object Doppler {

    /**
     * Received frequency for a transmitter at [transmitHz], given a line-of-sight
     * [rangeRateKmS].
     *
     * Range rate is negative while the satellite approaches, which raises the received
     * frequency, and positive as it recedes, which lowers it.
     */
    public fun receivedHz(transmitHz: Long, rangeRateKmS: Double): Double =
        transmitHz * (1.0 - rangeRateKmS / Constants.SPEED_OF_LIGHT_KM_S)

    /** The shift alone, in hertz. */
    public fun shiftHz(transmitHz: Long, rangeRateKmS: Double): Double =
        -transmitHz * rangeRateKmS / Constants.SPEED_OF_LIGHT_KM_S

    /**
     * Full correction state, including drift.
     *
     * [rangeRateKmS] and [rangeRateOneSecondLaterKmS] are both analytic values from the orbit
     * model one second apart, so the drift is a difference of exact quantities rather than a
     * numerical derivative of a noisy signal.
     *
     * Set [invert] for an inverting transponder, where the downlink moves opposite to the
     * uplink and the correction's sign flips with it.
     */
    public fun state(
        transmitHz: Long,
        rangeRateKmS: Double,
        rangeRateOneSecondLaterKmS: Double,
        invert: Boolean = false,
    ): DopplerState {
        val sign = if (invert) -1.0 else 1.0
        val shift = sign * shiftHz(transmitHz, rangeRateKmS)
        val nextShift = sign * shiftHz(transmitHz, rangeRateOneSecondLaterKmS)
        return DopplerState(
            correctedHz = transmitHz + shift,
            driftHzPerS = nextShift - shift,
            shiftHz = shift,
        )
    }

    /**
     * Maximum shift a satellite can produce at [transmitHz], hertz.
     *
     * Used to size the capture bandwidth so the whole pass fits inside the sampled band and
     * the receiver never has to retune its hardware mid-pass.
     */
    public fun maxShiftHz(transmitHz: Long, maxRangeRateKmS: Double): Double =
        transmitHz * maxRangeRateKmS / Constants.SPEED_OF_LIGHT_KM_S
}
