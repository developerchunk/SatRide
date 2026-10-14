package com.developerstring.satride.feature.play.engine

import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The Play screen's simulated time: real time, optionally running faster.
 *
 * Speeding time up is what turns a globe into a game — at 60× a low orbit visibly sweeps
 * around the planet, and at 600× the day/night terminator crawls across continents. The clock
 * is continuous across speed changes: [setSpeed] re-anchors at the current simulated instant,
 * so satellites never jump when the user changes gear.
 */
class GameClock(private val clock: Clock) {

    var speed: Double = 1.0
        private set

    private var wallAnchor: Instant = clock.now()
    private var simAnchor: Instant = wallAnchor

    /** The current simulated instant. */
    fun now(): Instant = simAnchor + (clock.now() - wallAnchor) * speed

    fun setSpeed(newSpeed: Double) {
        if (newSpeed == speed) return
        val current = now()
        simAnchor = current
        wallAnchor = clock.now()
        speed = newSpeed
    }

    /** Snaps simulated time back to the real present. */
    fun resync() {
        wallAnchor = clock.now()
        simAnchor = wallAnchor
    }
}
