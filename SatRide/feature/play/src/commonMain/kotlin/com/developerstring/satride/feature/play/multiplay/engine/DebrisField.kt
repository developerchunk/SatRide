package com.developerstring.satride.feature.play.multiplay.engine

import com.developerstring.satride.core.model.SatClass
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The obstacles: dead satellites and spent stages strewn along the corridor, drifting slowly
 * against the direction of travel and tumbling as they go.
 *
 * Seeded from the room, like the track, so everyone dodges the same field. The real catalog is
 * *also* in the arena (see `GameSession`), but real satellites are far too sparse to make a
 * course — a few hundred objects over the whole planet leave a racer whole laps between
 * encounters — so the field supplies the density and the catalog supplies the celebrity cameos.
 *
 * Positions are recomputed each frame from the track parameter, which keeps every piece in the
 * corridor forever without any integration drift.
 */
class DebrisField(seed: Long, private val track: Track, val capacity: Int) {

    /** Track parameter (radians around the lap) of each piece; advances with [driftRate]. */
    private val t = DoubleArray(capacity)
    private val lateralRad = DoubleArray(capacity)
    private val altOffsetKm = DoubleArray(capacity)
    private val driftRate = DoubleArray(capacity)
    private val tumbleRate = DoubleArray(capacity)
    private val tumblePhase = DoubleArray(capacity)

    /** Per-piece model and collision size, km. */
    val classes: Array<SatClass> = Array(capacity) { SatClass.OTHER }
    val radiusKm: DoubleArray = DoubleArray(capacity)

    /** World positions, km, packed x,y,z — refreshed by [update]. */
    val world: DoubleArray = DoubleArray(capacity * 3)

    /** The nose direction of each piece for the renderer, packed, unit. */
    val facing: DoubleArray = DoubleArray(capacity * 3)

    /** Tumble angle, radians, per piece — purely visual. */
    val tumble: DoubleArray = DoubleArray(capacity)

    /** How many of the [capacity] pieces are live. Dodge ramps this up. */
    var activeCount: Int = capacity
        set(value) {
            field = value.coerceIn(0, capacity)
        }

    private val scratch = DoubleArray(3)
    private val lateral = DoubleArray(3)

    init {
        val rng = SeededRandom(seed xor 0x0DEB_0DEBL)
        for (i in 0 until capacity) {
            // Spread evenly with jitter, so no stretch of track is empty and no stretch is a wall.
            t[i] = (i + rng.nextDouble() * 0.8) / capacity * 2.0 * PI
            // Most pieces sit in the corridor; a few sit right on the racing line.
            val lane = rng.nextDouble()
            lateralRad[i] = when {
                lane < 0.35 -> (rng.nextDouble() - 0.5) * 0.004

                // ±13 km: on the line
                else -> (rng.nextDouble() - 0.5) * 0.030 // ±95 km: the corridor
            }
            altOffsetKm[i] = (rng.nextDouble() - 0.5) * 220.0
            // Slow retrograde drift so the field is never quite where it was last lap.
            driftRate[i] = -(0.0006 + rng.nextDouble() * 0.0014)
            tumbleRate[i] = (rng.nextDouble() - 0.5) * 1.6
            tumblePhase[i] = rng.nextDouble() * 2.0 * PI
            val roll = rng.nextDouble()
            classes[i] = when {
                roll < 0.30 -> SatClass.ROCKET_BODY
                roll < 0.52 -> SatClass.OTHER
                roll < 0.66 -> SatClass.CUBESAT
                roll < 0.78 -> SatClass.WEATHER
                roll < 0.88 -> SatClass.NAVIGATION
                roll < 0.95 -> SatClass.AMATEUR
                else -> SatClass.CREWED
            }
            radiusKm[i] = when (classes[i]) {
                SatClass.CREWED -> 17.0
                SatClass.ROCKET_BODY -> 13.0
                SatClass.NAVIGATION, SatClass.WEATHER -> 11.0
                SatClass.CUBESAT -> 6.5
                else -> 9.0
            }
        }
        update(0.0, 0.0)
    }

    /** Advances the drift and recomputes every live piece's world position. */
    fun update(dt: Double, timeS: Double) {
        for (i in 0 until activeCount) {
            t[i] += driftRate[i] * dt
            track.centreAt(t[i], scratch)
            track.lateralAt(t[i], lateral)
            val l = lateralRad[i]
            scratch[0] += lateral[0] * l
            scratch[1] += lateral[1] * l
            scratch[2] += lateral[2] * l
            SphereMath.normalize(scratch)
            val r = SphereMath.EARTH_RADIUS_KM + track.altitudeAt(t[i]) + altOffsetKm[i]
            world[i * 3] = scratch[0] * r
            world[i * 3 + 1] = scratch[1] * r
            world[i * 3 + 2] = scratch[2] * r
            tumble[i] = tumblePhase[i] + timeS * tumbleRate[i]
            // A slow flat spin about the local vertical: the nose sweeps from along-track to
            // cross-track and back, which is what a dead satellite drifting past looks like.
            val alongX = scratch[1] * lateral[2] - scratch[2] * lateral[1]
            val alongY = scratch[2] * lateral[0] - scratch[0] * lateral[2]
            val alongZ = scratch[0] * lateral[1] - scratch[1] * lateral[0]
            val ct = cos(tumble[i])
            val st = sin(tumble[i])
            facing[i * 3] = alongX * ct + lateral[0] * st
            facing[i * 3 + 1] = alongY * ct + lateral[1] * st
            facing[i * 3 + 2] = alongZ * ct + lateral[2] * st
        }
    }

    /** Track parameter of piece [i], for coarse culling by distance along the lap. */
    fun parameter(i: Int): Double = t[i]
}
