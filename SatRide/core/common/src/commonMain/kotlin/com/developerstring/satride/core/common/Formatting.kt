package com.developerstring.satride.core.common

import kotlin.math.abs
import kotlin.math.round

/*
 * Number formatting shared by every surface that shows one.
 *
 * These live here rather than in a feature because a notification body, a catalog row and a pass
 * card all have to say "78°" and "437.800 MHz" the same way. Two copies of a rounding rule is how
 * the same satellite ends up quoted at two different frequencies on two screens.
 */

/**
 * Fixed-decimal formatting.
 *
 * Common Kotlin has no `String.format`, and rendering a Double directly gives
 * "34.499999999999996" on a row that ticks once a second.
 */
fun Double.format(decimals: Int): String {
    if (isNaN() || isInfinite()) return "—"
    var scale = 1L
    repeat(decimals) { scale *= 10L }
    val scaled = round(abs(this) * scale).toLong()
    val sign = if (this < 0.0 && scaled != 0L) "-" else ""
    val whole = scaled / scale
    if (decimals == 0) return "$sign$whole"
    return "$sign$whole.${(scaled % scale).toString().padStart(decimals, '0')}"
}

/**
 * A compass bearing as a point of the compass.
 *
 * "rises WSW" is something a person can act on standing in a field; "rises at 247°" is something
 * they have to translate first, in the dark, while the pass starts without them.
 */
fun Double.asCompassPoint(): String {
    val index = ((this % 360.0 + 360.0) % 360.0 / 22.5).toInt() % COMPASS_POINTS.size
    return COMPASS_POINTS[index]
}

/** Frequency in the units an operator reads: MHz to three decimals. */
fun Long.asMegahertz(): String = (this / 1_000_000.0).format(3)

private val COMPASS_POINTS =
    listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
