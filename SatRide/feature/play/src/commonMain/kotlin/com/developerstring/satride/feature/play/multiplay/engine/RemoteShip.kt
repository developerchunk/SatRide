package com.developerstring.satride.feature.play.multiplay.engine

import com.developerstring.satride.core.model.SatClass
import kotlin.math.exp

/**
 * Another player's craft as this phone sees it: the last thing the network said, extrapolated
 * forward and eased so it moves rather than teleports.
 *
 * Updates arrive a few times a second and hundreds of milliseconds late. Between them the ship is
 * dead-reckoned along its last heading at its last speed — on a sphere, which is a rotation, not
 * a line — and the *displayed* ship chases that prediction with a short time constant. A new
 * sample therefore corrects the prediction, and the correction is absorbed as a gentle nudge
 * instead of a jump.
 */
class RemoteShip(val uid: String) {
    var name: String = ""
    var colorArgb: Long = 0xFFFFFFFF
    var skin: SatClass = SatClass.OTHER
    val progress: Progress = Progress()

    /** Latest emote and when it was sent; the renderer shows it briefly above the craft. */
    var emote: String = ""
    var emoteAtMs: Long = 0L

    /** Last received sample. */
    private val samplePos = DoubleArray(3)
    private val sampleHdg = DoubleArray(3)
    private var sampleAlt = ShipPhysics.ALT_START_KM
    private var sampleSpeed = 0.0
    private var sampleAtMs = 0L
    private var hasSample = false

    /** The predicted (dead-reckoned) state. */
    private val predPos = DoubleArray(3)
    private val predHdg = DoubleArray(3)

    /** The displayed state. */
    val ship: ShipState = ShipState()

    var lastSeenMs: Long = 0L
        private set

    fun applySample(
        latDeg: Double,
        lonDeg: Double,
        altKm: Double,
        headingDeg: Double,
        speedKmS: Double,
        rollRad: Double,
        boosting: Boolean,
        sentAtMs: Long,
        receivedAtMs: Long,
    ) {
        SphereMath.fromLatLon(latDeg, lonDeg, samplePos)
        SphereMath.tangentFromHeading(samplePos, headingDeg, sampleHdg)
        sampleAlt = altKm
        sampleSpeed = speedKmS
        sampleAtMs = sentAtMs
        lastSeenMs = receivedAtMs
        ship.rollRad = rollRad
        ship.boostTimeS = if (boosting) 1.0 else 0.0
        ship.skin = skin
        if (!hasSample) {
            hasSample = true
            ship.placeAt(samplePos, sampleHdg, altKm)
            ship.speedKmS = speedKmS
        }
    }

    /** Advances the prediction to [nowMs] and eases the display toward it by [dt] seconds. */
    fun update(nowMs: Long, dt: Double) {
        if (!hasSample) return
        // Extrapolate from the sample by the age of the sample, capped: a player who stopped
        // sending should stop moving, not fly off around the planet.
        val ageS = ((nowMs - sampleAtMs) / 1000.0).coerceIn(0.0, MAX_EXTRAPOLATION_S)
        SphereMath.copy(samplePos, predPos)
        SphereMath.copy(sampleHdg, predHdg)
        val arc = sampleSpeed * ageS / (SphereMath.EARTH_RADIUS_KM + sampleAlt)
        if (arc > 0.0) SphereMath.step(predPos, predHdg, arc)

        // Ease the displayed pose toward the prediction.
        val k = 1.0 - exp(-dt / EASE_TAU_S)
        ship.pos[0] += (predPos[0] - ship.pos[0]) * k
        ship.pos[1] += (predPos[1] - ship.pos[1]) * k
        ship.pos[2] += (predPos[2] - ship.pos[2]) * k
        if (!SphereMath.normalize(ship.pos)) SphereMath.copy(predPos, ship.pos)
        ship.hdg[0] += (predHdg[0] - ship.hdg[0]) * k
        ship.hdg[1] += (predHdg[1] - ship.hdg[1]) * k
        ship.hdg[2] += (predHdg[2] - ship.hdg[2]) * k
        SphereMath.tangentialize(ship.hdg, ship.pos)
        if (!SphereMath.normalize(ship.hdg)) SphereMath.copy(predHdg, ship.hdg)
        SphereMath.copy(ship.hdg, ship.vel)
        ship.altKm += (sampleAlt - ship.altKm) * k
        ship.speedKmS += (sampleSpeed - ship.speedKmS) * k
        ship.flame += ((if (ship.boostTimeS > 0) 1.0 else 0.5) - ship.flame) * k
        SphereMath.copy(ship.world, ship.worldPrev)
        ship.refreshWorld()
    }

    /** True when nothing has arrived for a while — the craft is drawn faded. */
    fun isSilent(nowMs: Long): Boolean = hasSample && nowMs - lastSeenMs > SILENT_AFTER_MS

    companion object {
        const val MAX_EXTRAPOLATION_S: Double = 2.5
        private const val EASE_TAU_S = 0.28
        const val SILENT_AFTER_MS: Long = 6_000L
    }
}
