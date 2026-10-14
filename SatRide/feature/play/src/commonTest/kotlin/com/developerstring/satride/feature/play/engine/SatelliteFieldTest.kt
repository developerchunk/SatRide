package com.developerstring.satride.feature.play.engine

import com.developerstring.satride.core.model.SatClass
import com.developerstring.satride.core.model.Satellite
import com.developerstring.satride.core.model.Tle
import com.developerstring.satride.core.orbit.sgp4.TleFormatter
import com.developerstring.satride.core.orbit.sgp4.TleParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The globe's propagator pool.
 *
 * These are about the rotation, which is what lets the globe carry the whole tracked catalog: a
 * tick evaluates a slice, and everything outside it has to keep moving from its own last two fixes
 * rather than freezing or jumping.
 */
class SatelliteFieldTest {

    private val start = Instant.parse("2026-08-02T12:00:00Z")

    /**
     * Distinct orbits so no two entries share a propagator's cached state — a field of clones
     * would hide any indexing mistake, because every slot would agree anyway.
     */
    private fun field(size: Int): SatelliteField {
        val base = TleParser.parse(ISS)
        val satellites = ArrayList<Satellite>(size)
        val tles = HashMap<Int, Tle>(size)
        for (i in 0 until size) {
            val noradId = 90_000 + i
            satellites += Satellite(noradId = noradId, name = "SAT $noradId", classType = SatClass.OTHER)
            tles[noradId] = TleFormatter.format(
                base.copy(
                    noradId = noradId,
                    raanRad = (base.raanRad + i * 0.31) % (2 * kotlin.math.PI),
                    meanAnomalyRad = (base.meanAnomalyRad + i * 0.17) % (2 * kotlin.math.PI),
                ),
            )
        }
        return SatelliteField().apply { load(satellites, tles) }
    }

    private fun SatelliteField.positionOf(noradId: Int, at: Instant): DoubleArray? {
        val out = DoubleArray(3)
        val index = indexOf(noradId)
        if (index < 0) return null
        return if (temePositionInto(index, at.toEpochMilliseconds().toDouble(), out)) out else null
    }

    @Test
    fun theFirstTickGivesEverySatelliteAPosition() {
        val field = field(64)

        field.tick(start)

        for (i in 0 until field.count) {
            val out = DoubleArray(3)
            assertTrue(
                field.temePositionInto(i, start.toEpochMilliseconds().toDouble(), out),
                "slot $i had no position after the first fill",
            )
        }
    }

    /**
     * The point of the whole change: a tick may cover only part of the field.
     *
     * Everything outside the slice keeps the fixes it had, so it is still interpolatable — the
     * failure this guards against is a rotation that blanks whatever it did not touch, which would
     * make the globe flicker in bands.
     */
    @Test
    fun aSlicedTickLeavesTheRestOfTheFieldUsable() {
        val field = field(100)
        field.tick(start)

        field.tick(start + 1.seconds, sliceSize = 10)

        val at = (start + 1.seconds).toEpochMilliseconds().toDouble()
        val out = DoubleArray(3)
        for (i in 0 until field.count) {
            assertTrue(field.temePositionInto(i, at, out), "slot $i lost its position to the rotation")
        }
    }

    /**
     * A satellite the rotation has not reached yet still moves.
     *
     * Interpolation spans that satellite's *own* last two fixes and runs a full span past the
     * newest, so a slot refreshed every twenty seconds is drawn continuously rather than holding
     * still and then jumping — which is exactly what a field-wide clock would have produced.
     */
    @Test
    fun satellitesOutsideTheSliceKeepMoving() {
        val field = field(100)
        // Two ticks: the field forces both to cover everything, so every slot has a genuine pair
        // of fixes with a rate between them before any rotation starts.
        field.tick(start)
        field.tick(start + 1.seconds)

        // A third tick that touches only the first ten slots.
        field.tick(start + 2.seconds, sliceSize = 10)

        val untouched = 90_050
        val a = field.positionOf(untouched, start + 1500.milliseconds)!!
        val b = field.positionOf(untouched, start + 2.seconds)!!

        assertNotEquals(a[0], b[0], "an un-refreshed satellite froze instead of following its chord")
        // Its own fixes span one second, so reading half a second and one second past the newest
        // is inside the extrapolation bound. Half a second of low orbit is a few kilometres.
        assertTrue(
            distance(a, b) in 1.5..10.0,
            "moved ${distance(a, b)} km in half a second; expected roughly orbital speed",
        )
    }

    /**
     * Prediction is bounded, so a slot the rotation abandons holds rather than flying off.
     *
     * The other half of the previous test: motion past one span is not extrapolated forever. A
     * field whose rotation stalled would otherwise fling every stale dot across the globe.
     */
    @Test
    fun predictionStopsOneSpanPastTheNewestFix() {
        val field = field(20)
        field.tick(start)
        field.tick(start + 1.seconds)
        field.tick(start + 2.seconds, sliceSize = 1)

        val stale = 90_010
        val atBound = field.positionOf(stale, start + 3.seconds)!!
        val wellPast = field.positionOf(stale, start + 60.seconds)!!

        assertEquals(atBound[0], wellPast[0], 1e-9)
        assertEquals(atBound[1], wellPast[1], 1e-9)
    }

    /**
     * The rotation covers the field evenly, and in steady state nothing is stale.
     *
     * Without the wrap the tail would be refreshed less often than the head, and the satellites
     * near one edge of the globe would visibly lag the rest. Two full rotations, so every slot's
     * fixes span a whole rotation and its prediction therefore covers the gap until its next turn
     * — which is the property that makes the rotation invisible rather than merely cheap.
     */
    @Test
    fun everySatelliteKeepsMovingAcrossAFullRotation() {
        val field = field(50)
        field.tick(start)
        field.tick(start + 1.seconds)

        // Five per tick over fifty slots: a rotation is ten ticks. Run two.
        for (step in 2..21) field.tick(start + step.seconds, sliceSize = 5)

        // Sampled inside the last tick, where every slot is within its own prediction bound.
        val a = start + 21.seconds
        val b = start + 21500.milliseconds
        for (i in 0 until field.count) {
            val first = DoubleArray(3)
            val second = DoubleArray(3)
            assertTrue(field.temePositionInto(i, a.toEpochMilliseconds().toDouble(), first))
            assertTrue(field.temePositionInto(i, b.toEpochMilliseconds().toDouble(), second))
            assertTrue(
                distance(first, second) > 0.5,
                "slot $i is frozen; the rotation never reached it",
            )
        }
    }

    private fun distance(a: DoubleArray, b: DoubleArray): Double = kotlin.math.sqrt(
        (b[0] - a[0]) * (b[0] - a[0]) + (b[1] - a[1]) * (b[1] - a[1]) + (b[2] - a[2]) * (b[2] - a[2]),
    )

    /**
     * Time running backwards refills rather than interpolating.
     *
     * A speed change or a resync can move the simulated clock behind the newest fix, and running
     * every satellite backwards along its chord would send the whole globe into reverse for a
     * moment.
     */
    @Test
    fun timeGoingBackwardsRefillsTheField() {
        val field = field(20)
        field.tick(start)
        field.tick(start + 60.seconds)

        field.tick(start, sliceSize = 5)

        val out = DoubleArray(3)
        for (i in 0 until field.count) {
            assertTrue(field.temePositionInto(i, start.toEpochMilliseconds().toDouble(), out))
        }
    }

    @Test
    fun lookupByCatalogNumberFindsTheRightSlot() {
        val field = field(500)

        val slot = field.indexOf(90_137)

        assertEquals(137, slot)
        assertEquals(-1, field.indexOf(12_345), "an id that is not in the field must not resolve")
    }

    private companion object {
        /** Real ISS element text; the orbits above are perturbations of it. */
        val ISS = Tle(
            name = "ISS (ZARYA)",
            noradId = 25544,
            line1 = "1 25544U 98067A   26213.79682580  .00007322  00000-0  13941-3 0  9994",
            line2 = "2 25544  51.6314  74.3802 0007140   2.2740 357.8280 15.49304641578823",
            epoch = Instant.parse("2026-08-01T19:07:25Z"),
        )
    }
}
